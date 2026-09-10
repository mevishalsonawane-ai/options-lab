"""Options cost model.

Rates verified 2026-09-07. The headline that matters: explicit charges are
~0.437% of premium on a Rs 785 BANKNIFTY option, but because brokerage is a
FLAT Rs 20/order the fraction is premium-dependent - ~0.600% on a Rs 200 NIFTY
option and ~2.05% on a Rs 40 one. There is no single cost number.

The spread is 70-89% of the total and is an ASSUMPTION, not data: no free
source carries intraday quotes. Three named regimes stand in for a book.
"""
from __future__ import annotations

import pytest

from options_lab import costs


def test_explicit_charges_on_a_banknifty_atm_round_trip():
    c = costs.explicit(premium=785.80, lot_size=30, lots=1)

    assert c.brokerage == pytest.approx(40.00)
    assert c.stt == pytest.approx(35.37, abs=0.01)          # 0.15%, SELL leg only
    assert c.exchange == pytest.approx(16.76, abs=0.01)     # 0.03553%, both sides
    assert c.stamp == pytest.approx(0.71, abs=0.01)         # 0.003%, BUY leg only
    assert c.total == pytest.approx(103.09, abs=0.05)


def test_the_explicit_fraction_is_worse_on_cheaper_options():
    """Flat brokerage means a cheap option is proportionally far more expensive."""
    rich = costs.explicit(premium=785.80, lot_size=30, lots=1).fraction_of_premium
    mid = costs.explicit(premium=200.0, lot_size=65, lots=1).fraction_of_premium
    cheap = costs.explicit(premium=40.0, lot_size=65, lots=1).fraction_of_premium

    assert rich == pytest.approx(0.00437, abs=0.0002)
    assert mid == pytest.approx(0.00600, abs=0.0005)
    assert cheap == pytest.approx(0.02053, abs=0.002)
    assert cheap > mid > rich


def test_stt_is_charged_on_the_sell_leg_only():
    one = costs.explicit(premium=100.0, lot_size=65, lots=1)

    assert one.stt == pytest.approx(0.0015 * 100.0 * 65)


def test_brokerage_amortises_with_size_but_the_fraction_asymptotes():
    small = costs.explicit(premium=785.80, lot_size=30, lots=1).fraction_of_premium
    big = costs.explicit(premium=785.80, lot_size=30, lots=10).fraction_of_premium

    assert big < small
    assert big == pytest.approx(0.00237, abs=0.0003)


def test_round_trip_adds_a_crossed_spread_under_a_named_regime():
    quoted = costs.round_trip(premium=785.80, lot_size=30, lots=1, regime="quoted")
    roll = costs.round_trip(premium=785.80, lot_size=30, lots=1, regime="roll")

    assert quoted.fraction_of_premium > roll.fraction_of_premium
    assert quoted.fraction_of_premium == pytest.approx(0.0135, abs=0.003)


def test_stress_regime_is_the_most_expensive():
    fracs = [costs.round_trip(premium=785.80, lot_size=30, lots=1, regime=r)
             .fraction_of_premium for r in ("roll", "quoted", "stress")]

    assert fracs == sorted(fracs)


def test_there_is_no_zero_cost_regime():
    """Once a zero-cost path exists, some run will use it and be reported."""
    assert "zero" not in costs.REGIMES
    assert all(costs.SPREAD_PCT[r] > 0 for r in costs.REGIMES)

    with pytest.raises(ValueError):
        costs.round_trip(premium=100.0, lot_size=65, lots=1, regime="zero")


def test_breakeven_in_index_points_divides_premium_cost_by_delta():
    """An ATM option moves ~0.5 points per index point, so cost doubles in
    index terms - this is the number that decides whether anything is tradeable."""
    pts = costs.breakeven_index_points(premium=785.80, lot_size=30, lots=1,
                                       regime="quoted", delta=0.5)
    rt = costs.round_trip(premium=785.80, lot_size=30, lots=1, regime="quoted")

    assert pts == pytest.approx(rt.total / (30 * 0.5), rel=1e-9)
    assert 11.0 < pts < 30.0


def test_a_zero_delta_is_refused_rather_than_dividing_by_zero():
    with pytest.raises(ValueError):
        costs.breakeven_index_points(premium=100.0, lot_size=65, lots=1,
                                     regime="quoted", delta=0.0)


# --- buying a leg and holding it to settlement -----------------------------
def test_buying_to_settle_charges_one_order_not_two():
    """Same asymmetry as the sell side: settling is not a round trip."""
    one = costs.buy_to_settle(premium=5.0, lot_size=65, regime="quoted")
    two = costs.round_trip(premium=5.0, lot_size=65, regime="quoted")

    assert one.brokerage < two.brokerage


def test_the_buyer_pays_stamp_duty_and_the_seller_does_not():
    """Stamp duty is a buy-side charge. sell_to_settle correctly omits it, so
    a hedged position that only ever called sell_to_settle would understate."""
    bought = costs.buy_to_settle(premium=5.0, lot_size=65, regime="quoted")
    sold = costs.sell_to_settle(premium=5.0, lot_size=65, regime="quoted")

    assert bought.stamp > 0
    assert sold.stamp == 0


def test_the_buyer_pays_no_stt_on_the_premium():
    """STT on option premium falls on the writer."""
    bought = costs.buy_to_settle(premium=5.0, lot_size=65, regime="quoted")

    assert bought.stt == 0.0


def test_an_in_the_money_long_leg_pays_exercise_stt_on_its_intrinsic():
    """The 0.125%-of-intrinsic exercise charge falls on the option BUYER - the
    charge sell_to_settle explicitly does not pay. A bought wing that finishes
    in the money pays it, and it is not small: 0.125% of intrinsic."""
    worthless = costs.buy_to_settle(premium=5.0, lot_size=65, regime="quoted",
                                    intrinsic=0.0)
    exercised = costs.buy_to_settle(premium=5.0, lot_size=65, regime="quoted",
                                    intrinsic=200.0)

    assert exercised.stt > worthless.stt
    assert exercised.stt == pytest.approx(0.00125 * 200.0 * 65, rel=1e-9)


def test_the_buyer_crosses_half_a_spread_like_the_seller():
    dear = costs.buy_to_settle(premium=5.0, lot_size=65, regime="stress")
    cheap = costs.buy_to_settle(premium=5.0, lot_size=65, regime="roll")

    assert dear.spread > cheap.spread


def test_an_unknown_regime_is_refused_on_the_buy_side_too():
    with pytest.raises(ValueError):
        costs.buy_to_settle(premium=5.0, lot_size=65, regime="free")
