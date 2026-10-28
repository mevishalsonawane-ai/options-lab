"""R124 runner.

    python research/hunt/r124_big_moves/r124run.py census_stats     # part A on design (needs the census cache)
    python research/hunt/r124_big_moves/r124run.py wf               # part B + C walk-forward
    python research/hunt/r124_big_moves/r124run.py holdout          # the ONE holdout read (census check, model, rules)
"""
from __future__ import annotations

import json
import sys
import time
from datetime import timedelta
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r124cfg as K  # noqa: E402
import r124data as RD  # noqa: E402
import r124rules as RR  # noqa: E402
import r124zig as Z  # noqa: E402
import r123data as D3  # noqa: E402
import r123net as M  # noqa: E402
import r123rules as R3  # noqa: E402

# the network: R123's multi-level N2 with a fifth specialist for the level inputs (PREREG section 5). Its first two
# head-(a) slots carry the move labels (15 and 30 minutes, 3 classes); the other 14 slots are masked (never trained).
M.GROUPS["levels"] = RD.LEVEL_COLS
M.SPECIALISTS = tuple(M.GROUPS)
COLS = list(D3.TAB_COLS) + list(RD.LEVEL_COLS)
NJ = M.NJ
GROUP_TESTS = ("fast_encoder", "slow_encoder") + tuple(M.GROUPS)


def log(*a, **k):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def as_net_frame(F: pd.DataFrame) -> pd.DataFrame:
    F = F.copy()
    F["cls_0"], F["cls_1"] = F.mv_15.astype(int), F.mv_30.astype(int)
    for j in range(2, NJ):
        F[f"cls_{j}"] = -1
    return F


def net_outputs(net, F, store, daily, perm: str | None = None, rng=None):
    """Seed-averaged, temperature-scaled class probabilities for the two move heads (n, 2, 3) and the seed spread of
    P(move). `perm` shuffles one input group across rows (permutation importance)."""
    import torch
    X = net.tab.transform(F[net.cols].values.astype(np.float64))
    store.mu, store.sd = net.store.mu, net.store.sd
    daily.mu, daily.sd = net.daily.mu, net.daily.sd
    net.store, net.daily = store, daily
    fp = store.flat(F.und.values, F.gpos.values)
    dp = daily.flat(F.und.values, F.gpos.values)
    if perm is not None:
        rng = rng or np.random.default_rng(K.SEED)
        o = rng.permutation(len(F))
        if perm == "fast_encoder":
            fp = fp[o]
        elif perm == "slow_encoder":
            dp = dp[o]
        else:
            kept = [net.cols[i] for i in net.tab.keep]
            idx = [kept.index(c) for c in M.GROUPS[perm] if c in kept]
            X = X.copy()
            X[:, idx] = X[o][:, idx]
    P = []
    with torch.no_grad():
        for m in net.nets:
            out = []
            for b in range(0, len(X), 8192):
                sl = slice(b, b + 8192)
                la = m(*net._inputs(X, fp, dp, sl))[0][:, :2]
                la = la / torch.from_numpy(net.temps[:2]).float().view(1, -1, 1)
                out.append(torch.softmax(la, -1).numpy())
            P.append(np.concatenate(out))
    P = np.array(P)
    pm = P[..., 0] + P[..., 1]
    return P.mean(0), pm.max(0) - pm.min(0)


def attach(F, P, spread):
    F = F.copy()
    for k, h in enumerate(K.HS):
        F[f"p_up{h}"], F[f"p_dn{h}"], F[f"p_ne{h}"] = P[:, k, 0], P[:, k, 1], P[:, k, 2]
        F[f"spread{h}"] = spread[:, k]
        ab = spread[:, k] > K3_ABSTAIN
        F.loc[ab, f"p_up{h}"] = np.nan                     # Level 4: the seeds disagree -> no forecast, no trade
        F.loc[ab, f"p_dn{h}"] = np.nan
    return F


