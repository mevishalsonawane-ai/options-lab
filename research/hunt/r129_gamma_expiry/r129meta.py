"""R129 amendment 2 (PREREG_META.md): a winners filter on the loose gamma rule's trades.

    python research/hunt/r129_gamma_expiry/r129meta.py wf        # walk-forward -> results/meta_wf.json
    python research/hunt/r129_gamma_expiry/r129meta.py holdout   # the one holdout read (after meta_wf.json is committed)

A logistic model on 10 facts known before the entry minute; a test trade is kept when its score is at or above the 70th
percentile of the training rows' scores.
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r129 as G  # noqa: E402
import r129loose as L  # noqa: E402

K, D3, C7 = G.K, G.D3, G.C7
FEATS = ["minute", "log_prem", "vix", "move_open", "range_pct", "wall_steps", "log_oi_ratio", "otm_steps", "dir", "monthly"]
BASES = ("L2", "L4")
KEEP_Q = 0.70
C_REG = 1.0
FOLDS = {"F1": (date(2023, 1, 1), date(2023, 12, 31)), "F2": (date(2024, 1, 1), date(2024, 12, 31)),
         "F3": (date(2025, 1, 1), date(2025, 12, 31)), "F4": (date(2026, 1, 1), date(2026, 3, 15))}
HOLD = (date(2026, 4, 1), date(2026, 10, 19))
RANDOM = 2000
SEED = 1292


def monthly(d: date) -> int:
    return int((pd.Timestamp(d) + pd.Timedelta(days=7)).month != d.month)


def facts(T: pd.DataFrame) -> pd.DataFrame:
    """The 10 inputs of each trade, from bars before its entry minute (and the 13:45 chain for the wall's OI ratio)."""
    rows = []
    for und, X in T.groupby("und"):
        g = D3.load_grid(und, K.HOLDOUT[1])
        LO = D3.LakeOptions(und, K.HOLDOUT[1])
        pos, step = g.pos(), K.STEP[und]
        for d, Y in X.groupby("day"):
            i = pos[d]
            h, l, c, o = g.h[i], g.l[i], g.c[i], g.o[i]
            gf = G.day_frame(LO, d, c)
            spot = D3._last(c[:G.ROW_WALL + 1])
            oi = G.oi_at(gf, G.ROW_WALL, spot, step) if gf is not None else {}
            ws = L.walls(oi, spot, step) if oi else {1: None, -1: None}
            o0 = D3._first(o)
            for r in Y.itertuples():
                pre = int(r.entry_row) - 1
                cp = D3._last(c[:pre + 1])
                k = ws.get(int(r.side))
                cc, pp = (np.nan_to_num(x) for x in oi.get(k, (np.nan, np.nan))) if k is not None else (np.nan, np.nan)
                ratio = (cc / pp if r.side > 0 else pp / cc) if (k is not None and cc > 0 and pp > 0) else np.nan
                with np.errstate(all="ignore"):
                    rng = (np.nanmax(h[:pre + 1]) - np.nanmin(l[:pre + 1])) / cp * 100
                rows.append(dict(idx=r.Index, minute=pre, log_prem=np.log(r.entry),          # vix and wall_steps are the trade's own
                                 move_open=r.side * (cp / o0 - 1) * 100, range_pct=rng,
                                 log_oi_ratio=np.log(ratio) if np.isfinite(ratio) and ratio > 0 else np.nan,
                                 otm_steps=abs(r.strike - D3.atm(cp, step)) / step, dir=float(r.side), monthly=monthly(d)))
    F = pd.DataFrame(rows).set_index("idx").sort_index()
    return T.join(F)


def fit(train: pd.DataFrame):
    """(model, scaler, imputer medians, keep threshold) from training rows only."""
    from sklearn.linear_model import LogisticRegression
    X = train[FEATS].values.astype(float)
    med = np.nanmedian(X, 0)
    med = np.where(np.isfinite(med), med, 0.0)
    X = np.where(np.isfinite(X), X, med)
    mu, sd = X.mean(0), X.std(0)
    sd = np.where(sd > 1e-12, sd, 1.0)
    y = (train.net.values > 0).astype(int)
    m = LogisticRegression(C=C_REG, max_iter=1000).fit((X - mu) / sd, y)
    s = m.predict_proba((X - mu) / sd)[:, 1]
    return m, mu, sd, med, float(np.quantile(s, KEEP_Q))


def score(model, test: pd.DataFrame) -> np.ndarray:
    m, mu, sd, med, _ = model
    X = test[FEATS].values.astype(float)
    X = np.where(np.isfinite(X), X, med)
    return m.predict_proba((X - mu) / sd)[:, 1]


def summary(K_: pd.DataFrame) -> dict:
    if not len(K_):
        return dict(n=0)
    w = K_.net > 0
    mo = K_.assign(mo=[f"{d.year}-{d.month:02d}" for d in K_.day]).groupby("mo").net.sum()
    eq = K_.sort_values("day").net.cumsum().values
    return dict(n=int(len(K_)), win=float(w.mean()), avg_net=float(K_.net.mean()), avg_net_x2=float(K_.netx2.mean()),
                avg_win=float(K_.net[w].mean()) if w.any() else 0.0, avg_loss=float(K_.net[~w].mean()) if (~w).any() else 0.0,
                total=float(K_.net.sum()), worst_month=float(mo.min()),
                max_dd=float(min((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min(), 0.0)))


def random_p(test: pd.DataFrame, n_keep: int, kept_total: float, rng) -> float:
    if n_keep == 0:
        return 1.0
    nets = test.net.values
    tot = np.array([nets[rng.choice(len(nets), n_keep, replace=False)].sum() for _ in range(RANDOM)])
    return float((1 + (tot >= kept_total).sum()) / (1 + RANDOM))


def load(base: str) -> pd.DataFrame:
    T = pd.read_parquet(G.OUT / "loose_trades.parquet")
    T = T[T.variant == base].reset_index(drop=True)
    T["day"] = pd.to_datetime(T.day).dt.date
    return facts(T)


def wf():
    from sklearn.metrics import roc_auc_score
    out, ps = {}, []
    for base in BASES:
        T = load(base)
        rng = np.random.default_rng(SEED)
        kept, oof, folds = [], [], {}
        p_parts = []
        for f, (a, b) in FOLDS.items():
            tr, te = T[T.day < a], T[(T.day >= a) & (T.day <= b)].copy()
            if not len(te) or (tr.net > 0).sum() < 3:
                folds[f] = dict(n_test=int(len(te)), note="too few training winners")
                continue
            mdl = fit(tr)
            te["s"] = score(mdl, te)
            k = te[te.s >= mdl[4]]
            kept.append(k)
            oof.append(te)
            folds[f] = dict(train=int(len(tr)), train_wins=int((tr.net > 0).sum()), n_test=int(len(te)), **summary(k),
                            all_test_avg=float(te.net.mean()))
        K_ = pd.concat(kept) if kept else T.iloc[0:0]
        O = pd.concat(oof) if oof else T.iloc[0:0]
        p = random_p(O, len(K_), float(K_.net.sum()), rng)
        auc = float(roc_auc_score((O.net > 0).astype(int), O.s)) if len(O) and (O.net > 0).any() and (O.net <= 0).any() else None
        last = fit(T[T.day < FOLDS["F4"][0]])
        out[base] = dict(kept=summary(K_), all_test=summary(O), folds=folds, auc_oof=auc, p_random=p,
                         wins_kept=float((K_.net > 0).sum() / max(1, (O.net > 0).sum())),
                         losses_kept=float((K_.net <= 0).sum() / max(1, (O.net <= 0).sum())),
                         folds_positive=int(sum(1 for v in folds.values() if v.get("total", -1) > 0)),
                         weights=dict(zip(FEATS, map(float, last[0].coef_[0]))))
        ps.append(p)
    q = C7.R3.bh(ps)
    for base, qq in zip(BASES, q):
        st = out[base]
        st["q"] = qq
        k = st["kept"]
        st["gates_wf"] = dict(net_pos=k.get("avg_net", -1) > 0 and k.get("avg_net_x2", -1) > 0, folds=st["folds_positive"] >= 3,
                              random=qq <= 0.10, auc=(st["auc_oof"] or 0) > 0.5)
        st["passes_wf"] = all(st["gates_wf"].values())
    (G.RESULTS / "meta_wf.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps({b: {k: out[b][k] for k in ("kept", "all_test", "auc_oof", "p_random", "q", "wins_kept", "losses_kept",
                                                 "folds_positive", "passes_wf")} for b in BASES}, indent=1, default=str))


def holdout():
    if not (G.RESULTS / "meta_wf.json").exists():
        raise SystemExit("the walk-forward result must be written (and committed) first")
    w = json.loads((G.RESULTS / "meta_wf.json").read_text())
    out = {}
    for base in BASES:
        T = load(base)
        tr, te = T[T.day < HOLD[0]], T[(T.day >= HOLD[0]) & (T.day <= HOLD[1])].copy()
        mdl = fit(tr)
        te["s"] = score(mdl, te)
        k = te[te.s >= mdl[4]]
        s = summary(k)
        wf_k = w[base]["kept"]
        total_n = wf_k.get("n", 0) + s.get("n", 0)
        out[base] = dict(kept=s, all_holdout=summary(te), n_kept_total=total_n,
                         passes=bool(w[base]["passes_wf"] and s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0
                                     and s.get("max_dd", -1e9) > -40_000 and s.get("worst_month", -1e9) > -13_000 and total_n >= 100))
    (G.RESULTS / "meta_holdout.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    {"wf": wf, "holdout": holdout}[sys.argv[1]]()
