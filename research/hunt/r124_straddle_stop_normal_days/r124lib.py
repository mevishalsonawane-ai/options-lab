"""R124 engine (PREREG.md): R123's short ATM straddle with an index stop, on normal (non-expiry) sessions.

Entry and exit fills are R119's (`fill_leg`); a leg with no print at the exit is priced by R122's expiry-aware Black-76
(`r122lib.stale_price`: calendar time to the leg's expiry). The stop is R123's `stop_row` (reads closes after the
decision row only). Grid: row t = the bar that STARTS at 09:15 + t.
"""
from __future__ import annotations

from datetime import date

import r124cfg as K
import r119lib as L19
import r122lib as L122
import r123lib as L123
import r25lib as R25
import r50lib as L50


def straddle(c: L19.DayCtx, t: int, k: float, exp: date, mult: float = 1.0, strat: str = "R124"):
    k0, s = c.k0(t), c.em[t]
    if not (k0 == k0 and s == s and s > 0 and c.sp[t] == c.sp[t]) or c.lot <= 0:
        return None
    x, stopped = L123.stop_row(c.sp, t, k, s, K.ROW_END)
    legs = [L19.Leg(k0, "C", -1), L19.Leg(k0, "P", -1)]
    q, d = c.lot, c.day.day
    tr = L19.Trade(strat, c.und, d, t, x, legs)
    cash, ch = 0.0, 0.0
    for lg in legs:
        f = L19.fill_leg(c, lg, t + 1, False, mult, allow_model=False)
        if f is None or f[0] < K.MIN_PX:
            return None
        cash += f[0] * q
        ch += L50.charge(False, f[0], q, c.und, d)
    for lg in legs:
        g = L19.fill_leg(c, lg, x, True, mult, allow_model=False)
        if g is None:
            p0 = L122.stale_price(c, lg, x, exp)
            tr.stale += 1
            p = R25.buy_px(0.0 if p0 is None else p0, 0.0, mult)
        else:
            p = g[0]
        if p < K.MIN_PX:
            continue
        cash -= p * q
        ch += L50.charge(True, p, q, c.und, d)
    tr.gross, tr.charges = round(cash, 2), round(ch, 2)
    tr.net = round(cash - ch, 2)
    tr.margin = round(K.MARGIN_PCT * 2 * k0 * q, 2)
    tr.extra.update(stopped=stopped, exp=exp)
    return tr
