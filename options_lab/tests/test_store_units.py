"""The store normalises quantities to CONTRACTS at ingest.

Upstox publishes open interest and volume in lot-multiplied units; NSE and the
Kaggle archive publish contracts. If both land in the store raw, the splice
carries a 65x (NIFTY) / 30x (BANKNIFTY) step change - which, landing on a split
boundary, is indistinguishable from a strategy decaying out of sample.
"""
from __future__ import annotations

from datetime import date, datetime, timezone

import pandas as pd
import pytest

from options_lab.harvest import store

FETCHED = datetime(2026, 9, 7, 20, 0, tzinfo=timezone.utc)


def _rows(*, oi, volume, lot_size=65, n=1):
    return pd.DataFrame({
        "contract_id": ["NIFTY|2026-09-08|24050|CE"] * n,
        "instrument_key": ["NSE_FO|50979"] * n,
        "underlying": ["NIFTY"] * n,
        "expiry": [date(2026, 9, 8)] * n,
        "strike": [24050.0] * n,
        "right": ["CE"] * n,
        "lot_size": [lot_size] * n,
        "ts": pd.date_range("2026-09-04 09:15", periods=n, freq="1min",
                            tz="Asia/Kolkata"),
        "open": [58.0] * n, "high": [59.0] * n,
        "low": [57.0] * n, "close": [58.5] * n,
        "volume": [volume] * n,
        "open_interest": [oi] * n,
    })


def test_upstox_quantities_are_divided_into_contracts_on_write(tmp_path):
    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4),
                    _rows(oi=7_896_005, volume=2_566_200),
                    source="upstox", fetched_at=FETCHED)

    got = store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))

    assert got["open_interest"].tolist() == [121_477]
    assert got["volume"].tolist() == [39_480]


def test_contract_denominated_sources_are_stored_unchanged(tmp_path):
    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4),
                    _rows(oi=121_477, volume=39_480),
                    source="kaggle", fetched_at=FETCHED)

    got = store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))

    assert got["open_interest"].tolist() == [121_477]


def test_the_two_sources_agree_after_normalisation(tmp_path):
    """The whole point: the same real quantity from either feed lands equal."""
    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4),
                    _rows(oi=7_896_005, volume=2_566_200),
                    source="upstox", fetched_at=FETCHED)
    upstox_oi = store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))["open_interest"]

    store.write_day(tmp_path, "BANKNIFTY", date(2026, 9, 4),
                    _rows(oi=121_477, volume=39_480),
                    source="kaggle", fetched_at=FETCHED)
    kaggle_oi = store.read_day(tmp_path, "BANKNIFTY", date(2026, 9, 4))["open_interest"]

    assert upstox_oi.tolist() == kaggle_oi.tolist()


def test_a_non_divisible_upstox_quantity_is_refused_rather_than_stored(tmp_path):
    """Divisibility is the evidence the convention is right. Losing it is fatal."""
    with pytest.raises(store.UnitsMismatch):
        store.write_day(tmp_path, "NIFTY", date(2026, 9, 4),
                        _rows(oi=7_896_004, volume=2_566_200),
                        source="upstox", fetched_at=FETCHED)


def test_index_rows_pass_through_unscaled(tmp_path):
    """Index bars carry lot_size 1 and zero quantities; conversion is a no-op."""
    ix = _rows(oi=0, volume=0, lot_size=1)
    ix["right"] = "IX"
    ix["contract_id"] = "NIFTY|2099-01-01|0|IX"

    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4), ix,
                    source="upstox", fetched_at=FETCHED)

    got = store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))
    assert got["open_interest"].tolist() == [0]


def test_stored_rows_record_the_normalised_convention(tmp_path):
    store.write_day(tmp_path, "NIFTY", date(2026, 9, 4),
                    _rows(oi=7_896_005, volume=2_566_200),
                    source="upstox", fetched_at=FETCHED)

    got = store.read_day(tmp_path, "NIFTY", date(2026, 9, 4))

    assert set(got["qty_units"]) == {"contracts"}
