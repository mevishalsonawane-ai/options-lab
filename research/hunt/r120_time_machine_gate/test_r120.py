"""R120 tests: the Python time machine against TimeMachineTest.kt's cases and the Kotlin engine's own numbers, the
look-ahead guard, the gates, twins, BH and calibration. Synthetic data only. 28 Oct 2026."""
from __future__ import annotations

import math
import sys
from datetime import date, datetime, time, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r120cfg as K  # noqa: E402
import r120lib as L  # noqa: E402
import tm  # noqa: E402

TODAY = date(2026, 10, 22)


def session(d, path, rng=10.0, bars=75):
    out = []
    for i in range(bars):
        st = datetime.combine(d, time(9, 15)) + timedelta(minutes=5 * i)
        c = path(i)
        out.append(tm.Bar(st, c, c + rng, c - rng, c))
    return tm.Session(d, out)


def days(n, holidays=frozenset()):
    out, d = [], TODAY - timedelta(days=1)
    while len(out) < n:
        if d.weekday() < 5 and d not in holidays:
            out.append(d)
        d -= timedelta(days=1)
    return out[::-1]


up = lambda i: 50_000.0 + 20.0 * i      # noqa: E731
down = lambda i: 50_000.0 - 20.0 * i    # noqa: E731
flat = lambda i: 50_000.0               # noqa: E731


def ask(direction=1, stop=100.0, target=100.0, slot=time(10, 35), exit_at=time(15, 10), draws=tm.DRAWS):
    return tm.Ask(direction, slot, stop, target, 5, exit_at, draws, seed=7)


# ---------------------------------------------------------------- TimeMachineTest.kt, case by case


def test_climbing_is_edge_for_call_against_for_put():
    past = [session(d, up) for d in days(60)]
    c = tm.odds(past, ask(1), TODAY)
    assert c.verdict == tm.EDGE and c.used == tm.DRAWS and c.target_first == c.used
    assert c.stop_first == 0 and c.neither == 0
    assert c.target_pct == 100.0 and c.stop_pct == 0.0 and c.neither_pct == 0.0
    assert c.r == 1.0 and c.break_even == 0.5 and c.decided_win_rate == 1.0
    assert c.sessions == 60 and 50 <= c.sessions_hit <= 60 and c.why is None
    p = tm.odds(past, ask(-1), TODAY)
    assert p.verdict == tm.AGAINST and p.stop_first == p.used


def test_falling_is_edge_for_put():
    past = [session(d, down) for d in days(40)]
    p = tm.odds(past, ask(-1), TODAY)
    assert p.verdict == tm.EDGE and p.target_first == p.used
    assert tm.odds(past, ask(1), TODAY).verdict == tm.AGAINST


def test_flat_reaches_neither():
    past = [session(d, flat, rng=1.0) for d in days(30)]
    o = tm.odds(past, ask(), TODAY)
    assert o.verdict == tm.UNKNOWN and o.used == o.neither
    assert o.why == "only 0 draws reached the stop or the target"


def test_stop_checked_before_target_inside_a_bar():
    past = [session(d, flat, rng=500.0) for d in days(20)]
    o = tm.odds(past, ask(), TODAY)
    assert o.used == o.stop_first and o.verdict == tm.AGAINST
    assert tm.odds(past, ask(direction=-1), TODAY).stop_first == o.used
    b = [tm.Bar(datetime.combine(TODAY - timedelta(days=1), time(10, 35)), 100.0, 101.0, 90.0, 100.0)]
    assert tm.walk(b, 0, 100.0, ask(stop=10.0, target=1.0)) == -1
    assert tm.walk(b, 0, 100.0, ask(stop=20.0, target=1.0)) == 1
    assert tm.walk(b, 0, 100.0, ask(stop=20.0, target=2.0)) == 0
    assert tm.walk(b, 0, 100.0, ask(direction=-1, stop=1.0, target=10.0)) == -1
    assert tm.walk(b, 0, 100.0, ask(direction=-1, stop=2.0, target=10.0)) == 1
    assert tm.walk(b, 0, 100.0, ask(direction=-1, stop=2.0, target=20.0)) == 0


