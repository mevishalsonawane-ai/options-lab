"""Catalog family trend_indicator (TI-01 .. TI-10), as option BUYING on index minutes (research/option_buying_catalog.json).

Common conventions (see research/OBUY_GB.md):
- Candles of tf minutes anchored 09:15; indicators run continuously across days (chart convention). A signal is a
  candle CLOSE; the option is bought at the next minute's open (engine). 'VWAP' = TWAP of the 1-min typical price
  (no index volume). New entries until 14:30 (signal minute <= 14:29); expiry days skipped (the arms' rule); ATM unless
  stated; app fills and charges; lot as of each date.
- 'Opposite signal' exits are the signal's exit_at (closed at the open after that candle's close). Index stops are
  touch-based on the 1-min low / high, decided at the minute close, filled at the next open (engine).
- Flip / cross strategies that reverse use separate CE and PE books, so the reversal entry is not blocked by the exit.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from .base import Strategy
from .gb_ind import (Bars, CUT, adx, cross_dn, cross_up, ema, exit_at_from, finish, frame, heikin_ashi, next_event,
                     rolling_max, rolling_min, rsi, shift, supertrend)
from .common import prev_day

ALL4 = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")
NB = ("NIFTY", "BANKNIFTY")
SQ10, SQ15 = 15 * 60 + 10, 15 * 60 + 15
WIN = (9 * 60 + 20, 14 * 60 + 30)
EXE = Execution(expiry="skip")


def _risk_ok(side, ref, stop):
    with np.errstate(invalid="ignore"):
        return np.where(side > 0, ref - stop, stop - ref) > 0


# ------------------------------------------------------------------------------------------------ TI-01 Supertrend flip
def st_flip(mk, tf=5, n=10, mult=3.0, adx_min=None, skip=1, unds=ALL4, years=None):
    """Supertrend(n, mult) flips on tf-min closes: green flip buys CE, red flip buys PE; out at the opposite flip
    (= the Supertrend-line stop, close based). Skip the first `skip` candles of the day. Optional ADX(14) > adx_min."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        d, _ = supertrend(b.h, b.l, b.c, n, mult)
        pd_ = shift(d.astype(float))
        up, dn = (d == 1) & (pd_ == -1), (d == -1) & (pd_ == 1)
        ok = b.ok() & (b.bi >= skip)
        if adx_min:
            ok &= adx(b.h, b.l, b.c, 14)[0] > adx_min
        xu, xd = exit_at_from(next_event(dn, b.di), b.sig), exit_at_from(next_event(up, b.di), b.sig)
        out.append(frame(und, b, np.nonzero(up & ok)[0], 1, "gbst", exit_at=xu, per_side=True))
        out.append(frame(und, b, np.nonzero(dn & ok)[0], -1, "gbst", exit_at=xd, per_side=True))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ TI-02 Supertrend + EMA
def st_ema(mk, tf=15, n=10, mult=3.0, ef=20, es=50, unds=NB, years=None):
    """Supertrend flip up AND EMA(ef) > EMA(es) buys CE (mirror PE); out at the opposite Supertrend flip."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        d, _ = supertrend(b.h, b.l, b.c, n, mult)
        pd_ = shift(d.astype(float))
        up, dn = (d == 1) & (pd_ == -1), (d == -1) & (pd_ == 1)
        f, s = ema(b.c, ef), ema(b.c, es)
        ok = b.ok()
        xu, xd = exit_at_from(next_event(dn, b.di), b.sig), exit_at_from(next_event(up, b.di), b.sig)
        out.append(frame(und, b, np.nonzero(up & ok & (f > s))[0], 1, "gbste", exit_at=xu, per_side=True))
        out.append(frame(und, b, np.nonzero(dn & ok & (f < s))[0], -1, "gbste", exit_at=xd, per_side=True))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ TI-03 EMA cross
def ema_cross(mk, tf=5, fast=9, slow=21, filt=None, stop="cross", unds=ALL4, years=None):
    """EMA(fast) crosses EMA(slow) on a closed candle: CE above, PE below; out at the opposite cross. filt='twap': only
    with the close on the same side of the TWAP. stop='swing': also an index stop at the 5-candle swing low / high and an
    index target at 2R."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        f, s = ema(b.c, fast), ema(b.c, slow)
        up, dn = cross_up(f, s), cross_dn(f, s)
        ok = b.ok()
        cu, cd = ok.copy(), ok.copy()
        if filt == "twap":
            tw, _ = b.twap()
            cu &= b.c > tw
            cd &= b.c < tw
        xu, xd = exit_at_from(next_event(dn, b.di), b.sig), exit_at_from(next_event(up, b.di), b.sig)
        for side, ev, cc, xa in ((1, up, cu, xu), (-1, dn, cd, xd)):
            st = tg = None
            m = ev & cc
            if stop == "swing":
                st = rolling_min(b.l, 5) if side > 0 else rolling_max(b.h, 5)
                tg = b.c + 2 * (b.c - st)
                m &= _risk_ok(side, b.c, st)
            out.append(frame(und, b, np.nonzero(m)[0], side, "gbec", idx_stop=st, idx_target=tg, exit_at=xa, per_side=True))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ TI-04 EMA9 x TWAP
