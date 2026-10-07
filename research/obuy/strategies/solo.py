"""Solo midday (SoloMidday): at 12:00, if the index has moved >= k x ATR14 since the open and closes in the outer 25% of
its morning range, buy the ATM option that way at 12:00 (every index that signals). Port of jarvis_exits.solo_entries.

Validation: with Jarvis's C0 exits (30-pt stop, +60 target, ladder on 60, out 15:15, min premium 35, >= 50 lots
traded before the entry) this reproduces research/JARVIS_EXITS.md's SOLO row (623 trades, Rs +34,846).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from ..engine import LADDER, Execution, Exits, StrikeRule
from .base import Strategy


def signals(mk, k_atr=0.5, outer=0.25, at=720, unds=("NIFTY", "BANKNIFTY", "FINNIFTY")):
    out = []
    for und in unds:
        ix = mk.index(und)
        days = ix.days
        tr = []
        prev_c = None
        for d in days:
            x = ix.d[d]
            h, l, c = x["h"].max(), x["l"].min(), x["c"][-1]
            tr.append(h - l if prev_c is None else max(h - l, abs(h - prev_c), abs(l - prev_c)))
            prev_c = c
        for k, d in enumerate(days):
            if k < 14:
                continue
            atr = float(np.mean(tr[k - 14:k]))
            x = ix.d[d]
            m = x["m"]
            pre = m < at
            if pre.sum() < 100 or m[0] > 556 or not (m[pre][-1] == at - 1):
                continue
            o = x["o"][0]
            c = x["c"][pre][-1]
            hi, lo = x["h"][pre].max(), x["l"][pre].min()
            mv = c - o
            if abs(mv) < k_atr * atr:
                continue
            side = 1 if mv > 0 else -1
            pos = (hi - c) / (hi - lo) if side > 0 else (c - lo) / (hi - lo)
            if pos > outer:
                continue
            j = int(np.searchsorted(m, at))
            spot = x["o"][j] if j < len(m) and m[j] == at else c
            out.append(dict(und=und, day=d, sig_min=at - 1, side=side, ref_spot=float(spot), book=f"solo_{und}",
                            tag=f"{abs(mv) / atr:.2f}"))
    return pd.DataFrame(out)


C0 = Exits(stop_pts=30, tgt_pts=60, ladder=LADDER, ladder_ref_pts=60, sq_off=15 * 60 + 15)
JARVIS_EXE = Execution(min_premium=35.0, min_vol_lots=50, expiry="skip")

EXIT_GRID = [C0] + [
    Exits(stop_pct=s, tgt_pct=t, ladder=lad, ladder_ref_pct=t if lad else None, sq_off=15 * 60 + 15)
    for s in (0.15, 0.25, 0.35) for t in (0.3, 0.6, 1.0) for lad in (None, LADDER)
] + [Exits(stop_pct=s, pine_trail=True, sq_off=15 * 60 + 15) for s in (0.2, 0.3)] + [
    Exits(stop_pct=0.3, sq_off=15 * 60 + 15), Exits(sq_off=15 * 60 + 15)]

STRATEGIES = [
    Strategy(
        name="solo_midday",
        family="momentum",
        signal_fn=signals,
        sig_grid=[dict(k_atr=0.5)],
        rules=[StrikeRule(0)],
        exe=JARVIS_EXE,
        exits=[C0],
        pos=dict(one_at_a_time=False),
        window=(9 * 60 + 30, 14 * 60 + 30),
        doc="Solo midday signals bought the Jarvis way (validation: JARVIS_EXITS.md SOLO, C0 = Rs +34,846 / 623 trades).",
    ),
    Strategy(
        name="solo_midday_grid",
        family="momentum",
        signal_fn=signals,
        sig_grid=[dict(k_atr=k, at=a) for k in (0.3, 0.5, 0.8) for a in (660, 720, 780)],
        rules=[StrikeRule(0), StrikeRule(1)],
        exe=JARVIS_EXE,
        exits=EXIT_GRID,
        pos=dict(one_at_a_time=False),
        window=(9 * 60 + 30, 14 * 60 + 30),
        doc="Solo midday: decision time x move threshold x strike x 23 exit sets.",
    ),
]
