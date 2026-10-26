"""R107 book maths (PREREG sections 3-6), pure: the paper book's walk with the same-direction cap, the size overlay, risk
ratios, the account-rule counts, random-day twins (vectorised), BH and the gates. test_r107.py covers every function.

Trades are dicts with at least: und, day (date), side ('C' / 'P'), er (fill row), xr (exit row), net (1 lot) and, where
sized, net2 (the same trade priced at 2 lots).
"""
from __future__ import annotations

import heapq
import math
from collections import defaultdict

import numpy as np

import r107cfg as K


# ------------------------------------------------------------------------------------------------ the paper book's walk
def direction(side: str) -> int:
    return 1 if str(side).upper().startswith("C") else -1


def walk_cap(units: list, priced: list, cap_of=None, max_per_side: int = 2) -> list:
    """One day of the paper book: units [(key, cands, how)] in the book's tie order (arm, index, book), priced[g][j] the
    trade of cands[j] (None = no fill). Each arm's own taking rule (as R101 / R106: 'book', 'side2', 'one') plus the
    same-direction cap: an entry is refused when cap_of(cand, trade) or more positions betting the same way are open at
    its fill row (open = fill row <= row <= exit row). A refused entry takes nothing (its book stays free, no fill counted).
    cap_of None = no cap. Returns the taken trades (candidate keys added, 'er_sig' = the signal's entry row)."""
    heap = []
    for g, (key, cands, how) in enumerate(units):
        last = -1
        for j, c in enumerate(cands):
            assert c["er"] >= last, "candidates must be in time order"
            last = c["er"]
            heap.append((c["er"], g, j, c, priced[g][j]))
    heapq.heapify(heap)
    busy, n_side, one_done = {}, {}, set()
    open_pos = []                  # (fill row, exit row, direction)
    taken = []
    while heap:
        er, g, j, c, r = heapq.heappop(heap)
        if r is None:
            continue
        key, _, how = units[g]
        if how == "book":
            bk = (g, c.get("book"))
        elif how == "side2":
            bk = (g, c["side"])
        else:
            bk = (g,)
        if er <= busy.get(bk, -1):
            continue
        if how == "side2" and n_side.get(bk, 0) >= max_per_side:
            continue
        if how == "one" and g in one_done:
            continue
        if cap_of is not None:
            dr = direction(c["side"])
            fill = r["er"]
            n_open = sum(1 for a, b, d_ in open_pos if d_ == dr and a <= fill <= b)
            if n_open >= cap_of(c, r):
                continue
        busy[bk] = r["xr"]
        n_side[bk] = n_side.get(bk, 0) + 1
        one_done.add(g)
        open_pos.append((r["er"], r["xr"], direction(c["side"])))
        t = dict(r)
        t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        t["er_sig"] = c["er"]
        t["_g"] = g
        taken.append(t)
    taken.sort(key=lambda t: (t["_g"], t["er_sig"]))
    for t in taken:
        t.pop("_g", None)
    return taken


# ------------------------------------------------------------------------------------------------ series and risk
def daily_vector(trades: list, sessions: list, key: str = "net", lots_of=None) -> np.ndarray:
    """Net per session (0 on a session with no trade), in the order of `sessions`. lots_of(t) True = use net2."""
    ix = {d: i for i, d in enumerate(sessions)}
    v = np.zeros(len(sessions))
    for t in trades:
        i = ix.get(t["day"])
        if i is None:
            continue
        v[i] += t["net2"] if (lots_of is not None and lots_of(t)) else t[key]
    return v


def max_dd(v: np.ndarray) -> float:
    eq = np.concatenate([[0.0], np.cumsum(v)])
    return float((np.maximum.accumulate(eq) - eq).max())


def ratio(net: float, risk: float) -> float:
    """net / risk (risk >= 0); no risk at all: +inf when net > 0, else 0."""
    if risk > 0:
        return net / risk
    return math.inf if net > 0 else 0.0


def risk(v: np.ndarray, sessions: list | None = None) -> dict:
    """Net, max drawdown, worst day, net / max DD, net / |worst day|, and the account rule: worst calendar month,
    months below -13,000, drawdown past 40,000."""
    net = float(v.sum())
    mdd = max_dd(v)
    worst = float(v.min()) if len(v) else 0.0
    out = dict(net=round(net), max_dd=round(mdd), worst_day=round(worst), r_dd=ratio(net, mdd),
               r_wd=ratio(net, abs(min(worst, 0.0))))
    if sessions is not None:
        months = defaultdict(float)
        for d, x in zip(sessions, v):
            months[(d.year, d.month)] += x
        mv = list(months.values())
        out.update(months=len(mv), worst_month=round(min(mv)) if mv else 0,
                   months_below=int(sum(1 for x in mv if x <= K.ACCOUNT_MONTH)),
                   dd_over=bool(mdd >= K.ACCOUNT_DD), dd_breaches=dd_breaches(v))
    return out


