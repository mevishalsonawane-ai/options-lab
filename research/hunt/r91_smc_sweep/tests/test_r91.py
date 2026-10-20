"""R91 tests (PREREG section 9): hand-made cases and the look-ahead guard. No lake data needed."""
from __future__ import annotations

import numpy as np
import pytest

from research.hunt.r91_smc_sweep import cfg as K
from research.hunt.r91_smc_sweep import sweep as W

OPEN = K.NSE_OPEN
N = 375                                    # NSE minutes 09:15 .. 15:29


def series(days_ohlc, pdh=None, pdl=None, reset=None) -> W.Series:
    """days_ohlc: list of (o, h, l, c) arrays, one per session, each N minutes from 09:15."""
    nd = len(days_ohlc)
    mday = np.concatenate([np.full(len(x[0]), i) for i, x in enumerate(days_ohlc)])
    mt = np.concatenate([OPEN + np.arange(len(x[0])) for x in days_ohlc])
    o, h, l, c = (np.concatenate([np.asarray(x[j], float) for x in days_ohlc]) for j in range(4))
    hi = np.array([np.max(x[1]) for x in days_ohlc])
    lo = np.array([np.min(x[2]) for x in days_ohlc])
    pdh = np.r_[np.nan, hi[:-1]] if pdh is None else np.asarray(pdh, float)
    pdl = np.r_[np.nan, lo[:-1]] if pdl is None else np.asarray(pdl, float)
    return W.Series(mday, mt, o, h, l, c, OPEN, np.zeros(nd, bool) if reset is None else np.asarray(reset, bool),
                    pdh, pdl)


def flat_day(px=100.0, n=N):
    a = np.full(n, px)
    return a.copy(), a.copy(), a.copy(), a.copy()


def hourly_day(bars_hlc, n=N):
    """A day whose 1h bars have the given (high, low, close): each hour's minutes sit at its close, with one minute at
    the high and one at the low (before the last minute)."""
    o, h, l, c = (np.zeros(n) for _ in range(4))
    for b, (hh, ll, cc) in enumerate(bars_hlc):
        a, z = b * 60, min(b * 60 + 60, n)
        o[a:z] = h[a:z] = l[a:z] = c[a:z] = cc
        h[a + 1] = hh
        l[a + 2] = ll
    last = bars_hlc[-1][2]
    k = len(bars_hlc) * 60
    if k < n:
        o[k:] = h[k:] = l[k:] = c[k:] = last
    return o, h, l, c


# ------------------------------------------------------------------------------------------------ bars, swings, ATR
def test_bars_anchor_at_open_and_partial_last_hour():
    S = series([flat_day(), flat_day()])
    H = W.bars(S, 60)
    assert len(H["o"]) == 14                                  # 6 full hours + 15:15-15:29, per session
    assert list(S.mt[H["s"][:7]]) == [OPEN + 60 * i for i in range(7)]
    assert S.mt[H["e"][6]] == 15 * 60 + 29 and H["day"][7] == 1
    B5 = W.bars(S, 5)
    assert len(B5["o"]) == 150 and S.mt[B5["e"][0]] == OPEN + 4
    # every 1h close is also a 15m close (needed by S15)
    B15 = W.bars(S, 15)
    assert set(H["e"]) <= set(B15["e"])


def test_swings_strict_n2_n3():
    h = np.array([1, 2, 3, 5, 3, 2, 1, 4, 4, 1, 0], float)
    l = h.copy()
    hs, ls = W.swings(h, l, 2)
    assert list(np.flatnonzero(hs)) == [3]                    # the equal highs at 7, 8 are not swings (strict)
    assert list(np.flatnonzero(ls)) == [6]
    hs3, _ = W.swings(h, l, 3)
    assert list(np.flatnonzero(hs3)) == [3]
    assert not W.swings(h[:4], l[:4], 2)[0].any()


def test_atr_wilder():
    B = dict(h=np.full(20, 2.0), l=np.zeros(20), c=np.ones(20))
    A = W.atr(B, 14)
    assert np.isnan(A[12]) and A[13] == pytest.approx(2.0) and A[19] == pytest.approx(2.0)
    B["h"][14] = 16.0                                         # TR 16 -> (2 x 13 + 16) / 14 = 3
    assert W.atr(B, 14)[14] == pytest.approx(3.0)


