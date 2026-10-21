"""R97 step 1: measure gold's intraday moves on the MCX future (descriptive; pure functions on one day's minute closes).

Two different things are measured, and kept apart on purpose:

- HINDSIGHT swings (`zigzag`): the day's path cut into one-way legs; a leg ends when price comes back X points from
  its extreme. "The largest one-way swing with no retrace beyond X" is the largest such leg. The pivots are known only
  later, so these are the moves Boss SEES on the chart, not moves anyone could enter at the start.
- REAL-TIME triggers (`triggers`): the minute price has moved T points off its running extreme (a causal zig-zag with
  threshold T). From that minute, the label follows price until it comes back X from its best level (the exit of a
  trail of X points) or makes a new extreme beyond the pivot (the move failed) or the day ends. The label reads the
  future (it is the outcome); the trigger itself reads only the past.

All prices are minute closes (close-only series), so a "high" is the highest minute close.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass
class Leg:
    i0: int          # pivot where the leg starts (index into the day's arrays)
    i1: int          # pivot (extreme) where it ends
    ic: int          # minute the end was confirmed (price came back X); -1 when the day ended first
    d: int           # +1 up, -1 down
    size: float      # |F[i1] - F[i0]| points


def zigzag_pivots(F: np.ndarray, X: float):
    """Hindsight pivots of one day: list of (index, confirmed_at) where confirmed_at is the first minute price had come
    back X from that extreme (-1 for the final, unconfirmed extreme). The first pivot is the extreme on the far side of
    the first confirmed one (so the opening move counts as a leg)."""
    n = len(F)
    if n < 2:
        return []
    piv = []
    hi_i = lo_i = 0
    d = 0
    for i in range(1, n):
        x = F[i]
        if d == 0:
            if x > F[hi_i]:
                hi_i = i
            if x < F[lo_i]:
                lo_i = i
            if x - F[lo_i] >= X:                  # first confirmed pivot: a low
                if lo_i > 0:
                    piv.append((int(np.argmax(F[:lo_i + 1])), -1))
                piv.append((lo_i, i))
                d, hi_i = 1, lo_i + int(np.argmax(F[lo_i:i + 1]))
            elif F[hi_i] - x >= X:                # first confirmed pivot: a high
                if hi_i > 0:
                    piv.append((int(np.argmin(F[:hi_i + 1])), -1))
                piv.append((hi_i, i))
                d, lo_i = -1, hi_i + int(np.argmin(F[hi_i:i + 1]))
        elif d == 1:
            if x > F[hi_i]:
                hi_i = i
            elif F[hi_i] - x >= X:
                piv.append((hi_i, i))
                d, lo_i = -1, i
        else:
            if x < F[lo_i]:
                lo_i = i
            elif x - F[lo_i] >= X:
                piv.append((lo_i, i))
                d, hi_i = 1, i
    if d == 1:
        piv.append((hi_i, -1))
    elif d == -1:
        piv.append((lo_i, -1))
    else:                                         # the whole day moved less than X: one leg, low to high
        piv = sorted([(lo_i, -1), (hi_i, -1)])
    # the first pivot's own confirmation is meaningless (-1); drop exact duplicates
    out = []
    for p in piv:
        if not out or out[-1][0] != p[0]:
            out.append(p)
    return out


def zigzag(F: np.ndarray, X: float) -> list[Leg]:
    """One-way legs between consecutive hindsight pivots (a leg of 0 points is dropped)."""
    F = np.asarray(F, float)
    p = zigzag_pivots(F, X)
    legs = []
    for (a, _), (b, cb) in zip(p[:-1], p[1:]):
        if b <= a:
            continue
        s = F[b] - F[a]
        if s == 0:
            continue
        legs.append(Leg(a, b, cb, 1 if s > 0 else -1, float(abs(s))))
    return legs


@dataclass
class Trigger:
    i: int           # trigger minute (price is T beyond the running extreme)
    d: int           # +1 long, -1 short
    pivot: float     # the running extreme the move started from (known at i)
    entry: float     # F[i]
    best: float      # best close after i before the exit
    i_best: int
    i_exit: int      # exit minute: back X from the best, a new extreme beyond the pivot, or the last minute
    exit: float
    why: str         # "trail", "failed", "day_end"

    @property
    def swing(self) -> float:        # pivot -> best, in the trade's direction
        return self.d * (self.best - self.pivot)

    @property
    def capture(self) -> float:      # entry -> exit on the future
        return self.d * (self.exit - self.entry)


def trigger_points(F: np.ndarray, T: float):
    """Causal: [(i, d, pivot)] every minute the price has come T off its running extreme in the other direction (a
    zig-zag with threshold T, read in real time). Reads F[: i + 1] only."""
    F = np.asarray(F, float)
    out = []
    hi = lo = F[0] if len(F) else np.nan
    d = 0
    for i in range(1, len(F)):
        x = F[i]
        if d >= 0:
            hi = max(hi, x)
        if d <= 0:
            lo = min(lo, x)
        if d <= 0 and x - lo >= T:
            out.append((i, 1, lo))
            d, hi = 1, x
        elif d >= 0 and hi - x >= T:
            out.append((i, -1, hi))
            d, lo = -1, x
    return out


def follow(F: np.ndarray, i: int, d: int, pivot: float, X: float) -> Trigger:
    """The outcome label of a trigger at minute i (reads the future of the day)."""
    F = np.asarray(F, float)
    best, ib = F[i], i
    for k in range(i + 1, len(F)):
        x = F[k]
        if d * (x - best) > 0:
            best, ib = x, k
        if d * (pivot - x) > 0:
            return Trigger(i, d, pivot, F[i], best, ib, k, x, "failed")
        if d * (best - x) >= X:
            return Trigger(i, d, pivot, F[i], best, ib, k, x, "trail")
    k = len(F) - 1
    return Trigger(i, d, pivot, F[i], best, ib, k, F[k], "day_end")


def triggers(F: np.ndarray, T: float, X: float) -> list[Trigger]:
    return [follow(F, i, d, p, X) for i, d, p in trigger_points(F, T)]


def day_stats(F: np.ndarray, retrace=(100, 200, 300), sizes=(200, 300, 500, 1000)) -> dict:
    """Per-day numbers: range, open-to-close, efficiency, and per retrace X the largest one-way leg and how many legs
    reach each size."""
    F = np.asarray(F, float)
    F = F[np.isfinite(F)]
    if len(F) < 2:
        return {}
    hi, lo = float(F.max()), float(F.min())
    rng = hi - lo
    out = dict(n=len(F), open=float(F[0]), close=float(F[-1]), high=hi, low=lo, range=rng,
               oc=float(F[-1] - F[0]), eff=float(abs(F[-1] - F[0]) / rng) if rng > 0 else np.nan,
               high_first=bool(int(np.argmax(F)) < int(np.argmin(F))))
    # round trip: the part of the range given back by the close (0 = closed at the far extreme of the day's move)
    out["round_trip"] = float(1 - out["eff"]) if rng > 0 else np.nan
    for X in retrace:
        L = zigzag(F, X)
        sz = np.array([g.size for g in L]) if L else np.zeros(0)
        out[f"max_leg_{X}"] = float(sz.max()) if len(sz) else 0.0
        out[f"max_leg_share_{X}"] = float(sz.max() / rng) if len(sz) and rng > 0 else np.nan
        for S in sizes:
            out[f"n_legs_{X}_{S}"] = int((sz >= S).sum())
        out[f"path_{X}"] = float(sz.sum())
    return out
