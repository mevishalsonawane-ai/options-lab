"""Catalog family expiry_day (EXP-01 .. EXP-05): option buying on the expiry day of the contract expiring TODAY.

EXP-01 gc_exp01_hero    Hero-zero: at a fixed afternoon time buy the nearest OTM option priced in a band (Rs 2-5 / 5-10),
                        direction by an afternoon breakout of the 12:00..entry range / the day's trend / both sides.
                        Rs 5,000 ticket per option (both sides = two tickets). Exits: hold to 15:20, >= 4x target,
                        -50% stop + 4x, or lock 2x once 4x is reached. (Extends hero.py's ticket mechanics; its
                        straddle-expansion rule is the app's own variant, already tested as hero_grid.)
EXP-02 gc_exp02_gamma   Gamma blast: after `start`, the first 5-min close beyond the day's range (or the 12:00..start
                        consolidation), optionally only when the ATM straddle is compressed (< comp x spot), buys ATM /
                        1-OTM that way; -30/-50% stop, 2x / 3x target, lock 1.5x after 2x, optional index-back-in-range stop.
EXP-03 gc_exp03_orb     ORB15 (5-min close beyond the 09:15-09:30 range, first signal, cutoff 13:30) on expiry days only,
                        buying TODAY's expiry, ATM / ITM1 / ITM2; OR-opposite or -30% stops, 1:2 / profit-lock / trail.
EXP-04 gc_exp04_maxpain Max-pain convergence: at 10:30 / 11:00 on expiry day (or the session before), max pain from the
                        OI of the strikes in the data (ATM+-10: the data holds no other strikes); spot > d away -> buy
                        ATM / ITM1 toward it; target = spot reaches max pain, -30% / -50% stop.
EXP-05 gc_exp05_scalp1/5 The owner's +10% scalp: from 09:30 to 14:30, whenever flat, buy ATM in the direction of the last
                        5 / 15 minutes or of the day so far; +10%/-3.3%, +20%/-6.6%, +10%/-5%, +10% no stop; 1 or 5 a day.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..costs import Fills
from ..engine import LADDER, Execution, Exits, StrikeRule
from . import orb
from .base import Strategy
from .common import day_arrays
from .gc_common import atm, max_pain, straddle_series

UNDS4 = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")


def _hhmm(x):
    return (x // 100) * 60 + x % 100


# ------------------------------------------------------------------------------------------------ EXP-01
def hero_signals(mk, at=1330, dirn="breakout", last=1450, unds=UNDS4):
    T, L = _hhmm(at), _hhmm(last)
    out = []
    for und in unds:
        ix = mk.index(und)
        for d in ix.days:
            if not ix.d[d]["exp"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            t = T - C.OPEN_M
            if dirn == "both":
                for side, r in ((1, "C"), (-1, "P")):
                    out.append(dict(und=und, day=d, sig_min=T - 1, side=side, book=f"hero_{und}_{r}"))
                continue
            if dirn == "trend":
                x, o0 = c[t - 1], o[0] if np.isfinite(o[0]) else np.nan
                if not (np.isfinite(x) and np.isfinite(o0)) or x == o0:
                    continue
                out.append(dict(und=und, day=d, sig_min=T - 1, side=1 if x > o0 else -1, book=f"hero_{und}"))
                continue
            # breakout of the 12:00 .. entry-time range, first 1-min close beyond it until `last`
            s0 = 12 * 60 - C.OPEN_M
            hi, lo = np.nanmax(h[s0:t]), np.nanmin(l[s0:t])
            if not (np.isfinite(hi) and np.isfinite(lo)):
                continue
            for col in range(t - 1, L - C.OPEN_M + 1):
                x = c[col]
                if np.isnan(x):
                    continue
                side = 1 if x > hi else (-1 if x < lo else 0)
                if side:
                    out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"hero_{und}"))
                    break
    return pd.DataFrame(out)


SQ20 = 15 * 60 + 20
HERO_X = [Exits(sq_off=SQ20), Exits(tgt_pct=3.0, sq_off=SQ20), Exits(stop_pct=0.5, tgt_pct=3.0, sq_off=SQ20),
          Exits(ladder=((3.0, 1.0),), ladder_ref_pct=1.0, sq_off=SQ20)]


# ------------------------------------------------------------------------------------------------ EXP-02
def gamma_signals(mk, start=1345, ref="day", comp=None, last=1500, unds=UNDS4):
    S = _hhmm(start)
    out = []
    for und in unds:
        ix = mk.index(und)
        opts = mk.options(und)
        step = C.STEP[und]
        for d in ix.days:
            if not ix.d[d]["exp"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            t = S - C.OPEN_M
            s0 = 0 if ref == "day" else 12 * 60 - C.OPEN_M
            hi, lo = np.nanmax(h[s0:t]), np.nanmin(l[s0:t])
            if not (np.isfinite(hi) and np.isfinite(lo)):
                continue
            if comp is not None:
                ch = opts.chain(d, "near")
                if ch is None:
                    continue
                sp = c[t - 1]
                if not np.isfinite(sp):
                    continue
                st = straddle_series(ch, c, step, fixed=atm(sp, step))
                if not (np.isfinite(st[t - 1]) and st[t - 1] / sp < comp):
                    continue
            for col in range(t + 4 - (t % 5), _hhmm(last) - C.OPEN_M, 5):     # 5-min candle closes (09:15-anchored)
                x = c[col]
                if np.isnan(x):
                    continue
                side = 1 if x > hi else (-1 if x < lo else 0)
                if side:
                    out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"gb_{und}",
                                    idx_stop=hi if side > 0 else lo))
                    break
        mk.release(und)
    return pd.DataFrame(out)


LOCK15 = ((1.0, 0.5),)       # reach 2x (+100%) -> lock 1.5x (+50%)
GAMMA_X = [Exits(stop_pct=0.3, tgt_pct=1.0, sq_off=SQ20, sig_levels=False),
           Exits(stop_pct=0.5, tgt_pct=2.0, sq_off=SQ20, sig_levels=False),
           Exits(stop_pct=0.5, tgt_pct=2.0, ladder=LOCK15, ladder_ref_pct=1.0, sq_off=SQ20, sig_levels=False),
           Exits(stop_pct=0.3, tgt_pct=2.0, ladder=LOCK15, ladder_ref_pct=1.0, sq_off=SQ20, sig_levels=True)]


# ------------------------------------------------------------------------------------------------ EXP-03
def orb_exp_signals(mk, or_min=15, tf=5, stop="opp", cutoff=13 * 60 + 30, unds=UNDS4):
    s = orb.signals(mk, or_min=or_min, tf=tf, stop=stop, cutoff=cutoff, unds=unds)
    if s.empty:
        return s
    keep = [mk.index(u).d[d]["exp"] for u, d in zip(s.und, s.day)]
    return s[np.array(keep, bool)].reset_index(drop=True)


SQ15 = 15 * 60 + 15
ORB_X = [Exits(idx_tgt_r=2.0, sq_off=SQ15),
         Exits(ladder=LADDER, ladder_ref_pct=0.3, sq_off=SQ15),
         Exits(stop_pct=0.3, tgt_pct=0.6, sq_off=SQ15, sig_levels=False),
         Exits(stop_pct=0.3, ladder=LADDER, ladder_ref_pct=0.3, sq_off=SQ15, sig_levels=False),
         Exits(idx_tgt_r=2.0, pine_trail=True, sq_off=SQ15),
         Exits(stop_pct=0.3, pine_trail=True, sq_off=SQ15, sig_levels=False)]


# ------------------------------------------------------------------------------------------------ EXP-04
def maxpain_signals(mk, calc=1030, dist=0.004, rel=0, unds=("NIFTY", "BANKNIFTY")):
    T = _hhmm(calc)
    out = []
    for und in unds:
        ix = mk.index(und)
        opts = mk.options(und)
        for i, d in enumerate(ix.days):
            if rel == 0 and not ix.d[d]["exp"]:
                continue
            if rel == -1 and not (i + 1 < len(ix.days) and ix.d[ix.days[i + 1]]["exp"] and not ix.d[d]["exp"]):
                continue
            ch = opts.chain(d, "near")
            if ch is None:
                continue
            o, h, l, c = day_arrays(ix, d)
            col = T - 1 - C.OPEN_M
            sp = c[col]
            if not np.isfinite(sp):
                continue
            mp = max_pain(ch, col)
            if mp is None or abs(sp - mp) / sp <= dist:
                continue
            side = 1 if mp > sp else -1
            out.append(dict(und=und, day=d, sig_min=T - 1, side=side, book=f"mp_{und}", idx_target=mp,
                            tag=f"mp={mp:.0f}"))
        mk.release(und)
    return pd.DataFrame(out)


MP_X = [Exits(stop_pct=0.3, sq_off=SQ15), Exits(stop_pct=0.5, sq_off=SQ15),
        Exits(stop_pct=0.3, pine_trail=True, sq_off=SQ15)]


# ------------------------------------------------------------------------------------------------ EXP-05
def scalp_signals(mk, dirn="last5", first=930, last=1430, every=2, unds=("NIFTY", "BANKNIFTY")):
    """Entry candidates every `every` minutes (2: re-entry at most 1 minute later than 'immediately when flat';
    keeps the candidate set small enough for the shared machine)."""
    out = []
    for und in unds:
        ix = mk.index(und)
        for d in ix.days:
            if not ix.d[d]["exp"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            o0 = o[0]
            for col in range(_hhmm(first) - 1 - C.OPEN_M, _hhmm(last) - C.OPEN_M, every):
                x = c[col]
                if np.isnan(x):
                    continue
                if dirn == "day":
                    ref = o0
                else:
                    k = 5 if dirn == "last5" else 15
                    ref = c[col - k]
                if not np.isfinite(ref) or x == ref:
                    continue
                out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=1 if x > ref else -1, book=f"sc_{und}"))
    return pd.DataFrame(out)


SCALP_X = [Exits(stop_pct=0.033, tgt_pct=0.10, sq_off=SQ15), Exits(stop_pct=0.066, tgt_pct=0.20, sq_off=SQ15),
           Exits(stop_pct=0.05, tgt_pct=0.10, sq_off=SQ15), Exits(tgt_pct=0.10, sq_off=SQ15)]

EXP_EXE = Execution(expiry="only")

STRATEGIES = [
    Strategy(
        name="gc_exp01_hero", family="expiry_day", signal_fn=hero_signals,
        sig_grid=[dict(at=a, dirn=r) for a in (1300, 1330, 1400, 1430) for r in ("breakout", "trend", "both")],
        rules=[StrikeRule(0, prem_band=(2.0, 5.0)), StrikeRule(0, prem_band=(5.0, 10.0))],
        exe=Execution(fills=Fills("liq"), expiry="only", budget=5000.0, max_delay=3),
        exits=HERO_X, pos=dict(one_at_a_time=True, max_per_day=1), window=(12 * 60 + 59, 14 * 60 + 50),
        doc="EXP-01 hero-zero: OTM Rs 2-5 / 5-10 on expiry afternoons, Rs 5k ticket, breakout / trend / both sides."),
    Strategy(
        name="gc_exp02_gamma", family="expiry_day", signal_fn=gamma_signals,
        sig_grid=[dict(start=s, ref=r, comp=cp) for s in (1330, 1345, 1400) for r in ("day", "cons") for cp in (None, 0.003)],
        rules=[StrikeRule(0), StrikeRule(-1)],
        exe=EXP_EXE, exits=GAMMA_X, pos=dict(one_at_a_time=True, max_per_day=1), window=(13 * 60 + 30, 15 * 60),
        doc="EXP-02 gamma blast: afternoon 5-min range break on expiry day, ATM / OTM1."),
    Strategy(
        name="gc_exp03_orb", family="expiry_day", signal_fn=orb_exp_signals,
        sig_grid=[dict()], rules=[StrikeRule(0), StrikeRule(1), StrikeRule(2)],
        exe=EXP_EXE, exits=ORB_X, pos=dict(one_at_a_time=True, max_per_day=1), window=(9 * 60 + 34, 13 * 60 + 30),
        doc="EXP-03 ORB15 on expiry days with today's expiry, ATM / ITM1 / ITM2."),
    Strategy(
        name="gc_exp04_maxpain", family="expiry_day", signal_fn=maxpain_signals,
        sig_grid=[dict(calc=t, dist=x, rel=r) for t in (1030, 1100) for x in (0.002, 0.004, 0.006) for r in (0, -1)],
        rules=[StrikeRule(0), StrikeRule(1)],
        exe=Execution(expiry="allow"), exits=MP_X, pos=dict(one_at_a_time=True, max_per_day=1),
        window=(10 * 60 + 29, 10 * 60 + 59),
        doc="EXP-04 max-pain convergence on expiry day / T-1 (max pain over ATM+-10 strikes)."),
] + [
    Strategy(
        name=f"gc_exp05_scalp{n}", family="expiry_day", signal_fn=scalp_signals,
        sig_grid=[dict(dirn=r) for r in ("last5", "last15", "day")], rules=[StrikeRule(0)],
        exe=EXP_EXE, exits=SCALP_X, pos=dict(one_at_a_time=True, max_per_day=n), window=(9 * 60 + 29, 14 * 60 + 29),
        doc=f"EXP-05 expiry +10% scalp, ATM, whenever flat 09:30-14:30, {n} a day.")
    for n in (1, 5)
]