def test_strong_candle_rules():
    B5 = dict(o=np.array([0, 10.0, 10, 10, 10]), h=np.array([0, 10.2, 10.5, 10.2, 10.1]),
              l=np.array([0, 8.0, 8.0, 9.5, 8.6]), c=np.array([0, 8.2, 9.6, 9.6, 8.7]))
    A = np.array([2.0, 2.0, 2.0, 2.0, 2.0])
    assert W.strong(B5, A, 1, -1)                             # body 1.8 of 2.2 (82%), range 2.2 >= ATR 2
    assert not W.strong(B5, A, 1, 1)                          # bearish, not bullish
    assert not W.strong(B5, A, 2, -1)                         # body 0.4 of 2.5 (16%)
    assert not W.strong(B5, A, 3, -1)                         # range 0.7 < ATR 2
    assert not W.strong(B5, A, 4, -1)                         # range 1.5 < 2
    A2 = np.array([2.0, 2.0, 2.0, 0.7, 2.0])
    assert W.strong(B5, A2, 4, -1)                            # uses the PREVIOUS bar's ATR (0.7), not its own
    assert not W.strong(B5, A, 0, -1)


# ------------------------------------------------------------------------------------------------ levels and sweeps
def two_days(day2_bars, day1_bars=None):
    """Day 1 makes a 1h swing high of 110 at bar 2 (fractal 2) and a swing low of 90 at bar 5."""
    d1 = day1_bars or [(101, 99, 100), (103, 100, 102), (110, 103, 105), (104, 100, 101), (102, 95, 97),
                       (97, 90, 94), (98, 93, 96)]
    return series([hourly_day(d1), hourly_day(day2_bars)])


def test_swing_confirmed_after_right_bars_close():
    S = two_days([(100, 96, 97)] * 7)
    H = W.bars(S, 60)
    Lv, sw, _ = W.levels(S, H, H, 2, False, 0.05)
    hi = np.flatnonzero((Lv.side == 1) & (Lv.px == 110))
    assert len(hi) == 1
    assert Lv.born[hi[0]] == H["e"][4]                        # bar 2 + 2 right bars -> known at bar 4's close
    lo = np.flatnonzero((Lv.side == -1) & (Lv.px == 90))
    assert Lv.born[lo[0]] == H["e"][7]                        # bar 5 confirmed by bar 7 = the next day's first bar


def test_sweep_next_day_wick_above_close_below():
    S = two_days([(100, 96, 97), (111, 99, 108), (104, 98, 99), (100, 97, 98), (99, 97, 98), (99, 97, 98),
                  (99, 97, 98)])
    H = W.bars(S, 60)
    Lv, sw, amb = W.levels(S, H, H, 2, False, 0.05)
    s = [x for x in sw if x["side"] == -1]
    assert len(s) == 1 and s[0]["L"] == 110 and s[0]["X"] == 111 and s[0]["day"] == 1 and s[0]["t"] == H["e"][8]
    assert Lv.why[s[0]["lvl"]] == "sweep" and amb == 0


def test_close_above_is_a_break_not_a_sweep():
    S = two_days([(100, 96, 97), (112, 99, 111), (113, 99, 108)] + [(108, 100, 101)] * 4)
    H = W.bars(S, 60)
    Lv, sw, _ = W.levels(S, H, H, 2, False, 0.05)
    assert not [x for x in sw if x["side"] == -1 and x["L"] == 110]
    i = int(np.flatnonzero((Lv.side == 1) & (Lv.px == 110))[0])
    assert Lv.why[i] == "break" and Lv.died[i] == H["e"][8]


