"""Calendar: clock-only fields (hour, weekday, weekend, the Asia / Europe / US business hours). Point-in-time by
construction: they read nothing but the bar's own timestamp."""
from __future__ import annotations

import numpy as np
import pandas as pd

from ...markets import region_flags, window_flags
from .. import config
from .registry import feature


@feature("hour_utc", "calendar", 1, ["minutes.ts"], "1m", "UTC hour of the bar, 0-23.")
def hour(ctx):
    return ctx.ts.hour.to_numpy(float)


@feature("minute_of_day", "calendar", 1, ["minutes.ts"], "1m", "Minutes since the analysis day began (0-1439).")
def mod(ctx):
    start = pd.DatetimeIndex(ctx.aday.astype("datetime64[ns]")) - pd.Timedelta(minutes=ctx.market.day_offset_min)
    return ((ctx.ts - start) / pd.Timedelta(minutes=1)).to_numpy(float)


@feature("dow", "calendar", 1, ["minutes.ts"], "1m", "Day of week of the analysis day, Monday = 0 .. Sunday = 6.")
def dow(ctx):
    return pd.DatetimeIndex(ctx.aday.astype("datetime64[ns]")).weekday.to_numpy(float)


@feature("is_weekend", "calendar", 1, ["minutes.ts"], "1m", "1 on Saturday and Sunday (analysis day), else 0.")
def weekend(ctx):
    return (dow(ctx) >= 5).astype(float)


def _reg(ctx):
    return ctx.memo("reg", lambda: region_flags(ctx.ts))


@feature("in_asia", "calendar", 1, ["minutes.ts"], "1m", "1 inside Tokyo business hours (09:00-17:00 local, weekdays).")
def asia(ctx):
    return _reg(ctx)["asia"].astype(float)


@feature("in_europe", "calendar", 1, ["minutes.ts"], "1m", "1 inside London business hours (08:00-16:30 local, weekdays).")
def europe(ctx):
    return _reg(ctx)["europe"].astype(float)


@feature("in_us", "calendar", 1, ["minutes.ts"], "1m", "1 inside New York cash-equity hours (09:30-16:00 local, weekdays).")
def us(ctx):
    return _reg(ctx)["us"].astype(float)


def _win(ctx):
    return ctx.memo("win", lambda: window_flags(ctx.ts, config.WINDOW))


@feature("in_boss_window", "calendar", 1, ["minutes.ts"], "1m",
         "1 inside Boss's crypto window, 00:00-09:00 IST (18:30-03:30 UTC), every day.")
def in_window(ctx):
    return _win(ctx)[0].astype(float)


@feature("min_to_window_end", "calendar", 1, ["minutes.ts"], "1m",
         "Minutes from this bar's end to 09:00 IST, the end of Boss's crypto window (NaN outside it).")
def to_window_end(ctx):
    return _win(ctx)[1]
