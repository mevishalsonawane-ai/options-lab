"""R27 unit tests on synthetic nights (no lake files needed): python3 -m pytest research/hunt/r27/test_r27.py -q"""
from __future__ import annotations

import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r27cfg as K  # noqa: E402
import r27data as D  # noqa: E402
import r27lib as R  # noqa: E402
from r27data import C, H, L, MORN, O, Night  # noqa: E402


def _ohlc(px: np.ndarray, oi: float | None = None) -> np.ndarray:
    a = np.full((len(px), 5 if oi is not None else 4), np.nan)
    a[:, O] = np.r_[px[0], px[:-1]]
    a[:, C] = px
    a[:, H] = np.maximum(a[:, O], a[:, C])
    a[:, L] = np.minimum(a[:, O], a[:, C])
    if oi is not None:
        a[:, 4] = oi
    return a


def night(ix_path, opt_path, side=1, und="NIFTY", qty=75, entry=100.0, d_close=None, vix=None, vix_prev=15.0,
          x_nifty=24000.0, x=None) -> Night:
    ix_path = np.asarray(ix_path, float)
    opt_path = np.asarray(opt_path, float)
    x = x if x is not None else ix_path[0]
    n = Night(und, date(2024, 6, 6), date(2024, 6, 7), qty, x, x - 50, x + 10, x - 80, d_close if d_close is not None else x,
              x + 20, x - 100, 0.8, 0.003, 0.6, 0.1, 1.0, side, side, True, "")
    n.strike[side] = D.atm(x, K.STEP[und]) - side * K.STEP[und]
    n.entry[side] = entry
    n.last_d[side] = (entry, 1e5)
    n.ix = _ohlc(ix_path)
    n.opt[side] = _ohlc(opt_path, 1e5)
    n.vix = np.asarray(vix, float) if vix is not None else np.full(MORN, 15.0)
    n.vix_prev = vix_prev
    n.x_nifty = x_nifty
    return n


def flat_ix(level=24000.0):
    return np.full(MORN, level)


# ------------------------------------------------------------------------------------------------ R3's rules
def test_strong_close_and_r3_side_match_nightrules():
    assert D.strong_close(0.6, 0.0, 0.5) == 1
    assert D.strong_close(0.4, 0.0, 0.5) == -1
    assert D.strong_close(0.7, -0.001, 0.9) == 0
    assert D.strong_close(0.9, 0.01, np.nan) == 0
    assert D.r3_side(1, 0.5) == 1 and D.r3_side(1, 0.49) == 0
    assert D.r3_side(-1, -0.6) == -1 and D.r3_side(-1, 0.6) == 0
    assert D.r3_side(1, np.nan) == 0                       # an unknown z counts as 0


def test_zscore_window_and_minimum():
    assert np.isnan(D.zscore(1.0, [0.1] * 19 + [0.2] * 0))
    h = list(np.linspace(-1, 1, 80))
    z = D.zscore(0.5, h)
    last = np.array(h[-60:])
    assert z == pytest.approx((0.5 - last.mean()) / last.std(ddof=1))
    assert np.isnan(D.zscore(0.5, [0.5] * 30))             # no spread


def test_atm_is_floor_half_up():
    assert D.atm(24024.9, 50) == 24000 and D.atm(24025.0, 50) == 24050
    assert D.atm(55449.99, 100) == 55400 and D.atm(55450, 100) == 55500


def test_buildup_matches_formula():
    rows = []
    x, step = 24010.0, 50.0
    for k in (23900, 23950, 24000, 24050, 24100):
        for s, (c0, cT, o0, oT) in (("C", (100, 120, 1000, 1500)), ("P", (90, 80, 2000, 2600))):
            rows += [dict(m=0, strike=float(k), side=s, close=c0, oi=o0), dict(m=D.R1519, strike=float(k), side=s, close=cT, oi=oT)]
    day = pd.DataFrame(rows)
    want = 5 * ((1 * 500) - (-1 * 600)) / (5 * (1500 + 2600))
    assert D.buildup(day, x, step) == pytest.approx(want)
    # a strike missing a figure drops out of the numerator but keeps its OI in the denominator
    day2 = day[~((day.strike == 24000) & (day.side == "P") & (day.m == 0))]
    assert D.buildup(day2, x, step) == pytest.approx(4 * 1100 / (5 * 4100))


