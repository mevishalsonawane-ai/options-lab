"""Tests for fam_h25h37 (R52 family H25 + H37). Synthetic markets only, no lake needed.

    cd research/hunt/r52 && python -m pytest -q test_fam_h25h37.py

Each signal function gets a market that must fire exactly the expected (side, minute) and one that must not, plus a
look-ahead guard (changing every bar after a signal's minute, that day and later days, must not change it) and the edge
cases: empty data, a missing minute, an expiry day. The TA-Lib ports are checked against TA-Lib itself when it can be
imported (skipped otherwise).
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fam_h25h37 as F  # noqa: E402

W = 375
OPEN_M = 9 * 60 + 15


# ------------------------------------------------------------------------------------------------ fake market
class FakeIx:
    def __init__(self, days, M, exp=None):
        self.days = list(days)
        self.M = {k: np.asarray(v, float) for k, v in M.items()}
        exp = exp or set()
        self.d = {d: dict(exp=d in exp) for d in self.days}
        self.pos = {d: i for i, d in enumerate(self.days)}

    def mat(self):
        return self.M

    def daily(self):
        M = self.M
        rows = []
        for i, d in enumerate(self.days):
            ok = ~np.isnan(M["c"][i])
            if not ok.any():
                continue
            rows.append(dict(day=d, open=M["o"][i][ok][0], high=np.nanmax(M["h"][i]), low=np.nanmin(M["l"][i]),
                             close=M["c"][i][ok][-1]))
        return pd.DataFrame(rows).set_index("day") if rows else pd.DataFrame(columns=["open", "high", "low", "close"])


class FakeMk:
    def __init__(self, **ixs):
        self.ixs = ixs

    def index(self, u):
        return self.ixs.get(u) or FakeIx([], empty(0))


def bdays(n, start=date(2025, 1, 6)):
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def empty(nd):
    return {k: np.full((nd, W), np.nan) for k in "ohlc"}


def put_bar(M, d, s, n, O, H, L, C):
    """One candle over minutes s..s+n-1 of day row d: open O at the first minute, high H / low L touched there, close
    C at the last minute (and the minutes between)."""
    n = min(n, W - s)
    M["o"][d, s], M["h"][d, s], M["l"][d, s], M["c"][d, s] = O, H, L, (C if n == 1 else O)
    if n > 1:
        for k in "ohlc":
            M[k][d, s + 1:s + n] = C
        M["h"][d, s] = max(H, C)
        M["l"][d, s] = min(L, C)


def lay(bars, tf, ndays=None):
    """bars: list of (O, H, L, C) laid out tf-minute candles from 09:15, as many days as needed."""
    per = -(-W // tf)
    nd = ndays or -(-len(bars) // per)
    M = empty(nd)
    for j, (O, H, L, C) in enumerate(bars):
        d, b = divmod(j, per)
        put_bar(M, d, b * tf, tf, O, H, L, C)
    return M


def path_bars(closes, start=100.0, wick=0.5):
    """Candles from a close path: open = previous close, high/low = body +- wick."""
    out, prev = [], start
    for c in closes:
        out.append((prev, max(prev, c) + wick, min(prev, c) - wick, c))
        prev = c
    return out


def sig_min_of(j, tf):
    per = -(-W // tf)
    b = j % per
    return OPEN_M + min((b + 1) * tf, W) - 1


def rows(df):
    return sorted((r.und, r.day, int(r.sig_min), int(r.side)) for r in df.itertuples())


def perturb_after(M, d, col, seed=7):
    """Change every bar after column col of day row d and every later day (a random walk added to o/h/l/c)."""
    rng = np.random.default_rng(seed)
    M2 = {k: v.copy() for k, v in M.items()}
    mask = np.zeros(M["c"].shape, bool)
    mask[d, col + 1:] = True
    mask[d + 1:, :] = True
    noise = np.cumsum(rng.normal(0, 3, M["c"].size)).reshape(M["c"].shape)
    for k in "oc":
        M2[k] = np.where(mask, M[k] + noise, M[k])
    M2["h"] = np.where(mask, np.fmax(M2["o"], M2["c"]) + 1.0, M["h"])
    M2["l"] = np.where(mask, np.fmin(M2["o"], M2["c"]) - 1.0, M["l"])
    return M2


def assert_no_lookahead(fn, und, M, days, params, exp=None):
    base = fn(FakeMk(**{und: FakeIx(days, M, exp)}), **params)
    assert len(base), "the guard needs a market that fires"
    for r in base.itertuples():
        d = days.index(r.day)
        col = int(r.sig_min) - OPEN_M
        M2 = perturb_after(M, d, col)
        got = fn(FakeMk(**{und: FakeIx(days, M2, exp)}), **params)
        same = got[(got.day == r.day) & (got.sig_min == r.sig_min)]
        assert len(same) == 1 and int(same.side.iloc[0]) == int(r.side), (r.day, r.sig_min)
        earlier = base[(base.day < r.day) | ((base.day == r.day) & (base.sig_min <= r.sig_min))]
        got_e = got[(got.day < r.day) | ((got.day == r.day) & (got.sig_min <= r.sig_min))]
        assert rows(earlier) == rows(got_e)


# ================================================================================================ H25: candles
def seplines_market(equal_open=True, gap=False):
    """5-min SENSEX day: 41 background candles, then a black candle (open 100) and a long white belt hold opening at
    the same 100 with no lower shadow (TA-Lib separating lines, bullish)."""
    bars = []
    for j in range(41):
        bars.append((100.0, 100.8, 99.2, 100.3) if j % 2 == 0 else (100.0, 100.8, 99.2, 99.5))
    bars.append((100.0, 100.2, 98.8, 99.0))                                   # i-1: black
    bars.append((100.0 if equal_open else 100.5, 102.3, 100.0 if equal_open else 100.5, 102.0))   # i: white
    for j in range(20):
        bars.append((101.0, 101.8, 100.2, 101.3) if j % 2 == 0 else (101.0, 101.8, 100.2, 100.5))
    M = lay(bars, 5)
    if gap:
        M = {k: v.copy() for k, v in M.items()}
        for k in "ohlc":
            M[k][0, 42 * 5 + 2] = np.nan                                       # a missing minute inside the bar
    return M, bdays(1)


def test_seplines_fires_fade():
    M, days = seplines_market()
    got = F.h25_sig(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), tf=5, name="CDLSEPARATINGLINES", orient="fade")
    assert rows(got) == [("SENSEX", days[0], sig_min_of(42, 5), -1)]
    assert got.book.iloc[0] == "h25_CDLSEPARATINGLINES_fade_SENSEX"
    # the ARM exit's index stop: 50 SENSEX points against the close at sig_min (a PE: above)
    assert got.idx_stop.iloc[0] == pytest.approx(102.0 + 50.0)
    fol = F.h25_sig(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), tf=5, name="CDLSEPARATINGLINES", orient="follow")
    assert rows(fol) == [("SENSEX", days[0], sig_min_of(42, 5), 1)]


def test_seplines_no_fire_when_opens_differ():
    M, days = seplines_market(equal_open=False)
    got = F.h25_sig(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), tf=5, name="CDLSEPARATINGLINES", orient="fade")
    assert len(got) == 0 and list(got.columns) == F.COLS


def test_seplines_missing_minute_and_expiry_day():
    M, days = seplines_market(gap=True)
    got = F.h25_sig(FakeMk(SENSEX=FakeIx(days, M, exp={days[0]})), unds=("SENSEX",), tf=5,
                    name="CDLSEPARATINGLINES", orient="fade")
    # still one row at the same minute: the expiry day is dropped later by the engine (Execution expiry='skip')
    assert rows(got) == [("SENSEX", days[0], sig_min_of(42, 5), -1)]


def test_seplines_lookahead():
    M, days = seplines_market()
    assert_no_lookahead(F.h25_sig, "SENSEX", M, days, dict(unds=("SENSEX",), tf=5, name="CDLSEPARATINGLINES",
                                                           orient="fade"))


def ladder_market(fire=True):
    """1-minute candles: 20 small white candles, 3 black with lower opens / closes, a black with an upper shadow, then
    a white candle opening above it and closing above its high (TA-Lib ladder bottom)."""
    bars = [(110.0, 110.5, 109.8, 110.2)] * 20
    bars += [(110.0, 110.1, 107.8, 108.0), (109.0, 109.1, 106.8, 107.0), (108.0, 108.1, 105.8, 106.0),
             (107.0, 108.0, 104.8, 105.0), (107.5, 109.2, 107.4, 109.0 if fire else 107.9)]
    bars += [(109.0, 109.5, 108.8, 109.2)] * 30
    return lay(bars, 1), bdays(1)


def test_ladder_bottom_fires_fade_only():
    M, days = ladder_market()
    got = F.h25_sig(FakeMk(NIFTY=FakeIx(days, M)), unds=("NIFTY", "BANKNIFTY"), tf=1, name="CDLLADDERBOTTOM",
                    orient="fade")
    assert rows(got) == [("NIFTY", days[0], OPEN_M + 24, -1)]
    assert got.idx_stop.iloc[0] == pytest.approx(109.0 + 15.0)


def test_ladder_bottom_no_fire():
    M, days = ladder_market(fire=False)
    got = F.h25_sig(FakeMk(NIFTY=FakeIx(days, M)), unds=("NIFTY",), tf=1, name="CDLLADDERBOTTOM", orient="fade")
    assert len(got) == 0


def test_ladder_bottom_lookahead():
    M, days = ladder_market()
    assert_no_lookahead(F.h25_sig, "NIFTY", M, days, dict(unds=("NIFTY",), tf=1, name="CDLLADDERBOTTOM",
                                                          orient="fade"))


def doji_market(doji=True):
    """30-minute candles over 2 days: 20 black candles stepping down, then a doji (direction = reversal of the last 5
    candles' fall = bull), then more black candles."""
    bars = [(200.0 - j, 200.2 - j, 198.8 - j, 199.0 - j) for j in range(20)]
    bars.append((180.0, 180.7, 179.3, 180.01 if doji else 180.5))
    bars += [(181.0 - j, 181.2 - j, 179.8 - j, 180.0 - j) for j in range(4)]
    return lay(bars, 30), bdays(2)


def test_doji_follow_fires_after_fall():
    M, days = doji_market()
    for name in ("CDLDOJI", "CDLLONGLEGGEDDOJI"):
        got = F.h25_sig(FakeMk(BANKNIFTY=FakeIx(days, M)), unds=("BANKNIFTY",), tf=30, name=name, orient="follow")
        assert rows(got) == [("BANKNIFTY", days[1], sig_min_of(20, 30), 1)], name


def test_doji_no_fire_on_a_real_body():
    M, days = doji_market(doji=False)
    got = F.h25_sig(FakeMk(BANKNIFTY=FakeIx(days, M)), unds=("BANKNIFTY",), tf=30, name="CDLDOJI", orient="follow")
    assert len(got) == 0


def test_doji_lookahead():
    M, days = doji_market()
    assert_no_lookahead(F.h25_sig, "BANKNIFTY", M, days, dict(unds=("BANKNIFTY",), tf=30, name="CDLDOJI",
                                                              orient="follow"))


# ================================================================================================ H25: MA crosses
def step_market(step=True):
    closes = [100.0] * 30 + ([101.0] * 30 if step else [100.0] * 30)
    bars = [(c, c + 0.5, c - 0.5, c) for c in closes]
    return lay(bars, 5), bdays(1)


@pytest.mark.parametrize("name", ["SMA_X2_4", "EMA_X2_4", "WMA_X2_4"])
def test_ma_cross_fires_once_on_the_step(name):
    M, days = step_market()
    got = F.h25_sig(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), tf=5, name=name, orient="follow")
    assert rows(got) == [("SENSEX", days[0], sig_min_of(30, 5), 1)]


