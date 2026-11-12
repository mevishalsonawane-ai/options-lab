"""R163's pure pieces (no data): the models (PREREG section 5), the threshold rule (6), the exact random-skip twin and BH
(7), lot scaling, calibration bins and period summaries."""
from __future__ import annotations

import copy

import numpy as np
from scipy.stats import hypergeom
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.neural_network import MLPClassifier
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

SEED = 163
KEEPS = tuple(round(0.25 + 0.05 * i, 2) for i in range(15))          # 25% .. 95%
LGB = dict(n_estimators=300, learning_rate=0.03, num_leaves=15, min_child_samples=50, subsample=0.8, subsample_freq=1,
           colsample_bytree=0.8, reg_lambda=1.0, random_state=SEED, verbose=-1, n_jobs=4)
MLP_SEEDS = (163, 164, 165, 166, 167)
METHODS = ("G1", "G2", "N1", "E1")
CAL_BINS = (0.0, 0.40, 0.45, 0.50, 0.55, 0.60, 1.01)


# ------------------------------------------------------------------------------------------------ models
def logistic():
    """R151's M2 (P151's form)."""
    return make_pipeline(SimpleImputer(strategy="median", keep_empty_features=True), StandardScaler(),
                         LogisticRegression(C=0.1, max_iter=3000))


class MLP:
    """N1: the mean of 5 seeds' MLPs on median-imputed, standardised features."""

    def __init__(self):
        self.prep = make_pipeline(SimpleImputer(strategy="median", keep_empty_features=True), StandardScaler())
        self.nets = []

    def fit(self, X, y):
        """Early stopping on the LAST 20% of the training rows (time order, PREREG 5): one epoch at a time, the best
        validation log-loss kept, 20 epochs patience."""
        from sklearn.metrics import log_loss
        y = np.asarray(y)
        cut = int(round(0.8 * len(y)))
        self.prep.fit(X[:cut])
        Za, Zb = self.prep.transform(X[:cut]), self.prep.transform(X[cut:])
        self.nets = []
        for s in MLP_SEEDS:
            net = MLPClassifier(hidden_layer_sizes=(32, 16), alpha=0.01, learning_rate_init=0.001, max_iter=1,
                                warm_start=True, shuffle=True, random_state=s)
            best, best_net, wait = np.inf, None, 0
            for _ in range(500):
                net.fit(Za, y[:cut])
                v = log_loss(y[cut:], net.predict_proba(Zb)[:, 1], labels=[0, 1]) if len(Zb) else 0.0
                if v < best - 1e-6:
                    best, best_net, wait = v, copy.deepcopy(net), 0
                else:
                    wait += 1
                    if wait >= 20:
                        break
            self.nets.append(best_net if best_net is not None else net)
        return self

    def predict_proba(self, X):
        Z = self.prep.transform(X)
        p = np.mean([n.predict_proba(Z)[:, 1] for n in self.nets], axis=0)
        return np.column_stack([1 - p, p])


def lgb_classifier():
    import lightgbm as lgb
    return lgb.LGBMClassifier(**LGB)


def lgb_regressor():
    import lightgbm as lgb
    return lgb.LGBMRegressor(objective="huber", alpha=1000.0, **LGB)


def _logit(p):
    p = np.clip(np.asarray(p, float), 1e-6, 1 - 1e-6)
    return np.log(p / (1 - p))


class Ensemble:
    """E1: G1, N1 and R151's logistic fitted on the first 75% (time order) of the training trades, each Platt-calibrated
    on the last 25%, then averaged. X_all: all features; cols151: the column indices of R151's 28 features."""

    def __init__(self, cols151):
        self.cols151 = list(cols151)
        self.members, self.platt = [], []

    def fit(self, X, y):
        n = len(y)
        cut = int(round(0.75 * n))
        Xa, ya, Xb, yb = X[:cut], y[:cut], X[cut:], y[cut:]
        self.members = [("all", lgb_classifier().fit(Xa, ya)), ("all", MLP().fit(Xa, ya)),
                        ("151", logistic().fit(Xa[:, self.cols151], ya))]
        self.platt = []
        for kind, m in self.members:
            p = m.predict_proba(Xb if kind == "all" else Xb[:, self.cols151])[:, 1]
            self.platt.append(LogisticRegression(C=1e6, max_iter=1000).fit(_logit(p)[:, None], yb))
        return self

    def member_probs(self, X):
        out = []
        for (kind, m), pl in zip(self.members, self.platt):
            p = m.predict_proba(X if kind == "all" else X[:, self.cols151])[:, 1]
            out.append(pl.predict_proba(_logit(p)[:, None])[:, 1])
        return np.array(out)

    def predict_proba(self, X):
        p = self.member_probs(X).mean(axis=0)
        return np.column_stack([1 - p, p])


