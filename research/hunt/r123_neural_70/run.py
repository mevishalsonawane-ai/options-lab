"""R123 runner.

    python research/hunt/r123_neural_70/run.py build      # design rows (inputs, labels, trade outcomes)
    python research/hunt/r123_neural_70/run.py wf         # walk-forward F1-F4: models, rules, twins, gates 1-4
    python research/hunt/r123_neural_70/run.py holdout    # the ONE holdout read (refused after the first)

Caches (rows, sequences, predictions, model weights) go to $R123_OUT; small JSON results to results/.
"""
from __future__ import annotations

import json
import pickle
import sys
import time
from datetime import timedelta
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r123cfg as K  # noqa: E402
import r123data as D  # noqa: E402
import r123net as M  # noqa: E402
import r123rules as R  # noqa: E402

NJ = M.NJ


def log(*a, **k):
    print(time.strftime("%H:%M:%S"), *a, **{"flush": True, **k})


def daily_for(last_day) -> dict:
    """N2's slow-encoder daily bars per index, from the same minute grids the build used (same sessions, in order)."""
    vix = D.load_grid("INDIAVIX", last_day)
    return {u: M.daily_bars(D.load_grid(u, last_day), vix) for u in K.UNDS}


def fit_predict(train: pd.DataFrame, test: pd.DataFrame, S: dict, daily: dict, tag: str, epochs=K.EPOCHS,
                seeds=K.SEEDS):
    """Both networks (N1, N2), the base rate and the trees, fitted on `train` only. Returns {net: preds}, base,
    trees, the training 'neither' means."""
    import torch
    cols = list(D.TAB_COLS)
    log(f"{tag}: train {len(train)} rows / {train.day.nunique()} days, test {len(test)} rows")
    preds = {}
    K.OUT.mkdir(parents=True, exist_ok=True)
    for arch in K.NETS:
        log(f"{tag}: fitting {arch}")
        net = M.NetModel(seeds=seeds, epochs=epochs, log=log, arch=arch, daily=daily).fit(train, M.SeqStore(S), cols)
        preds[arch] = net.predict(test, M.SeqStore(S), M.DailyStore(daily) if arch == "N2" else None)
        torch.save(dict(states=[n.state_dict() for n in net.nets], temps=getattr(net, "temps", None)),
                   K.OUT / f"net_{arch}_{tag}.pt")
        np.savez(K.OUT / f"preds_{arch}_{tag}.npz", **{k: v for k, v in preds[arch].items()})
    pb = M.BaseRate().fit(train).predict(test)
    pt = M.Trees().fit(train, cols).predict(test)
    np.savez(K.OUT / f"preds_base_trees_{tag}.npz", base_probs=pb["probs"], tree_pwin=pt["pwin"],
             tree_range=pt["range"])
    return preds, pb, pt, R.neither_means(train)


def model_tests(P: pd.DataFrame, folds: list[str] | None) -> list[dict]:
    """Network vs the best baseline per cell (PREREG section 5). P carries nn_/base_/tree_ columns and outcomes."""
    out = []
    for j in range(NJ):
        c = P[f"cls_{j}"].values
        ok = c >= 0
        y = (c[ok] == K.WIN).astype(float)
        nn = R.brier(P[f"pw_{j}"].values[ok], y)
        bs = {"base": R.brier(P[f"base_pw_{j}"].values[ok], y), "trees": R.brier(P[f"tree_pw_{j}"].values[ok], y)}
        best = min(bs, key=lambda k: bs[k].mean())
        diff = nn - bs[best]
        m, lo, hi, p = R.session_boot_ci(P.day.values[ok], -diff)
        rec = dict(cell=f"{K.CELLS[j // 2]} {K.SIDES[j % 2]}", j=j, metric="brier", n=int(ok.sum()),
                   days=int(P.day[ok].nunique()), base_rate=float(y.mean()), nn=float(nn.mean()),
                   best=best, best_score=float(bs[best].mean()), nn_minus_best=float(diff.mean()),
                   ci=[-hi, -lo], p=p)
        if folds:
            fo = P.fold.values[ok]
            rec["folds_better"] = int(sum(diff[fo == f].mean() < 0 for f in folds if (fo == f).any()))
        out.append(rec)
    for k, h in enumerate(K.HS):
        y = P[f"y_range_{h}"].values
        ok = np.isfinite(y)
        se_nn = (P[f"nn_rg_{h}"].values[ok] - y[ok]) ** 2
        se_tr = (P[f"tree_rg_{h}"].values[ok] - y[ok]) ** 2
        m, lo, hi, p = R.session_boot_ci(P.day.values[ok], se_tr - se_nn)
        rec = dict(cell=f"range {h}", metric="r2", n=int(ok.sum()), days=int(P.day[ok].nunique()),
                   nn=float(R.r2(P[f"nn_rg_{h}"].values[ok], y[ok])), best="trees",
                   best_score=float(R.r2(P[f"tree_rg_{h}"].values[ok], y[ok])),
                   base_r2=float(R.r2(P[f"base_rg_{h}"].values[ok], y[ok])), ci_mse_gain=[lo, hi], p=p)
        if folds:
            fo = P.fold.values[ok]
            rec["folds_better"] = int(sum((se_nn[fo == f].mean() < se_tr[fo == f].mean()) for f in folds
                                          if (fo == f).any()))
        out.append(rec)
    for k, h in enumerate(K.HS):
        y = P[f"y_dir_{h}"].values
        ok = np.isfinite(y)
        ln = R.logloss(P[f"pdir{h}"].values[ok], y[ok])
        lb = R.logloss(P[f"base_dir_{h}"].values[ok], y[ok])
        m, lo, hi, p = R.session_boot_ci(P.day.values[ok], lb - ln)
        from sklearn.metrics import roc_auc_score
        rec = dict(cell=f"direction {h}", metric="logloss", n=int(ok.sum()), days=int(P.day[ok].nunique()),
                   nn=float(ln.mean()), best="base", best_score=float(lb.mean()),
                   auc=float(roc_auc_score(y[ok], P[f"pdir{h}"].values[ok])), ci_gain=[lo, hi], p=p)
        if folds:
            fo = P.fold.values[ok]
            rec["folds_better"] = int(sum((ln[fo == f].mean() < lb[fo == f].mean()) for f in folds if (fo == f).any()))
        out.append(rec)
    q = R.bh([r["p"] for r in out])
    for r, qq in zip(out, q):
        r["q"] = qq
    return out


