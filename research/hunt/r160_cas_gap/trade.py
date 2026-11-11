"""R160 trade T1 (PREREG 6): buy the ATM option toward the expected correction at 15:28, sell at 09:16 next session.

Prices come from the option's own minute bars (any offset row with the same strike and side). App fills (+-5 bps),
dated charges, a 0.16% half-spread on both legs. One record per night and side, so the rule and its random-side twin
price from the same table.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

from research.obuy.costs import Costs, Fills

from . import cfg as K

COST = Costs("dated")
FILL = Fills("app")


def book(opt_day: pd.DataFrame) -> dict:
    """{(side, strike): 4 x K.N array of open/high/low/close} for one day's option rows (ts naive)."""
    out = {}
    if opt_day.empty:
        return out
    r = (opt_day.ts.dt.hour * 60 + opt_day.ts.dt.minute - (9 * 60 + 15)).to_numpy(int)
    ok = (r >= 0) & (r < K.N)
    x = opt_day.loc[ok].assign(r=r[ok]).drop_duplicates(["side", "strike", "r"], keep="last")
    for (s, k), g in x.groupby(["side", "strike"], observed=True):
        a = np.full((4, K.N), np.nan)
        rr = g.r.to_numpy(int)
        for j, c in enumerate(("open", "high", "low", "close")):
            a[j, rr] = g[c].to_numpy(float)
        out[(str(s), float(k))] = a
    return out


def atm_strike(opt_day: pd.DataFrame, r: int) -> float | None:
    """The offset-0 strike in minute r (or the latest minute before it within 3 minutes)."""
    m = opt_day.ts.dt.hour * 60 + opt_day.ts.dt.minute - (9 * 60 + 15)
    for rr in range(r, r - 4, -1):
        z = opt_day[(m == rr) & (opt_day.offset == 0)]
        if len(z):
            return float(z.strike.iloc[-1])
    return None


def guard(a: np.ndarray, r: int) -> bool:
    """R146's guard: False only for an isolated stray print (open > 20% from its own close AND from the next open)."""
    o, c = a[0, r], a[3, r]
    if not (np.isfinite(o) and o > 0):
        return False
    if np.isfinite(c) and c > 0 and abs(o / c - 1) <= K.FILL_GUARD:
        return True
    nxt = a[0, r + 1: r + 4]
    nxt = nxt[np.isfinite(nxt)]
    return bool(nxt.size and abs(o / nxt[0] - 1) <= K.FILL_GUARD)


@dataclass
class Leg:
    d: date
    side: str
    strike: float
    qty: int
    e: float
    x: float
    x_row: int


def price_leg(d: date, side: str, strike: float, qty: int, bk_d: dict, bk_nd: dict) -> tuple[Leg | None, str]:
    a, nx = bk_d.get((side, strike)), bk_nd.get((side, strike))
    if a is None or nx is None:
        return None, "strike_missing"
    if not guard(a, K.ENTRY_ROW):
        return None, "no_entry_bar"
    for r in range(K.EXIT_ROW, K.EXIT_LAST + 1):
        if np.isfinite(nx[0, r]):
            if not guard(nx, r):
                return None, "fill_guard_exit"
            return Leg(d, side, strike, qty, float(a[0, K.ENTRY_ROW]), float(nx[0, r]), r), ""
    return None, "no_exit_bar"


def net(lg: Leg, spread_mult: float = 1.0) -> float:
    """Rupees after app fills, dated charges and the half-spread on both legs."""
    q = lg.qty
    dn = np.array([(lg.d - date(1970, 1, 1)).days])
    ef = float(FILL.buy(np.array([lg.e]))[0])
    xf = float(FILL.sell(np.array([lg.x]))[0])
    ch = float(COST.charge(True, np.array([ef]), np.array([q]), dn)[0]) + \
        float(COST.charge(False, np.array([xf]), np.array([q]), dn)[0])
    sp = K.HALF_SPREAD * (lg.e + lg.x) * q * spread_mult
    return (xf - ef) * q - ch - sp
