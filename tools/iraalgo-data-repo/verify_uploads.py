"""The nightly check of the phone's GitHub uploads (TODO G1, 13 Oct 2026), run in the data repository IraAlgo.

Every evening the phone uploads each recorded day to this repository's release ``live-YYYY-MM`` (``live-YYYY-MM-DD.irlive``)
and a phone backup to ``phone-backup`` (``phone-backup-YYYY-MM-DD-HHMMSS.irabk``). Each night this job
(.github/workflows/verify-uploads.yml) checks them. It never has Boss's passphrase and never decrypts anything (13 Oct
review: the passphrase is not an Actions secret, not even as an option):

* The catalogue (CATALOG.json, committed in this repository) is loaded FIRST. A file already in it with the same asset id
  and size is not downloaded again; only new or replaced files are (so a night costs the new days, never every file).
* Each NEW live file is downloaded once and checked whole, without the key: its name and release (the right month), the
  IRLIVE01 header (magic, key recipe, rounds and chunk size in range), the chunk layout (every chunk at least its 16-byte
  GCM tag, the file not cut short), that the bytes downloaded are exactly the size GitHub lists, and its sha256 (kept, so
  a copy elsewhere can be checked against it).
  The GCM tags themselves can only be checked with the key: that check is the phone's (the manifest it seals) and
  ``decrypt_live.py``'s on a PC, with the passphrase typed there.
* Each NEW backup: the IRABK3 header and that it is big enough to hold its salt, IV and tag (16 bytes downloaded).
* What is old: the newest backup's age, weekdays of the last 14 days with no live file (a holiday, or the phone not
  charging on Wi-Fi), files catalogued but gone from GitHub, and a ``*.new`` file the phone left half-replaced: warnings.

A file that fails is never catalogued and fails the job (a red X and, with the ``NTFY_TOPIC`` secret, a notification).

Usage (the workflow runs it with this repository's own GITHUB_TOKEN)::

    GITHUB_TOKEN=... python verify_uploads.py --catalog CATALOG.json --summary out.md
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import struct
import sys
import tempfile
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import BinaryIO, Iterable

REPO = os.environ.get("GITHUB_REPOSITORY") or "mevishalsonawane-ai/IraAlgo"
CODE_REPO = "options-lab"
BACKUP_TAG = "phone-backup"
LIVE_TAG = re.compile(r"^live-(\d{4})-(\d{2})$")
LIVE_NAME = re.compile(r"^live-(\d{4}-\d{2}-\d{2})\.irlive$")
BACKUP_NAME = re.compile(r"^phone-backup-(\d{4}-\d{2}-\d{2})-(\d{6})\.irabk$")
TEMP_SUFFIX = ".new"                    # GitHubReleases.tempName on the phone: a replacement on its way
BACKUP_MAGIC = b"IRABK3"
BACKUP_MIN = 6 + 16 + 12 + 16          # magic, salt, IV, the GCM tag of an empty body
STALE_BACKUP_DAYS = 2
GAP_DAYS = 14
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))

# The IRLIVE01 format (LiveSeal.kt on the phone; decrypt_live.py in options-lab opens it):
#   "IRLIVE01" (8) | kdf (1; 1 = PBKDF2-HMAC-SHA256) | rounds (4) | salt (16) | nonce prefix (8) | chunk size (4)
#   then AES-256-GCM chunks, each ciphertext + 16-byte tag; every chunk but the last holds exactly "chunk size" bytes.
LIVE_MAGIC = b"IRLIVE01"
KDF_PBKDF2_SHA256 = 1
HEADER_SIZE = 8 + 1 + 4 + 16 + 8 + 4
TAG = 16
MAX_ROUNDS = 10_000_000
MAX_CHUNK = 16 << 20


class Refused(Exception):
    """The file is not a sealed IraAlgo live file, or its header is out of range."""


def read_header(raw: bytes) -> tuple[int, int]:
    """(rounds, chunk size) from a live file's first HEADER_SIZE bytes."""
    if len(raw) < HEADER_SIZE:
        raise Refused("not an IraAlgo live file (too short)")
    if raw[:8] != LIVE_MAGIC:
        raise Refused("not an IraAlgo live file")
    kdf, rounds = raw[8], struct.unpack(">I", raw[9:13])[0]
    chunk = struct.unpack(">I", raw[37:41])[0]
    if kdf != KDF_PBKDF2_SHA256:
        raise Refused("unknown key recipe in the header")
    if not (1 <= rounds <= MAX_ROUNDS) or not (1 <= chunk <= MAX_CHUNK):
        raise Refused("the header is out of range")
    return rounds, chunk


