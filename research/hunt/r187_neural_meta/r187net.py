"""R187 network (PREREG section 2) and the uses' choices (section 3): the stacked design matrix, the training-only
imputation / scaling, the two sklearn multi-layer perceptrons (classifier: P(net > 0); regressor: expected net), the
hyperparameter choice on the inner validation quarter, the 3-seed refit, and the theta / delta choices.

Pure apart from sklearn: rows come in as dicts (r187study builds them). Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import warnings

import numpy as np

import r187cfg as K
import r187feats as F

P_FEATS = tuple(f"p_{n}" for n in ("tod", "side", "day_move", "from_open", "range_ratio", "gap", "twap_dist", "ema_slope",
                                   "ema_dist", "bar_size", "break_size", "breaks_today", "ret5d", "sr_ahead", "sr_behind",
                                   "sr_touch_ahead", "sr_crossed", "piv_ahead", "vix", "dvix", "breadth5", "f_rv", "f_gap",
                                   "f_vixchg", "f_breadth", "f_dow", "f_count", "f_vixreg")) + ("p_score",)
O_FEATS = tuple(f"o_{n}" for n in ("vix_prev", "vix_chg", "gap_dir", "width_atr", "t_sig", "expiry", "twap_dir", "bar_atr",
                                   "push", "pdhl", "logprem"))
NUM = F.COMMON + P_FEATS + O_FEATS
COLS = NUM + tuple(f"arm_{a}" for a in K.ARM_IDS) + tuple(f"und_{u}" for u in K.UNDS) + tuple(f"ex_{e}" for e in K.EXITS)
N_NUM = len(NUM)


# ================================================================================================ the matrix
def base_vector(row: dict) -> np.ndarray:
    """One trade's inputs without the exit one-hot: numeric block (NaN where missing) + arm + underlying one-hot."""
    f, st = row["feats"], row.get("study", {})
    v = [f.get(k, np.nan) for k in F.COMMON] + [st.get(k, np.nan) for k in P_FEATS + O_FEATS]
    v += [1.0 if K.ARM_ID[row["arm"]] == a else 0.0 for a in K.ARM_IDS]
    v += [1.0 if row["und"] == u else 0.0 for u in K.UNDS]
    return np.asarray(v, dtype=float)


def with_exit(bv: np.ndarray, exits) -> np.ndarray:
    """Stack a base vector (or (n, d) base matrix) with each exit's one-hot."""
    bv = np.atleast_2d(bv)
    out = []
    for e in exits:
        oh = np.zeros((bv.shape[0], len(K.EXITS)))
        oh[:, K.EXITS.index(e)] = 1.0
        out.append(np.hstack([bv, oh]))
    return np.vstack(out)


def stacked(rows: list) -> tuple:
    """(X, y_win, y_net, row index) over every row x every exit of its arm's menu with a finite net. Model.fit takes the
    rows' days through `order_of(rows, ix)` for its by-date, by-trade early-stopping split."""
    X, yw, yn, ix = [], [], [], []
    for i, r in enumerate(rows):
        bv = base_vector(r)
        for e in K.menu(r["arm"]):
            n = r["nets"].get(e, np.nan)
            if not np.isfinite(n):
                continue
            X.append(with_exit(bv, [e])[0])
            yw.append(1 if n > 0 else 0)
            yn.append(n)
            ix.append(i)
    return np.asarray(X), np.asarray(yw), np.asarray(yn, dtype=float), np.asarray(ix)


class Prep:
    """Median imputation and standardisation of the numeric block, fitted on training rows only; one-hots pass."""

    def __init__(self, X: np.ndarray):
        A = X[:, :N_NUM]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            med = np.nanmedian(A, axis=0)
        self.med = np.where(np.isfinite(med), med, 0.0)
        B = np.where(np.isfinite(A), A, self.med)
        self.mu, self.sd = B.mean(0), B.std(0)
        self.sd[~(self.sd > 1e-12)] = 1.0

    def __call__(self, X: np.ndarray) -> np.ndarray:
        A = X[:, :N_NUM]
        B = (np.where(np.isfinite(A), A, self.med) - self.mu) / self.sd
        return np.hstack([np.clip(B, -10, 10), X[:, N_NUM:]])


class Target:
    """The regressor's target: net / 1,000, winsorised at the training rows' 1st / 99th percentile."""

    def __init__(self, y: np.ndarray):
        self.lo, self.hi = np.quantile(y, K.WINSOR[0]), np.quantile(y, K.WINSOR[1])

    def __call__(self, y: np.ndarray) -> np.ndarray:
        return np.clip(y, self.lo, self.hi) / K.NET_SCALE


def order_of(rows: list, ix: np.ndarray) -> np.ndarray:
    """Per stacked row: (day ordinal * 1e6 + trade index), so the early-stopping split takes whole trades, the latest."""
    return np.array([rows[i]["day"].toordinal() * 1_000_000 + i for i in ix], dtype=np.int64)


def es_split(order: np.ndarray, frac: float) -> np.ndarray:
    """Boolean mask of the early-stopping rows: every stacked row of the latest `frac` of the trades (by date)."""
    trades = np.unique(order)
    n = max(1, int(round(frac * len(trades)))) if len(trades) > 1 else 0
    cut = trades[-n:] if n else trades[:0]
    return np.isin(order, cut)


