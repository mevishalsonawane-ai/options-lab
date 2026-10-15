"""R46 amendment 2: the other AI's v2 "Ensemble" filter, rebuilt.

Model: the average of an MLP 12 -> 64 -> 32 -> 1 (ReLU, dropout 0.2), GradientBoosting (200 trees, depth 4, lr 0.05; histogram splits) and
a RandomForest (200 trees, depth 10). Target: "the call premium is up 60 minutes later". Inputs (their 12): ret1, ret3,
ret6, ret12, vol12 and RSI14 of the call premium on 5-minute bars, spot_ret1, spot_ret6, spot_vol12, hours to expiry,
hour of day (0-1) and premium / spot.

Two ways to read the call's chart:
  roll   the ROLLING ATM series (as a "btc_atm" file): returns can compare two different contracts, and the label can
         compare the premium of one strike or expiry now with another one 60 minutes later;
  held   the HELD contract: the ATM call at the decision minute, followed on its own prices back and forward.

Two ways to score it:
  random a shuffled 80/20 split of the design rows (overlapping 60-minute labels sit on both sides: leaky);
  time   walk-forward blocks: each block's model is trained only on rows whose label ended at least 12 bars (60 min)
         before the block starts (purge + embargo), and scores that block.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import amend_cfg as A
from . import chain as CH
from .panel import PATH_LAGS

FIVE = pd.Timedelta(minutes=5)


# --- features -----------------------------------------------------------------------------------------------------------
def rsi_simple(P: np.ndarray, n: int = 14) -> np.ndarray:
    """RSI over the last n changes (simple averages). P[:, 0] is now, P[:, j] is j bars back. 100 if no loss, 50 if
    flat, NaN if any price is missing."""
    d = P[:, :n] - P[:, 1:n + 1]                        # change into bar j from bar j+1
    up = np.where(d > 0, d, 0.0).mean(1)
    dn = np.where(d < 0, -d, 0.0).mean(1)
    with np.errstate(divide="ignore", invalid="ignore"):
        r = 100 - 100 / (1 + up / dn)
    r = np.where(dn == 0, np.where(up == 0, 50.0, 100.0), r)
    return np.where(np.isfinite(P[:, :n + 1]).all(1), r, np.nan)


def path_features(P: np.ndarray, S: np.ndarray, ts: pd.DatetimeIndex, expiry: pd.DatetimeIndex) -> pd.DataFrame:
    """P: option premium (n, 15), S: spot (n, 13), column j = j five-minute bars back. The 12 inputs."""
    with np.errstate(divide="ignore", invalid="ignore"):
        r = P[:, :12] / P[:, 1:13] - 1                  # the last 12 one-bar returns
        sr = S[:, :12] / S[:, 1:13] - 1
        F = pd.DataFrame(dict(
            ret1=P[:, 0] / P[:, 1] - 1, ret3=P[:, 0] / P[:, 3] - 1, ret6=P[:, 0] / P[:, 6] - 1,
            ret12=P[:, 0] / P[:, 12] - 1, vol12=np.std(r, axis=1, ddof=1), rsi14=rsi_simple(P),
            spot_ret1=S[:, 0] / S[:, 1] - 1, spot_ret6=S[:, 0] / S[:, 6] - 1, spot_vol12=np.std(sr, axis=1, ddof=1),
            dte_h=(expiry - ts) / pd.Timedelta(hours=1),
            hour=(ts.hour + ts.minute / 60.0) / 24.0, ce_spot=P[:, 0] / S[:, 0]))
    return F.replace([np.inf, -np.inf], np.nan)[list(A.ENS_FEATURES)]


def lagged(series: pd.Series, ts: pd.DatetimeIndex, n: int, step=FIVE) -> np.ndarray:
    """series (minute index) at ts - j * step, j = 0 .. n-1 -> (len(ts), n)."""
    return np.column_stack([series.reindex(ts - j * step).to_numpy(float) for j in range(n)])


def panels(held: pd.DataFrame, roll: pd.DataFrame, spot_close: pd.Series) -> dict:
    """{'roll': (X, y, meta), 'held': (X, y, meta)} on the same decision minutes (the held panel's). meta: ts, entry,
    switch (the rolling contract changes inside (t, t + 60 min]), y of the other source."""
    held = held.sort_values("ts").reset_index(drop=True)
    ts = pd.DatetimeIndex(held.ts)
    S = lagged(spot_close, ts, 13)
    # held contract
    Ph = held[[f"p{j}" for j in range(PATH_LAGS)]].to_numpy(float)
    Xh = path_features(Ph, S, ts, pd.DatetimeIndex(held.expiry))
    yh = np.where(np.isfinite(held.p_fut) & np.isfinite(Ph[:, 0]), (held.p_fut.to_numpy(float) > Ph[:, 0]) * 1.0,
                  np.nan)
    # rolling series (stitched across contracts)
    R = roll.sort_values("ts").drop_duplicates("ts").set_index("ts")
    prem = R.premium
    Pr = lagged(prem, ts, PATH_LAGS)
    exp_r = pd.DatetimeIndex(R.expiry.reindex(ts))
    Xr = path_features(Pr, S, ts, exp_r)
    fut = prem.reindex(ts + pd.Timedelta(minutes=60)).to_numpy(float)
    yr = np.where(np.isfinite(fut) & np.isfinite(Pr[:, 0]), (fut > Pr[:, 0]) * 1.0, np.nan)
    cid = pd.Series(CH.contract_id(R.reset_index()), index=R.index)
    nchg = pd.Series(np.r_[0, np.cumsum(np.diff(cid.to_numpy()) != 0)], index=R.index)
    c0 = nchg.reindex(ts).to_numpy(float)
    c1 = nchg.reindex(ts + pd.Timedelta(minutes=60)).to_numpy(float)
    switch = np.where(np.isfinite(c0) & np.isfinite(c1), c1 > c0, False)
    # the lagged rolling path crosses a contract change too (its returns mix contracts)
    cl = nchg.reindex(ts - pd.Timedelta(minutes=60)).to_numpy(float)
    mixed_feat = np.where(np.isfinite(c0) & np.isfinite(cl), c0 > cl, False)
    meta = pd.DataFrame(dict(ts=ts, entry=ts + CH.ONE, switch=switch, mixed_feat=mixed_feat, y_roll=yr, y_held=yh))
    return {"roll": (Xr, yr, meta), "held": (Xh, yh, meta)}


# --- the ensemble -------------------------------------------------------------------------------------------------------
class TorchMLP:
    def __init__(self, cfg=A.ENS_MLP):
        self.cfg = cfg

    def fit(self, X, y):
        import torch
        c = self.cfg
        torch.manual_seed(c["seed"])
        torch.set_num_threads(1)              # a tiny net: one thread is fastest (and leaves the cores to the trees)
        X, y = np.asarray(X, np.float32), np.asarray(y, np.float32)
        nv = int(len(X) * c["val_frac"])
        Xt, yt, Xv, yv = X[:len(X) - nv], y[:len(X) - nv], X[len(X) - nv:], y[len(X) - nv:]
        self.mu, self.sd = Xt.mean(0), Xt.std(0)
        self.sd[self.sd == 0] = 1.0
        h1, h2 = c["hidden"]
        self.net = torch.nn.Sequential(
            torch.nn.Linear(X.shape[1], h1), torch.nn.ReLU(), torch.nn.Dropout(c["dropout"]),
            torch.nn.Linear(h1, h2), torch.nn.ReLU(), torch.nn.Dropout(c["dropout"]), torch.nn.Linear(h2, 1))
        opt = torch.optim.Adam(self.net.parameters(), lr=c["lr"])
        lossf = torch.nn.BCEWithLogitsLoss()
        tx = torch.from_numpy((Xt - self.mu) / self.sd)
        ty = torch.from_numpy(yt)
        vx = torch.from_numpy((Xv - self.mu) / self.sd)
        vy = torch.from_numpy(yv)
        g = torch.Generator().manual_seed(c["seed"])
        best, best_state, bad = np.inf, None, 0
        for _ in range(c["epochs"]):
            self.net.train()
            perm = torch.randperm(len(tx), generator=g)
            for s in range(0, len(perm), c["batch"]):
                bi = perm[s:s + c["batch"]]
                opt.zero_grad()
                loss = lossf(self.net(tx[bi])[:, 0], ty[bi])
                loss.backward()
                opt.step()
            if nv:
                self.net.eval()
                with torch.no_grad():
                    vl = float(lossf(self.net(vx)[:, 0], vy))
                if vl < best - 1e-5:
                    best, bad = vl, 0
                    best_state = {k: v.clone() for k, v in self.net.state_dict().items()}
                else:
                    bad += 1
                    if bad >= c["patience"]:
                        break
        if best_state is not None:
            self.net.load_state_dict(best_state)
        self.val_loss = best
        return self

    def predict(self, X):
        import torch
        self.net.eval()
        with torch.no_grad():
            z = self.net(torch.from_numpy(((np.asarray(X, np.float32) - self.mu) / self.sd).astype(np.float32)))
        return 1.0 / (1.0 + np.exp(-np.clip(z[:, 0].numpy().astype(float), -50, 50)))


class Ensemble:
    def fit(self, X, y):
        from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
        X, y = np.asarray(X, float), np.asarray(y, int)
        self.mlp = TorchMLP().fit(X, y)
        self.gb = HistGradientBoostingClassifier(**A.ENS_GB).fit(X, y)
        self.rf = RandomForestClassifier(**A.ENS_RF).fit(X, y)
        return self

    def predict(self, X):
        X = np.asarray(X, float)
        return (self.mlp.predict(X) + self.gb.predict_proba(X)[:, 1] + self.rf.predict_proba(X)[:, 1]) / 3.0


def auc(y, p) -> float:
    """Mann-Whitney AUC (ties count half). NaN with one class only."""
    y, p = np.asarray(y, float), np.asarray(p, float)
    ok = np.isfinite(y) & np.isfinite(p)
    y, p = y[ok], p[ok]
    n1, n0 = int((y == 1).sum()), int((y == 0).sum())
    if not n1 or not n0:
        return float("nan")
    r = pd.Series(p).rank().to_numpy()
    return float((r[y == 1].sum() - n1 * (n1 + 1) / 2) / (n1 * n0))


def usable(X: pd.DataFrame, y: np.ndarray) -> np.ndarray:
    return np.isfinite(X.to_numpy(float)).all(1) & np.isfinite(y)


def train_mask(ts: pd.DatetimeIndex, T: pd.Timestamp, data_from: pd.Timestamp) -> np.ndarray:
    """Rows a model starting at block T may learn from: t >= data_from and the 60-minute label ended at least the
    embargo (12 bars) before T."""
    lab_end = ts + pd.Timedelta(minutes=5 * A.ENS_LABEL_BARS)
    return np.asarray((ts >= data_from) & (lab_end <= T - pd.Timedelta(minutes=5 * A.ENS_EMBARGO_BARS)))


def walk_forward(X: pd.DataFrame, y: np.ndarray, ts: pd.DatetimeIndex, view: str, model=Ensemble, log_cb=None):
    """Out-of-sample probabilities for every row inside the view's blocks (NaN elsewhere) and a log."""
    v = A.VIEWS[view]
    ok = usable(X, y)
    Xa = X.to_numpy(float)
    prob = np.full(len(X), np.nan)
    log = []
    B = A.blocks(view)
    for T, T1 in zip(B[:-1], B[1:]):
        tr = ok & train_mask(ts, T, v["data_from"])
        te = np.isfinite(Xa).all(1) & np.asarray((ts >= T) & (ts < T1))
        rec = dict(block=str(T.date()), n_train=int(tr.sum()), n_test=int(te.sum()))
        if tr.sum() < A.ENS_MIN_TRAIN or not te.any():
            rec["trained"] = False
        else:
            m = model().fit(Xa[tr], y[tr])
            prob[te] = m.predict(Xa[te])
            rec.update(trained=True, base_rate=round(float(y[tr].mean()), 4),
                       auc_block=round(auc(y[te], prob[te]), 4) if np.isfinite(y[te]).any() else None)
        log.append(rec)
        if log_cb:
            log_cb(rec)
    return prob, log


def random_split_auc(X: pd.DataFrame, y: np.ndarray, ts: pd.DatetimeIndex, span, model=Ensemble, seed: int = 0):
    """The leaky way: shuffle the span's rows, train on 80%, score 20%."""
    ok = usable(X, y) & np.asarray((ts >= span[0]) & (ts < span[1]))
    idx = np.flatnonzero(ok)
    rng = np.random.default_rng(seed)
    rng.shuffle(idx)
    nt = int(len(idx) * A.ENS_RANDOM_TEST)
    te, tr = idx[:nt], idx[nt:]
    Xa = X.to_numpy(float)
    m = model().fit(Xa[tr], y[tr])
    p = m.predict(Xa[te])
    return dict(n_train=int(len(tr)), n_test=int(len(te)), auc=round(auc(y[te], p), 4),
                share_above=round(float((p > A.ENS_THRESHOLD).mean()), 4))
