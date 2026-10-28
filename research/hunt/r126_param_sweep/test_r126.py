"""R126 tests: the 118-variant grid, past-only series indicators (BB, MACD, RSI, Supertrend, EMA, crosses, VWAP) on
completed candles, parameterised sweeps and value areas, and the screening/rule helpers.

    python -m pytest research/hunt/r126_param_sweep/test_r126.py -q
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r126feat as F  # noqa: E402


class _G:
    pass


def _index(seed=0, days=6):
    rng = np.random.default_rng(seed)
    c = 25000 + rng.normal(0, 8, (days, F.N)).cumsum(1)
    I = _G()
    I.g = _G()
    I.g.h, I.g.l = c + 3, c - 3
    return I


def test_grid_counts():
    assert sum(len(v) for v in F.VARIANTS.values()) == 118
    assert len(F.ALL_COLS) == len(set(F.ALL_COLS))
    assert set(F.FAMILY_COLS) == set(F.VARIANTS)


def test_series_features_are_past_only():
    I = _index()
    v = np.abs(np.random.default_rng(1).normal(1000, 100, I.g.h.size))
    i, t = 4, 154
    R = pd.DataFrame(dict(i=[i], t=[t], m=[30.0], lo=[I.g.l[i, t]], hi=[I.g.h[i, t]]))
    a = F.series_features(I, v, R)
    I.g.h = I.g.h.copy()
    I.g.l = I.g.l.copy()
    I.g.h[i, t + 1:] += 400
    I.g.l[i, t + 1:] += 400
    I.g.h[i + 1:] += 400
    I.g.l[i + 1:] += 400
    v2 = v.copy()
    v2[i * F.N + t + 1:] *= 50
    b = F.series_features(I, v2, R)
    for k in a:
        x, y = a[k][0], b[k][0]
        assert (np.isnan(x) and np.isnan(y)) or x == pytest.approx(y, rel=1e-9, abs=1e-9), k


def test_series_features_read_only_completed_candles():
    """At bar t = 152 the 5-minute candle 150..154 is still open: changing bar 151 changes no tf=5 input, while
    changing bar 149 (in the last completed candle 145..149) does change some."""
    I = _index(2)
    v = np.ones(I.g.h.size) * 1000
    i, t = 3, 152
    R = pd.DataFrame(dict(i=[i], t=[t], m=[30.0], lo=[I.g.l[i, t]], hi=[I.g.h[i, t]]))
    a = F.series_features(I, v, R)
    tf5 = [k for k in a if "tf5" in k]
    I.g.h[i, 151] += 300
    b = F.series_features(I, v, R)
    for k in tf5:
        assert (np.isnan(a[k][0]) and np.isnan(b[k][0])) or a[k][0] == pytest.approx(b[k][0]), k
    I.g.h[i, 149] += 300
    c = F.series_features(I, v, R)
    assert any(not np.isclose(a[k][0], c[k][0], equal_nan=True) for k in tf5)


def test_cidx_and_tf_series():
    h = np.arange(2 * F.N, dtype=float).reshape(2, F.N)
    H, L = F.tf_series(h, h - 1, 5)
    assert H[0] == 4 and L[0] == -1 and len(H) == 2 * F.N // 5
    assert F.cidx(np.array([1]), np.array([4]), 5)[0] == F.N // 5
    assert F.cidx(np.array([1]), np.array([3]), 5)[0] == F.N // 5 - 1     # the previous session's last candle


def test_age_since_change_and_rsi():
    a = F.age_since_change(np.array([1.0, 1, -1, -1, -1, 1]))
    assert list(a) == [120, 120, 0, -1, -2, 0]                    # 0 on the bar of the change, capped before any
    r = F.rsi_series(np.arange(1.0, 60.0), 14)
    assert r[-1] == 100.0


def test_sweeps_param_failure_bars():
    h = np.full(60, 100.5)
    l = np.full(60, 99.5)
    h[40] = 107.0
    lv = lambda j: [105.0]                                                   # noqa: E731
    no = lambda j: []                                                        # noqa: E731
    assert F.sweeps_p(h, l, 41, lv, no, 30.0, 1, 10)["hi"] == 1.0           # 1 failure bar: known at 41
    assert F.sweeps_p(h, l, 41, lv, no, 30.0, 3, 10)["hi"] == 0.0           # 3 failure bars: not yet
    assert F.sweeps_p(h, l, 55, lv, no, 30.0, 1, 10)["hi"] == 0.0           # outside a 10-bar look-back
    assert F.sweeps_p(h, l, 55, lv, no, 30.0, 1, 30)["hi"] == 1.0


def test_profile_value_area_widths():
    hist = np.zeros(60)
    hist[20], hist[21], hist[22], hist[30] = 50, 30, 15, 5
    a = F.profile_va(hist, 5.0, 150.0, 151.0, 30.0, 0.6)
    b = F.profile_va(hist, 5.0, 150.0, 151.0, 30.0, 0.95)
    assert a["vah"] < b["vah"]                                               # a wider value area reaches further up
    assert a["poc"] == pytest.approx((150.0 - 102.5) / 30)
