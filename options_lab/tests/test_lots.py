"""Dated lot-size history.

Every rupee figure in this project multiplies by a lot size, and NIFTY's
changed four times across the sample: 50, then 25, then 75, then 65. Using
today's 65 for a 2024 session scales that session's P&L by 2.6x.

The table is read from NSE bhavcopy's NewBrdLotQty, not assumed. Dates outside
its coverage raise rather than extrapolate backwards, because a silently wrong
lot is exactly the class of error this project keeps paying for. Index-point
results never touch this; only rupee figures do.
"""
from __future__ import annotations

from datetime import date

import pytest

from options_lab import lots


# --- the verified sample points -------------------------------------------
@pytest.mark.parametrize("day,expected", [
    (date(2024, 1, 2), 50),
    (date(2024, 6, 3), 25),
    (date(2024, 11, 25), 25),
    (date(2025, 1, 2), 75),
    (date(2025, 6, 2), 75),
    (date(2026, 1, 2), 65),
    (date(2026, 4, 1), 65),
])
def test_known_dates_return_the_lot_bhavcopy_reports(day, expected):
    assert lots.lot_size_on("NIFTY", day) == expected


def test_the_lot_is_constant_within_an_era():
    a = lots.lot_size_on("NIFTY", date(2025, 2, 3))
    b = lots.lot_size_on("NIFTY", date(2025, 5, 5))

    assert a == b == 75


# --- refusals --------------------------------------------------------------
def test_a_date_before_coverage_is_refused_not_extrapolated_backwards():
    """Guessing backwards would silently rescale every early session."""
    with pytest.raises(lots.NoLotSize):
        lots.lot_size_on("NIFTY", date(2015, 1, 1))


def test_an_unknown_underlying_is_refused():
    with pytest.raises(lots.NoLotSize):
        lots.lot_size_on("FINNIFTY", date(2025, 1, 2))


def test_a_future_date_carries_the_latest_known_lot_forward():
    """Forward extrapolation is safe: the current lot IS current until NSE
    changes it, and monitor.check_lot_size exists to catch that change."""
    latest = lots.lot_size_on("NIFTY", date(2026, 4, 1))

    assert lots.lot_size_on("NIFTY", date(2027, 1, 1)) == latest


# --- table integrity -------------------------------------------------------
def test_every_table_is_in_chronological_order():
    for underlying, entries in lots.LOT_HISTORY.items():
        days = [d for d, _ in entries]
        assert days == sorted(days), f"{underlying} entries are out of order"


def test_no_era_repeats_the_previous_lot():
    """A row that does not change the lot is noise in the table."""
    for underlying, entries in lots.LOT_HISTORY.items():
        sizes = [s for _, s in entries]
        assert all(a != b for a, b in zip(sizes, sizes[1:])), \
            f"{underlying} has a redundant entry"


def test_every_lot_is_a_positive_whole_number():
    for entries in lots.LOT_HISTORY.values():
        assert all(isinstance(s, int) and s > 0 for _, s in entries)


def test_coverage_reports_where_the_table_starts():
    start = lots.coverage_start("NIFTY")

    assert start <= date(2024, 1, 2)
    with pytest.raises(lots.NoLotSize):
        lots.lot_size_on("NIFTY", start.replace(year=start.year - 1))


def test_banknifty_is_covered_too():
    assert lots.lot_size_on("BANKNIFTY", date(2026, 4, 1)) == 30
