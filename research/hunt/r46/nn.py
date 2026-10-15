"""R46 variant B: our own MLP in the other AI's shape (12 -> 64 -> 32 -> 1, ReLU, sigmoid), in numpy, trained
WALK-FORWARD: for each test month only on bars whose 60-minute label ended before that month began. Never on test data.

Scaling (mean / SD) is fitted on each fold's training rows only. Early stopping on the last 15% of the training rows
(time order). Seed fixed. The model predicts P(BTC spot is higher 60 minutes after the entry minute)."""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import r46cfg as K


class MLP:
    def __init__(self, n_in: int, hidden=K.NN_HIDDEN, seed: int = K.NN_SEED):
        rng = np.random.default_rng(seed)
        sizes = (n_in, *hidden, 1)
        self.W = [rng.normal(0, np.sqrt(2.0 / a), (a, b)) for a, b in zip(sizes[:-1], sizes[1:])]
        self.b = [np.zeros(b) for b in sizes[1:]]
        self.mu = np.zeros(n_in)
        self.sd = np.ones(n_in)

    def _fwd(self, X):
        acts = [X]
        h = X
        for i, (W, b) in enumerate(zip(self.W, self.b)):
            z = h @ W + b
            h = np.maximum(z, 0.0) if i < len(self.W) - 1 else z
            acts.append(h)
        return acts

    def predict(self, X) -> np.ndarray:
        X = (np.asarray(X, float) - self.mu) / self.sd
        z = self._fwd(X)[-1][:, 0]
        return 1.0 / (1.0 + np.exp(-np.clip(z, -50, 50)))

    @staticmethod
    def _bce(p, y):
        p = np.clip(p, 1e-7, 1 - 1e-7)
        return float(-np.mean(y * np.log(p) + (1 - y) * np.log(1 - p)))

    def fit(self, X, y, epochs=K.NN_EPOCHS, batch=K.NN_BATCH, lr=K.NN_LR, l2=K.NN_L2, val_frac=K.NN_VAL_FRAC,
            patience=K.NN_PATIENCE, seed=K.NN_SEED):
        X, y = np.asarray(X, float), np.asarray(y, float)
        nv = int(len(X) * val_frac)
        Xt, yt, Xv, yv = X[:len(X) - nv], y[:len(X) - nv], X[len(X) - nv:], y[len(X) - nv:]
        self.mu, self.sd = Xt.mean(0), Xt.std(0)
        self.sd[self.sd == 0] = 1.0
        Xt, Xv = (Xt - self.mu) / self.sd, (Xv - self.mu) / self.sd
        rng = np.random.default_rng(seed)
        params = self.W + self.b
        m = [np.zeros_like(p) for p in params]
        v = [np.zeros_like(p) for p in params]
        t = 0
        best, best_par, bad = np.inf, None, 0
        L = len(self.W)
        for _ in range(epochs):
            order = rng.permutation(len(Xt))
            for s in range(0, len(order), batch):
                bi = order[s:s + batch]
                acts = self._fwd(Xt[bi])
                z = acts[-1][:, 0]
                p = 1.0 / (1.0 + np.exp(-np.clip(z, -50, 50)))
                g = ((p - yt[bi]) / len(bi))[:, None]
                gW, gb = [None] * L, [None] * L
                for i in range(L - 1, -1, -1):
                    gW[i] = acts[i].T @ g + l2 * self.W[i]
                    gb[i] = g.sum(0)
                    if i:
                        g = (g @ self.W[i].T) * (acts[i] > 0)
                t += 1
                for j, gr in enumerate(gW + gb):
                    m[j] = 0.9 * m[j] + 0.1 * gr
                    v[j] = 0.999 * v[j] + 0.001 * gr * gr
                    mh, vh = m[j] / (1 - 0.9 ** t), v[j] / (1 - 0.999 ** t)
                    params[j] -= lr * mh / (np.sqrt(vh) + 1e-8)
            if nv:
                loss = self._bce(1.0 / (1.0 + np.exp(-np.clip(self._fwd(Xv)[-1][:, 0], -50, 50))), yv)
                if loss < best - 1e-5:
                    best, best_par, bad = loss, [p.copy() for p in params], 0
                else:
                    bad += 1
                    if bad >= patience:
                        break
        if best_par is not None:
            for j, p in enumerate(best_par):
                params[j][...] = p
        self.val_loss = best
        return self


def walk_forward(F: pd.DataFrame, y: np.ndarray, entries: pd.DatetimeIndex, months: list[pd.Timestamp],
                 query: pd.Series) -> tuple[pd.Series, list[dict]]:
    """F: features per 5-minute bar (index = bar start), y: labels, entries: the entry minute of each bar. For each
    test month M: train on rows whose label window [entry, entry + 60 min] ends before M (and finite), predict the
    rows of `query` (bool, same index) whose entry falls inside M. Returns probabilities (NaN elsewhere) and a log."""
    X = F.to_numpy(float)
    fin = np.isfinite(X).all(1) & np.isfinite(y)
    lab_end = entries + pd.Timedelta(minutes=K.HOLD_MIN)
    prob = pd.Series(np.nan, index=F.index)
    log = []
    first = entries[fin].min() if fin.any() else None
    for M in months:
        M1 = M + pd.offsets.MonthBegin(1)
        if first is None or (M - first) < pd.Timedelta(days=30 * K.NN_MIN_TRAIN_MONTHS - 3):
            log.append(dict(month=str(M.date()), trained=False))
            continue
        tr = fin & np.asarray(lab_end < M)
        te = np.asarray(query) & np.asarray((entries >= M) & (entries < M1)) & np.isfinite(X).all(1)
        net = MLP(X.shape[1]).fit(X[tr], y[tr])
        if te.any():
            prob.iloc[np.flatnonzero(te)] = net.predict(X[te])
        log.append(dict(month=str(M.date()), trained=True, n_train=int(tr.sum()), n_query=int(te.sum()),
                        val_bce=round(float(net.val_loss), 5), base_rate=round(float(y[tr].mean()), 4)))
    return prob, log
