"""r10 features from the panels (build.py): session VWAP family and Market-Profile day structure.

Every feature for a trade is read at column t = entry_min - 1 - 555 (the last bar CLOSED before the entry minute).
VWAP weights = nearest-expiry option volume per minute (proxy; see validate.py); TWAP = equal weights (robustness).
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

R10 = os.path.join(C.SCRATCH, "hunt", "r10")
W = C.W
PC0 = 15 * 60 - C.OPEN_M          # 15:00 bar = col 345: start of NSE's 30-minute closing-price VWAP window


def ffill2(a):
    return pd.DataFrame(a.T).ffill().bfill().values.T


def load(u):
    z = np.load(os.path.join(R10, f"panel_{u}.npz"))
    days = pd.to_datetime(z["days"], unit="D")
    o, h, l, c = (ffill2(z[k].astype(np.float64)) for k in ("o", "h", "l", "c"))
    v = np.nan_to_num(z["v"].astype(np.float64))
    return days, o, h, l, c, v, z["exp"]


def vwap_family(h, l, c, v):
    tp = (h + l + c) / 3.0
    out = {}
    for nm, w in (("vw", v + 1e-9), ("tw", np.ones_like(v))):
        cw = np.cumsum(w, axis=1)
        m1 = np.cumsum(w * tp, axis=1) / cw
        m2 = np.cumsum(w * tp * tp, axis=1) / cw
        out[nm] = m1
        out[nm + "_sd"] = np.sqrt(np.maximum(m2 - m1 * m1, 0))
    # prior-day full-session VWAP (level) and VWAP anchored at the prior day's 15:00 bar
    w = v + 1e-9
    pd_v = np.full(len(c), np.nan)
    pd_v[1:] = out["vw"][:-1, -1]
    out["pdvw"] = pd_v
    a_w = np.zeros(len(c)); a_wp = np.zeros(len(c))
    a_w[1:] = w[:-1, PC0:].sum(axis=1)
    a_wp[1:] = (w[:-1, PC0:] * tp[:-1, PC0:]).sum(axis=1)
    cw = np.cumsum(w, axis=1) + a_w[:, None]
    out["avpc"] = (np.cumsum(w * tp, axis=1) + a_wp[:, None]) / cw
    out["avpc"][0] = np.nan
    return out


def day_structure(o, h, l, c):
    """Market-Profile style day structure (objective rules, PREREG.md)."""
    n = len(c)
    O = o[:, 0]
    # first 30 minutes (TPO periods A) and IB (first 60 minutes, A+B)
    H30, L30, C30 = h[:, :30].max(1), l[:, :30].min(1), c[:, 29]
    H15, L15 = h[:, :15].max(1), l[:, :15].min(1)
    R30 = np.maximum(H30 - L30, 1e-9)
    IBH, IBL = h[:, :60].max(1), l[:, :60].min(1)
    IBR = IBH - IBL
    ser = pd.Series(IBR)
    ib_ratio = (ser / ser.rolling(20, min_periods=10).mean().shift(1)).values
    r30 = pd.Series(H30 - L30)
    or_ratio = (r30 / r30.rolling(20, min_periods=10).mean().shift(1)).values
    # opening type: 1 OD, 2 ORR, 3 OTD, 0 OA; direction +1/-1/0
    typ = np.zeros(n, int); dr = np.zeros(n, int)
    od_up = (O - L30 <= 0.1 * R30) & (C30 >= L30 + 0.7 * R30)
    od_dn = (H30 - O <= 0.1 * R30) & (C30 <= H30 - 0.7 * R30)
    orr_dn = (H15 - O >= 0.5 * R30) & (C30 <= O - 0.2 * R30)      # pushed up first, rejected, closed below open
    orr_up = (O - L15 >= 0.5 * R30) & (C30 >= O + 0.2 * R30)
    otd_up = (C30 >= L30 + 0.7 * R30) & (C30 > O)
    otd_dn = (C30 <= H30 - 0.7 * R30) & (C30 < O)
    for mask, t, d in ((od_up, 1, 1), (od_dn, 1, -1), (orr_up, 2, 1), (orr_dn, 2, -1), (otd_up, 3, 1), (otd_dn, 3, -1)):
        sel = mask & (typ == 0)
        typ[sel] = t; dr[sel] = d
    # outcome labels (end of day; never used as a filter)
    DH, DL, DC = h.max(1), l.min(1), c[:, -1]
    DR = np.maximum(DH - DL, 1e-9)
    ext = DR / np.maximum(IBR, 1e-9)
    loc = (DC - DL) / DR
    trend = (ext >= 2.0) & ((loc >= 0.8) | (loc <= 0.2))
    rng = ext < 1.3
    post = np.sign(DC - c[:, 59])
    dr20 = pd.Series(DR).rolling(20, min_periods=10).mean().shift(1).values
    post_abs = np.abs(DC - c[:, 59]) / dr20          # rest-of-day move vs typical day range (IB-independent)
    big_day = DR / dr20                               # day range vs typical (IB-independent)
    return dict(O=O, IBH=IBH, IBL=IBL, IBR=IBR, ib_ratio=ib_ratio, or_ratio=or_ratio, otype=typ, odir=dr,
                trend=trend, range_day=rng, ext=ext, post=post,
                post_abs=post_abs, big_day=big_day)


def ib_ext_at(h, l, IBH, IBL, i, t):
    """IB extension known at column t (>= 60): +1 up only, -1 down only, 2 both, 0 none."""
    if t < 60:
        return np.nan
    up = h[i, 60:t + 1].max() > IBH[i]
    dn = l[i, 60:t + 1].min() < IBL[i]
    return 2 if (up and dn) else 1 if up else -1 if dn else 0


def trade_features(u, T):
    """T: rows of one underlying with columns day, entry_min, side. Returns a DataFrame of features."""
    days, o, h, l, c, v, exp = load(u)
    vf = vwap_family(h, l, c, v)
    ds = day_structure(o, h, l, c)
    pos = {d: i for i, d in enumerate(days)}
    rows = []
    for d, em, s in zip(pd.to_datetime(T.day), T.entry_min.values, T.side.values):
        i = pos.get(pd.Timestamp(d).normalize())
        t = int(em) - 1 - C.OPEN_M
        if i is None or t < 1 or t >= W:
            rows.append(dict(ok=False)); continue
        px = c[i, t]
        f = dict(ok=True, px=px)
        for k in ("vw", "tw"):
            f[k] = vf[k][i, t]
            sd = vf[k + "_sd"][i, t]
            f[k + "_z"] = (px - f[k]) / sd if sd > 0 else 0.0
            f[k + "_slope"] = vf[k][i, t] - vf[k][i, max(t - 15, 0)]
        f["pdvw"] = vf["pdvw"][i]
        f["avpc"] = vf["avpc"][i, t]
        f["t"] = t
        f["ib_ratio"] = ds["ib_ratio"][i] if t >= 60 else np.nan
        f["or_ratio"] = ds["or_ratio"][i] if t >= 30 else np.nan
        f["otype"] = ds["otype"][i] if t >= 30 else -1
        f["odir"] = ds["odir"][i] if t >= 30 else 0
        f["ibx"] = ib_ext_at(h, l, ds["IBH"], ds["IBL"], i, t)
        f["exp"] = bool(exp[i])
        rows.append(f)
    return pd.DataFrame(rows, index=T.index)