def test_hma_cross_first_signal_on_the_step_and_fade():
    M, days = step_market()
    got = F.h25_sig(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), tf=5, name="HMA_X4_9", orient="fade")
    assert len(got) and int(got.sig_min.iloc[0]) == sig_min_of(30, 5) and int(got.side.iloc[0]) == -1


@pytest.mark.parametrize("name", ["SMA_X2_4", "EMA_X2_4", "HMA_X4_9"])
def test_ma_cross_no_fire_on_flat(name):
    M, days = step_market(step=False)
    got = F.h25_sig(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), tf=5, name=name, orient="follow")
    assert len(got) == 0


@pytest.mark.parametrize("name", ["SMA_X2_4", "EMA_X2_4", "HMA_X4_9"])
def test_ma_cross_lookahead(name):
    M, days = step_market()
    assert_no_lookahead(F.h25_sig, "SENSEX", M, days, dict(unds=("SENSEX",), tf=5, name=name, orient="fade"))


def test_ma_cross_after_1500_is_dropped():
    closes = [100.0] * 71 + [101.0] * 4             # the step's bar ends at 15:14 (> 15:00)
    M = lay([(c, c + 0.5, c - 0.5, c) for c in closes], 5)
    got = F.h25_sig(FakeMk(SENSEX=FakeIx(bdays(1), M)), unds=("SENSEX",), tf=5, name="SMA_X2_4", orient="follow")
    assert len(got) == 0


