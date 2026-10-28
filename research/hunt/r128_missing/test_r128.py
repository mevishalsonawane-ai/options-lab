"""R128 tests: straddle exits (time, close, stop/trail), convex exits on a single option, periods.

    python -m pytest research/hunt/r128_missing/test_r128.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r128 as R  # noqa: E402

D = date(2025, 6, 10)


def _opt(price=100.0):
    a = np.full((R.N, 5), np.nan)
    a[:, :4] = price
    a[:, 4] = 6500
    return a


def test_straddle_time_and_close_exits():
    c, p = _opt(), _opt()
    a = R.straddle(c, p, 10, 364, 65, D, "NIFTY", "a")
    b = R.straddle(c, p, 10, 364, 65, D, "NIFTY", "b")
    assert a["exit"] == 70 and b["exit"] == 364 and a["net"] < 0 and a["charges"] > 0


def test_straddle_stop_and_trail():
    c, p = _opt(), _opt()
    c[30:, :4] = 30.0                                   # combined 130 < 0.65 x 200 -> stop
    s = R.straddle(c, p, 10, 364, 65, D, "NIFTY", "c")
    assert s["why"] == "stop" and s["exit"] == 30
    c, p = _opt(), _opt()
    c[20:, :4] = 160.0                                  # combined 260 = +30% -> peak
    c[40:, :4] = 115.0                                  # combined 215 <= 0.85 x 260 -> trail
    s = R.straddle(c, p, 10, 364, 65, D, "NIFTY", "c")
    assert s["why"] == "trail" and s["exit"] == 40 and s["net"] > 0


def test_convex_exit_trail_and_hold():
    a = _opt(100.0)
    a[20:, :4] = 140.0                                  # +40 x 65 = +2,600 gross
    a[30:, :4] = 120.0                                  # falls below 60% of the peak net -> trail
    r = R.convex_exit(a, 10, 364, 65, D, "NIFTY", 1000.0, 2000.0, "x1")
    assert r["why"] == "trail" and r["exit"] == 30 and r["net"] > 0
    r2 = R.convex_exit(a, 10, 364, 65, D, "NIFTY", 1000.0, 2000.0, "x2")
    assert r2["why"] == "close" and r2["exit"] == 364
    b = _opt(100.0)
    b[25, 2] = 50.0                                     # a low wick through the -Rs 2,000 stop
    assert R.convex_exit(b, 10, 364, 65, D, "NIFTY", 1000.0, 2000.0, "x2")["why"] == "stop"


def test_periods():
    assert R.period_of(date(2023, 5, 1)) == "F1" and R.period_of(date(2026, 5, 1)) == "holdout"
    assert R.period_of(date(2026, 3, 20)) is None
