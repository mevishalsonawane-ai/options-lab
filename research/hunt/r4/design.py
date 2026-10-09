"""R4 trading variants. python3 -I design.py [design|holdout]
design  -> scratchpad/hunt/r4/{design_variants.csv, design_daily_<U>.npz, design_trades_<U>.parquet, frozen.json}
holdout -> run ONCE on frozen.json picks -> holdout_picks.csv (marker file blocks a second run)
"""
from __future__ import annotations

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib4 as L4  # noqa: E402
import signals4 as S4  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

L = L4.L


def run_period(u, period, only=None, seed=11):
    P = L.load_index(u)
    if period == "design":
        mask = P["ok"] & ~P["hold"]
    else:
        mask = P["ok"] & P["hold"] & P["inrange"]
    days_ok = np.nonzero(mask)[0]
    t0 = time.time()
    req = S4.build_all(P, days_ok)
    if only is not None:
        req = req[req["var"].isin(only)].reset_index(drop=True)
    print(u, period, "requests", len(req), "variants", req["var"].nunique(), f"{time.time() - t0:.0f}s", flush=True)
    real = L4.run(P, req)
    real = L4.nonoverlap(real)
    tw = L4.twins(real, seed)
    rnd = L4.run(P, tw)
    print(u, period, "kept", len(real), "twins", len(rnd), f"{time.time() - t0:.0f}s", flush=True)
    df, daily = L4.summarize(real, rnd, P, mask)
    df.insert(0, "und", u)
    L.D.market().release()
    return P, mask, real, rnd, df, daily


def design():
    allrows = []
    for u in L4.UNDS:
        P, mask, real, rnd, df, daily = run_period(u, "design", seed=hash(u) % 1000)
        rc, tst = L4.reality_check(daily)
        df["rc_p"] = df["var"].map(rc)
        df["t_daily"] = df["var"].map(tst)
        allrows.append(df)
        cols = ["var", "di", "s", "side", "x", "sp", "tp", "E", "X", "stop", "ent", "ex", "gross", "net"]
        real[cols].to_parquet(os.path.join(L4.OUT, f"design_trades_{u}.parquet"))
        rnd[["var", "rid", "net"]].astype({"net": "float32"}).to_parquet(os.path.join(L4.OUT, f"design_rand_{u}.parquet"))
        np.savez_compressed(os.path.join(L4.OUT, f"design_daily_{u}.npz"), vars=np.array(list(daily)),
                            M=np.array([daily[k] for k in daily], np.float32))
    res = pd.concat(allrows, ignore_index=True)
    res["q_bh"] = L.bh(res.p.values)
    res["q_bh_rand"] = L.bh(res.p_rand.values)
    res = res.sort_values(["und", "fam", "t_daily"], ascending=[True, True, False])
    res.to_csv(os.path.join(L4.OUT, "design_variants.csv"), index=False)
    picks = res[res.trades >= 30].groupby(["und", "fam"]).head(1)
    frozen = {"written": time.strftime("%Y-%m-%d %H:%M:%S"), "n_variants": int(len(res)),
              "picks": picks[["und", "fam", "var"]].to_dict("records")}
    with open(os.path.join(L4.OUT, "frozen.json"), "w") as f:
        json.dump(frozen, f, indent=1)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 500)
    print(picks[["und", "var", "trades", "rs_day", "rs_trade", "win", "t", "q_bh", "rand_trade", "p_rand", "maxdd",
                 "rc_p", "yrs_pos"]].round(3).to_string())
    print("variants", len(res), "positive rs_day", int((res.rs_day > 0).sum()), "min q", res.q_bh.min(),
          "min q_rand", res.q_bh_rand.min(), "min rc_p", res.rc_p.min())


def holdout():
    marker = os.path.join(L4.OUT, "holdout_opened.txt")
    if os.path.exists(marker):
        sys.exit("holdout already opened once: " + open(marker).read())
    with open(marker, "w") as f:
        f.write(pd.Timestamp.now().isoformat())
    fr = json.load(open(os.path.join(L4.OUT, "frozen.json")))
    picks = pd.DataFrame(fr["picks"])
    out, months = [], []
    for u in L4.UNDS:
        vs = set(picks[picks.und == u]["var"])
        P, mask, real, rnd, df, daily = run_period(u, "holdout", only=vs, seed=99)
        out.append(df)
        real.to_parquet(os.path.join(L4.OUT, f"holdout_trades_{u}.parquet"))
        mon = pd.Series([d.strftime("%Y-%m") for d in P["days"]])[mask].values
        for var, dser in daily.items():
            s = pd.Series(dser).groupby(mon).sum()
            months.append(dict(und=u, var=var, green_months=int((s > 0).sum()), months=len(s),
                               worst_month=float(s.min())))
    res = pd.concat(out, ignore_index=True).merge(pd.DataFrame(months), on=["und", "var"], how="left")
    res.to_csv(os.path.join(L4.OUT, "holdout_picks.csv"), index=False)
    pd.set_option("display.width", 250)
    print(res[["und", "var", "trades", "rs_day", "rs_trade", "win", "rand_trade", "p_rand", "maxdd", "green_months",
               "months"]].round(3).to_string())


if __name__ == "__main__":
    {"design": design, "holdout": holdout}[sys.argv[1] if len(sys.argv) > 1 else "design"]()
