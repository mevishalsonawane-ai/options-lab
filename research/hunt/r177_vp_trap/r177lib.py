"""R177 pure parts (PREREG.md sections 1, 2, 4): the previous session's TPO profile (POC, 70% value area), PDH / PDL,
the trap exit row (X1 / X2), the failed-break skip (K1), the earlier-of forced rows and the selection twin.

Index 1-minute bars only (signal on the underlying); arrays are (4, 375) open / high / low / close, NaN = no bar.
Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import math

import numpy as np

MIN_MINUTES = 300
VA_SHARE = 0.70
TRAP_ROWS = 10
SKIP_ROWS = 30
BIN_DIV = 5.0                     # bin = strike step / 5


def tpo_levels(idx: np.ndarray, step: float) -> dict | None:
    """{poc, vah, val, pdh, pdl} from one session's 1-minute index bars; None with fewer than 300 valid minutes."""
    h, lo_ = np.asarray(idx[1], float), np.asarray(idx[2], float)
    ok = np.isfinite(h) & np.isfinite(lo_)
    if int(ok.sum()) < MIN_MINUTES:
        return None
    b = step / BIN_DIV
    h, lo_ = h[ok], lo_[ok]
    base = math.floor(lo_.min() / b)
    top = math.floor(h.max() / b)
    n = top - base + 1
    counts = np.zeros(n)
    for a, z in zip(lo_, h):
        i0, i1 = math.floor(a / b) - base, math.floor(z / b) - base
        counts[i0:i1 + 1] += 1
    mid = (h.max() + lo_.min()) / 2.0
    best = np.flatnonzero(counts == counts.max())
    centers = (base + best + 0.5) * b
    poc = int(best[np.argmin(np.abs(centers - mid))])
    a = z = poc
    acc, total = counts[poc], counts.sum()
    while acc < VA_SHARE * total - 1e-9:
        up = counts[z + 1] if z + 1 < n else -1.0
        dn = counts[a - 1] if a - 1 >= 0 else -1.0
        if up >= dn:
            z += 1
            acc += up
        else:
            a -= 1
            acc += dn
    return dict(poc=float((base + poc + 0.5) * b), vah=float((base + z + 1) * b), val=float((base + a) * b),
                pdh=float(h.max()), pdl=float(lo_.min()))


def trap_row(close: np.ndarray, er: int, start: int, side: str, level: float, n: int = TRAP_ROWS) -> int:
    """X1 / X2: the forced exit row (the open after the first close back inside), or -1.
    Acts only when the signal close (row er - 1) is beyond the level: above it for a call, below for a put. Searches
    closes in rows max(er, start) .. er + n - 1 (start = the fill row, so the exit is always after the fill)."""
    if level is None or not np.isfinite(level) or er < 1:
        return -1
    s = close[er - 1]
    call = side == "C"
    if not np.isfinite(s) or (call and not s > level) or (not call and not s < level):
        return -1
    for m in range(max(er, start), min(er + n, len(close))):
        x = close[m]
        if np.isfinite(x) and ((call and x <= level) or (not call and x >= level)):
            return m + 1
    return -1


def failed_break(close: np.ndarray, s: int, side: str, level: float, w: int = SKIP_ROWS) -> bool:
    """K1: True when, in rows s - w .. s - 1, a close went beyond the level (above VAH for a call, below VAL for a put)
    and a LATER close (still before s) came back to it or inside."""
    if level is None or not np.isfinite(level):
        return False
    call = side == "C"
    beyond = False
    for r in range(max(0, s - w), max(0, s)):
        x = close[r]
        if not np.isfinite(x):
            continue
        if (call and x > level) or (not call and x < level):
            beyond = True
        elif beyond:
            return True
    return False


def earlier(fx_a: int, fx_b: int) -> int:
    """The earlier of two forced rows (-1 = none)."""
    xs = [x for x in (fx_a, fx_b) if x is not None and x >= 0]
    return min(xs) if xs else -1


def first_fill_row(o: np.ndarray, er: int, sq: int) -> int:
    """The simulator's fill row: the first finite positive open in er .. er + 2 (before sq); -1 none."""
    for j in range(er, min(er + 3, sq)):
        if np.isfinite(o[j]) and o[j] > 0:
            return j
    return -1


def selection_twin(nets: list, k: int, n: int, rng) -> np.ndarray:
    """Totals of `n` random drops of `k` trades from `nets` (A's trades)."""
    x = np.asarray(nets, float)
    tot = x.sum()
    if k <= 0 or len(x) == 0:
        return np.full(n, tot)
    k = min(k, len(x))
    return np.array([tot - x[rng.choice(len(x), k, replace=False)].sum() for _ in range(n)])
