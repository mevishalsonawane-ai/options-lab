"""R46 book-keeping: turn signals into trades (one position at a time, max 2 per IST day), summaries, random twins
and Benjamini-Hochberg."""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import r46cfg as K


def ist_day(ts) -> pd.Series:
    return (pd.DatetimeIndex(ts) + K.IST).normalize()


def select(sig_entries: pd.DatetimeIndex, pool: pd.DataFrame) -> pd.DataFrame:
    """Walk the signals in time order. A signal becomes a trade when its pool row is fillable (ok), no position is
    open at its entry minute, and fewer than MAX_PER_DAY trades were entered on its IST day. Unfilled signals use no
    slot. pool: one row per entry minute (one side, mode and exit), indexed by entry."""
    trades = []
    busy_until = pd.Timestamp.min
    per_day: dict = {}
    P = pool[~pool.index.duplicated()]
    for E in sorted(sig_entries):
        if E not in P.index:
            continue
        r = P.loc[E]
        if not bool(r.ok):
            continue
        if E < busy_until:
            continue
        d = (E + K.IST).normalize()
        if per_day.get(d, 0) >= K.MAX_PER_DAY:
            continue
        per_day[d] = per_day.get(d, 0) + 1
        busy_until = r.exit_ts + pd.Timedelta(minutes=1)
        trades.append(r)
    if not trades:
        return pool.iloc[:0].copy()
    T = pd.DataFrame(trades)
    T.index.name = "entry"
    return T


def max_dd(pnl: np.ndarray) -> float:
    eq = np.cumsum(np.asarray(pnl, float))
    if not len(eq):
        return 0.0
    peak = np.maximum.accumulate(np.r_[0.0, eq])[1:]
    return float((peak - eq).max())


def summary(T: pd.DataFrame, days: int, scale: float = 1.0) -> dict:
    """Per-trade money in USD x scale (1 = one lot; CLAIM_CONTRACTS = the other AI's 1000 contracts)."""
    n = len(T)
    if not n:
        return dict(trades=0, days=days)
    net, after = T.net.to_numpy(float) * scale, T.net_after.to_numpy(float) * scale
    w = net > 0
    return dict(
        trades=n, days=days, win=round(float(w.mean()), 4),
        avg_win=round(float(net[w].mean()), 2) if w.any() else None,
        avg_loss=round(float(net[~w].mean()), 2) if (~w).any() else None,
        gross=round(float((T.gross.to_numpy(float) * scale).sum()), 2),
        fees=round(float((T.fees.to_numpy(float) * scale).sum()), 2),
        net=round(float(net.sum()), 2), per_trade=round(float(net.mean()), 2),
        tax=round(float((T.tax.to_numpy(float) * scale).sum()), 2), after_tax=round(float(after.sum()), 2),
        after_per_trade=round(float(after.mean()), 2), per_day=round(float(after.sum()) / max(days, 1), 2),
        worst=round(float(after.min()), 2), max_dd=round(max_dd(after), 2),
        tds=round(float((T.tds.to_numpy(float) * scale).sum()), 2),
        stops=int((T.reason == 0).sum()), locks=int((T.reason == 1).sum()), times=int((T.reason == 2).sum()))


def twin_p(T: pd.DataFrame, pool: pd.DataFrame, draws: int = K.TWIN_DRAWS, seed: int = 0, col: str = "net_after"):
    """Random-entry twins: for each draw, on each IST day the rule traded, as many random fillable entry minutes of
    the same day (same side, mode and exit) as the rule took. p = (1 + #draws with mean >= the rule's) / (draws + 1).
    Returns (p, twin means)."""
    if not len(T):
        return np.nan, np.array([])
    P = pool[pool.ok.astype(bool)]
    vals = pd.Series(P[col].to_numpy(float), index=ist_day(P.index))
    groups = {d: g.to_numpy() for d, g in vals.groupby(level=0)}
    need = ist_day(T.index).value_counts()
    rng = np.random.default_rng(seed)
    means = np.empty(draws)
    for k in range(draws):
        tot, cnt = 0.0, 0
        for d, c in need.items():
            g = groups.get(d)
            if g is None or not len(g):
                continue
            tot += g[rng.integers(0, len(g), c)].sum()
            cnt += c
        means[k] = tot / cnt if cnt else np.nan
    rule = float(T[col].mean())
    p = float((1 + np.sum(means >= rule)) / (draws + 1))
    return p, means


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    m = int(ok.sum())
    if not m:
        return q
    pv = p[ok]
    o = np.argsort(pv)
    r = pv[o] * m / np.arange(1, m + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    out = np.empty(m)
    out[o] = np.minimum(r, 1.0)
    q[ok] = out
    return q