def test_same_day_swing_not_eligible_unless_any():
    # the swing high 110 forms on day 0 and is swept on day 0 (bar 6)
    d = [(101, 99, 100), (103, 100, 102), (110, 103, 105), (104, 100, 101), (102, 95, 97), (98, 94, 96),
         (111, 97, 105)]
    S = series([hourly_day(d), flat_day(100)])
    H = W.bars(S, 60)
    _, sw, _ = W.levels(S, H, H, 2, False, 0.05)
    assert not [x for x in sw if x["side"] == -1]
    _, sw2, _ = W.levels(S, H, H, 2, True, 0.05)
    assert [x["L"] for x in sw2 if x["side"] == -1] == [110]


def test_latest_intact_swing_is_the_candidate():
    # day 0: swing highs 110 (bar 2) and 108 (bar 6, confirmed day 1 bar 1); day 1 bar 3 pokes 109 and closes 107:
    # a sweep of the LATEST swing (108), not of 110
    d0 = [(101, 99, 100), (103, 100, 102), (110, 103, 105), (104, 100, 101), (102, 95, 97), (104, 96, 103),
          (108, 100, 104)]
    d1 = [(105, 101, 102), (104, 100, 101), (103, 100, 102), (109, 101, 107), (106, 102, 103), (104, 102, 103),
          (104, 102, 103)]
    S = series([hourly_day(d0), hourly_day(d1)])
    H = W.bars(S, 60)
    _, sw, _ = W.levels(S, H, H, 2, False, 0.05)
    assert [x["L"] for x in sw if x["side"] == -1] == [108]


def test_ambiguous_two_sided_bar_and_expiry_and_reset():
    S = two_days([(100, 96, 97), (112, 88, 100)] + [(101, 99, 100)] * 5)
    H = W.bars(S, 60)
    _, sw, amb = W.levels(S, H, H, 2, False, 0.05)
    assert amb == 1 and not [x for x in sw if x["k"] == 8]
    # expiry: 7 flat sessions later, a poke above 110 is not a sweep any more
    days = [hourly_day([(101, 99, 100), (103, 100, 102), (110, 103, 105), (104, 100, 101), (102, 95, 97),
                        (100, 96, 98), (100, 96, 98)])] + [flat_day(100)] * 6
    days.append(hourly_day([(111, 99, 105)] + [(105, 104, 105)] * 6))
    S = series(days)
    H = W.bars(S, 60)
    Lv, sw, _ = W.levels(S, H, H, 2, False, 0.05)
    assert not [x for x in sw if x["L"] == 110]
    assert Lv.why[int(np.flatnonzero(Lv.px == 110)[0])] == "expired"
    # reset (MCX contract roll) on day 1 clears the levels
    S = two_days([(100, 96, 97), (111, 99, 108)] + [(104, 98, 99)] * 5)
    S.reset[1] = True
    H = W.bars(S, 60)
    _, sw, _ = W.levels(S, H, H, 2, False, 0.05)
    assert not [x for x in sw if x["L"] == 110]


def test_sweep_on_15m_close():
    # day 1, 10:15-10:29: up to 111, then the 15m bar closes at 109 (back below 110); the hour closes at 112 above
    d1 = list(hourly_day([(100, 96, 97)] + [(101, 99, 100)] * 6))
    o, h, l, c = (np.array(x) for x in d1)
    h[61], c[60:75], o[60:75], l[60:75] = 111.0, 109.0, 109.0, 108.0
    h[60:75] = np.maximum(h[60:75], 109.0)
    c[75:120] = o[75:120] = l[75:120] = 112.0
    h[75:120] = 112.5
    S = series([hourly_day([(101, 99, 100), (103, 100, 102), (110, 103, 105), (104, 100, 101), (102, 95, 97),
                            (97, 90, 94), (98, 93, 96)]), (o, h, l, c)])
    H, Q = W.bars(S, 60), W.bars(S, 15)
    _, sw, _ = W.levels(S, H, Q, 2, False, 0.05)
    assert [(x["L"], S.mt[x["t"]]) for x in sw if x["side"] == -1] == [(110, 10 * 60 + 29)]
    _, sw1, _ = W.levels(S, H, H, 2, False, 0.05)
    assert not [x for x in sw1 if x["side"] == -1]            # on 1h the hour closed above: a break


