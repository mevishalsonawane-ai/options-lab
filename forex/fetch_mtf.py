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
import time

import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.mtf import TIMEFRAMES, resample  # noqa: E402
from marketlab.net import get  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data_mtf")
FEED = "https://datafeed.dukascopy.com/datafeed/XAUUSD"
DEADLINE = time.time() + 30 * 60  # Dukascopy throttles hard; after 30 minutes fall back to PAXG/USDT


def day(d):
    """One day's 1-minute candles; None if the feed refused (retried later), [] if there is no file."""
    if time.time() > DEADLINE:
        return None
    try:
        raw = get(f"{FEED}/{d.year}/{d.month - 1:02d}/{d.day:02d}/BID_candles_min_1.bi5", tries=6)
    except Exception:  # noqa: BLE001 - 503s from the feed when busy
        return None
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


def paxg_minutes():
    """PAXG/USDT 1-minute bars from Binance's public archive (same format as BTCUSDT)."""
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "crypto"))
    import fetch_mtf as btc_fetch  # the BTC downloader, reused with another symbol
    now = dt.datetime.now(dt.timezone.utc)
    start = now - dt.timedelta(days=3 * 365 + 5)
    urls = []
    y, m = start.year, start.month
    while (y, m) < (now.year, now.month):
        urls.append(f"{btc_fetch.BASE}/monthly/klines/PAXGUSDT/1m/PAXGUSDT-1m-{y}-{m:02d}.zip")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    d = now.date().replace(day=1)
    while d < now.date():
        urls.append(f"{btc_fetch.BASE}/daily/klines/PAXGUSDT/1m/PAXGUSDT-1m-{d.isoformat()}.zip")
        d += dt.timedelta(days=1)
    with cf.ThreadPoolExecutor(6) as ex:
        frames = [f for f in ex.map(btc_fetch.zip_frame, urls) if f is not None]
    m1 = pd.concat(frames).sort_index()
    m1 = m1[~m1.index.duplicated()]
    m1 = m1[m1.index >= pd.Timestamp(start)]
    return m1[m1["volume"] > 0]


def main():
    os.makedirs(OUT, exist_ok=True)
    today = dt.date.today()
    days = [today - dt.timedelta(days=i) for i in range(3 * 365 + 5, 0, -1)]
    days = [d for d in days if d.weekday() != 5]  # no trading on Saturdays
    rows, failed = [], []
    with cf.ThreadPoolExecutor(3) as ex:
        for i, (d, r) in enumerate(zip(days, ex.map(day, days))):
            if r is None:
                failed.append(d)
            else:
                rows += r
            if i % 200 == 0:
                print(f"  day {i}/{len(days)}: {len(rows)} bars, {len(failed)} to retry")
    for attempt in range(3 if time.time() < DEADLINE else 0):  # retry refused days slowly
        again = []
        for d in failed:
            time.sleep(2)
            r = day(d)
            if r is None:
                again.append(d)
            else:
                rows += r
        failed = again
        print(f"  retry round {attempt + 1}: {len(failed)} days still missing")
        if not failed:
            break
    if failed:
        print(f"  WARNING: {len(failed)} days could not be downloaded: {[str(d) for d in failed[:10]]}")
    source = "Dukascopy XAU/USD bid, 1-minute"
    if len(failed) > 0.1 * len(days):
        print(f"  Dukascopy incomplete ({len(failed)} of {len(days)} days missing): using PAXG/USDT 1-minute bars instead")
        m1 = paxg_minutes()
        source = "PAXG/USDT 1-minute (Binance), a gold-backed token (1 token = 1 troy ounce) used because the spot feed throttled"
        finish(m1, source)
        return
    m1 = pd.DataFrame(rows, columns=["time", "open", "high", "low", "close", "volume"]).set_index("time").sort_index()
    m1 = m1[~m1.index.duplicated()]
    med = m1["close"].median()
    div = next(x for x in (1000, 100, 10000, 10, 100000, 1) if 300 < med / x < 30000)
    m1[["open", "high", "low", "close"]] = m1[["open", "high", "low", "close"]] / div
    print(f"1m bars: {len(m1)} from {m1.index[0]} to {m1.index[-1]} (price divisor {div})")
    finish(m1, source)


def finish(m1, source):
    with open(f"{OUT}/SOURCE.txt", "w") as f:
        f.write(f"1-minute source: {source}\nbars: {len(m1)} from {m1.index[0]} to {m1.index[-1]}\n")
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
