"""R44 engine: signals -> trades (one position a side, max 2 entries a day a side), cell statistics, random twins.

Guard: `days_of(period)` refuses the holdout-touching periods unless run.py has opened its one holdout read
(ALLOW_HOLDOUT), so no design step can read a session from 27 Apr 2026 on.
"""
from __future__ import annotations

from datetime import date
from functools import lru_cache

import numpy as np
import pandas as pd

import r44cfg as K
import r44data
import sim
from ind import signals

ALLOW_HOLDOUT = False


def check_period(period: str):
    if period not in K.PERIODS:
        raise ValueError(period)
    if K.PERIODS[period][2] and not ALLOW_HOLDOUT:
        raise PermissionError(f"R44 period {period} touches the locked holdout (from {K.HOLDOUT_START}); "
                              f"it is read only by `run.py holdout`, once")


def in_period(day_ord: np.ndarray, period: str, last_sessions: list | None = None) -> np.ndarray:
    """Mask of day ordinals inside `period` (after the guard). Embargo days (14-26 Apr 2026) are in no design period."""
    check_period(period)
    a, b, _ = K.PERIODS[period]
    d = np.asarray(day_ord)
    return (d >= a.toordinal()) & (d <= b.toordinal())


@lru_cache(maxsize=8)
def data(und: str):
    return r44data.load(und)


def _guard_bars(D):
    """Never compute anything past the design end unless the holdout read is open."""
    if ALLOW_HOLDOUT:
        return D.bd, D.bb, D.bo, D.bh, D.bl, D.bc
    k = D.bd <= K.DESIGN_END.toordinal()
    return D.bd[k], D.bb[k], D.bo[k], D.bh[k], D.bl[k], D.bc[k]


_SIG: dict = {}


def signal_bars(D, strat: str):
    """(day, bar, side) of every signal (side 0 = CE, 1 = PE), in time order. Cached per series and signal."""
    kind, params, _, _ = K.STRATS[strat]
    ck = (D.und, id(D), kind, params, ALLOW_HOLDOUT)
    if ck not in _SIG:
        _SIG[ck] = _signal_bars(D, kind, params)
    return _SIG[ck]


def _signal_bars(D, kind, params):
    bd, bb, bo, bh, bl, bc = _guard_bars(D)
    lo, sh = signals(kind, params, bo, bh, bl, bc)
    out = []
    for side, m in ((0, lo), (1, sh)):
        i = np.flatnonzero(m)
        out.append(pd.DataFrame(dict(d=bd[i], b=bb[i], side=side, t=i)))
    return pd.concat(out).sort_values(["t", "side"]).reset_index(drop=True)


def plan_trades(D, sig: pd.DataFrame, side: int, session: str, keep_expiry: bool):
    """Apply the trade rules that do not need the exit (entry window, option day, ATM contract, expiry skip).
    Returns a frame of candidate entries (d, e, pi) in time order."""
    first, last = K.entry_bars(D.und, session)
    s = sig[sig.side == side]
    e = s.b.values + 1
    ok = (e >= first) & (e <= last)
    d = s.d.values[ok]
    e = e[ok]
    rows = np.array([D.day_row(int(x)) for x in d], dtype=np.int64)
    has = rows >= 0
    d, e, rows = d[has], e[has], rows[has]
    if not keep_expiry:
        ne = np.array([int(x) not in D.expiry for x in d], dtype=bool)
        d, e, rows = d[ne], e[ne], rows[ne]
    pi = D.pidx[rows, e, side] if len(rows) else np.zeros(0, np.int32)
    k = pi >= 0
    return pd.DataFrame(dict(d=d[k], e=e[k], pi=pi[k]))


def take_trades(D, cand: pd.DataFrame, strat: str):
    """Sequential rules: one open position, max 2 fills a day. Simulates every candidate once (batch), then walks them
    in order. Returns a frame of the taken trades (d, e, pi, raw/real entry/exit, exit bar, reason)."""
    if not len(cand):
        return pd.DataFrame(columns=["d", "e", "pi", "E", "X", "rE", "rX", "xb", "reason"])
    res, xb, rs = sim.run_batch(D, cand.pi.values, cand.e.values, strat)
    keep = []
    cur_d, n_day, busy_to = None, 0, -1
    for i, (d, e) in enumerate(zip(cand.d.values, cand.e.values)):
        if d != cur_d:
            cur_d, n_day, busy_to = d, 0, -1
        if n_day >= K.MAX_PER_DAY or e <= busy_to:
            continue
        if rs[i] == sim.R_NOFILL:
            continue
        keep.append(i)
        n_day += 1
        busy_to = xb[i]
    k = np.array(keep, dtype=np.int64)
    out = cand.iloc[k].reset_index(drop=True).copy()
    out["E"], out["X"], out["rE"], out["rX"] = res[k, 0], res[k, 1], res[k, 2], res[k, 3]
    out["xb"], out["reason"] = xb[k], rs[k]
    return out