def _fit_es(kind: str, hidden: tuple, alpha: float, seed: int, Z, y, order):
    """Amendment A (bug fix): Adam epochs with early stopping on the latest 10% of the training TRADES (all their
    stacked rows), keeping the best epoch; sklearn's own early stopping split stacked rows of one trade at random
    between fitting and stopping, so it never stopped (it memorised)."""
    import copy

    from sklearn.neural_network import MLPClassifier, MLPRegressor
    kw = {k: v for k, v in K.MLP_KW.items() if k not in ("early_stopping", "validation_fraction", "n_iter_no_change",
                                                        "max_iter")}
    cls = MLPClassifier if kind == "clf" else MLPRegressor
    m = cls(hidden_layer_sizes=hidden, alpha=alpha, random_state=seed, max_iter=1, shuffle=True, **kw)
    es = es_split(order, K.MLP_KW["validation_fraction"])
    fit, chk = (~es, es) if es.any() and (~es).any() else (np.ones(len(Z), bool), np.ones(len(Z), bool))
    best, best_m, bad = np.inf, None, 0
    for _ in range(K.MLP_KW["max_iter"]):
        if kind == "clf":
            m.partial_fit(Z[fit], y[fit], classes=np.array([0, 1]))
            q = np.clip(m.predict_proba(Z[chk])[:, 1], 1e-6, 1 - 1e-6)
            loss = float(-np.mean(y[chk] * np.log(q) + (1 - y[chk]) * np.log(1 - q)))
        else:
            m.partial_fit(Z[fit], y[fit])
            loss = float(np.mean((m.predict(Z[chk]) - y[chk]) ** 2))
        if loss < best - 1e-6:
            best, best_m, bad = loss, copy.deepcopy(m), 0
        else:
            bad += 1
            if bad >= K.MLP_KW["n_iter_no_change"]:
                break
    return best_m if best_m is not None else m


class Model:
    """The 3-seed average of one kind ('clf' / 'reg') at one setting, with its own Prep / Target."""

    def __init__(self, kind: str, hidden: tuple, alpha: float, seeds=K.SEEDS):
        self.kind, self.hidden, self.alpha, self.seeds = kind, hidden, alpha, tuple(seeds)
        self.nets = []

    def fit(self, X: np.ndarray, yw: np.ndarray, yn: np.ndarray, order: np.ndarray | None = None) -> "Model":
        self.prep = Prep(X)
        Z = self.prep(X)
        self.tgt = Target(yn)
        y = yw if self.kind == "clf" else self.tgt(yn)
        order = np.arange(len(X), dtype=np.int64) if order is None else order
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            self.nets = [_fit_es(self.kind, self.hidden, self.alpha, s, Z, y, order) for s in self.seeds]
        return self

    def predict(self, X: np.ndarray) -> np.ndarray:
        """clf: P(net > 0); reg: expected net in rupees."""
        Z = self.prep(X)
        if self.kind == "clf":
            return np.mean([m.predict_proba(Z)[:, 1] for m in self.nets], axis=0)
        return np.mean([m.predict(Z) for m in self.nets], axis=0) * K.NET_SCALE


def val_loss(kind: str, p: np.ndarray, yw: np.ndarray, yn: np.ndarray, tgt: Target) -> float:
    if kind == "clf":
        q = np.clip(p, 1e-6, 1 - 1e-6)
        return float(-np.mean(yw * np.log(q) + (1 - yw) * np.log(1 - q)))
    return float(np.mean((p / K.NET_SCALE - tgt(yn)) ** 2))


def choose_setting(kind: str, Xi, ywi, yni, Xv, ywv, ynv, oi=None) -> tuple:
    """(hidden, alpha, inner model, losses): the setting with the lowest inner-validation loss (seed 187 only)."""
    best, losses = None, {}
    for h in K.HIDDEN:
        for a in K.ALPHAS:
            m = Model(kind, h, a, seeds=(K.SEEDS[0],)).fit(Xi, ywi, yni, oi)
            loss = val_loss(kind, m.predict(Xv), ywv, ynv, m.tgt)
            losses[f"{h}|{a}"] = round(loss, 6)
            if best is None or loss < best[0]:
                best = (loss, h, a, m)
    return best[1], best[2], best[3], losses


# ================================================================================================ the uses' choices
def choose_theta(p: np.ndarray, net: np.ndarray, min_keep: float) -> float | None:
    """PREREG U1: the theta (grid) with the largest kept net among those keeping >= min_keep of the trades with a kept
    win rate above all trades'; None (no skip) when no theta beats keeping every trade. Ties: the lower theta."""
    p, net = np.asarray(p, float), np.asarray(net, float)
    n = len(net)
    if n == 0:
        return None
    win_all, net_all = float((net > 0).mean()), float(net.sum())
    best, best_net = None, net_all
    for th in K.THETAS:
        k = p >= th
        if k.sum() < min_keep * n or k.sum() == 0:
            continue
        if not float((net[k] > 0).mean()) > win_all:
            continue
        s = float(net[k].sum())
        if s > best_net + 1e-9:
            best, best_net = th, s
    return best


def exit_choice(ev: dict, base: str, delta: float) -> str:
    """PREREG U2: the exit with the highest expected net, used only when it beats the base's by more than delta."""
    e = max(ev, key=lambda k: (ev[k], k == base))
    return e if (np.isfinite(delta) and ev[e] - ev[base] > delta) else base


def choose_delta(evs: list, nets: list, base: str) -> float:
    """PREREG U2: delta (grid) with the largest validation net (each trade at its chosen exit's own net); ties: the larger
    delta (fewer switches)."""
    best, best_net = float("inf"), None
    for d in sorted(K.DELTAS, reverse=True):
        tot = 0.0
        for ev, nt in zip(evs, nets):
            e = exit_choice(ev, base, d)
            v = nt.get(e, np.nan)
            tot += v if np.isfinite(v) else nt[base]
        if best_net is None or tot > best_net + 1e-9:
            best, best_net = d, tot
    return best
