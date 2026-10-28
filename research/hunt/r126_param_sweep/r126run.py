"""R126 runner (PREREG section 3).

    python research/hunt/r126_param_sweep/r126run.py screen        # A: 118 variants x 2 horizons, walk-forward
    python research/hunt/r126_param_sweep/r126run.py n4            # B: the network with every variant (walk-forward)
    python research/hunt/r126_param_sweep/r126run.py finish_wf     # B comparison + C rule sweep + gates -> results/wf.json
    python research/hunt/r126_param_sweep/r126run.py holdout       # the ONE holdout read (frozen winners, N4, rules)
"""
from __future__ import annotations

import itertools
import json
import os
import sys
import time
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r126feat as F  # noqa: E402  (puts r125 / r124 / r123 / of1 on the path)
import r124cfg as K  # noqa: E402
import r124rules as RR  # noqa: E402
import r124run as R4  # noqa: E402
import r123net as M  # noqa: E402
import r123rules as R3  # noqa: E402

OUT = F.OUT
RESULTS = HERE / "results"
R124_OUT = K.OUT
R125_OUT = Path(os.environ.get("R125_OUT", K.K3.CACHE_ROOT / "r125")).expanduser()
FOLDS = {"F1": (date(2025, 4, 1), date(2025, 6, 30)), "F2": (date(2025, 7, 1), date(2025, 9, 30)),
         "F3": (date(2025, 10, 1), date(2025, 12, 31)), "F4": (date(2026, 1, 1), date(2026, 3, 15))}
CORE = ("ret5", "ret15", "ret30", "ret60", "rv30", "day_range", "dist_hi", "dist_lo", "from_open", "gap", "vix", "tod")
MIN_GAIN = 0.001
SHARES, TOPS, EXIT_E = (0.55, 0.60, 0.65), (0.95, 0.90, 0.80), F.EXITS
RULES = [dict(share=s, top=q, h=h, e=e) for s in SHARES for q in TOPS for h in K.HS for e in EXIT_E]   # 54


def log(*a, **k):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def fold_of(d):
    for k, (a, b) in FOLDS.items():
        if a <= d <= b:
            return k
    return None


def rule_id(r) -> str:
    return f"s{r['share']}_top{int(round((1 - r['top']) * 100))}_h{r['h']}_e{r['e']}"


# ------------------------------------------------------------------------------------------------ holdout lock
def _flag():
    return OUT / "HOLDOUT_READ.json"


def holdout_gate(status: str):
    from datetime import datetime, timezone
    p = _flag()
    old = json.loads(p.read_text()) if p.exists() else None
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (RESULTS / "holdout.json").exists() or (old and old.get("status") == "done"):
            raise SystemExit("refused: the R126 holdout was already read")
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
    if part == "holdout" and not holdout_open() and not (RESULTS / "holdout.json").exists():
        raise SystemExit("the R126 holdout is read only inside the one holdout read")
    Fr = pd.read_parquet(R124_OUT / f"rows_{part}.parquet")
    Fr = Fr[Fr.day >= F.START].reset_index(drop=True)
    X = pd.read_parquet(OUT / f"feat_{part}.parquet")
    Fr = Fr.merge(X, on=["und", "day", "row"], how="left")
    Fr["fold"] = [fold_of(d) for d in Fr.day] if part == "design" else "holdout"
    S = dict(np.load(R124_OUT / f"seq_{part}.npz"))
    return Fr, S


# ------------------------------------------------------------------------------------------------ A: screening
def _logit_ll(Xtr, ytr, Xte, yte):
    from sklearn.linear_model import LogisticRegression
    med = np.nanmedian(Xtr, 0)
    med = np.where(np.isfinite(med), med, 0.0)
    Xtr = np.where(np.isnan(Xtr), med, Xtr)
    Xte = np.where(np.isnan(Xte), med, Xte)
    mu, sd = Xtr.mean(0), Xtr.std(0)
    sd = np.where(sd > 1e-12, sd, 1.0)
    Xtr, Xte = np.clip((Xtr - mu) / sd, -5, 5), np.clip((Xte - mu) / sd, -5, 5)
    m = LogisticRegression(C=1.0, max_iter=300).fit(Xtr, ytr)
    p = np.clip(m.predict_proba(Xte)[:, 1], 1e-6, 1 - 1e-6)
    return -(yte * np.log(p) + (1 - yte) * np.log(1 - p))


