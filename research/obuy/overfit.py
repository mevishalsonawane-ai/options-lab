"""Overfitting controls for many strategy x parameter variants.

walk_forward     anchored yearly: for each test year, the variant with the best score over ALL earlier years (from the
                 first year of data) is traded that year; the first `train_years` calendar years only train.
random_baseline  'coin flip with the same exits': each real trade is replaced by a random alternative from the pool
                 built for it (same day, underlying and book; random minute in the strategy's entry window; coin-flip
                 side; same strike rule; same index-stop / target distances and holding offsets; same exit set), B times.
                 p = (1 + #{null mean per trade >= real mean per trade}) / (B + 1).
bh / holm        Benjamini-Hochberg FDR q-values and Holm family-wise adjusted p-values (variants never tested get p = 1,
                 which only makes both more conservative).
spa              White's Reality Check and Hansen's SPA (consistent, studentised) on the (days x variants) matrix of
                 daily P&L against a zero benchmark (not trading), stationary bootstrap (Politis-Romano).
dsr              Deflated Sharpe Ratio (Bailey & Lopez de Prado 2014) with N trials and the variance of the trials' Sharpes.
pbo              Probability of Backtest Overfitting by CSCV (Bailey, Borwein, Lopez de Prado, Zhu 2017): S blocks, all
                 C(S, S/2) splits; PBO = share of splits where the in-sample best variant ranks below the out-of-sample median.
"""
from __future__ import annotations

import itertools
import math

import numpy as np
import pandas as pd
from scipy import stats as sst


# ------------------------------------------------------------------------------------------------ walk-forward
def walk_forward(by_year: pd.DataFrame, trades: dict, train_years=2, score="net", min_trades=None, counts=None):
    """by_year: DataFrame variants x years of net P&L. trades: {vid: trades DataFrame}.
    Returns (table rows, concatenated out-of-sample trades with column 'picked')."""
    years = sorted(by_year.columns)
    rows, parts = [], []
    for y in years[train_years:]:
        past = by_year[[c for c in years if c < y]].sum(axis=1)
        if min_trades and counts is not None:
            past = past[counts[[c for c in years if c < y]].sum(axis=1) >= min_trades]
        if past.empty:
            continue
        pick = past.idxmax()
        t = trades[pick]
        t = t[t.year == y]
        parts.append(t.assign(picked=pick))
        rows.append(dict(year=y, picked=pick, train_net=float(past[pick]), test_net=float(by_year.loc[pick, y]),
                         n_test=len(t)))
    oos = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()
    return rows, oos


# ------------------------------------------------------------------------------------------------ random baseline
def random_baseline(real: pd.DataFrame, pool: pd.DataFrame, B=2000, seed=3):
    """real: the strategy's trades (column cand); pool: the same exits run on the pool pack (column parent).
    Returns dict(p, obs, null_mean, null_lo, null_hi, n_used, z)."""
    if real is None or pool is None or not len(real) or not len(pool):
        return dict(p=1.0, obs=np.nan, null_mean=np.nan, n_used=0)
    rng = np.random.default_rng(seed)
    grp = pool.groupby("parent").net.apply(np.asarray)
    use = real[real.cand.isin(grp.index)]
    if len(use) < 5:
        return dict(p=1.0, obs=np.nan, null_mean=np.nan, n_used=len(use))
    arrs = [grp[c] for c in use.cand.values]
    sizes = np.array([len(a) for a in arrs])
    flat = np.concatenate(arrs)
    offs = np.concatenate([[0], np.cumsum(sizes)[:-1]])
    pick = offs[None, :] + (rng.random((B, len(arrs))) * sizes[None, :]).astype(np.int64)
    null = flat[pick].mean(axis=1)
    # drawing 1 of K alternatives understates the null's spread by (K-1)/K in variance: widen it back
    kh = len(sizes) / np.sum(1.0 / sizes)
    if kh > 1:
        null = null.mean() + (null - null.mean()) * np.sqrt(kh / (kh - 1))
    obs = float(use.net.mean())
    p = (1 + int((null >= obs).sum())) / (B + 1)
    return dict(p=p, obs=obs, null_mean=float(null.mean()), null_lo=float(np.quantile(null, 0.025)),
                null_hi=float(np.quantile(null, 0.975)), n_used=len(use), z=float((obs - null.mean()) / (null.std() + 1e-12)))


