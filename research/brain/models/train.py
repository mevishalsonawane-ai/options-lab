"""Data Brain v1, L5: train and walk-forward-test the pre-registered models (PREREG.md). Design data only.

    python -m research.brain.models.train [--und NIFTY ...] [--cache DIR] [--upload]

Per index and target (T1 range30, T2 range60, T3 daytype, T4 regime):
  1. the design rows: feature store + labels at the decision minutes, joined by split.join_forward, refused by
     holdout.assert_design if anything is after 13 Apr 2026;
  2. walk-forward (split.walk_forward: 8 test blocks after 2 training blocks, purged, 1-day embargo), refitting every
     model and baseline on each fold's training rows only;
  3. metrics vs the baselines, a session-block bootstrap p, calibration tables; one BH family over the 24 primary tests;
  4. the final models, fitted on every design row, saved to <cache>/models/brain_v1/ (and with --upload to Google
     Drive gdrive:IraAlgo-data-lake/brain_models/v1/). Never to git.
Metrics go to research/brain/models/metrics_v1.json (committed). Nothing here trades.
"""
from __future__ import annotations

import argparse
import json
import pickle
import subprocess
import sys
import time
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from .. import config, holdout, labels as labels_mod, split

HERE = Path(__file__).resolve().parent
METRICS = HERE / "metrics_v1.json"
INDICES = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX")
DRIVE = "gdrive:IraAlgo-data-lake/brain_models/v1"
VERSION = "brain_v1"

M0945 = 29                                                  # the 09:44 bar: its close is 09:45
TARGETS = {
    "range30": dict(kind="reg", label="y_range_30m", h=30, minutes=list(range(29, 330, 15))),   # 09:45 .. 14:45
    "range60": dict(kind="reg", label="y_range_60m", h=60, minutes=list(range(29, 300, 15))),   # 09:45 .. 14:15
    "daytype": dict(kind="clf", label="y_er_rest", minutes=[M0945]),
    "regime": dict(kind="clf", label="y_range_rest", minutes=[M0945]),
}
GBM = dict(max_iter=200, learning_rate=0.05, max_leaf_nodes=15, min_samples_leaf=50, l2_regularization=1.0,
           random_state=0, early_stopping=False)
N_SPLITS, MIN_TRAIN_BLOCKS, BOOT = 8, 2, 2000
MIN_SESSIONS_PER_BLOCK = 10                               # fewer design sessions: "too little design data"
REG_LIN = {"range30": ["tod_logrange_30m", "log_range_30m", "log_range_60m", "log_vix"],
           "range60": ["tod_logrange_60m", "log_range_30m", "log_range_60m", "log_vix"]}
CLF_LIN = ["er_day", "day_range_pct", "gap_pct", "log_vix", "log_range_30m", "dte_days", "prev_state"]


# --- data -------------------------------------------------------------------------------------------------------------
ALL_MINUTES = sorted({m for T in TARGETS.values() for m in T["minutes"]})
_CACHE: dict = {}


def _load_rows(base: Path, minutes: set[int], to: date) -> pd.DataFrame:
    """Rows of a per-day Parquet store (features or labels) at the given minutes of day, for days up to `to`. The store
    is read once per run at every decision minute (ALL_MINUTES) and kept for the other targets."""
    key = (str(base), to)
    if key not in _CACHE:                                   # emptied by run() for every index
        _CACHE[key] = _read_rows(base, set(ALL_MINUTES), to)
    f = _CACHE[key]
    if f.empty:
        return f
    m = (f.ts.dt.hour - 9) * 60 + f.ts.dt.minute - 15
    return f[m.isin(minutes)].reset_index(drop=True)


def _read_rows(base: Path, minutes: set[int], to: date) -> pd.DataFrame:
    files = sorted(base.glob("*/*.parquet")) if base.exists() else []
    parts = []
    for p in files:
        if date.fromisoformat(p.stem) > to:
            continue
        f = pd.read_parquet(p)
        m = (f.ts.dt.hour - 9) * 60 + f.ts.dt.minute - 15
        parts.append(f[m.isin(minutes)])
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()


