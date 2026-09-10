"""TradingView Linear Regression Channel, made backtestable.

Two faithful-porting traps, both encoded below.

SIGN CONVENTION. Pine reads source[i] with i=0 as the CURRENT bar and sets
per = i + 1, so x runs BACKWARDS in time. A rising market therefore produces a
NEGATIVE slope, startPrice (fitted value at the oldest bar) sits BELOW endPrice
(fitted value at the current bar), and trend = sign(startPrice - endPrice) is
NEGATIVE in an uptrend. The script's own alerts agree: uptrend fires on trend < 0.

LAST-BAR ONLY. calcSlope returns [na,na,na] unless barstate.islast, so the
original produces exactly one channel ever and no history at all. The rolling
version here evaluates the same maths on every bar, which is the change that
makes it testable - and it is a change, not a port.
"""
from __future__ import annotations

import numpy as np
import pytest

from options_lab.indicators import linreg


def _rising(n=100, step=1.0, start=100.0):
    return np.arange(n, dtype="float64") * step + start


def test_a_rising_series_is_reported_as_an_uptrend():
    close = _rising()

    ch = linreg.channel(close, close, close, length=100)

    assert ch.trend < 0, "Pine's x runs backwards, so an uptrend is trend < 0"
    assert ch.is_uptrend


def test_a_falling_series_is_reported_as_a_downtrend():
    close = _rising()[::-1].copy()

    ch = linreg.channel(close, close, close, length=100)

    assert ch.trend > 0
    assert not ch.is_uptrend


def test_the_fitted_end_price_is_above_the_start_price_when_rising():
    close = _rising()

    ch = linreg.channel(close, close, close, length=100)

    assert ch.end_price > ch.start_price
    assert ch.end_price == pytest.approx(close[-1], abs=1e-6)
    assert ch.start_price == pytest.approx(close[-100], abs=1e-6)


def test_pearson_r_is_one_for_a_perfect_line():
    close = _rising()

    ch = linreg.channel(close, close, close, length=100)

    assert abs(ch.pearson_r) == pytest.approx(1.0, abs=1e-9)


def test_a_perfect_line_has_zero_standard_deviation():
    close = _rising()

    ch = linreg.channel(close, close, close, length=100)

    assert ch.std_dev == pytest.approx(0.0, abs=1e-9)


def test_the_channel_is_two_sigma_wide_by_default():
    rng = np.random.default_rng(0)
    close = _rising() + rng.normal(scale=5.0, size=100)

    ch = linreg.channel(close, close, close, length=100)

    assert ch.upper_end - ch.end_price == pytest.approx(2.0 * ch.std_dev, rel=1e-9)
    assert ch.end_price - ch.lower_end == pytest.approx(2.0 * ch.std_dev, rel=1e-9)


def test_deviation_multipliers_are_honoured():
    rng = np.random.default_rng(1)
    close = _rising() + rng.normal(scale=5.0, size=100)

    ch = linreg.channel(close, close, close, length=100,
                        upper_mult=3.0, lower_mult=1.0)

    assert ch.upper_end - ch.end_price == pytest.approx(3.0 * ch.std_dev, rel=1e-9)
    assert ch.end_price - ch.lower_end == pytest.approx(1.0 * ch.std_dev, rel=1e-9)


def test_rolling_returns_a_value_on_every_bar_after_warmup():
    """The original computes only on the last bar; this is the change."""
    close = _rising(300)

    out = linreg.rolling_trend(close, close, close, length=100)

    assert len(out) == 300
    assert np.isnan(out[:99]).all(), "no channel before 100 bars exist"
    assert not np.isnan(out[99:]).any()


def test_rolling_trend_flips_sign_when_the_series_turns():
    up = _rising(150)
    down = up[-1] - (np.arange(150, dtype="float64") * 1.0)
    close = np.concatenate([up, down])

    out = linreg.rolling_trend(close, close, close, length=100)

    assert out[149] < 0, "still an uptrend at the turn"
    assert out[-1] > 0, "a downtrend once the fall dominates the window"


def test_flip_signals_fire_only_on_the_bar_the_sign_changes():
    up = _rising(150)
    down = up[-1] - (np.arange(150, dtype="float64") * 1.0)
    close = np.concatenate([up, down])

    trend = linreg.rolling_trend(close, close, close, length=100)
    buys, sells = linreg.flip_signals(trend)

    assert buys.sum() + sells.sum() >= 1
    assert not (buys & sells).any(), "a bar cannot be both"
    assert sells.sum() >= 1, "the turn down must produce a sell"


def test_a_length_longer_than_the_data_yields_no_channel():
    with pytest.raises(ValueError):
        linreg.channel(_rising(50), _rising(50), _rising(50), length=100)