# ------------------------------------------------------------------------------------------------ multiple testing
def bh(p):
    p = np.asarray(p, dtype=float)
    m = len(p)
    if m == 0:
        return p
    o = np.argsort(p)
    q = p[o] * m / np.arange(1, m + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(m)
    out[o] = np.minimum(q, 1.0)
    return out


def holm(p):
    p = np.asarray(p, dtype=float)
    m = len(p)
    if m == 0:
        return p
    o = np.argsort(p)
    a = p[o] * (m - np.arange(m))
    a = np.maximum.accumulate(a)
    out = np.empty(m)
    out[o] = np.minimum(a, 1.0)
    return out


def _stationary_counts(T, B, mean_block, rng):
    """(B, T) counts of how often each day is drawn by the stationary bootstrap."""
    p = 1.0 / mean_block
    counts = np.zeros((B, T))
    for b in range(B):
        idx = np.empty(T, dtype=np.int64)
        idx[0] = rng.integers(T)
        jump = rng.random(T) < p
        newp = rng.integers(0, T, T)
        for t in range(1, T):
            idx[t] = newp[t] if jump[t] else (idx[t - 1] + 1) % T
        counts[b] = np.bincount(idx, minlength=T)
    return counts


def spa(X: np.ndarray, B=1000, mean_block=5.0, seed=4):
    """X: (T days, N variants) daily P&L (excess over the benchmark). Returns dict(rc_p, spa_p, best, t_best)."""
    X = np.asarray(X, dtype=float)
    T, N = X.shape
    rng = np.random.default_rng(seed)
    mu = X.mean(axis=0)
    counts = _stationary_counts(T, B, mean_block, rng)
    mus = counts @ X / T                                   # (B, N) bootstrap means
    omega = np.sqrt(T) * mus.std(axis=0, ddof=1)
    omega = np.where(omega > 0, omega, np.inf)
    # White's Reality Check (non-studentised)
    v = np.sqrt(T) * mu.max()
    vb = np.sqrt(T) * (mus - mu).max(axis=1)
    rc_p = float((vb >= v).mean())
    # Hansen SPA_c (studentised, recentred where the variant is not clearly bad)
    t = np.sqrt(T) * mu / omega
    vs = max(t.max(), 0.0)
    thr = -np.sqrt(2 * np.log(np.log(T))) * omega / np.sqrt(T)
    g = np.where(mu >= thr, mu, 0.0)
    ts = np.sqrt(T) * (mus - g) / omega
    vsb = np.maximum(ts.max(axis=1), 0.0)
    spa_p = float((vsb >= vs).mean())
    k = int(np.argmax(t))
    return dict(rc_p=rc_p, spa_p=spa_p, best=k, t_best=float(t[k]), n=N, T=T)


def dsr(returns, sr_trials, n_trials=None, var_sr=None):
    """Deflated Sharpe Ratio of `returns` (per-period, e.g. daily) given the per-period Sharpes of the trials.
    var_sr: the variance of the trials' Sharpes to use (default: of sr_trials). lab.py passes the variance across the
    family's own variants, floored at 1/T (the sampling variance of a Sharpe estimate when every true Sharpe is 0), so
    unrelated strategies' very different Sharpes do not inflate the benchmark. Returns per-period Sharpes."""
    r = np.asarray(returns, dtype=float)
    T = len(r)
    if T < 10 or r.std(ddof=1) == 0:
        return dict(sr=np.nan, sr0=np.nan, dsr=np.nan)
    sr = r.mean() / r.std(ddof=1)
    srs = np.asarray(sr_trials, dtype=float)
    srs = srs[np.isfinite(srs)]
    N = n_trials or len(srs)
    v = var_sr if var_sr is not None else (srs.var(ddof=1) if len(srs) > 1 else 1.0 / T)
    g = 0.5772156649
    if N > 1:
        sr0 = math.sqrt(v) * ((1 - g) * sst.norm.ppf(1 - 1.0 / N) + g * sst.norm.ppf(1 - 1.0 / (N * math.e)))
    else:
        sr0 = 0.0
    sk = sst.skew(r)
    ku = sst.kurtosis(r, fisher=False)
    den = math.sqrt(max(1 - sk * sr + (ku - 1) / 4 * sr ** 2, 1e-12))
    return dict(sr=float(sr), sr0=float(sr0), dsr=float(sst.norm.cdf((sr - sr0) * math.sqrt(T - 1) / den)), skew=float(sk),
                kurt=float(ku), n=N)


def pbo(X: np.ndarray, S=16, metric="sharpe", max_combos=None, seed=5):
    """X: (T, N) daily P&L of N variants. Returns dict(pbo, n_combos, median_logit)."""
    X = np.asarray(X, dtype=float)
    T, N = X.shape
    if N < 2 or T < S * 4:
        return dict(pbo=np.nan, n_combos=0)
    edges = np.linspace(0, T, S + 1).astype(int)
    s1 = np.stack([X[a:b].sum(axis=0) for a, b in zip(edges[:-1], edges[1:])])        # (S, N)
    s2 = np.stack([(X[a:b] ** 2).sum(axis=0) for a, b in zip(edges[:-1], edges[1:])])
    n = np.diff(edges).astype(float)
    combos = list(itertools.combinations(range(S), S // 2))
    if max_combos and len(combos) > max_combos:
        rng = np.random.default_rng(seed)
        combos = [combos[i] for i in rng.choice(len(combos), max_combos, replace=False)]
    M = np.zeros((len(combos), S))
    for i, c in enumerate(combos):
        M[i, list(c)] = 1.0

    def perf(mask):
        a, b, m = mask @ s1, mask @ s2, mask @ n
        mean = a / m[:, None]
        if metric == "mean":
            return mean
        var = np.maximum(b / m[:, None] - mean ** 2, 1e-18)
        return mean / np.sqrt(var)

    is_, oos = perf(M), perf(1 - M)
    best = is_.argmax(axis=1)
    rows = np.arange(len(combos))
    # relative rank of the IS-best variant out of sample (1 = best)
    r = (oos < oos[rows, best][:, None]).sum(axis=1) + 0.5 * ((oos == oos[rows, best][:, None]).sum(axis=1) - 1) + 1
    w = r / (N + 1)
    lam = np.log(w / (1 - w))
    return dict(pbo=float((lam <= 0).mean()), n_combos=len(combos), median_logit=float(np.median(lam)))
