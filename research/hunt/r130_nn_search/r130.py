"""R130 (PREREG.md): 84 S5-like rules from R123's network N1 (its out-of-sample forecasts), on the Drive lake.

    python research/hunt/r130_nn_search/r130.py      # -> results/results.json (+ trades in $R130_OUT)

Rule = window x side x EV threshold x exit. The best allowed, fillable cell-side by the network's EV at each decision row of the
window, taken when EV >= the threshold; one open trade per index, 5 a day; exits x0 (the cell's own, R123) or x1 (R128's lock).
"""
from __future__ import annotations

import json
import os
import sys
from itertools import product
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r128_missing"))
import r128 as T8  # noqa: E402  (puts r123 / r124 on the path)
import r123cfg as K  # noqa: E402
import r123data as D3  # noqa: E402
import r123rules as R3  # noqa: E402

NJ = 16
OUT = Path(os.environ.get("R130_OUT", K.CACHE_ROOT / "r130")).expanduser()
RESULTS = HERE / "results"
R123_OUT = T8.R123_OUT
WINDOWS = {"W1": (K.row(9, 19), K.row(9, 29)), "W2": (K.row(9, 34), K.row(9, 59)), "W3": (K.row(10, 4), K.row(10, 59)),
           "W4": (K.row(11, 4), K.row(11, 59)), "W5": (K.row(12, 4), K.row(12, 59)), "W6": (K.row(13, 4), K.row(13, 59)),
           "W7": (K.row(14, 4), K.row(14, 54))}
SIDES = {"any": (0, 1), "put": (1,), "call": (0,)}
THRESH = (300.0, 600.0)
EXITS = ("x0", "x1")
RULES = [f"{w}-{s}-{int(t)}-{x}" for w, s, t, x in product(WINDOWS, SIDES, THRESH, EXITS)]
TWINS_PER = 100
POOL = 1000
SEED = 130
FOLDS = dict(K.FOLDS)


def parse(rule: str):
    w, s, t, x = rule.split("-")
    return WINDOWS[w], SIDES[s], float(t), x


def candidates(F: pd.DataFrame, rule: str) -> pd.DataFrame:
    """Each row of the window: the best allowed fillable cell-side by EV, kept when EV >= the threshold (r123rules.candidates)."""
    (a, b), sides, thr, _ = parse(rule)
    X = F[(F.row >= a) & (F.row <= b)]
    if X.empty:
        return pd.DataFrame(columns=["und", "day", "row", "j", "score"])
    ev = X[[f"ev_{j}" for j in range(NJ)]].values.copy()
    cls = X[[f"cls_{j}" for j in range(NJ)]].values
    allowed = np.array([j % 2 in sides for j in range(NJ)])
    ev[(cls < 0) | ~allowed[None, :]] = -np.inf
    j = ev.argmax(1)
    sc = ev[np.arange(len(X)), j]
    ok = sc >= thr
    C = X.loc[ok, ["und", "day", "row"]].copy()
    C["j"], C["score"] = j[ok], sc[ok]
    return C


def x0_cols(F: pd.DataFrame) -> dict:
    """{(und, day, row, j): (net, netx2, exit row)} from R123's own simulated outcomes."""
    out = {}
    net = F[[f"net_{j}" for j in range(NJ)]].values
    nx2 = F[[f"netx2_{j}" for j in range(NJ)]].values
    ex = F[[f"exit_{j}" for j in range(NJ)]].values
    for i, (u, d, r) in enumerate(zip(F.und.values, F.day.values, F.row.values)):
        for j in range(NJ):
            if np.isfinite(net[i, j]):
                out[(u, d, int(r), j)] = (float(net[i, j]), float(nx2[i, j]), int(ex[i, j]))
    return out