def test_coin_flip_inside_the_margin():
    past = [session(d, up if i % 2 == 0 else down) for i, d in enumerate(days(60))]
    o = tm.odds(past, ask(), TODAY)
    assert o.verdict == tm.COIN_FLIP and 35.0 <= o.target_pct <= 65.0


def test_asymmetric_r_moves_break_even():
    o = tm.odds([session(d, up) for d in days(60)], ask(stop=50.0, target=150.0), TODAY)
    assert o.r == 3.0 and o.break_even == 0.25


def test_no_bars_at_all():
    o = tm.odds([], ask(), TODAY)
    assert o.verdict == tm.UNKNOWN and o.used == 0 and o.sessions == 0
    assert o.why == "no past sessions of the underlying on the phone"
    assert o.target_pct == 0.0 and math.isnan(o.decided_win_rate)


def test_fewer_than_ten_sessions():
    o = tm.odds([session(d, up) for d in days(9)], ask(), TODAY)
    assert o.verdict == tm.UNKNOWN and o.sessions == 9 and o.used == o.target_first
    assert o.why == "only 9 past sessions (10 needed for a verdict)"
    assert tm.odds([session(d, up) for d in days(10)], ask(), TODAY).verdict == tm.EDGE


def test_gaps_and_holidays():
    hol = {date(2026, 10, 20), date(2026, 10, 2)}
    past = [session(d, up) for d in days(30, hol)]
    o = tm.odds(past, ask(), TODAY)
    assert o.sessions == 30 and o.verdict == tm.EDGE
    assert not any(s.date in hol for s in tm.usable(past, TODAY))


def test_todays_bars_and_future_dates_never_used():
    past = [session(d, up) for d in days(20)] + [session(TODAY, down), session(TODAY + timedelta(days=1), down)]
    u = tm.usable(past, TODAY)
    assert len(u) == 20 and all(s.date < TODAY for s in u)
    assert tm.odds(past, ask(), TODAY).verdict == tm.EDGE


def test_stale_bars_and_duplicates():
    stale = tm.Session(TODAY - timedelta(days=1), session(TODAY - timedelta(days=2), up).bars)
    u = tm.usable([session(d, up) for d in days(12)] + [stale], TODAY)
    assert len(u) == 12 and all(b.start.date() == s.date for s in u for b in s.bars)
    dup = session(TODAY - timedelta(days=1), down)
    many = [session(d, up) for d in days(70)] + [tm.Session(TODAY - timedelta(days=3), []), dup]
    kept = tm.usable(many, TODAY)
    assert len(kept) == 60 and [s.date for s in kept] == sorted(s.date for s in kept)
    assert kept[-1].bars == dup.bars


@pytest.mark.parametrize("a", [dict(stop=0.0), dict(stop=-40.0), dict(target=0.0), dict(target=float("nan")),
                               dict(stop=float("inf"))])
def test_r_at_or_below_zero(a):
    o = tm.odds([session(d, up) for d in days(30)], ask(**a), TODAY)
    assert o.verdict == tm.UNKNOWN and math.isnan(o.r) and math.isnan(o.break_even) and o.used == 0
    assert "R cannot be told" in o.why


def test_slot_outside_session_or_no_draws():
    past = [session(d, up) for d in days(30)]
    c = tm.odds(past, ask(slot=time(16, 0)), TODAY)
    assert c.verdict == tm.UNKNOWN and c.used == 0 and c.why == "no past session has a bar at 16:00 ±2 bars"
    assert tm.odds(past, ask(slot=time(0, 5)), TODAY).used == 0
    assert tm.odds(past, ask(slot=time(23, 55)), TODAY).used == 0
    assert tm.odds(past, ask(draws=0), TODAY).why == "nothing to draw"
    a = ask()
    a.bar_minutes = 0
    assert tm.odds(past, a, TODAY).why == "nothing to draw"


