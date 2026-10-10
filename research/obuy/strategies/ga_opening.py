"""Catalog family 'opening_range' (research/option_buying_catalog.json OR-01 .. OR-08).

OR-01 / OR-02 / OR-03 reuse the ORB signal function in orb.py (extended there with touch triggers, an earliest entry,
range-width percentile / % bands, both directions, a gap-direction filter, previous-day and fixed-% index targets).
The rest are here. Common to all: time exit 15:15, expiry days skipped, the app's fills and charges, one position at
a time per book. Grids are one-factor-at-a-time around the catalog's 'most common' reading (fixed before any result).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from .base import Strategy
from .common import day_arrays, daily_atr
from .ga_common import SQ, UNDS2, UNDS4, close_exit, day_open, first_break, last_valid, twap
from .orb import signals as orb_signals

M = lambda h, m: h * 60 + m  # noqa: E731


# ------------------------------------------------------------------------------------------------ OR-01 ORB15
OR01_BASE = dict(or_min=15, tf=5, trigger="close", stop="opp", cutoff=M(13, 30), unds=UNDS4)
OR01_GRID = [OR01_BASE] + [dict(OR01_BASE, **d) for d in (
    dict(or_min=5), dict(or_min=30), dict(or_min=60),          # OR length
    dict(tf=1), dict(tf=15),                                    # signal bar
    dict(trigger="touch"),                                      # close vs touch
    dict(pctl=(20, 80)),                                        # OR-width percentile band (last 20 days)
    dict(both=True),                                            # both directions (one each)
    dict(cutoff=M(11, 0)), dict(cutoff=M(12, 0)),               # entry cutoff
)]

# ------------------------------------------------------------------------------------------------ OR-02 first 5-min candle
OR02_GRID = [dict(or_min=5, tf=tf, trigger="close", stop="opp", cutoff=M(13, 30), unds=UNDS4, pct_rng=pr, gap_dir=g,
                  pd_tgt=p)
             for tf in (1, 5) for pr in (None, (0.1, 0.6)) for g in (False, True) for p in (False, True)]

# ------------------------------------------------------------------------------------------------ OR-03 late / wide ORB
OR03_GRID = [dict(or_min=om, tf=1, trigger="close", stop="opp", cutoff=M(14, 30), start=st, tgt_pct=tp, unds=UNDS2)
             for om, st in ((30, None), (30, M(11, 15)), (60, None), (60, M(11, 15)), (120, None))
             for tp in (None, 0.2, 0.3, 0.5)]


# ------------------------------------------------------------------------------------------------ OR-04 Box 9
def or04_signals(mk, band=0.09, buf=0.0, stop_mode="close", tgt_r=None, unds=UNDS2):
    """Upper / lower = the first 5-min candle's close x (1 +- band%). First 5-min close beyond -> buy that way.
    Stop: the other band -/+ buf% of the index, on a 5-min CLOSE beyond it ('close') or touched ('touch').
    Target: tgt_r x (entry - stop) on the index (TP1 = 1R / TP2 = 2R taken whole; half-and-half not modelled)."""
    out = []
    for und in unds:
        ix = mk.index(und)
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            base = c[4]
            if not np.isfinite(base):
                continue
            up, dn = base * (1 + band / 100), base * (1 - band / 100)
            col, side = first_break(h, l, c, up, dn, 5, SQ - 5 - C.OPEN_M, tf=5)
            if not side:
                continue
            x = c[col]
            lvl = (dn - buf / 100 * base) if side > 0 else (up + buf / 100 * base)
            risk = abs(x - lvl)
            tgt = x + side * tgt_r * risk if tgt_r else np.nan
            row = dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"box9_{und}", idx_target=tgt)
            if stop_mode == "touch":
                row["idx_stop"] = lvl
            else:
                row["exit_at"] = close_exit(c, lvl, side, col + 1)
            out.append(row)
    return pd.DataFrame(out)


OR04_GRID = [dict(band=b, buf=bf, stop_mode=sm, tgt_r=t) for b in (0.05, 0.09, 0.15, 0.25) for bf in (0.0, 0.02)
             for sm in ("close", "touch") for t in (None, 1.0, 2.0)]


# ------------------------------------------------------------------------------------------------ OR-05 OHOL
def or05_signals(mk, check=M(9, 30), tol=0.0, pc_filter=False, unds=UNDS4):
    """At the check time: open == day low so far (within tol% of the open) -> buy CE; open == high -> PE (both -> none).
    pc_filter: the long also needs price above the previous close (short below). Index stop: the day's low / high at
    entry (minus / plus 0.01 so a retest of an exact low does not stop)."""
    out = []
    for und in unds:
        ix = mk.index(und)
        pcl = ix.daily().close.shift(1).to_dict()
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            col = check - 1 - C.OPEN_M          # the bar whose close is at the check time
            op = day_open(o, c)
            hi, lo = np.nanmax(h[: col + 1]), np.nanmin(l[: col + 1])
            x = last_valid(c, col)
            if not np.isfinite(op) or not np.isfinite(x):
                continue
            t = tol / 100 * op
            is_low, is_high = op - lo <= t + 1e-9, hi - op <= t + 1e-9
            if is_low == is_high:
                continue
            side = 1 if is_low else -1
            pc = pcl.get(d)
            if pc_filter and not (pc is not None and np.isfinite(pc) and (x - pc) * side > 0):
                continue
            lvl = lo - 0.01 if side > 0 else hi + 0.01
            if (x - lvl) * side <= 0:
                continue
            out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"ohol_{und}", idx_stop=lvl))
    return pd.DataFrame(out)


OR05_GRID = [dict(check=ck, tol=t, pc_filter=f) for ck in (M(9, 20), M(9, 25), M(9, 30)) for t in (0.0, 0.03, 0.05)
             for f in (False, True)]


# ------------------------------------------------------------------------------------------------ OR-06 premium momentum
_MEMO06 = {}


def _atm_legs(mk, und, d, col):
    """(strike, CE closes, PE closes) of the ATM strike frozen at column col (index close), near series; memoised."""
    k = (und, d, col)
    if k in _MEMO06:
        return _MEMO06[k]
    r = None
    ix = mk.index(und)
    o, h, l, c = day_arrays(ix, d)
    s = last_valid(c, col)
    ch = mk.options(und).chain(d, "near") if np.isfinite(s) else None
    if ch is not None:
        step = C.STEP[und]
        K = int(np.floor(s / step + 0.5) * step)
        i = ch.kpos(K)
        if i >= 0:
            r = (K, ch.c["C"][i].astype(np.float32), ch.c["P"][i].astype(np.float32))
    _MEMO06[k] = r
    return r


def or06_signals(mk, t0=M(9, 20), x=10.0, cutoff=M(14, 30), unds=("NIFTY", "BANKNIFTY", "SENSEX")):
    """At t0 record the ATM CE and PE (strike frozen at t0) last prices; buy the leg whose 1-min CLOSE first reaches
    +x% over its t0 price (the fill is the next minute's open). One-leg variant: the other leg is cancelled."""
    out = []
    for und in unds:
        ix = mk.index(und)
        for d in ix.days:
            if ix.d[d]["exp"]:
                continue                       # expiry days are skipped at execution anyway
            col0 = t0 - 1 - C.OPEN_M
            r = _atm_legs(mk, und, d, col0)
            if r is None:
                continue
            K, ce, pe = r
            rc, rp = last_valid(ce, col0), last_valid(pe, col0)
            if not (np.isfinite(rc) and np.isfinite(rp)) or rc <= 0 or rp <= 0:
                continue
            for col in range(col0 + 1, cutoff - C.OPEN_M):
                gc = ce[col] / rc - 1 if np.isfinite(ce[col]) else -9
                gp = pe[col] / rp - 1 if np.isfinite(pe[col]) else -9
                g = max(gc, gp)
                if g >= x / 100:
                    side = 1 if gc >= gp else -1
                    out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, strike=K, book=f"mom_{und}",
                                    tag=f"g={g:.3f}"))
                    break
        mk.release(und)
    return pd.DataFrame(out)


