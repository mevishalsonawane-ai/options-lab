"""R53: extend the release's XAUUSD 1-minute BID file (to 30 Sep 2026) up to 12 Oct 2026 from Dukascopy day files,
decoded exactly as research/hunt/r3/fetch_duka_xau.py does (fixed-width LZMA records only; raw bytes never kept).

    python3 -I fetch_xau_tail.py <release xauusd_m1_bid.csv.gz> <out.csv.gz>
"""
import datetime as dt
import lzma
import struct
import sys
import time
import urllib.error
import urllib.request

import pandas as pd

URL = "https://datafeed.dukascopy.com/datafeed/XAUUSD/{y}/{m:02d}/{d:02d}/BID_candles_min_1.bi5"


def get(url, tries=6):
    for k in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            return urllib.request.urlopen(req, timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return b""
            time.sleep(3 * (k + 1))
        except Exception:
            time.sleep(3 * (k + 1))
    return None


def decode(raw: bytes, day: dt.date) -> list:
    t0 = dt.datetime(day.year, day.month, day.day, tzinfo=dt.timezone.utc)
    rows = []
    for i in range(0, len(raw) // 24 * 24, 24):
        s, o, c, lo, h, v = struct.unpack(">IIIIIf", raw[i:i + 24])
        if v > 0:
            rows.append((int((t0 + dt.timedelta(seconds=s)).timestamp() * 1000), o / 1e3, h / 1e3, lo / 1e3, c / 1e3, v))
    return rows


def main(src, out, first=dt.date(2026, 10, 1), last=dt.date(2026, 10, 12)):
    base = pd.read_csv(src)
    rows, day = [], first
    while day <= last:
        if day.weekday() != 5:
            b = get(URL.format(y=day.year, m=day.month - 1, d=day.day))
            if b is None:
                raise SystemExit(f"failed {day}")
            if b:
                rows += decode(lzma.decompress(b), day)
            time.sleep(0.5)
        day += dt.timedelta(days=1)
    ext = pd.DataFrame(rows, columns=["timestamp", "open", "high", "low", "close", "volume"])
    allx = pd.concat([base, ext]).drop_duplicates("timestamp").sort_values("timestamp")
    allx.to_csv(out, index=False, compression="gzip")
    print(len(base), len(ext), pd.to_datetime(allx.timestamp.max(), unit="ms"))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
