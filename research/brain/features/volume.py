"""Volume family: the near-month future's volume and VWAP built from it.

The index has no volume, so the near-month future stands for it. The adapters switch to the next month on the roll day
(the near month's expiry day), so a session never mixes contracts. All values here come from 1-minute bars, so their
precision is "estimate" (the feature store's `precision` column; "exact" is reserved for values rebuilt from recorded
ticks). Where the source has no futures volume (the research-data release), every feature here is NaN: no volume is
never treated as zero volume.

VWAP uses the typical price (H+L+C)/3 of each futures bar. Its bands use the volume-weighted standard deviation of the
typical price around the VWAP, over the same rows.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config
from .registry import feature

FUT = ["minutes.fut_open", "minutes.fut_high", "minutes.fut_low", "minutes.fut_close", "minutes.fut_volume"]
VFUT = FUT + ["minutes.fut_flag"]
REL_SESSIONS = 20
REL_MIN = 15            # same-minute volume needs at least this many of the last 20 sessions


def _tp(f):
    return (f["h"] + f["l"] + f["c"]) / 3


def _cum(ctx, a):
    return ctx.groupby_day(a).cumsum().values


def _vwap(ctx):
    """Session VWAP (= the VWAP anchored at the day open) and its volume-weighted std, per row."""
    def make():
        f = ctx.fut()
        tp, v = _tp(f), f["v"]
        ok = np.isfinite(tp) & np.isfinite(v) & (v > 0)
        ref = ctx.day_first(np.where(ok, tp, np.nan))       # centre on the day's first price: no cancellation in var
        x = np.where(ok, tp - ref, 0.0)
        w = np.where(ok, v, 0.0)
        cv, cx, cx2 = _cum(ctx, w), _cum(ctx, w * x), _cum(ctx, w * x * x)
        with np.errstate(invalid="ignore", divide="ignore"):
            mx = np.where(cv > 0, cx / cv, np.nan)
            var = np.where(cv > 0, cx2 / cv - mx * mx, np.nan)
        return mx + ref, np.sqrt(np.clip(var, 0, None))
    return ctx.memo("vwap", make)


@feature("fut_volume", "volume", 1, VFUT, "0m",
         "Near-month future's volume this minute (contracts as the source reports). NaN where the source has none.")
def fut_volume(ctx):
    return ctx.fut()["v"]


@feature("fut_volume_day", "volume", 1, VFUT, "day",
         "Near-month future's volume so far this session (sum of the minutes that have volume; NaN before the first).")
def fut_volume_day(ctx):
    v = ctx.fut()["v"]
    seen = ctx.groupby_day(np.isfinite(v).astype(float)).cummax().values > 0
    return np.where(seen, _cum(ctx, np.nan_to_num(v)), np.nan)


@feature("rel_volume_20d", "volume", 1, VFUT, f"{REL_SESSIONS}d",
         f"This minute's futures volume / the mean volume of the same minute over the previous {REL_SESSIONS} sessions "
         f"(needs >= {REL_MIN} of them with volume).")
def rel_volume(ctx):
    v = ctx.fut()["v"]
    nd = len(ctx.days)
    V = np.full((nd, config.SESSION_MINUTES), np.nan)
    V[ctx.dayid, ctx.minute] = v
    base = pd.DataFrame(V).rolling(REL_SESSIONS, min_periods=REL_MIN).mean().shift(1).values
    b = base[ctx.dayid, ctx.minute]
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(b > 0, v / b, np.nan)


@feature("vwap", "volume", 1, VFUT, "day",
         "Session VWAP of the near-month future (typical price, from the day open: this is also the VWAP anchored at "
         "the open), in futures points.")
def vwap(ctx):
    return _vwap(ctx)[0]


@feature("vwap_dist_pct", "volume", 1, VFUT, "day", "Futures close vs the session VWAP, %.")
def vwap_dist(ctx):
    return (ctx.fut()["c"] / _vwap(ctx)[0] - 1) * 100


@feature("vwap_sd_pct", "volume", 1, VFUT, "day",
         "Volume-weighted standard deviation of the typical price around the VWAP (one band width), % of the VWAP.")
def vwap_sd(ctx):
    vw, sd = _vwap(ctx)
    return sd / vw * 100


@feature("vwap_z", "volume", 1, VFUT, "day",
         "Futures close minus VWAP in band units (σ): +1 / +2 are the +1σ / +2σ bands. NaN while σ is 0.")
def vwap_z(ctx):
    vw, sd = _vwap(ctx)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(sd > 0, (ctx.fut()["c"] - vw) / sd, np.nan)


@feature("vwap_band", "volume", 1, VFUT, "day",
         "Which VWAP band the futures close is in: +2 above +2σ, +1 between +1σ and +2σ, 0 within ±1σ, -1, -2.")
def vwap_band(ctx):
    z = vwap_z(ctx)
    with np.errstate(invalid="ignore"):
        b = np.where(z > 2, 2.0, np.where(z > 1, 1.0, np.where(z >= -1, 0.0, np.where(z >= -2, -1.0, -2.0))))
    return np.where(np.isfinite(z), b, np.nan)


def _band_feat(k):
    for sgn, nm in ((1, "up"), (-1, "dn")):
        @feature(f"vwap_{nm}{k}_dist_pct", "volume", 1, VFUT, "day",
                 f"Futures close vs the VWAP {'+' if sgn > 0 else '-'}{k}σ band, % (negative = below the band).")
        def f(ctx, sgn=sgn):
            vw, sd = _vwap(ctx)
            return (ctx.fut()["c"] / (vw + sgn * k * sd) - 1) * 100


for _k in (1, 2):
    _band_feat(_k)


def _avwap_prev(ctx, how):
    """VWAP anchored at the bar of the previous session's futures high (how="max") or low ("min"), running through
    the rest of that session and today. Only the previous session's complete data and today's rows <= t are used.
    NaN when the contract changed between the two sessions (roll), since the anchor is on another contract."""
    def make():
        f = ctx.fut()
        tp, v = _tp(f), f["v"]
        ok = np.isfinite(tp) & np.isfinite(v) & (v > 0)
        w, pv = np.where(ok, v, 0.0), np.where(ok, v * tp, 0.0)
        nd = len(ctx.days)
        tail_v, tail_pv = np.full(nd, np.nan), np.full(nd, np.nan)
        ext = f["h"] if how == "max" else f["l"]
        for d in range(nd):
            r = np.flatnonzero(ctx.dayid == d)
            e = ext[r]
            fin = np.isfinite(e) & ok[r]
            if not fin.any():
                continue
            cand = np.where(fin, e, -np.inf if how == "max" else np.inf)
            j = int(np.argmax(cand) if how == "max" else np.argmin(cand))       # the first bar at the extreme
            tail_v[d], tail_pv[d] = w[r[j]:r[-1] + 1].sum(), pv[r[j]:r[-1] + 1].sum()
        exp = pd.Series(pd.to_datetime(ctx.m.fut_expiry)).groupby(ctx.dayid).first() if "fut_expiry" in ctx.m \
            else pd.Series(pd.NaT, index=range(nd))
        e = exp.reindex(range(nd)).astype("datetime64[ns]").values.astype("int64")
        same = np.r_[False, e[1:] == e[:-1]]           # NaT == NaT counts as the same continuous series
        pv_prev = ctx.prev_session(pd.Series(tail_pv))
        v_prev = ctx.prev_session(pd.Series(tail_v))
        cv, cpv = _cum(ctx, w), _cum(ctx, pv)
        with np.errstate(invalid="ignore", divide="ignore"):
            out = (pv_prev + cpv) / (v_prev + cv)
        return np.where(same[ctx.dayid] & (v_prev + cv > 0), out, np.nan)
    return ctx.memo(("avwap_prev", how), make)


@feature("avwap_prev_high_dist_pct", "volume", 1, VFUT + ["minutes.fut_expiry"], "1d",
         "Futures close vs the VWAP anchored at the previous session's futures high (through that session and today "
         "so far), %. NaN on the roll day.")
def avwap_ph(ctx):
    return (ctx.fut()["c"] / _avwap_prev(ctx, "max") - 1) * 100


@feature("avwap_prev_low_dist_pct", "volume", 1, VFUT + ["minutes.fut_expiry"], "1d",
         "Futures close vs the VWAP anchored at the previous session's futures low, %. NaN on the roll day.")
def avwap_pl(ctx):
    return (ctx.fut()["c"] / _avwap_prev(ctx, "min") - 1) * 100
