"""Gamma exposure (GEX) features, options family (R28, 14 Oct 2026; research/hunt/r28/PREREG.md).

From the cube's chain (nearest expiry, ATM-10..ATM+10): each contract's last real trade (`ltp`, at most 30 minutes old,
`ltp_age`), its last reported OI (units) and the index close, through research/brain/gex.py (the same core the R28 study
runs on the lake's WEEK + MONTH files). The cube has the nearest expiry only, so these are "near" GEX; the far-OTM strikes
beyond ATM+-10 are missing (gex_edge_share says how much of the stored gamma sits at the edge).

Sign conventions: S1 = dealers long calls / short puts (calls +, puts -); S2 = minus customers' net buying since the open
(OI change signed by the price change); S3 (writers short everything) = -gex_gross_cr.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import gex
from .registry import feature

INPUTS = ["chain.ltp", "chain.ltp_age", "chain.oi", "chain.strike", "minutes.close", "minutes.expiry"]
NORM_DAYS = 20


def _per_day(ctx) -> dict:
    """gex.session for every session in the context, as arrays aligned to the context rows."""
    def make():
        out = {c: np.full(ctx.n, np.nan) for c in gex.OUT_COLS}
        ch = ctx.chain
        if ch.empty:
            return out
        ch = ch.assign(day=pd.DatetimeIndex(ch.ts).normalize())
        days = pd.DatetimeIndex(ctx.ts).normalize()
        for dd, g in ch.groupby("day", sort=True):
            rows = np.flatnonzero(days == dd)
            if not len(rows):
                continue
            mins = ctx.minute[rows]
            pos = pd.Series(np.arange(len(rows)), index=ctx.ts[rows])
            g = g[g.ts.isin(pos.index)]
            minute = ctx.minute[rows[pos.reindex(pd.DatetimeIndex(g.ts)).values.astype(int)]]
            df = pd.DataFrame(dict(minute=minute, offset=g.offset.values.astype(int), side=g.side.values,
                                   strike=g.strike.values.astype(float), px=g.ltp.values.astype(float),
                                   age=g.ltp_age.values.astype(float), oi=g.oi.values.astype(float)))
            df["n"] = gex.contract_flow(df)
            spot = np.full(gex.config.SESSION_MINUTES, np.nan)
            spot[mins] = ctx.c[rows]
            exp = pd.to_datetime(ctx.m.expiry.values[rows[0]])
            r = gex.session(df, spot, dd.date(), None if pd.isna(exp) else exp.date())
            for c in gex.OUT_COLS:
                out[c][rows] = r[c].values[mins]
        return out
    return ctx.memo("gex_day", make)


def _col(ctx, c):
    return _per_day(ctx)[c]


@feature("gex_net_cr", "options", 1, INPUTS, "30m",
         "Net dealer gamma exposure S1 (dealers long calls, short puts), Rs crore of index delta per 1% move: sum over "
         "the nearest expiry's ATM+-10 of gamma x OI x spot^2 x 0.01, calls +, puts - (Black-Scholes IV from the last "
         "trade <= 30 min old; gex.py).")
def gex_net(ctx):
    return _col(ctx, "net_cr")


@feature("gex_gross_cr", "options", 1, INPUTS, "30m",
         "Gross gamma exposure (calls + puts), Rs crore per 1% move; minus this is S3 'writers short everything'.")
def gex_gross(ctx):
    return _col(ctx, "gross_cr")


@feature("gex_s2_cr", "options", 1, INPUTS, "day",
         "GEX under S2 (customer net buying): minus sum of gamma x customers' net long units since the open, each "
         "contract's OI change signed by its price change; Rs crore per 1% move. 0 at the open by construction.")
def gex_s2(ctx):
    return _col(ctx, "s2_cr")


@feature("gex_ratio", "options", 1, INPUTS, "30m", "gex_net_cr / gex_gross_cr, in [-1, 1] (scale-free S1 sign).")
def gex_ratio(ctx):
    return _col(ctx, "ratio")


@feature("gex_norm_20d", "options", 1, INPUTS, f"{NORM_DAYS}d",
         "gex_net_cr / the median |gex_net_cr| at the same minute over the previous 20 sessions (at least 10).")
def gex_norm(ctx):
    net = _col(ctx, "net_cr")
    nd = len(ctx.days)
    a = np.full((nd, gex.config.SESSION_MINUTES), np.nan)
    a[ctx.dayid, ctx.minute] = net
    r = gex.rolling_norm(a, NORM_DAYS, 10)
    return r[ctx.dayid, ctx.minute]


@feature("gex_flip_dist_pct", "options", 1, INPUTS, "30m",
         "Zero-gamma flip vs spot, %: S1 recomputed with spot moved over +-5% (IV, T held), the zero crossing nearest "
         "spot; NaN with no crossing within +-5%.")
def gex_flip(ctx):
    return _col(ctx, "flip_dist_pct")


@feature("gex_spot_above_flip", "options", 1, INPUTS, "30m",
         "1 when spot is above the zero-gamma flip (positive-gamma side under S1), 0 below, NaN with no flip.")
def gex_above(ctx):
    return _col(ctx, "spot_above_flip")


def _dist(ctx, c):
    with np.errstate(invalid="ignore", divide="ignore"):
        return (_col(ctx, c) / ctx.c - 1) * 100


@feature("gex_wall_pos_dist_pct", "options", 1, INPUTS, "30m",
         "Positive gamma wall: the strike with the largest per-strike S1 (call gamma dominant) vs spot, %.")
def gex_wall_pos(ctx):
    return _dist(ctx, "wall_pos_strike")


@feature("gex_wall_neg_dist_pct", "options", 1, INPUTS, "30m",
         "Negative gamma wall: the strike with the most negative per-strike S1 (put gamma dominant) vs spot, %.")
def gex_wall_neg(ctx):
    return _dist(ctx, "wall_neg_strike")


@feature("gex_wall_abs_dist_pct", "options", 1, INPUTS, "30m",
         "The strike with the largest gross (call + put) gamma exposure vs spot, % (the expiry 'pin' candidate).")
def gex_wall_abs(ctx):
    return _dist(ctx, "wall_abs_strike")


@feature("gex_edge_share", "options", 1, INPUTS, "30m",
         "Coverage check: share of the stored gross GEX at the edge strikes (ATM+-9, +-10). High = much gamma likely "
         "lies beyond the stored ATM+-10.")
def gex_edge(ctx):
    return _col(ctx, "edge_share")


@feature("oi_edge_share", "options", 1, ["chain.oi"], "0m",
         "Coverage check: share of the stored OI (calls + puts) at the edge strikes ATM+-9, +-10.")
def oi_edge(ctx):
    return _col(ctx, "oi_edge_share")
