"""The spread has a fixed tick floor, and a percentage model breaks on cheap options.

Fitted on a real book across a 96x premium range:
    absolute spread per unit = Rs 0.162 + 0.00292 x premium   (R-squared 0.974)

A pure percentage understates a cheap option badly. The expiry-day put trades
at a credit of about Rs 4.85/unit, where 0.9% is Rs 0.044 - BELOW the Rs 0.05
minimum tick, i.e. a spread narrower than the market can quote.

CAVEAT recorded in the model: the law was fitted on BANKNIFTY quotes between
Rs 10 and Rs 960 and is EXTRAPOLATED downward to Rs 5. It is the best estimate
available - no free source carries intraday quotes - but it is an assumption,
and it is the single largest input to this strategy's reported profit.
"""
from __future__ import annotations

import pytest

from options_lab import costs


def test_the_spread_law_reproduces_its_fitted_form():
    """The law was fitted IN-SESSION, so it is the `roll` regime, not `quoted`."""
    assert costs.spread_per_unit(premium=100.0, regime="roll") == pytest.approx(
        0.162 + 0.00292 * 100.0)


def test_a_cheap_option_is_floored_at_one_tick_not_a_percentage():
    """0.9% of Rs 4.85 is Rs 0.044 - narrower than the market can quote."""
    pct_model = 0.009 * 4.85

    assert pct_model < costs.TICK_SIZE
    assert costs.spread_per_unit(premium=4.85) >= costs.TICK_SIZE
    assert costs.spread_per_unit(premium=4.85) > 3 * pct_model


def test_the_spread_never_falls_below_one_tick():
    for premium in (0.05, 0.50, 1.00, 2.00):
        assert costs.spread_per_unit(premium=premium) >= costs.TICK_SIZE


def test_on_an_expensive_option_quoted_matches_the_old_percentage():
    """At Rs 785 the proportional term dominates, so the scaled law and the
    old flat 0.90% agree closely - which is why the percentage looked fine
    until it met a Rs 5 option."""
    law = costs.spread_per_unit(premium=785.80, regime="quoted")
    pct = 0.0090 * 785.80

    assert law == pytest.approx(pct, rel=0.10)


def test_sell_to_settle_crosses_half_the_spread_once():
    c = costs.sell_to_settle(premium=4.85, lot_size=65, lots=1, regime="quoted")
    expected = costs.spread_per_unit(premium=4.85) / 2.0 * 65

    assert c.spread == pytest.approx(expected)


def test_the_floor_makes_a_cheap_trade_materially_more_expensive():
    """The whole point: this changes the strategy's reported profit."""
    c = costs.sell_to_settle(premium=4.85, lot_size=65, lots=1, regime="quoted")
    old_pct_spread = 0.0090 / 2.0 * 4.85 * 65

    assert c.spread > 3 * old_pct_spread


def test_the_regime_still_scales_the_spread():
    tight = costs.spread_per_unit(premium=100.0, regime="roll")
    quoted = costs.spread_per_unit(premium=100.0, regime="quoted")
    wide = costs.spread_per_unit(premium=100.0, regime="stress")

    assert tight < quoted < wide
    assert tight >= costs.TICK_SIZE
