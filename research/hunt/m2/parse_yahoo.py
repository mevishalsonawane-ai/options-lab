"""Parse Yahoo chart JSON (untrusted; run with python3 -I) -> scratchpad/hunt/m2/data/yahoo.parquet (long)."""
import json, sys, glob, os
sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd
R = sys.argv[1]; OUT = sys.argv[2]
rows = []
for f in glob.glob(f"{R}/*.json"):
    js = json.load(open(f))["chart"]["result"][0]
    q = js["indicators"]["quote"][0]
    df = pd.DataFrame({k: q[k] for k in ("open", "high", "low", "close", "volume")})
    df["date"] = pd.to_datetime(js["timestamp"], unit="s").normalize()
    df["sym"] = js["meta"]["symbol"]
    rows.append(df.dropna(subset=["close"]))
    print(js["meta"]["symbol"], len(df), df.date.iloc[0].date(), df.date.iloc[-1].date())
pd.concat(rows).to_parquet(OUT, compression="zstd")
