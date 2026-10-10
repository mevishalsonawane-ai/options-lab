"""h37 stage 3 (pre-holdout only): matched-random tests, BH, gates, walk-forward by family, summaries.

    python3 -I research/hunt/h37/analyze.py       -> <scratch>/hunt/h37/{variants_pre.parquet, passers.csv, wf.csv, top10.csv, summary.log}
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
from obuy.overfit import bh  # noqa: E402
import evaluate as EV  # noqa: E402

PRE_YEARS = [2020, 2021, 2022, 2023, 2024, 2025]
WF_YEARS = [2022, 2023, 2024, 2025]


def holm(p):
    p = np.asarray(p, float)
    o = np.argsort(p)
    m = len(p)
    adj = np.empty(m)
    run = 0.0
    for i, j in enumerate(o):
        run = max(run, (m - i) * p[j])
        adj[j] = min(run, 1.0)
    return adj


def clustered_p(nd, dsum, dss):
    nd = np.asarray(nd, float)
    with np.errstate(all="ignore"):
        mu = dsum / nd
        var = (dss - nd * mu ** 2) / (nd - 1)
        t = mu / np.sqrt(var / nd)
    t = np.where((nd >= 2) & (var > 0), t, 0.0)
    return t, norm.sf(t)


def load():
    V = pd.read_parquet(os.path.join(EV.OUT, "pre.parquet"))
    fx = os.path.join(EV.OUT, "pre_fx.parquet")
    if os.path.exists(fx):
        V = pd.concat([V, pd.read_parquet(fx)], ignore_index=True)
    return V


def main():
    V = load()
    out = []
    log = open(os.path.join(EV.OUT, "summary.log"), "w")

    def P(*a):
        print(*a, flush=True)
        print(*a, file=log)

    V["mean"] = V.net / V.n.clip(lower=1)
    with np.errstate(all="ignore"):
        sd = np.sqrt((V.ss - V.n * V["mean"] ** 2) / (V.n - 1))
        V["t_net"] = np.where(V.n >= 2, V["mean"] / (sd / np.sqrt(V.n)), 0.0)
    V["t_rand"], p = clustered_p(V.nd, V.dsum, V.dss)
    V["p"] = np.where(V.net > 0, p, 1.0)
    V["q"] = bh(V.p.values)
    yrs = np.zeros(len(V), int)
    pos = np.zeros(len(V), int)
    for y in PRE_YEARS:
        has = V[f"n_{y}"] > 0
        yrs += has
        pos += has & (V[f"net_{y}"] > 0)
    V["yrs"], V["yrs_pos"] = yrs, pos
    V["gate"] = (V.stress > 0) & (V.q < 0.05) & (V.yrs_pos * 2 > V.yrs) & (V.n >= 100)
    V["vid"] = V.und + "|" + V.sig + "|" + V.tf + "|" + V.par + "|" + V.exit
    V.to_parquet(os.path.join(EV.OUT, "variants_pre.parquet"))
    spa = json.load(open(os.path.join(EV.OUT, "spa.json")))
    P(f"variants {len(V):,}  (with trades: {(V.n > 0).sum():,})  trades {int(V.n.sum()):,}")
    P("SPA/RC:", spa)
    P(f"variant gate passers: {int(V.gate.sum())}; min q {V.q.min():.3g}; q<0.05: {(V.q < 0.05).sum()}; "
      f"net>0: {(V.net > 0).sum():,}; stress>0 & n>=100: {((V.stress > 0) & (V.n >= 100)).sum():,}")
    tot = V[["n", "net", "stress", "gross", "m0", "ties"]].sum()
    P(f"ALL trades pooled (pre): per trade gross {tot.gross / tot.n:.1f}, net {tot.net / tot.n:.1f}, stress "
      f"{tot.stress / tot.n:.1f}, matched random {tot.m0 / tot.n:.1f}, signal minus random {(tot.net - tot.m0) / tot.n:+.1f}; "
      f"ties {int(tot.ties):,} ({tot.ties / tot.n:.3%})")
    pd.set_option("display.width", 220)
    pd.set_option("display.max_rows", 200)
    for by in ("fam", "sig", "und", "tf", "exit"):
        g = V.groupby(by)[["n", "net", "stress", "gross", "m0", "ties"]].sum()
        g["variants"] = V.groupby(by).size()
        g["pos_var"] = V.assign(pp=V.net > 0).groupby(by).pp.mean().round(3)
        for c in ("gross", "net", "stress", "m0"):
            g[c + "/tr"] = (g[c] / g.n).round(1)
        g["edge/tr"] = ((g.net - g.m0) / g.n).round(1)
        g["tie%"] = (100 * g.ties / g.n).round(3)
        g = g[["variants", "n", "gross/tr", "net/tr", "stress/tr", "m0/tr", "edge/tr", "pos_var", "tie%"]]
        g.to_csv(os.path.join(EV.OUT, f"by_{by}.csv"))
        P(f"\n== by {by}\n" + g.to_string())
    # ---- walk-forward per family (= signal name)
    wf = []
    for sig, g in V.groupby("sig"):
        picks = []
        for Y in WF_YEARS:
            tr_y = [y for y in PRE_YEARS if y < Y]
            ntr = g[[f"n_{y}" for y in tr_y]].sum(axis=1)
            nettr = g[[f"net_{y}" for y in tr_y]].sum(axis=1)
            ok = ntr >= 30
            if not ok.any():
                continue
            best = nettr[ok].idxmax()
            r = g.loc[best]
            picks.append(dict(sig=sig, year=Y, vid=r.vid, train_net=nettr[best], n=r[f"n_{Y}"], net=r[f"net_{Y}"],
                              stress=r[f"stress_{Y}"], m0=r[f"m0_{Y}"], nd=r[f"nd_{Y}"], dsum=r[f"dsum_{Y}"],
                              dss=r[f"dss_{Y}"]))
        if not picks:
            continue
        pk = pd.DataFrame(picks)
        out.append(pk)
        t, p = clustered_p(pk.nd.sum(), pk.dsum.sum(), pk.dss.sum())
        wf.append(dict(sig=sig, fam=g.fam.iloc[0], years=len(pk), yrs_pos=int(((pk.net > 0) & (pk.n > 0)).sum()),
                       n=int(pk.n.sum()), net=pk.net.sum(), stress=pk.stress.sum(), m0=pk.m0.sum(), t=float(t),
                       p=float(p) if pk.net.sum() > 0 else 1.0))
    W = pd.DataFrame(wf)
    W["holm"] = holm(W.p.values)
    W["bh"] = bh(W.p.values)
    W["gate"] = (W.net > 0) & (W.holm < 0.05) & (W.yrs_pos * 2 > W.years)
    W.to_csv(os.path.join(EV.OUT, "wf.csv"), index=False)
    pd.concat(out).to_csv(os.path.join(EV.OUT, "wf_picks.csv"), index=False)
    P("\n== walk-forward by family (test years 2022-2025 Jan-Sep)\n" + W.sort_values("net", ascending=False).round(3).to_string())
    P(f"family gate passers: {int(W.gate.sum())} of {len(W)}")
    # ---- passers and top 10
    pas = V[V.gate].copy()
    pas["why"] = "variant gate"
    fam_pass = W[W.gate].sig.tolist()
    fp = pd.concat(out)
    fp = fp[fp.sig.isin(fam_pass)]
    extra = V[V.vid.isin(fp.vid)].copy()
    extra["why"] = "family gate pick"
    pas = pd.concat([pas, extra]).drop_duplicates("vid")
    pas.to_csv(os.path.join(EV.OUT, "passers.csv"), index=False)
    top = V[V.n >= 100].sort_values("t_net", ascending=False).head(10)
    top.to_csv(os.path.join(EV.OUT, "top10.csv"), index=False)
    cols = ["vid", "n", "gross", "net", "stress", "m0", "t_net", "t_rand", "p", "q", "yrs_pos", "yrs"]
    P("\n== passers\n" + pas[cols + ["why"]].round(3).to_string())
    P("\n== top 10 by pre-holdout t of net per trade (n >= 100)\n" + top[cols].round(3).to_string())
    P("\n== best 15 by matched-random clustered t (net > 0, n >= 100)\n" +
      V[(V.n >= 100) & (V.net > 0)].sort_values("t_rand", ascending=False).head(15)[cols].round(4).to_string())
    log.close()


if __name__ == "__main__":
    main()
