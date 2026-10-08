"""h42 shared: panel loading, option-trade P&L per exit (app fills + charges + h24 real half-spread), clustered stats, BH."""
from __future__ import annotations

import math
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
from obuy import config as C  # noqa: E402
from obuy.costs import Costs  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h42")
HERE = os.path.dirname(os.path.abspath(__file__))
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
HOLD = (date(2025, 10, 1) - date(1970, 1, 1)).days
LOT = {"NIFTY": 65, "BANKNIFTY": 35, "FINNIFTY": 60, "MIDCPNIFTY": 120, "SENSEX": 20}     # today's lots
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042, "SENSEX": 0.0020}  # h24 real
COST = Costs("app")
UPS, DNS, HS_ = (15, 20, 25, 30), (10, 15, 20), (15, 30, 60)
EXITS = ["time", "liq"] + [f"T{t}S{s}H{h}" for t in UPS for s in DNS for h in HS_]
NEVER = 255


def load_panel(u):
    z = np.load(os.path.join(OUT, f"c_{u}.npz"))
    keys = ["days", "E", "t15", "t30", "t60", "tsq", "liq", "liqstop"] + [f"hu{t}" for t in UPS] + [f"fu{t}" for t in UPS] + \
           [f"hd{s}" for s in DNS] + [f"fd{s}" for s in DNS]
    return {k: z[k] for k in keys}


def exit_px(P, ex, horizon, d, s, side):
    """exit price, stop flag, minutes held for arrays of (day-row d, decision s, side 0=CE/1=PE)."""
    tkey = {"15": "t15", "30": "t30", "60": "t60", "sq": "tsq", "5": "t15"}[str(horizon)]
    if ex == "time":
        X = P[tkey][d, s, side]
        held = {"t15": 15, "t30": 30, "t60": 60, "tsq": 355 - (s + 1)}[tkey]
        return X, np.zeros(len(X), bool), np.full(len(X), held) if np.isscalar(held) else held
    if ex == "liq":
        return P["liq"][d, s, side], P["liqstop"][d, s, side].astype(bool), 355 - (s + 1)
    t, rest = ex[1:].split("S")
    st, h = rest.split("H")
    t, st, h = int(t), int(st), int(h)
    hu = P[f"hu{t}"][d, s, side].astype(np.int16)
    hd = P[f"hd{st}"][d, s, side].astype(np.int16)
    tg = (hu < h) & (hu < hd)
    sp = (hd < h) & (hd <= hu)
    tx = P[f"t{h}"][d, s, side]
    X = np.where(tg, P[f"fu{t}"][d, s, side], np.where(sp, P[f"fd{st}"][d, s, side], tx))
    held = np.where(tg, hu + 1, np.where(sp, hd + 1, h))
    return X, sp, held


def pnl(u, E, X, stop):
    """per-trade (net Rs, gross Rs) at 1 lot (today's lot): app fills (+-5 bps market, -10 bps stops), h24 real
    half-spread on both sides, app charges."""
    q = LOT[u]
    hs = HS[u]
    bse = u == "SENSEX"
    buy = E * 1.0005 * (1 + hs)
    sell = X * (1 - np.where(stop, 0.0010, 0.0005)) * (1 - hs)
    qq = np.full(len(E), q, dtype=np.float64)
    ch = COST.charge(True, buy, qq, None, bse) + COST.charge(False, sell, qq, None, bse)
    return (sell - buy) * q - ch, (X - E) * q


def cl_mean(y, g):
    """mean and day-clustered SE."""
    y = np.asarray(y, float)
    _, gi = np.unique(g, return_inverse=True)
    G = gi.max() + 1 if len(gi) else 0
    n = len(y)
    if n < 2 or G < 2:
        return (float(np.mean(y)) if n else np.nan), np.nan, G
    m = y.mean()
    r = np.bincount(gi, weights=y - m, minlength=G)
    se = math.sqrt(G / (G - 1) * (r ** 2).sum()) / n
    return float(m), se, G


def cl_diff(y, a, g):
    """mean(y|a) - mean(y|~a) with a day-clustered SE (clusters shared)."""
    y = np.asarray(y, float)
    _, gi = np.unique(g, return_inverse=True)
    G = gi.max() + 1
    na, nb = a.sum(), (~a).sum()
    if na < 2 or nb < 2 or G < 3:
        return np.nan, np.nan, np.nan, np.nan
    ma, mb = y[a].mean(), y[~a].mean()
    ra = np.bincount(gi[a], weights=y[a] - ma, minlength=G) / na
    rb = np.bincount(gi[~a], weights=y[~a] - mb, minlength=G) / nb
    se = math.sqrt(G / (G - 1) * ((ra - rb) ** 2).sum())
    return float(ma - mb), se, float(ma), float(mb)


def p2(z):
    return math.erfc(abs(z) / math.sqrt(2)) if np.isfinite(z) else np.nan


def p1(z):
    return 0.5 * math.erfc(z / math.sqrt(2)) if np.isfinite(z) else np.nan


def bh(p):
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    pv = p[ok]
    n = len(pv)
    if n == 0:
        return q
    o = np.argsort(pv)
    r = pv[o] * n / np.arange(1, n + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    qq = np.empty(n)
    qq[o] = np.minimum(r, 1)
    q[ok] = qq
    return q


def load_table(kind, u, cols=None):
    return pd.read_parquet(os.path.join(OUT, f"{kind}_{u}.parquet"), columns=cols)


def entry_col(df, spec):
    if spec in df.columns:
        return df[spec].values.astype(int)
    return np.full(len(df), int(spec))
