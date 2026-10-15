"""R61b study: trap / fake-breakout / order-flow checks for Solo (PREREG.md). Research only.

    R57_OUT=<R61B_OUT> python research/hunt/r57/r57data.py build
    python research/hunt/r61b/r61bdata.py build
    python research/hunt/r61b/r61bstudy.py design     # candidates, labels, TR features, walk-forward on DESIGN only
    python research/hunt/r61b/r61bstudy.py dryrun     # the final pipeline on the last 3 design months (crash check)
    python research/hunt/r61b/r61bstudy.py final      # the ONE final read (refuses a second)

Writes results/design.json, results/final.json and, for a passing model, results/models/*.json (small files only).
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np  # noqa: E402

import r61bcfg as K  # noqa: E402
import r57data  # noqa: E402
import r57model as M57  # noqa: E402
import r57setups as S57  # noqa: E402
import r57study as R57  # noqa: E402
import r61feat as F61  # noqa: E402
import r61study as ST  # noqa: E402
import r61bdata  # noqa: E402
import r61beval as EV  # noqa: E402

A2 = F61.A2
SETUPS = ("S1", "S2", "S3", "S4")


def names(v: dict) -> list:
    return A2 + list(v["extra"])


# ------------------------------------------------------------------------------------------------ rows
def rows_for(name: str, lo: date, hi: date) -> list:
    path = K.OUT / f"{name}.pkl"
    if path.exists():
        with open(path, "rb") as f:
            return pickle.load(f)
    for u in K.UNDS:
        r57data.load(u)
    rows = r61bdata.add(R57.a_rows(S57.candidates(lo, hi, setups=SETUPS)))
    with open(path, "wb") as f:
        pickle.dump(rows, f, protocol=pickle.HIGHEST_PROTOCOL)
    return rows


# ------------------------------------------------------------------------------------------------ one variant
def run(v: dict, rows: list, months: list, cal: list, keep_models=False) -> dict:
    """Walk-forward scores of the variant's model; a skip rule removes flagged candidates before A2's take rule."""
    X = M57.matrix([r["f"] for r in rows], names(v))
    y = np.array([r["y_win"] for r in rows])
    days = np.array([r["day"] for r in rows], dtype=object)
    sc, thr, models = ST.walk(days, X, y, v["kind"], months, cal, keep_models)
    keep = np.ones(len(rows), bool)
    if v["skip"]:
        keep = np.array([not EV.skip(v["skip"], r["f"]) for r in rows])
    sel = np.where(keep, sc, np.nan)
    trades = R57.select_a(rows, sel, thr, "logit")
    tested = np.isfinite(sel)
    return dict(trades=trades, auc=ST.auc(y[tested], sel[tested]), tested=int(tested.sum()),
                skipped=int((np.isfinite(sc) & ~keep).sum()), X=X, y=y, models=models)


def summary(trades: list) -> dict:
    st = R57.stats(trades)
    mid = st.get("per_index", {}).get("MIDCPNIFTY", {}).get("net", 0)
    st["ex_midcp"] = ST.ex_index(trades)
    st["midcp_share"] = round(mid / st["net"], 3) if st.get("net", 0) > 0 else None
    return st


# ------------------------------------------------------------------------------------------------ ablations
def ablations(rows, months, cal) -> dict:
    m1 = dict(kind="logit", extra=tuple(K.TR), skip=None)
    base = run(m1, rows, months, cal)
    ref = dict(auc=base["auc"], net=R57.stats(base["trades"]).get("net", 0))
    out = dict(M1=ref, drop={}, add={})
    for g, cols in K.GROUPS.items():
        r = run(dict(m1, extra=tuple(n for n in K.TR if n not in cols)), rows, months, cal)
        net = R57.stats(r["trades"]).get("net", 0)
        out["drop"][g] = dict(auc=r["auc"], d_auc=ST._d(r["auc"], ref["auc"]), net=net, d_net=net - ref["net"])
    a2 = run(dict(m1, extra=()), rows, months, cal)
    out["A2"] = dict(auc=a2["auc"], net=R57.stats(a2["trades"]).get("net", 0))
    for n in K.TR:
        r = run(dict(m1, extra=(n,)), rows, months, cal)
        net = R57.stats(r["trades"]).get("net", 0)
        out["add"][n] = dict(auc=r["auc"], d_auc=ST._d(r["auc"], a2["auc"]), net=net, d_net=net - out["A2"]["net"])
    t0 = run(K.REFERENCE["T0"], rows, months, cal)
    out["T0"] = dict(auc=t0["auc"], test=summary(t0["trades"]))
    return out


def flag_table(rows: list) -> dict:
    """Descriptive: per setup, held-contract win rate and mean net with each flag / filter on vs off (counts)."""
    out = {}
    tests = {n: (lambda f, n=n: EV.ok(f.get(n)) and f[n] == 1.0) for n in K.TRAPS + K.SWEEPS}
    tests.update({r: (lambda f, r=r: EV.skip(r, f)) for r in ("F1", "F2", "F3", "F4")})
    for name, fn in tests.items():
        res = {}
        for s in SETUPS + ("all",):
            sub = [r for r in rows if s == "all" or r["setup"] == s]
            on = [r for r in sub if fn(r["f"])]
            off = [r for r in sub if not fn(r["f"])]
            res[s] = {k: dict(n=len(x), win=round(float(np.mean([r["y_win"] for r in x])), 3) if x else None,
                              mean_net=round(float(np.mean([r["net"] for r in x]))) if x else None)
                      for k, x in (("on", on), ("off", off))}
        out[name] = res
    return out


def coverage(rows: list) -> dict:
    return {n: round(float(np.mean([EV.ok(r["f"].get(n)) for r in rows])), 3) for n in K.TR} if rows else {}


# ------------------------------------------------------------------------------------------------ evaluate
def evaluate(rows: list, months: list, cal: list, lo: date, hi: date, design_res: dict | None) -> tuple:
    out, trades = dict(variants={}), {}
    sessions = sorted({d for d in cal if lo <= d <= hi})
    win_rows = [x for x in rows if lo <= x["day"] <= hi]
    for vid, v in K.VARIANTS.items():
        r = run(v, rows, months, cal)
        tr = trades[vid] = r["trades"]
        p, tm = ST.twin_p(tr)
        out["variants"][vid] = dict(kind=v["kind"], extra=list(v["extra"]), skip=v["skip"], test=summary(tr),
                                    auc_test=r["auc"], skipped=r["skipped"], p=p, twin_mean=tm,
                                    p_selection=ST.selection_p(tr, win_rows), ex_midcp=ST.ex_index(tr))
        print(vid, json.dumps(out["variants"][vid], default=str)[:300], flush=True)
    ids = list(K.VARIANTS)
    qs = R57.bh([out["variants"][i]["p"] if out["variants"][i]["p"] is not None else 1.0 for i in ids])
    for i, q in zip(ids, qs):
        r = out["variants"][i]
        r["q"] = round(float(q), 4)
        f = r["test"]
        wf = design_res["variants"][i]["wf"]["net"] if design_res else 1
        fails = [m for c, m in ((f["net"] <= 0, "final net <= 0"),
                                (r["p"] is None or r["p"] >= K.GATE_P, "p >= 0.05"),
                                (r["q"] > K.GATE_Q, "q > 0.10"),
                                (f["trades"] < K.GATE_MIN_TRADES, "fewer than 40 trades"),
                                (bool(f["trades"]) and f.get("ex_top5", 0) <= 0, "top-5 >= net"),
                                (wf <= 0, "design walk-forward <= 0")) if c]
        r["verdict"] = "PASS" if not fails else "FAIL: " + "; ".join(fails)
    out["replace"] = replace_decision(out, trades, sessions, design_res)
    for s in SETUPS:
        out.setdefault("reference", {})[f"{s}_all"] = R57.stats(R57.in_months([r for r in rows if r["setup"] == s],
                                                                               months))
    return out, trades


def replace_decision(out: dict, trades: dict, sessions: list, design_res: dict | None) -> dict:
    """PREREG section 7: the passing F1-F4 / M1-M3 with the best design walk-forward net, against V1."""
    passing = [v for v in K.VARIANTS if v != "V1" and out["variants"][v]["verdict"] == "PASS"]
    if not passing:
        return dict(candidate=None, replaces=False, why="no F1-F4 / M1-M3 variant passed the gates")
    wf = {v: (design_res["variants"][v]["wf"]["net"] if design_res else 0) for v in passing}
    c = max(passing, key=lambda v: (wf[v], v))
    a, b = out["variants"][c], out["variants"]["V1"]
    checks = dict(net=a["test"]["net"] > b["test"]["net"], auc=(a["auc_test"] or 0) > (b["auc_test"] or 0),
                  boot_p=ST.paired_boot(trades[c], trades["V1"], sessions), ex_midcp=a["ex_midcp"] > b["ex_midcp"])
    checks["boot"] = checks["boot_p"] is not None and checks["boot_p"] < K.REPLACE_BOOT_P
    ok = checks["net"] and checks["auc"] and checks["boot"] and checks["ex_midcp"]
    return dict(candidate=c, replaces=bool(ok), checks=checks)


# ------------------------------------------------------------------------------------------------ steps
def design():
    for u in K.UNDS:
        r57data.load(u)
    cal = [d for d in ST.calendar() if d <= K.DESIGN_END]
    rows = rows_for("design_rows", K.START, K.DESIGN_END)
    out = dict(rows=len(rows), variants={})
    for vid, v in K.VARIANTS.items():
        r = run(v, rows, K.WF_MONTHS, cal)
        res = dict(kind=v["kind"], extra=list(v["extra"]), skip=v["skip"], wf=summary(r["trades"]), auc_wf=r["auc"],
                   skipped=r["skipped"])
        if not v["skip"]:
            res["auc_random"] = ST.auc(r["y"], ST.random_split(r["X"], r["y"], v["kind"]))
        out["variants"][vid] = res
        print(vid, json.dumps(res, default=str)[:300], flush=True)
    out["ablation"] = ablations(rows, K.WF_MONTHS, cal)
    out["flags"] = flag_table(rows)
    out["coverage"] = coverage(rows)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))


def dryrun():
    for u in K.UNDS:
        r57data.load(u)
    cal = [d for d in ST.calendar() if d <= K.DESIGN_END]
    rows = rows_for("design_rows", K.START, K.DESIGN_END)
    months = K.WF_MONTHS[-3:]
    design_res = json.loads((K.RESULTS / "design.json").read_text())
    out, _ = evaluate(rows, months, cal, M57.month_start(*months[0]), K.DESIGN_END, design_res)
    print({k: v["verdict"] for k, v in out["variants"].items()}, out["replace"])


def final():
    if K.FINAL_FLAG.exists():
        raise SystemExit("R61b: the final test was already read (results/FINAL_READ). One read only.")
    design_res = json.loads((K.RESULTS / "design.json").read_text())
    K.FINAL_FLAG.write_text(f"final test read on {date.today()}\n")
    S57.open_final()
    for u in K.UNDS:
        r57data.load(u)
    cal = ST.calendar()
    rows = rows_for("design_rows", K.START, K.DESIGN_END) + rows_for("final_rows", K.FINAL_START, K.END)
    out, trades = evaluate(rows, K.FINAL_MONTHS, cal, K.FINAL_START, K.END, design_res)
    out["ablation"] = ablations(rows, K.FINAL_MONTHS, cal)
    out["coverage_final"] = coverage([r for r in rows if r["day"] >= K.FINAL_START])
    out["final_rows"] = sum(r["day"] >= K.FINAL_START for r in rows)
    passing = [v for v, r in out["variants"].items() if r["verdict"] == "PASS" and K.VARIANTS[v]["extra"]]
    out["exported"] = export_models(passing, rows, cal) if passing else {}
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    with open(K.OUT / "final_trades.pkl", "wb") as f:
        pickle.dump(trades, f)
    print("replace:", out["replace"], "exported:", out["exported"])


def export_models(vids: list, rows: list, cal: list, out_dir=None) -> dict:
    """Deployment model of each passing meta-labeller (fitted on every labelled row) as JSON + 25 golden vectors.
    F1-F4 need no new model: they are V1's model plus r61beval.skip."""
    d = K.RESULTS / "models" if out_dir is None else out_dir
    d.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(K.SEED)
    gaps = {}
    for vid in vids:
        v = K.VARIANTS[vid]
        nm = names(v)
        X = M57.matrix([r["f"] for r in rows], nm)
        y = np.array([r["y_win"] for r in rows])
        m = ST.fit(v["kind"], X, y)
        thr = float(np.percentile(ST.predict(m, X), 70))
        js = M57.export("gbc" if v["kind"] == "gbc" else "logit", m, nm, thr, ">=", dict(
            take="first candidate of the day (time order; same minute: highest score) with score >= threshold; then "
                 "nothing more that day", setups={k: S57.SOURCE[k] for k in SETUPS},
            features="r61beval.features (TR block) + A2's 29", trained_on=f"{min(cal)} - {max(cal)}"))
        (d / f"{vid}.json").write_text(json.dumps(js, separators=(",", ":")))
        idx = rng.choice(len(X), size=min(25, len(X)), replace=False)
        sk = ST.predict(m, X[idx])
        gold = [dict(inputs=[None if not np.isfinite(x) else float(x) for x in X[i]], score=float(s))
                for i, s in zip(idx, sk)]
        (d / f"{vid}_golden.json").write_text(json.dumps(dict(model=f"{vid}.json", rows=gold), indent=0))
        gaps[vid] = max(abs(EV.score(js, g["inputs"]) - g["score"]) for g in gold)
    return gaps


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"design": design, "dryrun": dryrun, "final": final}.get(cmd, lambda: print(__doc__))()
