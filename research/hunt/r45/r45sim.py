"""R45: the trade engine of the audited "NIFTY premium mean-reversion scalp" (pure functions, no I/O).

SIGNAL SOURCE: the OPTION'S OWN PRICE CHART (5-minute bars of an ATM call premium). Not the default kind of rule under
CLAUDE.md: labelled, reported separately, paper only if Boss switches it on.

One session at a time. A session gives:
  t        5-minute bar start, minutes since midnight (only bars of the SIGNAL series that exist)
  close    signal-series closes on those bars
  lower    Bollinger lower band on those bars (NaN where not defined)
  bars(k)  the TRADE bars (open, high, low, close lists on the same grid, None where a bar is missing) for the trade
           entered on bar k. T0 trades the signal series itself; P1a re-prices each trade on one fixed contract.
Rules (PREREG.md): signal at i when close[i] < lower[i] and close[i-1] >= lower[i-1]; entry at the open of i+1; stop,
stepped lock, time exit, 15:15 square-off; max 2 trades; no overlap.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field, replace

import numpy as np

CUT = 15 * 60 + 15            # square-off bar (15:15) and the last minute an entry may NOT start at
LOCKS = (0.25, 0.50, 0.75, 1.00)


@dataclass(frozen=True)
class Rules:
    stop: float = 0.10            # stop 10% below entry
    locks: tuple = LOCKS          # stepped profit lock levels (gain over entry)
    checked: int = 3              # bars checked for stop/lock: e .. e+checked-1
    exit_at: int = 3              # time exit at the CLOSE of bar e+exit_at (their code: 3 -> e+3, unchecked)
    gap: bool = False             # P2: a bar opening beyond the level fills at the open
    prev_lock: bool = False       # P2: lock levels armed by the peak up to the previous bar only
    max_trades: int = 2
    cut: int = CUT
    after: str = "exit"           # a new signal must come after: 'exit' bar (default), on/after the 'exit_bar', or
                                  # after the previous 'signal' (overlapping trades; reproduction aid only)


def bollinger_lower(close, n: int = 20, k: float = 2.0, ddof: int = 0) -> np.ndarray:
    """SMA(n) - k*SD(n) of `close`; NaN for the first n-1 values (and where the window holds a NaN)."""
    c = np.asarray(close, dtype=np.float64)
    out = np.full(len(c), np.nan)
    if len(c) < n:
        return out
    w = np.lib.stride_tricks.sliding_window_view(c, n)
    out[n - 1:] = w.mean(axis=1) - k * w.std(axis=1, ddof=ddof)
    return out


def cross_below(close, lower) -> np.ndarray:
    """True at i when close[i] < lower[i] and close[i-1] >= lower[i-1] (NaN comparisons are False)."""
    c = np.asarray(close, dtype=np.float64)
    lo = np.asarray(lower, dtype=np.float64)
    s = np.zeros(len(c), dtype=bool)
    if len(c) < 2:
        return s
    with np.errstate(invalid="ignore"):
        s[1:] = (c[1:] < lo[1:]) & (c[:-1] >= lo[:-1])
    return s


@dataclass
class Trade:
    sig: int                      # signal bar index
    e: int                        # entry bar index
    x: int                        # exit bar index
    entry: float
    exit: float
    why: str                      # stop | lock | time | sq (15:15) | eod (session ended)
    extra: dict = field(default_factory=dict)


def _last_close(c, j, lo):
    """Last traded close at or before bar j (not before lo); None if nothing traded."""
    for q in range(j, lo - 1, -1):
        if c[q] is not None and not (isinstance(c[q], float) and math.isnan(c[q])):
            return c[q]
    return None


def _level(peak, locks):
    """The highest lock level the peak gain has reached (None below the first)."""
    return max([L for L in locks if peak >= L - 1e-12], default=None)


def _ok(v):
    return v is not None and not (isinstance(v, float) and math.isnan(v))


def run_trade(t, o, h, lo_, c, e: int, r: Rules):
    """One trade entered at the open of bar e on trade bars (o, h, l, c). Returns (x, exit_price, why) or None when
    bar e has no open (no entry possible)."""
    if e >= len(t) or not _ok(o[e]):
        return None
    ent = o[e]
    stop = ent * (1 - r.stop)
    peak = 0.0                                    # best gain reached so far (fraction)
    n = len(t)
    last = min(e + max(r.checked - 1, r.exit_at), n - 1)
    for j in range(e, last + 1):
        if t[j] >= r.cut:                         # square-off at this bar's open
            px = o[j] if _ok(o[j]) else _last_close(c, j - 1, e)
            return j, px, "sq"
        if j > e + r.checked - 1:                 # an unchecked bar: only the time exit can happen
            if j == e + r.exit_at:
                px = c[j] if _ok(c[j]) else _last_close(c, j, e)
                return j, (px if px is not None else ent), "time"
            continue
        if not _ok(h[j]):                         # missing bar: nothing can be checked
            if j == e + r.exit_at:
                px = _last_close(c, j, e)
                return j, (px if px is not None else ent), "time"
            continue
        lvl_prev = _level(peak, r.locks)          # the lock armed by the bars BEFORE this one
        if r.gap and _ok(o[j]):                   # P2: a bar opening beyond a level fills at the open
            if o[j] <= stop:
                return j, o[j], "stop"
            if lvl_prev is not None and o[j] <= ent * (1 + lvl_prev):
                return j, o[j], "lock"
        if lo_[j] <= stop:                        # stop first
            return j, stop, "stop"
        if not r.prev_lock:                       # their code: this bar's high arms a lock its own low can fill
            peak = max(peak, h[j] / ent - 1)
        lvl = _level(peak, r.locks)
        if lvl is not None and lo_[j] <= ent * (1 + lvl):
            return j, ent * (1 + lvl), "lock"
        if r.prev_lock:
            peak = max(peak, h[j] / ent - 1)
        if j == e + r.exit_at:
            return j, c[j], "time"
    # session ended before the time exit
    px = _last_close(c, last, e)
    return last, (px if px is not None else ent), "eod"


def simulate_session(t, close, lower, bars, r: Rules, entries=None):
    """All trades of one session. `bars(k)` -> (o, h, l, c) trade bars for an entry on bar k. `entries`: an
    iterable of forced signal indices (random twins) used instead of the Bollinger cross."""
    t = list(t)
    sig = cross_below(close, lower) if entries is None else None
    out = []
    i = 1
    after = -1                                    # a new signal must be on a bar after this one
    forced = None if entries is None else list(entries)
    while len(out) < r.max_trades:
        if forced is not None:
            if not forced:
                break
            i = forced.pop(0)
            if i <= after:
                continue
        else:
            while i < len(t) and not (sig[i] and i > after):
                i += 1
            if i >= len(t):
                break
        e = i + 1
        if e >= len(t) or t[e] >= r.cut:
            if forced is None:
                break                             # every later signal is too late as well
            continue
        o, h, lo_, c = bars(e)
        res = run_trade(t, o, h, lo_, c, e, r)
        if res is None:
            i += 1
            continue
        x, px, why = res
        out.append(Trade(sig=i, e=e, x=x, entry=o[e], exit=float(px), why=why))
        after = {"exit": x, "exit_bar": x - 1, "signal": i}[r.after]
        i = after + 1
    return out


def eligible_signal_bars(t, r: Rules, after: int = -1, first: int = 20):
    """Bars a random twin may 'signal' on: the Bollinger cross needs bars first-1 and first (bands from bar 19), the
    entry bar must exist and start before the cut, and the bar must be after the previous trade's exit."""
    return [i for i in range(max(first, after + 1), len(t) - 1) if t[i + 1] < r.cut]


def twin_session(t, bars, r: Rules, n: int, rng):
    """A random-entry twin: n sequential random entries (same exits), each at a uniformly drawn eligible bar after the
    previous trade's exit."""
    out = []
    after = -1
    for _ in range(n):
        el = eligible_signal_bars(t, r, after)
        if not el:
            break
        i = int(el[rng.integers(len(el))])
        tr = simulate_session(t, None, None, bars, replace(r, max_trades=1), entries=[i])
        if not tr:
            after = i
            continue
        out.append(tr[0])
        after = tr[0].x
    return out
