"""R104 statistics and gates (PREREG.md section 7). Pure functions on plain arrays / DataFrames.

A trades frame has one row per POSITION: day (date), net (Rs after costs, per lot / per unit), win (net > 0) and fold.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import r104cfg as K


def daily_pnl(t: pd.DataFrame) -> np.ndarray:
    if len(t) == 0:
        return np.zeros(0)
    return t.groupby("day").net.sum().values.astype(float)


def boot_p(x: np.ndarray, n_boot: int = K.N_BOOT, block: int = 5, seed: int = K.SEED) -> float:
    """One-sided p that the mean of x (daily P&L in time order) is <= 0: moving-block bootstrap of the demeaned series."""
    x = np.asarray(x, float)
    n = len(x)
    if n < 2:
        return 1.0
    obs = x.mean()
    if obs <= 0:
        return 1.0
    z = x - obs
    rng = np.random.default_rng(seed)
    b = min(block, n)
    nb = int(np.ceil(n / b))
    starts = rng.integers(0, n - b + 1, size=(n_boot, nb))
    idx = (starts[:, :, None] + np.arange(b)[None, None, :]).reshape(n_boot, -1)[:, :n]
    means = z[idx].mean(axis=1)
    return float((1 + np.sum(means >= obs)) / (n_boot + 1))


def bh(p) -> np.ndarray:
    """Benjamini-Hochberg q-values."""
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


def summary(t: pd.DataFrame) -> dict:
    if len(t) == 0:
        return dict(n=0, win=None, net=0.0, per_trade=None, worst=None, best5=0.0, maxdd=0.0)
    net = t.net.values.astype(float)
    eq = np.cumsum(daily_pnl(t))
    dd = float(np.min(eq - np.maximum.accumulate(np.r_[0.0, eq])[1:])) if len(eq) else 0.0
    return dict(n=int(len(t)), win=float((net > 0).mean()), net=float(net.sum()), per_trade=float(net.mean()),
                worst=float(net.min()), best5=float(np.sort(net)[-5:].sum()), maxdd=dd)


def fold_nets(t: pd.DataFrame, nfold: int) -> list:
    return [float(t.loc[t.fold == i, "net"].sum()) for i in range(nfold)]


def walk_forward(by_var: dict, nfold: int):
    """by_var: {variant: design trades}. Anchored choice: fold k >= 1 trades the variant with the best net over folds
    < k (ties: first listed). Single variant: every fold is its own. Returns (series, picks, first_scored_fold)."""
    names = list(by_var)
    if len(names) == 1:
        return by_var[names[0]], [names[0]] * nfold, 0
    fn = {v: fold_nets(by_var[v], nfold) for v in names}
    parts, picks = [], [None]
    for k in range(1, nfold):
        best = max(names, key=lambda v: (sum(fn[v][:k]), -names.index(v)))
        picks.append(best)
        tv = by_var[best]
        parts.append(tv[tv.fold == k])
    s = pd.concat(parts) if parts else by_var[names[0]].iloc[:0]
    return s, picks, 1


def entry_gates(market: str, wf: pd.DataFrame, first_fold: int, nfold: int, p: float, q: float | None,
                hold: pd.DataFrame | None = None, twin_p: float | None = None) -> dict:
    mn_d, mn_h = K.MIN_TRADES[market]
    s = summary(wf)
    fn = fold_nets(wf, nfold)[first_fold:]
    g = {
        "design_net_pos": s["net"] > 0,
        "every_fold_pos": all(x > 0 for x in fn) and len(fn) > 0,
        "min_trades_design": s["n"] >= mn_d,
        "not_top5": (s["net"] - s["best5"]) > 0 if s["n"] > 5 else False,
        "bh_q": (q is not None and q < K.GATE_Q),
    }
    if twin_p is not None:
        g["twin"] = twin_p < K.GATE_TWIN_P
    if hold is not None:
        h = summary(hold)
        g["holdout_pos"] = h["net"] > 0
        g["min_trades_holdout"] = h["n"] >= mn_h
    return g


def design_pass(g: dict) -> bool:
    keys = ("design_net_pos", "every_fold_pos", "min_trades_design", "not_top5", "bh_q")
    return all(g.get(k, False) for k in keys)
