"""Matched-baseline effects, day-clustered errors and Benjamini-Hochberg (PREREG section 6)."""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

STRATA = ("und", "tf", "tod", "trend", "volreg", "col")


def baseline(df: pd.DataFrame, y: str, strata=STRATA) -> np.ndarray:
    """The matched random baseline of every row: the mean of y over ALL candles of the same instrument, timeframe,
    time-of-day bucket, trend state, volatility regime and colour in the same period (the event rows included: they
    are a small share; stated). Same colour removes drift (R85 D1)."""
    return df.groupby(list(strata), observed=True)[y].transform("mean").to_numpy()


def clustered(e: np.ndarray, day: np.ndarray) -> tuple[float, float, int]:
    """Mean of e and its standard error clustered by session (forward windows overlap within a day)."""
    ok = np.isfinite(e)
    e, day = e[ok], day[ok]
    n = len(e)
    if n < 2:
        return (float(e.mean()) if n else math.nan), math.nan, 0
    m = e.mean()
    s = pd.Series(e - m).groupby(day).sum().to_numpy()
    g = len(s)
    if g < 2:
        return float(m), math.nan, g
    se = math.sqrt((s ** 2).sum() * g / (g - 1)) / n
    return float(m), float(se), g


def pval(z: float) -> float:
    return math.erfc(abs(z) / math.sqrt(2)) if np.isfinite(z) else math.nan


def effect(y: np.ndarray, base: np.ndarray, day: np.ndarray, mask: np.ndarray) -> dict:
    mask = mask & np.isfinite(y) & np.isfinite(base)
    e = (y - base)[mask]
    m, se, g = clustered(e, day[mask])
    z = m / se if se and np.isfinite(se) and se > 0 else math.nan
    return dict(n=int(mask.sum()), days=g, raw=float(np.mean(y[mask])) if mask.any() else math.nan,
                base=float(np.mean(base[mask])) if mask.any() else math.nan, eff=m, se=se, z=z, p=pval(z))


def bh(p) -> np.ndarray:
    """Benjamini-Hochberg q-values (NaN p stays NaN and is not counted)."""
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.flatnonzero(np.isfinite(p))
    if not len(ok):
        return q
    ps = p[ok]
    o = np.argsort(ps)
    n = len(ps)
    r = ps[o] * n / np.arange(1, n + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(r, 1.0)
    q[ok] = out
    return q
