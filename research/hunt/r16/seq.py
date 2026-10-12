"""R16 model ladder step 3 (PREREG.md): a small 1D-CNN on the last 60 one-minute bars + the tabular features, for
the targets where a tree model beat the baselines above the bar in DESIGN (a1, a2 on both indices). DESIGN only;
it becomes the frozen choice only if it beats the best of linear/LightGBM on DESIGN MAE.

    python3 -I research/hunt/r16/seq.py <scratch>
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd
import torch
from torch import nn

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lib  # noqa: E402
from run import HOLD_INTRA, VOL_F  # noqa: E402

torch.set_num_threads(4)
W = 60


def sequences(scr, sym, df):
    z = np.load(os.path.join(scr, f"{sym}.npz"))
    days = {pd.Timestamp(str(d)).date(): i for i, d in enumerate(z["days"])}
    ix = z["ix"]
    out = np.zeros((len(df), 2, W), dtype=np.float32)
    for r, (d, t) in enumerate(zip(df.day.values, df.t.values)):
        a = ix[days[d]]
        lo = t - W + 1
        C = a[:, 3]
        prev = np.r_[C[0], C[:-1]]
        ret = np.log(C / prev) * 1e3
        rng = np.log(np.maximum(a[:, 1] - a[:, 2], 1e-6) / C)
        s = np.zeros((2, W), dtype=np.float32)
        k = max(lo, 0)
        s[0, W - (t + 1 - k):] = ret[k:t + 1]
        s[1, W - (t + 1 - k):] = rng[k:t + 1] + 8.0      # log range ~ -8; pad = 0 means "no bar"
        out[r] = s
    return out


class Net(nn.Module):
    def __init__(self, nf):
        super().__init__()
        self.conv = nn.Sequential(nn.Conv1d(2, 16, 5, padding=2), nn.ReLU(), nn.Conv1d(16, 16, 5, padding=2), nn.ReLU(),
                                  nn.AdaptiveAvgPool1d(4))
        self.head = nn.Sequential(nn.Linear(64 + nf, 32), nn.ReLU(), nn.Linear(32, 1))

    def forward(self, s, x):
        return self.head(torch.cat([self.conv(s).flatten(1), x], 1)).squeeze(1)


def fit_predict(S, X, y, St, Xt, seed=16):
    torch.manual_seed(seed)
    mu, sd = np.nanmean(X, 0), np.nanstd(X, 0) + 1e-9
    f = lambda a: torch.tensor(np.nan_to_num((a - mu) / sd), dtype=torch.float32)
    ym = y.mean()
    net = Net(X.shape[1])
    opt = torch.optim.Adam(net.parameters(), lr=2e-3, weight_decay=1e-4)
    Xs, Ss, ys = f(X), torch.tensor(S), torch.tensor(y - ym, dtype=torch.float32)
    n = len(y)
    for ep in range(15):
        perm = torch.randperm(n)
        for i in range(0, n, 256):
            b = perm[i:i + 256]
            opt.zero_grad()
            loss = (net(Ss[b], Xs[b]) - ys[b]).abs().mean()
            loss.backward()
            opt.step()
    with torch.no_grad():
        return net(torch.tensor(St), f(Xt)).numpy() + ym


def main():
    scr = sys.argv[1]
    choices = json.load(open(os.path.join(scr, "choices.json")))
    res = {}
    for sym in ("BANKNIFTY", "NIFTY"):
        A = pd.read_parquet(os.path.join(scr, f"rows_{sym}_intra.parquet"))
        A["day"] = pd.to_datetime(A.day).dt.date
        for h, name in ((30, "a1"), (60, "a2")):
            key = f"{name}_{sym}"
            tgt = f"v{h}"
            feats = VOL_F + [f"btod{h}"]
            df = A[(A.t >= 30) & (A.t <= 360 - h) & A[tgt].notna() & (A.day < HOLD_INTRA[sym])].reset_index(drop=True)
            seqs = sequences(scr, sym, df)
            pred = pd.Series(np.nan, index=df.index)
            for m, end, test_days in lib.folds(df.day.values, None):
                tr = df.index[df.day < end]
                te = df.index[df.day.isin(test_days)]
                if len(tr) < 100 or not len(te):
                    continue
                pred.loc[te] = fit_predict(seqs[tr], df.loc[tr, feats].values.astype(float), df.loc[tr, tgt].values,
                                           seqs[te], df.loc[te, feats].values.astype(float))
            prev = pd.read_parquet(os.path.join(scr, f"preds_design_{name}_{sym}.parquet"))
            prev["day"] = pd.to_datetime(prev.day).dt.date
            j = df.assign(seq=pred).merge(prev[["day", "t", "pred", "base"]].rename(columns={"pred": "best"}),
                                          on=["day", "t"], how="inner")
            j = j[j.seq.notna()]
            mae_seq = float(np.mean(np.abs(j.seq - j[tgt])))
            mae_best = float(np.mean(np.abs(j.best - j[tgt])))
            mae_base = float(np.mean(np.abs(j.base - j[tgt])))
            win = mae_seq < mae_best
            res[key] = dict(n=int(len(j)), mae_cnn=mae_seq, mae_frozen=mae_best, mae_baseline=mae_base,
                            frozen=choices[key]["model"], cnn_wins=bool(win))
            print(key, res[key], flush=True)
            if win:
                choices[key]["model"] = "cnn"
                choices[key]["was"] = res[key]["frozen"]
    json.dump(choices, open(os.path.join(scr, "choices.json"), "w"), indent=1)
    json.dump(res, open(os.path.join(scr, "seq_design.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
