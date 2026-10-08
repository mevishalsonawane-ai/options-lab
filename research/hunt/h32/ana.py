"""h32 analysis of fwd.py pre-holdout results: per-variant P&L, random baseline, BH/Holm, SPA, walk-forward, gates.
python3 -I research/hunt/h32/ana.py  -> ana_variants.csv, ana.log (stdout)"""
from __future__ import annotations

import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import DISC_END, HOLD, OUT, UNDS  # noqa: E402
from fwd import F25, trading_days  # noqa: E402
from obuy import overfit as OV  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

pd.set_option("display.width", 250)
T = pd.read_parquet(os.path.join(OUT, "pre_trades.parquet"))
P = pd.read_parquet(os.path.join(OUT, "pre_pools.parquet"))
T["year"] = pd.to_datetime(T.dn, unit="D").dt.year
fwd = T[(T.dn >= DISC_END) & (T.dn < HOLD)]
Pf = P[(P.dn >= DISC_END) & (P.dn < HOLD)]
days_f = trading_days(DISC_END, HOLD)
days_d = trading_days(0, DISC_END)
nd_f = len(days_f)
rows = []
for v, g in T.groupby("vid"):
    gf = g[(g.dn >= DISC_END) & (g.dn < HOLD)]
    gd = g[g.dn < DISC_END]
    r = dict(vid=v, n_fwd=len(gf), gross_fwd=gf.gross.sum(), net_fwd=gf.net.sum(), netsp_fwd=gf.net_sp.sum(),
             netst_fwd=gf.net_st.sum(), rsday_gross=gf.gross.sum() / nd_f, rsday_netsp=gf.net_sp.sum() / nd_f,
             win=(gf.net_sp > 0).mean(), netsp_disc=gd.net_sp.sum(), rsday_disc=gd.net_sp.sum() / len(days_d),
             y2024=gf[gf.dn < F25].net_sp.sum(), y2025a=gf[gf.dn >= F25].net_sp.sum())
    rb = OV.random_baseline(gf.assign(net=gf.net_sp), Pf[Pf.vid == v], B=2000)
    r.update(p_rand=rb["p"], rand_mean=rb.get("null_mean"), obs_mean=rb.get("obs"))
    rows.append(r)
V = pd.DataFrame(rows)
V.loc[V.netsp_fwd <= 0, "p_rand"] = 1.0
V["bh_q"] = OV.bh(V.p_rand.values)
V["holm"] = OV.holm(V.p_rand.values)
# walk-forward (anchored by year, net+spread, 2021..2025-09; rules were chosen on <=2023, so only 2024/2025 tests are clean)
pre = T[T.dn < HOLD]
by = pre.pivot_table(index="vid", columns="year", values="net_sp", aggfunc="sum").fillna(0)
cnt = pre.pivot_table(index="vid", columns="year", values="net_sp", aggfunc="count").fillna(0)
trades = {v: g.assign(net=g.net_sp) for v, g in pre.groupby("vid")}
wf, oos = OV.walk_forward(by, trades, train_years=2, min_trades=20, counts=cnt)
print("walk-forward (net+spread, 1 lot):")
for w in wf:
    print(" ", w)
# SPA over forward daily P&L
X = pd.DataFrame({v: g.groupby("day").net_sp.sum() for v, g in fwd.groupby("vid")})
X = X.reindex(days_f).fillna(0)
sp = OV.spa(X.values, B=1000)
spg = OV.spa(pd.DataFrame({v: g.groupby("day").gross.sum() for v, g in fwd.groupby("vid")}).reindex(days_f).fillna(0).values, B=1000)
print("SPA forward net+spread:", sp, "\nSPA forward gross:", spg)
V["gate"] = (V.netsp_fwd > 0) & (V.bh_q < 0.10) & (V.y2024 > 0) & (V.y2025a > 0)
V = V.sort_values("netsp_fwd", ascending=False)
V.to_csv(os.path.join(OUT, "ana_variants.csv"), index=False)
print("forward trading days", nd_f, "variants", len(V))
print(V.round(3).to_string())
print("WF oos total net_sp:", oos.net.sum() if len(oos) else 0, "gross:", oos.gross.sum() if len(oos) else 0)
print("survivors:", V[V.gate].vid.tolist())
# per index for top 5 variants by forward net+spread
for v in V.vid.head(5):
    g = fwd[fwd.vid == v]
    print(v, "\n", g.groupby("und").agg(n=("net", "size"), gross=("gross", "sum"), net=("net", "sum"), net_sp=("net_sp", "sum")).round(0))
