"""R51 family: Boss's 7 sweep strategies (R44 spec), signal on the 5-minute INDEX bars, the ATM option bought.

Signals: research/hunt/r44/ind.py `signals` (EMA cross + ADX, RSI fade / momentum, Donchian 20) on the continuous
series of 5-minute index bars from 1 Sep 2024 (warm-up), unchanged. A signal on bar b's close fills at the open of
bar b+1 (row 5(b+1)); entries 09:20-15:10; square-off 15:15; stop -25%, target +50%, 60-minute time exit; the stepped
lock for the "plock" version; one position a side, at most 2 fills a day a side; expiry days skipped.
"""
from __future__ import annotations

from functools import lru_cache

import numpy as np

import common as C
import engine as E
import oldcode
import r51cfg as K
import r51data

IND = oldcode.load("r44", "ind")
R44K = oldcode.load("r44", "r44cfg")

SQ = K.row(15, 15)
FIRST, LAST = 5, K.row(15, 10)
STEPS = ((0.25, 0.0), (0.50, 0.25), (0.75, 0.50), (1.00, 0.75))
STRATS = {   # Boss's 7 (R44 PREREG); "ema821_adx15_plock" is the stepped-lock version
    "ema821_adx15": ("ema", (8, 21, 15.0), False),
    "ema821_adx15_plock": ("ema", (8, 21, 15.0), True),
    "ema921_adx20": ("ema", (9, 21, 20.0), False),
    "ema926_adx20": ("ema", (9, 26, 20.0), False),
    "rsi_fade": ("rsi_fade", (30.0, 70.0), False),
    "rsi_momo": ("rsi_momo", (55.0, 45.0), False),
    "donchian20": ("donchian", (20,), False),
}
EXITS = {"A": (0.25, 0.50, 60), "B": (0.15, 0.30, 30), "C": (0.35, 1.00, 120)}
OLD = {   # R44 (5 indices, real costs, 2021-26): every strategy lost; values from HUNT_R44_SWEEP_RETEST.md
    "ema821_adx15": "lost (part of -Rs 26.6 lakh 2021-23 and -Rs 36.4 lakh 2024-26 across the 7); 0 of 112 cells pass",
}


@lru_cache(maxsize=8)
def bars5(und: str):
    days = r51data.load(und)
    d_, b_, o_, h_, l_, c_ = [], [], [], [], [], []
    for d in sorted(days):
        a = days[d].idx
        for b in range(75):
            seg = a[:, 5 * b:5 * b + 5]
            ok = np.isfinite(seg[3])
            if not ok.any():
                continue
            o = seg[0][ok][0]
            d_.append(d)
            b_.append(b)
            o_.append(o)
            h_.append(np.nanmax(seg[1]))
            l_.append(np.nanmin(seg[2]))
            c_.append(seg[3][ok][-1])
    return (np.array(d_, dtype=object), np.array(b_), np.array(o_), np.array(h_), np.array(l_), np.array(c_))


@lru_cache(maxsize=64)
def signal_list(und: str, strat: str):
    kind, params, _ = STRATS[strat]
    d, b, o, h, l, c = bars5(und)
    lo, sh = IND.signals(kind, params, o, h, l, c, warm=R44K.WARM_BARS)
    out = {}
    for side, m in (("C", lo), ("P", sh)):
        for i in np.flatnonzero(m):
            out.setdefault(d[i], []).append((int(b[i]), side))
    return {k: sorted(v) for k, v in out.items()}


def exits(strat: str, ex_key: str = "A") -> E.Exits:
    s, t, m = EXITS[ex_key]
    return E.Exits(stop=s, target=t, tmax=m, steps=STEPS if STRATS[strat][2] else (), sq_row=SQ)


def make(strat: str, ex_key: str = "A", money: int = 0) -> C.Config:
    ex = exits(strat, ex_key)

    def run(lo, hi):
        rows = []
        for und in K.UNDS:
            sig = signal_list(und, strat)
            days = r51data.load(und)
            for d in C.days_in(und, lo, hi):
                D = days[d]
                if D.expiry or d not in sig:
                    continue
                cands = []
                for b, side in sig[d]:
                    er = 5 * (b + 1)
                    if er < FIRST or er > LAST:
                        continue
                    k = E.strike_for(D, und, side, er - 1, money)
                    if k is not None:
                        cands.append(dict(er=er, side=side, strike=k))
                rows += C.take(und, D, cands, ex, scope="side", max_per_side=2)
        return C.frame(rows)

    return C.Config(f"{strat}|{ex_key}|m{money}", run, lambda und: C.TwinSpec(ex, FIRST, LAST, money))


def variants() -> list:
    out = []
    for s in STRATS:
        grid = [make(s, "A", 0)] + [make(s, e, m) for e in EXITS for m in (0, 1) if (e, m) != ("A", 0)]
        desc = f"{s}: 5-min index signal, ATM, stop -25% / target +50% / 60 min" + (", stepped lock" if STRATS[s][2] else "")
        out.append(C.Variant(f"R44-{s}", "R44 sweep", "index (5-min)", "orig", grid[:1], desc))
        out.append(C.Variant(f"R44-{s}-RT", "R44 sweep", "index (5-min)", "retune", grid,
                             f"{s} re-tune: exits A/B/C x ATM / 1-ITM"))
    return out
