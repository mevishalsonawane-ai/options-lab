"""Upstox instrument master: parsing and contract identity.

A contract is identified by (underlying, expiry, strike, right). The broker's
`instrument_key` is deliberately NOT part of that identity: Upstox recycles
tokens once a contract settles, so a stored key can later resolve to a
different underlying's contract without any error being raised.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))

OPTION_RIGHTS = ("CE", "PE")
NSE_FO = "NSE_FO"


class NoSuchUnderlying(LookupError):
    """The master contains no option contracts for the requested underlying."""


def expiry_date(epoch_ms: int) -> date:
    """Millisecond epoch -> IST calendar date. NSE expiries are IST-local."""
    return datetime.fromtimestamp(epoch_ms / 1000, tz=IST).date()


@dataclass(frozen=True)
class Contract:
    underlying: str
    expiry: date
    strike: float
    right: str
    lot_size: int
    instrument_key: str
    trading_symbol: str

    @property
    def contract_id(self) -> str:
        """Stable identity, independent of the recycled broker token."""
        return f"{self.underlying}|{self.expiry:%Y-%m-%d}|{self.strike:g}|{self.right}"

    def is_expired(self, on: date) -> bool:
        """Expiry day itself is tradeable; the contract is dead the day after."""
        return on > self.expiry


def _underlying_of(row: dict) -> str | None:
    return row.get("underlying_symbol") or row.get("asset_symbol")


def parse_options(master: list[dict], underlying: str) -> list[Contract]:
    """Every listed CE/PE contract on `underlying`, in master order."""
    out = [
        Contract(
            underlying=underlying,
            expiry=expiry_date(row["expiry"]),
            strike=float(row["strike_price"]),
            right=row["instrument_type"],
            lot_size=int(row["lot_size"]),
            instrument_key=row["instrument_key"],
            trading_symbol=row["trading_symbol"],
        )
        for row in master
        if row.get("segment") == NSE_FO
        and row.get("instrument_type") in OPTION_RIGHTS
        and _underlying_of(row) == underlying
    ]
    if not out:
        raise NoSuchUnderlying(underlying)
    return out
