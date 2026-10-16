"""R64 tests: the breakeven-step engine (parity with R51's when no breakeven step, the ladder's semantics: next-minute
locks, breakeven = fill + charges, locks only rise, gap fills, stop / target ties), the variant table, the statistics
(big movers kept / cut, green days, drawdown, empty data), the paired bootstrap, BH, the gates and the decision, the
one-time final read, and the look-ahead guard of the reused signals (OOV's levels and first bar, R44's Donchian).
Synthetic data only (no lake).

    python -m pytest research/hunt/r64 -q
"""
from __future__ import annotations

import importlib.util
import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r51"))
sys.path.insert(0, str(HERE))

import engine as E  # noqa: E402
import r51cfg as K  # noqa: E402
import r64arms as A  # noqa: E402
import r64cfg as R  # noqa: E402
import r64engine as G  # noqa: E402
import r64study as ST  # noqa: E402

N = K.NROWS
NONE = np.zeros((0, 2))
LAD = np.array(R.LADDER, dtype=np.float64)


def contract(o=100.0, vol=1000.0):
    a = np.full((5, N), float(o))
    a[4] = vol
    return a


def sim(C, e=10, sq=K.SQ_ROW, fx=-1, stop=-1.0, tgt=-1.0, steps=NONE, tmax=-1, be=0.0, qty=75.0):
    return G.simulate(C, e, sq, fx, stop, tgt, -1.0, 0.0, 0.0, steps, tmax, -1.0, -1.0, K.TICK, qty, be)


# ------------------------------------------------------------------------------------------------ parity with R51
def test_parity_with_r51_engine_without_breakeven_step():
    rng = np.random.default_rng(1)
    cases = [dict(stop=0.15, tgt=0.30, steps=NONE, tmax=30), dict(stop=0.30, tgt=-1.0, steps=np.array([[0.4, 0.1]]),
             tmax=60), dict(stop=0.10, tgt=-1.0, steps=np.array([[0.3, 0.0], [0.6, 0.3], [1.0, 0.6]]), tmax=-1),
             dict(stop=-1.0, tgt=0.5, steps=NONE, tmax=-1)]
    for k in range(60):
        C = contract()
        walk = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, N)))
        C[0] = walk * (1 + rng.normal(0, 0.003, N))
        C[3] = walk
        C[1] = np.maximum(C[0], C[3]) * (1 + np.abs(rng.normal(0, 0.01, N)))
        C[2] = np.minimum(C[0], C[3]) * (1 - np.abs(rng.normal(0, 0.01, N)))
        C[4] = rng.integers(0, 5000, N)
        gaps = rng.random(N) < 0.05
        C[:4, gaps] = np.nan
        c = cases[k % len(cases)]
        e = int(rng.integers(5, 300))
        fx = int(rng.integers(e, N)) if k % 3 == 0 else -1
        a = E.simulate(C, e, K.SQ_ROW, fx, c["stop"], c["tgt"], -1.0, 0.0, 0.0, c["steps"], c["tmax"], -1.0, -1.0,
                       K.TICK, 75.0)
        b = G.simulate(C, e, K.SQ_ROW, fx, c["stop"], c["tgt"], -1.0, 0.0, 0.0, c["steps"], c["tmax"], -1.0, -1.0,
                       K.TICK, 75.0, 0.0)
        assert np.allclose(np.array(a, float), np.array(b, float), equal_nan=True), (k, a, b)


# ------------------------------------------------------------------------------------------------ the ladder
def test_ladder_breakeven_arms_next_minute_not_same_minute():
    C = contract()
    C[1, 12] = 131.0                    # +31% high ...
    C[2, 12] = 95.0                     # ... and a dip in the same minute: the lock is not active yet (stop at 90)
    C[0, 13], C[2, 13] = 102.0, 100.5   # next minute opens above breakeven 101 and dips under it
    r = sim(C, stop=0.10, steps=LAD, be=101.0)
    assert r[6] == E.R_LOCK and r[5] == 13 and r[1] == pytest.approx(101.0)


