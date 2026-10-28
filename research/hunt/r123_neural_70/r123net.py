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


class DailyStore:
    """N2's slow encoder input (Amendment 1): each index's completed sessions as daily bars, 7 channels (gap,
    open-to-close, open-to-high, open-to-low, range, VIX daily change, mask), padded in front with SLOW_N empty days.
    The row of session i reads sessions i-SLOW_N .. i-1 (never today)."""

    SLOW_N = 20

    def __init__(self, daily: dict):
        self.A, self.base, off = [], {}, 0
        for u in K.UNDS:
            a = daily.get(u, np.zeros((0, 7), np.float32)).astype(np.float32)
            self.A += [np.zeros((self.SLOW_N, 7), np.float32), a]
            self.base[u] = off + self.SLOW_N
            off += self.SLOW_N + len(a)
        self.A = np.concatenate(self.A)
        self.mu, self.sd = np.zeros(7, np.float32), np.ones(7, np.float32)

    def flat(self, und, gpos) -> np.ndarray:
        """Flat position of the row's own session (today); the window ends one day before it."""
        return np.array([self.base[u] for u in und]) + np.asarray(gpos, dtype=np.int64) // K.N

    def fit(self, flat_pos) -> "DailyStore":
        W = self.A[np.unique(flat_pos)[:, None] + np.arange(-self.SLOW_N, 0)[None, :]]
        m = W[..., -1] > 0
        for c in range(6):
            v = W[..., c][m]
            if len(v):
                self.mu[c], self.sd[c] = v.mean(), (v.std() if v.std() > 1e-9 else 1.0)
        return self

    def window(self, flat_pos) -> np.ndarray:
        W = self.A[np.asarray(flat_pos)[:, None] + np.arange(-self.SLOW_N, 0)[None, :]].copy()
        W[..., :6] = np.clip((W[..., :6] - self.mu[:6]) / self.sd[:6], -K.CLIP, K.CLIP) * (W[..., -1:] > 0)
        return W


def daily_bars(grid, vix) -> np.ndarray:
    """(sessions, 7) daily bars of one index from its minute grid (Amendment 1), aligned to grid.days."""
    with np.errstate(all="ignore"):
        o = np.array([_first(r) for r in grid.o])
        c = np.array([_last(r) for r in grid.c])
        h, lo = np.nanmax(grid.h, 1), np.nanmin(grid.l, 1)
        vpos = vix.pos()
        vc = np.array([_last(vix.c[vpos[d]]) if d in vpos else np.nan for d in grid.days])
        pc = np.concatenate([[np.nan], c[:-1]])
        pv = np.concatenate([[np.nan], vc[:-1]])
        a = np.column_stack([np.log(o / pc), np.log(c / o), np.log(h / o), np.log(lo / o), (h - lo) / c,
                             np.log(vc / pv)]) * 100
    ok = np.isfinite(a[:, 1]) & np.isfinite(a[:, 4])
    a = np.where(np.isfinite(a), a, 0.0)
    a[~ok] = 0.0
    return np.column_stack([a, ok.astype(float)]).astype(np.float32)


def _first(x):
    f = np.flatnonzero(np.isfinite(x))
    return x[f[0]] if len(f) else np.nan


def _last(x):
    f = np.flatnonzero(np.isfinite(x))
    return x[f[-1]] if len(f) else np.nan


