"""R2: merge all DESIGN cells, BH across every cell, apply the PREREG gates."""
import numpy as np, pandas as pd
from scipy import stats as ss
from lib import *
parts = []
d = pd.read_csv(OUT / "daily_design.csv"); parts.append(d[d.fam.isin(["F1", "F5"])])
parts.append(pd.read_csv(OUT / "hourly_cells_design.csv"))
parts.append(pd.read_csv(OUT / "events_design.csv"))
lt = pd.read_csv(OUT / "leadlag_trade_design.csv")
lt = pd.DataFrame(dict(fam="F3", rule=[f"us_lead_look{a}_hz{b}_q{c}" for a, b, c in zip(lt.look, lt.hz, lt.q)], com=lt.com, n=lt.n,
                       hit=lt.hit, mean_bp=lt.mean_bp, t=lt.t, p_rand=ss.norm.sf(lt.t),
                       net_tr=(lt.mean_bp - lt.cost_bp) * 1e-4 * lt.com.map({"GOLD": 148000, "SILVER": 225000, "CRUDE": 85000, "NATGAS": 77500}), mos_pos=np.nan, yrs_pos=np.nan))
parts.append(lt)
A = pd.concat(parts, ignore_index=True)
A["q_bh"] = bh(A.p_rand.values)
per = np.where(A.yrs_pos.notna() & (A.get("n_years", pd.Series(np.nan, index=A.index)).fillna(0) >= 3), A.yrs_pos, A.mos_pos)
A["consist"] = per
A["gate"] = (A.n >= 30) & (A.net_tr > 0) & (A.p_rand < 0.05) & (A.q_bh < 0.10) & ((A.consist.fillna(1)) >= 0.55)
A.to_csv(OUT / "design_all_cells.csv", index=False)
pd.set_option("display.width", 250); pd.set_option("display.max_rows", 300)
print("cells:", len(A), " pass gate:", int(A.gate.sum()))
print(A.sort_values("p_rand")[["fam", "rule", "com", "n", "hit", "mean_bp", "t", "p_rand", "q_bh", "net_tr", "consist", "gate"]].round(3).head(40).to_string())
