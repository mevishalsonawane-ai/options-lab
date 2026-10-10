"""h26: combine the 6 pre-holdout Lab runs: BH over all variants, union White RC / SPA, gross / net / 1.5x-stress per
variant, family gates, survivors (PREREG rule), and the holdout picks.

    OBUY_CACHE=<scratch>/hunt/h26/cache python3 -I research/hunt/h26/post.py pre
    OBUY_CACHE=<scratch>/hunt/h26/cache python3 -I research/hunt/h26/post.py hold <run name>
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.data import market  # noqa: E402

RUNS = ["BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "CONS"]
HS = {"BANKNIFTY": .0016, "NIFTY": .0016, "MIDCPNIFTY": .0021, "FINNIFTY": .0042, "SENSEX": .0016}
HOLD = pd.Timestamp("2025-10-01")
OUTD = os.path.join(C.SCRATCH, "hunt", "h26")
pd.set_option("display.width", 260)
pd.set_option("display.max_rows", 400)


def ndays(u, lo, hi):
    d = pd.to_datetime(pd.Series(market().index(u).days))
    return int(((d >= lo) & (d < hi)).sum())


def enrich(t):
    hs = t.und.map(HS).values
    sp = hs * (t.entry.values + t.exit.values) * t.qty.values
    t["gross_p"] = t.net + t.charges + sp            # approx bar-print P&L (no spread, no charges)
    t["net15"] = t.net - 0.5 * sp
    return t


def pre():
    V, F, T = [], [], []
    for r in RUNS:
        d = os.path.join(C.CACHE, "runs", f"h26_pre_{r}")
        V.append(pd.read_csv(os.path.join(d, "variants.csv")).assign(run=r))
        F.append(pd.read_csv(os.path.join(d, "families.csv")).assign(run=r))
        T.append(pd.read_csv(os.path.join(d, "trades.csv.gz"), parse_dates=["day"]))
    V, F, T = pd.concat(V, ignore_index=True), pd.concat(F, ignore_index=True), enrich(pd.concat(T, ignore_index=True))
    V["bh_all"] = OF.bh(V.p_rand.fillna(1.0).values)
    V["und"] = V.strategy.str.rsplit("_", n=1).str[1]
    first = {u: T[T.und == u].day.min() for u in T.und.unique()}
    nd = {u: ndays(u, first[u], HOLD) for u in first}
    g = T.groupby("vid").agg(gross_p=("gross_p", "sum"), net15=("net15", "sum"))
    V = V.join(g, on="vid")
    V["nd"] = V.und.map(nd)
    for c in ("gross_p", "net", "net15"):
        V[c + "_day"] = V[c] / V.nd
    # union SPA / RC over every variant's daily P&L
    days = sorted(T.day.unique())
    X = T.groupby(["day", "vid"]).net.sum().unstack().reindex(index=days, columns=V.vid).fillna(0.0).values
    spa = OF.spa(X, B=1000)
    print(f"variants {len(V)}; union White RC p = {spa['rc_p']:.3f}, Hansen SPA p = {spa['spa_p']:.3f}")
    print(f"variants with net > 0 (real spread): {(V.net > 0).sum()}; with p_rand < .05: {(V.p_rand < .05).sum()}; "
          f"min BH q (all {len(V)}): {V.bh_all.min():.3f}")
    print("\n=== families (walk-forward OOS, 1 lot, real spread + app charges) ===")
    cols = ["strategy", "n_variants", "wf_trades", "wf_net", "wf_years_pos", "wf_years", "p_rand", "p_rand_holm",
            "wf_dd", "best_variant", "best_net", "pbo", "promoted"]
    F = F.sort_values("wf_net", ascending=False)
    print(F[[c for c in cols if c in F]].round(3).to_string(index=False))
    print("\n=== top 25 variants by pre-holdout net (in-sample, selection-biased) ===")
    vc = ["vid", "exits", "trades", "gross_p_day", "net_day", "net15_day", "pf", "win", "max_dd", "years_pos", "years",
          "p_rand", "bh_all"]
    print(V.sort_values("net", ascending=False)[vc].head(25).round(3).to_string(index=False))
    print("\n=== by trigger family: share of variants with net > 0, mean net Rs/day, best ===")
    V["trig"] = V.strategy.str.rsplit("_", n=1).str[0]
    print(V.groupby("trig").agg(n=("vid", "size"), pos=("net", lambda x: (x > 0).mean()), mean_day=("net_day", "mean"),
                                 best_day=("net_day", "max"), mean_gross_day=("gross_p_day", "mean"),
                                 min_p=("p_rand", "min")).round(3).to_string())
    print("\n=== by exit (all option-trigger variants): mean net Rs/day ===")
    print(V.groupby("exits").agg(n=("vid", "size"), mean_day=("net_day", "mean"), pos=("net", lambda x: (x > 0).mean())).round(2).to_string())
    # survivors: family promoted + its last WF pick has BH q < .10 and net15 > 0
    surv = []
    for _, f in F.iterrows():
        if not bool(f.get("promoted", False)):
            continue
        picks = str(f.picks).split(";")
        last = f.strategy + "|" + picks[-1].split(":", 1)[1]
        v = V.set_index("vid").loc[last]
        if v.bh_all < 0.10 and v.net15 > 0:
            surv.append(last)
    print("\nSURVIVORS:", surv or "none")
    # holdout picks
    if surv:
        picks = surv
        label = "survivors"
    else:
        picks = []
        for r in RUNS:
            f = F[(F.run == r) & F.wf_trades.notna() & (F.wf_trades > 0)].sort_values("wf_net", ascending=False)
            if len(f):
                fr = f.iloc[0]
                last = fr.strategy + "|" + str(fr.picks).split(";")[-1].split(":", 1)[1]
                picks.append(last)
        label = "information only (no survivors)"
    pk = []
    for p in picks:
        s, si, _, xi = p.split("|")
        pk.append(dict(strategy=s, s=int(si[1:]), x=int(xi[1:])))
    print(f"holdout picks ({label}):", json.dumps(pk))
    json.dump(dict(label=label, picks=pk), open(os.path.join(OUTD, "hold_picks.json"), "w"))
    V.to_csv(os.path.join(OUTD, "variants_all.csv"), index=False)
    F.to_csv(os.path.join(OUTD, "families_all.csv"), index=False)


def hold(name):
    d = os.path.join(C.CACHE, "runs", name)
    V = pd.read_csv(os.path.join(d, "variants.csv"))
    T = enrich(pd.read_csv(os.path.join(d, "trades.csv.gz"), parse_dates=["day"]))
    rows = []
    last = T.day.max()
    for vid, t in T.groupby("vid"):
        u = t.und.iloc[0]
        n = ndays(u, HOLD, last + pd.Timedelta(days=1))
        dl = t.groupby("day").net.sum()
        dl = dl.reindex(pd.to_datetime(pd.Series(market().index(u).days)).loc[lambda s: (s >= HOLD) & (s <= last)].values,
                        fill_value=0.0)
        eq = dl.cumsum().values
        dd = float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min())
        mon = t.groupby(t.day.dt.to_period("M")).net.sum()
        rows.append(dict(vid=vid, trades=len(t), days=n, gross_day=t.gross_p.sum() / n, net_day=t.net.sum() / n,
                         net15_day=t.net15.sum() / n, win=(t.net > 0).mean(), max_dd=dd, worst_day=dl.min(),
                         worst_month=mon.min(), months_neg=f"{(mon < 0).sum()}/{len(mon)}",
                         p_rand=float(V.set_index("vid").loc[vid, "p_rand"])))
    R = pd.DataFrame(rows)
    print(R.round(3).to_string(index=False))
    R.to_csv(os.path.join(OUTD, f"{name}.csv"), index=False)


if __name__ == "__main__":
    pre() if sys.argv[1] == "pre" else hold(sys.argv[2])
