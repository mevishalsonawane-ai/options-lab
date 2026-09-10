"""Health checks against the expiry put's documented kill conditions.

This is NOT a learner. It adapts nothing and fits nothing. It answers one
question: are the conditions this strategy depends on still true?

That distinction matters. A bandit over a single unfitted strategy has nothing
to allocate, 52 trades a year cannot feed one, and online adaptation would make
every future evaluation in-sample by construction - which is exactly how this
project produced a 98.8% win rate twice. What actually ends this strategy is a
precondition quietly ceasing to hold, and nothing currently notices that.

The checks are computed from a trade ledger, so they run on live fills exactly
as they run on backtested ones.
"""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from options_lab import monitor


def _ledger(n=20, credit=4.85, strike=23_800.0, settlement=24_000.0,
            forward=24_000.0, lot=65):
    """A ledger of comfortable wins, unless the caller says otherwise."""
    return pd.DataFrame({
        "session": [date(2026, 1, 1) + pd.Timedelta(weeks=i) for i in range(n)],
        "strike": [strike] * n,
        "credit": [credit] * n,
        "settlement": [settlement] * n,
        "forward": [forward] * n,
        "lot_size": [lot] * n,
        "net_pnl": [credit * lot - 40.0] * n,
        "won": [True] * n,
        # What the position actually paid out, and how far the strike really
        # sat - both per session, because the nominal parameter is not what
        # any individual trade was exposed to.
        "intrinsic": [max(strike - settlement, 0.0)] * n,
        "otm_realised": [(forward - strike) / forward] * n,
    })


# --- credit floor ----------------------------------------------------------
def test_a_healthy_credit_passes():
    assert monitor.check_credit(_ledger(credit=4.85)).status == "pass"


def test_a_credit_below_the_floor_fails():
    """Below about Rs 2/unit the flat Rs 20 brokerage is a quarter of gross,
    and the trade stops being worth doing at all."""
    c = monitor.check_credit(_ledger(credit=1.20))

    assert c.status == "fail"
    assert "1.2" in c.measured


def test_a_credit_near_the_floor_warns_before_it_fails():
    assert monitor.check_credit(_ledger(credit=2.40)).status == "warn"


# --- lot size --------------------------------------------------------------
def test_an_unchanged_lot_size_passes():
    assert monitor.check_lot_size(65, assumed=65).status == "pass"


def test_a_changed_lot_size_fails_because_the_economics_move():
    """At the lot of 25 that ruled most of 2024 the same trade won 94.1%,
    not 100%. Flat brokerage does not scale, so lot size IS the economics."""
    c = monitor.check_lot_size(25, assumed=65)

    assert c.status == "fail"
    assert "25" in c.measured


# --- margin of safety ------------------------------------------------------
def test_margin_of_safety_is_settlement_minus_breakeven():
    """Break-even is strike minus the credit per unit. This is the single best
    diagnostic for a zero-loss record: it says how close the wins actually came."""
    led = _ledger(strike=23_800.0, credit=5.0, settlement=23_900.0)

    c = monitor.check_margin_of_safety(led)

    assert c.status == "pass"
    assert "105" in c.measured, "23,900 - (23,800 - 5) = 105 points"


def test_a_thin_margin_of_safety_warns_even_though_every_trade_won():
    """The warning that matters: still 100% winning, but only just."""
    led = _ledger(strike=23_800.0, credit=5.0, settlement=23_800.0)   # 5 pts clear

    c = monitor.check_margin_of_safety(led)

    assert c.status in ("warn", "fail")
    assert led["won"].all(), "every trade still won - that is the point"


# --- regime ----------------------------------------------------------------
def test_a_quiet_regime_passes():
    assert monitor.check_regime(_ledger(), otm_pct=0.0075).status == "pass"


