"""R120 (28 Oct 2026): a faithful Python port of the app's time machine (android/engine .../risk/TimeMachine.kt).

Same maths, line for line: the usable sessions (dated before today, bars on their own day, one per date, the newest 60),
the bootstrap (a random past session, the same time-of-day slot +-2 bars, in at that bar's open), the walk (the stop
checked BEFORE the target inside a bar; the arm's time exit or the session's end = "neither"), the break-even 1 / (1 + R)
and the verdict (edge / coin flip / against, margin max(5 points, 2 standard errors over the distinct sessions drawn)).

The random draws are Kotlin's own: `kotlin.random.Random(seed: Long)` is XorWow (Marsaglia 2003) seeded as
XorWowRandom(seed.toInt(), (seed shr 32).toInt()), and `nextInt(from, until)` is ported with its power-of-two shortcut and
its rejection loop, so a check here and on the phone with the same seed draw the same sessions and slots (pinned in
test_r120.py against numbers printed by the Kotlin engine). The app seeds each check with `ref.hashCode().toLong()`
(Java's String.hashCode, sign-extended); `java_hash` ports that.

Pure: no clock, no I/O. A bar is (start: datetime, open, high, low, close).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, datetime, time
from typing import NamedTuple

DRAWS = 2_000
SLOT_TOLERANCE = 2
MIN_SESSIONS = 10
KEEP_SESSIONS = 60
MIN_DECIDED = 100
MIN_MARGIN = 0.05

EDGE, COIN_FLIP, AGAINST, UNKNOWN = "edge", "coin flip", "against", "unknown"


class Bar(NamedTuple):
    start: datetime
    open: float
    high: float
    low: float
    close: float


@dataclass
class Session:
    date: date
    bars: list


@dataclass
class Ask:
    direction: int
    slot: time
    stop_points: float
    target_points: float
    bar_minutes: int
    exit_at: time | None = None
    draws: int = DRAWS
    tolerance: int = SLOT_TOLERANCE
    seed: int = 0


@dataclass
class Odds:
    draws: int
    used: int
    sessions: int
    sessions_hit: int
    target_first: int
    stop_first: int
    neither: int
    r: float
    break_even: float
    verdict: str
    why: str | None
    slot: time = field(default=time(0, 0))
    tolerance: int = SLOT_TOLERANCE

    def _pct(self, n: int) -> float:
        return 0.0 if self.used == 0 else 100.0 * n / self.used

    @property
    def target_pct(self) -> float:
        return self._pct(self.target_first)

    @property
    def stop_pct(self) -> float:
        return self._pct(self.stop_first)

    @property
    def neither_pct(self) -> float:
        return self._pct(self.neither)

    @property
    def decided_win_rate(self) -> float:
        d = self.target_first + self.stop_first
        return float("nan") if d == 0 else self.target_first / d


# ---------------------------------------------------------------- Kotlin's Random, ported

_M32 = 0xFFFFFFFF


def _i32(x: int) -> int:
    x &= _M32
    return x - (1 << 32) if x & 0x80000000 else x


class KotlinRandom:
    """kotlin.random.XorWowRandom, seeded as kotlin.random.Random(seed: Long)."""

    def __init__(self, seed: int):
        seed &= 0xFFFFFFFFFFFFFFFF
        s1 = _i32(seed)
        s2 = _i32(seed >> 32)
        self.x, self.y, self.z, self.w = s1, s2, 0, 0
        self.v = _i32(~s1)
        self.addend = _i32((s1 << 10) ^ ((s2 & _M32) >> 4))
        if (self.x | self.y | self.z | self.w | self.v) == 0:
            raise ValueError("initial state must have at least one non-zero element")
        for _ in range(64):
            self.next_int()

    def next_int(self) -> int:
        t = self.x
        t = _i32(t ^ ((t & _M32) >> 2))
        self.x, self.y, self.z = self.y, self.z, self.w
        v0 = self.v
        self.w = v0
        t = _i32((t ^ (t << 1)) ^ v0 ^ (v0 << 4))
        self.v = t
        self.addend = _i32(self.addend + 362437)
        return _i32(t + self.addend)

    def next_bits(self, bit_count: int) -> int:
        r = self.next_int()
        # Int.takeUpperBits: this.ushr(32 - bitCount) and (-bitCount).shr(31); the JVM masks a shift of 32 to 0.
        sh = (32 - bit_count) & 31
        mask = -1 if bit_count > 0 else 0
        return _i32(((r & _M32) >> sh) & mask)

    def next_int_range(self, lo: int, until: int) -> int:
        if until <= lo:
            raise ValueError("empty range")
        n = _i32(until - lo)
        if n > 0 or n == -(1 << 31):
            if n > 0 and (n & -n) == n:
                rnd = self.next_bits(n.bit_length() - 1)
            else:
                while True:
                    bits = (self.next_int() & _M32) >> 1
                    v = bits % n
                    if _i32(bits - v + (n - 1)) >= 0:
                        break
                rnd = v
            return lo + rnd
        while True:
            rnd = self.next_int()
            if lo <= rnd < until:
                return rnd


def java_hash(s: str) -> int:
    """Java's String.hashCode (UTF-16 code units), as a signed 32-bit int; .toLong() keeps the sign."""
    u = s.encode("utf-16-be")
    h = 0
    for i in range(0, len(u), 2):
        h = (31 * h + int.from_bytes(u[i:i + 2], "big")) & _M32
    return _i32(h)


# ---------------------------------------------------------------- the time machine


