"""h46: "VWAP reject" (EMA9 vs session VWAP trend, candle rejects off VWAP, exit on EMA9 close/touch), option BUYING.

    flock <scratch>/obuy.lock python3 -I research/hunt/h46/sim.py [--holdout] UND [UND ...]

Per index: builds an in-RAM price oracle (bfilled 1-minute option OPENs of every nearest-expiry strike, per day), the
signals for every tf x VWAP source x rejection, the exits (CLOSE / TOUCH), the one-at-a-time filter, then prices
ATM and 1-ITM. Writes <scratch>/hunt/h46/trades_<UND>[_ho].parquet. In-sample runs also compute, for every variant with
net > 0, the time-of-day + side matched random baseline (B=200) -> base_<UND>.parquet.
See PREREG.md. In-sample = day < 2025-10-01; --holdout = day >= 2025-10-01 (locked; run once at the end).
"""
from __future__ import annotations

import glob
import os
import sys
import time
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, adverse_bps  # noqa: E402
from obuy.data import market  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h46")
HO = date(2025, 10, 1)
STK0 = date(2024, 10, 7)
TFS = (1, 2, 3, 5, 15)
TOL = 0.0002
SQC = 15 * 60 + 10 - C.OPEN_M          # 355: square-off fill column
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "FINNIFTY": 0.0042, "MIDCPNIFTY": 0.0021, "SENSEX": 0.0020}
COST = Costs("app")
B = 200

CONST = {
    "NIFTY": "ADANIENT ADANIPORTS APOLLOHOSP ASIANPAINT AXISBANK BAJAJ-AUTO BAJFINANCE BAJAJFINSV BEL BHARTIARTL CIPLA "
             "COALINDIA DRREDDY EICHERMOT ETERNAL GRASIM HCLTECH HDFCBANK HDFCLIFE HINDALCO HINDUNILVR ICICIBANK INDIGO "
             "INFY ITC JIOFIN JSWSTEEL KOTAKBANK LT M_M MARUTI MAXHEALTH NESTLEIND NTPC ONGC POWERGRID RELIANCE SBILIFE "
             "SBIN SHRIRAMFIN SUNPHARMA TATACONSUM TMPV TATASTEEL TCS TECHM TITAN TRENT ULTRACEMCO WIPRO",
    "BANKNIFTY": "HDFCBANK ICICIBANK SBIN KOTAKBANK AXISBANK INDUSINDBK BANKBARODA FEDERALBNK AUBANK IDFCFIRSTB PNB CANBK",
    "FINNIFTY": "HDFCBANK ICICIBANK SBIN KOTAKBANK AXISBANK BAJFINANCE BAJAJFINSV SHRIRAMFIN CHOLAFIN HDFCLIFE SBILIFE PFC "
                "RECLTD ICICIGI ICICIPRULI MUTHOOTFIN SBICARD LICHSGFIN HDFCAMC JIOFIN BSE",
    "MIDCPNIFTY": "AUBANK BSE CUMMINSIND DIXON FEDERALBNK HINDPETRO IDFCFIRSTB INDUSTOWER LUPIN PERSISTENT COFORGE POLYCAB "
                  "SUZLON HDFCAMC MPHASIS GODREJPROP PIIND AUROPHARMA VOLTAS CONCOR KPITTECH ASHOKLEY",
    "SENSEX": "RELIANCE HDFCBANK ICICIBANK INFY BHARTIARTL TCS LT ITC SBIN AXISBANK KOTAKBANK HINDUNILVR BAJFINANCE M_M "
              "SUNPHARMA MARUTI HCLTECH NTPC ULTRACEMCO TITAN POWERGRID TATASTEEL ETERNAL ASIANPAINT ADANIPORTS BAJAJFINSV "
              "TECHM BEL TMPV TRENT",
}