def test_expiry_days_from_time_value():
    tv = pd.Series([1.2, 0.9, 0.3, 1.5, 1.1, 0.25, 1.4], index=pd.date_range("2024-01-01", periods=7).date)
    assert D.expiry_days(tv) == {tv.index[2], tv.index[5]}


def test_part_boundaries():
    assert K.part(date(2022, 12, 31)) == "desc" and K.part(date(2023, 1, 2)) == "wf"
    assert K.part(date(2025, 9, 30)) == "wf" and K.part(date(2025, 10, 1)) == "hold"


def test_lot_includes_bn_30_from_dec_2025():
    assert K.lot("BANKNIFTY", date(2026, 10, 13)) == 30
    assert K.lot("BANKNIFTY", date(2025, 10, 13)) == 35
    assert K.lot("NIFTY", date(2026, 10, 13)) == 65


# ------------------------------------------------------------------------------------------------ exits
def test_b0_sells_at_the_0916_open():
    n = night(flat_ix(), np.full(MORN, 110.0))
    n.opt[1][1, O] = 108.0
    t = R.trade(n, 1, R.B0)
    assert t.exit == 108.0 and t.why == "b0_0916" and not t.held
    assert t.net == pytest.approx(t.net_b0)


def test_b0_skips_a_missing_0916_bar():
    n = night(flat_ix(), np.full(MORN, 110.0))
    n.opt[1][1, :] = np.nan
    n.opt[1][2, O] = 111.0
    assert R.trade(n, 1, R.B0).exit == 111.0


def test_premium_target_and_stop_first_on_a_tie():
    p = np.full(MORN, 110.0)
    n = night(flat_ix(), p)
    n.opt[1][5, H] = 131.0                          # +21 over the 09:16 open
    t = R.trade(n, 1, R.BY_ID["V5_H1_P20"])
    assert t.why == "target" and t.exit == 130.0 and t.row == 5
    n.opt[1][5, L] = 89.0                           # the same bar also takes the stop: stop first
    t = R.trade(n, 1, R.BY_ID["V5_H1_P20"])
    assert t.why == "stop" and t.exit == 90.0
    assert t.xfill == pytest.approx(90.0 * (1 - 0.001), abs=0.01)


def test_premium_gap_through_the_stop_fills_at_the_open():
    p = np.full(MORN, 110.0)
    n = night(flat_ix(), p)
    n.opt[1][7, [O, H, L, C]] = [80.0, 82.0, 79.0, 81.0]
    t = R.trade(n, 1, R.BY_ID["V5_H1_P20"])
    assert t.why == "stop_gap" and t.exit == 80.0 and t.row == 7


def test_index_target_sells_at_the_next_minutes_option_open():
    ix = flat_ix()
    n = night(ix, np.full(MORN, 110.0))
    n.ix[10, H] = 24021.0                           # +21 NIFTY points in bar 10
    n.opt[1][11, O] = 117.0
    t = R.trade(n, 1, R.BY_ID["V1_H1_I20s"])
    assert t.why == "idx_target" and t.row == 11 and t.exit == 117.0
    t = R.trade(n, 1, R.BY_ID["V2_H1_I30s"])        # +30 not reached: time cap
    assert t.why == "time_1030" and t.row == R.CAP_1030


def test_put_side_index_levels_are_mirrored():
    n = night(flat_ix(), np.full(MORN, 110.0), side=-1)
    n.ix[6, L] = 23979.0
    n.opt[-1][7, O] = 118.0
    t = R.trade(n, -1, R.BY_ID["V1_H1_I20s"])
    assert t.why == "idx_target" and t.exit == 118.0
    n2 = night(flat_ix(), np.full(MORN, 110.0), side=-1)
    n2.ix[6, H] = 24021.0
    t2 = R.trade(n2, -1, R.BY_ID["V1_H1_I20s"])
    assert t2.why == "idx_stop"


