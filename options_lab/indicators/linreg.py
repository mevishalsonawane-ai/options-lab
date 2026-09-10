"""TradingView's built-in Linear Regression Channel, ported and made rolling.

Source: the user's LinRegtxt.txt, which is TradingView's stock indicator. That
file arrived with every operator character stripped (`*`, `/`, `>`, `<`, `?`,
`:` all appear zero times), so the arithmetic below is reconstructed from the
intact structure. Reconstructed lines are marked RESTORED.

TWO THINGS TO KNOW BEFORE READING THE MATHS.

1. X RUNS BACKWARDS. Pine reads `source[i]` with i=0 as the CURRENT bar and sets
   `per = i + 1`. So per=1 is now and per=length is the oldest bar in the window.
   A rising market therefore has a NEGATIVE slope here, and
   `trend = sign(startPrice - endPrice)` is NEGATIVE in an uptrend. The script's
   own alerts agree - "Switched to Uptrend" fires on trend < 0. Getting this
   backwards inverts every signal, so it is asserted in the tests.

2. THE ORIGINAL HAS NO HISTORY. `calcSlope` returns [na, na, na] unless
   `barstate.islast`, so the published indicator draws exactly one channel, on
   the most recent bar, and produces no series at all. `rolling_trend` below
   evaluates the same maths on every bar. That is a CHANGE of semantics, not a
   port, and it is what makes the thing backtestable.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class Channel:
    slope: float
    average: float
    intercept: float
    start_price: float      # fitted value at the OLDEST bar of the window
    end_price: float        # fitted value at the CURRENT bar
    std_dev: float
    pearson_r: float
    up_dev: float
    dn_dev: float
    upper_start: float
    upper_end: float
    lower_start: float
    lower_end: float
    trend: float            # sign(start_price - end_price); NEGATIVE = uptrend

    @property
    def is_uptrend(self) -> bool:
        return self.trend < 0


def channel(
    source: np.ndarray,
    high: np.ndarray,
    low: np.ndarray,
    *,
    length: int = 100,
    upper_mult: float = 2.0,
    lower_mult: float = 2.0,
    use_upper: bool = True,
    use_lower: bool = True,
) -> Channel:
    """The channel as of the LAST element of the arrays."""
    if length < 1:
        raise ValueError("length must be >= 1")
    if len(source) < length:
        raise ValueError(
            f"need at least {length} bars, got {len(source)} - the channel is "
            "undefined until the window is full"
        )

    # per = i + 1 with i=0 the current bar, so reverse the window.
    win = np.asarray(source[-length:], dtype="float64")[::-1]
    hi = np.asarray(high[-length:], dtype="float64")[::-1]
    lo = np.asarray(low[-length:], dtype="float64")[::-1]
    per = np.arange(1, length + 1, dtype="float64")

    sum_x = per.sum()
    sum_y = win.sum()
    sum_x_sqr = (per * per).sum()            # RESTORED: per * per
    sum_xy = (win * per).sum()               # RESTORED: val * per

    denom = length * sum_x_sqr - sum_x * sum_x   # RESTORED
    slope = 0.0 if denom == 0 else (length * sum_xy - sum_x * sum_y) / denom
    average = sum_y / length
    intercept = average - slope * sum_x / length + slope

    start_price = intercept + slope * (length - 1)
    end_price = intercept

    # calcDev: walks the window forward along the fitted line.
    periods = length - 1
    da_y = intercept + slope * periods / 2.0     # RESTORED: slope * periods / 2
    val = intercept

    up_dev = dn_dev = 0.0
    std_acc = dsxx = dsyy = dsxy = 0.0
    for j in range(periods + 1):
        price = hi[j] - val
        if price > up_dev:                        # RESTORED: >
            up_dev = price
        price = val - lo[j]
        if price > dn_dev:                        # RESTORED: >
            dn_dev = price

        price = win[j]
        dxt = price - average
        dyt = val - da_y
        price -= val
        std_acc += price * price
        dsxx += dxt * dxt
        dsyy += dyt * dyt
        dsxy += dxt * dyt
        val += slope

    std_dev = float(np.sqrt(std_acc / (1 if periods == 0 else periods)))
    pearson_r = (0.0 if dsxx == 0 or dsyy == 0
                 else dsxy / float(np.sqrt(dsxx * dsyy)))   # RESTORED ternary

    up_off = (upper_mult * std_dev) if use_upper else up_dev
    dn_off = (-lower_mult * std_dev) if use_lower else -dn_dev

    return Channel(
        slope=slope, average=average, intercept=intercept,
        start_price=start_price, end_price=end_price,
        std_dev=std_dev, pearson_r=pearson_r, up_dev=up_dev, dn_dev=dn_dev,
        upper_start=start_price + up_off, upper_end=end_price + up_off,
        lower_start=start_price + dn_off, lower_end=end_price + dn_off,
        trend=float(np.sign(start_price - end_price)),
    )


def rolling_trend(
    source: np.ndarray, high: np.ndarray, low: np.ndarray, *, length: int = 100
) -> np.ndarray:
    """`trend` evaluated at every bar. NaN until the window is full.

    Uses closed-form sums rather than the O(n*length) loop, since only the
    slope is needed for the trend and the loop in `channel` exists to reproduce
    the deviation bands exactly.
    """
    src = np.asarray(source, dtype="float64")
    n = len(src)
    out = np.full(n, np.nan)
    if length < 1 or n < length:
        return out

    per = np.arange(1, length + 1, dtype="float64")
    sum_x = per.sum()
    sum_x_sqr = (per * per).sum()
    denom = length * sum_x_sqr - sum_x * sum_x
    if denom == 0:
        return out

    # Window reversed => most recent bar carries per=1.
    windows = np.lib.stride_tricks.sliding_window_view(src, length)[:, ::-1]
    sum_y = windows.sum(axis=1)
    sum_xy = (windows * per).sum(axis=1)
    slope = (length * sum_xy - sum_x * sum_y) / denom

    # start_price - end_price = slope * (length - 1), so the sign is the slope's.
    out[length - 1:] = np.sign(slope * (length - 1))
    return out


def flip_signals(trend: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Bars where the regression trend changes sign.

    Mirrors the script's alertconditions:
        uptrend   : trend[1] >= 0 and trend < 0    -> BUY
        downtrend : trend[1] <= 0 and trend > 0    -> SELL
    """
    t = np.asarray(trend, dtype="float64")
    prev = np.roll(t, 1)
    prev[0] = np.nan

    valid = ~np.isnan(t) & ~np.isnan(prev)
    buys = valid & (prev >= 0) & (t < 0)
    sells = valid & (prev <= 0) & (t > 0)
    return buys, sells
