"""R6 shared: MCX minute panels (from Dhan rollingoption 'spot' = near-month futures, close-only), the option engine
(1-ITM near-month CE/PE, 1 lot, option 1-minute high/low exits, printed minutes only), the mini/micro futures engine,
MCX costs (McxCosts.kt = Zerodha), twins, non-overlap, summaries and the reality check (R4's statistics, unchanged).
Run with python3 -I.
"""
from __future__ import annotations

import math
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "m3"))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h37"))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import m3lib as M  # noqa: E402

SP = M.S
OUT = SP / "hunt" / "r6"
OUT.mkdir(parents=True, exist_ok=True)
TAIL = OUT / "raw" / "opt_tail.parquet"
GTAIL = SP / "hunt" / "r3" / "raw" / "goldm_opt_tail.parquet"

HOLD0 = pd.Timestamp("2025-10-01")
HOLD1 = pd.Timestamp("2026-10-08")
OPEN_M = 9 * 60
W = 15 * 60                 # columns 09:00 .. 23:59
NAN = float("nan")


def col(h, m):
    return h * 60 + m - OPEN_M


SQ = col(23, 15)            # flat by 23:15
W0, W1 = col(9, 5), col(22, 45)   # signal-minute window
EVE = col(17, 0)

# option books: lot multiplier (Rs per point), full bid-ask spread before / after 17:00 IST
OPT_MULT = {"CRUDEOIL": 100, "NATURALGAS": 1250, "NATGASMINI": 250, "GOLDM": 10, "SILVERM": 5}
OPT_SPREAD = {"CRUDEOIL": (0.006, 0.003), "NATURALGAS": (0.006, 0.003), "NATGASMINI": (0.008, 0.004),
              "GOLDM": (0.008, 0.004), "SILVERM": (0.008, 0.004)}
# futures: product -> (signal series, Rs per point of the series price)
FUT = {"CRUDEOILM": ("CRUDEOIL", 10.0), "NATGASMINI": ("NATURALGAS", 250.0), "SILVERMIC": ("SILVERM", 1.0),
       "GOLDPETAL": ("GOLDM", 0.1)}
FUT_COST = 0.0003           # round-trip slippage/spread as a fraction of notional (half each side) + Zerodha charges
# Rs grids per contract (placebo + round-number rules): (RND_B "50s", RND_A "00s", RND_MAJ)
GRID = {"CRUDEOIL": (50, 100, 500), "NATURALGAS": (5, 10, 50), "GOLDM": (100, 500, 1000), "GOLD": (100, 500, 1000),
        "SILVERM": (500, 1000, 5000), "SILVER": (500, 1000, 5000), "NATGASMINI": (5, 10, 50)}
GANN_SCALE = {"CRUDEOIL": 1.0, "NATURALGAS": 0.1, "NATGASMINI": 0.1, "GOLDM": 10.0, "GOLD": 10.0, "SILVERM": 10.0,
              "SILVER": 10.0}
NUM9 = {"CRUDEOIL": 9.0, "NATURALGAS": 0.9, "NATGASMINI": 0.9, "GOLDM": 9.0, "GOLD": 9.0, "SILVERM": 9.0, "SILVER": 9.0}


# ------------------------------------------------------------------------------------------------ data
def _load_opt_raw(sym):
    """M3 files + the R6 / R3 tails (8-9 Oct 2026)."""
    if sym == "CRUDEOIL":
        O, sp = _orig(sym)
    else:
        O = pd.read_parquet(M.RAW / f"opt_{sym}.parquet")
        sp = pd.read_parquet(M.RAW / f"spot_{sym}.parquet")
    tails = []
    if TAIL.exists():
        t = pd.read_parquet(TAIL)
        tails.append(t[t.sym == sym].drop(columns=["sym"]))
    if sym == "GOLDM" and GTAIL.exists():
        tails.append(pd.read_parquet(GTAIL))
    for t in tails:
        if not len(t):
            continue
        t = t[t.ts > O.ts.max()]
        ts = t[(t.k == 0)][["ts", "spot"]].drop_duplicates("ts")
        sp = pd.concat([sp, ts[ts.ts > sp.ts.max()]], ignore_index=True)
        t = t.drop(columns=["spot"])
        for c in t.columns:
            if c not in ("ts", "k", "cp"):
                t[c] = t[c].astype("float32")
        O = pd.concat([O, t[O.columns]], ignore_index=True)
    return O, sp


