"""Higher-timeframe bars from the cube minutes, and mapping bar values back to minute rows, point in time.

Bars are anchored to the session (bar k of a day covers minutes k*tf .. k*tf+tf-1; the last bar of the day may be
shorter, e.g. 60-minute bars end with 15:15-15:29). A bar exists only once the row of its final minute is present, and
is stamped with that row: the first row at which it is complete. Rows without index prices are skipped, and a bar with
no prices at all is dropped.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config


def bars(ctx, tf: int) -> pd.DataFrame:
    """Index OHLC bars of `tf` minutes: columns day (dayid), k, open, high, low, close, last_row."""
    def make():
        b = pd.DataFrame(dict(day=ctx.dayid, k=ctx.minute // tf, o=ctx.o, h=ctx.h, l=ctx.l, c=ctx.c,
                              row=np.arange(ctx.n), mn=ctx.minute))
        # first/last over rows with prices only (a gap row inside the bar does not end it)
        g = b.groupby(["day", "k"], sort=True)
        out = pd.DataFrame(dict(open=g.o.first(), high=g.h.max(), low=g.l.min(), close=g.c.last(),
                                last_mn=g.mn.max(), last_row=g.row.max())).reset_index()
        end = np.minimum(out.k.values * tf + tf - 1, config.SESSION_MINUTES - 1)
        out = out[out.last_mn.values == end]
        return out.dropna(subset=["open", "high", "low", "close"]).reset_index(drop=True)
    return ctx.memo(("bars", tf), make)


def to_rows(ctx, b: pd.DataFrame, values: np.ndarray, same_day: bool = False) -> np.ndarray:
    """Each minute row gets the value of the last bar complete at or before it (NaN before the first). same_day: NaN
    until the session's own first bar is complete (no carry-over from the previous session)."""
    if not len(b):
        return np.full(ctx.n, np.nan)
    idx = np.searchsorted(b.last_row.values, np.arange(ctx.n), side="right") - 1
    has = idx >= 0
    j = np.where(has, idx, 0)
    out = np.where(has, np.asarray(values, float)[j], np.nan)
    if same_day:
        out = np.where(has & (b.day.values[j] == ctx.dayid), out, np.nan)
    return out


def compress(*arrays):
    """Rows where every array is finite: (index, compressed arrays). Indicators run on these and are scattered back,
    so a missing minute is skipped, never filled."""
    ok = np.ones(len(arrays[0]), bool)
    for a in arrays:
        ok &= np.isfinite(a)
    idx = np.flatnonzero(ok)
    return idx, [np.asarray(a, float)[idx] for a in arrays]


def scatter(n: int, idx: np.ndarray, v: np.ndarray) -> np.ndarray:
    out = np.full(n, np.nan)
    out[idx] = v
    return out


def ema(x: np.ndarray, span: float | None = None, alpha: float | None = None) -> np.ndarray:
    """Recursive EMA seeded with the first value (as charting platforms do). Uses x[..t] only."""
    if len(x) == 0:
        return np.asarray(x, float)
    s = pd.Series(np.asarray(x, float))
    return (s.ewm(span=span, adjust=False).mean() if span is not None else s.ewm(alpha=alpha, adjust=False).mean()).values


def wilder(x: np.ndarray, n: int) -> np.ndarray:
    """Wilder's smoothing (RMA): alpha = 1/n."""
    return ema(x, alpha=1.0 / n)


def true_range(h, l, c) -> np.ndarray:
    pc = np.r_[np.nan, c[:-1]]
    tr = np.fmax(h - l, np.fmax(np.abs(h - pc), np.abs(l - pc)))
    return np.where(np.isfinite(pc), tr, h - l)
