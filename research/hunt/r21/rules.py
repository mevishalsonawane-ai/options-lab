"""R21 rules: the app's Hero arm (engine orb/HeroRules.kt) and the pre-registered variants, on one expiry Day.

Grid: row t = the 1-minute bar that STARTS at 09:15 + t and CLOSES at t + 1. A decision "at row t" may read rows <= t
only; it acts at row t + 1 (the next bar's open). `decide()` is the only signal entry point, and the look-ahead guard
(test_r21.py) checks that it gives the same answer on a Day whose rows after t are blanked out (`masked`).

Times in the app are bar CLOSE times: the window "13:30 - 14:45" is rows 254 .. 329 (bars starting 13:29 .. 14:44),
the straddle low "since 12:00" starts at row 164 (the bar starting 11:59), and the 15:05 exit sells at row 350's open.
"""
from __future__ import annotations

import math
from copy import copy
from dataclasses import dataclass, field, replace

import numpy as np

import config as K  # noqa: F401  (puts research/ on the path)
from data import C, H, L, O, OI, V, Day, minute  # noqa: F401
from obuy.costs import Costs, Fills

TICK = 0.05
EPS = 1e-9
BUDGET = 5000.0
LOW_FROM = minute(11, 59)          # close time 12:00
EXIT_ROW = minute(15, 5)           # the 15:05 square-off sells at this bar's open
LAST_STOP_ROW = minute(15, 3)      # the stop reads closes before 15:05 (bars closing <= 15:04)
TRADED_BARS = 5
LEG_STALE = 2
SPOT_BAND = 0.03
FILL_TRIES = 3                     # the app works the entry limit for 3 minutes

FILLS = Fills("liq")
COSTS = Costs("app")


def close_row(hh: int, mm: int) -> int:
    """Row of the bar that CLOSES at hh:mm (the app's times)."""
    return minute(hh, mm) - 1


@dataclass(frozen=True)
class Params:
    name: str = "V0_hero"
    trigger: str = "straddle"      # straddle | straddle_recent | onesided | blind
    st_exp: float = 0.15
    recent: int = 0                # straddle_recent: expansion >= st_exp at any row in [t - recent, t]
    mom: float = 0.0025
    mom_k: int = 15
    k: float = 0.0                 # onesided: option close >= k x its own low since 12:00
    first: int = close_row(13, 30)
    last: int = close_row(14, 45)
    band: tuple = (1.0, 5.0)
    exits: str = "app"             # app | plain | lock | trail
    blind_at: int = close_row(13, 30)
    spread_mult: float = 1.0       # 2.0 = the stress test (twice the modelled half-spread)

    def label(self) -> str:
        return self.name


# ------------------------------------------------------------------------------------------------ point-in-time reads
def masked(day: Day, t: int) -> Day:
    """A copy of the day in which nothing after row t exists (the look-ahead guard's input)."""
    d = copy(day)
    d.ix = day.ix.copy()
    d.ix[t + 1:] = np.nan
    d.vix = None if day.vix is None else day.vix.copy()
    if d.vix is not None:
        d.vix[t + 1:] = np.nan
    d.opts = {}
    for k, a in day.opts.items():
        b = a.copy()
        b[t + 1:] = np.nan
        d.opts[k] = b
    return d


def spot(day: Day, t: int) -> float:
    return float(day.ix[t, 3]) if 0 <= t < len(day.ix) else math.nan


def traded(a: np.ndarray, t: int) -> np.ndarray:
    """Rows <= t with a real trade (volume > 0)."""
    v = a[: t + 1, V]
    return np.nonzero(np.nan_to_num(v) > 0)[0]


def ltp(a: np.ndarray, t: int) -> tuple[float, int]:
    """Last traded close at or before t, and its row (-1: none)."""
    r = traded(a, t)
    if not len(r):
        return math.nan, -1
    return float(a[r[-1], C]), int(r[-1])


def leg_price(a: np.ndarray | None, t: int) -> float:
    """HeroRules.legPrice: the newest bar's close if the leg traded within the last LEG_STALE minutes, else NaN."""
    if a is None:
        return math.nan
    p, r = ltp(a, t)
    if r < 0 or r < t - LEG_STALE:
        return math.nan
    # newest bar at or before t (a padded flat copy repeats the last trade's close)
    ok = np.nonzero(~np.isnan(a[: t + 1, C]))[0]
    return float(a[ok[-1], C])


