"""R160 statistics (PREREG 5, 7): OLS slope, permutation p, bootstrap, hit-rate, BH."""
from __future__ import annotations

import numpy as np
from scipy import stats as ss


def clean(x, y):
    x, y = np.asarray(x, float), np.asarray(y, float)
    m = np.isfinite(x) & np.isfinite(y)
    return x[m], y[m]


def slope(x, y) -> float:
    x, y = clean(x, y)
    if x.size < 3 or np.var(x) == 0:
        return np.nan
    xc = x - x.mean()
    return float((xc * (y - y.mean())).sum() / (xc * xc).sum())


def perm_p(x, y, n_perm: int, rng: np.random.Generator, side: str = "greater", chunk: int = 10_000) -> float:
    """Permutation p for the OLS slope of y on x (x shuffled across nights). side: 'greater' or 'two'."""
    x, y = clean(x, y)
    if x.size < 3 or np.var(x) == 0:
        return np.nan
    xc = x - x.mean()
    yc = y - y.mean()
    sxx = (xc * xc).sum()
    obs = (xc * yc).sum() / sxx
    hits, done = 0, 0
    while done < n_perm:
        k = min(chunk, n_perm - done)
        idx = np.argsort(rng.random((k, x.size)), axis=1)
        b = (xc[idx] * yc).sum(axis=1) / sxx
        hits += int((b >= obs - 1e-12).sum() if side == "greater" else (np.abs(b) >= abs(obs) - 1e-12).sum())
        done += k
    return (hits + 1) / (n_perm + 1)


def boot_diff(x1, y1, x2, y2, n: int, rng: np.random.Generator) -> tuple:
    """95% bootstrap interval of slope(x1, y1) - slope(x2, y2), nights resampled within each sample."""
    x1, y1 = clean(x1, y1)
    x2, y2 = clean(x2, y2)
    out = np.empty(n)
    for i in range(n):
        a = rng.integers(0, x1.size, x1.size)
        b = rng.integers(0, x2.size, x2.size)
        out[i] = slope(x1[a], y1[a]) - slope(x2[b], y2[b])
    out = out[np.isfinite(out)]
    return float(np.quantile(out, 0.025)), float(np.quantile(out, 0.975))


def hit_rate(x, y, min_abs: float) -> tuple:
    """Share of nights with sign(y) == sign(x) among |x| >= min_abs and y != 0; (rate, n, binomial p one-sided)."""
    x, y = clean(x, y)
    m = (np.abs(x) >= min_abs) & (y != 0)
    n = int(m.sum())
    if n == 0:
        return np.nan, 0, np.nan
    k = int((np.sign(x[m]) == np.sign(y[m])).sum())
    return k / n, n, float(ss.binomtest(k, n, 0.5, alternative="greater").pvalue)


def spearman(x, y) -> float:
    x, y = clean(x, y)
    if x.size < 3:
        return np.nan
    return float(ss.spearmanr(x, y).statistic)


def pearson(x, y) -> float:
    x, y = clean(x, y)
    if x.size < 3:
        return np.nan
    return float(np.corrcoef(x, y)[0, 1])


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts the earlier tests as p = 1 (R106 / R154's convention)."""
    p = np.asarray(p, float)
    m = len(p) if m_total is None else max(m_total, len(p))
    order = np.argsort(p)
    q = np.empty(len(p))
    prev = 1.0
    for rank in range(len(p), 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


def twin_p(actual: float, twins: np.ndarray) -> float:
    return float(((twins >= actual - 1e-9).sum() + 1) / (len(twins) + 1))