GROUPS = {
    "size": ("vix", "vix_chg", "vix_chg30", "rv30", "day_range", "prev_range", "avg_range20", "gap", "dist_hi",
             "dist_lo"),
    "direction": ("from_open", "ret5", "ret15", "ret30", "ret60", "gap", "prev_ret", "dist_hi", "dist_lo", "br_up",
                  "br_from_open", "br_ret15"),
    "chain": ("atm_c_pct", "atm_p_pct", "strad_pct", "log_strad_rs", "log_c_rs", "log_p_rs", "atm_iv", "skew", "pcr",
              "pcr_chg15", "c_oi_chg15", "p_oi_chg15", "cp_vol15"),
    "calendar": tuple("u_" + u for u in K.UNDS) + ("tod", "weekday", "to_expiry", "is_expiry", "is_budget"),
}
SPECIALISTS = tuple(GROUPS)


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
            self.trunk = nn.Sequential(nn.Linear(96 + 64, 128), nn.ReLU(), nn.Dropout(K.DROPOUT),
                                       nn.Linear(128, 64), nn.ReLU())
            self.head_a = nn.Linear(64, NJ * 3)
            self.head_b = nn.Linear(64, 2)
            self.head_c = nn.Linear(64, 2)

        def forward(self, seq, tab):
            h = self.inp(seq.transpose(1, 2))                       # (B, 32, T)
            for cv in self.convs:
                h = h + torch.relu(cv(h))
            enc = torch.cat([h[:, :, -1], h[:, :, -15:].mean(2), h.mean(2)], 1)     # all 120 minutes reach it
            z = self.trunk(torch.cat([enc, self.tab(tab)], 1))
            return self.head_a(z).view(-1, NJ, 3), self.head_b(z), self.head_c(z)

    return Net()


def fit_temperatures(L: np.ndarray, cls: np.ndarray) -> np.ndarray:
    """Level 4: per cell-side, the temperature on a 0.5-3.0 grid that minimises the log-loss of the seed-averaged
    softmax(logits / T), exactly as predict() applies it. L: (seeds, n, 16, 3) validation logits; cls: (n, 16)."""
    grid = np.round(np.arange(0.5, 3.01, 0.05), 2)
    temps = np.ones(L.shape[2])
    for j in range(L.shape[2]):
        ok = cls[:, j] >= 0
        if ok.sum() < 100:
            continue
        z, y = L[:, ok, j], cls[ok, j]
        nll = []
        for T in grid:
            zz = z / T
            zz = zz - zz.max(-1, keepdims=True)
            p = (np.exp(zz) / np.exp(zz).sum(-1, keepdims=True)).mean(0)
            nll.append(-np.log(np.clip(p[np.arange(len(y)), y], 1e-9, 1)).mean())
        temps[j] = grid[int(np.argmin(nll))]
    return temps


def make_multi(group_idx: dict, n_ch: int = 7):
    """N2 (Amendment 1): Level 1 fast TCN + slow GRU encoders; Level 2 four specialists on [state, own inputs];
    Level 3 a gated committee -> head (a). Returns (a logits, range, dir, aux chain (a), aux calendar (a), gate)."""
    torch = _torch()
    nn = torch.nn
    fast = make_net(1, n_ch)                 # reuse N1's encoder layers (its tabular / trunk parts are unused)

    class Spec(nn.Module):
        def __init__(self, n_in):
            super().__init__()
            self.f = nn.Sequential(nn.Linear(96 + 32 + n_in, 64), nn.ReLU(), nn.Dropout(K.DROPOUT),
                                   nn.Linear(64, 32), nn.ReLU())

        def forward(self, x):
            return self.f(x)

    class Multi(nn.Module):
        def __init__(self):
            super().__init__()
            self.inp, self.convs = fast.inp, fast.convs
            self.slow = nn.GRU(7, 32, batch_first=True)
            self.idx = {k: torch.as_tensor(v, dtype=torch.long) for k, v in group_idx.items()}
            self.spec = nn.ModuleDict({k: Spec(len(v)) for k, v in group_idx.items()})
            self.size_head = nn.Linear(32, 2)
            self.dir_head = nn.Linear(32, 2)
            self.chain_head = nn.Linear(32, NJ * 3)
            self.cal_head = nn.Linear(32, NJ * 3)
            self.gate = nn.Linear(32 * len(group_idx), len(group_idx))
            self.committee = nn.Sequential(nn.Linear(32 + 2 + 2, 64), nn.ReLU(), nn.Dropout(K.DROPOUT),
                                           nn.Linear(64, NJ * 3))

        def forward(self, seq, tab, day):
            h = self.inp(seq.transpose(1, 2))
            for cv in self.convs:
                h = h + torch.relu(cv(h))
            state = torch.cat([h[:, :, -1], h[:, :, -15:].mean(2), h.mean(2), self.slow(day)[1][0]], 1)   # 96 + 32
            outs = {k: self.spec[k](torch.cat([state, tab[:, self.idx[k]]], 1)) for k in SPECIALISTS}
            stack = torch.stack([outs[k] for k in SPECIALISTS], 1)                           # (B, 4, 32)
            g = torch.softmax(self.gate(stack.flatten(1)), 1)
            mix = (stack * g.unsqueeze(-1)).sum(1)
            rg, dr = self.size_head(outs["size"]), self.dir_head(outs["direction"])
            la = self.committee(torch.cat([mix, rg, dr], 1)).view(-1, NJ, 3)
            return (la, rg, dr, self.chain_head(outs["chain"]).view(-1, NJ, 3),
                    self.cal_head(outs["calendar"]).view(-1, NJ, 3), g)

    return Multi()


