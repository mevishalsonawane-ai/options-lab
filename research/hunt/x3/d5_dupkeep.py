"""x3: when one strike appears at two offsets in the same minute (monthly series), does obuy's 'keep the first' rule
(data.py:104-106, lower offset after the stable sort) keep the live print or the stale carried row?"""
import sys, glob
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd, pyarrow.parquet as pq
D = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/dhan/repo/dhan-data/options"
for f in ["BANKNIFTY/MONTH/CALL/2022", "BANKNIFTY/MONTH/PUT/2024", "NIFTY/MONTH/CALL/2025", "BANKNIFTY/WEEK/CALL/2021"]:
    t = pq.read_table(f"{D}/{f}.parquet", columns=["ts", "offset", "strike", "close", "volume"]).to_pandas()
    t["k"] = t.strike.round()
    g = t[t.duplicated(["ts", "k"], keep=False)].sort_values(["ts", "k", "offset"])
    first = g.groupby(["ts", "k"]).head(1).set_index(["ts", "k"]); other = g.groupby(["ts", "k"]).tail(1).set_index(["ts", "k"])
    kept_stale = ((first.volume == 0) & (other.volume > 0)).sum(); kept_live = ((first.volume > 0) & (other.volume == 0)).sum()
    both_live = ((first.volume > 0) & (other.volume > 0)).sum()
    near = (first.index.get_level_values(0).isin(first.index.get_level_values(0))).sum()
    print(f"{f}: pairs {len(first)}; kept live/dropped stale {kept_live}; kept STALE/dropped live {kept_stale}; both live {both_live}; "
          f"|offset| of pair rows median {first.offset.abs().median():.0f}; pairs at |offset|<=2: {(first.offset.abs() <= 2).sum()}")
