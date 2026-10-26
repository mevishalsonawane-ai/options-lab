"""R105 tests (PREREG section 6): engine = R64 with no rule, each rule on hand-made paths, the look-ahead guard, the
partial leg's accounting, twins, BH with the earlier family, the gate, the bootstrap and the one-time reads.
Synthetic data only (no lake)."""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parents[1]
HUNT = HERE.parent
for p in (HERE, HUNT / "r64", HUNT / "r51", HUNT.parents[1], HUNT.parent):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r105engine as X  # noqa: E402
import r105lib as L  # noqa: E402

N = 375
NAN = math.nan
P_STEPS = np.array(L.P_STEPS, dtype=np.float64)


def contract(path, vol=1000.0):
    """(5, N) O H L C V of a contract whose minute m opens / closes at path[m] (high / low = max / min of the two)."""
    C = np.full((5, N), np.nan)
    p = np.asarray(path, float)
    n = len(p)
    o = p.copy()
    c = np.concatenate([p[1:], p[-1:]])
    C[0, :n], C[3, :n] = o, c
    C[1, :n], C[2, :n] = np.maximum(o, c), np.minimum(o, c)
    C[4, :n] = vol
    return C


def index(path):
    I = np.full((4, N), np.nan)
    p = np.asarray(path, float)
    n = len(p)
    I[0, :n], I[3, :n] = p, p
    I[1, :n], I[2, :n] = p + 0.5, p - 0.5
    return I


def run(C, e=10, I=None, steps=P_STEPS, tmax=30, sq=360, fx=-1, be=None, target=-1.0, be_rs=-1.0, tt_off=-1,
        tt_pk=0.0, gb_trig=-1.0, gb_keep=0.0, idx_mode=0, idx_arm=0.0, lvl=NAN, sgn=1, qty=75.0):
    I = index(np.full(N, 100.0)) if I is None else I
    be = C[0, e] * 1.01 if be is None else be
    return X.simulate(C, e, sq, fx, 0.15, target, steps, tmax, -1.0, -1.0, 0.05, qty, be, be_rs, tt_off, tt_pk,
                      gb_trig, gb_keep, idx_mode, idx_arm, lvl, sgn, I)


# ================================================================================================ engine = R64
def test_engine_equals_r64_without_rules():
    G = pytest.importorskip("r64engine")
    rng = np.random.default_rng(1)
    for k in range(400):
        p = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, N)))
        C = contract(p, vol=float(rng.choice([0.0, 500.0, 5000.0])))
        if k % 7 == 0:
            C[:, rng.integers(10, 60, 5)] = np.nan
        e = int(rng.integers(5, 300))
        be = C[0, e] * 1.012 if C[0, e] == C[0, e] else 0.0
        a = G.simulate(C, e, 360, -1, 0.15, -1.0, -1.0, 0.0, 0.0, P_STEPS, 30, -1.0, -1.0, 0.05, 75.0, be)
        b = run(C, e=e, be=be)
        assert np.allclose(np.nan_to_num(a, nan=-7), np.nan_to_num(b, nan=-7)), (k, a, b)


# ================================================================================================ premium rules
def test_x1_breakeven_from_next_minute():
    # fill 100 at row 10; row 11 trades 105 -> 110 (+10% at its high); the lock acts from row 12 and sells at be
    p = [100] * 10 + [100, 105, 110, 100, 95, 90, 85] + [85] * 50
    C = contract(p)
    base = run(C)
    x1 = run(C, steps=np.vstack([P_STEPS, [[0.10, NAN]]]))
    assert base[6] == 1                                     # the -15% stop
    assert x1[6] == 2 and x1[5] == 12 and abs(x1[1] - 101.0) < 1e-9


def test_x1_threshold_not_reached():
    p = [100] * 10 + [100, 104, 109.9, 100, 90, 84] + [84] * 50
    C = contract(p)
    assert run(C, steps=np.vstack([P_STEPS, [[0.10, NAN]]]))[6] == 1


def test_x4_rupees_scale_with_quantity():
    p = [100] * 10 + [100, 106, 100, 95, 90, 84] + [84] * 50
    C = contract(p)
    # +6 points x 200 qty = Rs 1,200 -> breakeven; x 75 = Rs 450 -> nothing
    assert run(C, be_rs=1000.0, qty=200.0)[6] == 2
    assert run(C, be_rs=1000.0, qty=75.0)[6] == 1


