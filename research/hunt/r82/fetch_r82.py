"""R82 data fetch (never committed; writes only under the destination folder, e.g. /dev/shm/r82).

- XAGUSD / XAUUSD Dukascopy 1-minute BID day files, decoded by R53's decoder (research/hunt/r53/fetch_xau_tail.py),
  2 Oct 2023 - 16 Oct 2026, one CSV per symbol: timestamp (ms UTC), open, high, low, close, volume.
- Yahoo daily charts (GC=F, SI=F, INR=X, DX-Y.NYB, ^TNX) as raw JSON.

    python3 -m research.hunt.r82.fetch_r82 duka XAGUSD /dev/shm/r82/xagusd_m1_bid.csv.gz
    python3 -m research.hunt.r82.fetch_r82 yahoo /dev/shm/r82/yahoo
"""
from __future__ import annotations

import datetime as dt
import json
import lzma
import sys
import time
import urllib.request
from pathlib import Path

import pandas as pd

from research.hunt.r53.fetch_xau_tail import decode, get

DUKA = "https://datafeed.dukascopy.com/datafeed/{sym}/{y}/{m:02d}/{d:02d}/BID_candles_min_1.bi5"
FIRST, LAST = dt.date(2023, 10, 2), dt.date(2026, 10, 16)
YAHOO = ["GC=F", "SI=F", "INR=X", "DX-Y.NYB", "^TNX"]


def duka(sym: str, out: str, first=FIRST, last=LAST):
    cache = Path(out + ".days")
    cache.mkdir(parents=True, exist_ok=True)
    days, d = [], first
    while d <= last:
        if d.weekday() < 5:
            days.append(d)
        d += dt.timedelta(days=1)
    for _ in range(10):                      # the source rate-limits; failed days are retried in later passes
        todo = [d for d in days if not (cache / f"{d}.json").exists()]
        if not todo:
            break
        for d in todo:
            b = get(DUKA.format(sym=sym, y=d.year, m=d.month - 1, d=d.day), tries=3)
            if b is None:
                time.sleep(5)
                continue
            rows = decode(lzma.decompress(b), d) if b else []
            (cache / f"{d}.json").write_text(json.dumps(rows))
            time.sleep(0.3)
    rows = []
    for d in days:
        p = cache / f"{d}.json"
        if p.exists():
            rows += json.loads(p.read_text())
    df = pd.DataFrame(rows, columns=["timestamp", "open", "high", "low", "close", "volume"])
    df = df.drop_duplicates("timestamp").sort_values("timestamp")
    df.to_csv(out, index=False, compression="gzip")
    miss = [str(d) for d in days if not (cache / f"{d}.json").exists()]
    print(sym, len(df), "rows; days not fetched:", len(miss), miss[:10])


def yahoo(dest: str):
    Path(dest).mkdir(parents=True, exist_ok=True)
    for s in YAHOO:
        url = f"https://query1.finance.yahoo.com/v8/finance/chart/{s}?period1=946684800&period2=1792000000&interval=1d"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        raw = urllib.request.urlopen(req, timeout=60).read()
        json.loads(raw)
        (Path(dest) / f"{s.replace('^', '')}.json").write_bytes(raw)
        print(s, len(raw))


DAY = "https://datafeed.dukascopy.com/datafeed/{sym}/{y}/BID_candles_day_1.bi5"


def duka_days(sym: str, out: str, first_year=2003, last_year=2026):
    """Dukascopy daily BID candles (UTC days), one request a year; weekday rows with volume only."""
    rows = []
    for y in range(first_year, last_year + 1):
        b = None
        for _ in range(6):
            b = get(DAY.format(sym=sym, y=y), tries=3)
            if b is not None:
                break
            time.sleep(10)
        if not b:
            print(sym, y, "missing")
            continue
        rows += decode(lzma.decompress(b), dt.date(y, 1, 1))
        time.sleep(1)
    df = pd.DataFrame(rows, columns=["timestamp", "open", "high", "low", "close", "volume"])
    df["date"] = pd.to_datetime(df.timestamp, unit="ms").dt.date
    df = df[pd.to_datetime(df.date).dt.weekday < 5].drop_duplicates("date").sort_values("date")
    df.to_csv(out, index=False)
    print(sym, len(df), df.date.min(), df.date.max())


HOURLY = ["GC=F", "SI=F", "INR=X"]


def yahoo_hourly(dest: str):
    """Yahoo 1-hour charts for the last 729 days (Yahoo's limit), raw JSON."""
    Path(dest).mkdir(parents=True, exist_ok=True)
    now = int(time.time())
    for s in HOURLY:
        url = (f"https://query1.finance.yahoo.com/v8/finance/chart/{s}?period1={now - 729 * 86400}&period2={now}"
               f"&interval=1h&includePrePost=true")
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        raw = urllib.request.urlopen(req, timeout=60).read()
        json.loads(raw)
        (Path(dest) / f"{s}_1h.json").write_bytes(raw)
        print(s, len(raw))


if __name__ == "__main__":
    {"duka": lambda: duka(sys.argv[2], sys.argv[3]), "days": lambda: duka_days(sys.argv[2], sys.argv[3]),
     "hourly": lambda: yahoo_hourly(sys.argv[2]), "yahoo": lambda: yahoo(sys.argv[2])}[sys.argv[1]]()
