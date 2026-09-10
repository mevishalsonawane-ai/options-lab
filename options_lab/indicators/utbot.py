"""UT Bot Alerts, ported from Pine v4.

Source: the user's "UT bot.txt.txt" (study "UT Bot Alerts", @version=4).
Run settings: Key Value a = 2, ATR Period c = 1, Heikin Ashi off.

Faithful-port notes:

  ema(src, 1) IS src. Pine's EMA alpha is 2/(length+1), which is 1 at length 1,
  so the published `above = crossover(ema, stop)` is crossover(src, stop) and
  `buy = src > stop and above` collapses to `above` (the crossover already
  requires src > stop). Same on the sell side. Implemented directly.

  atr(1) IS the current bar's true range. Pine's atr() is Wilder's RMA, whose
  alpha is 1/length; at length 1 no smoothing survives. So with a = 2 the stop
  distance is 2 x TR recomputed every bar, which makes this configuration far
  twitchier than the script's default ATR(10).

  The stop is a recursion (it reads its own previous value), so it is a loop.
  On the first bar Pine's nz(stop[1], 0) is 0 and price is positive, so the
  first branch is taken and the series starts in long mode.
"""
from __future__ import annotations

import numpy as np


class DirtyInput(ValueError):
    """A price series contains NaN.

    The trailing stop is a recursion, so a NaN does not stay local: it makes
    two stop bars NaN and then, when the true-range window clears the gap, the
    recursion restarts from whichever branch a NaN comparison happens to fall
    through to - an arbitrary side, unrelated to the actual trend. Measured on
    a 120-bar walk, one NaN silently cost a signal. Failing loudly is the only
    honest option; the caller must decide how to fill the gap.
    """


def _require_clean(name: str, a: np.ndarray) -> np.ndarray:
    arr = np.asarray(a, dtype="float64")
    bad = np.flatnonzero(~np.isfinite(arr))
    if bad.size:
        raise DirtyInput(
            f"{name} has {bad.size} non-finite value(s), first at index "
            f"{int(bad[0])}. The trailing stop is a recursion and cannot "
            f"absorb a gap - fill or drop it before calling."
        )
    return arr


def true_range(high: np.ndarray, low: np.ndarray, close: np.ndarray) -> np.ndarray:
    high = np.asarray(high, dtype="float64")
    low = np.asarray(low, dtype="float64")
    close = np.asarray(close, dtype="float64")

    prev_close = np.roll(close, 1)
    prev_close[0] = close[0]           # Pine's first bar has no previous close
    return np.maximum.reduce([
        high - low,
        np.abs(high - prev_close),
        np.abs(low - prev_close),
    ])


def atr(high, low, close, *, period: int = 1) -> np.ndarray:
    """Wilder's RMA of true range, matching Pine's atr()."""
    tr = true_range(high, low, close)
    if period <= 1:
        return tr

    out = np.empty_like(tr)
    out[0] = tr[0]
    alpha = 1.0 / period
    for i in range(1, len(tr)):
        out[i] = alpha * tr[i] + (1.0 - alpha) * out[i - 1]
    return out


def trailing_stop(high, low, close, *, key_value: float = 2.0,
                  atr_period: int = 1) -> np.ndarray:
    """The xATRTrailingStop recursion, bar by bar."""
    high = _require_clean("high", high)
    low = _require_clean("low", low)
    src = _require_clean("close", close)
    n_loss = key_value * atr(high, low, src, period=atr_period)

    stop = np.zeros(len(src))
    prev = 0.0                          # Pine: nz(stop[1], 0)
    for i in range(len(src)):
        prev_src = src[i - 1] if i > 0 else src[i]
        if src[i] > prev and prev_src > prev:
            stop[i] = max(prev, src[i] - n_loss[i])
        elif src[i] < prev and prev_src < prev:
            stop[i] = min(prev, src[i] + n_loss[i])
        elif src[i] > prev:
            stop[i] = src[i] - n_loss[i]
        else:
            stop[i] = src[i] + n_loss[i]
        prev = stop[i]
    return stop


def _crossover(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Pine crossover(a, b): a > b now, and a <= b on the previous bar."""
    prev_a, prev_b = np.roll(a, 1), np.roll(b, 1)
    out = (a > b) & (prev_a <= prev_b)
    out[0] = False
    return out


def signals(high, low, close, *, key_value: float = 2.0,
            atr_period: int = 1) -> tuple[np.ndarray, np.ndarray]:
    """(buy, sell) boolean arrays, one element per bar."""
    src = np.asarray(close, dtype="float64")
    stop = trailing_stop(high, low, src, key_value=key_value, atr_period=atr_period)
    return _crossover(src, stop), _crossover(stop, src)
