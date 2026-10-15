"""R24 pattern miner (PREREG.md Step 2): itemsets of 1-3 binned items, their support and lift, and a small tree.
Pure functions on arrays, so the tests can plant a pattern in synthetic data and check it is found. 13 Oct 2026.

Z: (rows, items) int8 codes, -1 = missing (that row is in no cell of that item). Every itemset is counted with
np.bincount over the combined cell code, so all 1-3 item combinations of ~26 items run in minutes on ~2M rows.
"""
from __future__ import annotations

from itertools import combinations

import numpy as np
import pandas as pd


def mine(Z: np.ndarray, nbins: list[int], names: list[str], day: np.ndarray, win: np.ndarray, lose: np.ndarray,
         nets: dict[str, np.ndarray], max_items: int = 3, min_support: float = 0.005, min_days: int = 100,
         skip_pairs: set | None = None) -> pd.DataFrame:
    """Every itemset (1..max_items items from different columns) with support >= min_support and >= min_days distinct
    days. day: int day id per row (0..D-1). win / lose: bool per row. nets: name -> float per row (means reported).
    skip_pairs: item-name pairs never combined (e.g. two views of the same quantity)."""
    Z = np.asarray(Z)
    N, M = Z.shape
    D = int(day.max()) + 1 if len(day) else 1
    W, L = win.astype(np.float64), lose.astype(np.float64)
    base_w, base_l = W.mean(), L.mean()
    tw, tl = W.sum(), L.sum()
    cols64 = [Z[:, j].astype(np.int64) for j in range(M)]
    valid = [Z[:, j] >= 0 for j in range(M)]
    netv = {k: np.nan_to_num(np.asarray(v, np.float64)) for k, v in nets.items()}
    rows = []
    for size in range(1, max_items + 1):
        for combo in combinations(range(M), size):
            if skip_pairs and any((names[a], names[b]) in skip_pairs or (names[b], names[a]) in skip_pairs
                                  for a, b in combinations(combo, 2)):
                continue
            ok = valid[combo[0]].copy()
            for j in combo[1:]:
                ok &= valid[j]
            idx = np.flatnonzero(ok)
            if len(idx) < min_support * N:
                continue
            cell = np.zeros(len(idx), np.int64)
            mult = 1
            mults = []
            for j in combo:
                cell += cols64[j][idx] * mult
                mults.append(mult)
                mult *= nbins[j]
            ncell = mult
            n = np.bincount(cell, minlength=ncell)
            keep = np.flatnonzero(n >= min_support * N)
            if not len(keep):
                continue
            dd = np.bincount(cell * D + day[idx], minlength=ncell * D).reshape(ncell, D)
            ndays = (dd[keep] > 0).sum(axis=1)
            keep2 = ndays >= min_days
            keep, ndays = keep[keep2], ndays[keep2]
            if not len(keep):
                continue
            wins = np.bincount(cell, weights=W[idx], minlength=ncell)[keep]
            loss = np.bincount(cell, weights=L[idx], minlength=ncell)[keep]
            sums = {k: np.bincount(cell, weights=v[idx], minlength=ncell)[keep] for k, v in netv.items()}
            nk = n[keep].astype(np.float64)
            for i, c in enumerate(keep):
                its = tuple((names[j], int((c // mults[q]) % nbins[j])) for q, j in enumerate(combo))
                r = dict(items=its, size=size, n=int(nk[i]), days=int(ndays[i]), support=nk[i] / N,
                         win=wins[i] / nk[i], lift_w=(wins[i] / nk[i]) / base_w if base_w > 0 else np.nan,
                         lose=loss[i] / nk[i], lift_l=(loss[i] / nk[i]) / base_l if base_l > 0 else np.nan,
                         cover_w=wins[i] / tw if tw > 0 else np.nan, cover_l=loss[i] / tl if tl > 0 else np.nan)
                for k in sums:
                    r["mean_" + k] = sums[k][i] / nk[i]
                rows.append(r)
    out = pd.DataFrame(rows)
    if len(out):
        out.attrs.update(base_w=base_w, base_l=base_l, rows=N)
    return out


def fixes_side(items: tuple, side_fixing: set) -> bool:
    return any(n in side_fixing for n, _ in items)


def tree_leaves(Z: np.ndarray, names: list[str], y: np.ndarray, extra: dict[str, np.ndarray], depth: int = 4,
                min_leaf: float = 0.02, seed: int = 24) -> pd.DataFrame:
    """A depth-limited decision tree for y (big winner) on the item codes (missing = its own code 9); one row per leaf
    with its path in item codes, rows, the rate of y and its lift, and the mean of each extra array."""
    from sklearn.tree import DecisionTreeClassifier
    X = np.where(Z < 0, 9, Z).astype(np.int16)
    t = DecisionTreeClassifier(max_depth=depth, min_samples_leaf=max(1, int(min_leaf * len(y))),
                               class_weight="balanced", random_state=seed).fit(X, y)
    leaf = t.apply(X)
    tr = t.tree_
    paths = {}

    def walk(node, conds):
        if tr.children_left[node] == -1:
            paths[node] = conds
            return
        f, th = names[tr.feature[node]], tr.threshold[node]
        walk(tr.children_left[node], conds + [f"{f} <= {th:.1f}"])
        walk(tr.children_right[node], conds + [f"{f} > {th:.1f}"])
    walk(0, [])
    base = y.mean()
    rows = []
    for lf, conds in paths.items():
        m = leaf == lf
        r = dict(path=" & ".join(conds), n=int(m.sum()), rate=float(y[m].mean()), lift=float(y[m].mean() / base))
        for k, v in extra.items():
            r["mean_" + k] = float(np.nanmean(v[m]))
        rows.append(r)
    return pd.DataFrame(rows).sort_values("lift", ascending=False).reset_index(drop=True)


def bh(ps) -> np.ndarray:
    """Benjamini-Hochberg q-values."""
    ps = np.asarray(ps, dtype=float)
    n = len(ps)
    o = np.argsort(ps)
    q = np.empty(n)
    run = 1.0
    for r in range(n - 1, -1, -1):
        run = min(run, ps[o[r]] * n / (r + 1))
        q[o[r]] = run
    return q
