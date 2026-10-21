"""R96 tests: pricing, the stop / lock walk, exits, fills, expiry and period caps, costs, daily indicators, the HAR
forecast, twins, BH, gates, the one-time holdout lock and the look-ahead guard (synthetic data only; no lake needed)."""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
import pytest

from research.hunt.r53.mcx.data import Market
from research.hunt.r96_mcx_buy import cfg as K
from research.hunt.r96_mcx_buy import engine as E
from research.hunt.r96_mcx_buy import history as H
from research.hunt.r96_mcx_buy import rules as R
from research.hunt.r96_mcx_buy import study as S
from research.hunt.r96_mcx_buy.data import Book

STEP = 50.0


# ------------------------------------------------------------------------------------------------ synthetic book
def make_book(days=8, path=None, exp_day=None, vol=0.30, start="2025-11-03", minutes=(9 * 60, 23 * 60 + 29),
              drop_prints=None):
    """A synthetic Market: every session 09:00-23:29 (one minute bars), future F (default flat 5000), ATM+/-3 option
    rows priced by Black-76 at `vol`, every minute printed (volume 1)."""
    dd = pd.bdate_range(start, periods=days)
    ts = []
    for d in dd:
        ts.extend(d + pd.to_timedelta(np.arange(minutes[0], minutes[1] + 1), unit="m"))
    ts = pd.DatetimeIndex(ts)
    n = len(ts)
    F = np.full(n, 5000.0) if path is None else np.asarray(path(np.arange(n), ts), float)
    exp = pd.Timestamp(exp_day) if exp_day else dd[-1] + pd.Timedelta(days=30)
    rows = []
    for j in range(n):
        atm = round(F[j] / STEP) * STEP
        T = max((exp + pd.Timedelta(hours=23, minutes=30) - ts[j]).total_seconds() / (365 * 86400), 1e-6)
        for cp in (0, 1):
            for off in range(-3, 4):
                k = atm + off * STEP
                px = E.b76(F[j], k, T, vol, cp)
                rows.append((ts[j], cp, off, k, px, px, px, px, 1, False))
    O = pd.DataFrame(rows, columns=["ts", "cp", "offset", "strike", "open", "high", "low", "close", "volume", "f1"])
    if drop_prints is not None:
        O = O[~drop_prints(O)]
    m = Market("SYN", ts.values, F, O)
    m.exp_days = np.array([np.datetime64(exp.normalize(), "ns")])
    m.seg = np.searchsorted(m.exp_days, m.day, side="left").astype(np.int32)
    m.is_exp = np.isin(m.day, m.exp_days)
    iv = {(cp, off): np.full(n, vol * 100) for cp in (0, 1) for off in range(-3, 4)}
    return Book("CRUDEOILM", m, iv, pd.DataFrame(), 10.0, "crude", ("eia",))


# ------------------------------------------------------------------------------------------------ pricing and costs
def test_b76_parity_and_intrinsic():
    F, k, T, v = 5000.0, 4950.0, 0.05, 0.4
    c, p = E.b76(F, k, T, v, 0), E.b76(F, k, T, v, 1)
    assert c - p == pytest.approx(F - k, abs=1e-6)
    assert E.b76(F, k, 0.0, v, 0) == 50.0 and E.b76(F, k, 0.0, v, 1) == 0.0
    assert E.b76(F, k, T, float("nan"), 0) == 50.0


def test_costs_model_exit_and_double_spread():
    base = E.opt_cost("crude", 1000, 1000, 18 * 60, 18 * 60)
    mdl = E.opt_cost("crude", 1000, 1000, 18 * 60, 18 * 60, model_exit=True)
    two = E.opt_cost("crude", 1000, 1000, 18 * 60, 18 * 60, mult=2.0)
    assert mdl - base == pytest.approx(0.003 * 1000)
    assert two - base == pytest.approx(0.003 * 2000)
    assert E.opt_cost("crude", 1000, 1000, 10 * 60, 10 * 60) - base == pytest.approx(0.003 * 2000)


# ------------------------------------------------------------------------------------------------ the walk
def rows(vals):
    a = np.asarray(vals, float)
    return a, a, a, a, np.full(len(a), np.nan)


def test_walk_hard_stop_and_gap():
    o, h, l, c, mdl = rows([100, 90, 70, 50])
    F = np.full(4, 10.0)
    k, why, px, md = E.walk_single(o, h, l, c, mdl, F, 1, 100.0, 101.0, None)
    assert (k, why, px) == (3, "stop", 50.0)              # 70 stays above the -40% level (60); 50 gaps through it
    o2 = np.array([100, 90, 55, 50.0])
    k, why, px, md = E.walk_single(o2, o2, o2, o2, mdl, F, 1, 100.0, 101.0, None)
    assert why == "stop" and px == 55.0                    # gapped below the level: out at the open


def test_walk_lock_ladder_and_trail():
    v = [100, 135, 165, 120, 110]
    o, h, l, c, mdl = rows(v)
    k, why, px, md = E.walk_single(o, h, l, c, mdl, np.full(5, 1.0), 1, 100.0, 101.0, None)
    assert why == "lock" and px == pytest.approx(120.0) and k == 3   # +65% peak locks +30%; opened below: out at 120
    o2 = np.array([100, 135, 165, 140, 110.0])
    l2 = np.array([100, 135, 165, 125, 110.0])
    k, why, px, md = E.walk_single(o2, o2, l2, o2, mdl, np.full(5, 1.0), 1, 100.0, 101.0, None)
    assert why == "lock" and px == pytest.approx(130.0) and k == 3
    v = [100, 260, 210]
    o, h, l, c, mdl = rows(v)
    k, why, px, md = E.walk_single(o, h, l, c, mdl, np.full(3, 1.0), 1, 100.0, 101.0, None)
    assert why == "lock" and px == pytest.approx(210.0)              # +160% peak, trail +120% (220); opened at 210


