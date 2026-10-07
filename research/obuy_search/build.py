"""OBUY search - build step: per underlying, per trading day, per 5-minute decision slot (09:20 .. 14:30, 63 slots):
  * the condition atoms (booleans, for a CALL decision and for a PUT decision), from data known at the slot;
  * the net rupee P&L of 1 lot for every exit in the fixed menu, for ATM and 1-ITM, call and put, nearest expiry.

    python3 -I research/obuy_search/build.py <scratchpad> <UND>

Inputs (untrusted data, hence python -I): <scratchpad>/maxloss/prep/<UND>.gz (+ _expiry.csv) - the Dhan expired-option
minutes (index 1-min OHLC + ATM+-10 option minutes with volume and OI of the nearest listed expiry), and the Dhan daily
India VIX candles. Output: <scratchpad>/obs/is_<UND>.npz and ho_<UND>.npz (holdout = days >= 2025-10-01, written to a
separate file that only holdout.py reads).

DECISION / FILL: slot minute t; the signal uses index bars with start minute <= t-1 (closed by t) and option OI up to
minute t-1. The option is bought at the OPEN of minute t (the next minute after the signal bar) + slippage.
SLIPPAGE: market buy = open*1.003 + 0.05; market sell = price*0.997 - 0.05; stop sell = min(stop, open)*0.994 - 0.05
(double slippage, gap-through filled at the open); target = resting limit filled at the target price.
CHARGES (per leg, research/jarvis_exits.charge as a float): Rs 20 brokerage, STT 0.15% on the sell, NSE txn 0.03553%,
SEBI Rs 10/crore, stamp 0.003% on the buy, 18% GST on brokerage+txn+SEBI.
Within a minute: a time exit (at that minute's open) first, then the stop / ladder lock, then the target.
Skipped: premium < 10 at entry, no trades in the strike over the 6 minutes up to entry, strike missing.
"""
from __future__ import annotations

import gzip
import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SP, UND = sys.argv[1], sys.argv[2]
PREP = os.path.join(SP, "maxloss/prep")
VIXF = os.path.join(SP, "dhan/repo/dhan-data/candles/daily/IDX_I/INDIA_VIX.parquet")
OUTD = os.path.join(SP, "obs")
HOLDOUT = date(2025, 10, 1)
STEP = {"NIFTY": 50, "BANKNIFTY": 100, "FINNIFTY": 50}[UND]
M0, NM = 555, 375                       # minutes 09:15 .. 15:29
SLOTS = np.arange(560, 871, 5)          # 63 decision minutes 09:20 .. 14:30
NS = len(SLOTS)
EOD = 910                               # 15:10
MIN_PREM = 10.0

# ---------------------------------------------------------------- exit menu (fixed before any result)
STOPS = [0.15, 0.25, 0.35]
TGTS = [0.30, 0.50, 1.00, None]
TIMES = [30, 60, 120, None]             # None = 15:10
EXITS = [("pct", s, g, T) for s in STOPS for g in TGTS for T in TIMES] + [("pts", 30, 60, T) for T in TIMES]
NE = len(EXITS)                         # 52
LADDER = [(15.0, 0.0), (30.0, 15.0), (45.0, 30.0)]   # 30/60 pts: at +15 lock BE(+charges), +30 lock 15, +45 lock 30


def exit_name(k):
    kind, s, g, T = EXITS[k]
    t = "1510" if T is None else f"{T}m"
    if kind == "pts":
        return f"30/60pts+ladder,{t}"
    return f"SL{int(s*100)}%,TG{'none' if g is None else str(int(g*100)) + '%'},{t}"


def charges(e, x, q):
    b, s = e * q, x * q
    txn_b, txn_s = b * 0.0003553, s * 0.0003553
    seb_b, seb_s = b * 1e-6, s * 1e-6
    buy = 20 + txn_b + seb_b + b * 0.00003 + (20 + txn_b + seb_b) * 0.18
    sell = 20 + s * 0.0015 + txn_s + seb_s + (20 + txn_s + seb_s) * 0.18
    return buy + sell


