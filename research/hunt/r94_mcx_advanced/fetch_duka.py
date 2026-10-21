"""R94 idea 5: Dukascopy 1-minute BID candles (labelled PROXY for COMEX gold / silver, NYMEX WTI and Henry Hub gas).

Written 21 Oct 2026. One file per UTC day and instrument from Dukascopy's free datafeed (the URL's month is 0-based),
LZMA-compressed 24-byte records (seconds from the day's start, open, close, low, high as integer points, volume as a
float), decoded with struct only (research/hunt/r83/fetch_proxy.py). Prices are divided by POINT (gas 10,000, the
rest 1,000; checked 21 Oct 2026 on 15 Sep 2026 minutes: gold USD 4,297, silver 63.2, WTI 102.5, gas 2.87; gas read
28.7 at 1,000, i.e. 10x too high, against MCX NATURALGAS ~Rs 300 = ~USD 3.4). Raw bytes are never kept;
the output goes to common.cache_dir()/duka/<SYM>.parquet (never git). Minutes with zero volume (market closed) are
dropped.

    python -m research.hunt.r94_mcx_advanced.fetch_duka [SYM ...]
"""
from __future__ import annotations

import datetime as dt
import lzma
import os
import struct
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import pandas as pd

from research.hunt.r94_mcx_advanced import common as C

URL = "https://datafeed.dukascopy.com/datafeed/{s}/{y}/{m:02d}/{d:02d}/BID_candles_min_1.bi5"
SYMS = ["XAUUSD", "XAGUSD", "LIGHTCMDUSD", "GASCMDUSD"]
POINT = {"XAUUSD": 1000.0, "XAGUSD": 1000.0, "LIGHTCMDUSD": 1000.0, "GASCMDUSD": 10000.0}
FIRST, LAST = dt.date(2025, 7, 1), dt.date(2026, 10, 20)


def out_dir():
    p = C.cache_dir() / "duka"
    p.mkdir(parents=True, exist_ok=True)
    return p


def get(url, tries=10):
    for k in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            return urllib.request.urlopen(req, timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return b""
            time.sleep(1 + k)
        except Exception:
            time.sleep(1 + k)
    return None


def decode(raw: bytes, day: dt.date, point: float) -> list:
    """Decompressed day file -> [(utc epoch seconds of the minute start, o, h, l, c, v)], zero-volume minutes dropped.
    Record layout: >IIIIIf = seconds, open, close, low, high, volume (r83 reads them as s, o, c, lo, h, v)."""
    t0 = int(dt.datetime(day.year, day.month, day.day, tzinfo=dt.timezone.utc).timestamp())
    rows = []
    for i in range(0, len(raw) // 24 * 24, 24):
        s, o, c, lo, h, v = struct.unpack(">IIIIIf", raw[i:i + 24])
        if v > 0:
            rows.append((t0 + s, o / point, h / point, lo / point, c / point, float(v)))
    return rows


def one(sym, day):
    b = get(URL.format(s=sym, y=day.year, m=day.month - 1, d=day.day))
    if b is None:
        return day, None
    return day, (decode(lzma.decompress(b), day, POINT[sym]) if b else [])


def main(syms):
    for sym in syms:
        days = [FIRST + dt.timedelta(n) for n in range((LAST - FIRST).days + 1)]
        days = [d for d in days if d.weekday() != 5]
        rows, failed = [], []
        with ThreadPoolExecutor(int(os.environ.get("R94_THREADS", "12"))) as ex:
            for day, r in ex.map(lambda d: one(sym, d), days):
                if r is None:
                    failed.append(day)
                else:
                    rows += r
        df = pd.DataFrame(rows, columns=["t", "open", "high", "low", "close", "volume"]).sort_values("t")
        df = df.drop_duplicates("t", keep="last").reset_index(drop=True)
        df.to_parquet(out_dir() / f"{sym}.parquet")
        med = float(df.close.median()) if len(df) else float("nan")
        print(sym, len(df), "rows; median close", round(med, 3), "; failed days", len(failed), failed[:5], flush=True)


if __name__ == "__main__":
    main(sys.argv[1:] or SYMS)
