"""h35: the app's support / resistance levels, ported faithfully from the Kotlin, and the level EVENTS (break-and-hold,
retest-then-go, bounce) on closed 5-minute index bars. See PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h35/cache flock <scratch>/obuy.lock python3 -I research/hunt/h35/levels.py

Writes <OBUY_CACHE>/h35/events.parquet (und, day, sig_min, side, fam, mode, lev) for all days (both periods).

Ports (android/ = /home/user/options-lab/android):
  pivD   ira-core Reason.kt:233-236  Pivots.of: P=(H+L+C)/3, R1=2P-L, S1=2P-H, R2=P+(H-L), S2=P-(H-L);
         Reason.kt:246-249: while trading, from the session BEFORE today.
  pivW   Reason.kt:822-826  weekly: Pivots.of(max H, min L, last C) of the last 5 sessions (here: 5 complete sessions
         before today, as nothing of today is complete while trading).
  brain  ira-core Brain.kt:76-97  yesterday's high / low / close; today's opening range = the first 15 one-minute candles
         (OPENING_MINUTES = 15, Brain.kt:39), shown once more than 15 minutes are in; swing highs / lows of the CLOSED
         15-minute candles of the last 5 days (days.takeLast(5), today included) with Candles.swings(c15, 3)
         (Candles.kt:108-115: strictly higher / lower than the 3 candles each side).
  struct ira-core Structure.kt:23, 142, 173: today's closed 5-minute candles, Candles.swings(k = 2).
  maxoi  ira-core ChainRead.kt:22-24: the strike with the biggest call OI ("resistance") and with the biggest put OI
         ("support"), OI > 0; maxByOrNull keeps the first (lowest strike) on a tie.
  maxpain engine ChainAnalytics.kt:141-158: for each strike K*: sum over strikes K < K* of (K* - K) x CE OI plus over
         K > K* of (K - K*) x PE OI; the least total, first on a tie (strikes ascending).
  round  ira-core RoundCloses.kt:103: step 1,000 for BANKNIFTY / SENSEX, else 500.
  vix    ira-core Reason.kt:107-110 ExpectedRange.points = price x VIX / 100 / sqrt(252); Outlook.brief (Reason.kt:
         855-857): "a usual day spans" last close -/+ points (VIX: India VIX's previous close).
"""
from __future__ import annotations

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.CACHE, "h35")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY")
FAMS = ("pivD", "pivW", "brain", "struct", "maxoi", "maxpain", "round", "vix")
TOL = 0.0003            # 0.03% "touch" tolerance (PREREG)
RT_BARS = 12            # retest window: 60 minutes of 5-minute bars
FIRST_SIG, LAST_SIG = 555 + 14, 555 + 314      # signal bar closes 09:30 .. 14:30 (sig_min = the bar's last minute)
ROUND_STEP = {"BANKNIFTY": 1000.0, "SENSEX": 1000.0}


# ---------------------------------------------------------------------------------------------- ports
def pivots(h, l, c):
    p = (h + l + c) / 3
    return [p + (h - l), 2 * p - l, p, 2 * p - h, p - (h - l)]          # R2, R1, P, S1, S2


def swings(H, L, k):
    """Candles.swings: indices of swing highs / lows, strictly above / below the k candles each side."""
    hi, lo = [], []
    n = len(H)
    for i in range(k, n - k):
        w = list(range(i - k, i)) + list(range(i + 1, i + k + 1))
        if all(H[j] < H[i] for j in w):
            hi.append(i)
        if all(L[j] > L[i] for j in w):
            lo.append(i)
    return hi, lo


def max_pain(K, ce, pe):
    """ChainAnalytics.maxPain over strikes K (ascending) with OI arrays (NaN / <= 0 -> 0); first minimum on a tie."""
    ce = np.where(ce > 0, ce, 0.0); pe = np.where(pe > 0, pe, 0.0)
    D = K[:, None] - K[None, :]                 # candidate x strike
    tot = np.round(np.maximum(D, 0) @ ce + np.maximum(-D, 0) @ pe, 2)
    return K[int(np.argmin(tot))]


