"""h25 stage 3 (pre-holdout only): random-baseline p, BH, gates, walk-forward per family, family summaries, top 20.

    python3 -I research/hunt/h25/analyze.py

Reads pre.parquet and spa.json; writes <scratch>/hunt/h25/: variants_pre.parquet, wf.csv, fam_summary.csv,
sig_summary.csv, top20.csv, passers.csv. Never reads hold_sealed.parquet.
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.stats import norm  # noqa: E402
from obuy.overfit import bh, holm  # noqa: E402

OUT = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h25"
YEARS = [2020, 2021, 2022, 2023, 2024, 2025]
WF_TEST = [2022, 2023, 2024, 2025]
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 50)


def main():
    v = pd.read_parquet(os.path.join(OUT, "pre.parquet"))
    v["mean"] = v.net / v.n.clip(lower=1)
    sd = np.sqrt(np.maximum(v.ss / v.n.clip(lower=1) - v["mean"] ** 2, 0))
    v["t"] = np.where(v.n >= 2, v["mean"] / sd.replace(0, np.nan) * np.sqrt(v.n), 0)
    z = (v.net - v.m0) / np.sqrt(v.v0.replace(0, np.nan))
    v["z_rand"] = z
    v["p"] = np.where((v.net > 0) & (v.n >= 10), norm.sf(z.fillna(-9)), 1.0)
    v["q"] = bh(v.p.values)
    yrs = np.stack([v[f"n_{y}"] > 0 for y in YEARS], axis=1)
    pos = np.stack([v[f"net_{y}"] > 0 for y in YEARS], axis=1)
    v["yrs"] = yrs.sum(axis=1)
    v["yrs_pos"] = (yrs & pos).sum(axis=1)
    v["vgate"] = (v.stress > 0) & (v.q < 0.05) & (v.yrs_pos * 2 > v.yrs) & (v.n >= 100)
    v.to_parquet(os.path.join(OUT, "variants_pre.parquet"))
    spa = json.load(open(os.path.join(OUT, "spa.json")))
    print("variants", len(v), "SPA/RC", spa)
    print("net>0", int((v.net > 0).sum()), "gross>0", int((v.gross > 0).sum()), "stress>0", int((v.stress > 0).sum()),
          "raw p<0.05", int((v.p < 0.05).sum()), "q<0.05", int((v.q < 0.05).sum()), "variant gate", int(v.vgate.sum()))

    # walk-forward per family (signal x orientation): pick the best (und, tf, exit) on all earlier years
    wf_rows, wf_fam = [], []
    for (sig, orient), g in v.groupby(["sig", "orient"]):
        tot = dict(net=0.0, m0=0.0, v0=0.0, n=0, stress=0.0)
        ypos = ytot = 0
        for Y in WF_TEST:
            past = [y for y in YEARS if y < Y]
            pn = g[[f"n_{y}" for y in past]].sum(axis=1)
            pv = g[[f"net_{y}" for y in past]].sum(axis=1)[pn >= 30]
            if pv.empty:
                continue
            i = pv.idxmax()
            r = g.loc[i]
            tot["net"] += r[f"net_{Y}"]
            tot["stress"] += r[f"stress_{Y}"]
            tot["m0"] += r[f"m0_{Y}"]
            tot["v0"] += r[f"v0_{Y}"]
            tot["n"] += int(r[f"n_{Y}"])
            if r[f"n_{Y}"] > 0:
                ytot += 1
                ypos += r[f"net_{Y}"] > 0
            wf_rows.append(dict(sig=sig, orient=orient, year=Y, pick=f"{r.und}/{r.tf}m/{r.exit}", train=pv[i],
                                test=r[f"net_{Y}"], n=int(r[f"n_{Y}"])))
        zf = (tot["net"] - tot["m0"]) / np.sqrt(tot["v0"]) if tot["v0"] > 0 else 0.0
        wf_fam.append(dict(sig=sig, orient=orient, fam=g.fam.iloc[0], wf_net=tot["net"], wf_stress=tot["stress"],
                           wf_n=tot["n"], wf_yrs_pos=ypos, wf_yrs=ytot, wf_vs_rand=tot["net"] - tot["m0"],
                           wf_p=float(norm.sf(zf)) if tot["n"] else 1.0))
    wf = pd.DataFrame(wf_fam)
    wf["wf_holm"] = holm(wf.wf_p.values)
    wf["wf_bh"] = bh(wf.wf_p.values)
    wf["fgate"] = (wf.wf_net > 0) & (wf.wf_holm < 0.05) & (wf.wf_yrs_pos * 2 > wf.wf_yrs)
    wf.to_csv(os.path.join(OUT, "wf.csv"), index=False)
    pd.DataFrame(wf_rows).to_csv(os.path.join(OUT, "wf_picks.csv"), index=False)
    print("families", len(wf), "WF net>0", int((wf.wf_net > 0).sum()), "WF beats random raw p<.05",
          int((wf.wf_p < 0.05).sum()), "Holm<.05", int((wf.wf_holm < 0.05).sum()), "family gate", int(wf.fgate.sum()))
    print(wf.sort_values("wf_net", ascending=False).head(15).to_string())

    # summaries
    def summ(g):
        return pd.Series(dict(
            variants=len(g), trades=int(g.n.sum()), gross_pos=(g.gross > 0).mean(), net_pos=(g.net > 0).mean(),
            stress_pos=(g.stress > 0).mean(), med_gross_tr=(g.gross / g.n.clip(lower=1)).median(),
            med_net_tr=g["mean"].median(), avg_net_tr=g.net.sum() / max(g.n.sum(), 1),
            avg_vs_rand_tr=(g.net.sum() - g.m0.sum()) / max(g.n.sum(), 1),
            raw_p05=int((g.p < 0.05).sum()), q05=int((g.q < 0.05).sum()), gate=int(g.vgate.sum()),
            best_net=g.net.max()))
    fs = v.groupby("fam").apply(summ)
    fs.loc["ALL"] = summ(v)
    for col in ("orient", "exit", "tf", "und"):
        print(v.groupby(col).apply(summ).to_string())
    fs.to_csv(os.path.join(OUT, "fam_summary.csv"))
    print(fs.to_string())
    ss = v.groupby(["fam", "sig", "orient"]).apply(summ).reset_index().merge(wf, on=["sig", "orient", "fam"])
    ss.to_csv(os.path.join(OUT, "sig_summary.csv"), index=False)
    for c in ("orient", "exit", "tf", "und"):
        v.groupby(c).apply(summ).to_csv(os.path.join(OUT, f"by_{c}.csv"))

    top = v[v.n >= 100].sort_values("t", ascending=False).head(20)
    top.to_csv(os.path.join(OUT, "top20.csv"), index=False)
    print(top[["und", "tf", "sig", "orient", "exit", "n", "net", "stress", "gross", "mean", "t", "p", "q", "yrs_pos",
               "yrs", "vgate"]].to_string())
    new = v[(v.n >= 100) & v.exit.str.match(r"P\d+_\d+")].sort_values("t", ascending=False).head(20)
    new.to_csv(os.path.join(OUT, "top20_new.csv"), index=False)
    print("top 20 among the amendment's point exits")
    print(new[["und", "tf", "sig", "orient", "exit", "n", "net", "stress", "gross", "mean", "t", "p", "q", "yrs_pos",
               "yrs", "vgate"]].to_string())
    pas = v[v.vgate]
    pas.to_csv(os.path.join(OUT, "passers.csv"), index=False)
    wf[wf.fgate].to_csv(os.path.join(OUT, "fam_passers.csv"), index=False)


if __name__ == "__main__":
    main()
