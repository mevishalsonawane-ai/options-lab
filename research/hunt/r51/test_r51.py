"""R51 tests: the held-contract engine (fills, stops, gaps, locks, time stops, forced exits, missing minutes, expiry
square-off), the period guard and one-time final read, lots by date, strikes, sequential taking, statistics, BH,
walk-forward selection, the gates, and the look-ahead guard of the reused signal code. Synthetic data only (no lake).

    python -m pytest research/hunt/r51 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import common as C  # noqa: E402
import engine as E  # noqa: E402
import r51cfg as K  # noqa: E402
import r51data  # noqa: E402

NAN = np.nan
N = K.NROWS


def contract(o=100.0, n=N, vol=1000.0):
    """A flat contract: every minute O=H=L=C=o, volume vol."""
    a = np.full((5, n), float(o))
    a[4] = vol
    return a


def sim(C_, e=10, sq=K.SQ_ROW, fx=-1, ex=E.Exits(), qty=75.0):
    return E.simulate(np.asarray(C_, float), e, sq, fx, ex.stop, ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail,
                      ex.steps_arr(), ex.tmax, ex.need, ex.close_stop, K.TICK, qty)


# ------------------------------------------------------------------------------------------------ engine
def test_no_fill_when_no_trade_in_three_minutes():
    a = contract()
    a[:, 10:13] = NAN
    assert sim(a)[6] == E.R_NOFILL
    a = contract()
    a[:, 10:12] = NAN                       # third minute trades: filled there
    r = sim(a)
    assert r[6] != E.R_NOFILL and r[4] == 12


def test_entry_after_square_off_is_no_fill():
    assert sim(contract(), e=K.SQ_ROW)[6] == E.R_NOFILL


def test_real_fill_costs_buy_up_sell_down():
    r = sim(contract(), ex=E.Exits())          # square-off at the same price
    E_, X_, rE, rX = r[:4]
    assert E_ == X_ == 100.0
    assert rE == pytest.approx(100.05 + 0.05)  # +5 bps, 1 tick (>= 20 lots in the 5 minutes before)
    assert rX == pytest.approx(99.95 - 0.05)
    assert r[6] == E.R_SQ and r[5] == K.SQ_ROW


def test_thin_volume_widens_spread():
    a = contract(vol=0.0)
    rE = sim(a)[2]
    assert rE == pytest.approx(100.05 + 4 * 0.05)


def test_stop_on_low_and_gap_fills_at_worse_open():
    a = contract()
    a[2, 20] = 70.0                             # wick through a -25% stop
    r = sim(a, ex=E.Exits(stop=0.25))
    assert r[6] == E.R_STOP and r[1] == pytest.approx(75.0) and r[5] == 20
    b = contract()
    b[0, 20], b[1, 20], b[2, 20], b[3, 20] = 60.0, 62.0, 58.0, 61.0    # gap open below the stop
    r = sim(b, ex=E.Exits(stop=0.25))
    assert r[1] == pytest.approx(60.0)
    assert r[3] == pytest.approx(round(60 * 0.999, 2) - 2 * 0.05)      # stop sell: -10 bps, 2x half-spread


def test_stop_wins_a_tie_with_target():
    a = contract()
    a[1, 20], a[2, 20] = 160.0, 70.0
    assert sim(a, ex=E.Exits(stop=0.25, target=0.5))[6] == E.R_STOP


def test_target_is_a_limit_at_its_price():
    a = contract()
    a[1, 30] = 200.0
    r = sim(a, ex=E.Exits(target=0.5))
    assert r[6] == E.R_TARGET and r[1] == r[3] == pytest.approx(150.0)


def test_rupee_lock_arms_next_minute_and_trails():
    a = contract()
    a[1, 20] = 120.0                            # +Rs 20 x 75 = Rs 1,500 >= 1,000: lock = max(E+500/75, peak-500/75)
    a[2, 20] = 100.0                            # same minute dip: the lock is not armed yet
    a[:, 21] = [115.0, 115.0, 110.0, 112.0, 1000.0]   # next minute: low 110 < 120 - 6.67 = 113.33 -> lock out
    r = sim(a, ex=E.Exits(lock_trig=1000, lock_keep=500, lock_trail=500))
    assert r[6] == E.R_LOCK and r[5] == 21
    assert r[1] == pytest.approx(120 - 500 / 75)
    a[0, 21] = 105.0                                  # opening below the lock: the worse price, the open
    assert sim(a, ex=E.Exits(lock_trig=1000, lock_keep=500, lock_trail=500))[1] == pytest.approx(105.0)


def test_stepped_lock():
    a = contract()
    a[1, 20] = 130.0                            # +30%: lock at +0%
    a[2, 22] = 99.0
    r = sim(a, ex=E.Exits(steps=((0.25, 0.0), (0.5, 0.25))))
    assert r[6] == E.R_LOCK and r[1] == pytest.approx(100.0)


def test_time_stop_and_need():
    a = contract()
    r = sim(a, ex=E.Exits(tmax=30))
    assert r[6] == E.R_TIME and r[5] == 10 + 29
    b = contract()
    b[:4, 39:] = 106.0                          # +6% at the time-stop minute: kept (need 5%) to the square-off
    r = sim(b, ex=E.Exits(tmax=30, need=0.05))
    assert r[6] == E.R_SQ


def test_close_stop_sells_next_open():
    a = contract()
    a[3, 25] = 65.0                             # close <= 70% of the fill
    a[0, 26] = 66.0
    r = sim(a, ex=E.Exits(close_stop=0.30))
    assert r[6] == E.R_CSTOP and r[5] == 26 and r[1] == pytest.approx(66.0)


def test_forced_exit_row_and_missing_minutes():
    a = contract()
    r = sim(a, fx=50)
    assert r[6] == E.R_FORCED and r[5] == 50
    b = contract()
    b[:, 40:] = NAN                             # the contract stops trading: last close, flagged missing
    r = sim(b)
    assert r[6] == E.R_MISSING and r[1] == pytest.approx(100.0)


def test_sq_row_expiry_and_own():
    d = r51data.Day(date(2025, 1, 2), np.zeros((4, N)), expiry=True)
    assert E.sq_row(d, E.Exits()) == K.SQ_ROW_EXPIRY
    assert E.sq_row(d, E.Exits(sq_row=K.row(15, 20))) == K.SQ_ROW_EXPIRY
    d.expiry = False
    assert E.sq_row(d, E.Exits(sq_row=K.row(15, 15))) == K.row(15, 15)


def test_strike_rules():
    idx = np.full((4, N), 24012.0)
    atm = np.full(N, 24000.0)
    d = r51data.Day(date(2025, 1, 2), idx, atm)
    assert E.strike_for(d, "NIFTY", "C", 5, 1) == 23950.0          # 1-ITM call below
    assert E.strike_for(d, "NIFTY", "P", 5, 1) == 24050.0          # 1-ITM put above
    assert E.strike_for(d, "NIFTY", "C", 5, -2) == 24100.0         # OTM2 call
    d.atm[5] = NAN                                                # no rolling ATM: index rounded to the step
    assert E.strike_for(d, "NIFTY", "C", 5, 0) == 24000.0
    d.idx[3, 5] = NAN
    assert E.strike_for(d, "NIFTY", "C", 5, 0) is None


def test_charges_dated_stt_change():
    a = E.charges("NIFTY", 100.0, 100.0, 75, date(2026, 3, 31))[0]
    b = E.charges("NIFTY", 100.0, 100.0, 75, date(2026, 4, 1))[0]
    assert b > a                                                  # STT 0.1% -> 0.15% on the sell from 1 Apr 2026


# ------------------------------------------------------------------------------------------------ config and guards
def test_lots_by_date():
    assert K.lot("NIFTY", date(2024, 11, 21)) == 25
    assert K.lot("NIFTY", date(2024, 11, 28)) == 75
    assert K.lot("NIFTY", date(2026, 1, 5)) == 65
    assert K.lot("BANKNIFTY", date(2025, 7, 1)) == 35
    assert K.lot("BANKNIFTY", date(2026, 1, 5)) == 30


def test_period_guard():
    with pytest.raises(PermissionError):
        C.check_range(date(2024, 11, 1), date(2025, 1, 1))        # before 20 Nov 2024
    with pytest.raises(PermissionError):
        C.check_range(K.START, date(2026, 4, 1))                  # the final test, before `study.py final`
    C.check_range(K.START, K.DESIGN_END)
    assert K.period_of(date(2024, 11, 19)) is None
    assert K.period_of(date(2026, 3, 31)) == "design" and K.period_of(date(2026, 4, 1)) == "final"


def test_final_read_only_once(tmp_path, monkeypatch):
    import study
    flag = tmp_path / "FINAL_READ"
    flag.write_text("read once\n")
    monkeypatch.setattr(K, "FINAL_FLAG", flag)
    with pytest.raises(SystemExit):
        study.final()


# ------------------------------------------------------------------------------------------------ taking and stats
def _day_with(contracts: dict, expiry=False):
    d = r51data.Day(date(2025, 2, 3), np.full((4, N), 24000.0), np.full(N, 24000.0), expiry=expiry)
    d.con = contracts
    return d


def test_take_one_position_a_side_and_max_per_side():
    d = _day_with({("C", 24000.0): contract(), ("P", 24000.0): contract()})
    ex = E.Exits(tmax=30)
    cands = [dict(er=10, side="C", strike=24000.0), dict(er=20, side="C", strike=24000.0),   # busy: skipped
             dict(er=50, side="C", strike=24000.0), dict(er=60, side="P", strike=24000.0),
             dict(er=100, side="C", strike=24000.0)]
    T = C.take("NIFTY", d, cands, ex, scope="side", max_per_side=2)
    assert [(t["side"], t["er"]) for t in T] == [("C", 10), ("C", 50), ("P", 60)]
    T = C.take("NIFTY", d, cands, ex, scope="all", max_per_day=2)
    assert [(t["side"], t["er"]) for t in T] == [("C", 10), ("C", 50)]


def test_stats_top5_and_drawdown():
    T = pd.DataFrame(dict(day=[date(2025, 1, d) for d in (1, 1, 2, 3, 6, 7, 8)],
                          net=[100.0, -50.0, 1000.0, -300.0, 200.0, 50.0, -20.0], why="time"))
    s = C.stats(T, sessions=10)
    assert s["trades"] == 7 and s["net"] == 980.0 and s["per_day"] == 98.0
    assert s["ex_top5"] == pytest.approx(980 - (1000 + 200 + 100 + 50 - 20))
    assert s["max_dd"] == 300.0 and s["worst_day"] == -300.0
    assert C.stats(T.iloc[:0], 10)["trades"] == 0


def test_bh():
    q = C.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5)
    assert np.all(q >= np.array([0.01, 0.04, 0.03, 0.5]) - 1e-12)


def test_gates():
    import study
    f = dict(trades=50, net=1000.0, p=0.01, q=0.05, ex_top5=10.0)
    assert study.gates(f, 5.0) == (True, "PASS")
    assert study.gates(dict(f, trades=39), 5.0)[1] == "too few trades to judge"
    ok, why = study.gates(dict(f, ex_top5=-1.0, q=0.2), -1.0)
    assert not ok and "top-5" in why and "q > 0.10" in why and "walk-forward" in why


def test_walk_forward_picks_from_the_past_only():
    import study
    days = pd.date_range("2024-12-01", "2026-03-31", freq="7D").date
    a = pd.DataFrame(dict(day=days, net=np.where(np.array(days) < date(2025, 7, 1), 100.0, -100.0)))
    b = pd.DataFrame(dict(day=days, net=np.where(np.array(days) < date(2025, 7, 1), -100.0, 100.0)))
    v = C.Variant("x", "f", "index", "retune", [C.Config("A", None), C.Config("B", None)])
    wf = study.walk_forward(v, [a, b])
    assert wf["wf_picks"][0] == "A" and wf["wf_picks"][1] == "A"      # A led before Apr / Jul 2025
    assert wf["final_pick"] == "B" or wf["final_pick"] == "A"


def test_twin_totals_deterministic():
    d = _day_with({("C", 24000.0): contract(), ("C", 23950.0): contract()})
    days = {d.d: d}
    ex = E.Exits(tmax=10)
    t1 = E.twin_totals("NIFTY", days, [(d.d, "C", 2, 5, 300)], ex, 50, np.random.default_rng(1))
    t2 = E.twin_totals("NIFTY", days, [(d.d, "C", 2, 5, 300)], ex, 50, np.random.default_rng(1))
    assert np.allclose(t1, t2) and np.all(t1 < 0)                 # flat prices: costs only


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_r44_signals_do_not_change_when_the_future_is_cut():
    import fam_r44
    rng = np.random.default_rng(3)
    c = 24000 + np.cumsum(rng.normal(0, 10, 900))
    h, l = c + 5, c - 5
    for kind, params, _ in fam_r44.STRATS.values():
        full = fam_r44.IND.signals(kind, params, c, h, l, c, warm=100)
        cut = fam_r44.IND.signals(kind, params, c[:600], h[:600], l[:600], c[:600], warm=100)
        assert np.array_equal(full[0][:600], cut[0]) and np.array_equal(full[1][:600], cut[1])


def test_liquidity_forced_row_uses_minutes_after_entry_only():
    import fam_liq
    idx = np.full((4, N), 100.0)
    d = r51data.Day(date(2025, 2, 3), idx)
    s = dict(er=50, sgn=1, idx_stop=90.0, target=np.nan, bar_exit=-1)
    d.idx[2, 40] = 80.0                          # before the entry: ignored
    assert fam_liq.forced_row(d, s) == -1
    d.idx[2, 60] = 89.0                          # index stop touched at 60 -> out at the next open
    assert fam_liq.forced_row(d, s) == 61
    s2 = dict(s, bar_exit=55)
    assert fam_liq.forced_row(d, s2) == 55
