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

import pandas as pd
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


# --- reading the lot off the chain itself ----------------------------------
"""The date table is not the last word, because NSE applies a lot change to
contracts INTRODUCED after it. A monthly listed before the change keeps the old
lot until it expires. On 2025-01-30 every contract expiring that day carried 25
while the rest of the NIFTY book was at 75 - so the table's answer (75) is
wrong for the trade that session, by a factor of three.

Open interest settles it. Upstox publishes OI lot-multiplied, so the most
common OI move IS one lot, and it must divide nearly every other move."""


def _oi_chain(lot, *, n=200, noise=0):
    """A chain whose OI moves in whole lots, plus `noise` stray moves."""
    import numpy as np
    rng = np.random.default_rng(0)
    rows, oi = [], 10_000
    steps = list(rng.integers(1, 12, n) * lot) + [7] * noise
    for i, step in enumerate(steps):
        oi += int(step)
        rows.append({"ts": pd.Timestamp("2025-01-30 09:15", tz="Asia/Kolkata")
                     + pd.Timedelta(minutes=i),
                     "strike": 23_000.0, "right": "PE", "close": 10.0,
                     "open_interest": oi})
    return pd.DataFrame(rows)


def test_the_lot_is_read_off_the_open_interest_moves():
    assert lots.lot_from_chain(_oi_chain(75)) == 75


def test_a_few_stray_moves_do_not_derail_it():
    """A raw gcd collapses to 5 on one bad tick. 2026-03-30 has 152 stray moves
    out of 11,401 and its true lot is 65."""
    assert lots.lot_from_chain(_oi_chain(65, n=400, noise=4)) == 65


def test_too_much_disagreement_returns_no_answer_rather_than_a_wrong_one():
    assert lots.lot_from_chain(_oi_chain(65, n=20, noise=60)) is None


def test_a_chain_with_no_open_interest_column_returns_no_answer():
    chain = _oi_chain(75).drop(columns=["open_interest"])

    assert lots.lot_from_chain(chain) is None


def test_a_chain_whose_open_interest_never_moves_returns_no_answer():
    """No evidence is not evidence for a lot of one."""
    flat = _oi_chain(75)
    flat["open_interest"] = 10_000

    assert lots.lot_from_chain(flat) is None


def test_the_observed_lot_beats_the_table_where_they_disagree():
    """This is the whole point: the chain is the primary source and the table
    is the fallback, not the other way round."""
    observed = lots.lot_from_chain(_oi_chain(25))

    assert observed == 25
    assert lots.lot_size_on("NIFTY", date(2025, 1, 30)) == 75
