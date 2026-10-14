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


def test_regime_flags_read_earlier_expiries_only():
    rng = np.random.default_rng(1)
    gs = list(rng.uniform(1, 2, 60))
    gn = list(rng.uniform(-1, 1, 60))
    full = G.regime_flags(gs, gn)
    assert not any(f["ok"] for f in full[: G.SCALE_N + G.MIN_CUT])         # 20 to scale, then 10 for a cut
    assert all(f["ok"] for f in full[G.SCALE_N + G.MIN_CUT:])
    # changing a later expiry changes nothing before it
    gs2, gn2 = gs[:], gn[:]
    gs2[45], gn2[45] = 99.0, -99.0
    alt = G.regime_flags(gs2, gn2)
    assert alt[:45] == full[:45]
    assert alt[45]["x1"] and alt[45]["x2"]
    # a day without GEX gets no decision, and the scale waits until the last 20 expiry slots all have GEX again
    gs3 = gs[:]
    gs3[40] = math.nan
    f3 = G.regime_flags(gs3, gn)
    assert not f3[40]["ok"] and not f3[41]["ok"]
    assert all(not f["ok"] for f in f3[40: 40 + G.SCALE_N + 1])
    assert f3[:40] == full[:40]
