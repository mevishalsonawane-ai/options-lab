"""h38: combine the pre-holdout Lab runs (BASIS, DAILY): BH over all variants, union White RC / SPA, gross / net /
1.5x-stress per variant, time-of-day matched random p, family walk-forward, survivors (PREREG rule), holdout picks.
Also: the Part B summary and the holdout table with sizing.

    OBUY_CACHE=<scratch>/hunt/h38/cache python3 -I research/hunt/h38/post.py pre
    OBUY_CACHE=<scratch>/hunt/h38/cache python3 -I research/hunt/h38/post.py partB
    OBUY_CACHE=<scratch>/hunt/h38/cache python3 -I research/hunt/h38/post.py hold <run name>
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

RUNS = ["BASIS", "DAILY"]
HS = {"BANKNIFTY": .0016, "NIFTY": .0016, "MIDCPNIFTY": .0021, "FINNIFTY": .0042, "SENSEX": .0016}
HOLD = pd.Timestamp("2025-10-01")
OUTD = os.path.join(C.SCRATCH, "hunt", "h38")
pd.set_option("display.width", 260)
pd.set_option("display.max_rows", 400)


def ndays(u, lo, hi):
    d = pd.to_datetime(pd.Series(market().index(u).days))
    return int(((d >= lo) & (d < hi)).sum())


def enrich(t):
    sp = t.und.map(HS).values * (t.entry.values + t.exit.values) * t.qty.values
    t["gross_p"] = t.net + t.charges + sp
    t["net15"] = t.net - 0.5 * sp
    return t


def load(names):
    V, F, T = [], [], []
    for r in names:
        d = os.path.join(C.CACHE, "runs", r)
        v = pd.read_csv(os.path.join(d, "variants.csv")).assign(run=r)
        tod = os.path.join(OUTD, f"tod_{r}.csv")
        if os.path.exists(tod):
            v = v.merge(pd.read_csv(tod)[["vid", "p_tod"]], on="vid", how="left")
        V.append(v)
        F.append(pd.read_csv(os.path.join(d, "families.csv")).assign(run=r))
        T.append(pd.read_csv(os.path.join(d, "trades.csv.gz"), parse_dates=["day"]))
    return pd.concat(V, ignore_index=True), pd.concat(F, ignore_index=True), enrich(pd.concat(T, ignore_index=True))


def add_days(V, T, hi):
    V["und"] = V.strategy.str.rsplit("_", n=1).str[1]
    first = {u: T[T.und == u].day.min() for u in T.und.unique()}
    nd = {u: ndays(u, first[u], hi) for u in first}
    g = T.groupby("vid").agg(gross_p=("gross_p", "sum"), net15=("net15", "sum"))
    V = V.join(g, on="vid")
    V["nd"] = V.und.map(nd)
    for c in ("gross_p", "net", "net15"):
        V[c + "_day"] = V[c] / V.nd
    V["trig"] = V.strategy.str.rsplit("_", n=1).str[0]
    return V


def pre():
    V, F, T = load([f"h38_pre_{r}" for r in RUNS])
    V["bh_all"] = OF.bh(V.p_rand.fillna(1.0).values)
    V = add_days(V, T, HOLD)
    days = sorted(T.day.unique())
    X = T.groupby(["day", "vid"]).net.sum().unstack().reindex(index=days, columns=V.vid).fillna(0.0).values
    spa = OF.spa(X, B=1000)
    print(f"variants {len(V)}; union White RC p = {spa['rc_p']:.3f}, Hansen SPA p = {spa['spa_p']:.3f}")
    print(f"variants with net > 0 (real spread): {(V.net > 0).sum()}; gross > 0: {(V.gross_p > 0).sum()}; "
          f"p_rand < .05: {(V.p_rand < .05).sum()}; p_tod < .05: {(V.p_tod < .05).sum()}; min BH q: {V.bh_all.min():.3f}")
    print("\n=== families (walk-forward OOS, 1 lot, real spread + app charges) ===")
    cols = ["strategy", "n_variants", "wf_trades", "wf_net", "wf_years_pos", "wf_years", "p_rand", "p_rand_holm",
            "wf_dd", "best_variant", "best_net", "pbo", "promoted"]
    F = F.sort_values("wf_net", ascending=False)
    print(F[[c for c in cols if c in F]].round(3).to_string(index=False))
    print("\n=== top 25 variants by pre-holdout net (in-sample, selection-biased) ===")
    vc = ["vid", "exits", "trades", "gross_p_day", "net_day", "net15_day", "pf", "win", "max_dd", "years_pos", "years",
          "p_rand", "p_tod", "bh_all"]
    print(V.sort_values("net", ascending=False)[[c for c in vc if c in V]].head(25).round(3).to_string(index=False))
    print("\n=== by trigger: share of variants net > 0, mean / best net Rs/day, mean gross ===")
    print(V.groupby("trig").agg(n=("vid", "size"), pos=("net", lambda x: (x > 0).mean()), mean_day=("net_day", "mean"),
                                best_day=("net_day", "max"), mean_gross_day=("gross_p_day", "mean"),
                                gross_pos=("gross_p", lambda x: (x > 0).mean()), min_p=("p_rand", "min")).round(3).to_string())
    print("\n=== by exit: mean net Rs/day ===")
    print(V.groupby("exits").agg(n=("vid", "size"), mean_day=("net_day", "mean"), pos=("net", lambda x: (x > 0).mean())).round(2).to_string())
    surv = []
    for _, f in F.iterrows():
        if not bool(f.get("promoted", False)):
            continue
        last = f.strategy + "|" + str(f.picks).split(";")[-1].split(":", 1)[1]
        v = V.set_index("vid").loc[last]
        if v.bh_all < 0.10 and v.net15 > 0:
            surv.append(last)
    print("\nSURVIVORS:", surv or "none")
    if surv:
        picks, label = surv, "survivors"
    else:
        picks = []
        F["trig"] = F.strategy.str.rsplit("_", n=1).str[0].str.replace("rev$", "", regex=True)
        for tg, f in F[F.wf_trades.notna() & (F.wf_trades > 0)].groupby("trig"):
            fr = f.sort_values("wf_net", ascending=False).iloc[0]
            picks.append(fr.strategy + "|" + str(fr.picks).split(";")[-1].split(":", 1)[1])
        label = "information only (no survivors)"
    pk = []
    for p in picks:
        s, si, _, xi = p.split("|")
        pk.append(dict(strategy=s, s=int(si[1:]), x=int(xi[1:])))
    print(f"holdout picks ({label}):", json.dumps(pk))
    json.dump(dict(label=label, picks=pk), open(os.path.join(OUTD, "hold_picks.json"), "w"))
    V.to_csv(os.path.join(OUTD, "variants_pre.csv"), index=False)
    F.to_csv(os.path.join(OUTD, "families_pre.csv"), index=False)


def partB():
    V, F, T = load(["h38_partB"])
    V["bh_all"] = OF.bh(V.p_rand.fillna(1.0).values)
    V = add_days(V, T, pd.Timestamp("2026-10-07"))
    lo = T.day.min()
    for u in V.und.unique():
        V.loc[V.und == u, "nd"] = ndays(u, lo, pd.Timestamp("2026-10-07"))
    for c in ("gross_p", "net", "net15"):
        V[c + "_day"] = V[c] / V.nd
    days = sorted(T.day.unique())
    X = T.groupby(["day", "vid"]).net.sum().unstack().reindex(index=days, columns=V.vid).fillna(0.0).values
    spa = OF.spa(X, B=1000)
    print(f"Part B variants {len(V)} (window {lo.date()} .. 2026-10-06, ~{int(V.nd.max())} days); union RC p = "
          f"{spa['rc_p']:.3f}, SPA p = {spa['spa_p']:.3f}; net > 0: {(V.net > 0).sum()}; gross > 0: {(V.gross_p > 0).sum()}; "
          f"p_rand < .05: {(V.p_rand < .05).sum()}; min BH q {V.bh_all.min():.3f}")
    print(V.groupby("trig").agg(n=("vid", "size"), trades_mean=("trades", "mean"), pos=("net", lambda x: (x > 0).mean()),
                                mean_day=("net_day", "mean"), best_day=("net_day", "max"), mean_gross_day=("gross_p_day", "mean"),
                                min_p=("p_rand", "min")).round(3).to_string())
    vc = ["vid", "exits", "trades", "gross_p_day", "net_day", "net15_day", "win", "max_dd", "p_rand", "p_tod", "bh_all"]
    print(V.sort_values("net", ascending=False)[[c for c in vc if c in V]].head(20).round(3).to_string(index=False))
    V.to_csv(os.path.join(OUTD, "variants_partB.csv"), index=False)


def hold(name):
    d = os.path.join(C.CACHE, "runs", name)
    V = pd.read_csv(os.path.join(d, "variants.csv"))
    tod = os.path.join(OUTD, f"tod_{name}.csv")
    ptod = pd.read_csv(tod).set_index("vid").p_tod if os.path.exists(tod) else pd.Series(dtype=float)
    T = enrich(pd.read_csv(os.path.join(d, "trades.csv.gz"), parse_dates=["day"]))
    rows = []
    last = T.day.max()
    rng = np.random.default_rng(5)
    for vid, t in T.groupby("vid"):
        u = t.und.iloc[0]
        cal = pd.to_datetime(pd.Series(market().index(u).days)).loc[lambda s: (s >= HOLD) & (s <= last)].values
        n = len(cal)
        dl = t.groupby("day").net.sum().reindex(cal, fill_value=0.0)
        eq = dl.cumsum().values
        dd = float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min())
        mon = t.groupby(t.day.dt.to_period("M")).net.sum()
        # bootstrap P(losing month): resample 21-day months from the daily P&L
        boot = rng.choice(dl.values, size=(5000, 21)).sum(axis=1)
        prem = float((t.entry * t.qty).mean())
        net_day = t.net.sum() / n
        rows.append(dict(vid=vid, trades=len(t), days=n, gross_day=t.gross_p.sum() / n, net_day=net_day,
                         net15_day=t.net15.sum() / n, win=(t.net > 0).mean(), max_dd=dd, worst_day=dl.min(),
                         worst_month=mon.min(), months_neg=f"{(mon < 0).sum()}/{len(mon)}", p_lose_month=(boot < 0).mean(),
                         prem_per_lot=prem, lots_1L=int(100000 // prem) if prem > 0 else 0,
                         lots_for_5k=(5000 / net_day) if net_day > 0 else np.nan,
                         p_rand=float(V.set_index("vid").loc[vid, "p_rand"]), p_tod=float(ptod.get(vid, np.nan))))
    R = pd.DataFrame(rows)
    print(R.round(3).to_string(index=False))
    R.to_csv(os.path.join(OUTD, f"{name}.csv"), index=False)


if __name__ == "__main__":
    {"pre": pre, "partB": partB}.get(sys.argv[1], lambda: hold(sys.argv[2]))()
