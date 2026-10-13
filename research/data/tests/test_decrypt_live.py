"""Tests for research/data/decrypt_live.py: the PC opens exactly what the phone seals (LiveSeal.kt)."""
from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import decrypt_live as d  # noqa: E402

SALT = bytes(range(16))
PREFIX = bytes(range(0xA0, 0xA8))
PASS = "Boss's pässphrase 1"

# Written by the phone's own code: LiveSealTest.theVectorThePcTestOpens (android/ira-core) checks it byte for byte.
VECTOR = bytes.fromhex(
    "49524c495645303101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a6a700000010560d6c4d7a69d6119e87b0312b24d930"
    "5a6df450ff941dd1bff61533a1a6fbb45e0e3fbe229764333ceee9549cbe5bed754c96f76b807648c934b316e2e7e8a39d11a5fd0c56dffac4"
    "8318df95c4acc0f552131bf879")


def seal(plain: bytes, chunk: int = 64, passphrase: str = PASS) -> bytes:
    return d.seal_bytes(plain, passphrase, salt=SALT, prefix=PREFIX, rounds=1000, chunk=chunk)


def test_opens_the_phones_vector():
    assert d.open_bytes(VECTOR, PASS) == b"IraAlgo live day, sealed on the phone."


def test_seals_the_same_bytes_as_the_phone():
    assert seal(b"IraAlgo live day, sealed on the phone.", chunk=16) == VECTOR


@pytest.mark.parametrize("n", [0, 1, 63, 64, 65, 192, 1000])
def test_round_trip_across_chunk_edges(n):
    plain = os.urandom(n)
    assert d.open_bytes(seal(plain), PASS) == plain


def test_wrong_passphrase_is_refused():
    with pytest.raises(d.Refused, match="Wrong passphrase"):
        d.open_bytes(VECTOR, "not the passphrase")


def test_any_changed_byte_is_refused():
    sealed = seal(os.urandom(150))
    for i in range(len(sealed)):
        bad = bytearray(sealed)
        bad[i] ^= 1
        with pytest.raises(d.Refused):
            d.open_bytes(bytes(bad), PASS)


def test_a_cut_file_is_refused():
    sealed = seal(os.urandom(200))
    for cut in (d.HEADER_SIZE + 2 * (64 + d.TAG), len(sealed) - 1, 10):
        with pytest.raises(d.Refused):
            d.open_bytes(sealed[:cut], PASS)


def test_not_our_file_is_refused():
    with pytest.raises(d.Refused, match="not an IraAlgo live file"):
        d.open_bytes(b"IRABK3" + bytes(100), PASS)


def _day_zip() -> bytes:
    files = {"2026-10-13/market.jsonl.gz": b"m" * 300, "2026-10-13/trades.jsonl.gz.vault": b"still sealed by the phone"}
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        for name, data in files.items():
            z.writestr(name, data)
        z.writestr("MANIFEST.json", json.dumps({"day": "2026-10-13", "files": [
            {"name": n, "bytes": len(v), "sha256": hashlib.sha256(v).hexdigest()} for n, v in files.items()]}))
    return b.getvalue()


def test_the_command_writes_the_zip_and_checks_its_manifest(tmp_path, monkeypatch, capsys):
    src = tmp_path / "live-2026-10-13.irlive"
    src.write_bytes(seal(_day_zip(), chunk=1 << 20))
    monkeypatch.setenv(d.ENV, PASS)
    out = tmp_path / "out"
    assert d.main([str(src), "--out", str(out), "--extract", str(tmp_path / "x")]) == 0
    assert (out / "live-2026-10-13.zip").exists()
    assert (tmp_path / "x" / "2026-10-13" / "trades.jsonl.gz.vault").read_bytes() == b"still sealed by the phone"
    assert "manifest ok" in capsys.readouterr().out


def test_the_command_fails_on_a_wrong_passphrase_and_leaves_nothing(tmp_path, monkeypatch):
    src = tmp_path / "live-2026-10-13.irlive"
    src.write_bytes(seal(_day_zip()))
    monkeypatch.setenv(d.ENV, "wrong")
    assert d.main([str(src)]) == 1
    assert not (tmp_path / "live-2026-10-13.zip").exists()
    assert not (tmp_path / "live-2026-10-13.zip.part").exists()


def test_a_manifest_mismatch_is_reported(tmp_path):
    z = tmp_path / "a.zip"
    with zipfile.ZipFile(z, "w") as f:
        f.writestr("x", b"abc")
        f.writestr("MANIFEST.json", json.dumps({"files": [{"name": "x", "bytes": 3, "sha256": "0" * 64},
                                                          {"name": "y", "bytes": 1, "sha256": "0" * 64}]}))
    problems = d.check_manifest(z)
    assert any("sha256" in p for p in problems) and any("y: missing" in p for p in problems)
