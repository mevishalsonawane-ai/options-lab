"""R41 runner (PREREG.md): design walk-forward, then ONE holdout read per market.

    python research/hunt/r41/run.py build              # India design data (5 indices), Q2 arm data, crypto design
    python research/hunt/r41/run.py wf q1|q2|q3|q4|q5  # design walk-forward -> results/wf_<q>.json, <OUT>/preds
    python research/hunt/r41/run.py holdout india      # the one India read (Q1-Q4)  -> results/holdout_india.json
    python research/hunt/r41/run.py holdout crypto     # the one crypto read (Q5)    -> results/holdout_crypto.json
    python research/hunt/r41/run.py summary            # BH families over everything -> results/summary.json

Each fold refits every network (3 seeds x MLP / TCN / GRU) and every baseline on rows strictly before the fold.
Validation = the last 20% of the training window's sessions (1-session gap), used for early stopping and every rule
threshold. Holdout models are trained on the design block before the holdout only.
"""
from __future__ import annotations

import json
import sys
import time
from datetime import date

import numpy as np
import pandas as pd

import r41cfg as K
import evalx as E
import nets as NN

ARCH = ("mlp", "tcn", "gru")
BASE = ("base", "linear", "trees")
LOG = lambda *a: print(time.strftime("%H:%M:%S"), *a, flush=True)  # noqa: E731


# ------------------------------------------------------------------------------------------------ question specs
def _onehot(D: pd.DataFrame, col: str, levels) -> pd.DataFrame:
    return pd.DataFrame({f"is_{v}": (D[col] == v).astype(float).values for v in levels}, index=D.index)


def spec(q: str) -> dict:
    if q in ("q1", "q5"):
        hs = K.HORIZONS if q == "q1" else K.C_HORIZONS
        t = []
        for h in hs:
            t += [(f"dir_{h}", "bin"), (f"size_{h}", "reg")]
        return dict(targets=t)
    return dict(targets={"q2": [("win", "bin")], "q3": [("blast", "bin")], "q4": [("gap", "bin")]}[q])


def targets(q: str, D: pd.DataFrame) -> np.ndarray:
    cols = []
    for name, kind in spec(q)["targets"]:
        if name.startswith("dir_"):
            r = D[f"y_ret_{name[4:]}"].to_numpy(float)
            cols.append(np.where(np.isfinite(r) & (r != 0), (r > 0).astype(float), np.nan))
        elif name.startswith("size_"):
            v = D[f"y_range_{name[5:]}"].to_numpy(float)
            if q == "q1":
                with np.errstate(all="ignore"):
                    v = np.where(v > 0, np.log(v), np.nan)
            cols.append(v)                                    # crypto labels are already log ranges
        else:
            cols.append(D[f"y_{name}"].to_numpy(float))
    return np.column_stack(cols)


def inputs(q: str, D: pd.DataFrame) -> tuple[np.ndarray, list[str], np.ndarray | None]:
    """Tabular inputs (brain features + the question's extras) and the B1 size-formula inputs (Q1)."""
    if q == "q5":
        drop = {"und", "ts", "day"}
        cols = [c for c in D.columns if c not in drop and not c.startswith(("y_", "tr_"))]
        return D[cols].to_numpy(float), cols, None
    from india import input_cols
    base = input_cols(D)
    X = D[base].astype(float)
    extra = [_onehot(D, "und", K.UNDS), pd.DataFrame({"row_frac": D["row"].astype(float) / 375}, index=D.index)]
    if q in ("q2", "q3"):
        extra.append(pd.DataFrame({"side_flag": D["side"].astype(float)}, index=D.index))
    if q == "q3":
        extra.append(D[[c for c in D.columns if c.startswith("ctx_")]].astype(float))
    X = pd.concat([X] + extra, axis=1)
    b1 = None
    if q == "q1":
        with np.errstate(all="ignore"):
            b1 = np.column_stack([D.get("tod_logrange_30m", np.nan), D.get("tod_logrange_60m", np.nan),
                                  np.log(D["range_30m_pct"]), np.log(D["range_60m_pct"]), np.log(D["vix"])])
        b1 = np.where(np.isfinite(b1), b1, np.nan)
    return X.to_numpy(float), list(X.columns), b1


