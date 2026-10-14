"""Options market: Deribit option trades by hour (cube v3 opt_* columns, adapters.option_hours). BTC and ETH only;
NaN for the other coins (14 Oct 2026, F1 refresh). DVOL itself is in the vol family.
"""
from __future__ import annotations

import numpy as np

from .derivs import _z
from .registry import feature


@feature("opt_atm_iv", "options", 1, ["minutes.opt_atm_iv"], "180m",
         "Median implied vol of at-the-money Deribit option trades (strike within 2% of the index, 1-30 days) in the "
         "last full UTC hour, vol points.")
def atm_iv(ctx):
    return ctx.col("opt_atm_iv")


@feature("opt_atm_iv_minus_rv", "options", 1, ["minutes.opt_atm_iv", "minutes.close"], "1440m",
         "At-the-money option IV minus the realised vol of the last 24 hours (annualised), vol points: > 0 = options "
         "price more movement than the market has shown.")
def iv_rv(ctx):
    from .price import rv_1440m
    return ctx.col("opt_atm_iv") - rv_1440m(ctx) * np.sqrt(365.0)


@feature("opt_skew", "options", 1, ["minutes.opt_skew"], "360m",
         "Put-call skew: median IV of out-of-the-money puts (strike 85-97% of the index) minus calls (103-115%), "
         "3-45 days, over the last 4 hours, vol points. > 0 = downside protection is dearer.")
def skew(ctx):
    return ctx.col("opt_skew")


@feature("opt_skew_chg_24h", "options", 1, ["minutes.opt_skew"], "1800m", "Change of the put-call skew over 24 hours, vol points.")
def skew_chg(ctx):
    s = ctx.col("opt_skew")
    return s - ctx.shift(s, 1440)


@feature("opt_pc_24h", "options", 1, ["minutes.opt_pc_24h"], "1620m",
         "Put / call traded amount (coins) on Deribit over the last 24 hours, all expiries.")
def pc(ctx):
    return ctx.col("opt_pc_24h")


@feature("opt_pc_z_7d", "options", 1, ["minutes.opt_pc_24h"], "8d",
         "z-score of the 24-hour put/call ratio against the last 7 days of minutes.")
def pc_z(ctx):
    return _z(ctx, ctx.col("opt_pc_24h"), 7 * 1440, 0.7)


@feature("opt_flow_24h", "options", 1, ["minutes.opt_flow_24h"], "1620m",
         "Option taker flow over 24 hours: (call buys - call sells - put buys + put sells) / total amount, -1 .. +1 "
         "(> 0 = takers leaning bullish).")
def flow(ctx):
    return ctx.col("opt_flow_24h")
