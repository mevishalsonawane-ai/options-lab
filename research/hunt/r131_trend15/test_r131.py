"""R131 tests: wick breaks of the 15-minute structure (point in time), stop-and-reverse trading, the entry window, the 1R
target, the R123 network lookup (decisions before the entry only) and the look-ahead guard.

    python -m pytest research/hunt/r131_trend15 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r131 as T  # noqa: E402

N = T.N


def _grid(bars15, nd=1):
    """15-minute bars (h, l) per day -> minutes: each minute carries its bar's high and low; opens / closes mid."""
    g = SimpleNamespace(days=[date(2025, 6, 10 + i) for i in range(nd)])
    h, l = np.full((nd, N), np.nan), np.full((nd, N), np.nan)
    for d in range(nd):
        for b, (bh, bl) in enumerate(bars15[d]):
            h[d, 15 * b:15 * b + 15], l[d, 15 * b:15 * b + 15] = bh, bl
    g.h, g.l = h, l
    g.o = g.c = (h + l) / 2
    return g


# day 1: a swing high at bar 2 (110), known after bar 4 ends (minute 75); bar 6 breaks it
DAY = [(100, 95), (105, 99), (110, 104), (108, 101), (106, 100), (107, 102), (112, 105)] + [(111, 106)] * 18


def test_break_waits_for_the_swing_to_be_known():
    g = _grid([DAY])
    up = [b for b in T.breaks(g) if b.side > 0]
    assert up and (up[0].d, up[0].r) == (0, 90)              # bar 6 starts at minute 90; 112 >= 110 + 1 tick
    day2 = list(DAY)
    day2[4] = (111, 100)                                     # bar 4 reaches 111 BEFORE the swing at bar 2 is known
    up2 = [b for b in T.breaks(_grid([day2])) if b.side > 0]
    assert all(b.r != 60 for b in up2)                       # no break at bar 4 (110 not yet a known swing)


def test_breaks_look_ahead_guard():
    rng = np.random.default_rng(3)
    c = 1000 + np.cumsum(rng.normal(0, 1, 2 * N))
    g = SimpleNamespace(days=[date(2025, 6, 10), date(2025, 6, 11)])
    g.h, g.l = (c + rng.random(2 * N)).reshape(2, N), (c - rng.random(2 * N)).reshape(2, N)
    g.o = g.c = c.reshape(2, N)
    full = [(b.d, b.r, b.side) for b in T.breaks(g)]
    for cut in (100, 250):
        g2 = SimpleNamespace(days=g.days, o=g.o.copy(), c=g.c.copy(), h=g.h.copy(), l=g.l.copy())
        for a in (g2.o, g2.h, g2.l, g2.c):
            a[1, cut:] = np.nan
        part = [(b.d, b.r, b.side) for b in T.breaks(g2)]
        assert part == [x for x in full if (x[0], x[1]) < (1, cut)]


def _flat(nd=1, px=100.0):
    g = SimpleNamespace(days=[date(2025, 6, 10 + i) for i in range(nd)])
    g.h, g.l = np.full((nd, N), px + 1), np.full((nd, N), px - 1)
    g.o, g.c = np.full((nd, N), px), np.full((nd, N), px)
    return g


def test_stop_and_reverse_window_and_square_off():
    g = _flat()
    brks = [T.Break(0, 10, 1, 95.0),                         # before 09:30: no entry
            T.Break(0, 40, 1, 95.0),                         # buy at 41
            T.Break(0, 60, 1, 95.0),                         # same direction: nothing
            T.Break(0, 100, -1, 105.0),                      # exit the call at 101, buy the put at 101
            T.Break(0, 320, 1, 95.0)]                        # exit the put at 321; 14:35 is past the window: flat
    tr = T.trade_breaks(g, brks, T.VERSIONS["T1"], set(), lambda d: True)
    assert [(t["side"], t["entry"], t["exit"], t["why"]) for t in tr] == [(1, 41, 101, "flip"), (-1, 101, 321, "flip")]
    tr2 = T.trade_breaks(g, brks[:2], T.VERSIONS["T1"], set(), lambda d: True)
    assert tr2[0]["exit"] == T.K.K3.EXIT_LAST and tr2[0]["why"] == "time"
    assert T.trade_breaks(g, brks, T.VERSIONS["T1"], {0}, lambda d: True) == []      # blocked day


def test_1r_target():
    g = _flat()
    g.h[0, 80] = 106.0                                       # entry ref 100, opposite swing 95 -> R 5, target 105
    tr = T.trade_breaks(g, [T.Break(0, 40, 1, 95.0)], T.VERSIONS["T4"], set(), lambda d: True)
    assert (tr[0]["exit"], tr[0]["why"], tr[0]["pts"], tr[0]["R"]) == (80, "target", 5.0, 1.0)


def test_60_minute_filter():
    g = _flat()
    h60 = np.full((1, N), -1, dtype=np.int8)
    assert T.trade_breaks(g, [T.Break(0, 40, 1, 95.0)], T.VERSIONS["T2"], set(), lambda d: True, h60=h60) == []
    h60[:] = 1
    assert len(T.trade_breaks(g, [T.Break(0, 40, 1, 95.0)], T.VERSIONS["T2"], set(), lambda d: True, h60=h60)) == 1


def test_network_lookup_uses_only_earlier_decisions():
    nn = T.NN.__new__(T.NN)
    day = date(2025, 6, 10)
    nn.rows = [4, 9, 14, 19]
    nn.by = {(day, 4): (0.4, -10.0, 50.0), (day, 9): (0.7, 20.0, -5.0), (day, 14): (0.2, -1.0, 1.0)}
    assert nn.at(day, 10) == (0.7, 20.0, -5.0)               # row 9 acts at 10: usable at entry 10
    assert nn.at(day, 9) == (0.4, -10.0, 50.0)               # row 9 is not usable for an entry AT 9
    assert nn.ok("dir", day, 10, 1) and not nn.ok("dir", day, 10, -1)
    assert nn.ok("ev", day, 9, -1) and not nn.ok("ev", day, 9, 1)
    assert not nn.ok("dir", day, 3, 1)                       # no decision yet: no trade
