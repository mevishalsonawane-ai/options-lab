"""R117 tests: the lock rules, the look-ahead guard, FT3's per-mark reasons, statistics and gates.

    python3 -m pytest research/hunt/r117_zero_hero_lock -q
"""
from __future__ import annotations

import math
import pickle
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r117cfg as K  # noqa: E402
import r117lib as L  # noqa: E402
import r117day as D  # noqa: E402
import ft3_app as F  # noqa: E402
import r98data as R98  # noqa: E402


def run(rule, c, dec=None, upd=None, late=None, e=10.0):
    c = np.asarray(c, float)
    n = len(c)
    ok = ~np.isnan(c)
    dec = ok.copy() if dec is None else np.asarray(dec, bool)
    upd = ok.copy() if upd is None else np.asarray(upd, bool)
    late = np.zeros(n, bool) if late is None else np.asarray(late, bool)
    return L.decide(rule, e, c, dec, upd, late)


# ------------------------------------------------------------------------------------------------ rules
def test_hold_never_sells():
    assert run("HOLD", [10, 50, 0.05]) == []


def test_empty_and_all_nan_paths():
    for r in K.ALL_EXITS:
        assert run(r, []) == []
        assert run(r, [np.nan] * 5) == []


def test_t0_is_a_25pct_stop_from_the_fill():
    assert run("T0", [10, 9, 7.6, 7.4]) == [(3, 1.0)]
    assert run("T0", [10, 20, 15.5, 14.9]) == [(3, 1.0)]          # trail from the best close 20


def test_new_high_never_triggers_its_own_sale():
    for r in ("T0", "L4", "L5", "L7"):
        assert run(r, [10, 11, 12, 13, 14]) == []


def test_armed_trail_has_no_stop_before_2x():
    assert run("L4", [10, 5, 1, 0.1]) == []
    assert run("L4", [10, 19.9, 5]) == []
    assert run("L4", [10, 20, 12.1, 11.9]) == [(3, 1.0)]           # 40% off the best 20 = 12
    assert run("L5", [10, 20, 10.1, 9.9]) == [(3, 1.0)]


def test_arming_close_sells_only_from_the_next_bar():
    # L4 arms on the 2x close; the same close cannot sell
    assert run("L4", [10, 20]) == []


def test_half_trail():
    d = run("L1", [10, 31, 40, 20.1, 19.9])
    assert d == [(1, 0.5), (4, 0.5)]                                 # half at 3x, rest when <= 0.5 x best 40
    assert run("L1", [10, 29, 2, 0.05]) == []                        # never 3x: held, no stop
    assert run("L2", [10, 20, 12, 10]) == [(1, 0.5), (3, 0.5)]
    # the half sale is a sale: a 2x close on a non-decision bar does not sell
    assert run("L2", [10, 20, 12, 10], dec=[1, 0, 1, 1]) == []


def test_half_needs_decision_and_real_close():
    assert run("L1", [10, 30, 30], dec=[0, 0, 0]) == []
    assert run("L1", [10, 30, 30], upd=[1, 0, 0]) == []                # proxy closes do not trigger the target


def test_step_lock_floors():
    assert run("L3", [10, 39, 5]) == []                              # never 4x: no floor, no stop
    assert run("L3", [10, 40, 25, 20]) == [(3, 1.0)]                 # 4x -> floor 2x = 20
    assert run("L3", [10, 80, 41, 40]) == [(3, 1.0)]                 # 8x -> floor 4x = 40
    assert run("L3", [10, 170, 90, 80]) == [(3, 1.0)]                # 16x -> floor 8x
    assert run("L3", [10, 330, 161, 160]) == [(3, 1.0)]              # 32x -> floor 16x


def test_time_lock_only_from_1400():
    c = [10, 30, 20, 20, 20]
    assert run("L6", c, late=[0, 0, 0, 0, 0]) == []
    assert run("L6", c, late=[0, 0, 0, 1, 1]) == [(3, 1.0)]          # 30% off best 30 = 21
    assert run("L6", [10, 19, 5, 1], late=[1] * 4) == []             # never 2x: nothing


