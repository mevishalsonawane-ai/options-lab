"""R112 tests: the 10:00 entry cut and the 10:00 close, the Supertrend filter, the +Rs 1,500 / -Rs 1,000 levels in the
engine (gaps, forced close), look-ahead guards (nothing after an entry or exit minute changes it), statistics, twins,
BH with the earlier families and the gates. Synthetic data only: no stores, no lake."""
from __future__ import annotations

from datetime import date

import numpy as np
import pytest

import r112cfg as K
import r112lib as L

D0 = date(2026, 1, 5)


def _t(day, net, er=10, xr=20, why="time"):
    return dict(day=day, net=float(net), er=er, xr=xr, why=why, und="NIFTY", side="C")


# ------------------------------------------------------------------------------------------------ settings
def test_rows_and_rules_are_what_prereg_says():
    assert K.row(9, 15) == 0 and K.CUT_A == 45 and K.CUT_B == 40 and K.CLOSE_B == 45
    assert set(K.RULES) == {"B0", "CUT10", "TS", "M10", "CUT10X", "M10X"}
    assert len(K.TESTS) * len(K.ARMS) == 10
    assert K.N_PRIOR == 5819 and K.TWINS == 100_000 and K.GATE_Q == 0.10
    assert K.RULES["M10"] == dict(cut=45, ts=True, close=None)
    assert K.RULES["M10X"] == dict(cut=40, ts=True, close=45)
    # every test with an entry cut has an all-day base with the same exits; every changed exit has an exit twin
    for r in K.TESTS:
        sp = K.RULES[r]
        if sp["cut"] is not None:
            base = K.RULES[K.ENTRY_BASE[r]]
            assert base["cut"] is None and base["ts"] == sp["ts"] and base["close"] is None
        assert (r in K.EXIT_TWIN) == (sp["ts"] or sp["close"] is not None)


def test_smallest_possible_q_can_pass():
    p_min = 1 / (K.TWINS + 1)
    assert p_min * (K.N_PRIOR + 10) <= K.GATE_Q


# ------------------------------------------------------------------------------------------------ entries
def test_cut_keeps_the_0955_bar_and_drops_1000():
    cands = [dict(er=e) for e in (5, 40, 45, 50, 355)]
    assert L.cut_keep(cands, K.CUT_A) == [0, 1, 2]          # 09:20, 09:55, 10:00 entries (bars to 09:55)
    assert L.cut_keep(cands, K.CUT_B) == [0, 1]
    assert L.cut_keep(cands, None) == [0, 1, 2, 3, 4]
    assert L.cut_keep([], K.CUT_A) == []


def test_cut_look_ahead_guard_reads_only_the_entry_row():
    a = [dict(er=40, later=1.0), dict(er=60, later=2.0)]
    b = [dict(er=40, later=-99.0), dict(er=60, later=1e9)]   # anything after the entry changed
    assert L.cut_keep(a, K.CUT_A) == L.cut_keep(b, K.CUT_A) == [0]


def test_with_close_adds_the_forced_row_without_touching_the_original():
    c = [dict(er=10, side="C")]
    out = L.with_close(c, 45)
    assert out[0]["fx"] == 45 and "fx" not in c[0]
    assert "fx" not in L.with_close(c, None)[0]


def test_supertrend_keep():
    d1 = date(2026, 1, 5)
    pos = {(d1, 0): 10, (d1, 1): 11, (d1, 2): 12}
    st = np.zeros(20, np.int64)
    st[10], st[11], st[12] = 1, -1, 0
    sig = {d1: [(5, "C"), (5, "P"), (10, "P"), (10, "C"), (15, "C"), (20, "C")]}
    # bar 0 up: call kept, put dropped; bar 1 down: put kept; bar 2 unknown (0): dropped; bar 3 missing: dropped
    assert L.supertrend_keep(sig, pos, st) == {d1: [(5, "C"), (10, "P")]}
    assert L.supertrend_keep({d1: [(15, "C")]}, pos, st) == {}
    assert L.supertrend_keep({}, pos, st) == {}