# ------------------------------------------------------------------------------------------------ the trade
def one_day(edits, px=100.0):
    o, h, l, c = flat_day(px)
    for m, (oo, hh, ll, cc) in edits.items():
        o[m - N], h[m - N], l[m - N], c[m - N] = oo, hh, ll, cc      # keys are global minutes of day 1
    return series([flat_day(px), (o, h, l, c)], pdh=[np.nan, 101.0], pdl=[np.nan, 92.0])


def lv_with_low(S, px=94.0):
    return W.Levels(np.array([-1]), np.array([px]), np.array([0]), np.array([0]), np.array([0]),
                    np.array([W.INF]), [""])


def run1(S, v=K.BY_NAME["B"], Lv=None, X=101.0, sweep_min=59, first=K.NSE_FIRST, last=K.NSE_LAST, sweeps=None):
    B5 = W.bars(S, 5)
    A = np.ones(len(B5["o"]))
    sq = np.array([N - 1, N + (15 * 60 + 15 - OPEN)])
    sw = sweeps or [dict(side=-1, L=100.5, X=X, k=0, day=1, t=N + sweep_min)]
    return W.run(S, v, lv_with_low(S) if Lv is None else Lv, sw, B5, A, 0.05, sq, first, last, np.array([False, True]))


STRONG = {N + 60: (100, 100.2, 98, 98.2), **{N + m: (98.2, 98.3, 98.1, 98.2) for m in range(61, 65)}}


def test_short_trade_fill_and_target():
    S = one_day({**STRONG, N + 66: (98.1, 98.15, 97.9, 98.0), N + 80: (95, 95, 93.9, 94.5)})
    T, F = run1(S)
    assert F["filled"] == 1 and len(T) == 1
    t = T[0]
    assert t["side"] == -1 and t["entry"] == 98 and t["stop"] == pytest.approx(101.05) and t["target"] == 94
    assert t["t"] == N + 66 and t["fill"] == 98 and t["why"] == "target" and t["xm"] == N + 80
    assert t["pts"] == pytest.approx(4.0) and t["risk"] == pytest.approx(3.05) and t["reward"] == pytest.approx(4.0)


def test_rr_filter_and_targets():
    S = one_day({**STRONG, N + 66: (98.1, 98.15, 97.9, 98.0)})
    _, F = run1(S, Lv=lv_with_low(S, 95.0))                   # reward 3 / risk 3.05 < 1
    assert F["rr"] == 1 and F["filled"] == 0
    _, F = run1(S, Lv=lv_with_low(S, 99.0))                   # no swing low below the entry
    assert F["no_target"] == 1
    T, _ = run1(S, v=K.BY_NAME["TPD"])                        # previous day's low 92
    assert T[0]["target"] == 92
    T, _ = run1(S, v=K.BY_NAME["T2R"])
    assert T[0]["target"] == pytest.approx(98 - 2 * 3.05)
    _, F = run1(S, v=K.BY_NAME["RR2"])                        # 94: RR 1.31 < 2
    assert F["rr"] == 1


def test_target_snapshot_uses_only_levels_alive_then():
    S = one_day({**STRONG, N + 66: (98.1, 98.15, 97.9, 98.0)})
    tc = N + 64
    late = W.Levels(np.array([-1]), np.array([94.0]), np.array([0]), np.array([0]), np.array([tc + 1]),
                    np.array([W.INF]), [""])
    _, F = run1(S, Lv=late)                                   # born after the strong candle's close
    assert F["no_target"] == 1
    dead = W.Levels(np.array([-1]), np.array([94.0]), np.array([0]), np.array([0]), np.array([0]),
                    np.array([tc]), ["break"])
    _, F = run1(S, Lv=dead)                                   # broken at that very close
    assert F["no_target"] == 1


def test_cancel_before_the_candle_and_no_candle():
    S = one_day({N + 60: (100, 101.1, 99.5, 99.6), N + 66: (99.6, 99.6, 97.0, 97.5)})
    _, F = run1(S)
    assert F["cancel_pre"] == 1                               # the sweep high (+1 tick) retaken first
    S = one_day({N + 60: (100, 100.2, 99.8, 99.9)})
    _, F = run1(S)
    assert F["no_candle"] == 1
    # a strong candle in the 7th bar after the sweep is too late
    S = one_day({N + 90: (100, 100.2, 98, 98.2), N + 96: (98.1, 98.15, 97.9, 98.0)})
    _, F = run1(S)
    assert F["no_candle"] == 1


