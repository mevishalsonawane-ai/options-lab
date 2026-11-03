"""R145 statistics (PREREG_R145_NN.md section 5): per-row losses, weekly one-sided t-tests, AUC, IC, BH, the gates.

The default statistic: for each test week, the mean improvement in loss of the model over its comparator on the
out-of-sample rows (log-loss for binary targets, squared error for size and volatility); for the rank target the week's
mean per-hour Spearman IC difference. One-sided t-test across weeks (H1: improvement > 0).
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats as st

from .models import is_binary

EPS = 1e-6


def bh(p) -> np.ndarray:
    """Benjamini-Hochberg q-values (monotone, capped at 1). NaN p stay NaN and are not counted."""
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    n = int(ok.sum())
    if n == 0:
        return q
    pv = p[ok]
    o = np.argsort(pv)
    r = pv[o] * n / np.arange(1, n + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(r, 1.0)
    q[ok] = out
    return q


def row_loss(pred: np.ndarray, y: np.ndarray, binary: bool) -> np.ndarray:
    if binary:
        p = np.clip(pred, EPS, 1 - EPS)
        return -(y * np.log(p) + (1 - y) * np.log(1 - p))
    return (pred - y) ** 2


def auc(score: np.ndarray, y: np.ndarray) -> float:
    m = np.isfinite(score) & np.isfinite(y)
    s, y = score[m], y[m].astype(bool)
    n1, n0 = int(y.sum()), int((~y).sum())
    if n1 == 0 or n0 == 0:
        return np.nan
    r = st.rankdata(s)
    return float((r[y].sum() - n1 * (n1 + 1) / 2) / (n1 * n0))


def ic_by_time(ts: np.ndarray, pred: np.ndarray, y: np.ndarray, min_n: int = 10) -> pd.Series:
    """Spearman correlation of prediction and outcome across coins at each decision time."""
    d = pd.DataFrame({"ts": ts, "p": pred, "y": y}).dropna()
    d = d[d.groupby("ts").p.transform("size") >= min_n]
    if not len(d):
        return pd.Series(dtype=float)
    d["rp"] = d.groupby("ts").p.rank()
    d["ry"] = d.groupby("ts").y.rank()
    g = d.groupby("ts")
    cov = g.apply(lambda x: np.corrcoef(x.rp, x.ry)[0, 1] if x.rp.std() > 0 and x.ry.std() > 0 else 0.0,
                  include_groups=False)
    return cov


def week(ts) -> np.ndarray:
    return pd.DatetimeIndex(ts).to_period("W-SUN").start_time.values


def t_one_sided(x) -> tuple[float, float, int]:
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    n = len(x)
    if n < 3 or np.std(x, ddof=1) == 0:
        return np.nan, np.nan, n
    t = np.mean(x) / (np.std(x, ddof=1) / np.sqrt(n))
    return float(t), float(st.t.sf(t, n - 1)), n


def compare(df: pd.DataFrame, target: str, model: str, comp: str, fold_col: str = "fold") -> dict:
    """Model vs comparator on one target over the OOS rows in df (columns 'model|target', target, ts, fold)."""
    y = df[target].to_numpy(float)
    pm = df[f"{model}|{target}"].to_numpy(float)
    pc = df[f"{comp}|{target}"].to_numpy(float) if comp != "ZERO" else np.zeros(len(df))
    m = np.isfinite(y) & np.isfinite(pm) & np.isfinite(pc)
    d = df.loc[m, ["ts", fold_col]].copy()
    y, pm, pc = y[m], pm[m], pc[m]
    kind = target.split("_")[1]
    res = dict(target=target, model=model, comp=comp, n=int(m.sum()))
    if kind == "xs":
        icm = ic_by_time(d.ts.values, pm, y)
        icc = ic_by_time(d.ts.values, pc, y) if comp not in ("B0", "ZERO") else icm * 0.0
        diff = (icm - icc.reindex(icm.index).fillna(0.0))
        wk = pd.Series(diff.values, index=week(diff.index)).groupby(level=0).mean()
        t, p, nw = t_one_sided(wk.values)
        fd = pd.Series(diff.values, index=d.drop_duplicates("ts").set_index("ts")[fold_col].reindex(diff.index).values)
        per_fold = fd.groupby(level=0).mean()
        res.update(effect=float(diff.mean()), model_ic=float(icm.mean()), comp_ic=float(icc.mean()),
                   n_times=int(len(icm)), weeks=nw, t=t, p=p,
                   folds_pos=int((per_fold > 0).sum()), folds=int(len(per_fold)), metric="IC")
        return res
    binary = is_binary(target)
    imp = row_loss(pc, y, binary) - row_loss(pm, y, binary)
    d["imp"] = imp
    wk = d.groupby(week(d.ts.values)).imp.mean()
    t, p, nw = t_one_sided(wk.values)
    per_fold = d.groupby(fold_col).imp.mean()
    if binary:
        a_m, a_c = auc(pm, y), (auc(pc, y) if np.std(pc) > 0 else 0.5)
        eff, extra = a_m - a_c, dict(model_auc=a_m, comp_auc=a_c, metric="AUC")
    else:
        sst = np.sum((y - y.mean()) ** 2)
        r2m = 1 - np.sum((pm - y) ** 2) / sst
        r2c = 1 - np.sum((pc - y) ** 2) / sst
        eff, extra = r2m - r2c, dict(model_r2=float(r2m), comp_r2=float(r2c), metric="R2")
    res.update(effect=float(eff), weeks=nw, t=t, p=p, folds_pos=int((per_fold > 0).sum()), folds=int(len(per_fold)),
               mean_imp=float(imp.mean()), **extra)
    return res


MIN_EFFECT = dict(AUC=0.005, R2=0.005, IC=0.01)


def passes(r: dict, q: float, min_effect: float | None = None, q_bar: float = 0.05, folds_bar: int = 4) -> bool:
    me = MIN_EFFECT[r["metric"]] if min_effect is None else min_effect
    return bool(np.isfinite(q) and q < q_bar and r.get("folds_pos", 0) >= folds_bar and r["effect"] >= me)


def bootstrap_auc_p(score, y, weeks, n_boot: int = 2000, seed: int = 0) -> tuple[float, float]:
    """One-sided weekly-block bootstrap p for AUC > 0.5: the share of resampled AUCs <= 0.5."""
    score, y, weeks = np.asarray(score, float), np.asarray(y, float), np.asarray(weeks)
    a0 = auc(score, y)
    uw, inv = np.unique(weeks, return_inverse=True)
    idx = [np.flatnonzero(inv == i) for i in range(len(uw))]
    rng = np.random.default_rng(seed)
    le = 0
    for _ in range(n_boot):
        pick = rng.integers(0, len(uw), len(uw))
        ii = np.concatenate([idx[i] for i in pick])
        if auc(score[ii], y[ii]) <= 0.5:
            le += 1
    return a0, (le + 1) / (n_boot + 1)