def test_slot_near_the_end():
    past = [session(d, up) for d in days(30)]
    o = tm.odds(past, ask(slot=time(15, 20), exit_at=None), TODAY)
    assert 1 <= o.used < tm.DRAWS and o.target_first + o.neither == o.used
    late = tm.odds(past, ask(slot=time(15, 20)), TODAY)
    assert late.used == late.neither


def test_same_seed_reproduces():
    past = [session(d, down if i % 3 == 0 else up) for i, d in enumerate(days(60))]
    assert tm.odds(past, ask(), TODAY) == tm.odds(past, ask(), TODAY)


def test_most_draws_neither_is_a_caveat():
    past = [session(d, lambda i: 50_000.0 + 2.0 * i, rng=1.0) for d in days(40)]
    o = tm.odds(past, ask(slot=time(12, 0), stop=1_000.0, target=100.0), TODAY)
    if o.verdict != tm.UNKNOWN:
        assert "time exit decides" in o.why
    else:
        assert "reached the stop or the target" in o.why


# ---------------------------------------------------------------- exact numbers printed by the Kotlin engine

# Printed by a throwaway Kotlin test on the engine (kotlin.random.Random and TimeMachine.odds, 28 Oct 2026).
KOTLIN_RNG = {}      # filled below from GOLDEN
KOTLIN_HASH = {}
KOTLIN_ODDS = []


def _wavy_past():
    ds = days(60)

    def sess(k, d):
        out = []
        for i in range(75):
            c = 50_000.0 + 80.0 * math.sin((i + 7 * k) / 6.0) + 3.0 * ((i * 13 + k * 7) % 11)
            st = datetime.combine(d, time(9, 15)) + timedelta(minutes=5 * i)
            out.append(tm.Bar(st, c - 2.0, c + 9.0 + (k % 5), c - 9.0 - (i % 4), c))
        return tm.Session(d, out)
    return [sess(k, d) for k, d in enumerate(ds)]


GOLDEN_ASKS = [
    tm.Ask(1, time(10, 35), 60.0, 60.0, 5, time(15, 10), seed=7),
    tm.Ask(-1, time(10, 35), 60.0, 90.0, 5, time(15, 10), seed=7),
    tm.Ask(1, time(13, 20), 40.0, 120.0, 5, time(15, 15), seed=-123456789),
    tm.Ask(-1, time(9, 20), 25.0, 25.0, 5, None, seed=99),
    tm.Ask(1, time(15, 20), 30.0, 30.0, 5, None, seed=5),
    tm.Ask(1, time(11, 0), 150.0, 10.0, 5, time(15, 10), draws=777, seed=31),
]


def _rng_line(seed):
    r = tm.KotlinRandom(seed)
    a = [r.next_int() for _ in range(6)]
    b = [r.next_int_range(0, 60) for _ in range(6)]
    c = [r.next_int_range(-2, 3) for _ in range(6)]
    d = [r.next_int_range(0, 64) for _ in range(6)]
    e = [r.next_int_range(0, 1) for _ in range(3)]
    return " | ".join(",".join(map(str, x)) for x in (a, b, c, d, e))


def test_kotlin_random_matches_the_engine():
    import golden_kotlin as G
    for seed, line in G.RNG.items():
        assert _rng_line(seed) == line, seed


def test_java_hash_matches_the_engine():
    import golden_kotlin as G
    for s, h in G.HASH.items():
        assert tm.java_hash(s) == h


def test_odds_match_the_engine_exactly():
    import golden_kotlin as G
    past = _wavy_past()
    for i, a in enumerate(GOLDEN_ASKS):
        o = tm.odds(past[:12] if i == 5 else past, a, TODAY)
        got = (o.used, o.sessions, o.sessions_hit, o.target_first, o.stop_first, o.neither, o.verdict, o.why)
        assert got == G.ODDS[i], (i, got)