# ---- the checks (pure) ----------------------------------------------------------------------------------------------

def check_live_stream(f: BinaryIO, size: int) -> tuple[str | None, str]:
    """(problem or None, sha256) for a sealed live file of ``size`` bytes, read once from ``f``. No key needed."""
    h = hashlib.sha256()
    head = f.read(HEADER_SIZE)
    h.update(head)
    problem = None
    try:
        _rounds, chunk = read_header(head)
    except Refused as e:
        problem, chunk = str(e), 0
    read = len(head)
    while True:
        b = f.read(1 << 20)
        if not b:
            break
        h.update(b)
        read += len(b)
    if read != size:
        return f"downloaded {read} bytes, GitHub lists {size}", h.hexdigest()
    if problem is None:
        problem = layout_problem(size, chunk)
    return problem, h.hexdigest()


def layout_problem(size: int, chunk: int) -> str | None:
    """Whether a body of this size can be whole chunks (each chunk + 16-byte tag; the last may be short, never under 16)."""
    body = size - HEADER_SIZE
    if body < TAG:
        return "no sealed data after the header (cut short)"
    rest = body % (chunk + TAG)
    if rest != 0 and rest < TAG:
        return f"the last chunk is cut short ({rest} bytes)"
    return None


def check_backup_head(head: bytes, size: int) -> str | None:
    if size < BACKUP_MIN:
        return f"too small for a backup ({size} bytes)"
    if head[:6] != BACKUP_MAGIC:
        return "not an IraAlgo backup (IRABK3) header"
    return None


def live_name_problem(tag: str, name: str) -> str | None:
    m, t = LIVE_NAME.match(name), LIVE_TAG.match(tag)
    if not m:
        return "not a live-YYYY-MM-DD.irlive name"
    try:
        day = dt.date.fromisoformat(m.group(1))
    except ValueError:
        return "not a real date"
    if not t or (day.year, day.month) != (int(t.group(1)), int(t.group(2))):
        return f"in release {tag}, not live-{day:%Y-%m}"
    return None


def weekday_gaps(days: Iterable[dt.date], today: dt.date, span: int = GAP_DAYS) -> list[dt.date]:
    """Weekdays of the ``span`` days before ``today`` with no live file, from the first day ever uploaded."""
    have = set(days)
    if not have:
        return []
    first = min(have)
    out = []
    for i in range(span, 0, -1):
        d = today - dt.timedelta(days=i)
        if d >= first and d.weekday() < 5 and d not in have:
            out.append(d)
    return out


# ---- the catalogue --------------------------------------------------------------------------------------------------

ABOUT = ("Every file the phone uploaded to this data repository that passed the nightly check (verify_uploads.py): the "
         "recorded days (kind live) and the phone backups (kind backup), sealed with Boss's backup passphrase. The Data "
         "Brain reads its live days from here (options-lab: research/data/ARCHITECTURE.md, L1).")


def load_catalog(path: Path) -> dict:
    """The last catalogue (this repository's CATALOG.json), loaded before anything is downloaded."""
    if path.exists():
        c = json.loads(path.read_text())
        c.setdefault("files", [])
        return c
    return {"version": 1, "about": ABOUT, "files": []}


def save_catalog(path: Path, c: dict) -> None:
    c["files"].sort(key=lambda e: (e.get("kind", ""), e.get("day", ""), e.get("asset", "")))
    path.write_text(json.dumps(c, indent=2, sort_keys=False) + "\n")


# ---- GitHub ---------------------------------------------------------------------------------------------------------