K3_ABSTAIN = 0.10


def baselines(train, test):
    """Base rate per index and hour, and trees (multiclass) on the tabular inputs, for each horizon."""
    from sklearn.ensemble import HistGradientBoostingClassifier
    out = {}
    hb_tr, hb_te = M.hour_bucket(train.row.values), M.hour_bucket(test.row.values)
    for h in K.HS:
        y = train[f"mv_{h}"].values
        ok = y >= 0
        tab = pd.DataFrame(dict(u=train.und.values[ok], hb=hb_tr[ok], y=y[ok]))
        allp = np.bincount(y[ok], minlength=3) / ok.sum()
        rates = {}
        for (u, b), g in tab.groupby(["u", "hb"]):
            c = np.bincount(g.y.values, minlength=3)
            rates[(u, b)] = (c + allp * 5) / (c.sum() + 5)
        out[f"base{h}"] = np.array([rates.get((u, b), allp) for u, b in zip(test.und.values, hb_te)])
        m = HistGradientBoostingClassifier(**K.K3.GBM)
        m.fit(train[COLS].values[ok].astype(float), y[ok])
        pr = m.predict_proba(test[COLS].values.astype(float))
        full = np.zeros((len(test), 3))
        full[:, m.classes_] = pr
        out[f"tree{h}"] = full
    return out


def fit_fold(train, test, S, daily, tag):
    tr, te = as_net_frame(train), as_net_frame(test)
    net = M.NetModel(arch="N2", daily=daily, log=log).fit(tr, M.SeqStore(S), COLS)
    import torch
    torch.save(dict(states=[n.state_dict() for n in net.nets], temps=net.temps), K.OUT / f"net_{tag}.pt")
    Pte, spte = net_outputs(net, te, M.SeqStore(S), M.DailyStore(daily))
    Ptr, sptr = net_outputs(net, tr, M.SeqStore(S), M.DailyStore(daily))
    thr = {h: RR.top_threshold(Ptr[:, k, 0] + Ptr[:, k, 1]) for k, h in enumerate(K.HS)}
    base = baselines(train, test)
    imp = importance(net, te, S, daily, Pte)
    out = attach(test, Pte, spte)
    for h in K.HS:
        out[[f"base{h}_{c}" for c in ("up", "dn", "ne")]] = base[f"base{h}"]
        out[[f"tree{h}_{c}" for c in ("up", "dn", "ne")]] = base[f"tree{h}"]
    out["thr15"], out["thr30"] = thr[15], thr[30]
    gate = None
    return out, imp, net


def _move_brier(p_move, y):
    return (p_move - (y != K.NEITHER).astype(float)) ** 2


def importance(net, te, S, daily, P0) -> dict:
    """How much worse the 15-minute 'move coming' Brier score and the direction log-loss get when one input group is
    shuffled across rows (this fold's test rows)."""
    y = te.mv_15.values
    ok = y >= 0
    mv = ok & (y != K.NEITHER)

    def scores(P):
        pm = P[:, 0, 0] + P[:, 0, 1]
        b = _move_brier(pm[ok], y[ok]).mean()
        sh = np.clip(P[mv, 0, 0] / (P[mv, 0, 0] + P[mv, 0, 1]), 1e-6, 1 - 1e-6)
        yy = (y[mv] == K.UP).astype(float)
        ll = -(yy * np.log(sh) + (1 - yy) * np.log(1 - sh)).mean()
        return b, ll

    b0, l0 = scores(P0)
    out = dict(base=dict(brier=float(b0), dir_logloss=float(l0)))
    for g in GROUP_TESTS:
        P, _ = net_outputs(net, as_net_frame(te), M.SeqStore(S), M.DailyStore(daily), perm=g)
        b, l = scores(P)
        out[g] = dict(brier_worse=float(b - b0), dir_logloss_worse=float(l - l0))
    return out


