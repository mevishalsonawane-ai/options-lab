"""R78 data: the research-data release's XAUUSD 1-minute BID file (2 Oct 2023 - 30 Sep 2026) plus real Dukascopy day
files for (a) every weekday the release lacks and (b) 1 - 16 Oct 2026, decoded by R53's own `decode()`. The release's rows
win on any duplicate timestamp. Nothing is forward-filled or invented; an empty day file stays missing.

    python3 -m research.hunt.r78.fetch_fill <release xauusd_m1_bid.csv.gz> <out.csv.gz> [last YYYY-MM-DD]
"""
from __future__ import annotations

import datetime as dt
import json
import lzma
import sys
import time

import pandas as pd

from research.hunt.r53.fetch_xau_tail import URL, decode, get


def missing_weekdays(ts_ms: pd.Series, first: dt.date, last: dt.date) -> list:
    have = set(pd.to_datetime(ts_ms, unit="ms").dt.date.unique())
    out, d = [], first
    while d <= last:
        if d.weekday() < 5 and d not in have:
            out.append(d)
        d += dt.timedelta(days=1)
    return out


def main(src, out, last="2026-10-16"):
    base = pd.read_csv(src)
    first = pd.to_datetime(base.timestamp.min(), unit="ms").date()
    last_d = dt.date.fromisoformat(last)
    days = missing_weekdays(base.timestamp, first, last_d)
    rows, log = [], {}
    for day in days:
        b = get(URL.format(y=day.year, m=day.month - 1, d=day.day))
        if b is None:
            raise SystemExit(f"failed {day}")
        r = decode(lzma.decompress(b), day) if b else []
        log[str(day)] = len(r)
        rows += r
        time.sleep(0.4)
    ext = pd.DataFrame(rows, columns=["timestamp", "open", "high", "low", "close", "volume"])
    allx = pd.concat([base, ext]).drop_duplicates("timestamp").sort_values("timestamp")
    allx.to_csv(out, index=False, compression="gzip")
    info = dict(release_rows=len(base), added_rows=len(ext), days_fetched=len(days),
                empty_days=[k for k, v in log.items() if v == 0], per_day=log,
                last_minute=str(pd.to_datetime(allx.timestamp.max(), unit="ms")))
    print(json.dumps({k: v for k, v in info.items() if k != "per_day"}))
    return info


if __name__ == "__main__":
    info = main(*sys.argv[1:])
    with open(sys.argv[2] + ".fill.json", "w") as f:
        json.dump(info, f, indent=1)
