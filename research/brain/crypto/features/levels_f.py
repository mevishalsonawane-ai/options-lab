"""Levels: previous day / week high-low-close and round numbers. Distances are (close / level - 1), %."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .registry import feature


def _d(ctx, lvl):
    with np.errstate(invalid="ignore", divide="ignore"):
        return (ctx.c / lvl - 1) * 100


@feature("pdh_dist_pct", "levels", 1, ["minutes.high", "minutes.close"], "2d", "Close vs the previous analysis day's high, %.")
def pdh(ctx):
    return _d(ctx, ctx.prev_days(ctx.h, "max"))


@feature("pdl_dist_pct", "levels", 1, ["minutes.low", "minutes.close"], "2d", "Close vs the previous analysis day's low, %.")
def pdl(ctx):
    return _d(ctx, ctx.prev_days(ctx.l, "min"))


@feature("pdc_dist_pct", "levels", 1, ["minutes.close"], "2d", "Close vs the previous analysis day's last close, %.")
def pdc(ctx):
    return _d(ctx, ctx.prev_days(ctx.c, "last"))


def _prev_week(ctx, a, how):
    """how() over the previous ISO week (Mon-Sun of the analysis day), known from the next Monday on. NaN unless that
    week has all 7 days at >= 90% coverage."""
    def make():
        dates = pd.DatetimeIndex(ctx.days)
        s = pd.Series(np.asarray(a, float))
        per = getattr(s.groupby(ctx.dayid), how)()
        cnt = pd.Series(np.isfinite(ctx.c)).groupby(ctx.dayid).sum()
        wk = dates.to_period("W-SUN")
        ok_day = (cnt.values >= 0.9 * 1440)
        dfw = pd.DataFrame({"wk": wk, "v": per.values, "ok": ok_day})
        g = dfw.groupby("wk")
        wv = getattr(g.v, how)()
        full = g.ok.sum() == 7
        wv[~full] = np.nan
        prevw = (wk - 1)
        return pd.Series(wv).reindex(prevw).to_numpy()[ctx.dayid]
    return ctx.memo(("pw", how, id(a)), make)


@feature("pwh_dist_pct", "levels", 1, ["minutes.high", "minutes.close"], "14d", "Close vs the previous ISO week's high, %.")
def pwh(ctx):
    return _d(ctx, _prev_week(ctx, ctx.h, "max"))


@feature("pwl_dist_pct", "levels", 1, ["minutes.low", "minutes.close"], "14d", "Close vs the previous ISO week's low, %.")
def pwl(ctx):
    return _d(ctx, _prev_week(ctx, ctx.l, "min"))


def _round(ctx, step):
    with np.errstate(invalid="ignore"):
        near = np.round(ctx.c / step) * step
    return _d(ctx, near)


@feature("round_small_dist_pct", "levels", 1, ["minutes.close"], "1m",
         "Close vs the nearest small round number (BTC 1,000; ETH 100; SOL/BNB 10; XRP 0.1), %.")
def round_small(ctx):
    return _round(ctx, ctx.spec.round_small) if ctx.spec else np.full(ctx.n, np.nan)


@feature("round_big_dist_pct", "levels", 1, ["minutes.close"], "1m",
         "Close vs the nearest big round number (BTC 5,000; ETH 500; SOL/BNB 50; XRP 0.5), %.")
def round_big(ctx):
    return _round(ctx, ctx.spec.round_big) if ctx.spec else np.full(ctx.n, np.nan)


@feature("day_high_dist_pct", "levels", 1, ["minutes.high", "minutes.close"], "day", "Close vs today's high so far, %.")
def day_high(ctx):
    return _d(ctx, ctx.day_cum(ctx.h, "max"))


@feature("day_low_dist_pct", "levels", 1, ["minutes.low", "minutes.close"], "day", "Close vs today's low so far, %.")
def day_low(ctx):
    return _d(ctx, ctx.day_cum(ctx.l, "min"))
