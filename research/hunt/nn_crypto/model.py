"""NN-CRYPTO models: 1-D CNN (torch) vs logistic regression vs gradient boosting vs random, walk-forward + locked holdout.

python3 -I model.py dev        # anchored walk-forward on DEV only (test blocks 2022, 2023, 2024, 2025, 2026Q1)
python3 -I model.py holdout    # ONE run on the locked holdout 2026-04-01 .. end (refuses to run twice)
Pre-registration: scratchpad/hunt/nn_crypto/PREREG.txt.  Trade translation and stats: evaluate.py.
"""
from __future__ import annotations

import hashlib
import json
import os
import pickle
import sys
import time
import warnings

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from common import DATA, LOGS, MODELS, SCR, log  # noqa: E402

warnings.filterwarnings("ignore")
HOR = (15, 60, 240)
KB = {15: 1, 60: 4, 240: 16}
WIN = 64
SEQ = ["c_r", "c_rng", "c_lv", "c_tb"]
STATIC = ["ret4", "ret16", "ret96", "ret288", "ret672", "rv1d", "rv7d", "rvratio", "hi24", "lo24", "lvol", "tb4",
          "fund", "f3", "fz", "oi1h", "oi4h", "oi24h", "toplsr", "takr", "has_oi", "dvol", "dvol24", "vrp", "has_dvol",
          "hod_s", "hod_c", "dow_s", "dow_c", "weekend", "ev_next_h", "ev_prev_h", "ev_near", "x_ret4", "x_ret16",
          "x_ret96", "is_eth"]
TS = lambda s: int(pd.Timestamp(s, tz="UTC").timestamp())  # noqa: E731
HOLD0 = TS("2026-04-01")
FOLDS = [("2022", TS("2022-01-01"), TS("2023-01-01")), ("2023", TS("2023-01-01"), TS("2024-01-01")),
         ("2024", TS("2024-01-01"), TS("2025-01-01")), ("2025", TS("2025-01-01"), TS("2026-01-01")),
         ("2026Q1", TS("2026-01-01"), HOLD0)]
EMB = 86400 + 240 * 60          # 1-day embargo + longest label (purge)
SEEDS = (0, 1, 2)
OUT = os.path.join(SCR, "preds")
os.makedirs(OUT, exist_ok=True)


def load():
    f = pd.read_parquet(os.path.join(DATA, "feat_15m.parquet"))
    f["is_eth"] = (f.asset == "ETH").astype(np.float32)
    f = f.replace([np.inf, -np.inf], np.nan)
    b0 = int(f.b.min())
    nb = int(f.b.max()) - b0 + 1
    ch = np.zeros((2, nb, 8), np.float32)
    for ai, a in enumerate(("BTC", "ETH")):
        g = f[f.asset == a]
        pos = g.b.values - b0
        for j, c in enumerate(SEQ):
            ch[ai, pos, j] = np.nan_to_num(g[c].values, nan=0.0)
            ch[1 - ai, pos, 4 + j] = np.nan_to_num(g[c].values, nan=0.0)
    ch[:, :, [3, 7]] *= 4.0                       # taker-buy share: put on a similar scale
    f["pos"] = f.b - b0
    f["ai"] = f.is_eth.astype(int)
    ok = (f.pos >= WIN) & f.sig.notna() & (f.nmin > 0) & f[[f"y{h}" for h in HOR]].notna().all(axis=1)
    f = f[ok].reset_index(drop=True)
    # labels
    for h in HOR:
        f[f"d{h}"] = (f[f"y{h}"] > 0).astype(np.float32)
        f[f"s{h}"] = (f[f"y{h}"].abs() / (f.sig * np.sqrt(KB[h]))).clip(0, 8).astype(np.float32)
    return f, ch


def summary_feats(f, ch):
    """Static + last 4 bars of both assets' channels (for LR / HGB)."""
    X = f[STATIC].to_numpy(np.float32)
    lastk = np.concatenate([ch[f.ai.values, f.pos.values - k, :] for k in range(4)], axis=1)
    return np.concatenate([X, lastk], axis=1)