def ema_twap(mk, tf=5, n=9, unds=("NIFTY",), years=None):
    """EMA(n) crosses above the TWAP: buy 1-ITM CE; below: 1-ITM PE; out (and reverse) at the opposite cross."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        tw, _ = b.twap()
        e = ema(b.c, n)
        up, dn = cross_up(e, tw) & ~b.newday, cross_dn(e, tw) & ~b.newday   # TWAP restarts each day
        ok = b.ok()
        xu, xd = exit_at_from(next_event(dn, b.di), b.sig), exit_at_from(next_event(up, b.di), b.sig)
        out.append(frame(und, b, np.nonzero(up & ok)[0], 1, "gbev", exit_at=xu, per_side=True))
        out.append(frame(und, b, np.nonzero(dn & ok)[0], -1, "gbev", exit_at=xd, per_side=True))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ TI-05 VWAP pullback
def vwap_pullback(mk, dist=0.001, conf="green", chop=None, tgt="2R", trend_n=6, unds=NB, years=None):
    """5-min. Trend: the trend_n closes before the pullback candle all above the TWAP and the TWAP rising over them.
    Pullback candle's low within `dist` of the TWAP; the next candle (conf 'green' bullish, or 'engulf' bullish
    engulfing) closes above the TWAP -> CE (mirror PE). Chop filter: <= chop TWAP crosses in the last 12 candles.
    Index stop: the lower of the pullback swing low and the TWAP. Target: 2R / 3R / prior-day high (else 2R)."""
    out = []
    for und in unds:
        ix = mk.index(und)
        b = Bars(ix, 5)
        pdd = prev_day(ix)
        pdh = np.array([pdd[d][1] for d in b.day])
        pdl = np.array([pdd[d][2] for d in b.day])
        tw, _ = b.twap()
        n = b.n
        above, below = b.c > tw, b.c < tw
        lag = lambda a, k: shift(a.astype(float), k) if k else a.astype(float)  # noqa: E731
        span_ok = b.same_day_lag(trend_n + 1)
        tr_up = np.ones(n, bool)
        tr_dn = np.ones(n, bool)
        for k in range(2, trend_n + 2):
            tr_up &= lag(above, k) == 1
            tr_dn &= lag(below, k) == 1
        tw1, twk = shift(tw, 1), shift(tw, trend_n + 1)
        tr_up &= span_ok & (tw1 > twk)
        tr_dn &= span_ok & (tw1 < twk)
        pb_up = shift(b.l, 1) <= shift(tw, 1) * (1 + dist)
        pb_dn = shift(b.h, 1) >= shift(tw, 1) * (1 - dist)
        po, pc = shift(b.o, 1), shift(b.c, 1)
        if conf == "engulf":
            cu = (b.c > b.o) & (pc < po) & (b.c >= po) & (b.o <= pc)
            cd = (b.c < b.o) & (pc > po) & (b.c <= po) & (b.o >= pc)
        else:
            cu, cd = b.c > b.o, b.c < b.o
        sigu = tr_up & pb_up & cu & above
        sigd = tr_dn & pb_dn & cd & below
        if chop is not None:
            x = np.r_[False, (above[1:] != above[:-1]) & (b.di[1:] == b.di[:-1])].astype(int)
            nx = pd.Series(x).rolling(12, min_periods=1).sum().values
            sigu &= nx <= chop
            sigd &= nx <= chop
        ok = b.ok()
        for side, m in ((1, sigu & ok), (-1, sigd & ok)):
            if side > 0:
                st = np.fmin(np.fmin(shift(b.l, 1), b.l), tw)
                pdx = pdh
            else:
                st = np.fmax(np.fmax(shift(b.h, 1), b.h), tw)
                pdx = pdl
            r = side * (b.c - st)
            m = m & (r > 0)
            if tgt == "pdh":
                tg = np.where(side * (pdx - b.c) > 0, pdx, b.c + side * 2 * r)
            else:
                tg = b.c + side * float(tgt[:-1]) * r
            out.append(frame(und, b, np.nonzero(m)[0], side, "gbvp", idx_stop=st, idx_target=tg))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ TI-06 RSI 50
def rsi_regime(mk, tf=5, n=14, thr=50, unds=("NIFTY",), years=None):
    """RSI(n) crosses above thr: buy ATM CE; below 100 - thr: ATM PE. One trade a day (the first cross, EOD hold)."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        r = rsi(b.c, n)
        ok = b.ok()
        f = pd.concat([frame(und, b, np.nonzero(cross_up(r, float(thr)) & ok)[0], 1, "gbrsi"),
                       frame(und, b, np.nonzero(cross_dn(r, float(100 - thr)) & ok)[0], -1, "gbrsi")])
        out.append(f.sort_values("sig_min", kind="stable").drop_duplicates("day"))     # the day's first signal only
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ TI-07 RSI range shift
def rsi_shift(mk, tf=15, version="breakout", hi=60, lo=40, stop="rsi", tgt="2R", look=10, unds=ALL4, years=None):
    """RSI(14) range shift. breakout: RSI crosses above hi -> CE (below lo -> PE). pullback: in a bull regime (RSI was
    > hi within `look` candles and has not closed below lo since) RSI dips into lo..50 and turns up -> CE (mirror PE).
    stop 'rsi': out when RSI closes back below lo (above hi for PE); 'swing': index stop at the 3-candle swing.
    tgt '2R': index target 2 x the swing risk; 'rsi80': out when RSI > 80 (< 20 for PE)."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        r = rsi(b.c, 14)
        r1 = shift(r)
        if version == "breakout":
            su, sd = cross_up(r, float(hi)), cross_dn(r, float(lo))
        else:
            rs = pd.Series(r)
            was_hi = rs.rolling(look, min_periods=1).max().values > hi
            was_lo = rs.rolling(look, min_periods=1).min().values < lo
            min_since = rs.rolling(look, min_periods=1).min().values
            max_since = rs.rolling(look, min_periods=1).max().values
            su = was_hi & (min_since >= lo) & (r1 >= lo) & (r1 <= 50) & (r > r1)
            sd = was_lo & (max_since <= hi) & (r1 <= hi) & (r1 >= 50) & (r < r1)
        ok = b.ok()
        for side, m in ((1, su & ok), (-1, sd & ok)):
            sw = rolling_min(b.l, 3) if side > 0 else rolling_max(b.h, 3)
            risk = side * (b.c - sw)
            m = m & (risk > 0)
            evs = []
            if stop == "rsi":
                evs.append(r < lo if side > 0 else r > hi)
            if tgt == "rsi80":
                evs.append(r > 80 if side > 0 else r < 20)
            xa = None
            if evs:
                big = b.n + 1
                nn = np.min([np.where(x < 0, big, x) for x in (next_event(e, b.di) for e in evs)], axis=0)
                xa = exit_at_from(np.where(nn == big, -1, nn), b.sig)
            st = sw if stop == "swing" else None
            tg = b.c + side * 2 * risk if tgt == "2R" else None
            out.append(frame(und, b, np.nonzero(m)[0], side, "gbrs", idx_stop=st, idx_target=tg, exit_at=xa))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ TI-08 Heikin-Ashi
def ha_trend(mk, tf=5, nc=3, tol=0.0, ema_n=None, exit_mode="red", unds=NB, years=None):
    """Heikin-Ashi: the nc-th consecutive strong green HA candle with no lower wick (wick <= tol x range) buys CE
    (mirror PE). Optional: HA close above EMA(ema_n) of the close. Index stop: the signal HA candle's low. Out at the
    first red HA candle ('red') or the first doji-like candle with wicks on both sides ('doji')."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        ho, hh, hl, hc = heikin_ashi(b.o, b.h, b.l, b.c)
        rng = np.maximum(hh - hl, 1e-9)
        lw = np.minimum(ho, hc) - hl
        uw = hh - np.maximum(ho, hc)
        g = (hc > ho) & (lw <= tol * rng)
        rd = (hc < ho) & (uw <= tol * rng)
        def run(x):
            cnt = np.zeros(len(x), int)
            k = 0
            for i, v in enumerate(x):
                k = k + 1 if v else 0
                cnt[i] = k
            return cnt
        cg, cr = run(g), run(rd)
        su, sd = cg == nc, cr == nc
        if ema_n:
            e = ema(b.c, ema_n)
            su &= hc > e
            sd &= hc < e
        if exit_mode == "red":
            xu_ev, xd_ev = hc < ho, hc > ho
        else:
            doji = (lw > 0.1 * rng) & (uw > 0.1 * rng) & (np.abs(hc - ho) <= 0.5 * rng)
            xu_ev = xd_ev = doji
        ok = b.ok()
        for side, m, ev, st in ((1, su & ok, xu_ev, hl), (-1, sd & ok, xd_ev, hh)):
            m = m & _risk_ok(side, b.c, st)
            out.append(frame(und, b, np.nonzero(m)[0], side, "gbha", idx_stop=st,
                             exit_at=exit_at_from(next_event(ev, b.di), b.sig)))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ TI-09 ADX / DMI
