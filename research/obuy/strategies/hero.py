"""Expiry-day 'hero-zero' (the app's Hero arm, HeroRules.kt as ported in scratchpad hero_deep/scripts/sim.py):
on an expiry day, from 13:30 to 14:45, when the ATM straddle has expanded >= `st_exp` above its low since 12:00 and the
index has moved >= `mom` over the last `mom_k` minutes, buy the nearest OTM option that way priced Rs `min_px`-`max_px`
(traded in the last 4 minutes, within 3% of spot, skipped if a further-OTM strike is dearer than it by the slack), sized
to a Rs 5,000 budget, held to 15:05. One ticket a day per index.

Validation: research hero_deep/out/replay.md (NIFTY, 319 expiry days: 61 trades, net Rs +230,521 with realistic
fills). Differences: the index here is the real index OHLC (the replay used Dhan's spot print) and the fill is the next
minute's open + the 'liq' half-spread (the replay worked a limit order over 3 minutes).
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from .. import config as C
from ..costs import Fills
from ..engine import Execution, Exits, StrikeRule
from .base import Strategy


def _lastc(a, t):
    """Last close at or before column t if it printed in the last 3 bars (leg_price), else None."""
    lo = max(t - 2, 0)
    x = a[lo:t + 1]
    ok = np.nonzero(~np.isnan(x))[0]
    return x[ok[-1]] if len(ok) else None


def signals(mk, st_exp=0.15, mom=0.0025, mom_k=15, first=809, last=884, low_from=719, min_px=1.0, max_px=5.0, band=0.03,
            unds=("NIFTY",)):
    out = []
    for und in unds:
        ix = mk.index(und)
        opts = mk.options(und)
        step = C.STEP[und]
        M = ix.mat()
        for d in ix.days:
            if not ix.d[d]["exp"]:
                continue
            ch = opts.chain(d, "near")
            if ch is None:
                continue
            sp = M["c"][ix.pos[d]]
            st = np.full(C.W, np.nan)
            for t in range(low_from - C.OPEN_M, last - C.OPEN_M + 1):
                s = sp[t]
                if np.isnan(s):
                    continue
                k = math.floor(s / step + 0.5) * step
                i = ch.kpos(k)
                if i < 0:
                    continue
                cc, pp = _lastc(ch.c["C"][i], t), _lastc(ch.c["P"][i], t)
                if cc is not None and pp is not None:
                    st[t] = cc + pp
            low = None
            for t in range(low_from - C.OPEN_M, last - C.OPEN_M + 1):
                s = st[t]
                valid = not np.isnan(sp[t]) and not np.isnan(s)
                if valid:
                    low = s if low is None else min(low, s)
                if t < first - C.OPEN_M or not valid or low is None or low <= 0:
                    continue
                back = sp[t - mom_k]
                if np.isnan(back):
                    continue
                exp = s / low - 1
                mv = sp[t] / back - 1
                if exp < st_exp - 1e-9:
                    continue
                side = 1 if mv >= mom - 1e-9 else (-1 if mv <= -mom + 1e-9 else 0)
                if side == 0:
                    continue
                k = _pick(ch, side, t, sp[t], min_px, max_px, band)
                if k is None:
                    continue
                out.append(dict(und=und, day=d, sig_min=t + C.OPEN_M, side=side, strike=k, book=f"hero_{und}",
                                tag=f"exp={exp:.2f},mom={mv:.4f}"))
                break
    return pd.DataFrame(out)


def _pick(ch, side, t, s0, lo, hi, band):
    r = "C" if side > 0 else "P"
    K = ch.K
    otm = [i for i in range(len(K)) if (K[i] > s0 + 1e-9 if side > 0 else K[i] < s0 - 1e-9) and abs(K[i] - s0) <= s0 * band]
    otm.sort(key=lambda i: abs(K[i] - s0))
    cl = ch.c[r]
    def ltp(i):
        x = cl[i, :t + 1]
        ok = np.nonzero(~np.isnan(x))[0]
        return (x[ok[-1]], ok[-1]) if len(ok) else (np.nan, -1)
    vals = [ltp(i) for i in otm]
    for j, i in enumerate(otm):
        c, lt = vals[j]
        if np.isnan(c) or c < lo - 1e-9 or c > hi + 1e-9 or lt < t - 4 or c <= 0:
            continue
        slack = max(0.10, 0.25 * c)
        if any((not np.isnan(vals[jj][0])) and vals[jj][0] > c + slack + 1e-9 for jj in range(j + 1, len(otm))):
            continue
        return int(K[i])
    return None


HERO_EXE = Execution(fills=Fills("liq"), expiry="only", budget=5000.0, max_delay=3)
SQ = 15 * 60 + 5

STRATEGIES = [
    Strategy(
        name="hero_zero",
        family="expiry-lottery",
        signal_fn=signals,
        sig_grid=[dict()],
        rules=[StrikeRule(0, prem_band=(1.0, 5.0))],
        exe=HERO_EXE,
        exits=[Exits(sq_off=SQ)],
        pos=dict(one_at_a_time=True, max_per_day=1),
        window=(13 * 60 + 29, 14 * 60 + 44),
        doc="The app's Hero rule on NIFTY expiries (Rs 5k ticket, out 15:05).",
    ),
    Strategy(
        name="hero_grid",
        family="expiry-lottery",
        signal_fn=signals,
        sig_grid=[dict(st_exp=se, mom=mo, unds=("NIFTY", "SENSEX", "BANKNIFTY", "FINNIFTY")) for se in (0.10, 0.15, 0.25)
                  for mo in (0.0015, 0.0025, 0.004)],
        rules=[StrikeRule(0, prem_band=(1.0, 5.0))],
        exe=HERO_EXE,
        exits=[Exits(sq_off=SQ), Exits(sq_off=15 * 60 + 20), Exits(stop_pct=0.6, sq_off=SQ, intrabar=False),
               Exits(tgt_pct=4.0, sq_off=SQ), Exits(tgt_pct=9.0, sq_off=SQ), Exits(trail_pct=0.5, trail_arm=2.0, sq_off=SQ, intrabar=False)],
        pos=dict(one_at_a_time=True, max_per_day=1),
        window=(13 * 60 + 29, 14 * 60 + 44),
        doc="Hero grid: straddle expansion x momentum, 4 indices, 6 exit sets.",
    ),
]