def test_walk_breakeven_lock_not_on_own_high():
    o, h, l, c, mdl = rows([100, 131, 105])
    l = np.array([100, 131, 100.0])
    k, why, px, md = E.walk_single(o, h, l, c, mdl, np.full(3, 1.0), 1, 100.0, 101.5, None)
    assert why == "lock" and px == pytest.approx(101.5) and k == 2


def test_walk_underlying_stop_and_time():
    o, h, l, c, mdl = rows([100, 100, 100, 100])
    F = np.array([10.0, 9.5, 8.9, 8.0])
    k, why, px, md = E.walk_single(o, h, l, c, mdl, F, 1, 100.0, 101.0, 9.0)
    assert (k, why, px) == (2, "ustop", None)
    k, why, px, md = E.walk_single(o, h, l, c, mdl, F[::-1].copy(), -1, 100.0, 101.0, 20.0)
    assert (k, why) == (3, "time")


def test_walk_model_rows_and_unknown_rows():
    nan = np.nan
    o = np.array([100, nan, nan, nan])
    mdl = np.array([nan, nan, 55.0, nan])
    k, why, px, md = E.walk_single(o, o, o, o, mdl, np.full(4, 1.0), 1, 100.0, 101.0, None)
    assert why == "stop" and md and px == 55.0


def test_exit_price_order():
    nan = np.nan
    day = np.array([1, 1, 1, 1, 2])
    c = np.array([100, nan, nan, 103, 104.0])
    o = np.array([100, nan, nan, 102, 104.0])
    mdl = np.array([nan, 99.0, nan, nan, nan])
    assert E.exit_price(o, c, mdl, day, 0) == (100.0, False, False)
    assert E.exit_price(o, c, mdl, day, 1) == (102.0, False, False)        # next print within 10 minutes
    c2 = np.array([100, nan, nan, nan, 104.0])
    o2 = np.array([100, nan, nan, nan, 104.0])
    assert E.exit_price(o2, c2, mdl, day, 1) == (99.0, True, False)        # model when out of the window
    assert E.exit_price(o2, c2, np.full(5, nan), day, 2) == (100.0, False, True)   # stale


def test_walk_straddle():
    a = np.array([50, 40, 30, 30.0])
    b = np.array([50, 45, 40, 40.0])
    nan = np.full(4, np.nan)
    k, why = E.walk_straddle([(a, a, a, a, nan), (b, b, b, b, nan)], 100.0, 101.0)
    assert (k, why) == (2, "stop")                                          # 70 <= 70
    a = np.array([50, 80, 60, 50.0])
    k, why = E.walk_straddle([(a, a, a, a, nan), (b * 0 + 50, b * 0 + 50, b * 0 + 50, b * 0 + 50, nan)], 100.0, 101.0)
    assert why == "lock" and k == 3                                         # peak 130 -> breakeven 101 -> 100 exits


# ------------------------------------------------------------------------------------------------ fills, caps, marks
def test_sim_single_fill_hold_and_expiry_cap():
    bk = make_book(days=8, exp_day="2025-11-12")             # sessions 3..12 Nov; expiry Wed 12 Nov (8th session)
    m = bk.m
    d0 = m.days[0]
    i_sig, i_from = R.entry_ix(bk, d0, K.ENTRY_TOD)
    t = E.sim_single(bk, i_sig, 1, None, 10, None, i_from, stop_frac=0.99)   # no stop: only the caps act
    assert t is not None and t["entry_ts"] == pd.Timestamp(m.ts[i_from])
    assert t["exit_ts"] == pd.Timestamp("2025-11-11 23:14")                # square-off of the session before expiry
    assert t["why"] == "time"
    assert t["strike"][0] == 4950.0                                        # 1-ITM call = ATM - 1
    # fewer than 3 sessions before expiry: no multi-day entry; the expiry day: no entry at all
    i3 = R.entry_ix(bk, m.days[5], K.ENTRY_TOD)
    assert E.sim_single(bk, i3[0], 1, None, 5, None, i3[1]) is None
    i5 = R.entry_ix(bk, m.days[7], K.ENTRY_TOD)
    assert E.sim_single(bk, i5[0], 1, None, None, None, i5[1]) is None


def test_period_cap():
    bk = make_book(days=8)
    m = bk.m
    i_sig, i_from = R.entry_ix(bk, m.days[0], K.ENTRY_TOD)
    t = E.sim_single(bk, i_sig, -1, None, 7, m.days[2], i_from)
    assert t["exit_ts"].normalize() <= pd.Timestamp(m.days[2])
    assert t["strike"][0] == 5050.0                                        # 1-ITM put = ATM + 1


def test_no_fill_without_print():
    bk = make_book(days=4, drop_prints=lambda O: (O.ts.dt.hour == 9))     # nothing prints 09:00-09:59
    m = bk.m
    i_sig, i_from = R.entry_ix(bk, m.days[0], K.ENTRY_TOD)
    assert E.sim_single(bk, i_sig, 1, None, 2, None, i_from) is None