class Scaler:
    def fit(self, X):
        self.mu = np.nanmedian(X, axis=0)
        self.sd = np.nanstd(X, axis=0) + 1e-6
        self.mu = np.nan_to_num(self.mu)
        self.sd = np.nan_to_num(self.sd, nan=1.0)
        return self

    def __call__(self, X):
        Z = (X - self.mu) / self.sd
        return np.clip(np.nan_to_num(Z, nan=0.0), -6, 6).astype(np.float32)


# ------------------------------------------------------------------------------------------------ NN
def build_net(n_static):
    import torch.nn as nn

    class Net(nn.Module):
        def __init__(self):
            super().__init__()
            self.conv = nn.Sequential(nn.Conv1d(8, 16, 5, padding=2), nn.GELU(),
                                      nn.Conv1d(16, 16, 5, padding=4, dilation=2), nn.GELU(),
                                      nn.Conv1d(16, 16, 5, padding=8, dilation=4), nn.GELU())
            self.stat = nn.Sequential(nn.Linear(n_static, 32), nn.GELU())
            self.head = nn.Sequential(nn.Dropout(0.2), nn.Linear(64, 64), nn.GELU(), nn.Dropout(0.2), nn.Linear(64, 6))

        def forward(self, seq, st):
            z = self.conv(seq)
            z = __import__("torch").cat([z.mean(dim=2), z[:, :, -1], self.stat(st)], dim=1)
            return self.head(z)
    return Net()


def nn_fit_predict(ch, f, tr_idx, va_idx, te_idx, Xs_tr, Xs_va, Xs_te, seed):
    import torch
    torch.set_num_threads(3)
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    CH = torch.from_numpy(ch)
    offs = torch.arange(-WIN + 1, 1)
    A = torch.from_numpy(f.ai.values.astype(np.int64))
    P = torch.from_numpy(f.pos.values.astype(np.int64))
    Yd = torch.from_numpy(f[[f"d{h}" for h in HOR]].to_numpy(np.float32))
    Ys = torch.from_numpy(f[[f"s{h}" for h in HOR]].to_numpy(np.float32))

    def batch(idx, Xs):
        i = torch.from_numpy(idx)
        seq = CH[A[i][:, None], P[i][:, None] + offs[None, :]].permute(0, 2, 1)
        return seq, torch.from_numpy(Xs)

    net = build_net(Xs_tr.shape[1])
    opt = torch.optim.AdamW(net.parameters(), lr=1e-3, weight_decay=1e-4)
    bce = torch.nn.BCEWithLogitsLoss()
    hub = torch.nn.HuberLoss()

    def predict(idx, Xs):
        net.eval()
        out = []
        with torch.no_grad():
            for k in range(0, len(idx), 8192):
                s, x = batch(idx[k:k + 8192], Xs[k:k + 8192])
                out.append(net(s, x))
        net.train()
        o = torch.cat(out).numpy()
        return 1 / (1 + np.exp(-o[:, :3])), o[:, 3:]

    def vloss():
        pd_, ps_ = predict(va_idx, Xs_va)
        y = f[[f"d{h}" for h in HOR]].to_numpy()[va_idx]
        return float(-np.mean(y * np.log(pd_ + 1e-7) + (1 - y) * np.log(1 - pd_ + 1e-7)))

    best, best_state, bad = 1e9, None, 0
    for ep in range(12):
        perm = rng.permutation(len(tr_idx))
        for k in range(0, len(perm), 1024):
            j = perm[k:k + 1024]
            s, x = batch(tr_idx[j], Xs_tr[j])
            o = net(s, x)
            ii = torch.from_numpy(tr_idx[j])
            loss = bce(o[:, :3], Yd[ii]) + 0.5 * hub(o[:, 3:], Ys[ii])
            opt.zero_grad()
            loss.backward()
            opt.step()
        vl = vloss()
        if vl < best - 1e-5:
            best, bad = vl, 0
            best_state = {k: v.clone() for k, v in net.state_dict().items()}
        else:
            bad += 1
            if bad >= 2:
                break
    net.load_state_dict(best_state)
    pv = predict(va_idx, Xs_va)
    pt = predict(te_idx, Xs_te)
    return net, pv, pt, ep + 1, best


