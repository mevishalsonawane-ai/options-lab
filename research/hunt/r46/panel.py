"""R46 amendments: build, once, everything the amendments read from the option candles (cached in R46_OUT, never git):

  roll_C, roll_P   the ROLLING ATM call / put series, one row per minute (what a "btc_atm" file holds)
  held_C           per 5-minute decision minute: the HELD ATM call's own price 0, 5, ..., 70 minutes back and 60 minutes
                   ahead (the v2 features and label on one contract)
  dip_C, dip_P     per 15-minute bar close: the ATM contract's drop over that bar ON THE SAME CONTRACT, and the honest
                   trade from it (first real trade from 1 minute after the close + half spread; 60-minute exit with a
                   stop and a lock; exits less half spread, a tick and 1%), for exits L1 and L2

A decision minute t is the last minute of a bar (its close); everything at t uses minutes <= t, the label and the
trade use minutes > t only.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import amend_cfg as A
from . import chain as CH
from . import pool as PL
from . import r46cfg as K
from .exits import from_cfg, walk

PATH_LAGS = 15                  # prices at t, t-5, ..., t-70 (RSI14 needs 14 changes)


def held_day(day: CH.ChainDay, rows: np.ndarray, spot_close: np.ndarray) -> pd.DataFrame:
    """The HELD ATM call at each 5-minute decision minute of E's own window."""
    i0, i1 = day.own
    ks, _, _, _, c, _ = day.arr["C"]
    G = len(day.grid)
    ii = np.array([i for i in range(i0, i1 + 1) if day.grid[i].minute % K.BAR_MIN == K.BAR_MIN - 1], int)
    if not len(ii):
        return pd.DataFrame()
    k = rows[ii]
    ok = (k >= 0) & (ii - 5 * (PATH_LAGS - 1) >= 0) & (ii + 60 < G)
    ii, k = ii[ok], k[ok]
    lag = ii[:, None] - 5 * np.arange(PATH_LAGS)[None, :]
    P = c[k[:, None], lag]
    fut = c[k, ii + 60]
    out = pd.DataFrame({f"p{j}": P[:, j] for j in range(PATH_LAGS)})
    out.insert(0, "ts", day.grid[ii])
    out["expiry"] = day.E
    out["strike"] = ks[k]
    out["p_fut"] = fut
    out["spot"] = spot_close[ii]
    return out


