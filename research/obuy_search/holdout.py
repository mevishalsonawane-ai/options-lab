"""OBUY search - the ONE look at the holdout (days >= 2025-10-01) for the rules analyze.py handed over.

    python3 -I research/obuy_search/holdout.py <scratchpad>
"""
from __future__ import annotations

import json
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402

from common import WINDOWS, monte_carlo, rule_trades  # noqa: E402

SP = sys.argv[1]
OBS = os.path.join(SP, "obs")
rng = np.random.default_rng(11)


def main():
    an = json.load(open(os.path.join(OBS, "analysis.json")))
    hs = an["holdout_set"]
    res = []
    for d in hs:
        z = np.load(os.path.join(OBS, f"ho_{d['und']}.npz"))
        days, slot, pnl, prem = rule_trades(z, d["atoms"], d["dr"], d["window"], d["strike_k"], d["exit_k"])
        nd = len(z["days"])
        daily = np.zeros(nd)
        daily[days] = pnl
        # random entries on the holdout, same index/side/window/strike/exit, one per day (all days with a price)
        a, b = WINDOWS[d["window"]]
        P = z["pnl"][:, a:b, d["dr"], d["strike_k"], d["exit_k"]]
        rnd = np.nanmean(P)
        n = len(pnl)
        t = pnl.mean() / (pnl.std(ddof=1) / np.sqrt(n)) if n > 2 else float("nan")
        r = dict(rule=f"{d['und']} {d['side']} {'+'.join(d['atoms'])} {d['window']} {d['strike']} {d['exit']}",
                 is_t=d["t"], is_n=d["n"], is_mean=d["mean"], ho_n=int(n), ho_mean=float(pnl.mean()) if n else 0.0,
                 ho_total=float(pnl.sum()), ho_win=float((pnl > 0).mean()) if n else 0.0, ho_t=float(t),
                 ho_random_mean=float(rnd), mc_ho=monte_carlo(daily, rng) if n else None, mc_is=d.get("mc_is"))
        res.append(r)
        print(json.dumps(r, default=float))
    tot = sum(r["ho_total"] for r in res)
    out = dict(kind=an["holdout_set_kind"], rules=res, sum_total=tot, n_positive=sum(r["ho_total"] > 0 for r in res))
    json.dump(out, open(os.path.join(OBS, "holdout.json"), "w"), indent=1, default=float)
    print("kind", out["kind"], "sum of holdout totals", round(tot), "positive", out["n_positive"], "/", len(res))


if __name__ == "__main__":
    main()
