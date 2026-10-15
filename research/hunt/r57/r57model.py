"""R57 features, models, purged walk-forward and model export (PREREG sections 4, 5 and 8).

Features are read at the signal row (the bar that closes at the fill minute's open), so every value is known before
the trade. The brain's index features come from research.brain.features (r57data.feat_<UND>.pkl); the rest are R57's
own point-in-time readings of the day store.
"""
from __future__ import annotations

from datetime import date

import numpy as np
from sklearn.ensemble import GradientBoostingRegressor, HistGradientBoostingClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

import r57cfg as K
import r57data

BRAIN = list(r57data.FEATURES_BRAIN)
OWN = ["atr14d_pct", "minute", "dte", "vix", "vix_chg_pct", "pcr_oi", "iv_atm", "iv_skew"]
IDX = [f"idx_{u}" for u in K.UNDS]
BASE = BRAIN + OWN + IDX
SIGNED = ("ret_15m", "ret_60m", "ret_day", "gap_pct", "dist_tpavg_pct", "dist_prev_close_pct", "or30_state")
SETUPS4 = ("S1", "S2", "S3", "S4")
B_TIME_NAMES = ("t_0945", "t_1030", "t_1200", "t_1330")


def names_a(with_c4: bool = False) -> list:
    return BASE + ["side"] + [f"setup_{s}" for s in SETUPS4 + (("C4",) if with_c4 else ())]


def names_b() -> list:
    return BASE + list(B_TIME_NAMES)


def base_row(und: str, D, frow: int) -> dict:
    """The unsigned base features of index `und` on day store D at row frow (all known at frow's close)."""
    f = r57data.load_feat(und)
    arr = f["days"].get(D.d)
    out = {}
    for i, n in enumerate(f["names"]):
        out[n] = float(arr[frow, i]) if arr is not None else np.nan
    c = D.idx[3, :frow + 1]
    c = c[np.isfinite(c)]
    px = float(c[-1]) if len(c) else np.nan
    out["atr14d_pct"] = D.atr / px * 100 if np.isfinite(px) and px > 0 else np.nan
    out["minute"] = float(frow)
    out["dte"] = float(D.dte)
    v = float(D.vix[frow]) if D.vix is not None else np.nan
    out["vix"] = v
    out["vix_chg_pct"] = (v / D.vix_prev - 1) * 100 if np.isfinite(v) and np.isfinite(D.vix_prev) and D.vix_prev > 0 \
        else np.nan
    ctx = D.ctx or {}
    out["pcr_oi"] = float(ctx["pcr5"][frow]) if "pcr5" in ctx else np.nan
    out["iv_atm"] = float(ctx["iv_atm"][frow]) if "iv_atm" in ctx else np.nan
    out["iv_skew"] = float(ctx["iv_skew"][frow]) if "iv_skew" in ctx else np.nan
    for u in K.UNDS:
        out[f"idx_{u}"] = 1.0 if u == und else 0.0
    return out


def row_a(und: str, D, frow: int, side: str, setup: str) -> dict:
    """Family A: base features, directional ones signed by the trade's side, the side and the setup."""
    r = base_row(und, D, frow)
    s = 1.0 if side == "C" else -1.0
    for n in SIGNED:
        r[n] = r[n] * s
    p = r["pos_in_day_range"]
    r["pos_in_day_range"] = (1.0 - p if s > 0 else p) if np.isfinite(p) else np.nan   # distance from its own edge
    r["side"] = s
    for x in SETUPS4 + ("C4",):
        r[f"setup_{x}"] = 1.0 if x == setup else 0.0
    return r


def row_b(und: str, D, frow: int) -> dict:
    r = base_row(und, D, frow)
    for n, t in zip(B_TIME_NAMES, (K.row(9, 45), K.row(10, 30), K.row(12, 0), K.row(13, 30))):
        r[n] = 1.0 if frow + 1 == t else 0.0
    return r


def matrix(rows: list, names: list) -> np.ndarray:
    return np.array([[r.get(n, np.nan) for n in names] for r in rows], dtype=np.float64).reshape(len(rows), len(names))


# ------------------------------------------------------------------------------------------------ models
def make(kind: str):
    if kind == "gbc":
        return HistGradientBoostingClassifier(max_depth=3, max_iter=150, learning_rate=0.05, min_samples_leaf=40,
                                              l2_regularization=1.0, early_stopping=False, random_state=K.SEED)
    if kind == "logit":
        return Pipeline([("imp", SimpleImputer(strategy="median", keep_empty_features=True)),
                         ("sc", StandardScaler()), ("lr", LogisticRegression(C=0.1, max_iter=5000))])
    if kind == "gbr":
        # HistGradientBoosting has no Huber loss, so A3 uses GradientBoostingRegressor (median-imputed inputs).
        return Pipeline([("imp", SimpleImputer(strategy="median", keep_empty_features=True)),
                         ("gb", GradientBoostingRegressor(loss="huber", max_depth=3, n_estimators=150,
                                                          learning_rate=0.05, min_samples_leaf=40,
                                                          random_state=K.SEED))])
    raise ValueError(kind)


def fit(kind: str, X: np.ndarray, y: np.ndarray):
    m = make(kind)
    m.fit(X, y)
    return m