# ------------------------------------------------------------------------------------------------ data
def load(q: str, part: str) -> tuple[pd.DataFrame, np.ndarray]:
    if q == "q5":
        import crypto
        d = crypto.load(part)
        return d["q5"], d["q5_seq"]
    if q == "q2":
        import arms
        d = arms.load(part)
        return d["q2"], d["q2_seq"]
    import india
    Ds, Ss = [], []
    for u in K.UNDS:
        if not india.path(u, part).exists():
            continue
        d = india.load(u, part)
        if len(d[q]):
            Ds.append(d[q])
            Ss.append(d[q + "_seq"])
    D = pd.concat(Ds, ignore_index=True)
    return D, np.concatenate(Ss)


def day_key(q: str, D: pd.DataFrame) -> np.ndarray:
    """The session a row belongs to (India: its date; crypto: the IST date of the entry minute)."""
    if q == "q5":
        return (pd.to_datetime(D.ts) + pd.Timedelta(minutes=331)).dt.date.values
    return np.asarray(D.day.values)


def folds(q: str, D: pd.DataFrame) -> dict:
    """{fold: (train_idx, test_idx)} on design rows: train strictly before the fold (Q4: also drop the last training
    session, whose label is the fold's first morning; crypto: one day before the fold)."""
    out = {}
    if q == "q5":
        ts = pd.to_datetime(D.ts)
        for f, (lo, hi) in K.C_FOLDS.items():
            tr = np.flatnonzero((ts < lo - pd.Timedelta(days=1)).values)
            te = np.flatnonzero(((ts >= lo) & (ts < hi)).values)
            out[f] = (tr, te)
        return out
    day = np.asarray(D.day.values)
    for f, (lo, hi) in K.FOLDS.items():
        trm = day < lo
        if q == "q4" and trm.any():
            trm &= day < max(day[trm])
        te = np.flatnonzero((day >= lo) & (day <= hi))
        out[f] = (np.flatnonzero(trm), te)
    return out


def enough(q: str, D: pd.DataFrame, tr: np.ndarray) -> bool:
    if q == "q5":
        return len(set(pd.to_datetime(D.ts.values[tr]).date)) >= K.C_MIN_TRAIN_DAYS
    return len(set(zip(D.und.values[tr], D.day.values[tr]))) >= K.MIN_TRAIN_SESSIONS


# ------------------------------------------------------------------------------------------------ one fit
def fit_fold(q: str, X, S, Y, b1, days, tr, te, tag: str, save_models: bool = False) -> dict:
    """Fit every model on the training rows (minus validation for the nets' early stopping), predict validation and
    test rows. Returns {"val": {model: (n_val, k)}, "test": {...}, "info": ...}."""
    kinds = [k for _, k in spec(q)["targets"]]
    val, keep = NN.val_mask(days[tr])
    trk, v = tr[keep], val[keep]
    vrows, frows = trk[v], trk[~v]
    out = {"val": {}, "test": {}, "info": {}, "val_rows": vrows}
    t0 = time.time()
    for a in ARCH:
        net = NN.Net(a, kinds).fit(X[trk], S[trk], Y[trk], v, log=lambda m: LOG(tag, m))
        out["val"][a] = net.predict(X[vrows], S[vrows])
        out["test"][a] = net.predict(X[te], S[te])
        out["info"][a] = dict(seeds=net.info, params=NN.n_params(net.models[0]))
        if save_models:
            p = K.OUT / "models" / f"{tag}_{a}.pt"
            p.parent.mkdir(parents=True, exist_ok=True)
            import torch
            torch.save(dict(states=[m.state_dict() for m in net.models], tab=net.tab.__dict__,
                            seq=net.seq.__dict__, ymu=net.ymu, ysd=net.ysd, kinds=kinds), p)
            out["info"][a]["file"] = str(p)
            out["info"][a]["net"] = net
    out["val"]["nn"] = np.mean([out["val"][a] for a in ARCH], 0)
    out["test"]["nn"] = np.mean([out["test"][a] for a in ARCH], 0)
    for b, cls in (("base", NN.BaseRate), ("linear", NN.Linear), ("trees", NN.Trees)):
        vv, tt = [], []
        for j, k in enumerate(kinds):
            ok = np.isfinite(Y[frows, j])
            m = cls(k).fit(X[frows][ok], Y[frows, j][ok])
            vv.append(m.predict(X[vrows]))
            tt.append(m.predict(X[te]))
        out["val"][b], out["test"][b] = np.column_stack(vv), np.column_stack(tt)
    if b1 is not None:                                       # B1's 4-input size formula (size targets only)
        vv, tt = [], []
        for j, (name, k) in enumerate(spec(q)["targets"]):
            if k != "reg":
                vv.append(np.full(len(vrows), np.nan))
                tt.append(np.full(len(te), np.nan))
                continue
            h = int(name.split("_")[1])
            cols = [1 if h == 60 else 0, 2, 3, 4]
            ok = np.isfinite(Y[frows, j])
            m = NN.Linear("reg").fit(b1[frows][:, cols][ok], Y[frows, j][ok])
            vv.append(m.predict(b1[vrows][:, cols]))
            tt.append(m.predict(b1[te][:, cols]))
        out["val"]["b1"], out["test"]["b1"] = np.column_stack(vv), np.column_stack(tt)
    LOG(tag, f"fold fitted in {time.time() - t0:.0f}s ({len(trk)} train rows, {len(te)} test rows)")
    return out


