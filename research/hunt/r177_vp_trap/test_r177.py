"""R177 tests (synthetic data only): the TPO profile, PDH / PDL, the trap exit row, the failed-break skip, the earlier-of
forced rows, the fill row, the selection twin, and the look-ahead guard."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r177lib as L  # noqa: E402

N = 375


def _day(lo, hi, n=N):
    """A session whose every minute spans [lo, hi] (arrays broadcast)."""
    lo = np.broadcast_to(np.asarray(lo, float), (n,)).copy()
    hi = np.broadcast_to(np.asarray(hi, float), (n,)).copy()
    mid = (lo + hi) / 2
    return np.vstack([mid, hi, lo, mid])


# ------------------------------------------------------------------------------------------------ profile
def test_tpo_poc_and_value_area_on_a_peaked_profile():
    # 300 minutes in 100-110, 75 minutes spread 100-200: POC inside 100-110, value area near it
    lo = np.r_[np.full(300, 100.0), np.full(75, 100.0)]
    hi = np.r_[np.full(300, 109.0), np.full(75, 199.0)]
    lv = L.tpo_levels(_day(lo, hi), 50.0)                          # bin 10
    assert 100 <= lv["poc"] <= 110
    assert lv["val"] == 100.0 and lv["vah"] == 160.0                # 375 + 5 x 75 = 750 >= 70% of 1,050
    assert lv["pdh"] == 199.0 and lv["pdl"] == 100.0


def test_tpo_value_area_holds_at_least_70_percent():
    rng = np.random.default_rng(1)
    c = 20000 + np.cumsum(rng.normal(0, 5, N))
    idx = np.vstack([c, c + 4, c - 4, c])
    lv = L.tpo_levels(idx, 50.0)
    inside = ((idx[2] < lv["vah"]) & (idx[1] >= lv["val"])).mean()
    assert lv["val"] < lv["poc"] < lv["vah"] and inside >= 0.7


def test_tpo_tie_picks_bin_nearest_mid():
    lo = np.full(N, 100.0)
    hi = np.full(N, 139.0)                                         # 4 equal bins 100..140; mid 119.5 -> bin 110-120
    lv = L.tpo_levels(_day(lo, hi), 50.0)
    assert lv["poc"] == pytest.approx(115.0)


def test_tpo_needs_300_minutes():
    idx = _day(100.0, 110.0)
    idx[:, :76] = np.nan                                           # 299 valid
    assert L.tpo_levels(idx, 50.0) is None
    idx = _day(100.0, 110.0)
    idx[:, :75] = np.nan                                           # 300 valid
    assert L.tpo_levels(idx, 50.0) is not None


# ------------------------------------------------------------------------------------------------ trap exit
def test_trap_row_call_first_close_back_inside():
    c = np.full(N, 105.0)
    c[49] = 112.0                                                  # signal close above VAH 110
    c[50:53] = 113.0
    c[53] = 109.0                                                  # back inside at row 53 -> exit at 54
    assert L.trap_row(c, 50, 50, "C", 110.0) == 54


def test_trap_row_only_within_10_rows_and_only_beyond_at_signal():
    c = np.full(N, 113.0)
    c[60] = 109.0                                                  # row 60 = er + 10: outside the window
    assert L.trap_row(c, 50, 50, "C", 110.0) == -1
    c2 = np.full(N, 105.0)                                         # signal close not above VAH
    assert L.trap_row(c2, 50, 50, "C", 110.0) == -1


def test_trap_row_put_mirror_nan_and_fill_start():
    c = np.full(N, 95.0)
    c[49] = 88.0
    c[50] = np.nan
    c[51] = 91.0                                                   # back at / above VAL 90 -> exit at 52
    assert L.trap_row(c, 50, 50, "P", 90.0) == 52
    c[50] = 91.0                                                   # row 50 back inside, but fill was at 51
    assert L.trap_row(c, 50, 51, "P", 90.0) == 52                  # searches from the fill row on
    assert L.trap_row(c, 50, 50, "P", float("nan")) == -1


# ------------------------------------------------------------------------------------------------ skip
def test_failed_break_in_window():
    c = np.full(N, 105.0)
    c[80] = 112.0                                                  # above VAH 110
    c[85] = 108.0                                                  # back inside before the signal (row 100)
    assert L.failed_break(c, 100, "C", 110.0)
    assert not L.failed_break(c, 100, "P", 90.0)


def test_failed_break_ignores_outside_window_and_after_signal():
    c = np.full(N, 105.0)
    c[60] = 112.0                                                  # 40 rows before: outside the 30-row window
    c[65] = 108.0
    assert not L.failed_break(c, 100, "C", 110.0)
    c = np.full(N, 105.0)
    c[95:100] = 112.0                                              # above, and back inside only after the signal
    c[101] = 108.0
    assert not L.failed_break(c, 100, "C", 110.0)
    c = np.full(N, 112.0)                                          # stays above: a real break, not failed
    assert not L.failed_break(c, 100, "C", 110.0)


def test_earlier_and_fill_row():
    assert L.earlier(-1, 30) == 30 and L.earlier(25, 30) == 25 and L.earlier(-1, -1) == -1 and L.earlier(None, 5) == 5
    o = np.full(N, np.nan)
    o[52] = 10.0
    assert L.first_fill_row(o, 50, 360) == 52 and L.first_fill_row(o, 53, 360) == -1


def test_selection_twin():
    rng = np.random.default_rng(0)
    tw = L.selection_twin([1.0, 2.0, 3.0, 4.0], 2, 200, rng)
    assert tw.shape == (200,) and tw.min() >= 3.0 and tw.max() <= 7.0
    assert (L.selection_twin([1.0, 2.0], 0, 5, rng) == 3.0).all()
    assert (L.selection_twin([1.0, 2.0], 5, 5, rng) == 0.0).all()


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("seed", range(10))
def test_look_ahead_guard(seed):
    rng = np.random.default_rng(seed)
    c = 100 + np.cumsum(rng.normal(0, 1.5, N))
    er = 120
    level = float(np.median(c[100:119]))
    t = L.trap_row(c, er, er, "C", level)
    k = L.failed_break(c, er - 1, "C", level)
    cut = (t if t >= 0 else er + L.TRAP_ROWS)
    d = c.copy()
    d[cut:] = rng.normal(0, 100, N - cut)                          # noise after the decision row
    assert L.trap_row(d, er, er, "C", level) == t
    e = c.copy()
    e[er - 1:] = rng.normal(0, 100, N - er + 1) if not k else e[er - 1:]
    assert L.failed_break(e, er - 1, "C", level) == k


def test_amendment_a_counts():
    import r177study as R
    assert R.M_TOTAL == 3997 and R.M_TOTAL_A == 4000 and R.M_TOTAL_B == 4006 and R.M_TOTAL_C == 4012
