"""h32 build: every 2 minutes (09:20..14:50), for each nearest-expiry CE/PE x ATM/1-ITM contract of NIFTY, BANKNIFTY,
FINNIFTY, MIDCPNIFTY, SENSEX: causal precursor features (index chart, the option's own chart, both sides' OI, clock)
and forward first-passage labels (what the option did AFTER the decision minute).

python3 -I research/hunt/h32/build.py NIFTY BANKNIFTY ...   (under flock <scratch>/obuy.lock)

Decision on the CLOSE of minute s; the "entry" reference is the option's OPEN at s+1 (what a buyer would get).
Features use bars <= s only. Labels use bars >= s+1 only, up to 60 minutes, never past 15:09 (15:10 square-off).
Output: <scratch>/hunt/h32/rows_<U>.parquet
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

OUT = os.path.join(C.SCRATCH, "hunt", "h32")
UP_PTS = (15, 20, 25, 30)
UP_PCT = (0.30,)          # side note only (Boss: points = absolute premium rupees)
DN_PTS = (10, 15)
DN_PCT = (0.15,)
NEVER = 255
SQ = 354
HMAX = 60
SDEC = np.arange(5, 336, 2)          # 09:20 .. 14:50
TYPES = (("CE", 0), ("CE", 1), ("PE", 0), ("PE", 1))
UCODE = {"NIFTY": 0, "BANKNIFTY": 1, "FINNIFTY": 2, "MIDCPNIFTY": 3, "SENSEX": 4}


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


def first_hit(mask):
    return np.where(mask.any(1), mask.argmax(1), NEVER).astype(np.uint8)


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
    rng1 = M["h"] - M["l"]
    mr1 = np.nanmean(rng1, axis=1)                         # mean 1-min range per day (points)
    Cd = np.array([x[~np.isnan(x)][-1] if (~np.isnan(x)).any() else np.nan for x in M["c"]])
    parts = []
    t0 = time.time()
    cur = None
    for di, d in enumerate(ix.days):
        if d.year != cur:
            if cur is not None:
                mk.release(und)
            cur = d.year
            print(und, d.year, f"{time.time() - t0:.0f}s", flush=True)
        if di < 6 or not ix.d[d]["real"]:
            continue
        o, h, l, c = (M[k][di] for k in ("o", "h", "l", "c"))
        if np.isnan(c[:345]).mean() > 0.2:
            continue
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        cff = pd.Series(c).ffill().bfill().values
        hff = pd.Series(h).ffill().bfill().values
        lff = pd.Series(l).ffill().bfill().values
        cs = cff[s]
        ref1 = np.nanmean(mr1[di - 5:di])                  # previous 5 days' mean 1-min range
        f = {}
        f["day"] = np.full(ns, D.dnum(d), np.int32)
        f["s"] = s.astype(np.int16)
        f["dte"] = np.full(ns, dte[d], np.float32)
        f["exp"] = np.full(ns, ix.d[d]["exp"], np.int8)
        bps = lambda a, b: (a / b - 1.0) * 1e4  # noqa: E731
        for k in (5, 15, 30, 60):
            f[f"ir{k}"] = bps(cs, cff[np.maximum(s - k, 0)])
        f["igap"] = np.full(ns, bps(cff[0], Cd[di - 1]))
        f["iday"] = bps(cs, cff[0])
        crng = np.concatenate([[0], np.cumsum(np.nan_to_num(rng1[di]))])
        f["rexp5"] = (crng[s + 1] - crng[s - 4]) / 5 / ref1          # last 5 min range speed vs normal
        f["rexp15"] = (crng[s + 1] - crng[np.maximum(s - 14, 0)]) / np.minimum(s + 1, 15) / ref1
        hh = pd.Series(hff).rolling(30, min_periods=1).max().values
        ll = pd.Series(lff).rolling(30, min_periods=1).min().values
        f["box30"] = (hh[s] - ll[s]) / (ref1 * 30 ** 0.5 * 2.0)       # last-30-min box height vs normal (coil < 1)
        hi = np.fmax.accumulate(hff)[s]
        lo = np.fmin.accumulate(lff)[s]
        f["dhi"] = bps(hi, cs)                                         # bps below day high
        f["dlo"] = bps(cs, lo)                                         # bps above day low
        f["drng"] = (hi - lo) / ref1                                   # day range so far / normal 1-min range
        # VIX
        vp = vix.prev_close(d)
        f["vix"] = np.full(ns, vp, np.float32)
        vm = vmin.get(d)
        if vm is not None:
            vf = pd.Series(vm).ffill().values
            f["vix15"] = (vf[s] / vf[np.maximum(s - 15, 0)] - 1) * 100
        else:
            f["vix15"] = np.full(ns, np.nan)
        # chain (both sides)
        Cc, Pc = ffill_rows(ch.c["C"]), ffill_rows(ch.c["P"])
        oiC, oiP = ffill_rows(ch.oi["C"]), ffill_rows(ch.oi["P"])
        vC = np.concatenate([np.zeros((len(ch.K), 1)), np.cumsum(np.nan_to_num(ch.v["C"]), 1)], 1)
        vP = np.concatenate([np.zeros((len(ch.K), 1)), np.cumsum(np.nan_to_num(ch.v["P"]), 1)], 1)
        atm = np.rint(cs / step) * step
        ka = np.clip(np.searchsorted(ch.K, atm), 0, len(ch.K) - 1)
        s15 = np.maximum(s - 15, 0)
        strad = Cc[ka, s] + Pc[ka, s]
        f["strad"] = strad / cs * 1e4
        f["strad15"] = (strad / (Cc[ka, s15] + Pc[ka, s15]) - 1) * 100
        near = np.abs(ch.K[:, None] - atm[None, :]) <= 3 * step

        def tot(a, cols):
            return np.where(near, np.nan_to_num(a[:, cols]), 0).sum(axis=0)

        Pn, Cn, P15, C15 = tot(oiP, s), tot(oiC, s), tot(oiP, s15), tot(oiC, s15)
        den = np.where(Pn + Cn > 0, Pn + Cn, np.nan)
        pcrch = ((Pn - P15) - (Cn - C15)) / den * 1e4                  # net put-minus-call OI added, bps of OI
        vtotC = tot(vC, s + 1) - tot(vC, np.maximum(s - 4, 0))
        vtotP = tot(vP, s + 1) - tot(vP, np.maximum(s - 4, 0))
        base = pd.DataFrame({k: (v if k in ("day", "s", "exp") else np.asarray(v, np.float32)) for k, v in f.items()})
        e = s + 1
        idx = np.minimum(e[:, None] + win[None, :], SQ)
        inwin = (e[:, None] + win[None, :]) <= SQ
        for ti, (side, money) in enumerate(TYPES):
            a = 1 if side == "CE" else -1
            r = "C" if side == "CE" else "P"
            strike = atm - money * step if side == "CE" else atm + money * step
            ki = np.searchsorted(ch.K, strike)
            ok = ki < len(ch.K)
            ki = np.minimum(ki, len(ch.K) - 1)
            ok &= ch.K[ki] == strike
            O, H, L = ch.o[r], ch.h[r], ch.l[r]
            Cf, OIf, Vc = (Cc, oiC, vC) if r == "C" else (Pc, oiP, vP)
            Cx, OIx, Vx = (Pc, oiP, vP) if r == "C" else (Cc, oiC, vC)
            E = O[ki, e]
            ok &= np.isfinite(E) & (E > 1.0) & np.isfinite(Cf[ki, s])
            if not ok.any():
                continue
            rw = np.nonzero(ok)[0]
            kr, er, sr = ki[rw], E[rw], s[rw]
            ir, mw = idx[rw], inwin[rw]
            Hw = np.where(mw, H[kr[:, None], ir], np.nan)
            Lw = np.where(mw, L[kr[:, None], ir], np.nan)
            Cw = np.where(mw, Cf[kr[:, None], ir], np.nan)
            out = base.iloc[rw].reset_index(drop=True)
            # side-aligned index features
            for k in ("ir5", "ir15", "ir30", "ir60", "igap", "iday", "pcrch"):
                src = out[k].values if k != "pcrch" else pcrch[rw]
                out["a_" + k] = (a * src).astype(np.float32)
            out = out.drop(columns=["ir5", "ir15", "ir30", "ir60", "igap", "iday"])
            out["a_dfav"] = np.where(a > 0, out.dhi, out.dlo).astype(np.float32)    # room to the day extreme in favour
            out["a_dadv"] = np.where(a > 0, out.dlo, out.dhi).astype(np.float32)
            out = out.drop(columns=["dhi", "dlo"])
            out["typ"] = np.int8(ti)
            out["E"] = er.astype(np.float32)
            c0 = Cf[kr, sr]
            out["prem_pct"] = (c0 / cs[rw] * 1e4).astype(np.float32)               # premium in bps of spot
            out["o5"] = ((c0 / Cf[kr, np.maximum(sr - 5, 0)] - 1) * 100).astype(np.float32)
            out["o15"] = ((c0 / Cf[kr, np.maximum(sr - 15, 0)] - 1) * 100).astype(np.float32)
            dayhi = np.fmax.accumulate(np.where(np.isnan(ch.h[r][kr]), -np.inf, ch.h[r][kr]), axis=1)
            dl = ch.l[r][kr]
            daylo = np.fmin.accumulate(np.where(np.isnan(dl), np.inf, dl), axis=1)
            dh, dlw = dayhi[np.arange(len(rw)), sr], daylo[np.arange(len(rw)), sr]
            out["o_pos"] = np.where(dh > dlw, (c0 - dlw) / np.where(dh > dlw, dh - dlw, 1), 0.5).astype(np.float32)
            out["o_off_lo"] = ((c0 / np.where(dlw > 0, dlw, np.nan) - 1) * 100).astype(np.float32)
            v5 = Vc[kr, sr + 1] - Vc[kr, np.maximum(sr - 4, 0)]
            lo35 = np.maximum(sr - 34, 0)
            v30 = (Vc[kr, np.maximum(sr - 4, 0)] - Vc[kr, lo35]) / np.maximum((np.maximum(sr - 4, 0) - lo35) / 5, 0.2)
            out["o_vsurge"] = (v5 / np.where(v30 > 0, v30, np.nan)).astype(np.float32)
            vx5 = Vx[kr, sr + 1] - Vx[kr, np.maximum(sr - 4, 0)]
            out["o_vshare"] = (v5 / np.where(v5 + vx5 > 0, v5 + vx5, np.nan)).astype(np.float32)  # my side's share
            out["o_oi15"] = ((OIf[kr, sr] / np.where(OIf[kr, np.maximum(sr - 15, 0)] > 0, OIf[kr, np.maximum(sr - 15, 0)], np.nan) - 1) * 100).astype(np.float32)
            out["x_oi15"] = ((OIx[kr, sr] / np.where(OIx[kr, np.maximum(sr - 15, 0)] > 0, OIx[kr, np.maximum(sr - 15, 0)], np.nan) - 1) * 100).astype(np.float32)
            out["x_o15"] = ((Cx[kr, sr] / Cx[kr, np.maximum(sr - 15, 0)] - 1) * 100).astype(np.float32)
            out["chainv5"] = ((vtotC + vtotP)[rw]).astype(np.float32)
            # ---- labels (first-passage minute index 0..59 after the entry bar, 255 = never within the window)
            rmax = np.fmax.accumulate(np.where(np.isnan(Hw), -np.inf, Hw), axis=1)
            rmin = np.fmin.accumulate(np.where(np.isnan(Lw), np.inf, Lw), axis=1)
            for u in UP_PTS:
                out[f"up{u}"] = first_hit(rmax >= (er + u)[:, None])
            for u in UP_PCT:
                out[f"upp{int(u * 100)}"] = first_hit(rmax >= (er * (1 + u))[:, None])
            for dd in DN_PTS:
                out[f"dn{dd}"] = first_hit(rmin <= (er - dd)[:, None])
            for dd in DN_PCT:
                out[f"dnp{int(dd * 100)}"] = first_hit(rmin <= (er * (1 - dd))[:, None])
            for k in (15, 30):
                cl = pd.DataFrame(Cw[:, :k]).ffill(axis=1).values[:, -1]
                out[f"ret{k}"] = (cl / er - 1).astype(np.float32)
            parts.append(out)
    df = pd.concat(parts, ignore_index=True)
    df.insert(0, "und", np.int8(UCODE[und]))
    os.makedirs(OUT, exist_ok=True)
    df.to_parquet(os.path.join(OUT, f"rows_{und}.parquet"), compression="zstd", index=False)
    print(und, "rows", len(df), f"{time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    for u in sys.argv[1:]:
        build(u)