def test_order_cancelled_at_stop_expired_and_gap_fill():
    S = one_day({**STRONG, N + 66: (99, 101.1, 99, 100.9), N + 70: (98, 98, 97, 97.5)})
    _, F = run1(S)
    assert F["order_stop"] == 1
    S = one_day({**STRONG, N + 100: (98, 98, 97, 97.5)})      # beyond the 6-bar order life (to minute 94)
    _, F = run1(S)
    assert F["order_expired"] == 1
    S = one_day({**STRONG, N + 66: (97.5, 97.6, 97.0, 97.2)})  # opened below the entry -> filled at that open
    T, _ = run1(S)
    assert T[0]["fill"] == 97.5 and T[0]["t"] == N + 66


def test_fill_and_stop_in_one_minute_and_stop_exit():
    S = one_day({**STRONG, N + 66: (98.1, 101.2, 97.9, 101.1)})
    T, F = run1(S)
    assert F["filled"] == 1 and T[0]["why"] == "stop" and T[0]["xm"] == N + 66   # the fill minute's close is the stop
    S = one_day({**STRONG, N + 66: (98.1, 98.15, 97.9, 98.0), N + 70: (101.2, 101.3, 101.0, 101.1)})
    T, _ = run1(S)
    assert T[0]["why"] == "stop" and T[0]["pts"] == pytest.approx(98 - 101.2)     # gapped through: that open


def test_window_flat_time_and_daily_cap():
    S = one_day({**STRONG, N + 66: (98.1, 98.15, 97.9, 98.0)})
    _, F = run1(S, last=OPEN + 65)                            # the fill minute is after the last fill time
    assert F["order_expired"] == 1
    T, _ = run1(S)
    assert T[0]["why"] == "time" and T[0]["xm"] == N + 360    # flat at the open of 15:15
    # three sweeps, each with its own strong candle and fill; the third is over the daily cap
    ed = {}
    for base in (60, 120, 180):
        ed[N + base] = (100, 100.2, 98, 98.2)
        for m in range(base + 1, base + 5):
            ed[N + m] = (98.2, 98.3, 98.1, 98.2)
        ed[N + base + 6] = (98.1, 98.15, 97.9, 98.0)
        ed[N + base + 10] = (99.5, 101.2, 99.5, 100.0)        # stopped out
        for m in range(base + 11, base + 60):
            ed[N + m] = (100, 100, 100, 100)
    S = one_day(ed)
    sw = [dict(side=-1, L=100.5, X=101.0, k=i, day=1, t=N + b - 1) for i, b in enumerate((60, 120, 180))]
    T, F = run1(S, sweeps=sw)
    assert F["filled"] == 2 and F["cap"] == 1


def test_one_position_at_a_time():
    ed = {**STRONG, N + 66: (98.1, 98.15, 97.9, 98.0), N + 70: (100, 100.2, 98, 98.2),
          **{N + m: (98.2, 98.3, 98.1, 98.2) for m in range(71, 75)}, N + 76: (98.1, 98.15, 97.9, 98.0)}
    S = one_day(ed)
    sw = [dict(side=-1, L=100.5, X=101.0, k=0, day=1, t=N + 59), dict(side=-1, L=100.5, X=101.0, k=1, day=1, t=N + 69)]
    T, F = run1(S, sweeps=sw)
    assert F["filled"] == 1 and F["busy"] == 1


