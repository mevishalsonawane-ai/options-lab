"""Fetch public Deribit data (no keys): perp 5-min candles, hourly funding, hourly DVOL for BTC and ETH.
Usage: python3 -I fetch_base.py OUTDIR"""
import sys, json, time, urllib.request, os
sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps under python -I
import pandas as pd
OUT = sys.argv[1]
API = "https://www.deribit.com/api/v2/public/"
T0 = int(pd.Timestamp("2021-03-20", tz="UTC").timestamp() * 1000)
T1 = int(time.time() * 1000)

def get(method, **kw):
    q = "&".join(f"{k}={v}" for k, v in kw.items())
    for a in range(6):
        try:
            with urllib.request.urlopen(API + method + "?" + q, timeout=60) as r:
                return json.load(r)["result"]
        except Exception as e:
            time.sleep(2 + 3 * a)
    raise RuntimeError(method + q)

for cur in ["BTC", "ETH"]:
    # perp 5-min
    rows = []; step = 5000 * 5 * 60000; s = T0
    while s < T1:
        e = min(s + step - 1, T1)
        d = get("get_tradingview_chart_data", instrument_name=f"{cur}-PERPETUAL", start_timestamp=s, end_timestamp=e, resolution=5)
        if d.get("ticks"):
            rows.append(pd.DataFrame({"t": d["ticks"], "o": d["open"], "h": d["high"], "l": d["low"], "c": d["close"]}))
        s = e + 1
    df = pd.concat(rows).drop_duplicates("t").sort_values("t")
    df["t"] = pd.to_datetime(df["t"], unit="ms", utc=True)
    df.to_parquet(f"{OUT}/{cur}_perp5m.parquet"); print(cur, "perp", len(df), df.t.min(), df.t.max(), flush=True)
    # funding hourly
    rows = []; step = 744 * 3600000; s = T0
    while s < T1:
        e = min(s + step - 1, T1)
        d = get("get_funding_rate_history", instrument_name=f"{cur}-PERPETUAL", start_timestamp=s, end_timestamp=e)
        if d: rows.append(pd.DataFrame(d))
        s = e + 1
    f = pd.concat(rows).drop_duplicates("timestamp").sort_values("timestamp")
    f["t"] = pd.to_datetime(f["timestamp"], unit="ms", utc=True)
    f[["t", "index_price", "interest_8h", "interest_1h"]].to_parquet(f"{OUT}/{cur}_funding1h.parquet"); print(cur, "fund", len(f), flush=True)
    # DVOL hourly
    rows = []; step = 1000 * 3600000; s = T0
    while s < T1:
        e = min(s + step - 1, T1)
        d = get("get_volatility_index_data", currency=cur, start_timestamp=s, end_timestamp=e, resolution=3600)
        if d["data"]: rows.append(pd.DataFrame(d["data"], columns=["t", "o", "h", "l", "c"]))
        s = e + 1
    v = pd.concat(rows).drop_duplicates("t").sort_values("t")
    v["t"] = pd.to_datetime(v["t"], unit="ms", utc=True)
    v.to_parquet(f"{OUT}/{cur}_dvol1h.parquet"); print(cur, "dvol", len(v), v.t.min(), v.t.max(), flush=True)