def design_frame(root: Path, und: str, target: str) -> pd.DataFrame:
    """Features + labels at the target's decision minutes, design days only, with the derived columns of PREREG."""
    T = TARGETS[target]
    mins = set(T["minutes"])
    F = _load_rows(root / "features" / und, mins, holdout.DESIGN_END)
    L = _load_rows(root / "labels" / und, mins | {M0945}, holdout.DESIGN_END)
    if F.empty or L.empty:
        return pd.DataFrame()
    holdout.assert_design(F.ts)
    holdout.assert_design(L.ts)
    L = L[["ts", T["label"]] + (["y_er_rest", "y_range_rest"] if T["kind"] == "clf" else [])]
    L = L.loc[:, ~L.columns.duplicated()]
    X = split.join_forward(F, L).sort_values("ts").reset_index(drop=True)
    X["day"] = X.ts.dt.date
    with np.errstate(invalid="ignore", divide="ignore"):
        X["log_vix"] = np.log(X["vix"]) if "vix" in X else np.nan
        X["log_range_30m"] = np.log(X["range_30m_pct"])
        X["log_range_60m"] = np.log(X["range_60m_pct"])
    for c in ("log_vix", "log_range_30m", "log_range_60m"):
        X[c] = X[c].where(np.isfinite(X[c]))
    if T["kind"] == "reg":
        with np.errstate(invalid="ignore", divide="ignore"):
            X["target"] = np.log(X[T["label"]])
        X["target"] = X.target.where(np.isfinite(X.target))
        # tod_logrange_* is the log of a FRACTION of the close; the target is the log of a PERCENT: + log(100).
        # (14 Oct 2026: the first full run compared the target with the unconverted value; fixed before reporting.)
        X["base_tod"] = X[f"tod_logrange_{T['h']}m"] + np.log(100.0)
        X["base_last"] = X[f"log_range_{T['h']}m"]
        X["vix_h"] = np.log(X.vix * np.sqrt(T["h"] / 375 / 252)) if "vix" in X else np.nan
        X["vix_h"] = X.vix_h.where(np.isfinite(X.vix_h))
        return X.dropna(subset=["target", "base_tod", "base_last"]).reset_index(drop=True)
    # classifiers: one row per session at 09:45
    D = X.sort_values("day").reset_index(drop=True)
    if target == "daytype":
        D["target"] = (D.y_er_rest >= 0.5).astype(float).where(D.y_er_rest.notna())
    else:
        with np.errstate(invalid="ignore", divide="ignore"):
            lr = np.log(D.y_range_rest).where(D.y_range_rest > 0)
        thr = lr.rolling(60, min_periods=30).median().shift(1)        # earlier sessions' finished labels only
        D["target"] = (lr > thr).astype(float).where(lr.notna() & thr.notna())
    D["prev_state"] = D.target.shift(1)                                 # yesterday's day type / regime (known at 09:45)
    D = D.drop(columns=["y_er_rest", "y_range_rest"])
    return D.dropna(subset=["target"]).reset_index(drop=True)


def feature_cols(X: pd.DataFrame) -> list[str]:
    drop = {"ts", "precision", "day", "target", "base_tod", "base_last", "vix_h"}
    return [c for c in X.columns if c not in drop and not c.startswith(labels_mod.PREFIX)]


# --- models -----------------------------------------------------------------------------------------------------------
class Lin:
    """Standardised linear model with training-median imputation (ridge or logistic)."""

    def __init__(self, cols, kind):
        self.cols, self.kind = cols, kind

    def _x(self, X):
        A = X[self.cols].to_numpy(float)
        A = np.where(np.isfinite(A), A, self.med)
        return (A - self.mu) / self.sd

    def fit(self, X, y):
        import warnings
        from sklearn.linear_model import LogisticRegression, Ridge
        A = X[self.cols].to_numpy(float)
        with warnings.catch_warnings():                     # a column with no value in training (e.g. VIX before 2021)
            warnings.simplefilter("ignore", RuntimeWarning)
            self.med = np.nan_to_num(np.nanmedian(np.where(np.isfinite(A), A, np.nan), axis=0))
        A = np.where(np.isfinite(A), A, self.med)
        self.mu, self.sd = A.mean(0), A.std(0) + 1e-12
        self.m = Ridge(alpha=1.0) if self.kind == "reg" else LogisticRegression(C=1.0, max_iter=1000)
        self.m.fit((A - self.mu) / self.sd, y)
        return self

    def predict(self, X):
        return self.m.predict(self._x(X)) if self.kind == "reg" else self.m.predict_proba(self._x(X))[:, 1]


