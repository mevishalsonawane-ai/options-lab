"""R30 models (PREREG section 4): MLP, TCN and GRU nets, their seed ensembles, and the baselines.

Every scaler, imputer and label scale here is fitted on the training rows passed to `fit` and nothing else
(tests/test_r30.py checks it). Targets are a matrix Y (rows x k) with a kind per column: "bin" (0/1, BCE) or "reg"
(MSE on the training-standardised value). NaN in Y masks that row for that column (a multi-task net learns 15/30/60
together; a row whose 60-minute window runs past the session has only the 15/30 labels).
"""
from __future__ import annotations

import time

import numpy as np

import r30cfg as K


# ------------------------------------------------------------------------------------------------ preprocessing
class Tab:
    """Training-median imputation, training mean/SD scaling, clip to +-CLIP; columns NaN on > NAN_DROP of the
    training rows are dropped. fit() sees training rows only."""

    def fit(self, X: np.ndarray) -> "Tab":
        X = np.asarray(X, dtype=np.float64)
        nan = np.isnan(X).mean(0) if len(X) else np.ones(X.shape[1])
        self.keep = np.flatnonzero(nan <= K.NAN_DROP)
        Z = X[:, self.keep]
        with np.errstate(all="ignore"):
            self.med = np.nanmedian(Z, 0)
        self.med = np.where(np.isfinite(self.med), self.med, 0.0)
        Z = np.where(np.isnan(Z), self.med, Z)
        self.mu = Z.mean(0)
        sd = Z.std(0)
        self.sd = np.where(sd > 1e-12, sd, 1.0)
        return self

    def transform(self, X: np.ndarray) -> np.ndarray:
        Z = np.asarray(X, dtype=np.float64)[:, self.keep]
        Z = np.where(np.isnan(Z), self.med, Z)
        return np.clip((Z - self.mu) / self.sd, -K.CLIP, K.CLIP).astype(np.float32)


class Seq:
    """Per-channel scaling of a sequence tensor (rows x N x C), training rows only. The last channel is the 0/1 mask
    and is left alone; missing values are 0 (never filled from later bars)."""

    def fit(self, S: np.ndarray) -> "Seq":
        S = np.asarray(S, dtype=np.float64)
        m = S[..., -1] > 0
        self.mu = np.zeros(S.shape[-1])
        self.sd = np.ones(S.shape[-1])
        for c in range(S.shape[-1] - 1):
            v = S[..., c][m]
            v = v[np.isfinite(v)]
            if len(v):
                self.mu[c] = v.mean()
                self.sd[c] = v.std() if v.std() > 1e-12 else 1.0
        return self

    def transform(self, S: np.ndarray) -> np.ndarray:
        S = np.asarray(S, dtype=np.float64).copy()
        m = S[..., -1] > 0
        for c in range(S.shape[-1] - 1):
            x = (S[..., c] - self.mu[c]) / self.sd[c]
            S[..., c] = np.clip(np.where(m & np.isfinite(x), x, 0.0), -K.CLIP * 2, K.CLIP * 2)
        return S.astype(np.float32)


# ------------------------------------------------------------------------------------------------ the nets
def _torch():
    import torch
    torch.set_num_threads(int(__import__("os").environ.get("R30_THREADS", "1")))
    return torch


