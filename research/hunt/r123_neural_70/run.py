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


def fit_predict(train: pd.DataFrame, test: pd.DataFrame, S: dict, tag: str, epochs=K.EPOCHS, seeds=K.SEEDS):
    cols = list(D.TAB_COLS)
    log(f"{tag}: train {len(train)} rows / {train.day.nunique()} days, test {len(test)} rows")
    net = M.NetModel(seeds=seeds, epochs=epochs, log=log).fit(train, M.SeqStore(S), cols)
    pn = net.predict(test, M.SeqStore(S))
    base = M.BaseRate().fit(train)
    pb = base.predict(test)
    trees = M.Trees().fit(train, cols)
    pt = trees.predict(test)
    mn = R.neither_means(train)
    K.OUT.mkdir(parents=True, exist_ok=True)
    import torch
    torch.save([n.state_dict() for n in net.nets], K.OUT / f"net_{tag}.pt")
    np.savez(K.OUT / f"preds_{tag}.npz", nn_probs=pn["probs"], nn_range=pn["range"], nn_dir=pn["dir"],
             base_probs=pb["probs"], base_range=pb["range"], base_dir=pb["dir"], tree_pwin=pt["pwin"],
             tree_range=pt["range"], day=test.day.astype(str).values, und=test.und.values, row=test.row.values)
    return pn, pb, pt, mn


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
    return pd.concat([P.reset_index(drop=True), pd.DataFrame(extra)], axis=1)


def rule_results(P: pd.DataFrame, sessions_by: dict, folds: list[str] | None) -> dict:
    pools = R.twin_pools(P)
    out = {}
    for rule in K.RULE_IDS:
        T = R.trades(P, rule)
        sess = sorted(set().union(*sessions_by.values())) if folds else sessions_by["all"]
        st = R.stats(T, sess)
        tw = R.twins(T, pools)
        st["twin_mean"] = float(tw.mean())
        st["twin_p95"] = float(np.percentile(tw, 95))
        st["p_twins"] = R.p_vs(float(T.net.sum()), tw) if len(T) else 1.0
        if len(T):
            sw = R.side_twins(T, P)
            st["p_side_twins"] = R.p_vs(float(T.net.sum()), sw)
            st["side_twin_mean"] = float(sw.mean())
        if folds:
            fo = {}
            for f in folds:
                Tf = T[T.day.isin(sessions_by[f])]
                fo[f] = dict(n=len(Tf), net=float(Tf.net.sum()), win=float((Tf.net > 0).mean()) if len(Tf) else None,
                             avg=float(Tf.net.mean()) if len(Tf) else None)
            st["folds"] = fo
            st["folds_positive"] = int(sum(v["net"] > 0 for v in fo.values()))
        out[rule] = st
        T.to_parquet(K.OUT / f"trades_{rule}_{'wf' if folds else 'holdout'}.parquet")
        log(rule, {k: st.get(k) for k in ("n", "win_rate", "avg_net", "avg_net_x2", "per_session", "p_twins")})
    q = R.bh([out[r]["p_twins"] for r in K.RULE_IDS])
    for r, qq in zip(K.RULE_IDS, q):
        out[r]["q"] = qq
    return out


def cmd_build():
    D.save("design", log=log)


def cmd_wf():
    F, S = D.load("design")
    F["fold"] = [K.fold_of(d) for d in F.day]
    parts, sessions_by = [], {}
    for f, (a, b) in K.FOLDS.items():
        train = F[F.day < a - timedelta(days=K.FOLD_EMBARGO_DAYS)]
        test = F[F.fold == f]
        pn, pb, pt, mn = fit_predict(train, test, S, f"wf_{f}")
        P = with_preds(test, pn, pb, pt, mn)
        parts.append(P)
        sessions_by[f] = set(test.day.unique())
    P = pd.concat(parts, ignore_index=True)
    P.to_parquet(K.OUT / "wf_rows_preds.parquet")
    finish_wf(P, sessions_by)


