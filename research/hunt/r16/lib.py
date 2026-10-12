"""R16 shared pieces: walk-forward by month with embargo, the two pre-registered models, bootstrap tests."""
from __future__ import annotations

import warnings

import numpy as np
import pandas as pd

warnings.filterwarnings("ignore")
EMBARGO = 5
B = 2000
LGB = dict(n_estimators=200, learning_rate=0.03, num_leaves=15, min_child_samples=50, subsample=0.8,
           subsample_freq=1, verbose=-1, n_jobs=4)


def model(kind, task):
    from sklearn.linear_model import LogisticRegression, Ridge
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    from sklearn.impute import SimpleImputer
    if kind == "linear":
        est = Ridge(alpha=1.0) if task == "reg" else LogisticRegression(C=1.0, max_iter=2000)
        return make_pipeline(SimpleImputer(strategy="median"), StandardScaler(), est)
    import lightgbm as lgb
    return lgb.LGBMRegressor(**LGB) if task == "reg" else lgb.LGBMClassifier(**LGB)


def folds(days, first_test, last_day=None, warm_months=6):
    """Yield (test_month, train_day_set_end_exclusive, test_mask_days) over sorted unique session dates.
    Train = sessions before the test month's first session minus EMBARGO sessions."""
    days = np.array(sorted(set(days)))
    months = pd.PeriodIndex(pd.to_datetime(days), freq="M")
    um = months.unique()
    start = um[min(warm_months, len(um) - 1)]
    if first_test is not None:
        start = max(start, pd.Period(first_test, "M"))
    for m in um:
        if m < start:
            continue
        idx = np.where(months == m)[0]
        cut = idx[0] - EMBARGO
        if cut <= 20:
            continue
        test_days = days[idx]
        if last_day is not None:
            test_days = test_days[test_days <= last_day]
            if not len(test_days):
                continue
        yield m, days[cut], set(test_days)


def walk(df, feats, target, kind, task, first_test=None, last_day=None, extra=None):
    """Monthly expanding walk-forward. df has column 'day' (date). Returns Series of OOS predictions (index of df).
    extra(train_df, test_df) -> dict of extra columns (e.g. in-fold fitted baselines)."""
    pred = pd.Series(np.nan, index=df.index)
    ext = {}
    for m, train_end, test_days in folds(df.day.values, first_test, last_day):
        tr = df[(df.day < train_end) & df[target].notna()]
        te = df[df.day.isin(test_days)]
        if len(tr) < 100 or te.empty:
            continue
        if kind is not None:
            mdl = model(kind, task)
            X = tr[feats].astype(float)
            if kind == "linear":
                mdl.fit(X, tr[target].values)
            else:
                mdl.fit(X, tr[target].values)
            Xt = te[feats].astype(float)
            pred.loc[te.index] = mdl.predict(Xt) if task == "reg" else mdl.predict_proba(Xt)[:, 1]
        if extra is not None:
            for k, v in extra(tr, te).items():
                ext.setdefault(k, pd.Series(np.nan, index=df.index)).loc[te.index] = v
    return pred, ext


def boot_mean_p(diff, groups, rng=None):
    """One-sided p that the mean of per-group mean differences is <= 0 (diff > 0 = model better)."""
    rng = rng or np.random.default_rng(16)
    g = pd.Series(diff).groupby(np.asarray(groups)).mean().values
    n = len(g)
    bm = g[rng.integers(0, n, (B, n))].mean(1)
    return float((1 + (bm <= 0).sum()) / (B + 1)), float(g.mean())


def boot_auc_p(y, p, groups, rng=None):
    from sklearn.metrics import roc_auc_score
    rng = rng or np.random.default_rng(16)
    y, p, groups = np.asarray(y), np.asarray(p), np.asarray(groups)
    ug, inv = np.unique(groups, return_inverse=True)
    rows = [np.where(inv == i)[0] for i in range(len(ug))]
    out = []
    for _ in range(B):
        pick = np.concatenate([rows[i] for i in rng.integers(0, len(ug), len(ug))])
        if len(np.unique(y[pick])) < 2:
            continue
        out.append(roc_auc_score(y[pick], p[pick]))
    out = np.array(out)
    return float((1 + (out <= 0.5).sum()) / (len(out) + 1)), float(roc_auc_score(y, p))


def clf_metrics(y, p, base):
    from sklearn.metrics import roc_auc_score
    y, p = np.asarray(y), np.clip(np.asarray(p), 1e-4, 1 - 1e-4)
    b = np.clip(np.full(len(y), base), 1e-4, 1 - 1e-4)
    ll = lambda q: float(-np.mean(y * np.log(q) + (1 - y) * np.log(1 - q)))
    return dict(n=int(len(y)), auc=float(roc_auc_score(y, p)), acc=float(np.mean((p >= 0.5) == y)),
                acc_base=float(np.mean((b >= 0.5) == y)), ll=ll(p), ll_base=ll(b),
                brier=float(np.mean((p - y) ** 2)), brier_base=float(np.mean((b - y) ** 2)))


def calib(y, p, bins=10):
    q = pd.qcut(p, bins, duplicates="drop")
    t = pd.DataFrame({"p": p, "y": y}).groupby(q, observed=True).agg(n=("y", "size"), pred=("p", "mean"), hit=("y", "mean"))
    return t.round(3)


def crps_ens(ens, y):
    """CRPS of an empirical ensemble (1-D array) for one observation."""
    x = np.sort(ens)
    n = len(x)
    t1 = np.mean(np.abs(x - y))
    # E|X-X'| = 2/n^2 * sum (2i - n - 1) x_i  (i = 1..n)
    t2 = 2.0 / n ** 2 * np.sum((2 * np.arange(1, n + 1) - n - 1) * x)
    return t1 - 0.5 * t2


def bh(p):
    p = np.asarray(p, dtype=float)
    n = len(p)
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1)
    return out
