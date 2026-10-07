"""SCALP17: "+15-20 premium points, up to 5 trades a day, 1 lot (60 qty)" on NIFTY / BANKNIFTY / FINNIFTY options.

Stages (python3 -I research/obuy/scalp17.py <stage> ...; heavy stages under flock <scratch>/obuy.lock):
  build U      first-passage map for every entry minute of one underlying -> <cache>/scalp17/{feat,out}_<U>.parquet
               entry types: CE/PE x ATM/1-ITM of the nearest expiry ('near' series); decision on the CLOSE of minute s
               (09:16 .. 15:00), fill at the OPEN of minute s+1 (real Dhan option minute bars); the path is checked
               minute by minute up to 60 bars and never past the 15:10 square-off.
               Stored per entry: first bar (1..60, 127 = never) at which the option HIGH reaches E+u for u in UP, and
               the LOW reaches E-d for d in DN; the open gap through each stop level; the close at 15/30/60 bars.
  screen       every condition x (T, S, L) x entry type x underlying x timeframe (1-min and 5-min decision points):
               additive per-year sums for day-clustered means / t-stats, gross and net -> <cache>/scalp17/screen.npz
  select       BH over all screened combos, shortlist on 2020-2024 only, sequential (max 5 a day, one at a time)
               simulation, SPA, random-entry baseline, the single 2025-Oct 2026 holdout, anchored walk-forward.
  report       -> research/SCALP17.md

Fill model (all prices are traded prints; Dhan has no bid/ask):
  h = half-spread in points. Buy at the ask F = open + h. Target = resting limit sell at F + T: fills only when the
  traded high reaches F + T + h (so the bid has reached it). Stop = SL-M with trigger F - S on the LTP: triggers when
  the traded low reaches F - S (mid-move S - h), fills at the bid = min(trigger, bar open) - h. Time stop: sell at the
  close of bar L at the bid (close - h). Same-bar target + stop: the stop is assumed first (conservative).
  GROSS = h 0, no charges, no bps (what Boss asked for). NET(app) = h 0 + the app's fills (+-5 bps, stops -10 bps)
  + the app's charges (costs.Costs('app')). NET(+spread) = NET(app) with h 0.5 (1-pt spread). STRESS = h 1.0.
"""
from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from obuy import config as C  # noqa: E402  (adds the pandas deps path for python -I)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy import data as D  # noqa: E402

OUT = os.path.join(C.CACHE, "scalp17")
QTY = 60
UNDS3 = ("NIFTY", "BANKNIFTY", "FINNIFTY")
TS = (10, 15, 17, 20, 25)
SS = (10, 15, 20, 25, 30)
LS = (15, 30, 60)
HS = (0.0, 0.5, 1.0)
UP = sorted({t + 2 * h for t in TS for h in HS})               # traded-high thresholds above the open
DN = sorted({s - h for s in SS for h in HS})                    # traded-low thresholds below the open
NEVER = 127
S0, S1 = 1, 345                                                 # decision minutes 09:16 .. 15:00 (columns)
SQ = 354                                                        # last bar before the 15:10 square-off (15:09)
HMAX = 60
LIMIT = int(os.environ.get("S17_LIMIT", "0"))
TYPES = (("CE", 0), ("CE", 1), ("PE", 0), ("PE", 1))           # side, money (0 ATM, 1 = 1 ITM)


# ------------------------------------------------------------------------------------------------ build
def _bps(a, b, c):
    return (a - b) / c * 1e4


def _ema(x, n):
    out = np.full_like(x, np.nan)
    a = 2 / (n + 1)
    v = np.nan
    for i, xi in enumerate(x):
        if np.isnan(xi):
            out[i] = v
            continue
        v = xi if np.isnan(v) else v + a * (xi - v)
        out[i] = v
    return out


def _dte(ix):
    days = ix.days
    exp = np.array([ix.d[d]["exp"] for d in days])
    nxt = np.full(len(days), -1)
    j = -1
    for i in range(len(days) - 1, -1, -1):
        if exp[i]:
            j = i
        nxt[i] = j
    return {d: (nxt[i] - i if nxt[i] >= 0 else np.nan) for i, d in enumerate(days)}