def test_banknifty_scaled_vs_raw_points():
    n = night(np.full(MORN, 52000.0), np.full(MORN, 500.0), und="BANKNIFTY", qty=30, x=52000.0, x_nifty=26000.0)
    n.ix[4, H] = 52025.0                            # +25 BN points: raw 20 hit, scaled 40 not
    n.opt[1][5, O] = 505.0
    assert R.trade(n, 1, R.BY_ID["V3_H1_I20r"]).why == "idx_target"
    assert R.trade(n, 1, R.BY_ID["V1_H1_I20s"]).why == "time_1030"
    assert R.idx_pts(n, R.BY_ID["V1_H1_I20s"], 20) == pytest.approx(40.0)


def test_h2_holds_only_when_gap_and_first_bar_agree():
    s = R.BY_ID["V7_H2_I30s"]
    ix = flat_ix(24000.0)
    n = night(ix, np.full(MORN, 110.0), d_close=23950.0)
    n.ix[0, [O, C]] = [23980.0, 23990.0]            # gap up, first bar up: hold (call)
    assert R.decide(n, 1, s)
    n.ix[0, [O, C]] = [23980.0, 23970.0]            # first bar down: sell
    assert not R.decide(n, 1, s)
    n.ix[0, [O, C]] = [23940.0, 23945.0]            # gap down: sell
    assert not R.decide(n, 1, s)


def test_h2_stop_is_the_index_back_through_its_open():
    n = night(flat_ix(24000.0), np.full(MORN, 110.0), d_close=23950.0)
    n.ix[0, [O, H, L, C]] = [23980.0, 23992.0, 23979.0, 23990.0]
    n.ix[1:, [O, H, L, C]] = 23990.0
    n.ix[8, L] = 23979.0
    n.opt[1][9, O] = 104.0
    t = R.trade(n, 1, R.BY_ID["V7_H2_I30s"])
    assert t.held and t.why == "idx_stop" and t.exit == 104.0


def test_h3_lock_arms_at_1000_and_trails_500_below_the_best():
    # qty 50: Rs 1,000 = 20 points over the 15:20 buy (100), Rs 500 = 10 points
    p = np.full(MORN, 105.0)
    p[4:8] = 121.0                                  # +21 over the buy in bar 4: armed; floor from bar 5 at 111
    p[8:10] = 126.0                                 # best 126 in bar 8 -> floor 116 from bar 9
    p[10:] = 115.0                                  # bar 10 falls through 116
    n = night(flat_ix(), p, qty=50, entry=100.0)
    t = R.trade(n, 1, R.BY_ID["V8_H3_LOCK"])
    assert t.why == "lock" and t.exit == 116.0 and t.row == 10


def test_h3_floor_starts_at_once_when_already_up_1000_at_0916():
    p = np.full(MORN, 125.0)
    n = night(flat_ix(), p, qty=50, entry=100.0)   # +25 a unit = +Rs 1,250 at 09:16
    n.opt[1][3, L] = 114.0                          # floor 125 - 10 = 115
    t = R.trade(n, 1, R.BY_ID["V8_H3_LOCK"])
    assert t.why == "lock" and t.exit == 115.0 and t.row == 3


def test_h3_stop_is_1000_below_the_0916_value():
    n = night(flat_ix(), np.full(MORN, 105.0), qty=50, entry=100.0)
    n.opt[1][6, L] = 84.0                           # 105 - 20 = 85
    t = R.trade(n, 1, R.BY_ID["V8_H3_LOCK"])
    assert t.why == "stop" and t.exit == 85.0


def test_every_variant_has_a_stop_and_a_cap():
    for s in R.VARIANTS:
        assert s.stop and s.cap == R.CAP_1030
    assert len(R.VARIANTS) <= 8


def test_costs_net_formula():
    n = night(flat_ix(), np.full(MORN, 110.0), qty=75, entry=100.0)
    t = R.trade(n, 1, R.B0)
    ef, xf = float(R.FILL.buy(100.0)), float(R.FILL.sell(110.0))
    want = (xf - ef) * 75 - float(R.COST.charge(True, ef, 75)) - float(R.COST.charge(False, xf, 75)) - 0.0016 * 210 * 75
    assert t.net == pytest.approx(want)
    assert t.net15 == pytest.approx(want - 0.5 * 0.0016 * 210 * 75)


