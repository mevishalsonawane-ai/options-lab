"""R4 POST-HOC descriptive: how often does the day's high or low land within 0.05% of SOME claimed level (and of
some placebo level)? Shows why levels 'always work' in hindsight. python3 -I hindsight.py"""
from __future__ import annotations
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib4 as L4  # noqa: E402
import placebo as PL  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
L = L4.L
rows = []
for u in L4.UNDS:
    P = L.load_index(u)
    days = np.nonzero(P["ok"] & ~P["hold"])[0]
    rng = np.random.default_rng(1)
    for di in days:
        h, l = np.nanmax(P["h"][di][:L4.SQ]), np.nanmin(P["l"][di][:L4.SQ])
        for fam, (cl, pls) in PL.fam_levels(u, P, di, rng).items():
            for k, lev in enumerate([cl, pls[0]]):
                inr = ((lev >= l) & (lev <= h)).sum()
                near = lambda x: (np.abs(lev / x - 1) <= 0.0005).any()  # noqa: E731
                rows.append((u, fam, k, inr, int(near(h) or near(l))))
    L.D.market().release()
d = pd.DataFrame(rows, columns=["und", "fam", "placebo", "levels_in_range", "extreme_near"])
out = d.groupby(["fam", "placebo"]).agg(levels_in_range=("levels_in_range", "mean"), extreme_near=("extreme_near", "mean")).unstack()
out.to_csv(os.path.join(L4.OUT, "hindsight.csv"))
print(out.round(3).to_string())
