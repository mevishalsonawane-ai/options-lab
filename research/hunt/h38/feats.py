"""h38: trigger states from the panels built by build.py (see PREREG.md).

Intraday arrays are (ndays, 375); a value at column t uses bars <= t only. Normalisers use PREVIOUS days only.
Daily states are keyed by the TRADING day (they use the previous session's bhavcopy only).
"""
from __future__ import annotations

import os
from datetime import date, timedelta
from functools import lru_cache

from obuy import config as C  # noqa: F401  (sets the pandas deps path first)
import numpy as np
import pandas as pd

DATA = os.path.join(C.SCRATCH, "hunt", "h38", "data")
H26 = os.path.join(C.SCRATCH, "hunt", "h26", "cache", "h26")
H26_FEATS = ["spot", "BU15", "BUopen", "dOIpc2", "dOIpc5", "wallC", "wallP", "vc5", "vp5", "dpc5", "dpp5", "vc15", "vp15", "pf15"]
EPOCH = date(1970, 1, 1)
T0, T1 = 15, 315                  # 09:30 .. 14:30 trigger window (columns)
DAILY_COL = 15                    # daily triggers: signal on the 09:30 bar close, entry 09:31
SAMPLE = np.arange(T0, T1 + 1, 5)
B_START = pd.Timestamp("2026-07-29")


def _lag(A, L):
    out = np.full_like(A, np.nan)
    out[:, L:] = A[:, :-L]
    return out


def ffill2(A):
    return pd.DataFrame(np.asarray(A, dtype=np.float64).T).ffill().values.T


def trail_z(F, n=60, minp=20, expanding=False):
    """z of F against the mean / std of its 5-minute samples (09:30-14:30) over the previous n days (or all previous)."""
    s = F[:, SAMPLE]
    m1 = pd.Series(np.nanmean(s, axis=1))
    m2 = pd.Series(np.nanmean(s * s, axis=1))
    if expanding:
        mu = m1.expanding(min_periods=minp).mean().shift(1).values
        mm = m2.expanding(min_periods=minp).mean().shift(1).values
    else:
        mu = m1.rolling(n, min_periods=minp).mean().shift(1).values
        mm = m2.rolling(n, min_periods=minp).mean().shift(1).values
    sd = np.sqrt(np.maximum(mm - mu ** 2, 0))
    with np.errstate(invalid="ignore", divide="ignore"):
        return (F - mu[:, None]) / np.where(sd > 0, sd, np.nan)[:, None]


# ------------------------------------------------------------------------------------------------ Part A intraday
@lru_cache(maxsize=None)
def syn(u):
    z = np.load(os.path.join(DATA, f"syn_{u}.npz"))
    days = [EPOCH + timedelta(days=int(x)) for x in z["days"]]
    X = z["X"].astype(np.float64)
    b = ffill2(X[:, 1, :])
    D = {"spot": X[:, 0, :], "basis": b}
    for L in (5, 15, 30):
        D[f"db{L}_z"] = trail_z(b - _lag(b, L))
    D["dbopen_z"] = trail_z(b - b[:, [5]])
    return days, D


