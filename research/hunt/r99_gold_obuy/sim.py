"""R99 intraday trade engine (PREREG.md s3-4) on R96's GOLDM book: one option bought on a future signal, out on the
future's target / stop, a time exit, the hard premium stop, or the square-off - the same session.

R96's pieces are reused unchanged, so fills, marks and costs match every earlier MCX option study:
- `pick_strike` (stored strikes; ATM = nearest the future; 1-ITM = the next one in the money);
- `first_print` (the OPEN of the first printed minute within the wait);
- `contract_marks` / `exit_price` (printed close, else the next print's open within 10 minutes, else a Black-76 model
  mark for a strike outside the stored window - paying 2x the half-spread - else the last print, counted stale);
- `opt_cost` (Zerodha MCX option charges + a half-spread per fill: 0.30% from 17:00, 0.60% before; x mult).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r96_mcx_buy import cfg as K96
from research.hunt.r96_mcx_buy import engine as E96

FILL_WAIT = 5                     # minutes after the signal minute to find the first print
NO_ENTRY_LAST = 30                # no entry in the last 30 minutes of the session
FIRST_ENTRY_TOD = 9 * 60 + 15     # no entry before 09:15
PREM_STOP = 0.40                  # hard stop: -40% of the fill on the option's printed lows


def entry_end_tod(m, day) -> int:
    """The last minute of day an entry signal may come (close - 30)."""
    return m.session_end(np.datetime64(pd.Timestamp(day).normalize(), "ns")) - NO_ENTRY_LAST


def walk(o, l, F, side: int, ref: float, target: float, stop: float, fill: float):
    """Relative minute 0 = the fill minute. The hard stop on the option's printed low first (out at the level, or at the
    open when the minute opened below it), then the future's target / stop on its minute close (out at the option's exit
    price that minute). Returns (k, why, price or None); None = price it with exit_price at k; k = len - 1: time."""
    lvl = fill * (1 - PREM_STOP)
    n = len(F)
    for k in range(n):
        if np.isfinite(l[k]) and l[k] <= lvl:
            px = o[k] if (np.isfinite(o[k]) and o[k] < lvl) else lvl
            return k, "stop_prem", float(px)
        if np.isfinite(F[k]):
            g = side * (F[k] - ref)
            if g >= target - 1e-9:
                return k, "target", None
            if g <= -stop + 1e-9:
                return k, "stop", None
    return n - 1, "time", None


def trade(book, i_sig: int, side: int, itm: int, target: float, stop: float, hold_min: int | None, last_day=None):
    """Buy one GOLDM option for a signal at grid minute i_sig (its close): side +1 a call, -1 a put; itm 0 ATM / 1 1-ITM.
    Out on the future's target / stop (points from its close at i_sig), after hold_min minutes (None: the square-off),
    the hard premium stop, or the square-off. Returns a trade dict, or a dict with 'unfilled' / 'skip' set."""
    m = book.m
    if i_sig + 1 >= len(m.ts) or m.day[i_sig + 1] != m.day[i_sig]:
        return {"skip": "no next minute"}
    if m.is_exp[i_sig]:
        return {"skip": "expiry day"}
    if E96.sessions_to_expiry(book, i_sig) < 0:
        return {"skip": "after the last expiry"}
    if last_day is not None and pd.Timestamp(m.day[i_sig]) > pd.Timestamp(last_day):
        return {"skip": "after the period"}
    cp = 0 if side > 0 else 1
    strike = E96.pick_strike(m, cp, i_sig, itm)
    if not np.isfinite(strike):
        return {"unfilled": "no strike"}
    fp = E96.first_print(book, cp, strike, i_sig + 1, wait=FILL_WAIT)
    if fp is None:
        return {"unfilled": "no print"}
    i_in, fill = fp
    i_sq = E96.sq_ix(m, m.day[i_in])
    if i_sq <= i_in:
        return {"unfilled": "at the square-off"}
    i_end = i_sq
    if hold_min is not None:
        t_end = m.ts[i_in] + np.timedelta64(int(hold_min), "m")
        i_end = min(i_sq, int(np.searchsorted(m.ts, t_end, side="right")) - 1)
        i_end = max(i_end, i_in)
    o, h, l, c, mdl = E96.contract_marks(book, cp, strike, i_in, i_end)
    o[0] = fill
    ref = float(m.F[i_sig])
    k, why, px = walk(o, l, m.F[i_in:i_end + 1], side, ref, target, stop, fill)
    md = stale = False
    if px is None:
        px, md, stale = E96.exit_price(o, c, mdl, m.day[i_in:i_end + 1], k)
    if not np.isfinite(px):
        return {"unfilled": "no exit price"}
    if why == "time" and i_end == i_sq:
        why = "squareoff"
    i_out = i_in + k
    u = book.units
    tin, tout = int(m.tod[i_in]), int(m.tod[i_out])
    b, s = fill * u, px * u
    gross = (px - fill) * u
    cost = E96.opt_cost(book.ck, b, s, tin, tout, 1.0, md)
    return dict(i_sig=int(i_sig), i_in=int(i_in), i_out=int(i_out), day=pd.Timestamp(m.day[i_in]),
                entry_ts=pd.Timestamp(m.ts[i_in]), exit_ts=pd.Timestamp(m.ts[i_out]), side=int(side), strike=float(strike),
                fill=float(fill), exit=float(px), why=why, model=bool(md), stale=bool(stale), gross=gross, cost=cost,
                net=gross - cost, net2=gross - E96.opt_cost(book.ck, b, s, tin, tout, 2.0, md),
                net05=gross - E96.opt_cost(book.ck, b, s, tin, tout, 0.5, md), prem_rs=b,
                risk_rs=b * PREM_STOP + cost, ref=ref)


def half_spread(tod: int) -> float:
    """R85 strict half-spread for gold (for the reports)."""
    return K96.opt_half("gold", tod)
