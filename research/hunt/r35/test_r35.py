"""R35 tests: python3 -m pytest research/hunt/r35/test_r35.py -q"""
from __future__ import annotations

import math
import os
import sys
from dataclasses import replace

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r35lib as S  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r26lib as L26  # noqa: E402
from r25data import C, H, L, N, V, minute  # noqa: E402
from test_r25 import _bars, flat, make_day  # noqa: E402


def test_supertrend_flips_green_on_a_rally_and_red_on_a_fall():
    c = np.r_[np.full(30, 100.0), np.linspace(100, 130, 20), np.linspace(130, 95, 25)]
    h, l = c + 0.5, c - 0.5
    st = S.supertrend(h, l, c, 7, 3.0)
    assert (st[:6] == 0).all()                       # no ATR yet
    assert st[45] == 1 and st[-1] == -1
    # bar i depends on bars <= i only
    for i in (20, 40, 60):
        assert np.array_equal(S.supertrend(h[: i + 1], l[: i + 1], c[: i + 1], 7, 3.0), st[: i + 1])


def test_ema_prev_uses_earlier_bars_only():
    v = np.array([10.0, 20.0, 30.0, 1000.0])
    e = S.ema_prev(v, 10)
    assert math.isnan(e[0]) and e[1] == 10.0
    assert e[3] < 1000.0                             # the current bar never enters its own EMA


def test_twap_is_the_mean_typical_price_since_the_open():
    day = make_day(np.linspace(25000, 25100, N))
    tw = S.twap(day)
    tp = (day.ix[:, 1] + day.ix[:, 2] + day.ix[:, 3]) / 3
    assert tw[0] == pytest.approx(tp[0]) and tw[100] == pytest.approx(tp[:101].mean())


def _ctx(day, hv_sign=1):
    ctx = S.Ctx.__new__(S.Ctx)
    ctx.tw = S.twap(day)
    ctx.ratios = np.ones(31)
    ctx.ix_rows = np.nonzero(np.isfinite(day.ix[:, 3]))[0]
    ctx.ist7 = np.zeros(N)
    ctx.ist10 = np.zeros(N)
    hv = {}
    for s in S.HEAVY:
        a = np.full((N, 2), 100.0)
        a[:, 1] = 100.0 + hv_sign
        hv[s] = a
    ctx.hv = hv
    return ctx


def _spike_day():
    """Spot drifts up all day (above its TWAP); the ATM+1 call trades flat at 20, then jumps with 5x volume at 14:10."""
    spot = np.linspace(25000, 25040, N)
    day = make_day(spot)
    t0 = R.close_row(14, 10)
    for k in (25050.0, 25100.0):
        px = np.full(N, 20.0) + np.sin(np.arange(N)) * 0.2
        px[t0:] = 26.0 + np.arange(N - t0) * 0.05
        b = _bars(px, 5000.0)
        b[t0, V] = 50000.0
        day.opts[(k, "C")] = b
    return day, t0


def test_trigger_needs_every_condition():
    day, t0 = _spike_day()
    ctx = _ctx(day)
    s = S.signal(day, S.S1, ctx)
    assert s is not None and s[1] == 1 and s[0] == t0
    assert s[2] == R.atm(day, R.spot(day, t0)) + 50.0
    assert S.signal(day, S.S1, _ctx(day, hv_sign=-1)) is None              # heavyweights red
    assert S.signal(day, replace(S.S1, heavy=False), _ctx(day, hv_sign=-1)) is not None
    ctx2 = _ctx(day)
    ctx2.tw = ctx2.tw + 1000                                                # spot below the TWAP
    assert S.signal(day, S.S1, ctx2) is None
    day.opts[(25050.0, "C")][t0, V] = 5000.0                                # no volume spike
    day.opts[(25100.0, "C")][t0, V] = 5000.0
    assert S.signal(day, S.S1, _ctx(day)) is None


def test_trigger_reads_no_future():
    day, t0 = _spike_day()
    want = S.signal(day, S.S1, _ctx(day))
    for fut in (L26.masked(day, t0), L26.poisoned(day, t0)):
        got = S.signal(fut, S.S1, _ctx(fut))
        assert got is not None and got[:3] == want[:3]


def test_window_1400_to_1445():
    assert S.FIRST == R.close_row(14, 0) and S.LAST == R.close_row(14, 45)
    day = make_day(flat())
    assert S.signal(day, S.S1, _ctx(day)) is None


def test_one_lot_above_2000_is_skipped_and_whole_lots_otherwise():
    day = make_day(flat())
    day.lot = 150
    k = 25100.0
    day.opts[(k, "C")] = _bars(np.full(N, 20.0))
    tr, skipped = S.trade(day, k, 1, S.FIRST, 20.0, "E1")
    assert tr is None and skipped
    day.lot = 25
    tr, skipped = S.trade(day, k, 1, S.FIRST, 20.0, "E1")
    assert tr is not None and not skipped and tr.lots == int(2000 // (tr.entry * 25))


def test_heavyweights_missing_bar_fails():
    day = make_day(flat())
    ctx = _ctx(day)
    ctx.hv["RELIANCE"][S.FIRST] = np.nan
    assert not S.heavy_ok(ctx, S.FIRST, 1)
    ctx.hv = None
    assert not S.heavy_ok(ctx, S.FIRST, 1)


def test_rules_and_family():
    assert [r.id for r in S.RULES] == ["S1", "S2", "S3", "S4", "S5"]
    assert S.RULES[1].exits == "E1" and not S.RULES[2].heavy and S.RULES[3].ivrv == 1.0 and S.RULES[4].chart == "index"


@pytest.mark.skipif(not D.path("NIFTY").exists(), reason="the lake day files are not built here")
def test_guard_on_real_days():
    n = 0
    for u in ("NIFTY", "SENSEX"):
        ds = sorted(D.load(u, "all"), key=lambda d: d.day)
        for day in (ds[len(ds) // 3], ds[2 * len(ds) // 3]):
            ctx = S.Ctx(day, None)
            for rule in (S.S1, S.RULES[2], S.RULES[4]):
                full = S.signal(day, rule, ctx)
                for t in range(S.FIRST, S.LAST + 1, 9):
                    fut = L26.poisoned(day, t)
                    c2 = S.Ctx(fut, None)
                    c2.hv = ctx.hv
                    got = S.signal(fut, rule, c2)
                    want = full if full is not None and full[0] <= t else None
                    if want is None:
                        assert got is None or got[0] > t
                    else:
                        assert got is not None and got[:3] == want[:3]
                    n += 1
    assert n > 20