def test_model_marks_when_strike_leaves_window():
    def up(i, ts):
        return 5000.0 + 1.0 * i                                            # +1 point a minute: the strike drifts out
    bk = make_book(days=2, path=up)
    m = bk.m
    i0 = 10
    o, h, l, c, mdl = E.contract_marks(bk, 0, 5000.0, i0, i0 + 400)
    assert np.isfinite(c[:100]).all() and np.isnan(mdl[:100]).all()
    late = np.flatnonzero(np.isfinite(mdl))
    assert len(late) and np.isnan(c[late]).all()
    r = late[0]
    T = (pd.Timestamp(m.exp_days[0]) + pd.Timedelta(hours=23, minutes=30) - pd.Timestamp(m.ts[i0 + r])).total_seconds()
    assert mdl[r] == pytest.approx(E.b76(m.F[i0 + r], 5000.0, T / (365 * 86400), 0.30, 0))


def test_pick_strike_ignores_repeated_offsets():
    bk = make_book(days=1)
    m = bk.m
    j = 100
    assert E.pick_strike(m, 0, j, 1) == 4950.0 and E.pick_strike(m, 1, j, 1) == 5050.0
    assert E.pick_strike(m, 0, j, 0) == 5000.0 and np.isnan(E.pick_strike(m, 0, j, 4))
    for k in (-1, 1):                               # stale map: offsets -1 and +1 repeat the ATM strike
        m.k_at[(0, k)] = m.k_at[(0, 0)].copy()
        m.k_at[(1, k)] = m.k_at[(1, 0)].copy()
    assert E.pick_strike(m, 0, j, 1) == 4900.0      # the next stored strike below the ATM


def test_straddle_and_event_end():
    bk = make_book(days=6)
    m = bk.m
    i_sig, i_from = R.entry_ix(bk, m.days[1], 20 * 60 - 20)
    i_end = m.at_or_before(m.days[1], 20 * 60 + 45)
    t = S.simulate(bk, dict(kind="straddle", i_sig=i_sig, i_from=i_from, i_end_fixed=i_end, n_sessions=None,
                            fill_wait=15), None)
    assert t["kind"] == "straddle" and t["exit_ts"] <= pd.Timestamp(m.ts[i_end])
    assert len(t["fill"]) == 2 and t["strike"][0] == t["strike"][1] == 5000.0


# ------------------------------------------------------------------------------------------------ daily indicators
def daily_frame(n=300, seed=1):
    rng = np.random.default_rng(seed)
    c = 5000 + np.cumsum(rng.normal(0, 30, n))
    o = c + rng.normal(0, 10, n)
    h = np.maximum(o, c) + rng.uniform(5, 40, n)
    l = np.minimum(o, c) - rng.uniform(5, 40, n)
    d = pd.DataFrame(dict(date=pd.bdate_range("2024-01-01", periods=n), open=o, high=h, low=l, close=c,
                          oi=np.full(n, 1000.0)))
    from research.hunt.r93_breadth_donchian_mcx import rules as R93
    return R93.daily_series(d)


SIG = ["sq", "sq_release", "sq_side", "nr7", "wrb_side", "pb_side", "atr", "fc_vol"]


@pytest.mark.parametrize("mode", ["delete", "perturb", "roll"])
def test_lookahead_guard_daily(mode):
    from research.hunt.r93_breadth_donchian_mcx import rules as R93
    base = daily_frame()
    full = R.daily_ind(base)
    for cut in (120, 200, 260):
        d = base.copy()
        if mode == "delete":
            d = d.iloc[:cut + 1]
        elif mode == "perturb":
            d.loc[cut + 1:, ["open", "high", "low", "close"]] *= 1.3
        else:                                   # a roll after the cut shifts every earlier adjusted price equally
            d.loc[cut + 5:, ["open", "high", "low", "close"]] += 400.0
            d.loc[cut + 5, "oi"] = 5000.0
            d = R93.daily_series(d[["date", "open", "high", "low", "close", "oi"]])
        part = R.daily_ind(d)
        for col in SIG:
            a, b = full[col].values[:cut + 1], part[col].values[:cut + 1]
            if a.dtype.kind == "f":
                assert np.allclose(a, b, equal_nan=True, rtol=1e-9, atol=1e-9), (mode, cut, col)
            else:
                assert (a == b).all(), (mode, cut, col)


def test_lookahead_guard_catches_a_leak():
    base = daily_frame()
    leak = base.close.shift(-1)
    d = base.copy()
    d.loc[201:, "close"] *= 1.3
    assert not np.allclose(leak.values[:201], d.close.shift(-1).values[:201], equal_nan=True)


def test_har_forecast_causal_and_constant():
    rv = np.full(400, 1e-4)
    f = R.har_forecast(rv, min_rows=50)
    assert np.isfinite(f[100:]).all() and np.allclose(f[100:], 1e-4, rtol=1e-6)
    rng = np.random.default_rng(3)
    x = rng.uniform(1e-5, 1e-3, 500)
    a = R.har_forecast(x, min_rows=100)
    y = x.copy()
    y[351:] *= 10
    b = R.har_forecast(y, min_rows=100)
    assert np.allclose(a[:351], b[:351], equal_nan=True)