def screen_part(Fr: pd.DataFrame, train_of, test_sets, cells=None) -> list:
    """For each variant (or the given cells) and horizon: test-row log-loss change of direction-given-a-move when the
    variant's inputs join the price core. train_of(test_key) -> training frame."""
    out = []
    todo = cells or [(fam, var, h) for fam, vs in F.VARIANTS.items() for var in vs for h in K.HS]
    base_cache = {}
    for fam, var, h in todo:
        cols = F.VARIANTS[fam][var]
        diffs, days, folds = [], [], []
        for key, te in test_sets.items():
            tr = train_of(key)
            mtr = tr[f"mv_{h}"].isin([K.UP, K.DOWN]).values
            mte = te[f"mv_{h}"].isin([K.UP, K.DOWN]).values
            ytr = (tr[f"mv_{h}"].values[mtr] == K.UP).astype(float)
            yte = (te[f"mv_{h}"].values[mte] == K.UP).astype(float)
            ck = (key, h)
            if ck not in base_cache:
                base_cache[ck] = _logit_ll(tr[list(CORE)].values[mtr].astype(float), ytr,
                                           te[list(CORE)].values[mte].astype(float), yte)
            lb = base_cache[ck]
            lv = _logit_ll(tr[list(CORE) + cols].values[mtr].astype(float), ytr,
                           te[list(CORE) + cols].values[mte].astype(float), yte)
            diffs.append(lv - lb)
            days.append(te.day.values[mte])
            folds.append(np.full(mte.sum(), key, dtype=object))
        d, dy, fo = np.concatenate(diffs), np.concatenate(days), np.concatenate(folds)
        _, lo, hi, p = R3.session_boot_ci(dy, -d)
        rec = dict(family=fam, variant=var, h=h, n=int(len(d)), ll_change=float(d.mean()), ci=[-hi, -lo], p=p)
        rec["folds_better"] = int(sum(d[fo == k].mean() < 0 for k in test_sets if (fo == k).any()))
        out.append(rec)
    return out


def cmd_screen():
    Fr, _ = rows("design")
    tests = {f: Fr[Fr.fold == f] for f in FOLDS}
    res = screen_part(Fr, lambda f: Fr[Fr.day < FOLDS[f][0] - timedelta(days=10)], tests)
    for r, q in zip(res, R3.bh([r["p"] for r in res])):
        r["q"] = q
        r["adds"] = bool(q <= 0.05 and r["folds_better"] >= 3 and -r["ll_change"] >= MIN_GAIN)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "screen_wf.json").write_text(json.dumps(res))
    log("screen done:", sum(r["adds"] for r in res), "of", len(res), "add")


# ------------------------------------------------------------------------------------------------ B: N4
def setup_n4():
    for fam, cols in F.FAMILY_COLS.items():
        M.GROUPS[f"x_{fam}"] = tuple(cols)
    M.SPECIALISTS = tuple(M.GROUPS)
    R4.COLS = list(R4.COLS) + list(F.ALL_COLS)
    R4.GROUP_TESTS = ("fast_encoder", "slow_encoder") + tuple(M.GROUPS)


def fit_n4(train, test, S, daily, tag):
    """R124's fit with N4's inputs; 'move likely' thresholds at the 80/90/95th training percentiles."""
    import torch
    tr, te = R4.as_net_frame(train), R4.as_net_frame(test)
    net = M.NetModel(arch="N2", daily=daily, log=log).fit(tr, M.SeqStore(S), R4.COLS)
    torch.save(dict(states=[n.state_dict() for n in net.nets], temps=net.temps), OUT / f"net_{tag}.pt")
    Pte, spte = R4.net_outputs(net, te, M.SeqStore(S), M.DailyStore(daily))
    Ptr, _ = R4.net_outputs(net, tr, M.SeqStore(S), M.DailyStore(daily))
    out = R4.attach(test, Pte, spte)
    for k, h in enumerate(K.HS):
        pm = Ptr[:, k, 0] + Ptr[:, k, 1]
        for q in TOPS:
            out[f"thr{h}_q{q}"] = float(np.nanquantile(pm, q))
    imp = R4.importance(net, te, S, daily, Pte)
    return out, imp