def adx_dmi(mk, tf=5, thr=25, n=14, stop="swing", adx_exit=True, unds=ALL4, years=None):
    """ADX(n) > thr and rising with +DI > -DI on a closed candle; buy CE when a 1-min bar of the NEXT candle trades
    above the setup candle's high (mirror PE below its low). stop 'swing': index stop at the 3-candle swing low, target
    2R; 'twap': index stop at the TWAP, target 1R. adx_exit: out when ADX turns down on a candle close."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        a, pdi, mdi = adx(b.h, b.l, b.c, n)
        rising = a > shift(a)
        su = (a > thr) & rising & (pdi > mdi)
        sd = (a > thr) & rising & (mdi > pdi)
        tw, _ = b.twap()
        H, L = b.M["h"], b.M["l"]
        down = a < shift(a)
        nx = next_event(down, b.di)
        okb = b.real
        rows = []
        for side, m in ((1, su & okb), (-1, sd & okb)):
            for i in np.nonzero(m)[0]:
                j = i + 1
                if j >= b.n or b.di[j] != b.di[i]:
                    continue
                lvl = b.h[i] if side > 0 else b.l[i]
                seg = (H if side > 0 else L)[b.di[j], b.s[j]:b.e[j]]
                hit = np.nonzero(seg > lvl if side > 0 else seg < lvl)[0]
                if not len(hit):
                    continue
                sm = int(b.s[j] + hit[0] + C.OPEN_M)
                if sm > CUT:
                    continue
                if stop == "swing":
                    st = (min(b.l[max(i - 2, 0):i + 1]) if side > 0 else max(b.h[max(i - 2, 0):i + 1]))
                    k = 2.0
                else:
                    st, k = tw[i], 1.0
                r = side * (lvl - st)
                if not r > 0:
                    continue
                xa = np.nan
                if adx_exit:
                    jj = nx[i]
                    while jj >= 0 and b.sig[jj] < sm:
                        jj = nx[jj]
                    xa = b.sig[jj] + 1 if jj >= 0 else np.nan
                rows.append(dict(und=und, day=b.day[i], sig_min=sm, side=side, ref_spot=float(lvl), idx_stop=float(st),
                                 idx_target=float(lvl + side * k * r), exit_at=xa, book=f"gbadx_{und}", tag=""))
        out.append(pd.DataFrame(rows))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ TI-10 confluence
def confluence(mk, tf=5, conds=("st", "ema", "twap", "rsi"), rsi_thr=55, tgt="2R", unds=NB, years=None):
    """All of `conds` agree (Supertrend(10,3) green, EMA9 > EMA21, close > TWAP, RSI(14) > rsi_thr) on a candle close
    where they did not all agree on the previous one -> CE (mirror PE). Index stop: the Supertrend line at entry; out
    at the opposite Supertrend flip (the trail); tgt '2R': index target 2 x the risk to the line."""
    out = []
    for und in unds:
        b = Bars(mk.index(und), tf)
        d, line = supertrend(b.h, b.l, b.c, 10, 3.0)
        tw, _ = b.twap()
        e9, e21 = ema(b.c, 9), ema(b.c, 21)
        r = rsi(b.c, 14)
        bull = np.ones(b.n, bool)
        bear = np.ones(b.n, bool)
        for cnd in conds:
            u, v = {"st": (d == 1, d == -1), "ema": (e9 > e21, e9 < e21), "twap": (b.c > tw, b.c < tw),
                    "rsi": (r > rsi_thr, r < 100 - rsi_thr)}[cnd]
            bull &= u
            bear &= v
        su = bull & ~(shift(bull.astype(float)) == 1)
        sd = bear & ~(shift(bear.astype(float)) == 1)
        pd_ = shift(d.astype(float))
        fu, fd = (d == 1) & (pd_ == -1), (d == -1) & (pd_ == 1)
        ok = b.ok()
        for side, m, xev in ((1, su & ok, fd), (-1, sd & ok, fu)):
            m = m & _risk_ok(side, b.c, line)
            tg = b.c + 2 * (b.c - line) if tgt == "2R" else None
            out.append(frame(und, b, np.nonzero(m)[0], side, "gbcf", idx_stop=line, idx_target=tg,
                             exit_at=exit_at_from(next_event(xev, b.di), b.sig)))
    return finish(out, years)


# ------------------------------------------------------------------------------------------------ grids
ATM, ITM1 = StrikeRule(0), StrikeRule(1)

STRATEGIES = [
    Strategy(
        name="gb_ti01_supertrend", family="trend_indicator", signal_fn=st_flip,
        sig_grid=[dict(tf=tf, n=n, mult=m, adx_min=a) for tf in (3, 5, 15)
                  for n, m in ((7, 3.0), (10, 3.0), (14, 3.0), (10, 2.0), (10, 1.5)) for a in (None, 20)],
        rules=[ATM],
        exits=[Exits(sq_off=SQ10), Exits(stop_pct=0.3, pine_trail=True, sq_off=SQ10),
               Exits(stop_pct=0.25, tgt_pct=0.5, sq_off=SQ10)],
        exe=EXE, window=WIN,
        doc="TI-01 Supertrend flip (skip first candle), out at the opposite flip: tf 3/5/15 x (ATR, mult) x ADX>20 on/off x 3 exits."),
    Strategy(
        name="gb_ti02_st_ema", family="trend_indicator", signal_fn=st_ema,
        sig_grid=[dict(tf=tf, ef=ef, es=es, mult=m) for tf in (5, 15) for ef, es in ((20, 50), (9, 21)) for m in (3.0, 2.0)],
        rules=[ATM, ITM1],
        exits=[Exits(sq_off=SQ10), Exits(stop_pts=40, tgt_pts=80, sq_off=SQ10), Exits(stop_pct=0.3, tgt_pct=0.6, sq_off=SQ10),
               Exits(stop_pct=0.2, tgt_pct=0.4, sq_off=SQ10)],
        exe=EXE, window=WIN,
        doc="TI-02 Supertrend(10, 3/2) flip with EMA alignment, out at the opposite flip or premium SL/TP."),
    Strategy(
        name="gb_ti03_ema_cross", family="trend_indicator", signal_fn=ema_cross,
        sig_grid=[dict(tf=tf, fast=f, slow=s, filt=fl, stop=st) for tf in (3, 5, 15) for f, s in ((5, 20), (9, 21), (13, 34), (9, 50))
                  for fl in (None, "twap") for st in ("cross", "swing")],
        rules=[ATM, ITM1], exits=[Exits(sq_off=SQ15)], exe=EXE, window=WIN,
        doc="TI-03 EMA fast/slow cross, out at the opposite cross (or swing stop + 2R), TWAP-side filter on/off."),
    Strategy(
        name="gb_ti04_ema9_twap", family="trend_indicator", signal_fn=ema_twap,
        sig_grid=[dict(tf=tf) for tf in (3, 5, 15)],
        rules=[ITM1],
        exits=[Exits(tgt_pct=t, stop_pct=s, sq_off=SQ15) for t in (0.05, 0.08, 0.12, 0.20) for s in (None, 0.1, 0.2)],
        exe=EXE, window=WIN,
        doc="TI-04 NIFTY EMA9 x TWAP cross, 1-ITM, premium target 5/8/12/20%, SL none/10/20%, out at the opposite cross."),
    Strategy(
        name="gb_ti05_vwap_pullback", family="trend_indicator", signal_fn=vwap_pullback,
        sig_grid=[dict(dist=ds, conf=cf, chop=ch, tgt=tg) for ds in (0.001, 0.002) for cf in ("green", "engulf")
                  for ch in (None, 2) for tg in ("2R", "3R", "pdh")],
        rules=[ATM], exits=[Exits(sq_off=SQ15), Exits(stop_pct=0.3, pine_trail=True, sq_off=SQ15)],
        exe=EXE, pos=dict(one_at_a_time=True, max_per_day=3), window=(9 * 60 + 55, 14 * 60 + 30),
        doc="TI-05 TWAP pullback continuation (5-min), stop at the swing/TWAP, target 2R/3R/prior-day extreme."),
    Strategy(
        name="gb_ti06_rsi50", family="trend_indicator", signal_fn=rsi_regime,
        sig_grid=[dict(tf=tf, n=n, thr=th) for tf in (3, 5, 15) for n in (9, 14) for th in (50, 55, 60)],
        rules=[ATM],
        exits=[Exits(stop_pct=0.25, tgt_pct=0.5, sq_off=SQ15), Exits(stop_pct=0.25, tgt_pct=0.5, pine_trail=True, sq_off=SQ15),
               Exits(stop_pct=0.25, tgt_pct=0.5, trail_pct=0.15, trail_arm=0.15, sq_off=SQ15),
               Exits(stop_pct=0.2, tgt_pct=0.4, sq_off=SQ15), Exits(stop_pct=0.3, tgt_pct=0.6, sq_off=SQ15)],
        exe=EXE, pos=dict(one_at_a_time=True, max_per_day=1), window=WIN,
        doc="TI-06 NIFTY RSI cross 50 (or 55/45, 60/40), first signal of the day, -25%/+50% (+ trail variants), EOD."),
    Strategy(
        name="gb_ti07_rsi_shift", family="trend_indicator", signal_fn=rsi_shift,
        sig_grid=[dict(tf=tf, version=v, hi=hi, lo=lo, stop=st, tgt=tg) for tf in (15, 60) for v in ("breakout", "pullback")
                  for hi, lo in ((60, 40), (55, 45)) for st in ("rsi", "swing") for tg in ("2R", "rsi80")],
        rules=[ATM], exits=[Exits(sq_off=SQ15), Exits(stop_pct=0.3, sq_off=SQ15)],
        exe=EXE, pos=dict(one_at_a_time=True, max_per_day=2), window=(10 * 60, 14 * 60 + 30),
        doc="TI-07 RSI 60/40 range shift (breakout / pullback), 15/60-min, intraday only."),
    Strategy(
        name="gb_ti08_heikin_ashi", family="trend_indicator", signal_fn=ha_trend,
        sig_grid=[dict(tf=tf, nc=k, tol=t, ema_n=e, exit_mode=x) for tf in (5, 15) for k in (1, 2, 3) for t in (0.0, 0.1)
                  for e in (None, 50) for x in ("red", "doji")],
        rules=[ATM], exits=[Exits(sq_off=SQ15)], exe=EXE, window=WIN,
        doc="TI-08 Heikin-Ashi wickless run of 1/2/3, stop at the HA low, out at the first red / doji HA candle."),
    Strategy(
        name="gb_ti09_adx", family="trend_indicator", signal_fn=adx_dmi,
        sig_grid=[dict(tf=tf, thr=th, n=n, stop=st, adx_exit=ax) for tf in (5, 15) for th in (20, 25, 30) for n in (10, 14)
                  for st in ("swing", "twap") for ax in (True, False)],
        rules=[ATM], exits=[Exits(sq_off=SQ15)], exe=EXE, pos=dict(one_at_a_time=True, max_per_day=3), window=WIN,
        doc="TI-09 ADX>thr rising with DI alignment, entry on the next candle's break of the setup high/low."),
    Strategy(
        name="gb_ti10_confluence", family="trend_indicator", signal_fn=confluence,
        sig_grid=[dict(tf=tf, conds=c, rsi_thr=r, tgt=tg) for tf in (5, 15)
                  for c, r in ((("st", "ema", "twap", "rsi"), 55), (("st", "ema", "twap", "rsi"), 60), (("st", "ema", "twap"), 55),
                               (("st", "twap", "rsi"), 55), (("st", "twap", "rsi"), 60), (("st", "ema", "rsi"), 55),
                               (("st", "ema", "rsi"), 60))
                  for tg in ("2R", "trail")],
        rules=[ATM], exits=[Exits(sq_off=SQ15), Exits(stop_pct=0.3, sq_off=SQ15)],
        exe=EXE, pos=dict(one_at_a_time=True, max_per_day=3), window=WIN,
        doc="TI-10 Supertrend + EMA9/21 + TWAP + RSI confluence (subsets), stop at the ST line, 2R or ST trail."),
]