def test_long_mirror():
    ed = {N + 60: (100, 102, 99.8, 101.8), **{N + m: (101.8, 101.9, 101.7, 101.8) for m in range(61, 65)}, N + 66: (101.9, 102.1, 101.9, 102.0), N + 80: (106, 106.2, 105.5, 106)}
    S = one_day(ed)
    hi = W.Levels(np.array([1]), np.array([106.0]), np.array([0]), np.array([0]), np.array([0]),
                  np.array([W.INF]), [""])
    sw = [dict(side=1, L=99.5, X=99.0, k=0, day=1, t=N + 59)]
    T, F = run1(S, Lv=hi, sweeps=sw)
    assert F["filled"] == 1
    t = T[0]
    assert t["side"] == 1 and t["entry"] == 102 and t["stop"] == pytest.approx(98.95) and t["target"] == 106
    assert t["why"] == "target" and t["pts"] == pytest.approx(4.0)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def random_days(rng, nd=25, n=N):
    out = []
    px = 1000.0
    for _ in range(nd):
        r = rng.standard_t(3, n) * 0.8
        c = px + np.cumsum(r)
        o = np.r_[px, c[:-1]]
        h = np.maximum(o, c) + np.abs(rng.normal(0, 0.6, n))
        l = np.minimum(o, c) - np.abs(rng.normal(0, 0.6, n))
        out.append((o, h, l, c))
        px = c[-1] + rng.normal(0, 3)
    return out


def everything(S, v):
    H, Q, B5 = W.bars(S, 60), W.bars(S, v.sweep_tf), W.bars(S, 5)
    A = W.atr(B5)
    Lv, sw, _ = W.levels(S, H, Q, v.n, v.anyday, 0.05)
    nd = S.ndays
    sq = np.array([int(np.flatnonzero((S.mday == d) & (S.mt >= 15 * 60 + 15))[0]) for d in range(nd)])
    T, _ = W.run(S, v, Lv, sw, B5, A, 0.05, sq, K.NSE_FIRST, K.NSE_LAST, np.ones(nd, bool))
    return Lv, sw, T, A, B5


def test_random_series_make_trades():
    n = sum(len(everything(series(random_days(np.random.default_rng(s))), K.BY_NAME["B"])[2]) for s in range(1, 7))
    assert n >= 10


@pytest.mark.parametrize("seed", [1, 2, 3, 4, 5, 6])
@pytest.mark.parametrize("vname", [v.name for v in K.VARIANTS])
def test_look_ahead_guard(seed, vname):
    v = K.BY_NAME[vname]
    rng = np.random.default_rng(seed)
    days = random_days(rng)
    S = series(days)
    Lv, sw, T, A, B5 = everything(S, v)
    assert len(sw) >= 2                                       # the guard is not vacuous
    nt = len(S.mt)
    for cut in rng.integers(nt // 4, nt - 10, size=4):
        cut = int(cut)
        noisy = []
        for x in days:
            noisy.append(tuple(np.array(a, float) for a in x))
        flat = [np.concatenate([d[j] for d in noisy]) for j in range(4)]
        z = rng.normal(0, 25, nt - cut - 1)
        for j in range(4):
            flat[j][cut + 1:] += z
        flat[1][cut + 1:] = np.maximum.reduce([flat[0][cut + 1:], flat[1][cut + 1:], flat[3][cut + 1:]])
        flat[2][cut + 1:] = np.minimum.reduce([flat[0][cut + 1:], flat[2][cut + 1:], flat[3][cut + 1:]])
        S2 = W.Series(S.mday, S.mt, flat[0], flat[1], flat[2], flat[3], S.anchor, S.reset, S.pdh, S.pdl)
        Lv2, sw2, T2, A2, B52 = everything(S2, v)
        b = Lv.born <= cut
        b2 = Lv2.born <= cut
        assert sorted(zip(Lv.side[b], Lv.px[b], Lv.p[b], Lv.born[b])) == \
            sorted(zip(Lv2.side[b2], Lv2.px[b2], Lv2.p[b2], Lv2.born[b2]))
        key = lambda s: (s["t"], s["side"], s["L"], s["X"])  # noqa: E731
        assert [key(s) for s in sw if s["t"] <= cut] == [key(s) for s in sw2 if s["t"] <= cut]
        tk = lambda t: (t["t"], t["side"], t["entry"], t["stop"], t["target"], t["tc"])  # noqa: E731
        assert [tk(t) for t in T if t["t"] <= cut] == [tk(t) for t in T2 if t["t"] <= cut]
        done = B5["e"] <= cut
        np.testing.assert_allclose(A[done], A2[done])
