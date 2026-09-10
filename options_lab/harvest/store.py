"""Parquet bar store, partitioned as one file per (underlying, trade_date).

Layout:  <root>/bars/<UNDERLYING>/<YYYY-MM-DD>.parquet

A day-partition is the unit a backtest reads ("what did the whole chain look
like at 11:47 on this date"), and the unit a harvest writes. Writes are an
upsert keyed on (contract_id, ts), so a retried or overlapping fetch converges
instead of double-counting.

Every row carries `source` and `fetched_at`. Numbers from three feeds with
different OI cadences end up in the same table, and a result that cannot say
where its rows came from is not a result.
"""
from __future__ import annotations

import os
from datetime import date, datetime
from pathlib import Path

import pandas as pd

from options_lab import units
from options_lab.units import UnitsMismatch  # re-exported: raised from write_day

SOURCES = tuple(units.SOURCE_UNITS)
KEY = ["contract_id", "ts"]

# Quantities normalised to contracts at ingest; `qty_units` records that so a
# later reader never has to infer it from `source`.
QTY_COLUMNS = ("open_interest", "volume")

COLUMNS = [
    "contract_id", "instrument_key", "underlying", "expiry", "strike", "right",
    "lot_size", "ts", "open", "high", "low", "close", "volume", "open_interest",
    "source", "qty_units", "fetched_at",
]

IST = "Asia/Kolkata"


class WrongDay(ValueError):
    """Rows carry timestamps outside the day they are being written under."""


def _path(root: Path, underlying: str, day: date) -> Path:
    return Path(root) / "bars" / underlying / f"{day:%Y-%m-%d}.parquet"


def _to_contracts(bars: pd.DataFrame, source: str) -> pd.DataFrame:
    """Normalise OI and volume to contracts, one lot size at a time.

    Raises UnitsMismatch rather than storing a quantity that is not a whole
    number of lots - divisibility is the evidence the convention is right.
    """
    src_units = units.SOURCE_UNITS[source]
    if src_units == units.CONTRACTS or bars.empty:
        return bars

    for lot, idx in bars.groupby("lot_size").groups.items():
        for col in QTY_COLUMNS:
            bars.loc[idx, col] = units.to_contracts(
                bars.loc[idx, col], lot_size=int(lot), src_units=src_units
            )
    return bars


def empty_frame() -> pd.DataFrame:
    return pd.DataFrame({c: pd.Series(dtype="object") for c in COLUMNS})


def write_day(
    root: Path,
    underlying: str,
    day: date,
    bars: pd.DataFrame,
    *,
    source: str,
    fetched_at: datetime,
) -> Path:
    """Upsert `bars` into the (underlying, day) partition. Idempotent."""
    if source not in SOURCES:
        raise ValueError(f"unknown source {source!r}; expected one of {SOURCES}")

    if not bars.empty:
        days = pd.to_datetime(bars["ts"]).dt.tz_convert(IST).dt.date.unique()
        if set(days) - {day}:
            raise WrongDay(f"rows for {sorted(set(days))} written under {day}")

    incoming = _to_contracts(bars.copy(), source)
    incoming["source"] = source
    incoming["qty_units"] = units.CONTRACTS
    incoming["fetched_at"] = pd.Timestamp(fetched_at)

    path = _path(root, underlying, day)
    # An empty write must not create a partition: harvested_days() would then
    # report the day as collected when it holds no bars at all.
    if incoming.empty and not path.exists():
        return path
    path.parent.mkdir(parents=True, exist_ok=True)

    if path.exists():
        merged = pd.concat([pd.read_parquet(path), incoming], ignore_index=True)
    else:
        merged = incoming

    # Always deduplicate, not only when merging with an existing partition: a
    # batched write can carry the same (contract_id, ts) twice if a contract was
    # retried inside the batch. Before batching that was unreachable.
    merged = (merged.drop_duplicates(subset=KEY, keep="last")
                    .sort_values(KEY).reset_index(drop=True))

    # Write to a sibling temp file then rename. os.replace is atomic on the same
    # filesystem, so an interrupted harvest can never leave a half-written
    # partition that later reads as a valid-but-truncated parquet.
    tmp = path.with_suffix(".parquet.tmp")
    merged[COLUMNS].to_parquet(tmp, index=False)
    os.replace(tmp, path)
    return path


def read_day(root: Path, underlying: str, day: date) -> pd.DataFrame:
    path = _path(root, underlying, day)
    if not path.exists():
        return empty_frame()
    return pd.read_parquet(path)


def harvested_days(root: Path, underlying: str) -> list[date]:
    """Which days have already been collected for this underlying."""
    folder = Path(root) / "bars" / underlying
    if not folder.exists():
        return []
    return sorted(
        date.fromisoformat(p.stem) for p in folder.glob("*.parquet")
    )
