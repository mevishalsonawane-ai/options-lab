"""R123 models (PREREG section 5): the one network over everything (a causal TCN market encoder over the index minutes
+ an MLP over the index, VIX, breadth, calendar and option-chain state) with three heads, and the baselines.

Every scaler, imputer and label scale is fitted on the training rows passed to `fit` only (test_r123.py checks it).
Scaling follows R41's `Tab` / `Seq` (research/hunt/r41/nets.py), copied here so the two studies' settings stay apart.
"""
from __future__ import annotations

import time

import numpy as np

import r123cfg as K

NJ = len(K.CS)                      # 16 cell-sides


# ------------------------------------------------------------------------------------------------ preprocessing
class Tab:
    """Training-median imputation, training mean/SD scaling, clip to +-CLIP; columns NaN on > NAN_DROP of the
    training rows are dropped."""

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


class SeqStore:
    """The five indices' minute channels, each padded in front with SEQ_N empty minutes (mask 0), so a window that
    starts before the first minute is zeros. window(und_code, gpos) = minutes gpos-SEQ_N+1 .. gpos (never later)."""

    def __init__(self, seqs: dict):
        self.codes = {u: i for i, u in enumerate(K.UNDS)}
        parts, self.base = [], {}
        off = 0
        for u in K.UNDS:
            a = seqs.get(u)
            if a is None:
                a = np.zeros((0, 7), np.float32)
            pad = np.zeros((K.SEQ_N, a.shape[1] if a.ndim == 2 else 7), np.float32)
            parts += [pad, a.astype(np.float32)]
            self.base[u] = off + K.SEQ_N
            off += K.SEQ_N + len(a)
        self.A = np.concatenate(parts)
        self.mu = np.zeros(self.A.shape[1], np.float32)
        self.sd = np.ones(self.A.shape[1], np.float32)

    def flat(self, und: np.ndarray, gpos: np.ndarray) -> np.ndarray:
        return np.array([self.base[u] for u in und]) + np.asarray(gpos, dtype=np.int64)

    def fit(self, flat_pos: np.ndarray, rng=None) -> "SeqStore":
        """Channel scale from the training rows' windows only (the 0/1 channels are left alone)."""
        rng = rng or np.random.default_rng(K.SEED)
        p = flat_pos if len(flat_pos) <= 20000 else rng.choice(flat_pos, 20000, replace=False)
        W = self.A[p[:, None] + np.arange(-K.SEQ_N + 1, 1)[None, :]]
        m = W[..., -1] > 0
        for c in range(5):
            v = W[..., c][m]
            if len(v):
                self.mu[c] = v.mean()
                self.sd[c] = v.std() if v.std() > 1e-9 else 1.0
        return self

    def window(self, flat_pos: np.ndarray) -> np.ndarray:
        W = self.A[flat_pos[:, None] + np.arange(-K.SEQ_N + 1, 1)[None, :]].copy()
        W[..., :5] = np.clip((W[..., :5] - self.mu[:5]) / self.sd[:5], -K.CLIP, K.CLIP) * (W[..., -1:] > 0)
        return W


# ------------------------------------------------------------------------------------------------ the network
def _torch():
    import torch
    torch.set_num_threads(4)
    return torch


def make_net(n_tab: int, n_ch: int = 7):
    torch = _torch()
    nn = torch.nn

    class CausalConv(nn.Module):
        def __init__(self, cin, cout, dil):
            super().__init__()
            self.pad = 2 * dil
            self.conv = nn.Conv1d(cin, cout, 3, dilation=dil)

        def forward(self, x):
            return self.conv(nn.functional.pad(x, (self.pad, 0)))

    class Net(nn.Module):
        def __init__(self):
            super().__init__()
            self.inp = nn.Conv1d(n_ch, 32, 1)
            self.convs = nn.ModuleList([CausalConv(32, 32, d) for d in (1, 2, 4, 8)])
            self.tab = nn.Sequential(nn.Linear(n_tab, 64), nn.ReLU(), nn.Dropout(K.DROPOUT))
            self.trunk = nn.Sequential(nn.Linear(64 + 64, 128), nn.ReLU(), nn.Dropout(K.DROPOUT),
                                       nn.Linear(128, 64), nn.ReLU())
            self.head_a = nn.Linear(64, NJ * 3)
            self.head_b = nn.Linear(64, 2)
            self.head_c = nn.Linear(64, 2)

        def forward(self, seq, tab):
            h = self.inp(seq.transpose(1, 2))                       # (B, 32, T)
            for cv in self.convs:
                h = h + torch.relu(cv(h))
            enc = torch.cat([h[:, :, -1], h[:, :, -15:].mean(2)], 1)
            z = self.trunk(torch.cat([enc, self.tab(tab)], 1))
            return self.head_a(z).view(-1, NJ, 3), self.head_b(z), self.head_c(z)

    return Net()