def test_nr7_wrb_pullback_examples():
    n = 80
    c = np.linspace(5000, 5800, n)                               # steady uptrend
    c[-2] -= 30
    c[-1] = c[-2] - 30                                           # two down closes
    h = c + 20
    l = c - 20
    h[-10] = c[-10] + 2                                          # a tiny range day
    l[-10] = c[-10] - 2
    d = pd.DataFrame(dict(date=pd.bdate_range("2024-01-01", periods=n), open=c, high=h, low=l, close=c,
                          oi=np.full(n, 1000.0)))
    from research.hunt.r93_breadth_donchian_mcx import rules as R93
    x = R.daily_ind(R93.daily_series(d))
    assert bool(x.nr7.values[-10]) and not bool(x.nr7.values[-9])
    assert x.pb_side.values[-1] == 1 and x.pb_side.values[-3] == 0
    d.loc[n - 5, ["high", "low", "close"]] = [c[n - 5] + 300, c[n - 5] - 10, c[n - 5] + 290]
    x = R.daily_ind(R93.daily_series(d))
    assert x.wrb_side.values[n - 5] == 1


# ------------------------------------------------------------------------------------------------ intraday rules
def test_us_momentum_filter_is_causal():
    def path(i, ts):
        return 5000.0 + 0.0 * i
    bk = make_book(days=30, path=path, start="2025-11-03")
    rule = R.USMomentum()
    m = bk.m
    rng = np.random.default_rng(5)
    F = m.F.copy()
    for d in m.days:
        t0 = rule.us_open(d)
        ix = m.day_ix(d)
        F[ix[m.tod[ix] >= t0]] += rng.normal(0, 10)
    m.F = F
    a = rule.candidates(bk, (K.DESIGN[0], K.HOLDOUT[1]))
    m.F = F.copy()
    m.F[m.day >= m.days[20]] *= 1.05
    b = rule.candidates(bk, (K.DESIGN[0], K.HOLDOUT[1]))
    ka = [(s["day"], s["side"]) for s in a if s["day"] < m.days[20]]
    kb = [(s["day"], s["side"]) for s in b if s["day"] < m.days[20]]
    assert ka == kb and len(ka) >= 1


def test_event_calendar_and_twin_skips_release_days():
    bk = make_book(days=10, start="2025-11-03")
    rel = R.release_days(bk)
    assert all(10 * 60 < v < 24 * 60 for v in rel.values())
    wed = [d for d in bk.m.days if d in rel]
    assert len(wed) >= 1
    rule = R.EventStraddle()
    sp = rule.spec(bk, wed[0], rel[wed[0]])
    assert sp is not None and rule.twin(bk, sp, wed[0]) is None


# ------------------------------------------------------------------------------------------------ statistics
def test_bh():
    q = S.bh([0.01, 0.04, 0.03, 0.5])
    assert q == pytest.approx([0.04, 0.0533333, 0.0533333, 0.5], rel=1e-4)
    assert S.bh([float("nan")]) == [1.0]


def test_summarize_and_gates():
    df = pd.DataFrame(dict(book="X", day=pd.to_datetime(["2025-09-01", "2025-12-01", "2026-03-02", "2026-06-01"]),
                           entry_ts=pd.to_datetime(["2025-09-01", "2025-12-01", "2026-03-02", "2026-06-01"]),
                           exit_ts=pd.to_datetime(["2025-09-02", "2025-12-02", "2026-03-03", "2026-06-02"]),
                           net=[1000.0, -500.0, 3000.0, 200.0], net2=[900.0, -600.0, 2900.0, 100.0],
                           gross=[1100.0, -400.0, 3100.0, 300.0], cost=[100.0] * 4, model=False, stale=False,
                           why="time", risk_rs=[400.0] * 4))
    s = S.summarize(df)
    assert s["net"] == 3700 and s["wo_top3"] == -500 and s["max_dd"] == -500 and s["worst_month"] == -500
    fl = S.folds(df)
    assert [f["trades"] for f in fl] == [1, 1, 1, 1] and sum(f["net"] > 0 for f in fl) == 3
    g = S.gates(s, fl, 0.01, 0.05, dict(mean=0.001))
    assert not g["trades"] and g["net"] and g["folds"] and not g["wo_top3"] and g["history"]


def test_twins_draw_other_days_same_month(monkeypatch):
    bk = make_book(days=10, start="2025-11-03")
    seen = []

    class Fake:
        def twin(self, book, sp, day):
            seen.append(day)
            return None

    monkeypatch.setitem(R.RULES, "ZZ", Fake())
    t = dict(book="CRUDEOILM", day=pd.Timestamp(bk.m.days[2]), spec={})
    tot = S.twins("ZZ", [t], {"CRUDEOILM": bk}, K.DESIGN, n=5)
    assert (tot == 0).all() and seen and all(d != bk.m.days[2] for d in seen)
    assert all(pd.Timestamp(d).month == 11 for d in seen)


def test_holdout_lock(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "RESULTS", tmp_path)
    (tmp_path / "design_round1.json").write_text(json.dumps(dict(rules=dict(S1=dict(**{"pass": False})))))
    with pytest.raises(SystemExit):
        S.holdout("S1")
    assert not (tmp_path / "HOLDOUT_READ_S1.json").exists()
    (tmp_path / "HOLDOUT_READ_S2.json").write_text("{}")
    with pytest.raises(SystemExit, match="already read"):
        S.holdout("S2")


