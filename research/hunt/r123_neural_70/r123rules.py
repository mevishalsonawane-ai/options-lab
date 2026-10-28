"""R123 rules, twins and statistics (PREREG section 6). Pure functions over a frame of decision rows that carries the
network's predictions (pw_j, pl_j, pn_j, rg60) and the simulated outcomes (cls_j, net_j, netx2_j, exit_j).
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

import r123cfg as K

NJ = len(K.CS)


def attach(F: pd.DataFrame, pred: dict, m_neither: dict) -> pd.DataFrame:
    """Copy the network's outputs onto the rows, plus each row's EV per cell-side (PREREG S4)."""
    F = F.copy()
    P = pred["probs"]
    for j in range(NJ):
        F[f"pw_{j}"], F[f"pl_{j}"], F[f"pn_{j}"] = P[:, j, 0], P[:, j, 1], P[:, j, 2]
        x, y, _ = K.CELLS[j // 2]
        m = np.array([m_neither.get((u, j), 0.0) for u in F.und.values])
        F[f"ev_{j}"] = P[:, j, 0] * x - P[:, j, 1] * y + P[:, j, 2] * m
    if "spread" in pred:                                   # N2 Level 4 abstention (Amendment 1)
        for j in range(NJ):
            F[f"abst_{j}"] = pred["spread"][:, j] > K.ABSTAIN
    F["rg60"] = pred["range"][:, K.HS.index(60)]
    F["pdir30"] = pred["dir"][:, 0]
    F["pdir60"] = pred["dir"][:, 1]
    return F


def neither_means(F: pd.DataFrame) -> dict:
    """Training mean net of the 'neither' outcomes per index and cell-side (S4's m)."""
    out = {}
    for j in range(NJ):
        c, n = F[f"cls_{j}"].values, F[f"net_{j}"].values
        for u in K.UNDS:
            ok = (F.und.values == u) & (c == K.NEITHER) & np.isfinite(n)
            out[(u, j)] = float(n[ok].mean()) if ok.sum() >= 30 else 0.0
    return out


def candidates(F: pd.DataFrame, rule: str) -> pd.DataFrame:
    """For each row: the rule's chosen cell-side j and its score, or no entry. A side whose trade cannot be filled
    (no option bar at t+1, or the window passes the square-off) is never chosen."""
    R = K.RULES[rule]
    cls = F[[f"cls_{j}" for j in range(NJ)]].values
    valid = cls >= 0
    if R["kind"] == "p":
        c = R["cell"]
        js = np.array([2 * c, 2 * c + 1])
        pw = F[[f"pw_{j}" for j in js]].values.copy()
        pw[~valid[:, js]] = -1.0
        k = pw.argmax(1)
        j = js[k]
        score = pw[np.arange(len(F)), k]
        ok = score >= R["thr"]
        if R["room"] is not None:
            lot = np.array([K.LOT[u] for u in F.und.values])
            pts = np.exp(F.rg60.values) / 100 * F.close.values
            ok &= pts * K.ATM_DELTA * lot >= R["room"]
    else:
        ev = F[[f"ev_{j}" for j in range(NJ)]].values.copy()
        ev[~valid] = -np.inf
        j = ev.argmax(1)
        score = ev[np.arange(len(F)), j]
        ok = score >= R["thr"]
    if R["rows"] is not None:
        ok &= F.row.isin(R["rows"]).values
    if "abst_0" in F:                                      # N2 Level 4: the chosen cell-side's seeds disagree
        ab = F[[f"abst_{x}" for x in range(NJ)]].values.astype(bool)
        ok &= ~ab[np.arange(len(F)), j]
    C = F.loc[ok, ["und", "day", "row"]].copy()
    C["j"] = j[ok]
    C["score"] = score[ok]
    idx = np.flatnonzero(ok)
    jj = j[ok]
    C["cls"] = cls[idx, jj]
    C["net"] = F[[f"net_{x}" for x in range(NJ)]].values[idx, jj]
    C["netx2"] = F[[f"netx2_{x}" for x in range(NJ)]].values[idx, jj]
    C["exit"] = F[[f"exit_{x}" for x in range(NJ)]].values[idx, jj]
    return C


def portfolio(C: pd.DataFrame, day_max: int = K.DAY_MAX) -> pd.DataFrame:
    """At most one open trade per index and day_max trades a day, in time order; ties at one minute by score."""
    C = C.sort_values(["day", "row", "score"], ascending=[True, True, False])
    keep = []
    for d, g in C.groupby("day", sort=True):
        busy, n = {}, 0
        for ix, r in zip(g.index, g.itertuples(index=False)):
            if n >= day_max:
                break
            if busy.get(r.und, -1) > r.row:
                continue
            keep.append(ix)
            busy[r.und] = r.exit
            n += 1
    return C.loc[keep]


def trades(F: pd.DataFrame, rule: str) -> pd.DataFrame:
    return portfolio(candidates(F, rule))


# ------------------------------------------------------------------------------------------------ twins
def twin_pools(F: pd.DataFrame) -> dict:
    """{(und, day, cell, side): array of nets} over every row where that cell-side is defined."""
    pools = {}
    for j in range(NJ):
        c, n = F[f"cls_{j}"].values, F[f"net_{j}"].values
        ok = (c >= 0) & np.isfinite(n)
        sub = pd.DataFrame(dict(und=F.und.values[ok], day=F.day.values[ok], net=n[ok]))
        for (u, d), g in sub.groupby(["und", "day"]):
            pools[(u, d, j // 2, j % 2)] = g.net.values
    return pools


def twins(T: pd.DataFrame, pools: dict, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """Random-entry twins (PREREG section 6): per rule trade, a random row of the same index and day where its cell
    is defined, a random side, the same exits. Returns n twin totals."""
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    for u, d, j in zip(T.und.values, T.day.values, T.j.values):
        c = j // 2
        side = rng.integers(0, 2, n)
        pc, pp = pools.get((u, d, c, 0)), pools.get((u, d, c, 1))
        for s, pool, other in ((0, pc, pp), (1, pp, pc)):
            m = side == s
            pl = pool if pool is not None and len(pool) else other
            if pl is None or not len(pl) or not m.any():
                continue
            tot[m] += pl[rng.integers(0, len(pl), m.sum())]
    return tot


def side_twins(T: pd.DataFrame, F: pd.DataFrame, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """Same entries, random side (reported only): the other side's outcome at the same row and cell, when it exists."""
    rng = np.random.default_rng(seed + 1)
    key = F.set_index(["und", "day", "row"])
    tot = np.zeros(n)
    for u, d, r, j, net in zip(T.und.values, T.day.values, T.row.values, T.j.values, T.net.values):
        jo = j + 1 if j % 2 == 0 else j - 1
        other = key.at[(u, d, r), f"net_{jo}"]
        if not np.isfinite(other):
            other = net
        flip = rng.integers(0, 2, n).astype(bool)
        tot += np.where(flip, other, net)
    return tot


def p_vs(tot: float, tw: np.ndarray) -> float:
    return float((1 + (tw >= tot).sum()) / (1 + len(tw)))


def bh(ps) -> list:
    ps = np.asarray(ps, dtype=float)
    m = len(ps)
    if m == 0:
        return []
    o = np.argsort(ps)
    q = ps[o] * m / np.arange(1, m + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(m)
    out[o] = np.minimum(q, 1.0)
    return out.tolist()


# ------------------------------------------------------------------------------------------------ statistics
def stats(T: pd.DataFrame, sessions: list) -> dict:
    """Per-rule numbers for one period. sessions: every trading session of the period (for the per-day figure)."""
    n = len(T)
    if n == 0:
        return dict(n=0, sessions=len(sessions))
    net, nx2 = T.net.values, T.netx2.values
    win = net > 0
    eq = np.cumsum(net)
    dd = float((eq - np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:]).min())
    dd = min(dd, 0.0)
    daily = T.groupby("day").net.sum()
    month = T.assign(mo=[f"{d.year}-{d.month:02d}" for d in T.day]).groupby("mo").net.sum()
    return dict(
        n=n, sessions=len(sessions), trade_days=int(daily.size), trades_per_session=n / max(len(sessions), 1),
        win_rate=float(win.mean()), target_hit_rate=float((T.cls.values == K.WIN).mean()),
        stop_rate=float((T.cls.values == K.LOSS).mean()),
        avg_win=float(net[win].mean()) if win.any() else 0.0,
        avg_loss=float(net[~win].mean()) if (~win).any() else 0.0,
        avg_net=float(net.mean()), avg_net_x2=float(nx2.mean()),
        breakeven_win=float(-net[~win].mean() / (net[win].mean() - net[~win].mean())) if win.any() and (~win).any()
        else float("nan"),
        total=float(net.sum()), total_x2=float(nx2.sum()),
        per_session=float(net.sum() / max(len(sessions), 1)), per_trade_day=float(daily.mean()),
        max_dd=dd, worst_day=float(daily.min()), best_day=float(daily.max()), green_days=float((daily > 0).mean()),
        worst_month=float(month.min()), worst_month_name=str(month.idxmin()), months=int(month.size),
        months_green=int((month > 0).sum()),
        by_und={u: dict(n=int((T.und == u).sum()), net=float(T.net[T.und == u].sum()),
                        win=float((T.net[T.und == u] > 0).mean()) if (T.und == u).any() else float("nan"))
                for u in K.UNDS},
        by_cell={str(K.CELLS[c]): int((T.j // 2 == c).sum()) for c in range(len(K.CELLS))},
        calls=float((T.j % 2 == 0).mean()))


def calibration(F: pd.DataFrame, cell: int) -> list:
    """Predicted P(+X first) for the better side of a cell, in bins, vs what happened (rows where it is defined)."""
    js = [2 * cell, 2 * cell + 1]
    pw = F[[f"pw_{j}" for j in js]].values.copy()
    cls = F[[f"cls_{j}" for j in js]].values
    pw[cls < 0] = -1
    k = pw.argmax(1)
    p = pw[np.arange(len(F)), k]
    c = cls[np.arange(len(F)), k]
    ok = c >= 0
    out = []
    lo_all = (0.0,) + K.CALIB_BINS
    for lo, hi in zip(lo_all[:-1], lo_all[1:]):
        m = ok & (p >= lo) & (p < hi)
        out.append(dict(bin=f"[{lo:.1f}, {min(hi, 1.0):.1f})", n=int(m.sum()),
                        predicted=float(p[m].mean()) if m.any() else None,
                        observed=float((c[m] == K.WIN).mean()) if m.any() else None))
    return out


def session_boot_ci(day: np.ndarray, diff: np.ndarray, n: int = K.BOOT, seed: int = K.SEED):
    """95% session-block bootstrap CI of the mean of `diff`, and the one-sided p that the mean is <= 0."""
    rng = np.random.default_rng(seed)
    df = pd.DataFrame(dict(d=day, x=diff)).dropna()
    g = df.groupby("d").x.agg(["sum", "count"])
    s, c = g["sum"].values, g["count"].values
    k = len(s)
    if k == 0:
        return float("nan"), float("nan"), float("nan"), 1.0
    b = rng.integers(0, k, (n, k))
    means = s[b].sum(1) / c[b].sum(1)
    m = s.sum() / c.sum()
    p = float((1 + (means <= 0).sum()) / (1 + n))
    return float(m), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5)), p


def brier(p, y):
    return (np.asarray(p) - np.asarray(y)) ** 2


def logloss(p, y):
    p = np.clip(np.asarray(p, dtype=float), 1e-6, 1 - 1e-6)
    y = np.asarray(y, dtype=float)
    return -(y * np.log(p) + (1 - y) * np.log(1 - p))


def r2(pred, y):
    y = np.asarray(y)
    ok = np.isfinite(y) & np.isfinite(pred)
    return 1 - np.sum((y[ok] - pred[ok]) ** 2) / np.sum((y[ok] - y[ok].mean()) ** 2) if ok.sum() > 2 else math.nan
