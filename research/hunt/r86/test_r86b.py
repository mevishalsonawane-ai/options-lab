"""R86b tests: synthetic data only. Basket direction and bursts, efficiency, same-slot medians (previous sessions
only), 5-minute bars, absorption, the overnight simulator, and a LOOK-AHEAD GUARD on every signal.

    python -m pytest research/hunt/r86/test_r86b.py -q
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r86 import r86blib as B

RNG = np.random.default_rng(1)


def test_efficiency_line_chop_and_empty():
    assert B.efficiency(100.0, np.array([101, 102, 103.0])) == pytest.approx(1.0)
    assert B.efficiency(100.0, np.array([101, 100, 101, 100.0])) == pytest.approx(0.0)
    assert np.isnan(B.efficiency(100.0, np.array([])))
    assert np.isnan(B.efficiency(100.0, np.array([100.0, 100.0])))     # no movement at all


def _eq():
    rows = []
    for s, px in (("A", [10, 11, 12]), ("B", [20, 21, 22]), ("C", [30, 29, 30])):
        for m, p in zip((555, 556, 557), px):
            rows.append(dict(symbol=s, day=pd.Timestamp("2024-01-02"), mod=m, close=float(p), volume=10.0))
    rows.append(dict(symbol="A", day=pd.Timestamp("2024-01-03"), mod=555, close=13.0, volume=1.0))
    return pd.DataFrame(rows)


def test_basket_minutes_counts_and_day_boundary():
    b = B.basket_minutes(_eq())
    r556 = b[(b["mod"] == 556) & (b.day == "2024-01-02")].iloc[0]
    assert (r556.up, r556.dn, r556.n) == (2, 1, 3)
    assert r556.tv == pytest.approx(10 * (11 + 21 + 29))
    first = b[(b["mod"] == 555) & (b.day == "2024-01-02")].iloc[0]
    assert first.n == 0                                                 # no previous close yet
    nxt = b[b.day == "2024-01-03"].iloc[0]
    assert nxt.n == 0                                                   # yesterday's close is not "previous minute"
    only = B.basket_minutes(_eq(), {"A"})
    assert set(only.tv.round(1)) <= {110.0, 120.0, 100.0, 13.0}


def test_burst_flags_rules():
    f = B.burst_flags(up=[9, 9, 5, 0, 9], dn=[1, 0, 5, 9, 0], n=[10, 10, 10, 9, 9], tv=[30, 30, 30, 30, 29],
                      tv_med=[10, 10, 10, 10, 10], n_min=8)
    assert list(f) == [1, 1, 0, -1, 0]                                 # last: turnover 2.9x < 3x
    assert list(B.burst_flags([9], [0], [5], [30], [10], n_min=8)) == [0]            # too few printed
    assert list(B.burst_flags([9], [0], [9], [30], [np.nan], n_min=8)) == [0]        # no median yet


def test_same_slot_median_uses_previous_sessions_only():
    days = pd.bdate_range("2024-01-01", periods=15)
    df = pd.DataFrame({"day": np.repeat(days, 2), "slot": np.tile([0, 1], 15), "x": np.arange(30.0)})
    med = B.same_slot_median(df, "x", lookback=3, min_obs=3)
    assert np.isnan(med[:6]).all()
    assert med[6] == pytest.approx(2.0)          # day 3, slot 0: previous slot-0 values 0, 2, 4
    df2 = df.copy()
    df2.loc[df2.day == days[-1], "x"] = 1e9      # today's own value never enters today's median
    np.testing.assert_allclose(B.same_slot_median(df2, "x", 3, 3), med, equal_nan=True)


def test_prev_quantile_excludes_today():
    x = pd.Series(np.arange(200.0))
    q = B.prev_quantile(x, 0.5, window=100, min_obs=50)
    assert np.isnan(q.iloc[49]) and q.iloc[150] == pytest.approx(np.median(np.arange(50, 150)))


def _bars_day(n=60, seed=0):
    g = np.random.default_rng(seed)
    mod = 555 + np.arange(n)
    c = 100 + np.cumsum(g.normal(0, 0.05, n))
    o = np.r_[100, c[:-1]]
    h = np.maximum(o, c) + 0.02
    l = np.minimum(o, c) - 0.02
    return mod, o, h, l, c, np.full(n, 1e6)


def test_five_min_bars_alignment():
    mod, o, h, l, c, tv = _bars_day()
    bb = B.five_min_bars(mod, o, h, l, c, tv)
    assert len(bb) == 12 and bb.slot.tolist() == list(range(12))
    assert bb.o[0] == o[0] and bb.c[0] == c[4] and bb.h[1] == h[5:10].max() and bb.tv[0] == 5e6


def test_absorption_new_high_heavy_and_quiet():
    bars = pd.DataFrame({"slot": [10, 11, 12, 13], "h": [100.0, 100.2, 100.25, 100.1], "l": [99.8, 99.9, 100.22, 99.9],
                         "c": [100.0, 100.1, 100.24, 100.0], "tv": [1.0, 1.0, 5.0, 1.0]})
    tv_med = np.array([1.0, 1.0, 1.0, 1.0])
    rng_med = np.array([0.002, 0.002, 0.002, 0.002])
    assert B.absorption_bars(bars, tv_med, rng_med) == [(2, -1)]
    loud = bars.copy()
    loud.loc[2, "l"] = 99.5                                             # range too big -> not absorption
    assert B.absorption_bars(loud, tv_med, rng_med) == []
    early = bars.assign(slot=[8, 9, 10, 11])                             # before 10:15 -> ignored
    assert B.absorption_bars(early, tv_med, rng_med) == []


def test_absorption_lookahead_guard():
    bars = pd.DataFrame({"slot": [10, 11, 12, 13, 14], "h": [100.0, 100.2, 100.25, 100.1, 101.0],
                         "l": [99.8, 99.9, 100.22, 99.9, 100.5], "c": [100.0, 100.1, 100.24, 100.0, 100.9],
                         "tv": [1.0, 1.0, 5.0, 1.0, 9.0]})
    tv_med, rng_med = np.ones(5), np.full(5, 0.002)
    a = B.absorption_bars(bars, tv_med, rng_med)
    b = B.absorption_bars(bars.iloc[:3], tv_med[:3], rng_med[:3])
    assert [x for x in a if x[0] <= 2] == b


def test_basket_lookahead_guard():
    e = _eq()
    full = B.basket_minutes(e)
    cut = e[~((e.day == "2024-01-02") & (e["mod"] == 557))]
    part = B.basket_minutes(cut)
    a = full[(full.day == "2024-01-02") & (full["mod"] <= 556)].reset_index(drop=True)
    b = part[(part.day == "2024-01-02") & (part["mod"] <= 556)].reset_index(drop=True)
    pd.testing.assert_frame_equal(a, b)


def test_sim_overnight_gap_touch_and_hold():
    assert B.sim_overnight(100.0, 1, 2.0, 97.0, [96.0], [98.0], 99.0) == (97.0, True)     # gap through the stop
    assert B.sim_overnight(100.0, 1, 2.0, 99.0, [99.0, 97.5], [100.0, 99.0], 99.5) == (98.0, True)
    assert B.sim_overnight(100.0, -1, 2.0, 101.0, [100.5], [101.5], 100.2) == (100.2, False)
    assert B.sim_overnight(100.0, -1, 2.0, 103.0, [], [], 100.0) == (103.0, True)
