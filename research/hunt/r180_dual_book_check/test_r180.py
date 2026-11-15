"""R180 tests: the manual's straddle measure, Book 1 / Book 2 signals, the X1 / E3 exits, the one-position account and
the monthly pause, the study helpers (BH count, gates, reproduction check), and the look-ahead guard (values at a minute
never change when later minutes are deleted or perturbed). Synthetic data only: no stores, no lake."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r180cfg as K  # noqa: E402

pytest.importorskip("numba")
import r180engine as G  # noqa: E402
import r180rules as RU  # noqa: E402
import r180study as S  # noqa: E402

N = 375
D0 = date(2025, 6, 10)


def _con(px=100.0, vol=1e7):
    C = np.full((5, N), px, dtype=np.float64)
    C[4] = vol
    return C


class _Day:
    def __init__(self, d=D0, expiry=False):
        self.d = d
        self.expiry = expiry
        self.idx = np.full((4, N), 20000.0)
        self.atm = np.full(N, 20000.0)
        self.con = {("C", 20000.0): _con(100.0), ("P", 20000.0): _con(100.0)}


# ------------------------------------------------------------------------------------------------ straddle
def test_straddle_measure_and_lookahead():
    D = _Day()
    D.con[("C", 20000.0)][3, :] = np.linspace(100, 140, N)
    S_ = RU.straddle_series(D.atm, D.con)
    v = RU.straddle_vs_0920(S_)
    assert np.isnan(v[:5]).all()
    assert v[5] == 0.0
    base = S_[5]
    assert v[200] == pytest.approx((S_[200] - base) / base)
    # look-ahead guard: perturb / delete every minute after m, the value at m is unchanged
    for m in (5, 14, 180, 330):
        D2 = _Day()
        D2.con[("C", 20000.0)][3, :] = np.linspace(100, 140, N)
        D2.con[("C", 20000.0)][3, m + 1:] = np.nan if m % 2 else 999.0
        D2.atm[m + 1:] = 20050.0
        v2 = RU.straddle_vs_0920(RU.straddle_series(D2.atm, D2.con))
        assert v2[m] == v[m]


def test_straddle_missing_base_or_leg_is_nan():
    D = _Day()
    D.con[("P", 20000.0)][3, 5] = np.nan
    assert np.isnan(RU.straddle_vs_0920(RU.straddle_series(D.atm, D.con))).all()
    D = _Day()
    D.atm[100] = np.nan
    v = RU.straddle_vs_0920(RU.straddle_series(D.atm, D.con))
    assert np.isnan(v[100]) and v[101] == 0.0
    assert np.isnan(RU.straddle_series(None, {})).all()


# ------------------------------------------------------------------------------------------------ 5-minute helpers
def test_donchian_prior_excludes_current_bar():
    h = np.arange(30, dtype=float)
    l = h - 1
    chi, clo = RU.donchian_prior(h, l, 20)
    assert np.isnan(chi[19]) and chi[20] == 19.0 and clo[20] == -1.0
    assert chi[25] == 24.0


def test_atr_ewm_span14():
    h = np.array([10.0, 12, 11]); l = np.array([9.0, 10, 9]); c = np.array([9.5, 11, 10])
    a = RU.atr_ewm(h, l, c)
    al = 2 / 15
    assert a[0] == 1.0
    assert a[1] == pytest.approx(al * 2.5 + (1 - al) * 1.0)


def test_last_bar_at():
    bod = np.arange(75)
    assert RU.last_bar_at(bod, 3) == -1
    assert RU.last_bar_at(bod, 4) == 0          # bar 0 completes at the close of minute 4
    assert RU.last_bar_at(bod, 184) == 36
    assert RU.last_bar_at(bod, 183) == 35
    assert RU.last_bar_at(np.array([0, 1, 5]), 40) == 2


# ------------------------------------------------------------------------------------------------ Book 1
def _b1_world(n_days=3, brk_day=2, brk_bar=10, s=1, unds=K.UNDS, jump=50.0, strad_v=0.02):
    """Flat 5-minute bars, then a break on (brk_day, brk_bar) on every index in `unds`."""
    days = [date(2025, 6, 2 + i) for i in range(n_days)]
    bars, mins, strad = {}, {}, {}
    for u in K.UNDS:
        d_, b_, o_, h_, l_, c_ = [], [], [], [], [], []
        mins[u], strad[u] = {}, {}
        for di, d in enumerate(days):
            M = np.full((4, N), 100.0)
            M[1] += 1.0
            M[2] -= 1.0
            for b in range(75):
                o, h, l, c = 100.0, 101.0, 99.0, 100.0
                if di == brk_day and b == brk_bar and u in unds:
                    c = 100.0 + s * jump
                    h, l = max(h, c), min(l, c)
                    M[3, 5 * b + 4] = c
                d_.append(d); b_.append(b); o_.append(o); h_.append(h); l_.append(l); c_.append(c)
            mins[u][d] = M
            strad[u][d] = np.full(N, strad_v)
        bars[u] = (np.array(d_, dtype=object), np.array(b_), np.array(o_), np.array(h_), np.array(l_), np.array(c_))
    return bars, mins, strad


def test_book1_breadth_four_of_five():
    bars, mins, strad = _b1_world(unds=K.UNDS[:4])
    c, n = RU.book1_candidates(bars, mins, strad)
    assert len(c) == 4 and {x["und"] for x in c} == set(K.UNDS[:4])
    assert all(x["side"] == 1 and x["em"] == 55 and x["sig"] == 54 for x in c)
    bars, mins, strad = _b1_world(unds=K.UNDS[:3])
    assert RU.book1_candidates(bars, mins, strad)[0] == []


def test_book1_filters_straddle_window_strength():
    bars, mins, strad = _b1_world(strad_v=0.0)              # straddle must be > 0
    c, n = RU.book1_candidates(bars, mins, strad)
    assert c == [] and n["straddle"] == 5
    bars, mins, strad = _b1_world(brk_bar=1)                # em = 10 < 15
    c, n = RU.book1_candidates(bars, mins, strad)
    assert c == [] and n["window"] == 5
    bars, mins, strad = _b1_world(jump=1.2)                 # |C - edge| / ATR < 0.5 (edge 101, ATR ~2)
    c, n = RU.book1_candidates(bars, mins, strad)
    assert c == [] and n["strength"] == 5
    bars, mins, strad = _b1_world(s=-1)
    c, _ = RU.book1_candidates(bars, mins, strad)
    assert len(c) == 5 and all(x["side"] == -1 for x in c)


def test_book1_initial_breakout_lock():
    bars, mins, strad = _b1_world()
    d, b, o, h, l, c = bars["NIFTY"]
    # a second break 3 bars later on every index, the index never back to the channel middle in between
    for u in K.UNDS:
        d, b, o, h, l, c = bars[u]
        i = [k for k in range(len(d)) if d[k] == date(2025, 6, 4) and b[k] == 13][0]
        c = c.copy(); h = h.copy()
        c[i] = 160.0; h[i] = 160.0
        bars[u] = (d, b, o, h, l, c)
        mins[u][date(2025, 6, 4)][2, 55:] = 120.0        # lows stay above the middle (~100) after the first break
    cs, n = RU.book1_candidates(bars, mins, strad)
    assert len(cs) == 5 and n["not_initial"] == 5
    # back at the middle -> free again from xm + 1
    for u in K.UNDS:
        mins[u][date(2025, 6, 4)][2, 60] = 99.0
    cs, n = RU.book1_candidates(bars, mins, strad)
    assert len(cs) == 10 and n["not_initial"] == 0


def test_release_minute():
    M = np.full((4, N), 100.0)
    assert RU.release_minute(M, 50, 99.0, 1) == K.row(15, 10)
    M[2, 70] = 98.0
    assert RU.release_minute(M, 50, 99.0, 1) == 70
    M[1, 60] = 102.0
    assert RU.release_minute(M, 50, 101.0, -1) == 60


def test_book1_lookahead_guard():
    """Signals up to a minute are unchanged when every bar after that minute changes."""
    bars, mins, strad = _b1_world()
    base, _ = RU.book1_candidates(bars, mins, strad)
    for u in K.UNDS:
        d, b, o, h, l, c = bars[u]
        cut = [k for k in range(len(d)) if d[k] == date(2025, 6, 4) and b[k] == 10][0]
        rng = np.random.default_rng(1)
        c = c.copy(); h = h.copy(); l = l.copy()
        c[cut + 1:] += rng.normal(0, 30, len(c) - cut - 1)
        h[cut + 1:] = np.maximum(h[cut + 1:], c[cut + 1:]); l[cut + 1:] = np.minimum(l[cut + 1:], c[cut + 1:])
        bars[u] = (d, b, o, h, l, c)
        strad[u][date(2025, 6, 4)][55:] = -1.0
    got, _ = RU.book1_candidates(bars, mins, strad)
    first = [x for x in got if (x["day"], x["sig"]) <= (date(2025, 6, 4), 54)]
    assert [(x["und"], x["sig"], x["side"]) for x in first] == [(x["und"], x["sig"], x["side"]) for x in base]


# ------------------------------------------------------------------------------------------------ Book 2
def _bars_from(o, h, l, c, day=D0):
    n = len(c)
    return (np.array([day] * n, dtype=object), np.arange(n), np.array(o, float), np.array(h, float),
            np.array(l, float), np.array(c, float))


def _ob_series():
    """25 small bars, a swing high at bar 22, a bearish candle at 25, a big bullish displacement at 26."""
    o = [100.0] * 30; c = [100.5] * 30; h = [101.0] * 30; l = [99.5] * 30
    h[22] = 104.0                                   # swing high at 22, confirmed at 24
    o[25], c[25], h[25], l[25] = 101.0, 100.0, 101.2, 99.8      # last bearish candle (the OB)
    o[26], c[26], h[26], l[26] = 100.0, 106.0, 106.5, 99.9      # displacement: body 6 > 1.5 x 0.5, close > 104
    return o, h, l, c


def test_order_block_found():
    ob = RU.order_blocks(_bars_from(*_ob_series()))
    assert ob == [(26, 1, 99.8, 101.2)]


def test_order_block_needs_big_body_break_and_opposite_candle():
    o, h, l, c = _ob_series()
    c2 = list(c); c2[26] = 103.0                    # below the swing high: no break
    assert RU.order_blocks(_bars_from(o, h, l, c2)) == []
    o3 = list(o); o3[26] = 105.5                    # body 0.5: not a displacement
    assert RU.order_blocks(_bars_from(o3, h, l, c)) == []
    o4, c4 = list(o), list(c)
    o4[25], c4[25] = 100.0, 100.5                   # no bearish candle within 5 bars
    assert RU.order_blocks(_bars_from(o4, h, l, c4)) == []


def test_swing_broken_once():
    o, h, l, c = _ob_series()
    o += [100.0] * 3; h += [106.4] * 3; l += [99.5] * 3; c += [106.0] * 3
    o[28], c[28], h[28], l[28] = 101.0, 100.0, 101.0, 99.0
    o[29], c[29] = 100.0, 106.2                     # a big bull bar above the old (already broken) swing 104, below the
    #                                                 new swing 106.5 (bar 26, confirmed at 28): no new break
    ob = RU.order_blocks(_bars_from(o, h, l, c))
    assert [x[0] for x in ob] == [26]


def test_book2_trigger_and_stop_points():
    o, h, l, c = _ob_series()
    # place the 30 bars so the displacement bar is bar 36 (12:15-12:19): validity covers 1-min 185 .. 244
    pad = 10
    o = [100.0] * pad + o; h = [101.0] * pad + h; l = [99.5] * pad + l; c = [100.5] * pad + c
    bars = {"NIFTY": _bars_from(o, h, l, c)}
    M = np.full((4, N), 105.0)
    M[0, 200], M[1, 200], M[2, 200], M[3, 200] = 101.0, 101.5, 100.9, 101.4    # dips into [99.8, 101.2], closes up
    strad = {"NIFTY": {D0: np.full(N, -0.10)}}
    cs, n = RU.book2_candidates(bars, {"NIFTY": {D0: M}}, strad, unds=("NIFTY",))
    assert len(cs) == 1
    x = cs[0]
    assert x["sig"] == 200 and x["side"] == 1 and x["ref"] == 101.4
    atr = RU.atr_ewm(*[np.array(a) for a in (h, l, c)])
    assert x["stop_pts"] == pytest.approx(abs(101.4 - 99.8) + 0.10 * atr[39])     # bar 39 = 12:30-12:34 done by 12:35
    # straddle not below -5% -> no trade; trigger outside 12:15 - 14:45 -> no trade
    cs, _ = RU.book2_candidates(bars, {"NIFTY": {D0: M}}, {"NIFTY": {D0: np.full(N, -0.04)}}, unds=("NIFTY",))
    assert cs == []
    # look-ahead guard: perturb every minute after the trigger, the trigger is unchanged
    M2 = M.copy()
    M2[:, 201:] = 50.0
    s2 = {"NIFTY": {D0: np.r_[np.full(201, -0.10), np.full(N - 201, 0.5)]}}
    cs2, _ = RU.book2_candidates(bars, {"NIFTY": {D0: M2}}, s2, unds=("NIFTY",))
    assert [(y["sig"], y["ref"], y["stop_pts"]) for y in cs2] == [(x["sig"], x["ref"], x["stop_pts"])]


# ------------------------------------------------------------------------------------------------ exits
SL = G.SLIP1


def _x1(C, e=100, end=160, mode_open=False, qty=75.0):
    return G.walk_x1(C, e, end, mode_open, qty, 0.05, 0.30, 0.20, 0.50, *SL)


def test_x1_time_exit_and_entry_at_close():
    C = _con(100.0)
    C[3, 100] = 102.0
    r = _x1(C)
    assert r[0] == 102.0 and r[4] == 100 and r[5] == 160 and r[6] == 4
    r = _x1(C, mode_open=True)
    assert r[0] == 100.0 and r[6] == 4


def test_x1_stop_and_gap():
    C = _con(100.0)
    real = G._r2(100.0 * SL[0]) + 0.05
    C[2, 110] = 60.0
    r = _x1(C)
    assert r[6] == 1 and r[5] == 110 and r[1] == pytest.approx(0.70 * real)
    C[0, 110] = 50.0                                # gap below the stop: the open
    r = _x1(C)
    assert r[1] == 50.0


def test_x1_lock_uses_prior_peak():
    C = _con(100.0)
    C[1, 105] = 130.0                               # peak +30% at 105
    C[2, 105] = 99.0                                # the same minute's low does not lock (prior peak only)
    C[0, 106], C[2, 106] = 120.0, 110.0            # 106: opens 120, low 110 <= real + 0.5 x (130 - real) ~ 115
    r = _x1(C)
    assert r[6] == 2 and r[5] == 106
    real = r[2]
    assert r[1] == pytest.approx(real + 0.5 * (130.0 - real))


def test_x1_never_reads_before_entry_and_expiry_end():
    C = _con(100.0)
    C[2, :100] = 1.0                                # crashes before the entry minute are ignored
    assert _x1(C)[6] == 4
    assert G.end_row("B1", 330, True) == K.EXPIRY_EOD
    assert G.end_row("B1", 330, False) == K.B1_EOD
    assert G.end_row("B1", 100, False) == 160
    assert G.end_row("B2", 200, False) == K.B2_EOD and G.end_row("B2", 200, True) == K.EXPIRY_EOD


def _e3(C, I, side=1, ref=20000.0, pts=20.0, mode_open=False, e=200, end=364, qty=75.0):
    return G.walk_e3(C, I, e, end, side, ref, pts, mode_open, qty, 0.05, 1000.0, 0.60, *SL)


def test_e3_index_stop_lock_and_time():
    C = _con(100.0)
    I = np.full((4, N), 20000.0)
    r = _e3(C, I)
    assert r[6] == 4 and r[5] == 364
    I[2, 250] = 19979.0                             # breaches 20000 - 20
    r = _e3(C, I)
    assert r[6] == 1 and r[5] == 250 and r[1] == 100.0
    r = _e3(C, I, mode_open=True, e=201)
    assert r[6] == 1 and r[5] == 251
    I2 = np.full((4, N), 20000.0)
    I2[1, 250] = 20021.0
    assert _e3(C, I2, side=-1)[6] == 1
    C2 = _con(100.0)
    C2[3, 210] = 120.0                              # +Rs 1,500 (75 x 20)
    C2[3, 211] = 108.0                              # +Rs 600 <= 60% of 1,500
    r = _e3(C2, np.full((4, N), 20000.0))
    assert r[6] == 2 and r[5] == 211 and r[1] == 108.0
    C3 = _con(100.0)
    C3[3, 210] = 110.0                              # +Rs 750 peak: below the Rs 1,000 trigger, no lock
    C3[3, 211] = 101.0
    assert _e3(C3, np.full((4, N), 20000.0))[6] == 4


def test_e3_lookahead_exit_unchanged_by_later_data():
    C = _con(100.0)
    I = np.full((4, N), 20000.0)
    I[2, 250] = 19979.0
    r = _e3(C, I)
    C[:, 251:] = 5.0
    I[:, 251:] = 1.0
    assert _e3(C, I) == r


def test_price_cap_and_charges():
    D = _Day()
    D.con[("C", 20000.0)] = _con(800.0)             # 800 x 75 > Rs 56,000
    cnd = dict(book="B1", und="NIFTY", side=1, sig=99)
    t = G.price(cnd, D)
    assert not t["ok"] and t["why"] == "cap"
    D.con[("C", 20000.0)] = _con(100.0)
    t = G.price(cnd, D)
    assert t["ok"] and t["net"] < 0 and t["charges"] > 0     # flat price: loses slippage and charges
    assert G.price(dict(book="B1", und="NIFTY", side=-1, sig=99), _Day())["ok"]
    D2 = _Day()
    D2.con = {}
    assert G.price(cnd, D2)["why"] == "nofill"


# ------------------------------------------------------------------------------------------------ account
def _c(day, op, xr, net, order=(0, 0), ok=True, book="B1"):
    return dict(day=day, open=op, xr=xr, net=net, order=order, ok=ok, book=book, und="NIFTY", net2=net, why="time")


def test_one_position_and_tie_break():
    a = _c(D0, 100, 150, 10.0, order=(0, -0.2))
    b = _c(D0, 100, 120, 20.0, order=(0, -0.1))
    c = _c(D0, 150, 160, 5.0)                       # opens at the exit minute of a: blocked (needs exit + 1)
    d = _c(D0, 151, 160, 7.0)
    got = RU.one_position([d, c, b, a])
    assert got == [a, d]
    bad = _c(D0, 100, 150, 10.0, order=(0, -0.3), ok=False)
    assert RU.one_position([bad, a]) == [a]
    nxt = _c(date(2025, 6, 11), 20, 30, 1.0)
    assert RU.one_position([a, nxt]) == [a, nxt]


def test_monthly_pause():
    tr = [_c(date(2025, 6, 2 + i), 100, 110, -6000.0) for i in range(3)] + [_c(date(2025, 7, 1), 100, 110, 50.0)]
    got = RU.one_position(tr, pause=-11000.0)
    assert [t["day"] for t in got] == [date(2025, 6, 2), date(2025, 6, 3), date(2025, 7, 1)]
    assert len(RU.one_position(tr)) == 4


# ------------------------------------------------------------------------------------------------ study helpers
def test_bh_count_and_q():
    assert K.M_TOTAL == 4044 + 7
    q = S.bh_q([0.0001, 0.5])
    assert q[0] == pytest.approx(min(1.0, 0.0001 * K.M_TOTAL))
    assert q[1] == 1.0


def test_reproduces_and_gates():
    ours = dict(trades=300, win=64.0, net=50000)
    assert S.reproduces(ours, (361, 67.9, 78504))["all"]
    assert not S.reproduces(dict(trades=300, win=50.0, net=50000), (361, 67.9, 78504))["all"]
    assert not S.reproduces(dict(trades=361, win=67.9, net=-1), (361, 67.9, 78504))["net"]
    tr = [dict(day=date(2025, 6, 2), net=100.0, net2=90.0, und="NIFTY", why="time"),
          dict(day=date(2026, 5, 2), net=-50.0, net2=-60.0, und="NIFTY", why="stop")]
    sp = S.split(tr)
    assert sp["all"]["trades"] == 2 and sp["design"]["net"] == 100 and sp["hold"]["net"] == -50
    g = S.gates(sp, 0.01, 0.05)
    assert not g["passed"] and "hold_pos" in g["fails"] and "hold_n" in g["fails"]


def test_max_dd():
    assert S.max_dd([100, -50, -80, 200]) == -130
    assert S.max_dd([]) == 0
    assert S.max_dd([-10]) == -10


def test_import_does_not_touch_r51_config():
    import r51cfg
    assert r51cfg.START == date(2024, 11, 20) and r51cfg.OUT.name != "clean_r180"


def test_periods():
    assert K.period_of(date(2024, 11, 4)) == "design"
    assert K.period_of(date(2026, 3, 31)) == "design"
    assert K.period_of(date(2026, 4, 1)) == "hold"
    assert K.period_of(date(2026, 10, 20)) is None
    assert K.period_of(date(2024, 10, 31)) is None