class Gbm:
    def __init__(self, kind):
        self.kind = kind

    def fit(self, X, y):
        from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
        cols = feature_cols(X)
        A = X[cols].to_numpy(np.float32)
        self.cols = [c for c, ok in zip(cols, np.isfinite(A).any(0)) if ok]
        self.m = (HistGradientBoostingRegressor(loss="absolute_error", **GBM) if self.kind == "reg"
                  else HistGradientBoostingClassifier(**GBM))
        self.m.fit(X[self.cols].to_numpy(np.float32), y)
        return self

    def predict(self, X):
        A = X[self.cols].to_numpy(np.float32)
        return self.m.predict(A) if self.kind == "reg" else self.m.predict_proba(A)[:, 1]


def _vix_base(tr: pd.DataFrame, te: pd.DataFrame) -> np.ndarray:
    """a + b * log(VIX * sqrt(h/375/252)) fitted on the training rows; B-tod where VIX is missing."""
    ok = tr.vix_h.notna()
    out = te.base_tod.to_numpy(float).copy()
    if ok.sum() >= 50:
        b, a = np.polyfit(tr.vix_h[ok], tr.target[ok], 1)
        m = te.vix_h.notna().to_numpy()
        out[m] = a + b * te.vix_h.to_numpy(float)[m]
    return out


def _clip(p):
    return np.clip(p, 1e-4, 1 - 1e-4)


def _logloss(y, p):
    p = _clip(p)
    return -(y * np.log(p) + (1 - y) * np.log(1 - p))


# --- statistics -------------------------------------------------------------------------------------------------------
def boot_p(diff: np.ndarray, day: np.ndarray, B: int = BOOT, seed: int = 0) -> float:
    """One-sided p that the per-session mean of `diff` (baseline loss - model loss) is <= 0 (session-block bootstrap)."""
    s = pd.Series(diff).groupby(day).mean().to_numpy()
    if len(s) < 10:
        return 1.0
    rng = np.random.default_rng(seed)
    bm = s[rng.integers(0, len(s), (B, len(s)))].mean(1)
    return float((1 + (bm <= 0).sum()) / (B + 1))


def bh(ps: list[float]) -> list[float]:
    p = np.asarray(ps, float)
    n = len(p)
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1)
    return [float(x) for x in out]


def calib_reg(pred_log: np.ndarray, y_log: np.ndarray) -> list[dict]:
    """Forecast deciles: mean forecast and actual range (%), and actual/forecast quantiles."""
    q = pd.qcut(pd.Series(pred_log).rank(method="first"), 10, labels=False)
    out = []
    for k in range(10):
        m = (q == k).to_numpy()
        r = np.exp(y_log[m] - pred_log[m])
        out.append(dict(decile=k + 1, n=int(m.sum()), forecast_pct=round(float(np.exp(pred_log[m]).mean()), 4),
                        actual_pct=round(float(np.exp(y_log[m]).mean()), 4),
                        ratio_p10=round(float(np.quantile(r, .1)), 3), ratio_p50=round(float(np.median(r)), 3),
                        ratio_p90=round(float(np.quantile(r, .9)), 3)))
    return out


def calib_clf(p: np.ndarray, y: np.ndarray) -> list[dict]:
    q = pd.qcut(pd.Series(p).rank(method="first"), 10, labels=False)
    return [dict(decile=k + 1, n=int((q == k).sum()), forecast=round(float(p[(q == k).to_numpy()].mean()), 4),
                 observed=round(float(y[(q == k).to_numpy()].mean()), 4)) for k in range(10)]


