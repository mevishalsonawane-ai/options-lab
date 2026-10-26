"""R106 tests: the walks against R101's taking rules, re-entries and the day goal, the rupee target / stop solvers, the
engine against R64's, look-ahead guards (rupee levels and exits read nothing after their minute), the twins' rows,
statistics, BH with the earlier families and the gates. Synthetic data only: no stores, no lake."""
from __future__ import annotations

from datetime import date

import numpy as np
import pytest

import r106cfg as K
import r106study as S
import r106walk as W

D0 = date(2026, 1, 5)


# ------------------------------------------------------------------------------------------------ helpers
def _cand(er, side="C", book=None, **kw):
    c = dict(er=er, side=side, strike=100.0, und=kw.pop("und", "NIFTY"))
    if book is not None:
        c["book"] = book
    c.update(kw)
    return c


def _res(er, xr, net, why="time", und="NIFTY"):
    return dict(und=und, day=D0, er=er, xr=xr, net=float(net), why=why, side="C", strike=100.0)


def _r101_walks():
    import r101study as R
    return R.walk_book, R.walk_side2, R.walk_one


# ------------------------------------------------------------------------------------------------ walks = R101's
@pytest.mark.parametrize("how", ["book", "side2", "one"])
def test_walk_day_without_rules_is_r101_walk(how):
    walk_book, walk_side2, walk_one = _r101_walks()
    ref = {"book": walk_book, "side2": walk_side2, "one": walk_one}[how]
    rng = np.random.default_rng(7)
    for _ in range(200):
        n = int(rng.integers(0, 9))
        ers = sorted(int(x) for x in rng.integers(5, 300, size=n))
        cands = [_cand(e, side=str(rng.choice(["C", "P"])), book=(15 if how == "book" else None)) for e in ers]
        res = [None if rng.random() < 0.15 else _res(e + 1, e + int(rng.integers(1, 60)), rng.normal(0, 500)) for e in ers]
        a = ref(cands, res)
        b = W.walk_day([(("NIFTY", D0, None), cands, how)], [res])
        assert [(t["er"], t["xr"], t["net"]) for t in a] == [(t["er"], t["xr"], t["net"]) for t in b]


def test_walk_day_units_are_independent_without_a_goal():
    c1 = [_cand(10), _cand(20)]
    c2 = [_cand(12, und="BANKNIFTY"), _cand(15, und="BANKNIFTY")]
    r1 = [_res(11, 30, 100), _res(21, 40, 200)]
    r2 = [_res(13, 14, -50, und="BANKNIFTY"), _res(16, 50, 70, und="BANKNIFTY")]
    out = W.walk_day([(("NIFTY", D0, None), c1, "side2"), (("BANKNIFTY", D0, None), c2, "side2")], [r1, r2])
    assert sorted(t["net"] for t in out) == [-50, 70, 100]       # NIFTY's 2nd signal is inside its open trade


def test_walk_day_rejects_unsorted_candidates():
    with pytest.raises(AssertionError):
        W.walk_day([(("NIFTY", D0, None), [_cand(20), _cand(10)], "side2")], [[None, None]])


# ------------------------------------------------------------------------------------------------ re-entries
def _re_factory(calls, last_row=300):
    def make_re(unit, c, r):
        calls.append(r["xr"])
        if r["xr"] + 1 > last_row:
            return None
        nc = dict(c)
        nc["er"] = r["xr"] + 1
        return nc

    def price_re(unit, nc):
        return _res(nc["er"], nc["er"] + 10, 1000, why="target")       # every re-entry hits its target again
    return make_re, price_re


