"""R145 models (PREREG_R145_NN.md section 4; fixed before any fit, no tuning).

  N1  multi-task MLP on the tabular features: 128 -> 64 (SiLU, dropout 0.1), 15 heads, AdamW lr 1e-3 wd 1e-4,
      batch 4,096, up to 6 epochs (best epoch on the validation slice), mean of 3 seeds
  N2  1-layer GRU (32) over the coin's last 48 five-minute bars x 6 channels, joined with N1's trunk, the same heads,
      up to 4 epochs, at most 800,000 training rows (random), 1 seed
  B0  naive: base rate (dir, big), training mean (size), HAR on log realised vol (vol), zero (rank)
  B1  ridge (alpha 1) / L2 logistic (C 1) on the tabular features
  B2  LightGBM per target (31 leaves, lr 0.05, <= 300 rounds, early stop on validation, min 1,000 rows a leaf, 0.8/0.8)
  TW  N1 (1 seed) refitted on training labels shuffled across rows: the noise floor

Every model returns, for each target, a prediction on the test rows: P(yes) for binary targets, the value on the
target's own scale for the others.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

KINDS = ("dir", "size", "vol", "big", "xs")
BINARY = ("dir", "big")
HORIZONS = (15, 60, 240)
TARGETS = [f"y_{k}_{h}" for k in KINDS for h in HORIZONS]


def is_binary(t: str) -> bool:
    return t.split("_")[1] in BINARY


class Prep:
    """Standardise features on the training rows, clip at +-6, fill missing with the training median (standardised)."""

    def fit(self, X: np.ndarray):
        self.mu = np.nanmean(X, axis=0)
        sd = np.nanstd(X, axis=0)
        self.sd = np.where(sd > 0, sd, 1.0)
        Z = np.clip((X - self.mu) / self.sd, -6, 6)
        self.med = np.nan_to_num(np.nanmedian(Z, axis=0))
        return self

    def __call__(self, X: np.ndarray) -> np.ndarray:
        Z = np.clip((X - self.mu) / self.sd, -6, 6)
        return np.where(np.isnan(Z), self.med, Z).astype(np.float32)


class TargetScale:
    def fit(self, Y: np.ndarray):
        self.bin = np.array([is_binary(t) for t in TARGETS])
        self.mu = np.where(self.bin, 0.0, np.nanmean(Y, axis=0))
        sd = np.nanstd(Y, axis=0)
        self.sd = np.where(self.bin | (sd == 0), 1.0, sd)
        return self

    def fwd(self, Y):
        return ((Y - self.mu) / self.sd).astype(np.float32)

    def inv(self, Z):
        out = Z * self.sd + self.mu
        out[:, self.bin] = 1.0 / (1.0 + np.exp(-Z[:, self.bin]))
        return out


# --- torch nets ---------------------------------------------------------------------------------------------------------
def _torch():
    import torch
    torch.set_num_threads(4)
    return torch


def _nets(n_tab: int, n_out: int, seq: bool):
    torch = _torch()
    nn = torch.nn

    class Trunk(nn.Module):
        def __init__(self):
            super().__init__()
            self.f = nn.Sequential(nn.Linear(n_tab, 128), nn.SiLU(), nn.Dropout(0.1),
                                   nn.Linear(128, 64), nn.SiLU(), nn.Dropout(0.1))

        def forward(self, x):
            return self.f(x)

    class MLP(nn.Module):
        def __init__(self):
            super().__init__()
            self.trunk = Trunk()
            self.head = nn.Linear(64, n_out)

        def forward(self, x, s=None):
            return self.head(self.trunk(x))

    class GRUNet(nn.Module):
        def __init__(self, n_ch=6):
            super().__init__()
            self.trunk = Trunk()
            self.gru = nn.GRU(n_ch, 32, batch_first=True)
            self.head = nn.Linear(96, n_out)

        def forward(self, x, s):
            _, h = self.gru(s)
            return self.head(torch.cat([self.trunk(x), h[-1]], dim=1))

    return GRUNet() if seq else MLP()


def _loss(torch, out, y, binmask):
    m = ~torch.isnan(y)
    y0 = torch.nan_to_num(y)
    bce = torch.nn.functional.binary_cross_entropy_with_logits(out, y0, reduction="none")
    mse = (out - y0) ** 2
    l = torch.where(binmask, bce, mse) * m
    return (l.sum(0) / m.sum(0).clamp(min=1)).sum()


class SeqGather:
    """The coin's last 48 bars of channels: C[k-47..k, j, :] for rows (bar k, coin column j). NaN -> 0."""

    def __init__(self, C: np.ndarray, bar: np.ndarray, col: np.ndarray, L: int = 48):
        self.C, self.bar, self.col, self.L = C, bar.astype(np.int64), col.astype(np.int64), L

    def __call__(self, idx: np.ndarray) -> np.ndarray:
        k = self.bar[idx][:, None] + np.arange(-self.L + 1, 1)[None, :]
        k = np.clip(k, 0, len(self.C) - 1)
        s = self.C[k, self.col[idx][:, None], :]
        return np.nan_to_num(s, nan=0.0, posinf=0.0, neginf=0.0)


