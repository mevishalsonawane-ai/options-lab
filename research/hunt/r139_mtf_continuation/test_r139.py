"""Tests for R139 (PREREG.md): each layer of the stack on hand-built 5-minute candles (the 1-hour level, the
15-minute break, the 5-minute FVG pullback, the 3-minute entry and its stop), a cancelled setup, the 4-hour trend, the
bearish mirror and the look-ahead guard."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r139 as X  # noqa: E402
from test_r131 import grid, random_grid, truncate  # noqa: E402

FLAT = (20000, 20005, 19995, 20000)
NO_TREND = dict(X.VARIANTS["C4"])


def day(over: dict) -> list:
    b = [FLAT] * 75
    for i, v in over.items():
        b[i] = v
    return b


SETUP = {15: (20000, 20100, 19995, 20010),          # hour 2 (10:15-11:14) high 20100: the 1-hour swing high
         51: (20000, 20060, 19998, 20055), 52: (20055, 20090, 20050, 20085),
         53: (20085, 20120, 20080, 20115),          # the 15-min candle 13:30-13:44 closes 20115 > 20100; FVG 20060-20080
         54: (20115, 20118, 20090, 20095),
         55: (20095, 20097, 20075, 20085),          # the pullback into the gap (low 20075 <= 20080)
         56: (20085, 20087, 20078, 20082),
         57: (20082, 20110, 20081, 20105)}          # the 3-minute candle 13:40-13:42 closes above the one before


def mirror(bars):
    return [(-o, -l, -h, -c) for o, h, l, c in bars]


def test_the_one_hour_level_and_the_15_minute_break():
    g = grid([day({}), day({}), day(SETUP)], tf=5)
    br = X.breaks(X.R.sides_of(g)[1], None, None)
    assert [(d, s, e, lv) for d, s, e, lv, _ in br] == [(2, 255, 269, 20100.0)]


def test_full_stack_entry_and_stop():
    g = grid([day({}), day({}), day(SETUP)], tf=5)
    sides = X.R.sides_of(g)
    funnel = dict(breaks=0, zone=0, pullback=0, entry=0)
    c = X.candidates(sides, NO_TREND, {1: None, -1: None}, funnel)
    assert [(sd, x.d, x.r0) for sd, x in c] == [(1, 2, 288)]
    assert c[0][1].stop == pytest.approx(X.R.stop_below(20075))
    assert funnel == dict(breaks=1, zone=1, pullback=1, entry=1)


def test_the_break_entry_without_the_small_timeframes():
    g = grid([day({}), day({}), day(SETUP)], tf=5)
    v = dict(NO_TREND, stack=False)
    (sd, c), = X.candidates(X.R.sides_of(g), v, {1: None, -1: None})
    assert (c.d, c.r0) == (2, 270) and c.stop == pytest.approx(X.R.stop_below(19998))


def test_a_close_through_the_zone_cancels():
    s = dict(SETUP)
    s[55] = (20095, 20097, 20040, 20050)        # closes below the gap's bottom (20060)
    g = grid([day({}), day({}), day(s)], tf=5)
    funnel = dict(breaks=0, zone=0, pullback=0, entry=0)
    assert X.candidates(X.R.sides_of(g), NO_TREND, {1: None, -1: None}, funnel) == []
    assert funnel == dict(breaks=1, zone=1, pullback=0, entry=0)


def test_no_pullback_no_trade():
    s = {k: v for k, v in SETUP.items() if k < 54}
    s.update({i: (20115, 20125, 20110, 20120) for i in range(54, 75)})
    g = grid([day({}), day({}), day(s)], tf=5)
    assert X.candidates(X.R.sides_of(g), NO_TREND, {1: None, -1: None}) == []


def test_bearish_mirror_buys_the_put():
    g = grid([mirror(day({})), mirror(day({})), mirror(day(SETUP))], tf=5)
    c = X.candidates(X.R.sides_of(g), NO_TREND, {1: None, -1: None})
    assert [(sd, x.d, x.r0) for sd, x in c] == [(-1, 2, 288)]
    assert c[0][1].stop == pytest.approx(X.R.stop_below(20075))     # the put side sees the mirrored day as the bullish one


def test_four_hour_trend_and_when_it_is_known():
    up = [[(p, p + 5, p - 5, p + 2)] * 75 for p in range(20000, 20000 + 30 * 20, 20)]
    S = X.R.sides_of(grid(up, tf=5))
    T = X.Trend4H(S[1])
    assert T.up(29, X.H4_SPLIT, "ema") and not X.Trend4H(S[-1]).up(29, X.H4_SPLIT, "ema")
    assert X.Trend4H.last_done(5, X.H4_SPLIT - 1) == 9 and X.Trend4H.last_done(5, X.H4_SPLIT) == 10
    assert not T.up(0, 0, "ema")                # nothing completed yet


def test_four_hour_swing_structure():
    T = X.Trend4H.__new__(X.Trend4H)
    T.h = np.array([10, 12, 11, 10, 14, 13, 12, 16, 15, 14], float)
    T.l = np.array([5, 7, 6, 5, 8, 7, 6, 9, 8, 7], float)
    T.c = T.h
    sw = T._swing()
    assert sw[-1] and not sw[3]


def test_seven_variants():
    assert list(X.VARIANTS) == [f"C{i}" for i in range(1, 8)]


def test_lookahead_guard():
    rng = np.random.default_rng(139)
    g = random_grid(rng, 12)
    S = X.R.sides_of(g)
    tr = {sd: X.Trend4H(S[sd]) for sd in (1, -1)}
    found = 0
    for v in ("C1", "C4", "C5", "C7"):
        full = X.candidates(S, X.VARIANTS[v], tr)
        found += len(full)
        for i in rng.choice(len(full), size=min(5, len(full)), replace=False) if full else []:
            sd, c = full[i]
            St = X.R.sides_of(truncate(g, c.d, c.r0))
            trt = {s2: X.Trend4H(St[s2]) for s2 in (1, -1)}
            again = [(s2, x.d, x.r0, round(x.stop, 6)) for s2, x in X.candidates(St, X.VARIANTS[v], trt)]
            assert (sd, c.d, c.r0, round(c.stop, 6)) in again
    assert found > 0