def test_reentry_only_after_a_target_and_capped_at_five_an_index():
    calls = []
    mk, pr = _re_factory(calls)
    cands = [_cand(10)]
    res = [_res(11, 20, 1000, why="target")]
    out = W.walk_day([(("NIFTY", D0, None), cands, "side2")], [res], re=True, make_re=mk, price_re=pr, max_fills=5)
    assert len(out) == 5 and [t["re"] for t in out] == [0, 1, 2, 3, 4]
    out2 = W.walk_day([(("NIFTY", D0, None), cands, "side2")], [[_res(11, 20, -300, why="stop")]], re=True,
                      make_re=mk, price_re=pr)
    assert len(out2) == 1                                          # no re-entry after a stop


def test_reentry_does_not_use_up_fresh_fills_but_does_block_the_book():
    calls = []
    mk, pr = _re_factory(calls)
    # fresh at 10 (target at 20) -> re-entry 21..31 (target) -> re 32..42 ...; fresh at 25 is inside the re-entry
    cands = [_cand(10), _cand(25), _cand(200), _cand(250)]
    res = [_res(11, 20, 1000, why="target"), _res(26, 30, 5), _res(201, 210, 7), _res(251, 260, 9)]
    out = W.walk_day([(("NIFTY", D0, None), cands, "side2")], [res], re=True, make_re=mk, price_re=pr, max_fills=5)
    fresh = [t for t in out if t["re"] == 0]
    assert [t["er_sig"] for t in fresh] == [10]                   # the cap of 5 an index is reached by re-entries
    def pr2(unit, nc):                                            # re-entries hit the target until row 40, then time out
        why = "target" if nc["er"] < 40 else "time"
        return _res(nc["er"], nc["er"] + 10, 1000 if why == "target" else -20, why=why)
    out_cap = W.walk_day([(("NIFTY", D0, None), cands, "side2")], [res], re=True, make_re=mk, price_re=pr2, max_fills=99)
    fresh = [t["er_sig"] for t in out_cap if t["re"] == 0]
    assert [t["re"] for t in out_cap].count(0) == 2 and len(out_cap) == 5
    assert 25 not in fresh and 200 in fresh                       # 25 is inside a re-entry (the book is busy)
    assert 250 not in fresh                                       # 2 fresh fills a side: re-entries did not use them


def test_reentry_in_hours_only():
    calls = []
    mk, pr = _re_factory(calls, last_row=25)
    out = W.walk_day([(("NIFTY", D0, None), [_cand(10)], "one")], [[_res(11, 20, 1000, why="target")]], re=True,
                     make_re=mk, price_re=pr)
    assert len(out) == 2                                          # 21 allowed, 32 > 25 refused


def test_re_allowed_rules():
    assert S.re_allowed("R51", 100, -1, 105.0, 100.0, "C")
    assert not S.re_allowed("R51", 100, -1, 99.0, 100.0, "C")      # the index is back through the signal close
    assert S.re_allowed("R51", 100, -1, 95.0, 100.0, "P")
    assert not S.re_allowed("R51", 100, -1, float("nan"), 100.0, "C")
    assert not S.re_allowed("R51", K.LAST_ENTRY["R51"] + 1, -1, 105.0, 100.0, "C")
    assert S.re_allowed("LIQ", 100, -1, float("nan"), float("nan"), "C")     # no index exit pending
    assert S.re_allowed("LIQ", 100, 150, float("nan"), float("nan"), "C")
    assert not S.re_allowed("LIQ", 100, 100, 0, 0, "C")             # the index exit fires at the re-entry row
    assert not S.re_allowed("LIQ", K.LAST_ENTRY["LIQ"] + 1, -1, 0, 0, "C")


# ------------------------------------------------------------------------------------------------ day goal
def test_day_goal_stops_at_five_trades():
    units = [(("NIFTY", D0, None), [_cand(10 + 20 * i) for i in range(8)], "book")]
    res = [[_res(11 + 20 * i, 15 + 20 * i, -100) for i in range(8)]]
    for c in units[0][1]:
        c["book"] = 5
    out = W.walk_day(units, res, goal=5000, day_max=5)
    assert len(out) == 5