def test_unported_h25_signal_raises():
    M, days = step_market()
    with pytest.raises(ValueError):
        F.h25_sig(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), tf=5, name="CDLHAMMER", orient="fade")


# ================================================================================================ H37: swings
def fib_market(fire=True, daily=False):
    """Up-swing 100 -> 120 then a fall; FIB_K786 = first close below 120.5 - 0.786 x (120.5 - 99.5) = 103.994."""
    closes = [100.0] * 15 + [100.0 + i for i in range(1, 21)]
    closes += [120.0 - i for i in range(1, 21)] if fire else [120.0 - i for i in range(1, 16)] + [105.0] * 5
    bars = path_bars(closes)
    if daily:
        return lay(bars, W), bdays(len(bars))
    return lay(bars, 5), bdays(1), closes


def test_fib786_break_fires_pe():
    M, days, closes = fib_market()
    lev = 120.5 - 0.786 * (120.5 - 99.5)
    j = next(i for i in range(36, len(closes)) if closes[i] < lev <= closes[i - 1])
    got = F.h37_swing(FakeMk(NIFTY=FakeIx(days, M)), unds=("NIFTY",), tf=5, k=4, name="FIB_K786")
    assert rows(got) == [("NIFTY", days[0], sig_min_of(j, 5), -1)]
    assert got.idx_stop.isna().all()