_orig = M._load_opt_raw
M._load_opt_raw = _load_opt_raw

_MK = {}


def market(sym):
    if sym not in _MK:
        _MK[sym] = M.Market(sym)
    return _MK[sym]


def load_panel(sym):
    """Day x minute panel (09:00..23:59) of the near-month futures close (rollingoption spot). o = h = l = c."""
    m = market(sym)
    day = pd.DatetimeIndex(m.day)
    days = pd.DatetimeIndex(np.unique(m.day))
    days = days[days.weekday < 5]
    dpos = pd.Series(np.arange(len(days)), index=days)
    ok_i = day.isin(days)
    di = dpos.reindex(day[ok_i]).values
    cc = m.tod[ok_i] - OPEN_M
    good = (cc >= 0) & (cc < W)
    nd = len(days)
    C = np.full((nd, W), np.nan)
    IX = np.full((nd, W), -1, np.int64)
    ii = np.nonzero(ok_i)[0][good]
    C[di[good], cc[good]] = m.F[ii]
    IX[di[good], cc[good]] = ii
    nreal = np.isfinite(C).sum(1)
    last = np.array([np.nonzero(np.isfinite(r))[0].max() if np.isfinite(r).any() else -1 for r in C])
    Cf = pd.DataFrame(C).T.ffill().T.to_numpy(copy=True)
    for k in range(nd):
        Cf[k, last[k] + 1:] = np.nan
    # segment (contract) per day, roll days, expiry days
    seg = pd.Series(m.seg[ok_i]).groupby(di).first().reindex(range(nd)).values
    isexp = np.isin(days.values, m.exp_days)
    roll = np.r_[True, seg[1:] != seg[:-1]]
    dclose = np.array([Cf[k, last[k]] if last[k] >= 0 else np.nan for k in range(nd)])
    with np.errstate(all="ignore"):
        dhigh, dlow = np.nanmax(Cf, 1), np.nanmin(Cf, 1)
    dopen = np.array([r[np.isfinite(r)][0] if np.isfinite(r).any() else np.nan for r in C])
    P = dict(u=sym, days=list(days.date), dts=days, nd=nd, o=Cf, h=Cf, l=Cf, c=Cf, raw=C, IX=IX, last=last,
             ok=(nreal >= 300) & (last >= col(22, 0)), exp=isexp, roll=roll, nreal=nreal,
             dopen=dopen, dhigh=dhigh, dlow=dlow, dclose=dclose)
    for k, src in (("pclose", dclose), ("phigh", dhigh), ("plow", dlow)):
        x = np.r_[np.nan, src[:-1]]
        x[roll] = np.nan            # previous day belongs to another contract
        P[k] = x
    P["hold"] = days >= HOLD0
    P["inrange"] = days <= HOLD1
    P["design"] = P["ok"] & ~P["hold"]
    P["holdout"] = P["ok"] & P["hold"] & P["inrange"]
    return P


