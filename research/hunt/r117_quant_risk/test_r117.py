"""R117 tests: the six risk rules on top of R108's book, their day inputs, the look-ahead guard, Part A's measures,
twins and gates. Synthetic data only (no lake)."""
from __future__ import annotations

import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r117cfg as K  # noqa: E402,F401  (puts R108 on the path)
import r108book as B  # noqa: E402
import r117lib as L  # noqa: E402

D0 = date(2025, 6, 2)   # a Monday


def cand(arm, und, side, er, g, j=0, strike=100.0, book=None, risk=1000.0, how=None):
    how = how or {"LIQ": "book", "R72": "side2", "R51": "side2", "R52": "one"}[arm]
    return dict(arm=arm, und=und, side=side, er=er, g=g, j=j, how=how, strike=strike, book=book, risk=risk)


def res(er, xr, net=100.0):
    return dict(er=er, xr=xr, net=net, E=100.0, qty=50.0)


def outs(recs):
    return [(x["c"]["arm"], x["c"]["und"], x["c"]["er"], x["out"]) for x in recs]


def days(n, start=D0):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def rand_day(rng, n=30):
    arms = ["LIQ", "R72", "R51", "R52"]
    cs, ps = [], []
    for g in range(n):
        arm = arms[g % 4]
        er = int(rng.integers(5, 300))
        er = 5 if arm == "R52" else er
        cs.append(cand(arm, B.UNDS[g % 5], "CP"[g % 2], er, g, strike=float(g % 3), book=15 if arm == "LIQ" else None,
                       risk=float(rng.integers(500, 4000))))
        ps.append(res(er, er + int(rng.integers(1, 60)), float(rng.normal(0, 2000))))
    return cs, ps


# ------------------------------------------------------------------------------------------------ CAP2 parity
@pytest.mark.parametrize("seed", range(12))
def test_cap2_is_exactly_r108s_cap2(seed):
    rng = np.random.default_rng(seed)
    cs, ps = rand_day(rng)
    a = B.walk_day(cs, ps, B.make_rule("CAP2"), day=D0)
    b = B.walk_day(cs, ps, L.make_rule("CAP2"), {"week": 0.0, "month": 0.0}, D0)
    assert outs(a) == outs(b)


def test_cap2_walk_period_equals_r108s():
    rng = np.random.default_rng(3)
    ds = days(15)
    dc = {d: rand_day(rng, 20) for d in ds}
    a = B.walk_period(ds, dc, B.make_rule("CAP2"))
    b = L.walk_period(ds, dc, "CAP2")
    assert all(outs(a[d]) == outs(b[d]) for d in ds)


# ------------------------------------------------------------------------------------------------ cap 1 days
def test_cap1_day_refuses_the_second_same_way_and_keeps_r72s_priority():
    # two R72 puts on different indices at 09:20: CAP2 takes both; a cap-1 day takes only the first
    cs = [cand("R72", "NIFTY", "P", 5, 1, strike=1), cand("R72", "BANKNIFTY", "P", 5, 2, strike=2)]
    ps = [res(5, 40), res(5, 40)]
    r = L.make_rule("VIXCAP")
    full = B.walk_day(cs, ps, r, {"week": 0.0, "month": 0.0, "cap1": False}, D0)
    one = B.walk_day(cs, ps, r, {"week": 0.0, "month": 0.0, "cap1": True, "cap1_why": "VIX high"}, D0)
    assert [x["out"] for x in full] == [B.REAL, B.REAL]
    assert [x["out"] for x in one] == [B.REAL, B.WB_RULE]
    assert one[1]["why"].startswith("cap 1 today")
    # Liquidity inside R72's window on a cap-1 day: the last (only) place is kept for R72
    cs = [cand("LIQ", "BANKNIFTY", "C", 5, 1, book=15), cand("R72", "NIFTY", "C", 5, 2, strike=1)]
    o = B.walk_day(cs, [res(5, 40), res(5, 40)], r, {"week": 0.0, "month": 0.0, "cap1": True}, D0)
    assert [x["out"] for x in o] == [B.DROP, B.REAL]


