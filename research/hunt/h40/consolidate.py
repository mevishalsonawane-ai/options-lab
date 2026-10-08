"""h40: pack the per-day compact CSVs into a few parquet files and delete the per-day files.
    python3 -I consolidate.py
"""
import glob, os, sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd

D = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h40/data"
for pre, sub, out in (("fut_", "fo", "fo_futures.parquet"), ("opt_", "fo", "fo_idxopt_near.parquet"),
                      ("oagg_", "fo", "fo_idxopt_agg.parquet"), ("sto_", "fo", "fo_stkopt_agg.parquet"),
                      ("cm_", "cm", "cm_eq_delivery.parquet")):
    fs = sorted(glob.glob(f"{D}/{sub}/{pre}*.csv.gz"))
    if not fs:
        continue
    x = pd.concat([pd.read_csv(f, low_memory=False) for f in fs], ignore_index=True)
    for c in x.columns:
        if x[c].dtype == "float64":
            x[c] = x[c].astype("float32")
        elif x[c].dtype == object or str(x[c].dtype).startswith("str"):
            x[c] = x[c].astype(str).str.strip().astype("category")
    x.to_parquet(f"{D}/{out}", compression="zstd")
    print(out, len(x), len(fs), round(os.path.getsize(f"{D}/{out}") / 1e6, 1), "MB", flush=True)
    for f in fs:
        os.remove(f)