def test_history_trade_dir_stop():
    n = 30
    c = np.r_[np.full(15, 5000.0), np.full(15, 4800.0)]
    d = pd.DataFrame(dict(date=pd.bdate_range("2014-01-01", periods=n), open=c, high=c + 10, low=c - 10, close=c,
                          oi=np.full(n, 1000.0)))
    from research.hunt.r93_breadth_donchian_mcx import rules as R93
    x = R.daily_ind(R93.daily_series(d))
    r = H.trade_dir(x, 13, 1, 1.0, 5)                    # long into the drop: stopped at the first lower close
    assert r == pytest.approx((4800 - 5000) / 5000 - H.COST)


def test_periods_are_disjoint():
    assert K.DESIGN[1] < K.HOLDOUT[0]
    assert K.FOLDS[0][0] == K.DESIGN[0] and K.FOLDS[-1][1] == K.DESIGN[1]
    assert all(a[1] < b[0] for a, b in zip(K.FOLDS[:-1], K.FOLDS[1:]))


# ------------------------------------------------------------------------------------------------ round 2
def test_edge_flags_and_edge_exit_on_a_real_print():
    def up(i, ts):
        return 5000.0 + 1.0 * i                       # the future rises a point a minute
    bk = make_book(days=2, path=up)
    m = bk.m
    i0 = 10
    ed = E.edge_flags(bk, 0, 5000.0, i0, i0 + 400)
    o, h, l, c, mdl = E.contract_marks(bk, 0, 5000.0, i0, i0 + 400)
    first = int(np.flatnonzero(ed)[0])
    assert np.isfinite(c[first])                      # at the edge the strike is still stored and prints
    assert not ed[:first].any() and np.isnan(mdl[:first]).all()
    k, why, px, md = E.walk_single(o, h, l, c, mdl, m.F[i0:i0 + 401], 1, float(o[0]), float(o[0]) + 1, None,
                                   stop_frac=0.99, edge=ed)
    assert why == "edge" and k == first and px == c[first] and not md


def test_edge_past_the_window_waits_for_the_next_print():
    nan = np.nan
    o = np.array([100, 110, nan, nan, 130, 120.0])
    edge = np.array([False, False, True, True, True, False])
    mdl = np.array([nan, nan, 125, 128, nan, nan])
    k, why, px, md = E.walk_single(o, o, o, o, mdl, np.full(6, 1.0), 1, 100.0, 101.0, None, edge=edge)
    assert (k, why, px, md) == (4, "edge", 130.0, False)


def test_sim_single_edge_exit_is_real_and_cap_skips():
    def up(i, ts):
        return 5000.0 + 0.5 * i
    bk = make_book(days=6, path=up)                   # enough sessions before the (synthetic) expiry
    m = bk.m
    i_sig, i_from = R.entry_ix(bk, m.days[0], K.ENTRY_TOD)
    t = E.sim_single(bk, i_sig, 1, None, 2, None, i_from, edge_exit=True)
    assert t["why"] == "edge" and not t["model"]
    t2 = E.sim_single(bk, i_sig, 1, None, 2, None, i_from, edge_exit=False, stop_frac=0.99)
    assert t2["why"] != "edge"
    assert E.sim_single(bk, i_sig, 1, None, 2, None, i_from, edge_exit=True, max_risk=10.0) is None


def test_two_itm_strike():
    bk = make_book(days=1)
    assert E.pick_strike(bk.m, 0, 50, 2) == 4900.0 and E.pick_strike(bk.m, 1, 50, 2) == 5100.0


def test_round2_indicators_and_guard():
    base = daily_frame(n=320, seed=7)
    full = R.daily_ind(base)
    for col in ("inside", "nr7_id", "qb_side", "sq_release3"):
        assert col in full
    assert (full.nr7_id <= full.nr7).all()
    for cut in (150, 250):
        d = base.copy()
        d.loc[cut + 1:, ["open", "high", "low", "close"]] *= 1.25
        part = R.daily_ind(d)
        for col in ("inside", "nr7_id", "qb_side", "sq_release3"):
            assert (full[col].values[:cut + 1] == part[col].values[:cut + 1]).all(), (cut, col)


def test_gate8_real_exits():
    s = dict(trades=40, net=1000.0, net2=500.0, worst_month=-100.0, max_dd=-100.0, wo_top3=10.0,
             model_share=0.05, real_exit_net=200.0)
    fl = [dict(net=1.0)] * 4
    assert S.gates(s, fl, 0.01, 0.05, None, round_no=2)["real_exits"]
    s["model_share"] = 0.2
    assert not S.gates(s, fl, 0.01, 0.05, None, round_no=2)["real_exits"]
    assert "real_exits" not in S.gates(s, fl, 0.01, 0.05, None, round_no=1)


# ------------------------------------------------------------------------------------------------ round 3
def test_trail_levels_breakeven_then_chandelier():
    F = np.array([100, 104, 110, 120, 130, 125, 116, 114.0])
    lv = E.trail_levels(F, 1, 90.0, 10.0, 1.0, 1.5)
    assert lv[0] == lv[1] == 90.0                     # +4 < 1 ATR: the first stop stays
    assert lv[2] == 100.0                             # +10 = 1 ATR: breakeven on the future (110 - 15 = 95 < 100)
    assert lv[3] == 105.0 and lv[4] == 115.0          # chandelier: best close - 15
    assert lv[5] == lv[6] == lv[7] == 115.0           # never moves back
    sh = E.trail_levels(200 - F, -1, 110.0, 10.0, 1.0, 1.5)
    assert sh[2] == 100.0 and sh[4] == 85.0
    assert (E.trail_levels(F, 1, 90.0, float("nan")) == 90.0).all()   # no ATR: the fixed stop