def test_cap1_never_takes_more_than_cap2():
    rng = np.random.default_rng(7)
    for _ in range(40):
        cs, ps = rand_day(rng, 25)
        a = B.walk_day(cs, ps, L.make_rule("CAP2"), {"week": 0.0, "month": 0.0}, D0)
        b = B.walk_day(cs, ps, L.make_rule("COMBO"), {"week": 0.0, "month": 0.0, "cap1": True}, D0)
        assert sum(x["out"] == B.REAL for x in b) <= sum(x["out"] == B.REAL for x in a)


def test_the_flags_only_change_their_rules():
    rng = np.random.default_rng(11)
    ds = days(12)
    dc = {d: rand_day(rng, 20) for d in ds}
    high = {d: True for d in ds}
    base = L.walk_period(ds, dc, "CAP2")
    # VIX high every day does not touch RVCAP / DAYLOCK; realised volatility does not touch VIXCAP
    for name, kw in (("RVCAP", dict(vix_high=high)), ("VIXCAP", dict(rv_high=high)), ("DAYLOCK", dict(vix_high=high))):
        w = L.walk_period(ds, dc, name, **kw)
        if name != "DAYLOCK":
            assert all(outs(w[d]) == outs(base[d]) for d in ds), name


# ------------------------------------------------------------------------------------------------ EQ10, ARMEQ, DAYLOCK
def test_eq10_turns_on_after_a_losing_fortnight_and_needs_ten_sessions():
    ds = days(14)
    # one R72 call a day, losing Rs 1,000 each: from day 11 the book's last 10 sessions are below 0
    dc = {d: ([cand("R72", "NIFTY", "C", 5, 1, strike=1), cand("R72", "BANKNIFTY", "C", 5, 2, strike=2)],
              [res(5, 30, -500.0), res(5, 30, -500.0)]) for d in ds}
    w = L.walk_period(ds, dc, "EQ10")
    taken = [sum(x["out"] == B.REAL for x in w[d]) for d in ds]
    assert taken[:10] == [2] * 10 and taken[10:] == [1] * 4


def test_armeq_pauses_only_the_losing_arm_from_earlier_days():
    ds = days(3)
    alone = {"R72": [dict(day=ds[0], net=-100.0)], "R51": [dict(day=ds[0], net=50.0)], "LIQ": [], "R52": []}
    p = L.arm_paused(alone, ds, 20)
    assert p[ds[0]] == set()                # nothing closed before the first day
    assert p[ds[1]] == {"R72"} and p[ds[2]] == {"R72"}
    # same-day trades never count for that day
    alone["R51"].append(dict(day=ds[1], net=-1000.0))
    p = L.arm_paused(alone, ds, 20)
    assert p[ds[1]] == {"R72"} and p[ds[2]] == {"R72", "R51"}
    # the window: only the last 20 count
    alone = {"R72": [dict(day=ds[0], net=-10_000.0)] + [dict(day=ds[0], net=10.0)] * 20}
    assert L.arm_paused(alone, ds, 20)[ds[1]] == set()


def test_armeq_refuses_a_paused_arms_entries():
    cs = [cand("R72", "NIFTY", "C", 5, 1, strike=1), cand("R51", "BANKNIFTY", "P", 9, 2, strike=2)]
    o = B.walk_day(cs, [res(5, 30), res(9, 30)], L.make_rule("ARMEQ"), {"week": 0.0, "month": 0.0, "paused": {"R72"}}, D0)
    assert [x["out"] for x in o] == [B.WB_RULE, B.REAL]


