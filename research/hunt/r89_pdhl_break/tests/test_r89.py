"""R89 tests: candles and swings, the setup state machine (both readings, close / wick), targets, filters, exits, the
look-ahead guard, option and futures pricing (index on an R51 Day; MCX against R53's sim_option), twins, gates, guard."""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from research.hunt.r89_pdhl_break import cfg as K
from research.hunt.r89_pdhl_break import data, price, rules, study

O = K.NSE_OPEN


# ------------------------------------------------------------------------------------------------ synthetic panels
def nse_panel(days_bars: list, expiry=None, name="NIFTY") -> data.Panel:
    """days_bars: per session a list of (o, h, l, c) minute bars from 09:15."""
    per, days = [], []
    d0 = date(2025, 1, 6)
    for i, bars in enumerate(days_bars):
        a = np.asarray(bars, float)
        per.append((O + np.arange(len(a)), a[:, 0], a[:, 1], a[:, 2], a[:, 3]))
        days.append(d0 + timedelta(days=i))
    exp = np.zeros(len(days), bool) if expiry is None else np.asarray(expiry, bool)
    return data._flat(name, "nse", days, per, exp, np.zeros(len(days), bool), data._nse_sq(exp))


def flat_day(px=100.0, n=375, hi=None, lo=None):
    b = [(px, px + 0.5, px - 0.5, px)] * n
    if hi is not None:
        b[10] = (px, hi, px - 0.5, px)
    if lo is not None:
        b[20] = (px, px + 0.5, lo, px)
    return b


def random_days(n_days=8, seed=0, n=375):
    rng = np.random.default_rng(seed)
    out = []
    px = 100.0
    for _ in range(n_days):
        bars = []
        for _ in range(n):
            o = px
            c = o + rng.normal(0, 0.4)
            h = max(o, c) + abs(rng.normal(0, 0.2))
            lo = min(o, c) - abs(rng.normal(0, 0.2))
            bars.append((o, h, lo, c))
            px = c
        out.append(bars)
    return out


# ------------------------------------------------------------------------------------------------ candles, swings
def test_candles_tf_and_fractals():
    P = nse_panel([flat_day(), [(100, 101 + (i % 7 == 3) * 5, 99, 100) for i in range(375)]])
    C = data.candles(P, 5)
    assert len(C["day"]) == 150 and (C["e"] - C["s"] == 4).all()
    hs, ls = data.fractals(np.array([1, 2, 5, 2, 1, 3, 1.0]), np.array([1, 0, 1, 2, 1, -1, 1.0]), 5)
    assert hs.tolist() == [False, False, True, False, False, False, False]
    hs3, ls3 = data.fractals(np.array([1, 2, 5, 2, 1, 3, 1.0]), np.array([1, 0, 1, 2, 1, -1, 1.0]), 3)
    assert np.flatnonzero(hs3).tolist() == [2, 5] and np.flatnonzero(ls3).tolist() == [1, 5]


def test_levels_and_skip():
    P = nse_panel([flat_day(100, hi=105, lo=96), flat_day(101)])
    assert P.skip[0] and not P.skip[1]
    assert P.pdh[1] == 105 and P.pdl[1] == 96 and P.pclose[1] == 100


# ------------------------------------------------------------------------------------------------ state machine
def _day2(bars_today, prev=None):
    prev = prev or flat_day(100, hi=105, lo=95)
    return nse_panel([prev, bars_today])


def _trig(P, reading="C", tf=1, wick=False):
    return rules.gen_triggers(P, reading, tf, False, wick)["raw"]


def test_continuation_long_break_green_trigger():
    t = flat_day(100, n=375)
    t[30] = (104, 106, 103.8, 105.5)        # closes above PDH 105: the break
    t[31] = (105.5, 106.5, 105.2, 106.0)    # green, close > 105: signal candle (high 106.5)
    t[32] = (106.0, 107.0, 105.9, 106.8)    # breaks 106.5: trigger
    for i in range(33, 375):
        t[i] = (106.8, 107, 106.6, 106.8)
    raw = _trig(_day2(t))
    long_ = raw[raw[:, 1] == 1]
    assert len(long_) >= 1
    first = long_[0]
    P = _day2(t)
    assert P.mt[first[0]] == O + 32 and first[3] - first[4] == -1


