"""R146 prediction rules, walk-forward models and statistics (PREREG 5). Pure: frames in, arrays out.

Walk-forward: for each test block (a month for M, a year for D), train on every row whose NEXT session is before the
block's first day (so no training target reaches into the block), and predict the block's rows.
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.metrics import roc_auc_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from . import cfg as K


# ================================================================================================ rules (a side or 0)
def r_mom(move: np.ndarray) -> np.ndarray:
    return np.sign(np.nan_to_num(move)).astype(int)


def r_loc(loc: np.ndarray) -> np.ndarray:
    loc = np.asarray(loc, float)
    return np.where(loc >= K.LOC_UP, 1, np.where(loc <= K.LOC_DOWN, -1, 0)).astype(int)


def r_l30(l30: np.ndarray, min_abs: float = 0.0) -> np.ndarray:
    l30 = np.nan_to_num(np.asarray(l30, float))
    return np.where(np.abs(l30) >= max(min_abs, 1e-12), np.sign(l30), 0).astype(int)


def r_r17(loc, move, brd) -> np.ndarray:
    """Night's strong close with breadth >= 0 / <= 0 (m_brd is a mean of signs: 0 is a tie, as R3's 50%)."""
    loc, move, brd = (np.asarray(v, float) for v in (loc, move, brd))
    up = (loc >= K.LOC_UP) & (move >= 0) & (brd >= 0)
    dn = (loc <= K.LOC_DOWN) & (move <= 0) & (brd <= 0)
    return np.where(up, 1, np.where(dn, -1, 0)).astype(int)


def r_r3p(loc, move, brd, buz) -> np.ndarray:
    """R_r17 when the OI build-up's z agrees (side x z >= 0.5; unknown z = 0, as NightRules.side)."""
    s = r_r17(loc, move, brd)
    z = np.nan_to_num(np.asarray(buz, float))
    return np.where(s * z >= K.BUZ_MIN, s, 0).astype(int)


def r_asia(hsi) -> np.ndarray:
    return np.sign(np.nan_to_num(np.asarray(hsi, float))).astype(int)


def side_from_p(p: np.ndarray, edge: float = 0.0) -> np.ndarray:
    p = np.asarray(p, float)
    s = np.where(p >= 0.5, 1, -1)
    if edge > 0:
        s = np.where(np.abs(p - 0.5) >= edge, s, 0)
    return np.where(np.isfinite(p), s, 0).astype(int)


# ================================================================================================ walk-forward
def blocks(dates: pd.Series, kind: str, first: date, last: date) -> list[date]:
    """Block starts from `first` to `last` (inclusive): month starts (M) or year starts (D)."""
    out, cur = [], date(first.year, first.month if kind == "M" else 1, 1)
    while cur <= last:
        out.append(cur)
        cur = date(cur.year + (cur.month == 12), cur.month % 12 + 1, 1) if kind == "M" else date(cur.year + 1, 1, 1)
    return out


def _lr():
    return make_pipeline(SimpleImputer(strategy="median", add_indicator=True, keep_empty_features=True),
                         StandardScaler(), LogisticRegression(C=K.LR_C, max_iter=2000))


def _ridge():
    return make_pipeline(SimpleImputer(strategy="median", add_indicator=True, keep_empty_features=True),
                         StandardScaler(), Ridge(alpha=K.RIDGE_ALPHA))


def walk_forward(df: pd.DataFrame, cols: list[str], kind: str, first: date, last: date,
                 size_models: bool = True) -> pd.DataFrame:
    """OOS predictions for the rows with first <= d <= last. Columns added: p_lr, p_gb, p0_side (training mean gap's
    side), and (size_models) g_ridge, g_gb, a_ridge, a_gb (|g|), gm_train (training mean g)."""
    df = df.sort_values("d").reset_index(drop=True)
    out = []
    bl = blocks(df.d, kind, first, last)
    for i, b0 in enumerate(bl):
        b1 = bl[i + 1] if i + 1 < len(bl) else date(9999, 1, 1)
        te = df[(df.d >= b0) & (df.d < b1) & (df.d <= last)]
        if te.empty:
            continue
        tr = df[(df.nd < b0) & df.g.notna()]
        if len(tr) < 40 or tr.g.gt(0).nunique() < 2:
            continue
        X, y = tr[cols].to_numpy(float), (tr.g > 0).astype(int).to_numpy()
        Xt = te[cols].to_numpy(float)
        r = te[["d"]].copy()
        r["p_lr"] = _lr().fit(X, y).predict_proba(Xt)[:, 1]
        r["p_gb"] = HistGradientBoostingClassifier(**K.GB).fit(X, y).predict_proba(Xt)[:, 1]
        gm = float(tr.g.mean())
        r["p0_side"] = 1 if gm >= 0 else -1
        r["gm_train"] = gm
        r["am_train"] = float(tr.g.abs().mean())
        if size_models:
            r["g_ridge"] = _ridge().fit(X, tr.g.to_numpy()).predict(Xt)
            r["g_gb"] = HistGradientBoostingRegressor(**K.GB).fit(X, tr.g.to_numpy()).predict(Xt)
            r["a_ridge"] = _ridge().fit(X, tr.g.abs().to_numpy()).predict(Xt)
            r["a_gb"] = HistGradientBoostingRegressor(**K.GB).fit(X, tr.g.abs().to_numpy()).predict(Xt)
        out.append(r)
    if not out:
        return pd.DataFrame(columns=["d"])
    return pd.concat(out, ignore_index=True)


