"""R131 models, selective calls, the day-clustered test, BH and the gates (PREREG sections 4-6)."""
from __future__ import annotations

import math
import warnings

import numpy as np
import pandas as pd
from scipy import stats
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.neural_network import MLPClassifier

import r131cfg as K


# ------------------------------------------------------------------------------------------------ models
class Prep:
    """Median impute + standardise + clip, fitted on TRAINING rows only."""

    def fit(self, X: np.ndarray):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            self.med = np.nanmedian(X, 0)
        self.med = np.where(np.isfinite(self.med), self.med, 0.0)
        Z = np.where(np.isfinite(X), X, self.med)
        self.mu = Z.mean(0)
        sd = Z.std(0)
        self.sd = np.where(sd > 1e-12, sd, 1.0)
        return self

    def transform(self, X: np.ndarray) -> np.ndarray:
        Z = np.where(np.isfinite(X), X, self.med)
        return np.clip((Z - self.mu) / self.sd, -K.CLIP, K.CLIP)


def fit_predict(model: str, Xtr: np.ndarray, ytr: np.ndarray, Xte: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """P(up) on the training rows (in-sample, for the confidence cut) and on the test rows."""
    Xtr = np.asarray(Xtr, dtype=np.float64)
    Xte = np.asarray(Xte, dtype=np.float64)
    if model == "GBM":
        m = HistGradientBoostingClassifier(**K.GBM).fit(Xtr, ytr)
        return m.predict_proba(Xtr)[:, 1], m.predict_proba(Xte)[:, 1]
    pr = Prep().fit(Xtr)
    A, B = pr.transform(Xtr), pr.transform(Xte)
    if model == "LR":
        m = LogisticRegression(C=K.LR_C, max_iter=2000)
    elif model == "MLP":
        m = MLPClassifier(**K.MLP)
    else:
        raise ValueError(model)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        m.fit(A, ytr)
    return m.predict_proba(A)[:, 1], m.predict_proba(B)[:, 1]


def conf_cut(p_train: np.ndarray, k: float) -> float:
    """Confidence threshold for coverage k from the model's TRAINING predictions only."""
    if k >= 1.0:
        return -1.0
    return float(np.quantile(np.abs(np.asarray(p_train) - 0.5), 1 - k))


# ------------------------------------------------------------------------------------------------ tests
def cluster_p(hit: np.ndarray, day: np.ndarray, p0: float) -> float:
    """One-sided p of hit rate > p0 with calls clustered by day (the indices move together)."""
    if len(hit) == 0:
        return 1.0
    d = pd.DataFrame({"h": np.asarray(hit, float), "d": day}).groupby("d").h.agg(["sum", "count"])
    e = d["sum"].values - p0 * d["count"].values
    den = math.sqrt(float((e ** 2).sum()))
    if den <= 0:
        return 1.0 if e.sum() <= 0 else 0.0
    return float(stats.norm.sf(e.sum() / den))


def binom_p(k: int, n: int, p0: float) -> float:
    return float(stats.binomtest(int(k), int(n), p0, alternative="greater").pvalue) if n > 0 else 1.0


def bh(p: np.ndarray) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


def base_rate(y: np.ndarray) -> float:
    u = float(np.mean(y)) if len(y) else 0.5
    return max(u, 1 - u)


def evaluate(df: pd.DataFrame, p0: float, tiny: float) -> dict:
    """df: the calls (columns y, call, move, day, fold, und). Returns n, days, hit, p, fold hits, per index, tiny."""
    hit = (df.call.values == df.y.values).astype(float)
    n = len(df)
    out = {"n": int(n), "days": int(df.day.nunique()), "hit": float(hit.mean()) if n else np.nan, "p0": p0,
           "p": cluster_p(hit, df.day.values, p0), "p_binom": binom_p(int(hit.sum()), n, p0),
           "up_calls": float(df.call.mean()) if n else np.nan}
    big = df.move.abs().values >= tiny
    out["n_big"] = int(big.sum())
    out["hit_big"] = float(hit[big].mean()) if big.any() else np.nan
    out["folds"] = {}
    if "fold" in df:
        for f, g in df.assign(h=hit).groupby("fold"):
            out["folds"][f] = {"n": int(len(g)), "hit": float(g.h.mean())}
    out["und"] = {u: {"n": int(len(g)), "hit": float(g.h.mean())} for u, g in df.assign(h=hit).groupby("und")}
    return out


def auc(y, p) -> float:
    y = np.asarray(y)
    return float(roc_auc_score(y, p)) if len(np.unique(y)) == 2 else np.nan


def gate(res: dict, hold: dict | None, horizon: str, folds=tuple(K.FOLDS)) -> dict:
    """PREREG section 6: every condition, and whether all hold."""
    c = {}
    c["n_ge_100"] = res["n"] >= K.GATE_N
    c["hit_ge_60"] = bool(res["hit"] >= K.GATE_HIT) if np.isfinite(res["hit"]) else False
    c["q_le_010"] = bool(res.get("q", 1.0) <= K.GATE_Q)
    good = sum(1 for f in folds if res["folds"].get(f, {}).get("n", 0) >= K.FOLD_MIN_N
               and res["folds"][f]["hit"] >= K.GATE_HIT)
    c["folds_ge_3"] = good >= K.GATE_FOLDS
    if hold is None:
        c["holdout"] = None
    else:
        c["holdout"] = bool(hold["n"] > 0 and hold["hit"] >= K.GATE_HIT and hold["hit"] > hold["p0"])
    c["tradeable"] = horizon in K.TRADEABLE
    c["pass"] = all(v for k, v in c.items() if k != "holdout") and bool(c["holdout"])
    return c
