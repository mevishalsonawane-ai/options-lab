"""Structure family: opening range, the app's Liquidity levels, previous-session shape.

Liquidity levels are the ones the app's Liquidity BANKNIFTY arm trades (research/liquidity_break.py,
indicator/liquidity.py): Liquidity Swings (pivot length 20, full-range zones) + Liquidity Pools (2 contacts, 5 bars
apart, 10 confirmation bars), on 5-minute bars of the index, continuous across sessions like a TradingView chart.
Point in time: a 5-minute bar is used only from its last minute's row on; a zone counts from its confirmation bar
("known") and is active until a close takes it, judged only on bars already complete.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

from .registry import feature

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

OHLC = ["minutes.open", "minutes.high", "minutes.low", "minutes.close"]
TF = 5                  # the app's 5-minute book
MAX_AGE_BARS = 750      # zones older than 10 sessions of 5-minute bars are dropped (bounded history, reproducible)


def _or(ctx, mins):
    """Opening range over the first `mins` minutes; known from row mins-1 on (that bar's close)."""
    def make():
        first = ctx.minute < mins
        h = np.where(first, ctx.h, np.nan)
        l = np.where(first, ctx.l, np.nan)
        hi = pd.Series(ctx.groupby_day(h).cummax().values).groupby(ctx.dayid).ffill().values
        lo = pd.Series(ctx.groupby_day(l).cummin().values).groupby(ctx.dayid).ffill().values
        known = ctx.minute >= mins - 1
        return np.where(known, hi, np.nan), np.where(known, lo, np.nan)
    return ctx.memo(("or", mins), make)


def _or_feats(mins):
    @feature(f"or{mins}_width_pct", "structure", 1, OHLC, "day",
             f"Width of the {mins}-minute opening range (09:15 + {mins} min) as % of the close; NaN until it is complete.")
    def width(ctx):
        hi, lo = _or(ctx, mins)
        return (hi - lo) / ctx.c * 100

    @feature(f"or{mins}_high_dist_pct", "structure", 1, OHLC, "day",
             f"Close vs the {mins}-minute opening-range high, %.")
    def hd(ctx):
        return (ctx.c / _or(ctx, mins)[0] - 1) * 100

    @feature(f"or{mins}_low_dist_pct", "structure", 1, OHLC, "day",
             f"Close vs the {mins}-minute opening-range low, %.")
    def ld(ctx):
        return (ctx.c / _or(ctx, mins)[1] - 1) * 100

    @feature(f"or{mins}_state", "structure", 1, OHLC, "day",
             f"+1 close above the {mins}-minute opening-range high, -1 below its low, 0 inside.")
    def st(ctx):
        hi, lo = _or(ctx, mins)
        with np.errstate(invalid="ignore"):
            s = np.where(ctx.c > hi, 1.0, np.where(ctx.c < lo, -1.0, 0.0))
        return np.where(np.isfinite(hi) & np.isfinite(ctx.c), s, np.nan)


for _m in (15, 30):
    _or_feats(_m)


# --- Liquidity levels -------------------------------------------------------------------------------------------------
def _bars5(ctx):
    """5-minute bars per session (bar k of a day covers minutes 5k..5k+4), stamped with the row index of their last
    minute (the first row at which the bar is complete)."""
    def make():
        b = pd.DataFrame(dict(day=ctx.dayid, k=ctx.minute // TF, o=ctx.o, h=ctx.h, l=ctx.l, c=ctx.c,
                              row=np.arange(ctx.n), mn=ctx.minute))
        g = b.groupby(["day", "k"], sort=True)
        bars = pd.DataFrame(dict(open=g.o.first(), high=g.h.max(), low=g.l.min(), close=g.c.last(),
                                 last_mn=g.mn.max(), last_row=g.row.max())).reset_index()
        # complete only if its final minute (5k+4) is present in the rows given; drop rows without prices
        bars = bars[(bars.last_mn == bars.k * TF + TF - 1)]
        bars = bars.dropna(subset=["open", "high", "low", "close"]).reset_index(drop=True)
        return bars
    return ctx.memo("bars5", make)


def _liq(ctx):
    """Per row: nearest active liquidity edge above / below the close, and liquidity breaks today so far."""
    def make():
        b = _bars5(ctx)
        nb = len(b)
        above = np.full(ctx.n, np.nan)
        below = np.full(ctx.n, np.nan)
        breaks = np.zeros(ctx.n)
        last_dir = np.full(ctx.n, np.nan)
        if nb < 2:
            return above, below, np.where(np.isfinite(ctx.c), breaks, np.nan), last_dir
        zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
        up_edge = np.full(nb, np.nan)
        dn_edge = np.full(nb, np.nan)
        brk = np.zeros(nb)
        bdir = np.zeros(nb)
        start = np.array([z.known for z in zones]) if zones else np.array([], int)
        end = np.array([z.broken if z.broken >= 0 else nb for z in zones]) if zones else np.array([], int)
        orig = np.array([z.origin for z in zones]) if zones else np.array([], int)
        side = np.array([z.side for z in zones]) if zones else np.array([], int)
        edge = np.array([z.edge for z in zones]) if zones else np.array([])
        for z in zones:
            if 0 <= z.broken < nb and z.broken - z.origin <= MAX_AGE_BARS:
                brk[z.broken] += 1
                bdir[z.broken] = z.side
        close = b.close.values
        for k in range(nb):
            act = (start <= k) & (end > k) & (k - orig <= MAX_AGE_BARS)
            hi = act & (side > 0)
            lo = act & (side < 0)
            if hi.any():
                up_edge[k] = edge[hi].min()
            if lo.any():
                dn_edge[k] = edge[lo].max()
        # map bars to rows: each row sees the last bar complete at or before it
        rows = b.last_row.values
        idx = np.searchsorted(rows, np.arange(ctx.n), side="right") - 1
        has = idx >= 0
        j = np.where(has, idx, 0)
        up = np.where(has, up_edge[j], np.nan)
        dn = np.where(has, dn_edge[j], np.nan)
        above = (up / ctx.c - 1) * 100
        below = (ctx.c / dn - 1) * 100
        # breaks today: cumulative count of breaking bars of the same session, as of the last complete bar
        bday = b.day.values
        cum = pd.Series(brk).groupby(bday).cumsum().values
        same = has & (bday[j] == ctx.dayid)
        breaks = np.where(same, cum[j], 0.0)
        lastd = pd.Series(np.where(bdir != 0, bdir, np.nan)).groupby(bday).ffill().values
        last_dir = np.where(same, lastd[j], np.nan)
        return above, below, np.where(np.isfinite(ctx.c), breaks, np.nan), last_dir
    return ctx.memo("liq", make)


@feature("liq_above_dist_pct", "structure", 1, OHLC, "12d",
         "Distance from the close up to the nearest active liquidity level above (swing high/pool top, 5-min, the "
         "app's settings), % of the close. NaN if none.")
def liq_above(ctx):
    return _liq(ctx)[0]


@feature("liq_below_dist_pct", "structure", 1, OHLC, "12d",
         "Distance from the close down to the nearest active liquidity level below (swing low/pool bottom), %.")
def liq_below(ctx):
    return _liq(ctx)[1]


@feature("liq_breaks_today", "structure", 1, OHLC, "12d",
         "How many liquidity levels 5-minute closes have taken so far this session.")
def liq_breaks(ctx):
    return _liq(ctx)[2]


@feature("liq_last_break_dir", "structure", 1, OHLC, "12d",
         "Direction of this session's latest liquidity break: +1 a high taken, -1 a low taken, NaN none yet.")
def liq_last(ctx):
    return _liq(ctx)[3]


@feature("prev_inside_day", "structure", 1, OHLC, "2d",
         "1 if the previous session's range was inside the range of the session before it, else 0.")
def prev_inside(ctx):
    hi = ctx.day_complete_agg(ctx.h, "max")
    lo = ctx.day_complete_agg(ctx.l, "min")
    inside = ((hi < hi.shift(1)) & (lo > lo.shift(1))).astype(float)
    inside[hi.shift(1).isna()] = np.nan
    return ctx.prev_session(inside)


@feature("prev_day_range_pct", "structure", 1, OHLC, "1d", "The previous session's high-low range, % of its close.")
def prev_range(ctx):
    hi = ctx.day_complete_agg(ctx.h, "max")
    lo = ctx.day_complete_agg(ctx.l, "min")
    c = ctx.day_complete_agg(ctx.c, "last")
    return ctx.prev_session((hi - lo) / c * 100)
