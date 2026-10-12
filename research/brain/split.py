"""Training-side utilities: the ONLY place features and labels meet, and leakage-safe splits.

join_forward(features, labels)   features at t + labels describing t+1.. (same ts key), with name checks
walk_forward(ts, ...)            expanding-window folds by session: train strictly before test, purged + embargoed
purged_kfold(ts, ...)            k contiguous session blocks; train on both sides, purged before and embargoed after

Purge: drop every training sample whose label window [t, t + horizon] reaches into the test block.
Embargo: also drop training samples in the `embargo` after the test block (serial correlation / delayed effects).
"""
from __future__ import annotations

from typing import Iterator

import numpy as np
import pandas as pd

from .labels import MAX_HORIZON_MIN, PREFIX


def join_forward(features: pd.DataFrame, labels: pd.DataFrame) -> pd.DataFrame:
    fcols = [c for c in features.columns if c != "ts"]
    lcols = [c for c in labels.columns if c != "ts"]
    bad_f = [c for c in fcols if c.startswith(PREFIX)]
    bad_l = [c for c in lcols if not c.startswith(PREFIX)]
    if bad_f or bad_l or set(fcols) & set(lcols):
        raise ValueError(f"feature/label name clash: features with y_ {bad_f}, labels without y_ {bad_l}")
    return features.merge(labels, on="ts", how="inner", validate="one_to_one")


def _blocks(ts: pd.DatetimeIndex, n_blocks: int) -> list[tuple[pd.Timestamp, pd.Timestamp]]:
    days = np.array(sorted(set(ts.normalize())))
    parts = np.array_split(days, n_blocks)
    return [(pd.Timestamp(p[0]), pd.Timestamp(p[-1]) + pd.Timedelta(days=1) - pd.Timedelta(minutes=1))
            for p in parts if len(p)]


def _mask(ts, lo, hi, horizon, embargo, after: bool):
    """Training mask for a test block [lo, hi]: outside it, purged, embargoed."""
    end = ts + horizon
    overlap = (end >= lo) & (ts <= hi)
    keep = ~overlap
    if after:
        keep &= ~((ts > hi) & (ts <= hi + embargo))
    else:
        keep &= ts < lo
    return keep


def walk_forward(ts, n_splits: int = 5, horizon=pd.Timedelta(minutes=MAX_HORIZON_MIN), embargo=pd.Timedelta(days=1),
                 min_train_blocks: int = 1) -> Iterator[tuple[np.ndarray, np.ndarray]]:
    ts = pd.DatetimeIndex(ts)
    blocks = _blocks(ts, n_splits + min_train_blocks)
    for lo, hi in blocks[min_train_blocks:]:
        test = np.flatnonzero((ts >= lo) & (ts <= hi))
        train = np.flatnonzero(_mask(ts, lo, hi, horizon, embargo, after=False))
        yield train, test


def purged_kfold(ts, n_splits: int = 5, horizon=pd.Timedelta(minutes=MAX_HORIZON_MIN),
                 embargo=pd.Timedelta(days=1)) -> Iterator[tuple[np.ndarray, np.ndarray]]:
    ts = pd.DatetimeIndex(ts)
    for lo, hi in _blocks(ts, n_splits):
        test = np.flatnonzero((ts >= lo) & (ts <= hi))
        train = np.flatnonzero(_mask(ts, lo, hi, horizon, embargo, after=True))
        yield train, test