def fold(o, h, l, c, tf):
    """tf-minute candles from 09:15 of one day's dense arrays: (end_col, o, h, l, c)."""
    out = []
    for s in range(0, C.W, tf):
        e = min(s + tf, C.W)
        cc = c[s:e]
        ok = ~np.isnan(cc)
        if not ok.any():
            continue
        idx = np.nonzero(ok)[0]
        out.append((s + tf - 1, o[s + idx[0]], np.nanmax(h[s:e]), np.nanmin(l[s:e]), cc[idx[-1]]))
    return out


def ffill_cols(a):
    """forward-fill NaNs along axis 1 (minutes)."""
    a = a.copy()
    n, w = a.shape
    idx = np.where(~np.isnan(a), np.arange(w)[None, :], 0)
    np.maximum.accumulate(idx, axis=1, out=idx)
    out = a[np.arange(n)[:, None], idx]
    out[np.isnan(a[np.arange(n)[:, None], idx])] = np.nan
    return out


# ---------------------------------------------------------------------------------------------- events
def day_levels_static(fam, und, d, prevs, vixp):
    if fam == "pivD":
        ph, pl, pc = prevs[-1]
        return pivots(ph, pl, pc)
    if fam == "pivW":
        if len(prevs) < 5:
            return None
        w = prevs[-5:]
        return pivots(max(x[0] for x in w), min(x[1] for x in w), w[-1][2])
    if fam == "vix":
        pc = prevs[-1][2]
        if not (vixp > 0):
            return None
        p = pc * vixp / 100 / math.sqrt(252.0)
        return [pc - p, pc + p]
    return None


def detect(levels_at, bars, out_rows, und, d, fam):
    """levels_at(k) -> list of levels known at the close of bar k-1 (used to judge bar k). bars: (end_col,o,h,l,c)."""
    n = len(bars)
    E = np.array([b[0] for b in bars])
    Hh = np.array([b[2] for b in bars]); Ll = np.array([b[3] for b in bars]); Cc = np.array([b[4] for b in bars])
    sig = {"brk": {}, "rt": {}, "bo": {}}

    def add(mode, j, side, L):
        sm = 555 + int(E[j])
        if sm < FIRST_SIG or sm > LAST_SIG:
            return
        cur = sig[mode].get(j)
        if cur is None or abs(Cc[j] - L) < abs(Cc[j] - cur[1]):
            sig[mode][j] = (side, L)

    for k in range(1, n):
        Ls = levels_at(k)
        if not Ls:
            continue
        c0, c1 = Cc[k - 1], Cc[k]
        for L in Ls:
            if not np.isfinite(L):
                continue
            up = c0 <= L < c1
            dn = c0 >= L > c1
            if up or dn:
                s = 1 if up else -1
                if k + 1 < n and s * (Cc[k + 1] - L) > 0:
                    add("brk", k + 1, s, L)
                for j in range(k + 1, min(k + 1 + RT_BARS, n)):
                    if s * (Cc[j] - L) <= 0:
                        break
                    if (s > 0 and Ll[j] <= L * (1 + TOL)) or (s < 0 and Hh[j] >= L * (1 - TOL)):
                        add("rt", j, s, L)
                        break
            if c0 < L and Hh[k] >= L * (1 - TOL) and c1 < L:
                add("bo", k, -1, L)
            elif c0 > L and Ll[k] <= L * (1 + TOL) and c1 > L:
                add("bo", k, 1, L)
    for mode, dct in sig.items():
        for j, (side, L) in dct.items():
            out_rows.append((und, d, 555 + int(E[j]), side, fam, mode, float(L)))


