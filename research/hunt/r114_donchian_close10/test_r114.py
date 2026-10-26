"""R114 tests: the rows and counts the PREREG fixed, the morning cut and 10:00 close (with the look-ahead guard), the
book's statistics, the freed place under the cap, both twins, BH over m = 5,841 and the gates. Synthetic data only."""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np

import r114cfg as K
import r114lib as L
import r108book as B

D0 = date(2026, 4, 6)


def _t(day, net, arm="R72", er=10, xr=20, und="NIFTY", side="C"):
    return dict(day=day, net=float(net), arm=arm, er=er, xr=xr, er_sig=er, und=und, side=side, book=None)


# ------------------------------------------------------------------------------------------------ settings
def test_rows_and_counts_are_what_prereg_says():
    assert K.RULES["BOOK0"] == (None, None)
    assert K.RULES["X10"] == (40, 45)                           # last entry the 09:55 bar close, sold at 10:00
    assert [K.RULES[c] for c in K.CLOCKS] == [(25, 30), (55, 60), (70, 75), (100, 105)]
    assert K.SEED == 113 and K.TWINS == 100_000 and K.GATE_Q == 0.10 and K.MIN_CHANGED == 30
    assert K.N_PRIOR == 5819 + 10 + 11 and K.M_TOTAL == 5841
    assert K.ARMS == ("LIQ", "R72", "R51", "R52") and K.DONCHIAN == ("R72", "R51")


def test_smallest_possible_q_can_pass():
    q = B.bh([1 / (K.TWINS + 1)], K.M_TOTAL)[0]
    assert 0.05 < q <= K.GATE_Q


def test_periods():
    assert K.in_period(date(2025, 4, 1), "wf") and K.in_period(date(2025, 4, 1), "design")
    assert not K.in_period(date(2026, 4, 1), "design") and K.in_period(date(2026, 4, 1), "final")
    assert K.in_period(date(2026, 10, 13), "final") and not K.in_period(date(2026, 10, 14), "final")
    assert K.in_period(date(2026, 10, 19), "post") and not K.in_period(date(2026, 10, 20), "post")


# ------------------------------------------------------------------------------------------------ the rule
def test_morning_keeps_the_0955_bar_and_forces_the_1000_close():
    cands = [dict(er=e, side="C") for e in (5, 40, 41, 45, 355)]
    keep, cs = L.morning(cands, 40, 45)
    assert keep == [0, 1] and [c["fx"] for c in cs] == [45, 45]
    assert "fx" not in cands[0]                                  # the original is not touched
    keep, cs = L.morning(cands, None, None)
    assert keep == [0, 1, 2, 3, 4] and all("fx" not in c for c in cs)
    assert L.morning([], 40, 45) == ([], [])


def test_morning_look_ahead_guard_reads_only_the_entry_row():
    a = [dict(er=40, later=1.0, xr=200), dict(er=60, later=2.0)]
    b = [dict(er=40, later=-99.0, xr=46), dict(er=60, later=1e9)]   # everything after the entry changed
    assert L.morning(a, 40, 45)[0] == L.morning(b, 40, 45)[0] == [0]


def _cand(arm, er, side="C", und="NIFTY", g=1, how="side2", book=None):
    return dict(arm=arm, und=und, side=side, er=er, g=g, j=0, how=how, strike=100.0, book=book, risk=0.0)


def test_closing_at_1000_frees_a_place_for_liquidity_under_the_cap():
    """Two Donchian calls open from 09:20; Liquidity's call at 11:00 is refused while they run to 14:00, but taken
    when the 10:00 close has sold them (the knock-on effect R114 measures)."""
    cs = [_cand("R72", 5, g=1), _cand("R51", 5, und="BANKNIFTY", g=2), _cand("LIQ", 105, und="FINNIFTY", g=3,
                                                                              how="book", book=15)]
    allday = [dict(er=6, xr=290, net=-500.0), dict(er=6, xr=290, net=-400.0), dict(er=106, xr=200, net=900.0)]
    closed = [dict(er=6, xr=45, net=-100.0), dict(er=6, xr=45, net=-50.0), dict(er=106, xr=200, net=900.0)]
    rule = B.make_rule("CAP2")
    r0 = B.trades_of({D0: B.walk_day(cs, allday, rule, day=D0)})
    r1 = B.trades_of({D0: B.walk_day(cs, closed, rule, day=D0)})
    assert sorted(t["arm"] for t in r0) == ["R51", "R72"]
    assert sorted(t["arm"] for t in r1) == ["LIQ", "R51", "R72"]
    assert L.extra(r1, r0) == dict(n=1, net=900) and L.extra(r0, r1) == dict(n=0, net=0)
    assert B.changed_vs(r1, r0) == 1


