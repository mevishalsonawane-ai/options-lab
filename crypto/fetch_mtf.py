"""BTC multi-timeframe data into crypto/data_mtf/: 3 years of 1-minute BTCUSDT bars from Binance's public data
archive (with trade counts and taker-buy volume), resampled to 5m, 15m, 30m, 1h, 3h, 6h, 12h and 24h
(UTC-aligned, so every timeframe lines up by date and time).

Only public files, no keys. Run from the repo root: python crypto/fetch_mtf.py
"""
import concurrent.futures as cf
import datetime as dt
import io
import os
import sys
import zipfile

import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.mtf import TIMEFRAMES, resample  # noqa: E402
from marketlab.net import get  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data_mtf")
BASE = "https://data.binance.vision/data/spot"
NOW = dt.datetime.now(dt.timezone.utc)
START = NOW - dt.timedelta(days=3 * 365 + 5)
COLS = ["open_time", "open", "high", "low", "close", "volume", "close_time", "quote_volume", "trades",
        "taker_buy_volume", "taker_buy_quote_volume", "ignore"]


def zip_frame(url):
    b = get(url)
    if not b:
        return None
    with zipfile.ZipFile(io.BytesIO(b)) as z, z.open(z.namelist()[0]) as f:
        df = pd.read_csv(f, header=None)
    df = df[pd.to_numeric(df[0], errors="coerce").notna()]
    df.columns = COLS
    t = df["open_time"].astype("int64")
    unit = "us" if t.iloc[0] > 10 ** 14 else "ms"  # Binance spot files switched to microseconds in 2025
    df["time"] = pd.to_datetime(t, unit=unit, utc=True)
    return df.set_index("time")[["open", "high", "low", "close", "volume", "trades", "taker_buy_volume"]].astype(float)


def main():
    os.makedirs(OUT, exist_ok=True)
    urls = []
    y, m = START.year, START.month
    while (y, m) < (NOW.year, NOW.month):
        urls.append(f"{BASE}/monthly/klines/BTCUSDT/1m/BTCUSDT-1m-{y}-{m:02d}.zip")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    d = NOW.date().replace(day=1)
    while d < NOW.date():
        urls.append(f"{BASE}/daily/klines/BTCUSDT/1m/BTCUSDT-1m-{d.isoformat()}.zip")
        d += dt.timedelta(days=1)
    frames = []
    with cf.ThreadPoolExecutor(6) as ex:
        for df in ex.map(zip_frame, urls):
            if df is not None:
                frames.append(df)
    m1 = pd.concat(frames).sort_index()
    m1 = m1[~m1.index.duplicated()]
    m1 = m1[m1.index >= pd.Timestamp(START)]
    print(f"1m bars: {len(m1)} from {m1.index[0]} to {m1.index[-1]}")
    m1.to_csv(f"{OUT}/btc_1m.csv.gz", compression="gzip", float_format="%.8g")
    for tf, rule in TIMEFRAMES.items():
        if tf == "1m":
            continue
        b = resample(m1, rule)
        b.drop(columns="close_time").to_csv(f"{OUT}/btc_{tf}.csv" + (".gz" if tf in ("5m", "15m") else ""),
                                           compression="gzip" if tf in ("5m", "15m") else None, float_format="%.8g")
        print(f"  {tf}: {len(b)} bars")


if __name__ == "__main__":
    main()
