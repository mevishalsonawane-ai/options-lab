"""R65 tests: the step-trail table (Boss's +10% -> +5%, +15% -> +10% staircase, the 7.5 / 10 gaps, breakeven + charges,
the -5% stop moved up), the variant table (own stop / time exit / square-off kept, targets dropped only in the "n"
variants), the trail's engine semantics on R64's engine (next-minute locks, staircase between steps, gap fills, the
stop / lock / target order), the look-ahead guard (bars after a decision never change it), the trail accounting, the
random-exit twins, the decision and the one-time final read. Synthetic data only (no lake).

    python -m pytest research/hunt/r65 -q
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r51"))
sys.path.insert(0, str(HERE.parent / "r64"))
sys.path.insert(0, str(HERE))

import r51cfg as K  # noqa: E402
import r64cfg as R64  # noqa: E402
import r64engine as G  # noqa: E402
import r65cfg as R  # noqa: E402
import r65study as ST  # noqa: E402

N = K.NROWS


def contract(o=100.0, vol=1000.0):
    a = np.full((5, N), float(o))
    a[4] = vol
    return a


def steps_of(v, arm="Donchian"):
    return np.array(R.exit_spec(R.OWN[arm], v)["steps"], dtype=np.float64).reshape(-1, 2)


def sim(C, steps, e=10, stop=-1.0, tgt=-1.0, tmax=-1, be=0.0, fx=-1, qty=75.0):
    return G.simulate(C, e, K.SQ_ROW, fx, stop, tgt, -1.0, 0.0, 0.0, steps, tmax, -1.0, -1.0, K.TICK, qty, be)


def level(C, m, px):
    """Flat bars at `px` from minute m on."""
    C[:4, m:] = px


def path(C, m, hi=None, lo=None, op=None, cl=None):
    """Set minute m's bar."""
    if op is not None:
        C[0, m] = op
    if hi is not None:
        C[1, m] = hi
    if lo is not None:
        C[2, m] = lo
    if cl is not None:
        C[3, m] = cl


# ------------------------------------------------------------------------------------------------ the step table
def test_boss_staircase_s5():
    s = R.trail_steps(0.05)
    assert s[0] == (0.10, 0.05) and s[1] == (0.15, 0.10) and s[2] == (0.20, 0.15)
    assert len(s) == 199 and s[-1] == (10.0, 9.95)
    assert all(abs((b[0] - a[0]) - 0.05) < 1e-9 for a, b in zip(s, s[1:]))


def test_gap_75_and_10():
    s75 = R.trail_steps(0.075)
    assert s75[0] == (0.10, 0.025) and s75[1] == (0.15, 0.075)
    s10 = R.trail_steps(0.10)
    assert s10[0][0] == 0.10 and math.isnan(s10[0][1])          # breakeven + charges at +10%
    assert s10[1] == (0.15, 0.05) and s10[2] == (0.20, 0.10)


def test_t5_starts_at_plus5_with_breakeven():
    sp = R.exit_spec(R.OWN["Donchian"], "T5")
    assert sp["stop"] == 0.05 and sp["target"] == 0.30
    assert sp["steps"][0][0] == 0.05 and math.isnan(sp["steps"][0][1]) and sp["steps"][1] == (0.10, 0.05)
    assert R.exit_spec(R.OWN["Donchian"], "T5n")["target"] is None


def test_variant_table_keeps_own_stop_time_squareoff():
    for arm in ("Donchian", "OOV", "S1", "S2", "S3", "S4"):
        own = R.OWN[arm]
        for v in R.TRAILS:
            sp = R.exit_spec(own, v)
            assert sp["tmax"] == own.get("tmax", -1) and sp["sq"] == own["sq"]
            assert sp["stop"] == (0.05 if v.startswith("T") else own.get("stop"))
            assert sp["target"] == (None if v.endswith("n") else own.get("target"))
            assert tuple(own.get("steps", ())) == sp["steps"][:len(own.get("steps", ()))]   # own lock kept
        assert R.exit_spec(own, "B0") == R64.exit_spec(own, "B0")
        assert R.exit_spec(own, "A4") == R64.exit_spec(own, "A4")
        assert R.exit_spec(own, "N0")["target"] is None and R.exit_spec(own, "N0")["steps"] == tuple(own.get("steps", ()))
    with pytest.raises(ValueError):
        R.exit_spec(R.OWN["OOV"], "S6")


def test_plain_counterparts():
    assert all(R.PLAIN[v] == ("N0" if v.endswith("n") else "B0") for v in R.TRAILS)
    assert len(R.VARIANTS) * len(R.ARMS) == 30 and len(R.TRAILS) * len(R.ARMS) == 24


