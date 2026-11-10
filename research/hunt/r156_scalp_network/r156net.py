"""R156 network (PREREG section 3): a 1-D convolution over the last 60 one-minute bars joined with the 43 table numbers;
for each of the 24 exit rules a win logit and an expected net (rupees / 1,000).

Pure helpers (folds, validation split, standardiser) carry no torch import; `ScalpNet`, `train`, `predict` do.
"""
from __future__ import annotations

from datetime import date

import numpy as np

import r156cfg as K


# ================================================================================================ pure
def fold_masks(days: np.ndarray, first: date, last: date) -> tuple:
    """(train mask, score mask) over day ordinals: train = every day before `first`; score = first..last."""
    return days < first.toordinal(), (days >= first.toordinal()) & (days <= last.toordinal())


def val_split(days: np.ndarray, share: float = K.VAL_SHARE) -> tuple:
    """(fit mask, validation mask) over the training rows' day ordinals: the last `share` of the training SESSIONS (by
    date) are held for early stopping."""
    u = np.unique(days)
    n_val = max(1, int(round(len(u) * share)))
    cut = u[len(u) - n_val]
    return days < cut, days >= cut


class Standardiser:
    """Median imputation and z-scores for the table, fitted on training rows only."""

    def fit(self, tab: np.ndarray):
        self.med = np.nanmedian(tab, axis=0)
        self.med = np.where(np.isfinite(self.med), self.med, 0.0)
        x = np.where(np.isfinite(tab), tab, self.med)
        self.mu = x.mean(0)
        self.sd = x.std(0)
        self.sd = np.where(self.sd > 1e-9, self.sd, 1.0)
        return self

    def apply(self, tab: np.ndarray) -> np.ndarray:
        x = np.where(np.isfinite(tab), tab, self.med)
        return np.clip((x - self.mu) / self.sd, -K.CLIP, K.CLIP).astype(np.float32)


def targets(net: np.ndarray) -> tuple:
    """(win 0/1, net / 1,000 clipped to +-10, mask) from the labels; NaN = no fill = masked out."""
    mask = np.isfinite(net)
    win = (np.where(mask, net, 0.0) > 0).astype(np.float32)
    val = np.clip(np.where(mask, net, 0.0) / 1000.0, -K.CLIP, K.CLIP).astype(np.float32)
    return win, val, mask.astype(np.float32)


# ================================================================================================ torch
def make_net():
    import torch
    from torch import nn

    class ScalpNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.conv = nn.Sequential(nn.Conv1d(K.SEQ_CH, 32, 5, padding=2), nn.ReLU(),
                                      nn.Conv1d(32, 32, 5, padding=2), nn.ReLU(),
                                      nn.Conv1d(32, 32, 3, padding=1), nn.ReLU())
            self.tab = nn.Sequential(nn.Linear(K.N_TAB, 64), nn.ReLU())
            self.head = nn.Sequential(nn.Linear(128, 128), nn.ReLU(), nn.Dropout(K.DROPOUT), nn.Linear(128, 2 * K.NR))

        def forward(self, seq, tab):
            h = self.conv(seq.transpose(1, 2))
            z = torch.cat([h.mean(2), h.amax(2), self.tab(tab)], 1)
            out = self.head(z)
            return out[:, :K.NR], out[:, K.NR:]

    return ScalpNet()


def loss_fn(logit, ev, win, val, mask):
    import torch
    import torch.nn.functional as F
    bce = F.binary_cross_entropy_with_logits(logit, win, reduction="none")
    hub = F.huber_loss(ev, val, reduction="none", delta=1.0)
    return ((bce + hub) * mask).sum() / mask.sum().clamp(min=1.0)


def _batches(n: int, bs: int, rng=None):
    idx = rng.permutation(n) if rng is not None else np.arange(n)
    for a in range(0, n, bs):
        yield idx[a:a + bs]


def train(seq, tab, net, days, seed: int, epochs: int = K.EPOCHS, log=None, factory=None):
    """Train one seed on the given (training) rows; early stopping on the last 10% of sessions. Returns the model (best
    epoch's weights) and its history [(epoch, fit loss, validation loss)]. `tab` is already standardised."""
    import torch
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    fit, val = val_split(days)
    win, tv, mask = targets(net)
    T = lambda a: torch.from_numpy(np.ascontiguousarray(a))  # noqa: E731
    seq_t, tab_t, win_t, tv_t, m_t = T(seq), T(tab), T(win), T(tv), T(mask)      # seq may be float16: cast per batch
    fi, vi = np.flatnonzero(fit), np.flatnonzero(val)
    model = (factory or make_net)()
    opt = torch.optim.Adam(model.parameters(), lr=K.LR, weight_decay=K.WD)
    best, best_state, hist = np.inf, None, []
    for ep in range(epochs):
        model.train()
        tot = n = 0.0
        for b in _batches(len(fi), K.BATCH, rng):
            j = torch.from_numpy(fi[b])
            opt.zero_grad()
            lg, ev = model(seq_t[j].float(), tab_t[j])
            ls = loss_fn(lg, ev, win_t[j], tv_t[j], m_t[j])
            ls.backward()
            opt.step()
            tot += float(ls.detach()) * len(b)
            n += len(b)
        model.eval()
        vt = vn = 0.0
        with torch.no_grad():
            for b in _batches(len(vi), 8192):
                j = torch.from_numpy(vi[b])
                lg, ev = model(seq_t[j].float(), tab_t[j])
                vt += float(loss_fn(lg, ev, win_t[j], tv_t[j], m_t[j])) * len(b)
                vn += len(b)
        vl = vt / max(vn, 1)
        hist.append((ep, round(tot / max(n, 1), 5), round(vl, 5)))
        if log:
            log(hist[-1])
        if vl < best:
            best, best_state = vl, {k: v.clone() for k, v in model.state_dict().items()}
    model.load_state_dict(best_state)
    model.eval()
    return model, hist


def predict(model, seq, tab) -> tuple:
    """(win chance (n, 24), expected net in rupees (n, 24))."""
    import torch
    ps, es = [], []
    with torch.no_grad():
        for b in _batches(len(tab), 8192):
            lg, ev = model(torch.from_numpy(seq[b].astype(np.float32)), torch.from_numpy(tab[b]))
            ps.append(torch.sigmoid(lg).numpy())
            es.append(ev.numpy() * 1000.0)
    return np.concatenate(ps), np.concatenate(es)
