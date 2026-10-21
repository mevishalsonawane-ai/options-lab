"""R41 scoring (PREREG section 5-6): model metrics with session-block bootstrap CIs, calibration, BH, trade stats and
random twins. Pure numpy; no data access."""
from __future__ import annotations

import numpy as np

import r41cfg as K

EPS = 1e-6


# ------------------------------------------------------------------------------------------------ point metrics
def logloss(y, p) -> float:
    p = np.clip(np.asarray(p, float), EPS, 1 - EPS)
    y = np.asarray(y, float)
    return float(-np.mean(y * np.log(p) + (1 - y) * np.log(1 - p))) if len(y) else np.nan


def auc(y, p) -> float:
    y = np.asarray(y, int)
    p = np.asarray(p, float)
    n1, n0 = y.sum(), len(y) - y.sum()
    if n1 == 0 or n0 == 0:
        return np.nan
    from scipy.stats import rankdata
    r = rankdata(p)
    return float((r[y == 1].sum() - n1 * (n1 + 1) / 2) / (n1 * n0))


def brier(y, p) -> float:
    return float(np.mean((np.asarray(p, float) - np.asarray(y, float)) ** 2)) if len(y) else np.nan


def r2(y, f, ref=None) -> float:
    """1 - SSE / SST around the mean of y (ref: around another forecast instead)."""
    y, f = np.asarray(y, float), np.asarray(f, float)
    base = np.full(len(y), y.mean()) if ref is None else np.asarray(ref, float)
    sst = np.sum((y - base) ** 2)
    return float(1 - np.sum((y - f) ** 2) / sst) if sst > 0 else np.nan


def mae(y, f) -> float:
    return float(np.mean(np.abs(np.asarray(y, float) - np.asarray(f, float)))) if len(y) else np.nan


def score(kind, y, p) -> dict:
    if kind == "bin":
        return dict(n=int(len(y)), logloss=logloss(y, p), auc=auc(y, p), brier=brier(y, p))
    return dict(n=int(len(y)), r2=r2(y, p), mae=mae(y, p))


def primary(kind) -> str:
    return "logloss" if kind == "bin" else "r2"


def better(kind, a, b) -> bool:
    """Is metric value a better than b (log-loss lower, R² higher)?"""
    return a < b if kind == "bin" else a > b


# ------------------------------------------------------------------------------------------------ bootstrap by day
def _groups(days):
    u, inv = np.unique(np.asarray(days), return_inverse=True)
    return u, inv


def boot_diff(kind, y, p_a, p_b, days, n=K.BOOT, seed=K.SEED) -> dict:
    """Session-block bootstrap of metric(a) - metric(b) (log-loss: a - b < 0 means a is better; R²: > 0 better).
    Returns the point difference, 95% CI, and the one-sided p that a is NOT better."""
    y, p_a, p_b = (np.asarray(v, float) for v in (y, p_a, p_b))
    _, inv = _groups(days)
    G = inv.max() + 1 if len(inv) else 0
    if kind == "bin":
        pa, pb = np.clip(p_a, EPS, 1 - EPS), np.clip(p_b, EPS, 1 - EPS)
        la = -(y * np.log(pa) + (1 - y) * np.log(1 - pa))
        lb = -(y * np.log(pb) + (1 - y) * np.log(1 - pb))
        sa = np.bincount(inv, la, G)
        sb = np.bincount(inv, lb, G)
        cnt = np.bincount(inv, minlength=G)
        point = (sa.sum() - sb.sum()) / cnt.sum()
    else:
        ea, eb = (y - p_a) ** 2, (y - p_b) ** 2
        sa, sb = np.bincount(inv, ea, G), np.bincount(inv, eb, G)
        sy, sy2 = np.bincount(inv, y, G), np.bincount(inv, y * y, G)
        cnt = np.bincount(inv, minlength=G)

        def r2diff(w):
            N = (w * cnt).sum()
            sst = (w * sy2).sum() - (w * sy).sum() ** 2 / N
            return ((w * sb).sum() - (w * sa).sum()) / sst          # R²(a) - R²(b)
        point = r2diff(np.ones(G))
    rng = np.random.default_rng(seed)
    out = np.empty(n)
    for i in range(n):
        w = np.bincount(rng.integers(0, G, G), minlength=G).astype(float)
        if kind == "bin":
            out[i] = ((w * sa).sum() - (w * sb).sum()) / (w * cnt).sum()
        else:
            out[i] = r2diff(w)
    lo, hi = np.percentile(out, [2.5, 97.5])
    p_not_better = float(np.mean(out >= 0)) if kind == "bin" else float(np.mean(out <= 0))
    return dict(diff=float(point), lo=float(lo), hi=float(hi), p=max(p_not_better, 1.0 / n), sessions=int(G))