class GitHub:
    """Reads releases and downloads assets (a redirect to GitHub's file store is followed without the token)."""

    def __init__(self, token: str, repo: str = REPO):
        if repo.split("/")[-1].lower() == CODE_REPO:
            raise ValueError("the phone's uploads are never in options-lab")
        self.token, self.repo = token, repo

    def _req(self, url: str, accept: str = "application/vnd.github+json", auth: bool = True):
        if not url.startswith("https://"):
            raise OSError("refusing a request that is not HTTPS")
        h = {"Accept": accept, "User-Agent": "IraAlgo-verify", "X-GitHub-Api-Version": "2022-11-28"}
        if auth:
            h["Authorization"] = f"Bearer {self.token}"
        return urllib.request.Request(url, headers=h)

    def _pages(self, url: str) -> list[dict]:
        out, page = [], 1
        while True:
            with urllib.request.urlopen(self._req(f"{url}?per_page=100&page={page}"), timeout=60) as r:
                got = json.load(r)
            out += got
            if len(got) < 100:
                return out
            page += 1

    def releases(self) -> list[dict]:
        return self._pages(f"https://api.github.com/repos/{self.repo}/releases")

    def assets(self, release_id: int) -> list[dict]:
        return self._pages(f"https://api.github.com/repos/{self.repo}/releases/{release_id}/assets")

    def download(self, asset_id: int, dest: Path, limit: int | None = None) -> None:
        """The asset into ``dest`` (only its first ``limit`` bytes when given)."""
        opener = urllib.request.build_opener(_NoRedirect)
        url = f"https://api.github.com/repos/{self.repo}/releases/assets/{asset_id}"
        try:
            r = opener.open(self._req(url, "application/octet-stream"), timeout=120)
        except urllib.error.HTTPError as e:
            if e.code not in (301, 302, 303, 307, 308):
                raise
            to = e.headers["Location"]
            r = urllib.request.urlopen(self._req(to, "application/octet-stream", auth=False), timeout=600)
        with r, open(dest, "wb") as out:
            left = limit
            while left is None or left > 0:
                b = r.read(1 << 20 if left is None else min(1 << 20, left))
                if not b:
                    break
                out.write(b)
                if left is not None:
                    left -= len(b)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):  # the token must not follow a redirect to the file store
        return None


# ---- the run --------------------------------------------------------------------------------------------------------

@dataclass
class Report:
    failed: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    added: list[str] = field(default_factory=list)
    backups_added: list[str] = field(default_factory=list)
    still: int = 0
    downloads: int = 0
    pruned: int = 0
    backups: int = 0
    newest_backup: dt.datetime | None = None

    def markdown(self) -> str:
        lines = ["## Phone uploads (nightly check)", ""]
        lines.append(f"- Live days checked and added to the catalogue: {len(self.added)}" + (f" ({', '.join(self.added)})" if self.added else ""))
        lines.append(f"- Files already catalogued, still there (not downloaded again): {self.still}")
        lines.append(f"- New phone backups checked: {len(self.backups_added)}; on GitHub: {self.backups}" +
                     (f", newest {self.newest_backup:%Y-%m-%d %H:%M} IST" if self.newest_backup else ""))
        lines.append(f"- Downloads tonight: {self.downloads}")
        if self.failed:
            lines += ["", "### Failed (not catalogued)", ""] + [f"- {x}" for x in self.failed]
        if self.warnings:
            lines += ["", "### Warnings", ""] + [f"- {x}" for x in self.warnings]
        if not self.failed and not self.warnings:
            lines += ["", "Everything checked out."]
        return "\n".join(lines) + "\n"


def _known(old: dict | None, a: dict) -> bool:
    return bool(old) and old.get("asset_id") == a["id"] and old.get("bytes") == a["size"]


def _replace(catalog: dict, old: dict | None, entry: dict) -> None:
    if old:
        entry["replaced"] = old.get("replaced", 0) + 1
        catalog["files"].remove(old)
    catalog["files"].append(entry)