# ------------------------------------------------------------------------------------------------ requests
class Req:
    def __init__(self, P):
        self.rows = []
        self.P = P

    def add(self, var, di, s, side, x=-1, sp=NAN, tp=NAN, idx_exit=False, lo=None, hi=None, fs=NAN, ft=NAN):
        """x = exit column (index exit), sp/tp = option premium stop/target fractions; fs/ft = futures-only
        underlying stop/target fractions (the PCT exit)."""
        if lo is None:
            lo, hi = max(W0, s - 30), min(W1, s + 30)
        self.rows.append((var, var.split("|")[0], di, int(s), int(side), int(x), sp, tp, fs, ft, int(lo), int(hi),
                          bool(idx_exit)))

    def frame(self):
        return pd.DataFrame(self.rows, columns=["var", "fam", "di", "s", "side", "x", "sp", "tp", "fs", "ft", "lo", "hi",
                                                "idx_exit"])

    def _P_nat(self, var, di, s, side, v):
        if isinstance(v, (int, np.integer)):
            x = int(v)
        else:
            x = index_exit(self.P, di, s, side, v[0], v[1])
        self.add(var, di, s, side, x=x, idx_exit=x >= 0)


def index_exit(P, di, s, side, stop, tgt):
    """first minute j > s where the futures close touches stop (first) or tgt; exit column j+1, else -1."""
    c = P["c"][di, s + 1:SQ]
    with np.errstate(invalid="ignore"):
        if side == 0:
            hs = (c <= stop) if np.isfinite(stop) else np.zeros(len(c), bool)
            ht = (c >= tgt) if np.isfinite(tgt) else np.zeros(len(c), bool)
        else:
            hs = (c >= stop) if np.isfinite(stop) else np.zeros(len(c), bool)
            ht = (c <= tgt) if np.isfinite(tgt) else np.zeros(len(c), bool)
    hit = hs | ht
    if not hit.any():
        return -1
    return s + 1 + int(np.argmax(hit)) + 1


def add_std(R, base, di, s, side, nat=None, extra_nat=None, times=(30,), eod=False, opt=True):
    if nat is not None:
        R._P_nat(base + "|NAT", di, s, side, nat)
    if extra_nat:
        for nm, v in extra_nat.items():
            R._P_nat(base + "|" + nm, di, s, side, v)
    if opt:   # option: premium -25% / +50%; futures: underlying -0.4% / +0.8% (same request row, engine picks)
        R.add(base + "|OPT", di, s, side, sp=0.25, tp=0.50, fs=0.004, ft=0.008)
    for t in times:
        R.add(base + f"|T{t}", di, s, side, x=min(s + 1 + t, SQ), idx_exit=True)
    if eod:
        R.add(base + "|EOD", di, s, side)


# ------------------------------------------------------------------------------------------------ costs
def opt_charges(buy_rs, sell_rs):
    return M.opt_charges(buy_rs, sell_rs)


def fut_charges(buy_rs, sell_rs):
    return M.fut_charges(buy_rs, sell_rs)