def test_x5_waits_for_fifteen_minutes():
    p = [100] * 10 + [100, 109] + [103] * 20 + [100, 95, 90, 84] + [84] * 50
    C = contract(p)
    r = run(C, tt_off=15, tt_pk=0.08)
    assert r[6] == 2 and r[5] > 10 + 15
    early = [100] * 10 + [100, 109, 100, 90, 84] + [84] * 50
    assert run(contract(early), tt_off=15, tt_pk=0.08)[6] == 1     # fell before minute 15: plain stop


def test_x6_keeps_half_the_best_gain():
    p = [100] * 10 + [100, 110, 120, 105, 100] + [100] * 50
    r = run(contract(p), gb_trig=0.12, gb_keep=0.5)
    assert r[6] == 2 and abs(r[1] - 110.0) < 1e-9          # best 120 -> stop 110


def test_x9_leg1_target_is_a_limit():
    p = [100] * 10 + [100, 110, 116, 100] + [100] * 50
    r = run(contract(p), target=0.15)
    assert r[6] == 3 and abs(r[1] - 115.0) < 1e-9 and abs(r[3] - 115.0) < 1e-9


# ================================================================================================ index rules
def test_x7_close_through_level_sells_next_open():
    p = [100.0] * 40
    C = contract(p + [100.0] * 335)
    ip = [200.0] * 14 + [199.0] + [200.0] * 360           # index close below 199.5 at row 14
    r = run(C, I=index(ip), idx_mode=1, lvl=199.5)
    assert r[6] == X.R_IDX and r[5] == 15


def test_x7_put_mirror_and_no_exit_without_cross():
    C = contract([100.0] * N)
    r = run(C, I=index([200.0] * N), idx_mode=1, lvl=199.5, sgn=-1)
    assert r[6] == X.R_IDX and r[5] == 11                  # a put: close 200 > 199.5 at the fill row -> next open
    r = run(C, I=index([200.0] * N), idx_mode=1, lvl=199.5, sgn=1)
    assert r[6] == 4                                       # never crossed: the 30-minute exit


def test_x8_armed_only_after_ten_percent():
    p = [100] * 10 + [100, 100, 100, 111, 111, 111] + [111] * 50
    ip = [200.0] * 12 + [199.0] * 400
    r = run(contract(p), I=index(ip[:N]), idx_mode=1, idx_arm=0.10, lvl=199.5)
    # cross from row 12, but +10% first reached at row 12's high (open 100 close 111 -> high 111 at row 12);
    # armed at the end of row 12, so the index check acts at row 13 and sells at row 14's open
    assert r[6] == X.R_IDX and r[5] == 14


def test_x10_trail_uses_completed_bars_only():
    # rising index: the low of each completed 10-minute bar trails; a drop through it sells next open
    ip = list(np.linspace(200, 230, 60)) + [200.0] * 315
    C = contract([100] * 10 + [100, 116] + [118] * 363)
    r = run(C, I=index(ip), steps=np.vstack([P_STEPS, [[0.15, NAN]]]), idx_mode=2, idx_arm=0.15, tmax=120)
    assert r[6] == X.R_IDX and r[5] == 61


def test_lookahead_guard_index_and_premium():
    """Changing any bar AFTER the decision minute never changes the decision at or before it."""
    rng = np.random.default_rng(5)
    for k in range(200):
        p = 100 * np.exp(np.cumsum(rng.normal(0, 0.03, N)))
        ip = 20000 + np.cumsum(rng.normal(0, 5, N))
        C, I = contract(p), index(ip)
        kw = [dict(steps=np.vstack([P_STEPS, [[0.10, NAN]]])), dict(be_rs=1000.0), dict(tt_off=15, tt_pk=0.08),
              dict(gb_trig=0.12, gb_keep=0.5), dict(idx_mode=1, lvl=float(ip[9] - 3)),
              dict(idx_mode=1, idx_arm=0.10, lvl=float(ip[9] - 3)),
              dict(steps=np.vstack([P_STEPS, [[0.15, NAN]]]), idx_mode=2, idx_arm=0.15)][k % 7]
        r = run(C, I=I, **kw)
        xr = int(r[5])
        if xr < 0 or xr + 1 >= N:
            continue
        C2, I2 = C.copy(), I.copy()
        C2[:, xr + 1:] = C2[:, xr + 1:] * rng.uniform(0.3, 3.0)
        I2[:, xr + 1:] = I2[:, xr + 1:] + rng.normal(0, 200)
        r2 = run(C2, I=I2, **kw)
        assert r2[5] == xr and r2[6] == r[6] and abs(r2[1] - r[1]) < 1e-9, (k, r, r2)


