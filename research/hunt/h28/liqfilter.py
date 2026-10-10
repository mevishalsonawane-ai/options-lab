"""h28 Part C: calendar flags as a FILTER on the app's Liquidity 15+5 trades (rules unchanged). See PREREG.md.

    python3 -I research/hunt/h28/liqfilter.py            # PRE (< 2025-10-01): choose
    python3 -I research/hunt/h28/liqfilter.py --holdout  # survivors only, once

Trades: h24 trades24.parquet model flat_x1 kappa 0.02 (real spread) for BANKNIFTY / MIDCPNIFTY; h23 trades.parquet
net_0.02 (Roll spread) for NIFTY / FINNIFTY / SENSEX. Variants: 42 flags x {skip, only} x 5 indices.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.overfit import bh  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h28")
HOLD = date(2025, 10, 1)
UNDS = ("BANKNIFTY", "MIDCPNIFTY", "NIFTY", "FINNIFTY", "SENSEX")
BASE = ("und", "day", "open", "high", "low", "close", "opt_era")
B = 2000


def load_trades():
    t24 = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h24/trades24.parquet"))
    t24 = t24[(t24.model == "flat_x1") & (t24.kappa == 0.02) & (t24.lots > 0)]
    t24 = t24[["und", "day", "entry_min", "net", "gross"]]
    t23 = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h23/trades.parquet"))
    t23 = t23[t23.und.isin(["NIFTY", "FINNIFTY", "SENSEX"]) & (t23["lots_0.02"] > 0)]
    t23 = t23.rename(columns={"net_0.02": "net", "gross_0.02": "gross"})[["und", "day", "entry_min", "net", "gross"]]
    t = pd.concat([t24, t23], ignore_index=True)
    t["day"] = pd.to_datetime(t.day).dt.date
    return t


def main(holdout=False):
    fl = pd.read_parquet(os.path.join(OUT, "flags.parquet"))
    fl["day"] = pd.to_datetime(fl.day).dt.date
    fcols = [c for c in fl.columns if c not in BASE]
    tr = load_trades()
    rng = np.random.default_rng(11)
    rows = []
    for und in UNDS:
        t = tr[tr.und == und]
        t = t[(t.day >= HOLD) if holdout else (t.day < HOLD)]
        if t.empty:
            continue
        g = fl[fl.und == und].set_index("day")
        d0, d1 = t.day.min(), t.day.max()
        sess = [d for d in g.index if d0 <= d <= (d1 if holdout else date(2025, 9, 30)) and (not holdout or d >= HOLD)]
        nsess = len(sess)
        dn = t.groupby("day").net.sum()
        tdays = np.array(dn.index)
        tv = dn.values
        yrs = np.array([d.year for d in tdays])
        for f in fcols:
            m = np.array([bool(g[f].get(d, False)) for d in tdays])
            k = int(m.sum())
            base = tv.sum() / nsess
            r = dict(und=und, flag=f, sessions=nsess, trade_days=len(tdays), flag_trade_days=k, base_rs_day=base,
                     flag_net=float(tv[m].sum()), mean_flag=float(tv[m].mean()) if k else np.nan,
                     mean_rest=float(tv[~m].mean()) if k < len(tv) else np.nan)
            if k >= 5 and k <= len(tv) - 5:
                obs = tv[m].mean() - tv[~m].mean()
                perm = np.empty(B)
                for b in range(B):
                    mm = np.zeros(len(tv), bool)
                    mm[rng.choice(len(tv), k, replace=False)] = True
                    perm[b] = tv[mm].mean() - tv[~mm].mean()
                r["p_skip"] = (1 + (perm <= obs).sum()) / (B + 1)   # flag days worse than the rest
                r["p_only"] = (1 + (perm >= obs).sum()) / (B + 1)   # flag days better
            else:
                r["p_skip"] = r["p_only"] = 1.0
            r["skip_gain_rs_day"] = -tv[m].sum() / nsess
            r["only_rs_day"] = tv[m].sum() / nsess
            yr_skip = pd.Series(-tv[m], index=yrs[m]).groupby(level=0).sum()
            ys = sorted(set(yrs))
            r["skip_years_pos"] = int(sum(yr_skip.get(y, 0.0) > 0 for y in ys))
            yr_only = pd.Series(tv[m], index=yrs[m]).groupby(level=0).sum() - pd.Series(tv, index=yrs).groupby(level=0).sum()
            r["only_years_pos"] = int(sum(yr_only.get(y, 0.0) > 0 for y in ys))
            r["years"] = len(ys)
            rows.append(r)
    df = pd.DataFrame(rows)
    if not holdout:
        p = np.r_[df.p_skip.values, df.p_only.values]
        q = bh(p)
        df["q_skip"], df["q_only"] = q[:len(df)], q[len(df):]
        need = np.where(df.und == "MIDCPNIFTY", 3, 3)
        df["surv_skip"] = (df.q_skip < 0.10) & (df.skip_gain_rs_day > 20) & (df.skip_years_pos >= need)
        df["surv_only"] = (df.q_only < 0.10) & (df.only_rs_day - df.base_rs_day > 20) & (df.only_years_pos >= need)
        df.to_csv(os.path.join(OUT, "liqfilter_pre.csv"), index=False)
        pd.set_option("display.width", 250)
        print("variants:", 2 * len(df), "| min q skip", df.q_skip.min().round(3), "| min q only", df.q_only.min().round(3))
        print("survivors skip:", df[df.surv_skip][["und", "flag"]].values.tolist(),
              "only:", df[df.surv_only][["und", "flag"]].values.tolist())
        cols = ["und", "flag", "flag_trade_days", "base_rs_day", "mean_flag", "mean_rest", "skip_gain_rs_day", "p_skip",
                "q_skip", "skip_years_pos", "p_only", "q_only", "years"]
        print(df.sort_values("p_skip")[cols].head(15).round(3).to_string())
        print(df.sort_values("p_only")[cols].head(15).round(3).to_string())
        surv = df[df.surv_skip | df.surv_only][["und", "flag", "surv_skip", "surv_only"]].to_dict("records")
        with open(os.path.join(OUT, "liqfilter_choice.json"), "w") as f:
            json.dump(surv, f, default=str)
    else:
        ch = json.load(open(os.path.join(OUT, "liqfilter_choice.json")))
        keep = [(c["und"], c["flag"]) for c in ch]
        df = df[[(u, f) in keep for u, f in zip(df.und, df.flag)]]
        df.to_csv(os.path.join(OUT, "liqfilter_holdout.csv"), index=False)
        print(df.round(3).to_string())


if __name__ == "__main__":
    main("--holdout" in sys.argv)