def build(und):
    mk = D.market()
    ix = mk.index(und)
    op = mk.options(und)
    M = ix.mat()
    dte = _dte(ix)
    vix = mk.vix
    step = C.STEP[und]
    s = np.arange(S0, S1 + 1)
    ns = len(s)
    win = np.arange(HMAX)
    feats, outs = [], []
    t0 = time.time()
    rng1 = np.nanmean(M["h"] - M["l"], axis=1)                 # mean 1-min range per day
    h5 = np.full((len(ix.days), 75), np.nan)
    l5 = h5.copy()
    for k in range(75):
        h5[:, k] = np.nanmax(M["h"][:, 5 * k:5 * k + 5], axis=1)
        l5[:, k] = np.nanmin(M["l"][:, 5 * k:5 * k + 5], axis=1)
    rng5 = np.nanmean(h5 - l5, axis=1)
    cur_year = None
    for di, d in enumerate(ix.days):
        if d.year != cur_year:
            if cur_year is not None:
                mk.release(und)
            cur_year = d.year
            print(und, d.year, f"{time.time() - t0:.0f}s", flush=True)
        if di < 5 or (LIMIT and di > LIMIT):
            continue
        o, h, l, c = (M[k][di] for k in ("o", "h", "l", "c"))
        if np.isnan(c[S0:S1 + 1]).mean() > 0.2 or not ix.d[d]["real"]:
            continue
        ch = op.chain(d, "near")
        if ch is None:
            continue
        cff = pd.Series(c).ffill().values
        prev_c = M["c"][di - 1][~np.isnan(M["c"][di - 1])]
        if not len(prev_c):
            continue
        pc = prev_c[-1]
        r1p = np.nanmean(rng1[di - 5:di])
        r5p = np.nanmean(rng5[di - 5:di])
        # ---- 1-min index features (bullish positive), known at the close of minute s
        cs = cff[s]
        f = dict(day=np.full(ns, D.dnum(d), np.int32), s=s.astype(np.int16))
        f["r5"] = _bps(cs, cff[np.maximum(s - 5, 0)], cs)
        f["r15"] = _bps(cs, cff[np.maximum(s - 15, 0)], cs)
        f["r30"] = _bps(cs, cff[np.maximum(s - 30, 0)], cs)
        rng = h - l
        cr = np.nancumsum(np.nan_to_num(rng))
        cr = np.concatenate([[0], cr])
        lo15 = np.maximum(s - 14, 0)
        f["rexp"] = (cr[s + 1] - cr[lo15]) / (s + 1 - lo15) / r1p
        f["gap"] = np.full(ns, _bps(o[0], pc, pc))
        tp = np.nan_to_num((h + l + c) / 3)
        ctp = np.cumsum(tp)
        cnt = np.cumsum(~np.isnan(c))
        f["dvwap"] = _bps(cs, ctp[s] / np.maximum(cnt[s], 1), cs)
        hi = np.fmax.accumulate(np.where(np.isnan(h), -np.inf, h))[s]
        lo = np.fmin.accumulate(np.where(np.isnan(l), np.inf, l))[s]
        f["dhi"] = _bps(hi, cs, cs)
        f["dlo"] = _bps(cs, lo, cs)
        f["dayret"] = _bps(cs, o[0], o[0])
        f["wd"] = np.full(ns, d.weekday(), np.int8)
        f["dte"] = np.full(ns, dte[d], np.float32)
        f["vix"] = np.full(ns, vix.prev_close(d), np.float32)
        # PCR-style OI flow near the money over the last 15 minutes (bullish = puts added faster than calls)
        atm = np.rint(cs / step) * step
        oiC = pd.DataFrame(ch.oi["C"]).T.ffill().T.values
        oiP = pd.DataFrame(ch.oi["P"]).T.ffill().T.values
        near = np.abs(ch.K[:, None] - atm[None, :]) <= 5 * step            # (nk, ns)
        def tot(a, cols):
            v = a[:, cols]
            return np.where(near, np.nan_to_num(v), 0).sum(axis=0)
        cP, pP = tot(oiP, s), tot(oiP, np.maximum(s - 15, 0))
        cC, pC = tot(oiC, s), tot(oiC, np.maximum(s - 15, 0))
        den = pP + pC
        f["pcrch"] = np.where(den > 0, ((cP - pP) - (cC - pC)) / np.where(den > 0, den, 1) * 1e4, np.nan)
        # ---- 5-min features at 5-min bar closes (s % 5 == 4): bars k = 0..74 cover columns 5k .. 5k+4
        o5 = o[0::5]
        c5 = np.array([cff[5 * k + 4] for k in range(75)])
        hh5, ll5 = h5[di], l5[di]
        e9 = _ema(c5, 9)
        is5 = (s % 5) == 4
        k = s // 5
        kp = np.maximum(k - 1, 0)
        b5 = dict(b5ret=_bps(c5[k], o5[k], o5[k]), b5rng=(hh5[k] - ll5[k]) / r5p,
                  b5ema=_bps(c5[k], e9[k], c5[k]))
        bull = c5 > o5
        bear = c5 < o5
        eng = np.where((c5[k] > o5[k]) & bear[kp] & (c5[k] >= o5[kp]) & (o5[k] <= c5[kp]), 1,
                       np.where((c5[k] < o5[k]) & bull[kp] & (c5[k] <= o5[kp]) & (o5[k] >= c5[kp]), -1, 0))
        ins = ((hh5[k] <= hh5[kp]) & (ll5[k] >= ll5[kp])).astype(np.int8)
        brk = np.where(c5[k] > hh5[kp], 1, np.where(c5[k] < ll5[kp], -1, 0))
        col = np.sign(c5 - o5)
        stk = np.zeros(75)
        for j in range(75):
            if col[j] == 0:
                stk[j] = 0
            elif j and np.sign(stk[j - 1]) == col[j]:
                stk[j] = stk[j - 1] + col[j]
            else:
                stk[j] = col[j]
        b5.update(b5eng=eng, b5ins=ins, b5brk=brk, b5stk=stk[k])
        valid_k = k >= 1
        for kk, v in b5.items():
            v = np.asarray(v, dtype=np.float32)
            f[kk] = np.where(is5 & valid_k, v, np.nan).astype(np.float32)
        F = pd.DataFrame(f)
        feats.append(F)
        # ---- outcomes per entry type
        e = s + 1
        idx = np.minimum(e[:, None] + win[None, :], SQ)
        inwin = (e[:, None] + win[None, :]) <= SQ
        for ti, (side, money) in enumerate(TYPES):
            r = "C" if side == "CE" else "P"
            strike = atm - money * step if side == "CE" else atm + money * step
            ki = np.searchsorted(ch.K, strike)
            ok = (ki < len(ch.K))
            ki = np.minimum(ki, len(ch.K) - 1)
            ok &= ch.K[ki] == strike
            O, H, L, Cc = ch.o[r], ch.h[r], ch.l[r], ch.c[r]
            E = O[ki, e]
            ok &= np.isfinite(E) & (E > 0) & ok
            if not ok.any():
                continue
            rows = np.nonzero(ok)[0]
            kr, er = ki[rows], E[rows]
            ir, mw = idx[rows], inwin[rows]
            Hw = np.where(mw, H[kr[:, None], ir], np.nan)
            Lw = np.where(mw, L[kr[:, None], ir], np.nan)
            Ow = np.where(mw, O[kr[:, None], ir], np.nan)
            rmax = np.fmax.accumulate(np.where(np.isnan(Hw), -np.inf, Hw), axis=1)
            rmin = np.fmin.accumulate(np.where(np.isnan(Lw), np.inf, Lw), axis=1)
            Cf = pd.DataFrame(Cc[kr]).T.ffill().T.values
            out = dict(day=np.full(len(rows), D.dnum(d), np.int32), s=s[rows].astype(np.int16),
                       typ=np.full(len(rows), ti, np.int8), E=er.astype(np.float32))
            for u in UP:
                hit = rmax >= (er + u)[:, None]
                out[f"u{u:g}"] = np.where(hit.any(1), hit.argmax(1) + 1, NEVER).astype(np.int8)
            for dd in DN:
                trig = (er - dd)[:, None]
                hit = rmin <= trig
                j = hit.argmax(1)
                anyh = hit.any(1)
                out[f"d{dd:g}"] = np.where(anyh, j + 1, NEVER).astype(np.int8)
                ow = Ow[np.arange(len(rows)), j]
                gap = np.where(anyh & (j > 0) & np.isfinite(ow), np.maximum(trig[:, 0] - ow, 0), 0)
                out[f"g{dd:g}"] = gap.astype(np.float32)
            for Lm in LS:
                cl = np.minimum(e[rows] + Lm - 1, SQ)
                out[f"c{Lm}"] = (Cf[np.arange(len(rows)), cl] - er).astype(np.float32)
                out[f"n{Lm}"] = (cl - e[rows] + 1).astype(np.int8)             # bars actually available
            # contract features: OI change % over 15 min, premium change over the last 5 minutes (pts)
            oi = (oiC if r == "C" else oiP)[kr]
            sr = s[rows]
            oi_now, oi_p = oi[np.arange(len(rows)), sr], oi[np.arange(len(rows)), np.maximum(sr - 15, 0)]
            out["oich"] = np.where(oi_p > 0, (oi_now - oi_p) / np.where(oi_p > 0, oi_p, 1) * 100, np.nan).astype(np.float32)
            cpp = Cf[np.arange(len(rows)), sr]
            cp5 = Cf[np.arange(len(rows)), np.maximum(sr - 5, 0)]
            out["opm5"] = (cpp - cp5).astype(np.float32)
            # liquidity / sanity of the entry: volume of the fill bar and of the 5 bars before it, the last close
            V = np.nan_to_num(ch.v[r][kr])
            out["v_e"] = V[np.arange(len(rows)), e[rows]].astype(np.float32)
            cv = np.concatenate([np.zeros((len(rows), 1)), np.cumsum(V, axis=1)], axis=1)
            out["vol5"] = (cv[np.arange(len(rows)), sr + 1] - cv[np.arange(len(rows)), np.maximum(sr - 4, 0)]).astype(np.float32)
            out["cprev"] = cpp.astype(np.float32)
            outs.append(pd.DataFrame(out))
    mk.release(und)
    os.makedirs(OUT, exist_ok=True)
    F = pd.concat(feats, ignore_index=True)
    for c_ in F.columns:
        if F[c_].dtype == np.float64:
            F[c_] = F[c_].astype(np.float32)
    F.to_parquet(os.path.join(OUT, f"feat_{und}.parquet"))
    Oo = pd.concat(outs, ignore_index=True)
    Oo.to_parquet(os.path.join(OUT, f"out_{und}.parquet"))
    print(und, "rows", len(F), len(Oo), f"{time.time() - t0:.0f}s")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "build":
        for u in sys.argv[2:]:
            build(u)
    else:
        from obuy import scalp17_an as A  # noqa: E402
        getattr(A, cmd)(*sys.argv[2:])