def fam_levels(mk, und, i, ix, M, dl, opts, vixd):
    """{fam: f(t)}: the levels of each family known once minute column t (0 = 09:15) has closed, on day ix.days[i]."""
    d = ix.days[i]
    o, h, l, c = (M[k][i] for k in ("o", "h", "l", "c"))
    prevs = [(float(dl.high.iloc[j]), float(dl.low.iloc[j]), float(dl.close.iloc[j])) for j in range(i - 5, i)]
    vi = vixd.index.searchsorted(d) - 1
    vixp = float(vixd.iloc[vi]) if vi >= 0 else float("nan")
    F = {}
    for fam in ("pivD", "pivW", "vix"):
        lv = day_levels_static(fam, und, d, prevs, vixp)
        F[fam] = (lambda t, lv=lv: lv)
    step = ROUND_STEP.get(und, 500.0)
    base = math.floor(prevs[-1][2] / step) * step
    rl = [base + j * step for j in range(-4, 6)]
    F["round"] = lambda t: rl
    # brain: PDH/PDL/PDC + OR15 (once more than 15 minutes are in) + 15-min swings (last 5 days incl. today, k=3, closed)
    pd_l = [prevs[-1][0], prevs[-1][1], prevs[-1][2]]
    orh = np.nanmax(h[:15]); orl = np.nanmin(l[:15])
    c15 = []
    for j in range(i - 4, i + 1):
        oj, hj, lj, cj = (M[k][j] for k in ("o", "h", "l", "c"))
        for b in fold(oj, hj, lj, cj, 15):
            c15.append((j - i, b[0], b[2], b[3]))
    H15 = [x[2] for x in c15]; L15 = [x[3] for x in c15]
    sh, sl = swings(H15, L15, 3)
    sw = [(c15[x + 3][0], c15[x + 3][1], H15[x]) for x in sh] + [(c15[x + 3][0], c15[x + 3][1], L15[x]) for x in sl]

    def brain(t):
        lv = list(pd_l)
        if t >= 15:
            lv += [orh, orl]
        return lv + [x[2] for x in sw if x[0] < 0 or x[1] <= t]
    F["brain"] = brain
    b5 = fold(o, h, l, c, 5)
    H5 = [b[2] for b in b5]; L5 = [b[3] for b in b5]
    s5h, s5l = swings(H5, L5, 2)
    st = [(b5[x + 2][0], H5[x]) for x in s5h] + [(b5[x + 2][0], L5[x]) for x in s5l]
    F["struct"] = lambda t: [v for (tt, v) in st if tt <= t]
    ch = opts.chain(d, "near")
    if ch is None or len(ch.K) < 3:
        F["maxoi"] = F["maxpain"] = (lambda t: None)
        return F
    K = ch.K.astype(float)
    ce = ffill_cols(ch.oi["C"]); pe = ffill_cols(ch.oi["P"])

    def maxoi(t):
        a, b = ce[:, t], pe[:, t]
        lv = []
        if np.any(a > 0):
            lv.append(K[int(np.nanargmax(np.where(a > 0, a, -1)))])
        if np.any(b > 0):
            lv.append(K[int(np.nanargmax(np.where(b > 0, b, -1)))])
        return lv

    def maxpain(t):
        a, b = ce[:, t], pe[:, t]
        if np.any(a > 0) or np.any(b > 0):
            return [max_pain(K, np.nan_to_num(a), np.nan_to_num(b))]
        return None
    F["maxoi"], F["maxpain"] = maxoi, maxpain
    return F


def run_und(mk, und, rows):
    ix = mk.index(und)
    M = ix.mat()
    dl = ix.daily()
    opts = mk.options(und)
    vixd = mk.vix.daily.close
    for i, d in enumerate(ix.days):
        if i < 5 or not ix.d[d]["real"]:
            continue
        o, h, l, c = (M[k][i] for k in ("o", "h", "l", "c"))
        if np.isnan(c).sum() > 100:
            continue
        b5 = fold(o, h, l, c, 5)
        if len(b5) < 10:
            continue
        E5 = [b[0] for b in b5]
        F = fam_levels(mk, und, i, ix, M, dl, opts, vixd)
        for fam in FAMS:
            f = F[fam]
            detect(lambda k, f=f: f(E5[k - 1]), b5, rows, und, d, fam)
    mk.release(und)


def build():
    os.makedirs(OUT, exist_ok=True)
    mk = market()
    rows = []
    for u in UNDS:
        n0 = len(rows)
        run_und(mk, u, rows)
        print(u, len(rows) - n0, flush=True)
    E = pd.DataFrame(rows, columns=["und", "day", "sig_min", "side", "fam", "mode", "lev"])
    E.to_parquet(os.path.join(OUT, "events.parquet"))
    print(E.groupby(["fam", "mode"]).size().unstack().to_string())


if __name__ == "__main__":
    build()
