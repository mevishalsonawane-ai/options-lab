"""R2: holdout summary of frozen candidates C1-C5 + FOMC: BH across all holdout candidate cells, pass flags,
long/short split, per-month P&L of the passing cells."""
import numpy as np, pandas as pd
from lib import *
assert (R2 / "holdout.lock").exists()
c = pd.read_csv(OUT / "cand_holdout.csv")
l = pd.read_csv(OUT / "lead_holdout.csv"); l = l[l.com != "GOLD"]  # gold lead = C4 in cand
l["exit"] = "15m"
A = pd.concat([c, l], ignore_index=True)
A["q_bh"] = bh(A.p_rand.values)
A["pass_prereg"] = (A.net_tr > 0) & (A.p_rand < 0.10) & (A.mos_pos >= 0.5)
A["pass_bh"] = A.pass_prereg & (A.q_bh < 0.10)
A.to_csv(OUT / "holdout_all_cells.csv", index=False)
pd.set_option("display.width", 250); pd.set_option("display.max_rows", 200)
print("cells", len(A), "pass prereg", int(A.pass_prereg.sum()), "pass + BH q<0.10", int(A.pass_bh.sum()))
cols = ["com", "rule", "exit", "inst", "n", "hit", "win", "gross_tr", "net_tr", "net_day", "maxdd", "mos_pos", "n_mos", "p_rand", "q_bh", "pass_bh"]
print(A[A.pass_prereg][cols].round(3).to_string())
T = pd.concat([pd.read_parquet(OUT / "cand_trades_holdout.parquet"), pd.read_parquet(OUT / "lead_trades_holdout.parquet").assign(exit="15m")], ignore_index=True)
T["date"] = pd.to_datetime(T.date)
for _, r in A[A.pass_prereg].iterrows():
    g = T[(T.com == r.com) & (T.rule == r.rule) & (T.exit == r.exit) & (T.inst == r.inst)]
    ls = g.groupby(np.where(g.side > 0, "long", "short")).net.agg(["size", "mean"]).round(0)
    mo = g.groupby(g.date.dt.to_period("M")).net.sum().round(0)
    print(f"\n{r.com} {r.rule} {r.exit} {r.inst}: long/short {ls.to_dict('index')}")
    print("  by month:", " ".join(f"{k}:{int(v)}" for k, v in mo.items()))
