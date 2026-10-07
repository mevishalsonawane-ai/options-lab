"""Catalog family mean_reversion (MR-01 .. MR-06), as option BUYING on index minutes (research/option_buying_catalog.json).

Conventions as in gb_trend.py: tf-minute candles anchored 09:15, indicators continuous across days, signal = candle
close (or, for 'break' triggers, the 1-minute bar that trades through the alert level; the option is bought at the next
minute's open, a market-order approximation of a stop-entry), new entries until 14:30, expiry days skipped, ATM unless
stated, 'VWAP' = TWAP (no index volume).
"""
from __future__ import annotations

import warnings

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, LADDER, StrikeRule
from .base import Strategy
from .common import prev_day
from .gb_ind import (Bars, CUT, adx, cross_dn, cross_up, ema, exit_at_from, finish, frame, next_event, rsi, shift, sma,
                     stdev)

ALL4 = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")
NB = ("NIFTY", "BANKNIFTY")
SQ10, SQ15 = 15 * 60 + 10, 15 * 60 + 15
WIN = (9 * 60 + 20, 14 * 60 + 30)
EXE = Execution(expiry="skip")


def _alert_trades(und, b, alert, side, trig, rr, book, cut=CUT):
    """Power-of-Stocks alert-candle logic on Bars b. alert[i]: candle i is an alert (fully above the EMA / band for a PE
    setup, fully below for a CE setup). A later alert candle replaces the current one; the alert is cleared at the day
    start and when it triggers. Trigger: 'break' = a 1-min bar trades beyond the alert low (PE) / high (CE);
    'close' = a candle closes beyond it. Index stop: the alert's other extreme; index target rr x the risk."""
    H, L = b.M["h"], b.M["l"]
    rows = []
    al = None
    for i in range(b.n):
        if b.newday[i]:
            al = None
        if al is not None:
            ah, alo = al
            lvl = alo if side < 0 else ah
            sm = None
            if trig == "break":
                seg = (L if side < 0 else H)[b.di[i], b.s[i]:b.e[i]]
                with np.errstate(invalid="ignore"):
                    hit = np.nonzero(seg < lvl if side < 0 else seg > lvl)[0]
                if len(hit):
                    sm, E = int(b.s[i] + hit[0] + C.OPEN_M), lvl
            elif (b.c[i] < lvl) if side < 0 else (b.c[i] > lvl):
                sm, E = int(b.sig[i]), b.c[i]
            if sm is not None:
                al = None
                stop = ah if side < 0 else alo
                risk = side * (E - stop)
                if sm <= cut and b.real[i] and risk > 0:
                    rows.append(dict(und=und, day=b.day[i], sig_min=sm, side=side, idx_stop=float(stop),
                                     idx_target=float(E + side * rr * risk), book=f"{book}_{und}", tag=""))
                continue
        if alert[i]:
            al = (b.h[i], b.l[i])
    return pd.DataFrame(rows)


# ------------------------------------------------------------------------------------------------ MR-01 5-EMA
def ema5_alert(mk, tf_s=5, tf_l=15, n=5, rr=3.0, trig="break", unds=NB, years=None):
    """Power of Stocks 5-EMA: PE setups on tf_s candles fully ABOVE EMA(n) (low > EMA), entry on the break of the alert
    low; CE setups on tf_l candles fully BELOW EMA(n) (high < EMA), entry on the break of the alert high. Stop: the
    alert's other extreme; target rr x risk."""
    out = []
    for und in unds:
        ix = mk.index(und)
        bs = Bars(ix, tf_s)
        out.append(_alert_trades(und, bs, bs.l > ema(bs.c, n), -1, trig, rr, "gbe5"))
        bl = bs if tf_l == tf_s else Bars(ix, tf_l)
        out.append(_alert_trades(und, bl, bl.h < ema(bl.c, n), 1, trig, rr, "gbe5"))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ MR-02 Bollinger alert
def bb_alert(mk, tf=5, k=1.5, rr=4.0, trig="break", combined=False, unds=ALL4, years=None):
    """Power of Stocks Bollinger alert: a candle fully above the upper BB(20, k) is a PE alert (break of its low enters),
    fully below the lower band a CE alert (break of its high). combined: the candle must also be fully beyond EMA5."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        mid, sd = sma(b.c, 20), stdev(b.c, 20)
        with np.errstate(invalid="ignore"):
            up, lo = b.l > mid + k * sd, b.h < mid - k * sd
            if combined:
                e5 = ema(b.c, 5)
                up &= b.l > e5
                lo &= b.h < e5
        out.append(_alert_trades(und, b, up, -1, trig, rr, "gbbb"))
        out.append(_alert_trades(und, b, lo, 1, trig, rr, "gbbb"))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ MR-03 RSI 30/70
def rsi_reversal(mk, tf=5, n=14, low=30, unds=NB, years=None):
    """RSI(n) crosses back above `low` -> CE; back below 100 - low -> PE (premium SL/TP exits)."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        r = rsi(b.c, n)
        ok = b.ok()
        out.append(frame(und, b, np.nonzero(cross_up(r, float(low)) & ok)[0], 1, "gbrr"))
        out.append(frame(und, b, np.nonzero(cross_dn(r, float(100 - low)) & ok)[0], -1, "gbrr"))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ MR-04 Camarilla
