"""Decision-row datasets and walk-forward model fits for the B2 and R41 re-tests (PREREG.md 5.11, 5.12).

A row = (index, day, minute row t): the brain's feature values at t (features.py; each uses data up to t only), plus
labels from the future of the SAME day (index log return and range over the next 15 / 30 / 60 minutes, and for B2's OPT
target whether an ATM call / put bought at t+1 with F1's x1 exit made money after costs). Labels are used only to fit
models on rows strictly before the predicted block (5-session gap), never as inputs.

Training uses rows from 21 Nov 2024 only (Boss, 15 Oct: nothing before Nov 2024). Walk-forward: one model per design
fold (Apr-Jun 2025, ..., Jan-Mar 2026) trained on every earlier design row; the final-test model on all design rows.
"""
from __future__ import annotations

from datetime import date
from functools import lru_cache

import numpy as np
import pandas as pd

import common as C
import engine as E
import features as FT
import r51cfg as K
import r51data

HORIZONS = (15, 30, 60)
GAP_SESSIONS = 5
X1 = E.Exits(stop=0.15, lock_trig=1000, lock_keep=500, lock_trail=500, tmax=30, need=0.05)


def dataset(und: str, rows: tuple, tag: str, opt_labels: bool = False) -> pd.DataFrame:
    """Design rows always; final-test rows appended only inside `study.py final` (they are never built before)."""
    X = _part(und, rows, tag, opt_labels, "design")
    if C.final_open():
        X = pd.concat([X, _part(und, rows, tag, opt_labels, "final")], ignore_index=True)
    return X


def _part(und: str, rows: tuple, tag: str, opt_labels: bool, part: str) -> pd.DataFrame:
    p = K.OUT / "ml" / f"{tag}_{part}_{und}.parquet"
    if p.exists():
        return pd.read_parquet(p)
    lo, hi = (K.START, K.DESIGN_END) if part == "design" else (K.FINAL_START, K.END)
    days = r51data.load(und)
    parts = []
    for d in C.days_in(und, lo, hi):
        D = days[d]
        f = FT.day(und, d)
        if f is None:
            continue
        f = f[f.index.isin(rows)]
        num = [c for c in f.columns if pd.api.types.is_numeric_dtype(f[c])]
        f = f[num].astype(np.float32).copy()
        t = f.index.values.astype(int)
        c = D.idx[3]
        hi, lo = D.idx[1], D.idx[2]
        for h in HORIZONS:
            ok = t + h < K.NROWS
            fut = np.where(ok, c[np.minimum(t + h, K.NROWS - 1)], np.nan)
            f[f"y_ret{h}"] = np.log(fut / c[t]) * 100
            rng = [np.nanmax(hi[x + 1:x + h + 1]) - np.nanmin(lo[x + 1:x + h + 1]) if x + h < K.NROWS else np.nan
                   for x in t]
            f[f"y_rng{h}"] = np.array(rng) / c[t] * 100
        if opt_labels:
            for s in ("C", "P"):
                ks = [E.strike_for(D, und, s, int(x), 0) for x in t]
                res = E.run_day(und, D, [s] * len(t), ks, list(t + 1), X1)
                f[f"y_net_{s}"] = [r["net"] if r is not None else np.nan for r in res]
        f.insert(0, "row", t)
        f.insert(0, "day", pd.Timestamp(d))
        f["und"] = und
        f["expiry"] = D.expiry
        parts.append(f.reset_index(drop=True))
    X = pd.concat(parts, ignore_index=True)
    p.parent.mkdir(parents=True, exist_ok=True)
    X.to_parquet(p, index=False)
    return X


def feature_cols(X: pd.DataFrame) -> list:
    return [c for c in X.columns if c not in ("day", "row", "und", "expiry") and not c.startswith("y_")]


def matrix(X: pd.DataFrame, cols: list, unds: tuple) -> np.ndarray:
    M = X[cols].to_numpy(np.float32, na_value=np.nan)
    U = np.stack([(X.und == u).to_numpy(np.float32) for u in unds], axis=1)
    R = (X.row.values / 375.0).astype(np.float32)[:, None]
    return np.hstack([M, U, R])


def blocks():
    """(name, train_end_exclusive, block_start, block_end): the 4 design folds and the final-test block."""
    out = [(f"wf{i}", a, a, b) for i, (a, b) in enumerate(K.WF_FOLDS)]
    out.append(("final", K.FINAL_START, K.FINAL_START, K.END))
    return out


def train_mask(X: pd.DataFrame, block_start: date) -> np.ndarray:
    """Rows from 21 Nov 2024 up to GAP_SESSIONS sessions before the block (never past the design end)."""
    ds = np.array(sorted(X.day.dt.date.unique()))
    before = ds[ds < min(block_start, date(2026, 4, 1))]
    if len(before) <= GAP_SESSIONS:
        return np.zeros(len(X), bool)
    cut = before[-GAP_SESSIONS]
    d = X.day.dt.date.values
    return (d >= K.START) & (d < cut) & (d <= K.DESIGN_END)


def block_mask(X: pd.DataFrame, lo: date, hi: date) -> np.ndarray:
    d = X.day.dt.date.values
    return (d >= lo) & (d <= hi)


def guard(name: str):
    if name == "final" and not C.final_open():
        raise PermissionError("the final-test model predicts final-test rows only inside `study.py final`")