def finish_wf(P=None, sessions_by=None):
    if P is None:
        P = pd.read_parquet(K.OUT / "wf_rows_preds.parquet")
        sessions_by = {f: set(P.day[P.fold == f].unique()) for f in K.FOLDS}
    folds = list(K.FOLDS)
    res = dict(rules=rule_results(P, sessions_by, folds), models=model_tests(P, folds),
               calibration={r: R.calibration(P, K.RULES[r]["cell"]) for r in ("S1", "S3")},
               rows=len(P), sessions=len(set().union(*sessions_by.values())))
    for r in K.RULE_IDS:
        st = res["rules"][r]
        st["gates"] = dict(n=st["n"] >= K.G_WF_N, net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0,
                           folds=st.get("folds_positive", 0) >= K.G_WF_FOLDS, q=st["q"] <= K.G_Q)
        st["passes_wf"] = all(st["gates"].values())
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "wf.json").write_text(json.dumps(res, indent=1, default=str))
    log("wf written", {r: res["rules"][r]["passes_wf"] for r in K.RULE_IDS})


def cmd_holdout():
    D.holdout_gate("start")
    D.save("holdout", log=log)
    Fh, Sh = D.load("holdout")
    Fd, _ = D.load("design")
    # the holdout model trains on every design row; its sequences are read from the holdout build's arrays, which hold
    # the same minutes up to 15 Mar 2026 (rebuilt to 19 Oct), so design rows are re-keyed to that build
    Fd = rekey(Fd, Sh)
    pn, pb, pt, mn = fit_predict(Fd, Fh, Sh, "holdout")
    P = with_preds(Fh, pn, pb, pt, mn)
    P.to_parquet(K.OUT / "holdout_rows_preds.parquet")
    finish_holdout(P)
    D.holdout_gate("done")


def rekey(Fd: pd.DataFrame, Sh: dict) -> pd.DataFrame:
    """Design rows' gpos in the holdout build: both builds put session i of an index at minutes i*375.., and the
    holdout build's sessions are a superset that starts on the same first day (checked here)."""
    Sd = dict(np.load(K.OUT / "seq_design.npz"))
    for u in K.UNDS:
        n = len(Sd[u])
        if not np.array_equal(Sd[u], Sh[u][:n], equal_nan=True):
            raise RuntimeError(f"{u}: the holdout build's minutes differ from the design build's")
    return Fd


def finish_holdout(P=None):
    if P is None:
        P = pd.read_parquet(K.OUT / "holdout_rows_preds.parquet")
    sess = sorted(P.day.unique())
    res = dict(rules=rule_results(P, {"all": sess}, None), models=model_tests(P, None),
               calibration={r: R.calibration(P, K.RULES[r]["cell"]) for r in ("S1", "S3")},
               rows=len(P), sessions=len(sess))
    wf = json.loads((K.RESULTS / "wf.json").read_text())
    for r in K.RULE_IDS:
        st = res["rules"][r]
        st["gates"] = dict(wf=wf["rules"][r]["passes_wf"], n=st["n"] >= K.G_HO_N,
                           net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0, q=st["q"] <= K.G_Q,
                           account=st.get("max_dd", -1e9) > K.ACCOUNT_DD and st.get("worst_month", -1e9) > K.ACCOUNT_MONTH)
        st["passes"] = all(st["gates"].values())
        st["boss_target_strict"] = bool(st["n"] and st["win_rate"] >= K.TARGET_WIN and st["avg_net"] >= K.TARGET_AVG)
        # lenient: winners net about Rs 1,000, >= 65% win, and the average trade still positive (PREREG section 0)
        st["boss_target_lenient"] = bool(st["n"] and st["win_rate"] >= K.TARGET_WIN and st["avg_net"] > 0
                                         and st["avg_win"] >= K.TARGET_AVG)
    (K.RESULTS / "holdout.json").write_text(json.dumps(res, indent=1, default=str))
    log("holdout written", {r: res["rules"][r]["passes"] for r in K.RULE_IDS})


if __name__ == "__main__":
    cmd = sys.argv[1]
    {"build": cmd_build, "wf": cmd_wf, "finish_wf": finish_wf, "holdout": cmd_holdout,
     "finish_holdout": finish_holdout}[cmd]()