def test_day_goal_counts_only_trades_closed_before_the_entry():
    # trade A closes at 100 with +6,000; B enters at 50 (A still open: allowed), C enters at 150 (refused)
    ua = (("NIFTY", D0, None), [_cand(10)], "one")
    ub = (("BANKNIFTY", D0, None), [_cand(50, und="BANKNIFTY")], "one")
    uc = (("FINNIFTY", D0, None), [_cand(150, und="FINNIFTY")], "one")
    res = [[_res(11, 100, 6000)], [_res(51, 60, 10, und="BANKNIFTY")], [_res(151, 160, 10, und="FINNIFTY")]]
    out = W.walk_day([ua, ub, uc], res, goal=5000, day_max=5)
    assert sorted(t["und"] for t in out) == ["BANKNIFTY", "NIFTY"]
    # look-ahead guard: a trade that has not closed yet never stops the day, however big it ends
    res2 = [[_res(11, 200, 60000)], [_res(51, 60, 10, und="BANKNIFTY")], [_res(151, 160, 10, und="FINNIFTY")]]
    assert len(W.walk_day([ua, ub, uc], res2, goal=5000, day_max=5)) == 3


# ------------------------------------------------------------------------------------------------ rupee levels
def _charges(entry, exit_, qty=65):
    """A stand-in for dated charges: Rs 20 + Rs 20 brokerage, 0.1% STT on the sale, 0.05% each side, GST on Rs 40."""
    return 40.0 + 0.001 * exit_ * qty + 0.0005 * (entry + exit_) * qty + 0.18 * 40.0


def test_target_price_nets_at_least_T_and_one_tick_less_does_not():
    X = pytest.importorskip("r106engine")
    for re_, q, T in ((150.0, 65, 1000.0), (42.35, 120, 500.0), (310.0, 30, 1500.0)):
        ch = lambda a, b: _charges(a, b, q)  # noqa: E731
        x = X.target_price(re_, q, T, ch)
        assert (x - re_) * q - ch(re_, x) >= T - 1e-6
        assert (x - 0.05 - re_) * q - ch(re_, x - 0.05) < T
        assert abs(round(x / 0.05) - x / 0.05) < 1e-6              # on the tick grid


def test_stop_price_nets_about_minus_T_and_none_for_tiny_premiums():
    X = pytest.importorskip("r106engine")
    re_, q, T, ticks = 150.0, 65, 1000.0, 2.0
    ch = lambda a, b: _charges(a, b, q)  # noqa: E731
    s = X.stop_price(re_, q, T, ch, ticks)
    rx = round(s * 0.999, 2) - 2 * ticks * 0.05
    net = (rx - re_) * q - ch(re_, rx)
    assert -T - 1e-6 <= net <= -T + 0.05 * q + 1                   # at most T lost; within a tick of it
    assert X.stop_price(5.0, 65, 1000.0, lambda a, b: _charges(a, b, 65), ticks) == -1.0   # premium < T: no stop


# ------------------------------------------------------------------------------------------------ the engine
def _contract(rng, n=375, start=100.0):
    c = start * np.exp(np.cumsum(rng.normal(0, 0.02, n)))
    o = np.r_[start, c[:-1]]
    h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, 0.01, n)))
    l = np.minimum(o, c) * (1 - np.abs(rng.normal(0, 0.01, n)))
    v = rng.integers(0, 5000, n).astype(float)
    C = np.vstack([o, h, l, c, v]).astype(np.float64)
    C[:, rng.random(n) < 0.03] = np.nan                            # missing minutes
    return C


