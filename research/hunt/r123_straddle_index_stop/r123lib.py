"""R123 engine (PREREG.md): sell the ATM straddle on a bar close, buy it back at the next open after the index's first
1-minute close k straddles from the entry level, else at the 15:05 open. Pricing is R119's `run_trade` (R25 liq
slippage x R54 late multiplier, dated charges, Black-76 for a leg with no print at the exit).
Grid: row t = the bar that STARTS at 09:15 + t; the stop reads closes after the decision row only.
"""
from __future__ import annotations

import r123cfg as K
import r119lib as L19


def stop_row(sp, t: int, k: float, s: float, end: int = K.ROW_END):
    """(exit row, stopped?): the open after the first close in rows t+1 .. end-1 with |index - index[t]| >= k x s."""
    s0 = sp[t]
    for r in range(t + 1, end):
        x = sp[r]
        if x == x and abs(x - s0) >= k * s - 1e-9:
            return r + 1, True
    return end, False


def straddle(ctx: L19.DayCtx, t: int, k: float, mult: float = 1.0, strat: str = "R123"):
    k0, s = ctx.k0(t), ctx.em[t]
    if not (k0 == k0 and s == s and s > 0 and ctx.sp[t] == ctx.sp[t]):
        return None
    x, stopped = stop_row(ctx.sp, t, k, s)
    tr = L19.run_trade(ctx, strat, [L19.Leg(k0, "C", -1), L19.Leg(k0, "P", -1)], t, x, mult)
    if tr is not None:
        tr.extra["stopped"] = stopped
    return tr
