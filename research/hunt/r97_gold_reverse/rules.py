"""R97 step 4: the seven pre-registered rules (PREREG.md s3). Signal source of every rule: the MCX gold future (GOLDM's
near future, minute closes); R97E also reads XAUUSD spot as a filter on the future's own move. Trade: buy one GOLDM
option lot (ATM call / put) the same session, with a hard premium stop and a points profit lock.

Every candidate is built from `features.day_features` rows and `moves.trigger_points`, both causal (they read the
session up to the signal minute, the previous session's levels and context up to the minute's close).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r97_gold_reverse import data as D
from research.hunt.r97_gold_reverse import features as FE
from research.hunt.r97_gold_reverse import moves as M

NAN = float("nan")
# common exits (PREREG s4)
COMMON = dict(itm=0, fill_wait=5, prem_stop=0.15, ladder_pts=((60.0, NAN), (120.0, 60.0)), trail_gap_pts=80.0)
FIRST_TOD, LAST_TOD = 9 * 60 + 15, 22 * 60 + 30


def sessions(book, period, ctx):
    """(day, grid indices, F, tod, features) for every session of the book in the period. ctx: cached context
    (option volume, XAUUSD, SILVER, events). The previous session's levels come from the session before, whatever
    its period."""
    from research.hunt.r97_gold_reverse.step1 import event_minutes
    m = book.m
    prev = None
    evm = ctx.setdefault("evm", event_minutes(D.events()))
    for d in m.days:
        ix, F, tod = D.day_arrays(m, d)
        if D.in_period(d, period) and len(ix) >= 30:
            fe = FE.day_features(m.ts[ix], F, tod, prev, ctx.get("vol"), ctx.get("xau"), ctx.get("ag"),
                                 evm.get(pd.Timestamp(d).date(), []))
            yield d, ix, F, tod, fe
        if len(ix):
            prev = dict(high=float(np.max(F)), low=float(np.min(F)), close=float(F[-1]))


def context(book) -> dict:
    ag = D.load_silver_F()
    ag = pd.Series(ag.values, index=ag.index + pd.Timedelta(minutes=1))
    return dict(vol=D.option_volume(book.name), xau=D.load_xau(), ag=ag)


def spec(d, ix, F, tod, j, side, stop_pts, trail_x, exit_tod=None):
    return dict(day=np.datetime64(pd.Timestamp(d).normalize(), "ns"), i_sig=int(ix[j]), side=int(side),
                ustop=float(F[j] - side * stop_pts), stop_pts=float(stop_pts), trail_x=trail_x, exit_tod=exit_tod,
                tod=int(tod[j]), **COMMON)


class TriggerRule:
    """A T-point trigger (price T off its running extreme, read in real time) with a filter on the trigger minute."""
    books = ("GOLDM",)
    T, TRAIL, max_per_day = 100.0, 300.0, 2
    window = (FIRST_TOD, LAST_TOD)

    def keep(self, fe, j, d) -> bool:
        return True

    def candidates(self, book, period, ctx=None):
        ctx = context(book) if ctx is None else ctx
        out = []
        for day, ix, F, tod, fe in sessions(book, period, ctx):
            for j, side, pivot in M.trigger_points(F, self.T):
                if not (self.window[0] <= tod[j] <= self.window[1]):
                    continue
                if not self.keep(fe, j, side):
                    continue
                stop = max(side * (F[j] - pivot), 1.0)
                out.append(spec(day, ix, F, tod, j, side, stop, self.TRAIL))
        return out


class MorningExtreme(TriggerRule):
    """R97A: 09:15-12:00, the trigger is also a new high (long) / low (short) of the session."""
    rid, max_per_day = "R97A", 1
    window = (FIRST_TOD, 12 * 60)

    def keep(self, fe, j, d):
        return FE.directional(fe, j, d)["new_day_ext"]


class AllDayExtreme(TriggerRule):
    """R97B: 09:15-22:30, the trigger is a new session high / low; at most 2 a session."""
    rid = "R97B"

    def keep(self, fe, j, d):
        return FE.directional(fe, j, d)["new_day_ext"]


class PrevDayBreak(TriggerRule):
    """R97C: the trigger comes with a cross of the previous session's high (long) / low (short) in the last 15 min."""
    rid, max_per_day = "R97C", 1

    def keep(self, fe, j, d):
        return FE.directional(fe, j, d)["break_pd"]


class MorningFirst(TriggerRule):
    """R97D: the first trigger of 09:15-11:30, either side, no filter."""
    rid, max_per_day = "R97D", 1
    window = (FIRST_TOD, 11 * 60 + 30)


class BigJoin(TriggerRule):
    """R97F: join a move already 300 points off its extreme, at a new session high / low; trail 300."""
    rid, max_per_day, T = "R97F", 1, 300.0

    def keep(self, fe, j, d):
        return FE.directional(fe, j, d)["new_day_ext"]


class StretchReversal(TriggerRule):
    """R97G: the swing-start profile of step 2: the last 30 min ranged >= 600 points and price fell (rose) >= 200 in
    the last 15 min; then a 100-point reversal trigger the other way."""
    rid, max_per_day = "R97G", 1

    def keep(self, fe, j, d):
        r = FE.directional(fe, j, d)
        return r["rng30"] >= 600 and r["ret15"] <= -200


class XauLead(TriggerRule):
    """R97E: XAUUSD's 5-minute move beats the MCX future's by >= 12 bp, and XAUUSD moved >= 15 bp: buy in XAUUSD's
    direction (MCX catching up); stop 150 points on the future, out after 15 minutes at the latest."""
    rid, max_per_day = "R97E", 3
    GAP_BP, XAU_BP, STOP, HOLD = 12.0, 15.0, 150.0, 15

    def candidates(self, book, period, ctx=None):
        ctx = context(book) if ctx is None else ctx
        out = []
        for day, ix, F, tod, fe in sessions(book, period, ctx):
            gap = (fe.xau5_bp - fe.mcx5_bp).values
            x5 = fe.xau5_bp.values
            ok = np.isfinite(gap) & np.isfinite(x5) & (np.abs(gap) >= self.GAP_BP) & (np.abs(x5) >= self.XAU_BP) \
                & (np.sign(gap) == np.sign(x5)) & (tod >= self.window[0]) & (tod <= self.window[1])
            for j in np.flatnonzero(ok):
                side = int(np.sign(x5[j]))
                out.append(spec(day, ix, F, tod, int(j), side, self.STOP, None, int(tod[j]) + self.HOLD))
        return out


RULES = {r.rid: r for r in (MorningExtreme(), AllDayExtreme(), PrevDayBreak(), MorningFirst(), XauLead(), BigJoin(),
                            StretchReversal())}
ORDER = ("R97A", "R97B", "R97C", "R97D", "R97E", "R97F", "R97G")
