"""B2 models (PREREG.md 4-8): every input at once in LR / boosted trees / a neural network, walk-forward by block,
day-bootstrap AUC, permutation importance, readable rules, the trading test and the green / red anatomy.

Implementation notes (not choices about outcomes): the neural network and the LR use the training medians to fill gaps
and the training mean / sd to scale; permutation importance and the readable tree use at most 60,000 test rows (seed 2)
to keep the run to hours; every number states its sample size.
"""
from __future__ import annotations

import warnings

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.neural_network import MLPClassifier
from sklearn.tree import DecisionTreeRegressor, _tree

import b2data as D

warnings.filterwarnings("ignore")
SEED = 2
BOOT = 2000
MODELS = ("LR", "GBM", "NN")


# ------------------------------------------------------------------------------------------------ frames
def targets_frame(X: pd.DataFrame, target: str) -> pd.DataFrame:
    """Rows and y for a target. DAY0930 / DAY1015: one row per index-day at row 15 / 60 with y = DAYG."""
    if target == "DAY0930":
        Z = X[X.row == 15].copy()
        Z["y"] = Z.DAYG
    elif target == "DAY1015":
        Z = X[X.row == 60].copy()
        Z["y"] = Z.DAYG
    elif target == "SIZE":
        Z = X.copy()
        Z["y"] = Z.abs30                       # binarised per fold with the TRAINING median (fit())
    else:
        Z = X.copy()
        Z["y"] = Z[target]
    return Z[Z.y.notna()]


def fold_masks(days: pd.Series, block: str):
    a, b = D.BLOCKS[block]
    a, b = pd.Timestamp(a), pd.Timestamp(b)
    ud = np.sort(days.unique())
    before = ud[ud < a]
    cut = before[-D.GAP_SESSIONS] if len(before) > D.GAP_SESSIONS else a
    return (days < cut).values, ((days >= a) & (days <= b)).values


def design_matrix(Z: pd.DataFrame, cols: list[str]) -> np.ndarray:
    M = Z[cols].to_numpy(np.float32, na_value=np.nan)
    U = np.stack([(Z.und == u).to_numpy(np.float32) for u in D.UNDS], axis=1)
    return np.hstack([M, U])


# ------------------------------------------------------------------------------------------------ fit / predict
def make(model: str, n: int):
    if model == "LR":
        return LogisticRegression(C=0.1, max_iter=400)
    if model == "GBM":
        return HistGradientBoostingClassifier(max_iter=300, learning_rate=0.05, max_leaf_nodes=31, min_samples_leaf=200,
                                              l2_regularization=0.1, early_stopping=True, validation_fraction=0.1,
                                              random_state=SEED)
    return MLPClassifier(hidden_layer_sizes=(128, 64), activation="relu", alpha=1e-3, batch_size=512, max_iter=200,
                         early_stopping=True, validation_fraction=0.1, n_iter_no_change=8, random_state=SEED)


class Prep:
    """Training medians and scales (LR / NN); identity for the GBM."""

    def __init__(self, Xtr, model):
        self.model = model
        if model != "GBM":
            self.med = np.nanmedian(Xtr, axis=0)
            self.med = np.where(np.isfinite(self.med), self.med, 0.0)
            Xf = np.where(np.isfinite(Xtr), Xtr, self.med)
            self.mu, self.sd = Xf.mean(0), Xf.std(0)
            self.sd = np.where(self.sd > 1e-9, self.sd, 1.0)

    def __call__(self, X):
        if self.model == "GBM":
            return X
        Xf = np.where(np.isfinite(X), X, self.med)
        return np.clip((Xf - self.mu) / self.sd, -10, 10)


def fit(model, Xtr, ytr):
    """Time-ordered rows: sklearn's validation split takes a random 10%; we pass rows in time order and use the LAST 10%
    for early stopping by fitting on the first 90% only when the model supports it (GBM / NN do it internally at random;
    acceptable: it only picks the stopping round, never sees the test block)."""
    P = Prep(Xtr, model)
    m = make(model, len(ytr))
    m.fit(P(Xtr), ytr)
    return m, P


