"""A small feed-forward neural network in numpy (no torch needed): ReLU hidden layers, dropout,
L2, Adam, early stopping on a held-out tail, and an ensemble of seeds. Enough for tabular market
features; the data (a few thousand daily bars) is far too small for anything bigger."""
import numpy as np


class Scaler:
    def fit(self, X):
        self.mu = np.nanmean(X, 0)
        self.sd = np.nanstd(X, 0)
        self.sd[~np.isfinite(self.sd) | (self.sd < 1e-12)] = 1.0
        self.mu[~np.isfinite(self.mu)] = 0.0
        return self

    def transform(self, X):
        Z = (X - self.mu) / self.sd
        Z = np.nan_to_num(Z, nan=0.0, posinf=0.0, neginf=0.0)
        return np.clip(Z, -5, 5)


class MLP:
    """task='clf' (sigmoid output, log loss) or 'reg' (linear output, Huber loss)."""

    def __init__(self, hidden=(32, 16), task="clf", lr=1e-3, l2=1e-3, dropout=0.2,
                 epochs=300, batch=64, patience=25, seed=0):
        self.hidden, self.task, self.lr, self.l2 = hidden, task, lr, l2
        self.dropout, self.epochs, self.batch, self.patience = dropout, epochs, batch, patience
        self.rng = np.random.default_rng(seed)

    def _init(self, d):
        sizes = [d, *self.hidden, 1]
        self.W = [self.rng.normal(0, np.sqrt(2.0 / a), (a, b)) for a, b in zip(sizes[:-1], sizes[1:])]
        self.b = [np.zeros(b) for b in sizes[1:]]

    def _forward(self, X, train=False):
        acts, masks = [X], []
        h = X
        for i in range(len(self.W) - 1):
            h = np.maximum(h @ self.W[i] + self.b[i], 0)
            if train and self.dropout > 0:
                m = (self.rng.random(h.shape) >= self.dropout) / (1 - self.dropout)
                h = h * m
            else:
                m = None
            masks.append(m)
            acts.append(h)
        out = (h @ self.W[-1] + self.b[-1]).ravel()
        return out, acts, masks

    def _loss_grad(self, z, y):
        if self.task == "clf":
            p = 1 / (1 + np.exp(-np.clip(z, -30, 30)))
            loss = -np.mean(y * np.log(p + 1e-9) + (1 - y) * np.log(1 - p + 1e-9))
            return loss, (p - y) / len(y)
        r = z - y
        a = np.abs(r)
        loss = np.mean(np.where(a < 1, 0.5 * r * r, a - 0.5))
        return loss, np.clip(r, -1, 1) / len(y)

    def fit(self, X, y, val_frac=0.15):
        n = len(y)
        nv = max(int(n * val_frac), 10)
        Xt, yt, Xv, yv = X[:-nv], y[:-nv], X[-nv:], y[-nv:]  # time order: validate on the latest part
        self._init(X.shape[1])
        m = [np.zeros_like(w) for w in self.W] + [np.zeros_like(b) for b in self.b]
        v = [np.zeros_like(t) for t in m]
        b1, b2, step = 0.9, 0.999, 0
        best, best_params, bad = np.inf, None, 0
        for _ in range(self.epochs):
            idx = self.rng.permutation(len(yt))
            for s in range(0, len(idx), self.batch):
                j = idx[s:s + self.batch]
                z, acts, masks = self._forward(Xt[j], train=True)
                _, g = self._loss_grad(z, yt[j])
                g = g[:, None]
                gW, gb = [None] * len(self.W), [None] * len(self.b)
                for i in range(len(self.W) - 1, -1, -1):
                    gW[i] = acts[i].T @ g + self.l2 * self.W[i]
                    gb[i] = g.sum(0)
                    if i > 0:
                        g = g @ self.W[i].T
                        g = g * (acts[i] > 0)
                        if masks[i - 1] is not None:
                            g = g * (masks[i - 1] != 0) / (1 - self.dropout)
                step += 1
                params = self.W + self.b
                grads = gW + gb
                for k in range(len(params)):
                    m[k] = b1 * m[k] + (1 - b1) * grads[k]
                    v[k] = b2 * v[k] + (1 - b2) * grads[k] ** 2
                    mh = m[k] / (1 - b1 ** step)
                    vh = v[k] / (1 - b2 ** step)
                    params[k] -= self.lr * mh / (np.sqrt(vh) + 1e-8)
            zv, _, _ = self._forward(Xv)
            lv, _ = self._loss_grad(zv, yv)
            if lv < best - 1e-5:
                best, bad = lv, 0
                best_params = ([w.copy() for w in self.W], [b.copy() for b in self.b])
            else:
                bad += 1
                if bad >= self.patience:
                    break
        if best_params:
            self.W, self.b = best_params
        self.val_loss = best
        return self

    def predict(self, X):
        z, _, _ = self._forward(X)
        return 1 / (1 + np.exp(-np.clip(z, -30, 30))) if self.task == "clf" else z


class Ensemble:
    """Scales features on the training set and averages `n` networks trained from different seeds."""

    def __init__(self, n=5, **kw):
        self.n, self.kw = n, kw

    def fit(self, X, y):
        self.scaler = Scaler().fit(X)
        Z = self.scaler.transform(X)
        self.y_mu, self.y_sd = 0.0, 1.0
        if self.kw.get("task") == "reg":
            self.y_mu, self.y_sd = float(np.mean(y)), float(np.std(y) or 1.0)
            y = (y - self.y_mu) / self.y_sd
        self.models = [MLP(seed=s, **self.kw).fit(Z, y) for s in range(self.n)]
        return self

    def predict(self, X):
        Z = self.scaler.transform(X)
        p = np.mean([m.predict(Z) for m in self.models], 0)
        return p * self.y_sd + self.y_mu
