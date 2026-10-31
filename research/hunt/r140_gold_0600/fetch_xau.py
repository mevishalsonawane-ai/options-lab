"""R140: XAUUSD 1-minute BID bars from Dukascopy day files, throttled (Dukascopy answers 429 to 8 parallel requests).
Same source and decoder as research/hunt/r97_gold_reverse/fetch_xau.py; one small file per day so a stopped run
resumes; output stays outside git (IRAALGO_CACHE).

    python -m research.hunt.r140_gold_0600.fetch_xau <start> <end> <out.parquet> [workers]
"""
from __future__ import annotations

import datetime as dt
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pandas as pd

from research.hunt.r97_gold_reverse import fetch_xau as F


def slow_day(day):
    if day.weekday() == 5:                       # Saturday: gold is shut
        return day, []
    p = F.DAY_DIR / f"{day.isoformat()}.pkl"
    if p.exists():
        return day, pd.read_pickle(p)
    time.sleep(0.4)
    return F.one_day(day)


def main(a, b, out, workers=2):
    F.DAY_DIR = Path(out).parent / "xau_days"
    F.DAY_DIR.mkdir(parents=True, exist_ok=True)
    days = [a + dt.timedelta(days=k) for k in range((b - a).days + 1)]
    rows, failed = [], []
    with ThreadPoolExecutor(workers) as ex:
        for day, r in ex.map(slow_day, days):
            if r is None:
                failed.append(day)
            else:
                rows += r
    df = pd.DataFrame(rows, columns=["ms", "open", "high", "low", "close", "volume"])
    df["ts_utc"] = pd.to_datetime(df.ms, unit="ms")
    df = df.drop(columns="ms").drop_duplicates("ts_utc").sort_values("ts_utc").reset_index(drop=True)
    df.to_parquet(out, index=False)
    print(len(df), df.ts_utc.min(), df.ts_utc.max(), "failed days:", len(failed), [d.isoformat() for d in failed][:20])


if __name__ == "__main__":
    main(dt.date.fromisoformat(sys.argv[1]), dt.date.fromisoformat(sys.argv[2]), sys.argv[3],
         int(sys.argv[4]) if len(sys.argv) > 4 else 2)
