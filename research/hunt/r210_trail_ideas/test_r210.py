"""R210 tests (synthetic, no data): bars on the 09:15 grid (gaps, the short last 60-minute bar), ATR / EMA, swings, the
hybrid trail's stages, the Chandelier exit, the higher-timeframe filter (holiday gap = the previous session), the
look-ahead guard (minutes after the decision replaced by noise change nothing), the sign-flip test, BH, the gate (each
item fails alone), the holdout verdict, and the engine edge cases through r186engine.simulate (the moved fx sells at the
next minute's open; an fx at or past the square-off / the 15:05 expiry row changes nothing; breakeven at +1R)."""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r210cfg as K  # noqa: E402
import r210lib as L  # noqa: E402

N = 375


def _day(close, spread=1.0):
    """(4, 375) minutes from a close path (open = previous close, high / low = close +- spread)."""
    c = np.asarray(close, float)
    o = np.r_[c[0], c[:-1]]
    return np.vstack([o, np.maximum(o, c) + spread, np.minimum(o, c) - spread, c])


def _flat_day(px=100.0):
    return _day(np.full(N, px))


# ================================================================================================ settings
def test_18_tests_and_m():
    assert len(K.TESTS) == 18 and K.M_TOTAL == K.N_PRIOR + 18 == 4826
    assert {k for _, k in K.TESTS.values()} == set(K.KINDS)
    assert K.OPTION_CHART == {"HP"}


def test_folds_tile_the_design_years_and_the_holdout_comes_after():
    fs = [K.FOLDS[f] for f in ("F1", "F2", "F3", "F4")]
    assert fs[0][0] == K.DESIGN[0] and fs[-1][1] == K.DESIGN[1]
    for (a, b), (c, d) in zip(fs, fs[1:]):
        assert (c - b).days == 1
    assert K.HOLDOUT[0] > K.DESIGN[1]


# ================================================================================================ bars
def test_day_bars_grid_gaps_and_short_last_hour():
    a = _flat_day()
    a[:, 5:10] = np.nan                                   # the 09:20 bar has no minute: left out
    a[3, 14] = np.nan                                     # the 09:25 bar's last close missing: its close is row 13's
    a[3, 13] = 101.0
    b = L.day_bars(a, 5)
    assert len(b["c"]) == 74 and 9 not in b["end"] and b["end"][0] == 4 and b["end"][1] == 14
    assert b["c"][1] == 101.0
    h = L.day_bars(_flat_day(), 60)
    assert list(h["end"]) == [59, 119, 179, 239, 299, 359, 374]          # 15:15 - 15:29 is the short last bar
    assert len(L.day_bars(_flat_day(), 15)["c"]) == 25


def test_series_keys_are_sorted_and_last_done():
    S = L.series([_flat_day(), _flat_day()], 15)
    assert np.all(np.diff(S["key"]) > 0) and len(S["c"]) == 50
    assert L.last_done(S, 0, 13) == -1                    # nothing completed before 09:29
    assert L.last_done(S, 0, 14) == 0
    assert L.last_done(S, 1, 0) == 24                     # at 09:15 of day 2: the previous session's last bar
    assert L.last_done(S, 1, 29) == 26


def test_atr_and_ema():
    h = np.array([11.0, 12, 13, 12])
    l_ = np.array([9.0, 10, 11, 8])
    c = np.array([10.0, 11, 12, 9])
    a = L.atr(h, l_, c, n=2)
    tr = [2.0, 2.0, 2.0, max(4.0, abs(12 - 12), abs(8 - 12))]
    assert math.isnan(a[0]) and a[1] == pytest.approx(2.0) and a[3] == pytest.approx((tr[2] + tr[3]) / 2)
    a2 = L.atr(h, l_, c, n=14, min_n=2)
    assert math.isnan(a2[0]) and a2[3] == pytest.approx(np.mean(tr))
    e = L.ema(np.arange(1.0, 25.0), 20)
    assert np.isnan(e[18]) and e[19] == pytest.approx(10.5)
    assert e[20] == pytest.approx(2 / 21 * 21 + 19 / 21 * 10.5)