def test_daylock_stops_new_entries_after_a_good_closed_day():
    cs = [cand("R72", "NIFTY", "C", 5, 1, strike=1), cand("R51", "BANKNIFTY", "P", 60, 2, strike=2),
          cand("LIQ", "FINNIFTY", "P", 20, 3, book=30)]
    ps = [res(5, 30, 7_000.0), res(60, 90), res(20, 40)]
    o = {x["c"]["arm"]: x["out"] for x in B.walk_day(cs, ps, L.make_rule("DAYLOCK"), {"week": 0.0, "month": 0.0}, D0)}
    # Liquidity at row 20 (R72 still open) goes; R51 at row 60, after R72 closed +7,000, is refused
    assert o == {"R72": B.REAL, "LIQ": B.REAL, "R51": B.WB_RULE}


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("name", ["CAP2", *K.TESTS])
def test_look_ahead_guard_later_results_never_change_earlier_decisions(name):
    rng = np.random.default_rng(5)
    ds = days(16)
    dc = {d: rand_day(rng, 20) for d in ds}
    flags = {d: bool(i % 3 == 0) for i, d in enumerate(ds)}
    paused = {d: ({"R51"} if i % 2 else set()) for i, d in enumerate(ds)}
    a = L.walk_period(ds, dc, name, vix_high=flags, rv_high=flags, paused=paused)
    cut = ds[8]
    dc2 = {d: (cs, [dict(p, net=p["net"] * -5 + 3) for p in ps]) if d >= cut else (cs, ps) for d, (cs, ps) in dc.items()}
    b = L.walk_period(ds, dc2, name, vix_high=flags, rv_high=flags, paused=paused)
    assert all(outs(a[d]) == outs(b[d]) for d in ds if d < cut)
    # and inside a day, nothing after a minute changes that minute's decision
    cs, ps = dc[ds[0]]
    x = B.walk_day(cs, ps, L.make_rule(name), {"week": 0.0, "month": 0.0, "cap1": True, "paused": set()}, ds[0])
    ps2 = [dict(p, net=-p["net"] * 4, xr=p["xr"] + 25) if c["er"] > 150 else p for c, p in zip(cs, ps)]
    y = B.walk_day(cs, ps2, L.make_rule(name), {"week": 0.0, "month": 0.0, "cap1": True, "paused": set()}, ds[0])
    early = lambda recs: [(r["c"]["g"], r["out"]) for r in recs if r["c"]["er"] <= 150]  # noqa: E731
    assert early(x) == early(y)


def test_look_ahead_guard_realised_volatility_uses_closes_to_yesterday_only():
    ds = days(40)
    closes = {d: 100.0 * math.exp(0.01 * i * ((-1) ** i)) for i, d in enumerate(ds)}
    rv = L.realised_vol(closes, ds, 20)
    assert ds[20] not in rv and ds[21] in rv           # needs 21 closes before the day
    # changing today's and later closes never changes today's value
    later = dict(closes)
    for d in ds[30:]:
        later[d] = 1e6
    rv2 = L.realised_vol(later, ds, 20)
    assert all(rv[d] == rv2[d] for d in ds if d in rv and d <= ds[30])
    # it equals the stdev of the 20 returns ending yesterday
    d = ds[25]
    c = [closes[x] for x in ds[:25]]
    lr = np.diff(np.log(c))[-20:]
    assert rv[d] == pytest.approx(float(np.std(lr, ddof=1)))


def test_vix_open_uses_only_09_15_to_09_19():
    rows = {D0: [(3, 15.2), (0, 14.0), (30, 99.0)], D0 + timedelta(days=1): [(10, 20.0)], D0 + timedelta(days=2): [(2, float("nan")), (4, 13.0)]}
    v = L.vix_open(rows, 4)
    assert v == {D0: 14.0, D0 + timedelta(days=2): 13.0}


def test_thresholds_and_flags():
    assert L.percentile([1, 2, 3, 4, None, float("nan")], 50) == 2.5
    f = L.flags_high({D0: 10.0, D0 + timedelta(days=1): 20.0}, 15.0)
    assert f == {D0: False, D0 + timedelta(days=1): True}
    with pytest.raises(ValueError):
        L.percentile([], 67)


# ------------------------------------------------------------------------------------------------ Part A
def test_trade_stats_breakeven_and_kelly():
    s = L.trade_stats([300, 300, -100, -100, -100])
    assert s["win"] == 0.4 and s["avg_win"] == 300 and s["avg_loss"] == 100 and s["payoff"] == 3.0
    assert s["breakeven_win"] == 0.25 and s["expectancy"] == 60.0
    assert s["kelly_trade"] == pytest.approx(0.4 - 0.6 / 3)
    assert L.trade_stats([])["n"] == 0
    assert L.trade_stats([5, 5])["kelly_trade"] == 1.0