def add_nets(D, tr: pd.DataFrame) -> pd.DataFrame:
    if not len(tr):
        for c in ("boss", "real", "real_hist"):
            tr[c] = np.zeros(0)
        return tr
    res = tr[["E", "X", "rE", "rX"]].values
    tr["boss"], tr["real"] = sim.nets(D.und, res, tr.d.values)
    q = np.array([K.lot_hist(D.und, date.fromordinal(int(x))) for x in tr.d.values], float)
    tr["real_hist"] = sim.nets(D.und, res, tr.d.values, q)[1]
    return tr


def all_trades(und: str, strat: str, side: int, session: str, keep_expiry: bool = False) -> pd.DataFrame:
    D = data(und)
    sig = signal_bars(D, strat)
    cand = plan_trades(D, sig, side, session, keep_expiry)
    return add_nets(D, take_trades(D, cand, strat))


# ------------------------------------------------------------------------------------------------ statistics
def max_dd(daily: np.ndarray) -> float:
    if not len(daily):
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(daily)])
    return float((np.maximum.accumulate(eq) - eq).max())


def stats(tr: pd.DataFrame, n_sessions: int, col: str = "real") -> dict:
    n = len(tr)
    if not n:
        return dict(trades=0, win=None, net=0.0, exp=None, per_session=0.0, worst_day=0.0, max_dd=0.0,
                    net_boss=0.0, net_hist=0.0, ex_best_day=0.0, days=0)
    daily = tr.groupby("d")[col].sum()
    return dict(trades=n, win=round(float((tr[col] > 0).mean()), 4), net=round(float(tr[col].sum()), 2),
                exp=round(float(tr[col].mean()), 2),
                per_session=round(float(tr[col].sum()) / max(n_sessions, 1), 2),
                worst_day=round(float(daily.min()), 2), max_dd=round(max_dd(daily.values), 2),
                net_boss=round(float(tr.boss.sum()), 2), win_boss=round(float((tr.boss > 0).mean()), 4),
                net_hist=round(float(tr.real_hist.sum()), 2),
                ex_best_day=round(float(daily.sum() - daily.max()), 2), days=int(len(daily)),
                reasons={sim.REASONS[int(k)]: int(v) for k, v in tr.reason.value_counts().items()})


def valid_entry_bars(D, d_row: int, side: int, session: str):
    first, last = K.entry_bars(D.und, session)
    out = []
    for e in range(first, last + 1):
        pi = D.pidx[d_row, e, side]
        if pi >= 0 and np.isfinite(D.P[pi, 0, e]) and D.P[pi, 0, e] > 0:
            out.append((e, pi))
    return out


def twins(D, tr: pd.DataFrame, strat: str, side: int, session: str, n_twins: int = K.TWINS, seed: int = K.SEED):
    """Random-entry twins: same days, same number of entries a day, uniform entry bars (no replacement within a day)
    among the day's fillable ATM entries, same side and exits. Returns (p, twin real nets array)."""
    if not len(tr):
        return None, np.zeros(0)
    rng = np.random.default_rng(seed)
    per_day = tr.groupby("d").size()
    pis, es, ds, tw = [], [], [], []
    for d, cnt in per_day.items():
        vb = valid_entry_bars(D, D.day_row(int(d)), side, session)
        if not vb:
            continue
        cnt = min(int(cnt), len(vb))
        vb = np.array(vb)
        j = np.argsort(rng.random((n_twins, len(vb))), axis=1)[:, :cnt]     # cnt distinct bars per twin
        es.append(vb[j, 0].ravel())
        pis.append(vb[j, 1].ravel())
        ds.append(np.full(n_twins * cnt, d))
        tw.append(np.repeat(np.arange(n_twins), cnt))
    pi, e, d, tw = (np.concatenate(x) for x in (pis, es, ds, tw))
    res, xb, rs = sim.run_batch(D, pi, e, strat)
    ok = rs != sim.R_NOFILL
    _, real = sim.nets(D.und, np.nan_to_num(res), d)
    real = np.where(ok, real, 0.0)
    tot = np.bincount(tw, weights=real, minlength=n_twins)
    actual = float(tr.real.sum())
    p = (1 + int((tot >= actual).sum())) / (n_twins + 1)
    return p, tot


def bh(p: np.ndarray) -> np.ndarray:
    """Benjamini-Hochberg q values."""
    p = np.asarray(p, float)
    n = len(p)
    if not n:
        return p
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out
