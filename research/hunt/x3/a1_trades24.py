"""x3: decompose h24/h36 Plan A (BANKNIFTY Liquidity 1 lot) costs per trade from trades24.parquet."""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd
S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt"
T = pd.read_parquet(f"{S}/h24/trades24.parquet")
T["day"] = pd.to_datetime(T.day)
print(T.columns.tolist()); print(T.model.unique())
for per, m in (("pre", T.day < "2025-10-01"), ("hold", T.day >= "2025-10-01")):
    for kap in (0.02, 0.04):
        t = T[m & (T.model == "flat_x1") & (T.kappa == kap) & (T.und == "BANKNIFTY")]
        f = t[t.lots > 0]
        days = t.day.nunique()
        print(f"{per} k{kap}: signals {len(t)} filled {len(f)} missed {(t.lots==0).mean():.1%} | per filled trade: gross {f.gross.mean():7.1f} "
              f"charges {f.charges.mean():6.1f} spread_extra {f.spread_extra.mean():6.1f} net {f.net.mean():7.1f} | "
              f"prem/lot {(f.prem/f.lots).mean():8.0f} lot {f.lot.mean():.1f} | gross-net-chg-extra (=fill slip+impact) {(f.gross-f.net-f.charges-f.spread_extra).mean():6.1f}")
t2 = T[(T.model == "flat_x1") & (T.und == "BANKNIFTY")]
a = t2[t2.kappa == 0.02].set_index("cand"); b = t2[t2.kappa == 0.04].set_index("cand")
j = a.join(b[["net", "lots"]], rsuffix="_4")
both = j[(j.lots > 0) & (j.lots_4 > 0)]
print("trades filled at both kappas:", len(both), "net .02 - net .04 per trade:", round((both.net - both.net_4).mean(), 1),
      "=> impact at k .02 ~ same per trade; filled at .02 only:", int(((j.lots > 0) & (j.lots_4 == 0)).sum()))
print("why counts (BN, k.02, filled):", t2[(t2.kappa == 0.02) & (t2.lots > 0)].why.value_counts().to_dict())
