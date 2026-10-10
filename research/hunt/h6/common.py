"""h6 shared helpers: aligned index minute matrices for NIFTY/BANKNIFTY/FINNIFTY/SENSEX."""
from __future__ import annotations
import os, sys, pickle
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import obuy  # noqa
import numpy as np, pandas as pd
from obuy import config as C
from obuy.data import market

SCR = os.path.join(C.SCRATCH, "hunt", "h6")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"]
HOLD0 = pd.Timestamp("2025-10-01").date()


def aligned():
    """dict: days (common list over all 4, SENSEX may be NaN), C[u] (ndays, W) closes, O[u], exp[u] (bool), real[u]."""
    p = os.path.join(SCR, "aligned.pkl")
    if os.path.exists(p):
        return pd.read_pickle(p)
    mk = market()
    ixs = {u: mk.index(u) for u in UNDS}
    days = sorted(set(ixs["NIFTY"].days) | set(ixs["BANKNIFTY"].days))
    out = dict(days=days, C={}, O={}, H={}, L={}, exp={}, real={}, lot={})
    for u, ix in ixs.items():
        M = ix.mat()
        pos = {d: i for i, d in enumerate(ix.days)}
        n = len(days)
        for k in ("o", "h", "l", "c"):
            a = np.full((n, C.W), np.nan)
            for j, d in enumerate(days):
                if d in pos:
                    a[j] = M[k][pos[d]]
            out[k.upper()][u] = a
        out["exp"][u] = np.array([bool(ix.d[d]["exp"]) if d in pos else False for d in days])
        out["real"][u] = np.array([bool(ix.d[d]["real"]) if d in pos else False for d in days])
        out["lot"][u] = np.array([ix.lot(d) if d in pos else 0 for d in days])
    pd.to_pickle(out, p)
    return out
