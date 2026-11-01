"""Tests for R144a (PREREG.md): the features read nothing at or after the fill row (look-ahead guard), the proxies,
the anatomy helpers, the filter choice and the gates. No lake needed."""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r144a as T  # noqa: E402
import r144a_feat as F  # noqa: E402


def synth(seed=0, n=F.N):
    rng = np.random.default_rng(seed)
    ch = F.Chain.empty()
    spot = 20000 + np.cumsum(rng.normal(0, 5, n))
    ch.spot[:] = spot
    atm = np.round(spot / 50) * 50
    for si in range(2):
        for k in range(F.NOFF):
            off = k - 10
            ch.strike[si, k] = atm + 50 * off
            ch.close[si, k] = np.abs(rng.normal(100, 20, n)) + 1
            ch.vol[si, k] = rng.integers(0, 5000, n).astype(float)
            ch.oi[si, k] = rng.integers(10_000, 1_000_000, n).astype(float)
            ch.iv[si, k] = np.abs(rng.normal(14, 2, n))
    con = np.vstack([np.abs(rng.normal(100, 5, n)) + 1 for _ in range(4)] + [rng.integers(0, 3000, n).astype(float)])
    con[1] = np.maximum(con[1], con[[0, 2, 3]].max(axis=0))
    con[2] = np.minimum(con[2], con[[0, 1, 3]].min(axis=0))
    idx = np.vstack([spot, spot + 3, spot - 3, spot + rng.normal(0, 1, n)])
    vix = 14 + rng.normal(0, 0.2, n)
    ctx = dict(vix=vix, vix_prev=13.5, adr=250.0, basis=0.4, fut_vol_ratio=1.2, fut_quad="long build-up", us_chg=-0.8,
               hist_atm=rng.integers(0, 50_000, (20, n)).astype(float),
               hist_itm=rng.integers(0, 5_000, (20, n)).astype(float))
    strike = float(atm[100] - 50)
    return ch, con, idx, ctx, strike


def same(a, b):
    for k in a:
        x, y = a[k], b[k]
        if isinstance(x, float) and np.isnan(x):
            assert isinstance(y, float) and np.isnan(y), k
        else:
            assert x == y, (k, x, y)


@pytest.mark.parametrize("er,side", [(120, "C"), (120, "P"), (5, "C"), (300, "P"), (1, "C")])
def test_look_ahead_guard(er, side):
    """Change every minute at or after the fill row er: no feature may move."""
    ch, con, idx, ctx, strike = synth(1)
    before = F.features(ch, con, idx, er, side, strike, ctx)
    rng = np.random.default_rng(9)
    for a in (ch.close, ch.vol, ch.oi, ch.iv):
        a[:, :, er:] = rng.normal(500, 300, a[:, :, er:].shape)
    ch.strike[:, :, er:] += 50 * rng.integers(-3, 4, ch.strike[:, :, er:].shape)
    ch.spot[er:] = 1.0
    con[:, er:] = rng.normal(50, 40, con[:, er:].shape)
    idx[:, er:] = rng.normal(20000, 500, idx[:, er:].shape)
    ctx["vix"][er:] = 99.0
    after = F.features(ch, con, idx, er, side, strike, ctx)
    same(before, after)


def test_every_feature_known_on_full_data():
    ch, con, idx, ctx, strike = synth(2)
    f = F.features(ch, con, idx, 120, "C", strike, ctx)
    missing = [k for k in F.QUINT if not np.isfinite(f[k])]
    assert missing == [], missing
    assert f["F3"] in ("long build-up", "short build-up", "short covering", "long unwinding")
    assert f["F4"] == "build-up with"
    assert set(F.FEATS) == set(F.NAMES)