def camarilla(mk, tf=5, level=3, tgt="P", unds=ALL4, years=None):
    """Camarilla fade: a candle that trades at/above R{level} and closes back below it -> PE (S mirror -> CE). Only on days
    opening inside S3..R3, candles starting 09:30 or later, the first signal per side per day. Index stop: the next
    level out (R4 for R3, R3 for R2); index target: pivot P, previous close C, or R1/S1."""
    out = []
    F = {1: 12, 2: 6, 3: 4, 4: 2}
    for und in unds:
        ix = mk.index(und)
        b = Bars(ix, tf)
        pdd = prev_day(ix)
        PH = np.array([pdd[d][1] for d in b.day])
        PL = np.array([pdd[d][2] for d in b.day])
        PC = np.array([pdd[d][3] for d in b.day])
        rg = 1.1 * (PH - PL)
        R = {k: PC + rg / f for k, f in F.items()}
        S = {k: PC - rg / f for k, f in F.items()}
        piv = (PH + PL + PC) / 3
        dopen = b.M["o"][b.di, 0]
        dopen = np.where(np.isnan(dopen), b.o, dopen)
        with np.errstate(invalid="ignore"):
            inside = (dopen > S[3]) & (dopen < R[3])
            ok = b.ok() & inside & (b.s >= 15)
            su = ok & (b.l <= S[level]) & (b.c > S[level])
            sd = ok & (b.h >= R[level]) & (b.c < R[level])
        tg_u = {"P": piv, "C": PC, "R1": S[1]}[tgt]
        tg_d = {"P": piv, "C": PC, "R1": R[1]}[tgt]
        for side, m, st, tg in ((1, su, S[level + 1], tg_u), (-1, sd, R[level + 1], tg_d)):
            with np.errstate(invalid="ignore"):
                m = m & (side * (tg - b.c) > 0) & (side * (b.c - st) > 0)
            idx = np.nonzero(m)[0]
            if len(idx):
                first = ~pd.Series(b.di[idx]).duplicated().values
                idx = idx[first]
            out.append(frame(und, b, idx, side, "gbcam", idx_stop=st, idx_target=tg))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ MR-05 VWAP stretch
def vwap_stretch(mk, tf=5, k=2.0, filt=None, tgt="static", unds=ALL4, years=None):
    """A candle closes more than k standard deviations (running SD of the 1-min typical price) above the TWAP (and the
    previous one did not) -> PE toward the TWAP (mirror CE). Index stop: one more SD out (fixed at entry). Target:
    'static' = the TWAP level at entry; 'dynamic' = out when a candle closes back across the (moving) TWAP.
    filt 'adx20': only when ADX(14) on the candles is below 20 (range-bound). Entries from 09:45."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        tw, sd = b.twap()
        up, lo = tw + k * sd, tw - k * sd
        with np.errstate(invalid="ignore"):
            su = (b.c < lo) & ~((shift(b.c) < shift(lo)) & ~b.newday)
            sd_ = (b.c > up) & ~((shift(b.c) > shift(up)) & ~b.newday)
        ok = b.ok(lo=9 * 60 + 44)
        if filt == "adx20":
            ok &= adx(b.h, b.l, b.c, 14)[0] < 20
        for side, m, st, xev in ((1, su & ok, tw - (k + 1) * sd, b.c >= tw), (-1, sd_ & ok, tw + (k + 1) * sd, b.c <= tw)):
            if tgt == "static":
                out.append(frame(und, b, np.nonzero(m)[0], side, "gbvs", idx_stop=st, idx_target=tw))
            else:
                out.append(frame(und, b, np.nonzero(m)[0], side, "gbvs", idx_stop=st,
                                 exit_at=exit_at_from(next_event(xev, b.di), b.sig)))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ MR-06 OR fade
def or_fade(mk, or_min=15, tf=5, unds=("BANKNIFTY",), years=None):
    """Opening-range fade: after the first or_min minutes, price pokes above the range high and a tf-min candle closes
    back inside -> PE (mirror CE below the low). First signal per side per day; premium SL/TP exits."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        M = b.M
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            orh = np.nanmax(M["h"][:, :or_min], axis=1)
            orl = np.nanmin(M["l"][:, :or_min], axis=1)
            hs = np.fmax.accumulate(np.where(np.isnan(M["h"]), -np.inf, M["h"])[:, or_min:], axis=1)
            ls = np.fmin.accumulate(np.where(np.isnan(M["l"]), np.inf, M["l"])[:, or_min:], axis=1)
        cc = np.maximum(b.col - or_min, 0)
        H, L = hs[b.di, cc], ls[b.di, cc]
        oh, ol = orh[b.di], orl[b.di]
        ok = b.ok() & (b.s >= or_min) & np.isfinite(oh)
        with np.errstate(invalid="ignore"):
            sd = ok & (H > oh) & (b.c < oh) & (b.c > ol)
            su = ok & (L < ol) & (b.c > ol) & (b.c < oh)
        for side, m in ((1, su), (-1, sd)):
            idx = np.nonzero(m)[0]
            if len(idx):
                idx = idx[~pd.Series(b.di[idx]).duplicated().values]
            out.append(frame(und, b, idx, side, "gbof"))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ grids
