"""R30 strategies M1-M4, C1, C2 (PREREG.md), walk-forward over folds F1-F3, the gate, twins and BH.

    python3 research/hunt/r30/models.py run        # -> results/walkforward.json, results/trades.parquet (cache)

Everything a fold learns (models, thresholds, tercile cut points, kept combinations) comes from its training rows only.
"""
from __future__ import annotations

import json
import sys
import time
from datetime import date

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
from sklearn.inspection import permutation_importance

import r30cfg as K
import r30data as RD
import mine as MI                      # R29's BH

LABELS_PREFIX = ("C_", "P_")
META = {"day", "m", "qty", "mv30", "absmv30", "und", "year"}
GBM = dict(max_iter=200, learning_rate=0.05, max_leaf_nodes=31, min_samples_leaf=200, l2_regularization=1.0,
           random_state=K.SEED)
TOP = 0.05
MIN_ROWS, MIN_DAYS, KEEP = 300, 60, 20
TRIPLE_TOP = 30                         # PREREG amendment 1


def feature_cols(T: pd.DataFrame) -> list[str]:
    return [c for c in T.columns if c not in META and not c.startswith(LABELS_PREFIX)
            and T[c].dtype.kind in "fiub"]


def is_ribbon(c: str) -> bool:
    return c.startswith(("rib", "cls"))


# ------------------------------------------------------------------------------------------------ signals
def sig_m1(tr, te, X, label="X1"):
    out = {}
    preds_tr, preds_te = {}, {}
    for s in "CP":
        y = tr[f"{s}_{label}"].values
        ok = np.isfinite(y)
        mdl = HistGradientBoostingRegressor(**GBM).fit(tr.loc[ok, X], y[ok])
        preds_tr[s], preds_te[s] = mdl.predict(tr[X]), mdl.predict(te[X])
        out[s] = mdl
    best_tr = np.maximum(preds_tr["C"], preds_tr["P"])
    thr = max(np.quantile(best_tr, 1 - TOP), 0.0)
    side = np.where(preds_te["C"] >= preds_te["P"], "C", "P")
    best = np.maximum(preds_te["C"], preds_te["P"])
    fire = (best > thr) & (preds_te["C"] != preds_te["P"])
    return np.where(fire, side, ""), out


def sig_m3(tr, te, X):
    ok = np.isfinite(tr.absmv30.values)
    size = HistGradientBoostingRegressor(**GBM).fit(tr.loc[ok, X], tr.absmv30.values[ok])
    ok2 = np.isfinite(tr.mv30.values)
    dirn = HistGradientBoostingClassifier(**GBM).fit(tr.loc[ok2, X], (tr.mv30.values[ok2] > 0).astype(int))
    s_tr, s_te = size.predict(tr[X]), size.predict(te[X])
    p_tr, p_te = dirn.predict_proba(tr[X])[:, 1], dirn.predict_proba(te[X])[:, 1]
    big = s_te >= np.quantile(s_tr, 0.80)
    hi, lo = np.quantile(p_tr, 0.80), np.quantile(p_tr, 0.20)
    side = np.where(big & (p_te >= hi), "C", np.where(big & (p_te <= lo), "P", ""))
    return side, {"size": size, "dir": dirn}


def sig_m4(tr, te, X):
    pr_tr, pr_te, mdl = {}, {}, {}
    for s in "CP":
        y = tr[f"{s}_X1"].values
        ok = np.isfinite(y)
        m = HistGradientBoostingClassifier(**GBM).fit(tr.loc[ok, X], (y[ok] > 0).astype(int))
        pr_tr[s], pr_te[s], mdl[s] = m.predict_proba(tr[X])[:, 1], m.predict_proba(te[X])[:, 1], m
    thr = np.quantile(np.maximum(pr_tr["C"], pr_tr["P"]), 1 - TOP)
    best = np.maximum(pr_te["C"], pr_te["P"])
    side = np.where(pr_te["C"] > pr_te["P"], "C", np.where(pr_te["P"] > pr_te["C"], "P", ""))
    return np.where(best >= thr, side, ""), mdl