def with_preds(test: pd.DataFrame, pn, pb, pt, mn) -> pd.DataFrame:
    P = R.attach(test, pn, mn)
    extra = {}
    for j in range(NJ):
        extra[f"base_pw_{j}"] = pb["probs"][:, j, 0]
        extra[f"tree_pw_{j}"] = pt["pwin"][:, j]
    for k, h in enumerate(K.HS):
        extra[f"nn_rg_{h}"] = pn["range"][:, k]
        extra[f"tree_rg_{h}"] = pt["range"][:, k]
        extra[f"base_rg_{h}"] = pb["range"][:, k]
        extra[f"base_dir_{h}"] = pb["dir"][:, k]
    if "gate" in pn:
        for k, name in enumerate(M.SPECIALISTS):
            extra[f"gate_{name}"] = pn["gate"][:, k]
    return pd.concat([P.reset_index(drop=True), pd.DataFrame(extra)], axis=1)


def rule_results(PN: dict, sessions_by: dict, folds: list[str] | None) -> dict:
    """Every rule with every network (10 tests); BH over all 10 (Amendment 1)."""
    out = {}
    for arch, P in PN.items():
        pools = R.twin_pools(P)
        for rule in K.RULE_IDS:
            rid = f"{arch}-{rule}"
            T = R.trades(P, rule)
            sess = sorted(set().union(*sessions_by.values()))
            st = R.stats(T, sess)
            tw = R.twins(T, pools)
            st["twin_mean"] = float(tw.mean())
            st["twin_p95"] = float(np.percentile(tw, 95))
            st["p_twins"] = R.p_vs(float(T.net.sum()), tw) if len(T) else 1.0
            if len(T):
                sw = R.side_twins(T, P)
                st["p_side_twins"] = R.p_vs(float(T.net.sum()), sw)
                st["side_twin_mean"] = float(sw.mean())
            if arch == "N2":
                st["abstained_rows"] = int(sum(P[f"abst_{j}"].sum() for j in range(NJ)))
            if folds:
                fo = {}
                for f in folds:
                    Tf = T[T.day.isin(sessions_by[f])]
                    fo[f] = dict(n=len(Tf), net=float(Tf.net.sum()),
                                 win=float((Tf.net > 0).mean()) if len(Tf) else None,
                                 avg=float(Tf.net.mean()) if len(Tf) else None)
                st["folds"] = fo
                st["folds_positive"] = int(sum(v["net"] > 0 for v in fo.values()))
            out[rid] = st
            T.to_parquet(K.OUT / f"trades_{rid}_{'wf' if folds else 'holdout'}.parquet")
            log(rid, {k: st.get(k) for k in ("n", "win_rate", "avg_net", "avg_net_x2", "per_session", "p_twins")})
    q = R.bh([out[r]["p_twins"] for r in out])
    for r, qq in zip(list(out), q):
        out[r]["q"] = qq
    return out


def all_model_tests(PN: dict, folds) -> list:
    out = []
    for arch, P in PN.items():
        for r in model_tests(P, folds):
            r["net"] = arch
            out.append(r)
    q = R.bh([r["p"] for r in out])
    for r, qq in zip(out, q):
        r["q"] = qq
    return out


def gate_summary(PN: dict) -> dict:
    """N2's committee: the average weight it gave each specialist (reported only)."""
    P = PN.get("N2")
    if P is None or "gate_size" not in P:
        return {}
    return {k: float(P[f"gate_{k}"].mean()) for k in M.SPECIALISTS}


def cmd_build():
    D.save("design", log=log)


