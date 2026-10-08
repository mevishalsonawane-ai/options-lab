"""h34: IS-best entry parameters per registry strategy from the EARLIER catalog runs, pre-holdout days only.

    python3 -I research/hunt/h34/select_is.py      -> scratchpad/hunt/h34/is_best.json

IS-best = the variant (sig, rule, exits) with the highest net over days < 2025-10-01 in the earlier run that holds the
strategy (runs/{singles,grids,ga_all,gc_all} trades.csv.gz; runs/gb_all_c*/chunk.pkl daily matrices).
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
from obuy.final import load_pkl  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.strategies.registry import all_strategies  # noqa: E402

RUNS = os.path.join(C.CACHE, "runs")
OUT = os.path.join(C.SCRATCH, "hunt", "h34")
HOLD = date(2025, 10, 1)


def from_trades(run):
    t = pd.read_csv(os.path.join(RUNS, run, "trades.csv.gz"), usecols=["vid", "day", "net"])
    t = t[pd.to_datetime(t.day).dt.date < HOLD]
    return t.groupby("vid").net.sum()


def main():
    os.makedirs(OUT, exist_ok=True)
    nets = []
    for run in ("singles", "grids", "ga_all", "gc_all"):
        nets.append(from_trades(run))
        print(run, len(nets[-1]), flush=True)
    for k in range(7):
        r = load_pkl(os.path.join(RUNS, f"gb_all_c{k}", "chunk.pkl"))
        days = np.array(r["days"])
        m = np.array([d < HOLD for d in days])
        X = np.asarray(r["X"])
        nets.append(pd.Series(X[m].sum(axis=0), index=r["vids"]))
        print("gb", k, len(r["vids"]), flush=True)
    net = pd.concat(nets)
    net = net[~net.index.duplicated()]
    S = all_strategies()
    out = {}
    for name, st in S.items():
        v = net[[i for i in net.index if i.split("|")[0] == name]]
        if v.empty:
            out[name] = dict(found=False, si=0, ri=0, xi=0)
            print("MISSING", name)
            continue
        best = v.idxmax()
        _, s, r, x = best.split("|")
        out[name] = dict(found=True, vid=best, si=int(s[1:]), ri=int(r[1:]), xi=int(x[1:]), pre_net=float(v[best]),
                         n_variants=int(len(v)), sig=str(st.sig_grid[int(s[1:])]))
        print(f"{name:26s} best {best:34s} pre net {v[best]:12,.0f}  ({len(v)} variants)")
    with open(os.path.join(OUT, "is_best.json"), "w") as f:
        json.dump(out, f, indent=1)


if __name__ == "__main__":
    main()
