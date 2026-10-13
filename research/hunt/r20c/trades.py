#!/usr/bin/env python3
"""R20c trading test (PREREG.md part A3 framework, part B rules). Research only.

    python3 research/hunt/r20c/trades.py design            # design period only -> results/design.json (frozen)
    python3 research/hunt/r20c/trades.py holdout           # the ONE holdout read -> results/holdout.json (refuses twice)

A rule's trade on one index and day: the first confirmed break of each allowed side (study.py's event logic on the rule's
level types, timeframe and N), if its candle closes inside the rule's window and passes its filters, buys 1 lot of the
call (up) / put (down): ATM (ORB family) or 1-ITM (Liquidity family) strike from the index close at the event, the
nearest expiry AFTER the day, entry at the next minute's open (the option must print within 3 minutes, premium above
the floor). Fills / charges / ORB exits: R17's `arms.py`.
"""
from __future__ import annotations

import json
import math
import os
import sys
from datetime import date

import numpy as np
from scipy import stats as SS

HERE = os.path.dirname(os.path.abspath(__file__))
R17 = os.path.abspath(os.path.join(HERE, "..", "r17"))
for p in (R17, HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

import config as K17  # noqa: E402,F401
import arms as A  # noqa: E402
import events as EV  # noqa: E402
import levels as LV  # noqa: E402
import study as S  # noqa: E402
from research.obuy.costs import Fills, floor_tick  # noqa: E402

ARM = {"BANKNIFTY": dict(step=100, stop=40.0, tgt=40.0, floor=40.0), "NIFTY": dict(step=50, stop=20.0, tgt=20.0, floor=20.0)}
PREM_STOP = 0.15                     # Liquidity family: 15% premium stop
TIME_STOP = (20, 0.05)               # Liquidity family: out if not +5% after 20 minutes
TWINS = 1000
BH_Q = 0.05

# Part B rules (PREREG.md). Filled in the part B commit, before any trading test.
BOTH = ("BANKNIFTY", "NIFTY")
RULES: dict = {
    "r20brk-piv5n2": dict(types=["PIV"], tf=5, n=2, last_entry=(14, 0), sides=(1, -1), exit="orb", unds=BOTH),
    "r20brk-piv5n1": dict(types=["PIV"], tf=5, n=1, last_entry=(14, 0), sides=(1, -1), exit="orb", unds=BOTH),
    "r20brk-early15": dict(types=["PIV", "PDHL"], tf=15, n=2, last_entry=(10, 30), sides=(1, -1), exit="liq", unds=BOTH),
    "r20brk-lowvix15": dict(types=["PIV"], tf=15, n=2, last_entry=(14, 0), sides=(1, -1), exit="liq", unds=BOTH,
                            filters={"vix": ["<13"]}),
}


# ------------------------------------------------------------------------------------------------ one rule, one day
class DayLv:
    """The levels a rule may use on one index-day: daily types fixed, LIQ via the zone map (twins: mirrored copies)."""

    def __init__(self, ctx, tf, types, liq=None, signs=None):
        self.ctx, self.tf, self.types = ctx, tf, types
        o = float(ctx.ix[0, 0])
        fixed = []
        for t in types:
            if t == "LIQ":
                continue
            lv = LV.or_edges(ctx.ix, tf) if t == "OR" else ctx.levels[t]
            if signs is not None:
                lv = [(LV.mirror(p, o, signs.get((t, k), 1)), s, k, kb) for p, s, k, kb in lv]
            fixed += lv
        self.fixed = fixed
        self.liq = None
        if "LIQ" in types:
            m = liq if liq is not None else LV.LiqMap(ctx.hist[tf], ctx.ix, tf)
            if signs is not None:
                m = m.mirrored({k: signs.get(("LIQ", k), 1) for k in m.keys()})
            self.liq = m

    def __call__(self, b):
        out = [x for x in self.fixed if b >= x[3]]
        if self.liq is not None:
            out += self.liq.active(b)
        return out

    def known_bar(self):
        """The first candle any of the rule's levels may be crossed (the random-time twin's window start)."""
        kb = [x[3] for x in self.fixed]
        if self.liq is not None:
            kb.append(0)
        return min(kb) if kb else 0


def last_row(rule):
    h, m = rule["last_entry"]
    return h * 60 + m - (9 * 60 + 15) - 1          # the candle must close by this time


def passes_filters(rule, ctx, row):
    f = rule.get("filters", {})
    if "vix" in f and S.vix_bucket(ctx.vix) not in f["vix"]:
        return False
    if f.get("no_expiry") and ctx.expiry:
        return False
    if "tod" in f and S.tod_bucket(row) not in f["tod"]:
        return False
    return True


def signals(rule, ctx, lv):
    """[(side, decision row, level)] for one index-day: the first confirmed break per allowed side."""
    tf, n = rule["tf"], rule["n"]
    cd = LV.candles(ctx.ix, tf)
    out = []
    for side in rule["sides"]:
        ev = EV.first_break(cd[:, 3], cd[:, 0], ctx.prev_close, side, lv, n, tf)
        if ev is None:
            continue
        row = (ev["e"] + 1) * tf - 1
        if row > last_row(rule) or not passes_filters(rule, ctx, row):
            continue
        out.append((side, row, ev["level"]))
    return out


def strike_for(rule, und, spot, side):
    step = ARM[und]["step"]
    atm = float(math.floor(spot / step + 0.5) * step)
    return atm - side * step if rule["exit"] == "liq" else atm


def sim_liq(a, ix, c0, e, side, level, tf, lot):
    """Liquidity family exits: 15% premium stop (resting), failed break on a tf candle close of the index (next minute's
    open), time stop (not +5% after 20 minutes: next open), 15:10."""
    trig = float(floor_tick(np.array([e * (1 - PREM_STOP)]))[0])
    check = c0 + TIME_STOP[0] - 1
    for r in range(c0, A.SQ_ROW):
        if np.isfinite(a[r, 3]):
            o = a[r, 0] if np.isfinite(a[r, 0]) else a[r, 3]
            if a[r, 2] <= trig:
                return A.sell_fill(min(trig, o), a, r, lot, stop=True), r, "stop"
            if r == check and a[r, 3] < e * (1 + TIME_STOP[1]):
                rr, px = A.next_open(a, r + 1)
                return A.sell_fill(px, a, rr, lot), rr, "time"
        elif r == check:
            check += 1                                     # no print this minute: judge on the next printed minute
        if (r + 1) % tf == 0 and side * (ix[r, 3] - level) < 0:
            rr, px = A.next_open(a, r + 1)
            return A.sell_fill(px, a, rr, lot), rr, "failed_break"
    rr, px = A.next_open(a, A.SQ_ROW)
    return A.sell_fill(px, a, rr, lot), rr, "square_off"


def trade(rule, d, ctx, side, row, level):
    """One trade (dict) or None when the option does not print / is below the floor."""
    und = ctx.und
    cfg = ARM[und]
    spot = float(ctx.ix[row, 3])
    strike = strike_for(rule, und, spot, side)
    exp, a = A.contract(d, strike, side, same_day_ok=False)
    if a is None:
        return None
    c0, px = A.next_open(a, row + 1)
    if c0 > row + 3 or px <= cfg["floor"]:
        return None
    e = A.buy_fill(px, a, c0, d.lot)
    if rule["exit"] == "orb":
        x, xr, why = A.sim_fixed_pts(a, c0, e, cfg["stop"], cfg["tgt"], d.lot, d.lot)
    else:
        x, xr, why = sim_liq(a, ctx.ix, c0, e, side, level, rule["tf"], d.lot)
    ch = A.charges(e, x, d.lot)
    return dict(und=und, day=ctx.day, side=side, row=row, strike=strike, entry=e, exit=x, exit_row=xr, why=why,
                net=(x - e) * d.lot - ch, charges=ch, level=level)


def rule_day(rule, d, ctx, liq=None, signs=None):
    lv = DayLv(ctx, rule["tf"], rule["types"], liq, signs)
    out = []
    for side, row, level in signals(rule, ctx, lv):
        t = trade(rule, d, ctx, side, row, level)
        if t:
            out.append(t)
    return out


# ------------------------------------------------------------------------------------------------ scores
def daily(trades, sessions):
    """{calendar day: net} over the given sessions (no-trade sessions = 0)."""
    out = {s: 0.0 for s in sessions}
    for t in trades:
        if t["day"] in out:
            out[t["day"]] += t["net"]
    return out


def max_dd(x):
    c = np.cumsum(x)
    return float(np.min(c - np.maximum.accumulate(np.concatenate([[0.0], c]))[1:])) if len(c) else 0.0


def score(trades, sessions):
    dn = daily(trades, sessions)
    days = sorted(dn)
    x = np.array([dn[k] for k in days])
    tdays = {t["day"] for t in trades if t["day"] in dn}
    tx = np.array([dn[k] for k in sorted(tdays)])
    t, p = (SS.ttest_1samp(x, 0.0, alternative="greater") if len(x) > 2 and x.std() > 0 else (np.nan, 1.0))
    return dict(trades=sum(1 for t_ in trades if t_["day"] in dn), sessions=len(x), trade_days=len(tdays),
                net=float(x.sum()), per_session=float(x.mean()) if len(x) else 0.0,
                per_trade=float(np.mean([t_["net"] for t_ in trades if t_["day"] in dn])) if tdays else 0.0,
                green_trade_days=float((tx > 0).mean()) if len(tx) else np.nan, worst_day=float(x.min()) if len(x) else 0.0,
                best_day=float(x.max()) if len(x) else 0.0, max_dd=max_dd(x), t=float(t), p=float(p))


def blocks(trades, sessions, k=4):
    s = sorted(sessions)
    cuts = np.array_split(np.arange(len(s)), k)
    return [score(trades, [s[i] for i in c]) | {"from": str(s[c[0]]), "to": str(s[c[-1]])} for c in cuts]


def bh(pvals: dict, q=BH_Q):
    """Benjamini-Hochberg: {name: (p, q-value, rejected)}."""
    names = sorted(pvals, key=lambda k: pvals[k])
    m = len(names)
    ps = np.array([pvals[k] for k in names])
    qv = np.minimum.accumulate((ps * m / np.arange(1, m + 1))[::-1])[::-1]
    kmax = max([i + 1 for i in range(m) if ps[i] <= (i + 1) / m * q], default=0)
    return {k: (float(ps[i]), float(min(qv[i], 1.0)), i < kmax) for i, k in enumerate(names)}
