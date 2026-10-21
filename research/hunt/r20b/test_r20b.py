"""R20b unit tests on synthetic data (no release files needed): python3 -m pytest research/hunt/r20b/test_r20b.py -q

The look-ahead guard: every level and every signal at decision bar k must be the same when everything after bar k's
close is replaced by noise.
"""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import levels as LV  # noqa: E402
import study as S  # noqa: E402


def walk(seed, n=375, p=50000.0, vol=12.0):
    r = np.random.default_rng(seed)
    c = p + np.cumsum(r.normal(0, vol, n))
    o = np.concatenate([[p], c[:-1]])
    h = np.maximum(o, c) + np.abs(r.normal(0, vol / 2, n))
    lo = np.minimum(o, c) - np.abs(r.normal(0, vol / 2, n))
    return np.column_stack([o, h, lo, c])


class FakeDay:
    def __init__(self, d, ix):
        self.day, self.ix = d, ix


def make_dayin(seed, ix=None, wide=None, piv=None, oi=None, oii=None):
    hist = np.vstack([LV.bars15(walk(seed + 100 + j)) for j in range(5)])
    ix = walk(seed) if ix is None else ix
    bh, bl, bc = S.five(ix)
    atr = 300.0 if wide is None else (50.0 if wide else 5000.0)
    return S.DayIn(date(2025, 3, 3), "BANKNIFTY", 20, atr, float(bh[:10].max()), float(bl[:10].min()),
                   float(ix[LV.P0_ROW, 3]), bh, bl, bc, piv if piv is not None else LV.piv_levels(walk(seed + 99)),
                   oi if oi is not None else [49800.0, 50300.0], oii if oii is not None else [49900.0, 50200.0],
                   LV.LiqDay(hist, ix))


def noised_after(ix, row, seed):
    """ix with every minute after `row` replaced by an unrelated walk."""
    out = ix.copy()
    out[row + 1:] = walk(seed, p=float(ix[row, 3]) + 400, vol=40.0)[row + 1:]
    return out


# ------------------------------------------------------------------------------------------------ the look-ahead guard
@pytest.mark.parametrize("seed", [1, 2, 3])
def test_levels_at_bar_k_ignore_everything_after_it(seed):
    ix = walk(seed)
    full = make_dayin(seed, ix)
    for k in (9, 10, 17, 30, 44, 56):
        cut = make_dayin(seed, noised_after(ix, 5 * k + 4, seed + 7))
        assert full.liq.edges(k) == cut.liq.edges(k), k
        assert S.Levels(full).all(k) == S.Levels(cut).all(k), k


@pytest.mark.parametrize("seed", [4, 5, 6, 7])
@pytest.mark.parametrize("wide", [None, True])
def test_signals_at_bar_k_ignore_everything_after_it(seed, wide):
    ix = walk(seed)
    full = make_dayin(seed, ix, wide=wide)
    for v in ["V0"] + S.VARIANTS:
        for k in range(S.K0, S.K1 + 1, 3):
            cut = make_dayin(seed, noised_after(ix, 5 * k + 4, seed + 11), wide=wide)
            f1 = S.signal_fn(v, full, S.Levels(full))[0]
            f2 = S.signal_fn(v, cut, S.Levels(cut))[0]
            assert f1(k) == f2(k), (v, k)


def test_intraday_oi_walls_ignore_minutes_after_1004():
    day = date(2025, 3, 3)
    ts = pd.date_range("2025-03-03 09:15", periods=375, freq="min")
    rows = []
    for k, side, base in [(50000, "C", 100), (50100, "C", 300), (49900, "P", 250), (50000, "P", 120)]:
        for t in ts:
            rows.append(dict(ts=t, expiry=date(2025, 3, 6), strike=float(k), side=side, oi=base))
    o = pd.DataFrame(rows)
    assert LV.oii_walls(o, day) == [49900.0, 50100.0]
    late = o.copy()
    late.loc[(late.ts > ts[LV.P0_ROW]) & (late.strike == 50000) & (late.side == "C"), "oi"] = 10_000
    assert LV.oii_walls(late, day) == [49900.0, 50100.0]
    # an expiry ON the day is not used (the release's contracts are the nearest AFTER the day)
    same = o.assign(expiry=day)
    assert LV.oii_walls(same, day) == []