def test_break_candle_itself_is_not_the_signal_and_close_back_cancels():
    t = flat_day(100, n=375)
    t[30] = (104, 106, 103.8, 105.5)        # green break candle: must NOT be the signal
    t[31] = (105.5, 107, 105.4, 105.6)      # breaks the break candle's high, but no signal candle yet -> no trigger
    t[32] = (105.6, 105.7, 104.0, 104.5)    # closes back below PDH: setup cancelled
    t[33] = (104.5, 104.8, 104.4, 104.7)    # green but below the level
    t[34] = (104.7, 106, 104.6, 104.8)      # would break 104.8 but the setup is gone
    raw = _trig(_day2(t))
    assert not len(raw[raw[:, 1] == 1])


def test_next_candle_only_then_next_green():
    t = flat_day(100, n=375)
    t[30] = (104, 106, 103.8, 105.5)
    t[31] = (105.5, 106.5, 105.2, 106.0)    # signal 1 (high 106.5)
    t[32] = (106.0, 106.4, 105.6, 105.8)    # red, does not break 106.5 -> signal dropped
    t[33] = (105.8, 106.2, 105.7, 106.1)    # green above 105: signal 2 (high 106.2)
    t[34] = (106.1, 106.3, 106.0, 106.2)    # breaks 106.2 -> trigger on signal 2
    raw = _trig(_day2(t))
    long_ = raw[raw[:, 1] == 1]
    P = _day2(t)
    assert len(long_) == 1 and P.mt[long_[0, 0]] == O + 34


def test_reclaim_long_after_pdl_break():
    t = flat_day(100, n=375)
    t[40] = (96, 96.5, 94.0, 94.5)          # closes below PDL 95: armed (reclaim reading)
    t[41] = (94.5, 95.8, 94.4, 95.6)        # green, back above 95: signal (high 95.8)
    t[42] = (95.6, 96.2, 95.5, 96.0)        # trigger
    for i in range(43, 375):
        t[i] = (96, 96.2, 95.8, 96)
    raw = _trig(_day2(t), "R")
    long_ = raw[raw[:, 1] == 1]
    P = _day2(t)
    assert len(long_) == 1 and P.mt[long_[0, 0]] == O + 42
    # the continuation reading takes the same break as a PUT setup instead
    rawc = _trig(_day2(t), "C")
    assert not len(rawc[(rawc[:, 1] == 1) & (rawc[:, 2] == 1)])


def test_wick_break_arms_on_a_poke():
    t = flat_day(100, n=375)
    t[30] = (104, 105.5, 103.8, 104.9)      # wick above 105, closes below
    t[31] = (104.9, 105.6, 104.8, 105.3)    # green, close > 105: signal
    t[32] = (105.3, 105.9, 105.2, 105.7)    # trigger
    for i in range(33, 375):
        t[i] = (105.7, 105.9, 105.6, 105.7)
    rc = [r for r in _trig(_day2(t), wick=False) if r[1] == 1 and r[2] == 0]
    assert not rc
    rw = [r for r in _trig(_day2(t), wick=True) if r[1] == 1 and r[2] == 0]
    assert len(rw) == 1 and _day2(t).mt[rw[0][0]] == O + 32


def test_signal_window_15_candles():
    t = flat_day(106, n=375)
    t[0] = (104, 106, 103.8, 105.5)         # break at the open (prev close 100 <= 105)
    for i in range(1, 30):
        t[i] = (105.6, 105.7, 105.5, 105.6)  # flat (no green) for 29 candles
    t[30] = (105.6, 106, 105.5, 105.9)      # green but 30 candles after the break: setup expired
    t[31] = (105.9, 106.5, 105.8, 106.2)
    raw = _trig(_day2(t))
    assert not len(raw[raw[:, 1] == 1])