def first_true(a):
    """index of first True per row (2-D) or len if none."""
    n = a.shape[-1]
    i = np.argmax(a, axis=-1)
    return np.where(a.take(i, axis=-1) if a.ndim == 1 else np.take_along_axis(a, i[..., None], -1)[..., 0], i, n)


def simulate(o, h, l, c, i0, lot):
    """One entry at the open of minute index i0. Returns (NE,) net Rs P&L of 1 lot."""
    e = o[i0] * 1.003 + 0.05
    O, H, L = o[i0:], h[i0:], l[i0:]
    n = len(O)
    out = np.empty(NE)
    t0 = M0 + i0
    # time-exit indices (relative)
    tix = {}
    for T in TIMES:
        tm = EOD if T is None else min(t0 + T, EOD)
        tix[T] = max(1, tm - t0)  # at least one minute later
    eod_rel = EOD - t0
    # first stop / target hits
    shit = {s: first_true(L <= e * (1 - s)) for s in STOPS}
    ghit = {g: first_true(H >= e * (1 + g)) for g in TGTS if g is not None}
    k = 0
    for s in STOPS:
        st = e * (1 - s)
        for g in TGTS:
            for T in TIMES:
                jt = tix[T]
                js = shit[s]
                jg = ghit[g] if g is not None else n
                if jt <= js and jt <= jg:
                    j = min(jt, n - 1)
                    x = O[j] * 0.997 - 0.05
                elif js <= jg:
                    x = min(st, O[js]) * 0.994 - 0.05
                else:
                    x = e * (1 + g)
                out[k] = (x - e) * lot - charges(e, max(x, 0.0), lot)
                k += 1
    # 30/60 points + ladder
    cpu = charges(e, e, lot) / lot
    st, tg = e - 30, e + 60
    peak_prev = np.maximum.accumulate(np.concatenate([[e], H[:-1]]))   # peak through the previous minute
    gain = peak_prev - e
    lock = np.full(n, -np.inf)
    for a, b in LADDER:
        lv = np.maximum(e + b, e + cpu) if b == 0 else e + b
        lock = np.where(gain >= a, np.maximum(lock, lv), lock)
    lock = np.where(lock < peak_prev, lock, -np.inf)
    eff = np.maximum(lock, st if st > 0.05 else -np.inf)
    jsl = first_true(L <= eff)
    jg = first_true(H >= tg)
    for T in TIMES:
        jt = tix[T]
        if jt <= jsl and jt <= jg:
            j = min(jt, n - 1)
            x = O[j] * 0.997 - 0.05
        elif jsl <= jg:
            if lock[jsl] > st:
                x = min(lock[jsl], O[jsl]) * 0.997 - 0.05
            else:
                x = min(st, O[jsl]) * 0.994 - 0.05
        else:
            x = tg
        out[k] = (x - e) * lot - charges(e, max(x, 0.0), lot)
        k += 1
    return out, e


# ---------------------------------------------------------------- helpers for features
def ema(x, n):
    a = 2.0 / (n + 1)
    out = np.empty_like(x)
    v = x[0]
    for i in range(len(x)):
        v = v + a * (x[i] - v)
        out[i] = v
    return out


def rsi(c, n=14):
    d = np.diff(c, prepend=c[0])
    up, dn = np.clip(d, 0, None), np.clip(-d, 0, None)
    au, ad = np.empty_like(c), np.empty_like(c)
    u = dd = 0.0
    for i in range(len(c)):
        u = u + (up[i] - u) / n
        dd = dd + (dn[i] - dd) / n
        au[i], ad[i] = u, dd
    return 100 - 100 / (1 + au / np.maximum(ad, 1e-9))