def test_swings_need_two_bars_each_side():
    l_ = np.array([5.0, 4, 3, 4, 5, 2, 6])
    lo, hi = L.swings(-l_, l_, 2)
    assert lo.tolist() == [False, False, True, False, False, False, False]   # 2 at s=5 has one bar after it: unknown
    assert hi.tolist() == lo.tolist()


def test_earlier():
    assert L.earlier(-1, 30) == 30 and L.earlier(40, -1) == 40 and L.earlier(40, 30) == 30 and L.earlier(-1, -1) == -1
    assert L.earlier(None, 7) == 7


# ================================================================================================ the hybrid trail
def test_stage_rows():
    h = np.full(N, 100.0)
    h[30], h[50] = 131.0, 146.0
    h[5] = 200.0                                          # before the fill: never counts
    assert L.stage_rows(h, 10, 360, 100.0, 0.15) == (30, 50)
    assert L.stage_rows(h, 10, 40, 100.0, 0.15) == (30, -1)
    assert L.stage_rows(np.full(N, np.nan), 10, 360, 100.0, 0.15) == (-1, -1)


def _trend_then_drop(top_row=150, drop_to=None, up=1.0):
    c = np.full(N, 1000.0)
    c[20:top_row] = 1000.0 + up * np.arange(top_row - 20)
    c[top_row:] = c[top_row - 1] if drop_to is None else drop_to
    return c


def test_hybrid_waits_for_stage_two_then_trails_the_close():
    S = L.series([_day(_trend_then_drop(drop_to=1060.0))], 5)
    a = L.atr(S["h"], S["l"], S["c"], 14, 5)
    r_never = L.hybrid_row(S, a, 0, 20, 360, True, -1, -1)
    assert r_never == -1                                  # +2R never reached: no trail
    r = L.hybrid_row(S, a, 0, 20, 360, True, 60, -1)
    assert r == 155                                       # the 15:45-row bar (150..154) closes far under the trail
    # stage 2 reached only after the drop: the level starts from the highest close since entry, so it still sells
    r_late = L.hybrid_row(S, a, 0, 20, 360, True, 156, -1)
    assert r_late == 160
    # exit at or after the square-off row: nothing
    assert L.hybrid_row(S, a, 0, 20, 154, True, 60, -1) == -1


def test_hybrid_put_mirrors_and_structure_trail_is_tighter():
    c = 2000.0 - (_trend_then_drop(drop_to=1060.0) - 1000.0)          # a fall, then a jump back up
    S = L.series([_day(c)], 5)
    a = L.atr(S["h"], S["l"], S["c"], 14, 5)
    assert L.hybrid_row(S, a, 0, 20, 360, False, 60, -1) == 155
    # explicit bars: the structure trail (stage 3) sells on a close under the last confirmed swing low that the ATR
    # trail alone (with a huge ATR) would not
    lows = [999, 999, 999, 999, 1010, 1012, 1008, 1015, 1025, 1030, 1022, 1035, 1040, 1015]
    closes = [1000, 1000, 1000, 1000, 1012, 1016, 1010, 1020, 1030, 1040, 1036, 1045, 1050, 1020]
    S2 = _bars(lows, closes)
    big = np.full(len(closes), 1e6)
    assert L.hybrid_row(S2, big, 0, 20, 360, True, 25, -1) == -1
    assert L.hybrid_row(S2, big, 0, 20, 360, True, 25, 25) == 13 * 5 + 4 + 1   # b13 closes under b10's 1022
    assert L.hybrid_row(S2, big, 0, 20, 360, True, 25, 66) == 70                # stage 3 from b13 on: same bar
    assert L.hybrid_row(S2, big, 0, 20, 360, True, 25, 70) == -1                # stage 3 after the last bar


def _bars(lows, closes):
    lows, closes = np.asarray(lows, float), np.asarray(closes, float)
    n = len(closes)
    end = np.arange(n, dtype=np.int64) * 5 + 4
    return dict(day=np.zeros(n, np.int64), end=end, o=closes.copy(), h=np.maximum(lows, closes) + 2.0, l=lows,
                c=closes, key=end.copy())


