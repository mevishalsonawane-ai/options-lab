"""Provenance for the cached expiry chains.

170 parquet files sit in options_lab/data/expiry_cache and every result in this
repo is computed from them. Nothing recorded where they came from, when they
were fetched, or what was filtered out. A silently edited or truncated file
would change every published figure with no trace.

This module records what can be VERIFIED from the files themselves (content
hash, row counts, time span, settlement coverage) and requires the source to be
declared. What cannot be established is recorded as unknown and reported as
unknown - it is never invented.
"""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from options_lab import provenance as prov

IST = "Asia/Kolkata"


def _chain(day="2024-10-03", n_strikes=6, settle_bars=3):
    rows = []
    stamps = [pd.Timestamp(f"{day} 11:00", tz=IST)]
    stamps += [pd.Timestamp(f"{day} 15:{m:02d}", tz=IST)
               for m in range(0, settle_bars * 5, 5)]
    for ts in stamps:
        for i in range(n_strikes):
            for right in ("CE", "PE"):
                rows.append({"ts": ts, "strike": 24_000.0 + 50 * i,
                             "right": right, "close": 10.0,
                             "volume": 100, "open_interest": 500})
    return pd.DataFrame(rows)


@pytest.fixture
def cache(tmp_path):
    for day in ("2024-10-03", "2024-10-10"):
        _chain(day).to_parquet(tmp_path / f"{day}_chain.parquet")
    return tmp_path


# --- fingerprints ----------------------------------------------------------
def test_a_fingerprint_records_the_content_hash(cache):
    fp = prov.fingerprint(cache / "2024-10-03_chain.parquet")

    assert len(fp["sha256"]) == 64
    assert fp["session"] == date(2024, 10, 3)
    assert fp["n_rows"] > 0


def test_two_identical_files_hash_the_same_and_a_changed_one_does_not(cache):
    a = prov.fingerprint(cache / "2024-10-03_chain.parquet")["sha256"]

    _chain("2024-10-03", n_strikes=7).to_parquet(
        cache / "2024-10-03_chain.parquet")
    b = prov.fingerprint(cache / "2024-10-03_chain.parquet")["sha256"]

    assert a != b


def test_a_fingerprint_records_settlement_coverage(cache):
    """run_session skips a session with no 15:00-15:30 bars. Knowing that in
    advance turns a mystery skip into a known data gap."""
    fp = prov.fingerprint(cache / "2024-10-03_chain.parquet")

    assert fp["n_settlement_bars"] == 3


def test_a_file_whose_name_is_not_a_session_date_is_refused(tmp_path):
    (tmp_path / "notadate_chain.parquet").write_bytes(b"x")

    with pytest.raises(prov.BadCacheFile):
        prov.fingerprint(tmp_path / "notadate_chain.parquet")


# --- building the manifest -------------------------------------------------
def test_build_covers_every_file_in_the_cache(cache):
    man = prov.build(cache, source="upstox-v3-historical-candle")

    assert len(man) == 2
    assert set(man["session"]) == {date(2024, 10, 3), date(2024, 10, 10)}


def test_the_source_must_be_declared(cache):
    with pytest.raises(prov.UnknownProvenance):
        prov.build(cache, source="")


def test_an_unestablished_fetch_date_is_recorded_as_unknown_not_invented(cache):
    """These files were copied into this repo on 2026-09-10; that is a copy
    date, not a fetch date. Recording the copy date as the fetch date would be
    a fabricated fact that looks exactly like a verified one."""
    man = prov.build(cache, source="upstox-v3-historical-candle")

    assert man["fetched_on"].isna().all()
    assert (man["source"] == "upstox-v3-historical-candle").all()


def test_a_stated_fetch_date_is_kept(cache):
    man = prov.build(cache, source="kaggle-nse-options",
                     fetched_on=date(2026, 9, 1))

    assert (man["fetched_on"] == date(2026, 9, 1)).all()


# --- round trip and drift --------------------------------------------------
def test_write_then_read_round_trips(cache):
    man = prov.build(cache, source="upstox-v3-historical-candle")
    prov.write(cache, man)

    back = prov.read(cache)

    assert list(back["sha256"]) == list(man["sha256"])
    assert back["session"].tolist() == man["session"].tolist()


def test_an_unchanged_cache_reports_no_drift(cache):
    prov.write(cache, prov.build(cache, source="upstox-v3-historical-candle"))

    assert prov.verify(cache) == []


