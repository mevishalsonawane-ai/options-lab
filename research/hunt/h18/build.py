"""h18 panel builder: per (und, day, 5-min column) dealer-gamma / OI features from the nearest-expiry ATM±10 chain.

    OBUY_CACHE=<scratch>/hunt/h18/cache flock <scratch>/obuy.lock python3 -I research/hunt/h18/build.py [UND ...]
Output: <OBUY_CACHE>/h18/panel_<UND>.parquet
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))   # research/
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import data as D  # noqa: E402

OUT = os.path.join(C.CACHE, "h18")
COLS = np.arange(0, 371, 5)                   # 09:15 .. 15:25 every 5 min
GRID = np.linspace(-0.02, 0.02, 41)          # spot shifts for the gamma-flip search
SQ2PI = np.sqrt(2 * np.pi)


def ffill(a):
    """forward-fill NaNs along axis 1 of a 2-D array."""
    idx = np.where(~np.isnan(a), np.arange(a.shape[1])[None, :], 0)
    np.maximum.accumulate(idx, axis=1, out=idx)
    out = a[np.arange(a.shape[0])[:, None], idx]
    return out


def gamma(S, K, T, sig):
    """BS gamma; S (...), K (...), T years, sig decimal; broadcastable. r = q = 0."""
    sq = sig * np.sqrt(T)
    d1 = (np.log(S / K) + 0.5 * sig * sig * T) / sq
    return np.exp(-0.5 * d1 * d1) / (SQ2PI * S * sq)


def day_feats(ch, spot_cols, T, vix):
    K = ch.K.astype(float)
    oiC, oiP = ffill(ch.oi["C"]), ffill(ch.oi["P"])
    ivC, ivP = ffill(ch.iv["C"]), ffill(ch.iv["P"])
    oiC = np.nan_to_num(oiC[:, COLS]); oiP = np.nan_to_num(oiP[:, COLS])
    ivC = ivC[:, COLS]; ivP = ivP[:, COLS]
    S = spot_cols[None, :]
    iv = np.where(K[:, None] >= S, ivC, ivP)                    # OTM-side IV per strike
    iv = np.where((iv > 1) & (iv < 300), iv, np.nan)
    # ATM IV per column
    ia = np.argmin(np.abs(K[:, None] - S), axis=0)
    atm_iv = iv[ia, np.arange(len(COLS))]
    fb = np.where(np.isfinite(atm_iv), atm_iv, vix)
    iv = np.where(np.isfinite(iv), iv, fb[None, :]) / 100.0
    g = gamma(S, K[:, None], T[None, :], iv)
    unit = S[0] ** 2 * 0.01                                     # Rs per 1% move per unit gamma
    gC, gP = (g * oiC).sum(0) * unit, (g * oiP).sum(0) * unit
    gexA = gC - gP
    gtot = gC + gP
    # flip: GEX_A over shifted spots
    S2 = S[0][None, None, :] * (1 + GRID)[:, None, None]       # (grid, 1, cols)
    g2 = gamma(S2, K[None, :, None], T[None, None, :], iv[None, :, :])
    prof = ((g2 * oiC[None]).sum(1) - (g2 * oiP[None]).sum(1)) * S2[:, 0, :] ** 2 * 0.01   # (grid, cols)
    flip = np.full(len(COLS), np.nan)
    sgn = np.sign(prof)
    mid = len(GRID) // 2
    for j in range(len(COLS)):
        ch_ = np.nonzero(sgn[1:, j] != sgn[:-1, j])[0]
        if len(ch_):
            k = ch_[np.argmin(np.abs(ch_ + 0.5 - mid))]
            # linear interpolation
            y0, y1 = prof[k, j], prof[k + 1, j]
            x = GRID[k] + (GRID[k + 1] - GRID[k]) * (y0 / (y0 - y1)) if y0 != y1 else GRID[k]
            flip[j] = x * 100
    tot = oiC + oiP
    maxoi = K[np.argmax(tot, axis=0)]
    maxc = K[np.argmax(oiC, axis=0)]
    maxp = K[np.argmax(oiP, axis=0)]
    # max pain over candidate settlement = strikes
    P = K[:, None, None]
    pain = (oiC[None] * np.maximum(P - K[None, :, None], 0) + oiP[None] * np.maximum(K[None, :, None] - P, 0)).sum(1)
    mpain = K[np.argmin(pain, axis=0)]
    ok = tot.sum(0) > 0
    return dict(gexA=gexA, gtot=gtot, flip=flip, maxoi=maxoi, maxc=maxc, maxp=maxp, mpain=mpain,
                atm_iv=atm_iv, oi_tot=tot.sum(0), pcr=oiP.sum(0) / np.maximum(oiC.sum(0), 1), ok=ok)


def build(und):
    os.makedirs(OUT, exist_ok=True)
    ix = D.Index(und)
    op = D.Options(und)
    vx = D.Vix()
    M = ix.mat()
    exp_days = np.array([d for d in ix.days if ix.d[d]["exp"]])
    rows = []
    for i, d in enumerate(ix.days):
        x = ix.d[d]
        if not x["real"]:
            continue
        k = np.searchsorted(exp_days, d)
        if k >= len(exp_days):
            continue
        ed = exp_days[k]
        if (ed - d).days > 40:
            continue
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        cl = pd.Series(M["c"][i]).ffill().bfill().values
        sp = cl[COLS]
        if np.isnan(sp).any():
            continue
        T = ((ed - d).days + (930 - (C.OPEN_M + COLS)) / 1440.0) / 365.0
        T = np.maximum(T, 5 / 1440 / 365)
        vix = vx.prev_close(d)
        f = day_feats(ch, sp, T, vix)
        df = pd.DataFrame(f)
        df["col"] = COLS
        df["spot"] = sp
        df["day"] = pd.Timestamp(d)
        df["dte"] = (ed - d).days
        df["exp"] = bool(x["exp"])
        df["series"] = ch.series
        df["vix"] = vix
        df["step"] = C.STEP[und]
        rows.append(df)
    P = pd.concat(rows, ignore_index=True)
    P["und"] = und
    P.to_parquet(os.path.join(OUT, f"panel_{und}.parquet"))
    print(und, len(P), P.day.nunique(), flush=True)


if __name__ == "__main__":
    for u in sys.argv[1:] or ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY"]:
        build(u)
