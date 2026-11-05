"""R150 tests (synthetic only)."""
from __future__ import annotations

import numpy as np

from research.hunt.r150_sr_levels import levels as V

N = V.N


def day(base, seed):
    rng = np.random.default_rng(seed)
    c = base + np.cumsum(rng.normal(0, 1, N))
    o = np.concatenate([[base], c[:-1]])
    return np.vstack([o, np.maximum(o, c) + 0.5, np.minimum(o, c) - 0.5, c])


def test_bars15_shape_and_end_rows():
    b = V.bars15([day(100, 1), day(100, 2)])
    assert len(b["c"]) == 50 and list(b["end"][:2]) == [14, 29] and b["day"][25] == 1


def test_pivot_needs_k_bars_each_side_and_is_confirmed_k_later():
    h = np.array([1, 2, 3, 9, 3, 2, 1, 2, 3], float)
    l_ = h - 1
    piv = V.pivots(h, l_, k=3)
    highs = [p for p in piv if p[2] == 1]
    assert highs == [(3, 9.0, 1, 6)]
    assert all(p[0] >= 3 and p[0] <= len(h) - 4 for p in piv)


def test_group():
    g = V.group([100.0, 100.1, 99.95, 105.0, 105.1])
    assert len(g) == 2 and g[0][1] == 3 and abs(g[0][0] - 100.0167) < 1e-3 and g[1][1] == 2


def test_levels_at_ignores_unconfirmed_and_old_pivots():
    b = {"day": np.array([0] * 10 + [1] * 10), "end": np.tile(np.arange(14, 150, 15), 2)}
    piv = [(2, 100.0, 1, 6), (12, 100.05, 1, 16), (13, 99.98, -1, 17)]
    # day 1, minute 90: bars 10..15 closed (ends 14..89), so pivots confirmed at bars 16 / 17 are not known yet
    assert V.levels_at(b, piv, 1, 90, window=5) == [(100.0, 1)]
    lv3 = V.levels_at(b, piv, 1, 120, window=5)     # bars to 17 closed: all three known, one group
    assert len(lv3) == 1 and lv3[0][1] == 3
    lv2 = V.levels_at(b, piv, 1, 120, window=1)     # window 1 session: day-0 pivot dropped
    assert lv2[0][1] == 2 and abs(lv2[0][0] - 100.015) < 1e-9


def test_lookahead_levels_unchanged_by_later_bars():
    days = [day(100 + i, i) for i in range(5)]
    b = V.bars15(days)
    piv = V.pivots(b["h"], b["l"])
    lv = V.levels_at(b, piv, 4, 120, window=63)
    days2 = [d.copy() for d in days]
    days2[4][:, 120:] = 1e6                           # scramble everything from minute 120 of the last day
    b2 = V.bars15(days2)
    piv2 = V.pivots(b2["h"], b2["l"])
    lv2 = V.levels_at(b2, piv2, 4, 120, window=63)
    assert lv == lv2


def test_wall_and_cross():
    lv = [(100.0, 3), (101.0, 2), (99.0, 4)]
    assert V.wall_ahead(lv, 99.9, "C") and not V.wall_ahead(lv, 100.0, "C")
    assert V.wall_ahead(lv, 99.2, "P") and not V.wall_ahead(lv, 100.8, "C")      # 101 has only 2 touches
    assert V.wall_ahead(lv, 100.8, "C", min_touches=2)
    assert V.crossed(lv, 99.9, 100.2, "C") and not V.crossed(lv, 100.1, 100.2, "C")
    assert V.crossed(lv, 99.1, 98.9, "P")


def test_crossed_level_and_fake_exit():
    lv = [(100.0, 3), (100.5, 4), (101.0, 2)]
    assert V.crossed_level(lv, 99.8, 100.7, "C") == 100.5
    assert V.crossed_level(lv, 100.7, 99.8, "P") == 100.0
    assert V.crossed_level(lv, 100.6, 100.7, "C") is None
    closes = np.full(N, 100.7)
    closes[13] = 100.4                       # the 5-min bar ending row 14 closes at row 13 (row 14 missing)
    closes[14] = np.nan
    assert V.fake_exit_row(closes, 10, 100.5, "C") == 15
    assert V.fake_exit_row(closes, 15, 100.5, "C") == -1       # bars before the fill are not looked at
    assert V.fake_exit_row(np.full(N, 100.7), 10, 100.5, "C") == -1
