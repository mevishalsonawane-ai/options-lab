"""R121 (PREREG.md): the enhanced time machine. Each arm's own past entries are replayed on the underlying's 1-minute
bars, (V_SETUP) with stop and target to the square-off as the app's walk, or (V_EXITS) with the arm's real paper exits
converted to index points at delta 0.5; each replay is scored in R; a trade's verdict is the mean R of the arm's entries
on the same underlying in the 60 sessions before its day. 28 Oct 2026.

A day's bars: dict(minute=np.int array (minutes after midnight), open, high, low, close: float arrays), sorted.
A trade: R120's dict (arm, und, day, entry_ts, side, net, prem, setup, stop_pts, target_pts, ...).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, time

import numpy as np

DELTA = 0.5
KEEP = 60
MIN_N = 10
MIN_MARGIN = 0.05

# the paper lock (R66 L25 + R64 30-60-100): (premium gain that arms it, premium gain it locks); breakeven = 0
LOCK_L25 = ((0.25, 0.05), (0.30, 0.0), (0.60, 0.30), (1.00, 0.60))
# the owner's ORB ladder on the target distance: (share of the way, share locked)
ORB_LADDER = ((0.25, 0.0), (0.50, 0.25), (0.75, 0.50))


@dataclass
class Exits:
    """One entry's exits in INDEX points along its direction (+ = in its favour)."""
    stop: float | None                     # distance to the stop (> 0); None: no stop
    target: float | None = None            # distance to the target; None: none
    square_off: int = 15 * 60 + 15         # minute of the day it is out by (at that bar's open)
    tmax: int | None = None                # out at the open of the minute entry + tmax
    need_at: int | None = None             # minutes after the fill: out then unless the move reached need_pts
    need_pts: float = 0.0
    locks: tuple = ()                      # ((trigger points, locked points), ...): locks only rise
    trail_prem: float | None = None        # Hero: trail this share from the best PREMIUM (prem given)
    prem: float | None = None
    r_unit: float | None = None            # the points that make 1 R (the initial stop distance)


def _t(h, m) -> int:
    return h * 60 + m


def setup_exits(t: dict) -> Exits | None:
    """V_SETUP: the app's walk (TimeMachine.walk): the R120 ask's stop and target, out at its exit time."""
    if t.get("stop_pts") is None or t.get("target_pts") is None or not (t["stop_pts"] > 0 and t["target_pts"] > 0):
        return None
    ea = t.get("exit_at")
    so = _t(ea.hour, ea.minute) if ea is not None else _t(15, 30)
    return Exits(stop=t["stop_pts"], target=t["target_pts"], square_off=so, r_unit=t["stop_pts"])


def real_exits(t: dict) -> Exits | None:
    """V_EXITS: the arm's real paper exits (PREREG section 1), in index points at delta 0.5."""
    arm, prem = t["arm"], t.get("prem")
    pts = (lambda f: f * prem / DELTA) if prem else None
    lock = (lambda: tuple((pts(a), pts(b)) for a, b in LOCK_L25))
    if arm in ("R51", "R72") or (arm == "SOLO" and t.get("setup") == "S2"):
        if not prem:
            return None
        return Exits(stop=pts(0.15), square_off=_t(15, 15), tmax=30, locks=lock(), r_unit=pts(0.15))
    if arm == "R52" or (arm == "SOLO" and t.get("setup") in ("S3", "S4")):
        if not prem:
            return None
        s = 0.25 if t.get("setup") == "S4" else 0.30
        return Exits(stop=pts(s), square_off=_t(15, 10), tmax=60, locks=lock(), r_unit=pts(s))
    if arm == "SOLO":
        return None                                       # S1: no stop on the paper host
    if arm == "LIQ":
        tgt = t.get("pool_target_pts")                    # None: no pool target (no fixed target)
        return Exits(stop=t["stop_pts"], target=tgt, square_off=_t(15, 10), need_at=20,
                     need_pts=(pts(0.05) if prem else 0.0), r_unit=t["stop_pts"])
    if arm == "VIXDIV":
        if t["und"] != "NIFTY" or not prem:
            return None
        return Exits(stop=pts(0.15), square_off=_t(15, 10), need_at=20, need_pts=pts(0.05), r_unit=pts(0.15))
    if arm in ("ORB_ORB", "ORB_FRESH", "ORB_FADE", "ORB_SWEEP"):
        s = 40.0 / DELTA
        tg = (80.0 if arm == "ORB_SWEEP" else 40.0) / DELTA
        return Exits(stop=s, target=tg, square_off=_t(15, 10), locks=tuple((a * tg, b * tg) for a, b in ORB_LADDER),
                     r_unit=s)
    if arm == "HERO":
        if not prem:
            return None
        return Exits(stop=None, square_off=_t(15, 5), trail_prem=0.25, prem=prem, r_unit=pts(0.25))
    if arm.startswith("PINE_"):
        if t.get("stop_pts") is None:
            return None
        return Exits(stop=t["stop_pts"], square_off=_t(15, 15), r_unit=t["stop_pts"])
    raise KeyError(arm)