# --- walk-forward -----------------------------------------------------------------------------------------------------
def walk(X: pd.DataFrame, target: str) -> dict:
    """Pooled out-of-fold predictions of every model and baseline (design only)."""
    T = TARGETS[target]
    kind = T["kind"]
    horizon = pd.Timedelta(minutes=T["h"]) if kind == "reg" else pd.Timedelta(days=1)
    lin_cols = REG_LIN[target] if kind == "reg" else CLF_LIN
    preds = {k: np.full(len(X), np.nan) for k in (["gbm", "lin", "b_tod", "b_last", "b_vix"] if kind == "reg"
                                                    else ["gbm", "lin", "b_rate", "b_yday", "b_vix"])}
    folds = []
    for tr, te in split.walk_forward(X.ts, n_splits=N_SPLITS, horizon=horizon, min_train_blocks=MIN_TRAIN_BLOCKS):
        A, Bt = X.iloc[tr], X.iloc[te]
        split.check_precision(X, tr, te)
        y = A.target.to_numpy()
        preds["gbm"][te] = Gbm(kind).fit(A, y).predict(Bt)
        preds["lin"][te] = Lin(lin_cols, kind).fit(A, y).predict(Bt)
        if kind == "reg":
            preds["b_tod"][te] = Bt.base_tod
            preds["b_last"][te] = Bt.base_last
            preds["b_vix"][te] = _vix_base(A, Bt)
        else:
            preds["b_rate"][te] = y.mean()
            preds["b_yday"][te] = np.where(Bt.prev_state.isna(), y.mean(), np.clip(Bt.prev_state, 0.05, 0.95))
            if A.log_vix.notna().sum() >= 50:
                preds["b_vix"][te] = Lin(["log_vix"], "clf").fit(A, y).predict(Bt)
            else:
                preds["b_vix"][te] = y.mean()
        folds.append(dict(train_rows=len(tr), test_rows=len(te), test_from=str(Bt.day.min()),
                          test_to=str(Bt.day.max())))
    return dict(preds=preds, folds=folds)


def evaluate(X: pd.DataFrame, target: str, w: dict) -> dict:
    from sklearn.metrics import roc_auc_score
    kind = TARGETS[target]["kind"]
    P = w["preds"]
    m = np.isfinite(P["gbm"])
    y, day = X.target.to_numpy()[m], X.day.to_numpy()[m]
    res = dict(rows=int(m.sum()), sessions=int(len(set(day))), folds=w["folds"],
               first_day=str(min(day)) if m.any() else None, last_day=str(max(day)) if m.any() else None)
    if not m.any():
        return res
    if kind == "reg":
        mae = {k: float(np.mean(np.abs(v[m] - y))) for k, v in P.items()}
        rmse = {k: float(np.sqrt(np.mean((v[m] - y) ** 2))) for k, v in P.items()}
        bases = ["b_tod", "b_last", "b_vix"]
        best = min(bases, key=lambda k: mae[k])
        loss = lambda k: np.abs(P[k][m] - y)
        res.update(mae={k: round(v, 4) for k, v in mae.items()}, rmse={k: round(v, 4) for k, v in rmse.items()},
                   best_baseline=best, skill=round(1 - mae["gbm"] / mae[best], 4),
                   skill_lin=round(1 - mae["lin"] / mae[best], 4),
                   skill_vs_tod=round(1 - mae["gbm"] / mae["b_tod"], 4),
                   p=boot_p(loss(best) - loss("gbm"), day), calibration=calib_reg(P["gbm"][m], y),
                   median_forecast_pct=round(float(np.median(np.exp(P["gbm"][m]))), 4))
    else:
        ll = {k: float(np.mean(_logloss(y, v[m]))) for k, v in P.items()}
        auc = {k: (float(roc_auc_score(y, v[m])) if len(set(y)) == 2 and np.ptp(v[m]) > 0 else 0.5)
               for k, v in P.items()}
        brier = {k: float(np.mean((v[m] - y) ** 2)) for k, v in P.items()}
        acc = {k: float(np.mean((v[m] >= 0.5) == (y == 1))) for k, v in P.items()}
        bases = ["b_rate", "b_yday", "b_vix"]
        best = min(bases, key=lambda k: ll[k])
        res.update(base_rate=round(float(y.mean()), 4), auc={k: round(v, 4) for k, v in auc.items()},
                   logloss={k: round(v, 4) for k, v in ll.items()}, brier={k: round(v, 4) for k, v in brier.items()},
                   accuracy={k: round(v, 4) for k, v in acc.items()}, best_baseline=best,
                   p=boot_p(_logloss(y, P[best][m]) - _logloss(y, P["gbm"][m]), day),
                   calibration=calib_clf(P["gbm"][m], y))
    return res


