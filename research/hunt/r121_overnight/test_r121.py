"""R121 tests: nights of the same expiry, the overnight trade's arithmetic, stale-leg repricing, the look-ahead guard,
the bootstrap p.

    python3 -m pytest research/hunt/r121_overnight -q
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r121cfg as K  # noqa: E402
import r121lib as L  # noqa: E402
import r121study as S  # noqa: E402
import r119lib as L19  # noqa: E402
import r98data as R  # noqa: E402
import r54lib as LB  # noqa: E402
import r50lib as L50  # noqa: E402
import test_r118 as T118  # noqa: E402

LB.load_late()


def pair(seed=1, gap=0.0, d=date(2025, 1, 2), n=date(2025, 1, 3)):
    """An evening day and a morning day of the same synthetic chain; the morning index shifted by `gap` points."""
    eve = T118.synthetic_day(seed=seed, d=d)
    morn = T118.synthetic_day(seed=seed, d=n)
    morn.ix += gap
    return eve, morn


def test_keep_rows():
    m = np.arange(R.N)
    k = L.keep_rows(m)
    assert k[K.EVE_FROM] and k[K.MORN_TO] and not k[K.MORN_TO + 1] and not k[K.EVE_FROM - 1]


def test_years_to_expiry():
    y = L.years_to_expiry(date(2025, 1, 2), K.ROW_EVE, date(2025, 1, 3))
    assert abs(y * 365 * 24 * 60 - (24 * 60 + 15)) < 1e-6             # 15:15 to next day 15:30


def test_overnight_arithmetic_and_fields():
    eve, morn = pair()
    e, m = L.ctx(eve), L.ctx(morn)
    tr = L.overnight(e, m, eve.day, morn.day, morn.day)
    assert tr is not None and len(tr.legs) == 4
    assert [lg.sign for lg in tr.legs] == [-1, -1, 1, 1]
    assert tr.extra["eve_expiry"] and tr.extra["gap_days"] == 1
    assert tr.charges > 8 * 20                                        # 8 orders at least Rs 20 each
    assert math.isclose(tr.net, round(tr.gross - tr.charges, 2), abs_tol=0.02)
    assert tr.margin == round((K.MARGIN_PCT + K.ELM_PCT) * 2 * tr.legs[0].k * e.lot, 2)


def _flat_morning(eve, morn, shift=0.0):
    """Every option reopens at its evening close (a flat night), or at intrinsic after an index gap of `shift`."""
    s_eve = float(eve.ix[R.N - 1, 3])
    morn.ix[:, :] = s_eve + shift
    for key, a in morn.opts.items():
        e = eve.opts[key]
        c = float(e[R.N - 1, R.C])
        k, side = key
        px = c if shift == 0 else max((s_eve + shift - k) if side == "C" else (k - s_eve - shift), 0.0) + 0.05
        a[:, [R.O, R.H, R.L, R.C]] = px


def test_big_gap_hurts_the_seller_but_the_wings_cap_it():
    eve, morn = pair(seed=4)
    _flat_morning(eve, morn)
    flat = L.overnight(L.ctx(eve), L.ctx(morn), eve.day, morn.day, date(2025, 1, 9))
    eve2, morn2 = pair(seed=4)
    _flat_morning(eve2, morn2, shift=400.0)
    gap = L.overnight(L.ctx(eve2), L.ctx(morn2), eve2.day, morn2.day, date(2025, 1, 9))
    assert gap.net < flat.net
    w = gap.legs[2].k - gap.legs[0].k
    lot = L.ctx(eve2).lot
    assert gap.net >= -(w * lot) - gap.charges - 0.6 * 8 * lot       # defined risk: wing width plus costs and ticks


def test_stale_leg_is_repriced():
    eve, morn = pair(seed=2)
    e, m = L.ctx(eve), L.ctx(morn)
    tr0 = L.overnight(e, m, eve.day, morn.day, date(2025, 1, 9))
    wing = tr0.legs[2]
    a = morn.opts[(wing.k, wing.side)]
    a[:, :4] = np.nan                                                # the wing has no print in the morning
    tr = L.overnight(L.ctx(eve), L.ctx(morn), eve.day, morn.day, date(2025, 1, 9))
    assert tr.stale == 1
    p = L.stale_price(L.ctx(eve), L.ctx(morn), wing, eve.day, morn.day, date(2025, 1, 9))
    assert p is not None and p >= 0


def test_lookahead_guard_entry_ignores_the_morning():
    """The legs (strikes) chosen at 15:15 do not change when the next morning's prices change."""
    eve, morn = pair(seed=6)
    a = L.overnight(L.ctx(eve), L.ctx(morn), eve.day, morn.day, date(2025, 1, 9))
    eve2, morn2 = pair(seed=6, gap=300.0)
    for x in morn2.opts.values():
        x[:, :4] *= 3.0
    b = L.overnight(L.ctx(eve2), L.ctx(morn2), eve2.day, morn2.day, date(2025, 1, 9))
    assert [(lg.k, lg.side, lg.sign) for lg in a.legs] == [(lg.k, lg.side, lg.sign) for lg in b.legs]
    eve3, morn3 = pair(seed=6)
    for x in eve3.opts.values():
        x[K.ROW_EVE + 2:, :4] *= 2.0                                 # after the entry fill
    c = L.overnight(L.ctx(eve3), L.ctx(morn3), eve3.day, morn3.day, date(2025, 1, 9))
    assert [(lg.k, lg.side) for lg in c.legs] == [(lg.k, lg.side) for lg in a.legs]


def test_boot_p():
    def tr(d, net):
        t = L19.Trade("R121", "NIFTY", d, 0, 1, [])
        t.net = net
        return t
    good = [tr(date(2026, 1, 1 + i % 28), 100.0 + (i % 5)) for i in range(60)]
    bad = [tr(date(2026, 1, 1 + i % 28), -100.0) for i in range(60)]
    assert S.boot_p(good, n=500) == 0.0 and S.boot_p(bad, n=500) == 1.0 and S.boot_p([]) == 1.0


def test_tests_declared():
    assert list(K.TESTS) == ["T1", "T2"]
    t = L19.Trade("R121", "NIFTY", date(2026, 1, 1), 0, 1, [])
    t.extra.update(eve_expiry=False, gap_days=1)
    assert S.subset([t], "T2") == [] and S.subset([t], "T1") == [t]
