"""R114 pure parts (PREREG.md): the morning rule on a unit's candidates, the book's statistics, the two twins, BH and the
gates. Plain lists / arrays only, so test_r114.py covers everything without the lake.
"""
from __future__ import annotations

from collections import defaultdict

import numpy as np

import r114cfg as K
import r108book as B


# ------------------------------------------------------------------------------------------------ the rule
def morning(cands: list, cut: int | None, close: int | None) -> tuple:
    """(kept indices, kept copies): candidates whose entry row (the signal bar's close) is at or before `cut`, each copy
    carrying a forced exit 'fx' at row `close` (sold at that minute's open). None / None = today's rules (nothing cut,
    no forced exit). Reads only each candidate's own entry row: nothing after the entry decides whether it is kept."""
    keep = [j for j, c in enumerate(cands) if cut is None or int(c["er"]) <= cut]
    out = []
    for j in keep:
        c2 = dict(cands[j])
        if close is not None:
            c2["fx"] = int(close)
        out.append(c2)
    return keep, out


# ------------------------------------------------------------------------------------------------ statistics
def summary(trades: list, sessions: list) -> dict:
    """R108's book summary (net, trades, win, worst day, max drawdown, months / days at or below -Rs 13,000, separate
    Rs 40,000 breaches) plus average win / loss and % green days (PREREG section 5)."""
    s = B.summary(trades, sessions, K.MONTH_LIMIT, K.DD_LIMIT)
    w = [t["net"] for t in trades if t["net"] > 0]
    lo = [t["net"] for t in trades if t["net"] <= 0]
    v = B.daily_vector(trades, sessions)
    traded = v[v != 0]
    s.update(avg_win=round(float(np.mean(w))) if w else None, avg_loss=round(float(np.mean(lo))) if lo else None,
             green_days=round(float((traded > 0).mean()), 3) if len(traded) else None)
    return s


def trade_key(t: dict) -> tuple:
    return (t["arm"], t["und"], t["day"], t["er_sig"], t["side"], t.get("book"))


def extra(a: list, b: list, arms=("LIQ", "R52")) -> dict:
    """Trades of `arms` in book a and not in book b (by arm, index, day, signal row, side, book): Liquidity's and R52's
    trades taken (or lost) because the Donchian arms freed (or took) places under the cap."""
    kb = {trade_key(t) for t in b}
    xs = [t for t in a if t["arm"] in arms and trade_key(t) not in kb]
    return dict(n=len(xs), net=round(sum(t["net"] for t in xs)))


# ------------------------------------------------------------------------------------------------ twins
def by_day(trades: list, arms=None) -> dict:
    out = defaultdict(list)
    for t in trades:
        if arms is None or t["arm"] in arms:
            out[t["day"]].append(float(t["net"]))
    return dict(out)


def refusal_twin_totals(book0: list, x10: list, sessions: list, n: int, rng) -> tuple:
    """PREREG gate 3, refusal twin: each session, from BOOK0's real Donchian trades remove at random as many as X10 took
    fewer that day (k_d = max(0, BOOK0's - X10's)); BOOK0's Liquidity and R52 trades are kept. Returns (twins' total
    nets (n,), the removals)."""
    b_d = by_day(book0, K.DONCHIAN)
    x_d = by_day(x10, K.DONCHIAN)
    k = {d: max(0, len(b_d.get(d, [])) - len(x_d.get(d, []))) for d in sessions}
    M = B.twin_matrix(b_d, k, sessions, n, rng)
    ss = set(sessions)
    rest = sum(t["net"] for t in book0 if t["arm"] not in K.DONCHIAN and t["day"] in ss)
    return M.sum(axis=1) + rest, int(sum(k.values()))


def exit_twin_totals(fixed: float, row_nets: list, n: int, rng) -> np.ndarray:
    """PREREG gate 3, exit twin. fixed: the summed net of every X10 trade the 10:00 close did not sell earlier (all
    Liquidity and R52 trades included); row_nets: for each Donchian trade it did sell earlier, the nets of selling it at
    each minute strictly after its fill and before today's exit. Each twin draws one minute per trade uniformly."""
    tot = np.full(n, float(fixed))
    for nets in row_nets:
        nets = np.asarray(nets, float)
        if len(nets):
            tot += nets[rng.integers(0, len(nets), size=n)]
    return tot


def twin_p(actual: float, twins: np.ndarray) -> float:
    return float((1 + int((np.asarray(twins) >= actual - 1e-9).sum())) / (len(twins) + 1))


# ------------------------------------------------------------------------------------------------ gates
def decide(per: dict, p: float, changed_final: int) -> dict:
    """per[period] = (X10 summary, BOOK0 summary) for design / wf / final. Gate 1-2 (R108's gate_period: net above,
    months / breaches / days at or below the limits not more), gate 3 (BH over m = 5,841), gate 4 (>= 30 changed)."""
    q = float(B.bh([p], K.M_TOTAL)[0])
    g = B.gates(per, q, changed_final, ("design", "wf", "final"), K.GATE_Q, K.MIN_CHANGED)
    g.update(p=round(float(p), 6), q_own=round(float(min(1.0, p)), 6), m=K.M_TOTAL)
    return g
