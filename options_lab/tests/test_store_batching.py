"""Batched writes must equal per-contract writes.

The harvester originally called write_day once per contract, and write_day is a
read-modify-write of the whole day partition. With 502 contracts x 20 sessions
that is ~10,000 rewrites of a growing parquet file - quadratic, and measured
stalling a live run at 375/502.

Batching fixes it, but only if it is exactly equivalent. This locks that.
"""
from __future__ import annotations

from datetime import date, datetime, timezone

import pandas as pd

from options_lab.harvest import store

FETCHED = datetime(2026, 9, 7, 20, 0, tzinfo=timezone.utc)
DAY = date(2026, 9, 4)


def _contract(cid: str, strike: float, oi_base: int, n: int = 3):
    lot = 65
    return pd.DataFrame({
        "contract_id": [cid] * n,
        "instrument_key": [f"NSE_FO|{int(strike)}"] * n,
        "underlying": ["NIFTY"] * n,
        "expiry": [date(2026, 9, 8)] * n,
        "strike": [strike] * n,
        "right": ["CE"] * n,
        "lot_size": [lot] * n,
        "ts": pd.date_range("2026-09-04 09:15", periods=n, freq="1min",
                            tz="Asia/Kolkata"),
        "open": [10.0] * n, "high": [11.0] * n, "low": [9.0] * n,
        "close": [10.5] * n,
        "volume": [lot * (i + 1) for i in range(n)],
        "open_interest": [lot * (oi_base + i) for i in range(n)],
    })


CONTRACTS = [
    _contract("NIFTY|2026-09-08|24000|CE", 24000.0, 1000),
    _contract("NIFTY|2026-09-08|24050|CE", 24050.0, 2000),
    _contract("NIFTY|2026-09-08|24100|CE", 24100.0, 3000),
]


def test_writing_contracts_one_at_a_time_equals_writing_them_together(tmp_path):
    one_by_one = tmp_path / "seq"
    for frame in CONTRACTS:
        store.write_day(one_by_one, "NIFTY", DAY, frame,
                        source="upstox", fetched_at=FETCHED)

    batched = tmp_path / "batch"
    store.write_day(batched, "NIFTY", DAY, pd.concat(CONTRACTS, ignore_index=True),
                    source="upstox", fetched_at=FETCHED)

    a = store.read_day(one_by_one, "NIFTY", DAY).reset_index(drop=True)
    b = store.read_day(batched, "NIFTY", DAY).reset_index(drop=True)

    pd.testing.assert_frame_equal(a, b)


def test_batched_write_preserves_every_contract(tmp_path):
    store.write_day(tmp_path, "NIFTY", DAY, pd.concat(CONTRACTS, ignore_index=True),
                    source="upstox", fetched_at=FETCHED)

    got = store.read_day(tmp_path, "NIFTY", DAY)

    assert got["contract_id"].nunique() == 3
    assert len(got) == 9


def test_a_batch_that_repeats_a_contract_still_deduplicates(tmp_path):
    """A retried contract inside one batch must not double its rows."""
    doubled = pd.concat(CONTRACTS + [CONTRACTS[0]], ignore_index=True)

    store.write_day(tmp_path, "NIFTY", DAY, doubled,
                    source="upstox", fetched_at=FETCHED)

    got = store.read_day(tmp_path, "NIFTY", DAY)

    assert len(got) == 9