# ------------------------------------------------------------------------------------------------ rules
def rule_rows(q: str, D, pv, pt, val_rows, te, model: str, j_dir: int, j_size: int | None, h, top: float,
              size_filter: bool) -> pd.DataFrame:
    """Direction rules (R1-R6, R13-R16): the test rows the rule trades and their side; threshold from validation."""
    conf_v = np.abs(pv[model][:, j_dir] - 0.5)
    thr = np.nanquantile(conf_v, 1 - top)
    p = pt[model][:, j_dir]
    sel = np.abs(p - 0.5) >= thr
    if size_filter:
        sel &= pt[model][:, j_size] >= np.nanmedian(pv[model][:, j_size])
    side = np.where(p > 0.5, 1, -1)
    R = D.iloc[te].assign(_sel=sel, _side=side, _i=te)
    return R[R._sel]


def india_dir_trades(R: pd.DataFrame, h: int) -> pd.DataFrame:
    """One open position per index: walk each index-day in time order, skip a signal while the previous trade is open.
    A row needs both sides priced (so its random twin is defined)."""
    rows = []
    for (u, d), g in R.sort_values("row").groupby(["und", "day"]):
        busy = -1
        for _, r in g.iterrows():
            nc, npu = r[f"tr_{h}_C_net"], r[f"tr_{h}_P_net"]
            if not (np.isfinite(nc) and np.isfinite(npu)) or r.row <= busy:
                continue
            s = "C" if r._side > 0 else "P"
            o = "P" if s == "C" else "C"
            rows.append(dict(und=u, day=d, net=r[f"tr_{h}_{s}_net"], net_x2=r[f"tr_{h}_{s}_net_x2"],
                             other=r[f"tr_{h}_{o}_net"], cut=r[f"tr_{h}_{s}_cut"]))
            busy = r[f"tr_{h}_{s}_exit_row"]
    return pd.DataFrame(rows, columns=["und", "day", "net", "net_x2", "other", "cut"])


def crypto_dir_trades(R: pd.DataFrame, h: int) -> pd.DataFrame:
    rows = []
    for u, g in R.sort_values("ts").groupby("und"):
        busy = pd.Timestamp.min
        for _, r in g.iterrows():
            if not r[f"tr_{h}_inwin"] or r.ts <= busy:
                continue
            nl, ns = r[f"tr_{h}_L_net"], r[f"tr_{h}_S_net"]
            if not (np.isfinite(nl) and np.isfinite(ns)):
                continue
            s, o = ("L", "S") if r._side > 0 else ("S", "L")
            rows.append(dict(und=u, day=(r.ts + pd.Timedelta(minutes=331)).date(), net=r[f"tr_{h}_{s}_net"],
                             net_x2=r[f"tr_{h}_{s}_net_x2"], other=r[f"tr_{h}_{o}_net"], cut=r[f"tr_{h}_{s}_cut"]))
            busy = r.ts + pd.Timedelta(minutes=h)
    return pd.DataFrame(rows, columns=["und", "day", "net", "net_x2", "other", "cut"])