def test_supertrend_look_ahead_guard():
    P = pytest.importorskip("r73rules")
    rng = np.random.default_rng(5)
    c = 100 + np.cumsum(rng.normal(0, 1, 400))
    h, l = c + rng.uniform(0, 1, 400), c - rng.uniform(0, 1, 400)
    a = P.supertrend_dir(h, l, c, 10, 3.0)
    for cut in (30, 150, 399):
        h2, l2, c2 = h.copy(), l.copy(), c.copy()
        c2[cut + 1:] *= rng.uniform(0.5, 2.0)
        h2[cut + 1:], l2[cut + 1:] = c2[cut + 1:] + 5, c2[cut + 1:] - 5
        b = P.supertrend_dir(h2, l2, c2, 10, 3.0)
        assert (a[:cut + 1] == b[:cut + 1]).all()


# ------------------------------------------------------------------------------------------------ engine (synthetic)
def _flat(n=375, px=100.0):
    C = np.full((5, n), px, np.float64)
    C[4] = 1000.0
    return C


def _args(C, e=5, fx=-1, stop_abs=-1.0, tgt_abs=-1.0, stop_f=0.15, tmax=30):
    return (C, e, 355, fx, stop_f, -1.0, 0.0, 0.0, 0.0, np.zeros((0, 2)), tmax, -1.0, -1.0, 0.05, 65.0, 0.0, stop_abs,
            tgt_abs)


def test_engine_target_fills_at_its_level():
    X = pytest.importorskip("r106engine")
    C = _flat()
    C[1, 12] = 130.0                                            # the high touches 130 at row 12
    r = X.simulate(*_args(C, tgt_abs=125.0, stop_abs=90.0, stop_f=-1.0))
    assert r[6] == 3 and r[5] == 12 and r[3] == 125.0          # target, row 12, sold at the limit


def test_engine_gap_through_the_stop_fills_at_the_open():
    X = pytest.importorskip("r106engine")
    C = _flat()
    C[0, 15], C[1, 15], C[2, 15], C[3, 15] = 80.0, 81.0, 79.0, 80.0   # opens below the 90 stop
    r = X.simulate(*_args(C, tgt_abs=125.0, stop_abs=90.0, stop_f=-1.0))
    assert r[6] == 1 and r[5] == 15 and r[1] == 80.0           # stop at the open, worse than the level


def test_engine_forced_close_at_1000_sells_at_that_open():
    X = pytest.importorskip("r106engine")
    C = _flat()
    C[0, 45] = 104.0
    r = X.simulate(*_args(C, e=40, fx=K.CLOSE_B, tgt_abs=125.0, stop_abs=90.0, stop_f=-1.0))
    assert r[5] == 45 and r[1] == 104.0 and r[6] == 6          # the forced (index-exit) code, at the 10:00 open


def test_engine_look_ahead_guard_with_rupee_levels_and_close():
    X = pytest.importorskip("r106engine")
    rng = np.random.default_rng(12)
    for _ in range(100):
        c = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, 375)))
        o = np.r_[100.0, c[:-1]]
        C = np.vstack([o, np.maximum(o, c) * 1.005, np.minimum(o, c) * 0.995, c, np.full(375, 1000.0)])
        args = _args(C, e=20, fx=K.CLOSE_B, tgt_abs=112.0, stop_abs=92.0, stop_f=-1.0)
        a = X.simulate(*args)
        if a[5] < 0:
            continue
        C2 = C.copy()
        C2[:4, a[5] + 1:] *= rng.uniform(0.2, 5.0)
        b = X.simulate(*((C2,) + args[1:]))
        np.testing.assert_equal(np.array(a, float), np.array(b, float))


def test_levels_net_1500_and_minus_1000():
    X = pytest.importorskip("r106engine")

    def ch(a, b, qty=65.0):
        return 40.0 + 0.001 * b * qty + 0.0005 * (a + b) * qty + 7.2

    tgt = X.target_price(150.0, 65.0, K.TARGET, ch)
    assert (tgt - 150.0) * 65 - ch(150.0, tgt) >= 1500 > (tgt - 0.05 - 150.0) * 65 - ch(150.0, tgt - 0.05)
    s = X.stop_price(150.0, 65.0, K.STOP, ch, 1.0)
    sale = s * 0.999 - 2 * 0.05
    assert (sale - 150.0) * 65 - ch(150.0, sale) >= -1000 - 1e-6
    assert X.stop_price(10.0, 65.0, K.STOP, ch, 1.0) == -1.0  # premium worth less than Rs 1,000: no stop