def build(kind: str, n_tab: int, n_ch: int, n_out: int):
    torch = _torch()
    nn = torch.nn

    class MLP(nn.Module):
        def __init__(self):
            super().__init__()
            self.f = nn.Sequential(nn.Linear(n_tab, 64), nn.ReLU(), nn.Dropout(K.DROPOUT), nn.Linear(64, 32),
                                   nn.ReLU(), nn.Dropout(K.DROPOUT), nn.Linear(32, n_out))

        def forward(self, xt, xs):
            return self.f(xt)

    class Causal(nn.Module):
        def __init__(self, cin, cout, dil):
            super().__init__()
            self.pad = 2 * dil
            self.c = nn.Conv1d(cin, cout, 3, dilation=dil)

        def forward(self, x):                                 # x: batch x C x N; left padding only (causal)
            return self.c(nn.functional.pad(x, (self.pad, 0)))

    class TCN(nn.Module):
        def __init__(self):
            super().__init__()
            self.c = nn.ModuleList([Causal(n_ch, 16, 1), Causal(16, 16, 2), Causal(16, 16, 4)])
            self.d = nn.Dropout(K.DROPOUT)
            self.h = nn.Sequential(nn.Linear(16 + n_tab, 32), nn.ReLU(), nn.Dropout(K.DROPOUT), nn.Linear(32, n_out))

        def forward(self, xt, xs):
            x = xs.transpose(1, 2)
            for c in self.c:
                x = self.d(torch.relu(c(x)))
            return self.h(torch.cat([x[:, :, -1], xt], 1))

    class GRU(nn.Module):
        def __init__(self):
            super().__init__()
            self.g = nn.GRU(n_ch, 32, batch_first=True)
            self.h = nn.Sequential(nn.Linear(32 + n_tab, 32), nn.ReLU(), nn.Dropout(K.DROPOUT), nn.Linear(32, n_out))

        def forward(self, xt, xs):
            _, hN = self.g(xs)
            return self.h(torch.cat([hN[-1], xt], 1))

    return {"mlp": MLP, "tcn": TCN, "gru": GRU}[kind]()


def n_params(model) -> int:
    return int(sum(p.numel() for p in model.parameters()))


class Net:
    """One seed-ensemble of one architecture. fit(train) with early stopping on (val); predict -> per column:
    probabilities for "bin", the label's own units for "reg"."""

    def __init__(self, kind: str, kinds: list[str], seeds=K.SEEDS):
        self.kind, self.kinds, self.seeds = kind, list(kinds), list(seeds)

    def fit(self, Xt, Xs, Y, val, log=None):
        """Xt, Xs, Y: the training window's rows (already split: `val` is a boolean mask of its validation rows).
        Scalers and label scales are fitted on the rows with val == False only."""
        torch = _torch()
        Y = np.asarray(Y, dtype=np.float64)
        tr = ~np.asarray(val)
        self.tab = Tab().fit(Xt[tr])
        self.seq = Seq().fit(Xs[tr]) if Xs is not None else None
        self.ymu = np.zeros(Y.shape[1])
        self.ysd = np.ones(Y.shape[1])
        for j, k in enumerate(self.kinds):
            if k == "reg":
                v = Y[tr, j][np.isfinite(Y[tr, j])]
                self.ymu[j], self.ysd[j] = v.mean(), max(v.std(), 1e-9)
        T = self.tab.transform(Xt)
        S = self.seq.transform(Xs) if Xs is not None else np.zeros((len(T), 1, 1), np.float32)
        Z = (Y - self.ymu) / self.ysd
        M = np.isfinite(Z)
        Z = np.where(M, Z, 0.0)
        isbin = torch.tensor([k == "bin" for k in self.kinds])
        self.models, self.info = [], []
        idx_tr, idx_va = np.flatnonzero(tr), np.flatnonzero(~tr)
        tt = lambda a: torch.from_numpy(np.ascontiguousarray(a, dtype=np.float32))  # noqa: E731
        T_, S_, Z_, M_ = tt(T), tt(S), tt(Z), tt(M)

        def loss_fn(out, z, m):
            bce = torch.nn.functional.binary_cross_entropy_with_logits(out, z, reduction="none")
            mse = (out - z) ** 2
            el = torch.where(isbin, bce, mse) * m
            return el.sum() / m.sum().clamp(min=1.0)

        for sd in self.seeds:
            torch.manual_seed(sd)
            rng = np.random.default_rng(sd)
            mdl = build(self.kind, T.shape[1], S.shape[-1], Y.shape[1])
            opt = torch.optim.AdamW(mdl.parameters(), lr=K.LR, weight_decay=K.WD)
            best, best_state, bad, t0, ep = np.inf, None, 0, time.time(), 0
            for ep in range(K.EPOCHS):
                mdl.train()
                perm = rng.permutation(idx_tr)
                for b in range(0, len(perm), K.BATCH):
                    i = torch.from_numpy(perm[b:b + K.BATCH])
                    opt.zero_grad()
                    loss = loss_fn(mdl(T_[i], S_[i]), Z_[i], M_[i])
                    loss.backward()
                    opt.step()
                mdl.eval()
                with torch.no_grad():
                    vl = 0.0
                    if len(idx_va):
                        i = torch.from_numpy(idx_va)
                        vl = float(loss_fn(mdl(T_[i], S_[i]), Z_[i], M_[i]))
                if vl < best - 1e-5:
                    best, bad = vl, 0
                    best_state = {k: v.clone() for k, v in mdl.state_dict().items()}
                else:
                    bad += 1
                if bad >= K.PATIENCE or time.time() - t0 > K.FIT_SECONDS:
                    break
            if best_state is not None:
                mdl.load_state_dict(best_state)
            mdl.eval()
            self.models.append(mdl)
            self.info.append(dict(seed=sd, epochs=ep + 1, val_loss=best, seconds=round(time.time() - t0, 1)))
            if log:
                log(f"  {self.kind} seed {sd}: {ep + 1} epochs, val {best:.4f}, {time.time() - t0:.0f}s")
        return self

    def predict(self, Xt, Xs) -> np.ndarray:
        torch = _torch()
        T = torch.from_numpy(self.tab.transform(Xt))
        S = torch.from_numpy(self.seq.transform(Xs) if Xs is not None else np.zeros((len(T), 1, 1), np.float32))
        outs = []
        with torch.no_grad():
            for mdl in self.models:
                o = []
                for b in range(0, len(T), 4096):
                    o.append(mdl(T[b:b + 4096], S[b:b + 4096]).numpy())
                outs.append(np.concatenate(o) if o else np.zeros((0, len(self.kinds))))
        out = []
        for j, k in enumerate(self.kinds):
            if k == "bin":
                out.append(np.mean([1 / (1 + np.exp(-o[:, j])) for o in outs], 0))
            else:
                out.append(np.mean([o[:, j] for o in outs], 0) * self.ysd[j] + self.ymu[j])
        return np.column_stack(out) if out else np.zeros((len(T), 0))


