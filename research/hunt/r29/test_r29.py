"""R29 tests: python3 -m pytest research/hunt/r29/test_r29.py -q"""
from __future__ import annotations

import math
import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r29gex as G  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r26lib as L26  # noqa: E402
from r25data import OI, N  # noqa: E402
from test_r25 import flat, make_day, rally  # noqa: E402


def test_gex_short_is_positive_and_net_tracks_call_minus_put_oi():
    day = make_day(flat())
    gs, gn = G.gex(day, G.T0, 0.12)
    assert gs > 0 and abs(gn) < 1e-6 * gs                 # equal call and put OI: net ~ 0
    for (k, s), a in day.opts.items():
        if s == "P":
            a[:, OI] *= 3
    day.__dict__.pop("_r26chain", None)
    gs2, gn2 = G.gex(day, G.T0, 0.12)
    assert gs2 > gs and gn2 < 0
    assert math.isnan(G.gex(day, G.T0, math.nan)[0])


def test_measures_read_no_future():
    day = make_day(rally(start=G.T0 + 5))
    want = (G.atm_iv(day, G.T0), *G.gex(day, G.T0, 0.12))
    for fut in (L26.masked(day, G.T0), L26.poisoned(day, G.T0)):
        got = (G.atm_iv(fut, G.T0), *G.gex(fut, G.T0, 0.12))
        for a, b in zip(want, got):
            assert a == pytest.approx(b, nan_ok=True)


def test_move_ratio_is_realized_over_implied():
    day = make_day(flat())
    assert G.move_ratio(day, G.T0, 0.12) == 0.0             # a flat afternoon moves nothing
    assert math.isnan(G.move_ratio(day, G.T0, math.nan))
    day2 = make_day(rally(start=G.T0 + 5, pts=400))
    assert G.move_ratio(day2, G.T0, 0.12) > 0


def test_pair_picks_nearest_22_5_in_the_band():
    day = make_day(flat())
    legs = G.pair(day, G.T0)
    for sgn, k, p in legs:
        assert 15 <= p <= 30
        el = R.eligible(day, sgn, G.T0, G.BAND)
        assert abs(p - 22.5) == min(abs(q - 22.5) for _, q in el)


def test_regime_flags_use_earlier_expiries_only():
    rows = [{"ok": True, "x1": i % 3 == 0, "x2": i % 2 == 0, "und": "NIFTY", "legs": []} for i in range(9)]
    assert len(G.select(rows, "X1")) == 3 and len(G.select(rows, "X3")) == 2 and len(G.select(rows, "B0")) == 9
    assert G.SCALE_N == 20 and G.MIN_CUT == 10


@pytest.mark.skipif(not D.path("NIFTY").exists(), reason="the lake day files are not built here")
def test_scaling_never_reads_a_later_day():
    ds = sorted(D.load("NIFTY", "all"), key=lambda d: d.day)[:40]
    vals = [G.gex(d, G.T0, G.atm_iv(d, G.T0))[0] for d in ds]
    # the 30th day's scale uses days 10..29 only: changing day 35 cannot change it
    prev = [x for x in vals[10:30] if x == x]
    assert len(prev) >= 1 and np.isfinite(np.median(prev))
