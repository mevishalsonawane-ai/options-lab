"""R97: XAUUSD 1-minute BID bars from Dukascopy day files (the same source and decoder as research/hunt/r53/
fetch_xau_tail.py; fixed-width LZMA records only, raw bytes never kept). Output stays outside git.

    python -m research.hunt.r97_gold_reverse.fetch_xau <start YYYY-MM-DD> <end YYYY-MM-DD> <out.parquet>
"""
from __future__ import annotations

import datetime as dt
import lzma
import sys
from concurrent.futures import ThreadPoolExecutor

from pathlib import Path

import pandas as pd

from research.hunt.r53.fetch_xau_tail import URL, decode, get


DAY_DIR = None     # set by fetch(): one small file per fetched day, so an interrupted run resumes


def one_day(day: dt.date):
    p = DAY_DIR / f"{day.isoformat()}.pkl" if DAY_DIR is not None else None
    if p is not None and p.exists():
        return day, pd.read_pickle(p)
    # Dukascopy months are 0-based in the URL
    raw = get(URL.format(y=day.year, m=day.month - 1, d=day.day))
    if raw is None:
        return day, None
    rows = decode(lzma.decompress(raw), day) if raw else []
    if p is not None:
        pd.to_pickle(rows, p)
    return day, rows


def fetch(start: dt.date, end: dt.date, day_dir=None) -> pd.DataFrame:
    global DAY_DIR
    if day_dir is not None:
        DAY_DIR = Path(day_dir)
        DAY_DIR.mkdir(parents=True, exist_ok=True)
    days = [start + dt.timedelta(days=k) for k in range((end - start).days + 1)]
    rows, failed = [], []
    with ThreadPoolExecutor(8) as ex:
        for day, r in ex.map(one_day, days):
            if r is None:
                failed.append(day)
            else:
                rows += r
    df = pd.DataFrame(rows, columns=["ms", "open", "high", "low", "close", "volume"])
    df["ts_utc"] = pd.to_datetime(df.ms, unit="ms")
    df = df.drop(columns="ms").drop_duplicates("ts_utc").sort_values("ts_utc").reset_index(drop=True)
    df.attrs["failed_days"] = [d.isoformat() for d in failed]
    return df


if __name__ == "__main__":
    a, b, out = dt.date.fromisoformat(sys.argv[1]), dt.date.fromisoformat(sys.argv[2]), sys.argv[3]
    df = fetch(a, b, Path(out).parent / "xau_days")
    df.to_parquet(out, index=False)
    print(len(df), df.ts_utc.min(), df.ts_utc.max(), "failed days:", df.attrs["failed_days"])
