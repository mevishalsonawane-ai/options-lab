"""Extra gold data for the deeper study, into forex/data_extra/:

  gvz_1d.csv        CBOE gold volatility index (30-day implied vol of GLD options) - gold's option market
  vix_1d.csv        CBOE VIX
  gld_1d.csv        SPDR Gold Shares (price, volume)
  fred_*.csv        10-year TIPS real yield (DFII10), 10-year breakeven inflation (T10YIE),
                    broad trade-weighted dollar (DTWEXBGS)
  cot_gold.csv      CFTC Commitments of Traders, gold (COMEX): managed-money and producer positioning

Only public endpoints, no keys. Run from the repo root: python forex/fetch_extra.py
"""
import io
import os
import sys
import urllib.parse

import pandas as pd

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from marketlab.net import get, get_json  # noqa: E402
from marketlab.yahoo import chart  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "data_extra")
DAYS = 3 * 365 + 400  # a year more, so rolling features are warm at the start


def fred(series):
    b = get(f"https://fred.stlouisfed.org/graph/fredgraph.csv?id={series}")
    if not b:
        return None
    df = pd.read_csv(io.BytesIO(b))
    df.columns = ["time", "value"]
    df["time"] = pd.to_datetime(df["time"], utc=True)
    df["value"] = pd.to_numeric(df["value"], errors="coerce")
    df = df.dropna().set_index("time")
    return df[df.index >= pd.Timestamp.now(tz="UTC") - pd.Timedelta(days=DAYS)]


def cot():
    rows, offset = [], 0
    while True:
        q = urllib.parse.urlencode({"cftc_contract_market_code": "088691", "$limit": 1000, "$offset": offset,
                                    "$order": "report_date_as_yyyy_mm_dd"})
        j = get_json(f"https://publicreporting.cftc.gov/resource/72hh-3qpy.json?{q}") or []
        rows += j
        if len(j) < 1000:
            break
        offset += 1000
    if not rows:
        return None
    df = pd.DataFrame(rows)
    keep = {"report_date_as_yyyy_mm_dd": "time", "open_interest_all": "open_interest",
            "m_money_positions_long_all": "mm_long", "m_money_positions_short_all": "mm_short",
            "prod_merc_positions_long_all": "prod_long", "prod_merc_positions_short_all": "prod_short",
            "swap_positions_long_all": "swap_long", "swap__positions_short_all": "swap_short"}
    df = df[[c for c in keep if c in df]].rename(columns=keep)
    df["time"] = pd.to_datetime(df["time"], utc=True)
    df = df.set_index("time").apply(pd.to_numeric, errors="coerce").sort_index()
    return df[df.index >= pd.Timestamp.now(tz="UTC") - pd.Timedelta(days=DAYS)]


def save(name, df):
    if df is None or len(df) == 0:
        print(f"  {name}: no data")
        return
    df.to_csv(os.path.join(OUT, name))
    print(f"  {name}: {len(df)} rows")


def main():
    os.makedirs(OUT, exist_ok=True)
    for sym, name in (("^GVZ", "gvz"), ("^VIX", "vix"), ("GLD", "gld"), ("GC=F", "gold_futures"), ("SI=F", "silver"),
                      ("DX-Y.NYB", "dxy")):
        save(f"{name}_1d.csv", chart(sym, DAYS))
    for s in ("DFII10", "T10YIE", "DTWEXBGS"):
        try:
            save(f"fred_{s}.csv", fred(s))
        except Exception as e:  # noqa: BLE001
            print(f"  FRED {s} failed: {type(e).__name__}")
    try:
        save("cot_gold.csv", cot())
    except Exception as e:  # noqa: BLE001
        print(f"  COT failed: {type(e).__name__} {e}")
    print("done")


if __name__ == "__main__":
    main()