# ------------------------------------------------------------------------------------------------ option engine
def run_opt(P, req):
    """Buy 1 lot of the 1-ITM near-month option (CE for side 0, PE for side 1) at the open of the first printed minute
    in s+1..s+3; stop / target on the option's printed 1-minute low / high (stop first); index exits at the open of the
    exit column's first printed minute within 10 minutes; else out at 23:15. Expiry days are skipped."""
    sym = P["u"]
    m = market(sym)
    q = OPT_MULT[sym]
    am, pm = OPT_SPREAD[sym]
    n = len(req)
    E = np.full(n, np.nan); X = np.full(n, np.nan); ST = np.zeros(n, bool)
    ENT = np.full(n, -1); EX = np.full(n, -1); HE = np.zeros(n); HX = np.zeros(n)
    req = req.reset_index(drop=True)
    IX = P["IX"]
    for di, g in req.groupby("di", sort=True):
        if P["exp"][di]:
            continue
        ixrow = IX[di]
        cache = {}
        for r in g.itertuples():
            s = int(r.s)
            if s < 0 or s >= SQ - 1:
                continue
            i_s = ixrow[s]
            if i_s < 0:
                prev = ixrow[:s + 1][ixrow[:s + 1] >= 0]
                if not len(prev):
                    continue
                i_s = prev[-1]
            side = int(r.side)
            strike = m.itm[side][i_s]
            if not np.isfinite(strike):
                continue
            key = (side, round(float(strike), 4))
            if key not in cache:
                a = ixrow[ixrow >= 0]
                a0, a1 = int(a[0]), int(a[-1])
                o, h, lo, c, v = m.leg(side, strike, a0, a1)
                # map to columns
                Oc = np.full((5, W), np.nan)
                cols = np.nonzero(ixrow >= 0)[0]
                Oc[:, cols] = np.vstack([o, h, lo, c, v])[:, ixrow[cols] - a0]
                pr = np.isfinite(Oc[3]) & (np.nan_to_num(Oc[4]) > 0)
                cf = pd.Series(np.where(pr, Oc[3], np.nan)).ffill().values
                cache[key] = (Oc[0], Oc[1], Oc[2], cf, pr)
            O, H, L, Cf, pr = cache[key]
            e = -1
            for j in range(s + 1, min(s + 4, SQ)):
                if pr[j] and O[j] > 0:
                    e = j
                    break
            if e < 0:
                continue
            en = float(O[e])
            xe = int(r.x) if r.x >= 0 else SQ
            xe = min(max(xe, e + 1), SQ)
            stop_lvl = en * (1 - r.sp) if np.isfinite(r.sp) else -1.0
            tgt_lvl = en * (1 + r.tp) if np.isfinite(r.tp) else 1e12
            out_px, out_c, stopped = None, xe, False
            seg = np.arange(e, xe)
            ss = seg[pr[seg]]
            if len(ss):
                hit_s = ss[L[ss] <= stop_lvl]
                hit_t = ss[H[ss] >= tgt_lvl]
                js = hit_s[0] if len(hit_s) else 10 ** 6
                jt = hit_t[0] if len(hit_t) else 10 ** 6
                if js < 10 ** 6 and js <= jt:
                    out_px = min(stop_lvl, O[js]) if js > e else stop_lvl
                    out_c, stopped = js, True
                elif jt < 10 ** 6:
                    out_px = max(tgt_lvl, O[jt]) if jt > e else tgt_lvl
                    out_c = jt
            if out_px is None:
                nx = [k for k in range(xe, min(xe + 10, W)) if pr[k] and np.isfinite(O[k])]
                if nx:
                    out_px, out_c = float(O[nx[0]]), nx[0]
                else:
                    vv = Cf[xe - 1]
                    out_px = float(vv) if np.isfinite(vv) else en
            E[r.Index], X[r.Index], ST[r.Index], ENT[r.Index], EX[r.Index] = en, out_px, stopped, e, out_c
            HE[r.Index] = am / 2 if e < EVE else pm / 2
            HX[r.Index] = am / 2 if out_c < EVE else pm / 2
    hb = np.maximum(HE, 0.0005)
    hs = np.maximum(HX, 0.0005) + np.where(ST, 0.0005, 0.0)
    buy = E * (1 + hb)
    sell = X * (1 - hs)
    ok = np.isfinite(E)
    chg = np.where(ok, opt_charges(np.nan_to_num(buy) * q, np.nan_to_num(sell) * q), 0)
    net = (sell - buy) * q - chg
    gross = (X - E) * q
    return pd.DataFrame(dict(E=E, X=X, stop=ST, ent=ENT, ex=EX, gross=gross, net=net))


