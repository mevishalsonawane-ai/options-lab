"""x3 data check 1: minute-of-day coverage per file, out-of-session rows, duplicate (ts, offset) rows, a strike at two
offsets in one minute (and whether the two rows disagree), volume-0 rows (carried quotes)."""
import sys, glob, os
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pyarrow.parquet as pq
D = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/dhan/repo/dhan-data/options"
for und in ("NIFTY", "BANKNIFTY"):
    for f in sorted(glob.glob(f"{D}/{und}/*/*/*.parquet")):
        t = pq.read_table(f, columns=["ts", "offset", "strike", "open", "high", "low", "close", "volume"]).to_pandas()
        ts = t.ts.dt.tz_convert("Asia/Kolkata")
        m = ts.dt.hour * 60 + ts.dt.minute
        out = ((m < 555) | (m > 929)).sum()
        dup = t.duplicated(["ts", "offset"]).sum()
        k = t.assign(k=t.strike.round()).duplicated(["ts", "k"], keep=False)
        g = t[k].assign(k=t.strike.round()).groupby(["ts", "k"])
        dis = (g.close.nunique() > 1).sum() if k.any() else 0
        v0 = (t.volume == 0).mean()
        flat0 = ((t.volume == 0) & (t.open == t.close) & (t.high == t.low)).mean()
        print(f"{f.split('options/')[1]:38s} rows {len(t):>9,} outsess {out:>6} dup(ts,off) {dup:>5} strike@2offs {k.sum()//2:>6} disagree {dis:>5} vol0 {v0:6.1%} vol0flat {flat0:6.1%} m {m.min()}-{m.max()}", flush=True)