def test_ladder_same_minute_low_hits_stop_first():
    C = contract()
    C[1, 12] = 131.0
    C[2, 12] = 89.0                     # the -10% stop is hit in the minute the +30% is reached: a stop
    r = sim(C, stop=0.10, steps=LAD, be=101.0)
    assert r[6] == E.R_STOP and r[5] == 12 and r[1] == pytest.approx(90.0)


def test_ladder_steps_60_and_100_and_only_rise():
    C = contract()
    C[:4, 12:] = 170.0
    C[1, 12], C[1, 13] = 165.0, 205.0   # +65% then +105% (lows stay at 170: above the +30% lock of 130)
    C[2, 15] = 150.0                    # under the +60% lock (160, set by minute 13's high): out at 160
    r = sim(C, stop=0.10, steps=LAD, be=101.0)
    assert r[6] == E.R_LOCK and r[5] == 15 and r[1] == pytest.approx(160.0)
    C[2, 14] = 135.0                    # minute 14 dips to 135: under the +60% lock already (160), never back to 130
    r = sim(C, stop=0.10, steps=LAD, be=101.0)
    assert r[6] == E.R_LOCK and r[5] == 14 and r[1] == pytest.approx(160.0)


def test_ladder_gap_through_lock_fills_at_open():
    C = contract()
    C[1, 12] = 140.0
    C[0, 13], C[2, 13], C[1, 13], C[3, 13] = 97.0, 96.0, 98.0, 97.0     # opens below breakeven 101
    r = sim(C, stop=0.10, steps=LAD, be=101.0)
    assert r[6] == E.R_LOCK and r[1] == pytest.approx(97.0)


def test_no_target_holds_past_30_percent():
    C = contract()
    C[1, 12] = 131.0
    C[0, 13:], C[1, 13:], C[2, 13:], C[3, 13:] = 150.0, 150.0, 150.0, 150.0
    r = sim(C, stop=0.10, steps=LAD, be=101.0)
    assert r[6] == E.R_SQ and r[1] == pytest.approx(150.0)
    r2 = sim(C, stop=0.15, tgt=0.30)    # BEFORE: sold at the +30% target
    assert r2[6] == E.R_TARGET and r2[1] == pytest.approx(130.0)


def test_breakeven_level_is_fill_plus_round_trip_charges():
    class D:
        d = date(2026, 4, 10)
        expiry = False
    S = np.stack([contract()])
    be = G.be_levels("NIFTY", D, S, np.array([0, -1]), np.array([10, 10]), K.SQ_ROW, 65.0)
    rE = G.entry_fill(S[0], 10, K.SQ_ROW, K.TICK, 65.0, *G._ENTRY_SLIP())[1]
    ch = float(E.charges("NIFTY", rE, rE, 65.0, D.d)[0])
    assert be[0] == pytest.approx(rE + ch / 65.0) and be[0] > 100.0 and be[1] == 0.0


def test_entry_fill_matches_simulate_and_no_fill():
    C = contract()
    C[0, 10:12] = np.nan
    f = G.entry_fill(C, 10, K.SQ_ROW, K.TICK, 75.0, *G._ENTRY_SLIP())
    r = sim(C)
    assert f[2] == r[4] == 12 and f[1] == pytest.approx(r[2])
    C[0, 10:13] = np.nan
    assert G.entry_fill(C, 10, K.SQ_ROW, K.TICK, 75.0, *G._ENTRY_SLIP())[2] == -1


def test_peak_gain_window():
    C = contract()
    C[1, 20], C[1, 300] = 125.0, 140.0
    assert G.peak_gain(C, 10, 200) == pytest.approx(0.25)
    assert G.peak_gain(C, 10, 300) == pytest.approx(0.40)
    assert math.isnan(G.peak_gain(C, -1, 300))


