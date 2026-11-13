"""R168 signals: the app's ORB, ORB Fresh and ORB Sweep decisions on a day's index minutes (PREREG section 1).

A port of `OrbRules.entrySignal` / `SweepRules.entrySignal` as h20 / R14 ported them (research/hunt/h20/sim.py
`signals`): 5-minute bars built from the 1-minute index bars (bar k = minute rows 5k .. 5k+4, its close the close of row
5k+4), one opening range from bars 0 .. 9 (09:15 .. 10:00), the ATM strike from bar 1's close (half-up), and each arm's
decision window. A bar is decided only once it has closed; its entry fills at the next minute (row 5k+5).

Pure: no data access. The taking rule (one position at a time, the cool-down, the caps) needs each trade's exit and is
`walk` below, with the pricing passed in.
"""
from __future__ import annotations

import math

import numpy as np

import r168cfg as K


def bars5(idx: np.ndarray) -> tuple:
    """(high, low, close) of the 75 five-minute bars from a day's (4, 375) index minutes; NaN for a bar with no print
    (close: NaN when its last minute has no close, as h20)."""
    h, l, c = idx[1], idx[2], idx[3]
    bh = np.full(K.NBARS, np.nan)
    bl = np.full(K.NBARS, np.nan)
    bc = np.full(K.NBARS, np.nan)
    for k in range(K.NBARS):
        hh, ll = h[5 * k:5 * k + 5], l[5 * k:5 * k + 5]
        if np.isfinite(hh).any():
            bh[k] = np.nanmax(hh)
        if np.isfinite(ll).any():
            bl[k] = np.nanmin(ll)
        if 5 * k + 4 < len(c) and np.isfinite(c[5 * k + 4]):
            bc[k] = c[5 * k + 4]
    return bh, bl, bc


def atm(spot: float, step: float) -> float:
    """OrbRules.atmStrike: half-up, floor(spot / step + 0.5) * step."""
    return float(math.floor(spot / step + 0.5) * step)


def day_setup(idx: np.ndarray, und: str) -> dict | None:
    """The day's opening range and strike, or None for a day the arms skip (more than 2 empty range bars, or no 09:20
    close)."""
    bh, bl, bc = bars5(idx)
    if np.isnan(bh[:K.OR_BARS]).sum() > K.MAX_MISSING_OR or not np.isfinite(bc[K.STRIKE_BAR]):
        return None
    return dict(bh=bh, bl=bl, bc=bc, orh=float(np.nanmax(bh[:K.OR_BARS])), orl=float(np.nanmin(bl[:K.OR_BARS])),
                strike=atm(float(bc[K.STRIKE_BAR]), K.STEP[und]))


def signal(arm: str, s: dict, k: int) -> int:
    """+1 (buy the CE) / -1 (buy the PE) / 0 on bar k for `arm`, from bars up to k only. 0 outside the arm's window or
    when the bar has no print."""
    kind, k0, k1 = K.ARM_RULES[arm][:3]
    if k < k0 or k > k1:
        return 0
    bh, bl, bc, orh, orl = s["bh"], s["bl"], s["bc"], s["orh"], s["orl"]
    if not (np.isfinite(bc[k]) and np.isfinite(bh[k])):
        return 0
    if kind in ("break", "fresh"):
        d = 1 if bc[k] > orh else -1 if bc[k] < orl else 0
        if kind == "fresh" and d and np.isfinite(bc[k - 1]) and ((d > 0 and bc[k - 1] > orh) or (d < 0 and bc[k - 1] < orl)):
            return 0
        return d
    if bh[k] > orh and bc[k] < orh:
        return -1
    if np.isfinite(bl[k]) and bl[k] < orl and bc[k] > orl:
        return 1
    return 0


def entry_row(k: int) -> int:
    """The fill row of a decision on bar k: the minute after the bar closes."""
    return 5 * k + 5


def walk(arm: str, s: dict, price_fn) -> list:
    """One day of one arm under one exit rule. price_fn(er, side 'C'/'P') -> trade dict (with 'xr', the exit row, and
    'rE', the real fill) or None (no contract / no fill). The arm's taking rule:
      - one position at a time; after an exit the next decision bar must START after the exit's 5-minute bar;
      - a real fill <= 40 is refused (no count);
      - ORB / Fresh: at most 1 entry a side a day; Sweep: at most 2 entries a day."""
    side_cap, day_cap = K.ARM_RULES[arm][3], K.ARM_RULES[arm][4]
    k0, k1 = K.ARM_RULES[arm][1], K.ARM_RULES[arm][2]
    out, block = [], -1
    taken = {1: 0, -1: 0}
    for k in range(k0, k1 + 1):
        if 5 * k <= block:
            continue
        if len(out) >= day_cap:
            break
        d = signal(arm, s, k)
        if d == 0 or taken[d] >= side_cap:
            continue
        t = price_fn(entry_row(k), "C" if d > 0 else "P")
        if t is None or not (t["rE"] > K.MIN_PREMIUM):
            continue
        t["bar"] = k
        out.append(t)
        taken[d] += 1
        block = t["xr"] - t["xr"] % 5
    return out