def model_tests(P: pd.DataFrame, folds) -> list:
    from sklearn.metrics import roc_auc_score
    out = []
    for h in K.HS:
        y = P[f"mv_{h}"].values
        ok = (y >= 0) & np.isfinite(P[f"p_up{h}"].values)
        mvy = (y[ok] != K.NEITHER).astype(float)
        nn = P[f"p_up{h}"].values[ok] + P[f"p_dn{h}"].values[ok]
        cand = {k: P[f"{k}{h}_up"].values[ok] + P[f"{k}{h}_dn"].values[ok] for k in ("base", "tree")}
        sc = {k: ((v - mvy) ** 2) for k, v in cand.items()}
        best = min(sc, key=lambda k: sc[k].mean())
        d = (nn - mvy) ** 2 - sc[best]
        m, lo, hi, p = R3.session_boot_ci(P.day.values[ok], -d)
        rec = dict(cell=f"move coming {h}m", metric="brier", n=int(ok.sum()), base_rate=float(mvy.mean()),
                   nn=float(((nn - mvy) ** 2).mean()), best=best, best_score=float(sc[best].mean()),
                   nn_minus_best=float(d.mean()), ci=[-hi, -lo], p=p, auc_nn=float(roc_auc_score(mvy, nn)),
                   auc_best=float(roc_auc_score(mvy, cand[best])))
        if folds:
            fo = P.fold.values[ok]
            rec["folds_better"] = int(sum(d[fo == f].mean() < 0 for f in folds if (fo == f).any()))
        out.append(rec)
        mv = ok & (y != K.NEITHER) & (y >= 0)
        yy = (y[mv] == K.UP).astype(float)

        def ll(pu, pd_):
            s = np.clip(pu / (pu + pd_), 1e-6, 1 - 1e-6)
            return -(yy * np.log(s) + (1 - yy) * np.log(1 - s)), s

        lnn, snn = ll(P[f"p_up{h}"].values[mv], P[f"p_dn{h}"].values[mv])
        cands = {k: ll(P[f"{k}{h}_up"].values[mv], P[f"{k}{h}_dn"].values[mv]) for k in ("base", "tree")}
        best = min(cands, key=lambda k: cands[k][0].mean())
        d = lnn - cands[best][0]
        m, lo, hi, p = R3.session_boot_ci(P.day.values[mv], -d)
        rec = dict(cell=f"direction given a move {h}m", metric="logloss", n=int(mv.sum()), up_share=float(yy.mean()),
                   nn=float(lnn.mean()), best=best, best_score=float(cands[best][0].mean()),
                   nn_minus_best=float(d.mean()), ci=[-hi, -lo], p=p, auc_nn=float(roc_auc_score(yy, snn)),
                   auc_best=float(roc_auc_score(yy, cands[best][1])))
        if folds:
            fo = P.fold.values[mv]
            rec["folds_better"] = int(sum(d[fo == f].mean() < 0 for f in folds if (fo == f).any()))
        out.append(rec)
    for r, q in zip(out, R3.bh([r["p"] for r in out])):
        r["q"] = q
    return out


def calibration(P: pd.DataFrame, h: int) -> list:
    y = P[f"mv_{h}"].values
    pm = P[f"p_up{h}"].values + P[f"p_dn{h}"].values
    ok = (y >= 0) & np.isfinite(pm)
    out = []
    for lo, hi in ((0, .2), (.2, .3), (.3, .4), (.4, .5), (.5, .6), (.6, .7), (.7, 1.01)):
        m = ok & (pm >= lo) & (pm < hi)
        out.append(dict(bin=f"[{lo:.1f}, {min(hi, 1):.1f})", n=int(m.sum()),
                        predicted=float(pm[m].mean()) if m.any() else None,
                        observed=float((y[m] != K.NEITHER).mean()) if m.any() else None))
    return out


