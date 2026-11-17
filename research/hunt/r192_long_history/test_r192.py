"""R192 tests (PREREG.md section 9): the look-ahead guard, every rule on hand-made days, the calendars, Black-76, the
walker (real and synthetic legs), stats, twins, BH and the gate. No data files needed.

    python -m pytest research/hunt/r192_long_history -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r192cfg as K  # noqa: E402
import r192lib as G  # noqa: E402
import r192study as ST  # noqa: E402


def day(path, start=0) -> np.ndarray:
    """A (375, 4) day from a close path (open = previous close), rows from `start`."""
    a = np.full((K.N, 4), np.nan)
    c = np.asarray(path, float)
    o = np.concatenate([[c[0]], c[:-1]])
    n = min(len(c), K.N - start)
    a[start:start + n, 0], a[start:start + n, 3] = o[:n], c[:n]
    a[start:start + n, 1], a[start:start + n, 2] = np.maximum(o, c)[:n] + 1, np.minimum(o, c)[:n] - 1
    return a


def noise_after(a, t, seed=0):
    b = a.copy()
    b[t:] = np.random.default_rng(seed).normal(5000, 500, size=b[t:].shape)
    return b


def weekdays(start: date, n: int, skip=()) -> list:
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5 and d not in skip:
            out.append(d)
        d += timedelta(days=1)
    return out


# ================================================================================================ look-ahead guard
@pytest.mark.parametrize("seed", [1, 2, 3])
def test_gap_rules_ignore_every_minute_after_their_decision(seed):
    a = day(np.linspace(101.0, 103.0, K.N))        # opens 1% up from 100, keeps going
    for fn, t in ((G.sig_k5, K.R0920), (G.sig_k6, K.R0930)):
        assert fn(a, 100.6) == fn(noise_after(a, t, seed), 100.6)
        assert fn(a, 100.0) == fn(noise_after(a, t, seed), 100.0)


@pytest.mark.parametrize("seed", [4, 5])
def test_1519_context_ignores_1520_on(seed):
    a = day(np.linspace(100, 102, K.N))
    b = noise_after(a, K.R1520, seed)
    for x in (a, b):
        z = G.blank_after(x, K.R1520)
        assert G.last_close(z, K.R1520) == G.last_close(G.blank_after(a, K.R1520), K.R1520)
        assert G.day_hilo(z, K.R1520) == G.day_hilo(G.blank_after(a, K.R1520), K.R1520)


@pytest.mark.parametrize("seed", [6, 7, 8])
def test_daily_signals_read_only_the_past(seed):
    """K7, K8, K9 get histories that end with the previous session; noise on later days cannot reach them, and
    appending noise AFTER the cut does not change a decision made on the cut history."""
    rng = np.random.default_rng(seed)
    closes = 10000 * np.exp(np.cumsum(rng.normal(0.0005, 0.01, 600)))
    vix = 15 + np.abs(rng.normal(0, 3, 600))
    vix[-1] = 40
    closes[-1] = closes[-2] * 0.97
    fut = np.concatenate([closes, rng.normal(1, 1, 50)])
    futv = np.concatenate([vix, rng.normal(1, 1, 50)])
    i = 600
    px = closes[-1] * 1.02
    assert G.sig_k7(futv[:i], fut[:i]) == G.sig_k7(vix, closes)
    assert G.sig_k8(px, fut[:i]) == G.sig_k8(px, closes)
    assert G.sig_k9(px, fut[:i]) == G.sig_k9(px, closes)


# ================================================================================================ signals
def test_k4_base_strong_close():
    assert G.sig_k4_base(0.004, 0.7) == 1
    assert G.sig_k4_base(-0.004, 0.3) == -1
    assert G.sig_k4_base(0.004, 0.5) == 0
    assert G.sig_k4_base(-0.004, 0.6) == 0
    assert G.sig_k4_base(np.nan, 0.9) == 0


def test_k4_filter_menu():
    ctx = dict(move=0.02, loc=0.85, px=110.0, sma200=100.0, vix_high=True, gap_night=True)
    assert G.k4_allows("F0", 1, ctx)
    assert not G.k4_allows("F1", 1, ctx)
    assert G.k4_allows("F2", 1, ctx) and not G.k4_allows("F2", -1, ctx)
    assert not G.k4_allows("F3", 1, ctx)
    assert not G.k4_allows("F4", 1, ctx)
    assert G.k4_allows("F5", 1, ctx) and not G.k4_allows("F5", -1, ctx)
    assert not G.k4_allows("F2", 1, dict(ctx, sma200=np.nan))
    assert not G.k4_allows("F0", 0, ctx)
    with pytest.raises(ValueError):
        G.k4_allows("F9", 1, ctx)


def test_k5_gap_fade():
    up = day(np.full(K.N, 100.6))                  # +0.6% gap, still up at 09:20
    assert G.sig_k5(up, 100.0) == -1
    dn = day(np.full(K.N, 99.5))
    assert G.sig_k5(dn, 100.0) == 1
    assert G.sig_k5(day(np.full(K.N, 100.2)), 100.0) == 0      # gap too small
    assert G.sig_k5(day(np.full(K.N, 101.5)), 100.0) == 0      # gap too big
    filled = day(np.r_[100.6, 100.4, 99.9, 99.8, 99.9, np.full(K.N - 5, 99.9)])
    assert G.sig_k5(filled, 100.0) == 0                        # already back through the previous close
    assert G.sig_k5(day(np.full(K.N, 100.6)), np.nan) == 0


def test_k5_k6_missing_minutes():
    a = day(np.full(K.N, 100.6))
    a[:K.R0930] = np.nan                                       # nothing before 09:30
    assert G.sig_k5(a, 100.0) == 0 and G.sig_k6(a, 100.0) == 0
    b = day(np.full(K.N, 100.6), start=3)                      # first minute at 09:18
    assert G.sig_k5(b, 100.0) == -1
    assert G.sig_k5(np.full((K.N, 4), np.nan), 100.0) == 0     # an empty day


def test_k6_gap_follow():
    a = day(np.r_[100.8, np.linspace(100.8, 101.4, K.N - 1)])
    assert G.sig_k6(a, 100.0) == 1
    b = day(np.r_[100.8, np.linspace(100.8, 100.2, K.N - 1)])  # gap up but below the open at 09:30
    assert G.sig_k6(b, 100.0) == 0
    c = day(np.r_[99.2, np.linspace(99.2, 98.5, K.N - 1)])
    assert G.sig_k6(c, 100.0) == -1
    assert G.sig_k6(day(np.full(K.N, 100.3)), 100.0) == 0


def test_k7_vix_spike():
    v = np.r_[np.full(10, 16.0), 21.0]
    assert G.sig_k7(v, np.array([100.0, 99.0])) == 1
    assert G.sig_k7(v, np.array([100.0, 101.0])) == 0           # not a down day
    assert G.sig_k7(np.r_[np.full(10, 16.0), 19.0], np.array([100.0, 99.0])) == 0
    assert G.sig_k7(np.r_[np.full(9, 16.0), np.nan, 21.0], np.array([100.0, 99.0])) == 0   # a gap in VIX
    assert G.sig_k7(np.full(5, 16.0), np.array([100.0, 99.0])) == 0                         # too short


def test_k8_52_week():
    c = np.r_[np.linspace(90, 100, 300)]
    assert G.sig_k8(100.5, c) == 1
    assert G.sig_k8(99.0, c) == 0
    assert G.sig_k8(50.0, c) == -1
    assert G.sig_k8(100.5, c[:100]) == 0                       # not a year of history


def test_k9_trend_low_vol():
    rng = np.random.default_rng(3)
    r = np.r_[rng.normal(0, 0.02, 300), rng.normal(0, 0.003, 30)]   # calm at the end
    c = 100 * np.exp(np.cumsum(r + 0.001))
    assert G.sig_k9(c[-1] * 1.5, c) == 1
    assert G.sig_k9(c[-1] * 0.1, c) == -1
    r2 = np.r_[rng.normal(0, 0.003, 300), rng.normal(0, 0.03, 30)]  # wild at the end
    c2 = 100 * np.exp(np.cumsum(r2))
    assert G.sig_k9(c2[-1] * 1.5, c2) == 0
    assert G.sig_k9(100.0, c[:100]) == 0


def test_big_move():
    assert G.sig_big(0.02, True) == -1 and G.sig_big(0.02, False) == 1
    assert G.sig_big(-0.016, True) == 1 and G.sig_big(-0.016, False) == -1
    assert G.sig_big(0.01, True) == 0 and G.sig_big(np.nan, False) == 0


def test_close_loc():
    assert G.close_loc(105, 110, 100) == 0.5
    assert np.isnan(G.close_loc(105, 100, 100))


# ================================================================================================ calendars
def test_month_end_and_pre_holiday():
    hol = {date(2024, 1, 26)}                                   # a Friday holiday
    s = weekdays(date(2024, 1, 22), 12, skip=hol)
    assert G.holidays_between(s) == hol
    i25 = s.index(date(2024, 1, 25))
    assert G.is_pre_holiday(s, i25, hol)                       # Thu before the Fri holiday
    assert not G.is_pre_holiday(s, s.index(date(2024, 1, 24)), hol)
    assert G.is_last_of_month(s, s.index(date(2024, 1, 31)))
    assert not G.is_last_of_month(s, s.index(date(2024, 1, 30)))
    assert not G.is_last_of_month(s, len(s) - 1)               # the last known session: unknown, not taken
    assert not G.is_pre_holiday(s, len(s) - 1, hol)
    fri = weekdays(date(2024, 3, 25), 10, skip={date(2024, 3, 29), date(2024, 4, 1)})
    assert G.is_pre_holiday(fri, fri.index(date(2024, 3, 28)), {date(2024, 3, 29), date(2024, 4, 1)})


def test_synthetic_expiries_holiday_thursday_and_weekly_switch():
    s = weekdays(date(2018, 1, 1), 450, skip={date(2018, 3, 29)})   # 29 Mar 2018 (Thursday) a holiday
    ex = G.syn_expiries(s)
    mon = G.monthly_set(ex)
    assert date(2018, 3, 28) in ex and date(2018, 3, 29) not in ex  # moved to the session before
    assert date(2018, 1, 25) in ex and date(2018, 1, 18) not in ex  # monthly only before the weekly era
    assert G.expiry_for(date(2018, 1, 26), ex, mon) == date(2018, 2, 22)
    assert G.expiry_for(date(2019, 2, 5), ex, mon) == date(2019, 2, 28)   # weeklies start 11 Feb 2019
    assert G.expiry_for(date(2019, 2, 12), ex, mon) == date(2019, 2, 14)
    assert G.expiry_for(date(2019, 2, 14), ex, mon) == date(2019, 2, 14)  # an expiry day is its own expiry
    assert G.expiry_for(date(2030, 1, 1), ex, mon) is None


def test_hold_window_expiry_cut():
    assert G.hold_window("ON", [False, False]) == (K.R1520, 1, K.R0916)
    assert G.hold_window("ON", [True, False]) is None                  # 15:20 on an expiry day: no trade
    assert G.hold_window("ON", [False, True]) == (K.R1520, 1, K.R0916)  # 09:16 on expiry day is before 15:05
    assert G.hold_window("CC1", [False, True]) == (K.R1520, 1, K.R1505)
    assert G.hold_window("CC2", [False, True, False]) == (K.R1520, 1, K.R1505)
    assert G.hold_window("D2", [True, False]) == (K.R0920, 0, K.R1505)
    assert G.hold_window("G5", [True]) == (K.R0920, 0, K.R1505)
    assert G.hold_window("CC2", [False, False]) is None                # not enough sessions


# ================================================================================================ pricing
def test_black76_parity_and_limits():
    S, Kx, T, sg = 20000.0, 19950.0, 5 / 365, 0.15
    c, p = G.b76(S, Kx, T, sg, 1), G.b76(S, Kx, T, sg, -1)
    df = np.exp(-K.RATE * T)
    assert abs((c - p) - df * (S * np.exp(K.RATE * T) - Kx)) < 1e-6
    assert abs(G.b76(S, Kx, 1e-9, sg, 1) - (S - Kx)) < 1.0      # at expiry: intrinsic
    assert G.b76(S, Kx, T, 0.30, 1) > c                          # more vol, dearer


def test_clocks():
    d, ex = date(2024, 1, 3), date(2024, 1, 4)
    cal = G.t_years(d, [K.R1520], ex)[0]
    assert abs(cal * 365 * 1440 - (24 * 60 + 10)) < 1e-6         # 15:20 Wed -> 15:30 Thu
    tr = G.t_trading(d, [K.R1520], ex, 0, 1.0)[0]
    assert abs(tr * (G.YEAR_TRADING_MIN + G.YEAR_OTHER_MIN) - 24 * 60 - 10) < 1e-6   # w = 1: calendar minutes
    t0 = G.t_trading(d, [K.R1520], ex, 0, 0.0)[0]
    assert abs(t0 * G.YEAR_TRADING_MIN - (10 + 375)) < 1e-6      # w = 0: trading minutes only
    assert G.t_trading(ex, [K.N + 5], ex, 0, 0.0)[0] > 0         # never zero


def test_walker_entry_stop_target_and_fallbacks():
    n = 2 * K.N
    o = np.full(n, np.nan)
    c = np.full(n, np.nan)
    v = np.full(n, 2000.0)
    o[K.R0920:K.N], c[K.R0920:K.N] = 100.0, 100.0
    d0, d1 = date(2024, 1, 3), date(2024, 1, 4)
    dop = lambda p: d0 if p < K.N else d1  # noqa: E731
    r = G.walk(o, c, v, np.full(n, np.nan), K.R0920, K.R1520, 25, dop)
    assert r["why"] == "time" and r["exit_how"] == "open" and r["ef"] > 100 and r["net"] < 0
    c2 = c.copy()
    c2[K.R0920 + 10] = 60.0                                      # option close below 70% of the fill
    r = G.walk(o, c2, v, np.full(n, np.nan), K.R0920, K.R1520, 25, dop, stop_frac=0.7)
    assert r["why"] == "stop" and r["xpos"] == K.R0920 + 11
    ix = np.full(n, 99.0)
    ix[K.R0920 + 20] = 100.0
    r = G.walk(o, c, v, ix, K.R0920, K.R1520, 25, dop, target=100.0, side=1)
    assert r["why"] == "target" and r["xpos"] == K.R0920 + 21
    r = G.walk(o, c, v, ix, K.R0920, K.R1520, 25, dop, target=99.5, side=-1)
    assert r["why"] == "target" and r["xpos"] == K.R0920 + 1      # a put's target: index at or below
    o3 = np.full(n, np.nan)
    assert G.walk(o3, c, v, np.full(n, np.nan), K.R0920, K.R1520, 25, dop) is None   # no entry print
    # next-session exit with no print near 09:16: the last print before it (across the night)
    r = G.walk(o, c, v, np.full(n, np.nan), K.R1520, K.N + K.R0916, 25, dop)
    assert r["exit_how"] == "last_print" and r["xpos"] < K.N
    o4 = o.copy()
    o4[K.N + 3] = 120.0                                          # a print 2 minutes after 09:16
    r = G.walk(o4, c, v, np.full(n, np.nan), K.R1520, K.N + K.R0916, 25, dop)
    assert r["exit_how"] == "open" and r["xpos"] == K.N + 3 and r["net"] > 0


def test_fills_and_lots():
    assert G.buy_fill(100.0, 50) == 100.1                         # +5 bps (0.05) + 1 tick
    assert G.sell_fill(100.0, 0) == 99.75                         # -5 bps - 4 ticks (no volume)
    assert G.sell_fill(0.05, 0) == 0.0
    assert G.lot(date(2021, 1, 1)) == 75 and G.lot(date(2024, 6, 1)) == 25 and G.lot(date(2026, 6, 1)) == 65


# ================================================================================================ stats, twins, BH, gate
def test_stats_and_drawdown():
    tr = [dict(day=date(2024, 1, d), net=x) for d, x in ((1, 100.0), (2, -300.0), (3, 50.0), (3, 50.0))]
    s = G.stats(tr)
    assert s["trades"] == 4 and s["net"] == -100 and s["max_dd"] == 300 and s["worst_month"] == -100
    assert G.stats([])["trades"] == 0


def test_pooled_twins_and_p():
    rng = np.random.default_rng(0)
    pools = {"A": (np.full(50, 10.0), np.full(50, -10.0)), "B": (np.zeros(0), np.zeros(0))}
    tw = G.pooled_twins(pools, {"A": 20, "B": 5}, rng, n_twins=500)
    assert abs(tw.mean()) < 20 and tw.max() <= 200 and tw.min() >= -200
    pe, pn = G.twin_p(200.0, tw)
    assert pe < 0.01 and pn < 0.01
    assert G.twin_p(0.0, [])[0] == 1.0


def test_bh_counts_the_whole_log():
    q = G.bh_total([1e-6, 0.5], 4771)
    assert abs(q[0] - min(1e-6 * 4771, 1.0)) < 1e-9 and q[1] == 1.0


def test_gate():
    good = dict(trades=50, net=1000.0, net_wo_top5=10.0)
    eras = {e: good for e in K.ERAS}
    assert G.gate(eras, ("S0", "S", "A", "B", "C"), 0.01, 0.05) == []
    bad = dict(eras, S=dict(trades=50, net=-1.0, net_wo_top5=-5.0))
    f = G.gate(bad, ("S", "A", "B", "C"), 0.01, 0.05)
    assert any("era S: net" in x for x in f) and not any("era S: net without" in x for x in f)   # top-5 only real
    f = G.gate(dict(eras, A=dict(trades=12, net=5.0, net_wo_top5=-1.0)), ("A",), 0.2, 0.5)
    assert len(f) == 4
    assert G.gate({}, ("A",), None, 1.0)[0] == "era A: no trades"


def test_k4_choice_uses_only_its_window():
    def row(d, side, net):
        return dict(day=d, era="S", sides={"K4": side}, ctx=dict(move=0.002, loc=0.9, px=1.0, sma200=0.5,
                    vix_high=False, gap_night=False), pools={"ON": {side: {"syn": dict(net=net)}}})
    rows = [row(date(2018, 1, 1) + timedelta(days=i), 1, 10.0) for i in range(70)]
    rows += [row(date(2020, 1, 1) + timedelta(days=i), 1, -1e6) for i in range(70)]   # after the window
    out = ST.k4_choose(rows)
    assert out["menu"]["F0"]["trades"] == 70 and out["menu"]["F0"]["net"] == 700
    assert out["choice"] == "F0"                                   # ties keep F0