# ------------------------------------------------------------------------------------------------ futures engine
def run_fut(P, req, mult):
    """Futures (both directions): fill at the close of minute s+1; PCT exit = underlying -0.4% / +0.8% on minute
    closes; index exits at the close of the exit column's minute - 1 (= the touch minute, i.e. the next open);
    time exits at the close of column x - 1; else 23:15. Cost = 0.03% of notional round trip + Zerodha charges."""
    n = len(req)
    E = np.full(n, np.nan); X = np.full(n, np.nan); ENT = np.full(n, -1); EX = np.full(n, -1)
    req = req.reset_index(drop=True)
    C = P["c"]
    s_ = req.s.values; d_ = req.di.values; sd = np.where(req.side.values == 0, 1.0, -1.0)
    x_ = req.x.values; fs = req.fs.values; ft = req.ft.values; sp = req.sp.values
    for k in range(n):
        di, s = d_[k], s_[k]
        e = s + 1
        if e >= SQ:
            continue
        en = C[di, e]
        if not np.isfinite(en):
            continue
        if np.isfinite(sp[k]):          # OPT row -> PCT exit for futures
            r = (C[di, e + 1:SQ + 1] / en - 1) * sd[k]
            with np.errstate(invalid="ignore"):
                hit = np.nonzero((r <= -fs[k]) | (r >= ft[k]))[0]
            xc = e + 1 + int(hit[0]) if len(hit) else SQ
        else:
            xc = (int(x_[k]) - 1) if x_[k] >= 0 else SQ
            xc = min(max(xc, e + 1), SQ)
        xp = C[di, xc]
        if not np.isfinite(xp):
            vv = C[di, :xc + 1]
            vv = vv[np.isfinite(vv)]
            xp = vv[-1]
        E[k], X[k], ENT[k], EX[k] = en, xp, e, xc
    ok = np.isfinite(E)
    buy_rs = np.nan_to_num(np.where(sd > 0, E, X)) * mult
    sell_rs = np.nan_to_num(np.where(sd > 0, X, E)) * mult
    gross = (X - E) * sd * mult
    slip = FUT_COST * np.nan_to_num(E) * mult
    chg = np.where(ok, fut_charges(buy_rs, sell_rs), 0)
    net = gross - slip - chg
    return pd.DataFrame(dict(E=E, X=X, stop=np.zeros(n, bool), ent=ENT, ex=EX, gross=gross, net=net))


def run(P, req, inst):
    key = ["di", "s", "side", "x", "sp", "tp", "fs", "ft"]
    u = req[key].drop_duplicates().reset_index(drop=True)
    res = run_opt(P, u) if inst == "OPT" else run_fut(P, u, FUT[inst][1])
    u = pd.concat([u, res], axis=1)
    return req.merge(u, on=key, how="left")


# ------------------------------------------------------------------------------------------------ statistics (R4/R1)
def nonoverlap(tr, maxday=3):
    tr = tr[np.isfinite(tr.net)].sort_values(["var", "di", "s"])
    keep = np.zeros(len(tr), bool)
    v, d, s, ex = tr["var"].values, tr.di.values, tr.s.values, tr.ex.values
    last = None
    busy, cnt = -1, 0
    for i in range(len(tr)):
        k = (v[i], d[i])
        if k != last:
            last, busy, cnt = k, -1, 0
        if s[i] >= busy and cnt < maxday:
            keep[i] = True
            busy = ex[i]
            cnt += 1
    return tr[keep].reset_index(drop=True)


NRAND = 10


def twins(tr, seed):
    rng = np.random.default_rng(seed)
    r = tr.loc[tr.index.repeat(NRAND)].copy()
    r["rid"] = np.repeat(tr.index.values, NRAND)
    span = (r.hi - r.lo + 1).values
    sr = r.lo.values + (rng.random(len(r)) * span).astype(int)
    r["x"] = np.where(r.x.values >= 0, np.minimum(sr + (r.x.values - r.s.values), SQ), -1)
    r["s"] = sr
    r["side"] = rng.integers(0, 2, len(r))
    return r[["var", "fam", "di", "s", "side", "x", "sp", "tp", "fs", "ft", "rid"]].reset_index(drop=True)