def test_stop_then_wide():
    assert run("L7", [10, 7.4]) == [(1, 1.0)]                        # 25% stop first
    assert run("L7", [10, 20, 14, 10.1]) == []                       # widened to 50% once 2x
    assert run("L7", [10, 20, 14, 9.9]) == [(3, 1.0)]


def test_non_decision_rows_and_proxies():
    assert run("T0", [10, 7, 7], dec=[0, 0, 1]) == [(2, 1.0)]
    # a proxy close does not arm the trail
    assert run("L5", [10, 25, 5], upd=[1, 0, 1]) == []


def test_nan_gaps_are_skipped():
    assert run("L5", [10, np.nan, 20, np.nan, 9]) == [(4, 1.0)]


def test_lookahead_guard_decisions_are_causal():
    """Every decision on row i is the same whatever the closes after row i are."""
    rng = np.random.default_rng(1)
    for _ in range(300):
        n = 60
        c = 10 * np.exp(np.cumsum(rng.normal(0, 0.15, n)))
        c[rng.random(n) < 0.1] = np.nan
        late = np.arange(n) > 30
        for r in K.ALL_EXITS:
            full = run(r, c, late=late)
            for j in range(n):
                c2 = c.copy()
                c2[j + 1:] = rng.uniform(0.05, 500, n - j - 1)
                part = [d for d in run(r, c2, late=late) if d[0] <= j]
                assert part == [d for d in full if d[0] <= j], (r, j)


def test_fractions_never_exceed_one():
    rng = np.random.default_rng(2)
    for _ in range(200):
        c = 10 * np.exp(np.cumsum(rng.normal(0, 0.3, 80)))
        for r in K.ALL_EXITS:
            assert sum(f for _, f in run(r, c)) <= 1 + 1e-9


# ------------------------------------------------------------------------------------------------ FT3 path and sale
def _arr(n=R98.N):
    return np.full((n, 7), np.nan)


def test_ft3_sale_is_after_the_decision_and_falls_back():
    a = _arr()
    a[F.T_EXIT, R98.O] = 3.0
    a[200, R98.C] = 4.0
    assert L.ft3_sale(a, 150) == 3.0                                 # the first open at or after the row
    b = _arr()
    b[200, R98.C] = 4.0
    assert L.ft3_sale(b, 150) == 4.0                                 # no open left: the last close
    assert L.ft3_sale(_arr(), 150) == 0.0                            # nothing at all: worthless


def test_ft3_path_windows():
    a = _arr()
    kb = F.T_EXIT - 5
    a[kb:F.T_EXIT + 1, R98.C] = 5.0
    rows, c, dec, ok, late = L.ft3_path(a, kb)
    assert rows[0] == kb and rows[-1] == F.T_EXIT
    assert not dec[0] and not dec[-1] and dec[1:-1].all()            # never the fill bar, never the 15:04 bar
    assert late.all()


def test_ft3_legs_rest_held_to_the_end():
    a = _arr()
    kb = 100
    a[kb:F.T_EXIT + 1, R98.C] = 1.0
    a[kb:F.T_EXIT + 1, R98.O] = 2.0
    rows, *_ = L.ft3_path(a, kb)
    legs = L.ft3_legs(a, kb, rows, [(3, 0.5)])
    assert legs == [(0.5, 2.0), (0.5, 1.0)]
    assert L.ft3_legs(a, kb, rows, []) == [(1.0, 1.0)]


# ------------------------------------------------------------------------------------------------ statistics
def test_worst_run_and_drawdown():
    v = np.array([100, -10, -20, 5, -1, -1, -1, 50])
    assert L.worst_run(v) == (3, -30.0)
    assert L.max_dd(np.array([100, -30, -40, 10])) == 70
    assert L.max_dd(np.array([-5, -5])) == 10                        # from the starting 0
    assert L.max_dd(np.array([])) == 0.0
    assert L.worst_run(np.array([])) == (0, 0.0)


