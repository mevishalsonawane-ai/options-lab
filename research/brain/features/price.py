"""Price family: returns, ranges, volatility, distance from the session's reference prices."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .registry import feature

C = ["minutes.close"]
OHLC = ["minutes.open", "minutes.high", "minutes.low", "minutes.close"]


def _ret(ctx, k):
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.log(ctx.c / ctx.shift_in_day(ctx.c, k)) * 100


for _k in (1, 5, 15, 60):
    feature(f"ret_{_k}m", "price", 1, C, f"{_k}m",
            f"Index log return over the last {_k} minute(s), %, within the session (NaN before {_k} minutes have "
            f"passed).")(lambda ctx, k=_k: _ret(ctx, k))


@feature("ret_day", "price", 1, OHLC, "day", "Index log return from the session's first open to now, %.")
def ret_day(ctx):
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.log(ctx.c / ctx.day_first(ctx.o)) * 100


@feature("gap_pct", "price", 1, OHLC, "1d", "Opening gap: the session's first open vs the previous session's last close, %.")
def gap_pct(ctx):
    last = ctx.day_complete_agg(ctx.c, "last")
    return (ctx.day_first(ctx.o) / ctx.prev_session(last) - 1) * 100


def _roll(ctx, a, k, how):
    return getattr(ctx.groupby_day(a).rolling(k, min_periods=k), how)().reset_index(level=0, drop=True).sort_index().values


@feature("range_15m_pct", "price", 1, OHLC, "15m", "High-low range of the last 15 minutes as % of the close.")
def range_15m(ctx):
    return (_roll(ctx, ctx.h, 15, "max") - _roll(ctx, ctx.l, 15, "min")) / ctx.c * 100


@feature("day_range_pct", "price", 1, OHLC, "day", "Session high minus session low so far, % of the close.")
def day_range(ctx):
    return (ctx.groupby_day(ctx.h).cummax().values - ctx.groupby_day(ctx.l).cummin().values) / ctx.c * 100


@feature("pos_in_day_range", "price", 1, OHLC, "day",
         "Where the close sits in the session's range so far: 0 = at the low, 1 = at the high.")
def pos_in_range(ctx):
    hi, lo = ctx.groupby_day(ctx.h).cummax().values, ctx.groupby_day(ctx.l).cummin().values
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(hi > lo, (ctx.c - lo) / (hi - lo), np.nan)


@feature("atr_14m_pct", "price", 1, OHLC, "15m",
         "Mean true range of the last 14 one-minute bars (within the session), % of the close.")
def atr14(ctx):
    pc = ctx.shift_in_day(ctx.c, 1)
    tr = np.fmax(ctx.h - ctx.l, np.fmax(np.abs(ctx.h - pc), np.abs(ctx.l - pc)))
    tr = np.where(np.isfinite(pc), tr, ctx.h - ctx.l)
    return _roll(ctx, tr, 14, "mean") / ctx.c * 100


@feature("rv_30m", "price", 1, C, "30m",
         "Realised volatility: std of the last 30 one-minute log returns x sqrt(375), % (a session-scaled figure).")
def rv30(ctx):
    r = _ret(ctx, 1)
    return _roll(ctx, r, 30, "std") * np.sqrt(375)


@feature("dist_tpavg_pct", "price", 1, OHLC, "day",
         "Close vs the session's running mean of typical price (H+L+C)/3, %. An equal-weight VWAP proxy: the index "
         "has no volume.")
def dist_tpavg(ctx):
    tp = (ctx.h + ctx.l + ctx.c) / 3
    s = pd.Series(tp)
    m = s.groupby(ctx.dayid).cumsum() / s.notna().astype(float).groupby(ctx.dayid).cumsum()
    return (ctx.c / m.values - 1) * 100


def _prev(ctx, how, arr):
    return ctx.prev_session(ctx.day_complete_agg(arr, how))


@feature("dist_prev_high_pct", "price", 1, OHLC, "1d", "Close vs the previous session's high, %.")
def dist_prev_high(ctx):
    return (ctx.c / _prev(ctx, "max", ctx.h) - 1) * 100


@feature("dist_prev_low_pct", "price", 1, OHLC, "1d", "Close vs the previous session's low, %.")
def dist_prev_low(ctx):
    return (ctx.c / _prev(ctx, "min", ctx.l) - 1) * 100


@feature("dist_prev_close_pct", "price", 1, OHLC, "1d", "Close vs the previous session's last close, %.")
def dist_prev_close(ctx):
    return (ctx.c / _prev(ctx, "last", ctx.c) - 1) * 100


@feature("dist_pivot_pct", "price", 1, OHLC, "1d",
         "Close vs the classic floor pivot (H+L+C)/3 of the previous session, %.")
def dist_pivot(ctx):
    p = (_prev(ctx, "max", ctx.h) + _prev(ctx, "min", ctx.l) + _prev(ctx, "last", ctx.c)) / 3
    return (ctx.c / p - 1) * 100


def _mins_since_extreme(ctx, a, better):
    out = np.full(ctx.n, np.nan)
    best, at, cur = np.nan, -1, -1
    for i in range(ctx.n):
        if ctx.dayid[i] != cur:
            cur, best, at = ctx.dayid[i], np.nan, -1
        v = a[i]
        if np.isfinite(v) and (not np.isfinite(best) or better(v, best)):
            best, at = v, ctx.minute[i]
        if at >= 0:
            out[i] = ctx.minute[i] - at
    return out


@feature("mins_since_day_high", "price", 1, OHLC, "day", "Minutes since the session's high so far was made (ties: the latest).")
def mins_high(ctx):
    return _mins_since_extreme(ctx, ctx.h, lambda v, b: v >= b)


@feature("mins_since_day_low", "price", 1, OHLC, "day", "Minutes since the session's low so far was made (ties: the latest).")
def mins_low(ctx):
    return _mins_since_extreme(ctx, ctx.l, lambda v, b: v <= b)
