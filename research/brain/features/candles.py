"""Candles family: patterns of the last complete 5-minute and 15-minute index bar of the session.

A bar is used from the row of its final minute on (bars.py), and a pattern compares it with the previous bar of the
SAME session (NaN for the session's first bar). Big-bar ATR is Wilder's ATR(14) of earlier bars of the same timeframe,
continuous across sessions (it excludes the bar being judged).
"""
from __future__ import annotations

import numpy as np

from .bars import bars, ema, to_rows, true_range
from .registry import feature

OHLC = ["minutes.open", "minutes.high", "minutes.low", "minutes.close"]


def _pat(ctx, tf):
    def make():
        b = bars(ctx, tf)
        k = len(b)
        out = {}
        if not k:
            return {n: np.full(ctx.n, np.nan) for n in ("inside", "outside", "engulf", "big", "uw", "lw", "run")}
        o, h, l, c = (b[x].values for x in ("open", "high", "low", "close"))
        same = np.r_[False, b.day.values[1:] == b.day.values[:-1]]
        ph, pl, po, pc = (np.r_[np.nan, x[:-1]] for x in (h, l, o, c))
        with np.errstate(invalid="ignore", divide="ignore"):
            inside = np.where(same, ((h < ph) & (l > pl)).astype(float), np.nan)
            outside = np.where(same, ((h > ph) & (l < pl)).astype(float), np.nan)
            body, pbody = np.abs(c - o), np.abs(pc - po)
            bull = (c > o) & (pc < po) & (c >= po) & (o <= pc) & (body > pbody)
            bear = (c < o) & (pc > po) & (c <= po) & (o >= pc) & (body > pbody)
            engulf = np.where(same, np.where(bull, 1.0, np.where(bear, -1.0, 0.0)), np.nan)
            rng = h - l
            atr = ema(true_range(h, l, c), alpha=1 / 14)
            atr_prev = np.r_[np.nan, atr[:-1]]
            atr_prev = np.where(np.arange(k) >= 14, atr_prev, np.nan)  # 14 earlier bars before the ATR counts
            big = np.where(np.isfinite(atr_prev), (rng > 2 * atr_prev).astype(float), np.nan)
            uw = np.where(rng > 0, (h - np.maximum(o, c)) / rng, np.nan)
            lw = np.where(rng > 0, (np.minimum(o, c) - l) / rng, np.nan)
        col = np.sign(c - o)
        run = np.zeros(k)
        for i in range(k):
            if col[i] == 0:
                run[i] = 0
            elif i > 0 and same[i] and np.sign(run[i - 1]) == col[i]:
                run[i] = run[i - 1] + col[i]
            else:
                run[i] = col[i]
        for n, v in (("inside", inside), ("outside", outside), ("engulf", engulf), ("big", big), ("uw", uw),
                     ("lw", lw), ("run", run)):
            out[n] = to_rows(ctx, b, v, same_day=True)
        return out
    return ctx.memo(("candles", tf), make)


def _family(tf):
    p = f"c{tf}m"
    spec = [
        ("inside", "1 if the last complete bar is an inside bar (high below and low above the previous bar's)."),
        ("outside", "1 if the last complete bar is an outside bar (high above and low below the previous bar's)."),
        ("engulf", "+1 bullish engulfing (a green body engulfing the previous red body), -1 bearish, 0 neither."),
        ("big", "1 if the last complete bar's range is above 2x ATR(14) of the earlier bars of this timeframe."),
        ("uw", "Upper wick of the last complete bar / its range (NaN for a zero-range bar)."),
        ("lw", "Lower wick of the last complete bar / its range."),
        ("run", "Run of same-colour bars this session ending at the last complete bar: +3 = three green in a row, "
                "-2 = two red; a doji resets it to 0."),
    ]
    names = dict(uw="upper_wick", lw="lower_wick")
    for key, desc in spec:
        @feature(f"{p}_{names.get(key, key)}", "candles", 1, OHLC, "10d" if key == "big" else "day",
                 f"{tf}-minute bars: {desc}")
        def f(ctx, key=key):
            return _pat(ctx, tf)[key]


for _tf in (5, 15):
    _family(_tf)
