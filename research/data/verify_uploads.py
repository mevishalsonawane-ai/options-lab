"""The nightly check of the phone's GitHub uploads (TODO G1, 13 Oct 2026).

Every evening the phone uploads each recorded day to the release ``live-YYYY-MM`` (``live-YYYY-MM-DD.irlive``) and a
phone backup to ``phone-backup`` (``phone-backup-YYYY-MM-DD-HHMMSS.irabk``). Each night this job (.github/workflows/
uploads.yml) checks them, without ever needing Boss's passphrase:

* each NEW live file is downloaded once and checked whole: its name, its release (the right month), the IRLIVE01 header
  (magic, key recipe, rounds and chunk size in range) and its chunk layout (every chunk complete, the file not cut short),
  with its size and sha256, and that the bytes downloaded are exactly the size GitHub lists. No key is needed. What they
  cannot see without the key: a change inside the sealed chunks, or a cut that still leaves whole-looking chunks (the
  phone uploads with its exact length, so GitHub's listed size catches a short transfer); the passphrase check below does.
* a live file already in the catalogue is checked against GitHub's listing only (still there, same size); a file the
  phone replaced (the day grew after a late fill) is checked again as new.
* the backups: each one's IRABK3 header and size, and how old the newest is.
* gaps: weekdays of the last 14 days with no live file (a holiday, or the phone not charging on Wi-Fi: listed, not failed).

Optional: when the repository secret ``IRAALGO_BACKUP_PASSPHRASE`` is set (Boss's choice: it puts the passphrase in
GitHub's secret store), each new live file is also opened and every file in it checked against its manifest
(``decrypt_live.py``). Nothing decrypted is kept or printed.

Every live file that passes goes into ``research/data/CATALOG.json`` (release, asset, day, bytes, sha256, when uploaded
and when checked, how it was checked). A failed file is never catalogued and fails the job (a red X and, with the
``NTFY_TOPIC`` secret, a notification). Gaps and an old backup are warnings only.

Usage (the workflow runs it; by hand it needs a token that can read the repository's releases)::

    GITHUB_TOKEN=... python research/data/verify_uploads.py --catalog research/data/CATALOG.json --summary out.md
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
from typing import BinaryIO, Callable, Iterable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import decrypt_live  # noqa: E402

REPO = "mevishalsonawane-ai/options-lab"
BACKUP_TAG = "phone-backup"
LIVE_TAG = re.compile(r"^live-(\d{4})-(\d{2})$")
LIVE_NAME = re.compile(r"^live-(\d{4}-\d{2}-\d{2})\.irlive$")
BACKUP_NAME = re.compile(r"^phone-backup-(\d{4}-\d{2}-\d{2})-(\d{6})\.irabk$")
BACKUP_MAGIC = b"IRABK3"
BACKUP_MIN = 6 + 16 + 12 + 16          # magic, salt, iv, the GCM tag of an empty body
STALE_BACKUP_DAYS = 2
GAP_DAYS = 14
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


# ---- the checks (pure) ----------------------------------------------------------------------------------------------

def check_live_stream(f: BinaryIO, size: int) -> tuple[str | None, str]:
    """(problem or None, sha256) for a sealed live file of ``size`` bytes, read once from ``f``. No key needed."""
    h = hashlib.sha256()
    head = f.read(decrypt_live.HEADER_SIZE)
    h.update(head)
    problem = None
    try:
        _raw, _rounds, _salt, _prefix, chunk = decrypt_live.read_header(_Once(head))
    except decrypt_live.Refused as e:
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
    body = size - decrypt_live.HEADER_SIZE
    if body < decrypt_live.TAG:
        return "no sealed data after the header (cut short)"
    rest = body % (chunk + decrypt_live.TAG)
    if rest != 0 and rest < decrypt_live.TAG:
        return f"the last chunk is cut short ({rest} bytes)"
    return None


class _Once:
    """A tiny stream over bytes already read (read_header wants a file)."""

    def __init__(self, b: bytes):
        self.b = b

    def read(self, n: int) -> bytes:
        out, self.b = self.b[:n], self.b[n:]
        return out


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

def load_catalog(path: Path) -> dict:
    if path.exists():
        c = json.loads(path.read_text())
        c.setdefault("files", [])
        return c
    return {"version": 1,
            "about": "Every raw data file the Data Brain may read (research/data/ARCHITECTURE.md, L1). "
                     "Live files: the phone's recorded days, sealed with Boss's backup passphrase, as GitHub release "
                     "assets; checked nightly by research/data/verify_uploads.py.",
            "files": []}


def save_catalog(path: Path, c: dict) -> None:
    c["files"].sort(key=lambda e: (e.get("kind", ""), e.get("day", ""), e.get("asset", "")))
    path.write_text(json.dumps(c, indent=2, sort_keys=False) + "\n")


# ---- GitHub ---------------------------------------------------------------------------------------------------------

class GitHub:
    """Reads releases and downloads assets (a redirect to GitHub's file store is followed without the token)."""

    def __init__(self, token: str, repo: str = REPO):
        self.token, self.repo = token, repo

    def _req(self, url: str, accept: str = "application/vnd.github+json", auth: bool = True):
        h = {"Accept": accept, "User-Agent": "IraAlgo-verify", "X-GitHub-Api-Version": "2022-11-28"}
        if auth:
            h["Authorization"] = f"Bearer {self.token}"
        return urllib.request.Request(url, headers=h)

    def releases(self) -> list[dict]:
        out, page = [], 1
        while True:
            with urllib.request.urlopen(self._req(f"https://api.github.com/repos/{self.repo}/releases?per_page=100&page={page}"), timeout=60) as r:
                got = json.load(r)
            out += got
            if len(got) < 100:
                return out
            page += 1

    def assets(self, release_id: int) -> list[dict]:
        out, page = [], 1
        while True:
            url = f"https://api.github.com/repos/{self.repo}/releases/{release_id}/assets?per_page=100&page={page}"
            with urllib.request.urlopen(self._req(url), timeout=60) as r:
                got = json.load(r)
            out += got
            if len(got) < 100:
                return out
            page += 1

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
            if not to.startswith("https://"):
                raise IOError("refusing a download that is not HTTPS") from e
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
    still: int = 0
    backups: int = 0
    newest_backup: dt.datetime | None = None

    def markdown(self) -> str:
        lines = ["## Phone uploads (nightly check)", ""]
        lines.append(f"- Live days checked and added to the catalogue: {len(self.added)}" + (f" ({', '.join(self.added)})" if self.added else ""))
        lines.append(f"- Live days already catalogued, still there: {self.still}")
        lines.append(f"- Phone backups on GitHub: {self.backups}" +
                     (f", newest {self.newest_backup:%Y-%m-%d %H:%M} IST" if self.newest_backup else ""))
        if self.failed:
            lines += ["", "### Failed (not catalogued)", ""] + [f"- {x}" for x in self.failed]
        if self.warnings:
            lines += ["", "### Warnings", ""] + [f"- {x}" for x in self.warnings]
        if not self.failed and not self.warnings:
            lines += ["", "Everything checked out."]
        return "\n".join(lines) + "\n"


def run(gh: GitHub, catalog: dict, now: dt.datetime, work: Path,
        passphrase: str | None = None, opener: Callable[[Path, str], list[str]] | None = None) -> Report:
    rep = Report()
    known = {e["asset"]: e for e in catalog["files"] if e.get("kind") == "live"}
    seen_days: list[dt.date] = []
    listed: set[str] = set()
    checked_at = now.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    for rel in gh.releases():
        tag = rel.get("tag_name", "")
        if LIVE_TAG.match(tag):
            for a in gh.assets(rel["id"]):
                name = a["name"]
                listed.add(name)
                why = live_name_problem(tag, name)
                if why:
                    rep.failed.append(f"{tag}/{name}: {why}")
                    continue
                day = dt.date.fromisoformat(LIVE_NAME.match(name).group(1))
                seen_days.append(day)
                old = known.get(name)
                if old and old.get("asset_id") == a["id"] and old.get("bytes") == a["size"]:
                    rep.still += 1
                    continue
                dest = work / name
                try:
                    gh.download(a["id"], dest)
                    with open(dest, "rb") as f:
                        problem, sha = check_live_stream(f, a["size"])
                    how = "structure"
                    if problem is None and passphrase:
                        bad = (opener or open_and_check)(dest, passphrase)
                        problem = "; ".join(bad) if bad else None
                        how = "decrypted"
                except (OSError, urllib.error.URLError) as e:
                    problem, sha = f"could not download it ({type(e).__name__})", ""
                finally:
                    dest.unlink(missing_ok=True)
                if problem:
                    rep.failed.append(f"{tag}/{name}: {problem}")
                    continue
                entry = {"kind": "live", "source": "phone (TODO G1)", "release": tag, "asset": name, "asset_id": a["id"],
                         "day": day.isoformat(), "bytes": a["size"], "sha256": sha,
                         "uploaded_at": a.get("updated_at") or a.get("created_at"), "checked_at": checked_at, "check": how}
                if old:
                    entry["replaced"] = old.get("replaced", 0) + 1
                    catalog["files"].remove(old)
                catalog["files"].append(entry)
                rep.added.append(day.isoformat())
        elif tag == BACKUP_TAG:
            for a in gh.assets(rel["id"]):
                m = BACKUP_NAME.match(a["name"])
                if not m:
                    rep.warnings.append(f"{tag}/{a['name']}: not a phone-backup name (left alone)")
                    continue
                dest = work / a["name"]
                try:
                    gh.download(a["id"], dest, limit=16)
                    problem = check_backup_head(dest.read_bytes(), a["size"])
                except (OSError, urllib.error.URLError) as e:
                    problem = f"could not download it ({type(e).__name__})"
                finally:
                    dest.unlink(missing_ok=True)
                if problem:
                    rep.failed.append(f"{tag}/{a['name']}: {problem}")
                    continue
                rep.backups += 1
                at = dt.datetime.strptime(m.group(1) + m.group(2), "%Y-%m-%d%H%M%S").replace(tzinfo=IST)
                rep.newest_backup = max(rep.newest_backup or at, at)
    for name, e in known.items():
        if name not in listed:
            rep.warnings.append(f"{e['release']}/{name}: catalogued but no longer on GitHub (deleted by hand?)")
    if rep.newest_backup and (now - rep.newest_backup).days >= STALE_BACKUP_DAYS:
        rep.warnings.append(f"the newest phone backup is from {rep.newest_backup:%Y-%m-%d}: is the phone charging on Wi-Fi in the evening?")
    today = now.astimezone(IST).date()
    gaps = weekday_gaps(seen_days + [dt.date.fromisoformat(e["day"]) for e in known.values()], today)
    if gaps:
        rep.warnings.append("weekdays with no live file (holidays excepted): " + ", ".join(d.isoformat() for d in gaps))
    return rep


def open_and_check(path: Path, passphrase: str) -> list[str]:
    """Opens a live file with the passphrase and checks its zip against its manifest; the problems (empty: all fine)."""
    plain = path.with_suffix(".zip")
    try:
        with open(path, "rb") as f, open(plain, "wb") as out:
            decrypt_live.open_stream(f, passphrase, out)
        return decrypt_live.check_manifest(plain)
    except decrypt_live.Refused as e:
        return [str(e)]
    except Exception as e:  # a bad zip
        return [f"could not read the day's zip: {type(e).__name__}"]
    finally:
        plain.unlink(missing_ok=True)


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
    with tempfile.TemporaryDirectory() as work:
        rep = run(GitHub(token), catalog, dt.datetime.now(dt.timezone.utc), Path(work),
                  passphrase=os.environ.get(decrypt_live.ENV) or None)
    if rep.added or not a.catalog.exists():
        save_catalog(a.catalog, catalog)
    text = rep.markdown()
    print(text)
    if a.summary:
        a.summary.write_text(text)
    return 1 if rep.failed else 0


if __name__ == "__main__":
    sys.exit(main())
