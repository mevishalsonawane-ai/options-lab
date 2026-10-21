"""R53 crypto data: Binance public archive (USD-M perps + spot, BTCUSDT / ETHUSDT, 1-minute), Oct 2024 - 12 Oct 2026,
cached as parquet OUTSIDE git ($IRAALGO_CACHE/r53). Reuses RC1's downloader helpers (research/hunt/rc1/data.py).
October 2024 is warm-up only (no trade before 20 Nov 2024).

    python3 -m research.hunt.r53.fetch_crypto
"""
from __future__ import annotations

import os
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "rc1"))
import data as rc1  # noqa: E402

CACHE = os.path.join(os.environ.get("IRAALGO_CACHE", os.path.expanduser("~/.cache/iraalgo")), "r53")
FIRST_MONTH = "2024-10"
END = date(2026, 10, 12)


def fetch_1m(market: str, coin: str, end: date = END) -> pd.DataFrame:
    sym = rc1.SYMS[coin]
    root = f"{rc1.BASE}/spot" if market == "spot" else f"{rc1.BASE}/futures/um"
    months = [m for m in rc1._months(FIRST_MONTH, end) if m < f"{end.year:04d}-{end.month:02d}"]
    urls = [f"{root}/monthly/klines/{sym}/1m/{sym}-1m-{m}.zip" for m in months]
    d = date(end.year, end.month, 1)
    while d <= end:
        urls.append(f"{root}/daily/klines/{sym}/1m/{sym}-1m-{d.isoformat()}.zip")
        d += timedelta(days=1)
    with ThreadPoolExecutor(8) as ex:
        blobs = list(ex.map(rc1._get, urls))
    parts = [rc1._klines_frame(rc1._csv_from_zip(b)) for b in blobs if b]
    return pd.concat(parts).drop_duplicates("t").sort_values("t").reset_index(drop=True)


def main() -> None:
    os.makedirs(CACHE, exist_ok=True)
    for coin in ("BTC", "ETH"):
        for market in ("um", "spot"):
            df = fetch_1m(market, coin)
            df.to_parquet(os.path.join(CACHE, f"{market}_{coin}_1m.parquet"))
            print(market, coin, len(df), df.t.min(), df.t.max(), flush=True)
        f = rc1.fetch_funding(coin, END)
        f[f.t >= "2024-10-01"].to_parquet(os.path.join(CACHE, f"funding_{coin}.parquet"))
    u = rc1.fetch_usdinr(END)
    u.to_parquet(os.path.join(CACHE, "usdinr.parquet"))
    print("done", flush=True)


if __name__ == "__main__":
    main()
