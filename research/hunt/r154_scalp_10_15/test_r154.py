"""R154 tests (PREREG section 8): entries, look-ahead guard, index-trigger exits, the engine on a synthetic contract,
the plain walk, statistics, BH, twins, the walk-forward pick, gates and periods.

    python -m pytest research/hunt/r154_scalp_10_15/test_r154.py -q
"""
from __future__ import annotations

import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r154cfg as K  # noqa: E402
import r154signals as SG  # noqa: E402
import r154study as S  # noqa: E402


def flat_day(level: float = 100.0) -> np.ndarray:
    idx = np.full((4, K.NROWS), level, float)
    return idx


def set_bar(idx, r, o, h, lo, c):
    idx[:, r] = (o, h, lo, c)


def walk_day(seed: int, start: float = 20000.0, vol: float = 8.0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(0, vol, K.NROWS))
    o = np.concatenate([[start], c[:-1]])
    h = np.maximum(o, c) + rng.uniform(0, vol, K.NROWS)
    lo = np.minimum(o, c) - rng.uniform(0, vol, K.NROWS)
    idx = np.vstack([o, h, lo, c])
    gaps = rng.choice(K.NROWS, size=6, replace=False)          # a few minutes with no print
    idx[:, gaps] = np.nan
    return idx


# ================================================================================================ config
def test_rules_and_tests_count():
    assert len(K.RULE_IDS) == 24
    assert "A10s10t15" in K.RULES and "B15s22.5t30" in K.RULES and "A12s18t30" in K.RULES
    assert K.RULES["B15s22.5t30"] == dict(reading="B", target=15.0, stop=22.5, tmax=30)
    assert len(K.TESTS) == 33 * 24 == 792


def test_periods():
    assert K.period_of(date(2021, 7, 30)) is None
    assert K.period_of(date(2021, 8, 2)) == "P1"
    assert K.period_of(date(2025, 3, 31)) == "P1"
    assert K.period_of(date(2025, 4, 1)) == "P2"
    assert K.period_of(date(2026, 3, 31)) == "P2"
    assert K.period_of(date(2026, 4, 1)) == "P3"
    assert K.period_of(date(2026, 10, 13)) == "P3"
    assert K.period_of(date(2026, 10, 14)) == "P4"
    assert K.period_of(date(2026, 10, 20)) is None


def test_rows():
    assert K.row(9, 15) == 0 and K.SQ == K.row(15, 15) == 360 and K.SQ_EXPIRY == 350 and K.LAST_ENTRY == 330


# ================================================================================================ signals
def test_bars5_last_printed_close_and_missing_bar():
    idx = flat_day()
    idx[3, 4] = np.nan
    idx[3, 3] = 101.0
    idx[:, 5:10] = np.nan
    ends, c5, h5, l5 = SG.bars5(idx)
    assert ends[0] == 4 and ends[1] == 9
    assert c5[0] == 101.0
    assert np.isnan(c5[1]) and np.isnan(h5[1]) and np.isnan(l5[1])


def test_e1_or15_breaks_once_per_side():
    idx = flat_day()
    idx[1, :15] = 102.0
    idx[2, :15] = 98.0
    set_bar(idx, 20, 100, 103, 100, 102.5)        # close above 102 -> call at row 21
    set_bar(idx, 25, 100, 104, 100, 103.0)        # second call break: ignored
    set_bar(idx, 40, 99, 99, 97, 97.5)            # close below 98 -> put at row 41
    assert SG.e1_or15(idx) == [(21, "C"), (41, "P")]


def test_e1_needs_range_and_respects_cutoff():
    idx = flat_day()
    idx[:, :10] = np.nan
    assert SG.e1_or15(idx) == []
    idx = flat_day()
    idx[1, :15], idx[2, :15] = 102.0, 98.0
    set_bar(idx, K.LAST_ENTRY + 2, 100, 103, 100, 103)     # break after 14:45 -> no trade
    assert SG.e1_or15(idx) == []


def test_e2_twap_cross():
    idx = flat_day()
    idx[:, :40] = 100.0
    idx[1:, 35:40] = 99.0                          # bar ending 39 closes below the average
    idx[0, 40:45], idx[1, 40:45], idx[2, 40:45], idx[3, 40:45] = 100, 102, 100, 101.5   # bar ending 44 closes above
    sig = SG.e2_twapx(idx)
    assert sig[:3] == [(40, "P"), (45, "C"), (50, "P")]      # down through it, back above, then below again


