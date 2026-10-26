"""R105D tests: contract choice, bar volume, both ratios, the look-ahead guard, grouping and the permutation p."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import r105d as V  # noqa: E402


def mins(v=10.0):
    return np.full(V.NROWS, float(v))


def test_active_contract_uses_previous_day_only():
    vol = {"OCT": {date(2026, 9, 1): 100.0, date(2026, 9, 2): 10.0}, "NOV": {date(2026, 9, 1): 50.0, date(2026, 9, 2): 500.0}}
    assert V.active_contract(vol, date(2026, 9, 2)) == "OCT"           # 1 Sep decides, not the day itself
    assert V.active_contract(vol, date(2026, 9, 3)) == "NOV"
    assert V.active_contract(vol, date(2026, 9, 1)) is None


def test_bar_vol():
    m = mins(2.0)
    assert V.bar_vol(m, 10) == 10.0 and np.isnan(V.bar_vol(m, 4))
    m[5:10] = np.nan
    assert np.isnan(V.bar_vol(m, 10))


def test_ratio_tod_and_warmup():
    days = [mins(10.0) for _ in range(5)] + [mins(30.0)]
    assert V.ratio_tod(days, 20) == pytest.approx(3.0)
    assert np.isnan(V.ratio_tod(days[2:], 20))                         # only 3 earlier sessions


def test_ratio_20():
    today = mins(10.0)
    today[95:100] = 40.0                                               # the signal bar, rows 95-99 (er 100)
    days = [mins(10.0), today]
    assert V.ratio_20(days, 100) == pytest.approx(200.0 / 50.0)


def test_lookahead_guard():
    rng = np.random.default_rng(0)
    for k in range(50):
        days = [rng.uniform(1, 100, V.NROWS) for _ in range(12)]
        er = int(rng.integers(10, 370))
        a, b = V.ratio_tod(days, er), V.ratio_20(days, er)
        d2 = [x.copy() for x in days]
        d2[-1][er:] = rng.uniform(1000, 5000, V.NROWS - er)            # the entry minute and later
        assert V.ratio_tod(d2, er) == pytest.approx(a) and V.ratio_20(d2, er) == pytest.approx(b)


def test_group_and_perm():
    assert V.group(1.5) == "high" and V.group(1.49) == "low" and V.group(float("nan")) == "unknown"
    assert V.perm_p([100.0] * 30, [-100.0] * 30, n=500) < 0.01
    assert V.perm_p([-100.0] * 30, [100.0] * 30, n=500) > 0.9
    assert V.perm_p([], [1.0]) == 1.0
    s = V.summ([1.0, -2.0, 3.0])
    assert s["n"] == 3 and s["win"] == pytest.approx(0.6667, abs=1e-4) and s["net"] == 2
