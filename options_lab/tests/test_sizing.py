"""Position sizing for the expiry-day put.

The strategy's return is insensitive to almost every modelling choice - across
the whole spread range it pays 10.2%-11.1%. Sizing is the exception: it decides
whether a bad day is a setback or the end. So the invariant that matters most
here is not the return, it is that a plan claiming to survive an N% day
actually does.
"""
from __future__ import annotations

import pytest

from options_lab.strategy import sizing


def test_a_plan_actually_survives_the_move_it_claims_to_survive():
    """The load-bearing property. Margin plus the realised loss must fit
    inside the capital committed - otherwise the position is force-closed
    and the 'survives -6%' label is a lie."""
    plan = sizing.plan_position(400_000, survive_move_pct=-0.06)

    loss = sizing.loss_at_move(move_pct=-0.06, lots=plan.lots)
    committed = sizing.EXCHANGE_MARGIN_RS * plan.lots + loss

    assert committed <= plan.total_capital


def test_the_same_holds_at_the_stricter_survival_target():
    plan = sizing.plan_position(400_000, survive_move_pct=-0.13)

    loss = sizing.loss_at_move(move_pct=-0.13, lots=plan.lots)
    committed = sizing.EXCHANGE_MARGIN_RS * plan.lots + loss

    assert committed <= plan.total_capital


def test_reserving_for_a_worse_day_buys_fewer_lots():
    mild = sizing.plan_position(400_000, survive_move_pct=-0.06)
    severe = sizing.plan_position(400_000, survive_move_pct=-0.13)

    assert severe.lots < mild.lots
    assert severe.capital_per_lot > mild.capital_per_lot


def test_reserving_for_a_worse_day_lowers_the_percentage_return():
    """The real trade-off: safety is bought with return, not for free."""
    mild = sizing.plan_position(400_000, survive_move_pct=-0.06)
    severe = sizing.plan_position(400_000, survive_move_pct=-0.13)

    assert severe.expected_annual_pct < mild.expected_annual_pct


def test_position_never_exceeds_the_depth_ceiling_however_much_capital():
    rich = sizing.plan_position(50_000_000, survive_move_pct=-0.06)

    assert rich.lots == sizing.MAX_LOTS_ON_DEPTH


def test_beyond_the_depth_ceiling_extra_capital_only_dilutes_the_return():
    """Because lots are capped, more money cannot buy more position."""
    enough = sizing.plan_position(400_000, survive_move_pct=-0.06)
    far_more = sizing.plan_position(4_000_000, survive_move_pct=-0.06)

    assert far_more.lots == enough.lots
    assert far_more.expected_annual_rs == enough.expected_annual_rs
    assert far_more.expected_annual_pct < enough.expected_annual_pct


def test_insufficient_capital_is_refused_loudly_with_the_amount_needed():
    with pytest.raises(sizing.InsufficientCapital) as exc:
        sizing.plan_position(50_000, survive_move_pct=-0.06)

    assert "188" in str(exc.value) or "189" in str(exc.value), \
        "the message must name the capital actually required"


def test_expected_return_scales_with_lots():
    one = sizing.plan_position(200_000, survive_move_pct=-0.06)
    two = sizing.plan_position(400_000, survive_move_pct=-0.06)

    assert two.lots == 2 * one.lots
    assert two.expected_annual_rs == pytest.approx(2 * one.expected_annual_rs)


def test_the_plan_reports_the_worst_case_it_was_sized_against():
    plan = sizing.plan_position(400_000, survive_move_pct=-0.06)

    assert plan.survives_move_pct == -0.06
    assert plan.worst_case_rs == pytest.approx(
        sizing.loss_at_move(move_pct=-0.06, lots=plan.lots))


def test_a_positive_survive_move_is_refused_as_a_sign_error():
    """A short put is hurt by a FALL. Passing +0.06 is a sign mistake and
    would size against a move that cannot hurt this position."""
    with pytest.raises(ValueError):
        sizing.plan_position(400_000, survive_move_pct=0.06)


def test_the_default_policy_is_the_documented_one():
    explicit = sizing.plan_position(400_000, survive_move_pct=-0.06)
    default = sizing.plan_position(400_000)

    assert default.survives_move_pct == explicit.survives_move_pct
    assert default.lots == explicit.lots