OR06_GRID = [dict(t0=t, x=x) for t in (M(9, 16), M(9, 20), M(9, 30)) for x in (5.0, 10.0, 15.0, 20.0)]


# ------------------------------------------------------------------------------------------------ OR-07 gap and go
def or07_signals(mk, gap=0.5, or_min=15, stop="or", cutoff=M(12, 0), unds=UNDS4):
    """Gap = open / previous close - 1 >= gap% (down: <= -gap%). After the first or_min minutes, the first 1-min close
    beyond the opening range in the gap's direction buys that way, if the index is on the right side of the
    time-weighted VWAP (no index volume). Index stop: the range's other side ('or'), or the tighter of that and the
    VWAP at entry ('tight')."""
    out = []
    for und in unds:
        ix = mk.index(und)
        pcl = ix.daily().close.shift(1).to_dict()
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            op, pc = day_open(o, c), pcl.get(d)
            if pc is None or not np.isfinite(pc) or not np.isfinite(op):
                continue
            g = (op / pc - 1) * 100
            if abs(g) < gap:
                continue
            sd = 1 if g > 0 else -1
            hi, lo = np.nanmax(h[:or_min]), np.nanmin(l[:or_min])
            vw = twap(h, l, c)
            up = np.where(c > vw, hi, np.nan)          # long needs price above the VWAP
            dn = np.where(c < vw, lo, np.nan)
            col, side = first_break(h, l, c, up, dn, or_min, cutoff - C.OPEN_M, tf=1, sides=(sd,))
            if not side:
                continue
            lvl = lo if side > 0 else hi
            if stop == "tight":
                lvl = max(lvl, vw[col]) if side > 0 else min(lvl, vw[col])
            if (c[col] - lvl) * side <= 0:
                continue
            out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"gapgo_{und}", idx_stop=lvl,
                            tag=f"gap={g:.2f}"))
    return pd.DataFrame(out)