def test_engine_without_rupee_levels_is_r64():
    X = pytest.importorskip("r106engine")
    import r64engine as G
    rng = np.random.default_rng(3)
    steps = np.array([[0.30, np.nan], [0.60, 0.30], [1.00, 0.60], [0.25, 0.05]])
    for i in range(300):
        C = _contract(rng)
        e = int(rng.integers(5, 300)); fx = int(rng.integers(-1, 370)) if i % 3 == 0 else -1
        stop = float(rng.choice([-1.0, 0.15, 0.30])); tgt = float(rng.choice([-1.0, 0.30]))
        tmax = int(rng.choice([-1, 20, 30, 60])); need = float(rng.choice([-1.0, 0.05]))
        a = G.simulate(C, e, 355, fx, stop, tgt, -1.0, 0.0, 0.0, steps, tmax, need, -1.0, 0.05, 65.0, 101.0)
        b = X.simulate(C, e, 355, fx, stop, tgt, -1.0, 0.0, 0.0, steps, tmax, need, -1.0, 0.05, 65.0, 101.0, -1.0, -1.0)
        np.testing.assert_equal(np.array(a, float), np.array(b, float))


def test_engine_rupee_target_fills_at_its_limit_and_gap_stop_at_the_open():
    X = pytest.importorskip("r106engine")
    n = 375
    C = np.full((5, n), np.nan)
    C[:, :] = np.array([100.0, 100.5, 99.5, 100.0, 1000.0])[:, None]
    C[1, 30] = 112.0                                               # the high touches 110 at row 30
    r = X.simulate(C, 10, 355, -1, 0.15, -1.0, -1.0, 0.0, 0.0, np.zeros((0, 2)), -1, -1.0, -1.0, 0.05, 65.0, 0.0,
                   -1.0, 110.0)
    assert r[6] == 3 and r[1] == 110.0 and r[3] == 110.0 and r[5] == 30
    C2 = C.copy(); C2[1, 30] = 100.5
    C2[0, 40], C2[2, 40] = 90.0, 89.0                              # gaps through a 95 rupee stop
    r2 = X.simulate(C2, 10, 355, -1, -1.0, -1.0, -1.0, 0.0, 0.0, np.zeros((0, 2)), -1, -1.0, -1.0, 0.05, 65.0, 0.0,
                    95.0, 110.0)
    assert r2[6] == 1 and r2[1] == 90.0 and r2[5] == 40            # filled at the open, below the stop


def test_engine_look_ahead_guard_nothing_after_the_exit_minute_matters():
    X = pytest.importorskip("r106engine")
    rng = np.random.default_rng(11)
    for _ in range(100):
        C = _contract(rng)
        args = (10, 355, -1, -1.0, -1.0, -1.0, 0.0, 0.0, np.zeros((0, 2)), 60, -1.0, -1.0, 0.05, 65.0, 0.0, 95.0, 106.0)
        a = X.simulate(C, *args)
        if a[5] < 0:
            continue
        C2 = C.copy()
        C2[:4, a[5] + 1:] *= rng.uniform(0.2, 5.0)                 # rewrite every minute after the exit
        b = X.simulate(C2, *args)
        np.testing.assert_equal(np.array(a, float), np.array(b, float))


# ------------------------------------------------------------------------------------------------ twins
def test_twin_rows_strictly_inside_fill_and_plain_exit():
    rng = np.random.default_rng(1)
    r = S.twin_rows(20, 25, 5000, rng)
    assert r.min() == 21 and r.max() == 24


def test_twin_p_counts_ties_against_the_rule():
    assert S.twin_p(10.0, np.array([10.0, 9.0, 11.0, 0.0])) == pytest.approx(3 / 5)
    assert S.twin_p(100.0, np.zeros(500)) == pytest.approx(1 / 501)


# ------------------------------------------------------------------------------------------------ statistics
def _tr(day, net, why="target"):
    return dict(day=day, net=float(net), why=why)