def usable(sessions: list, today: date, keep: int = KEEP_SESSIONS) -> list:
    """Sessions dated before today, non-empty, every bar on its own day; one per date (the last given), newest keep."""
    by = {}
    for s in sessions:
        if not (s.date < today and s.bars and all(b.start.date() == s.date for b in s.bars)):
            continue
        by[s.date] = Session(s.date, sorted(s.bars, key=lambda b: b.start))
    out = [by[d] for d in sorted(by)]
    return out[-keep:] if keep > 0 else []


def walk(bars: list, start: int, entry: float, ask: Ask) -> int:
    """+1 the target came first, -1 the stop (checked first inside a bar), 0 neither."""
    up = ask.direction > 0
    stop = entry - ask.stop_points if up else entry + ask.stop_points
    target = entry + ask.target_points if up else entry - ask.target_points
    for j in range(start, len(bars)):
        b = bars[j]
        if ask.exit_at is not None and not (b.start.time() < ask.exit_at):
            return 0
        if up:
            if b.low <= stop:
                return -1
            if b.high >= target:
                return 1
        else:
            if b.high >= stop:
                return -1
            if b.low <= target:
                return 1
    return 0


def _ok(x) -> bool:
    return x is not None and not (isinstance(x, float) and (math.isnan(x) or math.isinf(x))) and x > 0


def odds(sessions: list, ask: Ask, today: date) -> Odds:
    """The bootstrap odds of ask from sessions for today (TimeMachine.odds without the rule's re-run)."""
    past = usable(sessions, today)
    r = ask.target_points / ask.stop_points if _ok(ask.stop_points) and _ok(ask.target_points) else float("nan")
    be = float("nan") if math.isnan(r) else 1.0 / (1.0 + r)

    def none(why):
        return Odds(ask.draws, 0, len(past), 0, 0, 0, 0, r, be, UNKNOWN, why, ask.slot, ask.tolerance)

    if math.isnan(r):
        return none("stop or target is not a positive number of points, so R cannot be told")
    if not past:
        return none("no past sessions of the underlying on the phone")
    if ask.draws <= 0 or ask.bar_minutes <= 0:
        return none("nothing to draw")

    # one index per session: the bar starting at each minute (Kotlin's indexOfFirst on the time)
    first_at = []
    for s in past:
        m = {}
        for i, b in enumerate(s.bars):
            k = b.start.hour * 60 + b.start.minute
            if b.start.second == 0 and b.start.microsecond == 0 and k not in m:
                m[k] = i
        first_at.append(m)
    rng = KotlinRandom(ask.seed)
    used = target = stop = neither = 0
    hit = set()
    memo = {}   # (session, bar) -> walk outcome: the same entry replayed again gives the same answer (speed only)
    slot_min = ask.slot.hour * 60 + ask.slot.minute   # toSecondOfDay() / 60
    for _ in range(ask.draws):
        si = rng.next_int_range(0, len(past))
        off = rng.next_int_range(-ask.tolerance, ask.tolerance + 1)
        sm = slot_min + off * ask.bar_minutes
        if sm < 0 or sm >= 24 * 60:
            continue
        i = first_at[si].get(sm)
        if i is None:
            continue
        s = past[si]
        used += 1
        hit.add(s.date)
        w = memo.get((si, i))
        if w is None:
            w = memo[(si, i)] = walk(s.bars, i, s.bars[i].open, ask)
        if w == 1:
            target += 1
        elif w == -1:
            stop += 1
        else:
            neither += 1
    decided = target + stop
    why = None
    hhmm = f"{ask.slot.hour:02d}:{ask.slot.minute:02d}"
    if used == 0:
        verdict, why = UNKNOWN, f"no past session has a bar at {hhmm} ±{ask.tolerance} bars"
    elif len(past) < MIN_SESSIONS:
        verdict, why = UNKNOWN, f"only {len(past)} past sessions ({MIN_SESSIONS} needed for a verdict)"
    elif decided < MIN_DECIDED:
        verdict, why = UNKNOWN, f"only {decided} draws reached the stop or the target"
    else:
        margin = max(MIN_MARGIN, 2.0 * math.sqrt(be * (1 - be) / len(hit)))
        win = target / decided
        verdict = EDGE if win >= be + margin else AGAINST if win <= be - margin else COIN_FLIP
        if neither > decided:
            why = "most draws reached neither, so the time exit decides more than the stop or the target"
    return Odds(ask.draws, used, len(past), len(hit), target, stop, neither, r, be, verdict, why, ask.slot, ask.tolerance)


def points(stop, target, premium: bool, delta: float | None = None):
    """TimeMachineHost.points: no target -> target = stop (R 1); premium points / delta (0.5 when none or out of range)."""
    t = target if target is not None else stop
    if not premium:
        return stop, t
    d = delta if (delta is not None and 0.05 < delta <= 1.0) else 0.5
    return (None if stop is None else stop / d), (None if t is None else t / d)


def fold(ones: list, minutes: int) -> list:
    """TimeMachineHost.fold: 1-minute bars into minutes-minute bars labelled by their start."""
    if minutes <= 1:
        return sorted(ones, key=lambda b: b.start)
    g = {}
    for b in sorted(ones, key=lambda b: b.start):
        m = b.start.hour * 60 + b.start.minute
        m -= m % minutes
        k = b.start.replace(hour=m // 60, minute=m % 60, second=0, microsecond=0)
        g.setdefault(k, []).append(b)
    return [Bar(k, v[0].open, max(x.high for x in v), min(x.low for x in v), v[-1].close) for k, v in sorted(g.items())]


def slot_of(t: datetime, bar_minutes: int) -> time:
    """The app's slot: the entry time with its minute floored to the bar interval."""
    return time(t.hour, t.minute - t.minute % bar_minutes)