# ------------------------------------------------------------------------------------------------ plan: target, filters
def test_target_needs_confirmed_swing_and_minrr():
    older = flat_day(100)
    older[300] = (100, 112, 99.5, 100)      # a swing high at 112 (two sessions back)
    prev = flat_day(100, hi=105, lo=95)
    t = flat_day(100, n=375)
    t[30] = (104, 106, 103.8, 105.5)
    t[31] = (105.5, 106.5, 105.2, 106.0)    # signal: entry 106.5, stop 105.2, risk 1.3
    t[32] = (106.0, 107.0, 105.9, 106.8)
    for i in range(33, 375):
        t[i] = (106.8, 107, 106.6, 106.8)
    P = nse_panel([older, prev, t])
    v = K.Variant("x")
    G = rules.gen_triggers(P, "C", 1, False, False)
    T = rules.plan(P, v, G, rules.Swings(G["C"], 5))
    long_ = [x for x in T if x["side"] == 1]
    assert len(long_) == 1 and long_[0]["target"] == 112 and long_[0]["stop"] == 105.2
    assert long_[0]["why"] == "time"
    # minRR too high for that swing -> no trade
    T = rules.plan(P, K.Variant("x", minrr=5.0), G, rules.Swings(G["C"], 5))
    assert not [x for x in T if x["side"] == 1]


def test_window_expiry_firstonly_and_cap():
    days = random_days(10, seed=3)
    P = nse_panel(days, expiry=[0] * 9 + [1])
    G = rules.gen_triggers(P, "C", 1, False, False)
    SW = rules.Swings(G["C"], 5)
    base = rules.plan(P, K.Variant("b"), G, SW)
    win = rules.plan(P, K.Variant("w", win=True), G, SW)
    f1 = rules.plan(P, K.Variant("f", first_only=True), G, SW)
    assert all(P.mt[x["t"]] >= K.NSE_WIN_FIRST for x in win) and not [x for x in win if x["day"] == 9]
    assert all(K.NSE_FIRST <= P.mt[x["t"]] <= K.NSE_LAST for x in base)
    per = pd.Series([x["day"] for x in base]).value_counts()
    assert per.max() <= K.MAX_PER_DAY
    assert pd.Series([x["day"] for x in f1]).value_counts().max() == 1
    # one position at a time
    for a, b in zip(base, base[1:]):
        if a["day"] == b["day"]:
            assert b["t"] > a["xm"]


def test_htf_filter_direction():
    days = random_days(30, seed=5)
    P = nse_panel(days)
    G = rules.gen_triggers(P, "C", 1, False, False)
    SW = rules.Swings(G["C"], 5)
    T = rules.plan(P, K.Variant("h", htf=True), G, SW)
    for x in T:
        assert P.htf_ok(x["day"], int(P.mt[x["t"]]), x["side"])
        assert (P.daily_up if x["side"] > 0 else P.daily_dn)[x["day"]]


def test_underlying_exit_rules():
    mo = np.array([100, 100, 100, 100, 100.0])
    mh = np.array([101, 101, 103, 101, 101.0])
    ml = np.array([99, 99, 97, 99, 99.0])
    mc = np.array([100, 100, 100, 100, 100.0])
    # stop and target in the same minute: stop first
    assert rules.underlying_exit(mo, mh, ml, mc, 0, 1, 98.0, 102.0, 4) == (2, 1)
    # trigger-minute close through the stop
    assert rules.underlying_exit(mo, mh, ml, np.array([97.5, 100, 100, 100, 100.0]), 0, 1, 98.0, 110.0, 4) == (0, 1)
    assert rules.underlying_exit(mo, mh, ml, mc, 0, 1, 90.0, 110.0, 4) == (4, 3)
    assert rules.underlying_exit(mo, mh, ml, mc, 0, -1, 102.0, 98.0, 4) == (2, 1)


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("reading,tf,sr,wick", [("C", 1, False, False), ("R", 1, False, False), ("C", 3, True, False),
                                                ("R", 5, False, True), ("C", 1, True, True)])
