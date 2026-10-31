"""Tests for R140 (PREREG.md): the trend-against filter and the structure-shift exit on hand-built zigzags, the decision
reading only closed bars, the loss measures, the gates and the look-ahead guard."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
sys.path.insert(0, str(HERE.parent / "r139_market_structure"))
import r140 as M  # noqa: E402
from test_r131 import grid, random_grid, truncate  # noqa: E402
from test_r139 import zig  # noqa: E402

UP = [100, 105, 102, 108, 104, 111, 107, 107]            # 15-min HH + HL (3 bars a leg)


def ix_of(g):
    return M.from_arrays(g.o, g.h, g.l, g.c, g.days)


def test_against_reads_the_15min_trend():
    g = grid([zig(UP)], tf=15)
    ix = ix_of(g)
    er = 20 * 15 + 1                                        # after bar 19 closed: trend UP
    assert M.against(ix, 15, g.days[0], er, "P") is True
    assert M.against(ix, 15, g.days[0], er, "C") is False
    assert M.against(ix, 15, g.days[0], 1, "P") is False    # nothing known yet: not against
    assert M.against(ix, 15, date(1999, 1, 1), er, "P") is False


def test_last_bar_uses_only_closed_bars():
    g = grid([zig(UP)], tf=15)
    ix = ix_of(g)
    assert M.last_bar(ix, 15, g.days[0], 15) == 0           # minute 14 closes bar 0
    assert M.last_bar(ix, 15, g.days[0], 14) == -1          # bar 0 not closed yet
    assert M.last_bar(ix, 15, g.days[0], 30) == 1


def test_mss_exit_row_for_a_call_and_a_put():
    # up-trend, then a fall that closes below the last swing low (106.9 wick at the 107 low)
    bars = zig([100, 105, 102, 108, 104, 111, 107, 110, 100])
    g = grid([bars], tf=15)
    ix = ix_of(g)
    ch = ix.tf[15][0]
    r = M.mss_row(ix, 15, g.days[0], 19 * 15, "C")
    assert r > 0
    k = r // 15 - 1
    assert ch.c[k] < 106.9 and (r - 1) == int(ch.B.end[k])
    assert all(ch.c[x] >= 106.9 for x in range(19, k))
    assert M.mss_row(ix, 15, date(1999, 1, 1), 10, "C") == -1


def test_mss_put_mirror():
    bars = zig([111, 106, 109, 103, 107, 100, 104, 101, 112])
    g = grid([bars], tf=15)
    ix = ix_of(g)
    ch = ix.tf[15][0]
    r = M.mss_row(ix, 15, g.days[0], 19 * 15, "P")
    assert r > 0 and ch.c[r // 15 - 1] > 104.1


def test_loss_measures_and_gates():
    d1, d2, d3 = date(2025, 1, 1), date(2025, 1, 2), date(2025, 2, 3)
    tr = [dict(day=d1, net=-500.0, er=5), dict(day=d1, net=200.0, er=40), dict(day=d2, net=-100.0, er=5),
          dict(day=d3, net=900.0, er=5)]
    s = M.loss_stats(tr)
    assert s["loss_sum"] == -400 and s["losing_days"] == 2 and s["days"] == 3
    assert s["worst_month"] == -400 and s["sum_losing_trades"] == -600
    assert M.loss_sum([(d1, -5), (d1, 2), (d2, 7)]) == -3
    assert M.loss_stats([])["loss_sum"] == 0
    base = {p: dict(loss_sum=-1000, max_dd=900, worst_day=-700, worst_month=-800, net=500, trades=100)
            for p in ("design", "wf", "final")}
    good = {p: dict(base[p], loss_sum=-800, max_dd=800) for p in base}
    assert M.gates(good, base, 0.05, 0.01, 40)["passed"]
    bad = {p: dict(good[p]) for p in good}
    bad["wf"]["net"] = -1                                    # cuts losses but no longer makes money
    assert not M.gates(bad, base, 0.05, 0.01, 40)["passed"]
    assert not M.gates(good, base, 0.05, 0.2, 40)["passed"]  # no better than random skipping
    same = {p: dict(base[p]) for p in base}
    assert not M.gates(same, base, 0.05, 0.01, 40)["passed"]  # losses not smaller


def test_family_is_twelve():
    assert len(M.TESTS) == 12
    assert sum(1 for v in M.TESTS.values() if v[1] == "exit") == 4


def test_lookahead_guard():
    rng = np.random.default_rng(140)
    g = random_grid(rng, 12)
    ix = ix_of(g)
    for _ in range(25):
        d = int(rng.integers(5, 12))
        er = int(rng.integers(2, 360))
        side = "C" if rng.random() < 0.5 else "P"
        for tf in (5, 15):
            a = M.against(ix, tf, g.days[d], er, side)
            it = ix_of(truncate(g, d, er))
            assert M.against(it, tf, g.days[d], er, side) == a
            r = M.mss_row(ix, tf, g.days[d], er, side)
            if r > 0:                                         # the exit is decided by minutes before it
                it2 = ix_of(truncate(g, d, r))
                assert M.mss_row(it2, tf, g.days[d], er, side) == r
