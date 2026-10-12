"""Levels family: distance to the nearest support / resistance level, research/brain/levels.py (the L3 `levels` table).

Only levels whose valid_from is at or before the minute count. A level's strength is the share of tests it HELD on the
previous 60 sessions (levels of the same kind; never the current session), NaN with fewer than 3 tests.
"""
from __future__ import annotations

from .. import levels
from .registry import feature

INP = ["minutes.open", "minutes.high", "minutes.low", "minutes.close", "minutes.basis", "minutes.expiry",
       "minutes.oi_max_call_strike", "minutes.oi_max_put_strike", "minutes.fut_volume"]
LB = f"{levels.LOOKBACK_SESSIONS}d"
CODES = ", ".join(f"{c} {t}" for t, c in levels.CODE.items())


@feature("lvl_above_dist_pct", "levels", 1, INP, LB, "Distance from the close up to the nearest level above, %.")
def above(ctx):
    return levels.nearest(ctx)["up"]


@feature("lvl_below_dist_pct", "levels", 1, INP, LB, "Distance from the close down to the nearest level below, %.")
def below(ctx):
    return levels.nearest(ctx)["dn"]


@feature("lvl_above_type", "levels", 1, INP, LB,
         f"Type code of the nearest level above (at a tie, the lowest code): {CODES}.")
def above_type(ctx):
    return levels.nearest(ctx)["up_code"]


@feature("lvl_below_type", "levels", 1, INP, LB, "Type code of the nearest level below (codes as lvl_above_type).")
def below_type(ctx):
    return levels.nearest(ctx)["dn_code"]


@feature("lvl_above_strength", "levels", 1, INP, LB,
         "Share of tests the nearest level above held on the previous 60 sessions (same type, or same type and price "
         "for swings and round numbers); NaN with < 3 tests.")
def above_str(ctx):
    return levels.nearest(ctx)["up_str"]


@feature("lvl_below_strength", "levels", 1, INP, LB, "As lvl_above_strength, for the nearest level below.")
def below_str(ctx):
    return levels.nearest(ctx)["dn_str"]


@feature("lvl_near_count", "levels", 1, INP, LB, "Number of valid levels within 0.2% of the close (a confluence count).")
def near(ctx):
    return levels.nearest(ctx)["near"]