def test_walk_single_with_a_trailing_ustop_array():
    o, h, l, c, mdl = rows([100, 100, 100, 100, 100, 100])
    F = np.array([100, 110, 130, 120, 114, 113.0])
    lv = E.trail_levels(F, 1, 90.0, 10.0)
    k, why, px, md = E.walk_single(o, h, l, c, mdl, F, 1, 100.0, 101.0, lv, ladder=(), trail_from=float("inf"))
    assert (k, why) == (4, "ustop")                   # 114 <= 130 - 15 = 115


def test_nr_n_and_low_vol():
    rng = np.array([10, 9, 8, 7, 6, 12, 3, 4.0])
    assert list(R.nr_n(rng, 4)) == [False, False, False, True, True, False, True, False]
    assert (R.nr_n(rng, 7) == (np.arange(8) == 6)).all()
    d = pd.DataFrame(dict(atr=np.r_[np.linspace(10, 30, 10), [10.0]], close=np.full(11, 1000.0)))
    lv = R.low_vol(d, win=5, frac=1 / 3)
    assert not lv[:4].any() and lv[10] and not lv[9]


def test_round3_indicators_and_guard():
    base = daily_frame(n=420, seed=11)
    full = R.daily_ind(base)
    assert (full.nr7_lowvol <= full.nr7).all() and (full.nr7 <= full.nr4).all()
    w_full = R.weekly_nr7(full)
    for cut in (300, 380):
        d = base.copy()
        d.loc[cut + 1:, ["open", "high", "low", "close"]] *= 1.25
        part = R.daily_ind(d)
        for col in ("nr4", "lowvol", "nr7_lowvol"):
            assert (full[col].values[:cut + 1] == part[col].values[:cut + 1]).all(), (cut, col)
        w_part = R.weekly_nr7(part)
        done = w_full["last"].values < cut - 6           # weeks that ended well before the cut
        k = int(done.sum())
        assert (w_full.wnr7.values[:k] == w_part.wnr7.values[:k]).all()


def test_weekly_nr7_flags_the_narrow_week():
    n = 60
    dates = pd.bdate_range("2024-01-01", periods=n)
    c = np.full(n, 5000.0)
    h, l = c + 50, c - 50
    wk = np.flatnonzero(pd.DatetimeIndex(dates).isocalendar().week.values == 9)
    h[wk], l[wk] = c[wk] + 5, c[wk] - 5
    from research.hunt.r93_breadth_donchian_mcx import rules as R93
    d = R.daily_ind(R93.daily_series(pd.DataFrame(dict(date=dates, open=c, high=h, low=l, close=c,
                                                       oi=np.full(n, 1000.0)))))
    w = R.weekly_nr7(d)
    assert w.wnr7.sum() == 1 and int(w["first"].values[np.flatnonzero(w.wnr7.values)[0]]) == int(wk[0])
    r = int(np.flatnonzero(w.wnr7.values)[0])
    d2 = d.copy()
    a, z = int(w["first"].values[r + 1]), int(w["last"].values[r + 1])
    d2.loc[a:z, ["ah", "al"]] = [5003.0, 4997.0]          # a quiet next week ...
    d2.loc[a + 1, ["ah", "ac"]] = [5100.0, 5090.0]        # ... whose second session breaks up and closes high
    assert H.trade_wnr7(d, w, r, n=1) is None             # a session through both levels without a gap: skipped
    x = H.trade_wnr7(d2, w, r, n=1)
    assert x == pytest.approx((5090.0 - 5005.0) / 5000.0 - H.COST)
    assert H.trade_wnr7(d2, w, len(w) - 1) is None        # no next week


def _nr7_book(path):
    bk = make_book(days=6, path=path)
    m = bk.m
    bk.daily = pd.DataFrame(dict(date=m.days, nr7=[True] + [False] * 5, atr=np.full(6, 100.0)))
    return bk


def test_us_open_entry_needs_the_break_to_hold():
    def hold(i, ts):
        t = pd.DatetimeIndex(ts)
        day1 = t.normalize() == t.normalize()[0] + pd.Timedelta(days=1)
        return np.where(day1 & (t.hour * 60 + t.minute >= 10 * 60), 5100.0, 5000.0)
    bk = _nr7_book(hold)
    rule = R.NR7Var("T", us_open=True, books=("CRUDEOILM",))
    c = rule.candidates(bk, (date_(2025, 11, 1), date_(2025, 11, 30)))
    assert len(c) == 1 and c[0]["side"] == 1
    assert bk.m.tod[c[0]["i_sig"]] == 20 * 60 - 1          # 3 Nov 2025: US winter time, cash open 20:00 IST
    assert c[0]["ustop"] == 5000.0

    def fade(i, ts):
        t = pd.DatetimeIndex(ts)
        day1 = t.normalize() == t.normalize()[0] + pd.Timedelta(days=1)
        tod = t.hour * 60 + t.minute
        return np.where(day1 & (tod >= 10 * 60) & (tod < 15 * 60), 5100.0, 5000.0)
    assert R.NR7Var("T", us_open=True, books=("CRUDEOILM",)).candidates(
        _nr7_book(fade), (date_(2025, 11, 1), date_(2025, 11, 30))) == []


