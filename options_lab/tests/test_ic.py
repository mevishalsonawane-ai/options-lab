"""Panel IC, measured before any rule is written.

The repo's history is 56 hand-written strategies that failed before anyone
asked whether the data was predictable. `panel_ic.py` was written last and
showed the best of 65 feature-horizon pairs was worth 5.4 bp against 10.7 bp of
cost - which explained all 56 failures at once.

So: measurement first, and every IC carries its null band and its partial
beside it. Reporting a raw IC alone is exactly how PCR and max pain looked
real - both are near-monotone transforms of the index level, and neither
survives residualising it out.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from options_lab import ic


def _sessions(n, per=50):
    return pd.Series(np.repeat(np.arange(n // per + 1), per)[:n], name="session")


def test_a_feature_that_is_the_forward_return_has_ic_of_one():
    rng = np.random.default_rng(0)
    fwd = pd.Series(rng.normal(size=200))

    row = ic.panel_ic(feature=fwd, forward=fwd, sessions=_sessions(200))

    assert row.ic == pytest.approx(1.0, abs=1e-9)


def test_a_feature_independent_of_the_forward_return_has_ic_near_zero():
    rng = np.random.default_rng(1)
    row = ic.panel_ic(feature=pd.Series(rng.normal(size=2000)),
                      forward=pd.Series(rng.normal(size=2000)),
                      sessions=_sessions(2000))

    assert abs(row.ic) < 0.06


def test_noise_lands_inside_its_own_null_band():
    rng = np.random.default_rng(2)
    row = ic.panel_ic(feature=pd.Series(rng.normal(size=2000)),
                      forward=pd.Series(rng.normal(size=2000)),
                      sessions=_sessions(2000), null_draws=20)

    assert abs(row.ic) <= row.null_ic_hi


def test_a_real_signal_escapes_its_null_band():
    rng = np.random.default_rng(3)
    f = pd.Series(rng.normal(size=2000))
    row = ic.panel_ic(feature=f, forward=f + rng.normal(scale=0.5, size=2000),
                      sessions=_sessions(2000), null_draws=20)

    assert row.ic > row.null_ic_hi


def test_partial_ic_removes_a_control_the_feature_merely_proxies():
    """The max-pain failure mode: a near-monotone transform of the index.

    Max pain's raw IC is +0.58 at 60 min purely because it correlates -0.933
    with the index level. A noisy proxy is the realistic shape - a perfect one
    is degenerate and handled by the test below.
    """
    rng = np.random.default_rng(4)
    index_move = pd.Series(rng.normal(size=2000))
    feature = index_move * 3.0 + rng.normal(scale=0.3, size=2000)   # noisy proxy
    forward = index_move + rng.normal(scale=0.3, size=2000)

    row = ic.panel_ic(feature=feature, forward=forward, sessions=_sessions(2000),
                      controls=pd.DataFrame({"index_ret": index_move}))

    assert row.ic > 0.8, "raw IC looks strong - this is the trap"
    assert abs(row.partial_ic) < 0.05, "and there is nothing left once controlled"


def test_a_feature_fully_explained_by_its_control_yields_no_partial_at_all():
    """Perfectly-explained residuals are numerical dust; correlating them would
    give a spurious +/-1 exactly where the honest answer is 'nothing left'."""
    rng = np.random.default_rng(41)
    index_move = pd.Series(rng.normal(size=2000))

    row = ic.panel_ic(feature=index_move * 3.0, forward=index_move,
                      sessions=_sessions(2000),
                      controls=pd.DataFrame({"index_ret": index_move}))

    assert np.isnan(row.partial_ic)


def test_partial_ic_keeps_information_the_control_does_not_explain():
    rng = np.random.default_rng(5)
    index_move = pd.Series(rng.normal(size=2000))
    own = pd.Series(rng.normal(size=2000))
    row = ic.panel_ic(feature=index_move + own, forward=own,
                      sessions=_sessions(2000),
                      controls=pd.DataFrame({"index_ret": index_move}))

    assert row.partial_ic > 0.3


def test_a_session_level_shock_fools_the_iid_ic_but_not_the_clustered_one():
    """The reason day-clustering is mandatory here.

    Every observation in a session shares one underlying path. A common shock
    makes the POOLED ic look overwhelming while there is no within-session
    predictability at all. The clustered interval must contain zero; the iid
    interval confidently excludes it. That gap is the whole point - and it is
    why HANDOFF.md records the iid t overstating significance ~3x.
    """
    rng = np.random.default_rng(6)
    sess = _sessions(2000, per=200)
    shock = pd.Series(np.repeat(rng.normal(size=10), 200))
    f = shock + rng.normal(scale=0.2, size=2000)

    row = ic.panel_ic(feature=f, forward=shock + rng.normal(scale=0.2, size=2000),
                      sessions=sess)

    assert row.ic > 0.8, "pooled IC looks decisive"
    assert row.ic_iid_lo > 0.5, "and the iid interval is confident about it"
    assert row.daily_ic_lo < 0 < row.daily_ic_hi, (
        "but the day-clustered interval must contain zero - there is no "
        "within-session predictability here at all"
    )


def test_edge_over_cost_is_the_single_leg_edge_against_the_breakeven():
    """Was the full decile SPREAD; that overstated a directional trade ~2x."""
    rng = np.random.default_rng(7)
    f = pd.Series(rng.normal(size=2000))
    row = ic.panel_ic(feature=f, forward=f, sessions=_sessions(2000),
                      breakeven_pts=20.0)

    assert row.edge_over_cost == pytest.approx(
        row.decile_spread_pts / 2.0 / 20.0)


def test_formatting_refuses_when_controls_were_never_supplied():
    """A raw IC with no partial beside it is not reportable."""
    rng = np.random.default_rng(8)
    f = pd.Series(rng.normal(size=200))
    row = ic.panel_ic(feature=f, forward=f, sessions=_sessions(200))

    with pytest.raises(ic.UncontrolledIC):
        row.format()


def test_formatting_succeeds_once_controls_are_supplied():
    rng = np.random.default_rng(9)
    f = pd.Series(rng.normal(size=200))
    row = ic.panel_ic(feature=f, forward=f, sessions=_sessions(200),
                      controls=pd.DataFrame({"index_ret": rng.normal(size=200)}))

    assert "partial_ic" in row.format()


def test_a_non_monotone_relationship_cannot_report_as_clearing_cost():
    """Observed on real 0-DTE data: IC +0.0065 with a decile spread of -0.52.

    The tails ran opposite to the rank correlation, and edge_over_cost - being
    a magnitude - read as 2.51x. There is no coherent direction to trade there,
    so it must not count as clearing the bar.
    """
    rng = np.random.default_rng(10)
    x = pd.Series(rng.normal(size=3000))
    # Mildly positive rank relation, but the extreme deciles reverse it.
    y = x * 0.25 - np.sign(x) * (x.abs() > 1.5) * x.abs() * 3.0
    row = ic.panel_ic(feature=x, forward=y, sessions=_sessions(3000),
                      controls=pd.DataFrame({"c": rng.normal(size=3000)}),
                      breakeven_pts=0.1)

    assert not row.monotone
    assert row.edge_over_cost > 2.0, "magnitude alone would have passed"
    assert not row.clears_cost
    assert "NON-MONOTONE" in row.format()


def test_a_monotone_relationship_reports_normally():
    rng = np.random.default_rng(11)
    x = pd.Series(rng.normal(size=3000))
    row = ic.panel_ic(feature=x, forward=x + rng.normal(scale=0.4, size=3000),
                      sessions=_sessions(3000),
                      controls=pd.DataFrame({"c": rng.normal(size=3000)}),
                      breakeven_pts=0.1)

    assert row.monotone
    assert "NON-MONOTONE" not in row.format()


def test_misaligned_inputs_are_refused_rather_than_silently_truncated():
    with pytest.raises(ValueError):
        ic.panel_ic(feature=pd.Series([1.0, 2.0, 3.0]),
                    forward=pd.Series([1.0, 2.0]),
                    sessions=pd.Series([0, 0, 0]))


def test_edge_over_cost_uses_a_single_leg_not_the_long_short_spread():
    """A directional trade earns its own decile's mean, not D10 minus D1.

    Dividing the full long-short spread by ONE round-trip cost overstates a
    single-leg strategy by ~2x. Every edge/cost figure produced before this
    fix was too generous by that factor.
    """
    rng = np.random.default_rng(20)
    f = pd.Series(rng.normal(size=4000))
    row = ic.panel_ic(feature=f, forward=f, sessions=_sessions(4000),
                      controls=pd.DataFrame({"c": rng.normal(size=4000)}),
                      breakeven_pts=1.0)

    assert row.single_leg_edge_pts == pytest.approx(row.decile_spread_pts / 2.0)
    assert row.edge_over_cost == pytest.approx(
        abs(row.single_leg_edge_pts) / 1.0)


def test_the_null_band_is_measured_on_the_same_statistic_as_the_interval():
    """null_ic_hi was pooled Spearman while the CI was built from per-session
    Spearmans, so the two were compared across different statistics."""
    rng = np.random.default_rng(21)
    f = pd.Series(rng.normal(size=3000))
    row = ic.panel_ic(feature=f, forward=pd.Series(rng.normal(size=3000)),
                      sessions=_sessions(3000),
                      controls=pd.DataFrame({"c": rng.normal(size=3000)}),
                      null_draws=20)

    # beats_null now compares the daily mean against a daily-mean null.
    assert row.null_is_daily
    assert abs(row.daily_ic_mean) <= row.null_ic_hi
    assert not row.beats_null
