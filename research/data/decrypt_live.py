"""Open the phone's sealed live-recording files on a PC (TODO G1, 13 Oct 2026).

Every evening the phone uploads each recorded day to the GitHub release ``live-YYYY-MM`` as
``live-YYYY-MM-DD.irlive``. The file is sealed on the phone with a key made from Boss's backup
passphrase, so GitHub only ever holds noise. This script opens it with the same passphrase.

Inside is a zip of that day's recordings folder, exactly as the phone keeps it:

* ``YYYY-MM-DD/market.jsonl.gz``        the minute rows and the snapshots at each signal and fill
* ``YYYY-MM-DD/ticks.jsonl.gz``         the tick zone, one row a second per instrument
* ``YYYY-MM-DD/orderflow_1m.jsonl.gz``  the order-flow minute aggregates
* ``YYYY-MM-DD/trades.jsonl.gz.vault``  Boss's own trades, STILL encrypted with the phone's key
  (only the phone opens it: Data & Harvest -> Recordings -> Export, with the PIN)
* anything else the phone keeps in that folder (for example the nightly lessons bundle)
* ``MANIFEST.json``                     every file's size and sha256, to check the copy

Usage::

    pip install cryptography
    python research/data/decrypt_live.py live-2026-10-13.irlive            # writes live-2026-10-13.zip
    python research/data/decrypt_live.py live-2026-10-*.irlive --out dir/   # several files
    python research/data/decrypt_live.py live-2026-10-13.irlive --extract dir/   # unzip too

The passphrase is asked for (never shown, never stored). For scripts it can come from the
environment variable ``IRAALGO_BACKUP_PASSPHRASE`` instead: never put it in a file in the repo.

File format "IRLIVE01" (big-endian), written by ``LiveSeal.kt`` (android/ira-core):

    header  "IRLIVE01" (8) | kdf (1; 1 = PBKDF2-HMAC-SHA256) | rounds (4) | salt (16)
            | nonce prefix (8) | chunk size (4)
    body    AES-256-GCM chunks, each ciphertext + 16-byte tag
            nonce = prefix + chunk number (4)
            additional data = header + chunk number (4) + last flag (1 byte: 1 on the last chunk)
            every chunk but the last holds exactly "chunk size" plain bytes

    key = PBKDF2-HMAC-SHA256(passphrase as UTF-8, salt, rounds, 32 bytes)

A wrong passphrase, any changed byte, swapped chunks or a cut-off file are all refused.
"""
from __future__ import annotations

import argparse
import getpass
import hashlib
import io
import json
import os
import struct
import sys
import zipfile
from pathlib import Path
from typing import BinaryIO, Iterable

MAGIC = b"IRLIVE01"
KDF_PBKDF2_SHA256 = 1
HEADER_SIZE = 8 + 1 + 4 + 16 + 8 + 4
TAG = 16
MAX_ROUNDS = 10_000_000
MAX_CHUNK = 16 << 20
ENV = "IRAALGO_BACKUP_PASSPHRASE"


class Refused(Exception):
    """The file is not ours, the passphrase is wrong, or the file was changed or cut."""


def _aesgcm(key: bytes):
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError as e:  # pragma: no cover - depends on the PC
        raise SystemExit("This needs the 'cryptography' package: pip install cryptography") from e
    return AESGCM(key)


def derive(passphrase: str, salt: bytes, rounds: int) -> bytes:
    """The key: PBKDF2-HMAC-SHA256 of the passphrase's UTF-8 bytes (as the phone derives it)."""
    return hashlib.pbkdf2_hmac("sha256", passphrase.encode("utf-8"), salt, rounds, 32)


def read_header(f: BinaryIO) -> tuple[bytes, int, bytes, bytes, int]:
    """(raw header, rounds, salt, nonce prefix, chunk size)."""
    raw = f.read(HEADER_SIZE)
    if len(raw) < HEADER_SIZE:
        raise Refused("This is not an IraAlgo live file (too short).")
    if raw[:8] != MAGIC:
        raise Refused("This is not an IraAlgo live file.")
    kdf, rounds = raw[8], struct.unpack(">I", raw[9:13])[0]
    salt, prefix = raw[13:29], raw[29:37]
    chunk = struct.unpack(">I", raw[37:41])[0]
    if kdf != KDF_PBKDF2_SHA256:
        raise Refused("Unknown key recipe in this file.")
    if not (1 <= rounds <= MAX_ROUNDS) or not (1 <= chunk <= MAX_CHUNK):
        raise Refused("This file's header is out of range.")
    return raw, rounds, salt, prefix, chunk


