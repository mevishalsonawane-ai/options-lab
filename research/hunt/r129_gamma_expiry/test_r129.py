"""R129 tests: the wall, the breakout (and its look-ahead guard), the volume rule, the trigger, the exits.

    python -m pytest research/hunt/r129_gamma_expiry/test_r129.py -q
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r129 as G  # noqa: E402

N = G.N


def test_wall_needs_a_3x_imbalance_at_the_heaviest_strike():
    assert G.wall_of({}) == (None, 0)
    assert G.wall_of({25000.0: (900.0, 300.0), 25100.0: (100.0, 100.0)}) == (25000.0, 1)
    assert G.wall_of({25000.0: (300.0, 900.0)}) == (25000.0, -1)
    assert G.wall_of({25000.0: (800.0, 300.0)}) == (25000.0, 0)          # 2.7x: not enough
    assert G.wall_of({25000.0: (np.nan, 500.0)}) == (25000.0, -1)        # no calls at all
    # the heaviest strike decides, even when another strike has the imbalance
    assert G.wall_of({25000.0: (500.0, 500.0), 25100.0: (600.0, 100.0)}) == (25000.0, 0)


def _day(base=25000.0):
    h = np.full(N, base + 5.0)
    l = np.full(N, base - 5.0)
    c = np.full(N, base)
    return h, l, c


def test_breakout_beyond_box_wall_and_twap_and_its_look_ahead_guard():
    h, l, c = _day()
    w0 = G.WIN[0]
    c[w0 + 10:] = 25060.0                                 # the 14:10..14:14 bar closes beyond the box (25,005) and the wall
    h[w0 + 10:] = 25065.0
    e = G.setup_bar(h, l, c, 25005.0, 24995.0, 25050.0, 1)
    assert e == w0 + 14
    # nothing after the setup bar changes it (look-ahead guard)
    c2, h2 = c.copy(), h.copy()
    c2[e + 1:] = 1.0
    h2[e + 1:] = 1.0
    assert G.setup_bar(h2, l, c2, 25005.0, 24995.0, 25050.0, 1) == e
    # already above the wall at 13:59: not a crossing in the window
    c3 = c.copy()
    c3[:w0] = 25070.0
    assert G.setup_bar(h, l, c3, 25005.0, 24995.0, 25050.0, 1) is None
    # bearish mirror
    h, l, c = _day()
    c[w0 + 20:] = 24940.0
    assert G.setup_bar(h, l, c, 25005.0, 24995.0, 24950.0, -1) == w0 + 24


def test_volume_rule_reads_only_the_previous_ten_traded_minutes():
    a = np.full((N, 5), np.nan)
    rows = np.arange(100, 130)
    a[rows, G.V_] = 100.0
    a[rows, G.C_] = 20.0
    a[120, G.V_] = 150.0
    idx = np.flatnonzero(np.isfinite(a[:, G.C_]))
    assert G.vol_ok(a, idx, 120)
    assert not G.vol_ok(a, idx, 119)                       # equal to the mean: not over it
    assert not G.vol_ok(a, idx, 105)                       # fewer than ten before
    assert not G.vol_ok(a, idx, 135)                       # not traded


def _opt(n0=0, px=20.0):
    a = np.full((N, 5), np.nan)
    a[n0:, :4] = px
    a[n0:, 4] = 1000.0
    return a


def test_walk_target_first_then_flip_then_cap():
    a = _opt()
    a[205, G.H_] = 80.0                                    # 4x of the 20.0 open
    assert G.walk(a, 200, 300, lambda r: r == 205) == (80.0, 205, "target")
    a = _opt()
    a[211, G.O_] = 18.0
    assert G.walk(a, 200, 300, lambda r: r == 210) == (18.0, 211, "flip")
    assert G.walk(_opt(), 200, 300, lambda r: False) == (20.0, 300, "cap")
    assert G.walk(_opt(n0=250), 200, 300, lambda r: False) is None   # no open at the entry
    b = _opt()
    b[301:, :] = np.nan
    assert G.walk(b, 200, 300, lambda r: r == 300) == (20.0, 300, "flip")   # a flip at the cap with no later open


def test_trigger_needs_both_supertrends_and_the_volume():
    a = _opt()
    a[150, G.V_] = 5000.0
    idx = np.flatnonzero(np.isfinite(a[:, G.C_]))
    up = np.ones(N, np.int64)
    dn = -np.ones(N, np.int64)
    assert G.trigger(a, idx, up, up, dn, dn, 1, 140, 160, "option") == 150
    assert G.trigger(a, idx, up, dn, dn, dn, 1, 140, 160, "option") is None
    assert G.trigger(a, idx, dn, dn, up, up, 1, 140, 160, "index") == 150
    assert G.trigger(a, idx, dn, dn, dn, dn, -1, 140, 160, "index") == 150


def test_option_supertrend_skips_untraded_minutes():
    a = _opt()
    rng = np.random.default_rng(0)
    a[:, G.C_] = 20 + np.cumsum(rng.normal(0, 0.3, N))
    a[:, G.H_] = a[:, G.C_] + 0.2
    a[:, G.L_] = a[:, G.C_] - 0.2
    a[50:60] = np.nan
    s7, s10, idx = G.traded_st(a)
    assert (s7[50:60] == 0).all() and len(idx) == N - 10
    assert set(np.unique(s7[100:])) <= {-1, 1}


def test_sizing_is_two_percent_of_capital():
    s = G.sizing()
    assert s["500000"]["NIFTY"]["20"] == int(500000 * 0.02 // (20 * 65))
    assert s["200000"]["SENSEX"]["15"] == int(200000 * 0.02 // (15 * 20))
