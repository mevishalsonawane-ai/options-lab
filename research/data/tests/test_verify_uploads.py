"""Tests for research/data/verify_uploads.py (the nightly check of the phone's uploads), with a fake GitHub."""
from __future__ import annotations

import datetime as dt
import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import decrypt_live as d  # noqa: E402
import verify_uploads as v  # noqa: E402

PASS = "Boss's pässphrase 1"
NOW = dt.datetime(2026, 10, 16, 22, 30, tzinfo=dt.timezone.utc)   # 17 Oct 04:00 IST


def day_zip(day: str) -> bytes:
    files = {f"{day}/market.jsonl.gz": b"m" * 500, f"{day}/trades.jsonl.gz.vault": b"sealed by the phone"}
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        for n, data in files.items():
            z.writestr(n, data)
        z.writestr("MANIFEST.json", json.dumps({"day": day, "files": [
            {"name": n, "bytes": len(x), "sha256": hashlib.sha256(x).hexdigest()} for n, x in files.items()]}))
    return b.getvalue()


def sealed(day: str, chunk: int = 256) -> bytes:
    return d.seal_bytes(day_zip(day), PASS, salt=bytes(16), prefix=bytes(8), rounds=1000, chunk=chunk)


class FakeGitHub(v.GitHub):
    def __init__(self):
        super().__init__("not-a-token")
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
    g.add("latest-apk", "IraAlgo.apk", b"apk")   # other releases are left alone
    return g


def test_new_days_are_checked_and_catalogued_once(gh, tmp_path):
    cat = v.load_catalog(tmp_path / "CATALOG.json")
    rep = v.run(gh, cat, NOW, tmp_path)
    assert not rep.failed and not rep.warnings, rep.markdown()
    assert rep.added == ["2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16"]
    e = next(x for x in cat["files"] if x["day"] == "2026-10-14")
    assert e["release"] == "live-2026-10" and e["check"] == "structure"
    assert e["sha256"] == hashlib.sha256(sealed("2026-10-14")).hexdigest()
    assert rep.backups == 1 and rep.newest_backup.hour == 18
    # Nothing is left in the work folder; a second night downloads nothing new.
    assert list(tmp_path.iterdir()) == []
    v.save_catalog(tmp_path / "CATALOG.json", cat)
    gh.downloads.clear()
    rep2 = v.run(gh, v.load_catalog(tmp_path / "CATALOG.json"), NOW, tmp_path)
    assert rep2.added == [] and rep2.still == 5
    assert all(gh.blobs[i][:6] == b"IRABK3" for i in gh.downloads)   # only the backup's 16-byte head again


def test_a_replaced_day_is_checked_again(gh, tmp_path):
    cat = v.load_catalog(tmp_path / "x.json")
    v.run(gh, cat, NOW, tmp_path)
    gh.remove("live-2026-10", "live-2026-10-16.irlive")
    gh.add("live-2026-10", "live-2026-10-16.irlive", sealed("2026-10-16", chunk=128))
    rep = v.run(gh, cat, NOW, tmp_path)
    assert rep.added == ["2026-10-16"]
    e = [x for x in cat["files"] if x["day"] == "2026-10-16"]
    assert len(e) == 1 and e[0]["replaced"] == 1


@pytest.mark.parametrize("damage, why", [
    # A cut that leaves a chunk shorter than its tag shows in the layout (a cut inside a long last chunk needs the key, or
    # GitHub's listed size, which test_a_size_that_differs_from_the_listing_fails covers).
    (lambda b: b[: d.HEADER_SIZE + (256 + d.TAG) + 10], "cut short"),
    (lambda b: b[: d.HEADER_SIZE + 5], "cut short"),
    (lambda b: b"NOTLIVE!" + b[8:], "not an IraAlgo live file"),
    (lambda b: b[:9] + (20_000_000).to_bytes(4, "big") + b[13:], "out of range"),
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


def test_backups_are_checked_and_an_old_one_warns(tmp_path):
    g = FakeGitHub()
    g.add("phone-backup", "phone-backup-2026-10-13-180000.irabk", backup_bytes())
    g.add("phone-backup", "phone-backup-2026-10-12-180000.irabk", b"garbage" * 20)
    rep = v.run(g, v.load_catalog(tmp_path / "x.json"), NOW, tmp_path)
    assert any("IRABK3" in f for f in rep.failed)
    assert any("newest phone backup is from 2026-10-13" in w for w in rep.warnings)


def test_gaps_and_vanished_files_are_warnings(gh, tmp_path):
    cat = v.load_catalog(tmp_path / "x.json")
    v.run(gh, cat, NOW, tmp_path)
    gh.remove("live-2026-10", "live-2026-10-13.irlive")
    rep = v.run(gh, cat, NOW, tmp_path)
    assert not rep.failed
    assert any("no longer on GitHub" in w for w in rep.warnings)
    # 13 Oct is still in the catalogue, so it is no gap; weekdays before the first upload never are.
    assert not any("weekdays" in w for w in rep.warnings)


def test_weekday_gaps():
    days = [dt.date(2026, 10, 12), dt.date(2026, 10, 14)]
    assert v.weekday_gaps(days, dt.date(2026, 10, 19)) == [dt.date(2026, 10, 13), dt.date(2026, 10, 15), dt.date(2026, 10, 16)]
    assert v.weekday_gaps([], dt.date(2026, 10, 19)) == []


def test_with_the_passphrase_each_day_is_opened_and_its_manifest_checked(gh, tmp_path):
    cat = v.load_catalog(tmp_path / "x.json")
    rep = v.run(gh, cat, NOW, tmp_path, passphrase=PASS)
    assert not rep.failed and {e["check"] for e in cat["files"]} == {"decrypted"}
    # A file sealed whole but with a wrong manifest, or under another passphrase, fails.
    other = d.seal_bytes(day_zip("2026-10-19"), "another passphrase", salt=bytes(16), prefix=bytes(8), rounds=1000, chunk=256)
    gh.add("live-2026-10", "live-2026-10-19.irlive", other)
    rep = v.run(gh, cat, NOW, tmp_path, passphrase=PASS)
    assert any("Wrong passphrase" in f for f in rep.failed)


def test_the_report_and_the_catalog_file(gh, tmp_path):
    cat = v.load_catalog(tmp_path / "CATALOG.json")
    assert cat["files"] == [] and "Data Brain" in cat["about"]
    rep = v.run(gh, cat, NOW, tmp_path)
    v.save_catalog(tmp_path / "CATALOG.json", cat)
    back = json.loads((tmp_path / "CATALOG.json").read_text())
    assert [e["day"] for e in back["files"]] == sorted(e["day"] for e in back["files"])
    md = rep.markdown()
    assert "Everything checked out." in md and "Phone backups on GitHub: 1" in md
    assert PASS not in md and "not-a-token" not in md
