"""R81 tests: each exit rule on synthetic bars, edge cases, and the look-ahead guard (altering bars after a rule's
decision minute never changes its decision). 18 Oct 2026. Pure; no data needed.

    python -m pytest research/hunt/r81/test_r81.py -q
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r81rules as RU  # noqa: E402

N = 375


def flat_idx(px=100.0, n=N):
    a = np.full((4, n), px, float)
    return a


def ramp_idx(start=100.0, step=1.0, n=N):
    c = start + step * np.arange(n)
    o = np.r_[start, c[:-1]]
    return np.vstack([o, np.maximum(o, c) + 0.2, np.minimum(o, c) - 0.2, c])


def opt(px=50.0, n=N):
    a = np.full((5, n), px, float)
    a[4] = 100.0
    return a


# ------------------------------------------------------------------------------------------------ ATR
def test_bars5_grid_and_gaps():
    a = ramp_idx()
    a[:, 5:10] = np.nan                       # a whole 5-minute bar missing
    b = RU.bars5(a)
    assert b[0, 0] == 5 and b[1, 0] == 15      # the empty bar is dropped
    assert b[0, 1] == a[0, 0] and b[0, 4] == a[3, 4]


def test_atr_needs_enough_bars_and_uses_only_complete_bars():
    a = ramp_idx()
    assert np.isnan(RU.atr5([], a, 30))         # 6 bars < 15
    x = RU.atr5([ramp_idx()], a, 30)
    assert np.isfinite(x) and x > 0
    b = a.copy()
    b[:, 30:] = 1e6                            # later bars changed: same ATR (look-ahead guard)
    assert RU.atr5([ramp_idx()], b, 30) == x
    c = a.copy()
    c[1, 29] += 50                             # the bar ending at row 30 is complete at row 30: it counts
    assert RU.atr5([ramp_idx()], c, 30) > x


def test_atr_empty_day():
    assert np.isnan(RU.atr5([], np.full((4, N), np.nan), 100))


# ------------------------------------------------------------------------------------------------ X2
def test_sweep_stop_far_side_never_tighter():
    a = flat_idx()
    a[2, 40:55] = 90.0                         # the breaking bar's low (15-min bar rows 40..54)
    assert RU.sweep_stop(a, 55, 15, +1, 95.0) == 90.0
    assert RU.sweep_stop(a, 55, 15, +1, 85.0) == 85.0          # B0's stop is further: keep it
    a[1, 40:55] = 110.0
    assert RU.sweep_stop(a, 55, 15, -1, 105.0) == 110.0
    a[:, 55:] = 0.0                            # later bars never read
    assert RU.sweep_stop(a, 55, 15, +1, 95.0) == 90.0


# ------------------------------------------------------------------------------------------------ X3
def test_chandelier_arms_then_trails():
    a = flat_idx()
    a[1, 10:20] = 100 + np.arange(1, 11)       # up 10 points
    a[3, 10:20] = 100 + np.arange(1, 11)
    a[3, 20] = a[1, 19] - 1.6 * 4              # close 1.6 ATR (ATR 4) under the best
    a[1, 20] = a[3, 20]
    m = RU.chandelier_row(a, 10, +1, 100.0, 4.0)
    assert m == 20
    b = a.copy()
    b[:, 21:] = -1e9                           # later bars: same decision
    assert RU.chandelier_row(b, 10, +1, 100.0, 4.0) == 20


def test_chandelier_not_armed_never_fires():
    a = flat_idx()
    a[3, 30] = 90.0                            # a big drop, but never 1 ATR in profit first
    assert RU.chandelier_row(a, 10, +1, 100.0, 4.0) == -1
    assert RU.chandelier_row(a, 10, +1, 100.0, float("nan")) == -1
    assert RU.chandelier_row(a, -1, +1, 100.0, 4.0) == -1


def test_chandelier_put_side():
    a = flat_idx()
    a[2, 10:20] = 100 - np.arange(1, 11)
    a[3, 10:20] = 100 - np.arange(1, 11)
    a[:, 20:22] = 92.0                         # within 1.5 ATR of the best low (91)
    a[3, 22] = 90 + 7.0                        # back up 7 > 1.5 x 4
    assert RU.chandelier_row(a, 10, -1, 100.0, 4.0) == 22


# ------------------------------------------------------------------------------------------------ X4
def test_noprog_fires_only_without_progress():
    a = flat_idx()
    assert RU.noprog_row(a, 50, +1, 100.0, 4.0) == 59           # no move: sell after the 10th minute
    a[3, 59] = 101.5                                             # +1.5 >= 0.25 x 4 = 1.0: kept
    assert RU.noprog_row(a, 50, +1, 100.0, 4.0) == -1
    a[3, 60:] = 0.0                                              # later bars never read
    assert RU.noprog_row(a, 50, +1, 100.0, 4.0) == -1
    a[3, 59] = np.nan
    assert RU.noprog_row(a, 50, +1, 100.0, 4.0) == -1            # no close: no decision
    assert RU.noprog_row(a, 370, +1, 100.0, 4.0) == -1           # past the session


def test_noprog_put():
    a = flat_idx()
    a[3, 59] = 98.5
    assert RU.noprog_row(a, 50, -1, 100.0, 4.0) == -1
    a[3, 59] = 99.5
    assert RU.noprog_row(a, 50, -1, 100.0, 4.0) == 59


# ------------------------------------------------------------------------------------------------ X11
def test_struct_flags_needs_both_and_bar_end():
    a = flat_idx()
    C = opt(50.0)
    a[3, 60:65] = 103.0                        # the 5-min bar rows 60..64 closes 3 above the level (0.5 x 4 = 2)
    f = RU.struct_flags(a, C, 50, +1, 100.0, 4.0, 50.0)
    assert not f.any()                         # option only at the fill: no flag
    C[3, 64] = 51.6                            # +3.2%
    f = RU.struct_flags(a, C, 50, +1, 100.0, 4.0, 50.0)
    assert np.flatnonzero(f).tolist() == [64]  # judged on the bar's last minute
    b, D = a.copy(), C.copy()
    b[:, 65:] = 0
    D[:, 65:] = 0
    assert (RU.struct_flags(b, D, 50, +1, 100.0, 4.0, 50.0)[:65] == f[:65]).all()
    assert not RU.struct_flags(a, C, 70, +1, 100.0, 4.0, 50.0).any()    # bars before the fill are ignored


# ------------------------------------------------------------------------------------------------ X7
def test_pyramid_trigger():
    a = flat_idx()
    C = opt(50.0)
    a[1, 80] = 105.0                           # 50% of the way to 110
    assert RU.pyramid_row(a, C, 60, 200, +1, 100.0, 110.0, 50.0) == -1   # option not above the fill
    C[3, 80] = 52.0
    assert RU.pyramid_row(a, C, 60, 200, +1, 100.0, 110.0, 50.0) == 80
    assert RU.pyramid_row(a, C, 60, 81, +1, 100.0, 110.0, 50.0) == -1    # too close to the base exit
    assert RU.pyramid_row(a, C, 60, 200, +1, 100.0, float("nan"), 50.0) == -1
    assert RU.pyramid_row(a, C, 60, 200, +1, 100.0, 95.0, 50.0) == -1    # target behind the entry
    b = a.copy()
    b[:, 81:] = 0
    assert RU.pyramid_row(b, C, 60, 200, +1, 100.0, 110.0, 50.0) == 80


# ------------------------------------------------------------------------------------------------ forced rows, X6
def test_forced_matches_rules():
    a = flat_idx()
    a[2, 100] = 94.0                           # index stop 95 touched at 100 -> sell at 101
    assert RU.forced(a, 60, +1, 95.0, 120.0, -1, N) == 101
    assert RU.forced(a, 60, +1, 95.0, 120.0, 90, N) == 90      # failed break first
    a[1, 70] = 121.0
    assert RU.forced(a, 60, +1, 95.0, 120.0, -1, N) == 71      # target first
    assert RU.forced(flat_idx(), 60, +1, 95.0, np.nan, -1, N) == -1
    assert RU.index_stop_hit(a, 60, +1, 95.0) == 100


def test_reentry_row_window_room_and_lookahead():
    a = flat_idx(99.0)
    a[3, 109] = 101.0                          # the 5-min bar ending at 110 closes back above the level 100
    assert RU.reentry_row(a, 100, +1, 100.0, 130.0, 10.0, 285) == 110
    assert RU.reentry_row(a, 100, +1, 100.0, 105.0, 10.0, 285) == -1    # room 4 < 10
    assert RU.reentry_row(a, 100, +1, 100.0, np.nan, 10.0, 285) == 110  # no target: no room check
    assert RU.reentry_row(a, 75, +1, 100.0, 130.0, 10.0, 285) == -1     # more than 30 minutes after the exit
    assert RU.reentry_row(a, 100, +1, 100.0, 130.0, 10.0, 105) == -1    # after the 14:00 cut-off
    b = a.copy()
    b[:, 110:] = 0
    assert RU.reentry_row(b, 100, +1, 100.0, 130.0, 10.0, 285) == 110
    a[3, 110:] = 101.0
    a[3, 119] = 99.0
    assert RU.failed_break_row(a, 110, +1, 100.0) == 120


# ------------------------------------------------------------------------------------------------ X10
def test_overnight_px_gtt_gap_and_open():
    C2 = opt(60.0)
    assert RU.overnight_px(C2, 50.0) == (60.0, 1, "open")
    C2[2, 0] = 49.0                            # trades through the GTT at 50 in the first minute
    assert RU.overnight_px(C2, 50.0) == (50.0, 0, "gtt")
    C2[0, 0] = 45.0                            # gapped below: fills at the open
    assert RU.overnight_px(C2, 50.0) == (45.0, 0, "gtt")
    C3 = opt(60.0)
    C3[0, :6] = np.nan
    C3[2, 0] = np.nan
    px, row, how = RU.overnight_px(C3, 50.0)
    assert np.isnan(px) and row == -1          # no bar: keep B0


# ------------------------------------------------------------------------------------------------ stats helpers
def test_flip_p_and_top5():
    rng = np.random.default_rng(1)
    assert RU.flip_p(np.array([]), 100, rng) == 1.0
    assert RU.flip_p(np.full(30, 10.0), 500, rng) < 0.01
    assert RU.flip_p(np.full(30, -10.0), 500, rng) > 0.99
    assert RU.top5_ok([100, 50, 40, 30, 20, 10, -5]) == 5
    assert RU.top5_ok([100, 1]) == 0
    assert RU.top5_ok([]) == 0


@pytest.mark.parametrize("sgn", [1, -1])
def test_rules_ignore_bars_after_decision_random(sgn):
    """Random walks: corrupting every bar after each rule's decision minute never changes the decision."""
    rng = np.random.default_rng(81 + sgn)
    for _ in range(50):
        c = 100 + np.cumsum(rng.normal(0, 1, N))
        o = np.r_[100, c[:-1]]
        a = np.vstack([o, np.maximum(o, c) + rng.random(N), np.minimum(o, c) - rng.random(N), c])
        ef, i0, atr = 30, float(a[0, 30]), 3.0
        m = RU.chandelier_row(a, ef, sgn, i0, atr)
        if m >= 0:
            b = a.copy()
            b[:, m + 1:] = rng.normal(0, 1000, (4, N - m - 1))
            assert RU.chandelier_row(b, ef, sgn, i0, atr) == m
        m = RU.noprog_row(a, ef, sgn, i0, atr)
        b = a.copy()
        b[:, ef + 10:] = 0
        assert RU.noprog_row(b, ef, sgn, i0, atr) == m