def cmd_n4():
    setup_n4()
    Fr, S = rows("design")
    daily = R4.daily_for(K.DESIGN[1])
    parts, imps = [], {}
    for f, (a, b) in FOLDS.items():
        train = Fr[Fr.day < a - timedelta(days=10)]
        test = Fr[Fr.fold == f]
        log(f"n4 fold {f}: train {len(train)}, test {len(test)}")
        out, imp = fit_n4(train, test, S, daily, f"n4_wf_{f}")
        parts.append(out)
        imps[f] = imp
    P = pd.concat(parts, ignore_index=True)
    P.to_parquet(OUT / "wf_preds_n4.parquet")
    (OUT / "wf_importance_n4.json").write_text(json.dumps(imps))
    log("n4 wf done")


# ------------------------------------------------------------------------------------------------ C: rule sweep
def candidates(P: pd.DataFrame, r: dict) -> pd.DataFrame:
    h, e = r["h"], r["e"]
    pu, pdn = P[f"p_up{h}"].values, P[f"p_dn{h}"].values
    pm = pu + pdn
    thr = P[f"thr{h}_q{r['top']}"].values
    with np.errstate(all="ignore"):
        share = pu / pm
    side = np.where(share >= r["share"], "C", np.where(share <= 1 - r["share"], "P", ""))
    ok = (pm >= thr) & (side != "")
    net = np.array([P[f"tr{h}_e{e}_{s}_net"].values[n] if s else np.nan for n, s in enumerate(side)])
    ok &= np.isfinite(net)
    idx = np.flatnonzero(ok)
    g = lambda k: np.array([P[f"tr{h}_e{e}_{side[n]}_{k}"].values[n] for n in idx])   # noqa: E731
    C = pd.DataFrame(dict(und=P.und.values[idx], day=P.day.values[idx], row=P.row.values[idx], side=side[idx],
                          score=pm[idx], net=net[idx], netx2=g("netx2").astype(float), exit=g("exit").astype(int),
                          why=g("why")))
    C["cls"] = C.why.map(RR.WHY_CLS).fillna(2).astype(int)
    C["j"] = np.where(C.side == "P", 1, 0)
    return C


def twin_pools(P: pd.DataFrame, h, e) -> dict:
    pools = {}
    for s in ("C", "P"):
        n = P[f"tr{h}_e{e}_{s}_net"].values
        ok = np.isfinite(n)
        sub = pd.DataFrame(dict(und=P.und.values[ok], day=P.day.values[ok], net=n[ok]))
        for (u, d), g in sub.groupby(["und", "day"]):
            pools[(u, d, s)] = g.net.values
    return pools


def twins(T, pools, n=K.TWINS, seed=K.SEED):
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    for u, d in zip(T.und.values, T.day.values):
        side = rng.integers(0, 2, n)
        for k, s, o in ((0, "C", "P"), (1, "P", "C")):
            msk = side == k
            pl = pools.get((u, d, s))
            if pl is None or not len(pl):
                pl = pools.get((u, d, o))
            if pl is None or not len(pl) or not msk.any():
                continue
            tot[msk] += pl[rng.integers(0, len(pl), msk.sum())]
    return tot


def rule_sweep(P: pd.DataFrame, sessions_by: dict, folds, only=None) -> dict:
    out = {}
    pools_cache = {}
    for r in RULES:
        rid = rule_id(r)
        if only is not None and rid not in only:
            continue
        T = R3.portfolio(candidates(P, r), day_max=K.DAY_MAX)
        sess = sorted(set().union(*sessions_by.values()))
        st = RR.stats(T, sess)
        key = (r["h"], r["e"])
        if key not in pools_cache:
            pools_cache[key] = twin_pools(P, *key)
        tw = twins(T, pools_cache[key]) if len(T) else np.zeros(K.TWINS)
        st["p_twins"] = R3.p_vs(float(T.net.sum()), tw) if len(T) else 1.0
        st["twin_mean"] = float(tw.mean())
        if folds:
            fo = {f: float(T.net[T.day.isin(sessions_by[f])].sum()) if len(T) else 0.0 for f in folds}
            st["folds"] = fo
            st["folds_positive"] = int(sum(v > 0 for v in fo.values()))
        st["rule"] = r
        out[rid] = st
    for rid, q in zip(list(out), R3.bh([out[k]["p_twins"] for k in out])):
        out[rid]["q"] = q
    return out


def compare_vs_base(PN: pd.DataFrame, PB: pd.DataFrame, folds) -> list:
    import r125run as R5
    PB = PB.copy()
    PB["fold"] = [fold_of(d) for d in PB.day] if folds else "holdout"
    return R5.compare(PB, PN, folds)


