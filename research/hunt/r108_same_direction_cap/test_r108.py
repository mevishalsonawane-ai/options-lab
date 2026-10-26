"""R108 tests: the app's cap as modelled (order, R72's kept place, would-be accounting, one option one arm), the 7 rules,
the look-ahead guard, twins, BH, gates and the account-rule maths. Synthetic data only (no lake)."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r108book as B  # noqa: E402
import r108cfg as K  # noqa: E402

D0 = date(2025, 6, 2)   # a Monday


def cand(arm, und, side, er, g, j=0, strike=100.0, book=None, risk=1000.0, how=None):
    how = how or {"LIQ": "book", "R72": "side2", "R51": "side2", "R52": "one"}[arm]
    return dict(arm=arm, und=und, side=side, er=er, g=g, j=j, how=how, strike=strike, book=book, risk=risk)


def res(er, xr, net=100.0):
    return dict(er=er, xr=xr, net=net, E=100.0, qty=50.0)


def outs(recs):
    return [(x["c"]["arm"], x["c"]["und"], x["c"]["er"], x["out"]) for x in recs]


def walk(cs, ps, rule="CAP2", **kw):
    return B.walk_day(cs, ps, B.make_rule(rule) if isinstance(rule, str) else rule, day=D0, **kw)


# ------------------------------------------------------------------------------------------------ basics
def test_direction_and_window():
    assert B.direction("C") == 1 and B.direction("P") == -1 and B.direction("ce") == 1
    assert not B.in_r72_window(4)          # 09:19
    assert B.in_r72_window(5) and B.in_r72_window(7) and not B.in_r72_window(8)   # 09:20-09:22, not 09:23
    assert B.in_r72_window(357) and not B.in_r72_window(358) and not B.in_r72_window(360)   # 15:12 yes, 15:13 no


def test_app_order_inside_a_minute():
    cs = [cand("R52", "NIFTY", "P", 5, 4), cand("R51", "NIFTY", "P", 5, 3), cand("R72", "SENSEX", "P", 5, 2),
          cand("R72", "NIFTY", "P", 5, 1), cand("LIQ", "MIDCPNIFTY", "P", 5, 5, book=5),
          cand("LIQ", "BANKNIFTY", "P", 5, 6, book=15)]
    order = sorted(cs, key=B.order_key)
    assert [(c["arm"], c["und"]) for c in order] == [("LIQ", "BANKNIFTY"), ("LIQ", "MIDCPNIFTY"), ("R72", "NIFTY"),
                                                      ("R72", "SENSEX"), ("R51", "NIFTY"), ("R52", "NIFTY")]


# ------------------------------------------------------------------------------------------------ the app's cap
def test_cap2_refuses_the_third_and_liquidity_first_takes_places():
    # 09:20: two Liquidity puts (different indices) then R72's put: R72's put is refused (cap full)
    cs = [cand("LIQ", "BANKNIFTY", "P", 30, 1, book=15, strike=1), cand("LIQ", "FINNIFTY", "P", 30, 2, book=30, strike=2),
          cand("R72", "NIFTY", "P", 30, 3, strike=3)]
    ps = [res(30, 60), res(30, 60), res(30, 50)]
    # row 30 = 09:45, inside R72's window: the 2nd Liquidity put needs the last place, kept for R72 -> refused
    o = outs(walk(cs, ps))
    assert o == [("LIQ", "BANKNIFTY", 30, B.REAL), ("LIQ", "FINNIFTY", 30, B.DROP), ("R72", "NIFTY", 30, B.REAL)]
    # outside R72's window (row 33 = 09:48) both Liquidity puts go and R72's is a would-be
    cs2 = [dict(c, er=33) for c in cs]
    o2 = outs(walk(cs2, [res(33, 60), res(33, 60), res(33, 50)]))
    assert o2 == [("LIQ", "BANKNIFTY", 33, B.REAL), ("LIQ", "FINNIFTY", 33, B.REAL), ("R72", "NIFTY", 33, B.WB_RULE)]


def test_kept_place_off_once_r72_holds_one_that_way():
    cs = [cand("R72", "NIFTY", "C", 5, 1, strike=1), cand("LIQ", "BANKNIFTY", "C", 10, 2, book=15, strike=2)]
    ps = [res(5, 40), res(10, 30)]
    assert outs(walk(cs, ps))[1][3] == B.REAL       # R72 holds a call: the second call place is free for Liquidity


def test_r51_after_r72_same_pass_kept_place_only_while_r72_has_a_due_break():
    # one call open (Liquidity, outside the window); at 09:25 R72 has a call break that is skipped by its own rule
    # (2 a side today already) and R51 then asks: the last place is kept for R72's due break -> R51 would-be
    base = [cand("LIQ", "BANKNIFTY", "C", 9, 1, book=15, strike=1)]
    r72 = [cand("R72", "NIFTY", "C", 10, 2, j=0, strike=2), cand("R72", "NIFTY", "C", 12, 2, j=1, strike=3)]
    cs = base + r72 + [cand("R72", "NIFTY", "C", 13, 2, j=2, strike=4), cand("R51", "SENSEX", "C", 13, 3, strike=5)]
    ps = [res(9, 100), res(10, 11), res(12, 12), res(13, 30), res(13, 30)]
    # R72's 3rd call (row 13) is skipped by its own rule (2 a side a day), so R72 holds nothing then; it was due at the
    # start of the pass -> R51 is refused for the kept place. Row 13 is not inside R72's window (09:28) -> R51 goes.
    o = outs(walk(cs, ps))
    assert o[-1] == ("R51", "SENSEX", 13, B.REAL)
    cs_w = base + r72 + [cand("R72", "NIFTY", "C", 15, 2, j=2, strike=4), cand("R51", "SENSEX", "C", 15, 3, strike=5)]
    ps_w = [res(9, 100), res(10, 11), res(12, 12), res(15, 30), res(15, 30)]
    o = outs(walk(cs_w, ps_w))
    assert o[3] == ("R72", "NIFTY", 15, B.OWN_SKIP) and o[-1] == ("R51", "SENSEX", 15, B.WB_RULE)
    # no R72 break due that way at 09:30 -> R51 takes the last place
    cs_n = base + [cand("R51", "SENSEX", "C", 15, 3, strike=5)]
    assert outs(walk(cs_n, [res(9, 100), res(15, 30)]))[-1][3] == B.REAL


def test_would_be_keeps_the_passed_arms_book_but_liquidity_book_stays_free():
    full = [cand("LIQ", "BANKNIFTY", "P", 8, 1, book=15, strike=1), cand("LIQ", "FINNIFTY", "P", 9, 2, book=30, strike=2)]
    fp = [res(8, 200), res(9, 200)]
    # R51 refused at row 11 (would-be to 40): its next put at row 21 is skipped by its own rule (book busy to 40)
    r51 = [cand("R51", "NIFTY", "P", 11, 3, j=0, strike=3), cand("R51", "NIFTY", "P", 21, 3, j=1, strike=4)]
    o = outs(walk(full + r51, fp + [res(11, 40), res(21, 50)]))
    assert o[2][3] == B.WB_RULE and o[3][3] == B.OWN_SKIP
    # R107's model frees the book: the later signal is taken... as another refusal here, but it is LOOKED at
    o107 = outs(walk(full + r51, fp + [res(11, 40), res(21, 50)], would_be=False))
    assert o107[2][3] == B.DROP and o107[3][3] == B.DROP
    # Liquidity refused at row 11 (outside R72's window) books nothing; its later candidate of the same book is taken
    # once a place is free (row 203 after the two puts closed at 200)
    liq = [cand("LIQ", "MIDCPNIFTY", "P", 11, 4, j=0, book=15, strike=5), cand("LIQ", "MIDCPNIFTY", "P", 203, 4, j=1, book=15, strike=6)]
    o = outs(walk(full + liq, fp + [res(11, 100), res(203, 220)]))
    assert o[2][3] == B.DROP and o[3][3] == B.REAL


def test_one_option_one_arm_is_a_would_be_and_never_counted():
    cs = [cand("R72", "NIFTY", "C", 5, 1, strike=100), cand("R51", "NIFTY", "C", 5, 2, strike=100),
          cand("R52", "NIFTY", "C", 5, 3, strike=100), cand("R51", "BANKNIFTY", "C", 5, 4, strike=7)]
    ps = [res(5, 30), res(5, 30), res(5, 30), res(5, 30)]
    def m(recs):
        return {(x["c"]["arm"], x["c"]["und"]): x["out"] for x in recs}
    assert m(walk(cs, ps)) == {("R72", "NIFTY"): B.REAL, ("R51", "NIFTY"): B.WB_HELD, ("R52", "NIFTY"): B.WB_HELD,
                               ("R51", "BANKNIFTY"): B.REAL}
    # without the rule (R107's model) R51 takes the same option and fills the cap
    assert m(walk(cs, ps, held_rule=False)) == {("R72", "NIFTY"): B.REAL, ("R51", "NIFTY"): B.REAL,
                                                ("R51", "BANKNIFTY"): B.WB_RULE, ("R52", "NIFTY"): B.WB_RULE}


def test_exit_minute_counts_as_open_and_the_next_minute_does_not():
    cs = [cand("LIQ", "BANKNIFTY", "C", 3, 1, book=15, strike=1), cand("LIQ", "FINNIFTY", "C", 3, 2, book=30, strike=2),
          cand("R51", "NIFTY", "C", 40, 3, strike=3), cand("R51", "SENSEX", "C", 41, 4, strike=4)]
    ps = [res(3, 40), res(3, 40), res(40, 60), res(41, 60)]
    o = outs(walk(cs, ps))
    assert o[2][3] == B.WB_RULE and o[3][3] == B.REAL


# ------------------------------------------------------------------------------------------------ the 7 rules
def test_cap3_and_index_caps():
    cs = [cand("R72", u, "P", 9, i + 1, strike=i) for i, u in enumerate(("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"))]
    ps = [res(9, 30)] * 4
    assert [x["out"] for x in walk(cs, ps, "CAP3")] == [B.REAL] * 3 + [B.WB_RULE]
    assert [x["out"] for x in walk(cs, ps, "IDX1")] == [B.REAL] * 4
    assert [x["out"] for x in walk(cs, ps, "IDX1C3")] == [B.REAL] * 3 + [B.WB_RULE]
    same = [cand("R72", "NIFTY", "P", 9, 1, strike=1), cand("R51", "NIFTY", "P", 9, 2, strike=2)]
    assert [x["out"] for x in walk(same, [res(9, 30)] * 2, "IDX1")] == [B.REAL, B.WB_RULE]
    # IDX1 keeps no place for R72: Liquidity at a bar close with nothing open on its index goes
    liq = [cand("LIQ", "BANKNIFTY", "P", 10, 1, book=15)]
    assert walk(liq, [res(10, 30)], "IDX1")[0]["out"] == B.REAL


def test_risk_cap_counts_rupees_a_way():
    cs = [cand("R72", "NIFTY", "P", 9, 1, strike=1, risk=3000.0), cand("R51", "BANKNIFTY", "P", 9, 2, strike=2, risk=3000.0),
          cand("R52", "FINNIFTY", "P", 9, 3, strike=3, risk=600.0), cand("R52", "SENSEX", "C", 9, 4, strike=4, risk=6000.0)]
    o = [x["out"] for x in walk(cs, [res(9, 30)] * 4, "RISK")]
    assert o == [B.REAL, B.REAL, B.WB_RULE, B.REAL]    # 3,000 + 3,000 + 600 > 6,500; the call side is apart


def test_exempt_leaves_r52_and_liquidity_outside():
    cs = [cand("R72", "NIFTY", "P", 9, 1, strike=1), cand("R51", "BANKNIFTY", "P", 9, 2, strike=2),
          cand("R52", "FINNIFTY", "P", 9, 3, strike=3), cand("R51", "SENSEX", "P", 9, 4, strike=4)]
    o = {(x["c"]["arm"], x["c"]["und"]): x["out"] for x in walk(cs, [res(9, 30)] * 4, "EXEMPT")}
    assert o == {("R72", "NIFTY"): B.REAL, ("R51", "BANKNIFTY"): B.REAL, ("R51", "SENSEX"): B.WB_RULE,
                 ("R52", "FINNIFTY"): B.REAL}


def test_stops_use_closed_trades_only_and_carry_the_week():
    cs = [cand("R72", "NIFTY", "P", 9, 1, strike=1), cand("R51", "BANKNIFTY", "C", 50, 2, strike=2),
          cand("R52", "FINNIFTY", "C", 80, 3, strike=3)]
    ps = [res(9, 60, -7000.0), res(50, 100, 500.0), res(80, 100, 500.0)]
    o = [x["out"] for x in walk(cs, ps, "STOPS")]
    # at row 50 the -7,000 trade is still open (exit 60): not counted; at row 80 it is closed -> day stop
    assert o == [B.REAL, B.REAL, B.WB_RULE]
    o = [x["out"] for x in B.walk_day(cs[1:2], ps[1:2], B.make_rule("STOPS"), {"week": -20_000.0, "month": 0.0}, D0)]
    assert o == [B.WB_RULE]
    # walk_period carries the week and resets it on Monday
    days = [D0 + timedelta(days=i) for i in range(7)]
    loss = {d: ([cand("R72", "NIFTY", "P", 9, 10 + i, strike=1)], [res(9, 20, -6000.0)]) for i, d in enumerate(days[:4])}
    w = B.walk_period(days, loss, B.make_rule("STOPS"))
    got = [w[d][0]["out"] for d in days[:4]]
    assert got == [B.REAL, B.REAL, B.REAL, B.WB_RULE]  # -6,000 a day: the month is at -18,000 before day 4
    loss2 = {d: ([cand("R72", "NIFTY", "P", 9, 10 + i, strike=1)], [res(9, 20, -7000.0)]) for i, d in enumerate(days)}
    w = B.walk_period(days, loss2, B.make_rule("STOPS"))
    assert [w[d][0]["out"] for d in days[:5]] == [B.REAL, B.REAL, B.WB_RULE, B.WB_RULE, B.WB_RULE]   # month -14,000 after day 2
    nxt = D0 + timedelta(days=7)
    w = B.walk_period([D0, nxt], {D0: loss2[D0], nxt: ([cand("R72", "NIFTY", "P", 9, 99, strike=1)], [res(9, 20, 1.0)])},
                      B.Stops(day=-1e9, week=-6000.0, month=-1e9))
    assert w[nxt][0]["out"] == B.REAL                  # a new week starts clean


def test_edge_orders_use_only_earlier_days():
    days = [D0, D0 + timedelta(days=1), D0 + timedelta(days=2)]
    rec = {days[0]: [("R51", 500.0), ("R52", -100.0)], days[1]: [("LIQ", 50.0)], days[2]: [("R72", 9999.0)]}
    eo = B.edge_orders(days, rec)
    assert eo[days[0]] == B.APP_ARM_ORDER                      # no history yet
    assert eo[days[1]] == ("R51", "R52", "LIQ", "R72")         # R51 500, R52 -100, then the rest in the app's order
    assert eo[days[2]] == ("R51", "LIQ", "R52", "R72")         # day 3's own trades are not seen
    cs = [cand("R72", "NIFTY", "P", 9, 1, strike=1), cand("R51", "BANKNIFTY", "P", 9, 2, strike=2),
          cand("R52", "FINNIFTY", "P", 9, 3, strike=3)]
    o = outs(B.walk_day(cs, [res(9, 30)] * 3, B.make_rule("EDGE"), day=D0, arm_order=("R52", "R51", "LIQ", "R72")))
    assert [x[0] for x in o] == ["R52", "R51", "R72"] and [x[3] for x in o] == [B.REAL, B.REAL, B.WB_RULE]


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("rule", ["CAP2", "CAP3", "IDX1", "IDX1C3", "RISK", "EDGE", "STOPS", "EXEMPT", "NOCAP"])
def test_look_ahead_guard_later_results_never_change_earlier_decisions(rule):
    rng = np.random.default_rng(1)
    arms = ["LIQ", "R72", "R51", "R52"]
    cs, ps = [], []
    for g in range(30):
        arm = arms[g % 4]
        er = int(rng.integers(5, 300))
        er = 5 if arm == "R52" else er
        cs.append(cand(arm, B.UNDS[g % 5], "CP"[g % 2], er, g, strike=float(g % 3), book=15 if arm == "LIQ" else None,
                       risk=float(rng.integers(500, 4000))))
        ps.append(res(er, er + int(rng.integers(1, 60)), float(rng.normal(0, 2000))))
    cut = 150
    a = walk(cs, ps, rule)
    ps2 = [dict(p, net=p["net"] * -3 + 7, xr=p["xr"] + 30) if c["er"] > cut else p for c, p in zip(cs, ps)]
    b = walk(cs, ps2, rule)
    early = lambda recs: [(x["c"]["g"], x["out"]) for x in recs if x["c"]["er"] <= cut]   # noqa: E731
    assert early(a) == early(b)


def test_look_ahead_guard_stops_never_see_an_open_trades_result():
    cs = [cand("R72", "NIFTY", "P", 9, 1, strike=1), cand("R51", "BANKNIFTY", "C", 50, 2, strike=2)]
    for net in (-1e6, 1e6):
        o = [x["out"] for x in walk(cs, [res(9, 60, net), res(50, 90)], "STOPS")]
        assert o == [B.REAL, B.REAL]


# ------------------------------------------------------------------------------------------------ maths
def test_summary_account_counts():
    sess = [date(2025, 1, 1) + timedelta(days=i) for i in range(70)]
    tr = [dict(day=sess[0], net=50_000.0), dict(day=sess[1], net=-45_000.0), dict(day=sess[40], net=-14_000.0),
          dict(day=sess[50], net=60_000.0), dict(day=sess[60], net=-41_000.0)]
    s = B.summary(tr, sess)
    assert s["net"] == 10_000 and s["max_dd"] == 59_000 and s["dd_breaches"] == 2 and s["days_below"] == 3
    assert s["worst_day"] == -45_000 and s["trades"] == 5 and s["win"] == 0.4
    assert s["months_below"] == 1     # Feb: -14,000; Jan +5,000; Mar +19,000


def test_twin_matrix_and_p():
    rng = np.random.default_rng(0)
    sess = [D0, D0 + timedelta(days=1), D0 + timedelta(days=2)]
    base = {sess[0]: [1.0, 2.0, 3.0], sess[1]: [10.0], sess[2]: []}
    T = B.twin_matrix(base, {sess[0]: 1, sess[1]: 1, sess[2]: 5}, sess, 3000, rng, chunk=700)
    assert set(np.unique(T[:, 0])) == {3.0, 4.0, 5.0} and (T[:, 1] == 0).all() and (T[:, 2] == 0).all()
    assert abs(T[:, 0].mean() - 4.0) < 0.1
    T0 = B.twin_matrix(base, {}, sess, 5, rng)
    assert (T0[:, 0] == 6).all() and (T0[:, 1] == 10).all()
    assert B.twin_p(5.0, np.array([1.0, 5.0, 6.0])) == 0.75
    r = B.matrix_r_dd(np.array([[1.0, -2.0, 3.0], [1.0, 1.0, 1.0]]))
    assert r[0] == pytest.approx(1.0) and np.isinf(r[1])


def test_bh_counts_the_earlier_tests():
    q = B.bh([0.00001, 0.5], 2 + K.N_PRIOR)
    assert q[0] == pytest.approx(min(1.0, 0.00001 * (2 + K.N_PRIOR)))
    assert K.N_PRIOR == 5777 and min(B.bh([1 / (K.TWINS + 1)], 7 + K.N_PRIOR)) < K.GATE_Q
    assert list(B.bh([0.01, 0.04, 0.03])) == pytest.approx([0.03, 0.04, 0.04])


def test_gates():
    cap2 = dict(net=100, months_below=2, dd_breaches=1, days_below=0)
    good = dict(net=200, months_below=2, dd_breaches=1, days_below=0)
    per = {p: (good, cap2) for p in K.PERIODS}
    assert B.gates(per, 0.05, 40)["passed"]
    assert not B.gates(per, 0.2, 40)["passed"]
    assert not B.gates(per, 0.05, 10)["passed"]
    worse = dict(good, months_below=3)
    g = B.gates(dict(per, wf=(worse, cap2)), 0.05, 40)
    assert not g["passed"] and g["fails"] == ["wf: more months at or below -Rs 13,000 than CAP2 (3 vs 2)"]
    assert not B.gates(dict(per, final=(dict(good, net=100), cap2)), 0.05, 40)["passed"]


def test_changed_vs_and_trades_of():
    cs = [cand("R72", "NIFTY", "P", 5, 1, strike=1), cand("R51", "BANKNIFTY", "P", 5, 2, strike=2),
          cand("R52", "FINNIFTY", "P", 5, 3, strike=3)]
    ps = [res(5, 30)] * 3
    a = B.trades_of({D0: walk(cs, ps, "CAP2")})
    b = B.trades_of({D0: walk(cs, ps, "CAP3")})
    assert len(a) == 2 and len(b) == 3 and B.changed_vs(a, b) == 1
    wb = B.trades_of({D0: walk(cs, ps, "CAP2")}, outs=(B.WB_RULE,))
    assert len(wb) == 1 and wb[0]["arm"] == "R52" and wb[0]["out"] == B.WB_RULE


def test_make_rule_names_and_config():
    for n in K.BASES[1:] + K.TESTS:
        assert B.make_rule(n).name == n
    assert len(K.TESTS) <= 8 and K.TWINS == 100_000 and K.PERIODS == ("design", "wf", "final")
    assert K.in_period(K.DESIGN_END, "design") and not K.in_period(K.FINAL_START, "design")
    assert K.in_period(K.FINAL_START, "final") and K.in_period(K.WF_START, "wf")


def test_candidates_risk_from_entry_and_quantity(monkeypatch):
    import r108study as S
    units = {a: [] for a in K.ARMS}
    units["R52"] = [(("NIFTY", D0, None), [dict(er=5, side="C", strike=100.0)], [dict(er=5, xr=9, net=1.0, E=200.0, qty=75.0)], "one")]
    units["LIQ"] = [(("BANKNIFTY", D0, 15), [dict(er=30, side="P", strike=5.0, book=15)], [None], "book")]
    dc = S.candidates(units)
    cs, ps = dc[D0]
    r52 = next(c for c in cs if c["arm"] == "R52")
    assert r52["risk"] == pytest.approx(200 * 75 * 0.30 + 60) and r52["how"] == "one"
    liq = next(c for c in cs if c["arm"] == "LIQ")
    assert liq["risk"] == 0.0 and liq["book"] == 15 and ps[cs.index(liq)] is None