def test_early_break_window_and_trail_spec():
    def late(i, ts):
        t = pd.DatetimeIndex(ts)
        day1 = t.normalize() == t.normalize()[0] + pd.Timedelta(days=1)
        return np.where(day1 & (t.hour * 60 + t.minute >= 12 * 60), 5100.0, 5000.0)
    bk = _nr7_book(late)
    per = (date_(2025, 11, 1), date_(2025, 11, 30))
    assert R.NR7Var("T", last_tod=K.R3_EARLY_TOD, books=("CRUDEOILM",)).candidates(bk, per) == []
    c = R.NR7Var("T", trail=K.R3_TRAIL, books=("CRUDEOILM",)).candidates(bk, per)
    assert len(c) == 1 and c[0]["no_ladder"] and c[0]["atr"] == 100.0 and c[0]["trail"] == K.R3_TRAIL
    t = S.simulate(bk, c[0], None)
    assert t is not None and t["why"] in ("time", "ustop", "edge", "stop")


def date_(y, mth, d):
    from datetime import date
    return date(y, mth, d)


def _tod_path(fn):
    def path(i, ts):
        t = pd.DatetimeIndex(ts)
        k = ((t.normalize() - t.normalize()[0]).days).values
        return fn(k, (t.hour * 60 + t.minute).values)
    return path


def test_eia_momentum_spec_and_twin():
    # 5 Nov 2025 (Wednesday, US winter time): 11:00 ET = 21:30 IST, 11:30 ET = 22:00 IST
    bk = make_book(days=6, path=_tod_path(lambda k, tod: np.where((k == 2) & (tod >= 21 * 60 + 45), 5030.0, 5000.0)))
    rule = R.EIAMomentum("T", books=("CRUDEOILM",))
    day = bk.m.days[2]
    sp = rule.spec(bk, day)
    assert sp["side"] == 1 and bk.m.tod[sp["i_sig"]] == 22 * 60 and sp["ustop"] == 5000.0 and sp["edge"]
    assert day in R.release_days(bk) and rule.twin(bk, sp, day) is None
    assert rule.spec(bk, bk.m.days[1]) is None                       # flat: no move, no trade
    c = rule.candidates(bk, (date_(2025, 11, 1), date_(2025, 11, 30)))
    assert [x["day"] for x in c] == [day]
    t = S.simulate(bk, sp, None)
    assert t["exit_ts"] <= pd.Timestamp(bk.m.ts[E.sq_ix(bk.m, day)])  # same-session square-off


def test_last_half_hour_filter_is_causal(monkeypatch):
    sizes = np.zeros(8)
    sizes[[0, 1, 2, 3, 4, 7]] = [5, 1, 2, 30, 3, 40]           # calendar-day offsets of 3-7 and 10 Nov 2025
    bk = make_book(days=6, path=_tod_path(lambda k, tod: 5000.0 + np.where(tod >= 22 * 60, sizes[k], 0.0)))
    bk.daily = pd.DataFrame(dict(date=bk.m.days, atr=np.full(6, 100.0)))
    monkeypatch.setattr(K, "R3_LH_PAST", 2)
    rule = R.LastHalfHour("T", books=("CRUDEOILM",))
    c = rule.candidates(bk, (date_(2025, 11, 1), date_(2025, 11, 30)))
    # the synthetic sessions end 23:29 (close 23:30), so the move is read at 22:44 against the previous last minute
    days = [pd.Timestamp(x["day"]).day for x in c]
    assert 6 in days and 10 in days and 4 not in days           # 30 and 40 are big against the earlier moves
    sp = [x for x in c if pd.Timestamp(x["day"]).day == 6][0]
    assert bk.m.tod[sp["i_sig"]] == 22 * 60 + 44
    w = rule.window(bk, bk.m.days[3])
    assert w is not None and np.isclose(w[1], 5030.0 / 5002.0 - 1.0)


def test_high_vol_orb():
    # 3 Nov 2025, US winter: 09:00-09:29 ET = 19:30-19:59 IST; the break must come 20:00-21:30 IST
    bk = make_book(days=3, path=_tod_path(lambda k, tod: np.where(
        (k == 0) & (tod >= 19 * 60 + 30) & (tod < 20 * 60), 5000.0 + (tod % 2) * 10,
        np.where((k == 0) & (tod >= 20 * 60 + 30), 5025.0, 5005.0))))
    bk.daily = pd.DataFrame(dict(date=bk.m.days - np.timedelta64(1, "D"), hivol=[True, False, True]))
    rule = R.HighVolORB("T", books=("CRUDEOILM",))
    sp = rule.spec(bk, bk.m.days[0])
    assert sp["side"] == 1 and bk.m.tod[sp["i_sig"]] == 20 * 60 + 30 and sp["ustop"] == 5005.0
    assert rule.spec(bk, bk.m.days[1]) is None                    # no break that day
    assert R.flag_before(bk, bk.m.days[0], "hivol") and not R.flag_before(bk, bk.m.days[1], "hivol")
    c = rule.candidates(bk, (date_(2025, 11, 1), date_(2025, 11, 30)))
    assert [x["day"] for x in c] == [bk.m.days[0]]


