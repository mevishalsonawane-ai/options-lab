"""x2 step D1 (descriptive, index level, PRE ONLY, no option P&L): h18 60-min-high/low break continuation (index bp
30 min after the break, in the break's direction) on 'active' days (previous day |move| >= 1% or today's |gap| >= 0.5%,
h42 facts) vs other days."""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
from datetime import date
import numpy as np, pandas as pd
S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt"
HOLD = pd.Timestamp("2025-10-01")
e = pd.read_parquet(f"{S}/h18/cache/h18/break_events_all.parquet")
e = e[(e.kind == "break") & (e.day < HOLD) & e.lvl.isin(["R60H", "R60L"])]
f = pd.read_parquet(f"{S}/h31/feat.parquet")[["und", "day", "nday", "DAY", "gap"]]
f["day"] = pd.to_datetime(f.day); f["nday"] = pd.to_datetime(f.nday)
reg = pd.DataFrame(dict(und=f.und, day=f.nday, prevday=f.DAY, gap=f.gap))  # DAY of D-1 and gap into D
e = e.merge(reg, on=["und", "day"], how="left")
e["active"] = (e.prevday.abs() >= 0.01) | (e.gap.abs() >= 0.005)
for u in ["BANKNIFTY", "NIFTY"]:
    g = e[e.und == u]
    for a in (True, False):
        h = g[g.active == a]
        for c in ("f15", "f30", "f60"):
            x = h[c].dropna()
            print(u, "active" if a else "other", c, len(x), f"mean {x.mean():+.2f} t {x.mean()/x.std()*np.sqrt(len(x)):+.2f} mean|.| {x.abs().mean():.1f}")
