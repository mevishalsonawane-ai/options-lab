"""R195 tests: pricing, stale marks, stops, margin, rules, the look-ahead guard, twins, BH and the holdout lock.
All on hand-made days; no lake needed.   python -m pytest research/hunt/r195_option_selling -q
"""
from __future__ import annotations

import copy
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r195cfg as K  # noqa: E402
import r195engine as E  # noqa: E402
import r195rules as RU  # noqa: E402
import r195study as S  # noqa: E402


class Day:                              # the shape of R51's Day
    def __init__(self, d, idx, con, expiry=False):
        self.d, self.idx, self.con, self.expiry = d, idx, con, expiry
        self.atm = np.full(K.NROWS, np.nan)


def flat_con(p, vol=1000.0):
    c = np.full((5, K.NROWS), np.nan, dtype=np.float32)
    c[0] = c[1] = c[2] = c[3] = p
    c[4] = vol
    return c


def make_day(d=date(2025, 3, 3), spot=22000.0, step=50.0, prem=None, expiry=False):
    idx = np.full((4, K.NROWS), spot)
    D = Day(d, idx, {}, expiry)
    D.atm[:] = spot
    for j in range(-10, 11):
        k = spot + j * step
        D.con[("C", k)] = flat_con(prem or max(100.0 - 8 * j, 5.0))
        D.con[("P", k)] = flat_con(prem or max(100.0 + 8 * j, 5.0))
    return D


EXP = date(2025, 3, 6)


# ------------------------------------------------------------------------------------------------ Black-Scholes
def test_bs_iv_roundtrip():
    t = 3 / 365
    p = float(E.bs(22000, 22100, t, 0.14, True))
    assert abs(E.implied_vol(p, 22000, 22100, t, True) - 0.14) < 1e-4
    assert float(E.bs(22000, 21000, 0.0, 0.2, True)) == 1000.0          # expired: intrinsic
    assert E.implied_vol(0.01, 22000, 22100, t, True) is None             # below intrinsic + 0.05


def test_marks_fresh_then_stale_reprice():
    D = make_day()
    con = D.con[("C", 22100.0)]
    con[:, 100:] = np.nan                                                 # stops printing after minute 99
    D.idx[3, 100:] = 21500.0                                              # the index falls away from the strike
    m, st = E.marks(D, E.Leg("C", 22100.0, 1), 95, 120, date(2025, 3, 3), EXP)
    assert not st[: 10].any() and st[10:].all()                           # 95..104 within FRESH minutes of 99
    assert m[0] == pytest.approx(con[3, 95])
    assert m[-1] < con[3, 99] / 2                                         # repriced lower, not frozen at 99's close
    assert m[-1] >= E.TICK


def test_entry_needs_real_prints():
    D = make_day()
    D.con[("C", 22000.0)][:, :20] = np.nan
    sp = RU.spec_straddle(D, "NIFTY", RU.R0920, stop_leg=1.25)
    assert sp is None or E.run(D, "NIFTY", D.d, sp, EXP, False) is None


# ------------------------------------------------------------------------------------------------ stops and fills
def test_per_leg_stop_closes_one_leg_other_runs():
    D = make_day()
    c = D.con[("C", 22000.0)]
    c[:, 50:] = 130.0                                                     # call +30% from minute 50
    sp = RU.spec_straddle(D, "NIFTY", RU.R0920, stop_leg=1.25)
    t = E.run(D, "NIFTY", D.d, sp, EXP, False)
    assert t["exits"][0] == 51 and t["exits"][1] == K.SQ_ROW              # filled at the next minute's open
    assert t["why"] == "stop+time"
    assert t["gross"] < 0


def test_prem_stop_and_expiry_squareoff():
    D = make_day(expiry=True)
    t = E.run(D, "NIFTY", D.d, RU.spec_straddle(D, "NIFTY", RU.R0920, wings=4, prem_stop=1.5), EXP, True)
    assert t["exits"] == [K.SQ_ROW_EXPIRY] * 4                            # nothing moved: 15:05 square-off
    D.con[("C", 22000.0)][:, 60:] = 200.0                                 # shorts 300 vs 200 entry -> 1.5x
    t = E.run(D, "NIFTY", D.d, RU.spec_straddle(D, "NIFTY", RU.R0920, wings=4, prem_stop=1.5), EXP, True)
    assert t["exits"] == [61] * 4