# ------------------------------------------------------------------------------------------------ statistics
def test_summary_adds_average_win_loss_and_green_days():
    days = [D0 + timedelta(days=i) for i in range(4)]
    tr = [_t(days[0], 1000), _t(days[0], -400), _t(days[1], -14000), _t(days[2], 300)]
    s = L.summary(tr, days)
    assert s["net"] == -13100 and s["trades"] == 4 and s["avg_win"] == 650 and s["avg_loss"] == -7200
    assert s["green_days"] == round(2 / 3, 3)                   # 3 traded days, 2 green; the empty day not counted
    assert s["days_below"] == 1 and s["worst_day"] == -14000 and s["months_below"] == 1


def test_summary_empty():
    s = L.summary([], [D0])
    assert s["net"] == 0 and s["trades"] == 0 and s["avg_win"] is None and s["green_days"] is None


# ------------------------------------------------------------------------------------------------ twins
def test_refusal_twin_same_count_is_book0_and_keeps_liquidity():
    days = [D0, D0 + timedelta(days=1)]
    b0 = [_t(days[0], 100), _t(days[0], 200, arm="R51"), _t(days[1], 50, arm="LIQ")]
    tw, k = L.refusal_twin_totals(b0, b0, days, 50, np.random.default_rng(1))
    assert k == 0 and np.allclose(tw, 350)


def test_refusal_twin_removes_the_shortfall_of_donchian_trades_only():
    days = [D0]
    b0 = [_t(D0, 100), _t(D0, 200, arm="R51"), _t(D0, 1000, arm="LIQ"), _t(D0, 7, arm="R52")]
    x = [_t(D0, 999)]                                            # X10 took one Donchian trade: remove one of two
    tw, k = L.refusal_twin_totals(b0, x, days, 2000, np.random.default_rng(2))
    assert k == 1 and set(np.unique(tw)) == {1107.0, 1207.0}
    x0 = [_t(D0, 5, arm="LIQ")]                                  # X10 took none: both removed, Liquidity and R52 stay
    tw, k = L.refusal_twin_totals(b0, x0, days, 10, np.random.default_rng(3))
    assert k == 2 and np.allclose(tw, 1007)


def test_exit_twin_and_p():
    rng = np.random.default_rng(4)
    tw = L.exit_twin_totals(100.0, [[1.0, 3.0], [10.0]], 5000, rng)
    assert set(np.unique(tw)) == {111.0, 113.0}
    assert np.allclose(L.exit_twin_totals(7.0, [], 3, rng), 7.0)
    assert np.allclose(L.exit_twin_totals(7.0, [[]], 3, rng), 7.0)  # no allowed minute: nothing drawn
    assert L.twin_p(10.0, np.array([1.0, 2.0, 3.0])) == 0.25
    assert L.twin_p(1.0, np.array([1.0, 2.0, 3.0])) == 1.0


# ------------------------------------------------------------------------------------------------ gates
def _s(net, mb=0, br=0, db=0):
    return dict(net=net, months_below=mb, dd_breaches=br, days_below=db)


def test_gates_pass_only_when_everything_holds():
    good = {p: (_s(10), _s(5)) for p in ("design", "wf", "final")}
    p_min = 1 / (K.TWINS + 1)
    g = L.decide(good, p_min, 40)
    assert g["passed"] and g["m"] == 5841
    assert not L.decide(good, 0.001, 40)["passed"]               # q = 5.8 > 0.10
    assert not L.decide(good, p_min, 29)["passed"]               # too few changed trades
    worse = dict(good, wf=(_s(4), _s(5)))
    assert any("wf" in f for f in L.decide(worse, p_min, 40)["fails"])
    risk = dict(good, final=(_s(10, db=3), _s(5, db=2)))
    assert any("days at or below" in f for f in L.decide(risk, p_min, 40)["fails"])
    tie = dict(good, design=(_s(5), _s(5)))                      # equal net is not "above"
    assert not L.decide(tie, p_min, 40)["passed"]