def atm(day: Day, s: float) -> float:
    st = {"NIFTY": 50, "BANKNIFTY": 100}[day.und]
    return math.floor(s / st + 0.5) * st


def straddle(day: Day, t: int) -> float:
    s = spot(day, t)
    if math.isnan(s):
        return math.nan
    k = atm(day, s)
    return leg_price(day.opts.get((k, "C")), t) + leg_price(day.opts.get((k, "P")), t)


def straddle_path(day: Day, t: int) -> tuple[np.ndarray, np.ndarray]:
    """Valid straddle at rows LOW_FROM..t (NaN = invalid) and its running low (an invalid row never moves the low)."""
    rows = np.arange(LOW_FROM, t + 1)
    st = np.array([straddle(day, r) for r in rows])
    low = np.full(len(rows), np.nan)
    cur = math.nan
    for i, x in enumerate(st):
        if not math.isnan(x) and not math.isnan(spot(day, rows[i])):
            cur = x if math.isnan(cur) else min(cur, x)
        low[i] = cur
    return st, low


def momentum(day: Day, t: int, k: int) -> float:
    s, b = spot(day, t), spot(day, t - k)
    if math.isnan(s) or math.isnan(b) or b <= EPS:
        return math.nan
    return s / b - 1


def own_low(a: np.ndarray, t: int) -> float:
    """The option's lowest traded close from LOW_FROM to t."""
    r = traded(a, t)
    r = r[r >= LOW_FROM]
    return float(np.min(a[r, C])) if len(r) else math.nan


def candidates(day: Day, side: int, t: int) -> list[tuple[float, float]]:
    """(strike, ltp) of OTM options on `side`, within 3% of spot, nearest the money first, traded in the last 5 bars."""
    s = spot(day, t)
    r = "C" if side > 0 else "P"
    out = []
    for (k, sd), a in day.opts.items():
        if sd != r or abs(k - s) > s * SPOT_BAND:
            continue
        if (side > 0 and k <= s + EPS) or (side < 0 and k >= s - EPS):
            continue
        p, row = ltp(a, t)
        out.append((k, p, row))
    out.sort(key=lambda x: abs(x[0] - s))
    return out


def pick(day: Day, side: int, t: int, band: tuple, k_up: float = 0.0) -> tuple[float, float] | None:
    """HeroRules.pick (band, traded in the last 5 bars, the stale-slack rule) plus, for the one-sided trigger, the
    option's own close >= k_up x its low since 12:00."""
    lo, hi = band
    cs = candidates(day, side, t)
    for j, (k, p, row) in enumerate(cs):
        if math.isnan(p) or p < lo - EPS or p > hi + EPS or row < t - (TRADED_BARS - 1):
            continue
        slack = max(0.10, 0.25 * p)
        if any((not math.isnan(q)) and q > p + slack + EPS for _, q, _ in cs[j + 1:]):
            continue
        if k_up > 0:
            low = own_low(day.opts[(k, "C" if side > 0 else "P")], t)
            if math.isnan(low) or low <= EPS or p < k_up * low - EPS:
                continue
        return k, p
    return None


# ------------------------------------------------------------------------------------------------ the decision
@dataclass
class Signal:
    row: int
    side: int
    strike: float
    ref: float
    why: str = ""