def test_e3_ema_cross_and_state():
    idx = flat_day(100.0)
    idx[:, 200:] = 110.0                           # a jump: EMA9 crosses above EMA21 on the bar ending 204
    sig, st = SG.e3_ema921(idx, {"e9": 100.0, "e21": 100.0})
    assert sig[0] == (205, "C")
    assert st["e9"] > st["e21"]


def test_e4_trend_filter():
    idx = flat_day(100.0)
    idx[3] = 100.0 + 0.1 * np.arange(K.NROWS)       # rising closes: price above a rising EMA21
    idx[0] = idx[3] - 0.05
    idx[1] = idx[3] + 0.02
    idx[2] = idx[3] - 0.1
    sig, _ = SG.e4_pbb(idx, {"e9": 90.0, "e21": 90.0})
    assert sig and all(s == "C" for _, s in sig)
    assert all(K.FIRST_ENTRY <= er <= K.LAST_ENTRY for er, _ in sig)


def test_random_entries_seeded():
    a = SG.random_entries("NIFTY", date(2025, 5, 5))
    b = SG.random_entries("NIFTY", date(2025, 5, 5))
    c = SG.random_entries("BANKNIFTY", date(2025, 5, 5))
    assert a == b and a != c and len(a) == 5
    assert all(K.FIRST_ENTRY <= r <= K.LAST_ENTRY for r, _ in a)


# ------------------------------------------------------------------------------------------------ look-ahead guard
@dataclass
class _D:
    d: date
    idx: np.ndarray
    atm: np.ndarray | None = None
    con: dict = field(default_factory=dict)
    expiry: bool = False


def _day(i: int) -> date:
    return date.fromordinal(date(2025, 1, 1).toordinal() + i)


@pytest.mark.parametrize("entry", ["E1", "E2", "E3", "E4"])
def test_lookahead_guard(entry):
    """Signals decided at or before minute s are unchanged when every bar after s (of the last session) is erased."""
    days = [(_day(i), _D(_day(i), walk_day(i))) for i in range(K.WARMUP_SESSIONS + 3)]
    full = SG.plain_signals(entry, days)
    last = days[-1][0]
    for s in (20, 64, 150, 233, 300, 331):
        cut = [(d, _D(d, D.idx.copy())) for d, D in days]
        cut[-1][1].idx[:, s + 1:] = np.nan
        part = SG.plain_signals(entry, cut)
        want = [x for x in full.get(last, []) if x[0] - 1 <= s]
        got = [x for x in part.get(last, []) if x[0] - 1 <= s]
        assert got == want, (entry, s)
        for d, _ in days[:-1]:
            assert full.get(d) == part.get(d)


def test_warmup_sessions_have_no_ema_signal():
    days = [(_day(i), _D(_day(i), walk_day(i))) for i in range(K.WARMUP_SESSIONS)]
    assert SG.plain_signals("E3", days) == {} and SG.plain_signals("E4", days) == {}


# ================================================================================================ engine
def _engine():
    try:
        import r154engine as Y
        Y.DATA.mods()
    except Exception as e:                       # the arm modules need the repo's research tree only (no data)
        pytest.skip(f"engine modules unavailable: {e}")
    return Y


def test_index_trigger_target_stop_and_same_minute():
    Y = _engine()
    idx = flat_day(1000.0)
    idx[1, 12] = 1011.0                                       # +11 at row 12
    m, kind, ref = Y.index_trigger(idx, 10, 39, 360, 1, 10.0, 10.0)
    assert (m, kind, ref) == (12, 3, 1000.0)
    m, kind, _ = Y.index_trigger(idx, 10, 39, 360, -1, 10.0, 10.0)   # a put: +11 is its stop
    assert (m, kind) == (12, 1)
    idx[2, 12] = 989.0                                        # both in one minute -> stop first
    m, kind, _ = Y.index_trigger(idx, 10, 39, 360, 1, 10.0, 10.0)
    assert (m, kind) == (12, 1)
    m, kind, _ = Y.index_trigger(idx, 13, 39, 360, 1, 10.0, 10.0)   # after the window's start only
    assert kind == 0 and m == -1
    m, kind, _ = Y.index_trigger(idx, 10, 11, 360, 1, 10.0, 10.0)   # outside the time window
    assert kind == 0