# ------------------------------------------------------------------------------------------------ Part A daily
@lru_cache(maxsize=None)
def daily(u):
    """DataFrame indexed by TRADING day with the states fixed in PREREG (each uses the previous session only)."""
    D = pd.read_parquet(os.path.join(DATA, "daily_fut.parquet"))
    D = D[D.sym == u].sort_values("date").reset_index(drop=True)
    roll = D.near_exp.ne(D.near_exp.shift(1))
    prev_same = np.where(roll, D.f_close_next.shift(1), D.f_close.shift(1))      # yesterday's close of TODAY's near contract
    D["dP"] = D.f_close - prev_same
    D["dOI"] = np.where(roll, np.nan, D.f_oi.diff())
    D["dOI_z"] = (D.dOI - D.dOI.rolling(60, min_periods=20).mean().shift(1)) / D.dOI.rolling(60, min_periods=20).std().shift(1)
    sP = np.sign(D.dP)
    D["DBU_bu"] = np.where((D.dOI_z >= 1) & (D.dOI > 0), sP, 0)
    D["DBU_all"] = np.where(D.dOI_z.abs() >= 1, sP, 0)            # LB / SC -> +1 (dP>0), SB / LU -> -1 (dP<0)
    med = D.f_contracts.rolling(20, min_periods=10).median().shift(1)
    for k in (1.5, 2.0):
        D[f"DVOL_{k}"] = np.where(D.f_contracts >= k * med, sP, 0)
    sf = np.where(D.dOI > 0, sP, 0)
    so = np.sign(D.o_chg_oi_PE - D.o_chg_oi_CE)
    D["DDIS_agree"] = np.where((sf != 0) & (sf == so), sf, 0)
    D["DDIS_fut"] = np.where((sf != 0) & (so != 0) & (sf != so), sf, 0)
    D["DDIS_opt"] = np.where((sf != 0) & (so != 0) & (sf != so), so, 0)
    basis = D.f_close / D.spot_close - 1
    db = np.where(roll, np.nan, basis.diff())
    db = pd.Series(db)
    D["dbas_z"] = (db - db.rolling(60, min_periods=20).mean().shift(1)) / db.rolling(60, min_periods=20).std().shift(1)
    for k in (1, 2):
        D[f"DBAS_{k}"] = np.where(D.dbas_z >= k, 1, np.where(D.dbas_z <= -k, -1, 0))
    cols = ["DBU_bu", "DBU_all", "DVOL_1.5", "DVOL_2.0", "DDIS_agree", "DDIS_fut", "DDIS_opt", "DBAS_1", "DBAS_2", "dbas_z", "dOI_z"]
    S = D[cols].shift(1)                                         # known on the NEXT trading day
    S.index = pd.to_datetime(D.date).dt.date
    return S.iloc[1:].fillna(0)


# ------------------------------------------------------------------------------------------------ Part B (futures minutes)
@lru_cache(maxsize=None)
def fut(u):
    from obuy.data import market
    x = pd.read_parquet(os.path.join(DATA, f"futmin_{u}.parquet"))
    exp = {"SENSEX": "2026-10-29"}.get(u, "2026-10-27")
    x = x[(x.expiry == exp) & (x.day >= B_START)]
    days = sorted(x.day.dt.date.unique())
    pos = {d: i for i, d in enumerate(days)}
    n = len(days)
    c = np.full((n, C.W), np.nan); v = np.zeros((n, C.W)); oi = np.full((n, C.W), np.nan)
    di = x.day.dt.date.map(pos).values
    c[di, x.col.values] = x.close.values
    v[di, x.col.values] = x.volume.values
    oi[di, x.col.values] = np.where(x.open_interest.values > 0, x.open_interest.values, np.nan)
    c, oi = ffill2(c), ffill2(oi)
    ix = market().index(u)
    M = ix.mat()
    sp = np.full((n, C.W), np.nan)
    for d, i in pos.items():
        if d in ix.pos:
            sp[i] = M["c"][ix.pos[d]]
    sp = ffill2(sp)
    D = {"c": c, "oi": oi, "spot": sp}
    for L in (5, 15, 30, "open"):
        if L == "open":
            dP, dO, base = c - c[:, [0]], oi - oi[:, [0]], oi[:, [0]]
        else:
            dP, dO, base = c - _lag(c, L), oi - _lag(oi, L), _lag(oi, L)
        with np.errstate(invalid="ignore", divide="ignore"):
            r = dO / base
        D[f"r{L}"] = r
        D[f"r{L}_z"] = trail_z(r, n=10, minp=5)
        D[f"dP{L}"] = dP
    cv = np.cumsum(v, axis=1)
    v5 = cv - np.concatenate([np.zeros((n, 5)), cv[:, :-5]], axis=1)
    med = pd.DataFrame(v5).rolling(10, min_periods=5).median().shift(1).values
    with np.errstate(invalid="ignore", divide="ignore"):
        D["vr5"] = v5 / np.where(med > 0, med, np.nan)
        bas = (c / sp - 1) * 1e4
    D["dP5v"] = c - _lag(c, 5)
    D["fbas"] = bas
    D["fbas15_z"] = trail_z(bas - _lag(bas, 15), n=10, minp=5)
    # option dOIpc b5 z from h26's panel (60-day trailing z, computed over h26's full history)
    D["doipc5_z"] = np.full((n, C.W), np.nan)
    f = os.path.join(H26, f"opt_{u}.npz")
    if os.path.exists(f):
        z = np.load(f)
        od = [EPOCH + timedelta(days=int(q)) for q in z["days"]]
        X = z["X"][:, H26_FEATS.index("dOIpc5"), :].astype(np.float64)
        Z = trail_z(X)
        op = {d: i for i, d in enumerate(od)}
        for d, i in pos.items():
            if d in op:
                D["doipc5_z"][i] = Z[op[d]]
    return days, D


