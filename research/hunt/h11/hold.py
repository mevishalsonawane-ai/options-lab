"""h11 stage 3: the ONE holdout test (2025-10-01 .. latest) of the variants frozen in choice.json by pre.py.

    OBUY_CACHE=<scratch>/hunt/h11/cache python3 -I research/hunt/h11/hold.py
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pre  # noqa: E402
from pre import PF, OF, HOLD, LONG, UNDS, col, stats  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402


def main():
    ch = json.load(open(os.path.join(pre.OUT, "choice.json")))
    T, P, D, BP = pre.load(pre=False)
    H = D[D.index >= HOLD]
    BH = BP[BP.index >= HOLD]
    res = {}
    for und in UNDS:
        key = ch["choice"][und]["key"]
        n = ch["capacity"][und]["lots"]
        t = T[(T.key == key) & (T.day >= HOLD)]
        rb = OF.random_baseline(t, P[(P.key == key) & (P.day >= HOLD)])
        s1 = col(H, key)
        r = dict(key=key, trades=len(t), net1=s1.sum(), net1_day=s1.mean(), gross1_day=col(H, key, what="gross").mean(),
                 rand_p=rb["p"], rand_obs=rb["obs"], rand_null=rb["null_mean"],
                 months1={str(k): int(v) for k, v in s1.groupby(s1.index.to_period("M")).sum().round(0).items()},
                 hold_p25_exit_v5=float(np.quantile(t.exit_v5, 0.25)) if len(t) else np.nan)
        r["confirmed"] = bool(ch["choice"][und]["PASS"] and r["net1"] > 0 and r["rand_p"] <= 0.05)
        for k in (0.01, 0.02, 0.04):
            x = col(H, key, n, k)
            st = stats(x)
            st.update(gross_day=col(H, key, n, k, "gross").mean(), lots=n,
                      P_lose_month=PF.p_losing_month(x.values, B=5000) if k == 0.02 else None,
                      prem_max=col(H, key, n, k, "prem").max())
            r[f"size_k{k}"] = st
        # combination with the h10 plan
        for lim in ("none", "both"):
            b = BH[f"{lim}|0.02|net"]
            x = col(H, key, n, 0.02)
            c = b + x
            r[f"comb_{lim}"] = dict(corr=float(np.corrcoef(b, x)[0, 1]), plan=stats(b), combined=stats(c),
                                    P_lose_month_plan=PF.p_losing_month(b.values, B=5000),
                                    P_lose_month_comb=PF.p_losing_month(c.values, B=5000))
        res[und] = r
        print(und, json.dumps(r, indent=1, default=float))
    # secondary (post-hoc metric, pre2.py) choice at 10 lots
    c2 = json.load(open(os.path.join(pre.OUT, "choice2.json")))
    for und in UNDS:
        key = c2["choice"][und]["key"]
        t = T[(T.key == key) & (T.day >= HOLD)]
        rg = OF.random_baseline(t.assign(net=t.gross), P[(P.key == key) & (P.day >= HOLD)].assign(net=lambda x: x.gross))
        x = col(H, key, 10)
        res[und + "_10lots"] = dict(key=key, net_day=x.mean(), gross_day=col(H, key, 10, what="gross").mean(),
                                    rand_p_gross=rg["p"], **{k: v for k, v in stats(x).items() if k != "net_day"})
        print(und, "10-lot choice holdout:", res[und + "_10lots"])
    # post-hoc (NOT used for any choice): the whole grid in the holdout, 1 lot
    rows = []
    for key in sorted(T.key.unique()):
        s = col(H, key)
        rows.append(dict(key=key, hold_net=s.sum(), hold_net_day=s.mean(), hold_gross_day=col(H, key, what="gross").mean()))
    G = pd.DataFrame(rows)
    print(G.round(1).to_string())
    json.dump(dict(res=res, grid_posthoc=G.round(2).to_dict(orient="records")), open(os.path.join(pre.OUT, "holdout.json"), "w"),
              indent=1, default=float)


if __name__ == "__main__":
    main()