def test_look_ahead_guard(reading, tf, sr, wick):
    """Every trigger at or before the cut minute, with its entry, stop, target and HTF state, is identical when every
    minute after the cut is replaced by noise."""
    days = random_days(12, seed=11)
    rng = np.random.default_rng(99)
    for cut_day, cut_min in ((9, 120), (11, 300), (10, 5)):
        noisy = [list(b) for b in days]
        for d in range(cut_day, len(noisy)):
            start = cut_min + 1 if d == cut_day else 0
            for i in range(start, 375):
                o = 50 + rng.normal(0, 5)
                c = o + rng.normal(0, 3)
                noisy[d][i] = (o, max(o, c) + 1, min(o, c) - 1, c)
        res = []
        for dd in (days, noisy):
            P = nse_panel(dd)
            G = rules.gen_triggers(P, reading, tf, sr, wick)
            SW = rules.Swings(G["C"], 5)
            cut = P.ms[cut_day] + cut_min
            T = rules.plan(P, K.Variant("g", reading=reading, tf=tf, sr=sr, wick=wick, htf=False), G, SW)
            keep = [(x["t"], x["side"], x["entry"], x["stop"], x["target"], P.htf_ok(x["day"], int(P.mt[x["t"]]), x["side"]))
                    for x in T if x["t"] <= cut]
            raw = [tuple(r) for r in G["raw"] if r[0] <= cut]
            lev = [tuple(np.nan_to_num(P.levels(d, sr))) for d in range(cut_day + 1)]
            res.append((keep, raw, lev))
        assert res[0][1] == res[1][1]
        assert res[0][2] == res[1][2]
        # trades before the cut can differ only through the one-open-position rule (an exit after the cut); compare the
        # triggers' own plan where the previous trade closed before the cut
        a = {x[0]: x for x in res[0][0]}
        b = {x[0]: x for x in res[1][0]}
        for t in set(a) & set(b):
            assert a[t] == b[t]


# ------------------------------------------------------------------------------------------------ pricing
def test_lots_and_fut_charges():
    assert price.lots_for(10.0, 75) == 3 and price.lots_for(100.0, 75) == 1 and price.lots_for(30.0, 65) == 1
    assert price.lots_for(0.0, 65) == 1
    c1 = price.idx_fut_charges(25000 * 75, 25010 * 75, date(2025, 6, 1))
    c2 = price.idx_fut_charges(25000 * 75, 25010 * 75, date(2026, 6, 1))
    # STT on the sell: 0.02% (Oct 2024 - Mar 2026), 0.05% from 1 Apr 2026, on about Rs 18.8 lakh a NIFTY lot
    assert 500 < c1 < 600 and 1000 < c2 < 1200


def test_index_option_pricing_on_an_r51_day():
    import r51data
    idx = np.full((4, 375), 100.0)
    atm = np.full(375, 100.0)
    o = np.full(375, 10.0)
    o[11] = 10.0
    o[16] = 12.0
    C = np.vstack([o, o + 0.5, o - 0.5, o, np.full(375, 1e6)]).astype(np.float32)
    # NIFTY step 50: ATM strike 100 -> the 1-ITM call is the 50 strike
    D = r51data.Day(date(2025, 6, 2), idx, atm, {("C", 50.0): C}, False)
    net, gross, rE, rX, ok = price.idx_option_day("NIFTY", D, ["C"], [10], [16], [75.0])
    assert ok[0]
    assert rE[0] == pytest.approx(round(10.0 * 1.0005, 2) + 0.05)        # next-minute open + 5 bps + 1 tick
    assert rX[0] == pytest.approx(round(12.0 * 0.9995, 2) - 0.05)        # forced exit at the open of minute 16
    assert gross[0] == pytest.approx((12.0 - 10.0) * 75)
    assert net[0] < gross[0]
    # a forced exit before the fill is moved to the fill minute (in and out at once: spreads + charges only)
    net2, gross2, *_ = price.idx_option_day("NIFTY", D, ["C"], [10], [5], [75.0])
    assert gross2[0] == 0 and net2[0] < 0