def test_fib786_no_fire_above_level():
    M, days, _ = fib_market(fire=False)
    got = F.h37_swing(FakeMk(NIFTY=FakeIx(days, M)), unds=("NIFTY",), tf=5, k=4, name="FIB_K786")
    assert len(got) == 0


def test_fib786_lookahead():
    M, days, _ = fib_market()
    assert_no_lookahead(F.h37_swing, "NIFTY", M, days, dict(unds=("NIFTY",), tf=5, k=4, name="FIB_K786"))


def test_fib786_daily_enters_next_day_0916():
    M, days = fib_market(daily=True)
    got = F.h37_swing(FakeMk(NIFTY=FakeIx(days, M)), unds=("NIFTY",), tf="D", k=4, name="FIB_K786")
    assert rows(got) == [("NIFTY", days[52], OPEN_M, -1)]       # day 51's close breaks; bought 09:16 on day 52
    assert_no_lookahead(F.h37_swing, "NIFTY", M, days, dict(unds=("NIFTY",), tf="D", k=4, name="FIB_K786"))


def ew3_market(fire=True):
    """p0 99.5, p1 120.5, p2 109.5 (52% retrace) then a close above p1 -> wave 3 CE. No-fire: a 90% retrace."""
    if fire:
        closes = [100.0] * 15 + [100.0 + i for i in range(1, 21)] + [120.0 - i for i in range(1, 11)]
        closes += [110.0 + i for i in range(1, 16)]
    else:
        closes = [100.0] * 10 + [100.0 + i for i in range(1, 21)] + [120.0 - i for i in range(1, 19)]
        closes += [102.0 + i for i in range(1, 24)]
    return lay(path_bars(closes), 5), bdays(1), closes


