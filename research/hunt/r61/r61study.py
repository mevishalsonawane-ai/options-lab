"""R61 study: Solo 3.0 (PREREG.md). Research only.

    python research/hunt/r61/r61data.py build && python research/hunt/r61/r61data.py context
    python research/hunt/r61/r61study.py design     # candidates, labels, features, purged walk-forward on DESIGN only
    python research/hunt/r61/r61study.py dryrun     # the final pipeline on the last 3 design months (crash check)
    python research/hunt/r61/r61study.py final      # the ONE final-test read (refuses a second)

Writes results/design.json, results/final.json and results/models/*.json (small files only).
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import KFold
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

import r61cfg as K
import r57data
import r57model as M57
import r57setups as S57
import r57study as R57
import r61data
import r61feat as F

SETUPS = ("S1", "S2", "S3", "S4")


# ------------------------------------------------------------------------------------------------ models
def make(kind: str):
    if kind == "logit":
        return Pipeline([("imp", SimpleImputer(strategy="median", keep_empty_features=True)),
                         ("sc", StandardScaler()), ("lr", LogisticRegression(C=0.1, max_iter=5000))])
    if kind == "l1":
        return Pipeline([("imp", SimpleImputer(strategy="median", keep_empty_features=True)),
                         ("sc", StandardScaler()),
                         ("lr", LogisticRegression(C=0.05, penalty="elasticnet", l1_ratio=1.0, solver="saga", max_iter=20000,
                                                   random_state=K.SEED))])
    if kind == "gbc":
        return HistGradientBoostingClassifier(max_depth=3, max_iter=150, learning_rate=0.03, min_samples_leaf=80,
                                              l2_regularization=5.0, early_stopping=False, random_state=K.SEED)
    raise ValueError(kind)


def fit(kind, X, y):
    m = make(kind)
    m.fit(X, y)
    return m


def predict(m, X) -> np.ndarray:
    return m.predict_proba(X)[:, 1] if len(X) else np.zeros(0)


def auc(y, s):
    y, s = np.asarray(y), np.asarray(s, float)
    ok = np.isfinite(s)
    if ok.sum() < 2 or len(np.unique(y[ok])) < 2:
        return None
    return round(float(roc_auc_score(y[ok], s[ok])), 4)


def walk(days, X, y, kind, months, cal, keep_models=False):
    """Purged walk-forward, monthly refits, 1-session embargo (R57 train_mask). Returns scores, thresholds (70th
    percentile of the fold's training scores) and, if asked, {month: model}."""
    sc = np.full(len(days), np.nan)
    thr = np.full(len(days), np.nan)
    models = {}
    for (yy, mm) in months:
        lo, hi = M57.month_start(yy, mm), M57.month_end(yy, mm)
        te = np.array([lo <= d <= hi for d in days], bool)
        if not te.any():
            continue
        tr = M57.train_mask(days, days, lo, cal)
        if tr.sum() < 50 or len(np.unique(y[tr])) < 2:
            continue
        m = fit(kind, X[tr], y[tr])
        sc[te] = predict(m, X[te])
        thr[te] = np.percentile(predict(m, X[tr]), 70)
        if keep_models:
            models[(yy, mm)] = m
    return sc, thr, models


def random_split(X, y, kind) -> np.ndarray:
    s = np.full(len(y), np.nan)
    for tr, te in KFold(5, shuffle=True, random_state=K.SEED).split(X):
        if len(np.unique(y[tr])) >= 2:
            s[te] = predict(fit(kind, X[tr], y[tr]), X[te])
    return s


# ------------------------------------------------------------------------------------------------ rows
def calendar() -> list:
    return R57.calendar()


def rows_for(name: str, lo: date, hi: date) -> list:
    path = K.OUT / f"{name}.pkl"
    if path.exists():
        with open(path, "rb") as f:
            return pickle.load(f)
    for u in K.UNDS:
        r57data.load(u)
    rows = F.add(R57.a_rows(S57.candidates(lo, hi, setups=SETUPS)))
    with open(path, "wb") as f:
        pickle.dump(rows, f, protocol=pickle.HIGHEST_PROTOCOL)
    return rows


def design_matrix(rows, names):
    return M57.matrix([r["f"] for r in rows], names)


# ------------------------------------------------------------------------------------------------ one variant
def run(kind: str, names: list, rows: list, months: list, cal: list, keep_models=False) -> dict:
    X = design_matrix(rows, names)
    y = np.array([r["y_win"] for r in rows])
    days = np.array([r["day"] for r in rows], dtype=object)
    sc, thr, models = walk(days, X, y, kind, months, cal, keep_models)
    trades = R57.select_a(rows, sc, thr, "logit")
    tested = np.isfinite(sc)
    return dict(trades=trades, auc=auc(y[tested], sc[tested]), tested=int(tested.sum()), X=X, y=y, models=models)


def twin_p(trades: list, n: int = K.TWINS) -> tuple:
    if not trades:
        return None, None
    rng = np.random.default_rng(K.SEED)
    lo, hi = R57.TWIN_WIN["A"]
    tot = S57.twin_totals(trades, n, rng, lo, hi)
    act = sum(t["net"] for t in trades)
    return round((1 + int((tot >= act).sum())) / (n + 1), 4), round(float(tot.mean()))


def selection_p(trades: list, rows: list, n: int = K.TWINS):
    pool = defaultdict(list)
    for r in rows:
        pool[r["day"]].append(r["net"])
    if not trades:
        return None
    rng = np.random.default_rng(K.SEED + 1)
    tot = np.zeros(n)
    for t in trades:
        x = np.array(pool[t["day"]])
        tot += x[rng.integers(0, len(x), size=n)]
    act = sum(t["net"] for t in trades)
    return round((1 + int((tot >= act).sum())) / (n + 1), 4)


def paired_boot(a: list, b: list, sessions: list, n: int = K.BOOT, seed: int = K.SEED) -> float | None:
    """One-sided p that trades `a` beat trades `b` on the same sessions: resample the sessions with replacement; p =
    share of resamples whose summed daily difference (a - b) is <= 0."""
    if not sessions:
        return None
    da, db = defaultdict(float), defaultdict(float)
    for t in a:
        da[t["day"]] += t["net"]
    for t in b:
        db[t["day"]] += t["net"]
    diff = np.array([da[d] - db[d] for d in sessions])
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, len(diff), size=(n, len(diff)))
    s = diff[idx].sum(1)
    return round(float((1 + (s <= 0).sum()) / (n + 1)), 4)