def test_index_trigger_missing_open_uses_previous_close():
    Y = _engine()
    idx = flat_day(1000.0)
    idx[0, 10] = np.nan
    idx[3, 9] = 995.0
    m, kind, ref = Y.index_trigger(idx, 10, 39, 360, 1, 10.0, 10.0)
    assert ref == 995.0 and kind == 0                       # the flat 1000 high is only +5 from 995
    idx[1, 12] = 1006.0                                      # +11 from 995
    m, kind, ref = Y.index_trigger(idx, 10, 39, 360, 1, 10.0, 10.0)
    assert (m, kind) == (12, 3)


def _contract(path_close: np.ndarray, vol: float = 1e6) -> np.ndarray:
    c = np.array(path_close, float)
    o = np.concatenate([[c[0]], c[:-1]])
    C = np.vstack([o, np.maximum(o, c), np.minimum(o, c), c, np.full(len(c), vol)])
    return C


def _synthetic_day(C: np.ndarray, idx: np.ndarray | None = None, d=date(2025, 6, 10), expiry=False):
    import r51data
    D = r51data.Day(d, idx if idx is not None else flat_day(25000.0))
    D.atm = np.full(K.NROWS, 25000.0)
    D.con = {("C", 24950.0): C.astype(np.float32)}
    D.expiry = expiry
    return D


@pytest.mark.parametrize("path,why", [("up", "target"), ("down", "stop"), ("flat", "time")])
def test_engine_reading_a(path, why):
    Y = _engine()
    p = np.full(K.NROWS, 100.0)
    if path == "up":
        p[25:] = 125.0
    elif path == "down":
        p[25:] = 80.0
    D = _synthetic_day(_contract(p), d=date(2031, 1, 1 + ["up", "down", "flat"].index(path)))
    t = Y.price("NIFTY", D, 20, "C", K.RULES["A10s10t15"], 1, strike=24950.0)
    assert t is not None and t["why"] == why
    if why == "target":
        assert t["rX"] == pytest.approx(Y.tick_up(t["rE"] + 10.0))
        assert t["xr"] == 25
    if why == "stop":
        assert t["rX"] < t["rE"] - 10.0 + 0.5            # the gap fills at the open with R17's stop slippage
    if why == "time":
        assert t["xr"] == t["er"] + 15 - 1
    assert t["charges"] > 0 and t["net"] == pytest.approx((t["rX"] - t["rE"]) * t["qty"] - t["charges"])


def test_engine_reading_b_and_squareoff():
    Y = _engine()
    p = np.full(K.NROWS, 100.0)
    idx = flat_day(25000.0)
    idx[1, 30] = 25011.0                                       # index +11 at row 30 -> sell at the open of 31
    D = _synthetic_day(_contract(p), idx, d=date(2031, 2, 1))
    t = Y.price("NIFTY", D, 20, "C", K.RULES["B10s10t15"], 1, strike=24950.0)
    assert t["why"] == "target" and t["xr"] == 31
    D2 = _synthetic_day(_contract(p), flat_day(25000.0), d=date(2031, 2, 2), expiry=True)
    t2 = Y.price("NIFTY", D2, 345, "C", K.RULES["B10s10t30"], 1, strike=24950.0)
    assert t2["why"] == "squareoff" and t2["xr"] == K.SQ_EXPIRY
    assert Y.price("NIFTY", D2, K.SQ_EXPIRY, "C", K.RULES["B10s10t30"], 1, strike=24950.0) is None


def test_engine_lots_scale_qty_and_twin_window():
    Y = _engine()
    p = np.full(K.NROWS, 100.0)
    p[60:] = 103.0
    D = _synthetic_day(_contract(p), d=date(2031, 3, 1))
    t1 = Y.price("NIFTY", D, 20, "C", K.RULES["A15s15t30"], 1, strike=24950.0)
    t3 = Y.price("NIFTY", D, 20, "C", K.RULES["A15s15t30"], 3, strike=24950.0)
    assert t3["qty"] == 3 * t1["qty"]
    assert t3["charges"] < 3 * t1["charges"]                   # brokerage is per order, not per lot
    tw = Y.twin_nets(t1, D)
    assert len(tw) == 30                                       # rows fill+1 .. fill+30