def verdict(r: dict, kind: str) -> str:
    if "q" not in r:
        return "no data"
    if kind == "reg":
        if r["skill"] >= 0.05 and r["q"] <= 0.05:
            return "predictable"
        return "weak" if r["skill"] >= 0.01 and r["q"] <= 0.05 else "not predictable"
    beats = all(r["logloss"]["gbm"] < r["logloss"][b] for b in ("b_rate", "b_yday", "b_vix"))
    if r["auc"]["gbm"] >= 0.58 and beats and r["q"] <= 0.05:
        return "predictable"
    return "weak" if r["auc"]["gbm"] >= 0.53 and r["q"] <= 0.05 else "not predictable"


# --- final fit and storage --------------------------------------------------------------------------------------------
def fit_final(X: pd.DataFrame, target: str) -> dict:
    kind = TARGETS[target]["kind"]
    y = X.target.to_numpy()
    lin_cols = REG_LIN[target] if kind == "reg" else CLF_LIN
    g, li = Gbm(kind).fit(X, y), Lin(lin_cols, kind).fit(X, y)
    return dict(version=VERSION, target=target, kind=kind, gbm=g, lin=li, features=g.cols, lin_features=lin_cols,
                design_end=str(holdout.DESIGN_END), rows=len(X), last_day=str(X.day.max()),
                decision_minutes=TARGETS[target]["minutes"], label=TARGETS[target]["label"])


def run(root: Path, unds=INDICES, out: Path | None = METRICS, save_models: bool = True, upload: bool = False,
        verbose: bool = True) -> dict:
    import sklearn
    t0 = time.time()
    tests, model_dir = [], root / "models" / VERSION
    for und in unds:
        _CACHE.clear()
        for target, T in TARGETS.items():
            X = design_frame(root, und, target)
            r = dict(und=und, target=target, kind=T["kind"])
            if len(X) < 20 * MIN_SESSIONS_PER_BLOCK or X.day.nunique() < MIN_SESSIONS_PER_BLOCK * (N_SPLITS + MIN_TRAIN_BLOCKS):
                r.update(rows=len(X), note="too little design data")
                tests.append(r)
                continue
            r.update(evaluate(X, target, walk(X, target)))
            tests.append(r)
            if save_models:
                model_dir.mkdir(parents=True, exist_ok=True)
                with open(model_dir / f"{und}_{target}.pkl", "wb") as f:
                    pickle.dump(fit_final(X, target), f)
            if verbose:
                key = r.get("skill", r.get("auc", {}).get("gbm"))
                print(f"  {und} {target}: rows {r['rows']}, sessions {r['sessions']}, "
                      f"{'skill' if T['kind'] == 'reg' else 'AUC'} {key}, p {r['p']:.4f} ({time.time() - t0:.0f}s)",
                      flush=True)
    have = [t for t in tests if "p" in t]
    for t, q in zip(have, bh([t["p"] for t in have])):
        t["q"] = round(q, 4)
    for t in tests:
        t["verdict"] = verdict(t, t["kind"])
        t["p"] = round(t["p"], 4) if "p" in t else None
    res = dict(version=VERSION, prereg="research/brain/models/PREREG.md", design_end=str(holdout.DESIGN_END),
               holdout_start=str(holdout.HOLDOUT_START), holdout_read=False, bh_family=len(have),
               walk_forward=dict(n_splits=N_SPLITS, min_train_blocks=MIN_TRAIN_BLOCKS, embargo_days=1),
               sklearn=sklearn.__version__, models_at=f"<cache>/models/{VERSION}/ and {DRIVE}/", tests=tests)
    if out:
        out.write_text(json.dumps(res, indent=1) + "\n")
    if upload and save_models and model_dir.exists():
        subprocess.run(["rclone", "copy", str(model_dir), DRIVE, "--checksum", "-q"], check=True)
        if verbose:
            print(f"models uploaded to {DRIVE}")
    return res


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.models.train")
    ap.add_argument("--und", nargs="*", default=list(INDICES))
    ap.add_argument("--cache", default=None)
    ap.add_argument("--upload", action="store_true", help=f"copy the model files to {DRIVE} (rclone)")
    a = ap.parse_args(argv)
    run(config.paths(a.cache).root, a.und, upload=a.upload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
