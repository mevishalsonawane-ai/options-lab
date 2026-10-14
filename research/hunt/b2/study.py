#!/usr/bin/env python3
"""B2 steps (PREREG.md). Research only, 14 Oct 2026.

    python3 research/hunt/b2/study.py design      # walk-forward of 9 targets x 2 input sets x 3 models, importance, rules, trading
    python3 research/hunt/b2/study.py anatomy     # why green / red: concurrent changes and green vs red days (design)
    python3 research/hunt/b2/study.py holdout     # the ONE read of 27 Apr .. 12 Oct 2026
"""
from __future__ import annotations

import json
import multiprocessing as mp
import sys
from datetime import datetime, timezone

import numpy as np
import pandas as pd

import b2data as D
import b2model as M

TARGETS = D.TARGETS
INPUTS = ("base", "all")
TRADE_TARGETS = ("G15", "G30", "G60")
IMPORT_TARGETS = ("G15", "G30", "G60", "GEOD", "DAY0930")
G = {}


# ------------------------------------------------------------------------------------------------ inputs
def with_sweep(X: pd.DataFrame) -> pd.DataFrame:
    parts = []
    for u in D.UNDS:
        S = pd.read_parquet(D.OUT / f"sweep_{u}.parquet")
        S["und"] = u
        parts.append(S)
    S = pd.concat(parts, ignore_index=True)
    S["day"] = pd.to_datetime(S.day)
    S["row"] = S.row.astype(int)
    X["row"] = X.row.astype(int)
    return X.merge(S, on=["und", "day", "row"], how="left", suffixes=("", "_sw"))


def column_sets(X: pd.DataFrame, base_cols: list[str]) -> dict:
    sw = [c for c in X.columns if c not in base_cols and c not in D.TARGETS and c not in ("day", "und", "y", "DAYG")
          and not c.startswith(("net_", "why_", "cut_")) and c not in D.OUTCOME_COLS
          and pd.api.types.is_numeric_dtype(X[c])]
    return {"base": base_cols, "all": base_cols + sw}


# ------------------------------------------------------------------------------------------------ one job
def job(args):
    target, inp, model = args
    done = D.OUT / f"res_{target}_{inp}_{model}.json"
    if done.exists():                       # a finished job is kept (restart after an interruption)
        return json.loads(done.read_text())
    X, cols = G["X"], G["cols"][inp]
    Z = M.targets_frame(X, target)
    preds, extra = [], {}
    imp = []
    for blk in D.BLOCKS:
        tr, te = M.fold_masks(Z.day, blk)
        if te.sum() == 0 or tr.sum() == 0:
            continue
        Xa = M.design_matrix(Z, cols)
        y = Z.y.values
        if target == "SIZE":
            med = Z[tr].groupby("und").y.median()
            y = (Z.y.values > Z.und.map(med).values).astype(float)
        m, P = M.fit(model, Xa[tr], y[tr])
        p = M.predict(m, P, Xa[te])
        part = Z.loc[te, ["und", "day", "row"]].copy()
        part["y"], part["p"], part["block"] = y[te], p, blk
        if model == "GBM" and inp == "all" and target in TRADE_TARGETS + ("CEW", "PEW"):
            rng = np.random.default_rng(M.SEED)
            k = np.flatnonzero(tr)
            k = rng.choice(k, min(len(k), 60000), replace=False)
            ptr = M.predict(m, P, Xa[k])
            extra[blk] = dict(hi=float(np.percentile(ptr, 90)), lo=float(np.percentile(ptr, 10)))
        if model == "GBM" and inp == "all" and target in IMPORT_TARGETS and blk in ("2025", "2026"):
            names = list(cols) + [f"und_{u}" for u in D.UNDS]
            base, im = M.group_importance(m, P, Xa[te], y[te], names)
            imp.append((int(te.sum()), {f: d for f, d, _ in im}))
        preds.append(part)
    Pd = pd.concat(preds, ignore_index=True)
    res = dict(target=target, inputs=inp, model=model, n=int(len(Pd)), days=int(Pd.day.nunique()),
               auc=M.auc_w(Pd.y.values, Pd.p.values), base_rate=float(Pd.y.mean()))
    res["p"], res["lo90"], res["hi90"] = M.boot_p(Pd.y.values, Pd.p.values, Pd.day.values.astype("datetime64[D]"))
    res["by_block"] = {b: M.auc_w(g.y.values, g.p.values) for b, g in Pd.groupby("block")}
    res["by_und"] = {u: M.auc_w(g.y.values, g.p.values) for u, g in Pd.groupby("und")}
    if imp:
        tot = sum(n for n, _ in imp)
        names = imp[0][1].keys()
        res["importance"] = sorted(((nm, sum(n * d.get(nm, 0.0) for n, d in imp) / tot) for nm in names), key=lambda r: -r[1])
    res["thresholds"] = extra
    Pd.to_parquet(D.OUT / f"pred_{target}_{inp}_{model}.parquet", index=False)
    print(target, inp, model, round(res["auc"], 4), "p", round(res["p"], 4), res["n"], flush=True)
    done.write_text(json.dumps(res, default=str))
    return res