def test_maxloss_and_index_stop():
    D = make_day()
    sp = RU.spec_spread(D, "NIFTY", RU.R1015, bull=True, index_level=21990.0)
    D.idx[3, 200:] = 21980.0                                              # index closes below the open level
    t = E.run(D, "NIFTY", D.d, sp, EXP, False)
    assert t["why"] == "index" and t["exits"][0] == 201
    D = make_day()
    sp = RU.spec_spread(D, "NIFTY", RU.R1015, bull=True)
    D.con[("P", 21950.0)][:, 150:] = 300.0                                # short put explodes
    t = E.run(D, "NIFTY", D.d, sp, EXP, False)
    assert t["why"] == "stop" and t["exits"][0] == 151


def test_charges_stt_on_sell_and_bse():
    d = date(2026, 5, 4)
    assert E.charge(100.0, 65, False, d, False) > E.charge(100.0, 65, True, d, False)   # STT on the sale
    assert E.charge(100.0, 20, False, d, True) != E.charge(100.0, 20, False, d, False)


def test_overnight_exit_on_next_session():
    D, Dn = make_day(), make_day(d=date(2025, 3, 4))
    for k in Dn.con:
        Dn.con[k][0, 6] = 80.0                                            # next morning's 09:21 open
    sp = RU.spec_straddle(D, "NIFTY", RU.R1515, wings=4)
    t = E.run(D, "NIFTY", D.d, sp, EXP, False, D_next=Dn, d_next=Dn.d, exit_next=6)
    assert t["exits"] == [6] * 4 and t["why"] == "next"


# ------------------------------------------------------------------------------------------------ margin
def test_margin_calibration_varsity_condor():
    legs = [E.Leg("C", 10200, -1), E.Leg("P", 9800, -1), E.Leg("C", 10400, 1), E.Leg("P", 9600, 1)]
    m = E.margin(legs, 75, date(2023, 1, 2), False)
    assert abs(m - 44303) / 44303 < 0.05                                  # 2% x (10200 + 9800) x 75 + 200 x 75
    m_exp = E.margin(legs, 75, date(2025, 1, 2), True)
    assert m_exp == pytest.approx(m + 0.02 * 20000 * 75)                  # +2% ELM on expiry day after Nov 2024
    assert E.margin(legs, 75, date(2024, 1, 4), True) == pytest.approx(m)  # not before 20 Nov 2024
    naked = E.margin([E.Leg("C", 23000, -1), E.Leg("P", 23000, -1)], 65, date(2026, 1, 5), False)
    assert naked > K.ACCOUNT * 2


# ------------------------------------------------------------------------------------------------ rules
def test_rule_strikes():
    D = make_day()
    sp = RU.spec_straddle(D, "NIFTY", RU.R0920, wings=6, side_stop=1.30)
    assert sorted((l.side, l.k, l.sign) for l in sp.legs) == [("C", 22000, -1), ("C", 22300, 1), ("P", 21700, 1),
                                                               ("P", 22000, -1)]
    c = RU.spec_condor(D, "NIFTY", RU.R0920)                              # S = 200 -> a = 2 steps, wings 3 beyond
    assert {(l.side, l.k) for l in c.legs} == {("C", 22100), ("P", 21900), ("C", 22250), ("P", 21750)}
    b = RU.spec_spread(D, "NIFTY", RU.R0920, bull=False)
    assert [(l.side, l.k, l.sign) for l in b.legs] == [("C", 22050, -1), ("C", 22250, 1)]


def _daily(n=300, seed=1):
    rng = np.random.default_rng(seed)
    days = [date(2024, 1, 1) + timedelta(days=i) for i in range(n)]
    c = 22000 * np.exp(np.cumsum(rng.normal(0, 0.008, n)))
    return RU.Daily(days, {d: i for i, d in enumerate(days)}, c * 1.001, c * 1.006, c * 0.994, c, 14 + rng.normal(0, 1, n))


