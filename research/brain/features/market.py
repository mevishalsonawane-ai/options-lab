"""Market family: India VIX and the futures basis."""
from __future__ import annotations

import numpy as np

from .registry import feature


def _vix(ctx):
    v = ctx.m.vix.values.astype(float)
    return np.where(ctx.m.vix_flag.isin(["ok", "stale"]).values, v, np.nan)


@feature("vix", "market", 1, ["minutes.vix"], "0m", "India VIX at this minute.")
def vix(ctx):
    return _vix(ctx)


@feature("vix_chg_15m_pct", "market", 1, ["minutes.vix"], "15m", "India VIX change over the last 15 minutes, %.")
def vix15(ctx):
    v = _vix(ctx)
    return (v / ctx.shift_in_day(v, 15) - 1) * 100


@feature("vix_chg_day_pct", "market", 1, ["minutes.vix"], "1d", "India VIX now vs the previous session's last VIX, %.")
def vixday(ctx):
    v = _vix(ctx)
    return (v / ctx.prev_session(ctx.day_complete_agg(v, "last")) - 1) * 100


def _basis(ctx):
    return ctx.m.basis_pct.values.astype(float)


@feature("basis_pct", "market", 1, ["minutes.basis_pct"], "0m",
         "Near-month future minus index, % of the index (both bars ok this minute; NaN otherwise). Jumps at the roll.")
def basis(ctx):
    return _basis(ctx)


@feature("basis_chg_day", "market", 1, ["minutes.basis_pct"], "day",
         "basis_pct now minus the session's first basis_pct, percentage points.")
def basis_day(ctx):
    b = _basis(ctx)
    return b - ctx.day_first(b)
