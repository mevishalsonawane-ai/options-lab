"""Provenance for the cached expiry chains.

170 parquet files sit in `options_lab/data/expiry_cache` and every published
figure in this repo is computed from them. Until now nothing recorded where
they came from, when they were fetched, or what was filtered out - so a
silently edited, truncated or half-rebuilt file would move every result with no
trace at all. That is the same class of failure as the pinned lot size: an
answer that stays plausible while its input quietly changes.

Two rules shape this module.

FACTS FROM THE FILE, NOT FROM THE FILESYSTEM. Content hash, row count, strike
count, time span and settlement coverage are all recomputed from the parquet.
The filesystem mtime is deliberately NOT used as a fetch date: these files were
copied into this repo on 2026-09-10, which is a copy date. Recording it as a
fetch date would be a fabricated fact that looks exactly like a verified one.

UNKNOWN IS RECORDED AS UNKNOWN. `source` must be declared. `fetched_on` may be
None, and then it stays None and reports as unknown. Nothing is inferred.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import pandas as pd

from options_lab import lots as lot_table

MANIFEST = "PROVENANCE.csv"
PATTERN = "*_chain.parquet"
CHUNK = 1 << 20

# The window NSE settles on. A session with no bars here cannot be priced at
# all, so knowing the count in advance turns a mystery skip into a known gap.
SETTLE_FROM, SETTLE_TO = 15, 15        # hour 15, minutes 00-29

# How the open_interest column is denominated. The harvest store normalises
# to contracts and stamps qty_units; this cache predates that firewall and
# carries no such column, so the units are INFERRED by divisibility and
# recorded here. Getting this wrong is what the 65x/30x splice trap was.
LOT_MULTIPLIED = "lot_multiplied"
CONTRACTS = "contracts"
INDETERMINATE = "indeterminate"

COLUMNS = ["session", "file", "sha256", "n_rows", "n_strikes",
           "first_ts", "last_ts", "n_settlement_bars", "oi_lot", "oi_units",
           "source", "fetched_on"]


class BadCacheFile(ValueError):
    """A file in the cache directory that is not a dated session chain."""


class UnknownProvenance(ValueError):
    """The source was not declared. An undeclared source is not provenance."""


@dataclass(frozen=True)
class Drift:
    kind: str            # changed | missing | unrecorded
    session: date
    why: str


def _session_of(path: Path) -> date:
    stem = path.name.split("_")[0]
    try:
        return date.fromisoformat(stem)
    except ValueError:
        raise BadCacheFile(
            f"{path.name} does not start with an ISO session date; the cache "
            f"holds only <YYYY-MM-DD>_chain.parquet files"
        ) from None


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def fingerprint(path: Path) -> dict:
    """Everything about one cached session that can be checked from the file."""
    path = Path(path)
    session = _session_of(path)
    df = pd.read_parquet(path)
    ts = pd.to_datetime(df["ts"])
    settle = ts[(ts.dt.hour == SETTLE_FROM) & (ts.dt.minute < 30)]
    return {
        "session": session,
        "file": path.name,
        "sha256": _sha256(path),
        "n_rows": int(len(df)),
        "n_strikes": int(df["strike"].nunique()),
        "first_ts": ts.min(),
        "last_ts": ts.max(),
        "n_settlement_bars": int(settle.nunique()),
    }


def infer_oi_units(path: Path, *, lot: int) -> str:
    """Is open_interest denominated in contracts, or already times the lot?

    The evidence is divisibility: Upstox publishes OI lot-multiplied, so every
    non-zero move is a whole number of lots. This is the same test the harvest
    store uses at ingest, applied to a cache that predates that firewall.

    No movement means no evidence, and no evidence is recorded as indeterminate
    rather than defaulted to either answer.
    """
    df = pd.read_parquet(path)
    if lot is None or lot <= 1 or "open_interest" not in df:
        return INDETERMINATE
    moves = (df.sort_values("ts")
               .groupby(["strike", "right"])["open_interest"].diff().dropna())
    moves = moves[moves != 0]
    if moves.empty:
        return INDETERMINATE
    # Nearly all, not all. Real sessions run 98.7% divisible, and demanding
    # perfection labelled three genuine Upstox files as contract-denominated -
    # the same fragility that collapsed a raw gcd to 5.
    agreement = float((moves % lot == 0).mean())
    return (LOT_MULTIPLIED if agreement >= lot_table.MIN_MOVE_AGREEMENT
            else CONTRACTS)


def build(cache: Path, *, source: str, fetched_on: date | None = None,
          underlying: str = "NIFTY") -> pd.DataFrame:
    """Fingerprint every session in the cache and stamp the declared source."""
    if not source:
        raise UnknownProvenance(
            "source must be declared; an undeclared source is not provenance. "
            "Name the dataset, e.g. 'upstox-v3-historical-candle'."
        )
    rows = []
    for path in sorted(Path(cache).glob(PATTERN)):
        fp = fingerprint(path)
        # The chain's own OI moves name the lot, and they beat the date table:
        # a contract listed before a lot change keeps the old lot until it
        # expires, and 2023 has no table coverage at all.
        lot = lot_table.lot_from_chain(pd.read_parquet(path))
        if lot is None:
            try:
                lot = lot_table.lot_size_on(underlying, fp["session"])
            except lot_table.NoLotSize:
                lot = None      # no divisor from either source, so no verdict
        rows.append(fp | {"oi_lot": lot,
                          "oi_units": infer_oi_units(path, lot=lot),
                          "source": source, "fetched_on": fetched_on})
    return pd.DataFrame(rows, columns=COLUMNS)


def write(cache: Path, manifest: pd.DataFrame) -> Path:
    path = Path(cache) / MANIFEST
    manifest.sort_values("session").to_csv(path, index=False)
    return path


def read(cache: Path) -> pd.DataFrame:
    path = Path(cache) / MANIFEST
    if not path.exists():
        raise FileNotFoundError(
            f"no {MANIFEST} in {cache}; the cache has no recorded provenance. "
            f"Build one with `options_lab.provenance.build`."
        )
    df = pd.read_csv(path, parse_dates=["first_ts", "last_ts"])
    df["session"] = pd.to_datetime(df["session"]).dt.date
    df["fetched_on"] = pd.to_datetime(df["fetched_on"], errors="coerce")
    df["fetched_on"] = df["fetched_on"].dt.date.where(df["fetched_on"].notna())
    return df


def verify(cache: Path) -> list[Drift]:
    """Re-hash the cache against its manifest. Empty list means unchanged.

    An UNRECORDED file is the dangerous case and is reported, not absorbed:
    results would silently include data nobody declared.
    """
    cache = Path(cache)
    manifest = read(cache)
    recorded = dict(zip(manifest["file"], manifest["sha256"]))
    on_disk = {p.name: p for p in sorted(cache.glob(PATTERN))}

    drift: list[Drift] = []
    for name, want in recorded.items():
        path = on_disk.get(name)
        if path is None:
            drift.append(Drift(
                "missing", _session_of(cache / name),
                f"{name} is in the manifest but not on disk; every result "
                f"computed since was over a smaller sample"))
            continue
        got = _sha256(path)
        if got != want:
            drift.append(Drift(
                "changed", _session_of(path),
                f"{name} no longer hashes to its recorded content "
                f"({want[:12]} -> {got[:12]}); results over it are not "
                f"comparable to published ones"))
    for name, path in on_disk.items():
        if name not in recorded:
            drift.append(Drift(
                "unrecorded", _session_of(path),
                f"{name} is on disk but was never recorded; results include "
                f"data whose origin is undeclared"))
    return sorted(drift, key=lambda d: (d.session, d.kind))
