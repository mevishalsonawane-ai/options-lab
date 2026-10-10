"""h1 build: ML feature set + first-passage labels at every 5-minute decision point (NIFTY, BANKNIFTY, FINNIFTY).

python3 -I research/hunt/h1/build.py NIFTY BANKNIFTY FINNIFTY   (under flock <scratch>/obuy.lock)

Decision on the CLOSE of minute s (s = 4, 9, ..., 339 -> 09:19 .. 14:54), fill at the OPEN of minute s+1 (real Dhan
option bars, nearest expiry 'near' series). Every feature uses data up to and including minute s only.
Candidates per decision point: CE/PE x ATM/1-ITM (strike from the index close at s).
Outcomes stored per candidate (premium-% levels): first bar at which the option HIGH reaches E*(1+u) (u in UPS) and
the LOW reaches E*(1-d) (d in DNS), the open-gap through each stop level, and the close at 15/30/60 bars (never past
the 15:10 square-off).
Output: <scratch>/hunt/h1/rows_<U>.parquet (one row per candidate, index features repeated).
"""
from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy import data as D  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h1")
UPS = (0.15, 0.20, 0.30, 0.40)
DNS = (0.10, 0.15, 0.20)
LS = (15, 30, 60)
NEVER = 127
SQ = 354           # 15:09, last bar before the 15:10 square-off
HMAX = 60
SDEC = np.arange(4, 340, 5)
LIMIT = int(os.environ.get('H1_LIMIT', '0'))
TYPES = (("CE", 0), ("CE", 1), ("PE", 0), ("PE", 1))


def bps(a, b):
    return (a / b - 1.0) * 1e4


def ffill_rows(a):
    return pd.DataFrame(a).T.ffill().T.values


def dte_map(ix):
    days = ix.days
    exp = np.array([ix.d[d]["exp"] for d in days])
    out, j = {}, -1
    for i in range(len(days) - 1, -1, -1):
        if exp[i]:
            j = i
        out[days[i]] = (j - i) if j >= 0 else np.nan
    return out