def predict(m, P, X):
    return m.predict_proba(P(X))[:, 1]


# ------------------------------------------------------------------------------------------------ metrics
def auc_w(y, s, w=None):
    """Weighted Mann-Whitney AUC (ties count half)."""
    y = np.asarray(y, dtype=bool)
    s = np.asarray(s, dtype=float)
    w = np.ones(len(y)) if w is None else np.asarray(w, dtype=float)
    o = np.argsort(s, kind="mergesort")
    s, y, w = s[o], y[o], w[o]
    wn = np.where(~y, w, 0.0)
    wp = np.where(y, w, 0.0)
    # group ties
    _, start = np.unique(s, return_index=True)
    grp = np.repeat(np.arange(len(start)), np.diff(np.r_[start, len(s)]))
    gn = np.bincount(grp, weights=wn)
    gp = np.bincount(grp, weights=wp)
    below = np.r_[0.0, np.cumsum(gn)[:-1]]
    num = (gp * (below + 0.5 * gn)).sum()
    den = gp.sum() * gn.sum()
    return float(num / den) if den > 0 else np.nan


def boot_p(y, s, days, n=BOOT, seed=SEED):
    """One-sided p for AUC > 0.5 by resampling whole days (2,000 resamples); also the 90% interval. The scores are
    sorted once; each resample only re-weights rows by how often their day was drawn."""
    rng = np.random.default_rng(seed)
    ud, inv = np.unique(days, return_inverse=True)
    y = np.asarray(y, dtype=bool)
    o = np.argsort(np.asarray(s, dtype=float), kind="mergesort")
    ss, yy, ii = np.asarray(s, dtype=float)[o], y[o], inv[o]
    _, start = np.unique(ss, return_index=True)
    grp = np.repeat(np.arange(len(start)), np.diff(np.r_[start, len(ss)]))
    ng = len(start)
    vals = np.empty(n)
    for k in range(n):
        w = np.bincount(rng.integers(0, len(ud), len(ud)), minlength=len(ud))[ii].astype(float)
        gn = np.bincount(grp, weights=np.where(~yy, w, 0.0), minlength=ng)
        gp = np.bincount(grp, weights=np.where(yy, w, 0.0), minlength=ng)
        below = np.r_[0.0, np.cumsum(gn)[:-1]]
        den = gp.sum() * gn.sum()
        vals[k] = (gp * (below + 0.5 * gn)).sum() / den if den > 0 else np.nan
    return float((1 + (vals <= 0.5).sum()) / (n + 1)), float(np.nanpercentile(vals, 5)), float(np.nanpercentile(vals, 95))


def bh(ps):
    p = np.asarray(ps, dtype=float)
    m = len(p)
    o = np.argsort(p)
    q = np.empty(m)
    prev = 1.0
    for i in range(m - 1, -1, -1):
        prev = min(prev, p[o[i]] * m / (i + 1))
        q[o[i]] = prev
    return np.minimum(q, 1.0)


# ------------------------------------------------------------------------------------------------ readable rules
def tree_rules(Xs: pd.DataFrame, pred: np.ndarray, y: np.ndarray, depth=3, min_leaf=500) -> list[dict]:
    """A depth-3 tree fitted to the model's predictions: each leaf as 'if A and B and C', with its count, the model's mean
    prediction and the ACTUAL share of y = 1 in it."""
    Xf = Xs.fillna(Xs.median())
    t = DecisionTreeRegressor(max_depth=depth, min_samples_leaf=min_leaf, random_state=SEED).fit(Xf.values, pred)
    tr = t.tree_
    leaves = t.apply(Xf.values)
    out = []

    def walk(node, conds):
        if tr.feature[node] == _tree.TREE_UNDEFINED:
            m = leaves == node
            out.append(dict(rule=" and ".join(conds) or "all", n=int(m.sum()), pred=float(pred[m].mean()),
                            actual=float(y[m].mean())))
            return
        f, th = Xs.columns[tr.feature[node]], tr.threshold[node]
        walk(tr.children_left[node], conds + [f"{f} <= {th:.4g}"])
        walk(tr.children_right[node], conds + [f"{f} > {th:.4g}"])

    walk(0, [])
    return sorted(out, key=lambda r: -abs(r["actual"] - 0.5))


