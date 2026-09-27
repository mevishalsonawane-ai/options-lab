"""Gold (XAU/USD) multi-timeframe data into forex/data_mtf/: 3 years of 1-minute bid candles from Dukascopy's
public feed (one file per day), resampled to 5m, 15m, 30m, 1h, 3h, 6h, 12h and 24h on UTC-aligned bins, so
every timeframe lines up by date and time (the 24h bars are UTC days, not New York sessions).

Only public files, no keys. Run from the repo root: python forex/fetch_mtf.py
"""
import concurrent.futures as cf
import datetime as dt
import lzma
import os
import struct
import sys

import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.mtf import TIMEFRAMES, resample  # noqa: E402
from marketlab.net import get  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data_mtf")
FEED = "https://datafeed.dukascopy.com/datafeed/XAUUSD"


def day(d):
    raw = get(f"{FEED}/{d.year}/{d.month - 1:02d}/{d.day:02d}/BID_candles_min_1.bi5")
    if not raw:
        return []
    data = lzma.decompress(raw)
    base = dt.datetime(d.year, d.month, d.day, tzinfo=dt.timezone.utc)
    out = []
    for i in range(0, len(data) - 23, 24):
        s, o, c, lo, hi, v = struct.unpack(">IIIIIf", data[i:i + 24])
        if v > 0:
            out.append((base + dt.timedelta(seconds=s), o, hi, lo, c, v))
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    today = dt.date.today()
    days = [today - dt.timedelta(days=i) for i in range(3 * 365 + 5, 0, -1)]
    days = [d for d in days if d.weekday() != 5]  # no trading on Saturdays
    rows = []
    with cf.ThreadPoolExecutor(8) as ex:
        for i, r in enumerate(ex.map(day, days)):
            rows += r
            if i % 200 == 0:
                print(f"  day {i}/{len(days)}: {len(rows)} bars")
    m1 = pd.DataFrame(rows, columns=["time", "open", "high", "low", "close", "volume"]).set_index("time").sort_index()
    m1 = m1[~m1.index.duplicated()]
    med = m1["close"].median()
    div = next(x for x in (1000, 100, 10000, 10, 100000, 1) if 300 < med / x < 30000)
    m1[["open", "high", "low", "close"]] = m1[["open", "high", "low", "close"]] / div
    print(f"1m bars: {len(m1)} from {m1.index[0]} to {m1.index[-1]} (price divisor {div})")
    m1.to_csv(f"{OUT}/xauusd_1m.csv.gz", compression="gzip", float_format="%.8g")
    for tf, rule in TIMEFRAMES.items():
        if tf == "1m":
            continue
        b = resample(m1, rule)
        b.drop(columns="close_time").to_csv(f"{OUT}/xauusd_{tf}.csv" + (".gz" if tf in ("5m", "15m") else ""),
                                           compression="gzip" if tf in ("5m", "15m") else None, float_format="%.8g")
        print(f"  {tf}: {len(b)} bars")


if __name__ == "__main__":
    main()