def fit_net(Xtr, Ytr, Xva, Yva, seq: SeqGather | None = None, tr_idx=None, va_idx=None, epochs=6, seed=0,
            batch=4096, lr=1e-3, wd=1e-4, log=print):
    """Fit one multi-task net; returns (model, best_epoch, val_losses). Ytr/Yva already scaled (TargetScale)."""
    torch = _torch()
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    net = _nets(Xtr.shape[1], Ytr.shape[1], seq is not None)
    opt = torch.optim.AdamW(net.parameters(), lr=lr, weight_decay=wd)
    binmask = torch.tensor([is_binary(t) for t in TARGETS])
    best, best_state, hist = np.inf, None, []

    def batches(n, shuffle):
        order = rng.permutation(n) if shuffle else np.arange(n)
        for i in range(0, n, batch):
            yield order[i:i + batch]

    def tens(X, Y, b, idx):
        x = torch.from_numpy(X[b])
        y = torch.from_numpy(Y[b])
        s = torch.from_numpy(seq(idx[b])) if seq is not None else None
        return x, y, s

    for ep in range(epochs):
        net.train()
        for b in batches(len(Xtr), True):
            x, y, s = tens(Xtr, Ytr, b, tr_idx)
            opt.zero_grad()
            loss = _loss(torch, net(x, s), y, binmask)
            loss.backward()
            opt.step()
        net.eval()
        tot, n = 0.0, 0
        with torch.no_grad():
            for b in batches(len(Xva), False):
                x, y, s = tens(Xva, Yva, b, va_idx)
                tot += float(_loss(torch, net(x, s), y, binmask)) * len(b)
                n += len(b)
        v = tot / max(n, 1)
        hist.append(v)
        log(f"    epoch {ep + 1}: val loss {v:.5f}")
        if v < best:
            best = v
            best_state = {k: t.detach().clone() for k, t in net.state_dict().items()}
    net.load_state_dict(best_state)
    return net, int(np.argmin(hist)) + 1, hist


def predict_net(net, X, seq: SeqGather | None = None, idx=None, batch=16384) -> np.ndarray:
    torch = _torch()
    net.eval()
    out = []
    with torch.no_grad():
        for i in range(0, len(X), batch):
            x = torch.from_numpy(X[i:i + batch])
            s = torch.from_numpy(seq(idx[i:i + batch])) if seq is not None else None
            out.append(net(x, s).numpy())
    return np.concatenate(out)


# --- baselines ----------------------------------------------------------------------------------------------------------
HAR = ["lrv_60", "lrv_240", "lrv_1440"]


def naive(tr: pd.DataFrame, te: pd.DataFrame) -> dict[str, np.ndarray]:
    from sklearn.linear_model import LinearRegression
    out = {}
    for t in TARGETS:
        k = t.split("_")[1]
        y = tr[t].to_numpy(float)
        if k == "xs":
            out[t] = np.zeros(len(te))
        elif k == "vol":
            m = np.isfinite(y) & tr[HAR].notna().all(axis=1).to_numpy()
            if m.sum() < 100:
                out[t] = np.full(len(te), np.nan)
                continue
            lr = LinearRegression().fit(tr.loc[m, HAR].to_numpy(), y[m])
            Xt = te[HAR].fillna(tr[HAR].median()).to_numpy()
            out[t] = lr.predict(Xt)
        else:
            out[t] = np.full(len(te), np.nanmean(y))
    return out


def linear(Ztr, tr, Zte) -> dict[str, np.ndarray]:
    from sklearn.linear_model import LogisticRegression, Ridge
    out = {}
    for t in TARGETS:
        y = tr[t].to_numpy(float)
        m = np.isfinite(y)
        if m.sum() < 100:
            out[t] = np.full(len(Zte), np.nan)
            continue
        if is_binary(t):
            mdl = LogisticRegression(C=1.0, max_iter=300).fit(Ztr[m], y[m].astype(int))
            out[t] = mdl.predict_proba(Zte)[:, 1]
        else:
            out[t] = Ridge(alpha=1.0).fit(Ztr[m], y[m]).predict(Zte)
    return out


def trees(Xtr, tr, Xva, va, Xte, log=print) -> tuple[dict[str, np.ndarray], dict]:
    import lightgbm as lgb
    out, info = {}, {}
    for t in TARGETS:
        y, yv = tr[t].to_numpy(float), va[t].to_numpy(float)
        m, mv = np.isfinite(y), np.isfinite(yv)
        if m.sum() < 100 or mv.sum() < 10:
            out[t], info[t] = np.full(len(Xte), np.nan), dict(rounds=0, gain={})
            continue
        params = dict(objective="binary" if is_binary(t) else "regression", num_leaves=31, learning_rate=0.05,
                      min_data_in_leaf=1000, feature_fraction=0.8, bagging_fraction=0.8, bagging_freq=1,
                      verbose=-1, num_threads=4, seed=1)
        dtr = lgb.Dataset(Xtr[m], y[m])
        dva = lgb.Dataset(Xva[mv], yv[mv], reference=dtr)
        b = lgb.train(params, dtr, num_boost_round=300, valid_sets=[dva],
                      callbacks=[lgb.early_stopping(30, verbose=False)])
        out[t] = b.predict(Xte, num_iteration=b.best_iteration)
        info[t] = dict(rounds=int(b.best_iteration),
                       gain=dict(zip(range(Xtr.shape[1]), b.feature_importance("gain").tolist())))
    return out, info
