"""R17 unit tests on synthetic data (no release files needed): python3 -m pytest research/hunt/r17/test_r17.py"""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import arms as A  # noqa: E402
import run as R  # noqa: E402


def test_atm_delta_is_about_half_and_iv_round_trips():
    d = A.bs_delta(50000, 50000, 3 * 1440, 0.14, True)
    assert 0.45 < d < 0.6
    t = 3 * 1440 / (365 * 1440)
    p = A.bs_price(50000, 50000, t, 0.14, True)
    iv = A.implied_vol(p, 50000, 50000, 3 * 1440, True)
    assert abs(iv - 0.14) < 1e-4
    assert A.implied_vol(0.0, 50000, 50000, 100, True) is None


def test_delta_falls_back_to_vix_when_no_iv_solves():
    assert A.delta_of(50000, 50000, 1440, -1, 14.0, False) == A.bs_delta(50000, 50000, 1440, 0.14, False)


def test_cost_per_unit_matches_charges_plus_slippage():
    c = A.cost_per_unit(400.0, 30)
    assert 2.0 < c < 6.0                       # ~Rs 75 round trip / 30 units + 2 x (0.20 + 0.05)


def test_smart_points_floor_and_nan():
    assert A.smart_pts(1.0, 100.0, 0.5) == 50.0
    assert A.smart_pts(1.0, 0.01, 0.01) == 2 * A.TICK
    assert np.isnan(A.smart_pts(1.0, np.nan, 0.5))


def _path(prices):
    a = np.full((375, 5), np.nan)
    for r, (o, h, l, c) in enumerate(prices):
        a[r] = (o, h, l, c, 1000)
    return a


def test_fixed_stop_fills_at_trigger_and_target_sells_next_open():
    flat = [(100, 101, 99, 100)] * 375
    a = _path(flat[:10] + [(100, 100, 50, 55)] + flat[11:])
    x, r, why = A.sim_fixed_pts(a, 1, 100.0, 40.0, 40.0, 30, 30)
    assert why == "stop" and r == 10 and x < 60
    b = _path(flat[:10] + [(100, 145, 99, 140), (141, 142, 140, 141)] + flat[12:])
    x, r, why = A.sim_fixed_pts(b, 1, 100.0, 40.0, 40.0, 30, 30)
    assert why == "target" and r == 11 and 139 < x < 141


def test_danger_buckets_use_trailing_cuts():
    n = 130
    df = pd.DataFrame(dict(day=[date(2025, 1, 1)] * n, gap=np.linspace(0, 1, n), vix=np.linspace(10, 20, n),
                           vix_chg=[0.1] * n, event=[False] * n, expiry=[True] * n, dow=[0] * n, net=[0.0] * n))
    b = R.buckets(df, 125)
    assert b["gap"] == "large" and b["vix"] == "high" and b["vix_chg"] == "up" and b["expiry"] == "yes" and b["dow"] == "Mon"