# ------------------------------------------------------------------------------------------------ engine semantics
def test_plus10_locks_plus5_from_next_minute():
    C = contract()
    level(C, 12, 108.0)
    path(C, 12, hi=110.0)                                     # best +10% at minute 12
    path(C, 14, op=104.0, lo=104.0, hi=104.0, cl=104.0)       # falls through +5%, gaps below it
    E_, X, *_ , ef, xr, rs = sim(C, steps_of("S5n"))
    assert ef == 10 and xr == 14 and G.E.REASONS[rs] == "lock" and X == pytest.approx(104.0)   # gap fill at open


def test_lock_not_active_in_the_minute_that_set_it():
    C = contract()
    path(C, 12, hi=112.0, lo=99.0)                            # high and a dip in the same minute: no lock exit then
    out = sim(C, steps_of("S5n"))
    assert out[5] != 12


def test_staircase_holds_between_steps():
    C = contract()
    level(C, 12, 109.0)
    path(C, 12, hi=113.0)                                     # best +13% -> stop stays +5% (not +8%)
    path(C, 15, lo=106.0)                                     # +6%: above +5%, no exit
    path(C, 16, lo=105.0, op=106.0)                           # touches +5%
    out = sim(C, steps_of("S5n"))
    assert out[5] == 16 and out[1] == pytest.approx(105.0)


def test_plus15_moves_stop_to_plus10():
    C = contract()
    level(C, 12, 112.0)
    path(C, 12, hi=115.0)
    path(C, 14, lo=110.0, op=112.0)
    out = sim(C, steps_of("S5n"))
    assert out[5] == 14 and out[1] == pytest.approx(110.0)
    out75 = sim(C, steps_of("S75n"))                          # gap 7.5: +7.5% lock, not touched at 110
    assert out75[5] != 14


def test_s10_breakeven_plus_charges():
    C = contract()
    level(C, 12, 103.0)
    path(C, 12, hi=110.0)
    path(C, 14, lo=100.5, op=101.0)
    out = sim(C, steps_of("S10n"), be=100.8)                  # be above the raw fill
    assert out[5] == 14 and out[1] == pytest.approx(100.8)


def test_target_kept_wins_over_trail_in_s5():
    C = contract()
    path(C, 12, hi=131.0)                                     # through the +30% target the same minute
    out = sim(C, steps_of("S5"), tgt=0.30)
    assert G.E.REASONS[out[6]] == "target" and out[1] == pytest.approx(130.0)
    level(C, 13, 128.0)
    path(C, 15, lo=124.0, op=127.0)
    out_n = sim(C, steps_of("S5n"))                           # no target: best +31% -> step +30% -> lock +25%
    assert out_n[5] == 15 and out_n[1] == pytest.approx(125.0)


def test_own_stop_until_trail_rises_above_it():
    C = contract()
    path(C, 12, lo=84.0, op=99.0)                             # own -15% stop hit before any gain
    out = sim(C, steps_of("S5n"), stop=0.15)
    assert G.E.REASONS[out[6]] == "stop" and out[1] == pytest.approx(85.0)
    out_t = sim(C, steps_of("T5n"), stop=0.05)                # T5: -5% hit first
    assert out_t[5] == 12 and out_t[1] == pytest.approx(95.0)


def test_lookahead_guard_future_bars_never_change_a_decision():
    """Randomising every bar after the exit row leaves the exit unchanged; the lock at a minute depends only on highs
    before it."""
    rng = np.random.default_rng(65)
    for _ in range(40):
        C = contract()
        walk = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, N)))
        C[0] = walk
        C[3] = walk
        C[1] = walk * (1 + np.abs(rng.normal(0, 0.01, N)))
        C[2] = walk * (1 - np.abs(rng.normal(0, 0.01, N)))
        out = sim(C, steps_of("S5n"), stop=0.15, tmax=120)
        xr = out[5]
        C2 = C.copy()
        if xr + 1 < N:
            C2[:4, xr + 1:] = rng.uniform(1, 400, size=(4, N - xr - 1))
        out2 = sim(C2, steps_of("S5n"), stop=0.15, tmax=120)
        assert out2[5] == xr and out2[1] == pytest.approx(out[1]) and out2[6] == out[6]


# ------------------------------------------------------------------------------------------------ accounting
def tr(day, net, why="lock", ret=0.05, post=np.nan, er=10, und="NIFTY", side="C"):
    return dict(und=und, day=day, side=side, er=er, why=why, net=net, ret=ret, post=post, xr=er + 5)