def predict(kind: str, m, X: np.ndarray) -> np.ndarray:
    if not len(X):
        return np.zeros(0)
    return m.predict(X) if kind == "gbr" else m.predict_proba(X)[:, 1]


def auc(y, s):
    y, s = np.asarray(y), np.asarray(s, float)
    ok = np.isfinite(s)
    y, s = y[ok], s[ok]
    if len(np.unique(y)) < 2:
        return None
    return round(float(roc_auc_score(y, s)), 4)


# ------------------------------------------------------------------------------------------------ walk-forward
def month_start(y: int, m: int) -> date:
    return date(y, m, 1)


def month_end(y: int, m: int) -> date:
    nxt = date(y + 1, 1, 1) if m == 12 else date(y, m + 1, 1)
    return date.fromordinal(nxt.toordinal() - 1)


def train_mask(days, exit_days, start: date, calendar: list, embargo: int | None = None) -> np.ndarray:
    """Rows usable to train a model for a fold starting at `start`: the row's session and its label's exit session are
    before `start`, purged of the last `embargo` sessions of `calendar` before it (the embargo)."""
    embargo = K.EMBARGO_SESSIONS if embargo is None else embargo
    before = [d for d in calendar if d < start]
    if len(before) < embargo:
        return np.zeros(len(days), bool)
    cut = before[-embargo] if embargo else start
    return np.array([(d < cut) and (x < cut) for d, x in zip(days, exit_days)], bool)


def walk(days: np.ndarray, X: np.ndarray, y: np.ndarray, kind: str, months: list, calendar: list, fold_fn=None):
    """Purged walk-forward with monthly refits. Returns (scores, thresholds, fold ids) aligned with the rows (NaN where
    a row is in no test month). fold_fn(model, X_train, y_train, train_idx) -> extra per-fold info (stored)."""
    scores = np.full(len(days), np.nan)
    thr = np.full(len(days), np.nan)
    info = {}
    for (yy, mm) in months:
        lo, hi = month_start(yy, mm), month_end(yy, mm)
        te = np.array([lo <= d <= hi for d in days], bool)
        if not te.any():
            continue
        tr = train_mask(days, days, lo, calendar)
        if tr.sum() < 50 or (kind != "gbr" and len(np.unique(y[tr])) < 2):
            continue
        m = fit(kind, X[tr], y[tr])
        scores[te] = predict(kind, m, X[te])
        thr[te] = np.percentile(predict(kind, m, X[tr]), 70) if kind != "gbr" else 0.0
        if fold_fn is not None:
            info[(yy, mm)] = fold_fn(m, tr)
    return scores, thr, info


def random_split_auc(X, y, kind: str, folds: int = 5) -> float | None:
    """Out-of-fold AUC of a random K-fold split (no purge): the leaky number, for comparison."""
    from sklearn.model_selection import KFold
    s = np.full(len(y), np.nan)
    for tr, te in KFold(folds, shuffle=True, random_state=K.SEED).split(X):
        if len(np.unique(y[tr])) < 2 and kind != "gbr":
            continue
        s[te] = predict(kind, fit(kind, X[tr], y[tr]), X[te])
    return s


# ------------------------------------------------------------------------------------------------ export
def export(kind: str, m, names: list, threshold: float, rule: str = ">=", meta: dict | None = None) -> dict:
    """The model as a small JSON-able dict for r57eval.score (and a later Kotlin port)."""
    out = dict(kind={"gbc": "hgb_clf", "gbr": "gb_reg", "logit": "logit"}[kind], features=list(names),
               threshold=float(threshold), rule=rule, meta=meta or {})
    if kind == "gbc":
        out["baseline"] = float(np.ravel(m._baseline_prediction)[0])
        trees = []
        for (pred,) in m._predictors:
            nodes = []
            for nd in pred.nodes:
                nodes.append([int(nd["feature_idx"]), float(nd["num_threshold"]), int(nd["missing_go_to_left"]),
                              int(nd["left"]), int(nd["right"]), float(nd["value"]), int(nd["is_leaf"])])
            trees.append(nodes)
        out["trees"] = trees
    elif kind == "gbr":
        imp, gb = m.named_steps["imp"], m.named_steps["gb"]
        out["medians"] = [float(x) for x in imp.statistics_]
        out["init"] = float(np.ravel(gb.init_.constant_)[0])
        out["lr"] = float(gb.learning_rate)
        trees = []
        for est in gb.estimators_[:, 0]:
            t = est.tree_
            nodes = []
            for i in range(t.node_count):
                leaf = t.children_left[i] == -1
                nodes.append([int(t.feature[i]) if not leaf else 0, float(t.threshold[i]) if not leaf else 0.0, 1,
                              int(t.children_left[i]), int(t.children_right[i]), float(t.value[i][0][0]), int(leaf)])
            trees.append(nodes)
        out["trees"] = trees
    else:
        imp, sc, lr = m.named_steps["imp"], m.named_steps["sc"], m.named_steps["lr"]
        out["medians"] = [float(x) for x in imp.statistics_]
        out["mean"] = [float(x) for x in sc.mean_]
        out["scale"] = [float(x) if x > 0 else 1.0 for x in sc.scale_]
        out["coef"] = [float(x) for x in lr.coef_[0]]
        out["intercept"] = float(lr.intercept_[0])
    return out