# ------------------------------------------------------------------------------------------------ combinations
def terciles(tr: pd.DataFrame, te: pd.DataFrame, cols):
    """Tercile codes 0/1/2 (-1 = NaN) with cut points from the training rows only."""
    btr, bte = {}, {}
    for c in cols:
        v = tr[c].values.astype(float)
        q = np.nanquantile(v, [1 / 3, 2 / 3]) if np.isfinite(v).sum() > 100 else None
        if q is None or q[0] == q[1] and np.nanmin(v) == np.nanmax(v):
            continue
        for src, dst in ((tr, btr), (te, bte)):
            x = src[c].values.astype(float)
            b = np.digitize(x, q).astype(np.int8)
            b[~np.isfinite(x)] = -1
            dst[c] = b
    return btr, bte


def _cells(codes_list, ok_rows, y, day_idx, year_idx, n_years, ncell):
    """Per cell: count, mean, distinct days, min over training years of the yearly sum."""
    code = np.zeros(len(y), np.int64)
    valid = ok_rows.copy()
    for b in codes_list:
        valid &= b >= 0
        code = code * 3 + np.maximum(b, 0)
    c, yv, dv, yr = code[valid], y[valid], day_idx[valid], year_idx[valid]
    n = np.bincount(c, minlength=ncell)
    s = np.bincount(c, weights=yv, minlength=ncell)
    ys = np.bincount(c * n_years + yr, weights=yv, minlength=ncell * n_years).reshape(ncell, n_years)
    yn = np.bincount(c * n_years + yr, minlength=ncell * n_years).reshape(ncell, n_years)
    u = np.unique(c.astype(np.int64) * 100000 + dv)
    nd = np.bincount((u // 100000).astype(np.int64), minlength=ncell)
    minyear = np.where(yn > 0, ys, np.inf).min(axis=1)
    return n, s / np.maximum(n, 1), nd, minyear


def mine_combos(tr, btr, combos, side):
    y = tr[f"{side}_X1"].values
    ok = np.isfinite(y)
    days = pd.factorize(tr.und.astype(str) + tr.day.astype(str))[0]
    yrs, years = pd.factorize(tr.year)
    best = []
    for combo in combos:
        ncell = 3 ** len(combo)
        n, mean, nd, miny = _cells([btr[c] for c in combo], ok, np.where(ok, y, 0.0), days, yrs, len(years), ncell)
        good = (n >= MIN_ROWS) & (nd >= MIN_DAYS) & (miny > 0) & (mean > 0)
        for cell in np.flatnonzero(good):
            best.append((mean[cell], combo, int(cell), int(n[cell])))
    best.sort(key=lambda x: -x[0])
    return best[:KEEP]


def cell_hits(bte, kept, nrows):
    hit = np.zeros(nrows, bool)
    for _, combo, cell, _ in kept:
        code = np.zeros(nrows, np.int64)
        valid = np.ones(nrows, bool)
        for c in combo:
            valid &= bte[c] >= 0
            code = code * 3 + np.maximum(bte[c], 0)
        hit |= valid & (code == cell)
    return hit


def sig_combo(tr, te, X, kind):
    btr, bte = terciles(tr, te, [c for c in X if c != "und_id"])
    cols = list(btr)
    rib = [c for c in cols if is_ribbon(c)]
    oth = [c for c in cols if not is_ribbon(c)]
    if kind == "C1":
        combos = [(a, b) for i, a in enumerate(cols) for b in cols[i + 1:]]
    else:
        # amendment 1: triples = ribbon x pairs of the 30 non-ribbon features whose terciles separate the training
        # net (mean of both sides' |best - worst tercile|) the most
        sep = []
        for c in oth:
            v = []
            for s in "CP":
                y = tr[f"{s}_X1"].values
                mm = [np.nanmean(y[btr[c] == k]) if (btr[c] == k).any() else np.nan for k in range(3)]
                v.append(np.nanmax(mm) - np.nanmin(mm))
            sep.append((np.nanmean(v), c))
        top = [c for _, c in sorted(sep, reverse=True)[:TRIPLE_TOP]]
        combos = [(a, b) for a in rib for b in oth]
        combos += [(a, b, c) for a in rib for i, b in enumerate(top) for c in top[i + 1:]]
    kept = {s: mine_combos(tr, btr, combos, s) for s in "CP"}
    hc, hp = cell_hits(bte, kept["C"], len(te)), cell_hits(bte, kept["P"], len(te))
    side = np.where(hc & ~hp, "C", np.where(hp & ~hc, "P", ""))
    return side, kept


# ------------------------------------------------------------------------------------------------ trading
def trade(te: pd.DataFrame, side: np.ndarray, label: str) -> pd.DataFrame:
    """One trade at a time per index, at most 3 a day, in grid order. Exit row from the label table (SPR: 45-minute
    upper bound)."""
    te = te.assign(sig_side=side)
    rows = []
    for (u, d), g in te[te.sig_side != ""].groupby(["und", "day"], sort=True):
        busy, n = -1, 0
        for r in g.sort_values("m").itertuples(index=False):
            if n >= K.MAX_PER_DAY or r.m + 1 <= busy:
                continue
            s = r.sig_side
            net = getattr(r, f"{s}_{label}")
            if not np.isfinite(net):
                continue
            xr = getattr(r, f"{s}_X1_row") if label != "SPR" else r.m + 46
            if label == "X4":
                xr = getattr(r, f"{s}_X4_row")
            rows.append(dict(und=u, day=d, m=r.m, side=s, net=net, exit_row=xr,
                             net_2x=getattr(r, f"{s}_X1_2x") if label == "X1" else np.nan,
                             post=getattr(r, f"{s}_X1_post"), exit_px=getattr(r, f"{s}_X1_exit"),
                             entry=getattr(r, f"{s}_X1_entry"), qty=r.qty))
            busy, n = xr, n + 1
    return pd.DataFrame(rows)


def twin_p(Tr: pd.DataFrame, te: pd.DataFrame, label: str, n_sets=K.TWINS):
    """Same day, same side, a random grid row (from the label table)."""
    if not len(Tr):
        return np.nan, np.nan
    rng = np.random.default_rng(K.SEED)
    lab = {}
    for (u, d), g in te.groupby(["und", "day"]):
        lab[(u, d)] = {s: g.sort_values("m")[f"{s}_{label}"].values for s in "CP"}
    arrs = [lab[(u, d)][s] for u, d, s in zip(Tr.und, Tr.day, Tr.side)]
    draws = np.array([[a[rng.integers(len(a))] for a in arrs] for _ in range(n_sets)])
    means = np.nanmean(draws, axis=1)
    return float((means >= Tr.net.mean()).mean()), float(np.nanmean(means))


def run():
    t0 = time.time()
    T = pd.read_parquet(K.OUT / "table.parquet")
    T["day"] = pd.to_datetime(T.day).dt.date
    T["year"] = [d.year for d in T.day]
    assert not T.day.map(lambda d: K.F1_HOLD[0] <= d <= K.F1_HOLD[1]).any()
    X = feature_cols(T)
    strategies = {"M1": "X1", "M2": "SPR", "M3": "X1", "M4": "X1", "C1": "X1", "C2": "X1"}
    allt, info = [], {}
    for name, tr_end, te_a, te_b in K.FOLDS:
        tr = T[T.day < tr_end].reset_index(drop=True)
        te = T[(T.day >= te_a) & (T.day <= te_b)].reset_index(drop=True)
        info[name] = dict(train_rows=len(tr), test_rows=len(te), train_days=int(tr.day.nunique()),
                          test_days=int(te.day.nunique()))
        for st, label in strategies.items():
            ts = time.time()
            if st == "M1":
                side, mdl = sig_m1(tr, te, X)
            elif st == "M2":
                side, mdl = sig_m1(tr, te, X, "SPR")
            elif st == "M3":
                side, mdl = sig_m3(tr, te, X)
            elif st == "M4":
                side, mdl = sig_m4(tr, te, X)
            else:
                side, mdl = sig_combo(tr, te, X, st)
            Tr = trade(te, side, label)
            Tr["fold"], Tr["strategy"] = name, st
            if st == "M2" and len(Tr):
                Tr["net_2x"] = spread_2x(Tr)
            allt.append(Tr)
            imp = importance(mdl, te, X, st)
            info[name][st] = dict(signals=int((side != "").sum()), trades=len(Tr), seconds=round(time.time() - ts),
                                  top_features=imp)
            print(name, st, len(Tr), "trades", round(Tr.net.sum()) if len(Tr) else 0, f"{time.time() - t0:.0f}s",
                  flush=True)
    A = pd.concat(allt, ignore_index=True)
    A.to_parquet(K.OUT / "trades.parquet")
    res = evaluate(A, T)
    K.RESULTS.mkdir(exist_ok=True)
    (K.RESULTS / "walkforward.json").write_text(json.dumps(dict(folds=info, results=res), indent=1, default=str))
    return res


def spread_2x(Tr):
    out = []
    ops = {}
    for r in Tr.itertuples(index=False):
        if r.und not in ops:
            ops[r.und] = (RD.D29.load_options(r.und), None)
            ops[r.und] = (ops[r.und][0], RD.EN29.Paths(ops[r.und][0]))
        op, paths = ops[r.und]
        d = r.day.toordinal()
        out.append(RD._spread_trade(op, paths, r.und, d, r.m, r.side, r.qty, RD.S29.sq_row(op, d), 2.0)[0])
    return out


def importance(mdl, te, X, st):
    """Top features by permutation importance on (a sample of) the test fold; for combos, the kept cells."""
    if st in ("C1", "C2"):
        return {s: [(list(c), cell, n, round(m, 1)) for m, c, cell, n in mdl[s][:5]] for s in "CP"}
    m = mdl.get("C") if isinstance(mdl, dict) and "C" in mdl else mdl.get("dir")
    lab = "C_X1" if st in ("M1", "M4") else ("C_SPR" if st == "M2" else "mv30")
    S = te[np.isfinite(te[lab])].sample(min(4000, len(te)), random_state=K.SEED)
    y = S[lab].values
    if st == "M3":
        y = (y > 0).astype(int)
    elif st == "M4":
        y = (y > 0).astype(int)
    r = permutation_importance(m, S[X], y, n_repeats=3, random_state=K.SEED, n_jobs=1)
    o = np.argsort(-r.importances_mean)[:10]
    return [(X[i], round(float(r.importances_mean[i]), 5)) for i in o]


def metrics(G: pd.DataFrame, n_days: int) -> dict:
    if not len(G):
        return dict(trades=0, days=n_days)
    daily = G.groupby("day").net.sum()
    eq = daily.sort_index().cumsum()
    cut = G.post >= G.exit_px + 0.5 * G.entry
    return dict(trades=len(G), days=n_days, per_day=len(G) / n_days, win=float((G.net > 0).mean()),
                avg_win=float(G.net[G.net > 0].mean()) if (G.net > 0).any() else np.nan,
                avg_loss=float(G.net[G.net <= 0].mean()) if (G.net <= 0).any() else np.nan,
                net=float(G.net.sum()), per_trade=float(G.net.mean()), pnl_day=float(G.net.sum() / n_days),
                green_days=float((daily > 0).mean()), worst_day=float(daily.min()),
                max_dd=float((eq - eq.cummax().clip(lower=0)).min()), cut_short=int(cut.sum()),
                net_2x=float(np.nansum(G.net_2x)) if G.net_2x.notna().any() else np.nan)


def evaluate(A: pd.DataFrame, T: pd.DataFrame) -> dict:
    res = {}
    labels = {"M1": "X1", "M2": "SPR", "M3": "X1", "M4": "X1", "C1": "X1", "C2": "X1"}
    for st in labels:
        for u in K.UNDS:
            G = A[(A.strategy == st) & (A.und == u)]
            te_days = {f: T[(T.und == u) & (T.day >= a) & (T.day <= b)].day.nunique() for f, _, a, b in K.FOLDS}
            nd = sum(te_days.values())
            r = metrics(G, max(nd, 1))
            r["folds"] = {f: metrics(G[G.fold == f], max(te_days[f], 1)) for f in te_days if te_days[f]}
            te = T[(T.und == u) & T.day.map(lambda d: any(a <= d <= b for _, _, a, b in K.FOLDS))]
            r["p_twins"], r["twin_per_trade"] = twin_p(G, te, labels[st]) if len(G) else (np.nan, np.nan)
            res[f"{st}|{u}"] = r
    keys = [k for k, v in res.items() if np.isfinite(v.get("p_twins", np.nan))]
    q = MI.bh(np.array([res[k]["p_twins"] for k in keys]))
    for k, qq in zip(keys, q):
        res[k]["bh_q"] = float(qq)
    for k, r in res.items():
        f = r.get("folds", {})
        r["passes"] = bool(r.get("trades") and r["net"] > 0 and all(v.get("net", 0) > 0 for v in f.values()
                                                                    if v.get("trades"))
                           and r.get("p_twins", 1) < 0.05 and r.get("bh_q", 1) <= 0.10
                           and (r.get("net_2x") or 0) > 0)
    res["_family"] = dict(size=len(keys))
    return res


if __name__ == "__main__":
    if sys.argv[1] == "run":
        r = run()
        for k, v in r.items():
            if k.startswith("_"):
                continue
            print(k, {x: (round(y, 3) if isinstance(y, float) else y) for x, y in v.items()
                      if x in ("trades", "net", "per_trade", "pnl_day", "p_twins", "bh_q", "passes")})
