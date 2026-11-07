"""R153 tests: the day loss cap, the 10:00 cut, loss statistics, twins, gates, and LOCK2K in the engine (levels from the
fill only, a look-ahead guard, and the lock selling at about +Rs 1,000). Synthetic data only: no stores, no lake."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r153lib as L  # noqa: E402

D1, D2, D3 = date(2026, 4, 6), date(2026, 4, 7), date(2026, 5, 4)


def _t(day, er, xr, net, und="NIFTY", side="C", why="time"):
    return dict(day=day, er=er, er_sig=er - 1, xr=xr, net=float(net), und=und, side=side, why=why)


# ------------------------------------------------------------------------------------------------ day cap
def test_day_cap_blocks_entries_after_closed_losses_reach_minus_4000():
    tr = [_t(D1, 10, 20, -2100), _t(D1, 25, 30, -2000), _t(D1, 31, 40, 5000), _t(D1, 50, 60, 300, und="BANKNIFTY")]
    kept = L.day_cap(tr)
    assert [t["er"] for t in kept] == [10, 25]                    # -4,100 closed by row 30: later entries blocked


def test_day_cap_counts_only_trades_closed_before_the_entry():
    tr = [_t(D1, 10, 50, -5000), _t(D1, 20, 30, 100), _t(D1, 60, 70, 100)]
    kept = L.day_cap(tr)
    assert [t["er"] for t in kept] == [10, 20]                    # row 20: the -5,000 is still open, so allowed


def test_day_cap_resets_each_day_and_keeps_winning_days():
    tr = [_t(D1, 10, 20, -4000), _t(D1, 30, 40, 900), _t(D2, 10, 20, 100), _t(D2, 30, 40, 200)]
    assert [(t["day"], t["er"]) for t in L.day_cap(tr)] == [(D1, 10), (D2, 10), (D2, 30)]
    assert L.day_cap([]) == []


def test_am_keep_is_10_00_inclusive():
    assert L.am_keep(dict(er=45)) and not L.am_keep(dict(er=46))


# ------------------------------------------------------------------------------------------------ statistics
def test_loss_stats():
    tr = [_t(D1, 10, 20, -1000, why="stop2k"), _t(D1, 30, 40, 400, why="lock"), _t(D2, 10, 20, 2000),
          _t(D3, 10, 20, -500, why="stop")]
    s = L.loss_stats(tr)
    assert s["net"] == 900 and s["loss_sum"] == -1100 and s["losing_days"] == 2 and s["worst_day"] == -600
    assert s["max_dd"] == 600 and s["worst_month"] == -500 and s["sum_losing_trades"] == -1500
    assert s["mix"] == {"stop2k": 1, "lock": 1, "other": 1, "stop": 1}
    assert L.loss_stats([])["trades"] == 0


def test_changed_counts_new_missing_and_different_exits():
    a = [_t(D1, 10, 20, 100), _t(D1, 30, 40, 50)]
    b = [_t(D1, 10, 25, 100), _t(D2, 30, 40, 50)]
    assert L.changed(a, b) == 3 and L.changed(a, a) == 0


# ------------------------------------------------------------------------------------------------ twins and gates
def test_twin_p_counts_ties_and_uses_normal_tail_when_no_twin_reaches():
    assert L.twin_p(-10.0, [-10.0, -20.0, -5.0, -30.0])["p"] == pytest.approx(3 / 5)
    r = L.twin_p(0.0, np.random.default_rng(1).normal(-100, 10, 2000))
    assert r["hits"] == 0 and r["p"] < 1e-6


def test_exit_twin_loss_sums_fixed_only_and_with_draws():
    out = L.exit_twin_loss_sums([(D1, -100.0), (D2, 50.0)], [], 5, np.random.default_rng(0))
    assert np.all(out == -100.0)
    out = L.exit_twin_loss_sums([(D1, -100.0)], [(D1, [200.0])], 3, np.random.default_rng(0))
    assert np.all(out == 0.0)                                     # the day turns green with the drawn exit


def test_day_skip_twin_removes_the_same_number_per_day():
    base = [_t(D1, 10, 20, -100), _t(D1, 30, 40, -100), _t(D2, 10, 20, -50)]
    kept = [_t(D1, 10, 20, -100), _t(D2, 10, 20, -50)]
    out = L.day_skip_twin_loss_sums(base, kept, 50, np.random.default_rng(3))
    assert np.all(out == -150.0)                                  # one of D1's two equal trades removed


def _per(net=1000, loss=-500, dd=300, wd=-200, wm=-100, n=100):
    return dict(net=net, loss_sum=loss, max_dd=dd, worst_day=wd, worst_month=wm, trades=n)


def test_gates_need_every_loss_measure_in_every_period():
    base = {p: _per() for p in ("design", "wf", "final")}
    good = {p: _per(net=10, loss=-400, dd=300, wd=-200, wm=-100) for p in ("design", "wf", "final")}
    assert L.gates(good, base, 0.01, 0.05, 100)["passed"]
    bad = dict(good, wf=_per(net=10, loss=-400, dd=301))
    assert "wf: drawdown worse" in L.gates(bad, base, 0.01, 0.05, 100)["fails"]
    assert not L.gates(good, base, 0.06, 0.05, 100)["passed"]
    assert not L.gates(good, base, 0.01, 0.2, 100)["passed"]
    assert not L.gates(good, base, 0.01, 0.05, 29)["passed"]
    neg = {p: _per(net=-1, loss=-400) for p in ("design", "wf", "final")}
    assert any("no longer makes money" in f for f in L.gates(neg, base, 0.01, 0.05, 100)["fails"])


# ------------------------------------------------------------------------------------------------ LOCK2K in the engine
Z = pytest.importorskip("r153engine")


def _ch(qty):
    return lambda a, b: 40.0 + 0.001 * b * qty + 0.0005 * (a + b) * qty + 7.2  # noqa: E731


def test_lock_levels_net_2000_and_1000_for_the_position():
    import r106engine as X
    for qty in (65.0, 130.0, 195.0):
        lt, lk = Z.lock_levels(100.0, 100.2, qty, _ch(qty))
        xt, xk = 100.0 + lt / qty, 100.0 + lk / qty
        assert xt > xk > 100.2
        assert (xt - 100.2) * qty - _ch(qty)(100.2, xt) >= 2000 - 1e-6
        assert (xk - 100.2) * qty - _ch(qty)(100.2, xk) >= 1000 - 1e-6
        assert xk == X.target_price(100.2, qty, 1000.0, _ch(qty))


def _day(highs=(), lows=()):
    import r51data
    import engine as E
    C = np.full((5, 375), np.nan)
    C[:, :] = np.array([100.0, 100.5, 99.5, 100.0, 1e6])[:, None]
    for r, v in highs:
        C[1, r] = v
    for r, v in lows:
        C[2, r] = v
    E._STACKS.clear()
    return r51data.Day(D1, np.full((4, 375), 22000.0), atm=np.full(375, 22000.0), con={("C", 21950.0): C})


def _xl():
    import engine as E
    return E.Exits(stop=0.15, target=-1.0, steps=((0.30, np.nan), (0.60, 0.30), (1.00, 0.60), (0.25, 0.05)), tmax=30,
                   sq_row=360)


def test_lock_sells_near_plus_1000_after_reaching_plus_2000():
    # 3 lots (195): +2k needs about +10.9 a unit; the premium's high hits 112 at row 25; row 26 opens at 109 (above the
    # +1k lock, about 105.8) and dips to 104, so it sells at the lock price
    D = _day(highs=[(25, 112.0)])
    C = D.con[("C", 21950.0)]
    C[:4, 26] = (109.0, 109.5, 104.0, 105.0)
    c = [dict(side="C", strike=21950.0, er=20)]
    r = Z.price("NIFTY", D, c, _xl(), 2000.0, True, 3)[0]
    assert r["why"] == "lock" and r["xr"] == 26 and 800 <= r["net"] <= 1100
    C[0, 26] = 100.0                                              # a gap below the lock fills at the open instead
    import engine as E
    E._STACKS.clear()
    g = Z.price("NIFTY", D, c, _xl(), 2000.0, True, 3)[0]
    assert g["why"] == "lock" and g["X"] == 100.0 and g["net"] < r["net"]
    b0 = Z.price("NIFTY", D, c, _xl(), None, False, 3)[0]
    assert b0["why"] != "lock" or b0["xr"] != 30


def test_lock_look_ahead_guard_nothing_after_the_exit_minute_matters():
    D = _day(highs=[(25, 112.0)], lows=[(30, 104.0)])
    c = [dict(side="C", strike=21950.0, er=20)]
    a = Z.price("NIFTY", D, c, _xl(), 2000.0, True, 3)[0]
    D2_ = _day(highs=[(25, 112.0), (31, 300.0)], lows=[(30, 104.0), (35, 1.0)])
    b = Z.price("NIFTY", D2_, c, _xl(), 2000.0, True, 3)[0]
    assert (a["xr"], a["net"]) == (b["xr"], b["net"])


def test_no_lock_is_r152_pricing():
    import r152engine as Y
    D = _day(highs=[(25, 112.0)], lows=[(30, 104.0)])
    c = [dict(side="C", strike=21950.0, er=20)]
    import engine as E
    assert Z.price("NIFTY", D, c, _xl(), 2000.0, False, 2)[0]["net"] == pytest.approx(
        Y.price("NIFTY", D, c, _xl(), None, 2000.0, 2)[0]["net"])
    E._STACKS.clear()