def ex_index(trades: list, und: str = "MIDCPNIFTY") -> float:
    return round(sum(t["net"] for t in trades if t["und"] != und))


# ------------------------------------------------------------------------------------------------ ablations
def ablations(rows, months, cal) -> dict:
    """V2 minus one concept group at a time, and A2 plus one new feature at a time (logistic)."""
    full = F.names(("CH", "GK", "CX"))
    base = run("logit", full, rows, months, cal)
    ref = dict(auc=base["auc"], net=R57.stats(base["trades"]).get("net", 0))
    out = dict(V2=ref, drop={}, add={})
    for g, cols in K.GROUPS.items():
        r = run("logit", [n for n in full if n not in cols], rows, months, cal)
        st = R57.stats(r["trades"])
        out["drop"][g] = dict(auc=r["auc"], d_auc=_d(r["auc"], ref["auc"]), net=st.get("net", 0),
                              d_net=st.get("net", 0) - ref["net"])
    a2 = run("logit", F.A2, rows, months, cal)
    a2_auc = a2["auc"]
    for n in K.NEW:
        r = run("logit", F.A2 + [n], rows, months, cal)
        out["add"][n] = dict(auc=r["auc"], d_auc=_d(r["auc"], a2_auc))
    out["A2_auc"] = a2_auc
    return out


def _d(a, b):
    return None if a is None or b is None else round(a - b, 4)


def l1_coefs(rows, months, cal) -> dict:
    """The V7 (L1) model of the last fold: non-zero standardised coefficients, largest first."""
    names = F.names(("CH", "GK", "CX"))
    r = run("l1", names, rows, months[-1:], cal, keep_models=True)
    if not r["models"]:
        return {}
    m = list(r["models"].values())[-1]
    w = m.named_steps["lr"].coef_[0]
    nz = {n: round(float(x), 4) for n, x in zip(names, w) if abs(x) > 1e-9}
    return dict(sorted(nz.items(), key=lambda kv: -abs(kv[1])))


