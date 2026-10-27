"""R122 tests: the 70-minute trade's arithmetic, stale legs with calendar time, twins' rows, the look-ahead guard.

    python3 -m pytest research/hunt/r122_morning_premium -q
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r122cfg as K  # noqa: E402
import r122lib as L  # noqa: E402
import r98data as R  # noqa: E402
import r54lib as LB  # noqa: E402
import test_r118 as T118  # noqa: E402

LB.load_late()
EXP = date(2025, 1, 9)


def c_of(seed=1):
    return L.ctx(T118.synthetic_day(seed=seed))


def test_rows():
    assert K.ROW_DEC == 5 and K.ROW_EXIT == 75 and K.HOLD == 70


def test_trade_arithmetic():
    c = c_of()
    tr = L.trade(c, K.ROW_DEC, K.ROW_EXIT, EXP)
    assert tr is not None and [lg.sign for lg in tr.legs] == [-1, -1, 1, 1]
    assert math.isclose(tr.net, round(tr.gross - tr.charges, 2), abs_tol=0.02)
    assert tr.charges > 8 * 20 and not tr.extra["expiry"]
    assert tr.margin == round(K.MARGIN_PCT * 2 * tr.legs[0].k * c.lot, 2)


def test_expiry_flag_and_elm():
    c = c_of()
    d = c.day.day
    tr = L.trade(c, K.ROW_DEC, K.ROW_EXIT, d)
    assert tr.extra["expiry"] and tr.margin == round((K.MARGIN_PCT + K.ELM_PCT) * 2 * tr.legs[0].k * c.lot, 2)


def test_stale_leg_uses_calendar_time():
    c = c_of(seed=2)
    tr0 = L.trade(c, K.ROW_DEC, K.ROW_EXIT, EXP)
    wing = tr0.legs[2]
    c.day.opts[(wing.k, wing.side)][K.ROW_EXIT:K.ROW_EXIT + 3, :4] = np.nan
    c2 = L.ctx(c.day)
    tr = L.trade(c2, K.ROW_DEC, K.ROW_EXIT, EXP)
    assert tr.stale == 1
    near = L.stale_price(c2, wing, K.ROW_EXIT, c.day.day)
    far = L.stale_price(c2, wing, K.ROW_EXIT, EXP)
    assert near is not None and far is not None


def test_twin_rows_uniform_window():
    rng = np.random.default_rng(0)
    r = L.twin_rows(rng)
    assert len(r) == K.TWIN_ROWS_PER_DAY and r.min() >= K.TWIN_A and r.max() <= K.TWIN_B and len(set(r)) == len(r)


def test_lookahead_guard():
    """The legs chosen at 09:20 ignore everything after the 09:21 fill."""
    a = c_of(seed=5)
    ta = L.trade(a, K.ROW_DEC, K.ROW_EXIT, EXP)
    b = T118.synthetic_day(seed=5)
    rng = np.random.default_rng(1)
    for x in b.opts.values():
        x[K.ROW_DEC + 2:, :4] *= rng.uniform(0.3, 3.0, (R.N - K.ROW_DEC - 2, 1))
    b.ix[K.ROW_DEC + 2:] *= 1.01
    tb = L.trade(L.ctx(b), K.ROW_DEC, K.ROW_EXIT, EXP)
    assert [(lg.k, lg.side, lg.sign) for lg in ta.legs] == [(lg.k, lg.side, lg.sign) for lg in tb.legs]


def test_tests_declared():
    assert list(K.TESTS) == ["U1", "U2"]
