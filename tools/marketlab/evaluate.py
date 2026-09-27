"""Walk-forward testing: the model is retrained on everything before each block and scored only on
the block that follows, so every reported number is out-of-sample."""
import math

import numpy as np
import pandas as pd

from .nn import Ensemble


def walk_forward(X, y, start_frac=0.6, step=60, n_models=5, **kw):
    n = len(y)
    s0 = int(n * start_frac)
    pred = np.full(n, np.nan)
    for i in range(s0, n, step):
        m = Ensemble(n=n_models, **kw).fit(X[:i], y[:i])
        pred[i:i + step] = m.predict(X[i:i + step])
    return pred, s0


def auc(y, p):
    y = np.asarray(y)
    pos, neg = y == 1, y == 0
    if pos.sum() == 0 or neg.sum() == 0:
        return float("nan")
    ranks = pd.Series(p).rank().values
    return float((ranks[pos].sum() - pos.sum() * (pos.sum() + 1) / 2) / (pos.sum() * neg.sum()))


def binom_p(k, n, p0=0.5):
    """Two-sided normal-approximation p-value for k hits in n tries against a hit rate of p0."""
    if n == 0:
        return float("nan")
    z = (k - n * p0) / math.sqrt(n * p0 * (1 - p0))
    return float(math.erfc(abs(z) / math.sqrt(2)))


def clf_metrics(y, p, prev_ret):
    y, p = np.asarray(y), np.asarray(p)
    hit = (p > 0.5) == (y == 1)
    n = len(y)
    base = max(y.mean(), 1 - y.mean())
    mom = ((np.asarray(prev_ret) > 0) == (y == 1)).mean()
    conf = np.abs(p - 0.5) >= 0.05
    return {
        "n": int(n), "accuracy": float(hit.mean()), "auc": auc(y, p),
        "logloss": float(-np.mean(y * np.log(p + 1e-9) + (1 - y) * np.log(1 - p + 1e-9))),
        "brier": float(np.mean((p - y) ** 2)),
        "up_rate": float(y.mean()), "baseline_majority": float(base), "baseline_momentum": float(mom),
        "p_value_vs_coin": binom_p(int(hit.sum()), n),
        "p_value_vs_majority": binom_p(int(hit.sum()), n, base),
        "confident_share": float(conf.mean()),
        "confident_accuracy": float(hit[conf].mean()) if conf.any() else None,
    }


def reg_metrics(y, p, naive):
    y, p, naive = map(np.asarray, (y, p, naive))
    ok = np.isfinite(y) & np.isfinite(p) & np.isfinite(naive)
    y, p, naive = y[ok], p[ok], naive[ok]
    ss = np.sum((y - y.mean()) ** 2)
    return {
        "n": int(len(y)),
        "r2": float(1 - np.sum((y - p) ** 2) / ss), "r2_naive": float(1 - np.sum((y - naive) ** 2) / ss),
        "corr": float(np.corrcoef(y, p)[0, 1]), "corr_naive": float(np.corrcoef(y, naive)[0, 1]),
        "mae": float(np.mean(np.abs(y - p))), "mae_naive": float(np.mean(np.abs(y - naive))),
    }


def strategy(p, next_ret, per_year, cost, th=0.02, long_only=False):
    """Trade the model: long when p > 0.5+th, short (or flat) when p < 0.5-th, else flat.
    `cost` is charged per unit of position change (one side)."""
    p, r = np.asarray(p), np.asarray(next_ret)
    pos = np.where(p > 0.5 + th, 1.0, np.where(p < 0.5 - th, 0.0 if long_only else -1.0, 0.0))
    turn = np.abs(np.diff(np.concatenate([[0.0], pos])))
    pnl = pos * r - turn * cost
    return pos, pnl, summarize(pnl, per_year) | {"trades": int((turn > 0).sum()),
                                                  "exposure": float((pos != 0).mean())}


def summarize(logret, per_year):
    logret = np.nan_to_num(np.asarray(logret))
    eq = np.exp(np.cumsum(logret))
    n = len(logret)
    yrs = n / per_year
    peak = np.maximum.accumulate(eq)
    sd = logret.std()
    return {
        "total_return": float(eq[-1] - 1) if n else 0.0,
        "cagr": float(eq[-1] ** (1 / yrs) - 1) if n and yrs > 0 else 0.0,
        "ann_vol": float(sd * math.sqrt(per_year)),
        "sharpe": float(logret.mean() / sd * math.sqrt(per_year)) if sd > 0 else 0.0,
        "max_drawdown": float((eq / peak - 1).min()) if n else 0.0,
        "hit_rate": float((logret[logret != 0] > 0).mean()) if (logret != 0).any() else 0.0,
    }