def test_ew3_fires_ce():
    M, days, closes = ew3_market()
    j = next(i for i in range(46, len(closes)) if closes[i] > 120.5 >= closes[i - 1])
    got = F.h37_swing(FakeMk(MIDCPNIFTY=FakeIx(days, M)), unds=("MIDCPNIFTY",), tf=5, k=2, name="EW3")
    assert rows(got) == [("MIDCPNIFTY", days[0], sig_min_of(j, 5), 1)]


def test_ew3_no_fire_on_deep_retrace():
    M, days, _ = ew3_market(fire=False)
    got = F.h37_swing(FakeMk(MIDCPNIFTY=FakeIx(days, M)), unds=("MIDCPNIFTY",), tf=5, k=2, name="EW3")
    assert len(got) == 0


def test_ew3_lookahead():
    M, days, _ = ew3_market()
    assert_no_lookahead(F.h37_swing, "MIDCPNIFTY", M, days, dict(unds=("MIDCPNIFTY",), tf=5, k=2, name="EW3"))


# ================================================================================================ H37: Gann daily cycles
def gtc_market(n_fall=60):
    closes = [100.0] * 20 + [100.0 + 2 * i for i in range(1, 21)] + [140.0 - i for i in range(1, n_fall + 1)]
    bars = path_bars(closes)
    return lay(bars, W), bdays(len(bars))


def test_gtc_d_fires_reversal_after_high_pivot():
    M, days = gtc_market()
    got = F.h37_gtc_d(FakeMk(NIFTY=FakeIx(days, M)), unds=("NIFTY",), k=2)
    assert len(got) and (got.sig_min == OPEN_M).all()
    ords = np.array([d.toordinal() for d in days])
    peak = 39                                                  # the high pivot (close 140, high 140.5)
    for T in (30, 45, 60):
        t = int(np.searchsorted(ords, ords[peak] + T))
        assert ("NIFTY", days[t], OPEN_M, 1) in rows(got), T  # below the high pivot -> CE, bought 09:16 on day t
    # every entry is the first trading day on/after some pivot + a Gann count of calendar days
    starts = {0, peak}
    ok = {days[int(np.searchsorted(ords, ords[p] + T))] for p in starts for T in F.S37.GTC_D
          if int(np.searchsorted(ords, ords[p] + T)) < len(days)}
    assert set(got.day) <= ok


def test_gtc_d_no_fire_without_a_pivot():
    closes = [100.0] * 40
    M = lay(path_bars(closes), W)
    got = F.h37_gtc_d(FakeMk(NIFTY=FakeIx(bdays(40), M)), unds=("NIFTY",), k=2)
    assert len(got) == 0


def test_gtc_d_lookahead():
    M, days = gtc_market()
    assert_no_lookahead(F.h37_gtc_d, "NIFTY", M, days, dict(unds=("NIFTY",), k=2))


# ================================================================================================ H37: Point & Figure
def pf_market(fire=True, gap=False):
    """Day 0 closes at 1000 (box 1.0 for day 1 at 0.1 %). Day 1: up 5 boxes (X top 5), down to +1 (3-box reversal),
    up to +6: a double-top buy at the minute whose close is 1006."""
    M = empty(2)
    put_bar(M, 0, 0, W, 1000.0, 1000.5, 999.5, 1000.0)
    path = [1000.0 + i for i in range(1, 6)] + [1004.0, 1003.0, 1002.0, 1001.0]
    path += [1002.0, 1003.0, 1004.0, 1005.0] + ([1006.0] if fire else [1005.0])
    path += [path[-1]] * 40
    for m, p in enumerate([1000.0] + path):
        M["o"][1, m], M["h"][1, m], M["l"][1, m], M["c"][1, m] = p, p + 0.1, p - 0.1, p
    if gap:
        for k in "ohlc":
            M[k][1, 3] = np.nan
    return M, bdays(2), 1 + len(path[:14])


def test_pf_double_top_fires_ce():
    M, days, m = pf_market()
    got = F.h37_pf(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), box="b0.1pct", name="PF_DT")
    assert rows(got) == [("SENSEX", days[1], OPEN_M + m - 1, 1)]


def test_pf_no_fire_without_new_top():
    M, days, _ = pf_market(fire=False)
    got = F.h37_pf(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), box="b0.1pct", name="PF_DT")
    assert len(got) == 0


