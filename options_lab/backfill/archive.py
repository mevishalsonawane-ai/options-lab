"""Read single sessions out of the Kaggle options archive over HTTP Range.

    samardubey/niftybanknifty-options-data - 4.10 GB, MIT, no account needed.

A ZIP's central directory lives at the end of the file, so the whole member
list reads in three Range requests (~0.2 MiB) and any one session can then be
pulled on its own (~2 MB compressed). Nothing here downloads the archive.

This is the ONLY free source with expired contracts, which makes it the only
way to study near-expiry regimes: forward collection from a broker can never
return a chain that has already settled, so a live-API backfill bottoms out at
whatever DTE its still-listed contracts happen to have.

Provenance note: the uploader asserts MIT over data that was almost certainly
scraped from a broker feed (the 3-minute OI cadence is the tell). Fine for
private research; not a clean chain of title for redistribution.
"""
from __future__ import annotations

import io
import re
import time
import zipfile
from datetime import date

import requests

SLUG = "samardubey/niftybanknifty-options-data"
UA = {"User-Agent": "Mozilla/5.0"}
CHUNK = 4 << 20          # an unbounded read asks for all 4.1 GB and times out
URL_TTL = 150            # the signed GCS URL expires; refresh well inside it

FOLDERS = {
    "NIFTY": "filtered_feather_folder/filtered_feather_folder",
    "BANKNIFTY": "BANK_filtered_feather_folder/BANK_filtered_feather_folder",
}
SUFFIX = "-index-nfo-data.feather"
_MEMBER = re.compile(r"(\d{4}-\d{2}-\d{2})" + re.escape(SUFFIX) + r"$")


def member_path(underlying: str, day: date) -> str:
    """In-archive path for one session of one underlying."""
    try:
        folder = FOLDERS[underlying]
    except KeyError:
        raise ValueError(
            f"unknown underlying {underlying!r}; archive holds {sorted(FOLDERS)}"
        ) from None
    return f"{folder}/{day:%Y-%m-%d}{SUFFIX}"


def available_days(namelist: list[str], underlying: str) -> list[date]:
    """Sessions present for `underlying`, ignoring the archive's stray files."""
    folder = FOLDERS[underlying]
    days = []
    for name in namelist:
        head, _, tail = name.rpartition("/")
        if head != folder:
            continue
        m = _MEMBER.match(tail)
        if m:
            days.append(date.fromisoformat(m.group(1)))
    return sorted(days)


def signed_url() -> str:
    r = requests.get(f"https://www.kaggle.com/api/v1/datasets/download/{SLUG}",
                     headers=UA, allow_redirects=False, timeout=60)
    r.raise_for_status()
    return r.headers["location"]


class RemoteZip(io.RawIOBase):
    """Seekable read-only file over HTTP Range, with URL refresh and retries."""

    def __init__(self) -> None:
        self.url = signed_url()
        self.stamp = time.time()
        self.size = int(requests.head(self.url, headers=UA, timeout=60)
                        .headers["content-length"])
        self.pos = 0
        self.requests_made = 0

    def _url(self) -> str:
        if time.time() - self.stamp > URL_TTL:
            self.url = signed_url()
            self.stamp = time.time()
        return self.url

    def _get(self, start: int, end: int) -> bytes:
        last: Exception | None = None
        for attempt in range(4):
            try:
                r = requests.get(self._url(),
                                 headers={**UA, "Range": f"bytes={start}-{end}"},
                                 timeout=90, stream=True)
                r.raise_for_status()
                buf = b"".join(r.iter_content(1 << 18))
                self.requests_made += 1
                return buf
            except Exception as exc:            # noqa: BLE001 - retry transport
                last = exc
                time.sleep(1.5 * (attempt + 1))
                self.url, self.stamp = signed_url(), time.time()
        raise RuntimeError(f"range {start}-{end} failed after 4 tries") from last

    def seekable(self) -> bool:
        return True

    def readable(self) -> bool:
        return True

    def seek(self, offset: int, whence: int = 0) -> int:
        self.pos = (offset if whence == 0
                    else self.pos + offset if whence == 1
                    else self.size + offset)
        return self.pos

    def tell(self) -> int:
        return self.pos

    def read(self, n: int = -1) -> bytes:
        remaining = self.size - self.pos
        want = remaining if n is None or n < 0 else min(n, remaining)
        out = bytearray()
        while want > 0:
            take = min(want, CHUNK)
            out += self._get(self.pos, self.pos + take - 1)
            self.pos += take
            want -= take
        return bytes(out)

    def readinto(self, b) -> int:
        data = self.read(len(b))
        b[:len(data)] = data
        return len(data)


def open_archive() -> zipfile.ZipFile:
    """Open the archive for member access. Costs ~3 Range requests."""
    return zipfile.ZipFile(RemoteZip())


def read_session(zf: zipfile.ZipFile, underlying: str, day: date):
    """One session's option chain as a DataFrame."""
    import pandas as pd

    with zf.open(member_path(underlying, day)) as fh:
        return pd.read_feather(io.BytesIO(fh.read()))
