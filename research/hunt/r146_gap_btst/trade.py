"""R146 trade pricer (PREREG 6): a 1-ITM option bought at 15:20, sold next morning; Night's contract rules.

Prices come from the option's own minute bars: the entry is the open of the first bar in 15:20-15:22 of d, an exit is
the open of its minute next session (falling forward to 09:45). App fills (+-5 bps, stop -10 bps), app charges and the
0.16% half-spread on both legs. One record per night and side (call and put), so any side rule and the random twin
price from the same table.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np

from research.obuy.costs import Costs, Fills

from . import cfg as K
from .features import atm

COST = Costs("app")
FILL = Fills("app")
EXITS = ("X15", "X16", "X20", "X30", "S30")


@dataclass
class Leg:
    side: int
    strike: float
    qty: int
    e: float              # raw entry open
    ef: float             # entry fill
    x: dict               # exit id -> raw exit price
    xf: dict              # exit id -> exit fill
    why: dict             # exit id -> reason


def tradable(d: date, nd: date, expiry: date | None, expiries: set) -> str:
    """Night's contract rule (NightRules.expiry): '' when tradable, else why not."""
    if d in expiries:
        return "expiry_day"
    if expiry is None:
        return "no_contract"
    if not expiry > nd:
        return "expires_by_next_session"
    return ""


def _guard(a: np.ndarray, r: int) -> bool:
    """False only for an isolated stray print (NOTES A2): the fill bar's open more than 20% from its own close AND
    more than 20% from the next bar's open (within 3 minutes). A real gap persists into the next bar, so it passes."""
    o, c = a[0, r], a[3, r]
    if not (np.isfinite(o) and o > 0):
        return False
    if np.isfinite(c) and c > 0 and abs(o / c - 1) <= K.FILL_GUARD:
        return True
    nxt = a[0, r + 1: r + 4]
    nxt = nxt[np.isfinite(nxt)]
    return bool(nxt.size and abs(o / nxt[0] - 1) <= K.FILL_GUARD)


def _exit_at(nx: np.ndarray, r0: int) -> tuple[float, int]:
    for r in range(r0, K.EXIT_LAST + 1):
        if np.isfinite(nx[0, r]):
            return float(nx[0, r]), r
    return np.nan, -1


def leg(und: str, d: date, side: int, x: float, opt_d: dict, opt_nd: dict) -> tuple[Leg | None, str]:
    """Price one side's trade, every exit. (None, why) when it cannot be priced."""
    step = K.STEP[und]
    k = atm(x, step) - side * step
    key = ("C" if side > 0 else "P", k)
    a, nx = opt_d.get(key), opt_nd.get(key)
    if a is None or nx is None:
        return None, "strike_missing"
    er = next((r for r in K.ENTRY_ROWS if np.isfinite(a[0, r])), None)
    if er is None:
        return None, "no_entry_bar"
    if not _guard(a, er):
        return None, "fill_guard_entry"
    e = float(a[0, er])
    qty = K.lot(und, d)
    ef = float(FILL.buy(e))
    xs, xfs, why = {}, {}, {}
    for xid in ("X15", "X16", "X20", "X30"):
        px, r = _exit_at(nx, K.EXIT_ROW[xid])
        if not np.isfinite(px):
            return None, "no_exit_bar"
        tag = xid if r == K.EXIT_ROW[xid] else f"{xid}_late"
        if not _guard(nx, r):
            # NOTES A3: the 09:15 open is often a stale print; X15 (a comparison exit) then uses that bar's close
            if xid == "X15" and r == K.EXIT_ROW["X15"] and np.isfinite(nx[3, r]) and nx[3, r] > 0:
                px, tag = float(nx[3, r]), "X15_close"
            else:
                return None, "fill_guard_exit"
        xs[xid], xfs[xid], why[xid] = px, float(FILL.sell(px)), tag
    # S30: -30% resting stop, watched 15:21-15:29 of d after the entry bar, and the next 09:15 bar (gap-through first)
    stop = e * (1 - K.STOP_PCT)
    sx, sw = None, None
    lo = a[2, max(er + 1, K.STOP_WATCH_D[0]): K.STOP_WATCH_D[1] + 1]
    if np.any(np.isfinite(lo) & (lo <= stop)):
        sx, sw = stop, "stop_same_day"
    else:
        o0, l0 = xs["X15"], nx[2, K.EXIT_ROW["X15"]]     # the guarded 09:15 price (A3), and the bar's low
        if np.isfinite(o0) and o0 <= stop:
            sx, sw = float(o0), "stop_gap_through"
        elif np.isfinite(l0) and l0 <= stop:
            sx, sw = stop, "stop_0915"
    if sx is None:
        xs["S30"], xfs["S30"], why["S30"] = xs["X16"], xfs["X16"], "X16"
    else:
        xs["S30"], xfs["S30"], why["S30"] = sx, float(FILL.sell(sx, stop=True)), sw
    return Leg(side, k, qty, e, ef, xs, xfs, why), ""


def net(lg: Leg, xid: str, spread_mult: float = 1.0) -> float:
    """Rupees after fills, app charges and the half-spread on both legs (spread on the raw prices, as R27)."""
    q = lg.qty
    x, xf = lg.x[xid], lg.xf[xid]
    sp = K.HALF_SPREAD * (lg.e + x) * q * spread_mult
    return float((xf - lg.ef) * q - COST.charge(True, lg.ef, q) - COST.charge(False, xf, q) - sp)