# ------------------------------------------------------------------------------------------------ variants
def test_exit_spec_table():
    own = R.OWN["Donchian"]
    assert R.exit_spec(own, "B0") == dict(stop=0.15, target=0.30, steps=(), tmax=30, sq=(15, 15))
    a1 = R.exit_spec(own, "A1")
    assert a1["stop"] == 0.10 and a1["target"] is None and a1["steps"] == R.LADDER and a1["tmax"] == 30
    a2 = R.exit_spec(R.OWN["OOV"], "A2")
    assert a2["tmax"] == -1 and a2["sq"] == (15, 15) and a2["steps"] == R.LADDER
    a3 = R.exit_spec(R.OWN["OOV"], "A3")
    assert a3["stop"] == 0.10 and a3["steps"] == R.SOLO_LAYER and a3["tmax"] == 60
    a4 = R.exit_spec(R.OWN["S4"], "A4")
    assert a4["stop"] == 0.25 and a4["target"] is None and a4["steps"] == R.LADDER
    assert R.exit_spec(R.OWN["S1"], "A4")["stop"] is None          # S1 has no premium stop of its own
    with pytest.raises(ValueError):
        R.exit_spec(own, "Z9")
    assert G.needs_be(E.Exits(steps=R.LADDER)) and not G.needs_be(E.Exits(steps=R.SOLO_LAYER))


def test_periods_and_quarters():
    assert R.period_of(date(2024, 11, 19)) is None
    assert R.period_of(date(2026, 3, 31)) == "design" and R.period_of(date(2026, 4, 1)) == "final"
    assert R.quarter_of(date(2025, 3, 31)) is None and R.quarter_of(date(2026, 2, 2)) == 3


# ------------------------------------------------------------------------------------------------ statistics
def tr(day, net, why="time", peak=0.0, ret=0.0, und="NIFTY"):
    return dict(day=day, net=net, why=why, peak=peak, ret=ret, und=und)


def test_stats_empty_and_basic():
    assert ST.stats([])["trades"] == 0
    d1, d2, d3 = date(2025, 5, 1), date(2025, 5, 2), date(2025, 5, 5)
    T = [tr(d1, 1000, "target", 0.35, 0.30), tr(d1, -400, "stop"), tr(d2, -900, "lock", 0.5, 0.05),
         tr(d3, 200, "squareoff")]
    s = ST.stats(T)
    assert s["trades"] == 4 and s["days"] == 3 and s["net"] == -100 and s["win"] == 0.5
    assert s["worst_day"] == -900 and s["biggest_loss"] == -900 and s["max_dd"] == 900
    assert s["green_days"] == pytest.approx(2 / 3, abs=1e-3)
    assert s["stops"] == 1 and s["locks"] == 1 and s["targets"] == 1 and s["squareoff"] == 1
    assert s["big_movers"] == 2 and s["big_kept"] == 1 and s["big_cut"] == 1 and s["top5_share"] is None


def test_paired_boot_and_bh():
    d = [date(2025, 5, i) for i in range(1, 29)]
    before = [tr(x, -100) for x in d]
    after = [tr(x, 100) for x in d]
    p, diff = ST.paired_boot(before, after)
    assert p == 0.0 and diff == 5600
    p2, _ = ST.paired_boot(after, before)
    assert p2 == 1.0
    assert ST.paired_boot([], []) == (None, 0.0)
    q = ST.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5)


def test_gates_and_decision():
    good = dict(net=5000, trades=50, ex_top5=100, max_dd=3000, worst_day=-1000)
    assert ST.gates(good, dict(net=1), dict(net=1), 0.01, 0.05) == []
    f = ST.gates(dict(net=-1, trades=10, ex_top5=-5), dict(net=-1), dict(net=0), None, None)
    assert len(f) == 7
    base = dict(design=dict(net=100), wf=dict(net=100), final=dict(net=100, max_dd=5000, worst_day=-3000))
    arm = {"B0": dict(base, verdict="PASS"),
           "A1": dict(design=dict(net=200), wf=dict(net=200), final=dict(net=300, max_dd=6000, worst_day=-4000),
                      verdict="PASS"),
           "A2": dict(design=dict(net=50), wf=dict(net=200), final=dict(net=300, max_dd=2000, worst_day=-1000),
                      verdict="PASS"),
           "A3": dict(base, verdict="FAIL: p >= 0.05"), "A4": dict(base, verdict="FAIL: p >= 0.05")}
    dec = ST.decide(arm)
    assert dec["recommended"] == "A1" and dec["tradeoffs"] == ["A2"]