class NetModel:
    """3-seed ensemble. fit(train rows) with early stopping on the last VAL_SHARE of training sessions."""

    def __init__(self, seeds=K.SEEDS, epochs=K.EPOCHS, log=print):
        self.seeds, self.epochs, self.log = seeds, epochs, log

    def _arrays(self, F, store):
        X = self.tab.transform(F[self.cols].values.astype(np.float64))
        fp = store.flat(F.und.values, F.gpos.values)
        cls = F[[f"cls_{j}" for j in range(NJ)]].values.astype(np.int64)
        rg = F[[f"y_range_{h}" for h in K.HS]].values.astype(np.float64)
        rg = (rg - self.rg_mu) / self.rg_sd
        dr = F[[f"y_dir_{h}" for h in K.HS]].values.astype(np.float64)
        return X, fp, cls, rg.astype(np.float32), dr.astype(np.float32)

    def fit(self, F, store: SeqStore, cols):
        torch = _torch()
        self.cols = list(cols)
        days = np.array(sorted(F.day.unique()))
        cut = days[int(len(days) * (1 - K.VAL_SHARE))]
        tr, va = F[F.day < cut], F[F.day >= cut]
        self.tab = Tab().fit(tr[self.cols].values.astype(np.float64))
        store.fit(store.flat(tr.und.values, tr.gpos.values))
        self.store = store
        rg = tr[[f"y_range_{h}" for h in K.HS]].values
        self.rg_mu, self.rg_sd = np.nanmean(rg, 0), np.nanstd(rg, 0)
        A_tr, A_va = self._arrays(tr, store), self._arrays(va, store)
        self.nets = []
        for s in self.seeds:
            torch.manual_seed(s)
            rng = np.random.default_rng(s)
            net = make_net(A_tr[0].shape[1])
            opt = torch.optim.AdamW(net.parameters(), lr=K.LR, weight_decay=K.WD)
            best, best_state, bad = np.inf, None, 0
            t0 = time.time()
            for ep in range(self.epochs):
                net.train()
                order = rng.permutation(len(A_tr[0]))
                for b in range(0, len(order), K.BATCH):
                    ix = order[b:b + K.BATCH]
                    loss = self._loss(net, A_tr, ix, store)
                    opt.zero_grad()
                    loss.backward()
                    opt.step()
                net.eval()
                with torch.no_grad():
                    vl = float(np.mean([self._loss(net, A_va, np.arange(b, min(b + 4096, len(A_va[0]))), store).item()
                                        for b in range(0, len(A_va[0]), 4096)]))
                if vl < best - 1e-5:
                    best, bad = vl, 0
                    best_state = {k: v.clone() for k, v in net.state_dict().items()}
                else:
                    bad += 1
                self.log(f"  seed {s} epoch {ep} val {vl:.5f} ({time.time() - t0:.0f}s)", flush=True)
                if bad >= K.PATIENCE:
                    break
            net.load_state_dict(best_state)
            net.eval()
            self.nets.append(net)
        return self

    def _loss(self, net, A, ix, store):
        torch = _torch()
        X, fp, cls, rg, dr = A
        seq = torch.from_numpy(store.window(fp[ix]))
        tab = torch.from_numpy(X[ix])
        la, lb, lc = net(seq, tab)
        terms = []
        y = torch.from_numpy(cls[ix])
        for j in range(NJ):
            if (y[:, j] >= 0).any():
                terms.append(torch.nn.functional.cross_entropy(la[:, j], y[:, j], ignore_index=-1))
        r = torch.from_numpy(rg[ix])
        for k in range(r.shape[1]):
            m = torch.isfinite(r[:, k])
            if m.any():
                terms.append(((lb[m, k] - r[m, k]) ** 2).mean())
        d = torch.from_numpy(dr[ix])
        for k in range(d.shape[1]):
            m = torch.isfinite(d[:, k])
            if m.any():
                terms.append(torch.nn.functional.binary_cross_entropy_with_logits(lc[m, k], d[m, k]))
        return torch.stack(terms).mean()

    def predict(self, F, store: SeqStore) -> dict:
        """probs (n, 16, 3) [+X first, -Y first, neither]; range (n, 2) log % (30, 60); dir (n, 2) P(up)."""
        torch = _torch()
        X = self.tab.transform(F[self.cols].values.astype(np.float64))
        store.mu, store.sd = self.store.mu, self.store.sd
        fp = store.flat(F.und.values, F.gpos.values)
        P, R, D = [], [], []
        with torch.no_grad():
            for net in self.nets:
                pa, pb, pc = [], [], []
                for b in range(0, len(X), 8192):
                    la, lb, lc = net(torch.from_numpy(store.window(fp[b:b + 8192])), torch.from_numpy(X[b:b + 8192]))
                    pa.append(torch.softmax(la, -1).numpy())
                    pb.append(lb.numpy())
                    pc.append(torch.sigmoid(lc).numpy())
                P.append(np.concatenate(pa))
                R.append(np.concatenate(pb))
                D.append(np.concatenate(pc))
        return dict(probs=np.mean(P, 0), range=np.mean(R, 0) * self.rg_sd + self.rg_mu, dir=np.mean(D, 0))


