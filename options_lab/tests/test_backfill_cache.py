"""Turning archive sessions into expiry-cache chains.

backfill/archive.py could read any session out of the 4.1 GB Kaggle archive and
kaggle_map.py could shape it, but nothing connected them to the cache the
strategy actually reads. The expiry cache could not be rebuilt or extended to
BANKNIFTY.

Two things make this more than plumbing.

THE CACHE SCHEMA HAS NO EXPIRY COLUMN. An archive session carries every listed
expiry. Writing them all into one chain file would let run_session's
groupby(strike, right) silently merge two different contracts at the same
strike - the same defect already found in signal_backtest. One expiry per file,
chosen explicitly.

THE ARCHIVE AND THE LIVE CACHE DISAGREE ON UNITS. Kaggle publishes OI in
contracts; Upstox publishes it already multiplied by the lot. Splicing them
into one directory is exactly the 65x/30x trap this project has paid for once.
A cache directory carries one source, and writing a second is refused.
"""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from options_lab import provenance as prov
from options_lab.backfill import cache as bc


def _bars(day="2024-10-03", expiries=("2024-10-03",), n_strikes=4):
    rows = []
    for exp in expiries:
        for i in range(n_strikes):
            for right in ("CE", "PE"):
                for m in range(3):
                    rows.append({
                        "underlying": "NIFTY",
                        "expiry": date.fromisoformat(exp),
                        "strike": 24_000.0 + 50 * i,
                        "right": right,
                        "ts": pd.Timestamp(f"{day} 09:{15+m}"),
                        "close": 10.0 + i,
                        "volume": 100,
                        "open_interest": 500,
                        "dte": (date.fromisoformat(exp)
                                - date.fromisoformat(day)).days,
                    })
    return pd.DataFrame(rows)


# --- shaping ---------------------------------------------------------------
def test_a_chain_carries_the_cache_columns_and_nothing_else():
    chain = bc.chain_frame(_bars(), day=date(2024, 10, 3))

    assert list(chain.columns) == bc.CACHE_COLUMNS


def test_only_one_expiry_reaches_the_file():
    """The cache schema has no expiry column, so two expiries at one strike
    would be silently merged by any groupby(strike, right)."""
    bars = _bars(expiries=("2024-10-03", "2024-10-10"))

    chain = bc.chain_frame(bars, day=date(2024, 10, 3))

    assert len(chain) == len(bars) // 2


def test_the_expiring_series_is_the_one_kept():
    bars = _bars(expiries=("2024-10-10", "2024-10-03"))

    chain = bc.chain_frame(bars, day=date(2024, 10, 3))

    assert len(chain) == len(bars) // 2
    assert chain["close"].notna().all()


def test_a_session_with_nothing_expiring_is_refused_not_silently_emptied():
    bars = _bars(day="2024-10-03", expiries=("2024-10-10",))

    with pytest.raises(bc.NoExpiringSeries):
        bc.chain_frame(bars, day=date(2024, 10, 3))


def test_an_empty_session_is_refused():
    with pytest.raises(bc.NoExpiringSeries):
        bc.chain_frame(_bars().iloc[:0], day=date(2024, 10, 3))


# --- the units firewall ----------------------------------------------------
def test_writing_into_an_empty_directory_is_allowed(tmp_path):
    path = bc.write_session(tmp_path, date(2024, 10, 3),
                            bc.chain_frame(_bars(), day=date(2024, 10, 3)),
                            source=bc.KAGGLE_SOURCE)

    assert path.exists()
    assert path.name == "2024-10-03_chain.parquet"


def test_a_second_source_in_one_cache_directory_is_refused(tmp_path):
    """Kaggle OI is in contracts, Upstox OI is lot-multiplied. Splicing them is
    the 65x/30x trap that already cost this project once."""
    chain = bc.chain_frame(_bars(), day=date(2024, 10, 3))
    bc.write_session(tmp_path, date(2024, 10, 3), chain,
                     source="upstox-v3-historical-candle")

    with pytest.raises(bc.SourceConflict):
        bc.write_session(tmp_path, date(2024, 10, 10), chain,
                         source=bc.KAGGLE_SOURCE)


def test_the_same_source_may_be_added_to(tmp_path):
    chain = bc.chain_frame(_bars(), day=date(2024, 10, 3))
    bc.write_session(tmp_path, date(2024, 10, 3), chain, source=bc.KAGGLE_SOURCE)
    bc.write_session(tmp_path, date(2024, 10, 10), chain, source=bc.KAGGLE_SOURCE)

    assert len(prov.read(tmp_path)) == 2


def test_every_written_session_lands_in_the_provenance_manifest(tmp_path):
    chain = bc.chain_frame(_bars(), day=date(2024, 10, 3))
    bc.write_session(tmp_path, date(2024, 10, 3), chain, source=bc.KAGGLE_SOURCE)

    man = prov.read(tmp_path)

    assert man.iloc[0]["source"] == bc.KAGGLE_SOURCE
    assert prov.verify(tmp_path) == []