# ------------------------------------------------------------------------------------------------ final guard
def test_final_needs_the_flag(tmp_path, monkeypatch):
    monkeypatch.setattr(R, "FINAL_FLAG", tmp_path / "FINAL_READ")
    monkeypatch.setattr(R, "RESULTS", tmp_path)
    with pytest.raises(SystemExit):
        ST.run("r51", "final")
    (tmp_path / "FINAL_READ").write_text("x")
    (tmp_path / "final_r51.json").write_text("{}")
    with pytest.raises(SystemExit):
        ST.run("r51", "final")
    with pytest.raises(SystemExit):
        ST.final()


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_oov_side_reads_only_the_first_five_minutes():
    idx = np.full((4, N), 100.0)
    idx[0, 0], idx[3, 4] = 106.0, 107.0
    lev = (100.0, 105.0, 95.0)
    assert A.oov_side(idx, lev) == "C"
    later = idx.copy()
    later[:, 5:] = 50.0                 # anything after 09:19 cannot change the signal
    assert A.oov_side(later, lev) == "C"
    idx2 = idx.copy()
    idx2[0, 0], idx2[3, 4] = 94.0, 93.0
    assert A.oov_side(idx2, lev) == "P"
    assert A.oov_side(idx, (np.nan, 105.0, 95.0)) is None
    idx3 = idx.copy()
    idx3[3, 4] = 104.0                  # opened above, closed back inside
    assert A.oov_side(idx3, lev) is None


def test_value_area_is_the_previous_session_only():
    class Day:
        def __init__(self, a):
            self.idx = a
    rng = np.random.default_rng(3)
    days = {}
    for i in range(4):
        a = np.full((4, N), np.nan)
        c = 1000 + np.cumsum(rng.normal(0, 1, N))
        a[0], a[1], a[2], a[3] = c, c + 0.5, c - 0.5, c
        days[date(2025, 6, 2 + i)] = Day(a)
    lev = A.prev_value_area(days, K.ROOT)
    assert np.isnan(lev[date(2025, 6, 2)]).all()
    d3 = date(2025, 6, 4)
    before = np.array(lev[d3])
    days[d3].idx[:, :] = days[d3].idx * 1.1           # change that day and the next: its own levels must not move
    days[date(2025, 6, 5)].idx[:, :] = 1.0
    A._H37 = None
    lev2 = A.prev_value_area(days, K.ROOT)
    assert np.allclose(before, lev2[d3])


def test_donchian_signal_has_no_look_ahead():
    spec = importlib.util.spec_from_file_location("r64t_r44cfg", K.ROOT / "research" / "hunt" / "r44" / "r44cfg.py")
    sys.modules.setdefault("r44cfg", importlib.util.module_from_spec(spec))
    spec.loader.exec_module(sys.modules["r44cfg"])
    spec = importlib.util.spec_from_file_location("r64t_ind", K.ROOT / "research" / "hunt" / "r44" / "ind.py")
    ind = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ind)
    rng = np.random.default_rng(7)
    c = 100 + np.cumsum(rng.normal(0, 1, 400))
    o, h, l = c, c + 0.4, c - 0.4
    lo1, sh1 = ind.signals("donchian", (20,), o, h, l, c, warm=20)
    k = 250
    lo2, sh2 = ind.signals("donchian", (20,), o[:k], h[:k], l[:k], c[:k], warm=20)
    assert (lo1[:k] == lo2).all() and (sh1[:k] == sh2).all() and (lo1.any() or sh1.any())