def test_hybrid_level_never_loosens():
    c = _trend_then_drop(top_row=100, drop_to=None)          # up to row 99, then flat at 1079
    c[220:225] = c[99] - 0.5                                 # a small dip at bar 44 (rows 220..224)
    S = L.series([_day(c, spread=0.1)], 5)
    a = np.full(len(S["c"]), 0.01)                           # tight trail from bar 30 to 39 ...
    a[40:] = 1e6                                             # ... then the ATR explodes: the level must not fall
    assert L.hybrid_row(S, a, 0, 20, 360, True, 140, -1) == 225
    a2 = np.full(len(S["c"]), 1e6)                           # never tight: the dip does not sell
    assert L.hybrid_row(S, a2, 0, 20, 360, True, 140, -1) == -1


# ================================================================================================ the Chandelier exit
def test_chandelier_long_and_short():
    S = L.series([_day(_trend_then_drop(drop_to=1060.0))], 5)
    a = L.atr(S["h"], S["l"], S["c"], 14, 5)
    mh, ml = _day(_trend_then_drop(drop_to=1060.0))[1], _day(_trend_then_drop(drop_to=1060.0))[2]
    assert L.chandelier_row(S, a, 0, 20, 360, mh, ml, True, 3.0) == 155
    cp = 2000.0 - (_trend_then_drop(drop_to=1060.0) - 1000.0)
    dp = _day(cp)
    Sp = L.series([dp], 5)
    ap = L.atr(Sp["h"], Sp["l"], Sp["c"], 14, 5)
    assert L.chandelier_row(Sp, ap, 0, 20, 360, dp[1], dp[2], False, 3.0) == 155
    flat = _flat_day(1000.0)
    Sf = L.series([flat], 5)
    af = L.atr(Sf["h"], Sf["l"], Sf["c"], 14, 5)
    assert L.chandelier_row(Sf, af, 0, 20, 360, flat[1], flat[2], True, 3.0) == -1


def test_chandelier_ignores_highs_before_the_fill_and_unknown_atr():
    d = _flat_day(1000.0)
    d[1, 5] = 5000.0                                      # a spike before the fill must not set the level
    S = L.series([d], 5)
    a = L.atr(S["h"], S["l"], S["c"], 14, 1)
    a[:] = 2.0
    assert L.chandelier_row(S, a, 0, 20, 360, d[1], d[2], True, 3.0) == -1
    S2 = L.series([_day(_trend_then_drop(drop_to=1060.0))], 5)
    nan = np.full(len(S2["c"]), np.nan)
    assert L.chandelier_row(S2, nan, 0, 20, 360, d[1], d[2], True, 3.0) == -1


# ================================================================================================ the higher-timeframe filter
def test_mtf_agree_unknown_true_false_and_previous_session():
    up = [_day(np.linspace(1000, 1100, N)), _day(np.linspace(1100, 1200, N))]
    S = L.series(up, 15)
    e = L.ema(S["c"], 20)
    assert L.mtf_agree(S, e, 0, 100, "C") is None        # fewer than 20 bars so far: unknown (taken)
    assert L.mtf_agree(S, e, 1, 100, "C") is True and L.mtf_agree(S, e, 1, 100, "P") is False
    # at 09:20 of a session the last completed bar is the previous session's (a holiday between is just a gap)
    j = L.last_done(S, 1, 4)
    assert S["day"][j] == 0
    assert L.mtf_agree(S, e, 1, 5, "C") == bool(S["c"][j] > e[j])


# ================================================================================================ look-ahead guard
def _noise_after(a, row, seed=1):
    b = a.copy()
    rng = np.random.default_rng(seed)
    b[:, row:] = 1000.0 + rng.normal(0, 300, size=b[:, row:].shape)
    return b


