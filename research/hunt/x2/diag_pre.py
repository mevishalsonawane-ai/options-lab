"""x2 diagnostics on PRE only (after the PRE run; chooses nothing): decomposition of the night rules and
concentration. Written after pre.md; labelled diagnostic."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as X
import numpy as np, pandas as pd
W = X.load_night(); W = W[W.day < X.HOLD].reset_index(drop=True)
buz = W.buz.fillna(0).values
W["r17only"] = W.r17
W["buzonly"] = np.where(buz >= .5, 1, np.where(buz <= -.5, -1, 0))
W["r3ce"] = np.where(W.r3 > 0, 1, 0)
W["r3pe"] = np.where(W.r3 < 0, -1, 0)
W["ceall"] = 1
for c in ["r17only", "buzonly", "r3", "r3ce", "r3pe", "r4", "ceall"]:
    r = X.night_rows(W, c)
    for u in ("NIFTY", "BANKNIFTY"):
        g = r[r.und == u]
        if not len(g):
            continue
        top5 = np.sort(g.net.values)[-5:].sum()
        yr = g.groupby(g.day.dt.year).net.sum().round().astype(int).to_dict()
        print(f"{c:8s} {u:9s} n={len(g):4d} net/trade={g.net.mean():7.1f} total={g.net.sum():9.0f} "
              f"coin-flip/trade={(g.net.mean()+g.alt.mean())/2:7.1f} top5 share={top5/max(g.net.sum(),1):.2f} years={yr}")
