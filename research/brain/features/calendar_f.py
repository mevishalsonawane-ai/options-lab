"""Calendar family: time of day, weekday, expiry, scheduled events, holiday gaps."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .registry import feature


@feature("minute_of_day", "calendar", 1, ["minutes.minute"], "0m", "Minutes since 09:15 (0 = the 09:15 bar).")
def minute_of_day(ctx):
    return ctx.minute.astype(float)


@feature("day_of_week", "calendar", 1, ["minutes.day"], "0m", "Weekday, 0 = Monday.")
def dow(ctx):
    return pd.DatetimeIndex(ctx.m.day).dayofweek.values.astype(float)


@feature("dte_days", "calendar", 1, ["minutes.dte_days"], "0m",
         "Calendar days to the chain's (nearest) expiry; 0 on expiry day.")
def dte(ctx):
    return ctx.m.dte_days.values.astype(float)


@feature("is_expiry_day", "calendar", 1, ["minutes.is_expiry_day"], "0m",
         "1 on an expiry day of this underlying (scheduled; from contracts in the data or the NSE weekday rule).")
def is_exp(ctx):
    return ctx.m.is_expiry_day.values.astype(float)


@feature("is_event_day", "calendar", 1, ["minutes.events"], "0m",
         "1 if calendar_data/events.csv lists a scheduled event today (budget, RBI policy, ...).")
def is_event(ctx):
    return (ctx.m.events.fillna("").values != "").astype(float)


@feature("days_since_prev_session", "calendar", 1, ["minutes.day"], "1d",
         "Calendar days since the previous session (1 normal, 3 after a weekend, more after holidays).")
def since_prev(ctx):
    d = pd.Series(pd.to_datetime(ctx.days))
    gap = d.diff().dt.days.values.astype(float)
    return gap[ctx.dayid]
