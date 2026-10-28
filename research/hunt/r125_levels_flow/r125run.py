"""R125 runner (PREREG sections 3-4).

    python research/hunt/r125_levels_flow/r125run.py features design      # the 31 new inputs for R124's rows
    python research/hunt/r125_levels_flow/r125run.py wf base              # R124's network retrained (same rows/seeds)
    python research/hunt/r125_levels_flow/r125run.py wf n3                # N3 = + SR, LIQ, FLOW, VOL specialists
    python research/hunt/r125_levels_flow/r125run.py finish_wf            # comparison, importance, rules, gates
    python research/hunt/r125_levels_flow/r125run.py holdout              # the ONE holdout read (both variants)

Each network variant runs in its own process (the specialists are module-level in r123net).
"""
from __future__ import annotations

import json
import sys
import time
from datetime import timedelta
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r125feat as FT  # noqa: E402  (puts r124 / r123 / of1 on the path)
import r124cfg as K  # noqa: E402
import r124data as RD  # noqa: E402
import r124rules as RR  # noqa: E402
import r124run as R4  # noqa: E402
import r123net as M  # noqa: E402
import r123rules as R3  # noqa: E402

OUT = FT.OUT
R124_OUT = K.OUT                                       # R124's rows (K.OUT is redirected to R125's cache for weights)
RESULTS = HERE / "results"
from datetime import date  # noqa: E402
# amendment 3: two years (20 Oct 2024 - 19 Oct 2026); design to 15 Mar 2026; quarterly folds
YEAR_START = date(2024, 10, 20)
FOLDS = {"F1": (date(2025, 4, 1), date(2025, 6, 30)), "F2": (date(2025, 7, 1), date(2025, 9, 30)),
         "F3": (date(2025, 10, 1), date(2025, 12, 31)), "F4": (date(2026, 1, 1), date(2026, 3, 15))}


def fold_of(d):
    for k, (a, b) in FOLDS.items():
        if a <= d <= b:
            return k
    return None


RULES = {
    "M1": dict(h=15, rows=None, level=False, top=True),
    "M2": dict(h=30, rows=None, level=False, top=True),
    "M3": dict(h=15, rows=K.K3.MORNING_ROWS, level=False, top=True),
    "L1": dict(h=15, rows=None, level=True, top=False),
}
LEVEL_NEAR = 0.25


def log(*a, **k):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------------------------------------ holdout lock
def _flag():
    return OUT / "HOLDOUT_READ.json"