def rule_results(P, sessions_by, folds):
    pools = RR.twin_pools(P)
    out = {}
    for rule in K.RULE_IDS:
        h = K.RULES[rule]["h"]
        Ts = []
        for f, g in (P.groupby("fold") if folds else [("holdout", P)]):
            Ts.append(RR.trades(g, rule, float(g[f"thr{h}"].iloc[0])))
        T = pd.concat(Ts, ignore_index=True) if Ts else pd.DataFrame()
        sess = sorted(set().union(*sessions_by.values()))
        st = RR.stats(T, sess)
        tw = RR.twins(T, pools, rule)
        st["twin_mean"] = float(tw.mean())
        st["p_twins"] = R3.p_vs(float(T.net.sum()), tw) if len(T) else 1.0
        if len(T) and K.RULES[rule]["kind"] == "side":
            sw = RR.side_twins(T, P, rule)
            st["p_side_twins"] = R3.p_vs(float(T.net.sum()), sw)
        if folds:
            fo = {}
            for f in folds:
                Tf = T[T.day.isin(sessions_by[f])] if len(T) else T
                fo[f] = dict(n=len(Tf), net=float(Tf.net.sum()) if len(Tf) else 0.0,
                             win=float((Tf.net > 0).mean()) if len(Tf) else None)
            st["folds"] = fo
            st["folds_positive"] = int(sum(v["net"] > 0 for v in fo.values()))
        out[rule] = st
        if len(T):
            T.to_parquet(K.OUT / f"trades_{rule}_{'wf' if folds else 'holdout'}.parquet")
        log(rule, {k: st.get(k) for k in ("n", "win_rate", "avg_net", "avg_net_x2", "per_session", "p_twins")})
    for r, q in zip(K.RULE_IDS, R3.bh([out[r]["p_twins"] for r in K.RULE_IDS])):
        out[r]["q"] = q
    return out


def daily_for(last_day):
    vix = D3.load_grid("INDIAVIX", last_day)
    return {u: M.daily_bars(D3.load_grid(u, last_day), vix) for u in K.UNDS}


# ------------------------------------------------------------------------------------------------ commands
def cmd_census_stats(part="design"):
    Mv = pd.read_parquet(K.OUT / f"census_moves_{part}.parquet")
    Rw = pd.read_parquet(K.OUT / f"census_rows_{part}.parquet")
    res = dict(moves=Z.move_stats(Mv), factors=Z.factor_stats(Rw, stride=2))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / f"census_{part}.json").write_text(json.dumps(res, indent=1, default=str))
    log("census written", res["moves"]["big"], "big moves")
    return res


def cmd_wf():
    F, S = RD.load("design")
    F["fold"] = [K.fold_of(d) for d in F.day]
    daily = daily_for(K.DESIGN[1])
    parts, imps = [], {}
    for f, (a, b) in K.FOLDS.items():
        train = F[F.day < a - timedelta(days=K.K3.FOLD_EMBARGO_DAYS)]
        test = F[F.fold == f]
        log(f"fold {f}: train {len(train)}, test {len(test)}")
        out, imp, _ = fit_fold(train, test, S, daily, f"wf_{f}")
        parts.append(out)
        imps[f] = imp
    P = pd.concat(parts, ignore_index=True)
    P.to_parquet(K.OUT / "wf_rows_preds.parquet")
    (K.OUT / "wf_importance.json").write_text(json.dumps(imps))
    finish_wf()


