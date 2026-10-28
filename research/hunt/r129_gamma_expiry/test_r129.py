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


# ---- amendment 1: the loose rule (r129loose.py) ------------------------------------------------------------------------
def test_loose_walls_are_the_nearest_2x_strikes_each_way():
    import r129loose as L
    oi = {25000.0: (100.0, 100.0), 25050.0: (150.0, 100.0), 25100.0: (300.0, 100.0), 25150.0: (900.0, 100.0),
          24950.0: (100.0, 250.0), 24900.0: (50.0, 900.0)}
    w = L.walls(oi, 25010.0, 50.0)
    assert w[1] == 25100.0                                # the nearest 2x call wall above spot, not the biggest
    assert w[-1] == 24950.0                               # 2.5x puts below spot
    assert L.walls(oi, 25010.0, 50.0)[1] != 25150.0
    far = {25400.0: (900.0, 1.0)}                          # 7.8 strikes away: out of range
    assert L.walls(far, 25010.0, 50.0) == {1: None, -1: None}


def test_loose_setup_needs_the_near_side_at_1344_and_has_no_look_ahead():
    import r129loose as L
    c = np.full(N, 25000.0)
    c[L.WIN0 + 10:] = 25060.0
    e = L.setup_loose(c, 25050.0, 1)
    assert e == L.WIN0 + 14
    c2 = c.copy()
    c2[e + 1:] = 1.0
    assert L.setup_loose(c2, 25050.0, 1) == e
    c3 = c.copy()
    c3[:G.ROW_WALL + 1] = 25070.0                          # already beyond the wall at 13:44
    assert L.setup_loose(c3, 25050.0, 1) is None


def test_loose_heavyweights_need_two_of_three():
    import r129loose as L
    from datetime import date
    d = date(2025, 1, 2)
    up = (np.full(N, 101.0), np.full(N, 100.0))
    dn = (np.full(N, 99.0), np.full(N, 100.0))
    eq = {("A", d): up, ("B", d): up, ("C", d): dn}
    assert L.heavy_ok(eq, ("A", "B", "C"), d, 300, 1)
    assert not L.heavy_ok(eq, ("A", "B", "C"), d, 300, -1)
    assert L.heavy_ok(eq, (), d, 300, 1)
    assert not L.heavy_ok({("A", d): up}, ("A", "B", "C"), d, 300, 1)     # missing stocks do not count


# ---- amendment 2: the winners filter (r129meta.py) ---------------------------------------------------------------------
def _meta_frame(n=200, seed=0):
    import pandas as pd
    from datetime import date, timedelta
    import r129meta as M
    rng = np.random.default_rng(seed)
    F = pd.DataFrame({f: rng.normal(size=n) for f in M.FEATS})
    F["net"] = np.where(F.vix + rng.normal(0, 0.5, n) > 1.0, 1500.0, -500.0)    # winners carry high "vix"
    F["netx2"] = F.net - 10
    F["day"] = [date(2022, 1, 3) + timedelta(days=int(i)) for i in range(n)]
    return F


def test_meta_fit_uses_training_rows_only_and_keeps_the_top_30pct():
    import r129meta as M
    F = _meta_frame()
    tr, te = F.iloc[:150], F.iloc[150:].copy()
    m1 = M.fit(tr)
    te2 = te.copy()
    te2["net"] = -te2.net                                  # the test outcomes never reach the model
    m2 = M.fit(tr)
    assert np.allclose(M.score(m1, te), M.score(m2, te2)) and m1[4] == m2[4]
    s_tr = M.score(m1, tr)
    assert abs((s_tr >= m1[4]).mean() - 0.30) < 0.02
    # the planted signal is found: kept test trades win more than all of them
    k = te[M.score(m1, te) >= m1[4]]
    assert (k.net > 0).mean() > (te.net > 0).mean()


def test_meta_imputes_missing_with_training_medians():
    import r129meta as M
    F = _meta_frame()
    F.loc[5, "log_oi_ratio"] = np.nan
    m = M.fit(F)
    row = F.iloc[[0]].copy()
    row["log_oi_ratio"] = np.nan
    assert np.isfinite(M.score(m, row)).all()


def test_meta_summary_and_random_p():
    import r129meta as M
    F = _meta_frame()
    s = M.summary(F)
    assert s["n"] == len(F) and 0 < s["win"] < 1 and s["avg_win"] == 1500.0 and s["avg_loss"] == -500.0
    assert M.summary(F.iloc[0:0]) == dict(n=0)
    rng = np.random.default_rng(1)
    best = F.sort_values("net", ascending=False).iloc[:20]
    assert M.random_p(F, 20, float(best.net.sum()), rng) < 0.01
    assert M.random_p(F, 0, 0.0, rng) == 1.0
    from datetime import date
    assert M.monthly(date(2025, 10, 28)) == 1 and M.monthly(date(2025, 10, 7)) == 0