def build(und):
    mk = D.market()
    ix, op = mk.index(und), mk.options(und)
    M = ix.mat()
    dte = dte_map(ix)
    vix = mk.vix
    vmin = vix.minutes()
    step = C.STEP[und]
    s = SDEC
    ns = len(s)
    win = np.arange(HMAX)
    ucode = {"NIFTY": 0, "BANKNIFTY": 1, "FINNIFTY": 2}[und]
    # daily stats from minutes
    Hd, Ld, Cd, Od = (np.nanmax(M["h"], 1), np.nanmin(M["l"], 1), np.array([x[~np.isnan(x)][-1] if (~np.isnan(x)).any() else np.nan for x in M["c"]]),
                      np.array([x[~np.isnan(x)][0] if (~np.isnan(x)).any() else np.nan for x in M["o"]]))
    lr = np.diff(np.log(M["c"]), axis=1)
    rv1d = np.nanstd(lr, axis=1) * 1e4                      # day's 1-min realised vol (bps)
    rows = []
    t0 = time.time()
    cur = None
    for di, d in enumerate(ix.days):
        if d.year != cur:
            if cur is not None:
                mk.release(und)
            cur = d.year
            print(und, d.year, f"{time.time() - t0:.0f}s", flush=True)
        if di < 6 or (LIMIT and len(rows) >= 4 * LIMIT):
            continue
        o, h, l, c = (M[k][di] for k in ("o", "h", "l", "c"))
        if np.isnan(c[:345]).mean() > 0.2 or not ix.d[d]["real"]:
            continue
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        cff = pd.Series(c).ffill().bfill().values
        pc = Cd[di - 1]
        cs = cff[s]
        f = {}
        f["und"] = np.full(ns, ucode, np.int8)
        f["day"] = np.full(ns, D.dnum(d), np.int32)
        f["s"] = s.astype(np.int16)
        f["dte"] = np.full(ns, dte[d], np.float32)
        f["wd"] = np.full(ns, d.weekday(), np.int8)
        for k in (5, 15, 30, 60):
            f[f"r{k}"] = bps(cs, cff[np.maximum(s - k, 0)])
        f["dayret"] = bps(cs, o[0] if np.isfinite(o[0]) else cff[0])
        f["gap"] = np.full(ns, bps(cff[0], pc))
        f["prevret"] = np.full(ns, bps(Cd[di - 1], Cd[di - 2]))
        f["prevrng"] = np.full(ns, (Hd[di - 1] - Ld[di - 1]) / Cd[di - 1] * 1e4)
        f["prev5"] = np.full(ns, bps(Cd[di - 1], Cd[di - 6]))
        avg_rng5 = np.nanmean((Hd[di - 5:di] - Ld[di - 5:di]) / Cd[di - 5:di] * 1e4)
        hi = np.fmax.accumulate(np.where(np.isnan(h), -np.inf, h))[s]
        lo = np.fmin.accumulate(np.where(np.isnan(l), np.inf, l))[s]
        f["rngr"] = (hi - lo) / cs * 1e4 / avg_rng5
        f["pos"] = np.where(hi > lo, (cs - lo) / np.where(hi > lo, hi - lo, 1), 0.5) - 0.5
        f["dhi"] = bps(hi, cs)
        f["dlo"] = bps(cs, lo)
        orh, orl = np.nanmax(h[:15]), np.nanmin(l[:15])
        f["orpos"] = np.where(s >= 14, (cs - (orh + orl) / 2) / max(orh - orl, 1e-9), np.nan)
        lrd = np.nan_to_num(np.diff(np.log(cff), prepend=np.log(cff[0])))
        cl2 = np.concatenate([[0], np.cumsum(lrd ** 2)])
        lo30 = np.maximum(s - 29, 0)
        rv30 = np.sqrt((cl2[s + 1] - cl2[lo30]) / (s + 1 - lo30)) * 1e4
        f["rv30"] = rv30
        f["rvr"] = rv30 / np.nanmean(rv1d[di - 5:di])
        tp = np.nan_to_num((h + l + c) / 3, nan=0)
        cnt = np.cumsum(~np.isnan(c))
        f["dvwap"] = bps(cs, np.cumsum(tp)[s] / np.maximum(cnt[s], 1))
        # VIX
        vp = vix.prev_close(d)
        f["vix"] = np.full(ns, vp, np.float32)
        vm = vmin.get(d)
        if vm is not None:
            vf = pd.Series(vm).ffill().values
            f["vixd"] = (vf[s] / vp - 1) * 100
            f["vix30"] = (vf[s] / vf[np.maximum(s - 30, 0)] - 1) * 100
        else:
            f["vixd"] = np.full(ns, np.nan)
            f["vix30"] = np.full(ns, np.nan)
        # chain features: ATM straddle, IV, PCR / OI near the money (all at minute <= s)
        Cc, Pc = ffill_rows(ch.c["C"]), ffill_rows(ch.c["P"])
        oiC, oiP = ffill_rows(ch.oi["C"]), ffill_rows(ch.oi["P"])
        ivC, ivP = ffill_rows(ch.iv["C"]), ffill_rows(ch.iv["P"])
        atm = np.rint(cs / step) * step
        ka = np.clip(np.searchsorted(ch.K, atm), 0, len(ch.K) - 1)
        strad = Cc[ka, s] + Pc[ka, s]
        f["strad"] = strad / cs * 1e4
        f["strad15"] = (strad / (Cc[ka, np.maximum(s - 15, 0)] + Pc[ka, np.maximum(s - 15, 0)]) - 1) * 100
        fin = np.isfinite(f["strad"])
        f["stradd"] = f["strad"] - (f["strad"][fin][0] if fin.any() else np.nan)
        iv = (ivC[ka, s] + ivP[ka, s]) / 2
        f["iv"] = iv
        f["iv15"] = iv - (ivC[ka, np.maximum(s - 15, 0)] + ivP[ka, np.maximum(s - 15, 0)]) / 2
        near = np.abs(ch.K[:, None] - atm[None, :]) <= 5 * step

        def tot(a, cols):
            return np.where(near, np.nan_to_num(a[:, cols]), 0).sum(axis=0)

        Pn, Cn = tot(oiP, s), tot(oiC, s)
        P15, C15 = tot(oiP, np.maximum(s - 15, 0)), tot(oiC, np.maximum(s - 15, 0))
        P0, C0 = tot(oiP, np.full(ns, 2)), tot(oiC, np.full(ns, 2))
        f["pcr"] = np.where(Cn > 0, Pn / np.where(Cn > 0, Cn, 1), np.nan)
        f["pcr15"] = f["pcr"] - np.where(C15 > 0, P15 / np.where(C15 > 0, C15, 1), np.nan)
        f["pcrd"] = f["pcr"] - np.where(C0 > 0, P0 / np.where(C0 > 0, C0, 1), np.nan)
        f["coi15"] = np.where(C15 > 0, (Cn / np.where(C15 > 0, C15, 1) - 1) * 100, np.nan)
        f["poi15"] = np.where(P15 > 0, (Pn / np.where(P15 > 0, P15, 1) - 1) * 100, np.nan)
        base = pd.DataFrame({k: np.asarray(v, dtype=np.float32) if k not in ("und", "day", "s", "wd") else v for k, v in f.items()})
        # ---- candidates
        e = s + 1
        idx = np.minimum(e[:, None] + win[None, :], SQ)
        inwin = (e[:, None] + win[None, :]) <= SQ
        for ti, (side, money) in enumerate(TYPES):
            r = "C" if side == "CE" else "P"
            strike = atm - money * step if side == "CE" else atm + money * step
            ki = np.searchsorted(ch.K, strike)
            ok = ki < len(ch.K)
            ki = np.minimum(ki, len(ch.K) - 1)
            ok &= ch.K[ki] == strike
            O, H, L = ch.o[r], ch.h[r], ch.l[r]
            Cf = Cc if r == "C" else Pc
            E = O[ki, e]
            ok &= np.isfinite(E) & (E > 0.5)
            if not ok.any():
                continue
            rw = np.nonzero(ok)[0]
            kr, er = ki[rw], E[rw]
            ir, mw = idx[rw], inwin[rw]
            Hw = np.where(mw, H[kr[:, None], ir], np.nan)
            Lw = np.where(mw, L[kr[:, None], ir], np.nan)
            Ow = np.where(mw, O[kr[:, None], ir], np.nan)
            rmax = np.fmax.accumulate(np.where(np.isnan(Hw), -np.inf, Hw), axis=1)
            rmin = np.fmin.accumulate(np.where(np.isnan(Lw), np.inf, Lw), axis=1)
            out = base.iloc[rw].reset_index(drop=True)
            out["typ"] = np.int8(ti)
            out["side"] = np.int8(1 if side == "CE" else -1)
            out["money"] = np.int8(money)
            out["E"] = er.astype(np.float32)
            for u in UPS:
                hit = rmax >= (er * (1 + u))[:, None]
                out[f"u{int(u * 100)}"] = np.where(hit.any(1), hit.argmax(1) + 1, NEVER).astype(np.int8)
            for dd in DNS:
                trig = er * (1 - dd)
                hit = rmin <= trig[:, None]
                j = hit.argmax(1)
                anyh = hit.any(1)
                out[f"d{int(dd * 100)}"] = np.where(anyh, j + 1, NEVER).astype(np.int8)
                ow = Ow[np.arange(len(rw)), j]
                out[f"g{int(dd * 100)}"] = np.where(anyh & (j > 0) & np.isfinite(ow), np.maximum(trig - ow, 0), 0).astype(np.float32)
            sr = s[rw]
            for Lm in LS:
                cl = np.minimum(e[rw] + Lm - 1, SQ)
                out[f"c{Lm}"] = (Cf[kr, cl] - er).astype(np.float32)
                out[f"n{Lm}"] = (cl - e[rw] + 1).astype(np.int8)
            cpp = Cf[kr, sr]
            out["prem"] = (cpp / cs[rw] * 1e4).astype(np.float32)
            out["opm5"] = ((cpp / Cf[kr, np.maximum(sr - 5, 0)] - 1) * 100).astype(np.float32)
            out["opm15"] = ((cpp / Cf[kr, np.maximum(sr - 15, 0)] - 1) * 100).astype(np.float32)
            oi = (oiC if r == "C" else oiP)[kr]
            oi_now, oi_p = oi[np.arange(len(rw)), sr], oi[np.arange(len(rw)), np.maximum(sr - 15, 0)]
            out["oich"] = np.where(oi_p > 0, (oi_now / np.where(oi_p > 0, oi_p, 1) - 1) * 100, np.nan).astype(np.float32)
            V = np.nan_to_num(ch.v[r][kr])
            cv = np.concatenate([np.zeros((len(rw), 1)), np.cumsum(V, axis=1)], axis=1)
            out["vol5"] = (cv[np.arange(len(rw)), sr + 1] - cv[np.arange(len(rw)), np.maximum(sr - 4, 0)]).astype(np.float32)
            rows.append(out)
    mk.release(und)
    os.makedirs(OUT, exist_ok=True)
    R = pd.concat(rows, ignore_index=True)
    for c_ in R.columns:
        if R[c_].dtype == np.float64:
            R[c_] = R[c_].astype(np.float32)
    R.to_parquet(os.path.join(OUT, f"rows_{und}.parquet"))
    print(und, "rows", len(R), f"{time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    import warnings
    warnings.filterwarnings("ignore")
    for u in sys.argv[1:]:
        build(u)
