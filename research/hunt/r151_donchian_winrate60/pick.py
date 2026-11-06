"""R151's pure pieces (no data): the threshold rule (PREREG section 1) and the walk-forward training cut (section 3)."""
from __future__ import annotations

import numpy as np

WIN, KEEP = 0.60, 0.25


def thresholds(scores) -> np.ndarray:
    """The candidate thresholds: score quantiles 0 - 95% (39 steps), unique, ascending."""
    vals = np.array(sorted(set(float(v) for v in scores)))
    if not vals.size:
        return vals
    return np.unique(np.quantile(vals, np.linspace(0.0, 0.95, 39)))


def first_pass(curve: list, base: dict) -> dict | None:
    """The LOWEST threshold (curve ascending by 'thr') whose kept trades won >= 60%, made >= the arm's per trade and
    kept >= 25% of the arm's trades. None: no threshold does all three (the arm fails at design)."""
    for m in sorted(curve, key=lambda r: r["thr"]):
        if m.get("trades", 0) >= KEEP * base["trades"] and m.get("win", 0) >= WIN and \
                m.get("per_trade", -1e18) >= base["per_trade"]:
            return m
    return None


def train_mask(trade_days, fold_start) -> np.ndarray:
    """Which design trades train a fold's model: every trade on a day BEFORE the fold starts."""
    return np.array([d < fold_start for d in trade_days], bool)


def model_numbers(mdl, feats: list, threshold: float) -> dict:
    """A fitted M2 pipeline (median imputer, scaler, logistic) as plain numbers for the app (R72Picker.kt)."""
    imp, sc, lr = mdl.named_steps["simpleimputer"], mdl.named_steps["standardscaler"], mdl.named_steps["logisticregression"]
    return {"feats": list(feats), "median": [float(v) for v in imp.statistics_], "mean": [float(v) for v in sc.mean_],
            "scale": [float(v) for v in sc.scale_], "coef": [float(v) for v in lr.coef_[0]],
            "intercept": float(lr.intercept_[0]), "threshold": threshold}


def score_from_numbers(nums: dict, x) -> float:
    """The app's score (R72Picker.score) from [model_numbers]: NaN -> median, standardise, logistic."""
    z = nums["intercept"]
    for v, med, mu, s, c in zip(x, nums["median"], nums["mean"], nums["scale"], nums["coef"]):
        v = med if not np.isfinite(v) else v
        z += c * (v - mu) / s
    return float(1.0 / (1.0 + np.exp(-z)))
