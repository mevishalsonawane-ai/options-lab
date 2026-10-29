"""R132 tests: liquidity levels, the point-in-time sweep (breach, running low, reclaim), sudden / volume / external
filters, BOS entry and the reclaim entry, the opposite-liquidity target, the one-position merge, the look-ahead guard.

    python -m pytest research/hunt/r132_liquidity_playbook -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r132 as P  # noqa: E402

N = P.N

# Worked out by hand (NIFTY, round step 100):
#   day 0: PDH 1030 (bar 10), PDL 990 (bar 20).  day 1 (same ISO week, so no previous-week level):
#   opening range bars 0-2: ORH 1005, ORL 1000; swing high 1008 at bar 6 (known from bar 10);
#   bar 12 (10:15) breaches 1000 (ORL and the round 1000) to 995: depth 0.2 needed; reclaim at minute 61 (high 1002);
#   sudden: range 7 vs a median prior range of 3-4; BOS: bar 14's high 1010 >= 1008.05 at minute 70 -> buy at 71
#   (ref 1009); stop 995 - 0.02% = 994.801; R = 14.199; the nearest fresh level >= 1023.2 is PDH 1030 (round 1100 is
#   farther); bar 20's high 1031 reaches it at minute 100.
DAY0 = [(1000, 1002, 998, 1000)] * 75
DAY0[10] = (1000, 1030, 999, 1001)
DAY0[20] = (1000, 1001, 990, 1000)
DAY1 = [(1002, 1005, 1000, 1003)] * 3 + [(1003, 1004, 1001, 1002)] * 3 + [(1002, 1008, 1002, 1004)] + \
       [(1003, 1004, 1001, 1002)] * 5 + [(1001, 1002, 995, 1001)] + [(1001, 1004, 1000, 1003)] + \
       [(1006, 1010, 1005, 1009)] + [(1009, 1012, 1007, 1011)] * 5 + [(1011, 1031, 1010, 1030)] + \
       [(1030, 1031, 1028, 1029)] * 54


def minutes(days_bars, mirror_at=None):
    nd = len(days_bars)
    o, h, l, c = (np.full((nd, N), np.nan) for _ in range(4))
    for d, bars in enumerate(days_bars):
        for b, (bo, bh, bl, bc) in enumerate(bars):
            rows = slice(5 * b, 5 * b + 5)
            h[d, rows], l[d, rows], c[d, rows], o[d, rows] = bh, bl, bc, bc
            o[d, 5 * b] = bo
    if mirror_at is not None:
        o, h, l, c = mirror_at - o, mirror_at - l, mirror_at - h, mirror_at - c
    return SimpleNamespace(days=[date(2025, 6, 9), date(2025, 6, 10)][:nd], o=o, h=h, l=l, c=c)


def run(v, g=None, vol=None, h60=None, blocked=frozenset()):
    g = g or minutes([DAY0, DAY1])
    sig, _ = P.signals(g, "NIFTY", vol, h60, set(blocked), lambda d: True, versions={v: P.VERSIONS[v]})
    return sig[v]


def test_round_helpers():
    assert P.round_below(1001.0, 100) == 1000.0 and P.round_below(1000.0, 100) == 900.0
    assert P.round_above(1023.2, 100) == 1100.0 and P.round_below(1001.0, 100, 50) == 950.0


def test_p1_hand_built_buy():
    tr = [t for t in run("P1") if t["side"] > 0]
    assert len(tr) == 1
    t = tr[0]
    assert (t["entry"], t["exit"], t["why"]) == (71, 100, "target")
    assert t["ref"] == 1009.0
    assert t["stop_pts"] == pytest.approx(1009 - (995 - 0.0002 * 995))
    assert t["tgt_pts"] == pytest.approx(21.0)
    assert t["kinds"] == "orl"          # 1000 was only touched at the open, never traded through: not a fresh round


def test_p7_enters_on_the_reclaim():
    g = minutes([DAY0, DAY1])
    S = P.build_sides(g, 100.0, None)[1]
    sw, runs, H_at, _ = P.find_sweeps(S)
    c = [x for x in P.setups(S, sw, H_at, P.VERSIONS["P7"], None, 1) if x["d"] == 1]
    assert c[0]["entry"] == 62 and c[0]["ref"] == 1001.0       # the minute after the reclaim minute 61
    # merged, it is blocked: bar 6 swept the opening-range HIGH (1008 > 1005, back below), so P7 sold first
    tr = [t for t in run("P7") if t["d"] == 1]
    assert tr[0]["side"] < 0 and tr[0]["entry"] == 32 and tr[0]["exit"] == 70    # the sell blocks the 62 buy
    assert all(t["entry"] != 62 for t in tr)


def test_filters_external_open_and_volume():
    assert [t for t in run("P6") if t["side"] > 0] == []        # ORL / round only: not external
    assert [t for t in run("P4") if t["side"] > 0] != []        # the sweep bar starts 10:15
    vol = {date(2025, 6, 10): np.full(N, 100.0)}
    assert [t for t in run("P3", vol=vol) if t["side"] > 0] == []      # no volume spike
    vol[date(2025, 6, 10)][60:62] = 400.0                       # 4x in the sweep minutes up to the reclaim
    assert [t for t in run("P3", vol=vol) if t["side"] > 0] != []


def test_not_sudden_no_trade():
    day1 = list(DAY1)
    day1[12] = (1001, 1002, 999.7, 1001)                        # breach of 0.3 (> 0.2), range 2.3: not sudden
    assert [t for t in run("P1", minutes([DAY0, day1])) if t["side"] > 0] == []


def test_run_is_not_a_sweep():
    day1 = list(DAY1)
    day1[12] = (1001, 999.5, 995, 996)                          # no high back above 1000 within 3 bars
    day1[13] = (996, 999, 994, 995)
    day1[14] = (995, 999.9, 993, 994)
    assert [t for t in run("P7", minutes([DAY0, day1])) if t["side"] > 0 and t["entry"] < 80] == []


def test_mirror_gives_the_sell():
    t = [t for t in run("P1", minutes([DAY0, DAY1], mirror_at=4000.0)) if t["side"] < 0]
    assert len(t) == 1 and t[0]["entry"] == 71 and t[0]["why"] == "target"


def test_look_ahead_guard():
    full = [t for t in run("P1") if t["side"] > 0][0]
    g = minutes([DAY0, DAY1])
    for a in (g.o, g.h, g.l, g.c):
        a[1, full["entry"] + 1:] = np.nan
    cut = [t for t in run("P1", g) if t["side"] > 0][0]
    for k in ("entry", "ref", "stop_pts", "tgt_pts"):
        assert cut[k] == full[k]


def test_merge_one_position():
    c = [dict(d=0, entry=10, exit=50), dict(d=0, entry=30, exit=60), dict(d=0, entry=51, exit=70),
         dict(d=1, entry=5, exit=9)]
    assert [x["entry"] for x in P.merge(c, set())] == [10, 51, 5]
    assert [x["entry"] for x in P.merge(c, {1})] == [10, 51]


def test_race():
    h, l, o = np.full(N, 101.0), np.full(N, 99.0), np.full(N, 100.0)
    h[20] = 106.0
    assert P.race(h, l, o, 10, 100.0, 5.0, 364) == "up"
    l2 = np.full(N, 99.0)
    l2[15] = 94.0
    assert P.race(h, l2, o, 10, 100.0, 5.0, 364) == "down"