# ------------------------------------------------------------------------------------------------ indicators
def candles(o, h, l, c, tf):
    """(D, 375) dense -> per-candle arrays (D, G): o, h, l, c, valid, end col (exclusive)."""
    D, W = c.shape
    G = -(-W // tf)
    pad = G * tf - W
    P = lambda a: np.concatenate([a, np.full((D, pad), np.nan)], axis=1).reshape(D, G, tf)  # noqa: E731
    co, ch, cl, cc = P(o), P(h), P(l), P(c)
    ok = ~np.isnan(cc)
    valid = ok.any(axis=2)
    first = np.argmax(ok, axis=2)
    last = tf - 1 - np.argmax(ok[:, :, ::-1], axis=2)
    gi = np.arange(G)[None, :]
    di = np.arange(D)[:, None]
    with np.errstate(all="ignore"):
        Ho, Hh, Hl = co[di, gi, first], np.nanmax(ch, axis=2), np.nanmin(cl, axis=2)
    Hc = cc[di, gi, last]
    end = np.minimum((np.arange(G) + 1) * tf, W)
    return Ho, Hh, Hl, Hc, valid, end


def ema_cont(c, valid, n=9):
    """EMA(n) on the continuous series of valid candles across days (TradingView: SMA seed)."""
    flat = c[valid]
    out = np.full(len(flat), np.nan)
    a = 2.0 / (n + 1)
    if len(flat) >= n:
        out[n - 1] = flat[:n].mean()
        e = out[n - 1]
        for i in range(n, len(flat)):
            e = a * flat[i] + (1 - a) * e
            out[i] = e
    res = np.full(c.shape, np.nan)
    res[valid] = out
    return res


def vwap(h, l, c, valid, w):
    """Session-anchored VWAP of candle hlc3 with weights w (D, G); NaN where no weight yet."""
    src = (h + l + c) / 3.0
    w = np.where(valid & np.isfinite(w), w, 0.0)
    num = np.cumsum(np.where(w > 0, src * w, 0.0), axis=1)
    den = np.cumsum(w, axis=1)
    with np.errstate(all="ignore"):
        return np.where(den > 0, num / den, np.nan)


def stock_value(und, days):
    """(D, 375) summed traded value (close x volume) of the constituents present in NSE_EQ minute data."""
    di = {d: i for i, d in enumerate(days)}
    V = np.zeros((len(days), C.W))
    n = 0
    for s in CONST[und].split():
        fs = sorted(glob.glob(os.path.join(C.DATA, "candles", "minute", "NSE_EQ", s, "*.parquet")))
        if not fs:
            continue
        x = pd.concat([pd.read_parquet(f, columns=["ts", "close", "volume"]) for f in fs])
        ts = x.ts.dt.tz_localize(None)
        dd = ts.dt.date.map(di)
        col = (ts.dt.hour * 60 + ts.dt.minute - C.OPEN_M).values
        ok = dd.notna().values & (col >= 0) & (col < C.W)
        np.add.at(V, (dd.values[ok].astype(np.int64), col[ok]), (x.close.values * x.volume.values)[ok])
        n += 1
    return V, n


# ------------------------------------------------------------------------------------------------ option oracle
class Oracle:
    """Bfilled option OPENs per (day, strike, right): price at a column = first open at/after it."""

    def __init__(self, mk, und, days, need):
        opts = mk.options(und)
        keys, Os, Ss, LC = [], [], [], []
        self.need = need
        for i, d in enumerate(days):
            if not need[i]:
                continue
            ch = opts.chain(d, "near")
            if ch is None:
                continue
            for r, rv in (("C", 1), ("P", -1)):
                o = ch.o[r]
                cc = ch.c[r]
                ok = ~np.isnan(o)
                nK = o.shape[0]
                # next valid column index (bfill)
                idx = np.where(ok, np.arange(C.W)[None, :], C.W)
                nxt = np.minimum.accumulate(idx[:, ::-1], axis=1)[:, ::-1]
                ob = np.take_along_axis(np.concatenate([o, np.full((nK, 1), np.nan)], axis=1), nxt, axis=1)
                okc = ~np.isnan(cc)
                lastc = np.where(okc.any(axis=1), cc[np.arange(nK), C.W - 1 - np.argmax(okc[:, ::-1], axis=1)], np.nan)
                keys.append((i * 2 + (rv > 0)) * 10_000_000 + ch.K.astype(np.int64))
                Os.append(ob.astype(np.float32))
                Ss.append(nxt.astype(np.int16))
                LC.append(lastc.astype(np.float32))
        self.key = np.concatenate(keys)
        o = np.argsort(self.key, kind="stable")
        self.key = self.key[o]
        self.O = np.concatenate(Os)[o]
        self.S = np.concatenate(Ss)[o]
        self.LC = np.concatenate(LC)[o]
        mk.release(und)

    def map(self, dpos):
        return dpos

    def rows(self, dpos, side, strike):
        k = (dpos.astype(np.int64) * 2 + (side > 0)) * 10_000_000 + strike.astype(np.int64)
        r = np.searchsorted(self.key, k)
        r = np.minimum(r, len(self.key) - 1)
        return np.where(self.key[r] == k, r, -1)

    def price(self, rows, col, entry=False):
        ok = rows >= 0
        rr = np.where(ok, rows, 0)
        p = self.O[rr, col].astype(np.float64)
        if entry:
            gap = self.S[rr, col].astype(np.int64) - col
            p = np.where(gap <= 3, p, np.nan)
        else:
            p = np.where(np.isnan(p), self.LC[rr], p)
        return np.where(ok, p, np.nan)


def strike_of(und, spot, side, money):
    st = C.STEP[und]
    atm = np.round(spot / st) * st
    return atm - side * money * st


def price_trades(orc, und, dpos, spot, side, money, ent, ext, lot, bse):
    """Vectorised: returns e, x, gross, charges, extra spread, net (NaN where no price)."""
    k = strike_of(und, spot, side, money)
    rows = orc.rows(dpos, side, k)
    e = orc.price(rows, ent, entry=True)
    x = orc.price(rows, np.minimum(ext, C.W - 1))
    q = lot.astype(np.float64)
    ok = ~np.isnan(e) & ~np.isnan(x)
    e0, x0 = np.where(ok, e, 1.0), np.where(ok, x, 1.0)
    bf = adverse_bps(e0, 5, True)
    sf = adverse_bps(x0, 5, False)
    g = (sf - bf) * q
    ch = COST.charge(True, bf, q, bse=bse) + COST.charge(False, sf, q, bse=bse)
    sp = max(HS[und] - 0.0005, 0.0) * (e0 + x0) * q
    nan = np.where(ok, 1.0, np.nan)
    return e * nan, x * nan, g * nan, ch * nan, sp * nan, (g - ch - sp) * nan


# ------------------------------------------------------------------------------------------------ signals + exits
def gen(und, days, M, wts, exps):
    """All candidates after the one-at-a-time filter. wts: {src: (D,375) weights or None for TWAP}. Returns DataFrame."""
    o1, h1, l1, c1 = M["o"], M["h"], M["l"], M["c"]
    D = len(days)
    rows = []
    for tf in TFS:
        Co, Ch, Cl, Cc, valid, end = candles(o1, h1, l1, c1, tf)
        G = Co.shape[1]
        ema = ema_cont(Cc, valid)
        # EMA of the last CLOSED candle at each 1-minute column t: candle index t//tf - 1 (previous day's last if <0)
        flat_ema = ema[valid]
        vidx = np.full(valid.shape, -1)
        vidx[valid] = np.arange(valid.sum())
        # last valid candle index at or before each (d, g)
        lastv = np.where(valid, vidx, -1)
        lastv = np.maximum.accumulate(lastv.reshape(-1)).reshape(D, G)
        gprev = np.arange(C.W) // tf - 1                       # (375,)
        ep = np.full((D, C.W), np.nan)
        for d in range(D):
            gp = gprev
            li = np.where(gp >= 0, lastv[d, np.maximum(gp, 0)], lastv[d - 1, -1] if d > 0 else -1)
            ep[d] = np.where(li >= 0, flat_ema[np.maximum(li, 0)], np.nan)
        for src, W1 in wts.items():
            if W1 is None:
                w = valid.astype(float)
            else:
                pad = G * tf - C.W
                w = np.concatenate([W1, np.zeros((D, pad))], axis=1).reshape(D, G, tf).sum(axis=2)
            vw = vwap(Ch, Cl, Cc, valid, w)
            okb = valid & np.isfinite(vw) & np.isfinite(ema)
            bull_t = okb & (ema > vw)
            bear_t = okb & (ema < vw)
            r1b = bull_t & (Cl <= vw * (1 + TOL)) & (Co > vw) & (Cc > vw)
            r1s = bear_t & (Ch >= vw * (1 - TOL)) & (Co < vw) & (Cc < vw)
            sigs = {"R1": (r1b, r1s), "R2": (r1b & (Cc > Co), r1s & (Cc < Co))}
            below = valid & (Cc < ema)
            above = valid & (Cc > ema)
            # next candle index >= g with condition (G = none)
            def nxt(mask):
                idx = np.where(mask, np.arange(G)[None, :], G)
                return np.minimum.accumulate(idx[:, ::-1], axis=1)[:, ::-1]
            nb, na = nxt(below), nxt(above)
            nb = np.concatenate([nb, np.full((D, 1), G)], axis=1)
            na = np.concatenate([na, np.full((D, 1), G)], axis=1)
            endx = np.concatenate([end, [C.W]])
            tb = (l1 < ep)                                     # bull touch at minute t
            ts = (h1 > ep)
            def nxt1(mask):
                idx = np.where(mask, np.arange(C.W)[None, :], C.W)
                return np.minimum.accumulate(idx[:, ::-1], axis=1)[:, ::-1]
            ntb, nts = nxt1(tb), nxt1(ts)
            ntb = np.concatenate([ntb, np.full((D, 1), C.W)], axis=1)
            nts = np.concatenate([nts, np.full((D, 1), C.W)], axis=1)
            for rej, (mb, ms) in sigs.items():
                dd, gg = np.nonzero(mb | ms)
                if not len(dd):
                    continue
                side = np.where(mb[dd, gg], 1, -1)
                sig = end[gg] - 1
                ent = end[gg]
                keep = ent < SQC
                dd, gg, side, sig, ent = dd[keep], gg[keep], side[keep], sig[keep], ent[keep]
                xc = np.where(side > 0, endx[nb[dd, gg + 1]], endx[na[dd, gg + 1]])
                xt = np.where(side > 0, ntb[dd, ent], nts[dd, ent]) + 1
                for xm, xx in (("CLOSE", xc), ("TOUCH", xt)):
                    xx = np.minimum(xx, SQC)
                    # one at a time per index (sequential per day)
                    order = np.lexsort((sig, dd))
                    take = np.zeros(len(dd), bool)
                    ordn = np.zeros(len(dd), np.int16)
                    last_d, last_x, k = -1, -1, 0
                    for j in order:
                        if dd[j] != last_d:
                            last_d, last_x, k = dd[j], -1, 0
                        if sig[j] >= last_x:
                            take[j] = True
                            ordn[j] = k
                            k += 1
                            last_x = xx[j]
                    t = take
                    rows.append(pd.DataFrame(dict(tf=tf, src=src, rej=rej, xm=xm, dpos=dd[t], side=side[t],
                                                  sig=sig[t], ent=ent[t], ext=xx[t], k=ordn[t])))
        print(und, "tf", tf, "done", flush=True)
    df = pd.concat(rows, ignore_index=True)
    df["exp"] = exps[df.dpos.values]
    return df


def run(und, holdout=False):
    t0 = time.time()
    mk = market()
    ix = mk.index(und)
    M = ix.mat()
    days = ix.days
    D = len(days)
    # the in-sample/holdout split applies to TRADES; the EMA uses all prior days (continuous chart)
    exps = np.array([ix.d[d]["exp"] for d in days])
    lots = np.array([ix.lot(d) for d in days])
    sel = np.array([(d >= HO) if holdout else (d < HO) for d in days])
    wts = {"TWAP": None}
    V, n = stock_value(und, days)
    has_stk = (V.sum(axis=1) > 0)
    wts["STK"] = V
    print(und, "constituents found", n, "stock days", has_stk.sum(), flush=True)
    df = gen(und, days, M, wts, exps)
    stkok = has_stk & np.array([d >= STK0 for d in days])
    df = df[sel[df.dpos.values] & ((df.src != "STK") | stkok[df.dpos.values])].reset_index(drop=True)
    print(und, "candidates", len(df), f"{time.time() - t0:.0f}s", flush=True)
    need = np.zeros(D, bool)
    need[np.unique(df.dpos.values)] = True
    if not holdout:
        need |= sel                                       # random-baseline pool days
    orc = Oracle(mk, und, days, need)
    print(und, "oracle", orc.O.shape, f"{orc.O.nbytes / 1e6:.0f} MB", f"{time.time() - t0:.0f}s", flush=True)
    spot = M["c"][df.dpos.values, df.sig.values]
    bse = und in C.BSE
    parts = []
    for money in (0, 1):
        e, x, g, ch, sp, net = price_trades(orc, und, orc.map(df.dpos.values), spot, df.side.values, money,
                                            df.ent.values, df.ext.values, lots[df.dpos.values], bse)
        parts.append(df.assign(money=money, e=e, x=x, gross=g, chg=ch, spr=sp, net=net,
                               lot=lots[df.dpos.values]))
    tr = pd.concat(parts, ignore_index=True)
    tr = tr[np.isfinite(tr.net)].reset_index(drop=True)
    tr["day"] = [days[i] for i in tr.dpos.values]
    tr["und"] = und
    for c in ("tf", "k", "ent", "ext", "sig"):
        tr[c] = tr[c].astype(np.int16)
    tag = "_ho" if holdout else ""
    tr.drop(columns=["dpos"]).to_parquet(os.path.join(OUT, f"trades_{und}{tag}.parquet"), compression="zstd")
    print(und, "trades", len(tr), f"{time.time() - t0:.0f}s", flush=True)
    if not holdout:
        baseline(und, tr, orc, days, exps, lots, sel, spot_mat=M["c"], bse=bse)
    print(und, "all done", f"{time.time() - t0:.0f}s", flush=True)


VARS = [(mx, ex) for mx in (3, 5, 99) for ex in ("skip", "allow")]


def baseline(und, tr, orc, days, exps, lots, sel, spot_mat, bse):
    """Time-of-day + side matched random baseline for each variant with net > 0."""
    rng = np.random.default_rng(46)
    years = np.array([d.year for d in days])
    out = []
    grp = tr.groupby(["tf", "src", "rej", "xm", "money"])
    for key, t in grp:
        for mx, ex in VARS:
            v = t[(t.k < mx) & ((ex == "allow") | ~t.exp)]
            if len(v) < 5 or v.net.sum() <= 0:
                out.append(dict(zip(["tf", "src", "rej", "xm", "money"], key), mx=mx, ex=ex, p=1.0, n=len(v)))
                continue
            vd = v.dpos.values
            N = len(v)
            yr = years[vd]
            # pool per year
            pool = {}
            for y in np.unique(yr):
                m = sel & (years == y)
                if ex == "skip":
                    m &= ~exps
                if v.src.iloc[0] == "STK":
                    m &= np.array([d >= STK0 for d in days])
                pool[y] = np.nonzero(m)[0]
            rd = np.empty((B, N), np.int64)
            for y in np.unique(yr):
                idx = np.nonzero(yr == y)[0]
                pl = pool[y]
                rd[:, idx] = pl[rng.integers(0, len(pl), (B, len(idx)))]
            hold = (v.ext.values - v.ent.values).astype(np.int64)
            ent = np.broadcast_to(v.ent.values.astype(np.int64), (B, N)).ravel()
            ext = np.minimum(ent + np.broadcast_to(hold, (B, N)).ravel(), SQC)
            side = np.broadcast_to(v.side.values, (B, N)).ravel()
            rdf = rd.ravel()
            spot = spot_mat[rdf, v.sig.values.astype(np.int64)[None, :].repeat(B, 0).ravel()]
            net = np.empty(B * N)
            step = max(1, 3_000_000 // N) * N
            for s0 in range(0, B * N, step):
                sl = slice(s0, s0 + step)
                net[sl] = price_trades(orc, und, rdf[sl], spot[sl], side[sl], int(key[4]), ent[sl], ext[sl],
                                       lots[rdf[sl]], bse)[-1]
            net = net.reshape(B, N)
            means = np.nanmean(net, axis=1)
            obs = v.net.mean()
            p = (1 + int((means >= obs).sum())) / (B + 1)
            out.append(dict(zip(["tf", "src", "rej", "xm", "money"], key), mx=mx, ex=ex, p=p, n=N, obs=obs,
                            null_mean=float(np.nanmean(means)), null_hi=float(np.nanquantile(means, 0.975))))
    pd.DataFrame(out).assign(und=und).to_parquet(os.path.join(OUT, f"base_{und}.parquet"))


if __name__ == "__main__":
    a = sys.argv[1:]
    ho = "--holdout" in a
    a = [x for x in a if x != "--holdout"]
    os.makedirs(OUT, exist_ok=True)
    for u in a:
        run(u, ho)
