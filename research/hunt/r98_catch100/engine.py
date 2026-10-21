"""R98 trade engine (PREREG.md s4). One lot, one session; the future's path is its minute closes.

Future (GOLDM, 1 lot = Rs 10 a point): bought / sold at the close of the minute after the signal (R53's futures fill on
a close-only series). Then, on each later minute close:
  1. stop: the close at or beyond the stop level (entry -100, or the lock level once set) -> out at that CLOSE (a
     market order after the touch, so a gap costs extra);
  2. target: the close at or beyond entry +100 -> out at entry +100 exactly (a resting limit order; no overshoot);
  3. profit lock: once a close has been +70 or better, the stop moves to entry +20;
  4. time: out at the close 60 minutes after entry, or at the square-off (session close - 15 min).
Costs: Zerodha MCX futures charges + one half-spread each fill (labels.fut_cost_pts); gates also at 2x spreads.

Option (R98E): the ATM call / put at the first print within 5 minutes; the same future-based exits decide WHEN; the
option is sold at its exit price that minute (R96 exit_price; model marks counted); premium hard stop -15% on lows.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C53
from research.hunt.r96_mcx_buy import engine as E
from research.hunt.r98_catch100 import cfg as K

TARGET, STOP, HOLD = 100.0, 100.0, 60
LOCK_AT, LOCK_TO = 70.0, 20.0
PREM_STOP = 0.15


def fut_walk(path: np.ndarray, target=TARGET, stop=STOP, lock_at=LOCK_AT, lock_to=LOCK_TO):
    """path: d * (close - entry) for the minutes after the entry minute (k = 0 is the first later close).
    Returns (k, why, pnl_points)."""
    lvl = -stop
    for k, x in enumerate(path):
        if x <= lvl:
            return k, ("lock" if lvl > -stop else "stop"), float(x)
        if x >= target:
            return k, "target", float(target)
        if x >= lock_at and lvl < lock_to:
            lvl = lock_to
    if not len(path):
        return -1, "none", 0.0
    return len(path) - 1, "time", float(path[-1])


def _session_end(m, i):
    return E.sq_ix(m, m.day[i])


def sim_future(book, i_sig: int, d: int, last_day=None):
    m = book.m
    i_in = i_sig + 1
    if i_in >= len(m.ts) or m.day[i_in] != m.day[i_sig]:
        return None
    end = min(_session_end(m, i_in), i_in + HOLD)
    if end <= i_in:
        return None
    e = float(m.F[i_in])
    path = d * (m.F[i_in + 1:end + 1] - e)
    k, why, pts = fut_walk(path)
    if k < 0:
        return None
    i_out = i_in + 1 + k
    notional = e * K.UNITS
    half = C53.fut_half_spread(e, K.TICK, "gold")
    charges = C53.mcx_fut_charges(notional, notional + pts * K.UNITS)
    cost = charges + 2 * half * K.UNITS
    cost2 = charges + 4 * half * K.UNITS
    gross = pts * K.UNITS
    return dict(book=book.name, instrument="future", entry_ts=pd.Timestamp(m.ts[i_in]),
                exit_ts=pd.Timestamp(m.ts[i_out]), day=pd.Timestamp(m.day[i_in]), side=int(d), why=why,
                fill=[e], exit=[e + d * pts], gross=gross, cost=cost, net=gross - cost, net2=gross - cost2,
                model=False, stale=False, prem_rs=0.0, risk_rs=STOP * K.UNITS + cost, kind="future",
                strike=[np.nan])


def sim_option(book, i_sig: int, d: int, last_day=None):
    m = book.m
    if i_sig + 1 >= len(m.ts) or m.day[i_sig + 1] != m.day[i_sig] or m.is_exp[i_sig + 1]:
        return None
    cp = 0 if d > 0 else 1
    k_ = E.pick_strike(m, cp, i_sig, 0)
    if not np.isfinite(k_):
        return None
    fp = E.first_print(book, cp, k_, i_sig + 1, 5)
    if fp is None:
        return None
    i_in, fill = fp
    end = min(_session_end(m, i_in), i_in + HOLD)
    if end <= i_in:
        return None
    o, h, l, c, mdl = E.contract_marks(book, cp, k_, i_in, end)
    o[0] = fill
    e = float(m.F[i_in])
    path = d * (m.F[i_in:end + 1] - e)
    lvl_u = -STOP
    pstop = fill * (1 - PREM_STOP)
    k_out, why, px, md = len(c) - 1, "time", None, False
    for j in range(len(c)):
        if np.isfinite(l[j]) and l[j] <= pstop:
            k_out, why, px = j, "pstop", float(min(pstop, o[j]) if np.isfinite(o[j]) else pstop)
            break
        if j == 0:
            continue
        x = path[j]
        if x <= lvl_u:
            k_out, why = j, ("lock" if lvl_u > -STOP else "stop")
            break
        if x >= TARGET:
            k_out, why = j, "target"
            break
        if x >= LOCK_AT and lvl_u < LOCK_TO:
            lvl_u = LOCK_TO
    stale = False
    if px is None:
        px, md, stale = E.exit_price(o, c, mdl, m.day[i_in:end + 1], k_out)
    if not np.isfinite(px):
        return None
    i_out = i_in + k_out
    u = book.units
    tod_in, tod_out = int(m.tod[i_in]), int(m.tod[i_out])
    cost = E.opt_cost(book.ck, fill * u, px * u, tod_in, tod_out, 1.0, md)
    cost2 = E.opt_cost(book.ck, fill * u, px * u, tod_in, tod_out, 2.0, md)
    gross = (px - fill) * u
    return dict(book=book.name, instrument="option", entry_ts=pd.Timestamp(m.ts[i_in]),
                exit_ts=pd.Timestamp(m.ts[i_out]), day=pd.Timestamp(m.day[i_in]), side=int(d), why=why, fill=[fill],
                exit=[px], gross=gross, cost=cost, net=gross - cost, net2=gross - cost2, model=bool(md),
                stale=bool(stale), prem_rs=fill * u, risk_rs=fill * u * PREM_STOP + cost, kind="option",
                strike=[k_])


def simulate(book, instrument: str, i_sig: int, d: int, last_day=None):
    return (sim_future if instrument == "future" else sim_option)(book, i_sig, d, last_day=last_day)
