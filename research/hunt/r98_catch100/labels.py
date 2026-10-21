"""R98 outcome labels: from an entry minute, does the price make +TARGET before -STOP (a bracket), within H minutes,
the same session? Pure functions.

Future: entry = the future's close of the NEXT minute after the signal (the series is close-only; R53's futures fill);
checks on later minute closes; out at the first close at or beyond the target / stop, else at the close of the last
minute of the hold (or of the session). Costs in points: Zerodha MCX futures charges / units + one full spread.
Option: R96 engine prints (first print within 5 min, exits at printed closes).
"""
from __future__ import annotations

import numpy as np

from research.hunt.r53 import common as C53


def fut_cost_pts(price: float, units: float = 10.0, tick: float = 1.0) -> float:
    """Round-trip cost of one GOLDM futures lot in points of the quote (charges + 2 half-spreads)."""
    notional = price * units
    return C53.mcx_fut_charges(notional, notional) / units + 2 * C53.fut_half_spread(price, tick, "gold")


def bracket(F: np.ndarray, i: int, d: int, target: float, stop: float, hold: int):
    """Entry at F[i + 1] (direction d). Returns (outcome, pnl_pts, minutes): outcome 1 target, -1 stop, 0 time;
    None when there is no next minute. Rows are minutes of ONE session (index steps, not clock)."""
    n = len(F)
    if i + 1 >= n:
        return None
    e = F[i + 1]
    end = min(n - 1, i + 1 + hold)
    if end <= i + 1:
        return 0, 0.0, 0
    path = d * (F[i + 2:end + 1] - e)
    hit_t = np.flatnonzero(path >= target)
    hit_s = np.flatnonzero(path <= -stop)
    a = hit_t[0] if len(hit_t) else 10 ** 9
    b = hit_s[0] if len(hit_s) else 10 ** 9
    if a == b == 10 ** 9:
        return 0, float(path[-1]), int(len(path))
    if a < b:
        return 1, float(path[a]), int(a + 1)
    return -1, float(path[b]), int(b + 1)


def mfe_mae(F: np.ndarray, i: int, d: int, hold: int):
    """Best and worst move (points, direction d) from F[i + 1] over the next `hold` minutes of the session, and the
    minute the best was reached."""
    if i + 1 >= len(F):
        return np.nan, np.nan, -1
    e = F[i + 1]
    seg = d * (F[i + 2:min(len(F), i + 2 + hold)] - e)
    if not len(seg):
        return 0.0, 0.0, 0
    k = int(np.argmax(seg))
    return float(seg[k]), float(seg.min()), k + 1
