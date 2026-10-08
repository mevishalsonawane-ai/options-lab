"""x3 data check 2: what the out-of-session rows are, and examples of one strike at two offsets in one minute."""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd, pyarrow.parquet as pq
D = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/dhan/repo/dhan-data/options"
t = pq.read_table(f"{D}/BANKNIFTY/WEEK/CALL/2021.parquet").to_pandas()
t["ts"] = t.ts.dt.tz_convert("Asia/Kolkata"); t["m"] = t.ts.dt.hour * 60 + t.ts.dt.minute; t["d"] = t.ts.dt.date
o = t[(t.m < 555) | (t.m > 929)]
print("out-of-session days:", o.d.nunique(), "minutes:", o.m.value_counts().sort_index().to_dict() if o.m.nunique() < 40 else o.m.describe())
print(o.groupby("d").agg(n=("m", "size"), lo=("m", "min"), hi=("m", "max"), vol=("volume", "sum")).head(15))
dd = o.d.iloc[0]
x = t[(t.d == dd) & (t.offset == 0)].sort_values("ts")
print("in-session rows that day (ATM):", ((x.m >= 555) & (x.m <= 929)).sum())
print(x[(x.m >= 925)].head(12)[["ts", "strike", "open", "close", "volume", "spot"]])
m = pq.read_table(f"{D}/BANKNIFTY/MONTH/CALL/2024.parquet").to_pandas()
m["k"] = m.strike.round()
g = m[m.duplicated(["ts", "k"], keep=False)].sort_values(["ts", "k", "offset"])
print(g.head(16)[["ts", "offset", "strike", "spot", "open", "close", "volume", "oi"]])
print("offset pairs:", g.groupby(["ts", "k"]).offset.apply(tuple).value_counts().head(8))