def dd_breaches(v: np.ndarray, limit: float = K.ACCOUNT_DD) -> int:
    """How many separate times the drawdown reached `limit` (a new breach needs a new equity high first)."""
    eq = np.concatenate([[0.0], np.cumsum(v)])
    peak, n, inside = 0.0, 0, False
    for x in eq:
        if x > peak:
            peak, inside = x, False
        if not inside and peak - x >= limit:
            n, inside = n + 1, True
    return n


def matrix_ratios(M: np.ndarray) -> tuple:
    """Rows = twins, columns = sessions: (net / max DD, net / |worst day|) per row, the same conventions as ratio()."""
    net = M.sum(axis=1)
    eq = np.concatenate([np.zeros((M.shape[0], 1)), np.cumsum(M, axis=1)], axis=1)
    mdd = (np.maximum.accumulate(eq, axis=1) - eq).max(axis=1)
    wd = np.abs(np.minimum(M.min(axis=1), 0.0))

    def r(n, k):
        out = np.where(k > 0, n / np.where(k > 0, k, 1.0), np.where(n > 0, np.inf, 0.0))
        return out
    return r(net, mdd), r(net, wd)


# ------------------------------------------------------------------------------------------------ twins
def perm_matrix(n_twins: int, n: int, rng) -> np.ndarray:
    """(n_twins, n): each row a random permutation of range(n)."""
    return rng.permuted(np.tile(np.arange(n), (n_twins, 1)), axis=1)


def twin_day_size(base: np.ndarray, delta: np.ndarray, flags: np.ndarray, perm: np.ndarray) -> np.ndarray:
    """Day-level size twins: session k takes the flag of session perm[t, k]; daily = base + flag * delta."""
    return base[None, :] + flags[perm].astype(float) * delta[None, :]


def twin_index_size(base: np.ndarray, deltas: dict, flags: dict, perms: dict) -> np.ndarray:
    """Index-level size twins (G2). deltas[(und, slot)][k]: the extra net of that index's trades on session k using that
    reading (slot 0 = 09:20, 1 = 10:00); flags[und]: (n, 2) bool; perms[und]: (n_twins, n)."""
    out = np.tile(base, (next(iter(perms.values())).shape[0], 1)) if perms else base[None, :].copy()
    for (u, s), dl in deltas.items():
        f = flags[u][:, s][perms[u]].astype(float)
        out += f * dl[None, :]
    return out


def twin_p(actual: float, twins: np.ndarray) -> float:
    return float((1 + int((np.asarray(twins) >= actual).sum())) / (len(twins) + 1))


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts extra tests taken as p = 1 (the earlier families)."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(m_total or n, n)
    if n == 0:
        return p
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


# ------------------------------------------------------------------------------------------------ gates
def period_checks(v: dict, base: dict, always: dict, twin_med: dict) -> list:
    """PREREG gates 1-3 for one period: the failures (empty = passes). Each dict has net, r_dd, r_wd."""
    f = []
    if not v["net"] > 0:
        f.append("net not > 0")
    for k, w in (("r_dd", "net / max DD"), ("r_wd", "net / worst day")):
        if not v[k] > base[k]:
            f.append(f"{w} not above today's")
        if not v[k] > always[k]:
            f.append(f"{w} not above 'always'")
        if not v[k] > twin_med[k]:
            f.append(f"{w} not above the twins' median")
    return f


def gates(per: dict, q_all: float, changed_final: int) -> dict:
    """per[period] = (variant, base, always, twin median) risk dicts for design / wf / final."""
    fails = []
    for p in K.PERIODS:
        for x in period_checks(*per[p]):
            fails.append(f"{p}: {x}")
    if q_all > K.GATE_Q:
        fails.append(f"twin BH q {q_all:.2f} > 0.10")
    if changed_final < K.MIN_CHANGED:
        fails.append(f"only {changed_final} final trades changed (< {K.MIN_CHANGED})")
    return dict(passed=not fails, fails=fails, q_all=round(float(q_all), 4))