def design():
    X = with_sweep(D.load("design"))
    base_cols = D.feature_cols(D.load("design"))
    G["X"], G["cols"] = X, column_sets(X, base_cols)
    print("inputs:", {k: len(v) for k, v in G["cols"].items()}, "rows", len(X), flush=True)
    jobs = [(t, i, m) for t in TARGETS for i in INPUTS for m in M.MODELS]
    import os
    if os.environ.get("B2_SERIAL"):            # one process (the forked pool can deadlock in the GBM's OpenMP)
        res = [job(j) for j in jobs]
    else:
        with mp.get_context("fork").Pool(4) as pool:
            res = pool.map(job, jobs, chunksize=1)
    q = M.bh([r["p"] for r in res])
    for r, qq in zip(res, q):
        r["q"] = float(qq)
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), inputs={k: len(v) for k, v in G["cols"].items()},
               columns=G["cols"], results=res)
    out["rules"] = readable(X)
    out["trading"] = trading_design(X, {(r["target"]): r["thresholds"] for r in res if r["model"] == "GBM" and r["inputs"] == "all"})
    (D.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))


def readable(X):
    out = {}
    cols = G["cols"]["all"]
    for t in IMPORT_TARGETS:
        Pd = pd.read_parquet(D.OUT / f"pred_{t}_all_GBM.parquet")
        Z = Pd.merge(X[["und", "day", "row"] + cols], on=["und", "day", "row"], how="left")
        rng = np.random.default_rng(M.SEED)
        k = rng.choice(len(Z), min(len(Z), 60000), replace=False) if len(Z) > 60000 else np.arange(len(Z))
        Z = Z.iloc[k]
        out[t] = M.tree_rules(Z[cols], Z.p.values, Z.y.values, min_leaf=max(100, len(Z) // 100))
    return out


def trading_design(X, thr):
    out = {}
    for t in TRADE_TARGETS + ("OPT",):
        rows = []
        for blk in D.BLOCKS:
            if t == "OPT":
                pc = pd.read_parquet(D.OUT / "pred_CEW_all_GBM.parquet")
                pp = pd.read_parquet(D.OUT / "pred_PEW_all_GBM.parquet")
                pc, pp = pc[pc.block == blk], pp[pp.block == blk]
                Z = pc.merge(pp[["und", "day", "row", "p"]], on=["und", "day", "row"], suffixes=("_ce", "_pe"))
                Z = Z.merge(X[["und", "day", "row", "net_ce_atm_x1", "net_pe_atm_x1"]], on=["und", "day", "row"])
                T = M.trade_rule(Z, None, 0, 0, Z.p_ce.values, Z.p_pe.values, thr["CEW"][blk]["hi"], thr["PEW"][blk]["hi"])
            else:
                Pd = pd.read_parquet(D.OUT / f"pred_{t}_all_GBM.parquet")
                Pd = Pd[Pd.block == blk]
                Z = Pd.merge(X[["und", "day", "row", "net_ce_atm_x1", "net_pe_atm_x1"]], on=["und", "day", "row"])
                T = M.trade_rule(Z, Z.p.values, thr[t][blk]["hi"], thr[t][blk]["lo"])
            T["block"] = blk
            rows.append(T)
        T = pd.concat(rows, ignore_index=True)
        nd = X[(X.day >= pd.Timestamp(D.BLOCKS["2023"][0]))].day.nunique()
        sc = M.trade_score(T, nd)
        sc["by_block"] = {b: float(g.net.sum()) for b, g in T.groupby("block")}
        out[t] = sc
        print("trade", t, sc["trades"], round(sc["net"]), sc["by_block"], flush=True)
    return out


# ------------------------------------------------------------------------------------------------ anatomy
def anatomy():
    X = with_sweep(D.load("design"))
    base_cols = D.feature_cols(D.load("design"))
    cols = column_sets(X, base_cols)["all"]
    out = {}
    # B. what moves WITH the 15-minute candle: change of each input from t to t + 15
    X = X.sort_values(["und", "day", "row"])
    nxt = X.groupby(["und", "day"])[cols].shift(-3)          # rows are 5 minutes apart: t + 15 = 3 rows on
    dX = (nxt - X[cols])
    ok = X.G15.notna()
    y = X.G15[ok].values
    rows = []
    for c in cols:
        v = dX.loc[ok, c].values
        f = np.isfinite(v)
        if f.sum() < 5000 or np.nanstd(v[f]) == 0:
            continue
        rows.append((c, int(f.sum()), M.auc_w(y[f], v[f])))
    out["concurrent"] = sorted(rows, key=lambda r: -abs(r[2] - 0.5))[:40]
    # the same changes all at once: walk-forward GBM on the changes (how much of a candle's colour they "explain")
    Z = pd.concat([X.loc[ok, ["und", "day", "row"]].reset_index(drop=True),
                   dX.loc[ok].reset_index(drop=True).add_prefix("d_")], axis=1)
    Z["y"] = y
    dcols = [c for c in Z.columns if c.startswith("d_")]
    preds = []
    for blk in D.BLOCKS:
        tr, te = M.fold_masks(Z.day, blk)
        Xa = M.design_matrix(Z, dcols)
        m, P = M.fit("GBM", Xa[tr], Z.y.values[tr])
        preds.append(pd.DataFrame(dict(y=Z.y.values[te], p=M.predict(m, P, Xa[te]), day=Z.day.values[te])))
    Pd = pd.concat(preds)
    out["concurrent_model_auc"] = dict(auc=M.auc_w(Pd.y.values, Pd.p.values), n=int(len(Pd)), days=int(Pd.day.nunique()))
    # C. green vs red days (one row per index-day, at 09:30; features known then + the first 15 minutes)
    Dd = X[(X.row == 15) & X.DAYG.notna()].copy()
    tabs = {}

    def tab(name, key):
        g = Dd.groupby(key).DAYG.agg(["size", "mean"])
        tabs[name] = [(str(k), int(r["size"]), float(r["mean"])) for k, r in g.iterrows()]

    q = lambda s: pd.qcut(s, 5, labels=["very low", "low", "mid", "high", "very high"], duplicates="drop")
    tab("index", Dd.und)
    tab("weekday", Dd.day.dt.day_name())
    tab("expiry day", Dd.is_expiry.map({True: "expiry", False: "not expiry"}))
    tab("month", Dd.day.dt.month)
    tab("gap (fifths)", q(Dd.gap))
    tab("yesterday's return (fifths)", q(Dd.r1d))
    tab("last 5 days' return (fifths)", q(Dd.r5d))
    if "vix" in Dd:
        tab("VIX (fifths)", q(Dd.vix))
    tab("first 15 minutes (09:15-09:29) colour", np.sign(Dd["ret_15m"]).map({1.0: "green", -1.0: "red", 0.0: "flat"}))
    tab("first 15 minutes' move (fifths)", q(Dd["ret_15m"]))
    tab("position in yesterday's range at 09:30", q(Dd.pd_pos))
    tab("other index (BANKNIFTY / NIFTY) first 15 min", np.sign(Dd.get("oth_BANKNIFTY_rday", Dd.get("oth_NIFTY_rday"))).map(
        {1.0: "green", -1.0: "red", 0.0: "flat"}))
    out["days"] = tabs
    out["day_counts"] = dict(days=int(len(Dd)), green=float(Dd.DAYG.mean()))
    # what the next 15 minutes do after the last 15 minutes (persistence vs reversal), every row
    ok2 = X.G15.notna() & X.ret_15m.notna()
    out["after_last15"] = [(str(k), int(n), float(m)) for k, (n, m) in
                           X[ok2].groupby(np.sign(X.ret_15m[ok2])).G15.agg(["size", "mean"]).iterrows()]
    (D.RESULTS / "anatomy.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps({k: v for k, v in out.items() if k != "concurrent"}, indent=1, default=str)[:3000])
    print(out["concurrent"][:15])


# ------------------------------------------------------------------------------------------------ holdout (one read)
def holdout():
    if D.HOLD_FLAG.exists():
        raise SystemExit("B2's holdout has been read already. A new test needs a new PREREG and fresh data.")
    if not (D.RESULTS / "design.json").exists():
        raise SystemExit("run design first")
    import os
    import subprocess
    if "F1_SRC" not in os.environ or "B2_BRAIN_CACHE" not in os.environ:
        raise SystemExit("set F1_SRC (checkout of claude/f1-india-factory) and B2_BRAIN_CACHE (brain cache with the 2026 cube)")
    D.HOLD_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    env = dict(os.environ, IRAALGO_CACHE=os.environ["B2_BRAIN_CACHE"], B2_OUT=str(D.OUT), B2_F1=str(D.F1),
               IRAALGO_LAKE=str(D.LAKE))
    subprocess.run([sys.executable, str(D.HERE / "holdout_rows.py")], env=env, check=True)
    Xd = with_sweep(D.load("design"))
    Xh = with_sweep(D.load("holdout"))
    des = json.loads((D.RESULTS / "design.json").read_text())
    cols = des["columns"]
    res = []
    keep = {}
    for t in TARGETS:
        Zd, Zh = M.targets_frame(Xd, t), M.targets_frame(Xh, t)
        for inp in INPUTS:
            c = [x for x in cols[inp]]
            for c_ in c:
                if c_ not in Zh:
                    Zh[c_] = np.nan
            Ad, Ah = M.design_matrix(Zd, c), M.design_matrix(Zh, c)
            yd, yh = Zd.y.values, Zh.y.values
            if t == "SIZE":
                med = Zd.groupby("und").y.median()
                yd = (Zd.y.values > Zd.und.map(med).values).astype(float)
                yh = (Zh.y.values > Zh.und.map(med).values).astype(float)
            for model in M.MODELS:
                m, P = M.fit(model, Ad, yd)
                p = M.predict(m, P, Ah)
                r = dict(target=t, inputs=inp, model=model, n=int(len(yh)), days=int(Zh.day.nunique()),
                         auc=M.auc_w(yh, p), base_rate=float(np.mean(yh)))
                r["p"], r["lo90"], r["hi90"] = M.boot_p(yh, p, Zh.day.values.astype("datetime64[D]"))
                r["by_und"] = {u: M.auc_w(yh[Zh.und.values == u], p[Zh.und.values == u]) for u in D.UNDS}
                res.append(r)
                if model == "GBM" and inp == "all":
                    rng = np.random.default_rng(M.SEED)
                    k = rng.choice(len(yd), min(len(yd), 60000), replace=False)
                    ptr = M.predict(m, P, Ad[k])
                    keep[t] = (Zh[["und", "day", "row"]].assign(p=p), float(np.percentile(ptr, 90)), float(np.percentile(ptr, 10)))
                print("holdout", t, inp, model, round(r["auc"], 4), "p", round(r["p"], 4), flush=True)
    q = M.bh([r["p"] for r in res])
    for r, qq in zip(res, q):
        r["q"] = float(qq)
        r["pattern_pass"] = bool(r["auc"] >= 0.52 and r["p"] < 0.05 and r["q"] < 0.10)
    nd = Xh.day.nunique()
    trading = {}
    for t in TRADE_TARGETS + ("OPT",):
        if t == "OPT":
            (pc, hc, _), (pp, hp, _) = keep["CEW"], keep["PEW"]
            Z = pc.merge(pp, on=["und", "day", "row"], suffixes=("_ce", "_pe")).merge(
                Xh[["und", "day", "row", "net_ce_atm_x1", "net_pe_atm_x1"]], on=["und", "day", "row"])
            T = M.trade_rule(Z, None, 0, 0, Z.p_ce.values, Z.p_pe.values, hc, hp)
        else:
            pz, hi, lo = keep[t]
            Z = pz.merge(Xh[["und", "day", "row", "net_ce_atm_x1", "net_pe_atm_x1"]], on=["und", "day", "row"])
            T = M.trade_rule(Z, Z.p.values, hi, lo)
        sc = M.trade_score(T, nd)
        tw = M.twins(Xh, T)
        sc["twin_mean"], sc["twin_p"] = float(tw.mean()), float((1 + (tw >= sc["net"]).sum()) / (len(tw) + 1))
        trading[t] = sc
    tq = M.bh([trading[t]["twin_p"] for t in trading])
    for (t, sc), qq in zip(trading.items(), tq):
        dz = des["trading"][t]
        sc["q"] = float(qq)
        sc["pass"] = bool(dz["net"] > 0 and sum(v > 0 for v in dz["by_block"].values()) >= 3 and sc["net"] > 0
                          and sc["twin_p"] < 0.05 and qq < 0.10 and sum(v > 0 for v in sc["by_und"].values()) >= 3)
        print("holdout trade", t, sc["trades"], round(sc["net"]), "twin p", sc["twin_p"], flush=True)
    out = dict(read=D.HOLD_FLAG.read_text().strip(), rows=int(len(Xh)), days=int(nd), results=res, trading=trading)
    (D.RESULTS / "holdout.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    {"design": design, "anatomy": anatomy, "holdout": holdout}[sys.argv[1]]()