def test_lookahead_guard_filters_ignore_future_sessions():
    Dl = _daily()
    i = 260
    before = (RU.vix_ok(Dl, i), RU.calm_ok(Dl, i), RU.trend_up(Dl, i), RU.rv(Dl, i, 20))
    Dl2 = copy.deepcopy(Dl)
    for a in (Dl2.close, Dl2.high, Dl2.low, Dl2.vix920):
        a[i + 1:] *= 3.0                                                  # change every later session
    Dl2.close[i] *= 1.5                                                   # and today's close (not known at 09:20)
    Dl2.high[i] *= 1.5
    Dl2.low[i] *= 0.5
    assert (RU.vix_ok(Dl2, i), RU.calm_ok(Dl2, i), RU.trend_up(Dl2, i), RU.rv(Dl2, i, 20)) == before


def test_lookahead_guard_decision_ignores_later_minutes():
    D = make_day()
    sp1 = RU.spec_straddle(D, "NIFTY", RU.R0920, stop_leg=1.25)
    sig1 = RU.t1_signal(D)
    D2 = copy.deepcopy(D)
    D2.idx[:, RU.R1015 + 1:] *= 1.05
    D2.atm[RU.R0920 + 1:] = 23000.0
    for k in D2.con:
        D2.con[k][:, RU.R0920 + 1:] *= 3.0
    sp2 = RU.spec_straddle(D2, "NIFTY", RU.R0920, stop_leg=1.25)
    assert [(l.side, l.k) for l in sp1.legs] == [(l.side, l.k) for l in sp2.legs]
    assert RU.t1_signal(D2) == sig1


# ------------------------------------------------------------------------------------------------ statistics
def test_bh_matches_hand_calc():
    q = S.bh({"a": 0.001, "b": 0.02, "c": 0.5}, 10)
    assert q["a"] == pytest.approx(0.01) and q["b"] == pytest.approx(0.1) and q["c"] == pytest.approx(1.0)


def test_stats_drawdown_and_month():
    tr = [dict(day=date(2025, 1, i + 1), net=v, margin=40000, why="time", stale_legs=0, model_fills=0, credit=1, charges=1)
          for i, v in enumerate([1000, -3000, -2000, 4000, -500, 100, 100])]
    s = S.stats(tr)
    assert s["max_dd"] == 5000 and s["worst_month"] == -300 and s["n"] == 7
    assert s["net_wo_top5"] == -5000 and s["affordable_share"] == 1.0


def test_holdout_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "HOLDOUT_FLAG", tmp_path / "HOLDOUT_READ")
    (tmp_path / "HOLDOUT_READ").write_text("read")
    with pytest.raises(PermissionError):
        S.holdout()


def test_periods():
    assert K.period(date(2025, 12, 31)) == "wf" and K.period(date(2026, 1, 1)) == "ho"
    assert K.period(date(2021, 7, 30)) is None and K.period(date(2026, 11, 14)) is None


# ------------------------------------------------------------------------------------------------ Amendment 1 and N1
def test_quality_flags_stale_and_parity():
    import r195quality as Q
    D = make_day()
    store = {D.d: D}
    t = dict(day=D.d, decide=RU.R0920, legs=[("C", 22000.0, -1, 0), ("P", 22000.0, -1, 1)], tag="S1-NIFTY")
    exp = {D.d: EXP}
    assert not Q.flagged(t, store, exp)
    D.con[("C", 22000.0)][4, :] = 0.0                                     # the sold call traded nothing
    assert Q.flagged(t, store, exp)
    D = make_day()
    D.con[("C", 22000.0)][3, :] = 300.0                                   # call 300 vs put 100 at the money
    assert Q.parity_bad(t, {D.d: D}, exp)


def test_nn_features_ignore_future_sessions():
    import r195nn as N
    Dl = _daily()
    days = Dl.days
    store = {d: make_day(d=d) for d in days}
    d = days[260]
    exp = d + timedelta(days=3)
    x1 = N.features(d, store, Dl, exp)
    Dl2 = copy.deepcopy(Dl)
    for a in (Dl2.close, Dl2.high, Dl2.low, Dl2.vix920):
        a[261:] *= 2.0
    store[d].idx[:, RU.R0920 + 1:] *= 1.1
    x2 = N.features(d, store, Dl2, exp)
    assert x1 is not None and x1 == x2