def make(method: str, cols151):
    return {"G1": lgb_classifier, "G2": lgb_regressor, "N1": MLP, "E1": lambda: Ensemble(cols151),
            "P151": logistic}[method]()


def fit_score(method: str, Xtr, ytr_win, ytr_net, Xte, cols151) -> np.ndarray:
    """Fit on the training rows (time order kept) and score the test rows: P(win), or expected net for G2."""
    m = make(method, cols151)
    if method == "G2":
        m.fit(Xtr, ytr_net)
        return m.predict(Xte), m
    if method == "P151":
        m.fit(Xtr[:, cols151], ytr_win)
        return m.predict_proba(Xte[:, cols151])[:, 1], m
    m.fit(Xtr, ytr_win)
    return m.predict_proba(Xte)[:, 1], m


# ------------------------------------------------------------------------------------------------ folds / threshold
def train_mask(days, fold_start) -> np.ndarray:
    """Training rows of a fold: every trade on a day BEFORE the fold starts."""
    return np.array([d < fold_start for d in days], bool)


def keep_thresholds(scores) -> list:
    """(keep fraction, threshold): the score quantile that keeps that share of the rows (score >= threshold)."""
    s = np.asarray(scores, float)
    return [(k, float(np.quantile(s, 1.0 - k))) for k in KEEPS]


def choose(curve: list, bar_net: float) -> dict | None:
    """PREREG 6: among curve points with net >= bar_net, the highest win rate; ties -> the lower threshold. None if none."""
    ok = [m for m in curve if m.get("trades", 0) > 0 and m["net"] >= bar_net]
    if not ok:
        return None
    return sorted(ok, key=lambda m: (-round(m["win"], 12), m["thr"]))[0]


# ------------------------------------------------------------------------------------------------ statistics
def hyper_p(n_total: int, wins_total: int, k_keep: int, wins_kept: int) -> float:
    """P(a random k_keep-subset of n_total trades, wins_total of them winners, has >= wins_kept winners). Exact."""
    if k_keep <= 0:
        return 1.0
    return float(hypergeom.sf(wins_kept - 1, n_total, wins_total, k_keep))


def net_twin_p(nets, k_keep: int, real_net: float, rng, n: int = 20000) -> float:
    nets = np.asarray(nets, float)
    if k_keep <= 0 or k_keep > len(nets):
        return 1.0
    hits = 0
    for _ in range(n):
        hits += nets[rng.choice(len(nets), size=k_keep, replace=False)].sum() >= real_net - 1e-9
    return (1 + hits) / (1 + n)


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts the other tests as p = 1."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(n, int(m_total or n))
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return q


def lots_net(gross_per_unit: float, qty: float, n_lots: int, charge_fn) -> float:
    """A trade's net at n lots from its real fills: (rX - rE) x qty x n - charges at qty x n."""
    return gross_per_unit * qty * n_lots - charge_fn(qty * n_lots)


def summ(nets, days) -> dict:
    nets = np.asarray(nets, float)
    if not len(nets):
        return {"trades": 0, "win": 0.0, "net": 0.0, "per_trade": 0.0, "worst_month": 0.0, "max_dd": 0.0}
    order = np.argsort(np.array([d.toordinal() for d in days]), kind="stable")
    eq = np.cumsum(nets[order])
    dd = float(np.max(np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:] - eq)) if len(eq) else 0.0
    mon = {}
    for v, d in zip(nets, days):
        mon[(d.year, d.month)] = mon.get((d.year, d.month), 0.0) + float(v)
    return {"trades": int(len(nets)), "win": float((nets > 0).mean()), "net": float(nets.sum()),
            "per_trade": float(nets.mean()), "worst_month": float(min(mon.values())), "max_dd": dd}


def calibration(p, y) -> list:
    p, y = np.asarray(p, float), np.asarray(y, float)
    out = []
    for lo, hi in zip(CAL_BINS[:-1], CAL_BINS[1:]):
        m = (p >= lo) & (p < hi)
        out.append({"bin": f"{lo:.2f}-{min(hi, 1.0):.2f}", "n": int(m.sum()),
                    "pred": float(p[m].mean()) if m.any() else None, "actual": float(y[m].mean()) if m.any() else None})
    return out


def auc(p, y) -> float:
    from sklearn.metrics import roc_auc_score
    y = np.asarray(y)
    return float(roc_auc_score(y, p)) if 0 < y.sum() < len(y) else float("nan")


def brier(p, y) -> float:
    return float(np.mean((np.asarray(p, float) - np.asarray(y, float)) ** 2))
