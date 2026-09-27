"""Download 3 years of XAU/USD (spot gold) into forex/data/.

  xauusd_1h.csv    hourly bid candles from Dukascopy's public data feed (fallback: Yahoo gold futures)
  xauusd_1d.csv    daily candles built from the hourly ones on the New York 17:00 close
  context_*.csv    dollar index, US 10-year yield, silver, S&P 500, gold futures (Yahoo daily)

Only public endpoints, no keys. Run from the repo root: python forex/fetch.py
"""
import concurrent.futures as cf
import datetime as dt
import lzma
import os
import struct
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.net import get  # noqa: E402
from marketlab.yahoo import chart  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data")
DAYS = 3 * 365 + 5
FEED = "https://datafeed.dukascopy.com/datafeed/XAUUSD"


def _decode(raw, base):
    """Dukascopy candle file: LZMA, 24-byte big-endian records (secs, open, close, low, high, volume)."""
    if not raw:
        return []
    data = lzma.decompress(raw)
    out = []
    for i in range(0, len(data) - 23, 24):
        s, o, c, lo, hi, v = struct.unpack(">IIIIIf", data[i:i + 24])
        out.append((base + dt.timedelta(seconds=s), o, hi, lo, c, v))
    return out


def _month(y, m):
    base = dt.datetime(y, m, 1, tzinfo=dt.timezone.utc)
    return _decode(get(f"{FEED}/{y}/{m - 1:02d}/BID_candles_hour_1.bi5"), base)


def _day_minutes(d):
    base = dt.datetime(d.year, d.month, d.day, tzinfo=dt.timezone.utc)
    return _decode(get(f"{FEED}/{d.year}/{d.month - 1:02d}/{d.day:02d}/BID_candles_min_1.bi5"), base)


def dukascopy_hourly():
    today = dt.date.today()
    start = today - dt.timedelta(days=DAYS)
    months = []
    y, m = start.year, start.month
    while (y, m) < (today.year, today.month):
        months.append((y, m))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    rows = []
    with cf.ThreadPoolExecutor(4) as ex:
        for r in ex.map(lambda ym: _month(*ym), months):
            rows += r
    # the current month has no hourly file yet: build it from minute files
    days = [today.replace(day=1) + dt.timedelta(days=i) for i in range((today - today.replace(day=1)).days)]
    mins = []
    with cf.ThreadPoolExecutor(4) as ex:
        for r in ex.map(_day_minutes, days):
            mins += r
    if not rows:
        return None
    df = pd.DataFrame(rows, columns=["time", "open", "high", "low", "close", "volume"]).set_index("time")
    if mins:
        mdf = pd.DataFrame(mins, columns=["time", "open", "high", "low", "close", "volume"]).set_index("time")
        mdf = mdf[mdf.volume > 0]
        agg = mdf.resample("1h").agg({"open": "first", "high": "max", "low": "min", "close": "last",
                                      "volume": "sum"}).dropna()
        df = pd.concat([df, agg])
    df = df[df.volume > 0]
    df = df[~df.index.duplicated()].sort_index()
    # prices are integers in points; pick the divisor that puts gold in a sensible dollar range
    med = df["close"].median()
    div = next(d for d in (1000, 100, 10000, 10, 100000, 1) if 300 < med / d < 30000)
    df[["open", "high", "low", "close"]] = df[["open", "high", "low", "close"]] / div
    df = df[df.index >= pd.Timestamp(start, tz="UTC")]
    print(f"  Dukascopy XAUUSD 1h: {len(df)} bars (price divisor {div})")
    return df


def daily_from_hourly(h):
    ny = h.index.tz_convert("America/New_York")
    session = (ny + pd.Timedelta(hours=7)).date  # 17:00 New York starts the next trading day
    d = h.groupby(session).agg({"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"})
    d.index = pd.to_datetime(d.index).tz_localize("UTC")
    d.index.name = "time"
    d = d[d.index.dayofweek < 5]
    counts = h.groupby(session).size()
    counts.index = pd.to_datetime(counts.index).tz_localize("UTC")
    return d[counts.reindex(d.index).fillna(0) >= 6]  # drop stub sessions (holidays, partial first day)


def main():
    os.makedirs(OUT, exist_ok=True)
    print("XAU/USD hourly")
    try:
        h = dukascopy_hourly()
        src = "dukascopy"
    except Exception as e:  # noqa: BLE001
        print(f"  Dukascopy failed: {type(e).__name__} {e}")
        h = None
    if h is None or len(h) < 5000:
        print("  falling back to Yahoo gold futures (GC=F); hourly covers only the last ~2 years")
        h = chart("GC=F", 729, "1h")
        src = "yahoo GC=F"
        d = chart("GC=F", DAYS, "1d")
    else:
        d = daily_from_hourly(h)
    h.to_csv(f"{OUT}/xauusd_1h.csv")
    d.to_csv(f"{OUT}/xauusd_1d.csv")
    with open(f"{OUT}/SOURCE.txt", "w") as f:
        f.write(f"xauusd source: {src}\nfetched: {dt.datetime.now(dt.timezone.utc).isoformat()}\n")
    print(f"  daily: {len(d)} sessions")
    print("Context")
    for sym, name in (("DX-Y.NYB", "dxy"), ("^TNX", "us10y"), ("SI=F", "silver"), ("^GSPC", "spx"),
                      ("GC=F", "gold_futures"), ("BTC-USD", "btc")):
        df = chart(sym, DAYS)
        if df is not None:
            df.to_csv(f"{OUT}/context_{name}.csv")
            print(f"  {sym}: {len(df)} days")
    print("done")


if __name__ == "__main__":
    main()