def test_stats_days_breakeven_and_drawdown():
    d1, d2, d3 = date(2026, 1, 5), date(2026, 1, 6), date(2026, 1, 7)
    tr = [_tr(d1, 1000), _tr(d1, -500, "stop"), _tr(d2, -1500, "stop"), _tr(d3, 1000), _tr(d3, 1000)]
    s = S.stats(tr, sessions=4)
    assert s["trades"] == 5 and s["net"] == 1000 and s["win"] == 0.6
    assert s["days"] == 3 and s["green_days"] == pytest.approx(2 / 3, abs=1e-4) and s["worst_day"] == -1500
    assert s["max_dd"] == 1500 and s["per_session"] == 250
    assert s["avg_win"] == 1000 and s["avg_loss"] == 1000 and s["be_win"] == 0.5
    assert s["exits"] == {"target": 3, "stop": 2} and s["largest_loss"] == -1500
    assert S.stats([], sessions=3)["trades"] == 0


def test_be_theory():
    assert S.be_theory(1000, 1000) == 0.5
    assert S.be_theory(1000, 3000) == 0.75
    assert np.isnan(S.be_theory(0, 0))


def test_day_goal_profile_counts_empty_sessions_as_zero():
    d = [date(2026, 1, 5 + i) for i in range(4)]
    p = S.day_goal_profile([_tr(d[0], 6000), _tr(d[1], -2000)], d)
    assert p["sessions"] == 4 and p["traded"] == 2 and p["median_day"] == 0
    assert p["reach_goal"] == 0.25 and p["green"] == 0.25 and p["red"] == 0.25 and p["worst_day"] == -2000
    assert S.day_goal_profile([], [])["sessions"] == 0


# ------------------------------------------------------------------------------------------------ BH and gates
def test_bh_with_earlier_families_is_stricter():
    p = [0.001, 0.01, 0.5]
    q_own = S.bh(p)
    q_all = S.bh(p, 3 + K.N_PRIOR)
    assert q_own[0] == pytest.approx(0.003)
    assert q_all[0] == pytest.approx(min(1.0, 0.001 * (3 + K.N_PRIOR)))
    assert all(a >= b for a, b in zip(q_all, q_own)) and max(q_all) <= 1.0
    assert K.N_PRIOR == 513


def _s(net, worst=-1000, dd=2000, trades=100):
    return dict(net=net, worst_day=worst, max_dd=dd, trades=trades)


def test_better_than_b0_net_or_equal_net_with_smaller_risk():
    b = _s(10000, -5000, 20000)
    assert S.better_than_b0(_s(10001), b)
    assert S.better_than_b0(_s(9600, -3900, 15000), b)            # within 5%, both risks >= 20% smaller
    assert not S.better_than_b0(_s(9600, -4500, 15000), b)        # worst day only 10% smaller
    assert not S.better_than_b0(_s(9000, -1000, 1000), b)         # 10% less net


def test_gates_need_every_condition():
    d = dict(all=_s(5000), wf=_s(3000), b0_all=_s(4000), b0_wf=_s(2000))
    f = dict(all=_s(2000), b0_all=_s(1000))
    assert S.gates(d, f, 0.05)["passed"]
    assert not S.gates(d, f, 0.2)["passed"]
    assert not S.gates(dict(d, wf=_s(-1)), f, 0.05)["passed"]
    assert not S.gates(d, dict(f, all=_s(500)), 0.05)["passed"]
    assert not S.gates(d, dict(f, all=_s(2000, trades=39)), 0.05)["passed"]


def test_family_is_small_and_matches_prereg():
    assert len(K.RULES) == 9 <= 15
    assert {r["target"] for r in K.RULES.values()} == {500.0, 1000.0, 1500.0}
    assert K.DAY_GOAL == 5000 and K.DAY_MAX_TRADES == 5 and K.MAX_FILLS_PER_INDEX_DAY == 5
    assert K.period_of(date(2026, 3, 31)) == "design" and K.period_of(date(2026, 4, 1)) == "final"
    assert K.period_of(date(2026, 10, 14)) == "post" and K.period_of(date(2026, 10, 20)) is None
