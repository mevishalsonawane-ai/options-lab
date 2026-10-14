"""RC2 exits: every trade has a stop-loss AND a profit lock (Boss, 13 Oct 2026: no crypto trade without both).

An Exit cannot be built without a stop and a lock; the simulator refuses a trade without them. Levels are multiples of
the entry price (the option's mark at entry, before costs; costs are applied to the fills by the caller).

Bar order inside a 5-minute bar is unknown, so the check is the cautious one:
  1. a bar that starts at or after the deadline exits at its open ("time");
  2. the bar opens at or below the active level (stop or lock): exit at the open (a gap);
  3. the bar's low touches the active level: exit at the level;
  4. only then does the bar's high raise the peak, and a new lock applies from the next bar.
"""
from dataclasses import dataclass, field

import numpy as np


@dataclass(frozen=True)
class Exit:
    name: str
    stop: float                      # fraction of the entry lost at the stop (0.4 = exit at 0.6x)
    lock: str                        # "step" or "trail"
    rungs: tuple = ()                # step: ((peak multiple, locked multiple), ...)
    trail_start: float = 0.0         # trail: active once the peak reaches this multiple
    trail_keep: float = 0.0          # trail: lock = trail_keep x peak
    trail_floor: float = 1.0         # trail: never below this multiple once active

    def __post_init__(self):
        if not (0 < self.stop < 1):
            raise ValueError("every crypto trade needs a stop-loss (0 < stop < 1)")
        if self.lock == "step":
            if not self.rungs or any(not (1 <= lk < tr) for tr, lk in self.rungs):
                raise ValueError("a step lock needs rungs (trigger > lock >= 1x)")
        elif self.lock == "trail":
            if not (self.trail_start > 1 and 0 < self.trail_keep < 1 and self.trail_floor >= 1):
                raise ValueError("a trailing lock needs start > 1, 0 < keep < 1, floor >= 1")
        else:
            raise ValueError("every crypto trade needs a profit lock (step or trail)")

    def lock_level(self, entry, peak):
        m = peak / entry
        if self.lock == "step":
            best = 0.0
            for tr, lk in self.rungs:
                if m >= tr - 1e-12:
                    best = max(best, lk)
            return best * entry
        if m >= self.trail_start - 1e-12:
            return max(self.trail_floor * entry, self.trail_keep * peak)
        return 0.0


def run(ex: Exit, entry, o, h, l, c, deadline_idx):
    """Walk the bars after the entry bar. o/h/l/c: the option's marks from the first bar after entry; deadline_idx: the
    index of the first bar that starts at or after the forced exit (the rule's time limit or ten minutes before
    settlement, whichever is first) (len(o) when the data ends first).
    Returns (exit level, reason, bar index, peak multiple seen before the exit)."""
    if entry <= 0:
        raise ValueError("entry price must be positive")
    stop_lvl = entry * (1 - ex.stop)
    peak, lock_lvl = entry, 0.0
    n = len(o)
    last = min(deadline_idx, n)
    for i in range(n):
        if i >= last:
            return float(o[i]), "time", i, peak / entry
        lvl = max(stop_lvl, lock_lvl)
        why = "lock" if lock_lvl >= stop_lvl else "stop"
        if o[i] <= lvl:
            return float(o[i]), why, i, peak / entry
        if l[i] <= lvl:
            return float(lvl), why, i, peak / entry
        if h[i] > peak:
            peak = float(h[i])
            lock_lvl = ex.lock_level(entry, peak)
    return float(c[n - 1]) if n else float(entry), "data_end", max(n - 1, 0), peak / entry


# Pre-registered exits (PREREG.md). Directional rules R1-R5, R7, R8: stop -40% or -25%, step lock or trailing lock.
STEP_A = ((1.3, 1.0), (1.6, 1.3), (2.0, 1.6), (3.0, 2.4), (5.0, 4.0), (10.0, 8.0))
STEP_H = ((2.0, 1.3), (3.0, 2.0), (5.0, 3.5), (10.0, 7.0), (20.0, 14.0))
DIRECTIONAL = (
    Exit("s40_step", 0.40, "step", rungs=STEP_A),
    Exit("s40_trail", 0.40, "trail", trail_start=1.3, trail_keep=0.75, trail_floor=1.0),
    Exit("s25_step", 0.25, "step", rungs=STEP_A),
    Exit("s25_trail", 0.25, "trail", trail_start=1.3, trail_keep=0.75, trail_floor=1.0),
)
# The hero (cheap OTM, today's expiry): wider stops, rungs that start at 2x.
HERO = (
    Exit("s50_step", 0.50, "step", rungs=STEP_H),
    Exit("s50_trail", 0.50, "trail", trail_start=2.0, trail_keep=0.6, trail_floor=1.3),
    Exit("s70_step", 0.70, "step", rungs=STEP_H),
    Exit("s70_trail", 0.70, "trail", trail_start=2.0, trail_keep=0.6, trail_floor=1.3),
)