def cmd_wf():
    F, S = D.load("design")
    F["fold"] = [K.fold_of(d) for d in F.day]
    daily = daily_for(K.DESIGN[1])
    parts = {a: [] for a in K.NETS}
    for f, (a, b) in K.FOLDS.items():
        train = F[F.day < a - timedelta(days=K.FOLD_EMBARGO_DAYS)]
        test = F[F.fold == f]
        preds, pb, pt, mn = fit_predict(train, test, S, daily, f"wf_{f}")
        for arch in K.NETS:
            parts[arch].append(with_preds(test, preds[arch], pb, pt, mn))
    for arch in K.NETS:
        pd.concat(parts[arch], ignore_index=True).to_parquet(K.OUT / f"wf_rows_preds_{arch}.parquet")
    finish_wf()


def finish_wf():
    PN = {a: pd.read_parquet(K.OUT / f"wf_rows_preds_{a}.parquet") for a in K.NETS}
    P0 = PN[K.NETS[0]]
    sessions_by = {f: set(P0.day[P0.fold == f].unique()) for f in K.FOLDS}
    folds = list(K.FOLDS)
    res = dict(rules=rule_results(PN, sessions_by, folds), models=all_model_tests(PN, folds),
               calibration={f"{a}-{r}": R.calibration(PN[a], K.RULES[r]["cell"]) for a in K.NETS for r in ("S1", "S3")},
               committee_gate=gate_summary(PN), rows=len(P0), sessions=len(set().union(*sessions_by.values())))
    for r, st in res["rules"].items():
        st["gates"] = dict(n=st["n"] >= K.G_WF_N, net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0,
                           folds=st.get("folds_positive", 0) >= K.G_WF_FOLDS, q=st["q"] <= K.G_Q)
        st["passes_wf"] = all(st["gates"].values())
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "wf.json").write_text(json.dumps(res, indent=1, default=str))
    log("wf written", {r: st["passes_wf"] for r, st in res["rules"].items()})


def cmd_holdout():
    if not (K.RESULTS / "wf.json").exists():
        raise SystemExit("the walk-forward results must be committed before the holdout read")
    D.holdout_gate("start")
    D.save("holdout", log=log)
    Fh, Sh = D.load("holdout")
    Fd, _ = D.load("design")
    Fd = rekey(Fd, Sh)
    daily = daily_for(K.HOLDOUT[1])
    preds, pb, pt, mn = fit_predict(Fd, Fh, Sh, daily, "holdout")
    for arch in K.NETS:
        with_preds(Fh, preds[arch], pb, pt, mn).to_parquet(K.OUT / f"holdout_rows_preds_{arch}.parquet")
    finish_holdout()
    D.holdout_gate("done")


def rekey(Fd: pd.DataFrame, Sh: dict) -> pd.DataFrame:
    """Design rows keep their gpos in the holdout build: both builds put session i of an index at minutes i*375..,
    and the holdout build's sessions start on the same first day (checked: the design minutes are a prefix)."""
    Sd = dict(np.load(K.OUT / "seq_design.npz"))
    for u in K.UNDS:
        n = len(Sd[u])
        if not np.array_equal(Sd[u], Sh[u][:n], equal_nan=True):
            raise RuntimeError(f"{u}: the holdout build's minutes differ from the design build's")
    return Fd


def finish_holdout():
    PN = {a: pd.read_parquet(K.OUT / f"holdout_rows_preds_{a}.parquet") for a in K.NETS}
    sess = sorted(PN[K.NETS[0]].day.unique())
    res = dict(rules=rule_results(PN, {"all": set(sess)}, None), models=all_model_tests(PN, None),
               calibration={f"{a}-{r}": R.calibration(PN[a], K.RULES[r]["cell"]) for a in K.NETS for r in ("S1", "S3")},
               committee_gate=gate_summary(PN), rows=len(PN[K.NETS[0]]), sessions=len(sess))
    wf = json.loads((K.RESULTS / "wf.json").read_text())
    for r, st in res["rules"].items():
        st["gates"] = dict(wf=wf["rules"][r]["passes_wf"], n=st["n"] >= K.G_HO_N,
                           net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0, q=st["q"] <= K.G_Q,
                           account=st.get("max_dd", -1e9) > K.ACCOUNT_DD
                           and st.get("worst_month", -1e9) > K.ACCOUNT_MONTH)
        st["passes"] = all(st["gates"].values())
        st["boss_target_strict"] = bool(st["n"] and st["win_rate"] >= K.TARGET_WIN and st["avg_net"] >= K.TARGET_AVG)
        # lenient: winners net about Rs 1,000, >= 65% win, and the average trade still positive (PREREG section 0)
        st["boss_target_lenient"] = bool(st["n"] and st["win_rate"] >= K.TARGET_WIN and st["avg_net"] > 0
                                         and st["avg_win"] >= K.TARGET_AVG)
    (K.RESULTS / "holdout.json").write_text(json.dumps(res, indent=1, default=str))
    log("holdout written", {r: st["passes"] for r, st in res["rules"].items()})


if __name__ == "__main__":
    cmd = sys.argv[1]
    {"build": cmd_build, "wf": cmd_wf, "finish_wf": finish_wf, "holdout": cmd_holdout,
     "finish_holdout": finish_holdout}[cmd]()
