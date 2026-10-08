"""h26: derived features and trigger states from the panels built by build.py (see PREREG.md).

All arrays are (ndays, 375); a value at column t uses bars <= t only. Normalisers use PREVIOUS days only.
"""
from __future__ import annotations

import os
from datetime import date, timedelta
from functools import lru_cache

from obuy import config as C  # noqa: E402  (sets the pandas deps path first)
import numpy as np
import pandas as pd
import build as B

OUT = B.OUT
EPOCH = date(1970, 1, 1)
T0, T1 = 15, 315                  # 09:30 .. 14:30 trigger window (columns)
SAMPLE = np.arange(T0, T1 + 1, 5)


def _lag(A, L):
    out = np.full_like(A, np.nan)
    out[:, L:] = A[:, :-L]
    return out


def trail_z(F, n=60):
    """z of F against the mean / std of its 5-minute samples (09:30-14:30) over the previous n days."""
    s = F[:, SAMPLE]
    m1 = pd.Series(np.nanmean(s, axis=1))
    m2 = pd.Series(np.nanmean(s * s, axis=1))
    mu = m1.rolling(n, min_periods=20).mean().shift(1).values
    sd = np.sqrt(np.maximum(m2.rolling(n, min_periods=20).mean().shift(1).values - mu ** 2, 0))
    with np.errstate(invalid="ignore", divide="ignore"):
        return (F - mu[:, None]) / np.where(sd > 0, sd, np.nan)[:, None]


def trail_median(F, n=20):
    """same-column median of F over the previous n days."""
    df = pd.DataFrame(F)
    return df.rolling(n, min_periods=10).median().shift(1).values


@lru_cache(maxsize=None)
def opt(u):
    z = np.load(os.path.join(OUT, f"opt_{u}.npz"))
    days = [EPOCH + timedelta(days=int(x)) for x in z["days"]]
    X = z["X"].astype(np.float64)
    F = {f: X[:, i, :] for i, f in enumerate(B.FEATS)}
    step = C.STEP[u]
    D = {}
    D["spot"] = F["spot"]
    for f in ("BU15", "BUopen", "dOIpc2", "dOIpc5"):
        D[f + "_z"] = trail_z(F[f])
    with np.errstate(invalid="ignore", divide="ignore"):
        vim = (F["vc15"] - F["vp15"]) / (F["vc15"] + F["vp15"])
        D["VIMB_z"] = trail_z(vim)
        D["PFLOW_z"] = trail_z(F["pf15"])
        D["rc"] = F["vc5"] / trail_median(F["vc5"])
        D["rp"] = F["vp5"] / trail_median(F["vp5"])
    D["dpc5"], D["dpp5"] = F["dpc5"], F["dpp5"]
    D["dWC"] = (F["wallC"] - _lag(F["wallC"], 30)) / step
    D["dWP"] = (F["wallP"] - _lag(F["wallP"], 30)) / step
    return days, D


@lru_cache(maxsize=None)
def stk():
    z = np.load(os.path.join(OUT, "stk.npz"))
    names = list(z["names"])
    days = [EPOCH + timedelta(days=int(x)) for x in z["days"]]
    return names, days, z["Cl"].astype(np.float64), z["V"].astype(np.float64), z["VW"].astype(np.float64)


@lru_cache(maxsize=None)
def cons(u, mk_index_mat=None):
    """constituent features for BANKNIFTY / NIFTY on the days that have both stock and index minutes."""
    from obuy.data import market
    names, sdays, Cl, V, VW = stk()
    W = B.W_BN if u == "BANKNIFTY" else B.W_NF
    heavy = B.HEAVY[u]
    ix = market().index(u)
    M = ix.mat()
    days = [d for d in sdays if d in ix.pos]
    si = [sdays.index(d) for d in days]
    I = B.ffill(M["c"][[ix.pos[d] for d in days]])
    k = [names.index(s) for s in W]
    w = np.array([W[s] for s in W])
    cl, v, vw = Cl[k][:, si], V[k][:, si], VW[k][:, si]           # [n, nd, 375]
    with np.errstate(invalid="ignore", divide="ignore"):
        r5 = cl / np.concatenate([np.full(cl.shape[:2] + (5,), np.nan), cl[:, :, :-5]], axis=2) - 1
        r15 = cl / np.concatenate([np.full(cl.shape[:2] + (15,), np.nan), cl[:, :, :-15]], axis=2) - 1
        r3 = cl / np.concatenate([np.full(cl.shape[:2] + (3,), np.nan), cl[:, :, :-3]], axis=2) - 1
        cv = np.cumsum(v, axis=2)
        v5 = cv - np.concatenate([np.zeros(cv.shape[:2] + (5,)), cv[:, :, :-5]], axis=2)
        to = np.cumsum(v * np.nan_to_num(cl), axis=2)
        to15 = to - np.concatenate([np.zeros(to.shape[:2] + (15,)), to[:, :, :-15]], axis=2)
        D = {}
        hk = [list(W).index(s) for s in heavy]
        surge = np.zeros(cl.shape[1:])
        for j in hk:
            nrm = trail_median(v5[j])
            surge += w[j] * np.sign(np.nan_to_num(r5[j])) * (v5[j] >= 3 * nrm)
        D["HVSURGE"] = surge / w[hk].sum()
        D["VWB"] = np.nansum(to15 * np.sign(np.nan_to_num(r15)), axis=0) / np.nansum(to15, axis=0)
        D["VWAPSH"] = np.nansum(w[:, None, None] * (cl > vw), axis=0) / w.sum()
        b3 = np.nansum(w[:, None, None] * np.nan_to_num(r3), axis=0) / w.sum()
        D["B3_z"] = trail_z(b3)
        D["b3"] = b3
        D["i3"] = I / _lag(I, 3) - 1
        D["spot"] = I
        b1 = np.nansum(w[:, None, None] * np.nan_to_num(cl / np.concatenate([np.full(cl.shape[:2] + (1,), np.nan), cl[:, :, :-1]], axis=2) - 1), axis=0) / w.sum()
        D["b1"] = b1
    return days, D


