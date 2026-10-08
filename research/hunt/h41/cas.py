"""h41 part 3: the closing minutes before / after the Closing Auction Session (CAS, from 3 Aug 2026).

Run:  python3 -I research/hunt/h41/cas.py   -> scratchpad/hunt/h41/cas.log
Reads the raw 2026 index minute files directly (the obuy loader cuts at 15:29) and the NIFTY weekly option minutes.
"""
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import pyarrow.parquet as pq  # noqa: E402
from obuy import config as C  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h41")
CAS = pd.Timestamp("2026-08-03").date()
lines = []
for u in ["NIFTY", "BANKNIFTY", "SENSEX"]:
    df = pd.read_parquet(os.path.join(C.DATA, "candles", "minute", "IDX_I", u, "2026.parquet"))
    df["ts"] = df.ts.dt.tz_localize(None)
    df = df.sort_values("ts")
    df["d"] = df.ts.dt.date
    df["m"] = df.ts.dt.hour * 60 + df.ts.dt.minute
    df["r"] = df.groupby("d").close.transform(lambda s: np.log(s).diff().abs() * 1e4)
    for nm, s in [("2026 before CAS", df.d < CAS), ("from 3 Aug 2026 (CAS)", df.d >= CAS)]:
        x = df[s].groupby("m").r.mean()
        lines.append(f"{u:9s} {nm:22s} days {df[s].d.nunique():3d} | 14:59 {x[899]:.2f} | 15:00 {x[900]:.2f} | "
                     f"15:01 {x[901]:.2f} | 15:15-15:27 avg {x.loc[915:927].mean():.2f} | 15:28 {x[928]:.2f} | "
                     f"15:29 {x[929]:.2f} | 12:00-13:00 avg {x.loc[720:780].mean():.2f}  (mean |1-min return| bps)")
t = pq.read_table(os.path.join(C.DATA, "options", "NIFTY", "WEEK", "CALL", "2026.parquet"), columns=["ts", "volume"]).to_pandas()
t["ts"] = t.ts.dt.tz_localize(None)
t = t[t.ts >= pd.Timestamp("2026-08-03")]
t["m"] = t.ts.dt.hour * 60 + t.ts.dt.minute
v = t.groupby("m").volume.sum()
lines.append(f"NIFTY weekly CE minutes after CAS: last bar {v.index.max() // 60}:{v.index.max() % 60:02d}; volume 15:15-15:29 = "
             f"{100 * v.loc[915:929].sum() / v.sum():.1f}% of day, 15:30-15:39 = {100 * v.loc[930:939].sum() / v.sum():.1f}%")
open(os.path.join(OUT, "cas.log"), "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
