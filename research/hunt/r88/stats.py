"""R88 statistics (PREREG section 5): group differences with session-clustered standard errors, quintile cuts,
Benjamini-Hochberg."""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats as st


def cluster_diff(y, g, cluster) -> dict:
    """OLS of y on [1, g] (g boolean): diff = mean(y | g) - mean(y | not g), with a cluster-robust SE (clusters =
    sessions, small-sample factor G/(G-1) * (n-1)/(n-2)) and a t-test on G-1 degrees of freedom."""
    y, g, c = np.asarray(y, float), np.asarray(g, bool), np.asarray(cluster)
    ok = np.isfinite(y)
    y, g, c = y[ok], g[ok], c[ok]
    n1, n0 = int(g.sum()), int((~g).sum())
    if n1 < 2 or n0 < 2:
        return dict(diff=np.nan, se=np.nan, t=np.nan, p=np.nan, n1=n1, n0=n0, sessions=0)
    m1, m0 = y[g].mean(), y[~g].mean()
    u = y - np.where(g, m1, m0)
    X = np.column_stack([np.ones(len(y)), g.astype(float)])
    bread = np.linalg.inv(X.T @ X)
    codes, inv = np.unique(c, return_inverse=True)
    G = len(codes)
    s = np.zeros((G, 2))
    np.add.at(s, inv, X * u[:, None])
    meat = s.T @ s
    V = bread @ meat @ bread * (G / max(G - 1, 1)) * ((len(y) - 1) / max(len(y) - 2, 1))
    se = float(np.sqrt(V[1, 1]))
    t = (m1 - m0) / se if se > 0 else np.nan
    p = float(2 * st.t.sf(abs(t), df=max(G - 1, 1))) if np.isfinite(t) else np.nan
    return dict(diff=float(m1 - m0), se=se, t=float(t), p=p, n1=n1, n0=n0, sessions=int(G))


def one_sided(p_two: float, t: float, sign: float) -> float:
    """One-sided p-value for an effect in the direction `sign` from a two-sided p and its t."""
    if not np.isfinite(p_two) or not np.isfinite(t):
        return np.nan
    return p_two / 2 if np.sign(t) == np.sign(sign) else 1 - p_two / 2


def quintile_cuts(x) -> np.ndarray | None:
    """Cut points (20/40/60/80%) of the design values; None when the extreme quintiles cannot be told apart."""
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if len(x) < 100:
        return None
    q = np.quantile(x, [0.2, 0.4, 0.6, 0.8])
    return None if q[0] >= q[3] else q


def q1_q5(x, cuts) -> tuple[np.ndarray, np.ndarray]:
    x = np.asarray(x, float)
    return (x <= cuts[0]) & np.isfinite(x), (x > cuts[3]) & np.isfinite(x)


def bh(p) -> np.ndarray:
    """Benjamini-Hochberg q-values (NaN stays NaN)."""
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    if not ok.any():
        return q
    pv = p[ok]
    o = np.argsort(pv)
    r = pv[o] * len(pv) / np.arange(1, len(pv) + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    out = np.empty(len(pv))
    out[o] = np.minimum(r, 1.0)
    q[ok] = out
    return q


def factor_tests(k: pd.DataFrame, cont, cat, targets=("S", "D"), cuts=None, min_n=200, min_sessions=20) -> tuple:
    """Every factor x target test on the candles k. Continuous: Q5 vs Q1 (cuts given, else from k itself);
    categorical: each category with >= min_n candles on >= min_sessions sessions vs the rest.
    Returns (rows, cuts used)."""
    rows, used = [], {}
    sess = k.day.to_numpy()
    for f in cont:
        cq = (cuts or {}).get(f) if cuts is not None else quintile_cuts(k[f])
        if cq is None:
            continue
        used[f] = cq
        lo, hi = q1_q5(k[f], cq)
        sel = lo | hi
        for y in targets:
            r = cluster_diff(k[y].to_numpy()[sel], hi[sel], sess[sel])
            rows.append(dict(factor=f, level="Q5 vs Q1", target=y, **r,
                             up_hi=float(np.nanmean(k.up.to_numpy()[hi])) if hi.any() else np.nan,
                             up_lo=float(np.nanmean(k.up.to_numpy()[lo])) if lo.any() else np.nan))
    for f in cat:
        v = k[f].astype(object)
        for lev in sorted({x for x in v.dropna().unique()}):
            g = (v == lev).to_numpy()
            if g.sum() < min_n or len(set(sess[g])) < min_sessions or (~g & v.notna().to_numpy()).sum() < min_n:
                continue
            sel = v.notna().to_numpy()
            for y in targets:
                r = cluster_diff(k[y].to_numpy()[sel], g[sel], sess[sel])
                rows.append(dict(factor=f, level=str(lev), target=y, **r,
                                 up_hi=float(np.nanmean(k.up.to_numpy()[g])),
                                 up_lo=float(np.nanmean(k.up.to_numpy()[sel & ~g]))))
    return rows, used
