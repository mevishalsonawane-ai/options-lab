"""R150 levels (PREREG section 1): pivots on 15-minute index bars, grouped into support / resistance levels.

Pure functions. A pivot exists for a decision only once the K bars after it have closed (`confirm` = its bar + K).
"""
from __future__ import annotations

import numpy as np

K_SIDE = 4              # bars each side of a pivot
TOL = 0.0015            # a price joins a group within 0.15% of the group's running mean
WINDOW = 63             # sessions (about 3 months)
BAR_MIN = 15
N = 375


def bars15(day_arrays: list, minutes: int = BAR_MIN) -> dict:
    """Continuous `minutes`-minute bars (15 by default; Amendment B uses 5) from a list of (4, 375) O H L C day arrays (oldest first). Returns arrays:
    o h l c, day (index into the list), end (the bar's last minute row within its day). A bar with no minute is dropped."""
    o, h, l_, c, day, end = [], [], [], [], [], []
    for di, a in enumerate(day_arrays):
        for s in range(0, N, minutes):
            seg = slice(s, min(s + minutes, N))
            ok = np.isfinite(a[3, seg])
            if not ok.any():
                continue
            rows = np.flatnonzero(ok) + s
            o.append(a[0, rows[0]])
            h.append(np.nanmax(a[1, seg]))
            l_.append(np.nanmin(a[2, seg]))
            c.append(a[3, rows[-1]])
            day.append(di)
            end.append(min(s + minutes, N) - 1)
    return {k: np.asarray(v, float if k not in ("day", "end") else int)
            for k, v in (("o", o), ("h", h), ("l", l_), ("c", c), ("day", day), ("end", end))}


def pivots(h: np.ndarray, l_: np.ndarray, k: int = K_SIDE) -> list:
    """[(bar, price, +1 high / -1 low, confirm_bar)]: h[j] the max of h[j-k .. j+k] (ties allowed); mirror for lows.
    Only bars with k bars on both sides can be pivots."""
    out = []
    n = len(h)
    for j in range(k, n - k):
        w = slice(j - k, j + k + 1)
        if np.isfinite(h[j]) and h[j] >= np.nanmax(h[w]):
            out.append((j, float(h[j]), 1, j + k))
        if np.isfinite(l_[j]) and l_[j] <= np.nanmin(l_[w]):
            out.append((j, float(l_[j]), -1, j + k))
    return out


def group(prices, tol: float = TOL) -> list:
    """[(level, touches)] from pivot prices: sorted, each joins the current group when within tol of its running mean."""
    out = []
    cur = []
    for p in sorted(prices):
        if cur and abs(p / np.mean(cur) - 1) <= tol:
            cur.append(p)
        else:
            if cur:
                out.append((float(np.mean(cur)), len(cur)))
            cur = [p]
    if cur:
        out.append((float(np.mean(cur)), len(cur)))
    return out


def known_pivots(piv: list, last_closed_bar: int, first_bar: int) -> list:
    """Pivots confirmed by `last_closed_bar` whose own bar is at or after `first_bar` (the window's start)."""
    return [p for p in piv if p[3] <= last_closed_bar and p[0] >= first_bar]


def levels_at(b: dict, piv: list, day_i: int, minute: int, window: int = WINDOW, tol: float = TOL) -> list:
    """The levels known at `minute` (a row, 0 = 09:15) of day `day_i`: pivots confirmed by the last bar closed by then
    (a bar is closed when its end row < minute on that day, or it is on an earlier day), in the last `window` sessions."""
    closed = np.flatnonzero((b["day"] < day_i) | ((b["day"] == day_i) & (b["end"] < minute)))
    if not len(closed):
        return []
    last = int(closed[-1])
    starts = np.flatnonzero(b["day"] >= day_i - window + 1)
    first = int(starts[0]) if len(starts) else 0
    return group([p[1] for p in known_pivots(piv, last, first)], tol)


def wall_ahead(levels: list, price: float, side: str, dist: float = 0.0025, min_touches: int = 3) -> bool:
    """A level with >= min_touches within dist ahead of the trade (above a call, below a put)."""
    for lv, n in levels:
        if n < min_touches:
            continue
        if side == "C" and price < lv <= price * (1 + dist):
            return True
        if side == "P" and price * (1 - dist) <= lv < price:
            return True
    return False


def crossed(levels: list, prev: float, price: float, side: str, min_touches: int = 3) -> bool:
    """The break crossed a >= min_touches level: prev at or below and price above (call), mirror for a put."""
    for lv, n in levels:
        if n < min_touches:
            continue
        if side == "C" and prev <= lv < price:
            return True
        if side == "P" and price < lv <= prev:
            return True
    return False


def crossed_level(levels: list, prev: float, price: float, side: str, min_touches: int = 3):
    """The level the break crossed (the nearest to price: the highest crossed for a call, the lowest for a put); None."""
    xs = [lv for lv, n in levels if n >= min_touches and ((side == "C" and prev <= lv < price) or (side == "P" and price < lv <= prev))]
    if not xs:
        return None
    return max(xs) if side == "C" else min(xs)


def fake_exit_row(closes: np.ndarray, er: int, level: float, side: str, n: int = N) -> int:
    """FX (Amendment A2): the first 5-minute bar (09:15-anchored) ending at or after the fill row er whose close is back
    through `level` (below it for a call, above for a put); sold at the next minute: that bar's last row + 1. -1 if none."""
    for end in range(4, n, 5):
        if end < er:
            continue
        seg = closes[end - 4: end + 1]
        ok = np.flatnonzero(np.isfinite(seg))
        if not ok.size:
            continue
        c = seg[ok[-1]]
        if (side == "C" and c < level) or (side == "P" and c > level):
            return end + 1 if end + 1 < n else -1
    return -1