OR07_GRID = [dict(gap=g, or_min=om, stop=st) for g in (0.3, 0.5, 0.8, 1.2) for om in (5, 15) for st in ("or", "tight")]


# ------------------------------------------------------------------------------------------------ OR-08 gap fill
def or08_signals(mk, lo_gap=0.3, hi_gap=0.8, trigger="touch", fill=1.0, cutoff=M(13, 0), unds=UNDS2):
    """Gap of lo_gap..hi_gap % (absolute). After 09:30: gap-down -> buy CE on a break of the first 15-min candle's
    HIGH; gap-up -> PE on a break of its LOW (1-min 'touch' or 5-min 'close'). Index stop just past the candle's other
    extreme; index target = fill x the gap back toward the previous close (1.0 = the previous close, 0.5 = half)."""
    out = []
    for und in unds:
        ix = mk.index(und)
        pcl = ix.daily().close.shift(1).to_dict()
        for d in ix.days:
            if not ix.d[d]["real"]:
                continue
            o, h, l, c = day_arrays(ix, d)
            op, pc = day_open(o, c), pcl.get(d)
            if pc is None or not np.isfinite(pc) or not np.isfinite(op):
                continue
            g = (op / pc - 1) * 100
            if not (lo_gap <= abs(g) <= hi_gap):
                continue
            sd = -1 if g > 0 else 1
            hi, lo = np.nanmax(h[:15]), np.nanmin(l[:15])
            col, side = first_break(h, l, c, hi, lo, 15, cutoff - C.OPEN_M, tf=5, trigger=trigger, sides=(sd,))
            if not side:
                continue
            x = c[col]
            tgt = op + fill * (pc - op)
            if (tgt - x) * side <= 0:
                continue                       # already filled
            buf = 0.0002 * x
            lvl = lo - buf if side > 0 else hi + buf
            out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"gapfill_{und}", idx_stop=lvl,
                            idx_target=tgt, tag=f"gap={g:.2f}"))
    return pd.DataFrame(out)


OR08_GRID = [dict(lo_gap=a, hi_gap=b, trigger=t, fill=f) for a, b in ((0.3, 0.8), (0.2, 0.8), (0.3, 1.2))
             for t in ("touch", "close") for f in (1.0, 0.5)]


# ------------------------------------------------------------------------------------------------ strategies
EXE = Execution(expiry="skip")
ATM, ITM1, OTM1 = StrikeRule(0), StrikeRule(1), StrikeRule(-1)
LEVELS = Exits(sq_off=SQ)                          # the signal's own index stop / target / exit_at, out 15:15
P30 = Exits(stop_pct=0.3, sq_off=SQ)               # + a -30% premium stop
TRAIL = Exits(stop_pct=0.3, pine_trail=True, sq_off=SQ)