def dip_day(day: CH.ChainDay, typ: str, rows: np.ndarray, spot_open: np.ndarray, exits: dict) -> pd.DataFrame:
    """Every 15-minute bar close of E's own window with an ATM contract priced >= $50: its drop on the same contract
    and the honest trade (per exit)."""
    i0, i1 = day.own
    ks, o, h, l, c, v = day.arr[typ]
    G = len(day.grid)
    W = K.HOLD_MIN + 1
    ii = np.array([i for i in range(i0, i1 + 1) if day.grid[i].minute % A.DIP_BAR_MIN == A.DIP_BAR_MIN - 1], int)
    if not len(ii):
        return pd.DataFrame()
    k = rows[ii]
    ok = (k >= 0) & (ii - A.DIP_BAR_MIN >= 0)
    ii, k = ii[ok], k[ok]
    prem = c[k, ii]
    prev = c[k, ii - A.DIP_BAR_MIN]
    keep = np.isfinite(prem) & (prem >= A.DIP_MIN_PREM)
    ii, k, prem, prev = ii[keep], k[keep], prem[keep], prev[keep]
    n = len(ii)
    if not n:
        return pd.DataFrame()
    # fill: the first real trade (volume > 0) in minutes close+1 .. close+5 (the close is the start of minute i+1)
    f = np.full(n, -1)
    for r in range(n):
        a = ii[r] + 1 + A.DIP_FILL_FROM
        w = v[k[r], a:a + A.DIP_FILL_WINDOW]
        hit = np.flatnonzero(w > 0)
        if len(hit) and a + hit[0] + 1 < G:
            f[r] = a + hit[0]
    filled = f >= 0
    mid = np.where(filled, c[k, np.clip(f, 0, G - 1)], np.nan)
    s_in = np.where(filled, spot_open[np.clip(f, 0, G - 1)], np.nan)
    buy = mid + PL.half_spread(mid, s_in)
    O = np.full((n, W), np.nan)
    H, L = O.copy(), O.copy()
    S = np.full((n, W), np.nan)
    for r in np.flatnonzero(filled):
        a, b = f[r] + 1, min(f[r] + 1 + W, G)
        O[r, :b - a], H[r, :b - a], L[r, :b - a] = o[k[r], a:b], h[k[r], a:b], l[k[r], a:b]
        S[r, :b - a] = spot_open[a:b]
    base = dict(ts=day.grid[ii] + CH.ONE, side=typ, expiry=day.E, strike=ks[k], prem=prem, prev=prev,
                bar_chg=prem / prev - 1, traded=v[k, ii] > 0, filled=filled,
                fill_ts=day.grid[np.clip(f, 0, G - 1)], mid=mid, buy=buy, spot=s_in)
    out = []
    for name, ex in exits.items():
        px = np.full(n, np.nan)
        why = np.full(n, -1)
        col = np.full(n, -1)
        if filled.any():
            p, w_, cc, _ = walk(ex, mid[filled], O[filled], H[filled], L[filled], K.HOLD_MIN)
            px[filled], why[filled], col[filled] = p, w_, cc
        s_out = S[np.arange(n), np.clip(col, 0, W - 1)]
        s_out = np.where(np.isfinite(s_out), s_out, s_in)
        sell = np.maximum(px - PL.half_spread(px, s_out) - K.TICK - K.EXIT_SLIP_FRAC * px, 0.0)
        out.append(pd.DataFrame(dict(base, exit=name, sell=sell, reason=why, spot_out=s_out,
                                     exit_ts=day.grid[np.clip(f + 1 + np.maximum(col, 0), 0, G - 1)])))
    return pd.concat(out, ignore_index=True)


def build(spot: pd.DataFrame, a: pd.Timestamp, b: pd.Timestamp, frames: dict | None = None) -> dict:
    """Walk every expiry whose own window meets [a, b). frames: {expiry: candle frame} for tests (else the lake)."""
    exits = {e: from_cfg(K.OPT_EXITS[e]) for e in sorted(set(A.ENS_EXITS) | set(A.DIP_EXITS))}
    acc = {k: [] for k in ("roll_C", "roll_P", "held_C", "dip_C", "dip_P")}
    for E in CH.expiries(a, b):
        day = CH.ChainDay(E, None if frames is None else frames.get(E, pd.DataFrame()))
        if not day.ok:
            continue
        sc = spot.close.reindex(day.grid).to_numpy(float)
        so = spot.open.reindex(day.grid).to_numpy(float)
        for typ in ("C", "P"):
            rows = day.atm(typ, sc)
            acc[f"roll_{typ}"].append(CH.rolling_day(day, typ, sc, rows))
            acc[f"dip_{typ}"].append(dip_day(day, typ, rows, so, exits))
            if typ == "C":
                acc["held_C"].append(held_day(day, rows, sc))
    out = {}
    for k, v in acc.items():
        v = [x for x in v if len(x)]
        df = pd.concat(v, ignore_index=True) if v else pd.DataFrame()
        if len(df):
            df = df[(df.ts >= a) & (df.ts < b)].reset_index(drop=True)
        out[k] = df
    return out


def load_or_build(spot: pd.DataFrame, a: pd.Timestamp, b: pd.Timestamp) -> dict:
    keys = ("roll_C", "roll_P", "held_C", "dip_C", "dip_P")
    paths = {k: K.OUT / f"amend_{k}.parquet" for k in keys}
    if all(p.exists() for p in paths.values()):
        return {k: pd.read_parquet(p) for k, p in paths.items()}
    out = build(spot, a, b)
    K.OUT.mkdir(parents=True, exist_ok=True)
    for k, p in paths.items():
        out[k].to_parquet(p, index=False)
    return out
