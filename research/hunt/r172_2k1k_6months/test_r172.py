"""R172 tests: Boss's +Rs 2,000 / -Rs 1,000 levels (before charges, whole position, N lots), the 'tighter stop only' rule,
the simulator with those levels on synthetic contracts (target fills at its price, the stop's gross loss, gaps), no
look-ahead (levels and exits never change when data after the exit is changed), and the pure report helpers (exit mix,
months, worst month, the arms summed, the window). Synthetic data only: no stores, no lake."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r172cfg as K  # noqa: E402

Z = pytest.importorskip("r172engine")
import r106engine as X  # noqa: E402
import engine as E  # noqa: E402
import r172study as S  # noqa: E402

STEPS = np.array([[0.30, np.nan], [0.60, 0.30], [1.00, 0.60], [0.25, 0.05]])
XL = E.Exits(stop=0.15, target=-1.0, steps=tuple(map(tuple, STEPS)), tmax=30, sq_row=360)
LIQ = E.Exits(stop=0.15, tmax=20, need=0.05)


def _flat(n=375, px=100.0, vol=100000.0):
    C = np.full((5, n), px, dtype=np.float64)
    C[4] = vol
    return C


def _sim(C, e, qty, ex, on, sq=360):
    raw, real, ef, _ = X.entry_info(C, e, sq, qty)
    t, s, f, kind = Z.levels(raw, real, qty, ex, on)
    out = X.simulate(C, e, sq, -1, f, ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail, ex.steps_arr(), ex.tmax,
                     ex.need, ex.close_stop, 0.05, qty, 0.0, s, t, *E.SLIP)
    return (raw, real, t, s, kind), out


# ------------------------------------------------------------------------------------------------ levels
def test_target_is_entry_fill_plus_2000_over_qty_rounded_up():
    assert Z.target_px(100.2, 65.0) == pytest.approx(131.0)            # 100.2 + 30.769 = 130.969 -> 131.00
    assert (Z.target_px(100.2, 65.0) - 100.2) * 65 >= 2000.0
    assert Z.target_px(100.2, 195.0) == pytest.approx(110.5)           # 3 lots: 10.256 a unit -> 110.456 -> 110.50
    assert (Z.target_px(100.2, 195.0) - 100.2) * 195 >= 2000.0


def test_stop_is_entry_fill_minus_1000_over_qty_rounded_up_so_planned_loss_is_at_most_1000():
    s = Z.stop_px(100.2, 65.0)
    assert s == pytest.approx(84.85) and (100.2 - s) * 65 <= 1000.0
    s3 = Z.stop_px(100.2, 195.0)
    assert s3 == pytest.approx(95.1) and (100.2 - s3) * 195 <= 1000.0
    assert Z.stop_px(10.0, 65.0) == -1.0                                # the whole position is worth < Rs 1,000


def test_rupee_stop_acts_only_when_tighter_than_the_arms_own_stop():
    # 1 lot of 65 at Rs 100: Rs 1,000 = 15.4 points, -15% = Rs 85 -> the arm's own stop is tighter and stays
    t, s, f, k = Z.levels(100.0, 100.2, 65.0, XL, True)
    assert (s, f, k) == (-1.0, 0.15, "own") and t == pytest.approx(131.0)
    # 3 lots (195) at Rs 100: Rs 1,000 = 5.1 points -> the rupee stop (95.10) is tighter and replaces -15%
    t, s, f, k = Z.levels(100.0, 100.2, 195.0, XL, True)
    assert k == "rupee" and f == -1.0 and s == pytest.approx(95.1)
    # a cheap option at 1 lot: -15% is far tighter than Rs 1,000
    assert Z.levels(40.0, 40.1, 65.0, XL, True)[3] == "own"


def test_off_keeps_todays_exits_exactly():
    assert Z.levels(100.0, 100.2, 195.0, XL, False) == (-1.0, -1.0, 0.15, "own")
    assert Z.levels(100.0, 100.2, 195.0, LIQ, False) == (-1.0, -1.0, 0.15, "own")


# ------------------------------------------------------------------------------------------------ simulator
def test_target_fills_at_its_price_and_makes_at_least_2000_before_charges():
    C = _flat()
    C[1, 20:] = 140.0                                                  # the high reaches 140 at row 20
    (raw, real, t, s, kind), out = _sim(C, 10, 65.0, XL, True)
    a, b, rE, rX, r0, r1, r = out
    assert r == E.R_TARGET and r1 == 20 and rX == pytest.approx(t)
    assert (rX - rE) * 65 >= 2000.0


def test_rupee_stop_sells_near_minus_1000_and_gaps_fill_at_the_open():
    qty = 195.0
    C = _flat()
    C[2, 30] = 94.0                                                    # the low touches below the stop at row 30
    (raw, real, t, s, kind), out = _sim(C, 10, qty, XL, True)
    assert kind == "rupee"
    a, b, rE, rX, r0, r1, r = out
    assert r == E.R_STOP and r1 == 30 and b == pytest.approx(s)
    gross = (rX - rE) * qty
    assert -1100.0 < gross <= -1000.0 + 0.05 * qty                       # about Rs 1,000 plus the stop fill's slippage
    assert gross <= -(100.2 - s) * qty + 1e-6
    # a gap: the minute opens at 90 (below the stop) -> filled from the open, loss beyond Rs 1,000
    G_ = _flat()
    G_[0, 30] = G_[2, 30] = G_[3, 30] = 90.0
    _, out2 = _sim(G_, 10, qty, XL, True)
    assert out2[6] == E.R_STOP and out2[1] == pytest.approx(90.0) and (out2[3] - out2[2]) * qty < -1500.0


def test_own_stop_still_acts_at_one_lot_when_tighter():
    C = _flat()
    C[2, 30] = 84.9                                                    # below -15% (85) but above the Rs 1,000 level
    (raw, real, t, s, kind), out = _sim(C, 10, 65.0, XL, True)
    assert kind == "own" and out[6] == E.R_STOP and out[1] == pytest.approx(85.0)


def test_with_the_rule_off_the_simulator_is_todays_exits():
    rng = np.random.default_rng(172)
    for _ in range(30):
        c = 100.0 * np.exp(np.cumsum(rng.normal(0, 0.02, 375)))
        o = np.r_[100.0, c[:-1]]
        C = np.vstack([o, np.maximum(o, c) * 1.005, np.minimum(o, c) * 0.995, c, np.full(375, 5000.0)])
        _, a = _sim(C, 12, 65.0, XL, False)
        b = X.simulate(C, 12, 360, -1, XL.stop, XL.target, XL.lock_trig, XL.lock_keep, XL.lock_trail, XL.steps_arr(),
                       XL.tmax, XL.need, XL.close_stop, 0.05, 65.0, 0.0, -1.0, -1.0, *E.SLIP)
        assert np.allclose(np.nan_to_num(a, nan=-9), np.nan_to_num(b, nan=-9))


def test_no_look_ahead_levels_and_exit_ignore_data_after_the_exit():
    """Levels come from the entry fill only; changing every minute after the exit changes nothing."""
    rng = np.random.default_rng(7)
    for lots in (1, 2, 3):
        qty = 65.0 * lots
        for _ in range(20):
            c = 100.0 * np.exp(np.cumsum(rng.normal(0, 0.03, 375)))
            o = np.r_[100.0, c[:-1]]
            C = np.vstack([o, np.maximum(o, c) * 1.01, np.minimum(o, c) * 0.99, c, np.full(375, 5000.0)])
            lv, out = _sim(C, 12, qty, XL, True)
            xr = out[5]
            D = C.copy()
            D[:4, xr + 1:] *= rng.uniform(0.2, 5.0)
            lv2, out2 = _sim(D, 12, qty, XL, True)
            assert lv == lv2 and np.allclose(out, out2)
            # and the levels are fixed at the fill: changing rows after the fill row does not move them
            F = C.copy()
            F[:4, 14:] *= 3.0
            lv3, _ = _sim(F, 12, qty, XL, True)
            assert lv3 == lv


def test_liquidity_time_stop_and_index_exit_still_act():
    C = _flat()
    (raw, real, t, s, kind), out = _sim(C, 10, 105.0, LIQ, True)       # nothing moves: the 20-min time stop exits
    assert out[6] == E.R_TIME and out[5] == 10 + 20 - 1


# ------------------------------------------------------------------------------------------------ report helpers
def _t(d, net, why="time", arm=None):
    x = dict(day=d, net=net, why=why)
    if arm:
        x["arm"] = arm
    return x


def test_exit_mix_counts_target_stop_and_other():
    tr = [_t(date(2026, 5, 4), 2000, "target2k"), _t(date(2026, 5, 4), -1010, "stop1k"), _t(date(2026, 5, 5), -900, "stop"),
          _t(date(2026, 5, 6), 300, "lock"), _t(date(2026, 5, 6), 10, "time")]
    m = S.exit_mix(tr)
    assert (m["target2k"], m["stop1k"], m["other"], m["own_stop"], m["other_rest"]) == (1, 1, 3, 1, 2)
    assert S.exit_mix([]) == dict(target2k=0, stop1k=0, other=0, own_stop=0, other_rest=0, reasons={})


def test_avg_net_by_exit_reason():
    tr = [_t(date(2026, 5, 4), 1900, "target2k"), _t(date(2026, 5, 4), 1950, "target2k"), _t(date(2026, 5, 5), -1200, "stop1k")]
    assert S.avg_net(tr, "target2k") == 1925 and S.avg_net(tr, "stop1k") == -1200 and S.avg_net(tr, "lock") is None


def test_months_cover_the_whole_window_and_empty_months_are_zero():
    tr = [_t(date(2026, 4, 20), 100), _t(date(2026, 6, 1), -50), _t(date(2026, 6, 30), -25), _t(date(2026, 10, 19), 5)]
    m = S.monthly(tr)
    assert list(m) == list(K.MONTHS)
    assert m == {"2026-04": 100, "2026-05": 0, "2026-06": -75, "2026-07": 0, "2026-08": 0, "2026-09": 0, "2026-10": 5}
    assert S.worst_month(tr) == ("2026-06", -75)
    assert S.monthly([]) == {k: 0 for k in K.MONTHS}


def test_together_adds_up_the_arms_and_keeps_their_names():
    by = {"LIQ": [_t(date(2026, 5, 4), 10)], "R72": [_t(date(2026, 5, 3), 20)], "R51": []}
    tog = S.together(by)
    assert [t["arm"] for t in tog] == ["R72", "LIQ"] and sum(t["net"] for t in tog) == 30
    assert S.together({}) == []


def test_window_is_the_last_six_months_to_the_lakes_end():
    assert K.START == date(2026, 4, 20) and K.END == date(2026, 10, 19)
    assert K.in_window(date(2026, 4, 20)) and K.in_window(date(2026, 10, 19))
    assert not K.in_window(date(2026, 4, 17)) and not K.in_window(date(2026, 10, 20))
    assert K.month_of(date(2026, 10, 19)) == "2026-10" and K.MONTHS[0] == "2026-04" and K.MONTHS[-1] == "2026-10"
