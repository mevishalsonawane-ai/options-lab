"""R161 pure helpers (PREREG.md sections 2, 4, 5): trade statistics, C1's walk-forward choice, twins' p, gates and BH.

Plain lists and dicts only (no stores); test_r161.py covers every function.
"""
from __future__ import annotations

import math
from collections import defaultdict

import numpy as np

WIN_GAIN = 0.05          # gate 1: +5.0 points
MIN_FINAL = 40           # gate 6
GATE_Q = 0.10            # gate 5
N_PRIOR = 2164           # R160's tally (R154's 1,682 + 472 entries from R155-R159 + R160's 10)


# ================================================================================================ statistics
def stats(trades: list) -> dict:
    """Trades, win, break-even win (avg loss / (avg win + avg loss)), net, avg win, avg loss, worst day, max drawdown
    (daily), net without the 5 best trades, worst month."""
    n = len(trades)
    if not n:
        return dict(trades=0, win=None, be_win=None, net=0, avg_win=None, avg_loss=None, worst_day=0, max_dd=0,
                    net_ex5=0, worst_month=0)
    nets = np.array([float(t["net"]) for t in trades])
    w, lo = nets[nets > 0], nets[nets <= 0]
    aw = float(w.mean()) if len(w) else 0.0
    al = float(-lo.mean()) if len(lo) else 0.0
    dd = defaultdict(float)
    mon = defaultdict(float)
    for t in trades:
        dd[t["day"]] += float(t["net"])
        mon[(t["day"].year, t["day"].month)] += float(t["net"])
    seq = np.array([dd[d] for d in sorted(dd)])
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    ex5 = float(np.sort(nets)[:-5].sum()) if n > 5 else 0.0
    return dict(trades=n, win=round(float((nets > 0).mean()), 4),
                be_win=round(al / (aw + al), 4) if (aw + al) > 0 else None, net=round(float(nets.sum())),
                avg_win=round(aw) if len(w) else None, avg_loss=round(-al) if len(lo) else None,
                worst_day=round(float(seq.min())), max_dd=round(float((np.maximum.accumulate(eq) - eq).max())),
                net_ex5=round(ex5), worst_month=round(float(min(mon.values()))))


def in_window(trades: list, lo, hi) -> list:
    return [t for t in trades if lo <= t["day"] <= hi]


def win_net(trades: list) -> tuple:
    if not trades:
        return 0.0, 0.0
    nets = np.array([float(t["net"]) for t in trades])
    return float((nets > 0).mean()), float(nets.sum())


# ================================================================================================ C1's choice
def choose_combo(single: dict, base: tuple, pool) -> tuple:
    """PREREG section 2: single = {id: (win, net)} on the training window, base = B0's (win, net). A single qualifies
    when its win > B0's and its net >= B0's; the two with the largest win gain are ANDed (ties: the order of `pool`).
    Returns a tuple of ids: two, one, or () (= no change)."""
    q = [(single[s][0] - base[0], -k, s) for k, s in enumerate(pool)
         if s in single and single[s][0] > base[0] and single[s][1] >= base[1]]
    q.sort(reverse=True)
    return tuple(s for _, _, s in q[:2])


# ================================================================================================ twins
def twin_p(actual: float, twins) -> dict:
    """R115's p: (1 + twins >= actual) / (n + 1); when no twin reaches it, the normal-tail p from the twins' mean / sd."""
    t = np.asarray(twins, float)
    n = len(t)
    hits = int((t >= actual - 1e-9).sum())
    p_mc = (1 + hits) / (n + 1)
    sd = float(t.std(ddof=1)) if n > 1 else 0.0
    if sd > 0:
        p_norm = 0.5 * math.erfc(((actual - float(t.mean())) / sd) / math.sqrt(2.0))
    else:
        p_norm = 0.0 if actual > (float(t.mean()) if n else 0.0) else 1.0
    p = p_mc if hits > 0 else min(p_mc, p_norm)
    return dict(p=float(p), p_mc=float(p_mc), p_norm=float(p_norm), twin_mean=float(t.mean()) if n else None, hits=hits)


def random_drop(units: list, k: int, rng) -> list:
    """The units with k candidates removed at random (uniform over all candidates); empty units dropped. Prices travel
    with their candidates: units are (key, cands, res, how) and the caller's price lists are dropped the same way."""
    flat = [(g, j) for g, u in enumerate(units) for j in range(len(u[1]))]
    k = max(0, min(k, len(flat)))
    gone = {flat[x] for x in rng.choice(len(flat), size=k, replace=False)} if k else set()
    return [[j for j in range(len(u[1])) if (g, j) not in gone] for g, u in enumerate(units)]


# ================================================================================================ BH and gates
def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts the earlier tests as p = 1 (R106 / R154's convention)."""
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total or n, n)
    o = np.argsort(p)
    q = np.empty(n)
    run = 1.0
    for r in range(n - 1, -1, -1):
        run = min(run, p[o[r]] * m / (r + 1))
        q[o[r]] = run
    return np.minimum(q, 1.0)


def gates(rule: dict, ref: dict, p: float, q: float, net_periods=("design", "wf", "final")) -> dict:
    """PREREG section 5. rule / ref = {period: stats} with 'wf' and 'final' (and 'design' for gate 3)."""
    fails = []
    for per in ("wf", "final"):
        r, b = rule[per], ref[per]
        if not r["trades"] or r["win"] is None or b["win"] is None or r["win"] < b["win"] + WIN_GAIN - 1e-9:
            fails.append(f"{per}: win not up 5 points")
        if r["net"] < b["net"]:
            fails.append(f"{per}: net lower")
    for per in net_periods:
        if not rule[per]["net"] > 0:
            fails.append(f"{per}: net not > 0")
    if not q <= GATE_Q:
        fails.append(f"BH q {q:.3g} > {GATE_Q}")
    if rule["final"]["trades"] < MIN_FINAL:
        fails.append(f"< {MIN_FINAL} final trades")
    return dict(passed=not fails, fails=fails, p=p, q=q)