def finish_wf():
    P = pd.read_parquet(K.OUT / "wf_rows_preds.parquet")
    imps = json.loads((K.OUT / "wf_importance.json").read_text())
    sessions_by = {f: set(P.day[P.fold == f].unique()) for f in K.FOLDS}
    folds = list(K.FOLDS)
    imp = {g: {k: float(np.mean([imps[f][g][k] for f in imps])) for k in ("brier_worse", "dir_logloss_worse")}
           for g in GROUP_TESTS}
    res = dict(rows=len(P), sessions=len(set().union(*sessions_by.values())),
               models=model_tests(P, folds), importance=imp, importance_by_fold=imps,
               calibration={h: calibration(P, h) for h in K.HS},
               move_rate={h: float((P[f"mv_{h}"].isin([K.UP, K.DOWN])).sum() / (P[f"mv_{h}"] >= 0).sum())
                          for h in K.HS},
               abstained={h: int(P[f"p_up{h}"].isna().sum()) for h in K.HS},
               rules=rule_results(P, sessions_by, folds))
    for r, st in res["rules"].items():
        st["gates"] = dict(n=st["n"] >= K.G_WF_N, net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0,
                           folds=st.get("folds_positive", 0) >= K.G_WF_FOLDS, q=st["q"] <= K.G_Q)
        st["passes_wf"] = all(st["gates"].values())
    (K.RESULTS / "wf.json").write_text(json.dumps(res, indent=1, default=str))
    log("wf written", {r: st["passes_wf"] for r, st in res["rules"].items()})


def cmd_holdout():
    for need in ("wf.json", "census_design.json"):
        if not (K.RESULTS / need).exists():
            raise SystemExit(f"{need} must be committed before the holdout read")
    Z.holdout_gate("start")
    # part A check: the factors that passed in design, re-measured on the holdout
    Mv, Rw = Z.census("holdout", stride=2)
    Mv.to_parquet(K.OUT / "census_moves_holdout.parquet")
    Rw.to_parquet(K.OUT / "census_rows_holdout.parquet")
    cmd_census_stats("holdout")
    # parts B and C
    RD.save("holdout", log=log)
    Fh, Sh = RD.load("holdout")
    Fd, Sd = RD.load("design")
    for u in K.UNDS:
        if not np.array_equal(Sd[u], Sh[u][:len(Sd[u])], equal_nan=True):
            raise RuntimeError(f"{u}: the holdout build's minutes differ from the design build's")
    daily = daily_for(K.HOLDOUT[1])
    out, imp, _ = fit_fold(Fd, Fh, Sh, daily, "holdout")
    out.to_parquet(K.OUT / "holdout_rows_preds.parquet")
    (K.OUT / "holdout_importance.json").write_text(json.dumps(imp))
    finish_holdout()
    Z.holdout_gate("done")


def finish_holdout():
    P = pd.read_parquet(K.OUT / "holdout_rows_preds.parquet")
    imp = json.loads((K.OUT / "holdout_importance.json").read_text())
    sess = sorted(P.day.unique())
    wf = json.loads((K.RESULTS / "wf.json").read_text())
    res = dict(rows=len(P), sessions=len(sess), models=model_tests(P, None), importance=imp,
               calibration={h: calibration(P, h) for h in K.HS},
               move_rate={h: float((P[f"mv_{h}"].isin([K.UP, K.DOWN])).sum() / (P[f"mv_{h}"] >= 0).sum())
                          for h in K.HS},
               rules=rule_results(P, {"all": set(sess)}, None))
    for r, st in res["rules"].items():
        st["gates"] = dict(wf=wf["rules"][r]["passes_wf"], n=st["n"] >= K.G_HO_N,
                           net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0, q=st["q"] <= K.G_Q,
                           account=st.get("max_dd", -1e9) > K.ACCOUNT_DD
                           and st.get("worst_month", -1e9) > K.ACCOUNT_MONTH)
        st["passes"] = all(st["gates"].values())
    (K.RESULTS / "holdout.json").write_text(json.dumps(res, indent=1, default=str))
    log("holdout written", {r: st["passes"] for r, st in res["rules"].items()})


if __name__ == "__main__":
    {"census_stats": cmd_census_stats, "wf": cmd_wf, "finish_wf": finish_wf, "holdout": cmd_holdout,
     "finish_holdout": finish_holdout}[sys.argv[1]]()
