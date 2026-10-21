"""Tests for verify_uploads.py (the nightly check of the phone's uploads, run in the IraAlgo data repository), with a fake
GitHub. No passphrase anywhere: the check never decrypts."""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import struct
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import verify_uploads as v  # noqa: E402

NOW = dt.datetime(2026, 10, 16, 22, 30, tzinfo=dt.timezone.utc)   # 17 Oct 04:00 IST


def sealed(day: str, plain: int = 700, chunk: int = 256) -> bytes:
    """A file shaped as the phone seals one (IRLIVE01 header, chunks of ``chunk`` + a 16-byte tag), with made-up bytes."""
    seed = hashlib.sha256(day.encode()).digest()
    head = v.LIVE_MAGIC + bytes([v.KDF_PBKDF2_SHA256]) + struct.pack(">I", 600_000) + seed[:16] + seed[16:24] + struct.pack(">I", chunk)
    body = b""
    left = plain
    while True:
        n = min(chunk, left)
        body += (seed * (n // 32 + 1))[:n] + bytes(v.TAG)
        left -= n
        if left <= 0:
            break
    return head + body


class FakeGitHub(v.GitHub):
    def __init__(self):
        super().__init__("not-a-token", "mevishalsonawane-ai/IraAlgo")
        self.rel: dict[str, tuple[int, list[dict]]] = {}
        self.blobs: dict[int, bytes] = {}
        self.next = 1
        self.downloads: list[int] = []

    def add(self, tag: str, name: str, data: bytes, size: int | None = None) -> int:
        rid, assets = self.rel.setdefault(tag, (len(self.rel) + 1000, []))
        aid = self.next
        self.next += 1
        assets.append({"id": aid, "name": name, "size": len(data) if size is None else size, "updated_at": "2026-10-16T12:00:00Z"})
        self.blobs[aid] = data
        return aid

    def remove(self, tag: str, name: str):
        self.rel[tag][1][:] = [a for a in self.rel[tag][1] if a["name"] != name]

    def releases(self):
        return [{"id": rid, "tag_name": tag} for tag, (rid, _) in self.rel.items()]

    def assets(self, release_id):
        return next(a for rid, a in self.rel.values() if rid == release_id)

    def download(self, asset_id, dest, limit=None):
        self.downloads.append(asset_id)
        data = self.blobs[asset_id]
        dest.write_bytes(data if limit is None else data[:limit])


def backup_bytes() -> bytes:
    return b"IRABK3" + bytes(16 + 12 + 40)


@pytest.fixture
def gh():
    g = FakeGitHub()
    for day in ("2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16"):
        g.add("live-2026-10", f"live-{day}.irlive", sealed(day))
    g.add("phone-backup", "phone-backup-2026-10-16-180000.irabk", backup_bytes())
    return g


def test_the_last_catalogue_is_loaded_first_and_nothing_known_is_downloaded_again(gh, tmp_path):
    path = tmp_path / "CATALOG.json"
    cat = v.load_catalog(path)
    work = tmp_path / "work"
    work.mkdir()
    rep = v.run(gh, cat, NOW, work)
    assert not rep.failed and not rep.warnings, rep.markdown()
    assert rep.added == ["2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16"]
    assert rep.downloads == 6 and len(rep.backups_added) == 1
    e = next(x for x in cat["files"] if x.get("day") == "2026-10-14" and x["kind"] == "live")
    assert e["release"] == "live-2026-10" and e["check"] == "structure"
    assert e["sha256"] == hashlib.sha256(sealed("2026-10-14")).hexdigest()
    assert list(work.iterdir()) == []                       # nothing left behind
    v.save_catalog(path, cat)
    # The next night starts from the saved catalogue: no download at all, the backup's head included.
    gh.downloads.clear()
    rep2 = v.run(gh, v.load_catalog(path), NOW, work)
    assert gh.downloads == [] and rep2.downloads == 0
    assert rep2.added == [] and rep2.still == 6 and rep2.backups == 1
    # One new day and one new backup: exactly those two are downloaded.
    gh.add("live-2026-10", "live-2026-10-19.irlive", sealed("2026-10-19"))
    new_b = gh.add("phone-backup", "phone-backup-2026-10-19-180000.irabk", backup_bytes())
    rep3 = v.run(gh, v.load_catalog(path), NOW, work)
    assert rep3.added == ["2026-10-19"] and rep3.backups_added == ["phone-backup-2026-10-19-180000.irabk"]
    assert len(gh.downloads) == 2 and new_b in gh.downloads


def test_a_replaced_day_is_checked_again(gh, tmp_path):
    cat = v.load_catalog(tmp_path / "x.json")
    v.run(gh, cat, NOW, tmp_path)
    gh.remove("live-2026-10", "live-2026-10-16.irlive")
    gh.add("live-2026-10", "live-2026-10-16.irlive", sealed("2026-10-16", plain=900, chunk=128))
    rep = v.run(gh, cat, NOW, tmp_path)
    assert rep.added == ["2026-10-16"]
    e = [x for x in cat["files"] if x["day"] == "2026-10-16" and x["kind"] == "live"]
    assert len(e) == 1 and e[0]["replaced"] == 1


@pytest.mark.parametrize("damage, why", [
    # A cut that leaves a chunk shorter than its tag shows in the layout (a cut inside a long last chunk is caught by
    # GitHub's listed size: test_a_size_that_differs_from_the_listing_fails).
    (lambda b: b[: v.HEADER_SIZE + (256 + v.TAG) + 10], "cut short"),
    (lambda b: b[: v.HEADER_SIZE + 5], "cut short"),
    (lambda b: b"NOTLIVE!" + b[8:], "not an IraAlgo live file"),
    (lambda b: b[:8] + bytes([2]) + b[9:], "unknown key recipe"),
    (lambda b: b[:9] + (20_000_000).to_bytes(4, "big") + b[13:], "out of range"),
    (lambda b: b[:37] + (0).to_bytes(4, "big") + b[41:], "out of range"),
])
def test_a_damaged_file_fails_and_is_not_catalogued(gh, tmp_path, damage, why):
    gh.add("live-2026-10", "live-2026-10-17.irlive", damage(sealed("2026-10-17")))
    cat = v.load_catalog(tmp_path / "x.json")
    rep = v.run(gh, cat, NOW, tmp_path)
    assert len(rep.failed) == 1 and "live-2026-10-17" in rep.failed[0] and why in rep.failed[0], rep.failed
    assert "2026-10-17" not in [e["day"] for e in cat["files"]]


def test_a_size_that_differs_from_the_listing_fails(gh, tmp_path):
    gh.add("live-2026-10", "live-2026-10-17.irlive", sealed("2026-10-17"), size=99)
    rep = v.run(gh, v.load_catalog(tmp_path / "x.json"), NOW, tmp_path)
    assert any("GitHub lists 99" in f for f in rep.failed)


def test_names_and_months_must_match(gh, tmp_path):
    gh.add("live-2026-10", "live-2026-09-30.irlive", sealed("2026-09-30"))
    gh.add("live-2026-10", "notes.txt", b"x")
    rep = v.run(gh, v.load_catalog(tmp_path / "x.json"), NOW, tmp_path)
    assert any("not live-2026-09" in f for f in rep.failed)
    assert any("notes.txt" in f for f in rep.failed)


def test_a_half_done_replacement_is_a_warning(gh, tmp_path):
    gh.add("live-2026-10", "live-2026-10-16.irlive.new", sealed("2026-10-16"))
    gh.add("phone-backup", "phone-backup-2026-10-16-180000.irabk.new", backup_bytes())
    rep = v.run(gh, v.load_catalog(tmp_path / "x.json"), NOW, tmp_path)
    assert not rep.failed
    assert sum("half done" in w for w in rep.warnings) == 2


def test_backups_are_checked_and_an_old_one_warns(tmp_path):
    g = FakeGitHub()
    g.add("phone-backup", "phone-backup-2026-10-13-180000.irabk", backup_bytes())
    g.add("phone-backup", "phone-backup-2026-10-12-180000.irabk", b"garbage" * 20)
    g.add("phone-backup", "phone-backup-2026-10-11-180000.irabk", b"IRABK3" + bytes(10))
    rep = v.run(g, v.load_catalog(tmp_path / "x.json"), NOW, tmp_path)
    assert any("IRABK3" in f for f in rep.failed)
    assert any("too small" in f for f in rep.failed)
    assert any("newest phone backup is from 2026-10-13" in w for w in rep.warnings)


def test_pruned_backups_leave_the_catalogue_quietly(gh, tmp_path):
    cat = v.load_catalog(tmp_path / "x.json")
    v.run(gh, cat, NOW, tmp_path)
    gh.remove("phone-backup", "phone-backup-2026-10-16-180000.irabk")
    gh.add("phone-backup", "phone-backup-2026-10-16-190000.irabk", backup_bytes())
    rep = v.run(gh, cat, NOW, tmp_path)
    assert rep.pruned == 1 and not rep.failed
    assert [e["asset"] for e in cat["files"] if e["kind"] == "backup"] == ["phone-backup-2026-10-16-190000.irabk"]


def test_gaps_and_vanished_files_are_warnings(gh, tmp_path):
    cat = v.load_catalog(tmp_path / "x.json")
    v.run(gh, cat, NOW, tmp_path)
    gh.remove("live-2026-10", "live-2026-10-13.irlive")
    rep = v.run(gh, cat, NOW, tmp_path)
    assert not rep.failed
    assert any("no longer on GitHub" in w for w in rep.warnings)
    assert not any("weekdays" in w for w in rep.warnings)


def test_weekday_gaps():
    days = [dt.date(2026, 10, 12), dt.date(2026, 10, 14)]
    assert v.weekday_gaps(days, dt.date(2026, 10, 19)) == [dt.date(2026, 10, 13), dt.date(2026, 10, 15), dt.date(2026, 10, 16)]
    assert v.weekday_gaps([], dt.date(2026, 10, 19)) == []


def test_never_options_lab_never_a_passphrase_never_plain_http():
    with pytest.raises(ValueError):
        v.GitHub("t", "mevishalsonawane-ai/options-lab")
    with pytest.raises(OSError):
        v.GitHub("t", "mevishalsonawane-ai/IraAlgo")._req("http://api.github.com/x")
    src = (HERE / "verify_uploads.py").read_text()
    assert "PASSPHRASE" not in src and "cryptography" not in src and "AESGCM" not in src   # nothing here can decrypt


def test_the_report_and_the_catalog_file(gh, tmp_path):
    cat = v.load_catalog(tmp_path / "CATALOG.json")
    assert cat["files"] == [] and "Data Brain" in cat["about"]
    rep = v.run(gh, cat, NOW, tmp_path)
    v.save_catalog(tmp_path / "CATALOG.json", cat)
    back = json.loads((tmp_path / "CATALOG.json").read_text())
    days = [e["day"] for e in back["files"] if e["kind"] == "live"]
    assert days == sorted(days)
    md = rep.markdown()
    assert "Everything checked out." in md and "on GitHub: 1" in md
    assert "not-a-token" not in md


def test_the_committed_catalogue_loads():
    c = v.load_catalog(HERE / "CATALOG.json")
    assert c["version"] == 1 and c["files"] == []


# ---- parity with the phone's format, where options-lab's decrypt_live.py is at hand ---------------------------------

_DECRYPT = HERE.parents[1] / "research" / "data" / "decrypt_live.py"


@pytest.mark.skipif(not _DECRYPT.exists() or os.environ.get("SKIP_PARITY") == "1", reason="decrypt_live.py lives in options-lab")
def test_a_file_sealed_as_the_phone_seals_it_passes(tmp_path):
    pytest.importorskip("cryptography")
    sys.path.insert(0, str(_DECRYPT.parent))
    import decrypt_live as d
    assert (d.MAGIC, d.HEADER_SIZE, d.TAG, d.MAX_ROUNDS, d.MAX_CHUNK) == (v.LIVE_MAGIC, v.HEADER_SIZE, v.TAG, v.MAX_ROUNDS, v.MAX_CHUNK)
    for plain, chunk in ((0, 256), (700, 256), (512, 256), (5000, 1000)):
        b = d.seal_bytes(b"z" * plain, "a passphrase", salt=bytes(16), prefix=bytes(8), rounds=1000, chunk=chunk)
        g = FakeGitHub()
        g.add("live-2026-10", "live-2026-10-12.irlive", b)
        rep = v.run(g, v.load_catalog(tmp_path / "x.json"), NOW, tmp_path)
        assert not rep.failed, (plain, chunk, rep.failed)