def _tk(d, net, x="A", reach=1.0, sold=1.0, dec=(), rand=None):
    t = L.Ticket("FT3", "NIFTY", d, "C", 100.0, 0, 1, 10.0, reach)
    t.nets = {x: net, "B": 0.0}
    t.sold_mult = {x: sold, "B": 1.0}
    t.dec = {x: dec, "B": ()}
    t.rand = rand
    return t


def test_metrics_and_without_best_days():
    ts = [_tk(date(2026, 1, i + 1), n, reach=r) for i, (n, r) in enumerate([(1000, 5), (-500, 1), (300, 3), (-100, 1),
                                                                             (2000, 12)])]
    m = L.metrics(ts, "A", boot=False)
    assert m["tickets"] == 5 and m["total"] == 2700 and m["win"] == 60
    assert m["ex3"] == 2700 - 2000 - 1000 - 300
    assert m["r3"] == 60 and m["r5"] == 40 and m["r10"] == 20
    assert L.metrics([], "A") == dict(tickets=0)


def test_boot_range_contains_mean():
    ts = [_tk(date(2026, 1, 1 + i % 20), float(i % 7 - 3) * 100) for i in range(60)]
    lo, hi = L.boot_mean(ts, "A", n=500)
    m = np.mean([t.nets["A"] for t in ts])
    assert lo <= m <= hi


def test_twins_keep_unchanged_tickets():
    ts = [_tk(date(2026, 1, 1), 100.0, dec=(), rand=[1, 2]), _tk(date(2026, 1, 2), 50.0, dec=((5, 1.0),), rand=[7.0])]
    tw = L.twin_totals(ts, "A", "B", n=10)
    assert np.allclose(tw, 107.0)


def test_twin_p_and_bh():
    p = L.twin_p(10.0, np.zeros(100))
    assert p["hits"] == 0 and p["p"] <= 1 / 101
    assert L.twin_p(-1.0, np.zeros(100))["p"] == 1.0
    q = L.bh([0.01, 0.04, 0.5])
    assert np.allclose(q, [0.03, 0.06, 0.5])
    assert L.bh([0.01], 1000)[0] == 1.0                             # capped at 1
    assert np.isclose(L.bh([1e-6], 1000)[0], 1e-3)


def _m(mean, dd=0.0, ex3=1.0, n=50):
    return dict(tickets=n, mean=mean, max_dd=dd, ex3=ex3)


def test_gates():
    base = {p: _m(0.0, dd=100) for p in K.PERIODS}
    good = {p: _m(10.0, dd=50) for p in K.PERIODS}
    assert L.gates(good, base, 0.001, 0.01)["passed"]
    bad = dict(good, wf=_m(-1.0))
    assert "wf: mean not above today's exit" in L.gates(bad, base, 0.001, 0.01)["fails"]
    assert not L.gates(good, base, 0.2, 0.01)["passed"]
    assert not L.gates(good, base, 0.001, 0.5)["passed"]
    assert not L.gates(dict(good, final=_m(10.0, dd=500)), base, 0.001, 0.01)["passed"]
    assert not L.gates(dict(good, final=_m(10.0, ex3=-1)), base, 0.001, 0.01)["passed"]
    assert not L.gates(dict(good, final=_m(10.0, n=10)), base, 0.001, 0.01)["passed"]


def test_count_prior(tmp_path):
    (tmp_path / "a.yaml").write_text("id: r21-x\ndate: '2026-10-01'\nvariants_tried: 9\n")
    (tmp_path / "b.yaml").write_text("id: r21-y\ndate: '2026-10-01'\n")
    (tmp_path / "c.yaml").write_text("id: h3-z\ndate: '2026-10-01'\n")
    (tmp_path / "d.yaml").write_text("id: r200-late\ndate: '2026-11-01'\nvariants_tried: 50\n")
    (tmp_path / "e.yaml").write_text("id: r117-own\ndate: '2026-10-27'\n")
    assert L.count_prior(tmp_path) == 10 + 1
    assert L.count_prior(tmp_path, only=("R21",)) == 10