def cl_t(y, g):
    y = np.asarray(y, float)
    n = len(y)
    if n < 3:
        return (float(y.mean()) if n else np.nan), np.nan
    _, gi = np.unique(g, return_inverse=True)
    G = gi.max() + 1
    mm = y.mean()
    r = np.bincount(gi, weights=y - mm, minlength=G)
    se = math.sqrt(G / max(G - 1, 1) * (r ** 2).sum()) / n
    return float(mm), (mm / se if se > 0 else np.nan)


def p1(z):
    return 0.5 * math.erfc(z / math.sqrt(2)) if np.isfinite(z) else np.nan


def bh(p):
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    pv = p[ok]
    nn = len(pv)
    if nn == 0:
        return q
    o = np.argsort(pv)
    r = pv[o] * nn / np.arange(1, nn + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    qq = np.empty(nn)
    qq[o] = np.minimum(r, 1)
    q[ok] = qq
    return q


def maxdd(daily):
    eq = np.cumsum(daily)
    return float((np.maximum.accumulate(np.r_[0, eq])[1:] - eq).max()) if len(eq) else 0.0


def stationary_boot_idx(n, B, mean_block=5, seed=7):
    rng = np.random.default_rng(seed)
    p = 1.0 / mean_block
    out = np.empty((B, n), np.int64)
    for b in range(B):
        idx = np.empty(n, np.int64)
        idx[0] = rng.integers(n)
        newb = rng.random(n) < p
        starts = rng.integers(n, size=n)
        for i in range(1, n):
            idx[i] = starts[i] if newb[i] else (idx[i - 1] + 1) % n
        out[b] = idx
    return out


def summarize(real, rnd, P, mask, minn=30):
    rows, daily = [], {}
    nses = int(mask.sum())
    rg = rnd[np.isfinite(rnd.net)].groupby("var").net
    rstats = pd.DataFrame(dict(rm=rg.mean(), rv=rg.var(ddof=1), rn=rg.size()))
    mon = np.array([d.strftime("%Y-%m") for d in P["days"]])
    for var, g in real.groupby("var"):
        dd = np.zeros(P["nd"])
        np.add.at(dd, g.di.values, g.net.values)
        dser = dd[mask]
        n = len(g)
        mm, t = cl_t(g.net.values, g.di.values)
        if var in rstats.index and n > 2:
            rm, rv, rn = rstats.loc[var]
            se = math.sqrt(g.net.var(ddof=1) / n + rv / rn)
            zr = (g.net.mean() - rm) / se if se > 0 else np.nan
        else:
            rm, zr = np.nan, np.nan
        ms = pd.Series(dser).groupby(mon[mask]).sum()
        rows.append(dict(var=var, fam=var.split("|")[0], trades=n, rs_day=dser.sum() / max(nses, 1), rs_trade=mm,
                         t=t, p=p1(t) if n >= minn else 1.0, win=float((g.net > 0).mean()),
                         gross_trade=float(g.gross.mean()), rand_trade=rm, z_rand=zr,
                         p_rand=p1(zr) if n >= minn else 1.0, maxdd=maxdd(dser), total=dser.sum(),
                         green_months=f"{int((ms > 0).sum())}/{len(ms)}"))
        daily[var] = dser
    return pd.DataFrame(rows), daily


def reality_check(daily, B=1000, seed=3):
    vv = list(daily)
    Mx = np.array([daily[k] for k in vv], float).T
    n = Mx.shape[0]
    mu = Mx.mean(0)
    sd = Mx.std(0, ddof=1) + 1e-9
    tst = mu / (sd / np.sqrt(n))
    idx = stationary_boot_idx(n, B, 5, seed=seed)
    mx = np.empty(B)
    for b in range(B):
        mb = Mx[idx[b]].mean(0) - mu
        mx[b] = np.max(mb / (sd / np.sqrt(n)))
    rc = np.array([(mx >= t).mean() for t in tst])
    return dict(zip(vv, rc)), dict(zip(vv, tst))
