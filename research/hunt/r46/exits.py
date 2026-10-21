"""R46 exits: every trade has a stop-loss AND a profit lock (CLAUDE.md crypto rule) and a 60-minute time exit.

Vectorised over trades. Rows are trades; columns are the 1-minute bars after the fill (column 0 = the first minute
after the fill minute). Levels are multiples of the entry reference price. Inside a minute the order of high and low is
unknown, so the check is the cautious one (as RC2):
  1. column >= deadline: exit at that minute's open ("time");
  2. the minute opens at or below the active level (stop or lock): exit at the open (a gap: the worse of the two);
  3. the minute's low touches the level: exit at the level;
  4. only then does the high raise the peak; a new lock applies from the next minute.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

STOP, LOCK, TIME, DATA_END = 0, 1, 2, 3


@dataclass(frozen=True)
class Exit:
    stop: float                 # fraction lost at the stop (0.5 = exit at 0.5x)
    lock: str                   # "step" or "trail"
    step: float = 0.25          # step: rung size
    first: float = 1.25         # step: the first rung's peak (locks 1.0x)
    start: float = 1.30         # trail: active from this peak multiple
    keep: float = 0.50          # trail: share of the peak gain kept

    def __post_init__(self):
        if not (0 < self.stop < 1):
            raise ValueError("every crypto trade needs a stop-loss (0 < stop < 1)")
        if self.lock == "step":
            if not (self.step > 0 and self.first > 1):
                raise ValueError("a step lock needs step > 0 and a first rung above 1x")
        elif self.lock == "trail":
            if not (self.start > 1 and 0 < self.keep < 1):
                raise ValueError("a trailing lock needs start > 1 and 0 < keep < 1")
        else:
            raise ValueError("every crypto trade needs a profit lock (step or trail)")

    def lock_mult(self, peak: np.ndarray) -> np.ndarray:
        """Locked multiple for a peak multiple (0 where no lock is active yet)."""
        peak = np.asarray(peak, float)
        if self.lock == "step":
            k = np.floor((peak - self.first) / self.step + 1e-9)        # rungs passed beyond the first
            return np.where(peak >= self.first - 1e-12, 1.0 + np.maximum(k, 0) * self.step, 0.0)
        return np.where(peak >= self.start - 1e-12, 1.0 + self.keep * (peak - 1.0), 0.0)


def walk(ex: Exit, ref: np.ndarray, o: np.ndarray, h: np.ndarray, l: np.ndarray, deadline: np.ndarray | int):
    """ref (n,), o/h/l (n, m) price paths after the fill (NaN = no data: the trade exits at the last open seen,
    "data_end"), deadline = column index of the time exit per trade (<= m - 1).
    Returns (exit price, reason, column, peak multiple before the exit)."""
    ref = np.asarray(ref, float)
    n, m = o.shape if o.ndim == 2 else (0, 0)
    dl = np.broadcast_to(np.asarray(deadline, int), (n,))
    stop_lvl = ref * (1 - ex.stop)
    lock_lvl = np.zeros(n)
    peak = ref.copy()
    px = np.full(n, np.nan)
    why = np.full(n, DATA_END, int)
    col = np.full(n, max(m - 1, 0), int)
    done = np.zeros(n, bool)
    last_open = ref.copy()
    for j in range(m):
        oj, hj, lj = o[:, j], h[:, j], l[:, j]
        live = ~done
        nodata = live & ~np.isfinite(oj)
        px[nodata], why[nodata], col[nodata] = last_open[nodata], DATA_END, j
        done |= nodata
        live = ~done
        t = live & (j >= dl)
        px[t], why[t], col[t] = oj[t], TIME, j
        done |= t
        live = ~done
        lvl = np.maximum(stop_lvl, lock_lvl)
        r = np.where(lock_lvl >= stop_lvl, LOCK, STOP)
        gap = live & (oj <= lvl)
        px[gap], why[gap], col[gap] = oj[gap], r[gap], j
        touch = live & ~gap & (lj <= lvl)
        px[touch], why[touch], col[touch] = lvl[touch], r[touch], j
        done |= gap | touch
        live = ~done
        last_open = np.where(live, oj, last_open)
        up = live & (hj > peak)
        peak = np.where(up, hj, peak)
        lock_lvl = np.where(live, np.maximum(lock_lvl, ex.lock_mult(peak / ref) * ref), lock_lvl)
    rest = ~done
    if m and rest.any():
        px[rest] = last_open[rest]
    return px, why, col, peak / ref


def from_cfg(d: dict) -> Exit:
    return Exit(**d)
