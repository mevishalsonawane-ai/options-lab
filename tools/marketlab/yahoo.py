"""Yahoo Finance chart API (daily / hourly OHLC for futures and indices)."""
import time
import urllib.parse

import pandas as pd

from .net import get_json


def chart(symbol, days, interval="1d"):
    """OHLCV for the last `days` days as a DataFrame indexed by UTC timestamp, or None."""
    end = int(time.time())
    start = end - days * 86400
    q = urllib.parse.urlencode({"period1": start, "period2": end, "interval": interval,
                                "includePrePost": "false", "events": "div,splits"})
    for host in ("query1", "query2"):
        url = f"https://{host}.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(symbol)}?{q}"
        try:
            j = get_json(url)
        except Exception as e:  # noqa: BLE001 - try the other host, then give up
            print(f"  yahoo {symbol} {interval} via {host}: {type(e).__name__}")
            continue
        try:
            r = j["chart"]["result"][0]
            qd = r["indicators"]["quote"][0]
            df = pd.DataFrame({"open": qd["open"], "high": qd["high"], "low": qd["low"],
                               "close": qd["close"], "volume": qd.get("volume")},
                              index=pd.to_datetime(r["timestamp"], unit="s", utc=True))
            df = df.dropna(subset=["open", "high", "low", "close"])
            df.index.name = "time"
            return df
        except (KeyError, IndexError, TypeError):
            continue
    return None
