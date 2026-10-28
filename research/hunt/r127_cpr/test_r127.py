"""R127 tests: CPR levels, the wick pullback-hold-bounce setup, wick exits (stop first), and option pricing.

    python -m pytest research/hunt/r127_cpr/test_r127.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r127cpr as C  # noqa: E402


def test_levels_formula():
    lv = C.levels(110.0, 90.0, 105.0)
    p = 305 / 3
    assert lv["P"] == pytest.approx(p)
    assert lv["bottom"] == pytest.approx(100.0) and lv["top"] == pytest.approx(2 * p - 100)
    assert lv["R1"] == pytest.approx(2 * p - 90) and lv["S1"] == pytest.approx(2 * p - 110)
    lv2 = C.levels(110.0, 90.0, 92.0)                       # close below the middle: TC < BC, so top / bottom swap
    assert lv2["top"] == pytest.approx(100.0) and lv2["bottom"] < lv2["top"]


def _day(base=105.0):
    h = np.full(C.N, base + 0.5)
    l = np.full(C.N, base - 0.5)
    return h, l, (h + l) / 2


def test_setup_needs_touch_hold_and_bounce_on_wicks():
    top, bottom = 102.0, 100.0
    h, l, o = _day()
    l[12] = 102.02                                          # 5-min candle 10..14: low touches top (within 0.03%)
    h[16] = 106.0                                           # bounce: a high above that candle's high (105.5)
    assert C.find_setup(h, l, top, bottom, 1, 5) == 16
    h2, l2, o2 = _day()
    l2[12] = 99.5                                           # broke below bottom: does not hold
    h2[16] = 106.0
    assert C.find_setup(h2, l2, top, bottom, 1, 5) is None
    h3, l3, o3 = _day()
    l3[12] = 102.02
    h3[40] = 106.0                                          # bounce too late (beyond 3 candles)
    assert C.find_setup(h3, l3, top, bottom, 1, 5) is None


def test_setup_mirror_for_sells():
    top, bottom = 100.0, 98.0
    h, l, o = _day(95.0)
    h[12] = 97.99                                           # high touches bottom from below, stays under top
    l[16] = 94.0
    assert C.find_setup(h, l, top, bottom, -1, 5) == 16


def test_exit_stop_first_and_target_and_time():
    h, l, o = _day()
    h[30], l[30] = 120.0, 90.0                              # both in one minute: the stop
    assert C.exit_path(h, l, o, 20, 1, 99.0, 110.0, 364)[1] == "stop"
    h2, l2, o2 = _day()
    h2[30] = 111.0
    r, why, px = C.exit_path(h2, l2, o2, 20, 1, 99.0, 110.0, 364)
    assert (r, why, px) == (30, "target", 110.0)
    h3, l3, o3 = _day()
    assert C.exit_path(h3, l3, o3, 20, 1, 99.0, 110.0, 364)[1] == "time"


def test_option_trade_prices_traded_bars_only():
    a = np.full((C.N, 5), np.nan)
    a[:, :4] = 100.0
    a[:, 4] = 6500
    a[40:51] = np.nan
    a[39, 3] = 110.0
    r = C.option_trade(a, 20, 50, "time", 65, date(2025, 6, 10), "NIFTY")
    assert r[0] > 500                                       # sold at the last traded close (110) before minute 50
    a[20] = np.nan
    assert C.option_trade(a, 20, 50, "time", 65, date(2025, 6, 10), "NIFTY") is None
