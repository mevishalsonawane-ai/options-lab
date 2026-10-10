"""POST-HOC (after the placebo tables): are the "Gann evening marks" (17:45 / 18:30 / 20:00 IST) a US-clock effect?
Split the time-placebo hits by US daylight time (EDT: 18:00 IST = 08:30 ET; EST: 19:00 IST = 08:30 ET) and add a
'US clock' mark set (08:30 / 09:00 / 10:30 ET in IST, DST-exact). python3 -I posthoc_evetime.py"""
from __future__ import annotations
import os, sys, zlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib6 as L6  # noqa: E402
import placebo6 as PL  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

PL.fam_levels = lambda *a, **k: {}
rows = []
for u in ["CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM"]:
    P = L6.load_panel(u)
    for per in ("design", "holdout"):
        mask = P[per] & ~P["roll"]
        # inject US-clock marks by temporarily extending the panel's marks: run, then compute separately below
        ev, tc, tm = PL.run_panel(P, mask, L6.GRID[u], 1.0, 1.0, seed=zlib.crc32(f"{u}{per}".encode()))
        tm["dst"] = [PL.L6.M.us_dst(pd.Timestamp(P["days"][d])) for d in tm.di]
        for (fam, dst), g in tm.groupby(["fam", "dst"]):
            if not fam.startswith("GANN_TOPEN"):
                continue
            c, p = g[g.draw == -1].hit.mean(), g[g.draw >= 0].hit.mean()
            rows.append(dict(und=u, period=per, fam=fam, us_dst=dst, claimed=c, placebo=p, diff=c - p,
                             n=int((g.draw == -1).sum())))
out = pd.DataFrame(rows)
out.to_csv(os.path.join(str(L6.OUT), "posthoc_evetime.csv"), index=False)
print(out.round(3).to_string())
