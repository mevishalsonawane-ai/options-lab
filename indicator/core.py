"""Shared helpers and the registry every indicator is listed in.

Every indicator takes a DataFrame with columns open, high, low, close (and volume where it needs it), indexed by
time, plus its parameters, and returns a Series or a DataFrame aligned to that index. NaN where there is not yet
enough history. Formulas follow the authors' published definitions; where platforms differ (for example Wilder's
smoothing vs a plain EMA) the docstring says which one is used.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

import numpy as np
import pandas as pd

REGISTRY: dict[str, "Entry"] = {}


@dataclass
class Entry:
    name: str
    category: str
    fn: Callable
    summary: str
    needs_volume: bool


def indicator(category: str, summary: str, needs_volume: bool = False, name: str | None = None):
    """Register an indicator under [category] with a one-line [summary] for the catalog."""
    def wrap(fn):
        REGISTRY[name or fn.__name__] = Entry(name or fn.__name__, category, fn, summary, needs_volume)
        return fn
    return wrap


# ---- building blocks --------------------------------------------------------------------------------------------

def sma(s: pd.Series, n: int) -> pd.Series:
    return s.rolling(n, min_periods=n).mean()


def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False, min_periods=n).mean()


def rma(s: pd.Series, n: int) -> pd.Series:
    """Wilder's smoothing (TradingView ta.rma): an EMA with alpha = 1/n."""
    return s.ewm(alpha=1.0 / n, adjust=False, min_periods=n).mean()


def wma(s: pd.Series, n: int) -> pd.Series:
    w = np.arange(1, n + 1, dtype=float)
    return s.rolling(n, min_periods=n).apply(lambda x: np.dot(x, w) / w.sum(), raw=True)


def stdev(s: pd.Series, n: int) -> pd.Series:
    """Population standard deviation (TradingView ta.stdev)."""
    return s.rolling(n, min_periods=n).std(ddof=0)


def true_range(df: pd.DataFrame) -> pd.Series:
    pc = df.close.shift()
    tr = pd.concat([df.high - df.low, (df.high - pc).abs(), (df.low - pc).abs()], axis=1).max(axis=1)
    tr.iloc[0] = df.high.iloc[0] - df.low.iloc[0]
    return tr


def atr_wilder(df: pd.DataFrame, n: int = 14) -> pd.Series:
    return rma(true_range(df), n)


def typical(df: pd.DataFrame) -> pd.Series:
    return (df.high + df.low + df.close) / 3


def linreg(s: pd.Series, n: int, offset: int = 0) -> pd.Series:
    """Least-squares line over the last n values, evaluated at the last bar minus [offset] (ta.linreg)."""
    x = np.arange(n, dtype=float)
    xm = x.mean()
    den = ((x - xm) ** 2).sum()

    def f(y):
        b = np.dot(x - xm, y - y.mean()) / den
        a = y.mean() - b * xm
        return a + b * (n - 1 - offset)
    return s.rolling(n, min_periods=n).apply(f, raw=True)


def linreg_slope(s: pd.Series, n: int) -> pd.Series:
    x = np.arange(n, dtype=float)
    xm = x.mean()
    den = ((x - xm) ** 2).sum()
    return s.rolling(n, min_periods=n).apply(lambda y: np.dot(x - xm, y - y.mean()) / den, raw=True)


def highest(s: pd.Series, n: int) -> pd.Series:
    return s.rolling(n, min_periods=n).max()


def lowest(s: pd.Series, n: int) -> pd.Series:
    return s.rolling(n, min_periods=n).min()


def need_volume(df: pd.DataFrame) -> pd.Series:
    if "volume" not in df or df.volume.isna().all() or (df.volume.fillna(0) == 0).all():
        raise ValueError("this indicator needs a volume column (an index such as BANKNIFTY has none: use futures)")
    return df.volume.astype(float)