@pytest.mark.parametrize("seed", [0, 1, 2])
def test_look_ahead_guard(seed):
    rng = np.random.default_rng(seed)
    days = [_day(1000 + np.cumsum(rng.normal(0, 2, N))) for _ in range(4)]
    # the filter at entry row er reads only minutes <= er - 1
    er = 125
    S = L.series(days, 15)
    e = L.ema(S["c"], 20)
    want = [L.mtf_agree(S, e, 3, er, s) for s in "CP"]
    days2 = days[:3] + [_noise_after(days[3], er, seed)]
    S2 = L.series(days2, 15)
    e2 = L.ema(S2["c"], 20)
    assert [L.mtf_agree(S2, e2, 3, er, s) for s in "CP"] == want
    # the exits: everything after the exit bar's last minute replaced by noise -> the same row
    for long in (True, False):
        S5 = L.series(days, 5)
        a5 = L.atr(S5["h"], S5["l"], S5["c"], 14)
        r_ch = L.chandelier_row(S5, a5, 3, 30, 360, days[3][1], days[3][2], long, 1.0)
        r_hy = L.hybrid_row(S5, a5, 3, 30, 360, long, 40, 80, 0.5)
        for r in (r_ch, r_hy):
            if r < 0:
                continue
            d2 = days[:3] + [_noise_after(days[3], r, seed + 10)]
            T5 = L.series(d2, 5)
            b5 = L.atr(T5["h"], T5["l"], T5["c"], 14)
            got = (L.chandelier_row(T5, b5, 3, 30, 360, d2[3][1], d2[3][2], long, 1.0) if r is r_ch
                   else L.hybrid_row(T5, b5, 3, 30, 360, long, 40, 80, 0.5))
            assert got == r
    # ATR / EMA as of a bar never change with later bars
    S5 = L.series(days, 5)
    a5 = L.atr(S5["h"], S5["l"], S5["c"], 14)
    T5 = L.series(days[:3] + [_noise_after(days[3], 200, seed)], 5)
    b5 = L.atr(T5["h"], T5["l"], T5["c"], 14)
    k = L.last_done(S5, 3, 199)
    assert np.allclose(a5[:k + 1], b5[:k + 1], equal_nan=True)


# ================================================================================================ statistics
def test_sign_flip():
    rng = np.random.default_rng(0)
    s = L.sign_flip(np.full(30, 100.0), 4000, rng)
    assert s["p"] < 0.001 and s["p_normal"] == pytest.approx(0.5 * math.erfc(math.sqrt(30) / math.sqrt(2)))
    assert L.sign_flip(np.zeros(5), 100, rng)["p"] == 1.0
    assert L.sign_flip(np.array([]), 100, rng)["p"] == 1.0
    neg = L.sign_flip(np.full(30, -100.0), 2000, rng)
    assert neg["p"] > 0.99 and neg["p_normal"] > 0.99


def test_daily_diffs_union_of_days():
    a = [dict(day=date(2025, 1, 2), net=100.0), dict(day=date(2025, 1, 3), net=50.0)]
    b = [dict(day=date(2025, 1, 2), net=40.0), dict(day=date(2025, 1, 6), net=-10.0),
         dict(day=date(2026, 6, 1), net=999.0)]                       # outside the window: ignored
    d = L.daily_diffs(a, b, date(2025, 1, 1), date(2025, 12, 31))
    assert d.tolist() == [60.0, 50.0, 10.0]


def test_bh_counts_earlier_entries():
    q = L.bh([0.0001, 0.5], 1000)
    assert q[0] == pytest.approx(0.0001 * 1000) and q[1] == pytest.approx(min(1.0, 0.5 * 1000 / 2))
    assert L.bh([0.01, 0.02, 0.03], 3) == pytest.approx([0.03, 0.03, 0.03])


def _st(net, wm=-1000, trades=100):
    return dict(trades=trades, net=net, worst_month=dict(month="2025-01", net=wm), max_dd=1, win=50.0)


def _pack(net, folds, wm=-1000, trades=100):
    return {"design": _st(net, wm, trades), **{f"F{i + 1}": _st(x) for i, x in enumerate(folds)}}