def sim_x1(args):
    """R128's trailing exit for each (day, row, j) of one index: {(und, day, row, j): (net, netx2, exit row)}."""
    und, keys = args
    g = D3.load_grid(und, K.HOLDOUT[1])
    LO = D3.LakeOptions(und, K.HOLDOUT[1])
    exp = LO.expiry_days()
    lot, step = K.LOT[und], K.STEP[und]
    pos = g.pos()
    out = {}
    by_day = {}
    for (d, r, j) in keys:
        by_day.setdefault(d, []).append((r, j))
    for d in sorted(by_day):
        i = pos.get(d)
        if i is None:
            continue
        opts = LO.day(d, g.c[i])
        if opts is None:
            continue
        last = K.EXIT_LAST_EXPIRY if d in exp else K.EXIT_LAST
        for r, j in by_day[d]:
            x, y, _ = K.CELLS[j // 2]
            c = D3._last(g.c[i][:r + 1])
            if not np.isfinite(c):
                continue
            a = opts.contracts.get(("C" if j % 2 == 0 else "P", D3.atm(c, step)))
            res = T8.convex_exit(a, r, last, lot, d, und, x, y, "x1") if a is not None else None
            if res is not None:
                out[(und, d, r, j)] = (float(res["net"]), float(res["netx2"]), int(res["exit"]))
    return out


def trades_of(F: pd.DataFrame, rule: str, outcomes: dict) -> pd.DataFrame:
    """The rule's candidates with their outcomes, through R123's portfolio (one open trade per index, 5 a day)."""
    C = candidates(F, rule)
    if C.empty:
        return C.assign(net=[], netx2=[], exit=[])
    o = [outcomes.get((u, d, int(r), int(j))) for u, d, r, j in zip(C.und, C.day, C.row, C.j)]
    keep = [x is not None for x in o]
    C = C[keep].copy()
    o = [x for x in o if x is not None]
    C["net"], C["netx2"], C["exit"] = [x[0] for x in o], [x[1] for x in o], [x[2] for x in o]
    return R3.portfolio(C)


def twin_p(F: pd.DataFrame, T: pd.DataFrame, rule: str, outcomes: dict, rng) -> tuple[float, float]:
    """Random-row twins: 100 random decision rows of the same window, index and day with the same cell-side and exit."""
    if T.empty:
        return 1.0, 0.0
    (a, b), _, _, _ = parse(rule)
    rows_by = F[(F.row >= a) & (F.row <= b)].groupby(["und", "day"]).row.apply(lambda s: s.values).to_dict()
    sums = np.zeros(POOL)
    for u, d, j in zip(T.und, T.day, T.j):
        rows = rows_by.get((u, d), np.array([], int))
        vals = np.array([outcomes[(u, d, int(r), int(j))][0] for r in rows if (u, d, int(r), int(j)) in outcomes])
        if not len(vals):
            continue
        pool = rng.choice(vals, TWINS_PER)
        sums += rng.choice(pool, POOL)
    tot = float(T.net.sum())
    return float((1 + (sums >= tot).sum()) / (1 + POOL)), float(sums.mean())


def summary(T: pd.DataFrame) -> dict:
    if T.empty:
        return dict(n=0)
    net = T.net.values
    w = net > 0
    eq = np.cumsum(T.sort_values(["day", "row"]).net.values)
    mo = T.assign(mo=[f"{d.year}-{d.month:02d}" for d in T.day]).groupby("mo").net.sum()
    yr = T.assign(y=[d.year for d in T.day]).groupby("y").net.agg(["count", "sum"])
    days = T.groupby("day").net.sum().sort_values(ascending=False)
    tot = float(net.sum())
    best_year = int(yr["sum"].idxmax())
    return dict(n=int(len(T)), win=float(w.mean()), avg_net=float(net.mean()), avg_net_x2=float(T.netx2.mean()),
                avg_win=float(net[w].mean()) if w.any() else 0.0, avg_loss=float(net[~w].mean()) if (~w).any() else 0.0,
                total=tot, max_dd=float(min((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min(), 0.0)),
                worst_month=float(mo.min()), puts=float((T.j % 2 == 1).mean()),
                by_year={int(y): [int(r["count"]), float(r["sum"])] for y, r in yr.iterrows()},
                top5_share=float(days.head(5).sum() / tot) if tot > 0 else None,
                busiest_year_share=float(yr["count"].max() / len(T)),
                without_best_year=float(tot - yr.loc[best_year, "sum"]))


def load():
    W = pd.read_parquet(R123_OUT / "wf_rows_preds_N1.parquet")
    H = pd.read_parquet(R123_OUT / "holdout_rows_preds_N1.parquet")
    for X in (W, H):
        X["day"] = pd.to_datetime(X.day).dt.date
    W["fold"] = [K.fold_of(d) for d in W.day]
    return W, H


def run():
    from multiprocessing import Pool
    W, H = load()
    outcomes = {**x0_cols(W), **x0_cols(H)}
    x0 = dict(outcomes)
    # every (und, day, row, j) an x1 rule or its twins needs: all window rows of a candidate's day with its cell-side
    need = {}
    for X in (W, H):
        for rule in RULES:
            if not rule.endswith("x1"):
                continue
            C = candidates(X, rule)
            (a, b), _, _, _ = parse(rule)
            for u, d, j in set(zip(C.und, C.day, C.j)):
                for r in range(a, b + 1, 5):
                    need.setdefault(u, set()).add((d, r, int(j)))
    print({u: len(v) for u, v in need.items()}, flush=True)
    with Pool(len(K.UNDS)) as pool:
        parts = pool.map(sim_x1, [(u, sorted(v)) for u, v in need.items()])
    x1 = {k: v for p in parts for k, v in p.items()}
    rng = np.random.default_rng(SEED)
    res, ps = {}, []
    OUT.mkdir(parents=True, exist_ok=True)
    all_trades = []
    for rule in RULES:
        oc = x1 if rule.endswith("x1") else x0
        Tw = trades_of(W, rule, oc)
        Th = trades_of(H, rule, oc)
        p, tw_mean = twin_p(W, Tw, rule, oc, rng)
        folds = {f: float(Tw.net[[K.fold_of(d) == f for d in Tw.day]].sum()) if len(Tw) else 0.0 for f in FOLDS}
        res[rule] = dict(wf=summary(Tw), holdout=summary(Th), p_twins=p, twin_mean=tw_mean, folds=folds,
                         folds_positive=int(sum(v > 0 for v in folds.values())))
        ps.append(p)
        all_trades += [Tw.assign(rule=rule, part="wf"), Th.assign(rule=rule, part="holdout")]
    q = R3.bh(ps)
    for rule, qq in zip(RULES, q):
        r = res[rule]
        r["q"] = qq
        w, h = r["wf"], r["holdout"]
        r["gates_wf"] = dict(n=w.get("n", 0) >= 300, net=w.get("avg_net", -1) > 0 and w.get("avg_net_x2", -1) > 0,
                             folds=r["folds_positive"] >= 3, q=qq <= 0.10)
        r["passes_wf"] = all(r["gates_wf"].values())
        r["gates_ho"] = dict(n=h.get("n", 0) >= 100, net=h.get("avg_net", -1) > 0 and h.get("avg_net_x2", -1) > 0,
                             account=h.get("max_dd", -1e9) > -40_000 and h.get("worst_month", -1e9) > -13_000)
        r["passes"] = r["passes_wf"] and all(r["gates_ho"].values())
    pd.concat(all_trades, ignore_index=True).to_parquet(OUT / "trades.parquet")
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "results.json").write_text(json.dumps(res, indent=1, default=str))
    top = sorted(RULES, key=lambda r: -(res[r]["wf"].get("avg_net", -1e9) if res[r]["wf"].get("n", 0) >= 100 else -1e9))[:15]
    for r in top:
        w, h = res[r]["wf"], res[r]["holdout"]
        print(r, "wf", w.get("n"), round(w.get("avg_net", 0)), f"q {res[r]['q']:.3f}", "folds", res[r]["folds_positive"],
              "| ho", h.get("n"), round(h.get("avg_net", 0) or 0), "passes_wf", res[r]["passes_wf"], "passes", res[r]["passes"])
    print("passing wf:", [r for r in RULES if res[r]["passes_wf"]], "passing all:", [r for r in RULES if res[r]["passes"]])


if __name__ == "__main__":
    run()
