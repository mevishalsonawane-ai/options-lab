"""Map an archive session onto the store schema.

Three things must not go wrong here:
  - contract identity is (underlying, expiry, strike, right), matching what the
    Upstox path produces, so the two feeds splice on the same key;
  - archive OI is denominated in CONTRACTS while Upstox is in lot-multiplied
    units, and the store keeps contracts - so this path must NOT rescale;
  - lot size is absent from the archive and NIFTY's has changed over the
    sample, so it is recorded as unknown rather than guessed.
"""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from options_lab.backfill import kaggle_map


def _session():
    ts = pd.date_range("2024-08-22 09:15", periods=2, freq="1min", tz="Asia/Kolkata")
    return pd.DataFrame({
        "date": list(ts) * 3,
        "open": [100.0, 101.0, 50.0, 51.0, 23800.0, 23810.0],
        "high": [102.0, 103.0, 52.0, 53.0, 23820.0, 23830.0],
        "low": [99.0, 100.0, 49.0, 50.0, 23790.0, 23800.0],
        "close": [101.0, 102.0, 51.0, 52.0, 23810.0, 23825.0],
        "volume": [1000, 2000, 300, 400, 17160, 38415],
        "oi": [565025, 565100, 12000, 12050, 19098885, 19133530],
        "symbol": ["NIFTY24822 24000CE", "NIFTY24822 24000CE",
                   "NIFTY24822 24500PE", "NIFTY24822 24500PE",
                   "NIFTY24AUGFUT", "NIFTY24AUGFUT"],
        "name": ["NIFTY"] * 6,
        "expiry": [date(2024, 8, 22)] * 4 + [date(2024, 8, 29)] * 2,
        "strike": [24000.0, 24000.0, 24500.0, 24500.0, 0.0, 0.0],
        "instrument_type": ["CE", "CE", "PE", "PE", "FUT", "FUT"],
    })


def test_futures_rows_are_dropped_from_the_option_chain():
    got = kaggle_map.to_store_frame(_session(), "NIFTY")

    assert set(got["right"]) == {"CE", "PE"}
    assert len(got) == 4


def test_contract_id_matches_the_shape_the_upstox_path_produces():
    got = kaggle_map.to_store_frame(_session(), "NIFTY")

    assert set(got["contract_id"]) == {
        "NIFTY|2024-08-22|24000|CE", "NIFTY|2024-08-22|24500|PE"}


def test_open_interest_is_carried_through_unscaled():
    """Archive OI is already in contracts. Rescaling it would be a 65x error."""
    got = kaggle_map.to_store_frame(_session(), "NIFTY")
    ce = got[got["right"] == "CE"]

    assert ce["open_interest"].tolist() == [565025, 565100]


def test_lot_size_is_recorded_as_unknown_rather_than_guessed():
    """NIFTY's lot size changed across 2023-2026; the archive does not carry it."""
    got = kaggle_map.to_store_frame(_session(), "NIFTY")

    assert got["lot_size"].isna().all()


def test_the_broker_instrument_key_is_absent_not_invented():
    got = kaggle_map.to_store_frame(_session(), "NIFTY")

    assert got["instrument_key"].isna().all()


def test_timestamps_stay_tz_aware_ist():
    got = kaggle_map.to_store_frame(_session(), "NIFTY")

    assert str(got["ts"].dt.tz) in ("Asia/Kolkata", "pytz.FixedOffset(330)",
                                    "UTC+05:30")


def test_dte_is_attached_per_row():
    got = kaggle_map.to_store_frame(_session(), "NIFTY")

    assert set(got["dte"]) == {0}


def test_a_session_with_no_options_yields_an_empty_but_shaped_frame():
    futs = _session()
    futs = futs[futs.instrument_type == "FUT"]

    got = kaggle_map.to_store_frame(futs, "NIFTY")

    assert got.empty
    assert "open_interest" in got.columns


def test_mismatched_underlying_is_refused():
    with pytest.raises(ValueError):
        kaggle_map.to_store_frame(_session(), "BANKNIFTY")
