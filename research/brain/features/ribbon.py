"""EMA ribbon family (R29, 14 Oct 2026): many EMAs at once on 1/3/5/15-minute index bars, point in time.

Boss (14 Oct 2026): "Try to use multiple EMAs at once, and check the patterns." Single EMA crosses are covered by
`indicators.py` and the F1 factory; this module describes the SHAPE of a whole ribbon of EMAs:

- the Fibonacci ribbon RIB = EMA 5, 8, 13, 21, 34, 55, 89, 200 (features `rib<tf>_*`), and
- the common set CLS = EMA 9, 21, 50, 200 (features `cls<tf>_*`, stack only).

Bars are session-anchored (`bars.bars`): a bar is used from the row of its last minute on, never before. The EMAs run
continuously across sessions on the complete bars, like a chart (the overnight gap is part of the series). A bar value
is NaN until 200 bars exist (the slowest EMA needs them before it means anything).

Declared lookbacks are where an EMA-200 of that bar size has forgotten its start below float precision (~3,700 bars):
10 sessions of 1-minute bars, 30 of 3-minute, 50 of 5-minute and 150 of 15-minute bars. The store loads 252 sessions
of minutes anyway.

Every per-bar quantity is also available to research code through `ribbon_bars(ctx, tf)`, so a study and the store
read the same numbers.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .bars import bars, ema, to_rows, true_range, wilder
from .registry import feature

OHLC = ["minutes.open", "minutes.high", "minutes.low", "minutes.close"]
RIB = (5, 8, 13, 21, 34, 55, 89, 200)
CLS = (9, 21, 50, 200)
TFS = (1, 3, 5, 15)
LOOKBACK = {1: "10d", 3: "30d", 5: "50d", 15: "150d"}
WARM = 200            # bars before a ribbon value is given (the slowest EMA's span)
ATR_N = 14
CHG_BARS = 5          # width change: now vs 5 bars ago
SQZ_N, SQZ_RECENT = 50, 5    # squeeze: the narrowest width of the last 50 bars, seen within the last 5 bars


def stack_state(E: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """E: (k bars, s EMAs) ordered fastest -> slowest. Returns (stack, order):
    stack +1 every faster EMA above the next slower one (fully bullish), -1 every one below (fully bearish), else 0;
    order = (pairs in bullish order - pairs in bearish order) / (s - 1), from -1 to +1. NaN where any EMA is NaN."""
    d = E[:, :-1] - E[:, 1:]
    bad = ~np.isfinite(d).all(axis=1)
    up, dn = (d > 0).sum(axis=1), (d < 0).sum(axis=1)
    n = E.shape[1] - 1
    stack = np.where(up == n, 1.0, np.where(dn == n, -1.0, 0.0))
    order = (up - dn) / n
    return np.where(bad, np.nan, stack), np.where(bad, np.nan, order)


def last_full(stack: np.ndarray) -> np.ndarray:
    """The latest full stack seen so far (+1 / -1), carried forward through mixed bars; NaN before the first."""
    s = pd.Series(np.where(np.abs(stack) == 1, stack, np.nan))
    return s.ffill().values


def twist(stack: np.ndarray) -> np.ndarray:
    """+1 on the bar where the ribbon becomes fully bullish after its previous full stack was bearish (a full order
    flip), -1 the reverse, 0 otherwise; NaN where the stack is NaN."""
    lf = last_full(stack)
    prev = np.r_[np.nan, lf[:-1]]
    out = np.where(np.isfinite(prev) & np.isfinite(lf) & (lf != prev), lf, 0.0)
    return np.where(np.isfinite(stack), out, np.nan)


def pullback(stack, h, l, c, e21, e34) -> np.ndarray:
    """+1: fully bullish stack, the bar's low touches the 21/34 band (low <= EMA21, the band's top in an up-stack) and
    the bar closes back above EMA21. -1: the mirror in a fully bearish stack. 0 otherwise; NaN where the stack is NaN.
    (EMA34 is the band's far edge; a close beyond it is not a pullback but a break, so the bar must close back above
    the near edge, which is above EMA34 in a bullish stack anyway.)"""
    bull = (stack == 1) & (l <= e21) & (c > e21) & (c > e34)
    bear = (stack == -1) & (h >= e21) & (c < e21) & (c < e34)
    out = np.where(bull, 1.0, np.where(bear, -1.0, 0.0))
    return np.where(np.isfinite(stack), out, np.nan)


def squeeze_break(width, order, pos) -> np.ndarray:
    """Compression then expansion: the ribbon was at its narrowest of the last SQZ_N bars within the last SQZ_RECENT
    bars (not this one), it has widened on each of the last 2 bars, at least 6 of its 7 pairs are in order and the
    close is outside the ribbon on that side. +1 up, -1 down, 0 otherwise."""
    w = pd.Series(width)
    m = w.rolling(SQZ_N, min_periods=SQZ_N).min()
    at_min = (w <= m + 1e-12).astype(float).where(m.notna())
    recent = at_min.shift(1).rolling(SQZ_RECENT, min_periods=SQZ_RECENT).max().values == 1
    widen = (w > w.shift(1)) & (w.shift(1) > w.shift(2))
    sgn = np.sign(order)
    ok = recent & widen.values & (np.abs(order) >= 5 / 7 - 1e-9) & (pos == sgn) & (sgn != 0)
    out = np.where(ok, sgn, 0.0)
    return np.where(np.isfinite(width) & np.isfinite(order) & np.isfinite(pos), out, np.nan)


def ribbon_bars(ctx, tf: int, spans=RIB) -> pd.DataFrame:
    """Per complete bar of `tf` minutes: the bar (day, k, open..close, last_row), every EMA (e<span>), atr, and the
    ribbon's stack, order, width_atr, width_chg, slope_e<span> (EMA change over the bar, in ATRs), pos (+1 close above
    every EMA, -1 below every EMA, 0 inside), pos_frac (0 at the lowest EMA, 1 at the highest), twist, and for RIB
    pullback and sqz_break. Values are NaN for the first WARM bars."""
    def make():
        b = bars(ctx, tf).copy()
        k = len(b)
        if not k:
            return b
        h, l, c = b.high.values, b.low.values, b.close.values
        E = np.column_stack([ema(c, span=s) for s in spans])
        warm = np.arange(k) >= WARM
        E = np.where(warm[:, None], E, np.nan)
        for j, s in enumerate(spans):
            b[f"e{s}"] = E[:, j]
        atr = wilder(true_range(h, l, c), ATR_N)
        atr = np.where(atr > 0, atr, np.nan)
        b["atr"] = atr
        stack, order = stack_state(E)
        b["stack"], b["order"] = stack, order
        hi, lo = E.max(axis=1), E.min(axis=1)
        width = (hi - lo) / atr
        b["width_atr"] = width
        b["width_chg"] = width - np.r_[np.full(CHG_BARS, np.nan), width[:-CHG_BARS]] if k > CHG_BARS else np.nan
        for j, s in enumerate(spans):
            b[f"slope_e{s}"] = np.r_[np.nan, np.diff(E[:, j])] / atr
        pos = np.where(c > hi, 1.0, np.where(c < lo, -1.0, 0.0))
        b["pos"] = np.where(np.isfinite(hi), pos, np.nan)
        with np.errstate(invalid="ignore", divide="ignore"):
            b["pos_frac"] = np.where(hi > lo, (c - lo) / (hi - lo), np.nan)
        b["twist"] = twist(stack)
        if 21 in spans and 34 in spans:
            b["pullback"] = pullback(stack, h, l, c, b.e21.values, b.e34.values)
            b["sqz_break"] = squeeze_break(width, order, b.pos.values)
        return b
    return ctx.memo(("ribbon_bars", tf, tuple(spans)), make)


def _rows(ctx, tf, col, spans=RIB):
    b = ribbon_bars(ctx, tf, spans)
    if not len(b):
        return np.full(ctx.n, np.nan)
    return to_rows(ctx, b, b[col].values)


def _register(tf: int):
    lb, t = LOOKBACK[tf], f"{tf}-minute"
    p = f"rib{tf}"

    def reg(name, desc, col, spans=RIB):
        @feature(name, "indicators", 1, OHLC, lb, desc)
        def f(ctx):
            return _rows(ctx, tf, col, spans)

    reg(f"{p}_stack", f"EMA ribbon 5/8/13/21/34/55/89/200 on {t} bars (last complete bar): +1 fully bullish stacked "
        "(each faster EMA above the next slower), -1 fully bearish, 0 mixed.", "stack")
    reg(f"{p}_order", f"EMA ribbon 5..200 on {t} bars: (adjacent pairs in bullish order - pairs in bearish order) / 7, "
        "from -1 to +1.", "order")
    reg(f"{p}_width_atr", f"EMA ribbon 5..200 on {t} bars: highest EMA - lowest EMA, in ATR(14) of those bars.",
        "width_atr")
    reg(f"{p}_width_chg", f"EMA ribbon 5..200 on {t} bars: width (in ATRs) now minus 5 bars ago; > 0 fanning out, "
        "< 0 compressing.", "width_chg")
    for s in RIB:
        reg(f"{p}_slope_e{s}", f"Slope of EMA({s}) of {t} closes over the last bar, in ATR(14) of those bars.",
            f"slope_e{s}")
    reg(f"{p}_pos", f"Close vs the EMA ribbon 5..200 on {t} bars: +1 above every EMA, -1 below every EMA, 0 inside.",
        "pos")
    reg(f"{p}_pos_frac", f"Where the close sits in the EMA ribbon 5..200 on {t} bars: 0 at the lowest EMA, 1 at the "
        "highest (outside the ribbon: below 0 / above 1).", "pos_frac")
    reg(f"{p}_twist", f"EMA ribbon 5..200 on {t} bars: +1 on the bar it becomes fully bullish after its last full "
        "stack was bearish (a full order flip), -1 the reverse, else 0.", "twist")
    reg(f"{p}_pullback", f"Pullback to the ribbon on {t} bars: +1 fully bullish stack, the low touched EMA21 and the "
        "bar closed back above EMA21/34; -1 the bearish mirror; else 0.", "pullback")
    reg(f"{p}_sqz_break", f"Compression then expansion on {t} bars: the ribbon (in ATRs) was the narrowest of 50 bars "
        "within the last 5, widened on each of the last 2, has >= 6 of 7 pairs in order and the close is outside it "
        "on that side: +1 up, -1 down, else 0.", "sqz_break")
    reg(f"cls{tf}_stack", f"EMA 9/21/50/200 on {t} bars: +1 fully bullish stacked, -1 fully bearish, 0 mixed.",
        "stack", CLS)
    reg(f"cls{tf}_order", f"EMA 9/21/50/200 on {t} bars: (pairs in bullish order - pairs in bearish order) / 3.",
        "order", CLS)


for _tf in TFS:
    _register(_tf)


@feature("rib_mtf_align", "indicators", 1, OHLC, LOOKBACK[15],
         "Multi-timeframe EMA-ribbon agreement: the sum of the 1-, 5- and 15-minute ribbon stacks (+3 all fully "
         "bullish, -3 all fully bearish).")
def mtf_align(ctx):
    return _rows(ctx, 1, "stack") + _rows(ctx, 5, "stack") + _rows(ctx, 15, "stack")