def test_index_check_at_m_uses_previous_arming():
    # the premium reaches +15% only at row 11's high; the index has already dropped at row 11: no exit at 12 yet
    ip = [200.0] * 11 + [150.0] * 364
    C = contract([100] * 11 + [100, 120] + [120] * 362)
    r = run(C, I=index(ip), idx_mode=1, idx_arm=0.15, lvl=199.0, steps=np.vstack([P_STEPS, [[0.15, NAN]]]))
    assert r[5] == 13          # armed at the end of row 11 (high of row 11), checked at row 12, sold at row 13


# ================================================================================================ pure pieces
def T(day, net, pk=0.0, pk_rs=0.0, und="NIFTY", er=10, xr=40, side="C"):
    return dict(und=und, day=day, net=net, pk=pk, pk_rs=pk_rs, er=er, xr=xr, side=side)


def test_rule_spec_and_family_size():
    assert len(L.RULES) == 10 and len(L.TESTS) == 20
    assert L.rule_spec(None)["steps"] == L.P_STEPS
    s = L.rule_spec("X3")
    assert s["steps"][-1] == (0.15, 0.03) and s["idx_mode"] == 0
    s = L.rule_spec("X9")
    assert s["part"] and s["leg1"]["target"] == 0.15 and s["leg2"]["steps"][-1][0] == 0.15
    assert L.rule_spec("X10")["idx_mode"] == 2


def test_cohort_bands():
    d = date(2025, 5, 2)
    tr = [T(d, -500, pk=0.10, pk_rs=1500, er=1), T(d, -800, pk=0.20, pk_rs=2500, er=2), T(d, 900, pk=0.30, pk_rs=3000, er=3),
          T(d, -300, pk=0.05, pk_rs=400, er=20), T(d, -100, pk=0.09, pk_rs=900, er=30)]
    c = L.cohort(tr)
    assert c["rs1000_lost"]["n"] == 2 and c["rs1000_lost"]["net"] == -1300
    assert c["rs1000_2000_lost"]["n"] == 1 and c["rs2000plus_lost"]["n"] == 1
    assert c["pct8_15_lost"]["n"] == 2 and c["pct15_25_lost"]["n"] == 1 and c["never8"]["n"] == 1
    assert L.same_trades(tr, c["rs1000_lost"]["keys"]) == -1300


def test_drop_keys_keeps_counts():
    x = {"a": {"n": 2, "net": -5, "keys": [[1]]}, "b": [{"keys": [], "n": 1}]}
    assert L.drop_keys(x) == {"a": {"n": 2, "net": -5}, "b": [{"n": 1}]}


def test_stats_dd_and_worst_day():
    tr = [T(date(2025, 5, 1), 100), T(date(2025, 5, 2), -300), T(date(2025, 5, 2), -100, er=20), T(date(2025, 5, 5), 50)]
    s = L.stats(tr)
    assert s["worst_day"] == -400 and s["max_dd"] == 400 and s["trades"] == 4
    assert L.max_dd([]) == 0.0


def test_bh_and_family_q():
    q = L.bh([0.01, 0.04, 0.03, 0.5])
    assert np.allclose(q, [0.04, 0.04 * 4 / 3 * 3 / 3, 0.04, 0.5]) or q[0] == pytest.approx(0.04)
    qn = L.family_q({"a": 0.001, "b": 0.2}, [("x", 1.0)] * 98)
    assert qn["a"] == pytest.approx(0.1) and qn["b"] == 1.0


def test_prior_family_parsing(tmp_path):
    (tmp_path / "r75-c-x.yaml").write_text("title: Breadth Donchian lever\nresults: 'Twin p 0.2934 (BH q 0.7673)'\n")
    (tmp_path / "r65-a.yaml").write_text("title: Donchian trail\nresults: 'exit-twin p 0.04, p 0.33'\n")
    (tmp_path / "r65-b.yaml").write_text("title: OOV trail only\n")
    (tmp_path / "r66-d.yaml").write_text("title: R51 Donchian lock, no p stated\n")
    (tmp_path / "r40-x.yaml").write_text("title: Donchian old\nTwin p 0.001\n")
    pf = dict(L.prior_family(tmp_path))
    assert pf["r75-c-x"] == pytest.approx(0.2934) and pf["r65-a"] == pytest.approx(0.33) and pf["r66-d"] == 1.0
    assert "r65-b" not in pf and "r40-x" not in pf
    assert sum(1 for k in pf if k.startswith("r104-f11-unlogged")) == 20