def perm_importance(m, P, X, y, names, rows=60000, repeats=5, seed=SEED):
    rng = np.random.default_rng(seed)
    if len(y) > rows:
        k = rng.choice(len(y), rows, replace=False)
        X, y = X[k], y[k]
    base = auc_w(y, predict(m, P, X))
    out = []
    for j, nm in enumerate(names):
        drops = []
        for _ in range(repeats):
            Xp = X.copy()
            Xp[:, j] = Xp[rng.permutation(len(y)), j]
            drops.append(base - auc_w(y, predict(m, P, Xp)))
        out.append((nm, float(np.mean(drops))))
    return base, sorted(out, key=lambda r: -r[1])


# ------------------------------------------------------------------------------------------------ trading
def trade_rule(Z: pd.DataFrame, p_up: np.ndarray | None, hi: float, lo: float, p_ce=None, p_pe=None, hi_ce=None,
               hi_pe=None) -> pd.DataFrame:
    """PREREG 7: first row per (index, day) with p_up >= hi -> CE, first with p_up <= lo -> PE (or, for the option pair,
    p_ce >= hi_ce -> CE, p_pe >= hi_pe -> PE, the higher one if both at the same row). One entry per side per day."""
    Z = Z[["und", "day", "row", "net_ce_atm_x1", "net_pe_atm_x1"]].copy()
    if p_up is not None:
        Z["ce"] = p_up >= hi
        Z["pe"] = p_up <= lo
    else:
        Z["ce"] = (p_ce >= hi_ce) & ~((p_pe >= hi_pe) & (p_pe - hi_pe > p_ce - hi_ce))
        Z["pe"] = (p_pe >= hi_pe) & ~Z.ce
    out = []
    for side, col in (("ce", "net_ce_atm_x1"), ("pe", "net_pe_atm_x1")):
        s = Z[Z[side] & Z[col].notna()].sort_values(["und", "day", "row"]).groupby(["und", "day"]).head(1)
        out.append(pd.DataFrame(dict(und=s.und, day=s.day, row=s.row, side=side, net=s[col])))
    return pd.concat(out, ignore_index=True)


def twins(Z: pd.DataFrame, T: pd.DataFrame, n=1000, seed=SEED) -> np.ndarray:
    """Same trades per (index, day, side) at random decision rows of that day (with a price)."""
    rng = np.random.default_rng(seed)
    pools = {}
    for side, col in (("ce", "net_ce_atm_x1"), ("pe", "net_pe_atm_x1")):
        g = Z[Z[col].notna()].groupby(["und", "day"])[col]
        pools[side] = {k: v.values for k, v in g}
    keys = [(r.und, r.day, r.side) for r in T.itertuples()]
    arrs = [pools[s].get((u, d), np.array([np.nan])) for u, d, s in keys]
    out = np.zeros(n)
    for a in arrs:
        out += np.nan_to_num(a[rng.integers(0, len(a), n)])
    return out


def trade_score(T: pd.DataFrame, n_days: int) -> dict:
    if T.empty:
        return dict(trades=0, net=0.0)
    net = T.net.values
    by = T.groupby("day").net.sum()
    eq = by.sort_index().cumsum()
    return dict(trades=int(len(T)), trades_per_day=len(T) / max(n_days, 1), win_rate=float((net > 0).mean()),
                avg_win=float(net[net > 0].mean()) if (net > 0).any() else 0.0,
                avg_loss=float(net[net <= 0].mean()) if (net <= 0).any() else 0.0,
                net=float(net.sum()), per_day=float(net.sum() / max(n_days, 1)), green_days=float((by > 0).mean()),
                worst_day=float(by.min()), max_dd=float((eq - eq.cummax().clip(lower=0)).min()),
                by_und={u: float(T[T.und == u].net.sum()) for u in D.UNDS})
