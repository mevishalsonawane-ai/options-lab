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
