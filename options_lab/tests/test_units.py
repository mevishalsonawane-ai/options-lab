"""The units firewall.

Upstox reports open interest and volume in LOT-MULTIPLIED UNITS; NSE bhavcopy
and the Kaggle archive report open interest in CONTRACTS. Splicing the two
without converting puts a 65x (NIFTY) / 30x (BANKNIFTY) step change in the
data, which reads as a strategy decaying out of sample.

The store keeps one convention: CONTRACTS. Conversion happens once, at ingest,
and refuses to guess.
"""
from __future__ import annotations

import pandas as pd
import pytest

from options_lab import units


def test_units_are_passed_through_when_already_denominated_in_contracts():
    got = units.to_contracts(pd.Series([100, 250]), lot_size=65, src_units="contracts")

    assert got.tolist() == [100, 250]


def test_lot_multiplied_units_are_divided_by_the_lot_size():
    got = units.to_contracts(pd.Series([6500, 26_141_310]), lot_size=65, src_units="units")

    assert got.tolist() == [100, 402_174]


def test_conversion_refuses_values_that_are_not_whole_lots():
    """A non-divisible value means the source convention was misidentified."""
    with pytest.raises(units.UnitsMismatch):
        units.to_contracts(pd.Series([6500, 101]), lot_size=65, src_units="units")


def test_conversion_refuses_an_unknown_source_convention():
    with pytest.raises(ValueError):
        units.to_contracts(pd.Series([1]), lot_size=65, src_units="probably_contracts")


def test_conversion_refuses_a_missing_lot_size_rather_than_defaulting_to_one():
    with pytest.raises(units.NoLotSize):
        units.to_contracts(pd.Series([100]), lot_size=None, src_units="units")


def test_empty_series_converts_without_error():
    assert units.to_contracts(pd.Series([], dtype="int64"),
                              lot_size=65, src_units="units").empty


def test_source_units_records_the_known_convention_per_feed():
    assert units.SOURCE_UNITS["upstox"] == "units"
    assert units.SOURCE_UNITS["kaggle"] == "contracts"
    assert units.SOURCE_UNITS["nse_bhavcopy"] == "contracts"
