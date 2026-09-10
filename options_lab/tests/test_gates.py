"""Regime gates. These decide WHERE a signal is allowed to trade, not what it says.

DTE is the most important state variable in the whole project and it is a gate,
not a signal. ATM theta as a fraction of premium is 1/(2T) per year and is
volatility-independent: 2.27%/day at 22 DTE against ~50%/day at 1 DTE. A
round-trip spread of ~4 option points is 57 minutes of theta at 25 DTE but only
6.5 minutes at 1 DTE - which is the entire argument for hunting near expiry.
"""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from options_lab.features import gates


def test_dte_counts_calendar_days_to_expiry():
    assert gates.dte(date(2026, 9, 4), date(2026, 9, 8)) == 4


def test_expiry_day_itself_is_zero_dte():
    assert gates.dte(date(2026, 9, 8), date(2026, 9, 8)) == 0


def test_an_already_expired_contract_is_refused_rather_than_returned_negative():
    with pytest.raises(gates.Expired):
        gates.dte(date(2026, 9, 9), date(2026, 9, 8))


@pytest.mark.parametrize("days,bucket", [
    (0, "0"), (1, "1"), (2, "2-3"), (3, "2-3"),
    (4, "4-7"), (7, "4-7"), (8, "8-21"), (21, "8-21"), (22, "22+"), (40, "22+"),
])
def test_dte_buckets_split_at_the_points_where_theta_economics_change(days, bucket):
    assert gates.dte_bucket(days) == bucket


def test_theta_share_per_day_rises_sharply_into_expiry():
    """1/(2T) per year: the reason near-expiry is a different regime entirely."""
    far = gates.atm_theta_share_per_day(dte=22)
    near = gates.atm_theta_share_per_day(dte=1)

    assert far == pytest.approx(0.0227, abs=0.002)
    assert near > 0.40
    assert near > 15 * far


def test_theta_share_is_undefined_on_expiry_day_itself():
    with pytest.raises(ValueError):
        gates.atm_theta_share_per_day(dte=0)


def test_spread_cost_in_minutes_of_theta_collapses_near_expiry():
    """The core argument: identical spread, far less time needed to earn it back.

    At 25 DTE a 4-point spread on a 170-point option costs MORE THAN A WHOLE
    SESSION of theta (375 minutes) - arithmetically unearnable intraday. At
    1 DTE the same spread is ~18 minutes.
    """
    far = gates.spread_in_theta_minutes(spread_pts=4.0, premium=170.0, dte=25)
    near = gates.spread_in_theta_minutes(spread_pts=4.0, premium=170.0, dte=1)

    assert far > gates.SESSION_MINUTES, "unearnable within one session"
    assert near < 25
    assert far > 20 * near


def test_time_of_day_bucket_separates_the_open_from_the_midday_trough():
    ts = lambda h, m: pd.Timestamp(f"2026-09-04 {h:02d}:{m:02d}", tz="Asia/Kolkata")

    assert gates.time_of_day_bucket(ts(9, 20)) == "open"
    assert gates.time_of_day_bucket(ts(11, 45)) == "midday"
    assert gates.time_of_day_bucket(ts(15, 10)) == "close"


def test_liquidity_gate_rejects_a_contract_that_barely_trades():
    """37.7% of option rows are stale prints; filling against them fakes edge."""
    assert gates.liquid(traded_fraction=0.97)
    assert not gates.liquid(traded_fraction=0.21)
    assert gates.liquid(traded_fraction=0.80), "threshold is inclusive"
