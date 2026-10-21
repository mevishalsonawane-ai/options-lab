"""Profile family: the volume profile ("vertical volume") of the near-month future, research/brain/profile.py.

Intraday values come only from the DEVELOPING profile (bars <= t). A session's final profile is used only from the next
session on (it is valid after the close). Precision "estimate" (1-minute bars); NaN where the source has no volume.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import profile
from .volume import VFUT
from .registry import feature


def _dev(ctx):
    return profile.day_profiles(ctx)["dev"]


@feature("poc_dist_pct", "profile", 1, VFUT, "day",
         "Futures close vs the developing session profile's POC (the price with the most volume so far), %.")
def poc_dist(ctx):
    return (ctx.fut()["c"] / _dev(ctx)["poc"] - 1) * 100


@feature("vah_dist_pct", "profile", 1, VFUT, "day", "Futures close vs the developing value-area high (70%), %.")
def vah_dist(ctx):
    return (ctx.fut()["c"] / _dev(ctx)["vah"] - 1) * 100


@feature("val_dist_pct", "profile", 1, VFUT, "day", "Futures close vs the developing value-area low (70%), %.")
def val_dist(ctx):
    return (ctx.fut()["c"] / _dev(ctx)["val"] - 1) * 100


@feature("va_state", "profile", 1, VFUT, "day",
         "+1 futures close above the developing value area, 0 inside it, -1 below it.")
def va_state(ctx):
    c, d = ctx.fut()["c"], _dev(ctx)
    with np.errstate(invalid="ignore"):
        s = np.where(c > d["vah"], 1.0, np.where(c < d["val"], -1.0, 0.0))
    return np.where(np.isfinite(c) & np.isfinite(d["poc"]), s, np.nan)


@feature("va_width_pct", "profile", 1, VFUT, "day", "Width of the developing value area (VAH - VAL), % of its POC.")
def va_width(ctx):
    d = _dev(ctx)
    return (d["vah"] - d["val"]) / d["poc"] * 100


@feature("week_poc_dist_pct", "profile", 1, VFUT + ["minutes.fut_expiry"], "5d",
         "Futures close vs the POC of the developing week profile (this ISO week's earlier sessions on the same "
         "contract + today so far), %.")
def week_poc(ctx):
    return (ctx.fut()["c"] / _dev(ctx)["week_poc"] - 1) * 100


@feature("prev_poc_dist_pct", "profile", 1, VFUT + ["minutes.fut_expiry"], "1d",
         "Futures close vs the previous session's final POC, %. NaN on the roll day (that POC is on another "
         "contract).")
def prev_poc(ctx):
    P = profile.day_profiles(ctx)
    poc = pd.Series([x["poc"] if x is not None else np.nan for x in P["final"]])
    exp = P["exp"]
    same = np.r_[False, exp[1:] == exp[:-1]]
    pp = ctx.prev_session(poc)
    return np.where(same[ctx.dayid], (ctx.fut()["c"] / pp - 1) * 100, np.nan)