class NetModel:
    """3-seed ensemble. fit(train rows) with early stopping on the last VAL_SHARE of training sessions."""

    def __init__(self, seeds=K.SEEDS, epochs=K.EPOCHS, log=print, arch="N1", daily: dict | None = None):
        self.seeds, self.epochs, self.log, self.arch = seeds, epochs, log, arch
        self.daily = DailyStore(daily) if arch == "N2" else None

    def _arrays(self, F, store):
        X = self.tab.transform(F[self.cols].values.astype(np.float64))
        fp = store.flat(F.und.values, F.gpos.values)
        cls = F[[f"cls_{j}" for j in range(NJ)]].values.astype(np.int64)
        rg = F[[f"y_range_{h}" for h in K.HS]].values.astype(np.float64)
        rg = (rg - self.rg_mu) / self.rg_sd
        dr = F[[f"y_dir_{h}" for h in K.HS]].values.astype(np.float64)
        dp = self.daily.flat(F.und.values, F.gpos.values) if self.daily is not None else None
        return X, fp, cls, rg.astype(np.float32), dr.astype(np.float32), dp

    def fit(self, F, store: SeqStore, cols):
        torch = _torch()
        self.cols = list(cols)
        days = np.array(sorted(F.day.unique()))
        cut = days[int(len(days) * (1 - K.VAL_SHARE))]
        tr, va = F[F.day < cut], F[F.day >= cut]
        self.tab = Tab().fit(tr[self.cols].values.astype(np.float64))
        store.fit(store.flat(tr.und.values, tr.gpos.values))
        self.store = store
        if self.daily is not None:
            self.daily.fit(self.daily.flat(tr.und.values, tr.gpos.values))
            kept = [self.cols[i] for i in self.tab.keep]
            self.group_idx = {k: [kept.index(c) for c in v if c in kept] for k, v in GROUPS.items()}
        rg = tr[[f"y_range_{h}" for h in K.HS]].values
        self.rg_mu, self.rg_sd = np.nanmean(rg, 0), np.nanstd(rg, 0)
        A_tr, A_va = self._arrays(tr, store), self._arrays(va, store)
        self.nets = []
        for s in self.seeds:
            torch.manual_seed(s)
            rng = np.random.default_rng(s)
            net = make_net(A_tr[0].shape[1]) if self.arch == "N1" else make_multi(self.group_idx)
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
        if self.arch == "N2":
            self.temps = self._fit_temps(A_va)
        return self

    def _fit_temps(self, A_va):
        """Level 4: one temperature per cell-side on the validation slice (training-period sessions only)."""
        torch = _torch()
        X, fp, cls, _, _, dp = A_va
        logits = []
        with torch.no_grad():
            for net in self.nets:
                out = []
                for b in range(0, len(X), 8192):
                    sl = slice(b, b + 8192)
                    out.append(net(*self._inputs(X, fp, dp, sl))[0].numpy())
                logits.append(np.concatenate(out))
        L = np.array(logits)                                   # (seeds, n, 16, 3)
        return fit_temperatures(L, cls)

    def _inputs(self, X, fp, dp, ix):
        torch = _torch()
        seq = torch.from_numpy(self.store.window(fp[ix]))
        tab = torch.from_numpy(X[ix])
        if self.arch == "N1":
            return seq, tab
        return seq, tab, torch.from_numpy(self.daily.window(dp[ix]))

    def _loss(self, net, A, ix, store):
        torch = _torch()
        X, fp, cls, rg, dr, dp = A
        out = net(*self._inputs(X, fp, dp, ix))
        la, lb, lc = out[0], out[1], out[2]
        terms, weights = [], []
        y = torch.from_numpy(cls[ix])
        heads = [(la, 1.0)] + ([(out[3], 0.5), (out[4], 0.5)] if self.arch == "N2" else [])
        for logits, w in heads:
            for j in range(NJ):
                if (y[:, j] >= 0).any():
                    terms.append(torch.nn.functional.cross_entropy(logits[:, j], y[:, j], ignore_index=-1))
                    weights.append(w)
        r = torch.from_numpy(rg[ix])
        for k in range(r.shape[1]):
            m = torch.isfinite(r[:, k])
            if m.any():
                terms.append(((lb[m, k] - r[m, k]) ** 2).mean())
                weights.append(1.0)
        d = torch.from_numpy(dr[ix])
        for k in range(d.shape[1]):
            m = torch.isfinite(d[:, k])
            if m.any():
                terms.append(torch.nn.functional.binary_cross_entropy_with_logits(lc[m, k], d[m, k]))
                weights.append(1.0)
        w = torch.tensor(weights)
        return (torch.stack(terms) * w).sum() / w.sum()

    def predict(self, F, store: SeqStore, daily_store: "DailyStore | None" = None) -> dict:
        """probs (n, 16, 3) [+X first, -Y first, neither]; range (n, 2) log % (30, 60); dir (n, 2) P(up).
        N2 adds spread (n, 16): max - min over the seeds of P(+X first) (Level 4 abstains above K.ABSTAIN), and gate
        (n, 4): the committee's weight on each specialist."""
        torch = _torch()
        X = self.tab.transform(F[self.cols].values.astype(np.float64))
        store.mu, store.sd = self.store.mu, self.store.sd
        self.store = store
        fp = store.flat(F.und.values, F.gpos.values)
        dp = None
        if self.daily is not None:
            if daily_store is not None:
                daily_store.mu, daily_store.sd = self.daily.mu, self.daily.sd
                self.daily = daily_store
            dp = self.daily.flat(F.und.values, F.gpos.values)
        P, R, D, G = [], [], [], []
        with torch.no_grad():
            for net in self.nets:
                pa, pb, pc, pg = [], [], [], []
                for b in range(0, len(X), 8192):
                    out = net(*self._inputs(X, fp, dp, slice(b, b + 8192)))
                    la = out[0] / torch.from_numpy(self.temps).float().view(1, -1, 1) if self.arch == "N2" else out[0]
                    pa.append(torch.softmax(la, -1).numpy())
                    pb.append(out[1].numpy())
                    pc.append(torch.sigmoid(out[2]).numpy())
                    if self.arch == "N2":
                        pg.append(out[5].numpy())
                P.append(np.concatenate(pa))
                R.append(np.concatenate(pb))
                D.append(np.concatenate(pc))
                if pg:
                    G.append(np.concatenate(pg))
        P = np.array(P)
        out = dict(probs=P.mean(0), range=np.mean(R, 0) * self.rg_sd + self.rg_mu, dir=np.mean(D, 0))
        if self.arch == "N2":
            out["spread"] = P[..., 0].max(0) - P[..., 0].min(0)
            out["gate"] = np.mean(G, 0)
        return out


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