def test_periods():
    assert K.period(date(2021, 8, 5)) == "design"
    assert K.period(date(2024, 1, 1)) == "wf"
    assert K.period(date(2026, 10, 19)) == "final"
    assert K.period(date(2026, 10, 27)) is None                      # not in the lake
    assert len(K.TESTS) == 12


# ------------------------------------------------------------------------------------------------ FT3 marks (synthetic)
def synthetic_day(und="NIFTY", spot=22000.0, put_skew=1.0):
    """A chain where every strike's close is its Black-76 price at one vol (puts x put_skew), flat through the day."""
    ix = np.full((R98.N, 4), spot)
    opts = {}
    step = R98.STEP[und]
    for j in range(-10, 11):
        k = round(spot / step) * step + j * step
        for side in ("C", "P"):
            a = np.full((R98.N, 7), np.nan)
            for t in range(R98.N):
                yt = F.years_to_1530(t)
                sig = 0.15 * (put_skew if (side == "P" and k < spot) else 1.0)
                px = max(round(F.b76(spot, k, yt, sig, side == "C") * 20) / 20, 0.05)
                a[t, [R98.O, R98.H, R98.L, R98.C]] = px
                a[t, R98.V] = 100
            opts[(float(k), side)] = a
    return R98.Day(date(2026, 1, 1), und, ix, opts)


def test_mark_table_pick_matches_ft3_app():
    for skew in (1.0, 1.3):
        day = synthetic_day(put_skew=skew)
        for t in F.MARKS:
            rows = D.mark_table(day, t)
            assert D.pick_from_table(rows) == F.pick_at(day, t)


def test_put_skew_fails_the_iv_ratio():
    day = synthetic_day(put_skew=1.3)
    rows = D.mark_table(day, F.MARKS[0])
    puts = [r for r in rows if r["side"] == "P" and "price" not in r["fails"] and "otm" not in r["fails"]]
    assert puts and all("iv" in r["fails"] for r in puts)


def test_explain_stops_at_the_first_pick_and_names_fails():
    day = synthetic_day(put_skew=1.3)
    out = D.explain(day, 21800.0, "P")
    assert 1 <= len(out) <= len(F.MARKS)
    assert out[-1]["pick"] is not None or len(out) == len(F.MARKS)
    assert all(r["option"] is None or isinstance(r["option"]["fails"], list) for r in out)


def test_hm():
    assert D.hm(F.MARKS[0]) == "11:00" and D.hm(F.MARKS[-1]) == "12:55" and D.hm(F.T_EXIT) == "15:04"


# ------------------------------------------------------------------------------------------------ real data (skipped without the lake)
HAVE_FT3 = Path(K.os.environ["R98_OUT"], "days_NIFTY.pkl").exists()


@pytest.mark.skipif(not HAVE_FT3, reason="R98 day pickles not built")
def test_real_mark_table_matches_ft3_app_on_nifty():
    days = R98.load("NIFTY")[:40]
    for day in days:
        sp, cache = F.L.spot(day), {}
        for t in F.MARKS[::4]:
            assert D.pick_from_table(D.mark_table(day, t, sp, {})) == F.pick_at(day, t, sp, cache)


@pytest.mark.skipif(not HAVE_FT3, reason="R98 day pickles not built")
def test_real_ft3_hold_equals_ft3_app():
    days = R98.load("MIDCPNIFTY")
    ts = L.ft3_tickets(days, exits=["HOLD"])
    ref = [r for r in F.run_und("MIDCPNIFTY") if r["app"] == r["app"]]
    assert len(ts) == len(ref)
    assert all(abs(a.nets["HOLD"] - b["app"]) < 1e-6 for a, b in zip(ts, ref))
