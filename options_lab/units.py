"""The units firewall.

Open interest and volume arrive in two different denominations:

    upstox        lot-multiplied UNITS   (verified 2026-09-07: 100% of OI and
                                          volume values divisible by lot_size)
    kaggle        CONTRACTS
    nse_bhavcopy  CONTRACTS

The store keeps exactly one convention - CONTRACTS - and conversion happens
once, at ingest. Splicing feeds without converting puts a 65x (NIFTY) / 30x
(BANKNIFTY) step change into the series, which is indistinguishable from a
strategy decaying out of sample if it lands on a split boundary.

Nothing here guesses. A value that is not a whole number of lots means the
source convention was misidentified, and that is a hard error.
"""
from __future__ import annotations

import pandas as pd

CONTRACTS = "contracts"
UNITS = "units"
CONVENTIONS = (CONTRACTS, UNITS)

# Which denomination each feed publishes. Verified per feed, not assumed.
SOURCE_UNITS = {
    "upstox": UNITS,
    "kaggle": CONTRACTS,
    "nse_bhavcopy": CONTRACTS,
}


class UnitsMismatch(ValueError):
    """Values are not whole lots, so the declared convention must be wrong."""


class NoLotSize(ValueError):
    """No lot size available. Never defaults to 1 - that would silently pass
    lot-multiplied values through as if they were contracts."""


def to_contracts(series: pd.Series, *, lot_size: int | None, src_units: str) -> pd.Series:
    """Convert a quantity series to contracts."""
    if src_units not in CONVENTIONS:
        raise ValueError(f"unknown convention {src_units!r}; expected {CONVENTIONS}")
    if src_units == CONTRACTS:
        return series
    if not lot_size:
        raise NoLotSize("lot size required to convert lot-multiplied units")

    if series.empty:
        return series

    if (series.astype("int64") < 0).any():
        bad = series[series.astype("int64") < 0].head(3).tolist()
        raise UnitsMismatch(
            f"negative quantities {bad}; open interest and volume cannot be "
            f"negative, so the upstream data is corrupt"
        )

    remainder = series.astype("int64") % int(lot_size)
    if (remainder != 0).any():
        bad = series[remainder != 0].head(3).tolist()
        raise UnitsMismatch(
            f"values {bad} are not whole multiples of lot_size {lot_size}; "
            f"the source convention is not {UNITS!r}"
        )
    return series.astype("int64") // int(lot_size)