def wf_ridge_r2(df: pd.DataFrame, cols: list[str], kind: str, first: date, last: date) -> dict:
    """OOS R2 of g from ridge on `cols` (walk-forward), vs the training mean and vs zero."""
    df = df.sort_values("d").reset_index(drop=True)
    preds, ys, means = [], [], []
    bl = blocks(df.d, kind, first, last)
    for i, b0 in enumerate(bl):
        b1 = bl[i + 1] if i + 1 < len(bl) else date(9999, 1, 1)
        te = df[(df.d >= b0) & (df.d < b1) & (df.d <= last) & df.g.notna()]
        tr = df[(df.nd < b0) & df.g.notna()]
        if te.empty or len(tr) < 40:
            continue
        m = _ridge().fit(tr[cols].to_numpy(float), tr.g.to_numpy())
        preds.append(m.predict(te[cols].to_numpy(float)))
        ys.append(te.g.to_numpy())
        means.append(np.full(len(te), tr.g.mean()))
    if not preds:
        return {"n": 0}
    p, y, mu = np.concatenate(preds), np.concatenate(ys), np.concatenate(means)
    sse = float(((y - p) ** 2).sum())
    return {"n": int(len(y)), "r2_vs_train_mean": 1 - sse / float(((y - mu) ** 2).sum()),
            "r2_vs_zero": 1 - sse / float((y ** 2).sum()), "corr": float(np.corrcoef(p, y)[0, 1])}


# ================================================================================================ statistics
def hit_stats(side: np.ndarray, g: np.ndarray, p0: np.ndarray, rng: np.random.Generator,
              perms: int = K.PERMS) -> dict:
    """Hit rate of a side rule on the nights it picks, P0's hit on the same nights, and a permutation p (the sides
    shuffled across its own nights; p = (1 + #perm >= real) / (1 + perms))."""
    side, g, p0 = np.asarray(side, int), np.asarray(g, float), np.asarray(p0, int)
    m = (side != 0) & np.isfinite(g)
    n = int(m.sum())
    if n == 0:
        return {"n": 0}
    s, y, b = side[m], np.sign(g[m]), p0[m]
    hit = float(np.mean(s == y))
    hit0 = float(np.mean(b == y))
    sims = np.array([np.mean(rng.permutation(s) == y) for _ in range(perms)])
    p = (1 + int((sims >= hit - 1e-12).sum())) / (1 + perms)
    mean_g_signed = float(np.mean(s * g[m]))
    return {"n": n, "hit": hit, "hit_p0": hit0, "p_perm": p, "mean_signed_gap_bps": mean_g_signed * 1e4,
            "share_up_picks": float(np.mean(s > 0))}


def auc_stats(p: np.ndarray, g: np.ndarray, p0: np.ndarray, rng: np.random.Generator, perms: int = K.PERMS) -> dict:
    p, g = np.asarray(p, float), np.asarray(g, float)
    m = np.isfinite(p) & np.isfinite(g)
    y = (g[m] > 0).astype(int)
    if m.sum() < 10 or y.min() == y.max():
        return {"n": int(m.sum())}
    auc = float(roc_auc_score(y, p[m]))
    sims = np.array([roc_auc_score(rng.permutation(y), p[m]) for _ in range(perms)])
    pp = (1 + int((sims >= auc - 1e-12).sum())) / (1 + perms)
    side = side_from_p(p[m])
    base = float(np.mean(y))
    eps = 1e-6
    q = np.clip(p[m], eps, 1 - eps)
    ll = float(-np.mean(y * np.log(q) + (1 - y) * np.log(1 - q)))
    ll0 = float(-np.mean(y * np.log(max(base, eps)) + (1 - y) * np.log(max(1 - base, eps))))
    return {"n": int(m.sum()), "auc": auc, "p_perm": pp, "hit": float(np.mean(side == np.sign(g[m]))),
            "hit_p0": float(np.mean(np.asarray(p0, int)[m] == np.sign(g[m]))),
            "logloss": ll, "logloss_oracle_base_rate": ll0, "brier": float(np.mean((p[m] - y) ** 2))}


def bh(p: list[float]) -> list[float]:
    """Benjamini-Hochberg q-values (NaN p -> q NaN, left out of the count)."""
    p = np.asarray(p, float)
    ok = np.flatnonzero(np.isfinite(p))
    q = np.full(len(p), np.nan)
    if not ok.size:
        return q.tolist()
    pv = p[ok]
    n = len(pv)
    o = np.argsort(pv)
    run = 1.0
    qq = np.empty(n)
    for i in range(n - 1, -1, -1):
        run = min(run, pv[o[i]] * n / (i + 1))
        qq[o[i]] = run
    q[ok] = qq
    return q.tolist()
