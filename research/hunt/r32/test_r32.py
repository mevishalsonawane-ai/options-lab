"""R32 tests: python3 -m pytest research/hunt/r32/test_r32.py -q"""
from __future__ import annotations

import math
import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r32feat as X  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r26lib as L26  # noqa: E402
from r25data import N, minute  # noqa: E402
from test_r25 import make_day, rally  # noqa: E402


def _same(a, b):
    return a == b or (isinstance(a, float) and math.isnan(a) and isinstance(b, float) and math.isnan(b)) or \
        (np.isfinite(a) and np.isfinite(b) and abs(a - b) <= 1e-6 * max(1, abs(a)))


def test_helpers_read_the_past_only():
    x = np.r_[np.arange(10.0), 99.0]
    assert X.lag(x, 2)[5] == 3.0 and np.isnan(X.lag(x, 2)[1])
    assert X.ema(x, 3)[9] == X.ema(x[:10], 3)[9]
    assert X.roll(x, 3, "max")[9] == 9.0
    assert list(X.streak(np.array([1, 1, 0, 1], bool))) == [1, 2, 0, 1]
    assert np.isnan(X.since(np.array([0, 1, 0], bool))[0]) and X.since(np.array([0, 1, 0], bool))[2] == 1
    assert list(X.ffill(np.array([np.nan, 1.0, np.nan, 2.0]))[1:]) == [1.0, 1.0, 2.0]


def test_adx_rises_in_a_trend():
    c = np.r_[np.full(40, 100.0) + np.sin(np.arange(40)), np.linspace(100, 160, 60)]
    a = X.adx(c + 0.5, c - 0.5, c)
    assert a[-1] > 40 and (np.isnan(a[20]) or a[20] < a[-1])


def test_option_features_read_no_future_and_labels_do():
    day = make_day(rally(start=minute(13, 30)))
    k, s = 25150.0, "C"
    a = day.opts[(k, s)]
    full = X.option_block(a)
    t = R.close_row(13, 20)
    b = a.copy()
    b[t + 1:] = 1e6
    cut = X.option_block(b)
    for key, arr in full.items():
        if key.startswith("_fut"):
            continue
        assert _same(float(arr[t]), float(cut[key][t])), key
    assert full["_fut"][t] != cut["_fut"][t]                 # the label is hindsight


def test_index_and_chain_blocks_read_no_future():
    day = make_day(rally(start=minute(13, 30)))
    ib = X.index_block(day)
    t = R.close_row(13, 10)
    fut = L26.poisoned(day, t)
    ib2 = X.index_block(fut)
    for key in ib:
        if key.startswith("_newhi") or key.startswith("_newlo") or key == "_spot" or not key.startswith("_"):
            assert _same(float(ib[key][t]), float(ib2[key][t])), key
    ratios = np.ones(len(X.F.DECISIONS))
    cb = X.chain_block(day, ratios)
    cb2 = X.chain_block(fut, ratios)
    j = int(t - X.T0)
    for key in cb:
        assert _same(float(cb[key][j]), float(cb2[key][j])), key


def test_rows_only_otm_rs_1_to_30_traded():
    day = make_day(rally(start=minute(13, 30)))
    df = X.day_rows((day, np.ones(len(X.F.DECISIONS))))
    assert len(df) and df.price.between(1, 30).all()
    calls = df[df.side == "C"]
    assert (calls.strike > 25000 - 1e-9).all()
    assert df.t.between(X.T0, X.T1).all()


def test_label_ignores_an_untraded_bar():
    """An untraded bar (no high) never takes the next bar's open as a confirmed price."""
    day = make_day(rally(start=minute(13, 30)))
    a = day.opts[(25150.0, "C")].copy()
    t = R.close_row(13, 0)
    a[t + 1, :] = np.nan                                  # no trade
    a[t + 2, 0] = 1e4                                     # a freak open, closing back
    a[t + 2, 1] = 1e4
    a[t + 3, 0] = a[t + 2, 3]
    fut = X.option_block(a)["_fut"][t]
    assert fut < 1e4
