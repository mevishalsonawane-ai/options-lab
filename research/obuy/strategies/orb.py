"""Opening-range breakout (ORB): the opening range = the first `or_min` minutes' high / low; the first `tf`-minute close
beyond it (by a buffer of `buf` x the range) between the range's end and `cutoff` buys the ATM option that way.
One trade a day per index (the first break). Index stop: the range's middle ('mid') or opposite side ('opp') or none.
Filters: range width between `wmin` and `wmax` x ATR14 (daily, known before the day).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import LADDER, Execution, Exits, StrikeRule
from .base import Strategy
from .common import daily_atr, day_arrays


def signals(mk, or_min=15, tf=5, cutoff=13 * 60, buf=0.0, stop="mid", wmin=0.0, wmax=9.9,
            unds=("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")):
    out = []
    for und in unds:
        ix = mk.index(und)
        atr = daily_atr(ix)
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            if np.isnan(c[:or_min]).sum() > 2:
                continue
            hi, lo = np.nanmax(h[:or_min]), np.nanmin(l[:or_min])
            w = hi - lo
            a = atr.get(d)
            if not (a and a > 0) or not (wmin * a <= w <= wmax * a):
                continue
            up, dn = hi + buf * w, lo - buf * w
            for col in range(or_min + tf - 1, cutoff - C.OPEN_M, tf):
                x = c[col]
                if np.isnan(x):
                    continue
                side = 1 if x > up else (-1 if x < dn else 0)
                if side == 0:
                    continue
                lvl = {"mid": (hi + lo) / 2, "opp": lo if side > 0 else hi}.get(stop, np.nan)
                out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"orb_{und}", idx_stop=lvl,
                                tag=f"w/atr={w / a:.2f}"))
                break
    return pd.DataFrame(out)


SQ = 15 * 60 + 10
EXITS = (
    [Exits(stop_pct=s, tgt_pct=t, sq_off=SQ) for s in (0.2, 0.3) for t in (0.4, 0.8)]
    + [Exits(stop_pct=s, tgt_pct=t, ladder=LADDER, ladder_ref_pct=t, sq_off=SQ) for s in (0.2, 0.3) for t in (0.4, 0.8)]
    + [Exits(stop_pts=40, tgt_pts=40, ladder=LADDER, ladder_ref_pts=40, sq_off=SQ),     # the retired ORB arms' exits
       Exits(stop_pct=0.3, pine_trail=True, sq_off=SQ),
       Exits(stop_pct=0.25, time_stop=30, time_gain=0.05, sq_off=SQ),
       Exits(sq_off=SQ, sig_levels=True)]
)

STRATEGIES = [
    Strategy(
        name="orb15",
        family="breakout",
        signal_fn=signals,
        sig_grid=[dict(or_min=15, tf=5, stop="mid")],
        rules=[StrikeRule(0)],
        exe=Execution(expiry="skip"),
        exits=[Exits(stop_pct=0.3, tgt_pct=0.6, sq_off=SQ)],
        window=(9 * 60 + 30, 13 * 60),
        doc="ORB 15-min breakout on 5-min closes, ATM, index stop at the range middle, -30% / +60%.",
    ),
    Strategy(
        name="orb_grid",
        family="breakout",
        signal_fn=signals,
        sig_grid=[dict(or_min=om, tf=tf, stop=st, cutoff=co) for om in (15, 30) for tf in (1, 5) for st in ("mid", "opp")
                  for co in (11 * 60, 13 * 60)],
        rules=[StrikeRule(0), StrikeRule(1)],
        exe=Execution(expiry="skip"),
        exits=EXITS,
        window=(9 * 60 + 30, 13 * 60),
        doc="ORB grid: range 15/30 min x 1/5-min closes x stop mid/opp x cutoff 11:00/13:00 x ATM/ITM1 x 12 exit sets.",
    ),
]
