"""Tests for R138 (PREREG.md): the M and its fake breakout on hand-built 15-minute candles, every pattern variant, the
tolerance per index, the measures after the event, the trade's stop, and the look-ahead guard."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r138 as M  # noqa: E402
from test_r131 import grid, random_grid, truncate  # noqa: E402

PRIM = M.CONFIGS[M.PRIMARY]
PREV = [(19900, 19905, 19895, 19900)] * 25          # a quiet previous session: ATR known, no pattern
M_BARS = [(20000, 20010, 19995, 20005), (20005, 20050, 20000, 20045),
          (20045, 20100, 20040, 20090),              # 2: A, high 20100
          (20090, 20095, 20060, 20065), (20065, 20070, 20040, 20045),   # 4: the neckline, 20040
          (20045, 20080, 20042, 20075),
          (20075, 20103, 20070, 20095),              # 6: B, high 20103 (3 points from A): R = 20103
          (20095, 20098, 20080, 20085), (20085, 20090, 20075, 20088),
          (20088, 20110, 20085, 20095)]              # 9: C pokes to 20110, closes 20095 < R: the fake breakout
AFTER = [(20000, 20005, 19995, 20000)] * 14


def tops(bars, cfg=PRIM, und="NIFTY", prev=PREV):
    g = grid([prev, bars + AFTER], tf=15)
    return M.detect(M.R.sides_of(g)[-1].chart(15), und, cfg)


def test_primary_m_with_fake_breakout():
    ev = tops(M_BARS)
    assert [(e.a - 25, e.b - 25, e.c - 25, e.e - 25) for e in ev] == [(2, 6, 9, 9)]
    e = ev[0]
    assert (-e.level, -e.neck, -e.fake) == (20103, 20040, 20110)     # the M's level, neckline and fake high
    assert e.tol == 5.0


def test_closing_above_is_not_a_one_candle_fake():
    bars = list(M_BARS)
    bars[9] = (20088, 20110, 20085, 20105)          # closes above R
    assert tops(bars) == []
    bars += [(20105, 20106, 20085, 20090)]          # ...then the next candle closes back below: the two-candle fail
    ev = tops(bars, M.CONFIGS["max-tick-f2-same"])
    assert [(e.c - 25, e.e - 25, -e.fake) for e in ev] == [(9, 10, 20110)]
    bars[10] = (20105, 20120, 20104, 20115)         # stays above: a real breakout, no event
    assert tops(bars, M.CONFIGS["max-tick-f2-same"]) == []


def test_poke_by_the_tolerance():
    bars = list(M_BARS)
    bars[9] = (20088, 20106, 20085, 20095)          # 3 points above R: a tick poke, not a tolerance poke
    assert len(tops(bars)) == 1
    assert tops(bars, M.CONFIGS["max-tol-f1-same"]) == []
    assert len(tops(M_BARS, M.CONFIGS["max-tol-f1-same"])) == 1     # 7 points >= 5


def test_average_level():
    bars = list(M_BARS)
    bars[9] = (20088, 20110, 20085, 20102)          # below the max (20103), above the average (20101.5)
    assert len(tops(bars)) == 1
    assert tops(bars, M.CONFIGS["avg-tick-f1-same"]) == []


def test_span_previous_session():
    prev = PREV[:21] + M_BARS[:4]                    # A (20100) at the end of yesterday
    today = M_BARS[4:]
    g = grid([PREV, prev, today + AFTER], tf=15)
    ch = M.R.sides_of(g)[-1].chart(15)
    assert M.detect(ch, "NIFTY", PRIM) == []
    ev = M.detect(ch, "NIFTY", M.CONFIGS["max-tick-f1-span"])
    assert [(int(ch.day[e.a]), int(ch.day[e.e]), -e.neck) for e in ev] == [(1, 2, 20040)]


def test_needs_a_pullback_and_the_gap():
    bars = list(M_BARS)
    bars[3:6] = [(20090, 20095, 20093, 20094), (20094, 20096, 20094, 20095), (20095, 20097, 20094, 20096)]
    assert tops(bars) == []                         # a 9-point dip is not an M
    bars = M_BARS[:3] + M_BARS[6:]                  # B right after A
    assert tops(bars) == []


def test_tolerance_by_index():
    assert M.tolerance("NIFTY", 25000) == 5.0
    assert M.tolerance("BANKNIFTY", 50000) == pytest.approx(11.11, abs=0.01)
    bars = list(M_BARS)
    bars[6] = (20075, 20104.5, 20070, 20095)        # 4.5 points from A
    assert len(tops(bars, und="NIFTY")) == 1       # inside NIFTY's 5 points
    assert tops(bars, und="BANKNIFTY") == []       # outside 0.022% of 20,100 = 4.47


def test_mirror_finds_the_w():
    flip = [(-o, -l, -h, -c) for o, h, l, c in M_BARS]
    g = grid([[(-o, -l, -h, -c) for o, h, l, c in PREV], flip + [(-o, -l, -h, -c) for o, h, l, c in AFTER]], tf=15)
    ev = M.detect(M.R.sides_of(g)[1].chart(15), "NIFTY", PRIM)
    assert [(e.b - 25, e.e - 25) for e in ev] == [(6, 9)]


def test_measures_after_the_event():
    g = grid([PREV, M_BARS + AFTER], tf=15)
    S = M.R.sides_of(g)[-1]
    e = M.detect(S.chart(15), "NIFTY", PRIM)[0]
    row = int(S.chart(15).B.end[e.e])
    m = M.measure(S, 1, row, e.neck, e.fake)
    assert m["m15"] == pytest.approx(95.0)          # 20095 -> 20000: 95 points down, in the M's favour
    assert m["race"] == "neck"
    assert M.measure(S, 1, M.R.EXIT_LAST, e.neck, e.fake) is None


def test_plain_double_top():
    g = grid([PREV, M_BARS + AFTER], tf=15)
    ev = M.plain_doubles(M.R.sides_of(g)[-1].chart(15), "NIFTY")
    assert (6 + 25, 7 + 25) in [(e.b, e.e) for e in ev]


def test_trade_candidate_stop_and_target():
    g = grid([PREV, M_BARS + AFTER], tf=15)
    sides = M.R.sides_of(g)
    (sd, c), = M.trade_cands(sides, "NIFTY", M.VARIANTS["T1"])
    assert sd == -1 and c.r0 == 10 * 15
    assert -c.stop == pytest.approx(20110 * 1.0002)
    assert -c.neck == 20040


def test_eight_variants_and_sixteen_configs():
    assert list(M.VARIANTS) == [f"T{i}" for i in range(1, 9)]
    assert len(M.CONFIGS) == 16


def test_lookahead_guard():
    rng = np.random.default_rng(138)
    g = random_grid(rng, 20)
    S = M.R.sides_of(g)
    found = 0
    for name, cfg in M.CONFIGS.items():
        for sd in (-1, 1):
            ch = S[sd].chart(15)
            full = M.detect(ch, "NIFTY", cfg)
            found += len(full)
            for i in rng.choice(len(full), size=min(4, len(full)), replace=False) if full else []:
                e = full[i]
                d, r0 = int(ch.day[e.e]), int(ch.B.end[e.e]) + 1
                cht = M.R.sides_of(truncate(g, d, r0))[sd].chart(15)
                again = [(int(cht.day[x.e]), int(cht.B.end[x.e]), round(x.level, 6), round(x.neck, 6),
                          round(x.fake, 6)) for x in M.detect(cht, "NIFTY", cfg)]
                assert (d, r0 - 1, round(e.level, 6), round(e.neck, 6), round(e.fake, 6)) in again
    assert found > 0
