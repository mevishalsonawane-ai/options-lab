"""Catalog family overnight_positional (POS-01, POS-02, POS-04 tested; POS-03 skipped: no stock options in the data).
All hold across days on the real next-day option prices (multiday.py composite paths).

POS-01 gc_pos01_btst   BTST: at 15:15 buy the nearest-expiry ATM / ITM1 call (unconditional), or only when the day
                       closed in the top 25% of its range AND above its time-weighted VWAP, optionally mirrored (put on
                       a bottom-25% close below VWAP); skip Fridays (weekend theta) or not; never on an expiry day
                       (the contract would die tonight) nor the night before a macro event session; sell next session
                       at 09:20 / 09:30. Exits: time only, or a -30% stop (an overnight gap through it fills at the open).
POS-02 gc_pos02_trend  Positional trend + Varsity strike matrix: daily Supertrend(10,3) flip or EMA20/EMA50 cross at a
                       close -> next session 09:20 buy the nearest-MONTHLY option that way. Strike: ATM, or Varsity's
                       table (first half of the series: 5-session target -> OTM2, 10 -> ATM; second half: 5 -> OTM1,
                       10 -> ITM1). Out at the earlier of: hold 5 / 10 sessions (15:15), the next session's 09:20 after
                       the trend reverses at a close, 3 sessions before expiry. Optional India VIX < its 60-day median.
                       Exits: those only, -35% stop, -35% + Pine trail.
POS-04 gc_pos04_nextwk Day after weekly expiry (or the session after that): buy the new weekly ATM / ITM1 at 09:20 in
                       the direction of the daily EMA20 (its slope, or close vs EMA20, as of the previous close); hold
                       to 15:20 of the session before its expiry. Optional India VIX < 60-day median. Exits: none, -30%,
                       -30% / +50%.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from ..multiday import MD_SQ, contract_expiry
from .base import Strategy
from .common import day_arrays
from .gc_common import ema, events, nth_session, supertrend, twap

U2 = ("NIFTY", "BANKNIFTY")
EOD = 15 * 60 + 14


def _hm(x):
    return (x // 100) * 60 + x % 100


def _vix_ok(mk):
    """{day: India VIX previous close < median of the 60 closes before it}."""
    s = mk.vix.daily.close
    med = s.rolling(60).median().shift(1)        # median of the 60 closes up to yesterday
    return (s.shift(1) < med).to_dict()          # yesterday's close below it


# ------------------------------------------------------------------------------------------------ POS-01
def btst_signals(mk, filt="none", mirror=False, xmin=920, skipfri=False, unds=U2):
    xm = _hm(xmin)
    col = EOD - C.OPEN_M
    out = []
    for und in unds:
        ix = mk.index(und)
        evd = set(events(ix, "macro").day)
        for i, d in enumerate(ix.days[:-1]):
            nd = ix.days[i + 1]
            if ix.d[d]["exp"] or nd in evd or (skipfri and d.weekday() == 4):
                continue
            o, h, l, c = day_arrays(ix, d)
            x = c[col]
            if not np.isfinite(x):
                continue
            side = 1
            if filt == "strong":
                hi, lo = np.nanmax(h[: col + 1]), np.nanmin(l[: col + 1])
                tw = twap(h, l, c)[col]
                loc = (x - lo) / (hi - lo) if hi > lo else 0.5
                if loc >= 0.75 and x > tw:
                    side = 1
                elif mirror and loc <= 0.25 and x < tw:
                    side = -1
                else:
                    continue
            out.append(dict(und=und, day=d, sig_min=EOD, side=side, book=f"btst_{und}", exit_day=nd, exit_min=xm))
    return pd.DataFrame(out)


# ------------------------------------------------------------------------------------------------ POS-02
def trend_signals(mk, sig="st", strike="varsity", hold=5, vix=False, unds=U2):
    vok = _vix_ok(mk) if vix else {}
    out = []
    for und in unds:
        ix = mk.index(und)
        step = C.STEP[und]
        dl = ix.daily()
        if sig == "st":
            tr = supertrend(dl).values
        else:
            tr = np.where(ema(dl.close.values, 20) > ema(dl.close.values, 50), 1, -1)
        days = list(dl.index)
        for i in range(51, len(days) - 1):
            if tr[i] == tr[i - 1]:
                continue
            side = int(tr[i])
            d = days[i + 1]                                   # entry: next session 09:20
            if vix and not vok.get(d, False):
                continue
            # reversal: first later close where the trend differs -> out next session 09:20
            rev = None
            for j in range(i + 1, len(days) - 1):
                if tr[j] != side:
                    rev = days[j + 1]
                    break
            mexp = contract_expiry(ix, d, "month")
            if mexp is None:
                continue
            cap = nth_session(ix, mexp, -3)
            xd = nth_session(ix, d, hold)
            if cap is None or xd is None:
                continue
            xd, xm = min(xd, cap), 15 * 60 + 15
            if rev is not None and rev <= xd:
                xd, xm = rev, 9 * 60 + 20
            if xd <= d:
                continue
            k = np.nan
            if strike == "varsity":
                _, _, _, c = day_arrays(ix, d)
                sp = c[9 * 60 + 19 - C.OPEN_M]
                if not np.isfinite(sp):
                    continue
                first_half = (mexp - d).days > 14
                money = {(True, 5): -2, (True, 10): 0, (False, 5): -1, (False, 10): 1}[(first_half, hold)]
                k = float(np.floor(sp / step + 0.5) * step - side * money * step)
            out.append(dict(und=und, day=d, sig_min=9 * 60 + 19, side=side, book=f"trend_{und}", strike=k,
                            exit_day=xd, exit_min=xm))
    return pd.DataFrame(out)


# ------------------------------------------------------------------------------------------------ POS-04
def nextwk_signals(mk, trend="slope", lag=1, vix=False, unds=("NIFTY", "BANKNIFTY", "SENSEX")):
    vok = _vix_ok(mk) if vix else {}
    out = []
    for und in unds:
        ix = mk.index(und)
        opts = mk.options(und)
        dl = ix.daily()
        e20 = pd.Series(ema(dl.close.values, 20), index=dl.index)
        days = ix.days
        for i, x in enumerate(days):
            if not ix.d[x]["exp"]:
                continue
            d = nth_session(ix, x, lag)
            if d is None or ix.d[d]["exp"]:
                continue
            if opts.resolve(d, "week") is None:
                continue
            nx = contract_expiry(ix, d, "week")
            if nx is None:
                continue
            xd = nth_session(ix, nx, -1)
            if xd is None or xd <= d:
                continue
            if vix and not vok.get(d, False):
                continue
            p = ix.pos[d]
            e_prev, e_prev2, c_prev = e20.iloc[p - 1], e20.iloc[p - 2], dl.close.iloc[p - 1]
            if trend == "slope":
                side = 1 if e_prev > e_prev2 else -1
            else:
                side = 1 if c_prev > e_prev else -1
            out.append(dict(und=und, day=d, sig_min=9 * 60 + 19, side=side, book=f"nw_{und}", exit_day=xd,
                            exit_min=15 * 60 + 20))
        mk.release(und)
    return pd.DataFrame(out)


STRATEGIES = [
    Strategy(
        name="gc_pos01_btst", family="overnight_positional", signal_fn=btst_signals,
        sig_grid=([dict(filt="none", xmin=m, skipfri=f) for m in (920, 930) for f in (False, True)]
                  + [dict(filt="strong", mirror=mi, xmin=m, skipfri=f) for mi in (False, True) for m in (920, 930)
                     for f in (False, True)]),
        rules=[StrikeRule(0), StrikeRule(1)], exe=Execution(expiry="skip"),
        exits=[Exits(sq_off=MD_SQ), Exits(stop_pct=0.3, sq_off=MD_SQ)],
        pos=dict(one_at_a_time=True), window=(14 * 60 + 45, EOD),
        doc="POS-01 BTST 15:15 -> next 09:20/09:30, unconditional or strong-close filter (+ put mirror)."),
    Strategy(
        name="gc_pos02_trend", family="overnight_positional", signal_fn=trend_signals,
        sig_grid=[dict(sig=s, strike=k, hold=h, vix=v) for s in ("st", "ema") for k in ("varsity", "atm") for h in (5, 10)
                  for v in (False, True)],
        rules=[StrikeRule(0, series="month")], exe=Execution(expiry="skip"),
        exits=[Exits(sq_off=MD_SQ), Exits(stop_pct=0.35, sq_off=MD_SQ), Exits(stop_pct=0.35, pine_trail=True, sq_off=MD_SQ)],
        pos=dict(one_at_a_time=True), window=(9 * 60 + 16, 10 * 60),
        doc="POS-02 daily Supertrend / EMA cross, monthly option by the Varsity strike matrix, 5/10-session hold."),
    Strategy(
        name="gc_pos04_nextwk", family="overnight_positional", signal_fn=nextwk_signals,
        sig_grid=[dict(trend=t, lag=g, vix=v) for t in ("slope", "close") for g in (1, 2) for v in (False, True)],
        rules=[StrikeRule(0, series="week"), StrikeRule(1, series="week")], exe=Execution(expiry="skip"),
        exits=[Exits(stop_pct=0.3, tgt_pct=0.5, sq_off=MD_SQ), Exits(sq_off=MD_SQ), Exits(stop_pct=0.3, sq_off=MD_SQ)],
        pos=dict(one_at_a_time=True), window=(9 * 60 + 16, 10 * 60),
        doc="POS-04 the session(s) after a weekly expiry: new weekly ATM/ITM1 with the EMA20 trend, to T-1."),
]
