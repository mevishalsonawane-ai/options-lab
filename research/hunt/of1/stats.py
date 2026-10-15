"""OF1 statistics: OLS slope with Newey-West (HAC) errors, Benjamini-Hochberg, Spearman. numpy/scipy only."""
from __future__ import annotations

import numpy as np
from scipy import stats as st


def ols_hac(y, x, lag: int = 1) -> dict:
    """y = a + b x + e. Returns b, its Newey-West standard error (Bartlett weights, `lag`), t, the two-sided p (normal) and
    n. Rows with a NaN in x or y are dropped."""
    y = np.asarray(y, dtype=float)
    x = np.asarray(x, dtype=float)
    ok = ~(np.isnan(x) | np.isnan(y))
    y, x = y[ok], x[ok]
    n = len(y)
    if n < 10 or np.std(x) == 0:
        return {"b": np.nan, "se": np.nan, "t": np.nan, "p": np.nan, "n": n}
    X = np.c_[np.ones(n), x]
    XtX_inv = np.linalg.inv(X.T @ X)
    beta = XtX_inv @ X.T @ y
    e = y - X @ beta
    u = X * e[:, None]
    S = u.T @ u
    for L in range(1, lag + 1):
        w = 1.0 - L / (lag + 1.0)
        G = u[L:].T @ u[:-L]
        S += w * (G + G.T)
    V = XtX_inv @ S @ XtX_inv
    se = float(np.sqrt(V[1, 1]))
    t = float(beta[1] / se) if se > 0 else np.nan
    p = float(2 * st.norm.sf(abs(t))) if se > 0 else np.nan
    return {"b": float(beta[1]), "se": se, "t": t, "p": p, "n": n}


def spearman(y, x) -> float:
    y = np.asarray(y, dtype=float)
    x = np.asarray(x, dtype=float)
    ok = ~(np.isnan(x) | np.isnan(y))
    if ok.sum() < 10:
        return np.nan
    return float(st.spearmanr(x[ok], y[ok]).statistic)


def bh(p) -> np.ndarray:
    """Benjamini-Hochberg adjusted q-values (NaN p stays NaN and is not counted in m)."""
    p = np.asarray(p, dtype=float)
    q = np.full(p.shape, np.nan)
    ok = ~np.isnan(p)
    pv = p[ok]
    m = len(pv)
    if m == 0:
        return q
    order = np.argsort(pv)
    ranked = pv[order] * m / np.arange(1, m + 1)
    ranked = np.minimum.accumulate(ranked[::-1])[::-1]
    out = np.empty(m)
    out[order] = np.minimum(ranked, 1.0)
    q[ok] = out
    return q


def one_sided(p_two: float, b: float, sign: float) -> float:
    """One-sided p for 'slope has the given sign' from a two-sided p."""
    if np.isnan(p_two) or np.isnan(b):
        return np.nan
    return p_two / 2 if np.sign(b) == np.sign(sign) else 1 - p_two / 2