def dense(rows, ncol):
    """rows: list of (minute, v1..vk) -> (NM, k) forward-filled array (NaN before first)."""
    a = np.full((NM, ncol), np.nan)
    for r in rows:
        i = r[0] - M0
        if 0 <= i < NM:
            a[i] = r[1:]
    return a


def ffill_ohlc(a):
    """a: (NM, 4+) o,h,l,c,... ; missing minutes -> o=h=l=c=previous close, vol 0, oi carried."""
    miss = np.isnan(a[:, 3])
    if miss.all():
        return a, miss
    idx = np.where(~miss, np.arange(NM), 0)
    np.maximum.accumulate(idx, out=idx)
    first = np.argmax(~miss)
    c = a[idx, 3]
    out = a.copy()
    for j in range(4):
        out[miss, j] = c[miss]
    if a.shape[1] > 4:
        out[miss, 4] = 0.0
    if a.shape[1] > 5:
        out[:, 5] = a[idx, 5]
    out[:first] = np.nan
    return out, miss


# ---------------------------------------------------------------- read
def expiry_flags():
    f = {}
    with open(os.path.join(PREP, f"{UND}_expiry.csv")) as fh:
        next(fh)
        for ln in fh:
            p = ln.strip().split(",")
            f[date.fromisoformat(p[0])] = p[1] == "1" or p[2] == "1"
    return f


def days():
    head, ix, opt = None, [], []
    with gzip.open(os.path.join(PREP, f"{UND}.gz"), "rt") as f:
        for ln in f:
            t = ln[0]
            if t == "O":
                opt.append(ln)
            elif t == "X":
                p = ln.split(",")
                ix.append((int(p[1]), float(p[2]), float(p[3]), float(p[4]), float(p[5])))
            elif t == "D":
                if head is not None:
                    yield head, ix, opt
                head = ln.strip().split(",")
                ix, opt = [], []
    if head is not None:
        yield head, ix, opt


def lot_fallback(d):
    if UND == "BANKNIFTY":
        if d >= date(2025, 1, 31):
            return 30
        if d >= date(2024, 1, 1):
            return 15
        return 25 if d < date(2023, 7, 1) else 15
    if UND == "NIFTY":
        return 75 if d >= date(2024, 11, 20) else (25 if d >= date(2024, 4, 26) else 50)
    return 65 if d >= date(2024, 11, 20) else 40


def main():
    flags = expiry_flags()
    vx = pd.read_parquet(VIXF)
    vx["d"] = vx.ts.dt.tz_localize(None).dt.date
    vx = vx[vx.close > 0].set_index("d")
    vdays = list(vx.index)
    vmin = {}                                   # minute India VIX (Oct 2021 on): day -> dense close
    vdir = os.path.join(SP, "dhan/repo/dhan-data/candles/minute/IDX_I/INDIA_VIX")
    for fn in sorted(os.listdir(vdir)):
        m = pd.read_parquet(os.path.join(vdir, fn))
        m["ts"] = m.ts.dt.tz_localize(None)
        m["d"] = m.ts.dt.date
        m["mi"] = m.ts.dt.hour * 60 + m.ts.dt.minute
        m = m[(m.mi >= M0) & (m.mi < M0 + NM)]
        for d, g in m.groupby("d"):
            a = np.full(NM, np.nan)
            a[g.mi.values - M0] = g.close.values
            vmin[d] = pd.Series(a).ffill().values

    recs = []          # per day dict
    last_lot = 0
    for head, ixr, optr in days():
        d = date.fromisoformat(head[1])
        lot = int(head[4]) if int(head[4]) > 0 else (last_lot or lot_fallback(d))
        if int(head[4]) > 0:
            last_lot = int(head[4])
        exp = flags.get(d, head[3] == "1")
        X, _ = ffill_ohlc(dense(ixr, 4))
        if np.isnan(X[:, 3]).all() or np.isnan(X[:5, 3]).all():
            continue                       # partial days (index starting after 09:19) are dropped
        if np.isnan(X[0, 3]):
            X[:np.argmax(~np.isnan(X[:, 3]))] = X[np.argmax(~np.isnan(X[:, 3]))]
        # options
        by = {}
        for ln in optr:
            p = ln.split(",")
            by.setdefault((int(float(p[1])), p[2]), []).append(
                (int(p[3]), float(p[4]), float(p[5]), float(p[6]), float(p[7]), float(p[8]), float(p[9])))
        opts = {k: ffill_ohlc(dense(v, 6))[0] for k, v in by.items()}
        recs.append(dict(d=d, lot=lot, exp=exp, X=X, opts=opts))
        if len(recs) % 200 == 0:
            print(UND, "read", d, flush=True)
    process(recs, vx, vdays, vmin)