def test_gate_each_item_fails_alone():
    base = _pack(1000, [250, 250, 250, 250], wm=-5000)
    good = _pack(2000, [300, 300, 300, 100], wm=-5000)
    assert L.gate(good, base, 0.01)["passes"]
    cases = {
        "trades": (_pack(2000, [300, 300, 300, 100], wm=-5000, trades=39), 0.01),
        "net": (_pack(1000, [300, 300, 300, 100], wm=-5000), 0.01),
        "folds": (_pack(2000, [300, 300, 100, 100], wm=-5000), 0.01),
        "month": (_pack(2000, [300, 300, 300, 100], wm=-5001), 0.01),
        "q": (good, 0.11),
    }
    for name, (v, q) in cases.items():
        g = L.gate(v, base, q)
        assert not g["passes"] and len(g["fails"]) == 1, name


def test_holdout_verdict():
    assert L.holdout_verdict(_st(10, -100), _st(5, -100))["candidate"]
    assert not L.holdout_verdict(_st(5, -100), _st(5, -100))["candidate"]
    assert not L.holdout_verdict(_st(10, -101), _st(5, -100))["candidate"]


# ================================================================================================ the engine (R186's simulator)
def _engine():
    sys.path.insert(0, str(K.R193))
    T = pytest.importorskip("r193study")
    T.R7.mods()
    import r186engine as Z
    import r106engine as RX
    import engine as E
    return Z, RX, E


QTY = 65.0
DAY = date(2026, 5, 4)


def _sim(Z, RX, E, C, fx=-1, be_x=0.15, sq=360, tmax=0):
    ch = lambda a, b: float(E.charges("NIFTY", np.array([a]), np.array([b]), QTY, DAY)[0])  # noqa: E731
    raw, real, ef, tks = RX.entry_info(C, 10, sq, QTY)
    s0 = RX.stop_price(real, QTY, 0.0, ch, tks, E.SLIP[2], E.SLIP[3])
    lkt, lkk = be_x * raw * QTY * (1 - 1e-12), (s0 - raw) * QTY
    r = Z.simulate(C, 10, sq, fx, 0.15, lkt, lkk, 0.0, np.zeros((0, 2)), tmax, -1.0, -1.0, 0.05, QTY, 0.0, -1.0,
                   Z.NONE, Z.NONE, Z.NONE, np.nan, -1.0, 1.0, *E.SLIP)
    return r, s0


def _optflat(px=100.0):
    C = np.full((5, N), px)
    C[4] = 1e7
    return C


def test_moved_fx_sells_at_next_minute_open_and_past_square_off_changes_nothing():
    Z, RX, E = _engine()
    C = _optflat()
    C[0, 101] = 104.0
    r, _ = _sim(Z, RX, E, C, fx=101)
    assert r[5] == 101 and r[1] == 104.0 and int(r[6]) == 6              # the index exit's reason, at the open
    base, _ = _sim(Z, RX, E, C, fx=-1)
    past, _ = _sim(Z, RX, E, C, fx=360)
    assert past[5] == base[5] and past[1] == base[1]
    exp_sq = 350                                                          # the 15:05 expiry row: fx after it, no effect
    a, _ = _sim(Z, RX, E, C, fx=-1, sq=exp_sq)
    b, _ = _sim(Z, RX, E, C, fx=355, sq=exp_sq)
    assert a[5] == b[5] == exp_sq


def test_breakeven_at_plus_one_r():
    Z, RX, E = _engine()
    C = _optflat()
    C[1, 20] = 114.0                                                      # +14%: under +1R, no breakeven
    C[2, 40] = 99.0
    r, _ = _sim(Z, RX, E, C)
    assert r[5] != 40
    C[1, 20] = 115.5                                                      # +1R: stop to breakeven from the next minute
    C[:4, 21:] = 103.0
    C[2, 40] = 99.0
    r2, s0 = _sim(Z, RX, E, C)
    assert r2[5] == 40 and r2[1] == pytest.approx(s0)


def test_missing_minutes_do_not_break_the_moved_exit():
    Z, RX, E = _engine()
    C = _optflat()
    C[:4, 95:101] = np.nan
    C[0, 101] = 103.0
    r, _ = _sim(Z, RX, E, C, fx=101)
    assert r[5] == 101 and r[1] == 103.0
    C[:4, 101:103] = np.nan                                               # the fx minute itself missing: next open
    C[0, 103] = 102.0
    r2, _ = _sim(Z, RX, E, C, fx=101)
    assert r2[1] == 102.0