def test_pf_missing_minute_and_atr_box_runs():
    M, days, m = pf_market(gap=True)
    got = F.h37_pf(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), box="b0.1pct", name="PF_DT")
    assert rows(got) == [("SENSEX", days[1], OPEN_M + m - 1, 1)]
    got = F.h37_pf(FakeMk(SENSEX=FakeIx(days, M)), unds=("SENSEX",), box="b0.5atr", name="PF_DT")
    assert list(got.columns) == F.COLS or len(got) == 0


def test_pf_lookahead():
    M, days, _ = pf_market()
    assert_no_lookahead(F.h37_pf, "SENSEX", M, days, dict(unds=("SENSEX",), box="b0.1pct", name="PF_DT"))


# ================================================================================================ H37: Market Profile
def mp_market(outside=True):
    """Day 0 trades 1000..1010 all day (value area inside). Day 1 opens at 1050 (or 1005) and stays there."""
    M = empty(2)
    for m in range(W):
        p = 1000.0 + (m % 11)
        M["o"][0, m], M["h"][0, m], M["l"][0, m], M["c"][0, m] = p, p + 0.5, p - 0.5, p
    base = 1050.0 if outside else 1005.0
    for m in range(W):
        p = base + 1.0 + 0.01 * m
        M["o"][1, m], M["h"][1, m], M["l"][1, m], M["c"][1, m] = (base if m == 0 else p), p + 0.2, p - 0.2, p
    return M, bdays(2)


def test_mp_oov_fires_on_first_bar():
    M, days = mp_market()
    got = F.h37_mp(FakeMk(NIFTY=FakeIx(days, M)), unds=("NIFTY",), tf=5, name="MP_OOV")
    assert rows(got) == [("NIFTY", days[1], OPEN_M + 4, 1)]
    got15 = F.h37_mp(FakeMk(NIFTY=FakeIx(days, M)), unds=("NIFTY",), tf=15, name="MP_OOV")
    assert rows(got15) == [("NIFTY", days[1], OPEN_M + 14, 1)]


def test_mp_oov_no_fire_inside_value():
    M, days = mp_market(outside=False)
    got = F.h37_mp(FakeMk(NIFTY=FakeIx(days, M)), unds=("NIFTY",), tf=5, name="MP_OOV")
    assert len(got) == 0


def test_mp_oov_lookahead():
    M, days = mp_market()
    assert_no_lookahead(F.h37_mp, "NIFTY", M, days, dict(unds=("NIFTY",), tf=5, name="MP_OOV"))


# ================================================================================================ edge cases, specs
@pytest.mark.parametrize("fn,params", [
    (F.h25_sig, dict(unds=("SENSEX",), tf=5, name="CDLSEPARATINGLINES", orient="fade")),
    (F.h25_sig, dict(unds=("SENSEX",), tf=60, name="HMA_X13_34", orient="fade")),
    (F.h37_swing, dict(unds=("SENSEX",), tf=5, k=4, name="FIB_K786")),
    (F.h37_swing, dict(unds=("SENSEX",), tf="D", k=2, name="EW3")),
    (F.h37_gtc_d, dict(unds=("SENSEX",), k=2)),
    (F.h37_pf, dict(unds=("SENSEX",), box="b0.5atr", name="PF_DT")),
    (F.h37_mp, dict(unds=("SENSEX",), tf=5, name="MP_OOV")),
])
def test_empty_market(fn, params):
    got = fn(FakeMk(), **params)
    assert len(got) == 0 and list(got.columns) == F.COLS
    one = FakeIx(bdays(1), empty(1))                       # a day with no bars at all
    got = fn(FakeMk(SENSEX=one), **params)
    assert len(got) == 0


