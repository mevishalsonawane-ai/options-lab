"""Tests for the app's concept book (app_concepts.py), no lake needed:

    python -m pytest research/hunt/r61/test_app_concepts.py -q

A text transform, so there is no look-ahead to guard; instead these check that the bundled asset is current, every
concept has a signal source and an honest verdict with a sample size, and nothing claims a profit it never showed.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import app_concepts as A  # noqa: E402


@pytest.fixture(scope="module")
def book() -> dict:
    return A.build(json.loads(A.SRC.read_text()))


def test_bundled_asset_is_current():
    assert A.main(["--check"]) == 0, "run: python -m research.hunt.r61.app_concepts"


def test_all_r61_r61b_and_r62b_concepts_are_in(book):
    ids = [c["id"] for c in book["concepts"]]
    assert len(ids) == len(set(ids)) == 40
    for cid in ("oi", "gex", "traps", "fake_breakout", "stop_hunt", "delta_cvd", "ofi", "order_blocks", "bos_choch",
                "zigzag", "msb", "fib_factor"):
        assert cid in ids


def test_every_concept_is_whole_and_has_a_source(book):
    for c in book["concepts"]:
        for key in ("name", "what", "use", "measure", "phone", "role", "verdict", "latest"):
            assert c[key].strip(), (c["id"], key)
        assert c["source"] in A.SOURCES and c["sourceText"] == A.SOURCES[c["source"]]
        assert c["evidence"], c["id"]
        assert all(e["sample"].strip() for e in c["evidence"]), c["id"]


def test_chain_concepts_are_never_a_default_signal(book):
    for c in book["concepts"]:
        if c["source"] == "chain":
            assert "never the signal by default" in c["sourceText"]
    assert next(c for c in book["concepts"] if c["id"] == "pcr")["verdict"].count("Boss-only") == 1


def test_verdicts_carry_a_number_and_claim_no_win(book):
    for c in book["concepts"]:
        assert re.search(r"\d", c["verdict"]), c["id"]
        low = c["verdict"].lower()
        assert "profitable" not in low or "no variant profitable" in low or "turned no variant" in low, c["id"]
        assert "passed the gates" not in low and "beats solo" not in low, c["id"]


def test_r62b_updates_replace_the_placeholder_rows(book):
    ob = next(c for c in book["concepts"] if c["id"] == "order_blocks")
    assert all(e["study"] != "search" for e in ob["evidence"])
    assert any(e["study"] == "R62b" for e in ob["evidence"])
    assert "R62b" in next(c for c in book["concepts"] if c["id"] == "bos_choch")["latest"]


def test_source_map_and_verdicts_cover_exactly_the_concepts(book):
    ids = {c["id"] for c in book["concepts"]}
    assert set(A.SOURCE_OF) == ids and set(A.VERDICTS) == ids


def test_short_trims_at_a_sentence_end():
    assert A._short("a  b") == "a b"
    long = "One sentence here. " * 60
    out = A._short(long, 100)
    assert len(out) <= 100 and out.endswith(".")
    assert A._short("x" * 50, 20).endswith("...")


def test_check_flags_a_stale_asset(tmp_path, monkeypatch):
    monkeypatch.setattr(A, "OUT", tmp_path / "concepts.json")
    assert A.main(["--check"]) == 1  # missing
    assert A.main([]) == 0 and A.main(["--check"]) == 0
    (tmp_path / "concepts.json").write_text("{}")
    assert A.main(["--check"]) == 1  # stale
