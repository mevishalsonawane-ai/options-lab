"""R125 rules (PREREG.md): pure functions over R123 V2's trade list (each trade has .und, .day, .sig_row, .net)."""
from __future__ import annotations

from collections import defaultdict

import numpy as np

import r125cfg as K


def order(trs):
    return sorted(trs, key=lambda t: (t.day, K.PRIORITY.index(t.und), t.sig_row))


def one_a_day(trs):
    """Z1: per day, keep only the first index in the liquidity order."""
    out, seen = [], set()
    for t in order(trs):
        if t.day not in seen:
            out.append(t)
            seen.add(t.day)
    return out


def breaker(trs, limit: float = K.BREAKER):
    """Z2's breaker: in date order, once the calendar month's running total is <= limit, skip the rest of that month."""
    out, run, off = [], defaultdict(float), set()
    for t in order(trs):
        m = (t.day.year, t.day.month)
        if m in off:
            continue
        out.append(t)
        run[m] += t.net
        if run[m] <= limit:
            off.add(m)
    return out


def z1_twin(final_trs, n: int, rng) -> np.ndarray:
    """Z1's twin: on each day with several trades, keep ONE at random (instead of the liquidity order)."""
    by = defaultdict(list)
    for t in final_trs:
        by[t.day].append(t.net)
    tot = np.zeros(n)
    for nets in by.values():
        a = np.asarray(nets, float)
        tot += a[rng.integers(0, len(a), n)] if len(a) > 1 else a[0]
    return tot


def drop_twin(book_trs, n_drop: int, n: int, rng) -> np.ndarray:
    """Z2's twin: drop n_drop trades at random from the book (Z1's FINAL trades)."""
    v = np.asarray([t.net for t in book_trs], float)
    if n_drop <= 0:
        return np.full(n, v.sum())
    out = np.empty(n)
    for i in range(n):
        keep = np.ones(len(v), bool)
        keep[rng.choice(len(v), size=min(n_drop, len(v)), replace=False)] = False
        out[i] = v[keep].sum()
    return out
