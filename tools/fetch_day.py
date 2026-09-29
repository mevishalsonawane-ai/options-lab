"""BANKNIFTY 1-minute candles for recent sessions from Upstox v3 (unauthenticated), for chart studies.

    python tools/fetch_day.py <from YYYY-MM-DD> <to YYYY-MM-DD> <out.csv>
"""
import sys

import pandas as pd
import requests

frm, to, out = sys.argv[1], sys.argv[2], sys.argv[3]
url = f"https://api.upstox.com/v3/historical-candle/NSE_INDEX%7CNifty%20Bank/minutes/1/{to}/{frm}"
r = requests.get(url, headers={"Accept": "application/json", "User-Agent": "Mozilla/5.0"}, timeout=60)
print(r.status_code)
r.raise_for_status()
c = r.json()["data"]["candles"]
df = pd.DataFrame(c).iloc[:, :5]
df.columns = ["ts", "open", "high", "low", "close"]
df["ts"] = pd.to_datetime(df.ts).dt.tz_localize(None)
df.sort_values("ts").to_csv(out, index=False)
print(f"{len(df)} candles {df.ts.min()} .. {df.ts.max()}")