# ------------------------------------------------------------------------------------------------ fold runner
def run_fold(f, ch, X, name, t0, t1, save_models=False):
    T = f["T"].values
    tr_all = np.nonzero(T <= t0 - EMB)[0]
    te = np.nonzero((T > t0) & (T <= t1 - 240 * 60))[0]          # labels end inside the block
    cut = T[tr_all[int(len(tr_all) * 0.85)]]
    fit = tr_all[T[tr_all] <= cut - EMB]
    va = tr_all[T[tr_all] > cut]
    log(name, "fit", len(fit), "val", len(va), "test", len(te))
    res = {}
    # static scaler for NN
    sc = Scaler().fit(f[STATIC].to_numpy(np.float32)[fit])
    Xs = sc(f[STATIC].to_numpy(np.float32))
    pv_d, pv_s, pt_d, pt_s, eps = [], [], [], [], []
    for sd in SEEDS:
        t = time.time()
        net, pv, pt, ne, bl = nn_fit_predict(ch, f, fit, va, te, Xs[fit], Xs[va], Xs[te], sd)
        pv_d.append(pv[0]); pv_s.append(pv[1]); pt_d.append(pt[0]); pt_s.append(pt[1]); eps.append(ne)
        log(name, "nn seed", sd, "epochs", ne, "val logloss", round(bl, 5), f"{time.time() - t:.0f}s")
        if save_models:
            import torch
            torch.save(net.state_dict(), os.path.join(MODELS, f"nn_{name}_seed{sd}.pt"))
    res["nn"] = dict(va=np.mean(pv_d, 0), te=np.mean(pt_d, 0), va_s=np.mean(pv_s, 0), te_s=np.mean(pt_s, 0), epochs=eps)
    if save_models:
        with open(os.path.join(MODELS, f"scaler_{name}.pkl"), "wb") as fh:
            pickle.dump(dict(mu=sc.mu, sd=sc.sd, static=STATIC), fh)
    # LR / HGB on summary features
    from sklearn.ensemble import HistGradientBoostingClassifier
    from sklearn.linear_model import LogisticRegression
    sc2 = Scaler().fit(X[fit])
    Z = sc2(X)
    for mname in ("lr", "hgb"):
        va_p, te_p, mods = [], [], []
        for h in HOR:
            y = f[f"d{h}"].values
            if mname == "lr":
                m = LogisticRegression(C=0.05, max_iter=500)
            else:
                m = HistGradientBoostingClassifier(max_iter=200, learning_rate=0.05, min_samples_leaf=500,
                                                   l2_regularization=1.0, early_stopping=False, random_state=0)
            m.fit(Z[fit], y[fit].astype(int))
            va_p.append(m.predict_proba(Z[va])[:, 1]); te_p.append(m.predict_proba(Z[te])[:, 1])
            mods.append(m)
        res[mname] = dict(va=np.stack(va_p, 1), te=np.stack(te_p, 1))
        if save_models:
            with open(os.path.join(MODELS, f"{mname}_{name}.pkl"), "wb") as fh:
                pickle.dump(dict(models=mods, mu=sc2.mu, sd=sc2.sd), fh)
        log(name, mname, "done")
    rng = np.random.default_rng(hash(name) % 2**32)
    res["random"] = dict(va=rng.random((len(va), 3)), te=rng.random((len(te), 3)))
    return dict(name=name, va=va, te=te, res=res)


