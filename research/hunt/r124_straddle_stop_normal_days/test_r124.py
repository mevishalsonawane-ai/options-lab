"""R124 tests: the frozen rule on a normal day (no ELM, expiry-aware stale pricing), and the look-ahead guard.

    python3 -m pytest research/hunt/r124_straddle_stop_normal_days -q
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r124cfg as K  # noqa: E402
import r124lib as L  # noqa: E402
import r122lib as L122  # noqa: E402
import r123lib as L123  # noqa: E402
import r98data as R  # noqa: E402
import r54lib as LB  # noqa: E402
import test_r118 as T118  # noqa: E402

LB.load_late()
EXP = date(2025, 1, 9)


def c_of(seed=1):
    return L122.ctx(T118.synthetic_day(seed=seed))


def test_frozen_rule_matches_r123_stop():
    c = c_of(3)
    tr = L.straddle(c, K.ROW_DEC, 1.5, EXP)
    x, st = L123.stop_row(c.sp, K.ROW_DEC, 1.5, c.em[K.ROW_DEC], K.ROW_END)
    assert tr.exit_row == x and tr.extra["stopped"] == st
    assert [(lg.side, lg.sign) for lg in tr.legs] == [("C", -1), ("P", -1)]
    assert math.isclose(tr.net, round(tr.gross - tr.charges, 2), abs_tol=0.02)
    assert tr.margin == round(K.MARGIN_PCT * 2 * tr.legs[0].k * c.lot, 2)   # no expiry-day ELM


def test_stale_exit_uses_expiry_clock():
    day = T118.synthetic_day(seed=4)
    c = L122.ctx(day)
    tr0 = L.straddle(c, K.ROW_DEC, 9.0, EXP)                        # no stop: out at 15:05
    k0 = tr0.legs[0].k
    day.opts[(k0, "C")][K.ROW_END:K.ROW_END + 3, :4] = np.nan
    tr = L.straddle(L122.ctx(day), K.ROW_DEC, 9.0, EXP)
    assert tr.stale == 1


def test_lookahead_guard():
    a = c_of(9)
    b = c_of(9)
    b.sp[K.ROW_DEC + 1:] += 5000.0
    ta, tb = L.straddle(a, K.ROW_DEC, 1.0, EXP), L.straddle(b, K.ROW_DEC, 1.0, EXP)
    assert ta.legs == tb.legs
    assert tb.extra["stopped"] and tb.exit_row == K.ROW_DEC + 2


def test_tests_declared():
    assert K.TESTS == {"W1": 1.0, "W2": 1.5}
