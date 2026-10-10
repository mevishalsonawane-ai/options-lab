"""r2: parse untrusted raw downloads (Yahoo JSON, FRED CSV, EIA XLS, CFTC zips) into scratchpad/hunt/r2/data/*.parquet.
Run with python3 -I. Timestamps kept in UTC (tz-naive)."""
import json, zipfile, io, sys
from pathlib import Path
import numpy as np, pandas as pd
R = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r2")
RAW, D = R / "raw", R / "data"; D.mkdir(exist_ok=True)

def yjson(p):
    j = json.loads(p.read_text())["chart"]["result"][0]
    q = j["indicators"]["quote"][0]
    df = pd.DataFrame({k: q.get(k) for k in ("open", "high", "low", "close", "volume")})
    df["ts"] = pd.to_datetime(j["timestamp"], unit="s")
    return df.dropna(subset=["close"]).set_index("ts").sort_index(), j["meta"].get("gmtoffset", 0)

for iv in ("d", "h", "m5", "m1"):
    out = []
    for p in sorted((RAW / iv).glob("*.json")):
        try:
            df, off = yjson(p)
        except Exception as e:
            print("skip", iv, p.name, e); continue
        df["sym"] = p.stem; out.append(df.reset_index())
    a = pd.concat(out); a.to_parquet(D / f"yahoo_{iv}.parquet")
    print(iv, a.groupby("sym").ts.agg(["min", "max", "count"]).to_string())

fr = []
for p in sorted((RAW / "fred").glob("*.csv")):
    f = pd.read_csv(p); f.columns = ["date", "v"]; f["v"] = pd.to_numeric(f.v, errors="coerce"); f["id"] = p.stem; fr.append(f.dropna())
fr = pd.concat(fr); fr["date"] = pd.to_datetime(fr.date); fr.to_parquet(D / "fred.parquet")
print(fr.groupby("id").date.agg(["min", "max", "count"]))

for nm in ("WCESTUS1w", "NW2_EPG0_SWO_R48_BCFw"):
    try:
        x = pd.read_excel(RAW / "eia" / f"{nm}.xls", sheet_name="Data 1", skiprows=2)
        x.columns = ["date", "v"]; x["date"] = pd.to_datetime(x.date); x.to_parquet(D / f"eia_{nm}.parquet"); print(nm, x.date.min(), x.date.max(), len(x))
    except Exception as e:
        print("eia fail", nm, e)

rows = []
keep = {"067651": "CL", "023651": "NG", "088691": "GC", "084691": "SI"}
for p in sorted((RAW / "cot").glob("*.zip")):
    z = zipfile.ZipFile(p)
    for n in z.namelist():
        t = pd.read_csv(z.open(n), low_memory=False, dtype={"CFTC_Contract_Market_Code": str})
        t = t[t["CFTC_Contract_Market_Code"].astype(str).str.strip().isin(keep)]
        dcol = [c for c in t.columns if c.startswith("Report_Date_as")][0]
        rows.append(pd.DataFrame({"date": pd.to_datetime(t[dcol]), "code": t["CFTC_Contract_Market_Code"].str.strip().map(keep),
                                  "mm_long": t["M_Money_Positions_Long_All"], "mm_short": t["M_Money_Positions_Short_All"],
                                  "oi": t["Open_Interest_All"]}))
c = pd.concat(rows).drop_duplicates(["date", "code"]).sort_values("date"); c.to_parquet(D / "cot.parquet")
print(c.groupby("code").date.agg(["min", "max", "count"]))
