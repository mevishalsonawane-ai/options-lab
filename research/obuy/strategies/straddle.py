"""Long straddle / strangle at a fixed time (the '09:20 straddle buy'): buy the ATM call AND put (strangle: n strikes
OTM each side) at `at`, exits on the PAIR's value on minute closes (stop / target / trail), else the square-off.
Optional filter: India VIX (yesterday's close) below `vix_max`.

Cross-check: research/LONG_VOL.md (BANKNIFTY s0920 held to 15:10, 0.5 slippage a side, Rs 40 a leg, lot 30).
"""
from __future__ import annotations

import pandas as pd

from ..engine import Execution, Exits, StrikeRule
from .base import Strategy
from .common import vix_prev


def signals(mk, at=920, vix_max=None, lot=None, unds=("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"), since=None, until=None):
    """at: entry time as HHMM (920 = 09:20; the fill is that minute's open)."""
    at = (at // 100) * 60 + at % 100
    vp, _ = vix_prev(mk) if vix_max else ({}, None)
    out = []
    for und in unds:
        ix = mk.index(und)
        for d in ix.days:
            if (since and str(d) < since) or (until and str(d) > until):
                continue
            if vix_max and not (vp.get(d, 99) < vix_max):
                continue
            out.append(dict(und=und, day=d, sig_min=at - 1, side=0, book=f"strad_{und}", lot=lot if lot else float("nan")))
    return pd.DataFrame(out)


SQ = 15 * 60 + 10
EXITS = ([Exits(sq_off=SQ, intrabar=False)]
         + [Exits(stop_pct=s, tgt_pct=t, sq_off=SQ, intrabar=False) for s in (0.2, 0.3, 0.4) for t in (0.1, 0.2, 0.3, 0.5)]
         + [Exits(stop_pct=0.3, trail_pct=0.1, trail_arm=0.1, sq_off=SQ, intrabar=False),
            Exits(time_stop=60, time_gain=None, sq_off=SQ, intrabar=False)])

STRATEGIES = [
    Strategy(
        name="straddle920",
        family="long-vol",
        signal_fn=signals,
        sig_grid=[dict(at=920)],
        rules=[StrikeRule(0)],
        exe=Execution(expiry="skip"),
        exits=[Exits(sq_off=SQ, intrabar=False)],
        window=(9 * 60 + 19, 14 * 60),
        doc="09:20 ATM straddle bought, held to 15:10.",
    ),
    Strategy(
        name="straddle_grid",
        family="long-vol",
        signal_fn=signals,
        sig_grid=[dict(at=a, vix_max=v) for a in (920, 1000, 1300) for v in (None, 13.0)],
        rules=[StrikeRule(0), StrikeRule(-1)],
        exe=Execution(expiry="skip"),
        exits=EXITS,
        window=(9 * 60 + 19, 14 * 60),
        doc="Straddle / 1-OTM strangle at 09:20 / 10:00 / 13:00, with / without a low-VIX filter, 15 exit sets.",
    ),
]