def test_trail_account_given_up_saved_and_cut_winners():
    d1, d2, d3 = date(2026, 4, 1), date(2026, 4, 2), date(2026, 4, 3)
    trail = [tr(d1, 300, post=0.40), tr(d2, 200, ret=0.05), tr(d3, -100, why="stop")]
    plain = [tr(d1, 2000, why="target", ret=0.30), tr(d2, -900, why="stop", ret=-0.15), tr(d3, -100, why="stop")]
    a = ST.trail_account(trail, plain)
    assert a["locked"] == 2 and a["matched"] == 2 and a["unmatched"] == 0
    assert a["given_up"] == 1700 and a["saved"] == 1100 and a["net_effect"] == -600
    assert a["cut_plain30"] == 1 and a["went_on30"] == 1
    assert a["worst_example"]["plain_net"] == 2000 and a["best_example"]["plain_net"] == -900


def test_trail_account_empty_and_unmatched():
    a = ST.trail_account([], [])
    assert a["locked"] == 0 and a["given_up"] == 0 and a["worst_example"] is None
    b = ST.trail_account([tr(date(2026, 4, 1), 50)], [])
    assert b["locked"] == 1 and b["matched"] == 0 and b["unmatched"] == 1


# ------------------------------------------------------------------------------------------------ random-exit twins
def test_exit_twins_rows_and_counts():
    plain = [dict(net=100.0, er=10, xr=30), dict(net=-50.0, er=20, xr=21), dict(net=10.0, er=40, xr=60)]
    seen = []

    def nf(t, rows):
        assert ((rows > t["er"]) & (rows <= t["xr"])).all()
        seen.append(len(rows))
        return np.zeros(len(rows))
    tw = ST.exit_twin_totals(plain, 1, 200, np.random.default_rng(1), nf)
    assert tw.shape == (200,) and sum(seen) == 200                  # one early exit per set
    assert set(np.round(tw)) <= {-40.0, 50.0}                       # base 60 minus the zeroed trade's net
    # the 1-row trade (xr - er = 1) is never picked
    tw2 = ST.exit_twin_totals(plain, 5, 50, np.random.default_rng(2), nf)
    assert np.allclose(tw2, 0.0 - 50.0)                              # both eligible zeroed, the short one kept


def test_exit_twins_no_locks_and_no_trades():
    plain = [dict(net=100.0, er=10, xr=30)]
    assert np.allclose(ST.exit_twin_totals(plain, 0, 10, np.random.default_rng(0), None), 100.0)
    assert np.allclose(ST.exit_twin_totals([], 3, 10, np.random.default_rng(0), None), 0.0)
    assert ST.p_of(np.array([1.0, 2.0, 3.0]), 2.5) == round(2 / 4, 4)


def test_forced_rows_take_the_index_exit_first():
    assert ST.forced_rows(np.array([50, 80]), 60).tolist() == [50, 60]
    assert ST.forced_rows(np.array([50, 80]), -1).tolist() == [50, 80]


# ------------------------------------------------------------------------------------------------ decision, final read
def _arm(nets: dict, verdict="PASS", exit_q=0.01, dd=None):
    out = {}
    for v in R.VARIANTS:
        n = nets.get(v, 0)
        out[v] = dict(design=dict(net=n), wf=dict(net=n),
                      final=dict(net=n, max_dd=(dd or {}).get(v, 1000), worst_day=-500),
                      verdict=verdict, exit_q=exit_q)
    return out


def test_decide_needs_both_baselines_gates_and_exit_twins():
    a = _arm({"B0": 100, "A4": 200, "S5": 300})
    assert ST.decide(a)["recommended"] == "S5"
    assert ST.decide(_arm({"B0": 100, "A4": 400, "S5": 300}))["recommended"] is None      # loses to A4
    assert ST.decide(_arm({"B0": 500, "A4": 200, "S5": 300}))["recommended"] is None      # loses to B0
    assert ST.decide(_arm({"B0": 100, "A4": 200, "S5": 300}, verdict="FAIL: x"))["recommended"] is None
    assert ST.decide(_arm({"B0": 100, "A4": 200, "S5": 300}, exit_q=0.5))["recommended"] is None
    t = ST.decide(_arm({"B0": 100, "A4": 400, "S5": 300}, dd={"S5": 10}))
    assert "S5" in t["tradeoffs"] and t["keep"].startswith("A4")


def test_final_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(R, "RESULTS", tmp_path)
    monkeypatch.setattr(R, "FINAL_FLAG", tmp_path / "FINAL_READ")
    with pytest.raises(SystemExit):
        ST.run("r51", "final")                                      # not opened by `final`
    (tmp_path / "FINAL_READ").write_text("x")
    (tmp_path / "final_r51.json").write_text("{}")
    with pytest.raises(SystemExit):
        ST.run("r51", "final")                                      # already read
    with pytest.raises(SystemExit):
        ST.final()