def test_round3_hivol_guard():
    base = daily_frame(n=420, seed=5)
    full = R.daily_ind(base)
    for cut in (300, 390):
        d = base.copy()
        d.loc[cut + 1:, ["open", "high", "low", "close"]] *= 1.4
        part = R.daily_ind(d)
        assert (full.hivol.values[:cut + 1] == part.hivol.values[:cut + 1]).all()


def test_round3_rules_registered():
    assert len(R.ORDER3) == 10 and all(r in R.RULES for r in R.ORDER3)
    assert R.RULES["R3A"].books == K.CRUDE and R.RULES["R3E"].n == 5
    assert all(R.RULES[r].edge if hasattr(R.RULES[r], "edge") else True for r in R.ORDER3)


# ------------------------------------------------------------------------------------------------ R3K / R3L
def _daily_atr(bk, atr=100.0):
    bk.daily = pd.DataFrame(dict(date=bk.m.days - np.timedelta64(1, "D"), atr=np.full(len(bk.m.days), atr),
                                 nr7=np.zeros(len(bk.m.days), bool)))
    return bk


def test_daily_chandelier_checks_closes_only_and_trails():
    closes = {0: 5050.0, 1: 5400.0, 2: 5150.0}         # session closes (square-off minutes) of days 0, 1, 2
    def path(i, ts):
        t = pd.DatetimeIndex(ts)
        k = (t.normalize() - t.normalize()[0]).days.values
        return np.array([closes.get(int(x), 5100.0) for x in k])
    bk = _daily_atr(make_book(days=4, path=path))
    m = bk.m
    i_in = m.at_or_after(m.days[0], 10 * 60)
    i_end = E.sq_ix(m, m.days[3])
    lv = E.daily_chandelier(bk, i_in, i_end, 1, 5000.0)
    k = np.flatnonzero(np.isfinite(lv))
    assert len(k) == 4                                  # one check per session close
    assert lv[k[0]] == 4800.0                           # 5000 - 2 x 100
    assert lv[k[1]] == 4800.0                           # best 5050 - 300 = 4750 < 4800: never loosens
    assert lv[k[2]] == 5100.0                           # best 5400 - 300
    assert lv[k[3]] == 5100.0


def test_sim_future_lock_exit_and_costs():
    def path(i, ts):
        t = pd.DatetimeIndex(ts)
        k = (t.normalize() - t.normalize()[0]).days.values
        return np.select([k == 0, k == 1, k == 2], [5000.0, 5400.0, 5050.0], 5050.0)
    bk = _daily_atr(make_book(days=6, path=path))
    m = bk.m
    i_sig = m.at_or_after(m.days[0], 10 * 60)
    t = E.sim_future(bk, i_sig, 1, 10, None, i_sig + 1)
    assert t["why"] == "lock" and t["exit"][0] == 5050.0           # day 2 closes 5050 <= 5400 - 300
    assert t["gross"] == pytest.approx(50.0 * bk.units)
    assert t["cost"] > 0 and t["net2"] < t["net"] and not t["model"]
    t2 = E.sim_future(bk, i_sig, -1, 10, None, i_sig + 1)
    assert t2["why"] == "stop"                                      # short: day 1 closes 5400 >= 5000 + 200


def test_r3k_r3l_specs_and_history_trade():
    def brk(i, ts):
        t = pd.DatetimeIndex(ts)
        k = (t.normalize() - t.normalize()[0]).days.values
        return np.where((k == 1) & (t.hour * 60 + t.minute >= 10 * 60), 5100.0, 5000.0)
    bk = _nr7_book(brk)
    per = (date_(2025, 11, 1), date_(2025, 11, 30))
    ck = R.RULES["R3K"].candidates(bk, per)[0]
    cl = R.RULES["R3L"].candidates(bk, per)[0]
    assert ck["r93"] and ck["no_ladder"] and not ck["edge"] and ck["n_sessions"] == 10 and ck["kind"] == "single"
    assert cl["kind"] == "future" and cl["n_sessions"] == 10
    assert S.simulate(bk, cl, None)["kind"] == "future"
    tk = S.simulate(bk, ck, None)
    assert tk is not None and tk["why"] != "edge"
    assert R.RULES["R3K"].twin(bk, ck, bk.m.days[2])["r93"]
    # daily history: an up break, a 2 ATR stop and a chandelier exit on closes
    n = 30
    c = np.full(n, 5000.0)
    d = pd.DataFrame(dict(date=pd.bdate_range("2024-01-01", periods=n), open=c, high=c + 20, low=c - 20, close=c,
                          oi=np.full(n, 1000.0)))
    from research.hunt.r93_breadth_donchian_mcx import rules as R93
    x = R.daily_ind(R93.daily_series(d))
    i = 20
    x.loc[i + 1, ["ah", "ac"]] = [5100.0, 5090.0]
    x.loc[i + 2, "ac"] = 5300.0
    x.loc[i + 3, "ac"] = 5000.0
    assert float(x.atr.values[i]) == pytest.approx(40.0)
    # entry 5020 (the NR level); stop 5020 - 80; closes 5090 (lock 4970), 5300 (lock 5180), 5000 <= 5180 -> out
    assert H.trade_nr7_r93(x, i) == pytest.approx((5000.0 - 5020.0) / 5000.0 - H.COST)
    x.loc[i + 2, "ac"] = 4930.0                          # below the 2 ATR stop / lock (4970) on the second close
    assert H.trade_nr7_r93(x, i) == pytest.approx((4930.0 - 5020.0) / 5000.0 - H.COST)
