"""R97 intraday option-buy engine (PREREG.md s4). Reuses R96's pricing, fills, marks and costs (research/hunt/
r96_mcx_buy/engine.py); adds an exit on the UNDERLYING trail (the future comes back X points from its best close
since entry), next to R96's premium hard stop and profit-lock ladder.

One held contract from its fill, minute by minute (conservative order, as R96):
  1. premium stop / lock on the minute's low (level = max(hard stop, lock); out at the level, or the open if the
     minute opened below it);
  2. underlying checks on the future's minute close: the stop level (the swing's start, a fixed number of points
     beyond it), then the trail (back X from the best close since entry): out at the option's exit price that minute;
  3. peak / lock update (R96 ladder: +30% -> breakeven, +60% -> +30%, +100% -> +60%, trail peak - 40 pts of fill);
  4. time exit at the rule's clock (or the square-off, close - 15 min).
Exit price: the printed close, else the next print's open within 10 minutes the same session, else the Black-76 mark
when out of the stored window (2x half-spread, counted as a model exit), else the last print (stale, counted).
"""
from __future__ import annotations

import numpy as np

from research.hunt.r96_mcx_buy import cfg as K96
from research.hunt.r96_mcx_buy import engine as E


def lock_pts_update(lock: float, peak: float, fill: float, be: float, ladder_pts, trail_gap_pts: float) -> float:
    """Points-based profit lock on the premium (PREREG s4): ladder_pts = ((gain, keep), ...) in option points above
    the fill (keep NaN = breakeven: fill + round-trip costs); above the last step, a trail at peak - trail_gap_pts."""
    g = peak - fill
    for trig, keep in ladder_pts:
        if g >= trig - 1e-9:
            lv = be if not np.isfinite(keep) else fill + keep
            lock = max(lock, lv)
    if ladder_pts and g >= ladder_pts[-1][0] - 1e-9:
        lock = max(lock, peak - trail_gap_pts)
    return lock


def walk(o, h, l, c, mdl, F, side: int, fill: float, be: float, ustop: float, trail_x: float | None,
         stop_frac: float = K96.PREM_STOP, ladder=K96.LADDER, trail_from: float = K96.TRAIL_FROM,
         trail_gap: float = K96.TRAIL_GAP, ladder_pts=None, trail_gap_pts: float = 0.0):
    """Relative minute 0 = the fill minute. Returns (k, why, price or None, model?). With ladder_pts the lock is the
    points ladder (lock_pts_update) instead of R96's fraction ladder."""
    lvl_stop = fill * (1 - stop_frac)
    lock = -np.inf
    peak = fill
    best = F[0]
    n = len(c)
    for k in range(n):
        if np.isfinite(l[k]):
            op, hi, lo, md = o[k], h[k], l[k], False
        elif np.isfinite(mdl[k]):
            op = hi = lo = mdl[k]
            md = True
        else:
            op = hi = lo = np.nan
            md = False
        if np.isfinite(lo):
            lvl = max(lvl_stop, lock)
            if lo <= lvl:
                px = op if (np.isfinite(op) and op < lvl) else lvl
                return k, ("lock" if lock > lvl_stop else "stop"), float(px), md
        f = F[k]
        if np.isfinite(f):
            if np.isfinite(ustop) and side * (f - ustop) <= 0:
                return k, "ustop", None, False
            if side * (f - best) > 0:
                best = f
            if trail_x is not None and side * (best - f) >= trail_x:
                return k, "utrail", None, False
        if np.isfinite(hi):
            if hi > peak:
                peak = hi
            if ladder_pts is not None:
                lock = lock_pts_update(lock, peak, fill, be, ladder_pts, trail_gap_pts)
            else:
                lock = E._lock_update(lock, peak, fill, be, ladder, trail_from, trail_gap)
    return n - 1, "time", None, False


def sim(book, i_sig: int, side: int, ustop: float, trail_x: float | None, exit_tod: int | None, itm: int = 0,
        fill_wait: int = 5, last_day=None, prem_stop: float = K96.PREM_STOP, ladder_pts=None,
        trail_gap_pts: float = 0.0):
    """Buy one lot `itm` steps in the money (0 ATM, 1 ITM, -1 OTM) after a signal on the future's close at grid
    minute i_sig; filled at the open of the contract's first print within fill_wait minutes after i_sig. Out the same
    session at the latest (exit_tod, capped by the square-off). Returns an R96-style trade dict or None (no trade)."""
    m = book.m
    i_from = i_sig + 1
    if i_from >= len(m.ts) or m.day[i_from] != m.day[i_sig] or m.is_exp[i_from]:
        return None
    if E.sessions_to_expiry(book, i_from) < 0:
        return None
    cp = 0 if side > 0 else 1
    strike = E.pick_strike(m, cp, i_sig, itm)
    if not np.isfinite(strike):
        return None
    fp = E.first_print(book, cp, strike, i_from, fill_wait)
    if fp is None:
        return None
    i_in, fill = fp
    i_end = E.last_exit_ix(book, i_in, None, last_day)
    if i_end < 0:
        return None
    if exit_tod is not None:
        j = m.at_or_before(m.day[i_in], exit_tod)
        if j >= 0:
            i_end = min(i_end, j)
    if i_end <= i_in:
        return None
    o, h, l, c, mdl = E.contract_marks(book, cp, strike, i_in, i_end)
    o[0] = fill
    tod_in = int(m.tod[i_in])
    be = fill + E.opt_cost(book.ck, fill * book.units, fill * book.units, tod_in, tod_in) / book.units
    k, why, px, md = walk(o, h, l, c, mdl, m.F[i_in:i_end + 1], side, fill, be, ustop, trail_x, prem_stop,
                          ladder_pts=ladder_pts, trail_gap_pts=trail_gap_pts)
    stale = False
    if px is None:
        px, md, stale = E.exit_price(o, c, mdl, m.day[i_in:i_end + 1], k)
    if not np.isfinite(px):
        return None
    i_out = i_in + k
    tod_out = int(m.tod[i_out])
    b, s = fill * book.units, px * book.units
    cost = E.opt_cost(book.ck, b, s, tod_in, tod_out, 1.0, md)
    cost2 = E.opt_cost(book.ck, b, s, tod_in, tod_out, 2.0, md)
    t = E._trade_dict(book, i_in, i_out, side, [strike], [fill], [px], why, md, stale, cost, cost2, "single")
    t["risk_rs"] = fill * book.units * prem_stop + cost
    return t