def q4_trades(R: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for _, r in R.iterrows():
        nc, npu = r.tr_C_net, r.tr_P_net
        if not (np.isfinite(nc) and np.isfinite(npu)):
            continue
        s, o = ("C", "P") if r._side > 0 else ("P", "C")
        rows.append(dict(und=r.und, day=r.day, net=r[f"tr_{s}_net"], net_x2=r[f"tr_{s}_net_x2"],
                         other=r[f"tr_{o}_net"], cut=r[f"tr_{s}_cut"], call=nc, is_call=s == "C"))
    return pd.DataFrame(rows, columns=["und", "day", "net", "net_x2", "other", "cut", "call", "is_call"])


def rules_for_fold(q: str, D: pd.DataFrame, fit: dict, te: np.ndarray, model: str) -> dict:
    """{rule_id: trades frame (+ 'pool' for pick/skip twins)} for one fold and one model's scores."""
    pv, pt, vr = fit["val"], fit["test"], fit["val_rows"]
    names = [n for n, _ in spec(q)["targets"]]
    out = {}
    if q in ("q1", "q5"):
        hs = K.HORIZONS if q == "q1" else K.C_HORIZONS
        for i, h in enumerate(hs):
            jd, js = names.index(f"dir_{h}"), names.index(f"size_{h}")
            ids = ([f"R{1 + i}", f"R{4 + i}"] if q == "q1" else [f"R{14 + i}"])
            for rid, sf in zip(ids, (False, True)):
                R = rule_rows(q, D, pv, pt, vr, te, model, jd, js, h, K.Q1_TOP if q == "q1" else K.Q5_TOP, sf)
                out[rid] = dict(trades=india_dir_trades(R, h) if q == "q1" else crypto_dir_trades(R, h), twin="side")
    elif q == "q4":
        R = rule_rows(q, D, pv, pt, vr, te, model, 0, None, None, K.Q4_TOP, False)
        out["R13"] = dict(trades=q4_trades(R), twin="side")
    elif q == "q3":
        thr = np.nanquantile(pv[model][:, 0], 1 - K.Q3_TOP)
        T = D.iloc[te].assign(_p=pt[model][:, 0])
        T = T[np.isfinite(T.tr_net)]
        pick = T[T._p >= thr]
        out["R12"] = dict(trades=pd.DataFrame(dict(und=pick.und, day=pick.day, net=pick.tr_net,
                                                   net_x2=pick.tr_net_x2, cut=False)),
                          twin="pick", pool=T.tr_net.to_numpy(float))
    return out


def q2_rule(D: pd.DataFrame, fit: dict, te: np.ndarray, model: str) -> dict:
    thr = np.nanquantile(fit["val"][model][:, 0], K.Q2_SKIP)
    T = D.iloc[te].assign(_p=fit["test"][model][:, 0])
    keep = T._p >= thr
    return dict(trades=pd.DataFrame(dict(und=T.und[keep], day=T.day[keep], net=T.tr_net[keep],
                                         net_x2=T.tr_net_x2[keep], cut=False)),
                twin="skip", all=T.tr_net.to_numpy(float), all_tdays=T.day.values, n_skip=int((~keep).sum()),
                all_days=sorted(set(T.day)), base=E.trade_stats(T.tr_net, T.day, sorted(set(T.day))))


def rule_report(rule: dict, all_days) -> dict:
    t = rule["trades"]
    st = E.trade_stats(t.net.to_numpy(float), t.day.values, all_days, t.cut.to_numpy(float),
                       t.net_x2.to_numpy(float))
    tot = float(t.net.sum())
    if rule["twin"] == "side":
        tw = E.twins_side(t.net.to_numpy(float), t.other.to_numpy(float))
    elif rule["twin"] == "pick":
        tw = E.twins_pick(rule["pool"], len(t))
    else:
        tw = E.twins_skip(rule["all"], rule["n_skip"])
    st["p_vs_twins"] = E.twin_p(tot, tw) if len(t) else np.nan
    if "call" in t:              # not pre-registered (reported only): the same nights, always the call (gap-up drift)
        st["share_calls"] = float(t.is_call.mean()) if len(t) else np.nan
        st["same_nights_all_calls_pnl_per_day"] = float(t.call.sum() / max(len(set(all_days)), 1))
    st["twin_mean_total"] = float(np.mean(tw))
    if "base" in rule:
        st["unfiltered"] = rule["base"]
        st["skipped"] = rule["n_skip"]
    return st


# ------------------------------------------------------------------------------------------------ metrics
def model_metrics(q, Y, preds: dict, days, und) -> dict:
    """Per target: every model's pooled score + CI, NN vs the best baseline (bootstrap diff), per-index scores."""
    out = {}
    for j, (name, kind) in enumerate(spec(q)["targets"]):
        ok = np.isfinite(Y[:, j])
        y = Y[ok, j]
        d = days[ok]
        res = {"n": int(ok.sum()), "sessions": int(len(set(d))), "base_rate": float(np.mean(y)) if kind == "bin"
               else None, "models": {}}
        for m, P in preds.items():
            pj = P[ok, j]
            if not np.isfinite(pj).all():
                continue
            s = E.score(kind, y, pj)
            s.update(E.boot_ci(kind, y, pj, d))
            s["calibration"] = E.calibration(kind, y, pj)
            res["models"][m] = s
        prim = E.primary(kind)
        bases = [m for m in res["models"] if m in ("base", "linear", "trees", "b1")]
        best = (min if kind == "bin" else max)(bases, key=lambda m: res["models"][m][prim])
        res["best_baseline"] = best
        res["nn_vs_best"] = E.boot_diff(kind, y, preds["nn"][ok, j], preds[best][ok, j], d)
        res["nn_vs_trees"] = E.boot_diff(kind, y, preds["nn"][ok, j], preds["trees"][ok, j], d)
        res["per_und"] = {}
        for u in sorted(set(und[ok])):
            mm = und[ok] == u
            res["per_und"][str(u)] = {m: E.score(kind, y[mm], preds[m][ok, j][mm]) for m in ("nn", best, "trees")
                                      if m in preds}
        out[name] = res
    return out


# ------------------------------------------------------------------------------------------------ walk-forward
def run_wf(q: str, arm: str | None = None) -> dict:
    D, S = load(q, "design")
    if q == "q2":
        m = (D.arm == arm).values
        D, S = D[m].reset_index(drop=True), S[m]
    X, cols, b1 = inputs(q, D)
    Y = targets(q, D)
    days = day_key(q, D)
    und = D.und.values.astype(str)
    F = folds(q, D)
    preds = {}
    test_idx, fold_of, fold_res, rules = [], [], {}, {}
    gbm_rules = {}
    for f, (tr, te) in F.items():
        tag = f"{q}{'_' + arm if arm else ''}_{f}"
        if not len(te):
            fold_res[f] = dict(status="no test rows")
            continue
        if not enough(q, D, tr):
            fold_res[f] = dict(status="too little training data", train_rows=int(len(tr)))
            LOG(tag, "skipped: too little training data")
            continue
        fit = fit_fold(q, X, S, Y, b1, days, tr, te, tag)
        for m, P in fit["test"].items():
            preds.setdefault(m, []).append(P)
        test_idx.append(te)
        fold_of += [f] * len(te)
        fm = model_metrics(q, Y[te], fit["test"], days[te], und[te])
        fold_res[f] = dict(status="ok", train_rows=int(len(tr)), test_rows=int(len(te)), info=fit["info"],
                           metrics={k: dict(n=v["n"], best=v["best_baseline"], nn_vs_best=v["nn_vs_best"],
                                            scores={m: {kk: s[kk] for kk in ("logloss", "auc", "r2", "mae") if kk in s}
                                                    for m, s in v["models"].items()})
                                    for k, v in fm.items()})
        all_days = sorted(set(days[te]))
        for model, store in (("nn", rules), ("trees", gbm_rules)):
            rr = q2_rule(D, fit, te, model) if q == "q2" else rules_for_fold(q, D, fit, te, model)
            if q == "q2":
                rr = {"Q2": rr}
            for rid, r in rr.items():
                rep = rule_report(r, r.get("all_days", all_days))
                store.setdefault(rid, {"folds": {}, "_trades": [], "_days": [], "_r": []})
                store[rid]["folds"][f] = rep
                store[rid]["_trades"].append(r["trades"])
                store[rid]["_days"] += list(r.get("all_days", all_days))
                store[rid]["_r"].append(r)
    if not test_idx:
        return dict(q=q, arm=arm, folds=fold_res, status="no fold ran")
    te_all = np.concatenate(test_idx)
    P = {m: np.concatenate(v) for m, v in preds.items()}
    met = model_metrics(q, Y[te_all], P, days[te_all], und[te_all])
    for name in met:
        j = [n for n, _ in spec(q)["targets"]].index(name)
        kind = spec(q)["targets"][j][1]
        signs = []
        for f, fr in fold_res.items():
            if fr.get("status") == "ok":
                dd = fr["metrics"][name]["nn_vs_best"]["diff"]
                signs.append(bool(dd < 0) if kind == "bin" else bool(dd > 0))
        met[name]["nn_better_folds"] = f"{sum(signs)}/{len(signs)}"
    out_rules = {}
    for store, lab in ((rules, "nn"), (gbm_rules, "trees")):
        for rid, s in store.items():
            pooled = dict(trades=pd.concat(s["_trades"], ignore_index=True), twin=s["_r"][0]["twin"])
            if pooled["twin"] == "pick":
                pooled["pool"] = np.concatenate([r["pool"] for r in s["_r"]])
            if pooled["twin"] == "skip":
                pooled["all"] = np.concatenate([r["all"] for r in s["_r"]])
                pooled["n_skip"] = sum(r["n_skip"] for r in s["_r"])
                pooled["base"] = E.trade_stats(pooled["all"], np.concatenate([r["all_tdays"] for r in s["_r"]]),
                                               sorted(set(s["_days"])))
            rep = rule_report(pooled, sorted(set(s["_days"])))
            folds_pos = sum(1 for v in s["folds"].values() if v["pnl_per_day"] > 0)
            rep["positive_folds"] = f"{folds_pos}/{len(s['folds'])}"
            out_rules.setdefault(rid, {})[lab] = dict(pooled=rep, folds=s["folds"])
    (K.OUT / "preds").mkdir(parents=True, exist_ok=True)
    pd.to_pickle(dict(P=P, idx=te_all, fold=fold_of), K.OUT / "preds" / f"wf_{q}{'_' + arm if arm else ''}.pkl")
    return dict(q=q, arm=arm, rows=int(len(D)), sessions=int(len(set(zip(D.und, days)))), inputs=len(cols),
                folds=fold_res, metrics=met, rules=out_rules)


# ------------------------------------------------------------------------------------------------ holdout
def run_holdout_q(q: str, arm: str | None = None) -> dict:
    Dd, Sd = load(q, "design")
    Dh, Sh = load(q, "holdout")
    if q == "q2":
        md, mh = (Dd.arm == arm).values, (Dh.arm == arm).values
        Dd, Sd, Dh, Sh = Dd[md].reset_index(drop=True), Sd[md], Dh[mh].reset_index(drop=True), Sh[mh]
    if q == "q5":
        ts = pd.to_datetime(Dd.ts)
        lo, hi = K.C_HOLD_TRAIN
        m = ((ts >= lo) & (ts < hi - pd.Timedelta(days=1))).values
    else:
        lo, hi = K.HOLD_TRAIN
        m = np.array([lo <= d <= hi for d in Dd.day])
        if q == "q4":
            m &= Dd.day.values < max(Dd.day.values[m])
    Dd, Sd = Dd[m].reset_index(drop=True), Sd[m]
    D = pd.concat([Dd, Dh], ignore_index=True)
    S = np.concatenate([Sd, Sh])
    X, cols, b1 = inputs(q, D)
    Y = targets(q, D)
    days = day_key(q, D)
    und = D.und.values.astype(str)
    tr, te = np.arange(len(Dd)), np.arange(len(Dd), len(D))
    tag = f"hold_{q}{'_' + arm if arm else ''}"
    fit = fit_fold(q, X, S, Y, b1, days, tr, te, tag, save_models=True)
    met = model_metrics(q, Y[te], fit["test"], days[te], und[te])
    all_days = sorted(set(days[te]))
    rules = {}
    for model in ("nn", "trees"):
        rr = {"Q2": q2_rule(D, fit, te, model)} if q == "q2" else rules_for_fold(q, D, fit, te, model)
        for rid, r in rr.items():
            rules.setdefault(rid, {})[model] = rule_report(r, r.get("all_days", all_days))
    phone = {}
    for a in ARCH:
        net = fit["info"][a].pop("net")
        phone[a] = phone_cost(net, X[te[:1]], S[te[:1]])
    return dict(q=q, arm=arm, train_rows=int(len(tr)), test_rows=int(len(te)),
                test_sessions=int(len(set(zip(und[te], days[te])))), info=fit["info"], metrics=met, rules=rules,
                phone=phone)


def phone_cost(net, xt, xs) -> dict:
    import torch
    m = net.models[0]
    n = NN.n_params(m)
    T = torch.from_numpy(net.tab.transform(xt))
    S = torch.from_numpy(net.seq.transform(xs))
    with torch.no_grad():
        for _ in range(5):
            m(T, S)
        t0 = time.perf_counter()
        for _ in range(200):
            m(T, S)
    ms = (time.perf_counter() - t0) / 200 * 1000
    return dict(params=n, kb_float32=round(n * 4 / 1024, 1), ensemble_kb=round(n * 4 * len(net.models) / 1024, 1),
                ms_per_row_one_seed=round(ms, 3), ms_per_row_ensemble=round(ms * len(net.models), 3))


# ------------------------------------------------------------------------------------------------ CLI
def jdump(obj, p):
    def conv(o):
        if isinstance(o, (np.floating,)):
            return None if not np.isfinite(o) else float(o)
        if isinstance(o, (np.integer,)):
            return int(o)
        if isinstance(o, (np.bool_,)):
            return bool(o)
        if isinstance(o, (date, pd.Timestamp)):
            return str(o)
        if isinstance(o, np.ndarray):
            return o.tolist()
        return str(o)

    def clean(o):
        if isinstance(o, dict):
            return {str(k): clean(v) for k, v in o.items()}
        if isinstance(o, (list, tuple)):
            return [clean(v) for v in o]
        if isinstance(o, float) and not np.isfinite(o):
            return None
        return o
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(clean(json.loads(json.dumps(obj, default=conv))), indent=1) + "\n")


