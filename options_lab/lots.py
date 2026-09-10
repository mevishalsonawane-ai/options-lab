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

import numpy as np
import pandas as pd

__all__ = ["LOT_HISTORY", "NoLotSize", "lot_size_on", "lot_from_chain",
           "coverage_start", "MIN_MOVE_AGREEMENT"]

# The share of non-zero OI moves that must be whole lots before the modal
# move is believed. Measured over the 170 cached sessions: median 1.0000,
# minimum 0.9867. A raw gcd would be exact and useless - one stray tick
# collapses it (2026-03-30 gcd 5 against a true lot of 65).
MIN_MOVE_AGREEMENT = 0.95

# How many of the most frequent move sizes to reconcile.
TOP_MOVE_SIZES = 5




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


def lot_from_chain(chain: pd.DataFrame, *,
                   min_agreement: float = MIN_MOVE_AGREEMENT) -> int | None:
    """The lot the contracts in THIS chain carry, read off open interest.

    The date table is not the last word. NSE applies a lot change to contracts
    INTRODUCED after it, so a monthly listed before the change keeps the old lot
    until it expires. On 2025-01-30 every NIFTY contract expiring that day
    carried 25 while the rest of the book was at 75 - the table says 75, and a
    trade sized on that is three times too large.

    Upstox publishes OI already multiplied by the lot, so the most common
    non-zero OI move IS one lot. The modal move is used rather than the gcd
    because the gcd is exact and therefore fragile: a single non-conforming
    tick collapses it. The modal move is then required to divide nearly every
    other move, so a wrong guess cannot pass quietly.

    Returns None when the chain carries no evidence - no OI column, no moves,
    or too much disagreement. None means "ask something else", never "lot 1".
    """
    if "open_interest" not in chain or chain.empty:
        return None
    moves = (chain.sort_values("ts")
                  .groupby(["strike", "right"])["open_interest"].diff().dropna())
    moves = moves[moves != 0].abs().astype("int64")
    if moves.empty:
        return None
    # The gcd of the most FREQUENT move sizes. Plain gcd over every move is
    # exact and therefore fragile - one stray tick collapses it (2026-03-30
    # gcds to 5 against a true lot of 65). Ranking by count, not by share of
    # volume: a real session has ~4,000 distinct move sizes and the top five
    # are only 11% of the mass, but they are 65, 130, 195, 260, 325 - every one
    # a whole number of lots. The result is stable for any k from 1 to 12.
    common = moves.value_counts().index[:TOP_MOVE_SIZES].to_numpy()
    lot = int(np.gcd.reduce(common))
    if lot <= 1:
        return None
    if float((moves % lot == 0).mean()) < min_agreement:
        return None
    return lot
