"""R1 holdout (1 Oct 2025 - 6 Oct 2026), run ONCE on the frozen picks of design.py.

python3 -I research/hunt/r1/holdout.py -> scratchpad/hunt/r1/{holdout_picks.csv, holdout_trades_<U>.parquet, holdout_monthly.csv}
"""
from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib as L  # noqa: E402
import design as Dz  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402


def main():
    marker = os.path.join(L.OUT, "holdout_opened.txt")
    if os.path.exists(marker):
        sys.exit("holdout already opened once: " + open(marker).read())
    with open(marker, "w") as f:
        f.write(pd.Timestamp.now().isoformat())
    fr = json.load(open(os.path.join(L.OUT, "frozen.json")))
    picks = pd.DataFrame(fr["picks"])
    out, months = [], []
    for u in Dz.UNDS:
        vs = set(picks[picks.und == u]["var"])
        P, mask, req, elig = Dz.run_period(u, "holdout")
        req = req[req["var"].isin(vs)].reset_index(drop=True)
        tw = Dz.twins(req, seed=99)
        tw["rep"] = np.arange(len(tw)) % Dz.NRAND
        real = Dz.engine_dedup(P, req)
        rnd = Dz.engine_dedup(P, tw)
        df, daily = Dz.summarize(real, rnd, elig, P, mask)
        df.insert(0, "und", u)
        out.append(df)
        real.to_parquet(os.path.join(L.OUT, f"holdout_trades_{u}.parquet"))
        mon = pd.Series([d.strftime("%Y-%m") for d in P["days"]])
        for var, dd in daily.items():
            s = pd.Series(dd[mask]).groupby(mon[mask].values).sum()
            months.append(dict(und=u, var=var, green_months=int((s > 0).sum()), months=len(s),
                               worst_month=float(s.min()), best_month=float(s.max())))
        L.D.market().release()
    res = pd.concat(out, ignore_index=True)
    res = res.merge(pd.DataFrame(months), on=["und", "var"], how="left")
    res.to_csv(os.path.join(L.OUT, "holdout_picks.csv"), index=False)
    pd.set_option("display.width", 250)
    print(res[["und", "var", "trades", "rs_day", "rs_trade", "t", "win", "rand_trade", "p_rand", "maxdd",
               "green_months", "months"]].round(3).to_string())


if __name__ == "__main__":
    main()
