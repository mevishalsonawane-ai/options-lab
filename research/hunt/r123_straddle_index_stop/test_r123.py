"""R123 tests: the index stop, the straddle's legs and arithmetic, the look-ahead guard.

    python3 -m pytest research/hunt/r123_straddle_index_stop -q
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r123cfg as K  # noqa: E402
import r123lib as L  # noqa: E402
import r119lib as L19  # noqa: E402
import r98data as R  # noqa: E402
import r54lib as LB  # noqa: E402
import test_r118 as T118  # noqa: E402

LB.load_late()


def test_stop_row():
    sp = np.full(R.N, 100.0)
    assert L.stop_row(sp, 15, 1.0, 5.0) == (K.ROW_END, False)
    sp[40] = 105.0
    assert L.stop_row(sp, 15, 1.0, 5.0) == (41, True)               # exits at the next bar's open
    assert L.stop_row(sp, 15, 1.5, 5.0) == (K.ROW_END, False)
    sp[30] = 92.0
    assert L.stop_row(sp, 15, 1.5, 5.0) == (31, True)               # either direction
    sp2 = np.full(R.N, 100.0)
    sp2[K.ROW_END] = 200.0                                          # a move at 15:05 itself is too late
    assert L.stop_row(sp2, 15, 1.0, 5.0) == (K.ROW_END, False)
    sp3 = np.full(R.N, np.nan)
    sp3[15] = 100.0
    assert L.stop_row(sp3, 15, 1.0, 5.0) == (K.ROW_END, False)     # missing closes are skipped


def test_straddle_legs_and_arithmetic():
    c = L19.DayCtx(T118.synthetic_day(seed=3))
    tr = L.straddle(c, K.ROW_DEC, 1.0)
    k0 = c.k0(K.ROW_DEC)
    assert [(lg.k, lg.side, lg.sign) for lg in tr.legs] == [(k0, "C", -1), (k0, "P", -1)]
    assert math.isclose(tr.net, round(tr.gross - tr.charges, 2), abs_tol=0.02)
    assert tr.charges < 6 * 40                                      # 4 orders
    assert "stopped" in tr.extra


def test_big_move_triggers_the_stop_and_a_loss():
    day = T118.synthetic_day(seed=3)
    c = L19.DayCtx(day)
    s = c.em[K.ROW_DEC]
    calm = L.straddle(c, K.ROW_DEC, 1.0)
    day2 = T118.synthetic_day(seed=3)
    c2 = L19.DayCtx(day2)
    c2.sp[60:] = c2.sp[K.ROW_DEC] + 3 * s                           # the index runs 3 straddles
    tr = L.straddle(c2, K.ROW_DEC, 1.0)
    assert tr.extra["stopped"] and tr.exit_row == 61
    assert calm.legs == tr.legs


def test_lookahead_guard():
    """The legs and the stop decision at row r depend only on rows <= r."""
    a = L19.DayCtx(T118.synthetic_day(seed=8))
    b = L19.DayCtx(T118.synthetic_day(seed=8))
    cut = 200
    b.sp[cut + 1:] += 10_000.0
    ra, sa = L.stop_row(a.sp, K.ROW_DEC, 1.0, a.em[K.ROW_DEC])
    rb, sb = L.stop_row(b.sp, K.ROW_DEC, 1.0, b.em[K.ROW_DEC])
    if ra <= cut + 1:
        assert (ra, sa) == (rb, sb)
    else:
        assert rb == cut + 2 and sb                                 # first moved close after the cut
    ta, tb = L.straddle(a, K.ROW_DEC, 1.0), L.straddle(b, K.ROW_DEC, 1.0)
    assert ta.legs == tb.legs


def test_tests_declared():
    assert K.TESTS == {"V1": 1.0, "V2": 1.5}