def process(recs, vx, vdays, vmin):
    nd = len(recs)
    ds = [r["d"] for r in recs]
    # ---------------- continuous series for EMA / RSI
    allc = np.concatenate([r["X"][:, 3] for r in recs])
    e9, e21, e50 = ema(allc, 9), ema(allc, 21), ema(allc, 50)
    # 5-min and 15-min bars (close at end of bar), continuous
    def bars(tf):
        cl, hi, lo, op = [], [], [], []
        for r in recs:
            X = r["X"]
            for b in range(0, NM, tf):
                seg = X[b:b + tf]
                op.append(seg[0, 0]); hi.append(seg[:, 1].max()); lo.append(seg[:, 2].min()); cl.append(seg[-1, 3])
        return np.array(op), np.array(hi), np.array(lo), np.array(cl)
    o5, h5, l5, c5 = bars(5)
    nb5 = NM // 5  # 75
    e5a, e5b, e5c = ema(c5, 9), ema(c5, 21), ema(c5, 50)
    r5 = rsi(c5)
    rng5 = h5 - l5
    avg5 = pd.Series(rng5).shift(1).rolling(20, min_periods=10).mean().values
    o15, h15, l15, c15 = bars(15)
    nb15 = NM // 15  # 25
    f9, f21, f50 = ema(c15, 9), ema(c15, 21), ema(c15, 50)

    # ---------------- daily
    dO = np.array([r["X"][0, 0] for r in recs]); dH = np.array([np.nanmax(r["X"][:, 1]) for r in recs])
    dL = np.array([np.nanmin(r["X"][:, 2]) for r in recs]); dC = np.array([r["X"][-1, 3] for r in recs])
    fh = np.array([np.nanmax(r["X"][:60, 1]) - np.nanmin(r["X"][:60, 2]) for r in recs])
    tr = np.maximum(dH - dL, np.maximum(np.abs(dH - np.roll(dC, 1)), np.abs(dL - np.roll(dC, 1))))
    tr[0] = dH[0] - dL[0]
    atr = pd.Series(tr).rolling(14, min_periods=5).mean().shift(1).values        # known before today
    rngd = dH - dL
    fh_avg = pd.Series(fh).rolling(20, min_periods=10).mean().shift(1).values
    piv = (dH + dL + dC) / 3
    bc = (dH + dL) / 2
    tc = 2 * piv - bc
    cprw = np.abs(tc - bc) / piv
    cpr_lo = pd.Series(cprw).rolling(60, min_periods=30).quantile(0.2).shift(1).values   # today's CPR = prev day's
    cpr_hi = pd.Series(cprw).rolling(60, min_periods=30).quantile(0.8).shift(1).values
    exp = np.array([r["exp"] for r in recs])

    ATOMS = ["orb5_with", "orb15_with", "orb30_with", "orb15_against", "orb15_inside",
             "gap_with_big", "gap_with_small", "gap_flat", "gap_against_small", "gap_against_big",
             "vwap_with", "vwap_far_with", "vwap_far_against",
             "ema1_with", "ema5_with", "ema15_with", "ema5_against",
             "rsi_mom_with", "rsi_ext_with", "rsi_ext_against", "rsi_mid",
             "nr4", "nr7", "prev_wide", "fh_wide", "fh_narrow", "day_range_big",
             "pdhl_with", "pdc_with", "pdhl_against", "inside_prev",
             "cpr_narrow", "cpr_wide", "cpr_with",
             "vix_low", "vix_mid", "vix_high", "vix_up", "vix_down",
             "pcr_with", "pcr_against", "oi_with",
             "strad_up", "strad_down",
             "expiry_day", "pre_expiry", "far_expiry",
             "prev_with", "prev3_with", "prev_big_against",
             "bigbar_with", "bigbar_against",
             "move_with", "move_against"]
    NA = len(ATOMS)
    A = {a: i for i, a in enumerate(ATOMS)}
    feat = np.zeros((nd, NS, 2, NA), dtype=bool)            # [day, slot, dir(0=call,1=put), atom]
    pnl = np.full((nd, NS, 2, 2, NE), np.nan, dtype=np.float32)   # [day, slot, dir, strike(0=ATM,1=ITM1), exit]
    prem = np.full((nd, NS, 2, 2), np.nan, dtype=np.float32)
    lots = np.array([r["lot"] for r in recs])

    for di, r in enumerate(recs):
        X = r["X"]
        d = r["d"]
        if di < 1:
            continue
        pH, pL, pC, pO = dH[di - 1], dL[di - 1], dC[di - 1], dO[di - 1]
        gap = (dO[di] - pC) / pC * 100
        # vix
        vprev = vopen = np.nan
        k = np.searchsorted(vdays, d)
        if k < len(vdays) and vdays[k] == d and k > 0:
            vprev, vopen = vx.close.iloc[k - 1], vx.open.iloc[k]
        elif k > 0:
            vprev = vx.close.iloc[k - 1]
        vm = vmin.get(d)                      # (the daily open is just the previous close: use the minutes)
        nxt_exp = di + 1 < nd and exp[di + 1]
        tp = (X[:, 1] + X[:, 2] + X[:, 3]) / 3
        twap = np.cumsum(tp) / np.arange(1, NM + 1)
        opts = r["opts"]
        # straddle at the open ATM strike
        k0 = int(np.floor(X[0, 0] / STEP + 0.5)) * STEP
        sc, sp = opts.get((k0, "C")), opts.get((k0, "P"))
        strad = sc[:, 3] + sp[:, 3] if sc is not None and sp is not None else None
        for si, t in enumerate(SLOTS):
            j = t - 1 - M0                                     # last closed 1-min bar
            cl = X[j, 3]
            hi_sofar, lo_sofar = X[:j + 1, 1].max(), X[:j + 1, 2].min()
            g = di * NM + j                                     # continuous index
            b5 = di * nb5 + (t - M0) // 5 - 1                   # last closed 5-min bar
            b15 = di * nb15 + (t - M0) // 15 - 1
            for dr in (0, 1):
                s = 1 if dr == 0 else -1
                f = feat[di, si, dr]

                def beyond(hv, lv):   # close beyond the level in dir
                    return cl > hv if s > 0 else cl < lv

                def beyond_against(hv, lv):
                    return cl < lv if s > 0 else cl > hv
                or5h, or5l = X[:5, 1].max(), X[:5, 2].min()
                f[A["orb5_with"]] = beyond(or5h, or5l)
                if t >= 570:
                    or15h, or15l = X[:15, 1].max(), X[:15, 2].min()
                    f[A["orb15_with"]] = beyond(or15h, or15l)
                    f[A["orb15_against"]] = beyond_against(or15h, or15l)
                    f[A["orb15_inside"]] = or15l <= cl <= or15h
                if t >= 585:
                    f[A["orb30_with"]] = beyond(X[:30, 1].max(), X[:30, 2].min())
                sg = gap * s
                f[A["gap_with_big"]] = sg >= 0.5
                f[A["gap_with_small"]] = 0.15 <= sg < 0.5
                f[A["gap_flat"]] = abs(gap) < 0.15
                f[A["gap_against_small"]] = -0.5 < sg <= -0.15
                f[A["gap_against_big"]] = sg <= -0.5
                vd = (cl - twap[j]) / twap[j] * 100 * s
                f[A["vwap_with"]] = vd > 0
                f[A["vwap_far_with"]] = vd > 0.3
                f[A["vwap_far_against"]] = vd < -0.3
                f[A["ema1_with"]] = (e9[g] > e21[g] > e50[g]) if s > 0 else (e9[g] < e21[g] < e50[g])
                if b5 >= 50:
                    f[A["ema5_with"]] = (e5a[b5] > e5b[b5] > e5c[b5]) if s > 0 else (e5a[b5] < e5b[b5] < e5c[b5])
                    f[A["ema5_against"]] = (e5a[b5] < e5b[b5] < e5c[b5]) if s > 0 else (e5a[b5] > e5b[b5] > e5c[b5])
                    rv = r5[b5]
                    f[A["rsi_mom_with"]] = rv > 60 if s > 0 else rv < 40
                    f[A["rsi_ext_with"]] = rv > 70 if s > 0 else rv < 30
                    f[A["rsi_ext_against"]] = rv < 30 if s > 0 else rv > 70
                    f[A["rsi_mid"]] = 40 <= rv <= 60
                    if avg5[b5] == avg5[b5] and rng5[b5] > 2 * avg5[b5]:
                        body = (c5[b5] - o5[b5]) * s
                        f[A["bigbar_with"]] = body > 0
                        f[A["bigbar_against"]] = body < 0
                if b15 >= 50:
                    f[A["ema15_with"]] = (f9[b15] > f21[b15] > f50[b15]) if s > 0 else (f9[b15] < f21[b15] < f50[b15])
                if di >= 7:
                    f[A["nr4"]] = rngd[di - 1] <= rngd[di - 4:di].min()
                    f[A["nr7"]] = rngd[di - 1] <= rngd[di - 7:di].min()
                if atr[di] == atr[di]:
                    f[A["prev_wide"]] = rngd[di - 1] > 1.5 * atr[di - 1] if atr[di - 1] == atr[di - 1] else False
                    f[A["day_range_big"]] = (hi_sofar - lo_sofar) > 0.8 * atr[di]
                if t >= 615 and fh_avg[di] == fh_avg[di]:
                    f[A["fh_wide"]] = fh[di] > 1.3 * fh_avg[di]
                    f[A["fh_narrow"]] = fh[di] < 0.7 * fh_avg[di]
                f[A["pdhl_with"]] = beyond(pH, pL)
                f[A["pdc_with"]] = (cl - pC) * s > 0
                f[A["pdhl_against"]] = beyond_against(pH, pL)
                f[A["inside_prev"]] = pL <= cl <= pH
                if cpr_lo[di] == cpr_lo[di]:
                    f[A["cpr_narrow"]] = cprw[di - 1] < cpr_lo[di]
                    f[A["cpr_wide"]] = cprw[di - 1] > cpr_hi[di]
                top, bot = max(tc[di - 1], bc[di - 1]), min(tc[di - 1], bc[di - 1])
                f[A["cpr_with"]] = cl > top if s > 0 else cl < bot
                if vprev == vprev:
                    f[A["vix_low"]] = vprev < 13
                    f[A["vix_mid"]] = 13 <= vprev <= 18
                    f[A["vix_high"]] = vprev > 18
                if vm is not None and vprev == vprev and vm[j] == vm[j]:
                    vchg = (vm[j] / vprev - 1) * 100
                    f[A["vix_up"]] = vchg > 3
                    f[A["vix_down"]] = vchg < -3
                f[A["expiry_day"]] = exp[di]
                f[A["pre_expiry"]] = (not exp[di]) and nxt_exp
                f[A["far_expiry"]] = (not exp[di]) and not nxt_exp
                f[A["prev_with"]] = (pC - pO) * s > 0
                if di >= 4:
                    f[A["prev3_with"]] = (pC - dC[di - 4]) * s > 0
                f[A["prev_big_against"]] = (pC - dC[di - 2]) / dC[di - 2] * 100 * s < -1.0 if di >= 2 else False
                mv = (cl - dO[di]) / dO[di] * 100 * s
                f[A["move_with"]] = mv > 0.3
                f[A["move_against"]] = mv < -0.3
            # OI / PCR at ATM+-2 (OI known up to minute j)
            atm = int(np.floor(cl / STEP + 0.5)) * STEP
            co = po = co0 = po0 = 0.0
            ok = 0
            for kk in range(atm - 2 * STEP, atm + 3 * STEP, STEP):
                a_c, a_p = opts.get((kk, "C")), opts.get((kk, "P"))
                if a_c is None or a_p is None or np.isnan(a_c[j, 5]) or np.isnan(a_p[j, 5]):
                    continue
                ok += 1
                co += a_c[j, 5]; po += a_p[j, 5]
                fc = a_c[~np.isnan(a_c[:, 5]), 5][0]; fp = a_p[~np.isnan(a_p[:, 5]), 5][0]
                co0 += fc; po0 += fp
            if ok >= 3 and co > 0 and po > 0:
                pcr = po / co
                net = ((po - po0) - (co - co0)) / (po + co)
                feat[di, si, 0, A["pcr_with"]] = pcr > 1.2
                feat[di, si, 1, A["pcr_with"]] = pcr < 1 / 1.2
                feat[di, si, 0, A["pcr_against"]] = pcr < 1 / 1.2
                feat[di, si, 1, A["pcr_against"]] = pcr > 1.2
                feat[di, si, 0, A["oi_with"]] = net > 0.05
                feat[di, si, 1, A["oi_with"]] = net < -0.05
            if strad is not None and strad[0] == strad[0] and strad[j] == strad[j] and strad[0] > 0:
                ch = strad[j] / strad[0] - 1
                feat[di, si, :, A["strad_up"]] = ch >= 0.03
                feat[di, si, :, A["strad_down"]] = ch <= -0.10
            # ---------------- trades
            i0 = t - M0
            for dr, right in ((0, "C"), (1, "P")):
                for sk in (0, 1):
                    K = atm if sk == 0 else (atm - STEP if right == "C" else atm + STEP)
                    a = opts.get((K, right))
                    if a is None or np.isnan(a[i0, 0]) or a[i0, 0] < MIN_PREM:
                        continue
                    if np.nansum(a[i0 - 5:i0 + 1, 4]) <= 0:
                        continue
                    res, e = simulate(a[:, 0], a[:, 1], a[:, 2], a[:, 3], i0, r["lot"])
                    pnl[di, si, dr, sk] = res
                    prem[di, si, dr, sk] = e
        if di % 200 == 0:
            print(UND, "proc", d, flush=True)
    ds_arr = np.array([np.datetime64(x) for x in ds])
    ho = ds_arr >= np.datetime64(HOLDOUT)
    for tag, m in (("is", ~ho), ("ho", ho)):
        np.savez(os.path.join(OUTD, f"{tag}_{UND}.npz"), days=ds_arr[m], feat=feat[m], pnl=pnl[m], prem=prem[m],
                 lot=lots[m], atoms=np.array(ATOMS), exits=np.array([exit_name(k) for k in range(NE)]), slots=SLOTS)
    print(UND, "done", nd, "days; holdout", int(ho.sum()), flush=True)


if __name__ == "__main__":
    main()
