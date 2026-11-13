"""R171 (13 Nov 2026) pre-entry liquidity / execution-cost gates in options_lab.features.gates.

Every gate is skip-only and "cannot judge -> take". The existing liquid() / LIQUID_THRESHOLD defaults are untouched
(test_gates.py still pins them); these tests pin the new functions, including the empty / gap / NaN edges.
"""
from __future__ import annotations

import math

import numpy as np
import pytest

from options_lab import costs
from options_lab.features import gates


# ------------------------------------------------------------------------------------------------ the window
def test_window_ends_before_the_fill_minute_and_clips_at_the_open():
    assert gates.pre_entry_window(375, 100, 30) == slice(70, 100)
    assert gates.pre_entry_window(375, 5, 30) == slice(0, 5)        # a 09:20 entry sees 09:15-09:19 only
    assert gates.pre_entry_window(375, 0, 30) == slice(0, 0)


@pytest.mark.parametrize("row,mins", [(-1, 30), (376, 30), (10, 0)])
def test_window_refuses_impossible_rows(row, mins):
    with pytest.raises(ValueError):
        gates.pre_entry_window(375, row, mins)


def test_existing_defaults_are_unchanged():
    assert gates.LIQUID_THRESHOLD == 0.80
    assert gates.liquid(traded_fraction=0.80) is True


# ------------------------------------------------------------------------------------------------ G1
def test_traded_share_counts_missing_minutes_as_not_traded():
    assert gates.traded_share([1, 0, np.nan, 5]) == 0.5
    assert gates.traded_share([]) is None


def test_pre_entry_active_needs_share_and_a_recent_print():
    busy = np.ones(30)
    assert gates.pre_entry_active(busy, min_share=0.8)
    stale_tail = np.r_[np.ones(25), np.zeros(5)]                   # 83% traded but nothing in the last 5 minutes
    assert not gates.pre_entry_active(stale_tail, min_share=0.8)
    thin = np.r_[np.zeros(10), np.ones(20)]                        # 67%
    assert not gates.pre_entry_active(thin, min_share=0.8)
    assert gates.pre_entry_active(thin, min_share=0.6)


def test_pre_entry_active_empty_window_cannot_judge_so_takes():
    assert gates.pre_entry_active([], min_share=0.95)


def test_pre_entry_active_refuses_a_share_outside_0_1():
    with pytest.raises(ValueError):
        gates.pre_entry_active([1, 1], min_share=1.5)


def test_coverage_threshold_is_looser_only_on_0_1_dte():
    assert gates.coverage_threshold_for_dte(0) == 0.60
    assert gates.coverage_threshold_for_dte(1) == 0.60
    assert gates.coverage_threshold_for_dte(2) == 0.80
    assert gates.coverage_threshold_for_dte(20) == 0.80
    with pytest.raises(gates.Expired):
        gates.coverage_threshold_for_dte(-1)


# ------------------------------------------------------------------------------------------------ G2
def test_cost_share_falls_with_premium_because_brokerage_is_flat():
    cheap = gates.round_trip_cost_share(premium=40.0, lot_size=65)
    dear = gates.round_trip_cost_share(premium=785.0, lot_size=30)
    assert cheap > dear
    assert cheap == pytest.approx(costs.round_trip(premium=40.0, lot_size=65, regime="quoted").fraction_of_premium)


def test_cost_share_depends_on_the_assumed_spread_regime():
    r = [gates.round_trip_cost_share(premium=150.0, lot_size=75, regime=g) for g in ("roll", "quoted", "stress")]
    assert r[0] < r[1] < r[2]


def test_cost_share_refuses_a_zero_premium():
    with pytest.raises(ValueError):
        gates.round_trip_cost_share(premium=0.0, lot_size=75)


def test_cost_cap_skips_cheap_options_and_keeps_dear_ones():
    assert not gates.cost_cap_ok(premium=20.0, lot_size=75, cap=0.025)
    assert gates.cost_cap_ok(premium=400.0, lot_size=75, cap=0.025)


def test_premium_floor_and_bad_premium_are_skipped():
    assert not gates.cost_cap_ok(premium=9.95, lot_size=75, floor=10.0)
    assert gates.cost_cap_ok(premium=10.0, lot_size=75, floor=10.0)
    assert not gates.cost_cap_ok(premium=0.0, lot_size=75)
    assert not gates.cost_cap_ok(premium=float("nan"), lot_size=75)


def test_cost_cap_with_neither_rule_takes_any_positive_premium():
    assert gates.cost_cap_ok(premium=0.05, lot_size=75)


# ------------------------------------------------------------------------------------------------ G3 depth
def test_median_lots_per_minute_in_lots_with_gaps_as_zero():
    assert gates.median_lots_per_minute([750, 750, np.nan], 75) == 10.0
    assert gates.median_lots_per_minute([], 75) is None
    with pytest.raises(ValueError):
        gates.median_lots_per_minute([1], 0)