def walk(bars: dict, entry_minute: int, side: int, x: Exits) -> float | None:
    """Index points gained (+) or lost (-) along side from the entry minute's open to the exit; None: no bar there.
    Inside a bar: the time exits first (at the bar's open), then the stop / lock / trail (checked before the target),
    then the target; the best price, the locks and the trail move only after the bar."""
    mins = bars["minute"]
    i0 = int(np.searchsorted(mins, entry_minute))
    if i0 >= len(mins) or mins[i0] != entry_minute:
        return None
    o, h, l, c = bars["open"], bars["high"], bars["low"], bars["close"]
    e = o[i0]
    fav = (lambda p: (p - e) * side)
    best = 0.0
    floor = -x.stop if x.stop is not None else -math.inf          # the exit level, in favour-points
    if x.trail_prem is not None:
        floor = -x.trail_prem * x.prem / DELTA
    passed = x.need_at is None
    last = 0.0
    for i in range(i0, len(mins)):
        m = int(mins[i])
        if m >= x.square_off:
            return fav(o[i])
        if x.tmax is not None and m >= entry_minute + x.tmax:
            return fav(o[i])
        if not passed and m >= entry_minute + x.need_at:
            return fav(o[i])
        hi_f = fav(h[i]) if side > 0 else fav(l[i])               # the bar's best for the trade
        lo_f = fav(l[i]) if side > 0 else fav(h[i])               # its worst
        op_f = fav(o[i])
        if lo_f <= floor:
            return min(op_f, floor) if i > i0 else floor
        if x.target is not None and hi_f >= x.target:
            return max(op_f, x.target) if i > i0 else x.target
        best = max(best, hi_f)
        for trig, keep in x.locks:
            if best >= trig:
                floor = max(floor, keep)
        if x.trail_prem is not None:
            bp = x.prem + DELTA * best                            # the best premium so far
            floor = max(floor, best - x.trail_prem * bp / DELTA)
        last = fav(c[i])
        if not passed and m == entry_minute + x.need_at - 1:
            passed = last >= x.need_pts
    return last


def replay_r(bars: dict, t: dict, exits_fn) -> float | None:
    """One entry's result in R (points / its R unit); None when no replay is possible."""
    x = exits_fn(t)
    if x is None or not x.r_unit or x.r_unit <= 0 or bars is None:
        return None
    m = t["entry_ts"].hour * 60 + t["entry_ts"].minute
    pts = walk(bars, m, int(t["side"]), x)
    return None if pts is None else pts / x.r_unit


@dataclass
class Verdict:
    verdict: str
    n: int
    mean: float = float("nan")
    se: float = float("nan")


def verdict_of(rs: list) -> Verdict:
    """Mean R vs 0, margin max(0.05 R, 2 standard errors); fewer than MIN_N replays: unknown."""
    v = [r for r in rs if r is not None and math.isfinite(r)]
    if len(v) < MIN_N:
        return Verdict("unknown", len(v))
    a = np.asarray(v, float)
    mean = float(a.mean())
    se = float(a.std(ddof=1) / math.sqrt(len(a)))
    margin = max(MIN_MARGIN, 2.0 * se)
    w = "edge" if mean >= margin else "against" if mean <= -margin else "coin flip"
    return Verdict(w, len(a), mean, se)


def sample_before(entries: list, day: date, session_days: list, keep: int = KEEP) -> list:
    """The look-ahead guard: the entries dated on the newest `keep` sessions strictly before `day`.
    entries: [(day, r)], session_days: the underlying's trading dates, sorted."""
    j = int(np.searchsorted(np.array(session_days, dtype="datetime64[D]"), np.datetime64(day, "D")))
    window = set(session_days[max(0, j - keep):j])
    return [r for d, r in entries if d in window and d < day]
