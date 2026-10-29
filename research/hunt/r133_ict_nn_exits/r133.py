"""R133: every R131 entry (39) x five exits (E0 = R131's, E1-E4 = R132's), filtered by R123's walk-forward network
(N-EV, R132 Amendment A). PREREG.md. Nothing is redefined: R131's detectors, R132's sim_exit and r132nn's filter.

    python research/hunt/r133_ict_nn_exits/r133.py design         # folds F1-F4 -> results/design.json
    python research/hunt/r133_ict_nn_exits/r133.py holdout_seen   # the seen holdout period -> results/holdout_seen.json
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r132_ict_exits"))
import r132 as Q  # noqa: E402
import r132nn as NN  # noqa: E402

R, K = Q.R, Q.K
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R133_OUT", K.K3.CACHE_ROOT / "r133")).expanduser()
ENTRIES = tuple(R.ALL_VARIANTS)                                # the 39 R131 entries
EXITS = ("E0",) + tuple(Q.EXITS)
DONE_IN_R132 = {f"{e}-E0" for e in Q.ENTRIES}                   # the 10 V1-E0 + N-EV combinations (R132 Amendment A)
VARIANTS = [f"{e}-{x}" for e in ENTRIES for x in EXITS if f"{e}-{x}" not in DONE_IN_R132]
SEED = 133


def simulate(part: str) -> pd.DataFrame:
    """E1-E4 trades of all 39 entries: R132's run_index with its entry list widened and no twins (the gate here is
    the filter's subset test, PREREG section 2). The R132 module is configured, not edited."""
    from multiprocessing import Pool
    Q.ENTRIES = ENTRIES
    Q.TWINS = 0
    Q.VARIANTS = {f"{e}-{x}": (e, x) for e in ENTRIES for x in Q.EXITS}
    R.VARIANTS = dict(R.ALL_VARIANTS)                          # run_index looks entries up here
    with Pool(4) as pool:
        res = pool.map(Q.run_index, [(u, "design" if part == "design" else "holdout") for u in K.UNDS])
    T = pd.DataFrame([r for x in res for r in x["rows"]])
    return T.drop(columns=["twins"]).rename(columns={"variant": "v"})


def e0_trades(part: str) -> pd.DataFrame:
    """R131's own priced trades (exit E0) of the 39 entries."""
    names = ["trades_design.parquet", "trades_design_w.parquet"] if part == "design" else \
        ["trades_holdout.parquet", "trades_holdout_w.parquet"]
    T = pd.concat([pd.read_parquet(R.OUT / f) for f in names], ignore_index=True)
    T["entry_rule"], T["exit_rule"] = T.variant, "E0"
    T["v"] = T.variant + "-E0"
    return T


def evaluate(T: pd.DataFrame, P: pd.DataFrame, part: str, rng) -> dict:
    T = T.copy()
    T["day"] = pd.to_datetime(T.day).dt.date
    if part == "design":
        T = T[[K.fold_of(d) is not None for d in T.day]]
    M = NN.attach(T, P)
    out = {}
    for v in VARIANTS:
        X = M[M.v == v].sort_values(["day", "und", "entry"]).reset_index(drop=True)
        if not len(X):
            out[v] = dict(n=0, p_subsets=1.0)
            continue
        kp = NN.keep_mask(X, "NEV")
        strata = np.array([K.fold_of(d) or "H" for d in X.day])
        S = X[kp]
        s = R.money(S) if len(S) else dict(n=0)
        s["p_subsets"] = NN.subset_p(X.net.values, kp, strata, rng) if kp.any() else 1.0
        s["base"] = R.money(X)
        if part == "design" and len(S):
            fo = [K.fold_of(d) for d in S.day]
            s["by_fold"] = {k: R.money(S[[x == k for x in fo]]) for k in K.FOLDS}
            s["by_und"] = {u: R.money(Y) for u, Y in S.groupby("und")}
            for blk in ("by_fold", "by_und"):
                for x in s[blk].values():
                    for drop in ("exits", "worst_month_name", "months_green", "months", "buys"):
                        x.pop(drop, None)
        e, x = v.rsplit("-", 1)
        s["label"] = f"{R.ALL_VARIANTS[e]['label']} | " + ("R131 exits" if x == "E0" else Q.EXITS[x]["label"]) + \
            " | R123 network EV filter"
        out[v] = s
    return out


def gates(s: dict, q: float) -> dict:
    f = [s.get("by_fold", {}).get(k, {}).get("total", 0.0) > 0 for k in K.FOLDS]
    return dict(n=s.get("n", 0) >= 300, net=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                folds=sum(f) >= 3, q=q <= 0.10,
                account=s.get("max_dd", -1e9) > -40000.0 and s.get("worst_month", -1e9) > -13000.0)


def run(part: str):
    rng = np.random.default_rng(SEED + (0 if part == "design" else 1))
    P = pd.read_parquet(NN.PREDS / ("wf_rows_preds_N1.parquet" if part == "design" else "holdout_rows_preds_N1.parquet"))
    T = pd.concat([simulate(part), e0_trades(part)], ignore_index=True)
    OUT.mkdir(parents=True, exist_ok=True)
    T.to_parquet(OUT / f"trades_{part}.parquet")
    res = evaluate(T, P, part, rng)
    if part == "design":
        qs = R.R3.bh([res[v]["p_subsets"] for v in VARIANTS])
        for v, q in zip(VARIANTS, qs):
            res[v]["q"] = q
            res[v]["gates"] = gates(res[v], q)
            res[v]["passes_design"] = all(res[v]["gates"].values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}.json").write_text(json.dumps(dict(part=part, variants=res), indent=1, default=str))
    good = {v: (s["n"], round(s["avg_net"]), round(s["p_subsets"], 3)) for v, s in res.items()
            if s.get("n") and s["avg_net"] > 0}
    print(len(good), "positive:", good)


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
