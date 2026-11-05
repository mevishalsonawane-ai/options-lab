"""R146 daily data (PREREG 1, D): Yahoo chart API daily bars -> <OUT>/yahoo/<NAME>.csv.

    python -m research.hunt.r146_gap_btst.yahoo fetch

Plain HTTPS with a generic client name; no account, cookie or key. Each CSV: date (the exchange's local trading date),
open, high, low, close. Raw files stay in the cache (never in git).
"""
from __future__ import annotations

import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone

import pandas as pd

from . import cfg as K

URL = "https://query1.finance.yahoo.com/v8/finance/chart/{sym}?period1={p1}&period2={p2}&interval=1d&events=history"


def parse(js: dict) -> pd.DataFrame:
    """The chart JSON -> date, open, high, low, close (local exchange date from gmtoffset). Rows with no close dropped."""
    r = js["chart"]["result"][0]
    off = int(r["meta"].get("gmtoffset", 0))
    q = r["indicators"]["quote"][0]
    ts = pd.to_datetime(pd.Series(r["timestamp"]) + off, unit="s")
    df = pd.DataFrame({"date": ts.dt.date, "open": q["open"], "high": q["high"], "low": q["low"], "close": q["close"]})
    df = df.dropna(subset=["close"]).drop_duplicates("date", keep="last").sort_values("date")
    return df.reset_index(drop=True)


def fetch() -> None:
    K.YAHOO.mkdir(parents=True, exist_ok=True)
    p1 = int(datetime(2007, 1, 1, tzinfo=timezone.utc).timestamp())
    p2 = int(datetime(2026, 11, 5, tzinfo=timezone.utc).timestamp())
    for name, sym in K.YH.items():
        url = URL.format(sym=urllib.parse.quote(sym), p1=p1, p2=p2)
        for attempt in range(4):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (research script)"})
                with urllib.request.urlopen(req, timeout=60) as f:
                    df = parse(json.loads(f.read()))
                break
            except Exception as e:  # noqa: BLE001  (network retries)
                print(name, "retry", attempt, type(e).__name__, flush=True)
                time.sleep(2 ** (attempt + 1))
        else:
            print(name, "FAILED", flush=True)
            continue
        df.to_csv(K.YAHOO / f"{name}.csv", index=False)
        print(name, len(df), df.date.min(), "..", df.date.max(), flush=True)


def load(name: str) -> pd.DataFrame:
    df = pd.read_csv(K.YAHOO / f"{name}.csv", parse_dates=["date"])
    df["date"] = df["date"].dt.date
    return df.set_index("date")


if __name__ == "__main__":
    if sys.argv[1:] == ["fetch"]:
        fetch()