def _mcx_market():
    from research.hunt.r53.mcx import data as D53
    ts = pd.date_range("2025-09-01 09:00", periods=200, freq="min")
    F = 5000 + np.r_[np.zeros(50), np.linspace(0, 40, 50), np.linspace(40, -20, 100)]
    rows = []
    for i, t in enumerate(ts):
        for cp, off in ((0, -1), (1, 1)):
            k = 4950.0 if cp == 0 else 5050.0
            px = max(F[i] - k, 0) + 30 if cp == 0 else max(k - F[i], 0) + 30
            if i % 7 == 3:
                continue                      # unprinted minutes
            rows.append(dict(ts=t, cp=cp, offset=off, strike=k, open=px, high=px + 1, low=px - 1, close=px + 0.5,
                             volume=10, f1=False))
            rows.append(dict(ts=t, cp=cp, offset=0, strike=5000.0, open=30, high=31, low=29, close=30, volume=10,
                             f1=False))
    return D53.Market("GOLDM", ts.values, F, pd.DataFrame(rows))


def test_mcx_option_sim_matches_r53():
    from research.hunt.r53.mcx.sim import sim_option
    m = _mcx_market()
    B = price.McxBook(m)
    for j, side, stopF, tgtF in ((55, 1, 5000.0, 5035.0), (120, -1, 5045.0, 4990.0), (60, 1, 4900.0, 6000.0)):
        r53 = sim_option(m, j, side, exit_i=190, fam="gold", mult=10.0, strike="itm1", stopF=stopF, tgtF=tgtF)
        mine = price.mcx_one(B, "GOLDM", j, side, 190, stopF, tgtF)
        assert r53 is not None and mine is not None
        assert mine["fill"] == pytest.approx(r53["fill"]) and mine["px"] == pytest.approx(r53["exit"])
        assert mine["gross"] == pytest.approx(r53["gross"])


def test_twins_keep_side_count_and_window():
    days = random_days(6, seed=8)
    P = nse_panel(days)
    G = rules.gen_triggers(P, "C", 1, False, False)
    T = rules.plan(P, K.Variant("b"), G, rules.Swings(G["C"], 5))
    rows = [dict(x, date=P.days[x["day"]]) for x in T]
    rng = np.random.default_rng(1)
    tw = price.twins(P, rows, K.NSE_FIRST, K.NSE_LAST, 50, rng, "fut")
    assert tw.shape == (50,) and np.isfinite(tw).all()
    pool = price._pool(P, 2, K.NSE_WIN_FIRST, K.NSE_LAST)
    assert (P.mt[pool] >= K.NSE_WIN_FIRST).all() and (pool < P.sq[2]).all()


# ------------------------------------------------------------------------------------------------ study pieces
def test_bh_and_stats_and_gates():
    q = study.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5)
    T = pd.DataFrame(dict(date=[date(2025, 1, 2)] * 3 + [date(2025, 2, 3)] * 2, net=[100.0, -50, 20, -14000, 30]))
    s = study.stats(T, 10)
    assert s["trades"] == 5 and s["worst_month"] == -13970 and s["ex_top5"] == 0 and s["per_day"] == 0.5
    r = dict(inst="NIFTY", opt=dict(trades=50, net=10, twin_p=0.01, ex_top5=1, worst_month=-100), q=0.05,
             folds=[dict(net=1), dict(net=1), dict(net=1), dict(net=-1)])
    assert study.design_gates(r)["pass"]
    r["folds"][1]["net"] = -1
    assert not study.design_gates(r)["pass"]


def test_holdout_guard():
    with pytest.raises(PermissionError):
        study.check(date(2025, 1, 1), date(2026, 4, 2))
    study.check(date(2025, 1, 1), date(2026, 3, 31))


def test_variants_declared():
    names = [v.name for v in K.VARIANTS]
    assert len(names) == 20 and len(set(names)) == 20
    assert {v.gen_key for v in K.VARIANTS} == {(r, tf, sr, wk) for r in "CR" for tf, sr, wk in
                                               ((1, False, False), (1, True, False), (1, False, True), (3, False, False),
                                                (5, False, False))}
