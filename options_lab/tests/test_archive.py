"""Kaggle archive member addressing.

The archive is 4.10 GB but its central directory reads in three HTTP Range
requests, so individual sessions can be pulled without downloading it. What
this module has to get right is which member holds which (underlying, session)
- the two underlyings live in differently-named folders and there is no field
inside the file that says which is which.
"""
from __future__ import annotations

from datetime import date

import pytest

from options_lab.backfill import archive

NAMES = [
    "BANK_filtered_feather_folder/BANK_filtered_feather_folder/.conversion_index.json",
    "BANK_filtered_feather_folder/BANK_filtered_feather_folder/2023-01-02-index-nfo-data.feather",
    "BANK_filtered_feather_folder/BANK_filtered_feather_folder/2026-02-23-index-nfo-data.feather",
    "filtered_feather_folder/filtered_feather_folder/2023-01-02-index-nfo-data.feather",
    "filtered_feather_folder/filtered_feather_folder/2026-04-13-index-nfo-data.feather",
    "filtered_feather_folder/filtered_feather_folder/desktop.ini",
    "filtered_feather_folder/filtered_feather_folder/read_feather.py",
]


def test_member_path_for_nifty_uses_the_unprefixed_folder():
    got = archive.member_path("NIFTY", date(2023, 1, 2))

    assert got == ("filtered_feather_folder/filtered_feather_folder/"
                   "2023-01-02-index-nfo-data.feather")


def test_member_path_for_banknifty_uses_the_bank_folder():
    got = archive.member_path("BANKNIFTY", date(2026, 2, 23))

    assert got.startswith("BANK_filtered_feather_folder/")
    assert got.endswith("2026-02-23-index-nfo-data.feather")


def test_the_two_underlyings_never_resolve_to_the_same_member():
    day = date(2023, 1, 2)

    assert archive.member_path("NIFTY", day) != archive.member_path("BANKNIFTY", day)


def test_an_unknown_underlying_is_refused():
    with pytest.raises(ValueError):
        archive.member_path("FINNIFTY", date(2023, 1, 2))


def test_available_days_lists_only_that_underlying_s_sessions():
    assert archive.available_days(NAMES, "NIFTY") == [
        date(2023, 1, 2), date(2026, 4, 13)]
    assert archive.available_days(NAMES, "BANKNIFTY") == [
        date(2023, 1, 2), date(2026, 2, 23)]


def test_available_days_ignores_non_session_members():
    """The archive carries a json index, a desktop.ini and a helper script."""
    days = archive.available_days(NAMES, "NIFTY")

    assert all(isinstance(d, date) for d in days)
    assert len(days) == 2


def test_available_days_is_sorted():
    shuffled = list(reversed(NAMES))

    assert archive.available_days(shuffled, "NIFTY") == sorted(
        archive.available_days(shuffled, "NIFTY"))
