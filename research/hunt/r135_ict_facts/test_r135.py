"""Tests for R135's measuring tools (PREREG.md): the race, the tests, the period levels, the look-ahead of levels."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r135 as F  # noqa: E402

N = F.N


def test_race_up_down_neither_and_same_minute():
    h = np.full(N, 100.1)
    l = np.full(N, 99.9)
    assert F.race(h, l, 0, 100.0, 1) is None
    h2 = h.copy()
    h2[10] = 100.3
    assert F.race(h2, l, 0, 100.0, 1) == 1 and F.race(h2, l, 0, 100.0, -1) == 0
    l2 = l.copy()
    l2[10] = 99.7
    assert F.race(h2, l2, 0, 100.0, 1) == 0          # both in one minute: a loss
    assert F.race(h2, l, 11, 100.0, 1) is None        # starts after the move


def test_z_tests():
    a = [1] * 70 + [0] * 30
    b = [1] * 50 + [0] * 50
    t = F.z2(a, b)
    assert t["p"] < 0.01 and t["rate1"] == 0.7 and t["rate2"] == 0.5
    assert F.z2([None, 1], [])["p"] == 1.0
    assert F.z1(50, 100, 0.5)["p"] == 1.0 and F.z1(70, 100, 0.5)["p"] < 0.001


def test_period_levels_use_only_completed_periods():
    days = [date(2026, 1, 5) + timedelta(days=i) for i in range(21)]      # Mon 5 Jan .. 25 Jan
    days = [d for d in days if d.weekday() < 5]
    D = len(days)
    h = np.zeros((D, N)) + np.arange(D)[:, None] + 100.0
    l = h - 1.0
    lv, dh, dl = F.period_levels(days, h, l)
    assert np.isnan(lv[0, 0]) and lv[1, 0] == 100.0 and lv[1, 1] == 99.0            # PDH / PDL = yesterday
    assert np.isnan(lv[4, 2])                                                       # first week: no previous week
    assert lv[5, 2] == dh[4] and lv[5, 3] == dl[0]                                  # week 2 sees week 1 only
    assert np.isnan(lv[-1, 4])                                                      # January: no previous month


def test_race_on_mirrored_negative_prices():
    h = np.full(N, 100.1)
    l = np.full(N, 99.9)
    l[10] = 99.7                                   # a real drop of 0.3%
    # mirrored: high -> -low, low -> -high; a real drop is a mirrored rise, so a mirrored "up" race wins
    assert F.race(-l, -h, 0, -100.0, 1) == 1
    assert F.race(-l, -h, 0, -100.0, -1) == 0