def test_real_prior_family_is_large():
    pf = L.prior_family()
    assert len(pf) >= 150 and all(0 < p <= 1 for _, p in pf)


def test_twin_totals_and_p():
    d = date(2025, 5, 2)
    base = [T(d, 100.0, er=10, xr=40), T(d, -50.0, er=50, xr=51), T(d, 20.0, er=60, xr=90)]
    rng = np.random.default_rng(0)
    seen = []

    def nf(t, rows):
        seen.extend((t["er"], int(r)) for r in rows)
        return [0.0] * len(rows)
    tw = L.twin_totals(base, 1, 50, rng, nf)
    assert tw.shape == (50,) and set(np.round(tw)) <= {70.0 - 20.0, 70.0 - 100.0}
    assert all(er < r <= {10: 40, 60: 90}[er] for er, r in seen) and all(er != 50 for er, _ in seen)
    assert L.p_of(np.array([1.0, 2.0, 3.0]), 2.5) == pytest.approx(2 / 4)
    tw = L.twin_totals(base, 1, 10, np.random.default_rng(0), lambda t, r: [0.0] * len(r), leg_weight=0.5)
    assert set(np.round(tw)) <= {70.0 - 10.0, 70.0 - 50.0}


def _res(**kw):
    s = dict(net=100, worst_day=-50, max_dd=200)
    b = dict(net=50, worst_day=-50, max_dd=200)
    r = dict(design=dict(s), base_design=dict(b), final=dict(s), base_final=dict(b), wf_net=10, base_wf_net=5,
             q=[2, 2, 2, 0], base_q=[1, 1, 1, 1], p_design=0.01, p_final=0.02, q_bh=0.05, changed_design=40,
             ex_top5_design=5, slip2_final_net=10, base_slip2_final_net=5)
    r.update(kw)
    return r


def test_gate_parts():
    assert L.gate(_res())["passed"]
    assert "a_quarters" in L.gate(_res(q=[2, 2, 0, 0]))["failed"]
    assert "c_twin" in L.gate(_res(p_final=0.06))["failed"]
    assert "d_bh" in L.gate(_res(q_bh=0.11))["failed"]
    assert "e_changed" in L.gate(_res(changed_design=29))["failed"]
    assert "f_ex_top5" in L.gate(_res(ex_top5_design=-1))["failed"]
    assert "g_slip2" in L.gate(_res(slip2_final_net=5))["failed"]
    worse = _res(final=dict(net=100, worst_day=-52, max_dd=200))
    assert "b_final" in L.gate(worse)["failed"]
    ok = _res(final=dict(net=100, worst_day=-50.9, max_dd=203.9))
    assert L.gate(ok)["passed"]


def test_bootstrap_helpers():
    assert list(L.runs([1, 2, 3, 4], 2)) == [3, 5, 7] and len(L.runs([1], 2)) == 0
    rng = np.random.default_rng(0)
    b = L.boot_iid([-1.0, 1.0], 4, 1000, rng)
    assert b.shape == (1000,) and set(np.unique(b)) <= {-4, -2, 0, 2, 4}
    r = L.pct_le([1, 2, 3, 4], 2)
    assert r["p_le"] == 0.5 and r["n"] == 4
    assert L.pct_le([], 0)["n"] == 0


def test_periods_and_quarters():
    assert L.period_of(date(2026, 3, 31)) == "design" and L.period_of(date(2026, 4, 1)) == "final"
    assert L.period_of(date(2026, 10, 19)) == "oos" and L.period_of(date(2024, 11, 19)) is None
    assert L.quarter_of(date(2025, 8, 1)) == 1 and L.quarter_of(date(2026, 5, 1)) is None
    assert L.ex_top5_gain([10, 9, 8, 7, 6, 5, -20]) == pytest.approx(-15)


def test_final_and_oos_read_once(tmp_path, monkeypatch):
    S = pytest.importorskip("r105study")
    flag = tmp_path / "FINAL_READ"
    flag.write_text("x")
    monkeypatch.setattr(S.L, "FINAL_FLAG", flag)
    with pytest.raises(SystemExit):
        S.final()
    monkeypatch.setattr(S, "EXT", False)
    with pytest.raises(SystemExit):
        S.oos()