# ================================================================================================ walk, stats, BH
def test_walk_plain_one_open_and_day_cap():
    calls = []

    def pf(er, side):
        calls.append(er)
        return dict(xr=er + 10, net=1.0)
    out = S.walk_plain([(20, "C"), (25, "P"), (31, "C"), (41, "C"), (52, "P"), (63, "C"), (74, "C"), (90, "C")], pf)
    assert [t["xr"] - 10 for t in out] == [20, 31, 52, 63, 74]        # 41 = the open trade's exit minute: skipped
    assert 25 not in calls and 41 not in calls and 90 not in calls


def test_walk_plain_skips_unpriced():
    out = S.walk_plain([(20, "C"), (21, "C")], lambda er, s: None if er == 20 else dict(xr=30, net=0.0))
    assert len(out) == 1


def _arr(days, nets, why=None):
    n = len(nets)
    return dict(day=np.array([d.toordinal() for d in days], np.int64), net=np.array(nets, float),
                charges=np.full(n, 50.0), why=np.array(why or [0] * n, np.int8), und=np.zeros(n, np.int8))


def test_stats_breakeven_drawdown_and_top5():
    d1, d2, d3 = date(2025, 1, 1), date(2025, 1, 2), date(2025, 1, 3)
    a = _arr([d1, d1, d2, d3], [300, -100, -400, 100], [0, 1, 1, 2])
    s = S.stats(a, sessions=4)
    assert s["trades"] == 4 and s["win"] == 0.5 and s["net"] == -100
    assert s["be_win"] == pytest.approx(250 / (200 + 250), abs=1e-4)
    assert s["worst_day"] == -400 and s["max_dd"] == 400 and s["per_session"] == -25
    assert s["exits"] == {"target": 1, "stop": 2, "time": 1}
    assert s["ex_top5"] == -100 - (300 + 100 - 100 - 400) and s["avg_charges"] == 50
    assert S.stats(_arr([], []))["trades"] == 0


def test_bh_counts_prior_tests():
    q = S.bh([0.001, 0.5], m_total=1000)
    assert q[0] == pytest.approx(1.0) and q[1] == 1.0
    q = S.bh([0.01, 0.02, 0.5])
    assert q[0] == pytest.approx(0.03) and q[1] == pytest.approx(0.03) and q[2] == pytest.approx(0.5)


def test_twin_totals_and_p():
    rng = np.random.default_rng(1)
    tot = S.twin_totals([np.array([1.0, 1.0]), np.array([5.0])], 100, rng)
    assert np.all(tot == 6.0)
    assert S.twin_p(6.0, tot) == 1.0
    assert S.twin_p(7.0, tot) == pytest.approx(1 / 101)


def test_wf_pick_uses_only_earlier_days():
    a = {"X": _arr([date(2025, 1, 1), date(2025, 5, 1)], [100, -50]),
         "Y": _arr([date(2025, 1, 1), date(2025, 5, 1)], [50, 500])}
    out = S.wf_pick(a, [(date(2025, 4, 1), date(2025, 6, 30))], date(2024, 1, 1))
    assert out["windows"][0]["rule"] == "X" and out["net"] == -50


def test_gates():
    ok = dict(net=10, trades=50)
    assert S.gates(ok, ok, ok, 0.05)["passed"]
    g = S.gates(ok, dict(net=-1, trades=50), ok, 0.5)
    assert not g["passed"] and len(g["fails"]) == 2
    assert "final trades" in S.gates(ok, ok, dict(net=10, trades=39), 0.01)["fails"][0]
    g = S.gates(ok, ok, ok, 0.01, b0_p2=dict(net=20), b0_p3=dict(net=5))
    assert not g["passed"] and "own exits" in g["fails"][0]


def test_compact_and_ranges():
    t = [dict(day=date(2025, 1, 1), net=5.0, charges=40.0, why="target", und="SENSEX"),
         dict(day=date(2025, 2, 1), net=-5.0, charges=40.0, why="squareoff", und="NIFTY")]
    a = S.compact(t)
    assert list(a["why"]) == [0, 3] and list(a["und"]) == [4, 0]
    assert len(S.in_range(a, date(2025, 1, 15), date(2025, 3, 1))["net"]) == 1
    assert len(S.concat([a, a])["net"]) == 4 and len(S.concat([])["net"]) == 0
