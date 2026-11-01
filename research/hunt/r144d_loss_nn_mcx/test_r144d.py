"""Tests for R144d: the look-ahead guard on the network arms' entry features, the money metrics, buckets, filters,
the twin test and BH. Synthetic data only (no lake needed)."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nn_arms as N  # noqa: E402
import r144d as R  # noqa: E402


def _day(seed=0):
    rng = np.random.default_rng(seed)
    c = 20000 + np.cumsum(rng.normal(0, 5, N.NM))
    o = np.r_[c[0], c[:-1]]
    h = np.maximum(o, c) + 2
    lo = np.minimum(o, c) - 2
    return o, h, lo, c


def _prev(n=30):
    d0 = date(2025, 1, 1)
    rng = np.random.default_rng(1)
    cl = 20000 + np.cumsum(rng.normal(0, 50, n))
    return pd.DataFrame(dict(date=[d0 + timedelta(days=i) for i in range(n)], open=cl, high=cl + 80, low=cl - 80,
                             close=cl))


def _opt(seed=2):
    rng = np.random.default_rng(seed)
    c = 100 + np.cumsum(rng.normal(0, 1, N.NM))
    v = rng.integers(0, 50, N.NM).astype(float)
    return dict(o=np.r_[c[0], c[:-1]], h=c + 1, l=c - 1, c=c, v=v, oi=1000 + np.cumsum(rng.integers(-5, 6, N.NM)),
                iv=np.full(N.NM, 15.0) + rng.normal(0, 0.1, N.NM))


def _garbage_after(a, r0):
    a = np.array(a, float).copy()
    a[r0:] = 1e9
    return a


@pytest.mark.parametrize("r0,side", [(5, 1), (60, -1), (200, 1), (374, -1)])
def test_lookahead_guard_entry_features(r0, side):
    o, h, lo, c = _day()
    opt = _opt()
    br = np.linspace(0, 1, N.NM)
    hist = np.array([500.0] * 20)
    f1 = N.entry_features(r0, side, o, h, lo, c, _prev(), opt, hist, br)
    o2, h2, l2, c2 = (_garbage_after(x, r0) for x in (o, h, lo, c))
    opt2 = {k: _garbage_after(v, r0) for k, v in opt.items()}
    f2 = N.entry_features(r0, side, o2, h2, l2, c2, _prev(), opt2, hist, _garbage_after(br, r0))
    assert f1.keys() == f2.keys()
    for k in f1:
        assert (np.isnan(f1[k]) and np.isnan(f2[k])) or f1[k] == f2[k], k


def test_entry_features_empty_and_missing():
    o, h, lo, c = (np.full(N.NM, np.nan) for _ in range(4))
    f = N.entry_features(10, 1, o, h, lo, c, _prev(0).iloc[:0], None, None, None)
    assert np.isnan(f["f_push"]) and np.isnan(f["f_prem"]) and np.isnan(f["f_breadth_with"])
    assert f["f_min"] == 9 * 60 + 15 + 10


def test_entry_features_side_signs():
    o, h, lo, c = _day()
    a = N.entry_features(100, 1, o, h, lo, c, _prev(), None, None, None)
    b = N.entry_features(100, -1, o, h, lo, c, _prev(), None, None, None)
    for k in ("f_push", "f_trend20", "f_gap", "f_d_open", "f_ret5"):
        assert a[k] == pytest.approx(-b[k])


def test_no_trade_minutes_are_not_prices():
    o, h, lo, c = _day()
    opt = _opt()
    opt["v"][:] = 0.0                           # nothing traded: no premium, no spread, all minutes "no trade"
    f = N.entry_features(100, 1, o, h, lo, c, _prev(), opt, np.array([5.0] * 20), None)
    assert np.isnan(f["f_prem"]) and np.isnan(f["f_opt_spread"]) and f["f_opt_notrade"] == 1.0


def _trades(n=200, seed=3):
    rng = np.random.default_rng(seed)
    days = [date(2024, 11, 20) + timedelta(days=int(i)) for i in np.sort(rng.integers(0, 480, n))]
    T = pd.DataFrame(dict(day=days, net=rng.normal(50, 1000, n), f_a=rng.normal(0, 1, n),
                          f_cat=rng.integers(0, 3, n).astype(float)))
    T["pday"] = T.day
    T["period"] = [R.period_of(d) for d in T.day]
    return T


def test_money_metrics():
    net = np.array([100.0, -50, -70, 30])
    days = [date(2025, 1, 1), date(2025, 1, 1), date(2025, 1, 2), date(2025, 2, 3)]
    m = R.money(net, np.array(days))
    assert m["net"] == 10 and m["losing_days"] == -70 and m["worst_day"] == -70
    assert m["max_dd"] == 70 and m["worst_month"] == -20
    e = R.money(np.array([]), np.array([]))
    assert e["n"] == 0


def test_periods_and_holiday_gap():
    assert R.period_of(date(2022, 12, 1)) == "U"
    assert R.period_of(date(2024, 11, 19)) == "U"
    assert R.period_of(date(2024, 11, 20)) == "D"
    assert R.period_of(date(2026, 3, 31)) == "D"
    assert R.period_of(date(2026, 4, 1)) == "F"
    assert R.period_of(date(2022, 11, 30)) is None and R.period_of(date(2026, 10, 20)) is None


def test_bucket_and_skip_missing_never_skipped():
    x = pd.Series([1.0, 2, 3, 4, 5, np.nan, 7, 8, 9, 10])
    c = R.cuts_for(x)
    assert c["kind"] == "q"
    b = R.bucket(x, c)
    assert b.iloc[5] == "missing" and b.iloc[0] == "Q1" and b.iloc[-1] == "Q5"
    T = pd.DataFrame(dict(f_a=x))
    m = R.skip_mask(T, dict(kind="low", feature="f_a", cut=c["cuts"][0]))
    assert not m[5] and m[0]
    cat = R.cuts_for(pd.Series([0.0, 1, 1, 2]))
    assert cat["kind"] == "cat"


def test_choose_reads_design_only_and_limits():
    T = _trades()
    D = T[T.period == "D"].reset_index(drop=True)
    D.loc[D.f_a < D.f_a.quantile(0.2), "net"] = -800.0     # plant a losing lowest fifth
    cuts, ch = R.choose(D, ["f_a", "f_cat"])
    assert 1 <= len(ch) <= 3
    assert ch[0]["feature"] == "f_a" and ch[0]["kind"] == "low"
    for c in ch:
        assert c["design"]["skipped"] <= R.MAX_SKIP * len(D)


def test_twin_p_and_bh():
    rng = np.random.default_rng(0)
    net = np.r_[np.full(50, -100.0), np.full(50, 100.0)]
    codes = np.arange(100)
    m = net < 0                                           # skipping exactly the losers: nothing random does better
    p = R.twin_p([(net, codes, 100, m)], rng, n=200)
    assert p < 0.01
    p2 = R.twin_p([(net, codes, 100, ~m)], rng, n=200)    # skipping the winners: worst possible
    assert p2 > 0.9
    q = R.bh([0.01, 0.04, 0.5])
    assert q[0] == pytest.approx(0.03) and q[2] == 0.5 and R.bh([]) == []


def test_steady_needs_two_big_periods():
    a = dict(n=40, loser_rate=0.5, buckets={"f_a": {"Q1": dict(n=10, loser_rate=0.8, avg=-100)}})
    small = dict(n=10, loser_rate=0.5, buckets={"f_a": {"Q1": dict(n=3, loser_rate=1.0, avg=-100)}})
    assert R.steady({"U": a, "D": small, "F": small}, ["f_a"]) == []
    s = R.steady({"U": a, "D": a, "F": small}, ["f_a"])
    assert len(s) == 1 and s[0]["bucket"] == "Q1"


def test_verdict_small_period():
    g = dict(n30=True, losing_days_smaller=True, dd_no_worse=True, worst_day_no_worse=True, worst_month_no_worse=True,
             still_makes_money=True, twin=True)
    r = {"U": dict(before=dict(n=100), gates=g), "F": dict(before=dict(n=12), gates=g)}
    ok, why = R.verdict(r, 0.05)
    assert ok and any("too small" in w for w in why)
    ok2, _ = R.verdict(r, 0.2)
    assert not ok2
    r["U"]["before"]["n"] = 10
    ok3, _ = R.verdict(r, 0.01)
    assert not ok3
