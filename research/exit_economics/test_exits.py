import numpy as np
import pytest
from research.exit_economics.study import exit_fill, CENTER
from research.retest.study import exit_profile
from research.exit_economics import study as X


def test_ambiguous_bar_stops_before_target():
    assert exit_fill((100, 120, 80, 110, 1), 100, 1, CENTER) == (85., 'initial_stop')


def test_gap_stop_and_deadline_use_open():
    assert exit_fill((80, 120, 70, 100, 1), 100, 20, CENTER) == (80, 'gap_stop')
    assert exit_fill((105, 120, 90, 100, 1), 100, 20, CENTER) == (105, 'time_exit')


def test_selected_stop_geometry_is_used():
    bar = (100, 105, 88, 102, 1)
    assert exit_fill(bar, 100, 1, (.1, .1, 20)) == (90., 'initial_stop')
    assert exit_fill(bar, 100, 1, (.2, .1, 20)) is None


def test_unpriceable_bar_does_not_fill():
    for bar in [(100,110,90,100,0), (100,np.nan,90,100,1)]:
        assert exit_fill(bar, 100, 20, CENTER) is None
    with pytest.raises(ValueError):
        exit_fill((100,110,90,100,1),100,0,(0,.1,20))


def test_baseline_fill_parity_on_random_valid_ohlc():
    rng = np.random.default_rng(71)
    for _ in range(1000):
        op, close = rng.uniform(70,130,2)
        bar = (op, max(op,close)+rng.uniform(0,10), min(op,close)-rng.uniform(0,10), close, 1)
        elapsed = int(rng.integers(0,30))
        a = exit_fill(bar,100,elapsed,CENTER)
        b = exit_profile(bar,100,999,elapsed,'quick')
        assert (a is None) == (b is None)
        if a is not None:
            assert a[0] == b[0]


def test_risk_budget_recalculates_for_changed_stop(monkeypatch):
    monkeypatch.setattr(X.T, 'adapt', lambda sessions, rule, delay: sessions)
    sessions = [dict(day='2026-05-04', expiry='2026-05-05',
        candidates={600: ('C', 24000.)},
        book={(600,24000.,'C'):(200.,230.,190.,210.,1.)})]
    ledger, _, _ = X.replay(sessions, (.10,.10,20), 1.)
    assert len(ledger) == 1
    ledger, summary, _ = X.replay(sessions, (.20,.10,20), 1.)
    assert ledger.empty
    assert summary['rejections']['risk_budget'] == 1
    X.verify(ledger, summary)
