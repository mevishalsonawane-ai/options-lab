"""Fetch MCX CRUDEOIL expired-option minutes (rollingoption) Aug 2025 -> today.
expiryCode 1 (near month) ATM-2..ATM+2 CE/PE; expiryCode 2 (next month) ATM CE/PE.
Cache: scratchpad/hunt/strad_crude/cache/<code>_<strike>_<side>.parquet (float32)."""
import sys, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import client, safe, F, CACHE
import pandas as pd, numpy as np
cl, pc = client()
start, end = dt.date(2025, 7, 25), dt.date(2026, 10, 9)
REQ = ["open", "high", "low", "close", "iv", "volume", "oi", "strike", "spot"]
series = [(1, s, side) for s in ["ATM-2", "ATM-1", "ATM", "ATM+1", "ATM+2"] for side in ("CALL", "PUT")]
series += [(2, "ATM", "CALL"), (2, "ATM", "PUT")]
series += [(1, s, side) for s in ["ATM-3", "ATM+3"] for side in ("CALL", "PUT")]
for code, strike, side in series:
    out = CACHE / f"c{code}_{strike}_{side}.parquet"
    if out.exists():
        continue
    parts = []
    d0 = start
    while d0 < end:
        d1 = min(d0 + dt.timedelta(days=25), end)
        pl = {"securityId": "294", "exchangeSegment": "MCX_COMM", "instrument": "OPTFUT", "expiryFlag": "MONTH",
              "expiryCode": code, "strike": strike, "drvOptionType": side, "requiredData": REQ,
              "fromDate": d0.isoformat(), "toDate": d1.isoformat(), "interval": 1}
        try:
            js = cl.post("/charts/rollingoption", pl, pc)
            d = js.get("data", {}) or {}
            x = d.get("ce" if side == "CALL" else "pe")
            df = F.arrays_to_df(x) if x else pd.DataFrame()
        except Exception as e:
            print("ERR", code, strike, side, d0, safe(e)[:200], flush=True)
            df = pd.DataFrame()
        if len(df):
            parts.append(df)
        print(code, strike, side, d0, len(df), flush=True)
        d0 = d1
    df = pd.concat(parts).drop_duplicates("ts").sort_values("ts")
    for c in df.columns:
        if c != "ts":
            df[c] = df[c].astype("float32")
    df.to_parquet(out, index=False)
    print("saved", out.name, len(df), flush=True)