def cmd_finish_wf():
    PN = pd.read_parquet(OUT / "wf_preds_n4.parquet")
    PB = pd.read_parquet(R125_OUT / "wf_preds_base.parquet")
    PB = PB[PB.day >= F.START]
    imps = json.loads((OUT / "wf_importance_n4.json").read_text())
    folds = list(FOLDS)
    sessions_by = {f: set(PN.day[PN.fold == f].unique()) for f in folds}
    screen = json.loads((OUT / "screen_wf.json").read_text())
    sweep = rule_sweep(PN, sessions_by, folds)
    for rid, st in sweep.items():
        st["gates"] = dict(n=st["n"] >= K.G_WF_N, net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0,
                           folds=st.get("folds_positive", 0) >= K.G_WF_FOLDS, q=st["q"] <= K.G_Q)
        st["passes_wf"] = all(st["gates"].values())
    imp = {g: {k: float(np.mean([imps[f][g][k] for f in imps])) for k in ("brier_worse", "dir_logloss_worse")}
           for g in next(iter(imps.values())) if g != "base"}
    res = dict(rows=len(PN), sessions=len(set().union(*sessions_by.values())), screen=screen,
               screen_adds=[f"{r['family']}:{r['variant']}:h{r['h']}" for r in screen if r["adds"]],
               compare=compare_vs_base(PN, PB, folds), importance=imp, rules=sweep,
               rules_passing=[k for k, v in sweep.items() if v["passes_wf"]])
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "wf.json").write_text(json.dumps(res, indent=1, default=str))
    log("wf written; adds:", res["screen_adds"], "rules passing:", res["rules_passing"])


# ------------------------------------------------------------------------------------------------ holdout
def cmd_holdout():
    if not (RESULTS / "wf.json").exists():
        raise SystemExit("wf.json must be committed before the holdout read")
    wf = json.loads((RESULTS / "wf.json").read_text())
    holdout_gate("start")
    F.build("holdout", R124_OUT)
    Fh, Sh = rows("holdout")
    Fd, Sd = rows("design")
    for u in K.UNDS:
        if not np.array_equal(Sd[u], Sh[u][:len(Sd[u])], equal_nan=True):
            raise RuntimeError(f"{u}: the holdout build's minutes differ")
    # (a) the frozen screening winners only
    cells = [(r["family"], r["variant"], r["h"]) for r in wf["screen"] if r["adds"]]
    screen = screen_part(Fd, lambda k: Fd, {"holdout": Fh}, cells) if cells else []
    for r, q in zip(screen, R3.bh([r["p"] for r in screen])):
        r["q"] = q
        r["holds"] = bool(r["ci"][1] < 0 and q <= 0.05)
    # (b) N4 on the holdout vs R124's network (R125's holdout base predictions)
    setup_n4()
    daily = R4.daily_for(K.HOLDOUT[1])
    out, imp = fit_n4(Fd, Fh, Sh, daily, "n4_holdout")
    out.to_parquet(OUT / "holdout_preds_n4.parquet")
    PB = pd.read_parquet(R125_OUT / "holdout_preds_base.parquet")
    comp = compare_vs_base(out, PB, None)
    # (c) the rule settings that passed the walk-forward
    sess = {"all": set(out.day.unique())}
    sweep = rule_sweep(out, sess, None, only=set(wf["rules_passing"])) if wf["rules_passing"] else {}
    for rid, st in sweep.items():
        st["gates"] = dict(n=st["n"] >= K.G_HO_N, net_pos=st.get("avg_net", -1) > 0 and st.get("avg_net_x2", -1) > 0,
                           q=st["q"] <= K.G_Q, account=st.get("max_dd", -1e9) > K.ACCOUNT_DD
                           and st.get("worst_month", -1e9) > K.ACCOUNT_MONTH)
        st["passes"] = all(st["gates"].values())
    res = dict(rows=len(out), sessions=len(sess["all"]), screen=screen, compare=comp, importance=imp, rules=sweep)
    (RESULTS / "holdout.json").write_text(json.dumps(res, indent=1, default=str))
    holdout_gate("done")
    log("holdout written")


if __name__ == "__main__":
    {"screen": cmd_screen, "n4": cmd_n4, "finish_wf": cmd_finish_wf, "holdout": cmd_holdout}[sys.argv[1]]()