def boot_ci(kind, y, p, days, n=K.BOOT, seed=K.SEED) -> dict:
    """95% CI of the primary metric (and AUC for bin) by session blocks."""
    y, p = np.asarray(y, float), np.asarray(p, float)
    _, inv = _groups(days)
    G = inv.max() + 1 if len(inv) else 0
    rng = np.random.default_rng(seed)
    idx_by = [np.flatnonzero(inv == g) for g in range(G)]
    m, a = [], []
    for _ in range(min(n, 300)):            # 300 resamples for the per-model CI (the tests use boot_diff's 1,000)
        g = rng.integers(0, G, G)
        ii = np.concatenate([idx_by[k] for k in g])
        if kind == "bin":
            m.append(logloss(y[ii], p[ii]))
            a.append(auc(y[ii], p[ii]))
        else:
            m.append(r2(y[ii], p[ii]))
    out = dict(lo=float(np.percentile(m, 2.5)), hi=float(np.percentile(m, 97.5)))
    if kind == "bin":
        out.update(auc_lo=float(np.nanpercentile(a, 2.5)), auc_hi=float(np.nanpercentile(a, 97.5)))
    return out


def calibration(kind, y, p, bins=10) -> list[dict]:
    y, p = np.asarray(y, float), np.asarray(p, float)
    if len(y) < bins:
        return []
    q = np.quantile(p, np.linspace(0, 1, bins + 1))
    b = np.clip(np.searchsorted(q[1:-1], p, side="right"), 0, bins - 1)
    return [dict(bin=int(i), n=int((b == i).sum()), pred=float(p[b == i].mean()), obs=float(y[b == i].mean()))
            for i in range(bins) if (b == i).any()]


def bh(ps) -> list[float]:
    """Benjamini-Hochberg q-values (NaN p stays NaN and is not counted)."""
    ps = np.asarray(ps, float)
    ok = np.flatnonzero(np.isfinite(ps))
    q = np.full(len(ps), np.nan)
    if not len(ok):
        return q.tolist()
    p = ps[ok]
    m = len(p)
    o = np.argsort(p)
    r = p[o] * m / np.arange(1, m + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    q[ok[o]] = np.minimum(r, 1.0)
    return q.tolist()


# ------------------------------------------------------------------------------------------------ trades
def trade_stats(net, days, all_days, cut_short=None, net_x2=None) -> dict:
    """net: Rs per trade (after costs); days: the trade's session; all_days: every session the rule could trade (a day
    without a trade counts as Rs 0 in P&L/day, % green days and the worst day)."""
    net = np.asarray(net, float)
    days = np.asarray(days)
    alld = np.array(sorted(set(all_days)))
    n_days = len(alld)
    by = {}
    for d, x in zip(days, net):
        by[d] = by.get(d, 0.0) + x
    daily = np.array([by.get(d, 0.0) for d in alld])
    eq = np.cumsum(daily)
    dd = float((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min()) if n_days else 0.0
    win, loss = net[net > 0], net[net <= 0]
    traded = np.array([d in by for d in alld])
    out = dict(trades=int(len(net)), sessions=int(n_days), trades_per_day=float(len(net) / n_days) if n_days else 0.0,
               win_rate=float((net > 0).mean()) if len(net) else np.nan,
               avg_win=float(win.mean()) if len(win) else np.nan, avg_loss=float(loss.mean()) if len(loss) else np.nan,
               total=float(net.sum()), pnl_per_day=float(daily.mean()) if n_days else 0.0,
               green_days=float((daily[traded] > 0).mean()) if traded.any() else np.nan,
               worst_day=float(daily.min()) if n_days else 0.0, max_drawdown=dd)
    if cut_short is not None:
        out["big_winners_cut_short"] = int(np.nansum(cut_short))
    if net_x2 is not None:
        x = np.asarray(net_x2, float)
        out["pnl_per_day_stress"] = float(np.nansum(x) / n_days) if n_days and np.isfinite(x).any() else np.nan
    return out


def twin_p(rule_total: float, twin_totals: np.ndarray) -> float:
    t = np.asarray(twin_totals, float)
    return float((1 + np.sum(t >= rule_total)) / (1 + len(t)))


def twins_side(net_long: np.ndarray, net_short: np.ndarray, n=K.TWINS, seed=K.SEED) -> np.ndarray:
    """Random-side twins: each trade's side is a coin flip; returns the twins' total P&L. net_long/net_short are the
    P&L of the same entry taken on each side (calls vs puts; long vs short)."""
    a, b = np.asarray(net_long, float), np.asarray(net_short, float)
    rng = np.random.default_rng(seed)
    s = rng.integers(0, 2, (n, len(a))).astype(bool)
    return np.where(s, a, b).sum(1)


def twins_skip(net: np.ndarray, n_skip: int, n=K.TWINS, seed=K.SEED) -> np.ndarray:
    """Random-skip twins for a filter: skip n_skip trades at random; returns the kept totals."""
    net = np.asarray(net, float)
    rng = np.random.default_rng(seed)
    out = np.empty(n)
    for i in range(n):
        keep = np.ones(len(net), bool)
        keep[rng.choice(len(net), n_skip, replace=False)] = False
        out[i] = net[keep].sum()
    return out


def twins_pick(pool_net: np.ndarray, n_pick: int, n=K.TWINS, seed=K.SEED) -> np.ndarray:
    """Random picks of n_pick trades from the eligible pool; returns the totals."""
    pool = np.asarray(pool_net, float)
    rng = np.random.default_rng(seed)
    if n_pick == 0 or not len(pool):
        return np.zeros(n)
    return np.array([pool[rng.choice(len(pool), n_pick, replace=False)].sum() for _ in range(n)])