# ------------------------------------------------------------------------------------------------ statistics
def test_stats_months_below_and_empty():
    sess = [date(2026, 1, 5), date(2026, 1, 6), date(2026, 2, 2), date(2026, 3, 2)]
    tr = [_t(sess[0], -8000), _t(sess[1], -5000), _t(sess[2], 2000)]
    s = L.stats(tr, sess)
    assert s["months"] == 3 and s["months_below"] == 1 and s["worst_month"] == -13000
    assert s["trades"] == 3 and s["net"] == -11000 and s["worst_day"] == -8000
    e = L.stats([], sess)
    assert e["trades"] == 0 and e["months_below"] == 0 and e["months"] == 3
    assert L.stats([], [])["months"] == 0


def test_break_even_theory():
    assert L.be_theory() == pytest.approx(0.4)
    assert L.be_theory(1000, 1000) == pytest.approx(0.5)


# ------------------------------------------------------------------------------------------------ twins
def test_entry_twin_same_count_is_the_base():
    d1, d2 = date(2026, 1, 5), date(2026, 1, 6)
    base = [_t(d1, 100), _t(d1, -50), _t(d2, 30)]
    tw = L.entry_twin_totals(base, base, [d1, d2], 50, np.random.default_rng(1))
    assert np.allclose(tw, 80)
    tw0 = L.entry_twin_totals([], base, [d1, d2], 50, np.random.default_rng(1))
    assert np.allclose(tw0, 0)
    tw1 = L.entry_twin_totals([_t(d1, 100)], base, [d1, d2], 2000, np.random.default_rng(1))
    assert set(np.round(tw1)) <= {100.0, -50.0} and 0.4 < (tw1 == 100).mean() < 0.6
    # a rule day with more trades than the base keeps the whole base that day
    more = L.entry_twin_totals([_t(d2, 1), _t(d2, 1)], base, [d1, d2], 10, np.random.default_rng(1))
    assert np.allclose(more, 30)


def test_exit_twin_and_p():
    rng = np.random.default_rng(2)
    assert np.allclose(L.exit_twin_totals(500.0, [], 10, rng), 500.0)
    assert np.allclose(L.exit_twin_totals(0.0, [[7.0]], 10, rng), 7.0)
    tw = L.exit_twin_totals(0.0, [[0.0, 10.0]], 4000, rng)
    assert 4.5 < tw.mean() < 5.5
    assert L.twin_p(100, np.zeros(9)) == pytest.approx(0.1)
    assert L.twin_p(0, np.zeros(9)) == pytest.approx(1.0)


def test_bh_with_prior_tests():
    q = L.bh([1e-5, 0.5], 2 + K.N_PRIOR)
    assert q[0] == pytest.approx(1e-5 * (2 + K.N_PRIOR))
    assert q[1] == 1.0


# ------------------------------------------------------------------------------------------------ gates
def _s(net, wd=-1000, dd=2000, n=100):
    return dict(net=net, worst_day=wd, max_dd=dd, trades=n)


def test_gates():
    good = {p: (_s(200), _s(100)) for p in ("design", "wf", "final")}
    assert L.gates(good, 0.05, 100)["passed"]
    g = L.gates(dict(good, wf=(_s(50), _s(100))), 0.05, 100)
    assert not g["passed"] and any("wf" in f for f in g["fails"])
    g = L.gates(dict(good, design=(_s(-5), _s(-100))), 0.05, 100)
    assert not g["passed"] and any("positive" in f for f in g["fails"])
    assert not L.gates(good, 0.2, 100)["passed"]
    assert not L.gates(good, 0.05, 39)["passed"]
    # equal net with clearly smaller risk counts as better (R106 gate 2)
    eq = {p: (_s(98, wd=-500, dd=1000), _s(100)) for p in ("design", "wf", "final")}
    assert L.gates(eq, 0.05, 100)["passed"]