def test_falls_approaching_the_strike_distance_warn():
    """The 170-session record rests on NIFTY never falling 1% from 11:00.
    Falls creeping toward the strike are the leading indicator of the loss."""
    led = _ledger(n=20, forward=24_000.0)
    # The fixture's strike sits 0.833% out (23,800 against 24,000), so the
    # warning has to be measured against THAT, not against the nominal 0.75%.
    # A 0.72% fall is 86% of the real distance - close enough to warn.
    led.loc[0:3, "settlement"] = 24_000.0 * (1 - 0.0072)
    led["intrinsic"] = (led.strike - led.settlement).clip(lower=0.0)

    c = monitor.check_regime(led, otm_pct=0.0075)

    assert c.status in ("warn", "fail")
    assert "86%" in c.measured or "8" in c.measured


# --- variance risk premium -------------------------------------------------
def test_the_premium_passes_while_credit_exceeds_what_it_pays_out():
    """The edge IS the variance risk premium. The credit insures only the part
    of a fall BEYOND the strike, so it is compared to realised INTRINSIC, not
    to the whole move - comparing it to the whole move made a healthy holdout
    with zero ITM finishes read as a failure."""
    led = _ledger(credit=5.0, forward=24_000.0, settlement=24_000.0)

    assert monitor.check_variance_premium(led).status == "pass"


def test_the_premium_fails_when_realised_falls_exceed_the_credit():
    led = _ledger(n=20, credit=2.0, forward=24_000.0)
    led["settlement"] = 24_000.0 - 400.0        # 400-pt falls against a 2-pt credit
    led["intrinsic"] = (led.strike - led.settlement).clip(lower=0.0)

    assert monitor.check_variance_premium(led).status == "fail"


# --- the report ------------------------------------------------------------
def test_run_checks_returns_one_result_per_condition():
    checks = monitor.run_checks(_ledger(), otm_pct=0.0075, lot_size=65)

    assert len(checks) >= 5
    assert all(c.status in ("pass", "warn", "fail") for c in checks)
    assert len({c.name for c in checks}) == len(checks), "names must be unique"


def test_every_check_states_what_it_measured_and_against_what():
    for c in monitor.run_checks(_ledger(), otm_pct=0.0075, lot_size=65):
        assert c.measured, f"{c.name} reports no measurement"
        assert c.threshold, f"{c.name} reports no threshold"
        assert c.why, f"{c.name} does not say why it matters"


def test_the_overall_verdict_is_the_worst_individual_check():
    healthy = monitor.run_checks(_ledger(), otm_pct=0.0075, lot_size=65)
    assert monitor.verdict(healthy) == "pass"

    broken = monitor.run_checks(_ledger(credit=1.0), otm_pct=0.0075, lot_size=25)
    assert monitor.verdict(broken) == "fail"


def test_an_empty_ledger_is_refused_rather_than_reported_as_healthy():
    """No trades is not the same as no problems."""
    with pytest.raises(ValueError):
        monitor.run_checks(pd.DataFrame(), otm_pct=0.0075, lot_size=65)


# --- the lot the trades actually used --------------------------------------
def test_the_observed_lot_is_the_most_recent_one_the_trades_used():
    """Under dated lots the CLI has no scalar to hand the check, and passing
    the sentinel through made it report `measured: dated` and FAIL."""
    led = _ledger(n=5, lot=75)

    assert monitor.observed_lot(led) == 75


def test_a_window_that_spans_a_lot_change_is_reported_not_averaged():
    """Half the window at 25 and half at 75 is not a window with one economics.
    The most recent lot is the live one, but the mix has to be visible."""
    led = _ledger(n=6)
    led.loc[0:2, "lot_size"] = 25
    led.loc[3:5, "lot_size"] = 75

    c = monitor.check_lot_size(monitor.observed_lot(led), trades=led)

    assert c.status == "fail"
    assert "25" in c.measured and "75" in c.measured


def test_a_single_lot_window_reports_just_that_lot():
    led = _ledger(n=6, lot=65)

    c = monitor.check_lot_size(monitor.observed_lot(led), trades=led)

    assert c.status == "pass"
    assert c.measured == "65"


def test_observed_lot_refuses_a_ledger_without_the_column():
    """A ledger with no lot_size cannot support a rupee-denominated check."""
    led = _ledger(n=3).drop(columns=["lot_size"])

    with pytest.raises(KeyError):
        monitor.observed_lot(led)
