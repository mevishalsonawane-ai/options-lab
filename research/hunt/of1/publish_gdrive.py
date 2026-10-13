#!/usr/bin/env python3
"""OF1: copy the fetched NSE / NSDL files to Google Drive, under the data lake's folder, in a `participants/` area.

    python3 -I research/hunt/of1/publish_gdrive.py [--dest gdrive:IraAlgo-data-lake/participants] [--dry-run]

Layout on Drive (research/data/README.md on branch claude/data-lake-gdrive: the lake is gdrive:IraAlgo-data-lake):
  participants/oi/fao_participant_oi_DDMMYYYY.csv      NSE originals, unchanged
  participants/vol/fao_participant_vol_DDMMYYYY.csv
  participants/indices/ind_close_all_DDMMYYYY.csv      NSE all-index closing files
  participants/fii_stats/fii_stats_DD-Mon-YYYY.xls     NSE FII derivatives statistics (only the years fetched)
  participants/fpi/fpi_daily.csv                       NSDL daily FPI flows (parsed; the monthly pages are not kept)
  participants/bhav_index/bhav_index_YYYY.parquet      the F&O bhavcopy cut to index futures/options, one file a year
  participants/derived/*.parquet                       build.py's tables (pools, nearest-expiry chains, finals)
  participants/MANIFEST.txt                            path, bytes, sha256 of every file above
  participants/README.txt                              what this is and where the code lives

The rclone remote `gdrive` comes from the environment (RCLONE_CONFIG_GDRIVE_*); no token is read or printed here.
Each folder is copied with --checksum and then `rclone check`ed one-way; a failed check exits 1.
"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import load as LD  # noqa: E402

WORK = LD.CACHE / "of1" / "work"
README = """IraAlgo data lake: participants/ (OF1 order-flow forensics, 13 Oct 2026)

Public NSE and NSDL daily files, fetched by research/hunt/of1/fetch.py and fetch_fpi.py in
github.com/mevishalsonawane-ai/options-lab (branch claude/orderflow-forensics-india). NSE data is anonymous: it
reports participant TYPES (Client, DII, FII, Pro) only, never a person or firm.

oi/, vol/      NSE participant-wise open interest / volume (contracts), 2 Jan 2012 onward, unchanged
indices/       NSE all-index closing files (NIFTY 50, NIFTY BANK, India VIX, ...)
fii_stats/     NSE FII derivatives statistics (partial: only the years fetched)
fpi/           NSDL daily FPI equity flows by REPORTING date (trades of the previous session)
bhav_index/    F&O bhavcopy rows for index futures and options only, one parquet a year (OI in units, volume in
               contracts; settle on an option's expiry day = the underlying's final settlement, as NSE prints it)
derived/       build.py outputs: pools.parquet, chain_near.parquet, finals.parquet, fut_expiries.json
MANIFEST.txt   path, bytes, sha256

DII cash flows are not here: NSE publishes them only on its www API, which refused the fetching machine.
"""


def sha(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for b in iter(lambda: fh.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def stage(tmp: Path) -> Path:
    """Builds the upload tree in `tmp` (hard links or copies of the raw files, yearly bhav parquet)."""
    raw = LD.RAW
    root = tmp / "participants"
    for sub, dst in (("participants/oi", "oi"), ("participants/vol", "vol"), ("indices", "indices"), ("fii_stats", "fii_stats")):
        d = root / dst
        d.mkdir(parents=True, exist_ok=True)
        for f in sorted((raw / sub).glob("*")):
            if f.suffix in (".csv", ".xls", ".xlsx") and not f.name.endswith(".missing"):
                shutil.copy2(f, d / f.name)
    (root / "fpi").mkdir(parents=True, exist_ok=True)
    if (raw / "fpi" / "fpi_daily.csv").exists():
        shutil.copy2(raw / "fpi" / "fpi_daily.csv", root / "fpi" / "fpi_daily.csv")
    bd = root / "bhav_index"
    bd.mkdir(parents=True, exist_ok=True)
    days = LD.bhav_days()
    for y in sorted({d.year for d in days}):
        parts = [LD.bhav(d) for d in days if d.year == y]
        df = pd.concat(parts, ignore_index=True)
        df["expiry"] = pd.to_datetime(df.expiry)
        df["date"] = pd.to_datetime(df.date)
        df.to_parquet(bd / f"bhav_index_{y}.parquet", index=False, compression="zstd")
    dd = root / "derived"
    dd.mkdir(parents=True, exist_ok=True)
    for f in ("pools.parquet", "chain_near.parquet", "finals.parquet", "fut_expiries.json", "build_log.json",
              "group_pnl_daily_design.csv.gz", "group_pnl_daily_holdout.csv.gz"):
        if (WORK / f).exists():
            shutil.copy2(WORK / f, dd / f)
    (root / "README.txt").write_text(README)
    lines = [f"{p.relative_to(root)}\t{p.stat().st_size}\t{sha(p)}" for p in sorted(root.rglob("*")) if p.is_file()]
    (root / "MANIFEST.txt").write_text("path\tbytes\tsha256\n" + "\n".join(lines) + "\n")
    return root


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", default="gdrive:IraAlgo-data-lake/participants")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    with tempfile.TemporaryDirectory(dir=LD.CACHE / "of1") as t:
        root = stage(Path(t))
        n = sum(1 for p in root.rglob("*") if p.is_file())
        size = sum(p.stat().st_size for p in root.rglob("*") if p.is_file())
        print(f"staged {n} files, {size / 1e6:,.1f} MB")
        if a.dry_run:
            return 0
        env_flags = ["--checksum", "--transfers", "8", "--log-level", "ERROR"]
        r = subprocess.run(["rclone", "copy", str(root), a.dest, *env_flags])
        if r.returncode:
            return r.returncode
        c = subprocess.run(["rclone", "check", str(root), a.dest, "--one-way", "--log-level", "ERROR"])
        print("rclone check:", "OK" if c.returncode == 0 else "FAILED")
        return c.returncode


if __name__ == "__main__":
    sys.exit(main())
