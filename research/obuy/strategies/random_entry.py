"""Control: one entry a day per index at a random minute 09:20-14:30, side by coin flip (as jarvis_exits' RND set)."""
from __future__ import annotations

import numpy as np
import pandas as pd

from ..engine import Execution, Exits, StrikeRule
from .base import Strategy
from .solo import C0


def signals(mk, seed=7, lo=9 * 60 + 20, hi=14 * 60 + 30, unds=("NIFTY", "BANKNIFTY", "FINNIFTY")):
    rng = np.random.default_rng(seed)
    out = []
    for und in unds:
        for d in mk.index(und).days:
            out.append(dict(und=und, day=d, sig_min=int(rng.integers(lo, hi + 1)) - 1, side=int(rng.choice((1, -1))),
                            book=f"rnd_{und}"))
    return pd.DataFrame(out)


STRATEGIES = [
    Strategy(name="random_entry", family="control", signal_fn=signals, sig_grid=[dict(seed=7)], rules=[StrikeRule(0)],
             exe=Execution(min_premium=35.0, min_vol_lots=50, expiry="skip"), exits=[C0], pos=dict(one_at_a_time=False),
             window=(9 * 60 + 19, 14 * 60 + 29), doc="Random minute, coin-flip side, Jarvis C0 exits (a control: should fail)."),
]
