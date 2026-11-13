"""R168 tests: the pure statistics, twin p, BH and gates (the engine and walk are R152's, tested there)."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r168study as S  # noqa: E402


def tr(day, net, why="time"):
    return dict(day=day, net=net, why=why)


def test_stats_win_breakeven_drawdown_and_mix():
    d1, d2, d3 = date(2025, 4, 1), date(2025, 4, 2), date(2025, 4, 3)
    t = [tr(d1, 2000, "target"), tr(d2, -1000, "stop2k"), tr(d2, -1000, "stop2k"), tr(d3, 500)]
    s = S.stats(t)
    assert s["trades"] == 4 and s["win"] == 0.5 and s["net"] == 500
    assert s["avg_win"] == 1250 and s["avg_loss"] == 1000
    assert s["be_win"] == pytest.approx(1000 / 2250, abs=1e-4)
    assert s["worst_day"] == -2000 and s["max_dd"] == 2000
    assert s["mix"] == dict(target=1, stop1k=2, stop15=0, other=1)


def test_stats_empty_and_one_sided():
    assert S.stats([])["win"] is None
    s = S.stats([tr(date(2025, 1, 1), 100)])
    assert s["avg_loss"] is None and s["be_win"] == 0.0 and s["max_dd"] == 0
    s = S.stats([tr(date(2025, 1, 1), -100)])
    assert s["avg_win"] is None and s["be_win"] == 1.0


def test_between_is_inclusive():
    t = [tr(date(2025, 3, 31), 1), tr(date(2025, 4, 1), 2), tr(date(2026, 3, 31), 3), tr(date(2026, 4, 1), 4)]
    assert [x["net"] for x in S.between(t, date(2025, 4, 1), date(2026, 3, 31))] == [2, 3]


def test_twin_p_and_bh():
    assert S.twin_p(5.0, [1, 2, 3]) == pytest.approx(0.25)
    assert S.twin_p(1.0, [1, 2, 3]) == pytest.approx(1.0)
    q = S.bh([0.01, 0.04, 0.5], 3)
    assert q[0] == pytest.approx(0.03) and q[1] == pytest.approx(0.06)
    assert S.bh([0.002] * 6, S.M_TOTAL)[0] == pytest.approx(min(1.0, 0.002 * S.M_TOTAL / 6))
    assert len(S.bh([], 10)) == 0


def test_gates():
    b = {p: dict(win=0.45, net=1000, trades=100) for p in ("design", "wf", "final")}
    good = {p: dict(win=0.51, net=1100, trades=100) for p in ("design", "wf", "final")}
    assert S.gates(good, b, 0.05) == []
    f = S.gates(dict(good, final=dict(win=0.46, net=900, trades=10)), b, 0.5)
    assert any("win rate" in x for x in f) and any("net lower" in x for x in f)
    assert any("BH q" in x for x in f) and any("final trades" in x for x in f)
    neg = dict(good, design=dict(win=0.6, net=-1, trades=100))
    assert S.gates(neg, b, 0.05) == ["design: net not positive"]


def test_constants_match_the_prereg():
    assert (S.TARGET, S.STOP, S.LOTS, S.TWINS, S.SEED, S.M_TOTAL) == (2000.0, 1000.0, (1, 2, 3), 500, 168, 2674)
    assert np.isclose(S.WIN_UP, 0.05)
