"""Trend: EMA ribbons on the spot close (14 Oct 2026, F1 refresh).

A ribbon is five EMAs of spans 8, 13, 21, 34 and 55 bars. On the 1-minute grid a "15-minute" ribbon uses spans of
8x15 ... 55x15 minutes and a "1-hour" ribbon 8x60 ... 55x60 minutes (the same smoothing as those EMAs on 15-minute or
hourly bars, updated every minute, so nothing waits for a bar to close).

Every EMA here is TRUNCATED: weights alpha (1 - alpha)^k for k < 5 x span, renormalised over the finite closes in that
window. So a value depends on a fixed, declared lookback only (warm-up independence holds exactly) and a gap minute
simply drops out of the average instead of breaking it.
"""
from __future__ import annotations

import numpy as np
from scipy.signal import lfilter

from .registry import feature

SPANS = (8, 13, 21, 34, 55)
TRUNC = 5                                   # window = 5 x span minutes; the weight left beyond it is e^-10 ~ 4.5e-5


def tema(x: np.ndarray, span: int) -> np.ndarray:
    """Truncated EMA of x (span in minutes), NaN-aware, causal: value at t uses x[t-L+1 .. t], L = TRUNC x span.
    S_t = E_t - (1 - a)^L E_{t-L} for the plain recursion E (same for the weight sum), then S / W."""
    x = np.asarray(x, float)
    a = 2.0 / (span + 1)
    L = TRUNC * span
    fin = np.isfinite(x)
    num = lfilter([a], [1, -(1 - a)], np.where(fin, x, 0.0))
    den = lfilter([a], [1, -(1 - a)], fin.astype(float))
    q = (1 - a) ** L
    n_ = num.copy()
    d_ = den.copy()
    if len(x) > L:
        n_[L:] -= q * num[:-L]
        d_[L:] -= q * den[:-L]
    out = np.full(len(x), np.nan)
    ok = np.arange(len(x)) >= L - 1                       # a full window behind it
    # at least half the window's weight must come from finite closes
    good = ok & (d_ > 0.5 * (1 - q))
    out[good] = n_[good] / d_[good]
    return out


def _ribbon(ctx, mult: int) -> list[np.ndarray]:
    return ctx.memo(("ribbon", mult), lambda: [tema(ctx.c, s * mult) for s in SPANS])


def _order(ctx, mult):
    r = _ribbon(ctx, mult)
    with np.errstate(invalid="ignore"):
        s = sum(np.sign(r[i] - r[i + 1]) for i in range(len(r) - 1)) / (len(r) - 1)
    ok = np.all([np.isfinite(e) for e in r], axis=0)
    return np.where(ok, s, np.nan)


def _width(ctx, mult):
    r = np.vstack(_ribbon(ctx, mult))
    with np.errstate(invalid="ignore", divide="ignore"):
        return (np.max(r, axis=0) - np.min(r, axis=0)) / ctx.c * 100


def _dist(ctx, mult, i=2):
    with np.errstate(invalid="ignore", divide="ignore"):
        return (ctx.c / _ribbon(ctx, mult)[i] - 1) * 100


LB15 = f"{TRUNC * 55 * 15}m"
LB60 = f"{TRUNC * 55 * 60}m"


@feature("ema_rib15_order", "trend", 1, ["minutes.close"], LB15,
         "15-minute EMA ribbon (8/13/21/34/55 x 15 min) order: +1 every faster EMA above the next slower one (up "
         "trend), -1 all below, between = mixed.")
def rib15_order(ctx):
    return _order(ctx, 15)


@feature("ema_rib15_width_pct", "trend", 1, ["minutes.close"], LB15,
         "15-minute EMA ribbon width: highest minus lowest of the five EMAs, % of the close (narrow = squeeze).")
def rib15_width(ctx):
    return _width(ctx, 15)


@feature("ema_rib15_dist_pct", "trend", 1, ["minutes.close"], LB15,
         "Close vs the middle (21 x 15 min) EMA of the 15-minute ribbon, %.")
def rib15_dist(ctx):
    return _dist(ctx, 15)


@feature("ema_rib15_slope_pct", "trend", 1, ["minutes.close"], f"{TRUNC * 55 * 15 + 60}m",
         "Change of the middle (21 x 15 min) EMA over the last 60 minutes, %.")
def rib15_slope(ctx):
    e = _ribbon(ctx, 15)[2]
    with np.errstate(invalid="ignore", divide="ignore"):
        return (e / ctx.shift(e, 60) - 1) * 100


@feature("ema_rib1h_order", "trend", 1, ["minutes.close"], LB60,
         "1-hour EMA ribbon (8/13/21/34/55 x 60 min) order, -1 .. +1 as the 15-minute one.")
def rib1h_order(ctx):
    return _order(ctx, 60)


@feature("ema_rib1h_width_pct", "trend", 1, ["minutes.close"], LB60,
         "1-hour EMA ribbon width, % of the close.")
def rib1h_width(ctx):
    return _width(ctx, 60)


@feature("ema_rib1h_dist_pct", "trend", 1, ["minutes.close"], LB60,
         "Close vs the middle (21 x 60 min) EMA of the 1-hour ribbon, %.")
def rib1h_dist(ctx):
    return _dist(ctx, 60)