# ------------------------------------------------------------------------------------------------ baselines
class Linear:
    """Logistic (bin) or ridge (reg) on the standardised tabular inputs (training-only Tab)."""

    def __init__(self, kind: str):
        self.kind = kind

    def fit(self, X, y):
        from sklearn.linear_model import LogisticRegression, Ridge
        ok = np.isfinite(y)
        self.tab = Tab().fit(X[ok])
        Z = self.tab.transform(X[ok])
        if self.kind == "bin":
            self.m = LogisticRegression(C=1.0, max_iter=500).fit(Z, y[ok].astype(int))
        else:
            self.m = Ridge(alpha=1.0).fit(Z, y[ok])
        return self

    def predict(self, X):
        Z = self.tab.transform(X)
        return self.m.predict_proba(Z)[:, 1] if self.kind == "bin" else self.m.predict(Z)


class Trees:
    """Gradient-boosted trees (B1's settings) on the raw inputs; NaN handled natively."""

    def __init__(self, kind: str):
        self.kind = kind

    def fit(self, X, y):
        from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
        ok = np.isfinite(y)
        X = np.asarray(X, dtype=np.float64)
        if self.kind == "bin":
            self.m = HistGradientBoostingClassifier(**K.GBM).fit(X[ok], y[ok].astype(int))
        else:
            self.m = HistGradientBoostingRegressor(**K.GBM).fit(X[ok], y[ok])
        return self

    def predict(self, X):
        X = np.asarray(X, dtype=np.float64)
        return self.m.predict_proba(X)[:, 1] if self.kind == "bin" else self.m.predict(X)


class BaseRate:
    def __init__(self, kind: str):
        self.kind = kind

    def fit(self, X, y):
        self.v = float(np.nanmean(y))
        return self

    def predict(self, X):
        return np.full(len(X), self.v)


def val_mask(days: np.ndarray, share: float = K.VAL_SHARE, gap: int = 1) -> tuple[np.ndarray, np.ndarray]:
    """Inside a training window: (val, keep). val = the last `share` of distinct days; `gap` days just before the
    validation block are dropped (keep False) so no label window spans training and validation."""
    u = np.array(sorted(set(days)))
    n_val = max(1, int(round(len(u) * share)))
    vdays = set(u[-n_val:])
    gdays = set(u[-n_val - gap:-n_val]) if gap else set()
    val = np.array([d in vdays for d in days])
    keep = np.array([d not in gdays for d in days])
    return val, keep
