"""Parquet bar store: one file per (underlying, trade_date), all contracts in it.

Two properties matter more than anything else here:
  - re-running a day is idempotent, so a retried harvest never double-counts;
  - every row carries its provenance, so six months from now you can tell which
    numbers came from Upstox, which from the Kaggle archive, and when.
"""
from __future__ import annotations

from datetime import date, datetime, timezone

import pandas as pd
import pytest

from options_lab.harvest import store

FETCHED = datetime(2026, 9, 7, 20, 0, tzinfo=timezone.utc)


# Quantities are real lot-multiplied Upstox values: every one is a whole
# multiple of the NIFTY lot size 65, because the store refuses anything else.
OI_UNITS = [7_879_950, 7_896_005]          # -> 121_230, 121_477 contracts
VOL_UNITS = [899_990, 2_566_200]           # ->  13_846,  39_480 contracts
OI_CONTRACTS = [121_230, 121_477]


def _rows(*, close=60.65, oi=7896005, n=2):
    ts = pd.date_range("2026-09-04 09:15", periods=n, freq="1min", tz="Asia/Kolkata")
    return pd.DataFrame({
        "contract_id": ["NIFTY|2026-09-08|24050|CE"] * n,
        "instrument_key": ["NSE_FO|50979"] * n,
        "underlying": ["NIFTY"] * n,
        "expiry": [date(2026, 9, 8)] * n,
        "strike": [24050.0] * n,
        "right": ["CE"] * n,
        "lot_size": [65] * n,
        "ts": ts,
        "open": [58.0, 60.0][:n],
        "high": [59.0, 66.0][:n],
        "low": [57.0, 44.05][:n],
        "close": [58.5, close][:n],
        "volume": VOL_UNITS[:n],
        "open_interest": [OI_UNITS[0], oi][:n],
    })


def test_write_then_read_round_trips_the_bars(tmp_path):
    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4), _rows(),
                    source="upstox", fetched_at=FETCHED)

    got = store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))

    assert len(got) == 2
    assert got["open_interest"].tolist() == OI_CONTRACTS


def test_every_stored_row_carries_its_source_and_fetch_time(tmp_path):
    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4), _rows(),
                    source="upstox", fetched_at=FETCHED)

    got = store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))

    assert set(got["source"]) == {"upstox"}
    assert set(got["fetched_at"]) == {pd.Timestamp(FETCHED)}


def test_rewriting_the_same_day_does_not_duplicate_rows(tmp_path):
    for _ in range(2):
        store.write_day(tmp_path, "NIFTY", date(2026, 9, 4), _rows(),
                        source="upstox", fetched_at=FETCHED)

    assert len(store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))) == 2


def test_rewriting_a_bar_updates_it_rather_than_appending(tmp_path):
    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4), _rows(close=60.65),
                    source="upstox", fetched_at=FETCHED)
    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4), _rows(close=99.99),
                    source="upstox", fetched_at=FETCHED)

    got = store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))

    assert len(got) == 2
    assert got["close"].tolist() == [58.5, 99.99]


def test_reading_a_day_that_was_never_harvested_returns_an_empty_frame(tmp_path):
    got = store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))

    assert got.empty
    assert "open_interest" in got.columns


def test_write_refuses_rows_whose_timestamps_fall_outside_the_named_day(tmp_path):
    """Guards against silently mis-partitioning a chunked multi-day fetch."""
    with pytest.raises(store.WrongDay):
        store.write_day(tmp_path, "NIFTY", date(2026, 9, 3), _rows(),
                        source="upstox", fetched_at=FETCHED)


def test_write_refuses_an_unknown_source_label(tmp_path):
    with pytest.raises(ValueError):
        store.write_day(tmp_path, "NIFTY", date(2026, 9, 4), _rows(),
                        source="guesswork", fetched_at=FETCHED)


def test_harvested_days_lists_what_has_been_collected(tmp_path):
    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4), _rows(),
                    source="upstox", fetched_at=FETCHED)

    assert store.harvested_days(tmp_path, "NIFTY") == [date(2026, 9, 4)]
    assert store.harvested_days(tmp_path, "BANKNIFTY") == []
