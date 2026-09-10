"""Dated lot-size history for index options.

Every rupee figure in this project is an index-point figure multiplied by a lot
size, and NIFTY's lot changed three times inside the backtest sample: 50, then
25, then 75, then 65. Running a 2024 session at today's 65 scales that session's
P&L by 2.6x, and because brokerage is a flat Rs 20/order rather than a
percentage, it also moves the cost fraction - so it is not even a clean rescale.

The table below is READ FROM NSE BHAVCOPY, not assumed. Each date is the first
trading session on which the NEAREST expiry carried the new lot, bisected from
`NewBrdLotQty`. Nearest expiry specifically: NSE revises lots prospectively, so
a single bhavcopy can list two lots at once while already-introduced far
expiries run out their old size. The expiry put trades the nearest series, so
that is the series whose lot governs it.

Dates before coverage RAISE rather than extrapolate backwards. A silently wrong
lot is exactly the class of error this project keeps paying for, and a loud
failure on 12 sessions is cheaper than a quiet 2.6x error on all of them.

Index-point results never touch this module. Only rupee figures do.
"""
from __future__ import annotations

from datetime import date

__all__ = ["LOT_HISTORY", "NoLotSize", "lot_size_on", "coverage_start"]


class NoLotSize(LookupError):
    """No verified lot size covers this (underlying, date).

    Deliberately not a fallback. Callers that hit this are asking for a rupee
    figure the data cannot support, and should either narrow the date range or
    report in index points.
    """


# (first session the nearest expiry carried this lot, lot size).
# Sources are NSE F&O bhavcopy NewBrdLotQty; see docs/lot-history.md.
LOT_HISTORY: dict[str, list[tuple[date, int]]] = {
    "NIFTY": [
        (date(2024, 1, 1), 50),     # earliest UDiFF bhavcopy carrying the field
        (date(2024, 4, 26), 25),
        (date(2024, 12, 27), 75),   # 198 contracts still at 25 that day, 1361 at 75
        (date(2025, 12, 31), 65),
    ],
    "BANKNIFTY": [
        (date(2024, 1, 1), 15),
        (date(2025, 1, 31), 30),
    ],
}

# COVERAGE STARTS 2024-01-01, and the expiry sample starts 2023-01-05. The
# UDiFF bhavcopy is the earliest NSE file that publishes NewBrdLotQty at all;
# the legacy fo<DD><MON><YYYY>bhav.csv format has no lot column, so 2023 lots
# cannot be verified from the same source. Those sessions are therefore SKIPPED
# under DATED_LOT rather than run at an assumed 50. That loses roughly a third
# of the sample - which is the honest cost of not knowing, and is visible in
# the skipped count instead of hidden in the rupee totals.


def _entries(underlying: str) -> list[tuple[date, int]]:
    try:
        return LOT_HISTORY[underlying.upper()]
    except KeyError:
        raise NoLotSize(
            f"no lot history for {underlying!r}; known: "
            f"{', '.join(sorted(LOT_HISTORY))}"
        ) from None


def lot_size_on(underlying: str, day: date) -> int:
    """The lot the nearest expiry carried on `day`.

    Forward extrapolation past the last row is intentional and safe: the
    current lot IS current until NSE changes it, and monitor.check_lot_size
    exists precisely to catch that change against a live feed. Backward
    extrapolation is refused, because there the answer is simply unknown.
    """
    entries = _entries(underlying)
    latest = None
    for effective, lot in entries:
        if effective <= day:
            latest = lot
        else:
            break
    if latest is None:
        raise NoLotSize(
            f"{underlying.upper()} lot history starts {entries[0][0]}; "
            f"{day} is before it. Verify the lot from NSE bhavcopy and extend "
            f"LOT_HISTORY rather than assuming a value."
        )
    return latest


def coverage_start(underlying: str) -> date:
    """First date the table can answer for."""
    return _entries(underlying)[0][0]