def test_bhavcopy_walls_use_the_nearest_expiry_on_or_after_the_session():
    b = pd.DataFrame(dict(expiry=[date(2025, 3, 5)] * 2 + [date(2025, 3, 12)] * 4,
                          strike=[49000.0, 52000.0, 49500.0, 50500.0, 48000.0, 53000.0],
                          side=["P", "C", "P", "C", "P", "C"], oi=[9e9, 9e9, 10, 20, 30, 5]))
    assert LV.oi_walls  # the session's walls come from the PREVIOUS day's file (study.prep passes it)
    import oi as OI
    assert OI.walls(b, date(2025, 3, 6)) == (48000.0, 50500.0)       # the 5 Mar expiry has passed
    assert OI.walls(b, date(2025, 3, 5)) == (49000.0, 52000.0)
    assert all(np.isnan(OI.walls(None, date(2025, 3, 5))))


def test_oi_walls_without_a_previous_file_are_empty():
    assert LV.oi_walls("BANKNIFTY", date(2025, 3, 5), None) == []


# ------------------------------------------------------------------------------------------------ level builders
def test_piv_levels_formula():
    ix = np.array([[100, 110, 90, 100]] * 374 + [[100, 101, 99, 105]], dtype=float)
    h, l, c, p, r1, s1 = LV.piv_levels(ix)
    assert (h, l, c) == (110, 90, 105)
    assert p == pytest.approx(305 / 3) and r1 == pytest.approx(2 * p - 90) and s1 == pytest.approx(2 * p - 110)


def test_atr14_is_the_mean_true_range_of_the_14_sessions_before():
    days = []
    for j in range(16):
        a = np.full((375, 4), 100.0 + j)
        a[:, 1] += 5
        a[:, 2] -= 5
        days.append(FakeDay(date(2025, 1, 1 + j), a))
    assert np.isnan(LV.atr14(days, 14))
    assert LV.atr14(days, 15) == pytest.approx(10.0)        # h - l = 10 > |h - pc| = 6
    days[14].ix[:, 1] = 200.0
    assert LV.atr14(days, 15) == pytest.approx((13 * 10 + (200 - 109)) / 14)   # day 14: h 200, l 109


def test_completed_15_minute_bars():
    assert LV.completed15(0) == 0 and LV.completed15(1) == 0 and LV.completed15(2) == 1
    assert LV.completed15(9) == 3 and LV.completed15(74) == 25


def test_liquidity_port_matches_h4_zone_for_zone():
    """The copy in levels.py equals research/hunt/h4/comps.py (itself the port of LiquidityRules.kt)."""
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    src = open(os.path.join(root, "research", "hunt", "h4", "comps.py")).read()
    mine = open(os.path.join(os.path.dirname(__file__), "levels.py")).read()
    a, b = src.index("class Zone:"), src.index("def atm(spot, step):")
    assert src[a:b].strip() in mine
    assert "L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300" in src and \
        "L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300" in mine


def test_swing_zone_found_and_taken():
    n = 60
    H = np.full(n, 100.0)
    Lo = np.full(n, 90.0)
    C = np.full(n, 95.0)
    H[25] = 120.0
    C[55] = 121.0
    z = [q for q in LV.swing_zones(H, Lo, C) if q.side > 0]
    assert len(z) == 1 and z[0].origin == 25 and z[0].known == 45 and z[0].edge == 120.0 and z[0].broken == 55


def test_mirror_keeps_the_distance_and_a_zone_keeps_its_sign():
    assert LV.mirror(105.0, 100.0, -1) == 95.0 and LV.mirror(105.0, 100.0, 1) == 105.0
    di = make_dayin(8)
    L = S.Levels(di, np.random.default_rng(0))
    a, b = L.all(20), L.all(20)
    assert a == b
    real = S.Levels(di).all(20)
    assert sorted(abs(x - di.p0) for x in a) == pytest.approx(sorted(abs(x - di.p0) for x in real))


# ------------------------------------------------------------------------------------------------ rules
def test_range_from_levels_ignores_levels_near_p0_and_falls_back_to_the_or():
    di = make_dayin(9)
    di.p0, di.atr, di.orh, di.orl = 1000.0, 100.0, 1200.0, 800.0
    assert S.range_from_levels(di, [1005.0, 1050.0, 990.0]) == (1050.0, 800.0)
    assert S.range_from_levels(di, []) == (1200.0, 800.0)


def test_clip_only_on_wide_days_and_only_inside_the_range():
    di = make_dayin(10)
    di.p0, di.orh, di.orl = 1000.0, 1300.0, 700.0
    di.atr = 100.0                                      # W45 600 > 75: wide
    assert S.clipped_range(di, [1100.0, 1400.0, 900.0, 650.0]) == (1100.0, 900.0)
    assert S.clipped_range(di, [1400.0, 650.0]) == (1300.0, 700.0)
    di.atr = 10_000.0                                   # not wide
    assert S.clipped_range(di, [1100.0, 900.0]) == (1300.0, 700.0)