# ------------------------------------------------------------------------------------------------ trigger states
INTRA_A = ["BASIS"]
DAILY_A = ["DBU", "DVOL", "DDIS", "DBAS"]
PART_B = ["FBU", "FVOL", "FBAS", "FDIS"]
GRID = {"BASIS": ["db5_z", "db15_z", "db30_z", "dbopen_z"],
        "DBU": ["DBU_bu", "DBU_all"], "DVOL": ["DVOL_1.5", "DVOL_2.0"],
        "DDIS": ["DDIS_agree", "DDIS_fut", "DDIS_opt"], "DBAS": ["DBAS_1", "DBAS_2"],
        "FBU": [(L, m) for L in (5, 15, 30, "open") for m in ("bu", "all")], "FVOL": [3.0, 5.0],
        "FBAS": [2.0], "FDIS": [1.0]}
SIGNED = {"BASIS", "DBU", "DVOL", "DBAS", "FVOL", "FBAS"}       # run with the conventional AND the reversed sign
UNDS_A = {"BASIS": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]}
UNDS_DAILY = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]
UNDS_B = ["NIFTY", "BANKNIFTY", "MIDCPNIFTY", "SENSEX"]


def state(trig, u, p):
    """(days, S) with S in {-1, 0, +1} (conventional sign), intraday triggers only."""
    with np.errstate(invalid="ignore"):
        if trig == "BASIS":
            days, D = syn(u)
            x = D[p]
            S = np.where(x >= 2, 1, np.where(x <= -2, -1, 0))
        else:
            days, D = fut(u)
            if trig == "FBU":
                L, m = p
                z, dP, r = D[f"r{L}_z"], D[f"dP{L}"], D[f"r{L}"]
                big = (z >= 2) & (r > 0) if m == "bu" else (np.abs(z) >= 2)
                S = np.where(big, np.sign(dP), 0)
            elif trig == "FVOL":
                S = np.where(D["vr5"] >= p, np.sign(D["dP5v"]), 0)
            elif trig == "FBAS":
                x = D["fbas15_z"]
                S = np.where(x >= p, 1, np.where(x <= -p, -1, 0))
            elif trig == "FDIS":
                z, dP = D["r15_z"], D["dP15"]
                sf = np.where((np.abs(z) >= p), np.sign(dP), 0)
                so = np.where(D["doipc5_z"] >= p, 1, np.where(D["doipc5_z"] <= -p, -1, 0))
                S = np.where((sf != 0) & (sf == so), sf, 0)
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


def daily_events(u, col):
    S = daily(u)
    return [(d, DAILY_COL, int(s)) for d, s in S[col].items() if s != 0]
