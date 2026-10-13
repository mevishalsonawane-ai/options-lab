"""L1 raw: get Binance bulk-archive files into the local raw mirror (never into git).

    python -m research.brain.crypto.fetch sample --symbols BTCUSDT ETHUSDT --from 2025-10 --to 2026-03
    python -m research.brain.crypto.fetch drive --remote gdrive:IraAlgo-crypto-lake --symbols BTCUSDT --from 2024-01 --to 2024-03

`sample` downloads straight from data.binance.vision (reachable here; api.binance.com is geo-blocked) and checks each
file against Binance's own .CHECKSUM (sha256). `drive` copies the same months from the crypto lake on Google Drive
with rclone (remote `gdrive`, configured by the RCLONE_CONFIG_GDRIVE_* variables), so only the months being built are
ever on disk. Both write the archive's own layout under <cache>/crypto/raw/ (data/spot/..., data/futures/um/...),
which is what adapters.ArchiveAdapter reads.

Neither fetches a month on or after the locked holdout (config.HOLDOUT_START) unless --allow-holdout is given: design
work must not see it.
"""
from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pandas as pd

from . import config

BASE = "https://data.binance.vision/"
MONTHLY = {
    "spot": "data/spot/monthly/klines/{s}/1m/{s}-1m-{m}.zip",
    "perp": "data/futures/um/monthly/klines/{s}/1m/{s}-1m-{m}.zip",
    "mark": "data/futures/um/monthly/markPriceKlines/{s}/1m/{s}-1m-{m}.zip",
    "index": "data/futures/um/monthly/indexPriceKlines/{s}/1m/{s}-1m-{m}.zip",
    "premium": "data/futures/um/monthly/premiumIndexKlines/{s}/1m/{s}-1m-{m}.zip",
    "funding": "data/futures/um/monthly/fundingRate/{s}/{s}-fundingRate-{m}.zip",
}
DAILY = {"metrics": "data/futures/um/daily/metrics/{s}/{s}-metrics-{d}.zip"}
KINDS = tuple(MONTHLY) + tuple(DAILY)


def months(frm: str, to: str, allow_holdout: bool = False) -> list[str]:
    """YYYY-MM strings from frm to to inclusive, cut before the holdout month unless allowed."""
    ms = [str(p) for p in pd.period_range(frm, to, freq="M")]
    if not allow_holdout:
        ms = [m for m in ms if pd.Period(m, "M").start_time < config.HOLDOUT_START]
    return ms


def plan(symbols, frm: str, to: str, kinds=KINDS, allow_holdout: bool = False) -> list[str]:
    """Archive paths (relative to BASE) for every symbol, kind and month/day."""
    out = []
    for s in symbols:
        for m in months(frm, to, allow_holdout):
            for k in kinds:
                if k in MONTHLY:
                    out.append(MONTHLY[k].format(s=s, m=m))
                else:
                    for d in pd.date_range(pd.Period(m, "M").start_time, pd.Period(m, "M").end_time, freq="D"):
                        if allow_holdout or d < config.HOLDOUT_START:
                            out.append(DAILY[k].format(s=s, d=d.date()))
    return out


def _get(url: str) -> bytes | None:
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def fetch_one(rel: str, raw: Path, retries: int = 3) -> str:
    dest = raw / rel
    if dest.exists() and dest.stat().st_size > 0:
        return "have"
    for k in range(retries):
        try:
            body = _get(BASE + rel)
            if body is None:
                return "missing"           # the archive has no file (symbol not listed yet, or a day without data)
            chk = _get(BASE + rel + ".CHECKSUM")
            if chk is not None and chk.split()[0].decode() != hashlib.sha256(body).hexdigest():
                raise ValueError("checksum mismatch")
            dest.parent.mkdir(parents=True, exist_ok=True)
            tmp = dest.with_suffix(".part")
            tmp.write_bytes(body)
            tmp.replace(dest)
            return "got"
        except (urllib.error.URLError, ValueError, TimeoutError, OSError):
            if k == retries - 1:
                return "failed"
    return "failed"


def sample(symbols, frm, to, kinds=KINDS, root=None, allow_holdout=False, workers=8) -> dict:
    raw = config.paths(root).raw
    rels = plan(symbols, frm, to, kinds, allow_holdout)
    with ThreadPoolExecutor(workers) as ex:
        res = list(ex.map(lambda r: fetch_one(r, raw), rels))
    counts = {k: res.count(k) for k in ("got", "have", "missing", "failed")}
    print(f"{len(rels)} archive files -> {raw}: {counts}")
    for r, s in zip(rels, res):
        if s == "failed":
            print("  failed:", r)
    return counts


def drive(remote: str, symbols, frm, to, root=None, allow_holdout=False) -> int:
    """Copy the selected months of each symbol from the Drive lake (any folder layout; matched by file name)."""
    raw = config.paths(root).raw
    raw.mkdir(parents=True, exist_ok=True)
    inc = []
    for s in symbols:
        for m in months(frm, to, allow_holdout):
            inc += ["--include", f"**/{s}-*{m}*"]
    cmd = ["rclone", "copy", remote, str(raw), *inc, "--transfers", "8"]
    print(" ".join(cmd[:4]), f"(+{len(inc) // 2} patterns)")
    return subprocess.call(cmd)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.fetch")
    ap.add_argument("cmd", choices=["sample", "drive"])
    ap.add_argument("--symbols", nargs="+", default=["BTCUSDT", "ETHUSDT"])
    ap.add_argument("--from", dest="frm", required=True, help="first month, YYYY-MM")
    ap.add_argument("--to", required=True, help="last month, YYYY-MM")
    ap.add_argument("--kinds", nargs="+", default=list(KINDS), choices=list(KINDS))
    ap.add_argument("--remote", default="gdrive:IraAlgo-crypto-lake")
    ap.add_argument("--cache", default=None)
    ap.add_argument("--allow-holdout", action="store_true", help="also fetch months in the locked holdout")
    a = ap.parse_args(argv)
    if a.cmd == "sample":
        c = sample(a.symbols, a.frm, a.to, a.kinds, a.cache, a.allow_holdout)
        return 1 if c["failed"] else 0
    return drive(a.remote, a.symbols, a.frm, a.to, a.cache, a.allow_holdout)


if __name__ == "__main__":
    sys.exit(main())
