"""Session manifest: what was collected, how, and whether the chain is complete.

The distinction this exists to record: a session collected ON the day holds the
whole live chain, while a session reached by BACKFILL holds only the contracts
that still existed on the day the harvester ran. Every session collected on
2026-09-07 for August dates is partial - the front weeklies of those days had
already expired and no free source can return them.

Treating a partial-chain session as complete silently corrupts every
chain-aggregate feature (signed flow, PCR, skew), because the denominator is
missing most of its contracts.
"""
from __future__ import annotations

from datetime import date

import pytest

from options_lab.harvest import manifest


def test_recording_a_session_makes_it_readable_back(tmp_path):
    manifest.record(tmp_path, "NIFTY", date(2026, 9, 4), n_expiries=3,
                    n_contracts=420, scope="same_day", collected_on=date(2026, 9, 4))

    got = manifest.read(tmp_path, "NIFTY")

    assert len(got) == 1
    assert got.loc[0, "n_expiries"] == 3
    assert got.loc[0, "scope"] == "same_day"


def test_a_session_collected_later_than_its_date_is_backfill_not_same_day(tmp_path):
    with pytest.raises(manifest.ScopeMismatch):
        manifest.record(tmp_path, "NIFTY", date(2026, 8, 10), n_expiries=1,
                        n_contracts=24, scope="same_day",
                        collected_on=date(2026, 9, 7))


def test_backfill_scope_is_accepted_for_a_past_session(tmp_path):
    manifest.record(tmp_path, "NIFTY", date(2026, 8, 10), n_expiries=1,
                    n_contracts=24, scope="backfill", collected_on=date(2026, 9, 7))

    assert manifest.read(tmp_path, "NIFTY").loc[0, "scope"] == "backfill"


def test_recording_the_same_session_twice_updates_rather_than_appends(tmp_path):
    for n in (24, 136):
        manifest.record(tmp_path, "NIFTY", date(2026, 8, 10), n_expiries=1,
                        n_contracts=n, scope="backfill", collected_on=date(2026, 9, 7))

    got = manifest.read(tmp_path, "NIFTY")

    assert len(got) == 1
    assert got.loc[0, "n_contracts"] == 136


def test_complete_chain_sessions_excludes_backfilled_ones(tmp_path):
    manifest.record(tmp_path, "NIFTY", date(2026, 9, 4), n_expiries=3, n_contracts=420,
                    scope="same_day", collected_on=date(2026, 9, 4))
    manifest.record(tmp_path, "NIFTY", date(2026, 8, 10), n_expiries=1, n_contracts=24,
                    scope="backfill", collected_on=date(2026, 9, 7))

    assert manifest.complete_chain_sessions(tmp_path, "NIFTY") == [date(2026, 9, 4)]


def test_reading_a_manifest_that_does_not_exist_returns_an_empty_frame(tmp_path):
    got = manifest.read(tmp_path, "BANKNIFTY")

    assert got.empty
    assert "scope" in got.columns