def decide(day: Day, p: Params, t: int, _st=None) -> Signal | None:
    """The rule at the close of row t (reads rows <= t only). None: no entry at t."""
    if t < p.first or t > p.last:
        return None
    if p.trigger == "blind":
        if t != p.blind_at:
            return None
        mv = momentum(day, t, t - LOW_FROM)
        side = 1 if (math.isnan(mv) or mv >= 0) else -1
        c = pick(day, side, t, p.band)
        return Signal(t, side, c[0], c[1], "blind") if c else None
    mv = momentum(day, t, p.mom_k)
    if math.isnan(mv):
        return None
    side = 1 if mv >= p.mom - EPS else (-1 if mv <= -p.mom + EPS else 0)
    if side == 0:
        return None
    if p.trigger in ("straddle", "straddle_recent"):
        st, low = _st if _st is not None else straddle_path(day, t)
        n = 0 if p.trigger == "straddle" else p.recent
        ok = False
        for i in range(len(st) - 1, max(len(st) - 2 - n, -1), -1):
            if not math.isnan(st[i]) and not math.isnan(low[i]) and low[i] > EPS and st[i] / low[i] - 1 >= p.st_exp - EPS:
                ok = True
                break
        # the plain rule needs THIS minute's straddle valid and expanded (HeroRules.scan)
        if not ok or (n == 0 and math.isnan(st[-1])):
            return None
        c = pick(day, side, t, p.band)
        return Signal(t, side, c[0], c[1], "straddle") if c else None
    if p.trigger == "onesided":
        c = pick(day, side, t, p.band, p.k)
        return Signal(t, side, c[0], c[1], "onesided") if c else None
    raise ValueError(p.trigger)


def first_signal(day: Day, p: Params) -> Signal | None:
    """Scan the window minute by minute; one entry a day. (Straddle paths are built once, read up to each t.)"""
    full = None
    if p.trigger in ("straddle", "straddle_recent"):
        full = straddle_path(day, p.last)
    for t in range(p.first, p.last + 1):
        st = None
        if full is not None:
            n = t - LOW_FROM + 1
            st = (full[0][:n], full[1][:n])
        s = decide(day, p, t, st)
        if s is not None:
            return s
    return None


# ------------------------------------------------------------------------------------------------ fills and exits
def floor_tick(x: float) -> float:
    return round(math.floor(x / TICK + 1e-6) * TICK, 2)


def vol5_lots(a: np.ndarray, r: int, lot: int) -> float:
    v = a[max(r - 5, 0): r, V]
    return float(np.nansum(v)) / max(lot, 1)


def _ticks(v5: float, mult: float) -> float:
    return (1.0 if v5 >= 20 else (2.0 if v5 > 0 else 4.0)) * mult


def buy_px(p: float, v5: float, mult: float) -> float:
    return round(float(FILLS.buy(np.array([p]))[0]) + (_ticks(v5, mult) - 1) * TICK, 2)   # FILLS adds 1 tick itself


def sell_px(p: float, v5: float, mult: float) -> float:
    return max(round(float(FILLS.sell(np.array([p]))[0]) - (_ticks(v5, mult) - 1) * TICK, 2), 0.0)


@dataclass
class Trade:
    und: str
    day: object
    variant: str
    side: int
    strike: float
    sig_row: int
    fill_row: int
    entry: float
    lots: int
    qty: int
    exits: list = field(default_factory=list)     # (row, qty, price, why)
    gross: float = 0.0
    charges: float = 0.0
    net: float = 0.0
    peak_mult: float = 0.0                       # the option's best high after the fill / entry (before 15:05)


def enter(day: Day, sig: Signal, p: Params) -> tuple[int, float] | None:
    """The app's entry: LIMIT at the ask (+1 tick, 2 under Rs 2) capped at ref x 1.2 + 0.10, worked for 3 minutes."""
    a = day.opts[(sig.strike, "C" if sig.side > 0 else "P")]
    cap = floor_tick(sig.ref * 1.2 + 0.10)
    for r in range(sig.row + 1, min(sig.row + 1 + FILL_TRIES, EXIT_ROW)):
        o = a[r, O]
        if math.isnan(o):
            continue
        v5 = vol5_lots(a, r, day.lot)
        ask = buy_px(o, v5, p.spread_mult)       # the limit (ask + 1-2 ticks) is marketable: it fills at the ask
        if ask <= cap + EPS:
            return r, ask
        lo = a[r, L]
        if not math.isnan(lo) and buy_px(lo, v5, p.spread_mult) <= cap + EPS:
            return r, cap
    return None


