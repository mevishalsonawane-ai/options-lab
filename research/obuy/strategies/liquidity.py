"""Liquidity 15+5 (the app's arm): pool-on-swing liquidity breaks, BANKNIFTY 15-min + 5-min, FINNIFTY 30-min + 5-min.

Signals are read from liquidity_exits_3060.py's cache (scratchpad liqx/liqx_<U>.pkl: a line-by-line port of the Kotlin
replay that is trade-for-trade equal to the app); this module turns them into obuy signals with the arm's structural
exits as signal columns: idx_stop = the broken level -/+ the index stop (30 BANKNIFTY / 15 FINNIFTY), idx_target = the
next liquidity level, exit_at = the first failed-break / new-level bar end. One strike in the money, expiry days
skipped, one position per book, entries 09:20-14:00, the room filter (>= `room` index stops to the next level).
"""
from __future__ import annotations

import os
import pickle

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from .base import Strategy

IDX_STOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0}
WIN_FROM, WIN_TO = 9 * 60 + 20, 14 * 60


def signals(mk, room=1.0, unds=("BANKNIFTY", "FINNIFTY")):
    rows = []
    for und in unds:
        with open(os.path.join(C.LIQX, f"liqx_{und}.pkl"), "rb") as f:
            recs = pickle.load(f)
        istop = IDX_STOP[und]
        for r in recs:
            for book, sigs in r["books"].items():
                for s in sigs:
                    done = s["done"]
                    if done > 930 or done < WIN_FROM or done > WIN_TO:
                        continue
                    side, level, target = s["side"], s["level"], s["target"]
                    if target is not None and side * (target - s["close"]) < room * istop:
                        continue
                    xa = [x for x in (s["fb"], s["nl"]) if x is not None]
                    rows.append(dict(und=und, day=r["day"], sig_min=done - 1, gate=done, side=side, book=book,
                                     ref_spot=float(s["close"]), strike=s["strike"], idx_stop=level - side * istop,
                                     idx_target=np.nan if target is None else float(target),
                                     exit_at=float(min(xa)) if xa else np.nan, lot=r["lot"], tag=f"lvl={level:.2f}"))
    return pd.DataFrame(rows)


ARM_EXITS = Exits(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=15 * 60 + 10)

STRATEGIES = [
    Strategy(
        name="liquidity15_5",
        family="level-break",
        signal_fn=signals,
        sig_grid=[dict(room=1.0)],
        rules=[StrikeRule(money=1)],
        exe=Execution(expiry="skip"),
        exits=[ARM_EXITS],
        window=(WIN_FROM - 1, WIN_TO - 1),
        doc="The app's Liquidity 15+5 arm (validation: Rs +242,421 over 1,651 trades).",
    ),
]