# ------------------------------------------------------------------------------------------------ trigger states
OPT_TRIGS = ["BU", "DOIPC", "WALL", "VIMB", "PFLOW", "VSPIKE"]
CONS_TRIGS = ["HVSURGE", "VWB", "VWAP", "LEADLAG"]
GRID = {"BU": [("BU15_z", 2.0), ("BUopen_z", 2.0)], "DOIPC": [("dOIpc2_z", 2.0), ("dOIpc5_z", 2.0)],
        "WALL": [1, 2], "VIMB": [1.5, 2.5], "PFLOW": [1.5, 2.5], "VSPIKE": [3.0, 5.0],
        "HVSURGE": [0.4, 0.6], "VWB": [0.6, 0.8], "VWAP": [0.75, 0.9], "LEADLAG": [2.0, 3.0]}
SIGNED = {"BU", "DOIPC", "WALL", "VIMB", "PFLOW"}


def state(trig, u, p):
    """(days, S) with S in {-1, 0, +1} (conventional sign) for one setting."""
    if trig in CONS_TRIGS:
        days, D = cons(u)
    else:
        days, D = opt(u)
    with np.errstate(invalid="ignore"):
        if trig in ("BU", "DOIPC"):
            f, z = p
            x = D[f]
            S = np.where(x >= z, 1, np.where(x <= -z, -1, 0))
        elif trig == "WALL":
            S = np.where((D["dWC"] >= p) & (D["dWP"] >= p), 1, np.where((D["dWC"] <= -p) & (D["dWP"] <= -p), -1, 0))
        elif trig in ("VIMB", "PFLOW"):
            x = D[trig + "_z"]
            S = np.where(x >= p, 1, np.where(x <= -p, -1, 0))
        elif trig == "VSPIKE":
            rc, rp = D["rc"], D["rp"]
            bull = (rc >= p) & (D["dpc5"] > 0) & (rc > rp)
            bear = (rp >= p) & (D["dpp5"] > 0) & (rp > rc)
            S = np.where(bull, 1, np.where(bear, -1, 0))
        elif trig == "HVSURGE":
            x = D["HVSURGE"]
            S = np.where(x >= p, 1, np.where(x <= -p, -1, 0))
        elif trig == "VWB":
            x = D["VWB"]
            S = np.where(x >= p, 1, np.where(x <= -p, -1, 0))
        elif trig == "VWAP":
            x = D["VWAPSH"]
            S = np.where(x >= p, 1, np.where(x <= 1 - p, -1, 0))
        elif trig == "LEADLAG":
            z, b, i3 = D["B3_z"], D["b3"], D["i3"]
            sb = np.sign(b)
            S = np.where((np.abs(z) >= p) & (i3 * sb < 0.5 * np.abs(b)), sb, 0)
    return days, np.nan_to_num(S).astype(int)


def events(days, S, refractory=30):
    """event = state turns non-zero or flips side inside 09:30-14:30; >= refractory minutes between events."""
    out = []
    for i, d in enumerate(days):
        s = S[i]
        last = -10 ** 6
        prev = s[T0 - 1]
        for t in range(T0, T1 + 1):
            if s[t] != 0 and s[t] != prev and t - last >= refractory:
                out.append((d, t, int(s[t])))
                last = t
            prev = s[t]
    return out