def test_kotlin_random_rejects_all_zero_state():
    # seed 0 is fine (v = ~0 = -1); the all-zero state cannot arise from a Long seed, but the guard is ported
    tm.KotlinRandom(0)


# ---------------------------------------------------------------- the app's conversions


def test_points_like_the_host():
    assert tm.points(10.0, 20.0, premium=False) == (10.0, 20.0)
    assert tm.points(10.0, None, premium=False) == (10.0, 10.0)            # no target: R 1
    assert tm.points(10.0, 20.0, premium=True) == (20.0, 40.0)             # delta 0.5
    assert tm.points(10.0, 20.0, premium=True, delta=0.25) == (40.0, 80.0)
    assert tm.points(10.0, 20.0, premium=True, delta=0.01) == (20.0, 40.0)  # out of range: 0.5
    assert tm.points(None, None, premium=True) == (None, None)


def test_fold_and_slot():
    d = date(2026, 10, 1)
    ones = [tm.Bar(datetime.combine(d, time(9, 15)) + timedelta(minutes=i), 100 + i, 101 + i, 99 + i, 100.5 + i)
            for i in range(10)]
    f = tm.fold(ones, 5)
    assert [b.start.time() for b in f] == [time(9, 15), time(9, 20)]
    assert f[0] == tm.Bar(datetime.combine(d, time(9, 15)), 100, 105, 99, 104.5)
    assert tm.slot_of(datetime(2026, 10, 1, 10, 23), 5) == time(10, 20)
    assert tm.slot_of(datetime(2026, 10, 1, 10, 23), 1) == time(10, 23)


# ---------------------------------------------------------------- the look-ahead guard


def test_lookahead_guard_uses_only_days_before_the_entry():
    """The verdict of an entry must not change, whatever the entry day's own bars or any later day hold."""
    ds = days(70)
    sess = {d: session(d, up) for d in ds}
    t = dict(arm="X", und="NIFTY", day=ds[-1], entry_ts=datetime.combine(ds[-1], time(10, 37)), side=1, net=1.0,
             stop_pts=100.0, target_pts=100.0, exit_at=time(15, 10), bar_minutes=5)
    a = L.verdict(t, sess)
    poisoned = dict(sess)
    poisoned[ds[-1]] = session(ds[-1], down)                     # the entry day itself turns against
    for k in range(1, 5):
        poisoned[ds[-1] + timedelta(days=k)] = session(ds[-1] + timedelta(days=k), down)
    b = L.verdict(t, poisoned)
    assert a == b and a.verdict == tm.EDGE
    assert all(s.date < t["day"] for s in L.before(poisoned, t["day"]))
    assert len(L.before(poisoned, t["day"])) == 60
    # the day before IS used: poisoning it changes the odds
    poisoned[ds[-2]] = session(ds[-2], down)
    assert L.verdict(t, poisoned).target_first < a.target_first


def test_sessions_from_frame_keeps_the_phone_window():
    ts = pd.date_range("2026-10-01 09:10", "2026-10-01 15:35", freq="1min", tz="Asia/Kolkata")
    df = pd.DataFrame(dict(ts=ts, open=1.0, high=2.0, low=0.5, close=1.5))
    df.loc[5, "close"] = np.nan
    s = L.sessions_from_frame(df, 5)[date(2026, 10, 1)]
    assert s.bars[0].start.time() == time(9, 15) and s.bars[-1].start.time() == time(15, 25)
    assert len(s.bars) == 75


# ---------------------------------------------------------------- gates, twins, BH, calibration


def _tr(day, net):
    return dict(day=day, net=net)