def simulate(day: Day, sig: Signal, p: Params) -> Trade | None:
    side = "C" if sig.side > 0 else "P"
    a = day.opts[(sig.strike, side)]
    f = enter(day, sig, p)
    if f is None:
        return None
    fr, e = f
    lots = int(math.floor(BUDGET / (e * day.lot) + 1e-9)) if day.lot > 0 else 0
    if lots <= 0:
        return None
    tr = Trade(day.und, day.day, p.name, sig.side, sig.strike, sig.row, fr, e, lots, lots * day.lot)
    held = lots
    half_sold = False
    t1, t2 = floor_tick(5 * e), floor_tick(20 * e)
    best_hi, best_c, armed = e, e, False
    hi_after = np.nanmax(a[fr:EXIT_ROW, H]) if EXIT_ROW > fr else e
    tr.peak_mult = float(hi_after / e) if not math.isnan(hi_after) else 1.0

    def sell(r, n, px, why):
        tr.exits.append((r, n * day.lot, px, why))

    r = fr
    while r < EXIT_ROW and held > 0:
        hi, c = a[r, H], a[r, C]
        if p.exits in ("app", "lock", "trail") and not math.isnan(hi):
            if not half_sold and hi >= t1 + TICK - EPS:
                n = held if held == 1 else min(held, max(1, int(round(held * 0.5))))
                sell(r, n, t1, "5x")
                held -= n
                half_sold = True
            if half_sold and held > 0 and hi >= t2 + TICK - EPS:
                sell(r, held, t2, "20x")
                held = 0
                break
        if not math.isnan(hi):
            best_hi = max(best_hi, hi)
        stop_why = None
        if r > fr and r <= LAST_STOP_ROW and not math.isnan(c):
            if p.exits in ("app", "lock", "trail") and c <= 0.4 * e + EPS:
                stop_why = "stop"
            if p.exits == "lock" and armed and c <= 2 * e + EPS:
                stop_why = "lock2x"
            if p.exits == "trail" and armed and c <= 0.5 * best_c + EPS:
                stop_why = "trail"
        if not math.isnan(c):
            best_c = max(best_c, c)
        if p.exits == "lock" and best_hi >= 4 * e - EPS:
            armed = True          # takes effect from the next minute's close
        if p.exits == "trail" and best_c >= 3 * e - EPS:
            armed = True
        if stop_why:
            x = _next_open(a, r + 1)
            if x is not None:
                rr, o = x
                sell(rr, held, sell_px(o, vol5_lots(a, rr, day.lot), p.spread_mult), stop_why)
                held = 0
                break
        r += 1
    if held > 0:
        x = _next_open(a, EXIT_ROW)
        if x is None:          # no bar from 15:05 on: the last trade before it
            pr, row = ltp(a, EXIT_ROW - 1)
            rr, o = row, pr
        else:
            rr, o = x
        sell(rr, held, sell_px(o, vol5_lots(a, rr, day.lot), p.spread_mult), "1505")
    _settle(tr, day)
    return tr


def _next_open(a: np.ndarray, r: int) -> tuple[int, float] | None:
    for rr in range(r, len(a)):
        if not math.isnan(a[rr, O]):
            return rr, float(a[rr, O])
    return None


def _settle(tr: Trade, day: Day) -> None:
    buy = tr.entry * tr.qty
    ch = float(COSTS.charge(True, np.array([tr.entry]), np.array([tr.qty]))[0])
    sold = 0.0
    for _, q, px, _ in tr.exits:
        sold += px * q
        ch += float(COSTS.charge(False, np.array([px]), np.array([q]))[0])
    tr.gross = round(sold - buy, 2)
    tr.charges = round(ch, 2)
    tr.net = round(tr.gross - ch, 2)


def run_day(day: Day, p: Params) -> Trade | None:
    s = first_signal(day, p)
    return simulate(day, s, p) if s else None


def twin_signal(day: Day, p: Params, side: int, rng: np.random.Generator) -> Signal | None:
    """Random-time twin: a uniformly random minute of the variant's window, the same side and the same strike rule
    (band, liquidity, slack) but no trigger. Up to 20 draws to find a minute with a pickable option."""
    for _ in range(20):
        t = int(rng.integers(p.first, p.last + 1))
        c = pick(day, side, t, p.band)
        if c:
            return Signal(t, side, c[0], c[1], "twin")
    return None
