"""R48 tests: python3 -m pytest research/hunt/r48/test_r48.py -q   (no lake needed: synthetic sessions only)"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r48cfg as K  # noqa: E402
import r48cost as X  # noqa: E402
import r48data as D  # noqa: E402
import r48sim as S  # noqa: E402
import r48study as T  # noqa: E402

N = D.NMIN


def make_day(d: date, spot, strikes, price, window=None, vol=1000.0, step=50.0, rows=None) -> D.Day:
    """A synthetic session: spot (N,) or scalar; price(side, K, spot) -> premium; window(minute) -> (lo, hi) strikes
    with rows (default: all strikes); rows(side, k, minute) -> bool to drop single rows."""
    spot = np.full(N, float(spot)) if np.isscalar(spot) else np.asarray(spot, dtype=float)
    Ks = np.array(sorted(strikes), dtype=float)
    C = np.full((2, len(Ks), N), np.nan, np.float32)
    lo, hi = np.full(N, np.nan), np.full(N, np.nan)
    for m in range(N):
        a, b = window(m) if window else (Ks[0], Ks[-1])
        if a is None:
            continue
        lo[m], hi[m] = a, b
        for k, x in enumerate(Ks):
            if a - 1e-6 <= x <= b + 1e-6:
                for s in (0, 1):
                    if rows is None or rows(s, k, m):
                        C[s, k, m] = price(s, x, spot[m])
    O = C.copy()
    V = np.where(np.isfinite(C), vol, 0.0).astype(np.float32)
    return D.Day(d, Ks, C, O, V, lo, hi, spot, step)


def intrinsic_plus(tv):
    return lambda s, k, sp: max(sp - k, 0.0) + tv if s == 0 else max(k - sp, 0.0) + tv


# ------------------------------------------------------------------------------------------------ costs and fills
def test_lot_schedule():
    assert X.lot("NIFTY", date(2023, 1, 5)) == 50
    assert X.lot("NIFTY", date(2024, 6, 3)) == 25
    assert X.lot("NIFTY", date(2025, 3, 3)) == 75
    assert X.lot("NIFTY", date(2026, 3, 3)) == 65
    assert X.lot("BANKNIFTY", date(2025, 7, 1)) == 35
    assert X.lot("SENSEX", date(2025, 1, 1)) == 20


def test_fill_ticks_volume_far_and_stop_doubling():
    # >= 20 lots traded: 1 tick; thin: 2; nothing: 4; far +1; stop x2
    assert X.ticks(25, False, False) == 1 and X.ticks(5, False, False) == 2 and X.ticks(0, False, False) == 4
    assert X.ticks(25, True, False) == 2 and X.ticks(25, True, True) == 4 and X.ticks(0, False, True) == 8
    assert X.fill(100.0, True, 25) == round(100 * 1.0005 + 0.05, 2)
    assert X.fill(100.0, False, 25) == round(100 * 0.9995 - 0.05, 2)
    assert X.fill(100.0, False, 25, stop=True) == round(100 * 0.9995 - 0.10, 2)
    assert X.fill(0.10, False, 0) == K.MIN_SELL                      # never below Rs 0.05
    assert X.fill(10.0, True, 25, tick_mult=2.0) == round(10 * 1.0005 + 0.10, 2)


def test_charges_stt_only_on_the_sold_side_and_dated():
    d1, d2 = date(2022, 6, 1), date(2026, 5, 4)
    b, s = X.charge(True, 100.0, 50, d1, "NIFTY"), X.charge(False, 100.0, 50, d1, "NIFTY")
    assert s - b == pytest.approx(5000 * 0.0005 - 5000 * 0.00003, abs=0.02)        # STT 0.05% sell vs stamp on buy
    s2 = X.charge(False, 100.0, 50, d2, "NIFTY")
    b2 = X.charge(True, 100.0, 50, d2, "NIFTY")
    assert s2 - b2 == pytest.approx(5000 * 0.0015 - 5000 * 0.00003, abs=0.02)      # 0.15% from Apr 2026
    assert X.charge(False, 100.0, 20, d2, "SENSEX") != X.charge(False, 100.0, 20, d2, "NIFTY")   # BSE exchange rate
    assert X.charge(True, 0.0, 50, d1, "NIFTY") == 0.0


def test_exercise_stt_and_settlement():
    assert X.exercise_stt(0.0, 75, date(2025, 1, 1)) == 0.0
    assert X.exercise_stt(10.0, 75, date(2025, 1, 1)) == round(750 * 0.00125, 2)
    assert X.exercise_stt(10.0, 75, date(2026, 5, 1)) == round(750 * 0.0015, 2)
    assert X.settle_value(0, 25000, 25040) == 40 and X.settle_value(1, 25000, 25040) == 0
    assert X.settle_value(1, 25000, 24950) == 50


def test_margin_spread_exposure_and_expiry_elm():
    m = X.margin("NIFTY", date(2024, 1, 4), 20000, 50, 200, 1, False)
    assert m == pytest.approx(200 * 50 + 0.02 * 20000 * 50)
    assert X.margin("NIFTY", date(2024, 1, 4), 20000, 50, 200, 1, True) == pytest.approx(m)   # no ELM before 20 Nov 24
    m2 = X.margin("NIFTY", date(2025, 1, 2), 24000, 75, 200, 1, True)
    assert m2 == pytest.approx(200 * 75 + 0.04 * 24000 * 75)
    assert X.margin("NIFTY", date(2025, 1, 2), 24000, 75, 0, 0, True, premium=120) == 120 * 75


# ------------------------------------------------------------------------------------------------ held-contract values
STRIKES = [24500 + 50 * k for k in range(21)]          # 24500 .. 25500


def test_leg_inside_window_carries_last_close_over_a_missing_row():
    day = make_day(date(2025, 1, 2), 25000, STRIKES, intrinsic_plus(10.0),
                   rows=lambda s, k, m: not (s == 1 and STRIKES[k] == 24800 and 100 <= m < 110))
    ld = S.leg_day(day, 1, 24800.0, True)
    assert not ld.row[105] and ld.val[105] == pytest.approx(10.0)
    assert ld.fillpx[105] == pytest.approx(10.0)


def test_leg_outside_window_bounds():
    # spot falls from 25000 to 24000 at minute 200; the window follows ATM +- 500
    sp = np.where(np.arange(N) < 200, 25000.0, 24000.0)
    strikes = [23500 + 50 * k for k in range(41)]                       # 23500 .. 25500
    win = lambda m: (sp[m] - 500, sp[m] + 500)                                    # noqa: E731
    day = make_day(date(2025, 1, 2), sp, strikes, intrinsic_plus(5.0), window=win)
    # short put 25200 after the fall is ITM beyond the window (hi = 24500): intrinsic + edge (24500 put) time value
    ld = S.leg_day(day, 1, 25200.0, True)
    assert ld.val[250] == pytest.approx(1200 + 5.0)
    # a LONG put 25300 there: intrinsic only (lower bound), still sold
    ll = S.leg_day(day, 1, 25300.0, False)
    assert ll.val[250] == pytest.approx(1300.0) and not ll.nosell[250]
    # short call 25200 after the fall is OTM above the window: min(last close, edge 24500 call)
    sc = S.leg_day(day, 0, 25200.0, True)
    assert sc.val[150] == pytest.approx(5.0)                             # inside the window before the fall
    assert sc.val[250] == pytest.approx(min(5.0, 5.0))
    # a long call 25300 above the window: 0 and not sold
    lc = S.leg_day(day, 0, 25300.0, False)
    assert lc.val[250] == 0.0 and lc.nosell[250]


def test_short_otm_bound_uses_the_cheaper_of_last_and_edge():
    sp = np.where(np.arange(N) < 200, 25000.0, 24000.0)
    strikes = [23500 + 50 * k for k in range(41)]
    win = lambda m: (sp[m] - 500, sp[m] + 500)                                    # noqa: E731
    price = lambda s, k, x: (max(x - k, 0.0) + 40.0) if s == 0 else (max(k - x, 0.0) + 40.0)  # noqa: E731
    day = make_day(date(2025, 1, 2), sp, strikes, price, window=win)
    # edge 24500 call after the fall is OTM by 500: price 40 (flat tv in this toy); last = 40 -> 40
    sc = S.leg_day(day, 0, 25200.0, True)
    assert sc.val[250] == pytest.approx(40.0)


# ------------------------------------------------------------------------------------------------ positions
def _ctx_for(days, expiries):
    daily = pd.DataFrame(dict(symbol=["NIFTY"] * len(days) + ["INDIAVIX"] * len(days),
                              date=[d.day for d in days] * 2,
                              open=0.0, high=0.0, low=0.0, close=[d.spot[-1] for d in days] + [15.0] * len(days)))
    return T.context("NIFTY", days=days, daily=daily, expiries=expiries)


def two_day_spread(price2, d1=date(2025, 1, 6), d2=date(2025, 1, 7)):
    """Day 1: flat spot 25000, put tv 20 everywhere; day 2 (expiry): price2."""
    p1 = intrinsic_plus(20.0)
    a = make_day(d1, 25000, STRIKES, p1)
    b = make_day(d2, 25000, STRIKES, price2)
    return [a, b]


def test_entry_fills_next_minute_open_with_slippage_and_charges():
    days = two_day_spread(intrinsic_plus(20.0))
    ctx = _ctx_for(days, [days[1].day])
    t = D.mi(10, 0)
    legs = [S.Leg(1, 24800.0, -1), S.Leg(1, 24600.0, +1)]
    p = S.build_path(ctx.cache, "NIFTY", 0, 1, t + 1, legs)
    q = X.lot("NIFTY", days[0].day)
    assert p.entry_px[0] == X.fill(20.0, False, 1000 * 5 / q)
    assert p.entry_px[1] == X.fill(20.0, True, 1000 * 5 / q, far=True)       # 8 steps from ATM
    assert p.entry_charges == pytest.approx(X.charge(False, p.entry_px[0], q, days[0].day, "NIFTY")
                                            + X.charge(True, p.entry_px[1], q, days[0].day, "NIFTY"))
    # a leg with no row at the fill minute: no trade
    days2 = [make_day(days[0].day, 25000, STRIKES, intrinsic_plus(20.0),
                      rows=lambda s, k, m: not (m == t + 1 and STRIKES[k] == 24600)), days[1]]
    ctx2 = _ctx_for(days2, [days2[1].day])
    assert S.build_path(ctx2.cache, "NIFTY", 0, 1, t + 1, legs) is None


def _short_put_path(days, ctx, strike=24800.0):
    t = D.mi(10, 0)
    return S.build_path(ctx.cache, "NIFTY", 0, len(days) - 1, t + 1, [S.Leg(1, strike, -1)])


def test_profit_target_fills_at_next_minute_open():
    # day-1 premium decays linearly 20 -> 5 through the session
    def pr(m):
        return 20.0 - 15.0 * m / (N - 1)
    a = make_day(date(2025, 1, 6), 25000, STRIKES, intrinsic_plus(20.0))
    for m in range(N):
        a.C[1, :, m] = pr(m)
        a.O[1, :, m] = pr(m)
    days = [a, make_day(date(2025, 1, 7), 25000, STRIKES, intrinsic_plus(1.0))]
    ctx = _ctx_for(days, [days[1].day])
    p = _short_put_path(days, ctx)
    credit = S.credit_of(p)
    ex = S.credit_exits(p, days, 1, credit, np.zeros(len(p.spot), bool), holds=("day",), pts=(0.5,), sls=(None,),
                        isls=(False,))
    e = ex[(0.5, None, False, "day")]
    assert e.kind == "pt"
    g = e.g
    assert -p.mark[g] <= 0.5 * credit < -p.mark[g - 1]
    q = p.qty
    buy = X.fill(float(p.fillpx[0, g + 1]), True, p.vol5[0, g + 1] / q)
    exp_pnl = (p.entry_px[0] - buy) * q - p.entry_charges - X.charge(True, buy, q, days[0].day, "NIFTY")
    assert e.pnl == pytest.approx(exp_pnl, abs=0.01)


def test_overnight_gap_stop_pays_the_gap_with_doubled_ticks():
    # day 1 flat at 20; day 2 opens with the put at 90 (a gap through the 2x stop = 60)
    days = two_day_spread(intrinsic_plus(90.0))
    ctx = _ctx_for(days, [days[1].day])
    p = _short_put_path(days, ctx)
    credit = S.credit_of(p)
    ex = S.credit_exits(p, days, 1, credit, np.zeros(len(p.spot), bool), holds=("exp",), pts=(None,), sls=(2.0,),
                        isls=(False,))
    e = ex[(None, 2.0, False, "exp")]
    assert e.kind == "sl" and e.off == 1
    g = e.g
    assert p.dayoff[g] == 1 and p.minute[g] == 0                    # seen on day 2's first close
    assert p.minute[g + 1] == 1                                      # filled on its second minute
    q = p.qty
    buy = X.fill(90.0, True, p.vol5[0, g + 1] / q, stop=True)
    assert buy >= 90.0 + 0.1                                         # the gap, not the 60 stop level, plus 2x ticks
    assert e.pnl == pytest.approx((p.entry_px[0] - buy) * q - p.entry_charges
                                  - X.charge(True, buy, q, days[1].day, "NIFTY"), abs=0.01)


def test_daily_mark_to_market_sums_to_the_trade():
    days = two_day_spread(intrinsic_plus(30.0))
    ctx = _ctx_for(days, [days[1].day])
    p = _short_put_path(days, ctx)
    ex = S.credit_exits(p, days, 1, S.credit_of(p), np.zeros(len(p.spot), bool))
    for e in ex.values():
        assert sum(v for _, v in e.daily) == pytest.approx(e.pnl, abs=0.01)


def test_stop_wins_a_tie_with_the_target():
    days = two_day_spread(intrinsic_plus(20.0))
    ctx = _ctx_for(days, [days[1].day])
    p = _short_put_path(days, ctx)
    credit = S.credit_of(p)
    V = -p.mark
    ist = np.zeros(len(V), bool)
    ist[5] = True
    p.mark[5] = -0.1 * credit                                         # target reached on the same close
    ex = S.credit_exits(p, days, 1, credit, ist, holds=("exp",), pts=(0.5,), sls=(None,), isls=(True,))
    assert ex[(0.5, None, True, "exp")].kind == "isl"


def test_expiry_square_off_settles_a_cheap_long_hedge_with_exercise_stt():
    # expiry day: the put spread 24800/24600 with spot collapsing to 24000 after 15:05 -> long ends ITM
    d1, d2 = date(2025, 1, 6), date(2025, 1, 7)
    a = make_day(d1, 25000, STRIKES, intrinsic_plus(20.0))
    sp = np.full(N, 25000.0)
    sp[D.mi(15, 6):] = 24000.0
    b = make_day(d2, sp, STRIKES, lambda s, k, x: 0.5 if s == 1 else 0.5)    # every put 0.5 at 15:05
    days = [a, b]
    ctx = _ctx_for(days, [d2])
    legs = [S.Leg(1, 24800.0, -1), S.Leg(1, 24600.0, +1)]
    p = S.build_path(ctx.cache, "NIFTY", 0, 1, D.mi(10, 0) + 1, legs)
    ex = S.credit_exits(p, days, 1, S.credit_of(p), np.zeros(len(p.spot), bool), holds=("exp",), pts=(None,),
                        sls=(None,), isls=(False,))
    e = ex[(None, None, False, "exp")]
    assert e.kind == "time" and p.minute[e.g + 1] == D.mi(15, 5)
    q = p.qty
    settle_spot = float(np.mean(sp[D.mi(15, 0):]))
    iv = max(24600 - settle_spot, 0.0)
    assert iv > 0
    buy = X.fill(0.5, True, p.vol5[0, e.g + 1] / q)
    exp = (p.entry_px[0] - buy) * q + (iv - p.entry_px[1]) * q - p.entry_charges \
        - X.charge(True, buy, q, d2, "NIFTY") - X.exercise_stt(iv, q, d2)
    assert e.pnl == pytest.approx(exp, abs=0.01)


def test_buy_target_and_time_exit():
    a = make_day(date(2025, 1, 6), 25000, STRIKES, intrinsic_plus(50.0))
    for m in range(N):
        a.C[0, :, m] += 0.02 * m
        a.O[0, :, m] += 0.02 * m
    days = [a, make_day(date(2025, 1, 7), 25000, STRIKES, intrinsic_plus(1.0))]
    ctx = _ctx_for(days, [days[1].day])
    p = S.build_path(ctx.cache, "NIFTY", 0, 0, D.mi(10, 0) + 1, [S.Leg(0, 25000.0, +1)])
    ex = S.buy_exits(p, days, 1, tgts=(0.05,), stops=(None,), times=(30, "eod"))
    assert ex[(0.05, None, 30)].kind == "time" and ex[(0.05, None, 30)].g == 29
    assert ex[(0.05, None, "eod")].kind == "tgt"


# ------------------------------------------------------------------------------------------------ structures, signals
def test_credit_legs_strikes_and_widths():
    day = make_day(date(2025, 1, 6), 25010, STRIKES, intrinsic_plus(50.0))
    legs, kp, kc, W = S.credit_legs("IC", day, 60, 1.0, 4)
    E = 2 * 50.0 + 10                                               # ATM 25000 call 60 + put 50
    assert kp == np.floor((25010 - E) / 50) * 50 and kc == np.ceil((25010 + E) / 50) * 50
    assert W == 200 and [(L.side, L.strike, L.pos) for L in legs] == [(1, kp, -1), (1, kp - 200, 1), (0, kc, -1),
                                                                       (0, kc + 200, 1)]
    legs, kp, kc, W = S.credit_legs("IB", day, 60, 0.0, 2)
    assert kp == kc == 25000 and {(L.side, L.strike, L.pos) for L in legs} == {(0, 25000, -1), (1, 25000, -1),
                                                                                 (0, 25100, 1), (1, 24900, 1)}


def test_signals_use_no_future_minutes():
    rng = np.random.default_rng(3)
    sp = 25000 + np.cumsum(rng.normal(0, 5, N))
    days = [make_day(date(2025, 1, 6), 25000, STRIKES, intrinsic_plus(20.0)),
            make_day(date(2025, 1, 7), sp, STRIKES, intrinsic_plus(20.0))]
    ctx = _ctx_for(days, [days[1].day])
    for t in (5, 45, 105, 225):
        a = T.signals(ctx, 1, t)
        days[1].spot[t + 1:] = days[1].spot[t + 1:] + 500 * rng.choice([-1, 1])
        assert T.signals(ctx, 1, t) == a


def test_index_stop_conditions():
    class P:
        spot = np.array([25000.0, 24790.0, 25210.0])
    assert list(S.index_stop(P, "BPS", 24800.0, None, 10)) == [False, True, False]
    assert list(S.index_stop(P, "IC", 24800.0, 25200.0, 10)) == [False, True, True]
    assert list(S.index_stop(P, "IB", 25000.0, 25000.0, 205)) == [False, True, True]


# ------------------------------------------------------------------------------------------------ data
def test_build_days_holds_one_contract_across_offsets():
    ts = pd.to_datetime(["2025-01-06 09:15", "2025-01-06 09:16"]).tz_localize("Asia/Kolkata")
    df = pd.DataFrame(dict(ts=np.repeat(ts, 2), side=["C", "C", "C", "C"], open=[10, 20, 11, 21.0],
                           close=[10.5, 20.5, 11.5, 21.5], volume=[1, 2, 3, 4], strike=[25000, 25050, 25050, 25100.0],
                           spot=[25010.0] * 4))
    # minute 0: 25000 at offset 0, 25050 at +1; minute 1: 25050 at offset 0 (rolled), 25100 at +1
    days = D.build_days(df)
    d = days[0]
    k = d.kidx(25050.0)
    assert d.C[0, k, 0] == 20.5 and d.C[0, k, 1] == 11.5
    assert d.lo[0] == 25000 and d.hi[1] == 25100 and d.step == 50.0


def test_expiry_inference_on_time_value():
    def dd(day, tv):
        return make_day(day, 25000, STRIKES, intrinsic_plus(tv))
    days = [dd(date(2025, 1, 6), 40.0), dd(date(2025, 1, 7), 20.0), dd(date(2025, 1, 8), 60.0),
            dd(date(2025, 1, 9), 30.0)]
    assert D.expiry_days(days) == [date(2025, 1, 7)]               # 40/25000 = 0.32% but next is not 2x; 0.16% then 0.48%


def test_dte_and_contract_switch_guard():
    days = [make_day(date(2025, 1, d), 25000, STRIKES, intrinsic_plus(tv)) for d, tv in
            ((6, 30.0), (7, 15.0), (8, 80.0), (9, 70.0), (10, 60.0))]
    ctx = _ctx_for(days, [date(2025, 1, 7), date(2025, 1, 10)])
    assert list(ctx.dte) == [1, 0, 2, 1, 0]
    assert not ctx.switch[2]                                        # after an inferred expiry: not a switch
    days[3] = make_day(date(2025, 1, 9), 25000, STRIKES, intrinsic_plus(170.0))
    ctx = _ctx_for(days, [date(2025, 1, 7), date(2025, 1, 10)])
    assert ctx.switch[3]                                            # doubled overnight, flat index, no expiry


# ------------------------------------------------------------------------------------------------ selection, holdout lock
def test_quick_stats_and_halves():
    P = np.array([[100.0, -50], [200, np.nan], [-10, 30]])
    V = np.ones_like(P, dtype=bool)
    dates = [date(2024, 12, 2), date(2025, 2, 3), date(2025, 6, 2)]
    st = T.quick_stats(P, V, dates)
    assert list(st["n"]) == [3, 2] and st["net"][0] == 290 and st["win"][0] == pytest.approx(2 / 3)
    assert st["net"][1] == -20 and st["n_halves"] == 2
    assert list(st["ys_tot"]) == [0, 0]                            # fewer than 10 trades in each half
    P2 = np.r_[np.full(10, 5.0), np.full(10, -1.0)][:, None]
    d2 = [date(2025, 1, 6)] * 10 + [date(2025, 6, 2)] * 10
    st2 = T.quick_stats(P2, np.ones_like(P2, bool), d2)
    assert st2["ys_tot"][0] == 2 and st2["ys_ok"][0] == 1         # the second half loses: not eligible
    assert st2["n_ly"][0] == 20


def test_design_window_is_nov_2024_on():
    assert K.DESIGN_START == date(2024, 11, 1) and K.DESIGN_START < K.H1_END < K.DESIGN_END
    assert K.MIN_TRADES == 43 and K.LAST_YEAR == K.DESIGN_START


def test_pick_diversity():
    def r(t, und="NIFTY", kind="IC", m=1.0, w=4, dte=1, time="1000", exit_=(0.5,)):
        return dict(t=t, cfg=dict(und=und, kind=kind, m=m, w=w, dte=dte, time=time, sig="none", dir="none",
                                  exit=list(exit_)))
    c = [r(9), r(8, exit_=(0.3,)), r(7, dte=2), r(6, dte=3), r(5, kind="IB", m=0.0), r(4, und="SENSEX")]
    out = T.pick(c)
    ts = [o["t"] for o in out]
    assert ts == [9, 7, 5, 4]                                      # same entry dropped; 3rd condor on NIFTY dropped


def test_bh():
    q = T.bh([0.01, 0.04, 0.03, 0.2])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.2)
    assert q[1] == pytest.approx(0.04 * 4 / 3) and q[2] == pytest.approx(0.04 * 4 / 3)


def test_holdout_refuses_a_second_read(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "RESULTS", tmp_path)
    (tmp_path / "holdout.json").write_text("{}")
    with pytest.raises(SystemExit, match="refused"):
        T.cmd_holdout()


def test_holdout_not_read_without_candidates(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "RESULTS", tmp_path)
    (tmp_path / "design.json").write_text(json.dumps(dict(candidates=[], vix_median=15.0)))
    with pytest.raises(SystemExit, match="NOT read"):
        T.cmd_holdout()
    assert not (tmp_path / "holdout.json").exists()


def test_periods_do_not_overlap():
    assert K.DESIGN_END < K.HOLDOUT_START and (K.HOLDOUT_START - K.DESIGN_END).days >= 14
    assert K.LAST_YEAR < K.DESIGN_END


def test_entry_days_respect_the_periods():
    days = [make_day(date(2025, 10, 16), 25000, STRIKES, intrinsic_plus(10.0)),
            make_day(date(2025, 10, 17), 25000, STRIKES, intrinsic_plus(5.0)),
            make_day(date(2025, 11, 3), 25000, STRIKES, intrinsic_plus(10.0)),
            make_day(date(2025, 11, 4), 25000, STRIKES, intrinsic_plus(5.0)),
            make_day(date(2025, 11, 5), 25000, STRIKES, intrinsic_plus(20.0))]
    ctx = _ctx_for(days, [date(2025, 10, 17), date(2025, 11, 4)])
    assert [ctx.dates[i] for i in T.entry_days(ctx, "design")] == [date(2025, 10, 16), date(2025, 10, 17)]
    assert [ctx.dates[i] for i in T.entry_days(ctx, "holdout")] == [date(2025, 11, 3), date(2025, 11, 4)]


def test_twins_draw_the_same_number_of_trades():
    pool = {(0, 5, 0): 10.0, (1, 6, 0): -5.0}
    tw = T.twins(dict(kind="IC"), pool, 3, np.random.default_rng(1), n_twins=50)
    assert len(tw) == 50 and set(np.round(tw)) <= {30.0, 15.0, 0.0, -15.0}
