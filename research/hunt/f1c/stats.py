"""F1C statistics: Benjamini-Hochberg, Spearman with p-values, random-twin p-values, day-level summaries."""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats as st


def bh(p) -> np.ndarray:
    """Benjamini-Hochberg q-values (NaN p stays NaN and is not counted in the family)."""
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    m = int(ok.sum())
    if not m:
        return q
    pv = p[ok]
    order = np.argsort(pv)
    ranked = pv[order] * m / np.arange(1, m + 1)
    ranked = np.minimum.accumulate(ranked[::-1])[::-1]
    out = np.empty(m)
    out[order] = np.minimum(ranked, 1.0)
    q[ok] = out
    return q


def spearman(x, y, min_n: int = 200) -> tuple[float, float, int]:
    """Rank correlation, two-sided p, n (finite pairs only). NaN when fewer than min_n pairs or x is constant."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    ok = np.isfinite(x) & np.isfinite(y)
    n = int(ok.sum())
    if n < min_n or np.nanstd(x[ok]) == 0:
        return np.nan, np.nan, n
    r, p = st.spearmanr(x[ok], y[ok])
    return float(r), float(p), n


def decile_spread(x, y) -> float:
    """Mean of y in the top decile of x minus the bottom decile (y's units)."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    ok = np.isfinite(x) & np.isfinite(y)
    if ok.sum() < 100:
        return np.nan
    lo, hi = np.nanquantile(x[ok], [0.1, 0.9])
    if lo == hi:
        return np.nan
    return float(np.mean(y[ok][x[ok] >= hi]) - np.mean(y[ok][x[ok] <= lo]))


def twin_p(rule_mean: float, pool: np.ndarray, n: int, sims: int = 4000, seed: int = 0) -> float:
    """One-sided p: share of random twin sets (n trades drawn from the twin pool) whose mean is >= the rule's."""
    pool = np.asarray(pool, float)
    pool = pool[np.isfinite(pool)]
    if n <= 0 or len(pool) < 20 or not np.isfinite(rule_mean):
        return np.nan
    rng = np.random.default_rng(seed)
    means = np.array([pool[rng.integers(0, len(pool), n)].mean() for _ in range(sims)])
    return float((1 + (means >= rule_mean).sum()) / (sims + 1))


def t_p(x) -> tuple[float, float]:
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if len(x) < 3 or x.std(ddof=1) == 0:
        return np.nan, np.nan
    t, p = st.ttest_1samp(x, 0.0)
    return float(t), float(p)


def max_drawdown(pnl_by_day: pd.Series) -> float:
    eq = pnl_by_day.cumsum()
    return float((eq.cummax().clip(lower=0) - eq).max()) if len(eq) else 0.0
