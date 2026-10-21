"""R51 family: the "NIFTY premium mean-reversion scalp" (R45). SIGNAL SOURCE: THE OPTION'S OWN PRICE CHART (5-minute
ATM call premium crossing below its Bollinger(20, 2) lower band). NOT the default kind of rule (CLAUDE.md): labelled,
reported separately; only Boss can switch such a rule on.

R45's code is reused unchanged (r45data sessions, r45study.Prep / cont_lowers, r45sim.simulate_session), with R45's
FIX rules: WEEK only, max 2 a day, -10% stop, stepped lock 25/50/75/100%, gap fills at the open, locks armed by earlier
bars, 15-minute hold (bars e..e+2 checked, exit at the close of e+2), real expiry days skipped, 15:15 square-off, the
band carried across sessions (R45's best reproduction; sessions before 20 Nov 2024 only warm the band up).
F-a: signal on the rolling ATM call, each trade priced on the strike ATM at the entry bar (held). F-b: the 09:15 ATM
strike for signal and trade all session.
R51 costs: Costs('dated') + the liq rule at its "some volume" level (2 ticks; the 5-minute session bars carry no
volume): buys +5 bps + 2 ticks, sells -5 bps - 2 ticks, stops / locks -10 bps - 4 ticks. Lots in force on each date.
"""
from __future__ import annotations

import os
from dataclasses import replace
from datetime import date
from functools import lru_cache

import numpy as np

import common as C
import engine as E
import oldcode
import r51cfg as K

os.environ.setdefault("R45_CACHE", str(K.OUT / "r45"))
D45 = oldcode.load("r45", "r45data")
S45 = oldcode.load("r45", "r45sim")
ST45 = oldcode.load("r45", "r45study")
K45 = oldcode.load("r45", "r45cfg")
CFG = {"F-a": K45.variant({**K45.T0, "boll": "cont", "ddof": 0}, K45.VARIANTS["F-a"]),
       "F-b": K45.variant({**K45.T0, "boll": "cont", "ddof": 0}, K45.VARIANTS["F-b"])}


@lru_cache(maxsize=1)
def sessions():
    return D45.load("NIFTY", "WEEK", years=range(2023, 2027))


@lru_cache(maxsize=1)
def expiries():
    return D45.expiry_days("NIFTY", "WEEK", years=range(2023, 2027))


def _r2(x):
    return round(x * 100) / 100


def net_of(x, und, d):
    q = float(K.lot(und, d))
    rE = _r2(x.entry * 1.0005) + 2 * K.TICK
    if x.why in ("stop", "lock"):
        rX = max(_r2(x.exit * 0.999) - 4 * K.TICK, 0.0)
    else:
        rX = max(_r2(x.exit * 0.9995) - 2 * K.TICK, 0.0)
    return (rX - rE) * q - float(E.charges(und, rE, rX, q, d)[0]), rE, rX, q


def make(base: str, hold_bars: int = 3, stop: float = 0.10) -> C.Config:
    v = dict(CFG[base])
    v["rules"] = {**v["rules"], "checked": hold_bars, "exit_at": hold_bars - 1, "stop": stop}
    r = S45.Rules(**v["rules"])

    def preps(lo, hi):
        C.check_range(lo, hi)
        ss = [s for s in sessions() if s.day <= hi]
        out = ST45.cont_lowers(ss, v)
        return [p for p in out if lo <= p.s.day <= hi and len(p.t) >= 25 and p.s.day not in expiries()]

    def run(lo, hi):
        rows = []
        for p in preps(lo, hi):
            d = p.s.day
            for x in S45.simulate_session(p.t, p.c, p.lower, p.bars, r):
                net, rE, rX, q = net_of(x, "NIFTY", d)
                rows.append(dict(und="NIFTY", day=d, side="C", strike=float(p.strike_of(x.e) or np.nan),
                                 er=int(p.t[x.e]) - K.OPEN_MIN, xr=int(p.t[x.x]) - K.OPEN_MIN, E=x.entry, X=x.exit,
                                 rE=rE, rX=rX, qty=q, why=x.why, net=net, gross=(x.exit - x.entry) * q))
        return C.frame(rows)

    def twin_fn(T, nt, rng):
        """R45's own twins: the same sessions and trade counts, random eligible entry bars, same exits."""
        lo, hi = min(T.day), max(T.day)
        by = {p.s.day: p for p in preps(lo, hi)}
        cnt = T.groupby("day").size()
        tot = np.zeros(nt)
        for i in range(nt):
            for d, k in cnt.items():
                p = by.get(d)
                if p is None:
                    continue
                for x in S45.twin_session(p.t, p.bars, r, int(k), rng):
                    tot[i] += net_of(x, "NIFTY", d)[0]
        return tot

    return C.Config(f"r45|{base}|h{hold_bars}|s{stop}", run, None, twin_fn)


def variants() -> list:
    ex = dict(unds=("NIFTY",))
    src = "OPTION'S OWN PRICE CHART (non-default; Boss only)"
    grid = [make("F-a")] + [make("F-a", h, s) for h in (3, 6) for s in (0.10, 0.20) if (h, s) != (3, 0.10)]
    return [C.Variant("R45-F-a", "R45 premium scalp (non-default)", src, "orig", grid[:1],
                      "Bollinger lower-band cross on the 5-min rolling ATM call; trade the strike ATM at entry",
                      extra=dict(ex)),
            C.Variant("R45-F-b", "R45 premium scalp (non-default)", src, "orig", [make("F-b")],
                      "the same on the 09:15 ATM strike all session", extra=dict(ex)),
            C.Variant("R45-F-a-RT", "R45 premium scalp (non-default)", src, "retune", grid,
                      "re-tune: hold {15, 30} min x stop {10%, 20%}", extra=dict(ex))]
