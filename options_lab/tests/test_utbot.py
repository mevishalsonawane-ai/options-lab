"""UT Bot Alerts, ported from the user's Pine v4 source.

Settings for this run: Key Value a = 2, ATR Period c = 1, Heikin Ashi off.

Two facts that simplify the port and are asserted here:
  - ema(src, 1) == src in Pine (alpha = 2/(1+1) = 1), so the published
    `buy = src > stop and crossover(ema, stop)` reduces to crossover(src, stop).
  - atr(1) is Wilder's RMA with alpha = 1, i.e. just the current bar's true
    range. With a = 2 the stop distance is 2 x TR, recomputed every bar.
"""
from __future__ import annotations

import numpy as np
import pytest

from options_lab.indicators import utbot


def _ohlc(closes):
    c = np.asarray(closes, dtype="float64")
    return c + 0.5, c - 0.5, c          # high, low, close


def test_atr_period_one_is_the_current_bars_true_range():
    high = np.array([11.0, 12.0, 13.0])
    low = np.array([9.0, 10.0, 11.0])
    close = np.array([10.0, 11.0, 12.0])

    atr = utbot.atr(high, low, close, period=1)

    # bar 0: high-low = 2. bar 1: max(2, |12-10|, |10-10|) = 2.
    assert atr[0] == pytest.approx(2.0)
    assert atr[1] == pytest.approx(2.0)


def test_true_range_includes_the_gap_from_the_previous_close():
    high = np.array([11.0, 30.0])
    low = np.array([9.0, 28.0])
    close = np.array([10.0, 29.0])

    atr = utbot.atr(high, low, close, period=1)

    assert atr[1] == pytest.approx(20.0), "|high - prev_close| = |30 - 10|"


def test_the_trailing_stop_ratchets_up_and_never_falls_while_price_rises():
    close = np.arange(100.0, 130.0)
    high, low, c = _ohlc(close)

    stop = utbot.trailing_stop(high, low, c, key_value=2.0, atr_period=1)

    rising = stop[2:]
    assert np.all(np.diff(rising) >= -1e-9), "stop must not retreat in an uptrend"
    assert np.all(rising < c[2:]), "and it sits below price while long"


def test_the_trailing_stop_ratchets_down_while_price_falls():
    close = np.arange(130.0, 100.0, -1.0)
    high, low, c = _ohlc(close)

    stop = utbot.trailing_stop(high, low, c, key_value=2.0, atr_period=1)

    falling = stop[2:]
    assert np.all(np.diff(falling) <= 1e-9)
    assert np.all(falling > c[2:]), "and it sits above price while short"


def test_a_uniform_ramp_can_never_flip_the_stop_at_key_value_two():
    """A property of THESE settings, not a defect.

    A flip needs src[i] - src[i-1] > nLoss[i-1] = 2 x TR[i-1]. On a smooth ramp
    TR is about the step size, so 2 x TR always exceeds the step and the stop is
    never crossed. UT Bot at Key Value 2 / ATR 1 is a VOLATILITY-EXPANSION
    trigger - it needs a bar that moves more than twice the previous bar's
    range - not a trend-following one.
    """
    close = np.concatenate([np.arange(120.0, 100.0, -1.0),
                            np.arange(100.0, 130.0)])
    high, low, c = _ohlc(close)

    buys, sells = utbot.signals(high, low, c, key_value=2.0, atr_period=1)

    # Bars 0-4 carry Pine's initialisation transient: nz(stop[1], 0) starts the
    # series at 0, so it opens "long" and flips once price first meets the stop.
    assert buys[5:].sum() == 0
    assert sells[5:].sum() == 0


def test_a_buy_fires_when_a_bar_expands_beyond_twice_the_prior_range():
    quiet = np.full(20, 100.0)
    close = np.concatenate([np.arange(120.0, 100.0, -1.0), quiet, [140.0]])
    high = close + 0.5
    low = close - 0.5
    high[-1] = 141.0

    buys, _ = utbot.signals(high, low, close, key_value=2.0, atr_period=1)

    assert buys.sum() >= 1
    assert buys[-1], "the expansion bar is the signal"


def test_a_sell_fires_when_price_crosses_down_through_the_stop():
    close = np.concatenate([np.arange(100.0, 130.0),
                            np.arange(130.0, 100.0, -1.0)])
    high, low, c = _ohlc(close)

    buys, sells = utbot.signals(high, low, c, key_value=2.0, atr_period=1)

    assert sells.sum() >= 1


def test_a_bar_is_never_both_a_buy_and_a_sell():
    rng = np.random.default_rng(0)
    close = 100 + np.cumsum(rng.normal(size=500))
    high, low, c = _ohlc(close)

    buys, sells = utbot.signals(high, low, c, key_value=2.0, atr_period=1)

    assert not (buys & sells).any()


def test_a_larger_key_value_produces_fewer_signals():
    """Key Value is the sensitivity dial; widening it must reduce whipsaw."""
    rng = np.random.default_rng(1)
    close = 100 + np.cumsum(rng.normal(size=2000))
    high, low, c = _ohlc(close)

    tight = utbot.signals(high, low, c, key_value=1.0, atr_period=1)
    wide = utbot.signals(high, low, c, key_value=5.0, atr_period=1)

    assert (tight[0].sum() + tight[1].sum()) > (wide[0].sum() + wide[1].sum())


def test_signals_are_causal_so_truncating_the_series_changes_nothing_before_it():
    rng = np.random.default_rng(2)
    close = 100 + np.cumsum(rng.normal(size=400))
    high, low, c = _ohlc(close)

    full = utbot.signals(high, low, c, key_value=2.0, atr_period=1)
    part = utbot.signals(high[:300], low[:300], c[:300], key_value=2.0, atr_period=1)

    assert np.array_equal(full[0][:300], part[0])
    assert np.array_equal(full[1][:300], part[1])