# ------------------------------------------------------------------------------------------------ steps
def evaluate(rows: list, months: list, cal: list, lo: date, hi: date, design_res: dict | None) -> tuple:
    out = dict(variants={})
    trades = {}
    sessions = sorted({d for d in cal if lo <= d <= hi})
    for vid, v in K.VARIANTS.items():
        r = run(v["kind"], F.names(v["blocks"]), rows, months, cal)
        tr = r["trades"]
        trades[vid] = tr
        p, tm = twin_p(tr)
        out["variants"][vid] = dict(**v, test=R57.stats(tr), auc_test=r["auc"], p=p, twin_mean=tm,
                                    p_selection=selection_p(tr, [x for x in rows if lo <= x["day"] <= hi]),
                                    ex_midcp=ex_index(tr))
        print(vid, json.dumps(out["variants"][vid], default=str)[:300], flush=True)
    ids = list(K.VARIANTS)
    qs = R57.bh([out["variants"][i]["p"] if out["variants"][i]["p"] is not None else 1.0 for i in ids])
    for i, q in zip(ids, qs):
        r = out["variants"][i]
        r["q"] = round(float(q), 4)
        f = r["test"]
        wf = design_res["variants"][i]["wf"]["net"] if design_res else 1
        fails = []
        if f["net"] <= 0:
            fails.append("final net <= 0")
        if r["p"] is None or r["p"] >= K.GATE_P:
            fails.append("p >= 0.05")
        if r["q"] > K.GATE_Q:
            fails.append("q > 0.10")
        if f["trades"] < K.GATE_MIN_TRADES:
            fails.append("fewer than 40 trades")
        if f["trades"] and f.get("ex_top5", 0) <= 0:
            fails.append("top-5 >= net")
        if wf <= 0:
            fails.append("design walk-forward <= 0")
        r["verdict"] = "PASS" if not fails else "FAIL: " + "; ".join(fails)
    out["replace"] = replace_decision(out, trades, sessions, design_res)
    ps_ = R57.plain_solo(rows, lo, hi)
    out["reference"] = dict(plain_solo=R57.stats(ps_))
    for s in SETUPS:
        out["reference"][f"{s}_all"] = R57.stats(R57.in_months([r for r in rows if r["setup"] == s], months))
    return out, trades


def replace_decision(out: dict, trades: dict, sessions: list, design_res: dict | None) -> dict:
    """PREREG section 8: the passing V2-V7 with the best design walk-forward net, against V1 on the same sessions."""
    passing = [v for v in K.VARIANTS if v != "V1" and out["variants"][v]["verdict"] == "PASS"]
    if not passing:
        return dict(candidate=None, replaces=False, why="no V2-V7 variant passed the gates")
    wf = {v: (design_res["variants"][v]["wf"]["net"] if design_res else 0) for v in passing}
    c = max(passing, key=lambda v: (wf[v], v))
    a, b = out["variants"][c], out["variants"]["V1"]
    checks = dict(net=a["test"]["net"] > b["test"]["net"],
                  auc=(a["auc_test"] or 0) > (b["auc_test"] or 0),
                  boot_p=paired_boot(trades[c], trades["V1"], sessions),
                  ex_midcp=a["ex_midcp"] > b["ex_midcp"])
    checks["boot"] = checks["boot_p"] is not None and checks["boot_p"] < K.REPLACE_BOOT_P
    ok = checks["net"] and checks["auc"] and checks["boot"] and checks["ex_midcp"]
    return dict(candidate=c, replaces=bool(ok), checks=checks)


def design():
    for u in K.UNDS:
        r57data.load(u)
    cal = [d for d in calendar() if d <= K.DESIGN_END]
    rows = rows_for("design_rows", K.START, K.DESIGN_END)
    out = dict(rows=len(rows), variants={})
    for vid, v in K.VARIANTS.items():
        r = run(v["kind"], F.names(v["blocks"]), rows, K.WF_MONTHS, cal)
        out["variants"][vid] = dict(**v, wf=R57.stats(r["trades"]), auc_wf=r["auc"],
                                    auc_random=auc(r["y"], random_split(r["X"], r["y"], v["kind"])))
        print(vid, json.dumps(out["variants"][vid], default=str)[:300], flush=True)
    out["ablation"] = ablations(rows, K.WF_MONTHS, cal)
    out["l1_coefs"] = l1_coefs(rows, K.WF_MONTHS, cal)
    out["coverage"] = coverage(rows)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))


