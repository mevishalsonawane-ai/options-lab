"""R43 unit tests on synthetic data (no lake files needed): python3 -m pytest research/hunt/r43/test_r43.py -q"""
from __future__ import annotations

import math
import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r43cfg as K  # noqa: E402
import r43data as D  # noqa: E402
import r43lib as L  # noqa: E402
import r43sig as S  # noqa: E402
import r25data as D25  # noqa: E402
from r25data import C, H, IV, N, O, OI, V, Day  # noqa: E402
from r25data import L as LO  # noqa: E402


# ------------------------------------------------------------------------------------------------ indicators by hand
def test_ema_hand():
    e = S.ema([1, 2, 3, 4, 5], 3)                       # alpha 0.5, seed = mean(1, 2, 3) = 2
    assert np.isnan(e[:2]).all()
    assert e[2:] == pytest.approx([2.0, 3.0, 4.0])


def test_rma_hand_and_leading_nan():
    r = S.rma([np.nan, 3, 6, 9, 0], 3)                  # alpha 1/3, seed = mean(3, 6, 9) = 6 at index 3
    assert np.isnan(r[:3]).all()
    assert r[3] == pytest.approx(6.0)
    assert r[4] == pytest.approx(0 / 3 + 6 * 2 / 3)


def test_rma_short_series_all_nan():
    assert np.isnan(S.rma([1.0, 2.0], 3)).all()
    assert np.isnan(S.ema([], 3)).all()


def test_rsi_wilder_hand():
    r = S.rsi([10, 11, 10, 12], 2)
    # changes 1, -1, 2: gains 1, 0, 2; losses 0, 1, 0; seed (index 2) avg gain 0.5, avg loss 0.5 -> 50
    assert np.isnan(r[:2]).all()
    assert r[2] == pytest.approx(50.0)
    # index 3: gain 0.5 * 2 + 0.5 * 0.5 = 1.25, loss 0.5 * 0 + 0.5 * 0.5 = 0.25, rs 5 -> 100 - 100 / 6
    assert r[3] == pytest.approx(100 - 100 / 6)


def test_rsi_no_losses_is_100_no_gains_is_0():
    assert S.rsi([1, 2, 3, 4, 5], 2)[-1] == pytest.approx(100.0)
    assert S.rsi([5, 4, 3, 2, 1], 2)[-1] == pytest.approx(0.0)


def test_adx_wilder_hand():
    h = [10, 11, 12, 11, 13]
    lo = [9, 10, 10, 9, 11]
    c = [9.5, 10.5, 11, 10, 12]
    a = S.adx(h, lo, c, 2)
    # +DM 1, 1, 0, 2; -DM 0, 0, 1, 0; TR 1.5, 2, 2, 3 (by hand)
    atr = [1.75, 0.5 * 2 + 0.5 * 1.75, 0.5 * 3 + 0.5 * 1.875]
    pdm = [1.0, 0.5, 1.25]
    mdm = [0.0, 0.5, 0.25]
    pdi = [100 * p / t for p, t in zip(pdm, atr)]
    mdi = [100 * m / t for m, t in zip(mdm, atr)]
    dx = [abs(p - m) / (p + m) for p, m in zip(pdi, mdi)]
    assert np.isnan(a[:3]).all()
    assert a[3] == pytest.approx(100 * (dx[0] + dx[1]) / 2)
    assert a[4] == pytest.approx(100 * (0.5 * dx[2] + 0.5 * (dx[0] + dx[1]) / 2))
    assert a[3] == pytest.approx(50.0) and a[4] == pytest.approx(100 * (0.5 * 2 / 3 + 0.25))


# ------------------------------------------------------------------------------------------------ crossovers
def test_crossover_edges():
    a = np.array([1, 2, 2, 3, 2, 3, np.nan, 3])
    b = np.array([2, 2, 2, 2, 2, 2, 2, 2])
    co = S.crossover(a, b)
    cu = S.crossunder(a, b)
    # equal before then above counts (a[1] <= b[1]); touching (2 = 2) is not a cross; the first bar never crosses
    assert co.tolist() == [False, False, False, True, False, True, False, False]
    # 3 -> 2 is a touch, not a cross under (a < b fails); NaN neighbours never cross
    assert cu.tolist() == [False] * 8
    assert S.crossunder([3, 1], 2).tolist() == [False, True]
    assert S.crossunder([2, 1], 2).tolist() == [False, True]       # equal before then below counts


