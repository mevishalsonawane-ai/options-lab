"""R161 tests: synthetic data only.

    python -m pytest research/hunt/r161_night_frozen/tests -q
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pytest

from research.hunt.r160_cas_gap import cfg as K0
from research.hunt.r161_night_frozen import study as S


def test_atm_matches_night_rules():
    assert S.atm(22874.9, 50) == 22850.0 and S.atm(22875.0, 50) == 22900.0 and S.atm(51049, 100) == 51000.0


def test_condition_thresholds_and_nan():
    assert S.condition(106, 110, 100, 105) == 1          # loc 0.6, up
    assert S.condition(104, 110, 100, 105) == -1         # loc 0.4, down
    assert S.condition(106, 110, 100, 107) == 0          # loc 0.6 but down on the day
    assert S.condition(105, 110, 100, 100) == 0          # loc 0.5
    assert S.condition(100, 100, 100, 100) == 0          # no range: loc 0.5
    assert S.condition(np.nan, 110, 100, 105) == 0


def test_flip_kinds():
    assert S.flip_kind(1, 1) == "same" and S.flip_kind(0, 0) == "same"
    assert S.flip_kind(1, 0) == "A_only" and S.flip_kind(0, -1) == "B_only" and S.flip_kind(1, -1) == "opposite"


def _day(frozen=True):
    n = K0.N
    cl = 20000 + np.arange(n) * 0.5
    if frozen:
        cl[K0.row(15, 15):K0.row(15, 28) + 1] = cl[K0.row(15, 14)]
    hi, lo = cl + 1, cl - 1
    f = 20000 + np.arange(n) * 0.5 + 10.0                 # live market, carry 10
    return cl, hi, lo, f


def test_reading_b_sees_the_live_market_through_the_freeze():
    cl, hi, lo, f = _day()
    r = S.day_inputs(cl, hi, lo, f, prev=19900.0, step=50)
    live = 20000 + np.median([K0.row(15, m) for m in (17, 18, 19)]) * 0.5
    assert r["xb"] == pytest.approx(live)
    assert r["xa"] == pytest.approx(cl[K0.row(15, 14)])
    assert r["gap_bps"] > 0


def test_lookahead_guard_nothing_after_1519():
    cl, hi, lo, f = _day()
    a = S.day_inputs(cl, hi, lo, f, 19900.0, 50)
    for arr in (cl, hi, lo, f):
        arr[K0.row(15, 20):] += 999.0
    b = S.day_inputs(cl, hi, lo, f, 19900.0, 50)
    assert a == b


def test_range_widens_for_b_only():
    cl, hi, lo, f = _day()
    f[K0.row(15, 17):K0.row(15, 19) + 1] += 500.0          # live market jumps above the day's high
    r = S.day_inputs(cl, hi, lo, f, 19900.0, 50)
    assert r["cb"] == 1                                    # B sits at its own (widened) high
    assert r["kb"] > r["ka"]


def test_reading_b_needs_basis_and_minutes():
    cl, hi, lo, f = _day()
    f2 = f.copy()
    f2[K0.row(15, 17):K0.row(15, 19) + 1] = np.nan
    assert np.isnan(S.reading_b(f2, cl))
    f3 = f.copy()
    f3[K0.BASIS_ROWS[0]:K0.BASIS_ROWS[1] + 1] = np.nan
    assert np.isnan(S.reading_b(f3, cl))


def test_lots():
    assert S.lot("NIFTY", date(2026, 8, 3)) == 65 and S.lot("BANKNIFTY", date(2026, 8, 3)) == 30
    assert S.lot("BANKNIFTY", date(2025, 7, 1)) == 35


def test_price_one_itm_leg():
    d, nd = date(2026, 8, 5), date(2026, 8, 6)
    a = np.full((4, K0.N), np.nan)
    a[:, S.R1520] = 200.0
    nx = np.full((4, K0.N), np.nan)
    nx[:, K0.EXIT_ROW] = 210.0
    # x = 20010 -> ATM 20000; a call is one step in the money: 19950
    v = S.price("NIFTY", d, 1, 20010.0, {("C", 19950.0): a}, {("C", 19950.0): nx})
    assert v is not None and v < 10 * 65
    assert S.price("NIFTY", d, -1, 20010.0, {("C", 19950.0): a}, {("C", 19950.0): nx}) is None