def test_c_refuses_a_break_that_does_not_clear_the_nearest_level_and_keeps_the_side():
    di = make_dayin(11, piv=[], oi=[], oii=[])
    di.liq = LV.LiqDay(np.empty((0, 4)), walk(11))
    di.orh, di.orl, di.atr = 1000.0, 900.0, 100.0
    di.piv = [1020.0]
    di.bc = np.full(75, 950.0)
    di.bc[12], di.bc[14] = 1010.0, 1030.0
    di.table = {(12, 1): (100.0, 70), (14, 1): (200.0, 80)}
    assert S.run_day("C", di) == [(14, 1, 200.0, 80)]
    assert S.run_day("V0", di) == [(12, 1, 100.0, 70)]


def test_d_bounces_on_wide_days_and_skips_a_bar_touching_both_sides():
    di = make_dayin(12, piv=[], oi=[], oii=[])
    di.liq = LV.LiqDay(np.empty((0, 4)), walk(12))
    di.orh, di.orl, di.atr = 1200.0, 800.0, 100.0       # wide
    di.piv = [1000.0]
    di.bc = np.full(75, 1050.0)
    di.bh = np.full(75, 1060.0)
    di.bl = np.full(75, 1040.0)
    di.bl[15], di.bc[15] = 1002.0, 1010.0               # touches support 1000 (tol 5), closes above
    di.bh[20], di.bc[20], di.bc[19] = 1199.0, 1150.0, 1150.0   # touches resistance 1200 (range high), closes below
    di.table = {(15, 1): (10.0, 90), (20, -1): (20.0, 120), (30, 1): (30.0, 160)}
    assert S.run_day("D", di) == [(15, 1, 10.0, 90), (20, -1, 20.0, 120)]   # cap 2 a day
    f = S.signal_fn("D", di, S.Levels(di))[0]
    di.bl[25], di.bh[25], di.bc[25], di.bc[24] = 990.0, 1210.0, 1100.0, 1100.0
    di.piv = [1000.0]
    assert f(25) == 0


def test_run_day_caps_and_resumes_after_the_exit_bar():
    di = make_dayin(13)
    di.orh, di.orl = 1000.0, 900.0
    di.bc = np.full(75, 950.0)
    di.bc[10:20] = 1010.0
    di.bc[30] = 890.0
    di.table = {(10, 1): (5.0, 74), (11, 1): (6.0, 80), (30, -1): (-7.0, 200)}
    assert S.run_day("V0", di) == [(10, 1, 5.0, 74), (30, -1, -7.0, 200)]
    di.table[(10, 1)] = None
    di.table.pop((10, 1))
    assert S.run_day("V0", di)[0] == (11, 1, 6.0, 80)    # no contract on bar 10: the side is not used up


def test_score_counts_no_trade_days_as_zero_and_new_trade_days():
    base = [[], [(10, 1, 100.0, 60)], []]
    var = [[(12, 1, -50.0, 80)], [], [(15, -1, 30.0, 90)]]
    s = S.score(var, base)
    assert s["sessions"] == 3 and s["trades"] == 2 and s["total"] == -20 and s["green_pct"] == 50.0
    assert s["new_trade_days"] == 2 and s["new_days_net"] == -20 and s["dropped_trade_days"] == 1
    assert s["dropped_days_base_net"] == 100 and s["worst_day"] == -50 and s["max_dd"] == 50


def test_bh_step_up():
    assert S.bh([0.01, 0.04, 0.03, 0.5]) == {0}                    # 0.03 > 0.05 x 2/4
    assert S.bh([0.01, 0.03, 0.02, 0.5]) == {0, 1, 2}
    assert S.bh([0.01, 0.03, 0.02, 0.5] + [1.0] * 4) == set()      # more tests: a stricter bar
    assert S.bh([0.001, 0.03, 0.02, 0.5] + [1.0] * 4) == {0}


def test_holdout_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "FLAG", str(tmp_path / "HOLDOUT_READ"))
    monkeypatch.setattr(S, "OUT", str(tmp_path))
    monkeypatch.setattr(S, "RESULTS", str(tmp_path / "res"))
    (tmp_path / "HOLDOUT_READ").write_text("x")
    with pytest.raises(SystemExit):
        S.main("holdout")
