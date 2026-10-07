"""Catalog family 'time_of_day' (research/option_buying_catalog.json TD-01 .. TD-03). Expiry days skipped."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from .base import Strategy
from .common import day_arrays
from .ga_common import UNDS2, day_open, first_break, last_valid, twap

M = lambda h, m: h * 60 + m  # noqa: E731


# ------------------------------------------------------------------------------------------------ TD-01 2:50 candle
def td01_signals(mk, start=M(14, 50), pe=True, unds=("NIFTY",)):
    """The 5-min candle from `start`: the next 5-min candle's first 1-min high above its high buys a call (with pe: a
    1-min low below its low buys a put). Signal at that minute's close, fill at the next minute's open."""
    out = []
    for und in unds:
        ix = mk.index(und)
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            s = start - C.OPEN_M
            if np.isnan(c[s:s + 5]).sum() > 1:
                continue
            hi, lo = np.nanmax(h[s:s + 5]), np.nanmin(l[s:s + 5])
            col, side = first_break(h, l, c, hi, lo, s + 5, s + 10, trigger="touch", sides=(1, -1) if pe else (1,))
            if side:
                out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"c250_{und}"))
    return pd.DataFrame(out)


TD01_GRID = [dict(start=s, pe=p) for s in (M(14, 45), M(14, 50)) for p in (False, True)]


# ------------------------------------------------------------------------------------------------ TD-02 Gao et al.
def td02_signals(mk, r1="gap", at=M(15, 0), thr=0.0, vix=False, unds=UNDS2):
    """r1 = return to 09:45 from the previous close ('gap') or from the day's open ('open'). At `at` buy a call if
    r1 > thr% (a put if r1 < -thr%). vix: only when yesterday's India VIX is above its median of the 60 days before."""
    vp = mk.vix.daily.close
    vprev = vp.shift(1)
    vmed = vp.shift(1).rolling(60).median()
    vok = (vprev > vmed).to_dict()
    out = []
    for und in unds:
        ix = mk.index(und)
        pcl = ix.daily().close.shift(1).to_dict()
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            if vix and not vok.get(d, False):
                continue
            o, h, l, c = day_arrays(ix, d)
            x945 = last_valid(c, M(9, 44) - C.OPEN_M)
            base = pcl.get(d) if r1 == "gap" else day_open(o, c)
            if base is None or not np.isfinite(base) or not np.isfinite(x945):
                continue
            r = (x945 / base - 1) * 100
            if abs(r) <= thr or r == 0:
                continue
            out.append(dict(und=und, day=d, sig_min=at - 1, side=1 if r > 0 else -1, book=f"gao_{und}", tag=f"r1={r:.2f}"))
    return pd.DataFrame(out)


TD02_GRID = [dict(r1=r, at=a, thr=t, vix=v) for r in ("gap", "open") for a in (M(14, 45), M(15, 0))
             for t in (0.0, 0.25, 0.5) for v in (False, True)]


# ------------------------------------------------------------------------------------------------ TD-03 last-30-min OTM
def td03_signals(mk, rule="open", at=M(14, 45), unds=("BANKNIFTY", "NIFTY")):
    """At `at` buy an OTM option in the day's trend: index vs the day's open ('open'), the previous close ('prev') or the
    time-weighted VWAP ('twap'; the index has no volume)."""
    out = []
    for und in unds:
        ix = mk.index(und)
        pcl = ix.daily().close.shift(1).to_dict()
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            col = at - 1 - C.OPEN_M
            x = last_valid(c, col)
            ref = {"open": day_open(o, c), "prev": pcl.get(d, np.nan), "twap": twap(h, l, c)[col]}[rule]
            if not (np.isfinite(x) and ref is not None and np.isfinite(ref)) or x == ref:
                continue
            out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=1 if x > ref else -1, book=f"jack_{und}"))
    return pd.DataFrame(out)


TD03_GRID = [dict(rule=r) for r in ("open", "prev", "twap")]

EXE = Execution(expiry="skip")

STRATEGIES = [
    Strategy(name="ga_td01_c250", family="time_of_day", signal_fn=td01_signals, sig_grid=TD01_GRID,
             rules=[StrikeRule(1), StrikeRule(2), StrikeRule(0)],
             exits=[Exits(idx_stop_pts=s, idx_tgt_pts=t, sq_off=M(15, 15)) for s, t in ((15, 20), (10, 20), (20, 30), (30, 60))]
             + [Exits(sq_off=M(15, 15))], exe=EXE, window=(M(14, 50), M(15, 4)),
             doc="TD-01 NIFTY 2:50 (or 2:45) 5-min candle high break (PE mirror optional), ITM, index SL/TP points, out 15:15."),
    Strategy(name="ga_td02_gao", family="time_of_day", signal_fn=td02_signals, sig_grid=TD02_GRID,
             rules=[StrikeRule(0), StrikeRule(1)], exits=[Exits(sq_off=M(15, 25)), Exits(stop_pct=0.3, sq_off=M(15, 25))],
             exe=EXE, window=(M(14, 44), M(14, 59)),
             doc="TD-02 first-half-hour return (gap incl./excl.) sets the side at 14:45/15:00, |r1| threshold, VIX filter, "
                 "out 15:25."),
    Strategy(name="ga_td03_jackpot", family="time_of_day", signal_fn=td03_signals, sig_grid=TD03_GRID,
             rules=[StrikeRule(-1), StrikeRule(-2)],
             exits=[Exits(stop_pct=s, tgt_pct=1.0, sq_off=q) for s in (None, 0.3, 0.5) for q in (M(15, 15), M(15, 25))],
             exe=EXE, window=(M(14, 44), M(14, 44)),
             doc="TD-03 14:45 OTM1/OTM2 in the day's trend (vs open / prev close / TWAP), target 2x, stop none/30/50%, "
                 "out 15:15/15:25."),
]