ATM, ITM1 = StrikeRule(0), StrikeRule(1)

STRATEGIES = [
    Strategy(
        name="gb_mr01_5ema", family="mean_reversion", signal_fn=ema5_alert,
        sig_grid=[dict(tf_s=a, tf_l=c, n=n, rr=rr, trig=t) for a, c in ((5, 15), (5, 5), (15, 15)) for n in (5, 9)
                  for rr in (2.0, 3.0, 4.0) for t in ("break", "close")],
        rules=[ATM, ITM1], exits=[Exits(sq_off=SQ15)], exe=EXE, window=WIN,
        doc="MR-01 Power of Stocks 5-EMA alert candle (PE on 5-min, CE on 15-min), stop alert extreme, target 2/3/4R."),
    Strategy(
        name="gb_mr02_bb_alert", family="mean_reversion", signal_fn=bb_alert,
        sig_grid=[dict(tf=tf, k=k, rr=rr, trig=t, combined=cb) for tf in (5, 15) for k in (1.5, 2.0) for rr in (2.0, 3.0, 4.0)
                  for t in ("break", "close") for cb in (False, True)],
        rules=[ATM, ITM1], exits=[Exits(sq_off=SQ15)], exe=EXE, window=WIN,
        doc="MR-02 Bollinger(20, 1.5/2) alert candle, break/close trigger, target 2/3/4R, EMA5 combined filter on/off."),
    Strategy(
        name="gb_mr03_rsi_reversal", family="mean_reversion", signal_fn=rsi_reversal,
        sig_grid=[dict(tf=tf, n=n, low=lo) for tf in (5, 15) for n in (9, 14) for lo in (30, 20)],
        rules=[ATM],
        exits=[Exits(stop_pts=40, tgt_pts=80, sq_off=SQ10), Exits(stop_pts=20, tgt_pts=40, sq_off=SQ10),
               Exits(stop_pct=0.2, tgt_pct=0.4, sq_off=SQ10), Exits(stop_pct=0.3, tgt_pct=0.6, sq_off=SQ10),
               Exits(stop_pct=0.25, tgt_pct=0.5, sq_off=SQ10), Exits(sq_off=SQ10)],
        exe=EXE, window=WIN,
        doc="MR-03 RSI back inside 30/70 (or 20/80), premium SL/TP (repo 40/80 points and % variants)."),
    Strategy(
        name="gb_mr04_camarilla", family="mean_reversion", signal_fn=camarilla,
        sig_grid=[dict(tf=tf, level=lv, tgt=tg) for tf in (5, 15) for lv in (3, 2) for tg in ("P", "C", "R1")],
        rules=[ATM, ITM1],
        exits=[Exits(sq_off=SQ15), Exits(stop_pct=0.3, sq_off=SQ15), Exits(stop_pct=0.3, pine_trail=True, sq_off=SQ15)],
        exe=EXE, window=(9 * 60 + 30, 14 * 60 + 30),
        doc="MR-04 Camarilla R3/S3 (or R2/S2) rejection fade, stop next level, target pivot / prev close / R1."),
    Strategy(
        name="gb_mr05_vwap_stretch", family="mean_reversion", signal_fn=vwap_stretch,
        sig_grid=[dict(tf=tf, k=k, filt=f, tgt=tg) for tf in (5, 15) for k in (1.5, 2.0, 2.5) for f in (None, "adx20")
                  for tg in ("static", "dynamic")],
        rules=[ATM, ITM1], exits=[Exits(sq_off=SQ15), Exits(stop_pct=0.3, sq_off=SQ15)],
        exe=EXE, window=(9 * 60 + 45, 14 * 60 + 30),
        doc="MR-05 TWAP +-k SD stretch fade toward the TWAP, stop one SD further, ADX<20 filter on/off."),
    Strategy(
        name="gb_mr06_or_fade", family="mean_reversion", signal_fn=or_fade,
        sig_grid=[dict(or_min=om, tf=tf) for om in (15, 30) for tf in (1, 5)],
        rules=[ATM, ITM1],
        exits=[Exits(stop_pts=40, tgt_pts=30, sq_off=SQ10), Exits(stop_pts=40, tgt_pts=60, sq_off=SQ10),
               Exits(stop_pts=20, tgt_pts=40, sq_off=SQ10), Exits(stop_pct=0.3, tgt_pct=0.3, sq_off=SQ10),
               Exits(stop_pct=0.3, tgt_pct=0.6, sq_off=SQ10), Exits(stop_pts=40, tgt_pts=60, ladder=LADDER, ladder_ref_pts=60, sq_off=SQ10)],
        exe=EXE, pos=dict(one_at_a_time=True, max_per_day=2), window=(9 * 60 + 30, 14 * 60 + 30),
        doc="MR-06 BANKNIFTY opening-range fade (poke out, close back in), premium SL/TP (repo -40 / +30 / +60 points)."),
]