STRATEGIES = [
    Strategy(name="ga_or01_orb15", family="opening_range", signal_fn=orb_signals, sig_grid=OR01_GRID,
             rules=[ATM, ITM1, OTM1], exits=[Exits(idx_tgt_r=r, sq_off=SQ) for r in (1.0, 2.0, 3.0)], exe=EXE,
             window=(M(9, 20), M(13, 30)),
             doc="OR-01 ORB: 15-min range, 5-min close, index stop opposite side, 1/2/3R; OFAT over length, bar, touch, "
                 "width band, both sides, cutoff x ATM/ITM1/OTM1."),
    Strategy(name="ga_or02_first5", family="opening_range", signal_fn=orb_signals, sig_grid=OR02_GRID,
             rules=[ATM, ITM1], exits=[Exits(idx_tgt_r=r, sq_off=SQ) for r in (1.0, 2.0, 3.0)], exe=EXE,
             window=(M(9, 20), M(13, 30)),
             doc="OR-02 first 5-min candle breakout: 1/5-min close x range 0.1-0.6% filter x gap direction x PDH/PDL "
                 "target x 1/2/3R x ATM/ITM1."),
    Strategy(name="ga_or03_late", family="opening_range", signal_fn=orb_signals, sig_grid=OR03_GRID,
             rules=[ATM, ITM1], exits=[LEVELS, P30], exe=EXE, window=(M(9, 45), M(14, 30)),
             doc="OR-03 late/wide ORB: 30/60/120-min range, from range end or 11:15, 1-min close, stop opposite side, "
                 "target 0.2/0.3/0.5% or none."),
    Strategy(name="ga_or04_box9", family="opening_range", signal_fn=or04_signals, sig_grid=OR04_GRID,
             rules=[ATM, ITM1], exits=[LEVELS], exe=EXE, window=(M(9, 20), M(15, 10)),
             doc="OR-04 Box 9: band 0.05-0.25% around the first 5-min close, 5-min close trigger, stop other band "
                 "(+0/0.02%) on close or touch, target 1R/2R/none."),
    Strategy(name="ga_or05_ohol", family="opening_range", signal_fn=or05_signals, sig_grid=OR05_GRID,
             rules=[ATM], exits=[Exits(idx_tgt_r=r, sq_off=SQ) for r in (1.0, 2.0)] + [LEVELS], exe=EXE,
             window=(M(9, 19), M(9, 29)),
             doc="OR-05 open=low / open=high at 09:20/25/30, tolerance 0/0.03/0.05%, prev-close filter, stop day "
                 "extreme, 1R/2R/EOD."),
    Strategy(name="ga_or06_premmom", family="opening_range", signal_fn=or06_signals, sig_grid=OR06_GRID,
             rules=[ATM],
             exits=[Exits(stop_pct=s, tgt_pct=t, sq_off=SQ) for s in (0.2, 0.3) for t in (0.4, 1.0)]
             + [Exits(stop_pct=s, pine_trail=True, sq_off=SQ) for s in (0.2, 0.3)], exe=EXE,
             window=(M(9, 16), M(14, 30)),
             doc="OR-06 which ATM leg's premium rises x% first after t0 (09:16/09:20/09:30, x 5-20%), SL 20/30%, "
                 "TP 40/100% or trail."),
    Strategy(name="ga_or07_gapgo", family="opening_range", signal_fn=or07_signals, sig_grid=OR07_GRID,
             rules=[ATM, ITM1], exits=[LEVELS, TRAIL, Exits(trail_pct=0.25, trail_arm=0.1, stop_pct=0.3, sq_off=SQ)],
             exe=EXE, window=(M(9, 20), M(12, 0)),
             doc="OR-07 gap-and-go: gap 0.3-1.2%, 5/15-min range break in the gap's way above/below TWAP, stop range "
                 "or tighter TWAP, trails."),
    Strategy(name="ga_or08_gapfill", family="opening_range", signal_fn=or08_signals, sig_grid=OR08_GRID,
             rules=[ATM, ITM1], exits=[LEVELS, P30, TRAIL], exe=EXE, window=(M(9, 30), M(13, 0)),
             doc="OR-08 gap fill: gap band, first 15-min candle break against the gap (touch/close), target prev close "
                 "or 50% fill."),
]
