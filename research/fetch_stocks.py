"""1-minute bars of the 12 BANKNIFTY constituents from Upstox v3 (unauthenticated) for the year file's dates.

    python research/fetch_stocks.py <year.parquet> <out.parquet>
"""
from __future__ import annotations

import sys
import time
from datetime import timedelta

import pandas as pd
import requests

STOCKS = {
    "HDFCBANK": "NSE_EQ|INE040A01034", "ICICIBANK": "NSE_EQ|INE090A01021", "SBIN": "NSE_EQ|INE062A01020",
    "KOTAKBANK": "NSE_EQ|INE237A01036", "AXISBANK": "NSE_EQ|INE238A01034", "INDUSINDBK": "NSE_EQ|INE095A01012",
    "BANKBARODA": "NSE_EQ|INE028A01039", "FEDERALBNK": "NSE_EQ|INE171A01029", "IDFCFIRSTB": "NSE_EQ|INE092T01019",
    "AUBANK": "NSE_EQ|INE949L01017", "PNB": "NSE_EQ|INE160A01022", "CANBK": "NSE_EQ|INE476A01022",
}


def fetch(key, frm, to):
    parts, cur = [], frm
    k = requests.utils.quote(key, safe="")
    while cur <= to:
        end = min(cur + timedelta(days=27), to)
        url = f"https://api.upstox.com/v3/historical-candle/{k}/minutes/1/{end:%Y-%m-%d}/{cur:%Y-%m-%d}"
        for attempt in range(6):
            r = requests.get(url, headers={"Accept": "application/json", "User-Agent": "Mozilla/5.0"}, timeout=60)
            if r.status_code == 429:
                time.sleep(15 * (attempt + 1))
                continue
            break
        r.raise_for_status()
        c = r.json().get("data", {}).get("candles", [])
        if c:
            df = pd.DataFrame(c).iloc[:, :6]
            df.columns = ["ts", "open", "high", "low", "close", "volume"]
            parts.append(df)
        cur = end + timedelta(days=1)
        time.sleep(0.5)
    out = pd.concat(parts)
    out["ts"] = pd.to_datetime(out.ts).dt.tz_localize(None)
    return out.drop_duplicates("ts").sort_values("ts")


def main():
    days = pd.read_parquet(sys.argv[1], columns=["day"]).day
    frm, to = days.min().date(), days.max().date()
    frm -= timedelta(days=7)                      # the previous close for the first day
    out = []
    for sym, key in STOCKS.items():
        df = fetch(key, frm, to)
        print(f"{sym}: {len(df):,} bars {df.ts.min()} .. {df.ts.max()}", flush=True)
        out.append(df.assign(symbol=sym))
    pd.concat(out).to_parquet(sys.argv[2], compression="zstd", index=False)


if __name__ == "__main__":
    main()
