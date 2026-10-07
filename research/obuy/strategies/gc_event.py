"""Catalog family event (VOL-05, VOL-06, VOL-07 tested; VOL-08 skipped: no stock options in the data).

Event dates: gc_common.EVENTS (hand-built, see there). 'Event session' = the session the news first hits: the day itself
for Budget (11:00) / RBI (10:00) / election counting on a trading day (from 08:00, i.e. known through the morning);
the next session for US FOMC decisions (~midnight IST), weekend election results and heavyweight results.

VOL-05 gc_ev05_runup   Pre-event IV run-up: buy the nearest-MONTHLY ATM straddle / 1-OTM / 2-OTM strangle at 09:20,
                       5 / 4 / 3 sessions before a macro event session, sell at 15:20 one / two sessions before it.
                       The monthly must expire on or after the event session (else skipped: the data has no next
                       month). Exits: time only, or -20% on the combined premium. Positional (multi-day).
VOL-06 gc_ev06_eventday Event-day straddle held through the announcement (Budget, RBI, election counting on trading
                       days): buy the nearest-expiry ATM straddle / 1-OTM / 2-OTM strangle at 09:20 or 30 minutes before
                       the announcement (09:16 for counting days), out 30 / 60 minutes after it (counting: after
                       09:15) or at 15:15; optional -30% / +50% combined. ('expected > implied move' filter not
                       modelled.)
VOL-07 gc_ev07_post    Post-event directional: on each event session (macro, or macro + heavyweight results), the
                       range of the first 15 / 30 / 60 minutes after the announcement (after 09:15 for overnight news);
                       the first 5-min close beyond it (until 14:30) buys ATM / ITM1 that way; stop at the range's other
                       side, 1.5R / 2R target or 2R + trail; optional 'IV crushed' filter: ATM IV at the signal below
                       the pre-event ATM IV (1 minute before the announcement, or the previous session's 15:20).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from ..multiday import MD_SQ, contract_expiry
from .base import Strategy
from .common import day_arrays
from .gc_common import atm, events, nth_session

U2 = ("NIFTY", "BANKNIFTY")


# ------------------------------------------------------------------------------------------------ VOL-05
def runup_signals(mk, lead=5, xlag=1, unds=U2):
    out = []
    for und in unds:
        ix = mk.index(und)
        ev = events(ix, "macro")
        for r in ev.itertuples(index=False):
            d = nth_session(ix, r.day, -lead)
            xd = nth_session(ix, r.day, -xlag)
            if d is None or xd is None or xd <= d:
                continue
            mexp = contract_expiry(ix, d, "month")
            if mexp is None or mexp < r.day:
                continue
            out.append(dict(und=und, day=d, sig_min=9 * 60 + 19, side=0, book=f"run_{und}", exit_day=xd,
                            exit_min=15 * 60 + 20, tag=f"{r.kind}:{r.day}"))
    return pd.DataFrame(out)


# ------------------------------------------------------------------------------------------------ VOL-06
def eventday_signals(mk, entry="open", xafter=30, unds=U2):
    out = []
    for und in unds:
        ix = mk.index(und)
        ev = events(ix, "macro")
        for r in ev.itertuples(index=False):
            if r.kind not in ("budget", "rbi", "election") or r.day != r.src:
                continue                       # only news released on that trading day
            ann = r.ann_min
            if entry == "open":
                sm = 9 * 60 + 19
            else:
                sm = max(ann - 30, C.OPEN_M + 1) - 1
            if sm + 1 >= ann and r.intraday:
                continue
            xat = ann + xafter if xafter else np.nan
            out.append(dict(und=und, day=r.day, sig_min=sm, side=0, book=f"evd_{und}", exit_at=xat,
                            tag=f"{r.kind}"))
    return pd.DataFrame(out)


SQ15 = 15 * 60 + 15


# ------------------------------------------------------------------------------------------------ VOL-07
def _atm_iv(ch, spot, col, step):
    i = ch.kpos(atm(spot, step))
    if i < 0:
        return np.nan
    v = []
    for r in ("C", "P"):
        x = ch.iv[r][i, max(col - 4, 0): col + 1]
        x = x[np.isfinite(x) & (x > 0)]
        if len(x):
            v.append(x[-1])
    return float(np.mean(v)) if v else np.nan


def post_signals(mk, wait=30, ivf=False, kinds="macro", tf=5, cutoff=14 * 60 + 30, unds=U2):
    out = []
    for und in unds:
        ix = mk.index(und)
        opts = mk.options(und)
        step = C.STEP[und]
        ev = events(ix, kinds)
        for r in ev.itertuples(index=False):
            d = r.day
            o, h, l, c = day_arrays(ix, d)
            a = r.ann_min - C.OPEN_M
            e = a + wait
            if e >= cutoff - C.OPEN_M or np.isnan(c[a:e]).all():
                continue
            hi, lo = np.nanmax(h[a:e]), np.nanmin(l[a:e])
            iv0 = np.nan
            ch = None
            if ivf:
                ch = opts.chain(d, "near")
                if ch is None:
                    continue
                if r.intraday:
                    iv0 = _atm_iv(ch, c[a - 1], a - 1, step) if np.isfinite(c[a - 1]) else np.nan
                else:
                    pd_ = nth_session(ix, d, -1)
                    if pd_ is None:
                        continue
                    chp = opts.chain(pd_, "near")
                    _, _, _, cp = day_arrays(ix, pd_)
                    col = 15 * 60 + 20 - C.OPEN_M
                    iv0 = _atm_iv(chp, cp[col], col, step) if chp is not None and np.isfinite(cp[col]) else np.nan
                if not np.isfinite(iv0):
                    continue
            first = e - 1
            first += (tf - 1 - first % tf) % tf          # first tf-candle close at / after the range's end
            for col in range(first, cutoff - C.OPEN_M, tf):
                x = c[col]
                if np.isnan(x):
                    continue
                side = 1 if x > hi else (-1 if x < lo else 0)
                if side == 0:
                    continue
                if ivf:
                    ivn = _atm_iv(ch, x, col, step)
                    if not (np.isfinite(ivn) and ivn < iv0):
                        break
                out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"post_{und}",
                                idx_stop=lo if side > 0 else hi, tag=r.kind))
                break
        mk.release(und)
    return pd.DataFrame(out)


STRATEGIES = [
    Strategy(
        name="gc_ev05_runup", family="event", signal_fn=runup_signals,
        sig_grid=[dict(lead=a, xlag=x) for a in (5, 4, 3) for x in (1, 2)],
        rules=[StrikeRule(0, series="month"), StrikeRule(-1, series="month"), StrikeRule(-2, series="month")],
        exe=Execution(expiry="allow"),
        exits=[Exits(sq_off=MD_SQ, intrabar=False), Exits(stop_pct=0.2, sq_off=MD_SQ, intrabar=False)],
        pos=dict(one_at_a_time=True), window=(9 * 60 + 16, 10 * 60),
        doc="VOL-05 pre-event IV run-up: monthly straddle/strangle bought T-5..T-3, sold T-1/T-2."),
    Strategy(
        name="gc_ev06_eventday", family="event", signal_fn=eventday_signals,
        sig_grid=[dict(entry=e, xafter=x) for e in ("open", "pre30") for x in (30, 60, None)],
        rules=[StrikeRule(0), StrikeRule(-1), StrikeRule(-2)], exe=Execution(expiry="allow"),
        exits=[Exits(sq_off=SQ15, intrabar=False), Exits(stop_pct=0.3, tgt_pct=0.5, sq_off=SQ15, intrabar=False)],
        pos=dict(one_at_a_time=True), window=(9 * 60 + 16, 10 * 60 + 30),
        doc="VOL-06 event-day straddle/strangle through Budget / RBI / counting."),
    Strategy(
        name="gc_ev07_post", family="event", signal_fn=post_signals,
        sig_grid=[dict(wait=w, ivf=f, kinds=k) for w in (15, 30, 60) for f in (False, True) for k in ("macro", "all")],
        rules=[StrikeRule(0), StrikeRule(1)], exe=Execution(expiry="allow"),
        exits=[Exits(idx_tgt_r=2.0, sq_off=SQ15), Exits(idx_tgt_r=1.5, sq_off=SQ15),
               Exits(idx_tgt_r=2.0, pine_trail=True, sq_off=SQ15)],
        pos=dict(one_at_a_time=True, max_per_day=1), window=(9 * 60 + 30, 14 * 60 + 30),
        doc="VOL-07 post-event range breakout (after the IV crush)."),
]