def test_empty_and_missing_data_give_unknown_not_errors():
    _, _, idx, ctx, strike = synth(3)
    f = F.features(None, None, idx, 120, "C", strike, {})
    assert all(np.isnan(f[k]) for k in F.QUINT if k != "F2")
    assert f["F3"] is None and f["F4"] is None
    f0 = F.features(None, None, idx, 0, "C", strike, ctx)            # no minute before the fill
    assert all(np.isnan(f0[k]) for k in F.QUINT)
    ch = F.Chain.empty()                                               # market closed / no option rows
    f = F.features(ch, None, np.full((4, F.N), np.nan), 120, "P", strike, ctx)
    assert np.isnan(f["V1"]) and np.isnan(f["O4"]) and np.isnan(f["F2"])


def test_iv_zero_is_missing_and_signs_follow_the_trade():
    ch, con, idx, ctx, strike = synth(4)
    ch.iv[:, 10, :] = 0.0
    f = F.features(ch, con, idx, 120, "C", strike, ctx)
    assert np.isnan(f["O4"])
    fc = F.features(*synth(5)[:3], 120, "C", synth(5)[4], synth(5)[3])
    fp = F.features(*synth(5)[:3], 120, "P", synth(5)[4], synth(5)[3])
    assert fc["C11"] == pytest.approx(-fp["C11"]) and fc["O3"] == pytest.approx(-fp["O3"])
    assert fc["F4"] == "build-up with" and fp["F4"] == "build-up against"


def test_stale_minutes_and_volume_delta():
    ch, con, idx, ctx, strike = synth(6)
    con[4, 105:120] = 0.0
    con[0, 105:120], con[3, 105:120] = 100.0, 101.0
    f = F.features(ch, con, idx, 120, "C", strike, ctx)
    assert f["Q3"] == 15.0 and np.isnan(f["F1"])
    con[4, 105:120] = 10.0
    f = F.features(ch, con, idx, 120, "C", strike, ctx)
    assert f["Q3"] == 0.0 and f["F1"] == pytest.approx(1.0)


def test_quadrant():
    assert F.quadrant(1, 1) == "long build-up" and F.quadrant(-1, 1) == "short build-up"
    assert F.quadrant(1, -1) == "short covering" and F.quadrant(-1, -1) == "long unwinding"
    assert F.quadrant(0, 1) is None and F.quadrant(float("nan"), 1) is None


def test_window_median_needs_five_sessions():
    h = np.ones((4, F.N))
    assert np.isnan(F.window_median(h, 0, 5)) and np.isnan(F.window_median(None, 0, 5))
    h = np.vstack([np.full(F.N, x) for x in (1, 2, 3, 4, 100)])
    assert F.window_median(h, 10, 15) == 15.0
    assert np.isnan(F.window_median(h, 5, 5))


def test_us_change_uses_only_earlier_sessions_and_holidays(tmp_path):
    p = tmp_path / "us.json"
    p.write_text(json.dumps([["2025-01-02", 100.0], ["2025-01-03", 101.0], ["2025-01-06", 99.99]]))
    us = F.us_moves(p)
    assert F.us_change_for(us, date(2025, 1, 6)) == pytest.approx(1.0)       # Monday reads Friday's US session
    assert F.us_change_for(us, date(2025, 1, 7)) == pytest.approx(-1.0)
    assert np.isnan(F.us_change_for(us, date(2025, 1, 3)))                     # no change dated before
    assert np.isnan(F.us_change_for(us, date(2025, 2, 1)))                     # stale (> 5 days)


def tr(day, net, er=50, und="NIFTY", why="time", peak=0.0):
    return dict(day=day, net=net, er=er, xr=er + 10, und=und, why=why, peak=peak, side="C", strike=1.0, E=1.0, X=1.0)


def test_anatomy_helpers():
    d1, d2 = date(2025, 1, 6), date(2025, 1, 7)
    trades = [tr(d1, -100, why="stop"), tr(d1, -50, er=60), tr(d2, 300, peak=0.4), tr(d2, -200, und="BANKNIFTY",
                                                                                        peak=0.2)]
    s = T.summary(trades)
    assert s["n"] == 4 and s["losers"] == 3 and s["net"] == -50 and s["avg_loss"] == round(-350 / 3)
    m = T.money_lost(trades, T.exit_type)
    assert m["stop"]["lost"] == -100 and m["time / square-off"]["lost"] == -250
    assert abs(sum(v["share"] for v in m.values()) - 1) < 0.01
    assert T.peak_type(trades[0]) == "never worked (<5%)" and T.peak_type(trades[3]) == "gave it back (>=15%)"
    b = T.bunching(trades)
    assert b["losing_days"] == 1 and b["longest_losing_trade_streak"] == 3
    assert b["losers_on_all_loss_index_days"] == 1.0
    assert T.summary([]) == dict(n=0) and T.bunching([])["days"] == 0