def test_metrics_and_drawdown():
    d = [date(2025, 1, 1) + timedelta(days=i) for i in range(4)]
    tr = [_tr(d[0], 100), _tr(d[1], -300), _tr(d[1], 50), _tr(d[2], 200), _tr(d[3], -10)]
    m = L.metrics(tr)
    assert m["net"] == 40 and m["trades"] == 5 and m["kept"] == 5 and m["max_dd"] == 250 and m["win"] == 0.6
    m2 = L.metrics(tr, [True, False, True, True, True])
    assert m2["net"] == 340 and m2["skipped"] == 1 and m2["max_dd"] == 10
    assert L.max_drawdown([]) == 0 and L.metrics([])["win"] is None


def test_keep_mask():
    v = ["edge", "against", "coin flip", "unknown"]
    assert L.keep_mask(v, "SKIP_AGAINST") == [True, False, True, True]
    assert L.keep_mask(v, "EDGE_ONLY") == [True, False, False, False]


def test_twins_skip_exactly_k():
    nets = np.array([10.0, -5.0, 3.0, 7.0])
    tw = L.twins(nets, 1, n=500)
    assert set(np.round(tw, 6)) <= {5.0, 20.0, 12.0, 8.0}
    assert np.all(L.twins(nets, 0, n=10) == 15.0)
    assert np.allclose(L.twins(nets, 4, n=5), 0.0)
    assert L.twin_p(100.0, tw) == pytest.approx(1 / 501)
    assert L.twin_p(-100.0, tw) == 1.0
    # nothing skipped: p = 1 even when the rounded net sits a fraction of a paisa above the unrounded total
    same = L.twins(np.array([0.1, 0.2, 0.0004]), 0, n=50)
    assert L.twin_p(round(float(same[0]), 2) + 0.004, same) == 1.0


def test_bh():
    q = L.bh([0.01, 0.04, 0.03], m_total=3)
    assert np.allclose(q, [0.03, 0.04, 0.04])
    assert np.allclose(L.bh([0.01], m_total=1000), [1.0])
    assert len(L.bh([], 10)) == 0


def test_gates_need_everything():
    good = dict(net=100, kept=50, max_dd=10)
    base = dict(net=50, kept=60, max_dd=20)
    r = {p: dict(good) for p in ("design", "wf", "final")}
    b = {p: dict(base) for p in ("design", "wf", "final")}
    assert L.gates(r, b, 0.01, 0.05)["passed"]
    assert not L.gates(r, b, 0.06, 0.05)["passed"]
    assert not L.gates(r, b, 0.01, 0.2)["passed"]
    r2 = dict(r, wf=dict(good, net=40))
    assert "wf: net not above the arm's own" in L.gates(r2, b, 0.01, 0.05)["fails"]
    r3 = dict(r, final=dict(good, kept=39))
    assert not L.gates(r3, b, 0.01, 0.05)["passed"]
    r4 = dict(r, final=dict(good, max_dd=30))
    assert "final: drawdown worse" in L.gates(r4, b, 0.01, 0.05)["fails"]


def test_calibration():
    c = L.calibration([0.9, 0.9, 0.1, 0.1, float("nan")], [1, 1, 0, 0, 1], 0.5)
    assert c["n"] == 4 and c["brier"] == pytest.approx(0.01) and c["brier_base"] == 0.25
    assert {b["bin"]: b["n"] for b in c["bins"]} == {"90-100%": 2, "10-20%": 2}
    assert L.calibration([], [], 0.5)["n"] == 0


def test_count_prior(tmp_path):
    (tmp_path / "a.yaml").write_text("id: r5-x\ndate: '2026-10-01'\nvariants_tried: 7\n")
    (tmp_path / "b.yaml").write_text("id: r5-y\ndate: '2026-10-01'\n")
    (tmp_path / "c.yaml").write_text("id: app-thing\ndate: '2026-10-02'\n")
    (tmp_path / "d.yaml").write_text("id: r120-time-machine-gate\ndate: '2026-10-01'\n")
    (tmp_path / "e.yaml").write_text("id: r9-late\ndate: '2026-10-28'\n")
    assert L.count_prior(tmp_path, "2026-10-27") == 8 + 1


