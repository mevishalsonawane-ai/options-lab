"""R25 shared engine: point-in-time reads, fills, charges and the exits (PREREG.md "Costs and fills", Step 3).

Grid: row t = the bar that STARTS at 09:15 + t and CLOSES at t + 1. A decision at row t reads rows <= t only and acts
at row t + 1 (the next bar's open). Times in rules are bar CLOSE times, as in the app: close_row(13, 30) is the bar
that starts 13:29.

Out-of-range prices (PREREG.md "Missing prices"). A held strike can leave the stored ATM+-10 range:
  - further OTM: its value is the outermost stored strike's price on that side (an upper bound) or 0 (oor_zero=True);
  - deep ITM: its intrinsic value (a lower bound).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

import r25cfg as K
from r25data import C, H, L, N, O, OI, V, Day, minute  # noqa: F401
from obuy.costs import Costs, Fills

TICK = 0.05
EPS = 1e-9
EXIT_ROW = minute(15, 5)           # the 15:05 square-off sells at this bar's open
LAST_STOP_ROW = minute(15, 3)      # stops read closes of bars closing <= 15:04
LOW_FROM = minute(11, 59)          # close time 12:00
FILL_TRIES = 3
TRADED_BARS = 5

FILLS = Fills("liq")
COSTS = Costs("app")


def close_row(hh: int, mm: int) -> int:
    """Row of the bar that CLOSES at hh:mm."""
    return minute(hh, mm) - 1


def floor_tick(x: float) -> float:
    return round(math.floor(x / TICK + 1e-6) * TICK, 2)


# ------------------------------------------------------------------------------------------------ point-in-time reads
def masked(day: Day, t: int) -> Day:
    """A copy of the day in which nothing after row t exists (the look-ahead guard's input)."""
    from copy import copy
    d = copy(day)
    d.ix = day.ix.copy()
    d.ix[t + 1:] = np.nan
    d.vix = None if day.vix is None else day.vix.copy()
    if d.vix is not None:
        d.vix[t + 1:] = np.nan
    d.edge = None if day.edge is None else day.edge.copy()
    if d.edge is not None:
        d.edge[t + 1:] = np.nan
    d.opts = {}
    for k, a in day.opts.items():
        b = a.copy()
        b[t + 1:] = np.nan
        d.opts[k] = b
    if hasattr(d, "_rng"):
        del d._rng
    return d


def poisoned(day: Day, t: int, value: float = 1e6) -> Day:
    """A copy whose rows after t hold absurd values (the second look-ahead check)."""
    d = masked(day, t)
    d.ix[t + 1:] = value
    if d.vix is not None:
        d.vix[t + 1:] = value
    for a in d.opts.values():
        a[t + 1:] = value
    return d


def spot(day: Day, t: int) -> float:
    return float(day.ix[t, 3]) if 0 <= t < len(day.ix) else math.nan


def traded_rows(a: np.ndarray, t: int) -> np.ndarray:
    v = a[: t + 1, V]
    return np.nonzero(np.nan_to_num(v) > 0)[0]


def ltp(a: np.ndarray, t: int) -> tuple[float, int]:
    """Last traded close at or before t and its row (-1: none)."""
    r = traded_rows(a, t)
    if not len(r):
        return math.nan, -1
    return float(a[r[-1], C]), int(r[-1])


def atm(day: Day, s: float) -> float:
    st = K.STEP[day.und]
    return math.floor(s / st + 0.5) * st


def leg_price(a, t: int, stale: int = 2) -> float:
    if a is None:
        return math.nan
    p, r = ltp(a, t)
    if r < 0 or r < t - stale:
        return math.nan
    ok = np.nonzero(~np.isnan(a[: t + 1, C]))[0]
    return float(a[ok[-1], C])


def straddle(day: Day, t: int) -> float:
    s = spot(day, t)
    if math.isnan(s):
        return math.nan
    k = atm(day, s)
    return leg_price(day.opts.get((k, "C")), t) + leg_price(day.opts.get((k, "P")), t)


def otm_chain(day: Day, side: int, t: int, band: tuple, spot_band: float = 0.05) -> list[tuple[float, float, int]]:
    """(strike, ltp, row) of OTM options on `side` at row t, nearest the money first (all stored strikes within
    spot_band of spot; ltp may be NaN)."""
    s = spot(day, t)
    if math.isnan(s):
        return []
    r = "C" if side > 0 else "P"
    out = []
    for (k, sd), a in day.opts.items():
        if sd != r or abs(k - s) > s * spot_band:
            continue
        if (side > 0 and k <= s + EPS) or (side < 0 and k >= s - EPS):
            continue
        p, row = ltp(a, t)
        out.append((k, p, row))
    out.sort(key=lambda x: abs(x[0] - s))
    return out


def eligible(day: Day, side: int, t: int, band: tuple) -> list[tuple[float, float]]:
    """OTM (strike, ltp) in the band, traded in the last 5 bars, passing the app's stale-slack rule (no further-OTM
    strike priced above it by more than max(0.10, 25%))."""
    lo, hi = band
    cs = otm_chain(day, side, t, band)
    out = []
    for j, (k, p, row) in enumerate(cs):
        if math.isnan(p) or p < lo - EPS or p > hi + EPS or row < t - (TRADED_BARS - 1):
            continue
        slack = max(0.10, 0.25 * p)
        if any((not math.isnan(q)) and q > p + slack + EPS for _, q, _ in cs[j + 1:]):
            continue
        out.append((k, p))
    return out


# ------------------------------------------------------------------------------------------------ fills and charges
def vol5_lots(a: np.ndarray, r: int, lot: int) -> float:
    v = a[max(r - 5, 0): r, V]
    return float(np.nansum(v)) / max(lot, 1)


def _ticks(v5: float, mult: float) -> float:
    return (1.0 if v5 >= 20 else (2.0 if v5 > 0 else 4.0)) * mult


def buy_px(p: float, v5: float, mult: float = 1.0) -> float:
    return round(float(FILLS.buy(np.array([p]))[0]) + (_ticks(v5, mult) - 1) * TICK, 2)


def sell_px(p: float, v5: float, mult: float = 1.0) -> float:
    return max(round(float(FILLS.sell(np.array([p]))[0]) - (_ticks(v5, mult) - 1) * TICK, 2), 0.0)


def charge(buy: bool, px: float, qty: int, und: str) -> float:
    if px <= 0 or qty <= 0:
        return 0.0
    return float(COSTS.charge(buy, np.array([px]), np.array([qty]), bse=und in K.BSE)[0])


# ------------------------------------------------------------------------------------------------ the held option's path
def stored_range(day: Day) -> dict:
    """Per side, per row: the lowest and highest stored strike (NaN: none). Cached on the day."""
    if getattr(day, "_rng", None) is not None:
        return day._rng
    out = {}
    for s in ("C", "P"):
        lo = np.full(N, np.nan)
        hi = np.full(N, np.nan)
        for (k, sd), a in day.opts.items():
            if sd != s:
                continue
            ok = ~np.isnan(a[:, C])
            lo[ok] = np.fmin(lo[ok], k)
            hi[ok] = np.fmax(hi[ok], k)
        out[s] = (lo, hi)
    day._rng = out
    return out


def path_px(day: Day, k: float, side: str, r: int, oor_zero: bool = False) -> tuple[float, float, float, bool]:
    """(open, high, close, proxy) of the held option at row r. A row where the strike is outside the stored range gets
    the proxy (PREREG.md); a row with no bar inside the range is NaN (no trade that minute)."""
    a = day.opts[(k, side)]
    if not math.isnan(a[r, C]):
        return float(a[r, O]), float(a[r, H]), float(a[r, C]), False
    lo, hi = stored_range(day)[side]
    if math.isnan(lo[r]):
        return math.nan, math.nan, math.nan, False
    s = spot(day, r)
    further_otm = (side == "C" and k > hi[r]) or (side == "P" and k < lo[r])
    deep_itm = (side == "C" and k < lo[r]) or (side == "P" and k > hi[r])
    if further_otm:
        e = day.edge[r, 0 if side == "C" else 1] if day.edge is not None else math.nan
        p = 0.0 if oor_zero or math.isnan(e) else float(e)
        return p, p, p, True
    if deep_itm and not math.isnan(s):
        p = max(s - k, 0.0) if side == "C" else max(k - s, 0.0)
        return p, p, p, True
    return math.nan, math.nan, math.nan, False


# ------------------------------------------------------------------------------------------------ trades
@dataclass
class Trade:
    und: str
    day: object
    rule: str
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
    risked: float = 0.0                           # premium paid + buy charges
    peak_mult: float = 0.0
    proxy: bool = False                           # an out-of-range price was used

    @property
    def per_1000(self) -> float:
        return self.net / self.risked * 1000 if self.risked > 0 else 0.0


def enter(day: Day, k: float, side: str, sig_row: int, ref: float, mult: float = 1.0) -> tuple[int, float] | None:
    """The app's entry: LIMIT at the ask capped at ref x 1.2 + 0.10, worked for 3 minutes; never chases."""
    a = day.opts[(k, side)]
    cap = floor_tick(ref * 1.2 + 0.10)
    for r in range(sig_row + 1, min(sig_row + 1 + FILL_TRIES, EXIT_ROW)):
        o = a[r, O]
        if math.isnan(o):
            continue
        v5 = vol5_lots(a, r, day.lot)
        ask = buy_px(o, v5, mult)
        if ask <= cap + EPS:
            return r, ask
        lo = a[r, L]
        if not math.isnan(lo) and buy_px(lo, v5, mult) <= cap + EPS:
            return r, cap
    return None


LADDER = ((5.0, 2.5), (10.0, 5.0), (20.0, 10.0))   # (best high reaches m x the fill -> lock: a close <= l x sells)


def simulate(day: Day, k: float, sgn: int, sig_row: int, ref: float, budget: float | None, exits: str,
             rule: str = "", mult: float = 1.0, oor_zero: bool = False) -> Trade | None:
    """One ticket. budget None = exactly 1 lot. exits: hold | half3 | half5 | ladder | trail | app."""
    side = "C" if sgn > 0 else "P"
    f = enter(day, k, side, sig_row, ref, mult)
    if f is None:
        return None
    fr, e = f
    if day.lot <= 0:
        return None
    lots = 1 if budget is None else int(math.floor(budget / (e * day.lot) + 1e-9))
    if lots <= 0:
        return None
    tr = Trade(day.und, day.day, rule, sgn, k, sig_row, fr, e, lots, lots * day.lot)
    a = day.opts[(k, side)]
    held = lots
    first_t = {"half3": 3.0, "half5": 5.0, "ladder": 3.0, "app": 5.0}.get(exits)
    t1 = floor_tick(first_t * e) if first_t else math.inf
    t2 = floor_tick(20 * e) if exits == "app" else math.inf
    half_sold = False
    best_hi, best_c = e, e
    lock = -math.inf
    trail_armed = False
    peak = e

    def sell(r, n, px, why):
        tr.exits.append((r, n * day.lot, px, why))

    def half(n):
        return n if n == 1 else min(n, max(1, int(round(n * 0.5))))

    r = fr
    while r < EXIT_ROW and held > 0:
        o, hi, c, px = path_px(day, k, side, r, oor_zero)
        tr.proxy |= px
        if not math.isnan(hi):
            peak = max(peak, hi)
            conf = confirmed(a, r)
            if not half_sold and hi >= t1 + TICK - EPS and conf >= t1 - EPS and not px:
                n = half(held)
                sell(r, n, t1, f"{first_t:g}x")
                held -= n
                half_sold = True
            if half_sold and held > 0 and hi >= t2 + TICK - EPS and conf >= t2 - EPS and not px:
                sell(r, held, t2, "20x")
                held = 0
                break
        why = None
        if r > fr and r <= LAST_STOP_ROW and not math.isnan(c):
            if exits == "app" and c <= 0.4 * e + EPS:
                why = "stop"
            if exits == "ladder" and c <= lock + EPS:
                why = "lock"
            if exits == "trail" and trail_armed and c <= e + 0.5 * (best_c - e) + EPS:
                why = "trail"
        if not math.isnan(hi):
            best_hi = max(best_hi, hi)
        if not math.isnan(c):
            best_c = max(best_c, c)
        if exits == "ladder":                      # takes effect from the next minute's close
            for m, l in LADDER:
                if best_hi >= m * e - EPS:
                    lock = max(lock, l * e)
        if exits == "trail" and best_c >= 1.5 * e - EPS:   # open profit >= Rs 500 per Rs 1,000
            trail_armed = True
        if why:
            x = _next_open(day, k, side, r + 1, oor_zero)
            if x is not None:
                rr, oo, pxx = x
                tr.proxy |= pxx
                sell(rr, held, sell_px(oo, vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), why)
                held = 0
                break
        r += 1
    if held > 0:
        x = _next_open(day, k, side, EXIT_ROW, oor_zero)
        if x is None:
            p_, row = ltp(a, EXIT_ROW - 1)
            rr, oo, pxx = row, (0.0 if math.isnan(p_) else p_), False
        else:
            rr, oo, pxx = x
        tr.proxy |= pxx
        sell(rr, held, sell_px(oo, vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), "1505")
    tr.peak_mult = peak / e
    _settle(tr)
    return tr


def confirmed(a: np.ndarray, r: int) -> float:
    """The price the bar confirms: max(its close, the next bar's open). A target fills only when the high trades a tick
    through it AND this is at or above it, so a single freak print (seen in thin SENSEX strikes) fills nothing."""
    c = a[r, C]
    nx = a[r + 1, O] if r + 1 < N else math.nan
    return float(np.nanmax([c, nx])) if not (math.isnan(c) and math.isnan(nx)) else math.nan


def _next_open(day: Day, k: float, side: str, r: int, oor_zero: bool):
    for rr in range(r, N):
        o, _, _, px = path_px(day, k, side, rr, oor_zero)
        if not math.isnan(o):
            return rr, o, px
    return None


def _settle(tr: Trade) -> None:
    buy = tr.entry * tr.qty
    ch = charge(True, tr.entry, tr.qty, tr.und)
    tr.risked = buy + ch
    sold = 0.0
    for _, q, px, _ in tr.exits:
        if px <= 0:                       # worthless: no sell order, no charges
            continue
        sold += px * q
        ch += charge(False, px, q, tr.und)
    tr.gross = round(sold - buy, 2)
    tr.charges = round(ch, 2)
    tr.net = round(tr.gross - ch, 2)
