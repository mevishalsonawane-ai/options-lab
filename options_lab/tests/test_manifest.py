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


# --- claiming same_day honestly --------------------------------------------
def test_a_session_collected_today_with_a_complete_chain_is_same_day():
    assert manifest.scope_for(day=date(2026, 9, 10), today=date(2026, 9, 10),
                              failures=0) == manifest.SAME_DAY


def test_an_earlier_session_is_backfill_however_it_was_reached():
    assert manifest.scope_for(day=date(2026, 9, 9), today=date(2026, 9, 10),
                              failures=0) == manifest.BACKFILL


def test_a_today_session_with_failed_contracts_does_not_claim_same_day():
    """same_day means the partition holds the whole live chain as it traded.
    If contracts failed to fetch it does not, so the claim would be false.
    Understating is the safe direction: consumers treat same_day as the
    trustworthy set, and a partial chain silently inside it would corrupt
    every chain-aggregate feature computed from it."""
    assert manifest.scope_for(day=date(2026, 9, 10), today=date(2026, 9, 10),
                              failures=3) == manifest.BACKFILL


def test_the_scope_is_one_of_the_declared_scopes():
    for failures in (0, 1):
        for day in (date(2026, 9, 9), date(2026, 9, 10)):
            assert manifest.scope_for(day=day, today=date(2026, 9, 10),
                                      failures=failures) in manifest.SCOPES


def test_a_partition_with_no_option_contracts_cannot_claim_same_day():
    """Found in verification: BANKNIFTY recorded 2026-09-10 as same_day with
    n_contracts=0. Its nearest expiry was 19 days out, so no contract needed
    the current-session fetch and the partition held only index rows. An empty
    chain is not "the whole live chain as it traded", and a same_day session
    with no chain would poison any consumer that filters on that scope."""
    assert manifest.scope_for(day=date(2026, 9, 10), today=date(2026, 9, 10),
                              failures=0, n_contracts=0) == manifest.BACKFILL


def test_a_populated_partition_collected_today_still_claims_same_day():
    assert manifest.scope_for(day=date(2026, 9, 10), today=date(2026, 9, 10),
                              failures=0, n_contracts=101) == manifest.SAME_DAY