def q2_arms(part="design") -> list[str]:
    import arms
    D, _ = load("q2", "design")
    n = D.groupby("arm").size()
    return [a for a in arms.ARMS if a in n and n[a] >= K.MIN_ARM_TRADES]


def main(argv):
    cmd = argv[0]
    if cmd == "build":
        what = argv[1] if len(argv) > 1 else "all"
        if what in ("all", "india"):
            import india
            for u in (argv[2:] or K.UNDS):
                india.save(u, "design")
        if what in ("all", "q2"):
            import arms
            arms.save("design")
        if what in ("all", "crypto"):
            import crypto
            crypto.save("design")
    elif cmd == "wf":
        q = argv[1]
        if q == "q2":
            res = {"arms": {}}
            import arms
            D, _ = load("q2", "design")
            n = D.groupby("arm").size().to_dict()
            res["design_trades"] = n
            for a in arms.ARMS:
                if n.get(a, 0) >= K.MIN_ARM_TRADES:
                    res["arms"][a] = run_wf("q2", a)
                else:
                    res["arms"][a] = dict(status=f"not modelled: {n.get(a, 0)} design trades (< {K.MIN_ARM_TRADES})")
            jdump(res, K.RESULTS / "wf_q2.json")
        else:
            jdump(run_wf(q), K.RESULTS / f"wf_{q}.json")
    elif cmd == "holdout":
        import india
        kind = argv[1]
        india.holdout_gate(kind, "start")
        if kind == "india":
            import arms
            for u in K.UNDS:
                if not india.path(u, "holdout").exists():
                    india.save(u, "holdout")
            if not arms.path("holdout").exists():
                arms.save("holdout")
            res = {}
            for q in ("q1", "q3", "q4"):
                res[q] = run_holdout_q(q)
            res["q2"] = {a: run_holdout_q("q2", a) for a in q2_arms()}
        else:
            import crypto
            if not crypto.path("holdout").exists():
                crypto.save("holdout")
            res = {"q5": run_holdout_q("q5")}
        jdump(res, K.RESULTS / f"holdout_{kind}.json")
        india.holdout_gate(kind, "done")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