def holdout_gate(status: str) -> None:
    from datetime import datetime, timezone
    p = _flag()
    old = json.loads(p.read_text()) if p.exists() else None
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (RESULTS / "holdout.json").exists() or (old and old.get("status") == "done"):
            raise SystemExit("refused: the R125 holdout was already read")
        rec = dict(status="started", started_at=now, resumed=bool(old))
    else:
        rec = dict(old or {}, status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def holdout_open() -> bool:
    p = _flag()
    return p.exists() and json.loads(p.read_text()).get("status") == "started"


# ------------------------------------------------------------------------------------------------ data
def rows(part: str):
    """R124's rows (labels, trades, R124 inputs) + R125's new inputs. The holdout only inside the one read."""
    if part == "holdout" and not holdout_open() and not (RESULTS / "holdout.json").exists():
        raise SystemExit("the R125 holdout is read only inside the one holdout read")
    F = pd.read_parquet(R124_OUT / f"rows_{part}.parquet")
    S = dict(np.load(R124_OUT / f"seq_{part}.npz"))
    F = F[F.day >= YEAR_START].reset_index(drop=True)
    X = pd.read_parquet(OUT / f"feat_{part}.parquet")
    F = F.merge(X, on=["und", "day", "row"], how="left")
    return F, S


def set_variant(variant: str):
    if variant == "n3":
        for g, cols in FT.GROUPS.items():
            M.GROUPS[g] = cols
        M.SPECIALISTS = tuple(M.GROUPS)
        R4.COLS = list(R4.COLS) + list(FT.NEW_COLS)
    R4.GROUP_TESTS = ("fast_encoder", "slow_encoder") + tuple(M.GROUPS)


def cmd_features(part="design"):
    if part == "holdout" and not holdout_open():
        raise SystemExit("holdout features only inside the one holdout read")
    keys = pd.read_parquet(R124_OUT / f"rows_{part}.parquet", columns=["und", "day", "row"])
    keys = keys[keys.day >= YEAR_START]
    FT.add_features(part, keys)
    log("features", part, "done")


def cmd_wf(variant: str):
    set_variant(variant)
    F, S = rows("design")
    F["fold"] = [fold_of(d) for d in F.day]
    daily = R4.daily_for(K.DESIGN[1])
    parts, imps = [], {}
    for f, (a, b) in FOLDS.items():
        train = F[F.day < a - timedelta(days=K.K3.FOLD_EMBARGO_DAYS)]
        test = F[F.fold == f]
        log(f"{variant} fold {f}: train {len(train)}, test {len(test)}")
        R4.K.OUT = OUT                                     # model weights go to R125's cache
        out, imp, _ = R4.fit_fold(train, test, S, daily, f"{variant}_wf_{f}")
        parts.append(out)
        imps[f] = imp
    P = pd.concat(parts, ignore_index=True)
    P.to_parquet(OUT / f"wf_preds_{variant}.parquet")
    (OUT / f"wf_importance_{variant}.json").write_text(json.dumps(imps))
    log(variant, "wf done")


# ------------------------------------------------------------------------------------------------ comparison
def compare(PB: pd.DataFrame, PN: pd.DataFrame, folds) -> list:
    """N3 vs R124's network on the same rows: direction given a move (log-loss, AUC) and move coming (Brier)."""
    from sklearn.metrics import roc_auc_score
    key = ["und", "day", "row"]
    J = PB[key + ["fold", "mv_15", "mv_30"] + [f"p_{s}{h}" for s in ("up", "dn") for h in K.HS]].merge(
        PN[key + [f"p_{s}{h}" for s in ("up", "dn") for h in K.HS]], on=key, suffixes=("_b", "_n"))
    out = []
    for h in K.HS:
        y = J[f"mv_{h}"].values
        ok = (y >= 0) & np.isfinite(J[f"p_up{h}_b"].values) & np.isfinite(J[f"p_up{h}_n"].values)
        mv = ok & (y != K.NEITHER)
        yy = (y[mv] == K.UP).astype(float)

        def share(sfx):
            pu, pdn = J[f"p_up{h}_{sfx}"].values[mv], J[f"p_dn{h}_{sfx}"].values[mv]
            return np.clip(pu / (pu + pdn), 1e-6, 1 - 1e-6)

        sb, sn = share("b"), share("n")
        lb = -(yy * np.log(sb) + (1 - yy) * np.log(1 - sb))
        ln = -(yy * np.log(sn) + (1 - yy) * np.log(1 - sn))
        d = ln - lb
        _, lo, hi, p = R3.session_boot_ci(J.day.values[mv], -d)
        rec = dict(cell=f"direction given a move {h}m", metric="logloss", n=int(mv.sum()), n3=float(ln.mean()),
                   r124=float(lb.mean()), n3_minus_r124=float(d.mean()), ci=[-hi, -lo], p=p,
                   auc_n3=float(roc_auc_score(yy, sn)), auc_r124=float(roc_auc_score(yy, sb)))
        if folds:
            fo = J.fold.values[mv]
            rec["folds_better"] = int(sum(d[fo == f].mean() < 0 for f in folds if (fo == f).any()))
        out.append(rec)
        my = (y[ok] != K.NEITHER).astype(float)
        bb = (J[f"p_up{h}_b"].values[ok] + J[f"p_dn{h}_b"].values[ok] - my) ** 2
        bn = (J[f"p_up{h}_n"].values[ok] + J[f"p_dn{h}_n"].values[ok] - my) ** 2
        d = bn - bb
        _, lo, hi, p = R3.session_boot_ci(J.day.values[ok], -d)
        rec = dict(cell=f"move coming {h}m", metric="brier", n=int(ok.sum()), n3=float(bn.mean()),
                   r124=float(bb.mean()), n3_minus_r124=float(d.mean()), ci=[-hi, -lo], p=p,
                   auc_n3=float(roc_auc_score(my, J[f"p_up{h}_n"].values[ok] + J[f"p_dn{h}_n"].values[ok])),
                   auc_r124=float(roc_auc_score(my, J[f"p_up{h}_b"].values[ok] + J[f"p_dn{h}_b"].values[ok])))
        if folds:
            fo = J.fold.values[ok]
            rec["folds_better"] = int(sum(d[fo == f].mean() < 0 for f in folds if (fo == f).any()))
        out.append(rec)
    for r, q in zip(out, R3.bh([r["p"] for r in out])):
        r["q"] = q
    return out


def trades(P: pd.DataFrame, rule: str) -> pd.DataFrame:
    R = RULES[rule]
    h = R["h"]
    G = P
    if R["level"]:
        near = (G.sr_res_dist.fillna(9) <= LEVEL_NEAR) | (G.sr_sup_dist.fillna(9) <= LEVEL_NEAR)
        swept = (G.lq_sweep_hi > 0) | (G.lq_sweep_lo > 0)
        G = G[near | swept]
    Ts = []
    for f, g in G.groupby("fold"):
        thr = float(g[f"thr{h}"].iloc[0]) if R["top"] else -1.0
        cfg = dict(h=h, kind="side", rows=R["rows"], label=rule)
        K.RULES[f"_{rule}"] = cfg
        Ts.append(RR.trades(g, f"_{rule}", thr))
    return pd.concat(Ts, ignore_index=True) if Ts else pd.DataFrame()


def rule_results(P, sessions_by, folds):
    pools = RR.twin_pools(P)
    out = {}
    for rule in RULES:
        T = trades(P, rule)
        sess = sorted(set().union(*sessions_by.values()))
        st = RR.stats(T, sess)
        K.RULES[f"_{rule}"] = dict(h=RULES[rule]["h"], kind="side", rows=None, label=rule)
        tw = RR.twins(T, pools, f"_{rule}") if len(T) else np.zeros(K.TWINS)
        st["twin_mean"] = float(tw.mean())
        st["p_twins"] = R3.p_vs(float(T.net.sum()), tw) if len(T) else 1.0
        if len(T):
            st["p_side_twins"] = R3.p_vs(float(T.net.sum()), RR.side_twins(T, P, f"_{rule}"))
            T.to_parquet(OUT / f"trades_{rule}_{'wf' if folds else 'holdout'}.parquet")
        if folds:
            fo = {}
            for f in folds:
                Tf = T[T.day.isin(sessions_by[f])] if len(T) else T
                fo[f] = dict(n=len(Tf), net=float(Tf.net.sum()) if len(Tf) else 0.0)
            st["folds"] = fo
            st["folds_positive"] = int(sum(v["net"] > 0 for v in fo.values()))
        out[rule] = st
        log(rule, {k: st.get(k) for k in ("n", "win_rate", "avg_net", "avg_net_x2", "per_session", "p_twins")})
    for r, q in zip(RULES, R3.bh([out[r]["p_twins"] for r in RULES])):
        out[r]["q"] = q
    return out


def finish(part: str):
    PB = pd.read_parquet(OUT / f"{'wf' if part == 'wf' else 'holdout'}_preds_base.parquet")
    PN = pd.read_parquet(OUT / f"{'wf' if part == 'wf' else 'holdout'}_preds_n3.parquet")
    folds = list(FOLDS) if part == "wf" else None
    if part != "wf":
        PB["fold"] = PN["fold"] = "holdout"
    sessions_by = ({f: set(PN.day[PN.fold == f].unique()) for f in FOLDS} if folds
                   else {"all": set(PN.day.unique())})
    imp = json.loads((OUT / f"{'wf' if part == 'wf' else 'holdout'}_importance_n3.json").read_text())
    if part == "wf":
        imp = {g: {k: float(np.mean([imp[f][g][k] for f in imp])) for k in ("brier_worse", "dir_logloss_worse")}
               for g in next(iter(imp.values())) if g != "base"}
    res = dict(rows=len(PN), sessions=len(set().union(*sessions_by.values())), compare=compare(PB, PN, folds),
               n3_vs_baselines=R4.model_tests(PN, folds), importance=imp,
               rules=rule_results(PN, sessions_by, folds))
    if part == "wf":
        for r, st in res["rules"].items():
            st["gates"] = dict(n=st["n"] >= K.G_WF_N,
                               net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0,
                               folds=st.get("folds_positive", 0) >= K.G_WF_FOLDS, q=st["q"] <= K.G_Q)
            st["passes_wf"] = all(st["gates"].values())
    else:
        wf = json.loads((RESULTS / "wf.json").read_text())
        for r, st in res["rules"].items():
            st["gates"] = dict(wf=wf["rules"][r]["passes_wf"], n=st["n"] >= K.G_HO_N,
                               net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0, q=st["q"] <= K.G_Q,
                               account=st.get("max_dd", -1e9) > K.ACCOUNT_DD
                               and st.get("worst_month", -1e9) > K.ACCOUNT_MONTH)
            st["passes"] = all(st["gates"].values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}.json").write_text(json.dumps(res, indent=1, default=str))
    log(part, "written")


def cmd_holdout_variant(variant: str):
    """One variant's holdout fit (called by cmd_holdout in a subprocess while the lock is open)."""
    if not holdout_open():
        raise SystemExit("only inside the one holdout read")
    set_variant(variant)
    Fh, Sh = rows("holdout")
    Fd, Sd = rows("design")
    for u in K.UNDS:
        if not np.array_equal(Sd[u], Sh[u][:len(Sd[u])], equal_nan=True):
            raise RuntimeError(f"{u}: the holdout build's minutes differ")
    daily = R4.daily_for(K.HOLDOUT[1])
    R4.K.OUT = OUT
    out, imp, _ = R4.fit_fold(Fd, Fh, Sh, daily, f"{variant}_holdout")
    out.to_parquet(OUT / f"holdout_preds_{variant}.parquet")
    (OUT / f"holdout_importance_{variant}.json").write_text(json.dumps(imp))


def cmd_holdout():
    import subprocess
    if not (RESULTS / "wf.json").exists():
        raise SystemExit("wf.json must be committed before the holdout read")
    if not (R124_OUT / "rows_holdout.parquet").exists():
        raise SystemExit("R124's holdout rows are needed (built in R124's one read)")
    holdout_gate("start")
    cmd_features("holdout")
    for v in ("base", "n3"):
        subprocess.run([sys.executable, "-W", "ignore", "-u", __file__, "holdout_variant", v], check=True)
    finish("holdout")
    holdout_gate("done")


if __name__ == "__main__":
    c = sys.argv[1]
    if c == "features":
        cmd_features(sys.argv[2])
    elif c == "wf":
        cmd_wf(sys.argv[2])
    elif c == "finish_wf":
        finish("wf")
    elif c == "holdout":
        cmd_holdout()
    elif c == "holdout_variant":
        cmd_holdout_variant(sys.argv[2])
    elif c == "finish_holdout":
        finish("holdout")
