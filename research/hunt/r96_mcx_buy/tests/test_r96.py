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