def test_metrics_gave_back_and_rescued():
    class T:
        def __init__(s, d, net, b0, held=True):
            s.d, s.net, s.net_b0, s.held, s.und, s.net15 = d, net, b0, held, "NIFTY", net
    tr = [T(1, -10, 50), T(2, 30, -20), T(3, 5, 5, False), T(4, 100, 80)]
    m = R.metrics(tr, 10)
    assert m["gave_back"] == 1 and m["rescued"] == 1 and m["helped"] == 2 and m["hurt"] == 1 and m["same"] == 1
    assert m["diff"] == 125 - 115 and m["per_day"] == 12.5


def test_bh():
    q = R.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5)


def test_twin_rate_zero_equals_b0_and_one_equals_always_hold():
    ns = []
    rng = np.random.default_rng(1)
    for i in range(20):
        p = 110 + np.cumsum(rng.normal(0, 1, MORN))
        n = night(24000 + np.cumsum(rng.normal(0, 5, MORN)), p)
        n.d = date(2024, 1, 1 + i)
        ns.append(n)
    s = R.BY_ID["V7_H2_I30s"]
    b0 = sum(R.trade(n, 1, R.B0).net for n in ns)
    hold = sum(R.trade(n, 1, s, held=True).net for n in ns)
    assert R.twin_p(ns, s, b0, 0.0, B=50)["twin_mean"] == round(b0)
    assert R.twin_p(ns, s, hold, 1.0, B=50)["twin_mean"] == round(hold)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def _poison(n: Night, t: int, mode: str) -> Night:
    """A copy of n whose next-session rows >= t are blanked ('mask') or absurd ('poison')."""
    import copy
    m = copy.deepcopy(n)
    val = np.nan if mode == "mask" else 1e6
    m.ix[t:] = val
    m.vix[t:] = val
    for s in m.opt:
        m.opt[s][t:] = val
    return m


def _random_night(seed, side):
    rng = np.random.default_rng(seed)
    ix = 24000 + np.cumsum(rng.normal(0, 8, MORN))
    p = np.maximum(110 + side * np.cumsum(rng.normal(0, 2, MORN)), 1)
    n = night(ix, p, side=side, d_close=24000 + rng.normal(0, 40), qty=75, entry=100 + rng.normal(0, 10),
              vix=15 + np.cumsum(rng.normal(0, 0.05, MORN)))
    return n


def test_look_ahead_guard_decision_reads_only_the_0915_bar():
    for seed in range(30):
        for side in (1, -1):
            n = _random_night(seed, side)
            want = R.morning(n, side)
            for mode in ("mask", "poison"):
                m = _poison(n, 1, mode)
                assert R.morning(m, side) == pytest.approx(want, nan_ok=True)
                for s in R.VARIANTS:
                    assert R.decide(m, side, s) == R.decide(n, side, s)


def test_look_ahead_guard_exits_read_nothing_after_the_exit():
    """The exit (row, price, reason) is the same when every row after the exit row is blanked or absurd."""
    checked = 0
    for seed in range(40):
        for side in (1, -1):
            n = _random_night(seed, side)
            for s in (R.B0,) + R.VARIANTS:
                t = R.trade(n, side, s)
                if t.why.endswith("_lastclose"):
                    continue
                for mode in ("mask", "poison"):
                    m = _poison(n, t.row + 1, mode)
                    u = R.trade(m, side, s)
                    assert (u.row, u.exit, u.why, u.held) == (t.row, t.exit, t.why, t.held), (seed, side, s.id, mode)
                    checked += 1
    assert checked > 500


@pytest.mark.skipif(not D.path("NIFTY").exists(), reason="the lake night files are not built here")
def test_look_ahead_guard_on_real_desc_nights():
    n_checked = 0
    for u in K.UNDS:
        ns = [n for n in D.load(u, ("desc",)) if n.r3 != 0 and n.r3 in n.opt][:25]
        for n in ns:
            for s in (R.B0,) + R.VARIANTS:
                t = R.trade(n, n.r3, s)
                if t is None or t.why.endswith("_lastclose"):
                    continue
                u2 = R.trade(_poison(n, t.row + 1, "poison"), n.r3, s)
                assert (u2.row, u2.exit, u2.why) == (t.row, t.exit, t.why), (u, n.d, s.id)
                n_checked += 1
    assert n_checked > 100