def test_specs():
    keys = [s["key"] for s in F.SPECS]
    assert keys == ["H25-2", "H25-3", "H25-5", "H25-6", "H25-10", "H25-11", "H37-2@NIFTY", "H37-2@MIDCPNIFTY",
                    "H37-3", "H37-6", "H37-9", "H37-10"]
    for s in F.SPECS:
        o = s["orig"]
        assert s["source"] == "index"
        assert len(s["grid"]) <= 23
        assert o["money"] == 1 and o["series"] == "near" and o["expiry"] == "skip"
        assert o["pos"] == dict(one_at_a_time=True, max_per_day=5) and o["window"] == (OPEN_M, 15 * 60)
        assert set(o["unds"]) == set(o["params"]["unds"])
        for g in s["grid"]:
            assert set(g) <= {"params", "exits"}
            assert g.get("params", {}).get("unds") is None
        sq = [o["exits"]] + [g["exits"] for g in s["grid"] if "exits" in g]
        assert all(e["sq_off"] == 15 * 60 + 10 for e in sq)
    h25 = {s["key"]: s["orig"] for s in F.SPECS}
    assert h25["H25-2"]["params"] == dict(unds=("SENSEX",), tf=5, name="CDLSEPARATINGLINES", orient="fade")
    assert h25["H25-2"]["exits"] == dict(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=15 * 60 + 10)
    assert h25["H37-2@NIFTY"]["exits"]["ladder_ref_pts"] == 40
    assert h25["H37-2@MIDCPNIFTY"]["exits"]["stop_pts"] == 20 and h25["H37-2@MIDCPNIFTY"]["exits"]["tgt_pts"] == 20
    assert h25["H37-3"]["exits"]["stop_pts"] == 20 and h25["H37-3"]["exits"]["tgt_pts"] == 15


def test_specs_build_rules():
    r52rules = pytest.importorskip("r52rules")
    for s in F.SPECS:
        rs = r52rules.spec_rules(s)
        assert rs[0].vid == "orig" and len(rs) == len(s["grid"]) + 1
        for r in rs:
            r.exits.label()
            assert r.rule.money == 1 and r.expiry == "skip"


def test_exit_menus():
    assert len(F.H25_EXITS) == 18 and len(set(F.H25_EXITS)) == 18
    assert len(F.H37_EXITS) == 17 and len(set(F.H37_EXITS)) == 17
    assert F.h25_exit("P25_10") == dict(sq_off=15 * 60 + 10, sig_levels=False, stop_pts=10, tgt_pts=25)
    assert F.h25_exit("T30") == dict(sq_off=15 * 60 + 10, sig_levels=False, time_stop=30, time_gain=None)
    assert F.h37_exit("ARM") == dict(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=15 * 60 + 10,
                                     sig_levels=False)


# ================================================================================================ TA-Lib parity
def _ohlc(n, seed):
    rng = np.random.default_rng(seed)
    c = 1000 + np.cumsum(rng.normal(0, 2, n))
    o = np.r_[c[0], c[:-1]] + rng.normal(0, 0.5, n)
    # a few exact dojis / equal opens so the rare patterns get exercised
    k = rng.choice(n, n // 20, replace=False)
    c[k] = o[k]
    h = np.fmax(o, c) + np.abs(rng.normal(0, 1.5, n))
    lo = np.fmin(o, c) - np.abs(rng.normal(0, 1.5, n))
    return o, h, lo, c


@pytest.mark.parametrize("n", [0, 5, 12, 15, 300, 20000])
def test_talib_parity(n):
    talib = pytest.importorskip("talib")
    o, h, lo, c = _ohlc(n, n + 1)
    for nm, f in F.CDL.items():
        assert np.array_equal(getattr(talib, nm)(o, h, lo, c), f(o, h, lo, c)), nm
    for T, f in F.MA_FN.items():
        for p in (2, 4, 6, 13, 17, 34, 50, 200):
            assert np.array_equal(getattr(talib, T)(c, p), f(c, p), equal_nan=True), (T, p)


def test_ladder_bottom_synthetic_matches_talib():
    talib = pytest.importorskip("talib")
    for M in (ladder_market()[0], seplines_market()[0], doji_market()[0]):
        for tf in (1, 5, 30):
            B = F.S37.bars(M, tf)
            o, h, lo, c = (np.asarray(B[k], float) for k in "ohlc")
            for nm, f in F.CDL.items():
                assert np.array_equal(getattr(talib, nm)(o, h, lo, c), f(o, h, lo, c)), (nm, tf)
