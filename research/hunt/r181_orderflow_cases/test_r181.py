"""Tests for R181 (PREREG.md): minute bars, the volume profile, each setup and its order-flow condition, stacked
imbalances, the second-by-second exit (stop first, the A+ two-stage exit, time), costs, the twins' statistics, BH, and a
look-ahead guard (rows after the signal deleted or perturbed change nothing). Synthetic data only; no recordings needed."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parents[2])]
import r181lib as LB  # noqa: E402

D0 = date(2026, 10, 22)
UND = "NIFTY"


def bars(c, h=None, l=None, d=None, rows=None, v=None):
    c = np.asarray(c, float)
    n = len(c)
    pad = LB.N - n
    c = np.concatenate([c, np.full(pad, np.nan)])
    h = np.concatenate([np.asarray(h if h is not None else c[:n] + 1, float), np.full(pad, np.nan)])
    l = np.concatenate([np.asarray(l if l is not None else c[:n] - 1, float), np.full(pad, np.nan)])
    d = np.concatenate([np.asarray(d if d is not None else np.full(n, 10.0), float), np.zeros(pad)])
    rows = np.concatenate([np.asarray(rows if rows is not None else np.full(n, 60), float), np.zeros(pad)])
    v = np.concatenate([np.asarray(v if v is not None else np.full(n, 650.0), float), np.zeros(pad)])
    return LB.Bars(c.copy(), h, l, c, v, d, rows)


LV = dict(poc=1000.0, vah=1040.0, val=960.0, pdh=1080.0, pdl=930.0)


# ------------------------------------------------------------------------------------------------ bars and profile
def test_bars_from_seconds_ohlc_delta_rows():
    t0 = LB.day_epoch_0915(D0)
    sec = np.array([t0, t0 + 10, t0 + 59, t0 + 60])
    last = np.array([100.0, 103.0, 101.0, 99.0])
    nan = np.full(4, np.nan)
    B = LB.bars_from_seconds(sec, last, np.array([np.nan, 104, np.nan, np.nan]), nan, np.array([65, 130, 65, 65.0]),
                             np.array([65, 130, 0, 0.0]), np.array([0, 0, 65, 65.0]), t0)
    assert (B.o[0], B.h[0], B.l[0], B.c[0]) == (100.0, 104.0, 100.0, 101.0)
    assert B.d[0] == 130 and B.rows[0] == 3 and B.v[0] == 260
    assert B.c[1] == 99.0 and np.isnan(B.c[2])
    assert B.thin[0] and np.isnan(B.c[2]) and B.thin[2]


def test_day_epoch_matches_recording():
    assert LB.day_epoch_0915(date(2026, 10, 13)) == 1791863100     # the first export's 09:15 row


def test_ref_needs_ten_bars_and_reads_only_the_past():
    d = np.arange(1, 41, dtype=float)
    thin = np.zeros(40, bool)
    r = LB.ref_abs_delta(d, thin)
    assert np.isnan(r[9]) and r[10] == pytest.approx(np.mean(d[:10]))
    assert r[35] == pytest.approx(np.mean(d[5:35]))
    d2 = d.copy()
    d2[36:] = 1e9
    assert LB.ref_abs_delta(d2, thin)[36] == r[36]


def test_volume_profile_poc_and_value_area():
    prices = [1000.0] * 5 + [1010.0, 990.0, 1020.0, 980.0]
    vols = [100, 100, 100, 100, 100, 200, 150, 30, 20]
    lv = LB.volume_profile(prices, vols, 50.0, 1030, 975)
    assert lv["poc"] == pytest.approx(1005.0)          # bin 1000-1010 has 500
    # 70% of 900 = 630: 500 + 200 (1010 bin) = 700
    assert lv["val"] == pytest.approx(1000.0) and lv["vah"] == pytest.approx(1020.0)
    assert LB.volume_profile([], [], 50.0, 1, 0) is None


def test_shift_levels():
    assert LB.shift_levels(dict(poc=1.0, vah=2.0), 10)["vah"] == 12.0


# ------------------------------------------------------------------------------------------------ Case 1 / 2
def _case1_series(absorb=True, reclaim_delta=50.0):
    n = 60
    c = np.full(n, 970.0)
    d = np.full(n, 10.0)
    l = c - 1
    # break at 30, absorption at 31 (low not below bar 30's low, heavy selling), reclaim at 33
    c[30], l[30] = 955.0, 950.0
    c[31], l[31] = 952.0, 951.0 if absorb else 945.0
    d[31] = -100.0
    c[32], l[32] = 958.0, 955.0
    c[33], d[33] = 965.0, reclaim_delta
    return bars(c, h=c + 1, l=l, d=d)


def test_case1_absorption_and_reclaim():
    eps = LB.case1(_case1_series(), LV, UND, None, "L")
    assert len(eps) == 1
    e = eps[0]
    assert (e.case, e.side, e.b, e.r, e.of) == ("C1", "L", 30, 33, True)
    assert e.stop == pytest.approx(950.0 - 5.0) and e.target == LV["poc"]
    assert not e.rr_ok      # E 965, risk 20, reward 35 < 2 x 20


def test_case1_rr_rule():
    assert not LB.rr_ok("L", 965.0, 945.0, 1000.0)
    assert LB.rr_ok("L", 965.0, 950.0, 1000.0)
    assert LB.rr_ok("S", 100.0, 105.0, 90.0) and not LB.rr_ok("S", 100.0, 106.0, 90.0)


def test_case1_of_needs_no_new_low_and_reclaim_buyers():
    assert not LB.case1(_case1_series(absorb=False), LV, UND, None, "L")[0].of
    assert not LB.case1(_case1_series(reclaim_delta=-5.0), LV, UND, None, "L")[0].of


def test_case1_no_reclaim_within_15_bars_is_no_episode():
    n = 80
    c = np.full(n, 970.0)
    c[30:50] = 950.0
    assert LB.case1(bars(c), LV, UND, None, "L") == []


def test_case2_exhaustion_five_bars():
    n = 60
    c = np.full(n, 1030.0)
    h = c + 1
    d = np.full(n, 5.0)
    c[30:35] = [1045, 1046, 1047, 1048, 1049]
    h[30:35] = [1046, 1047, 1048, 1049, 1050]
    d[30:35] = [1295, 904, 622, 162, 12]
    c[35], d[35] = 1035.0, -40.0
    B = bars(c, h=h, l=c - 1, d=d)
    e = LB.case1(B, LV, UND, None, "S")[0]
    assert (e.case, e.side, e.b, e.r, e.of) == ("C2", "S", 30, 35, True)
    assert LB.exhaustion_bar(B, 34, LV["vah"]) and not LB.exhaustion_bar(B, 33, LV["vah"])
    d2 = d.copy()
    d2[32] = 1000                      # not strictly falling
    assert not LB.case1(bars(c, h=h, l=c - 1, d=d2), LV, UND, None, "S")[0].of


def test_thin_bars_fail_the_of_checks():
    B = _case1_series()
    B.rows[31] = 5
    B.derive()
    assert not LB.case1(B, LV, UND, None, "L")[0].of


# ------------------------------------------------------------------------------------------------ Case 3
def test_stacked_buy_imbalances():
    p = np.array([1000.0, 1002.0, 1004.0, 1006.0, 1008.0])
    b = np.array([0, 400, 400, 400, 0.0])
    s = np.array([100, 100, 100, 100, 100.0])
    span = LB.stacked((p, b, s), 2.0, 65, "L")
    assert span == (1002.0, 1008.0)
    b2 = b.copy()
    b2[2] = 200                         # 200 < 3 x 100: the run breaks
    assert LB.stacked((p, b2, s), 2.0, 65, "L") is None
    assert LB.stacked(None, 2.0, 65, "L") is None


def test_stacked_sell_imbalances():
    p = np.array([1000.0, 1002.0, 1004.0, 1006.0])
    s = np.array([400, 400, 400, 0.0])
    b = np.array([0, 100, 100, 100.0])
    assert LB.stacked((p, b, s), 2.0, 65, "S") == (1000.0, 1006.0)


def test_case3_breakout_retest_and_stacked_of():
    n = 60
    c = np.full(n, 1030.0)
    c[30] = 1048.0
    c[31] = 1052.0
    c[32] = 1045.0
    l = c - 1
    l[32] = 1043.0                       # retest: low <= VAH + 10, close > VAH
    B = bars(c, h=c + 2, l=l)
    fp = {30: (np.array([1040.0, 1042, 1044, 1046]), np.array([0, 400, 400, 400.0]), np.array([100, 100, 100, 100.0]))}
    e = LB.case3(B, LV, UND, fp, "L")[0]
    assert (e.b, e.r, e.of) == (30, 32, True)
    assert e.stop == pytest.approx(min(1043.0, 1030.0) - 5)
    assert e.target == pytest.approx(1045 + 2 * (1045 - e.stop)) and e.rr_ok
    assert not LB.case3(B, LV, UND, {}, "L")[0].of


# ------------------------------------------------------------------------------------------------ Case 4 / 5
def test_case4_double_bottom_with_cvd_higher_low():
    n = 80
    c = np.full(n, 1030.0)
    l = c - 1
    h = c + 1
    d = np.zeros(n)
    c[20], l[20], d[20] = 1003.0, 1001.0, -500     # low 1 at POC
    c[21:30] = 1025.0
    l[21:30] = 1020.0
    h[21:30] = 1026.0
    c[30], l[30], d[30] = 1004.0, 1002.0, -100     # low 2, CVD higher (sellers weaker)
    d[21:30] = 30
    c[31], h[31], l[31] = 1010.0, 1011.0, 1003.0   # close above bar 30's high (1005)
    h[30] = 1005.0
    B = bars(c, h=h, l=l, d=d)
    eps = LB.case4(B, LV, UND, "L")
    assert len(eps) == 1
    e = eps[0]
    assert (e.b, e.r, e.extra["j2"]) == (20, 31, 30) and e.of
    assert e.stop == pytest.approx(1001 - 5) and e.target == pytest.approx(1031.0)


def test_case5_sweep_and_trapped_sellers():
    n = 60
    c = np.full(n, 940.0)
    l = c - 1
    d = np.full(n, 10.0)
    c[30], l[30], d[30] = 928.0, 920.0, -300.0
    c[31], d[31] = 935.0, 20.0
    B = bars(c, h=c + 1, l=l, d=d)
    e = LB.case5(B, LV, UND, "L")[0]
    assert (e.b, e.r, e.of) == (30, 31, True)
    assert e.target == LV["val"] and e.stop == pytest.approx(915.0)
    d2 = d.copy()
    d2[30] = 5.0
    assert not LB.case5(bars(c, h=c + 1, l=l, d=d2), LV, UND, "L")[0].of


def test_oi_wall():
    ch = [[22500, "PE", 50, 900, 0], [22550, "PE", 60, 100, 0], [22700, "CE", 40, 5000, 0]]
    assert LB.oi_wall_ok(ch, "L", 22540.0, 20.0, 50.0)      # VAL spot 22520 vs 22500
    assert not LB.oi_wall_ok(ch, "L", 22640.0, 20.0, 50.0)
    assert LB.oi_wall_ok(ch, "S", 22720.0, 20.0, 50.0)
    assert not LB.oi_wall_ok(None, "L", 1, 1, 1)


# ------------------------------------------------------------------------------------------------ option leg, exits, costs
def test_pick_strike():
    assert LB.atm(22524.9, 50) == 22500 and LB.atm(22525, 50) == 22550
    assert LB.pick_strike([22500.0, 22550.0], 22500.0, 50, "L") == 22500.0
    assert LB.pick_strike([22450.0, 22550.0], 22500.0, 50, "L") == 22450.0     # tie: ITM call
    assert LB.pick_strike([22450.0, 22550.0], 22500.0, 50, "S") == 22550.0     # tie: ITM put
    assert LB.pick_strike([22650.0], 22500.0, 50, "L") is None


def _fut(t0, path):
    sec = np.arange(t0, t0 + len(path))
    p = np.asarray(path, float)
    return sec, p, p


def _opt(t0, n, price=100.0, slope=0.0):
    sec = np.arange(t0, t0 + n)
    last = price + slope * np.arange(n)
    return LB.Series(sec, last, np.full(n, 6500.0), last - 0.05, last + 0.05)


def test_exit_stop_checked_first_and_priced_at_that_second():
    t0 = LB.day_epoch_0915(D0)
    fut = _fut(t0, [100.0] * 10 + [94.0] + [120.0] * 20)
    opt = _opt(t0, 40, 100.0, -1.0)
    r = LB.simulate(fut, opt, t0, t0 + 30, "L", 95.0, 110.0, 65, D0)
    assert r["why"] == "stop" and r["exit_s"] == t0 + 10 and r["raw_x"] == 90.0
    assert r["net2"] < r["net"] < (90 - 100) * 65


def test_exit_same_second_both_is_stop():
    t0 = LB.day_epoch_0915(D0)
    sec = np.arange(t0, t0 + 5)
    hi = np.array([100, 100, 115, 100, 100.0])
    lo = np.array([100, 100, 90, 100, 100.0])
    assert LB.first_exit(sec, hi, lo, t0, t0 + 5, "L", 95, 110) == (t0 + 2, "stop")
    assert LB.first_exit(sec, hi, lo, t0, t0 + 2, "L", 95, 110) == (t0 + 2, "time")


def test_two_stage_aplus_exit():
    t0 = LB.day_epoch_0915(D0)
    fut = _fut(t0, [100, 105, 110, 108, 103, 99, 99, 99])          # T1 110 hit, then back to E = 100 -> be stop
    opt = _opt(t0, 10)
    r = LB.simulate(fut, opt, t0, t0 + 8, "L", 90.0, 110.0, 65, D0, t2=130.0, e_level=100.0)
    assert r["why"] == "be_stop" and r["exit_s"] == t0 + 5
    fut2 = _fut(t0, [100, 110, 120, 131, 99])
    r2 = LB.simulate(fut2, opt, t0, t0 + 5, "L", 90.0, 110.0, 65, D0, t2=130.0, e_level=100.0)
    assert r2["why"] == "target2" and r2["exit_s"] == t0 + 3


def test_time_exit_and_missing_entry_price():
    t0 = LB.day_epoch_0915(D0)
    fut = _fut(t0, [100.0] * 50)
    opt = _opt(t0, 60, 100.0, 0.5)
    r = LB.simulate(fut, opt, t0, t0 + 40, "L", 90.0, 110.0, 65, D0)
    assert r["why"] == "time" and r["raw_x"] == 120.0
    empty = LB.Series(np.array([t0 + 5000]), np.array([5.0]), np.array([0.0]), np.array([4.9]), np.array([5.1]))
    assert LB.simulate(fut, empty, t0, t0 + 40, "L", 90, 110, 65, D0) is None


def test_option_price_lookup_rules():
    t0 = 1000
    s = LB.Series(np.array([t0, t0 + 200]), np.array([10.0, 12.0]), np.array([65.0, 65.0]), np.array([9.9, 11.9]),
                  np.array([10.1, 12.1]))
    assert s.px(t0 + 100) == 10.0          # 100 s old
    assert s.px(t0 + 150) == 12.0          # stale (>120 s) -> next row within 60 s
    assert s.px(t0 + 500) is None
    assert s.px_any_after(t0 + 500) == 12.0
    assert s.vol5_lots(t0 + 201, 65) == 2.0


def test_costs_dated_and_slippage():
    n1 = LB.net_of(100.0, 110.0, 50.0, 50.0, False, 65, D0)
    gross = 10 * 65
    assert gross - 120 < n1 < gross
    sell_charge = LB.charge(False, 110.0, 65, D0)
    assert sell_charge > 20 + 0.0015 * 110 * 65         # STT 0.15% from 1 Apr 2026
    assert LB.net_of(100.0, 110.0, 50.0, 50.0, False, 65, D0, 2.0) < n1


# ------------------------------------------------------------------------------------------------ statistics
def test_selection_and_twin_p_and_bh():
    rng = np.random.default_rng(1)
    allx = list(range(-50, 50))
    assert LB.selection_p(allx, [49, 48, 47], rng, 2000) < 0.01
    assert LB.selection_p(allx, [1, 2], rng) == 1.0
    assert LB.twin_p([10, 10, 10], [0] * 199 + [20]) == pytest.approx(2 / 201)
    q_all, q_in = LB.bh_with_prior([0.001, 0.5, 0.04], 4066)
    assert q_all[0] == pytest.approx(min(1, 0.001 * 4066))
    assert q_in[0] == pytest.approx(0.003) and q_in[2] == pytest.approx(0.06)


def test_summary():
    s = LB.summary([100, -50, 30], [90, -60, 20])
    assert s["n"] == 3 and s["win"] == pytest.approx(0.667) and s["net"] == 80 and s["net2"] == 50
    assert s["avg_win"] == 65 and s["avg_loss"] == -50
    assert LB.summary([])["n"] == 0


# ------------------------------------------------------------------------------------------------ look-ahead guard
def _random_day(seed):
    rng = np.random.default_rng(seed)
    c = 1000 + np.cumsum(rng.normal(0, 6, LB.N))
    h = c + rng.uniform(0, 5, LB.N)
    l = c - rng.uniform(0, 5, LB.N)
    d = rng.normal(0, 300, LB.N)
    rows = rng.integers(10, 61, LB.N).astype(float)
    return c, h, l, d, rows


def _signals(c, h, l, d, rows, cut, fp):
    B = LB.Bars(c.copy(), h.copy(), l.copy(), c.copy(), np.full(LB.N, 650.0), d.copy(), rows.copy())
    ab = LB.absorb_formula(B, fp)
    lv = dict(poc=float(np.median(c)), vah=float(np.percentile(c, 70)), val=float(np.percentile(c, 30)),
              pdh=float(np.max(c)) - 5, pdl=float(np.min(c)) + 5)
    out = []
    out += LB.case1(B, lv, UND, ab, "L") + LB.case1(B, lv, UND, ab, "S")
    for s in "LS":
        out += LB.case3(B, lv, UND, fp, s) + LB.case4(B, lv, UND, s) + LB.case5(B, lv, UND, s)
    return [(e.case, e.side, e.b, e.r, e.of, e.ofv, round(e.stop, 6), round(e.target, 6)) for e in out if e.r <= cut], lv


@pytest.mark.parametrize("seed", range(12))
def test_lookahead_guard_rows_after_the_signal_change_nothing(seed):
    c, h, l, d, rows = _random_day(seed)
    fp = {i: (np.array([c[i] - 2, c[i], c[i] + 2]), np.array([100.0, 900, 300]), np.array([300.0, 100, 50]))
          for i in range(LB.N)}
    full, lv = _signals(c, h, l, d, rows, LB.N, fp)
    rng = np.random.default_rng(seed + 99)
    for cut in (60, 150, 250):
        # levels are fixed from the prior day, so keep them; perturb / delete every row after the cut
        c2, h2, l2, d2, r2 = (x.copy() for x in (c, h, l, d, rows))
        c2[cut + 1:] += rng.normal(0, 50, LB.N - cut - 1)
        h2[cut + 1:] = c2[cut + 1:] + 3
        l2[cut + 1:] = c2[cut + 1:] - 3
        d2[cut + 1:] = rng.normal(0, 5000, LB.N - cut - 1)
        r2[cut + 1:] = rng.integers(0, 61, LB.N - cut - 1)
        fp2 = {i: v for i, v in fp.items() if i <= cut}
        got = _signals_with_levels(c2, h2, l2, d2, r2, cut, fp2, lv)
        want = [x for x in _signals_with_levels(c, h, l, d, rows, LB.N, fp, lv) if x[3] <= cut]
        assert got == want
        c3, h3, l3 = (x.copy() for x in (c, h, l))
        c3[cut + 1:] = np.nan
        h3[cut + 1:] = np.nan
        l3[cut + 1:] = np.nan
        assert _signals_with_levels(c3, h3, l3, d, rows, cut, fp2, lv) == want
    assert sorted(full) == _signals_with_levels(c, h, l, d, rows, LB.N, fp, lv)


def _signals_with_levels(c, h, l, d, rows, cut, fp, lv):
    B = LB.Bars(c.copy(), h.copy(), l.copy(), c.copy(), np.full(LB.N, 650.0), d.copy(), rows.copy())
    ab = LB.absorb_formula(B, fp)
    out = LB.case1(B, lv, UND, ab, "L") + LB.case1(B, lv, UND, ab, "S")
    for s in "LS":
        out += LB.case3(B, lv, UND, fp, s) + LB.case4(B, lv, UND, s) + LB.case5(B, lv, UND, s)
    # A setup whose search window runs past the cut can only be known after it: compare signals at r <= cut
    return sorted((e.case, e.side, e.b, e.r, e.of, e.ofv, round(e.stop, 6), round(e.target, 6)) for e in out
                  if e.r <= cut)


def test_exit_decision_ignores_later_seconds():
    t0 = LB.day_epoch_0915(D0)
    path = [100.0] * 10 + [94.0] + [100.0] * 20
    a = LB.first_exit(*_fut(t0, path), t0, t0 + 30, "L", 95, 110)
    path2 = path[:11] + [500.0] * 20
    assert LB.first_exit(*_fut(t0, path2), t0, t0 + 30, "L", 95, 110) == a