def run(gh: GitHub, catalog: dict, now: dt.datetime, work: Path) -> Report:
    rep = Report()
    known_live = {e["asset"]: e for e in catalog["files"] if e.get("kind") == "live"}
    known_backup = {e["asset"]: e for e in catalog["files"] if e.get("kind") == "backup"}
    seen_days: list[dt.date] = []
    listed: set[str] = set()
    checked_at = now.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    for rel in gh.releases():
        tag = rel.get("tag_name", "")
        if LIVE_TAG.match(tag):
            for a in gh.assets(rel["id"]):
                name = a["name"]
                if name.endswith(TEMP_SUFFIX):
                    rep.warnings.append(f"{tag}/{name}: a replacement the phone left half done (it finishes on its next upload)")
                    continue
                listed.add(name)
                why = live_name_problem(tag, name)
                if why:
                    rep.failed.append(f"{tag}/{name}: {why}")
                    continue
                day = dt.date.fromisoformat(LIVE_NAME.match(name).group(1))
                seen_days.append(day)
                old = known_live.get(name)
                if _known(old, a):
                    rep.still += 1
                    continue
                dest = work / name
                try:
                    rep.downloads += 1
                    gh.download(a["id"], dest)
                    with open(dest, "rb") as f:
                        problem, sha = check_live_stream(f, a["size"])
                except (OSError, urllib.error.URLError) as e:
                    problem, sha = f"could not download it ({type(e).__name__})", ""
                finally:
                    dest.unlink(missing_ok=True)
                if problem:
                    rep.failed.append(f"{tag}/{name}: {problem}")
                    continue
                _replace(catalog, old, {"kind": "live", "source": "phone (TODO G1)", "release": tag, "asset": name,
                                        "asset_id": a["id"], "day": day.isoformat(), "bytes": a["size"], "sha256": sha,
                                        "uploaded_at": a.get("updated_at") or a.get("created_at"), "checked_at": checked_at,
                                        "check": "structure"})
                rep.added.append(day.isoformat())
        elif tag == BACKUP_TAG:
            for a in gh.assets(rel["id"]):
                name = a["name"]
                if name.endswith(TEMP_SUFFIX):
                    rep.warnings.append(f"{tag}/{name}: a replacement the phone left half done (it finishes on its next upload)")
                    continue
                m = BACKUP_NAME.match(name)
                if not m:
                    rep.warnings.append(f"{tag}/{name}: not a phone-backup name (left alone)")
                    continue
                listed.add(name)
                at = dt.datetime.strptime(m.group(1) + m.group(2), "%Y-%m-%d%H%M%S").replace(tzinfo=IST)
                old = known_backup.get(name)
                if _known(old, a):
                    rep.still += 1
                else:
                    dest = work / name
                    try:
                        rep.downloads += 1
                        gh.download(a["id"], dest, limit=16)
                        problem = check_backup_head(dest.read_bytes(), a["size"])
                    except (OSError, urllib.error.URLError) as e:
                        problem = f"could not download it ({type(e).__name__})"
                    finally:
                        dest.unlink(missing_ok=True)
                    if problem:
                        rep.failed.append(f"{tag}/{name}: {problem}")
                        continue
                    _replace(catalog, old, {"kind": "backup", "release": tag, "asset": name, "asset_id": a["id"],
                                            "day": at.date().isoformat(), "bytes": a["size"], "checked_at": checked_at,
                                            "check": "header"})
                    rep.backups_added.append(name)
                rep.backups += 1
                rep.newest_backup = max(rep.newest_backup or at, at)
    for name, e in known_live.items():
        if name not in listed:
            rep.warnings.append(f"{e['release']}/{name}: catalogued but no longer on GitHub (deleted by hand?)")
    # Backups the phone pruned (it keeps 30 daily and one a month) simply leave the catalogue.
    gone = [e for n, e in known_backup.items() if n not in listed]
    for e in gone:
        catalog["files"].remove(e)
    if rep.newest_backup and (now - rep.newest_backup).days >= STALE_BACKUP_DAYS:
        rep.warnings.append(f"the newest phone backup is from {rep.newest_backup:%Y-%m-%d}: is the phone charging on Wi-Fi in the evening?")
    today = now.astimezone(IST).date()
    gaps = weekday_gaps(seen_days + [dt.date.fromisoformat(e["day"]) for e in known_live.values()], today)
    if gaps:
        rep.warnings.append("weekdays with no live file (holidays excepted): " + ", ".join(d.isoformat() for d in gaps))
    rep.pruned = len(gone)
    return rep


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Check the phone's GitHub uploads and update the catalogue.")
    ap.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parent / "CATALOG.json")
    ap.add_argument("--summary", type=Path, default=None, help="write the report here (Markdown) as well")
    a = ap.parse_args(argv)
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        print("GITHUB_TOKEN is not set", file=sys.stderr)
        return 2
    catalog = load_catalog(a.catalog)
    before = json.dumps(catalog, sort_keys=True)
    with tempfile.TemporaryDirectory() as work:
        rep = run(GitHub(token), catalog, dt.datetime.now(dt.timezone.utc), Path(work))
    if json.dumps(catalog, sort_keys=True) != before or not a.catalog.exists():
        save_catalog(a.catalog, catalog)
    text = rep.markdown()
    print(text)
    if a.summary:
        a.summary.write_text(text)
    return 1 if rep.failed else 0


if __name__ == "__main__":
    sys.exit(main())