def test_slices_and_steady():
    ed = [1.0, 2.0, 3.0, 4.0]
    assert T.slice_of("V1", 0.5, ed) == 0 and T.slice_of("V1", 4.0, ed) == 4 and T.slice_of("V1", np.nan, ed) is None
    assert T.slice_of("F3", "long build-up", [], F.CATS) == "long build-up"
    assert T.slice_of("F3", None, [], F.CATS) is None
    base = {p: dict(n=100, loser_rate=0.5, avg=10) for p in ("unseen", "design", "final")}
    row = {p: dict(n=40, loser_rate=0.6, avg=-5) for p in ("unseen", "design", "final")}
    assert T.steady(row, base)
    assert not T.steady(dict(row, final=dict(n=29, loser_rate=0.9, avg=-50)), base)
    assert not T.steady(dict(row, unseen=dict(n=40, loser_rate=0.6, avg=1)), base)


def test_qualify_pick_and_design_gate():
    arm = {h: dict(n=500, avg=50, loser_rate=0.5) for h in ("H1", "H2")}
    good = {h: dict(n=60, avg=-10, loser_rate=0.6) for h in ("H1", "H2")}
    assert T.qualifies(good, arm)
    assert not T.qualifies(dict(good, H2=dict(n=29, avg=-10, loser_rate=0.6)), arm)
    assert not T.qualifies(dict(good, H1=dict(n=60, avg=-10, loser_rate=0.4)), arm)
    c = [dict(feature="V1", t=-3.0), dict(feature="V1", t=-4.0), dict(feature="O4", t=-1.0), dict(feature="C7", t=-2.0),
         dict(feature="F1", t=-0.5)]
    p = T.pick(c)
    assert [x["feature"] for x in p] == ["V1", "C7", "O4"] and p[0]["t"] == -4.0
    base = dict(loss_sum=-1000, max_dd=500, worst_day=-300, worst_month=-600, net=100)
    assert T.design_ok(dict(base, loss_sum=-900), base)
    assert not T.design_ok(dict(base, loss_sum=-900, net=-1), base)
    assert not T.design_ok(dict(base), base)


def test_gate_period_profit_rule_only_where_the_arm_made_money():
    base = dict(loss_sum=-1000, max_dd=500, worst_day=-300, worst_month=-600, net=-100)
    rule = dict(base, loss_sum=-800, net=-50)
    assert T.gate_period(rule, base, 0.01, 100, 50) == []                     # arm lost money: no profit gate
    assert "no longer makes money" in T.gate_period(rule, dict(base, net=100), 0.01, 100, 50)
    assert T.gate_period(rule, base, 0.06, 100, 50) and T.gate_period(rule, base, 0.01, 39, 50)
    assert T.gate_period(rule, base, 0.01, 100, 29)


def test_bh():
    q = T.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[1] == pytest.approx(0.04 * 4 / 3) and q[2] == pytest.approx(0.04 * 4 / 3)
    assert q[3] == 0.5
    assert list(T.bh([0.2])) == [0.2]
    assert all(np.diff(T.bh([0.001, 0.01, 0.02, 0.03])) >= 0)


def test_drop_candidate_unknown_never_drops():
    sel = dict(feature="V1", slice=0, edges=[1.0, 2.0, 3.0, 4.0])
    assert T.drop_candidate(sel, {"k": dict(V1=0.5)}, "k")
    assert not T.drop_candidate(sel, {"k": dict(V1=2.5)}, "k")
    assert not T.drop_candidate(sel, {"k": dict(V1=np.nan)}, "k") and not T.drop_candidate(sel, {}, "k")
