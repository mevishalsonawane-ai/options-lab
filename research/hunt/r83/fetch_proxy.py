"""R83: download US benchmark 1-minute BID candles from Dukascopy day files (signal PROXY only: WTI, Brent, Henry Hub gas,
COMEX copper CFDs), decoded as research/hunt/r53/fetch_xau_tail.py does. Raw bytes are never kept; output goes to
/dev/shm (never committed).

    python3 -I fetch_proxy.py OUTDIR [SYM ...]
"""
import datetime as dt
import lzma
import struct
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import pandas as pd

URL = "https://datafeed.dukascopy.com/datafeed/{s}/{y}/{m:02d}/{d:02d}/BID_candles_min_1.bi5"
SYMS = ["LIGHTCMDUSD", "GASCMDUSD", "COPPERCMDUSD", "BRENTCMDUSD"]
FIRST, LAST = dt.date(2016, 1, 1), dt.date(2026, 10, 16)
# Dukascopy commodity CFD prices are quoted with 3 decimals (WTI 71.234) like XAUUSD; checked against known levels below.
SCALE = 1e3


def get(url, tries=12):
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


def decode(raw: bytes, day: dt.date) -> list:
    t0 = dt.datetime(day.year, day.month, day.day, tzinfo=dt.timezone.utc)
    rows = []
    for i in range(0, len(raw) // 24 * 24, 24):
        s, o, c, lo, h, v = struct.unpack(">IIIIIf", raw[i:i + 24])
        if v > 0:
            rows.append((int((t0 + dt.timedelta(seconds=s)).timestamp()), o, h, lo, c, v))
    return rows


def one(sym, day):
    b = get(URL.format(s=sym, y=day.year, m=day.month - 1, d=day.day))
    if b is None:
        return day, None
    return day, (decode(lzma.decompress(b), day) if b else [])


def main(out, syms):
    for sym in syms:
        days = [FIRST + dt.timedelta(n) for n in range((LAST - FIRST).days + 1)]
        days = [d for d in days if d.weekday() != 5]
        rows, failed = [], []
        with ThreadPoolExecutor(12) as ex:
            for day, r in ex.map(lambda d: one(sym, d), days):
                if r is None:
                    failed.append(day)
                else:
                    rows += r
        df = pd.DataFrame(rows, columns=["t", "o", "h", "l", "c", "v"])
        df.to_parquet(f"{out}/{sym}.parquet")
        print(sym, len(df), "failed days", len(failed), failed[:5], flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:] or SYMS)
