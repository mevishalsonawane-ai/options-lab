"""R30 tests: python3 -m pytest research/hunt/r30/test_r30.py -q"""
from __future__ import annotations

import math
import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r30flip as X  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r26lib as L26  # noqa: E402
from r25data import C, N, OI, V, minute  # noqa: E402
from test_r25 import _bars, flat, make_day  # noqa: E402


def _chain_day(spot=None, put_heavy=25000.0):
    """Puts carry the OI below `put_heavy`, calls above it: net GEX changes sign near that level."""
    day = make_day(spot if spot is not None else flat())
    for (k, s), a in day.opts.items():
        oi = 3e6 if ((s == "C" and k > put_heavy) or (s == "P" and k < put_heavy)) else 1e5
        a[:, OI] = np.where(np.isnan(a[:, C]), np.nan, oi)
    return day


def test_flip_sits_between_the_put_and_call_oi():
    fl, prof = X.flip(_chain_day())
    assert fl is not None and 24900 < fl < 25100
    assert X.net_gex(prof, fl - 100) < 0 < X.net_gex(prof, fl + 100)


def test_no_flip_when_net_gex_never_changes_sign():
    day = make_day(flat())
    for (k, s), a in day.opts.items():
        a[:, OI] = np.where(np.isnan(a[:, C]), np.nan, 3e6 if s == "C" else 1e4)
    assert X.flip(day)[0] is None


def test_flip_reads_no_future():
    day = _chain_day()
    want = X.flip(day)[0]
    for fut in (L26.masked(day, X.T_GEX), L26.poisoned(day, X.T_GEX)):
        assert X.flip(fut)[0] == pytest.approx(want)


def test_trigger_needs_the_break_in_that_bar():
    r = np.arange(N)
    spot = np.where(r < R.close_row(14, 10), 24980.0, 25060.0)
    day = _chain_day(spot)
    fl, prof = X.flip(day)
    import r28lib as S
    ctx = S.Ctx(day, None)
    hv = {}
    for s in S.HEAVY:
        a = np.full((N, 2), 100.0)
        a[:, 1] = 101.0
        hv[s] = a
    ctx.hv = hv
    t = R.close_row(14, 10)
    k = R.atm(day, 25060.0) + 50.0
    day.opts[(k, "C")][t, V] = 1e6
    assert X.fires(day, X.G1, ctx, fl, prof, t, 1) is not None
    assert X.fires(day, X.G1, ctx, fl, prof, t + 1, 1) is None         # already above: no fresh break
    assert X.fires(day, X.G1, ctx, fl, prof, t, -1) is None


def _trade(px, lot=25):
    day = make_day(flat())
    day.lot = lot
    k = 25100.0
    day.opts[(k, "C")] = _bars(px)
    return X.simulate(day, k, 1, X.FIRST, float(px[X.FIRST]), "T2")


def test_t2_breakeven_at_2x_and_half_of_each_doubling():
    px = np.full(N, 20.0)
    px[X.FIRST + 5: X.FIRST + 10] = 45.0       # > 2x: lock at cost
    px[X.FIRST + 10:] = 19.0                    # back below cost: sells
    tr, sk = _trade(px)
    assert not sk and tr.exits[-1][3] == "lock"
    px = np.full(N, 20.0)
    px[X.FIRST + 5: X.FIRST + 10] = 90.0       # > 4x: lock at 2x
    px[X.FIRST + 10:] = 39.0
    tr, _ = _trade(px)
    assert tr.exits[-1][3] == "lock" and tr.net > 0
    tr, _ = _trade(np.full(N, 20.0))
    assert tr.exits[-1][3] == "1505" and tr.exits[-1][0] == minute(15, 5)


def test_one_lot_over_2000_is_skipped():
    tr, sk = _trade(np.full(N, 20.0), lot=150)
    assert tr is None and sk


def test_rules_and_window():
    assert [r.id for r in X.RULES] == ["G1", "G2", "G3", "G4"]
    assert X.FIRST == R.close_row(14, 0) and X.LAST == R.close_row(14, 45) and X.T_GEX == R.close_row(13, 45)


@pytest.mark.skipif(not D.path("NIFTY").exists(), reason="the lake day files are not built here")
def test_flip_and_signal_guard_on_real_days():
    import r28lib as S
    n = 0
    for u in ("NIFTY", "SENSEX"):
        ds = sorted(D.load(u, "all"), key=lambda d: d.day)
        for day in (ds[-30], ds[-60]):
            fl, prof = X.flip(day)
            ctx = S.Ctx(day, None)
            full = X.signal(day, X.RULES[3], ctx, fl, prof)
            for t in range(X.FIRST, X.LAST + 1, 9):
                fut = L26.poisoned(day, t)
                c2 = S.Ctx(fut, None)
                c2.hv = ctx.hv
                got = X.signal(fut, X.RULES[3], c2, fl, prof)
                want = full if full is not None and full[0] <= t else None
                if want is None:
                    assert got is None or got[0] > t
                else:
                    assert got is not None and got[:3] == want[:3]
                n += 1
            if fl is not None:
                assert X.flip(L26.poisoned(day, X.T_GEX))[0] == pytest.approx(fl)
    assert n > 10