def test_daily_stats_and_block_autocorr():
    v = np.array([100.0, -50.0, 80.0, -20.0, 60.0, -10.0] * 10)
    s = L.daily_stats(v)
    assert s["mean"] == pytest.approx(v.mean(), abs=0.1) and s["ac1"] < 0
    assert s["sharpe"] == pytest.approx(v.mean() / v.std(ddof=1) * math.sqrt(250), abs=1e-3)
    trend = np.repeat([1.0, 1.0, -1.0, -1.0], 10)
    assert L.block_autocorr(np.tile(trend, 3), 10) > 0
    assert math.isnan(L.block_autocorr(np.ones(20), 10))


def test_prob_drawdown_extremes():
    rng = np.random.default_rng(0)
    assert L.prob_drawdown(np.full(50, 100.0), 40_000, 120, 500, 5, rng) == 0.0
    assert L.prob_drawdown(np.full(50, -1_000.0), 40_000, 120, 500, 5, rng) == 1.0
    p = L.prob_drawdown(np.array([5_000.0, -5_000.0] * 50), 40_000, 120, 2_000, 5, rng)
    assert 0.0 <= p <= 1.0


def test_corr_matrix():
    c = L.corr_matrix({"a": [1, 2, 3, 4], "b": [2, 4, 6, 8], "z": [1, 1, 1, 1]})
    assert c == {"a~b": 1.0}


# ------------------------------------------------------------------------------------------------ twins and gates
def test_twin_counts():
    ds = days(3)
    base = {ds[0]: [1, 2, 3], ds[1]: [1], ds[2]: []}
    rule = {ds[0]: [1], ds[1]: [1, 2]}
    assert L.twin_counts(rule, base, ds) == {ds[0]: 2, ds[1]: 0, ds[2]: 0}


def _s(net, dd, days_b=1, months_b=1, br=1):
    return dict(net=net, max_dd=dd, days_below=days_b, months_below=months_b, dd_breaches=br)


def test_gates_need_less_loss_and_kept_profit_everywhere():
    base = _s(100_000, 50_000)
    ok = {p: (_s(95_000, 40_000), base) for p in ("design", "wf", "final")}
    assert L.gates(ok, 0.05, 40)["passed"]
    # profit floor: max(15,000, 15%) below CAP2
    assert L.net_floor(100_000) == 85_000 and L.net_floor(-128_786) == pytest.approx(-128_786 - 19_317.9)
    bad = dict(ok, wf=(_s(80_000, 40_000), base))
    assert not L.gates(bad, 0.05, 40)["passed"]
    worse_dd = dict(ok, final=(_s(95_000, 50_000), base))
    assert any("max drawdown" in f for f in L.gates(worse_dd, 0.05, 40)["fails"])
    more_bad_days = dict(ok, design=(_s(95_000, 40_000, days_b=2), base))
    assert not L.gates(more_bad_days, 0.05, 40)["passed"]
    assert not L.gates(ok, 0.2, 40)["passed"] and not L.gates(ok, 0.05, 10)["passed"]


def test_bh_counts_the_prior_tests():
    q = B.bh([1e-5, 0.5], 2 + K.N_PRIOR)
    assert q[0] == pytest.approx(min(1.0, 1e-5 * (2 + K.N_PRIOR)))
    assert K.N_PRIOR == 5_863


def test_paired_days_amendment_1():
    rng = np.random.default_rng(0)
    base = np.zeros(100)
    up = base.copy(); up[:30] = 500.0
    r = L.paired_days(up, base, 5_000, rng)
    assert r["days"] == 30 and r["mean"] == 500.0 and r["p"] < 0.001
    mixed = base.copy(); mixed[:20] = 500.0; mixed[20:40] = -500.0
    assert L.paired_days(mixed, base, 5_000, rng)["p"] > 0.3
    assert L.paired_days(base, base, 100, rng)["p"] == 1.0
