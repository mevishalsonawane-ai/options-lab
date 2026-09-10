"""Turn archive sessions into expiry-cache chains.

`archive.py` could read any session out of the 4.1 GB Kaggle archive and
`kaggle_map.py` could shape it, but nothing joined them to the cache the
strategy actually reads, so the expiry cache could not be rebuilt or extended
to BANKNIFTY. This is that join, plus the two guards it needs.

ONE EXPIRY PER FILE. The cache schema carries no expiry column - an archive
session carries every listed expiry, and writing them all into one chain would
let `run_session`'s groupby(strike, right) silently merge two different
contracts quoting the same strike. That defect has already been found once in
this repo (signal_backtest, AmbiguousChain). The expiring series is selected
explicitly and a session with nothing expiring is refused.

ONE SOURCE PER DIRECTORY. Kaggle publishes open interest in contracts; Upstox
publishes it already multiplied by the lot. Splicing the two into one cache is
the 65x/30x units trap this project has paid for once. The provenance manifest
is the enforcement point: a directory that already declares one source refuses
a second.
"""
from __future__ import annotations

from datetime import date
from pathlib import Path

import pandas as pd

from options_lab import provenance as prov

KAGGLE_SOURCE = "kaggle:samardubey/niftybanknifty-options-data"
UPSTOX_SOURCE = "upstox-v3-historical-candle"

# Exactly what the expiry cache holds; see options_lab/data/expiry_cache.
CACHE_COLUMNS = ["ts", "strike", "right", "close", "volume", "open_interest"]


class NoExpiringSeries(ValueError):
    """No contract in this session expires on the session date."""


class SourceConflict(ValueError):
    """This directory already holds chains from a different source."""


def chain_frame(bars: pd.DataFrame, *, day: date) -> pd.DataFrame:
    """Store-shaped bars -> one expiry-cache chain for `day`.

    Keeps only the series expiring ON `day`. Selecting the nearest expiry would
    quietly accept a session where nothing expires and hand back a chain the
    expiry strategy has no business trading.
    """
    if bars.empty:
        raise NoExpiringSeries(f"{day}: session carries no option bars")
    expiring = bars[pd.Series(list(bars["expiry"]), index=bars.index) == day]
    if expiring.empty:
        listed = sorted({str(e) for e in bars["expiry"]})[:4]
        raise NoExpiringSeries(
            f"{day}: nothing expires this session; listed expiries {listed}"
        )
    return expiring[CACHE_COLUMNS].reset_index(drop=True)


def declared_source(cache: Path) -> str | None:
    """The source this cache directory already holds, if any."""
    try:
        manifest = prov.read(cache)
    except FileNotFoundError:
        return None
    sources = {str(s) for s in manifest["source"].dropna()}
    return sources.pop() if len(sources) == 1 else None


def write_session(cache: Path, day: date, chain: pd.DataFrame, *,
                  source: str) -> Path:
    """Write one chain and record it, refusing to mix sources in one cache."""
    cache = Path(cache)
    cache.mkdir(parents=True, exist_ok=True)
    held = declared_source(cache)
    if held is not None and held != source:
        raise SourceConflict(
            f"{cache} already holds {held!r} chains and {source!r} denominates "
            f"open interest differently; splicing them is the 65x/30x units "
            f"trap. Write to a separate directory."
        )
    path = cache / f"{day:%Y-%m-%d}_chain.parquet"
    chain.to_parquet(path, index=False)
    prov.write(cache, prov.build(cache, source=source))
    return path