# ------------------------------------------------------------------------------------------------ baselines
def hour_bucket(rows: np.ndarray) -> np.ndarray:
    return (np.asarray(rows) + 15) // 60                       # 0 = 09:15-10:14, ...


class BaseRate:
    """The class frequencies of each cell-side per index and hour of day in the training rows (fallback: per
    cell-side)."""

    def fit(self, F):
        self.tab = {}
        self.all = {}
        hb = hour_bucket(F.row.values)
        for j in range(NJ):
            c = F[f"cls_{j}"].values
            ok = c >= 0
            self.all[j] = np.bincount(c[ok], minlength=3) / max(ok.sum(), 1)
            df = np.column_stack([F.und.values[ok], hb[ok], c[ok]])
            for (u, h), g in __import__("pandas").DataFrame(df, columns=["u", "h", "c"]).groupby(["u", "h"]):
                cnt = np.bincount(g.c.astype(int).values, minlength=3)
                self.tab[(j, u, int(h))] = (cnt + self.all[j] * 5) / (cnt.sum() + 5)
        self.dir = {h: float(np.nanmean(F[f"y_dir_{h}"])) for h in K.HS}
        self.rg = {h: float(np.nanmean(F[f"y_range_{h}"])) for h in K.HS}
        return self

    def predict(self, F) -> dict:
        hb = hour_bucket(F.row.values)
        P = np.zeros((len(F), NJ, 3))
        for j in range(NJ):
            for i, (u, h) in enumerate(zip(F.und.values, hb)):
                P[i, j] = self.tab.get((j, u, int(h)), self.all[j])
        return dict(probs=P, dir=np.column_stack([np.full(len(F), self.dir[h]) for h in K.HS]),
                    range=np.column_stack([np.full(len(F), self.rg[h]) for h in K.HS]))


class Trees:
    """Gradient-boosted trees on the tabular inputs: one "+X first vs not" classifier per cell-side, and the range
    regressors (B1 / R41 settings)."""

    def fit(self, F, cols):
        from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
        self.cols = list(cols)
        X = F[self.cols].values.astype(np.float64)
        self.clf, self.reg = {}, {}
        for j in range(NJ):
            c = F[f"cls_{j}"].values
            ok = c >= 0
            m = HistGradientBoostingClassifier(**K.GBM)
            m.fit(X[ok], (c[ok] == K.WIN).astype(int))
            self.clf[j] = m
        for h in K.HS:
            y = F[f"y_range_{h}"].values
            ok = np.isfinite(y)
            m = HistGradientBoostingRegressor(**K.GBM)
            m.fit(X[ok], y[ok])
            self.reg[h] = m
        return self

    def predict(self, F) -> dict:
        X = F[self.cols].values.astype(np.float64)
        pw = np.column_stack([self.clf[j].predict_proba(X)[:, 1] for j in range(NJ)])
        return dict(pwin=pw, range=np.column_stack([self.reg[h].predict(X) for h in K.HS]))