def _read_up_to(f: BinaryIO, n: int) -> bytes:
    out = bytearray()
    while len(out) < n:
        b = f.read(n - len(out))
        if not b:
            break
        out += b
    return bytes(out)


def open_stream(src: BinaryIO, passphrase: str, dst: BinaryIO) -> int:
    """Opens a sealed stream into ``dst``; returns the plain bytes written."""
    raw, rounds, salt, prefix, chunk = read_header(src)
    aes = _aesgcm(derive(passphrase, salt, rounds))
    block = chunk + TAG
    cur = _read_up_to(src, block)
    i, total = 0, 0
    while True:
        if len(cur) < TAG:
            raise Refused("The file is cut short.")
        nxt = _read_up_to(src, block) if len(cur) == block else b""
        last = not nxt
        nonce = prefix + struct.pack(">I", i)
        aad = raw + struct.pack(">I", i) + (b"\x01" if last else b"\x00")
        try:
            plain = aes.decrypt(nonce, cur, aad)
        except Exception as e:  # cryptography's InvalidTag
            raise Refused("Wrong passphrase, or the file was changed." if i == 0
                          else "The file was changed or cut short.") from e
        dst.write(plain)
        total += len(plain)
        if last:
            return total
        cur, i = nxt, i + 1


def open_bytes(sealed: bytes, passphrase: str) -> bytes:
    out = io.BytesIO()
    open_stream(io.BytesIO(sealed), passphrase, out)
    return out.getvalue()


def seal_bytes(plain: bytes, passphrase: str, *, salt: bytes, prefix: bytes,
               rounds: int = 600_000, chunk: int = 1 << 20) -> bytes:
    """The phone's sealing, for tests and for checking the format by hand (the phone does this itself)."""
    raw = MAGIC + bytes([KDF_PBKDF2_SHA256]) + struct.pack(">I", rounds) + salt + prefix + struct.pack(">I", chunk)
    aes = _aesgcm(derive(passphrase, salt, rounds))
    pieces = [plain[i:i + chunk] for i in range(0, len(plain), chunk)] or [b""]
    out = bytearray(raw)
    for i, p in enumerate(pieces):
        last = i == len(pieces) - 1
        out += aes.encrypt(prefix + struct.pack(">I", i), p,
                           raw + struct.pack(">I", i) + (b"\x01" if last else b"\x00"))
    return bytes(out)


def check_manifest(zip_path: Path) -> list[str]:
    """Problems found by comparing the zip's files with its MANIFEST.json (empty: all match)."""
    problems = []
    with zipfile.ZipFile(zip_path) as z:
        try:
            manifest = json.loads(z.read("MANIFEST.json"))
        except KeyError:
            return ["no MANIFEST.json in the zip"]
        for entry in manifest.get("files", []):
            name = entry["name"]
            try:
                data = z.read(name)
            except KeyError:
                problems.append(f"{name}: missing")
                continue
            if len(data) != entry["bytes"]:
                problems.append(f"{name}: {len(data)} bytes, manifest says {entry['bytes']}")
            elif hashlib.sha256(data).hexdigest() != entry["sha256"]:
                problems.append(f"{name}: sha256 differs from the manifest")
    return problems


def _passphrase() -> str:
    p = os.environ.get(ENV)
    return p if p else getpass.getpass("Backup passphrase: ")


def main(argv: Iterable[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Open IraAlgo's sealed live-recording files (.irlive).")
    ap.add_argument("files", nargs="+", type=Path, help="the .irlive files downloaded from the live-YYYY-MM release")
    ap.add_argument("--out", type=Path, default=None, help="folder for the .zip files (default: beside each file)")
    ap.add_argument("--extract", type=Path, default=None, help="also unzip each day into this folder")
    a = ap.parse_args(list(argv) if argv is not None else None)
    passphrase = _passphrase()
    failed = 0
    for src in a.files:
        dest_dir = a.out or src.parent
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / (src.stem + ".zip")
        tmp = dest.with_suffix(".zip.part")
        try:
            with open(src, "rb") as f, open(tmp, "wb") as out:
                n = open_stream(f, passphrase, out)
            tmp.replace(dest)
        except Refused as e:
            tmp.unlink(missing_ok=True)
            print(f"{src.name}: {e}", file=sys.stderr)
            failed += 1
            continue
        problems = check_manifest(dest)
        note = "manifest ok" if not problems else "; ".join(problems)
        print(f"{src.name}: {n:,} bytes -> {dest} ({note})")
        if problems:
            failed += 1
        if a.extract:
            with zipfile.ZipFile(dest) as z:
                z.extractall(a.extract)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
