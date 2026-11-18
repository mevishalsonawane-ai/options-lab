"""R197 engine (PREREG.md section 2): a two-leg credit spread walked minute by minute with a max-loss stop and a profit
target. Marks, fills, charges and margin are R195's; with no target it reproduces R195's E.run spread (test_r197.py).
The straddle / strangle family uses R196's pair loop with its `target` argument.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r195_option_selling"))
import r195cfg as K  # noqa: E402
import r195engine as E  # noqa: E402


def run_spread(D, und, d, short, long_, decide, expiry, expiry_day, stop=0.5, target=None, mult=1.0, tag=""):
    """short / long_ = (side, strike). Exit both at the next open when the open loss >= stop x max loss, or the open
    profit >= target x credit; else square-off."""
    q, bse = K.LOT[und], und in K.BSE
    L = [E.Leg(short[0], float(short[1]), -1), E.Leg(long_[0], float(long_[1]), 1)]
    er = decide + 1
    sq = K.SQ_ROW_EXPIRY if expiry_day else K.SQ_ROW
    if er >= sq:
        return None
    for l in L:
        if not E.fresh_at(D, l, decide) or E.real_open(D, l, er) is None:
            return None
    entry, ch = [], 0.0
    for l in L:
        p, _ = E.fill(D, l, er, l.sign > 0, d, expiry, q, mult)
        entry.append(p)
        ch += E.charge(p, q, l.sign > 0, d, bse)
    credit = (entry[0] - entry[1]) * q
    max_loss = abs(L[0].k - L[1].k) * q - credit
    n = sq - er + 1
    M = np.empty((2, n))
    stale = 0
    for i, l in enumerate(L):
        M[i], st = E.marks(D, l, er, sq, d, expiry)
        stale += int(st.any())
    pnl = ((entry[0] - M[0]) - (entry[1] - M[1])) * q
    xr, why = sq, "time"
    for j in range(n - 1):
        if pnl[j] <= -stop * max_loss:
            xr, why = er + j + 1, "stop"
            break
        if target is not None and pnl[j] >= target * credit:
            xr, why = er + j + 1, "target"
            break
    xs, modelled = [], 0
    for l in L:
        p, mdl = E.fill(D, l, xr, l.sign < 0, d, expiry, q, mult * (2.0 if why == "stop" else 1.0))
        xs.append(p)
        modelled += int(mdl)
        ch += E.charge(p, q, l.sign < 0, d, bse)
    gross = sum(l.sign * (x - f) for l, f, x in zip(L, entry, xs)) * q
    return dict(und=und, day=d, tag=tag, decide=decide, legs=[(l.side, l.k, l.sign, 0) for l in L], entry=entry,
                exitpx=xs, exits=[xr, xr], why=why, credit=round(credit, 2), max_loss=round(max_loss, 2),
                margin=E.margin(L, q, d, expiry_day), gross=round(gross, 2), charges=round(ch, 2),
                net=round(gross - ch, 2), stale_legs=stale, model_fills=modelled)
