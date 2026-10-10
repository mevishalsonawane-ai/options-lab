"""Parse Yahoo chart JSON (untrusted; run with python3 -I) into parquet. Timestamps kept in UTC."""
import json, sys, glob, os
sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd
R = sys.argv[1]; OUT = sys.argv[2]
for sub, tag in (("d", "1d"), ("h", "1h")):
    for f in sorted(glob.glob(f"{R}/{sub}/*.json")):
        name = os.path.basename(f)[:-5]
        try:
            js = json.load(open(f))
            res = js["chart"]["result"][0]
            q = res["indicators"]["quote"][0]
            df = pd.DataFrame({k: q.get(k) for k in ("open", "high", "low", "close", "volume")})
            df.index = pd.to_datetime(res["timestamp"], unit="s", utc=True).tz_localize(None)
            df.index.name = "ts_utc"
            df = df.dropna(subset=["close"]).astype("float64")
            t = "5m" if name.endswith("_5m") else tag
            nm = name.replace("_5m", "")
            df.to_parquet(f"{OUT}/yahoo_{t}_{nm}.parquet", compression="zstd")
            print(t, nm, len(df), df.index[0], df.index[-1])
        except Exception as e:
            print("FAIL", sub, name, type(e).__name__, str(e)[:100])
