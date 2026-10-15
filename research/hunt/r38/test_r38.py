"""R38 tests: python3 -m pytest research/hunt/r38/test_r38.py -q"""
from __future__ import annotations

import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r38trail as T  # noqa: E402
import r25lib as R  # noqa: E402
from r25data import C, H, L, N, O, OI, V, minute  # noqa: E402
from test_r25 import flat, make_day  # noqa: E402

K = 25100.0


def day_with(close, high=None, lot=25):
    day = make_day(flat())
    day.lot = lot
    c = np.asarray(close, dtype=float)
    a = np.full((N, 7), np.nan)
    a[:, C] = c
    a[:, O] = np.r_[c[0], c[:-1]]
    a[:, H] = np.maximum(a[:, O], a[:, C]) if high is None else np.fmax(np.maximum(a[:, O], a[:, C]), high)
    a[:, L] = np.minimum(a[:, O], a[:, C])
    a[:, V] = 5000.0
    a[:, OI] = 1e5
    a[:, 6] = 20.0
    day.opts[(K, "C")] = a
    return day


def test_stop_from_entry_cuts_a_loser_at_minus_25():
    px = np.full(N, 20.0)
    px[210:] = 14.9                                  # <= 0.75 x 20.06 = 15.05
    t = T.simulate(day_with(px), K, 1, 200, 20.0, 2000.0)
    assert t.exits == [(211, t.qty, R.sell_px(14.9, 1000.0), "trail")]


def test_trail_follows_the_confirmed_peak_and_sells_next_open():
    px = np.full(N, 20.0)
    px[210:220] = 100.0                              # peak 100: stop 75
    px[220:] = 74.0
    t = T.simulate(day_with(px), K, 1, 200, 20.0, 2000.0)
    assert t.exits[-1][:3] == (221, t.qty, R.sell_px(74.0, 1000.0)) and t.net > 0


def test_a_freak_high_does_not_raise_the_stop_but_raw_mode_does():
    px = np.full(N, 20.0)
    hi = np.full(N, np.nan)
    hi[210] = 200.0                                  # one print; close and next open stay 20
    t = T.simulate(day_with(px, hi), K, 1, 200, 20.0, 2000.0)
    assert t.exits[-1][3] == "end" and t.exits[-1][0] == minute(15, 5)
    t = T.simulate(day_with(px, hi), K, 1, 200, 20.0, 2000.0, arm="high")
    assert t.exits[-1][3] == "trail" and t.exits[-1][0] == 212


def test_stop_never_acts_in_the_bar_it_is_raised():
    px = np.full(N, 20.0)
    hi = np.full(N, np.nan)
    px[210] = 30.0
    hi[210] = 100.0                                  # confirmed only to max(close 30, next open 30) = 30
    px[211:] = 22.0                                  # 22 <= 0.75 x 30 = 22.5: sells at 212's open
    t = T.simulate(day_with(px, hi), K, 1, 200, 20.0, 2000.0)
    assert t.exits[-1][0] == 212


def test_end_of_day_and_size_rules():
    t = T.simulate(day_with(np.full(N, 20.0)), K, 1, 200, 20.0, 2000.0)
    assert t.exits[-1][0] == minute(15, 5) and t.exits[-1][3] == "end"
    t = T.simulate(day_with(np.full(N, 20.0)), K, 1, 200, 20.0, 2000.0, end_row=minute(15, 25))
    assert t.exits[-1][0] == minute(15, 25)
    assert T.simulate(day_with(np.full(N, 20.0), lot=150), K, 1, 200, 20.0, 2000.0, skip_over=True) is None
    assert T.simulate(day_with(np.full(N, 20.0), lot=150), K, 1, 200, 20.0, 2000.0).lots == 1
    assert R.EXIT_ROW == minute(15, 5)               # the K rules' longer entry window is restored