def test_depth_ok_scales_with_the_order():
    assert gates.depth_ok(10.0, order_lots=1, multiple=10)
    assert not gates.depth_ok(10.0, order_lots=3, multiple=10)
    assert gates.depth_ok(None, order_lots=1, multiple=50)          # cannot judge -> take
    with pytest.raises(ValueError):
        gates.depth_ok(10.0, order_lots=0, multiple=10)


def test_depth_regime_flags_orders_beyond_typical_depth():
    assert gates.depth_regime(100.0, order_lots=1) == "quoted"
    assert gates.depth_regime(100.0, order_lots=11) == "stress"
    assert gates.depth_regime(0.0, order_lots=1) == "stress"
    assert gates.depth_regime(None, order_lots=3) == "quoted"


# ------------------------------------------------------------------------------------------------ G3 Roll
def test_roll_recovers_a_bid_ask_bounce():
    # a constant mid, each trade at the bid or the ask at random (Roll's model): the estimate is the full spread
    q = np.random.default_rng(0).choice([-1.0, 1.0], 20_000)
    assert gates.roll_spread(100.0 + 0.5 * q) == pytest.approx(1.0, rel=0.05)


def test_roll_on_strict_alternation_is_twice_the_spread():
    # strict bid/ask alternation is NOT Roll's model (its order flow is not independent): cov = -s^2, so 2 sqrt = 2 s
    assert gates.roll_spread(np.tile([100.0, 101.0], 20)) == pytest.approx(2.0)


def test_roll_is_zero_on_a_trend_and_none_when_short():
    assert gates.roll_spread(np.arange(30.0)) == 0.0
    assert gates.roll_spread(np.arange(5.0)) is None
    assert gates.roll_spread([np.nan] * 40) is None


def test_roll_relative_divides_by_the_mean_price():
    q = np.random.default_rng(1).choice([-1.0, 1.0], 20_000)
    assert gates.roll_relative(100.0 + 0.5 * q) == pytest.approx(1.0 / 100.0, rel=0.05)
    assert gates.roll_relative([1.0, 2.0]) is None


def test_roll_normal_needs_enough_windows_and_is_floored():
    assert gates.roll_normal([0.01] * 5, floor=0.001) is None
    assert gates.roll_normal([0.0] * 6, floor=0.002) == 0.002
    assert gates.roll_normal([0.01, None, 0.02, 0.03, np.nan, 0.01, 0.02, 0.02], floor=0.0) == pytest.approx(0.02)


def test_roll_spike_skips_only_above_the_factor():
    assert gates.roll_spike_ok(0.02, 0.01, factor=2.0)
    assert not gates.roll_spike_ok(0.021, 0.01, factor=2.0)
    assert gates.roll_spike_ok(None, 0.01, factor=2.0)
    assert gates.roll_spike_ok(0.5, None, factor=2.0)
    with pytest.raises(ValueError):
        gates.roll_spike_ok(0.1, 0.1, factor=0)


# ------------------------------------------------------------------------------------------------ G4
def test_dte_spread_cap_tightens_with_dte():
    caps = gates.DTE_SPREAD_CAPS_TIGHT
    # the same 1% spread costs 2 x dte x 375 x 1% minutes of theta: 22.5 at 3 DTE, 52.5 at 7, 150 at 20
    assert gates.dte_spread_ok(spread_pts=1.0, premium=100.0, days=3, caps=caps)
    assert not gates.dte_spread_ok(spread_pts=1.0, premium=100.0, days=7, caps=caps)
    assert not gates.dte_spread_ok(spread_pts=1.0, premium=100.0, days=20, caps=caps)


def test_dte_spread_cap_never_skips_0_or_1_dte():
    caps = gates.DTE_SPREAD_CAPS_TIGHT
    assert gates.dte_spread_ok(spread_pts=50.0, premium=10.0, days=1, caps=caps)
    assert gates.dte_spread_ok(spread_pts=50.0, premium=10.0, days=0, caps=caps)   # expiry day: theta undefined


def test_dte_spread_cap_refuses_an_expired_contract_and_a_bad_premium():
    with pytest.raises(gates.Expired):
        gates.dte_spread_ok(spread_pts=1.0, premium=100.0, days=-1, caps=gates.DTE_SPREAD_CAPS_TIGHT)
    assert not gates.dte_spread_ok(spread_pts=1.0, premium=0.0, days=5, caps=gates.DTE_SPREAD_CAPS_TIGHT)


def test_dte_caps_match_spread_in_theta_minutes():
    m = gates.spread_in_theta_minutes(spread_pts=2.0, premium=150.0, dte=5)
    assert m == pytest.approx(2.0 / 150.0 * 2 * 5 * 375, rel=1e-9)
    assert math.isfinite(m)
