"""Big-bar pullback (research/COMBINED.md, the Solo setup): a 15-minute candle (start 09:15-14:15) whose body is in the
top `top` share of the previous 20 days' 15-minute bodies (optionally also WIDE: range >= `wide` x the trailing average
range of the previous 20 candles); within 60 minutes a 5-minute close pulls back >= `depth` of its range toward its
low (green; high for red) without breaking it -> buy the ATM option the candle's way at the next minute's open.
Index stop: the candle's low / high (touched). Index target: `k` x that risk from the entry. At most 2 a day.

Cross-check: COMBINED.md 'big candle only (no width rule), pullback 40%' on BANKNIFTY 2025-02-17..2026-02-23
(285 trades, Rs +7,468 with 0.5 slippage, Rs 40 a trip, lot 30, out at the option's close of the exit minute).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from .base import Strategy
from .common import candles, day_arrays

CUT = 15 * 60 + 10 - C.OPEN_M


def signals(mk, top=0.2, wide=None, depth=0.4, k=2.0, lot=None, unds=("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"),
            since=None, until=None):
    out = []
    for und in unds:
        ix = mk.index(und)
        hist = []          # per day: list of |body| and ranges of its 15-min candles
        for d in ix.days:
            o, h, l, c = day_arrays(ix, d)
            c15 = candles(o, h, l, c, 15)
            prev = [x for dd in hist[-20:] for x in dd[0]]
            seq = [x for dd in hist[-20:] for x in dd[1]][-20:]
            hist.append(([abs(x[5] - x[2]) for x in c15], [x[3] - x[4] for x in c15]))
            if (since and str(d) < since) or (until and str(d) > until) or len(prev) < 100 or len(seq) < 20:
                continue
            if not ix.d[d]["real"]:
                continue
            big = np.quantile(prev, 1 - top)
            atr = float(np.mean(seq))
            for (s, e, co, ch_, cl_, cc) in c15:
                rng = ch_ - cl_
                body = cc - co
                ok_big = abs(body) >= big and (wide is None or rng >= wide * atr)
                atr = atr + (rng - atr) / 20
                if s > 300 or not ok_big or body == 0:
                    continue
                sign = 1 if body > 0 else -1
                lvl = cl_ if sign > 0 else ch_
                for m in range(e, min(e + 60, 330)):
                    if (sign > 0 and l[m] <= lvl) or (sign < 0 and h[m] >= lvl):
                        break
                    if m % 5 != 4 or np.isnan(c[m]):
                        continue
                    pulled = (cc - c[m]) >= depth * rng if sign > 0 else (c[m] - cc) >= depth * rng
                    if not pulled:
                        continue
                    if m + 1 >= CUT:
                        break
                    e_ix = c[m]
                    risk = abs(e_ix - lvl)
                    out.append(dict(und=und, day=d, sig_min=m + C.OPEN_M, side=sign, book=f"bigbar_{und}", ref_spot=e_ix,
                                    idx_stop=lvl + sign * 0.001, idx_target=e_ix + sign * k * risk,
                                    lot=lot if lot else np.nan, tag=f"risk={risk:.1f}"))
                    break
    return pd.DataFrame(out)


SQ = 15 * 60 + 10

STRATEGIES = [
    Strategy(
        name="bigbar_pullback",
        family="pullback",
        signal_fn=signals,
        sig_grid=[dict(top=0.2, depth=0.4, k=2.0)],
        rules=[StrikeRule(0)],
        exe=Execution(expiry="skip"),
        exits=[Exits(sq_off=SQ)],
        pos=dict(one_at_a_time=True, max_per_day=2),
        window=(9 * 60 + 30, 15 * 60),
        doc="Big 15-min candle, 40% pullback on a 5-min close, ATM, index stop at the candle level, target 2R, max 2 a day.",
    ),
    Strategy(
        name="bigbar_grid",
        family="pullback",
        signal_fn=signals,
        sig_grid=[dict(top=t, depth=dp, k=k, wide=w) for t in (0.2, 0.3) for dp in (0.25, 0.4) for k in (1.5, 2.0, 3.0)
                  for w in (None, 1.2)],
        rules=[StrikeRule(0), StrikeRule(1)],
        exe=Execution(expiry="skip"),
        exits=[Exits(sq_off=SQ), Exits(stop_pct=0.3, sq_off=SQ), Exits(stop_pct=0.2, time_stop=30, sq_off=SQ),
               Exits(stop_pct=0.3, pine_trail=True, sq_off=SQ)],
        pos=dict(one_at_a_time=True, max_per_day=2),
        window=(9 * 60 + 30, 15 * 60),
        doc="Big-bar pullback grid: body top 20/30%, pullback 25/40%, target 1.5/2/3R, wide filter, ATM/ITM1, 4 exit sets.",
    ),
]