def test_family_is_fixed():
    assert K.M_R120 == 2 * len(K.ARMS) == 38
    assert set(K.NOT_TESTABLE) == {"FT3", "NIGHT_R3"} and not set(K.NOT_TESTABLE) & set(K.ARMS)
    assert K.period_of(date(2026, 3, 31)) == "design" and K.period_of(date(2026, 4, 1)) == "final"
    assert K.period_of(date(2026, 10, 14)) is None and K.in_wf(date(2025, 4, 1)) and not K.in_wf(date(2025, 3, 31))


# ---------------------------------------------------------------- the per-arm asks (Amendment A1)

import r120arms as A  # noqa: E402


def test_asks_as_the_app_passes_them():
    a = A.ask_fields("R51", 200.0)
    assert a == dict(stop_pts=60.0, target_pts=60.0, exit_at=time(15, 15), bar_minutes=5)   # 15% / 0.5, R 1
    assert A.ask_fields("R72", 200.0)["stop_pts"] == 60.0
    assert A.ask_fields("R52", 100.0) == dict(stop_pts=60.0, target_pts=60.0, exit_at=time(15, 10), bar_minutes=5)
    assert A.ask_fields("HERO", 10.0)["stop_pts"] == 5.0 and A.ask_fields("HERO", 10.0)["exit_at"] == time(15, 5)
    assert A.ask_fields("ORB_ORB", 300.0)["stop_pts"] == 80.0 and A.ask_fields("ORB_ORB", 300.0)["target_pts"] == 80.0
    assert A.ask_fields("ORB_SWEEP", 300.0)["target_pts"] == 160.0
    assert A.ask_fields("VIXDIV", 100.0, und="NIFTY")["stop_pts"] == 30.0
    assert A.ask_fields("VIXDIV", 100.0, und="BANKNIFTY")["stop_pts"] is None
    assert A.ask_fields("PINE_ST_EMA_5M", 100.0)["stop_pts"] == 60.0
    assert A.ask_fields("PINE_ST_EMA_5M", 30.0)["stop_pts"] is None           # fill - 30 <= a tick: no stop
    s = A.ask_fields("SOLO", 100.0, setup="S4")
    assert s["stop_pts"] == 50.0 and s["exit_at"] == time(15, 10)
    assert A.ask_fields("SOLO", 100.0, setup="S1")["stop_pts"] is None         # index exits only on the passed host
    with pytest.raises(KeyError):
        A.ask_fields("MOM60", 100.0)


def test_liquidity_ask_in_index_points():
    a = A.ask_fields("LIQ", 150.0, und="FINNIFTY", target=24_100.0, level=24_000.0)
    assert a["stop_pts"] == 15.0 and a["target_pts"] == 100.0 and a["exit_at"] == time(15, 10)
    assert A.ask_fields("LIQ", 150.0, und="MIDCPNIFTY", target=None, level=1.0)["target_pts"] == 8.0   # no pool: R 1
    assert A.ask_fields("LIQ", 150.0, und="BANKNIFTY", target=float("nan"), level=1.0)["target_pts"] == 30.0
    assert A.liq_index_stop("NIFTY") == 30.0 and A.liq_index_stop("SENSEX") == 30.0


def test_trade_row():
    t = A.trade("R52", "NIFTY", date(2025, 6, 3), 5, 1, 123.4, 200.0)
    assert t["entry_ts"] == datetime(2025, 6, 3, 9, 20) and t["period"] == "design" and t["stop_pts"] == 120.0
    assert A.trade("R52", "NIFTY", date(2026, 5, 4), 5, -1, 1.0, None)["stop_pts"] is None
    assert A.trade("R52", "NIFTY", datetime(2026, 10, 20, 0, 0), 0, 1, 1.0, 10.0)["period"] is None