def coverage(rows: list) -> dict:
    """Share of candidate rows with a value, per R61 feature (missing chain minutes, ranks without history)."""
    return {n: round(float(np.mean([np.isfinite(r["f"].get(n, np.nan)) for r in rows])), 3) for n in K.NEW} \
        if rows else {}


def dryrun():
    for u in K.UNDS:
        r57data.load(u)
    cal = [d for d in calendar() if d <= K.DESIGN_END]
    rows = rows_for("design_rows", K.START, K.DESIGN_END)
    months = K.WF_MONTHS[-3:]
    design_res = json.loads((K.RESULTS / "design.json").read_text())
    out, _ = evaluate(rows, months, cal, M57.month_start(*months[0]), K.DESIGN_END, design_res)
    print({k: v["verdict"] for k, v in out["variants"].items()}, out["replace"])


def final():
    if K.FINAL_FLAG.exists():
        raise SystemExit("R61: the final test was already read (results/FINAL_READ). One read only.")
    design_res = json.loads((K.RESULTS / "design.json").read_text())
    K.FINAL_FLAG.write_text(f"final test read on {date.today()}\n")
    S57.open_final()
    for u in K.UNDS:
        r57data.load(u)
    cal = calendar()
    rows = rows_for("design_rows", K.START, K.DESIGN_END) + rows_for("final_rows", K.FINAL_START, K.END)
    out, trades = evaluate(rows, K.FINAL_MONTHS, cal, K.FINAL_START, K.END, design_res)
    out["ablation"] = ablations(rows, K.FINAL_MONTHS, cal)
    out["coverage_final"] = coverage([r for r in rows if r["day"] >= K.FINAL_START])
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    with open(K.OUT / "final_trades.pkl", "wb") as f:
        pickle.dump(trades, f)
    export = sorted({"V1", "V2"} | {v for v, r in out["variants"].items() if r["verdict"] == "PASS"})
    print("replace:", out["replace"], "parity gaps:", export_models(export, rows, cal))


def export_models(vids: list, rows: list, cal: list, out_dir=None) -> dict:
    """Deployment model of each variant (fitted on every labelled row) as JSON + 25 golden vectors."""
    import r61eval
    d = K.RESULTS / "models" if out_dir is None else out_dir
    d.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(K.SEED)
    gaps = {}
    for vid in vids:
        v = K.VARIANTS[vid]
        names = F.names(v["blocks"])
        X = design_matrix(rows, names)
        y = np.array([r["y_win"] for r in rows])
        m = fit(v["kind"], X, y)
        thr = float(np.percentile(predict(m, X), 70))
        kind57 = "gbc" if v["kind"] == "gbc" else "logit"
        js = M57.export(kind57, m, names, thr, ">=", dict(
            take="first candidate of the day (time order; same minute: highest score) with score >= threshold; then "
                 "nothing more that day", setups={k: S57.SOURCE[k] for k in SETUPS},
            feature_doc={n: F.DOC[n] for n in names if n in F.DOC}, trained_on=f"{min(cal)} - {max(cal)}"))
        (d / f"{vid}.json").write_text(json.dumps(js, separators=(",", ":")))
        idx = rng.choice(len(X), size=min(25, len(X)), replace=False)
        sk = predict(m, X[idx])
        gold = [dict(inputs=[None if not np.isfinite(x) else float(x) for x in X[i]], score=float(s))
                for i, s in zip(idx, sk)]
        (d / f"{vid}_golden.json").write_text(json.dumps(dict(model=f"{vid}.json", rows=gold), indent=0))
        gaps[vid] = max(abs(r61eval.score(js, g["inputs"]) - g["score"]) for g in gold)
    return gaps


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"design": design, "dryrun": dryrun, "final": final}.get(cmd, lambda: print(__doc__))()