def _bars(c: np.ndarray, days: int = 3) -> pd.DataFrame:
    n = len(c)
    d0 = date(2024, 1, 1)
    d = [date.fromordinal(d0.toordinal() + i // 75) for i in range(n)]
    j = [i % 75 for i in range(n)]
    o = np.r_[c[0], c[:-1]]
    return pd.DataFrame({"d": d, "j": j, "o": o, "h": np.maximum(o, c) + 1, "l": np.minimum(o, c) - 1, "c": c})


def test_signal_families_fire_as_defined():
    c = np.r_[np.full(30, 100.0), np.linspace(100, 40, 30), np.linspace(40, 130, 40)]
    b = _bars(c)
    sg = S.signals(b)
    assert set(sg.columns) == set(K.SIGNALS)
    r = S.rsi(c, 14)
    # rsi_fade CE exactly where RSI crosses below 30; PE where it crosses above 70
    assert (sg.rsi_fade.values == 1).tolist() == S.crossunder(r, 30).tolist()
    assert (sg.rsi_fade.values == -1).tolist() == S.crossover(r, 70).tolist()
    assert (sg.rsi_momo.values == 1).tolist() == S.crossover(r, 55).tolist()
    # donchian: a close above the prior 20 highs on the rally; never on the first 20 bars
    assert (sg.donchian20.values[:20] == 0).all()
    assert (sg.donchian20.values[60:] == 1).any() and (sg.donchian20.values[30:60] == -1).any()
    # 1b is the same entries as 1
    assert (sg.ema821_adx15_lock.values == sg.ema821_adx15.values).all()
    e8, e21, ax = S.ema(c, 8), S.ema(c, 21), S.adx(b.h.values, b.l.values, c, 14)
    want = np.where(S.crossover(e8, e21) & (ax > 15), 1, np.where(S.crossunder(e8, e21) & (ax > 15), -1, 0))
    assert (sg.ema821_adx15.values == want).all()
    assert (sg.ema821_adx15.values == 1).any()


def test_signals_lookahead_guard():
    """The brain's guard style: the signal at bar i is the same when the future is cut off and when it is poisoned."""
    rng = np.random.default_rng(1)
    c = 20000 + np.cumsum(rng.normal(0, 15, 600))
    b = _bars(c)
    full = S.signals(b)
    for i in rng.integers(40, 599, 25):
        cut = S.signals(b.iloc[: i + 1].copy())
        assert (cut.iloc[i].values == full.iloc[i].values).all()
        poisoned = b.copy()
        poisoned.loc[poisoned.index[i + 1:], ["o", "h", "l", "c"]] = 1e9
        assert (S.signals(poisoned).iloc[i].values == full.iloc[i].values).all()


# ------------------------------------------------------------------------------------------------ 5-minute bars, gaps
def test_bars5_gaps_and_empty_bucket():
    rows = []
    d = date(2024, 6, 6)
    for m in (0, 1, 2, 4, 10, 11):                     # minute 3 missing; bucket 1 (rows 5-9) empty
        rows.append(dict(d=d, m=m, open=100 + m, high=101 + m, low=99 + m, close=100.5 + m))
    rows.append(dict(d=d, m=12, open=np.nan, high=np.nan, low=np.nan, close=np.nan))
    b = D.bars5_from_minutes(pd.DataFrame(rows))
    assert b.j.tolist() == [0, 2]
    assert b.iloc[0][["o", "h", "l", "c"]].tolist() == [100, 105, 99, 104.5]
    assert b.iloc[1][["o", "c"]].tolist() == [110, 111.5]


def test_bars5_empty_input():
    b = D.bars5_from_minutes(pd.DataFrame(columns=["d", "m", "open", "high", "low", "close"]))
    assert len(b) == 0


# ------------------------------------------------------------------------------------------------ expiry-day filter
def test_expiry_days_rule_and_split():
    idx = [date(2024, 12, 24), date(2024, 12, 26), date(2024, 12, 27), date(2025, 1, 2), date(2025, 1, 3)]
    tv = pd.Series([1.5, 0.3, 1.8, 0.2, 0.25], index=idx)
    # 26 Dec: tiny and the next session's > 2x -> expiry; 2 Jan: next is 0.25 (not > 2x) -> not; last never
    assert D25.expiry_days(tv) == [date(2024, 12, 26)]
    assert not K.is_holdout(date(2024, 12, 31)) and K.is_holdout(date(2025, 1, 1))


# ------------------------------------------------------------------------------------------------ synthetic expiry day
SPOT = 20000.0


def _flat(px: float, vol: float = 5000.0) -> np.ndarray:
    a = np.full((N, 7), np.nan)
    a[:, [O, H, LO, C]] = px
    a[:, V] = vol
    a[:, OI] = 1e5
    a[:, IV] = 20.0
    return a


def make_day(prices=None, und="NIFTY", d=date(2024, 6, 6)) -> Day:
    ix = np.full((N, 4), SPOT)
    opts = {}
    base = {50: 40.0, 100: 20.0, 150: 8.0, 200: 3.0}
    for off, p in base.items():
        opts[(SPOT + off, "C")] = _flat(p)
        opts[(SPOT - off, "P")] = _flat(p)
    for k, a in (prices or {}).items():
        opts[k] = a
    edge = np.full((N, 2), 1.0)
    return Day(d, und, ix, opts, np.full(N, 14.0), SPOT, 14.0, 25, edge, 100.0)


def test_pick_strike_rules():
    day = make_day()
    t = L.sig_row(40)
    assert L.pick_strike(day, 1, t, "cheap") == (SPOT + 100, 20.0)      # nearest OTM in Rs 5-30
    assert L.pick_strike(day, -1, t, "cheap") == (SPOT - 100, 20.0)
    assert L.pick_strike(day, 1, t, "otm2") == (SPOT + 100, 20.0)       # second OTM strike
    assert L.pick_strike(day, -1, t, "otm2") == (SPOT - 100, 20.0)


def test_pick_strike_stale_and_empty():
    a = _flat(20.0)
    a[:, V] = 0.0                                       # never traded
    day = make_day({(SPOT + 100, "C"): a})
    assert L.pick_strike(day, 1, L.sig_row(40), "otm2") is None
    assert L.pick_strike(day, 1, L.sig_row(40), "cheap") == (SPOT + 150, 8.0)
    empty = Day(date(2024, 6, 6), "NIFTY", np.full((N, 4), np.nan), {}, None, np.nan, np.nan, 25, None, np.nan)
    assert L.pick_strike(empty, 1, 100, "cheap") is None and L.pick_strike(empty, -1, 100, "otm2") is None
    assert L.day_table(empty) == []


def test_entry_is_next_bar_open_no_lookahead():
    a = _flat(20.0)
    j = 40
    a[L.sig_row(j) + 1:, [O, H, LO, C]] = 22.0          # the next bar opens at 22 (within the 1.2x + 0.10 cap)
    day = make_day({(SPOT + 100, "C"): a})
    tr = L.simulate(day, SPOT + 100, 1, L.sig_row(j), 20.0, "hold")
    assert tr.fill_row == 5 * (j + 1)                   # the NEXT 5-minute bar's first minute
    assert tr.entry == pytest.approx(L.L.buy_px(22.0, 5000 * 5 / 25))
    # the outcome at the signal row cannot see the future: poisoning rows after the signal leaves the strike pick alone
    b = a.copy()
    b[L.sig_row(j) + 1:, [O, H, LO, C]] = 1e6
    day2 = make_day({(SPOT + 100, "C"): b})
    assert L.pick_strike(day2, 1, L.sig_row(j), "otm2") == L.pick_strike(day, 1, L.sig_row(j), "otm2")


def test_no_fill_when_price_runs_above_cap():
    a = _flat(20.0)
    a[L.sig_row(40) + 1:, [O, H, LO, C]] = 30.0         # > 20 x 1.2 + 0.10 for the 3 tries
    day = make_day({(SPOT + 100, "C"): a})
    assert L.simulate(day, SPOT + 100, 1, L.sig_row(40), 20.0, "hold") is None


def _path(seq: list[tuple[int, float]]) -> np.ndarray:
    """A price path: (row, price) steps, flat in between."""
    a = _flat(seq[0][1])
    for r, p in seq:
        a[r:, [O, H, LO, C]] = p
    return a


def test_stop_at_minus_50():
    j = 40
    fr = 5 * (j + 1)
    a = _path([(0, 20.0), (fr + 10, 9.0)])
    day = make_day({(SPOT + 100, "C"): a})
    tr = L.simulate(day, SPOT + 100, 1, L.sig_row(j), 20.0, "hold")
    assert tr.exits[-1][3] == "stop" and tr.exits[-1][0] == fr + 11
    assert tr.net < 0


def test_hold_to_1505_and_trail_and_time():
    j = 40
    fr = 5 * (j + 1)
    # runs to 80 (about 4x), falls to 35: hold keeps to 15:05; trail sells on the close <= 50% of the best close
    a = _path([(0, 20.0), (fr + 5, 80.0), (fr + 20, 35.0)])
    day = make_day({(SPOT + 100, "C"): a})
    h = L.simulate(day, SPOT + 100, 1, L.sig_row(j), 20.0, "hold")
    t = L.simulate(day, SPOT + 100, 1, L.sig_row(j), 20.0, "trail")
    assert h.exits[-1][3] == "1505"
    assert t.exits[-1][3] == "trail" and t.exits[-1][0] == fr + 21
    # time: never reaches 2x within 60 minutes -> sells after the close 60 minutes after the fill
    b = _path([(0, 20.0), (fr + 30, 25.0)])
    day = make_day({(SPOT + 100, "C"): b})
    tm = L.simulate(day, SPOT + 100, 1, L.sig_row(j), 20.0, "time")
    assert tm.exits[-1][3] == "time" and tm.exits[-1][0] == fr + L.K.TIME_N + 1
    # a 2x close inside the 60 minutes switches the time stop off
    c = _path([(0, 20.0), (fr + 30, 45.0), (fr + 40, 30.0)])
    day = make_day({(SPOT + 100, "C"): c})
    assert L.simulate(day, SPOT + 100, 1, L.sig_row(j), 20.0, "time").exits[-1][3] == "1505"


def test_step_lock_levels_and_exit():
    assert L.step_lock(1.1) == -math.inf
    assert L.step_lock(1.25) == 1.0 and L.step_lock(1.6) == 1.25 and L.step_lock(1.8) == 1.5
    assert L.step_lock(2.5) == 1.75 and L.step_lock(3.0) == 2.0 and L.step_lock(7.9) == 6.0
    j = 40
    fr = 5 * (j + 1)
    a = _path([(0, 20.0), (fr + 5, 31.0), (fr + 9, 24.0)])     # best 1.5x (31/fill) locks 1.25x; 24 < lock
    day = make_day({(SPOT + 100, "C"): a})
    tr = L.simulate(day, SPOT + 100, 1, L.sig_row(j), 20.0, "step")
    assert tr.exits[-1][3] == "lock" and tr.exits[-1][0] == fr + 10


def test_budget_sizing_and_zero_lots():
    j = 40
    day = make_day()
    tr = L.simulate(day, SPOT + 100, 1, L.sig_row(j), 20.0, "hold")
    assert tr.lots == int(5000 // (tr.entry * 25)) and tr.qty == tr.lots * 25
    day = make_day({(SPOT + 100, "C"): _flat(300.0)})
    assert L.simulate(day, SPOT + 100, 1, L.sig_row(j), 300.0, "hold") is None      # 1 lot > Rs 5,000


# ------------------------------------------------------------------------------------------------ rules, twins, BH
def _table():
    t = {}
    for j, net in ((10, -100.0), (20, 500.0), (45, -50.0)):
        for sgn in (1, -1):
            t[("NIFTY", date(2024, 6, 6), sgn, "cheap", j, "hold")] = dict(net=net * sgn, risked=1000.0, peak=1.0,
                                                                          xmult=1.0, proxy=False, strike=1.0, ref=1.0)
    return t


def test_rule_trades_first_signal_with_trade_one_per_day():
    tb = _table()
    sigs = {("NIFTY", date(2024, 6, 6)): {"rsi_momo": {5: 1, 10: -1, 20: 1, 45: 1}}}
    tr = L.rule_trades(tb, sigs, ("rsi_momo", "cheap", "all", "hold"))
    assert len(tr) == 1 and tr[0]["j"] == 10 and tr[0]["side"] == -1          # bar 5 had no trade in the table
    tr = L.rule_trades(tb, sigs, ("rsi_momo", "cheap", "hero", "hold"))
    assert len(tr) == 1 and tr[0]["j"] == 45                                    # hero window: bars 38-65 only
    assert L.rule_trades(tb, {("NIFTY", date(2024, 6, 6)): {}}, ("rsi_momo", "cheap", "all", "hold")) == []


def test_baseline_takes_both_sides_at_the_fixed_bar():
    tb = {}
    for sgn in (1, -1):
        tb[("NIFTY", date(2024, 6, 6), sgn, "cheap", K.BASE_BAR["all"], "hold")] = dict(net=1.0, risked=1.0, peak=1.0,
                                                                                     xmult=1.0, proxy=False)
    tr = L.rule_trades(tb, {("NIFTY", date(2024, 6, 6)): {}}, ("base", "cheap", "all", "hold"))
    assert sorted(t["side"] for t in tr) == [-1, 1]


def test_twins_and_p():
    tb = _table()
    pool = L.twin_pool(tb, "cheap", "hold", "all")
    assert sorted(pool[("NIFTY", date(2024, 6, 6), 1)].tolist()) == [-100.0, -50.0, 500.0]
    tr = [dict(und="NIFTY", d=date(2024, 6, 6), side=1, net=500.0)]
    tw = L.twins(tr, pool, n=300, seed=1)
    assert set(np.unique(tw)) <= {-100.0, -50.0, 500.0}
    p = L.p_value(500.0, tw)
    assert p == pytest.approx((1 + np.sum(tw >= 500.0)) / 301)
    assert 0.2 < p < 0.5
    hero = L.twin_pool(tb, "cheap", "hold", "hero")
    assert hero[("NIFTY", date(2024, 6, 6), 1)].tolist() == [-50.0]


def test_bh():
    q = L.bh([0.01, 0.04, 0.03, 0.5])
    assert q == pytest.approx([0.04, 0.16 / 3, 0.16 / 3, 0.5])        # rank 2's 0.06 is pulled down to rank 3's 0.0533
    assert L.bh([]) == []


def test_metrics_top5_drawdown_and_empty():
    tr = [dict(d=date(2024, 1, i + 1), net=n, risked=1000.0, peak=pk, xmult=xm, proxy=False, side=1, und="NIFTY")
          for i, (n, pk, xm) in enumerate([(-500, 1, 0.5), (4000, 12, 5.2), (-300, 2, 0.6), (100, 1.5, 1.1)])]
    m = L.metrics(tr, 10)
    assert m["trades"] == 4 and m["net"] == 3300 and m["net_per_day"] == 330
    assert m["peak5"] == 1 and m["peak10"] == 1 and m["banked5"] == 1 and m["banked10"] == 0
    assert m["max_dd"] == -500 and m["worst_day"] == -500
    assert m["net_wo_top5"] == 0 and m["top5_share"] == 100.0
    e = L.metrics([], 0)
    assert e["trades"] == 0 and e["net"] == 0 and e["top5_share"] is None


def test_rule_grid_is_88():
    rs = K.rules()
    assert len(rs) == 88 and len(set(rs)) == 88
    assert sum(r[0] == "base" for r in rs) == 12
    assert all(r[3] == "step" for r in rs if r[0] == "ema821_adx15_lock")
