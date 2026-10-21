"""Price and volatility: returns, ranges, realised vol, ATR, DVOL (spot close unless said)."""
from __future__ import annotations

import numpy as np

from .registry import feature

WIN = {"1m": 1, "5m": 5, "15m": 15, "60m": 60, "240m": 240, "1440m": 1440}


def _ret(k):
    def f(ctx):
        with np.errstate(invalid="ignore", divide="ignore"):
            return np.log(ctx.c / ctx.shift(ctx.c, k)) * 100
    return f


for _n, _k in WIN.items():
    feature(f"ret_{_n}", "price", 1, ["minutes.close"], _n, f"Spot log return over the last {_k} minute(s), %.")(_ret(_k))


def _range(ctx, k):
    return ctx.memo(("range", k), lambda: (ctx.roll(ctx.h, k, "max") - ctx.roll(ctx.l, k, "min")) / ctx.c * 100)


@feature("range_60m_pct", "price", 1, ["minutes.high", "minutes.low", "minutes.close"], "60m",
         "High - low of the last 60 one-minute bars, % of the close.")
def range_60m(ctx):
    return _range(ctx, 60)


@feature("range_240m_pct", "price", 1, ["minutes.high", "minutes.low", "minutes.close"], "240m",
         "High - low of the last 240 one-minute bars, % of the close.")
def range_240m(ctx):
    return _range(ctx, 240)


@feature("day_ret_pct", "price", 1, ["minutes.open", "minutes.close"], "day",
         "Return since the analysis day's first open (UTC midnight by default), %.")
def day_ret(ctx):
    with np.errstate(invalid="ignore", divide="ignore"):
        return (ctx.c / ctx.day_first(ctx.o) - 1) * 100


@feature("day_range_pos", "price", 1, ["minutes.high", "minutes.low", "minutes.close"], "day",
         "Where the close sits in today's high-low so far: 0 = at the low, 1 = at the high.")
def day_range_pos(ctx):
    hi, lo = ctx.day_cum(ctx.h, "max"), ctx.day_cum(ctx.l, "min")
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(hi > lo, (ctx.c - lo) / (hi - lo), np.nan)


def _r1(ctx):
    return ctx.memo("r1", lambda: _ret(1)(ctx))


@feature("rv_60m_pct", "vol", 1, ["minutes.close"], "60m",
         "Realised volatility of the last 60 minutes: sqrt(sum of squared 1-minute log returns), %.")
def rv_60m(ctx):
    return np.sqrt(ctx.roll(_r1(ctx) ** 2, 60, "mean") * 60)


@feature("rv_1440m_pct", "vol", 1, ["minutes.close"], "1440m",
         "Realised volatility of the last 24 hours (1-minute returns), %.")
def rv_1440m(ctx):
    return ctx.memo("rv1440", lambda: np.sqrt(ctx.roll(_r1(ctx) ** 2, 1440, "mean") * 1440))


@feature("rv_ratio_1h_24h", "vol", 1, ["minutes.close"], "1440m",
         "Last hour's realised vol divided by the last 24 hours' hourly average (above 1 = busier than usual).")
def rv_ratio(ctx):
    with np.errstate(invalid="ignore", divide="ignore"):
        return rv_60m(ctx) / (rv_1440m(ctx) / np.sqrt(24))


@feature("atr14_1h_pct", "vol", 1, ["minutes.high", "minutes.low", "minutes.close"], "900m",
         "ATR(14) of rolling 60-minute bars ending at t, t-60, ..., t-780 (true range uses the close 60 minutes "
         "earlier), % of the close.")
def atr14_1h(ctx):
    h60, l60 = ctx.roll(ctx.h, 60, "max"), ctx.roll(ctx.l, 60, "min")
    pc = ctx.shift(ctx.c, 60)
    tr = np.fmax(h60, pc) - np.fmin(l60, pc)
    tr[~np.isfinite(pc)] = np.nan
    acc = np.zeros(ctx.n)
    cnt = np.zeros(ctx.n)
    for j in range(14):
        x = ctx.shift(tr, 60 * j)
        acc += np.nan_to_num(x)
        cnt += np.isfinite(x)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(cnt == 14, acc / 14, np.nan) / ctx.c * 100


@feature("atr14_1d_pct", "vol", 1, ["minutes.high", "minutes.low", "minutes.close"], "16d",
         "ATR(14) of the previous 14 complete analysis days (daily true range), % of the close.")
def atr14_1d(ctx):
    hi, lo = ctx.prev_days(ctx.h, "max"), ctx.prev_days(ctx.l, "min")
    pc2 = ctx.shift(ctx.prev_days(ctx.c, "last"), 1440)        # the close of the day before that one
    tr1 = np.fmax(hi, pc2) - np.fmin(lo, pc2)
    tr1[~(np.isfinite(hi) & np.isfinite(pc2))] = np.nan
    acc, cnt = np.zeros(ctx.n), np.zeros(ctx.n)
    for j in range(14):                                          # the same minute 0..13 days back = 14 daily ranges
        x = ctx.shift(tr1, 1440 * j)
        acc += np.nan_to_num(x)
        cnt += np.isfinite(x)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(cnt == 14, acc / 14, np.nan) / ctx.c * 100


@feature("dvol", "vol", 1, ["minutes.dvol"], "120m", "Deribit DVOL (30-day implied vol index, %), last hourly close.")
def dvol(ctx):
    return ctx.col("dvol")


@feature("dvol_chg_24h", "vol", 1, ["minutes.dvol"], "1560m", "DVOL change over the last 24 hours, vol points.")
def dvol_chg(ctx):
    d = ctx.col("dvol")
    return d - ctx.shift(d, 1440)


@feature("iv_minus_rv", "vol", 1, ["minutes.dvol", "minutes.close"], "1440m",
         "DVOL minus the last 24 hours' realised vol annualised (x sqrt(365)), vol points.")
def iv_rv(ctx):
    return ctx.col("dvol") - rv_1440m(ctx) * np.sqrt(365)