def test_an_edited_file_is_caught_by_its_hash(cache):
    prov.write(cache, prov.build(cache, source="upstox-v3-historical-candle"))
    _chain("2024-10-03", n_strikes=9).to_parquet(
        cache / "2024-10-03_chain.parquet")

    drift = prov.verify(cache)

    assert len(drift) == 1
    assert drift[0].kind == "changed"
    assert drift[0].session == date(2024, 10, 3)


def test_a_deleted_file_is_caught(cache):
    prov.write(cache, prov.build(cache, source="upstox-v3-historical-candle"))
    (cache / "2024-10-10_chain.parquet").unlink()

    drift = prov.verify(cache)

    assert [d.kind for d in drift] == ["missing"]


def test_a_file_the_manifest_never_recorded_is_caught(cache):
    """An unrecorded file is the dangerous case: results silently include data
    nobody declared. It is a finding, not something to quietly absorb."""
    prov.write(cache, prov.build(cache, source="upstox-v3-historical-candle"))
    _chain("2024-10-17").to_parquet(cache / "2024-10-17_chain.parquet")

    drift = prov.verify(cache)

    assert [d.kind for d in drift] == ["unrecorded"]
    assert drift[0].session == date(2024, 10, 17)


def test_verify_without_a_manifest_says_so_rather_than_passing(cache):
    with pytest.raises(FileNotFoundError):
        prov.verify(cache)


def test_every_drift_says_what_it_means(cache):
    prov.write(cache, prov.build(cache, source="upstox-v3-historical-candle"))
    (cache / "2024-10-10_chain.parquet").unlink()

    assert all(d.why for d in prov.verify(cache))


# --- OI units --------------------------------------------------------------
def _oi_chain(day, deltas, start=1000):
    """A two-bar chain whose OI moves by the given steps."""
    rows = []
    base = pd.Timestamp(f"{day} 09:15", tz=IST)
    for i in range(len(deltas) + 1):
        ts = base + pd.Timedelta(minutes=i)
        oi = start + sum(deltas[:i])
        rows.append({"ts": ts, "strike": 24_000.0, "right": "PE",
                     "close": 10.0, "volume": 1, "open_interest": oi})
    return pd.DataFrame(rows)


def test_lot_multiplied_oi_is_detected_by_divisibility(tmp_path):
    """Upstox publishes OI already multiplied by the lot. The evidence is that
    every non-zero move is a whole number of lots - the same divisibility test
    the harvest store uses, applied to a cache that predates it."""
    p = tmp_path / "2024-10-03_chain.parquet"
    _oi_chain("2024-10-03", [25, 50, -25]).to_parquet(p)

    assert prov.infer_oi_units(p, lot=25) == prov.LOT_MULTIPLIED


def test_contract_units_are_detected_when_moves_are_not_whole_lots(tmp_path):
    p = tmp_path / "2024-10-03_chain.parquet"
    _oi_chain("2024-10-03", [1, 3, -2]).to_parquet(p)

    assert prov.infer_oi_units(p, lot=25) == prov.CONTRACTS


def test_units_are_indeterminate_when_oi_never_moves(tmp_path):
    """No evidence is not the same as evidence for contracts."""
    p = tmp_path / "2024-10-03_chain.parquet"
    _oi_chain("2024-10-03", [0, 0]).to_parquet(p)

    assert prov.infer_oi_units(p, lot=25) == prov.INDETERMINATE


def test_build_records_oi_units_for_sessions_whose_lot_is_known(cache):
    man = prov.build(cache, source="upstox-v3-historical-candle",
                     underlying="NIFTY")

    assert "oi_units" in man.columns


def test_a_session_outside_the_lot_history_gets_indeterminate_units(tmp_path):
    """2023 has no verified lot, so the divisibility test has no divisor. That
    is recorded as indeterminate, not guessed from a neighbouring era."""
    _chain("2023-06-08").to_parquet(tmp_path / "2023-06-08_chain.parquet")

    man = prov.build(tmp_path, source="upstox-v3-historical-candle",
                     underlying="NIFTY")

    assert man.iloc[0]["oi_units"] == prov.INDETERMINATE


def test_a_handful_of_stray_ticks_does_not_flip_the_units_verdict(tmp_path):
    """Real sessions are 98.7% divisible, not 100%. Demanding perfection
    labelled three genuine Upstox files as contract-denominated."""
    p = tmp_path / "2024-10-03_chain.parquet"
    _oi_chain("2024-10-03", [25] * 200 + [7]).to_parquet(p)

    assert prov.infer_oi_units(p, lot=25) == prov.LOT_MULTIPLIED