def metrics(f, fold):
    from sklearn.metrics import roc_auc_score
    from scipy.stats import spearmanr
    rows = []
    te = fold["te"]
    for m, r in fold["res"].items():
        for ai, a in enumerate(("BTC", "ETH")):
            msk = f.ai.values[te] == ai
            for j, h in enumerate(HOR):
                y = f[f"d{h}"].values[te][msk]
                p = r["te"][msk, j]
                bins = np.clip((p * 10).astype(int), 0, 9)
                ece = sum(abs(y[bins == k].mean() - p[bins == k].mean()) * (bins == k).mean() for k in range(10) if (bins == k).any())
                q = np.quantile(p, [0.05, 0.95])
                ext = (p <= q[0]) | (p >= q[1])
                hit = np.mean((p[ext] >= q[1]) == (y[ext] == 1))
                row = dict(fold=fold["name"], model=m, asset=a, h=h, n=int(msk.sum()), auc=roc_auc_score(y, p),
                           acc=np.mean((p > 0.5) == (y == 1)), base_up=y.mean(), brier=np.mean((p - y) ** 2),
                           ece=ece, hit_top5=hit)
                if m == "nn":
                    sz = f[f"s{h}"].values[te][msk]
                    row["size_ic"] = spearmanr(r["te_s"][msk, j], sz)[0]
                    row["size_ic_rv1d_only"] = spearmanr(f.rv1d.values[te][msk], sz * f.sig.values[te][msk])[0]
                    row["size_ic_nn_raw"] = spearmanr(r["te_s"][msk, j] * f.sig.values[te][msk], sz * f.sig.values[te][msk])[0]
                rows.append(row)
    return rows


def save_preds(f, fold, tag):
    te, va = fold["te"], fold["va"]
    parts = []
    for part, idx in (("te", te), ("va", va)):
        d = pd.DataFrame({"fold": fold["name"], "part": part, "asset": f.asset.values[idx], "b": f.b.values[idx],
                          "T": f["T"].values[idx]})
        for m, r in fold["res"].items():
            for j, h in enumerate(HOR):
                d[f"{m}_p{h}"] = r[part][:, j].astype(np.float32)
            if m == "nn":
                for j, h in enumerate(HOR):
                    d[f"nn_s{h}"] = r[part + "_s"][:, j].astype(np.float32)
        parts.append(d)
    pd.concat(parts).to_parquet(os.path.join(OUT, f"preds_{tag}_{fold['name']}.parquet"), compression="zstd", index=False)


def code_hash():
    h = hashlib.sha256()
    for fn in ("features.py", "model.py", "evaluate.py"):
        p = os.path.join(os.path.dirname(os.path.abspath(__file__)), fn)
        if os.path.exists(p):
            h.update(open(p, "rb").read())
    return h.hexdigest()[:16]


def stage_dev():
    f, ch = load()
    X = summary_feats(f, ch)
    log("rows", len(f), "static", len(STATIC), "summary", X.shape[1])
    rows = []
    for name, t0, t1 in FOLDS:
        fold = run_fold(f, ch, X, name, t0, t1)
        save_preds(f, fold, "dev")
        rows += metrics(f, fold)
        pd.DataFrame(rows).to_csv(os.path.join(LOGS, "dev_metrics.csv"), index=False)
    M = pd.DataFrame(rows)
    print(M.pivot_table(index=["model", "asset", "h"], columns="fold", values="auc").round(4).to_string())
    json.dump(dict(frozen_at=time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()), code_hash=code_hash(),
                   static=STATIC, seq=SEQ, win=WIN, horizons=HOR, seeds=SEEDS, hold_start="2026-04-01"),
              open(os.path.join(MODELS, "frozen.json"), "w"), indent=1)


def stage_holdout():
    flag = os.path.join(MODELS, "HOLDOUT_DONE")
    if os.path.exists(flag) and "--force" not in sys.argv:
        sys.exit("holdout already run once; refusing")
    fr = json.load(open(os.path.join(MODELS, "frozen.json")))
    log("frozen", fr["frozen_at"], "code hash now", code_hash(), "at freeze", fr["code_hash"])
    f, ch = load()
    X = summary_feats(f, ch)
    open(flag, "w").write(time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()))
    fold = run_fold(f, ch, X, "holdout", HOLD0, int(f["T"].max()) + 240 * 60 + 1, save_models=True)
    save_preds(f, fold, "hold")
    M = pd.DataFrame(metrics(f, fold))
    M.to_csv(os.path.join(LOGS, "holdout_metrics.csv"), index=False)
    print(M.round(4).to_string())


if __name__ == "__main__":
    {"dev": stage_dev, "holdout": stage_holdout}[sys.argv[1]]()
