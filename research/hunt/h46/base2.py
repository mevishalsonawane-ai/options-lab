"""h46 precision rerun of the random baseline (B=2000 instead of 200) for the variants with in-sample net > 0.
POST-HOC only in resolution: with B=200 the smallest p is 1/201, so BH over 2,400 tests could never pass. Same
design otherwise (time of day + side matched, same money, same holding length, same calendar year).

    flock <scratch>/obuy.lock python3 -I research/hunt/h46/base2.py
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import sim  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402

sim.B = 2000

V = pd.read_csv(os.path.join(sim.OUT, "variants.csv"))
pos = V[V.net > 0]
out = []
for u, pv in pos.groupby("und"):
    mk = market()
    ix = mk.index(u)
    days = ix.days
    pidx = {d: i for i, d in enumerate(days)}
    exps = np.array([ix.d[d]["exp"] for d in days])
    lots = np.array([ix.lot(d) for d in days])
    sel = np.array([d < sim.HO for d in days])
    tr = pd.read_parquet(os.path.join(sim.OUT, f"trades_{u}.parquet"))
    tr["dpos"] = tr.day.map(pidx).values
    orc = sim.Oracle(mk, u, days, sel)
    keys = set(zip(pv.tf, pv.src, pv.rej, pv.xm, pv.money))
    tr = tr[[k in keys for k in zip(tr.tf, tr.src, tr.rej, tr.xm, tr.money)]]
    sim.OUT_SAVE = sim.OUT
    # reuse sim.baseline, writing to a temp name, then keep only the positive variants
    real_out = sim.OUT
    sim.OUT = os.path.join(real_out, "b2tmp")
    os.makedirs(sim.OUT, exist_ok=True)
    sim.baseline(u, tr, orc, days, exps, lots, sel, spot_mat=ix.mat()["c"], bse=u in C.BSE)
    b = pd.read_parquet(os.path.join(sim.OUT, f"base_{u}.parquet"))
    sim.OUT = real_out
    out.append(b)
    mk.release(u)
    print(u, "done", flush=True)
B2 = pd.concat(out, ignore_index=True)
B2.to_parquet(os.path.join(sim.OUT, "base2.parquet"))
m = V.merge(B2[["und", "tf", "src", "rej", "xm", "money", "mx", "ex", "p"]].rename(columns={"p": "p2"}),
            on=["und", "tf", "src", "rej", "xm", "money", "mx", "ex"], how="left")
m["p2"] = m.p2.fillna(1.0)
from obuy.overfit import bh  # noqa: E402
m["q2"] = bh(m.p2.values)
m.to_csv(os.path.join(sim.OUT, "variants_b2.csv"), index=False)
pd.set_option("display.width", 250)
print(m.sort_values("p2")[["vid", "n", "net_t", "net_d", "p_base", "p2", "q2"]].head(20).to_string())
print("min q2", m.q2.min(), "n q2<0.10", int((m.q2 < 0.10).sum()))
