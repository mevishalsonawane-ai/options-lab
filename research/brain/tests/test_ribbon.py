"""R29 EMA ribbon features (features/ribbon.py, 14 Oct 2026): the look-ahead guard on every ribbon feature, warm-up
independence, the pattern definitions on hand-made bars, and the edge cases (empty data, gaps, flat prices, a market
holiday between sessions, a short expiry-day session)."""
import numpy as np
import pandas as pd
import pytest

from research.brain.features import REGISTRY, compute, load_all
from research.brain.features import ribbon as R
from research.brain.features.registry import Ctx, warmup_sessions
from research.brain.guard import check_features


def _minutes(n_days=24, seed=3, start="2025-01-06", drift=None, holiday=None, short_last=False, noise=6.0):
    """Synthetic cube minutes: n_days sessions of 375 one-minute bars, a random walk with trending stretches."""
    rng = np.random.default_rng(seed)
    days = [d for d in pd.bdate_range(start, periods=n_days + (1 if holiday else 0)) if d != holiday]
    out, px = [], 20000.0
    for i, d in enumerate(days):
        n = 375
        if short_last and i == len(days) - 1:
            n = 200
        mu = drift[i % len(drift)] if drift else (3.0 if (i // 4) % 2 == 0 else -3.0)
        r = rng.normal(mu / 375, noise, n)
        c = px + np.cumsum(r)
        o = np.r_[px, c[:-1]]
        h = np.maximum(o, c) + rng.uniform(0, 4, n)
        lo = np.minimum(o, c) - rng.uniform(0, 4, n)
        ts = d + pd.Timedelta(hours=9, minutes=15) + pd.to_timedelta(np.arange(n), "min")
        out.append(pd.DataFrame(dict(ts=ts, day=d, minute=np.arange(n), flag="ok", open=o, high=h, low=lo, close=c)))
        px = c[-1] + rng.normal(0, 20)                                   # overnight gap
    return pd.concat(out, ignore_index=True)


def _names():
    load_all()
    return sorted(n for n in REGISTRY if n.startswith(("rib", "cls")))


def test_registered():
    names = _names()
    assert len(names) == 4 * 19 + 1                                       # 19 per bar size + the MTF agreement
    assert "rib15_pullback" in names and "cls5_stack" in names and "rib1_slope_e200" in names
    for n in names:
        f = REGISTRY[n]
        assert f.family == "indicators" and f.description and f.lookback.endswith("d")
    assert warmup_sessions(["rib15_stack"]) == 150 and warmup_sessions(["rib1_stack"]) == 10


def test_every_ribbon_feature_is_point_in_time():
    M = _minutes(24)
    days = sorted(M.day.unique())
    cuts = [days[-1] + pd.Timedelta(hours=9, minutes=m) for m in (15, 29, 44, 100, 223, 374)]
    cuts += [days[-2] + pd.Timedelta(hours=15, minutes=29), days[-3] + pd.Timedelta(hours=11, minutes=2)]
    F = compute(M, None, "NIFTY", _names())
    assert F["rib15_stack"].notna().sum() > 0 and F["rib5_pullback"].notna().sum() > 0   # the guard tests real values
    assert not check_features(M, None, "NIFTY", cuts, names=_names())


def test_guard_would_catch_a_leaky_ribbon():
    """The same guard flags a ribbon built on the NEXT bar's close (sanity check that the test can fail)."""
    from research.brain.features.registry import Feature

    def leaky(ctx):
        b = R.ribbon_bars(ctx, 5)
        v = np.r_[b["stack"].values[1:], np.nan]
        from research.brain.features.bars import to_rows
        return to_rows(ctx, b, v)
    REGISTRY["_canary_rib"] = Feature("_canary_rib", "indicators", 1, ("minutes.close",), "0m", "", leaky)
    try:
        M = _minutes(12)
        d = sorted(M.day.unique())[-1]
        bad = check_features(M, None, "NIFTY", [d + pd.Timedelta(hours=11)], names=["_canary_rib", "rib5_stack"])
    finally:
        del REGISTRY["_canary_rib"]
    assert set(bad) == {"_canary_rib"}


def test_declared_lookback_is_enough_for_1_minute_ribbon():
    """With 10 sessions of warm-up the 1-minute ribbon equals the one with 8 more sessions loaded."""
    M = _minutes(20)
    days = sorted(M.day.unique())
    names = [n for n in _names() if n.startswith(("rib1_", "cls1_"))]
    w = warmup_sessions(names)
    a = compute(M[M.day >= days[-1 - w]], None, "NIFTY", names)
    b = compute(M[M.day >= days[-9 - w]], None, "NIFTY", names)
    a, b = a[a.ts.dt.normalize() == days[-1]], b[b.ts.dt.normalize() == days[-1]]
    for n in names:
        assert np.allclose(a[n].values, b[n].values, rtol=1e-9, atol=1e-9, equal_nan=True), n


def test_stack_order_and_twist():
    E = np.array([[5, 4, 3, 2], [5, 4, 2, 3], [1, 2, 3, 4], [2, 1, 3, 4], [4, 3, 2, 1], [np.nan, 1, 2, 3]], float)
    s, o = R.stack_state(E)
    assert list(s[:5]) == [1, 0, -1, 0, 1] and np.isnan(s[5])
    assert o[0] == 1 and o[2] == -1 and o[1] == pytest.approx(1 / 3) and np.isnan(o[5])
    tw = R.twist(np.array([np.nan, 0, 1, 0, 1, -1, 0, -1, 1]))
    assert np.isnan(tw[0]) and list(tw[1:]) == [0, 0, 0, 0, -1, 0, 0, 1]   # the first full stack is not a twist


def test_pullback_needs_a_full_stack_a_touch_and_a_close_back():
    stack = np.array([1, 1, 1, 0, -1, -1])
    e21 = np.full(6, 100.0)
    e34 = np.full(6, 98.0)
    e34[4:] = 102.0                                                       # bearish: EMA34 above EMA21
    h = np.array([103, 103, 101, 103, 101, 99])
    lo = np.array([99.5, 101, 97, 99, 96, 95])
    c = np.array([101, 102, 99.5, 101, 99, 98])
    p = R.pullback(stack, h, lo, c, e21, e34)
    # 0: touched and closed back above -> +1; 1: no touch; 2: closed below EMA21 (a break, not a pullback);
    # 3: mixed stack; 4: bearish, high touched EMA21, closed back below -> -1; 5: bearish, no touch
    assert list(p) == [1, 0, 0, 0, -1, 0]


def test_squeeze_break():
    n = 70
    w = np.r_[np.linspace(3, 1.2, 60), 1.0, 1.1, 1.3, np.full(7, 1.3)]   # narrowest at bar 60, then widens
    order = np.full(n, 1.0)
    pos = np.full(n, 1.0)
    sq = R.squeeze_break(w, order, pos)
    assert sq[62] == 1 and sq[61] == 0 and sq[63] == 0                  # bar 63 is not widening any more
    assert R.squeeze_break(w, -order, -pos)[62] == -1
    assert R.squeeze_break(w, order, np.zeros(n))[62] == 0             # close inside the ribbon: no break
    assert np.isnan(R.squeeze_break(w, order, np.full(n, np.nan))[62])


def test_values_on_a_steady_uptrend():
    M = _minutes(14, drift=[150.0], noise=0.3)                           # a steady rise every day, little noise
    F = compute(M, None, "NIFTY", ["rib1_order", "rib1_pos", "rib5_stack", "cls5_stack", "rib1_width_atr",
                                   "rib1_slope_e21", "rib_mtf_align"])
    last = F[F.ts.dt.normalize() == F.ts.dt.normalize().max()]
    assert last.rib1_order.mean() > 0.5
    assert (last.rib5_stack == 1).mean() > 0.9 and (last.cls5_stack == 1).mean() > 0.9
    assert (last.rib1_width_atr > 0).all() and last.rib1_slope_e21.mean() > 0
    assert last.rib_mtf_align.notna().all() and last.rib_mtf_align.mean() > 1    # 14 sessions = 350 15-min bars
    early = F[F.ts.dt.normalize() == F.ts.dt.normalize().min()]
    assert early.rib_mtf_align.isna().all()                              # the 15-minute ribbon needs 200 bars first


def test_empty_gaps_flat_holiday_and_short_session():
    load_all()
    names = _names()
    # empty
    E = _minutes(1).iloc[:0]
    F = compute(E, None, "NIFTY", names)
    assert len(F) == 0
    # a gap of 30 minutes with no prices: those rows are NaN, never filled; the bar containing them still exists
    M = _minutes(12)
    d = sorted(M.day.unique())[-1]
    gap = (M.day == d) & M.minute.between(100, 129)
    M.loc[gap, "flag"] = "gap"
    F = compute(M, None, "NIFTY", ["rib1_stack", "rib5_stack"])
    g = F[(F.ts >= d + pd.Timedelta(hours=9, minutes=15 + 100)) & (F.ts < d + pd.Timedelta(hours=9, minutes=15 + 130))]
    assert len(g) == 30
    # flat prices: ATR 0 -> widths NaN, never inf
    Fl = _minutes(10)
    for c in ("open", "high", "low", "close"):
        Fl[c] = 20000.0
    F = compute(Fl, None, "NIFTY", ["rib1_width_atr", "rib1_slope_e8", "rib1_stack"])
    assert not np.isinf(F.rib1_width_atr).any() and F.rib1_width_atr.isna().all()
    assert set(F.rib1_stack.dropna().unique()) <= {0.0}
    # a market holiday between sessions and a short last session (an expiry/special day): no error, last bars used
    H = _minutes(12, holiday=pd.Timestamp("2025-01-10"), short_last=True)
    F = compute(H, None, "NIFTY", ["rib1_stack", "rib15_stack"])
    assert len(F) == len(H) and F.rib1_stack.iloc[-1] in (-1.0, 0.0, 1.0)


def test_ribbon_bars_shared_with_research():
    """A study reads the same per-bar numbers the store writes (ribbon_bars), stamped on the bar's last minute."""
    M = _minutes(12)
    ctx = Ctx(M, None, "NIFTY")
    b = R.ribbon_bars(ctx, 5)
    F = compute(M, None, "NIFTY", ["rib5_order"])
    j = b.dropna(subset=["order"]).iloc[-1]
    assert F.rib5_order.iloc[int(j.last_row)] == j.order
    assert {"e5", "e200", "atr", "stack", "pullback", "sqz_break", "twist"} <= set(b.columns)
