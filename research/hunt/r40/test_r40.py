"""R40 tests: python3 -m pytest research/hunt/r40/test_r40.py -q"""
from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r40flags as Z  # noqa: E402
import r26lib as L26  # noqa: E402
from r25data import N, minute  # noqa: E402
from test_r25 import make_day, rally  # noqa: E402


def _series(n=200, seed=1):
    rng = np.random.default_rng(seed)
    c = 100 + np.cumsum(rng.normal(0, 0.5, n))
    o = np.r_[c[0], c[:-1]]
    h = np.maximum(o, c) + rng.uniform(0, 0.3, n)
    l = np.minimum(o, c) - rng.uniform(0, 0.3, n)
    return o, h, l, c


def test_library_reads_the_past_only():
    o, h, l, c = _series()
    full = Z.library(o, h, l, c, (), vwap_ref=np.full(len(c), 100.0))
    t = 150
    o2, h2, l2, c2 = (x.copy() for x in (o, h, l, c))
    for x in (o2, h2, l2, c2):
        x[t + 1:] = 1e4
    cut = Z.library(o2, h2, l2, c2, (), vwap_ref=np.full(len(c), 100.0))
    for k in full:
        assert bool(full[k][t]) == bool(cut[k][t]), k


def test_candle_patterns():
    o = np.array([10, 10, 9.0, 8.5, 10.0])
    c = np.array([10, 9, 8.6, 10.2, 10.0])
    h = np.maximum(o, c) + 0.05
    l = np.minimum(o, c) - 0.05
    f = Z.library(o, h, l, c, (), vwap_ref=None)
    assert f["c_engulf_bull"][3] and not f["c_engulf_bull"][2]
    assert f["run_red2"][2] and f["c_doji"][4]


def test_index_put_flags_are_the_mirror():
    o, h, l, c = _series()
    call = Z.library(o, h, l, c, ())
    put_of_mirror = Z.library(-o, -l, -h, -c, ())
    assert np.array_equal(call["ema9_21_above"][30:], ~put_of_mirror["ema9_21_above"][30:] | (np.isclose(Z.ema(c, 9), Z.ema(c, 21))[30:]))
    assert np.array_equal(call["run_green2"], put_of_mirror["run_red2"])


def test_moves_are_signed_toward_the_option():
    c = np.r_[np.full(10, 100.0), np.full(10, 99.0)]
    up = Z.moves(c, 1, (0.005,))
    dn = Z.moves(c, -1, (0.005,))
    assert up["mv5_dn0.5"][12] and dn["mv5_up0.5"][12]


def test_volume_profile_and_oi_flags():
    c = np.r_[np.full(Z.X.T0 + 5, 10.0), np.full(10, 12.0)]
    v = np.r_[np.full(Z.X.T0 + 5, 100.0), np.full(10, 1.0)]
    f = Z.volume_flags(c, v, c, c)
    assert f["vprof_above_poc"][-1] and not f["vprof_below_poc"][-1]
    oi = np.r_[np.full(20, 1000.0), np.full(20, 1100.0)]
    g = Z.oi_flags(oi)
    assert g["oi1_up5"][20] and not g["oi1_up5"][21]


def test_day_flags_rows_and_no_future():
    day = make_day(rally(start=minute(13, 30)))
    keys, flags, names, so, si = Z.day_flags(day)
    assert len(keys) == len(flags) == len(so) == len(si) and flags.shape[1] == len(names) > 250
    t = keys[len(keys) // 2][2]
    fut = L26.poisoned(day, t)
    k2, f2, n2, _, _ = Z.day_flags(fut)
    pos = {k: i for i, k in enumerate(k2)}
    for i, k in enumerate(keys):
        if k[2] == t and k in pos:
            assert np.array_equal(flags[i], f2[pos[k]])
