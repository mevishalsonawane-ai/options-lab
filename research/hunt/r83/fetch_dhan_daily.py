"""R83: Dhan continuous near-month DAILY futures (expiryCode 0) for the MCX energy minis and base metals that the lake
does not hold. Token from the environment via research/data/dhan_client.py (headers only, never printed). Output to
/dev/shm (never committed).

    python3 -m research.hunt.r83.fetch_dhan_daily OUTDIR
"""
import csv
import datetime as dt
import io
import sys

import pandas as pd
import requests

from research.data import dhan_client as D

MASTER_URL = "https://images.dhan.co/api-data/api-scrip-master-detailed.csv"
WANT = ["COPPER", "ZINC", "ZINCMINI", "ALUMINIUM", "ALUMINI", "LEAD", "CRUDEOILM", "NATGASMINI"]


def main(out):
    txt = requests.get(MASTER_URL, timeout=120).text
    rows = [r for r in csv.DictReader(io.StringIO(txt)) if r["EXCH_ID"] == "MCX" and r["INSTRUMENT"] == "FUTCOM"]
    frames = []
    for s in WANT:
        live = sorted((r["SM_EXPIRY_DATE"], r["SECURITY_ID"]) for r in rows if r["UNDERLYING_SYMBOL"] == s)
        if not live:
            print(s, "not in master"); continue
        sid = live[0][1]
        st, js, err = D.post("/charts/historical", dict(securityId=sid, exchangeSegment="MCX_COMM", instrument="FUTCOM",
                                                         expiryCode=0, oi=True, fromDate="2010-01-01",
                                                         toDate=(dt.date.today() + dt.timedelta(1)).isoformat()))
        if not js or "close" not in js:
            print(s, "failed", st, D.redact(err)); continue
        df = pd.DataFrame({k: js[k] for k in ("open", "high", "low", "close", "volume", "timestamp") if k in js})
        if "open_interest" in js:
            df["oi"] = js["open_interest"]
        df["date"] = pd.to_datetime(df.timestamp, unit="s", utc=True).dt.tz_convert("Asia/Kolkata").dt.date
        df["symbol"] = s
        frames.append(df.drop(columns="timestamp"))
        print(s, len(df), df.date.min(), df.date.max(), flush=True)
    pd.concat(frames).to_parquet(f"{out}/mcx_daily_extra.parquet")


if __name__ == "__main__":
    main(sys.argv[1])