def permutation_importance(model, X, y, names, metric, repeats=5, seed=0):
    rng = np.random.default_rng(seed)
    base = metric(y, model.predict(X))
    out = []
    for j, name in enumerate(names):
        drops = []
        for _ in range(repeats):
            Xp = X.copy()
            Xp[:, j] = rng.permutation(Xp[:, j])
            drops.append(base - metric(y, model.predict(Xp)))
        out.append({"feature": name, "importance": float(np.mean(drops))})
    return sorted(out, key=lambda d: -d["importance"])


def run_direction_study(feat, tgt, per_year, cost, step, label, n_models=5, start_frac=0.6, batch=64):
    """Walk-forward direction model on a feature frame; returns metrics, strategy results and series."""
    df = feat.join(tgt[["up", "next_ret"]]).replace([np.inf, -np.inf], np.nan)
    df = df.dropna(subset=["up", "next_ret"])
    df = df.loc[df[feat.columns].notna().mean(axis=1) > 0.9]
    X = df[feat.columns].values.astype(float)
    y = df["up"].values
    pred, s0 = walk_forward(X, y, start_frac=start_frac, step=step, n_models=n_models,
                            task="clf", hidden=(32, 16), dropout=0.2, l2=1e-3, batch=batch)
    idx = df.index[s0:]
    p, yy, nr = pred[s0:], y[s0:], df["next_ret"].values[s0:]
    prev = df["ret_l1"].values[s0:] if "ret_l1" in df else np.zeros(len(yy))
    res = {"label": label, "features": list(feat.columns), "train_rows_first": int(s0),
           "test_start": str(idx[0]), "test_end": str(idx[-1]),
           "metrics": clf_metrics(yy, p, prev)}
    strat = {}
    for name, lo in (("long_short", False), ("long_flat", True)):
        pos, pnl, s = strategy(p, nr, per_year, cost, long_only=lo)
        strat[name] = s
        strat[name + "_equity"] = np.exp(np.cumsum(pnl)).round(5).tolist()
    strat["buy_hold"] = summarize(nr, per_year)
    strat["buy_hold_equity"] = np.exp(np.cumsum(np.nan_to_num(nr))).round(5).tolist()
    res["strategy"] = strat
    res["series"] = {"t": [str(t) for t in idx], "p": np.round(p, 4).tolist()}
    # importance: one model trained on the first 80 %, scored on the last 20 %
    cut = int(len(y) * 0.8)
    m = Ensemble(n=3, task="clf", hidden=(32, 16), dropout=0.2, l2=1e-3, batch=batch).fit(X[:cut], y[:cut])
    res["importance"] = permutation_importance(m, X[cut:], y[cut:], list(feat.columns), auc)[:15]
    # the model's reading of the latest bar (all data), for curiosity only
    full = feat.replace([np.inf, -np.inf], np.nan).iloc[[-1]]
    last = Ensemble(n=n_models, task="clf", hidden=(32, 16), dropout=0.2, l2=1e-3, batch=batch).fit(X, y)
    res["latest"] = {"time": str(full.index[-1]), "p_up": float(last.predict(full.values.astype(float))[0])}
    return res


def run_vol_study(feat, tgt, per_year, step, label, implied=None, n_models=5, start_frac=0.6):
    """Walk-forward model of next-5-bar realised volatility (log), against 'vol stays the same'."""
    df = feat.join(tgt[["fwd_rv"]]).replace([np.inf, -np.inf], np.nan).dropna(subset=["fwd_rv"])
    df = df.loc[df[feat.columns].notna().mean(axis=1) > 0.9]
    X = df[feat.columns].values.astype(float)
    y = df["fwd_rv"].values
    pred, s0 = walk_forward(X, y, start_frac=start_frac, step=step, n_models=n_models,
                            task="reg", hidden=(32, 16), dropout=0.1, l2=1e-3)
    naive = np.log(df["rv5"].values + 1e-6)
    res = {"label": label, "test_start": str(df.index[s0]), "test_end": str(df.index[-1]),
           "metrics": reg_metrics(y[s0:], pred[s0:], naive[s0:])}
    if implied is not None:
        iv = np.log(implied.reindex(df.index).values / 100 + 1e-6)
        res["metrics_implied"] = reg_metrics(y[s0:], iv[s0:], naive[s0:])
    res["series"] = {"t": [str(t) for t in df.index[s0:]],
                     "actual": np.round(np.exp(y[s0:]), 4).tolist(),
                     "pred": np.round(np.exp(pred[s0:]), 4).tolist(),
                     "naive": np.round(np.exp(naive[s0:]), 4).tolist()}
    return res
