"""R104 family F5: swing rules on daily bars (PREREG.md; mechanics from research/books/TESTABLE_RULES.md). Committed
before this family's first run.

Markets: DLY (index daily 2006+, a near-future proxy), MCXD (back-adjusted near future 2012+), STK (stock-future proxy),
IDX / IDXF for the rules that trade intraday or on 1-minute triggers. Readings fixed here:
- Daily stop-entries fill at max(open, level) on the trigger day (a gap through the level fills at the open); "skip a
  trigger hit by a gap" removes those fills (smith-01, smith-07).
- Larry Swing's re-armed trigger: one buy-stop a day at the previous day's high + 0.07 ATR(14) for 5 sessions, one-
  cancels-other across the 5 days; the half at +2.3 ATR is a sub-lot with that limit target, the rest trails at the
  previous day's low - 0.07 ATR; 20-session backstop.
- Graeme-Smith's trail: after each close that made a new extreme since the entry and is not an inside day, the stop
  moves to that day's far end minus the buffer (the midpoint minus the buffer on a >= 5% / >= 2.5 ATR day).
- SWING_DEEP's hosts (lswing-05, smith-03, smith-07): Donchian 20-day close breakout, the >200-DMA 3-down-closes
  pullback, the NR7 / inside-day stop entry both sides; the host's own exit for the comparison is SWING_DEEP's
  "2ATR stop + 3ATR chandelier (max 20d)". These three tests pass only if the book's exit (or filter) beats the host on
  the same entries in design and in every fold, as well as making money.
- Event and news blackouts with no historical calendar before 2024 (Sadowski 02, Smith 04) are dropped (labelled).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import r104data as DA
import r104ind as I
from f3_patterns import _daily_bars
from r104pat import NAN, Pat, gid
from r104run import rule

MCX_ALL = ("GOLD", "SILVER", "CRUDE", "NATURALGAS")
P_AFTER_20D = Pat.after_fill(20 * 375)      # 20 sessions of NSE minutes


def DP(ctx, n_sw=2):
    return Pat(ctx, 1, swing_n=n_sw, bars=_daily_bars(ctx))


def _rows(ctx, P, arr, dtype=float):
    return ctx.to_rows(P.b, arr, dtype)


# ------------------------------------------------------------------------------------------------ Woods 04
def _key_reversal(ctx, hold):
    P = DP(ctx)
    b, out = P.b, []
    for k in range(1, P.n - 1):
        if b.o[k] < b.l[k - 1] and b.c[k] > b.h[k - 1]:
            lvl = b.h[k] + P.tick
            tg = P.target_swing(k, 1, lvl) if hold == 0 else NAN
            out.append(P.stop_order(k, 1, lvl, 1, b.l[k], tg, tmax=P.after_fill(hold if hold else 10)))
        if b.o[k] > b.h[k - 1] and b.c[k] < b.l[k - 1]:
            lvl = b.l[k] - P.tick
            tg = P.target_swing(k, -1, lvl) if hold == 0 else NAN
            out.append(P.stop_order(k, -1, lvl, 1, b.h[k], tg, tmax=P.after_fill(hold if hold else 10)))
    return out, None, None


KR = {"h1": dict(hold=1), "h3": dict(hold=3), "h5": dict(hold=5), "swing": dict(hold=0)}


@rule("book-woods-04", "DLY", KR)
def woods04(ctx, hold):
    return _key_reversal(ctx, hold)


@rule("book-woods-04", "MCXD", KR, insts=("GOLD", "CRUDE"))
def woods04_m(ctx, hold):
    return _key_reversal(ctx, hold)


# ------------------------------------------------------------------------------------------------ Ozenbas 01
def _ma65(ctx, long_only):
    P = DP(ctx)
    b, out = P.b, []
    sm = I.sma(b.c, 65)
    up = I.crossed_above(b.c, sm)
    dn = I.crossed_below(b.c, sm)
    for k in range(66, P.n - 1):
        a = P.atr[k]
        if up[k]:
            out.append(P.mkt(k, 1, b.c[k] - 2 * a))
        elif dn[k] and not long_only:
            out.append(P.mkt(k, -1, b.c[k] + 2 * a))
    return out, None, {1: _rows(ctx, P, dn, bool), -1: _rows(ctx, P, up, bool)}


OZ = {"ls": dict(long_only=False), "long": dict(long_only=True)}


@rule("book-ozenbas-01", "DLY", OZ)
def ozenbas01(ctx, long_only):
    return _ma65(ctx, long_only)


@rule("book-ozenbas-01", "MCXD", OZ, insts=("GOLD", "SILVER", "CRUDE"))
def ozenbas01_m(ctx, long_only):
    return _ma65(ctx, long_only)


# ------------------------------------------------------------------------------------------------ Sadowski 01 / 02
def _sma_cross(ctx, filt):
    P = DP(ctx)
    b, out = P.b, []
    s20, s60, s100 = I.sma(b.c, 20), I.sma(b.c, 60), I.sma(b.c, 100)
    up = I.crossed_above(s20, s60)
    dn = I.crossed_below(s20, s60)
    for k in range(101, P.n - 1):
        if up[k] and (not filt or b.c[k] > s100[k]):
            out.append(P.mkt(k, 1, NAN))
        if dn[k] and (not filt or b.c[k] < s100[k]):
            out.append(P.mkt(k, -1, NAN))
    return out, None, {1: _rows(ctx, P, dn, bool), -1: _rows(ctx, P, up, bool)}


SC = {"plain": dict(filt=False), "sma100": dict(filt=True)}


@rule("book-sadowski-01", "DLY", SC)
def sad01(ctx, filt):
    return _sma_cross(ctx, filt)


@rule("book-sadowski-01", "MCXD", SC, insts=("GOLD", "CRUDE"))
def sad01_m(ctx, filt):
    return _sma_cross(ctx, filt)


def _ha_stoch(ctx):
    P = DP(ctx)
    b, out = P.b, []
    ho, hh, hl, hc = I.heikin_ashi(b.o, b.h, b.l, b.c)
    g = hc > ho
    r = hc < ho
    kk, _d = I.stoch(b.h, b.l, b.c, 14, 7, 3)
    sh, shi, sl, sli = P.swings(2)
    setup_up = np.zeros(P.n, bool)
    setup_dn = np.zeros(P.n, bool)
    for k in range(6, P.n - 1):
        a = P.atr[k]
        if a != a or P.rng[k - 1] > 2 * a:
            continue
        if g[k] and g[k - 1] and r[k - 4:k - 1].all() and np.nanmin(kk[k - 4:k + 1]) < 30:
            setup_up[k] = True
            lvl = hh[k] + 0.1 * a
            stop = max(lvl - a, sl[k]) if sl[k] == sl[k] and sl[k] < lvl else lvl - a
            out.append(P.stop_order(k, 1, lvl, 3, stop, be=0.5 * a / max(lvl - stop, 1e-9)))
        if r[k] and r[k - 1] and g[k - 4:k - 1].all() and np.nanmax(kk[k - 4:k + 1]) > 70:
            setup_dn[k] = True
            lvl = hl[k] - 0.1 * a
            stop = min(lvl + a, sh[k]) if sh[k] == sh[k] and sh[k] > lvl else lvl + a
            out.append(P.stop_order(k, -1, lvl, 3, stop, be=0.5 * a / max(stop - lvl, 1e-9)))
    trail = {1: _rows(ctx, P, sl), -1: _rows(ctx, P, sh)}
    xf = {1: _rows(ctx, P, setup_dn, bool), -1: _rows(ctx, P, setup_up, bool)}
    return out, trail, xf


@rule("book-sadowski-02", "DLY", {"base": dict(_tab=True)})
def sad02(ctx):
    return _ha_stoch(ctx)


@rule("book-sadowski-02", "MCXD", {"base": dict(_tab=True)}, insts=("GOLD", "SILVER", "CRUDE"))
def sad02_m(ctx):
    return _ha_stoch(ctx)


# ------------------------------------------------------------------------------------------------ Sadowski 10 (IDXF)
@rule("book-sadowski-10", "IDXF", {"base": dict(trend=False, _multiday=True), "trend50": dict(trend=True, _multiday=True)})
def sad10(ctx, trend):
    s = ctx.s
    d = ctx.daily
    d = d[d.index >= s.days[0]]
    a = I.atr(d.high.values, d.low.values, d.close.values, 14)
    kd, _ = I.stoch(d.high.values, d.low.values, d.close.values, 14, 3, 3)
    dd = d.copy()
    dd.index = pd.to_datetime(dd.index)
    wk = dd.resample("W-FRI").agg({"high": "max", "low": "min", "close": "last"}).dropna()
    kw, _ = I.stoch(wk.high.values, wk.low.values, wk.close.values, 14, 3, 3)
    wk_k = pd.Series(kw, index=wk.index)
    sm50 = I.sma(d.close.values, 50)
    hi60 = I.rolling_max(d.high.values, 60)
    lo60 = I.rolling_min(d.low.values, 60)
    P = Pat(ctx, 60)
    b, out = P.b, []
    day_pos = {dt: i for i, dt in enumerate(d.index)}
    ses_of_day = {dt: i for i, dt in enumerate(s.days)}
    for i in range(70, len(d) - 1):
        dt = d.index[i]
        last_w = wk_k[wk_k.index <= pd.Timestamp(dt)]        # completed weeks only (a week's label is its Friday)
        if not len(last_w):
            continue
        kwv = last_w.iloc[-1]
        c = d.close.values
        for side in (-1, 1):
            if side < 0:
                ok = kd[i] > 70 and kwv > 70 and c[i] - c[i - 10] > 2 * a[i] and hi60[i] - c[i] <= 0.5 * a[i]
                sw = hi60[i]
            else:
                ok = kd[i] < 30 and kwv < 30 and c[i - 10] - c[i] > 2 * a[i] and c[i] - lo60[i] <= 0.5 * a[i]
                sw = lo60[i]
            if not ok or (trend and (c[i] > sm50[i]) != (side > 0)):
                continue
            # over the next 5 sessions: a 60-minute close beyond the prior 60-minute bar's extreme
            nxt = [dt2 for dt2 in d.index[i + 1:i + 6] if dt2 in ses_of_day]
            if not nxt:
                continue
            s0 = ses_of_day[nxt[0]]
            s1 = ses_of_day[nxt[-1]]
            k0 = int(np.searchsorted(b.ses, s0))
            k1 = int(np.searchsorted(b.ses, s1, side="right"))
            for k in range(max(k0, 1), min(k1, P.n - 1)):
                hit = b.c[k] < b.l[k - 1] if side < 0 else b.c[k] > b.h[k - 1]
                if hit:
                    stop = sw + 0.25 * a[i] if side < 0 else sw - 0.25 * a[i]
                    risk = abs(b.c[k] - stop)
                    # 10 sessions = about 10 x 375 minutes of the series
                    out.append(P.mkt(k, side, stop, b.c[k] + side * 2 * risk, tmax=P.after_fill(10 * 375)))
                    break
    return out, None, None


# ------------------------------------------------------------------------------------------------ Larry Swing
def _master_plan(P, k, side, atr_k, stocks=False, hold=20, tgt_mult=2.3, buf=0.07, init_mult=1.3):
    """Larry Swing's 5-session re-armed stop entry with the book's stop, half target and trail. The two halves of a
    day share one position (grp '<setup>|<day>'); the 5 days are one-cancels-other (the key '<setup>')."""
    b = P.b
    out = []
    g = gid("mp")
    for j in range(0, 5):
        kk = k + j
        if kk >= P.n - 1:
            break
        if not stocks:
            lvl = b.h[kk] + buf * atr_k if side > 0 else b.l[kk] - buf * atr_k
            ref = (b.l[kk] - buf * atr_k) if side > 0 else (b.h[kk] + buf * atr_k)
            stop = max(lvl - init_mult * atr_k, ref) if side > 0 else min(lvl + init_mult * atr_k, ref)
            tgt = lvl + side * tgt_mult * atr_k
        else:                                    # stocks: the book's percentages
            lvl = b.h[kk] * 1.002 if side > 0 else b.l[kk] * 0.998
            ref = b.l[kk] * 0.998 if side > 0 else b.h[kk] * 1.002
            stop = max(lvl * 0.96, ref) if side > 0 else min(lvl * 1.04, ref)
            tgt = lvl * (1 + side * 0.07)
        grp = f"{g}|{j}"
        for w, tg in ((0.5, tgt), (0.5, NAN)):
            out.append((P.start(kk), side, 1, float(lvl), int(P.b.i1[kk + 1]), float(stop), float(tg),
                        P.after_fill(hold), NAN, grp, w, float(lvl)))
    return out


def _forceswing(ctx, force=True, stocks=False):
    P = DP(ctx)
    b, out = P.b, []
    c = b.c
    s10, s20 = I.sma(c, 10), I.sma(c, 20)
    fi = I.force_index(c, ctx.s.v)
    f3, f13 = I.sma(np.nan_to_num(fi), 3), I.sma(np.nan_to_num(fi), 13)
    _p, _m, adx = I.dmi(b.h, b.l, c, 20)
    for k in range(25, P.n - 1):
        a = P.atr[k]
        if a != a:
            continue
        if force and not np.isfinite(fi[k - 13:k + 1]).all():
            continue
        if force:
            L = c[k] > s10[k] and c[k] > s20[k] and b.h[k] < b.h[k - 1] < b.h[k - 2] and f3[k] <= 0 <= f13[k] and adx[k] > 30
            S = c[k] < s10[k] and c[k] < s20[k] and b.l[k] > b.l[k - 1] > b.l[k - 2] and f3[k] >= 0 >= f13[k] and adx[k] > 30
        else:
            L = c[k] > s10[k] and c[k] > s20[k] and s10[k] > s20[k] and b.h[k] < b.h[k - 1] < b.h[k - 2]
            S = c[k] < s10[k] and c[k] < s20[k] and s10[k] < s20[k] and b.l[k] > b.l[k - 1] > b.l[k - 2]
        for side, ok in ((1, L), (-1, S)):
            if ok:
                out += _master_plan(P, k, side, a, stocks=stocks)
    trail = {1: _rows(ctx, P, (b.l * (1 - 0.002)) if stocks else b.l - 0.07 * P.atr),
             -1: _rows(ctx, P, (b.h * (1 + 0.002)) if stocks else b.h + 0.07 * P.atr)}
    return out, trail, None


@rule("book-lswing-01", "DLY")
def lswing01(ctx):
    return _forceswing(ctx, True)


@rule("book-lswing-02", "MCXD", insts=MCX_ALL)
def lswing02(ctx):
    return _forceswing(ctx, True)


R58 = ("RELIANCE", "SBIN", "MARUTI", "ICICIBANK", "INFY", "LT", "KOTAKBANK", "M&M", "TRENT", "TATASTEEL")


@rule("book-lswing-03", "STK", insts=R58)
def lswing03(ctx):
    return _forceswing(ctx, True, stocks=True)


@rule("book-lswing-04", "DLY")
def lswing04(ctx):
    return _forceswing(ctx, False)


# ------------------------------------------------------------------------------------------------ SWING_DEEP hosts
def host_signals(P):
    """SWING_DEEP's three hosts as (k, kind, side): kind 'mkt' (next open) or 'oco' (NR7 / inside stop entries)."""
    b = P.b
    c = b.c
    hi20 = np.r_[np.nan, I.rolling_max(b.h, 20)[:-1]]
    lo20 = np.r_[np.nan, I.rolling_min(b.l, 20)[:-1]]
    sma200 = I.sma(c, 200)
    out = []
    for k in range(203, P.n - 1):
        if c[k] > hi20[k]:
            out.append((k, "don", 1))
        elif c[k] < lo20[k]:
            out.append((k, "don", -1))
        dn3 = c[k] < c[k - 1] < c[k - 2] < c[k - 3]
        up3 = c[k] > c[k - 1] > c[k - 2] > c[k - 3]
        if c[k] > sma200[k] and dn3:
            out.append((k, "pb", 1))
        elif c[k] < sma200[k] and up3:
            out.append((k, "pb", -1))
        rng = P.rng
        nr7 = rng[k] <= rng[k - 6:k + 1].min()
        inside = b.h[k] < b.h[k - 1] and b.l[k] > b.l[k - 1]
        if nr7 or inside:
            out.append((k, "nr", 0))
    return out


def _host_intents(P, sig, exit_kind, gap_skip=0.0):
    """exit_kind: 'host' (2 ATR stop + 3 ATR chandelier, max 20), 'mp' (Larry Swing's master-plan exits: stop, half at
    +2.3 ATR, prior-low trail, 20 days), 'smith' (significant-day trail, max 20)."""
    b, out = P.b, []
    for k, kind, side in sig:
        a = P.atr[k]
        if a != a:
            continue
        legs = []
        if kind == "nr":
            g = gid("nr")
            for sd in (1, -1):
                lvl = b.h[k] if sd > 0 else b.l[k]
                legs.append((sd, 1, lvl, g + ("|L" if sd > 0 else "|S")))
        else:
            legs.append((side, 0, b.c[k], gid("h")))
        for sd, kind_, lvl, g in legs:
            if gap_skip >= 0 and kind_ == 1 and k + 1 < P.n:
                gap = (b.o[k + 1] - lvl) * sd
                if gap > gap_skip * lvl:
                    continue                      # the trigger was hit by the open's gap
            if exit_kind == "host":
                stop = lvl - sd * 2 * a
                t = (P.start(k), sd, kind_, float(lvl) if kind_ else NAN, int(P.b.i1[min(k + 1, P.n - 1)]), float(stop),
                     NAN, P.after_fill(20), NAN, g, 1.0, float(lvl))
                out.append(t)
            elif exit_kind == "mp":
                stop = max(lvl - 1.3 * a, b.l[k] - 0.07 * a) if sd > 0 else min(lvl + 1.3 * a, b.h[k] + 0.07 * a)
                for w, tg in ((0.5, lvl + sd * 2.3 * a), (0.5, NAN)):
                    out.append((P.start(k), sd, kind_, float(lvl) if kind_ else NAN, int(P.b.i1[min(k + 1, P.n - 1)]),
                                float(stop), float(tg), P.after_fill(20), NAN, g, w, float(lvl)))
            else:
                stop = lvl - sd * 2 * a
                out.append((P.start(k), sd, kind_, float(lvl) if kind_ else NAN, int(P.b.i1[min(k + 1, P.n - 1)]),
                            float(stop), NAN, P.after_fill(20), NAN, g, 1.0, float(lvl)))
    return out


def _smith_trail_arrays(ctx, P, buf_pct=0.005, buf_atr=None, big_pct=0.05, big_atr=None):
    b = P.b
    a = P.atr
    buf = b.c * buf_pct if buf_atr is None else buf_atr * a
    inside = np.r_[False, (b.h[1:] <= b.h[:-1]) & (b.l[1:] >= b.l[:-1])]
    mid = (b.h + b.l) / 2
    up_big = ((b.c - b.o) >= (b.o * big_pct if big_atr is None else big_atr * a)) | \
             ((b.h - b.l) >= (b.o * big_pct if big_atr is None else big_atr * a))
    dn_big = ((b.o - b.c) >= (b.o * big_pct if big_atr is None else big_atr * a)) | \
             ((b.h - b.l) >= (b.o * big_pct if big_atr is None else big_atr * a))
    tl = np.where(inside, np.nan, np.where(up_big, mid - buf, b.l - buf))
    ts = np.where(inside, np.nan, np.where(dn_big, mid + buf, b.h + buf))
    return {1: _rows(ctx, P, tl), -1: _rows(ctx, P, ts)}


def _chandelier(ctx, P):
    return {1: _rows(ctx, P, 3 * P.atr), -1: _rows(ctx, P, 3 * P.atr)}


@rule("book-lswing-05", "DLY", {"mp": dict()})
def lswing05(ctx):
    P = DP(ctx)
    trail = {1: _rows(ctx, P, P.b.l - 0.07 * P.atr), -1: _rows(ctx, P, P.b.h + 0.07 * P.atr)}
    return _host_intents(P, host_signals(P), "mp", gap_skip=-1), trail, None


def swing_host(ctx):
    """SWING_DEEP's own exit on its hosts (the comparison for lswing-05 and smith-03; not a test)."""
    P = DP(ctx)
    return _host_intents(P, host_signals(P), "host", gap_skip=-1), _chandelier(ctx, P), None


rule("book-lswing-05~host", "DLY", {"host": dict(_tmode=2)})(swing_host)
rule("book-smith-03~host", "DLY", {"host": dict(_tmode=2)})(swing_host)


@rule("book-smith-03", "DLY", {"pct": dict(buf="pct", _tmode=1), "atr": dict(buf="atr", _tmode=1)})
def smith03(ctx, buf):
    P = DP(ctx)
    tr = _smith_trail_arrays(ctx, P) if buf == "pct" else _smith_trail_arrays(ctx, P, buf_atr=0.25, big_atr=2.5)
    return _host_intents(P, host_signals(P), "smith", gap_skip=-1), tr, None


def _stop_entry_hosts(ctx, g):
    """NR7 / inside-day and Donchian next-day stop entries, with the gap skip g (fraction; < 0 = no skip)."""
    P = DP(ctx)
    sig = [x for x in host_signals(P) if x[1] == "nr"]
    b = P.b
    hi20 = I.rolling_max(b.h, 20)
    lo20 = I.rolling_min(b.l, 20)
    out = _host_intents(P, sig, "host", gap_skip=g)
    for k in range(25, P.n - 1):
        a = P.atr[k]
        if a != a:
            continue
        gp = gid("dn")
        for sd, lvl in ((1, hi20[k]), (-1, lo20[k])):
            if g >= 0 and (b.o[k + 1] - lvl) * sd > g * lvl:
                continue
            out.append((P.start(k), sd, 1, float(lvl), int(b.i1[k + 1]), float(lvl - sd * 2 * a), NAN, P.after_fill(20),
                        NAN, gp + ("|L" if sd > 0 else "|S"), 1.0, float(lvl)))
    return out, _chandelier(ctx, P), None


@rule("book-smith-07", "DLY", {"skip": dict(g=0.0, _tmode=2), "skip25": dict(g=0.0025, _tmode=2)})
def smith07(ctx, g):
    return _stop_entry_hosts(ctx, g)


@rule("book-smith-07~host", "DLY", {"host": dict(g=-1.0, _tmode=2)})
def smith07_host(ctx, g):
    return _stop_entry_hosts(ctx, g)


# ------------------------------------------------------------------------------------------------ Graeme-Smith SST
def _sst_setups(P, b):
    """(k, side, ref bar): SMA50 side + DMI(8) cross or touch-and-widen; the reference = the last non-inside bar."""
    pdi, mdi, _adx = I.dmi(b.h, b.l, b.c, 8)
    sm50 = I.sma(b.c, 50)
    out = []
    for k in range(60, P.n - 1):
        d = pdi[k] - mdi[k]
        dp = pdi[k - 1] - mdi[k - 1]
        if d != d or dp != dp:
            continue
        cross_up = d > 0 >= dp
        cross_dn = d < 0 <= dp
        apart = np.nanmax(np.abs(pdi[k - 5:k] - mdi[k - 5:k])) > 3.0
        touched = abs(dp) <= 1.0 and apart
        widen_up = touched and d > dp and d > 0
        widen_dn = touched and d < dp and d < 0
        ref = k
        while ref > 0 and b.h[ref] <= b.h[ref - 1] and b.l[ref] >= b.l[ref - 1]:
            ref -= 1
        if (cross_up or widen_up) and b.c[k] > sm50[k]:
            out.append((k, 1, ref))
        if (cross_dn or widen_dn) and b.c[k] < sm50[k]:
            out.append((k, -1, ref))
    return out, I.sma(b.c, 200)


def _sst(ctx, P, buf, nbars=3, intraday=False, hold=-1):
    b, out = P.b, []
    setups, sm200 = _sst_setups(P, b)
    a = P.atr
    for k, side, ref in setups:
        if intraday and not P.ok_time(k, lo=9 * 60 + 15, hi=14 * 60 + 15):
            continue
        if a[k] != a[k]:
            continue
        bf = (b.c[k] * 0.005) if buf == "pct" else 0.25 * a[k]
        lvl = b.h[ref] + P.tick if side > 0 else b.l[ref] - P.tick
        stop = b.l[ref] - bf if side > 0 else b.h[ref] + bf
        # the 200-day barrier: skip if SMA200 lies between the entry and +1R
        r1 = lvl + side * abs(lvl - stop)
        s2 = sm200[k]
        if s2 == s2 and min(lvl, r1) < s2 < max(lvl, r1):
            continue
        if not intraday and k + 1 < P.n and (b.o[k + 1] - lvl) * side > 0:
            continue                                   # trigger hit by the gap at the open: skip
        out.append(P.stop_order(k, side, lvl, nbars, stop, tmax=hold))
    return out


@rule("book-smith-01", "DLY", {"pct": dict(buf="pct", _tmode=1), "atr": dict(buf="atr", _tmode=1)})
def smith01(ctx, buf):
    P = DP(ctx)
    tr = _smith_trail_arrays(ctx, P) if buf == "pct" else _smith_trail_arrays(ctx, P, buf_atr=0.25, big_atr=2.5)
    return _sst(ctx, P, buf), tr, None


@rule("book-smith-04", "MCXD", {"pct": dict(buf="pct", _tmode=1), "atr": dict(buf="atr", _tmode=1)}, insts=MCX_ALL)
def smith04(ctx, buf):
    P = DP(ctx)
    tr = _smith_trail_arrays(ctx, P) if buf == "pct" else _smith_trail_arrays(ctx, P, buf_atr=0.25, big_atr=2.5)
    return _sst(ctx, P, buf), tr, None


@rule("book-smith-02", "IDX", {"60m": dict(_tmode=1)})
def smith02(ctx):
    P = Pat(ctx, 60)
    b = P.b
    tr = _smith_trail_arrays(ctx, P, buf_atr=0.25, big_atr=2.5)
    out = _sst(ctx, P, "atr", nbars=3, intraday=True)
    # skip expiry days (weekly contracts)
    s = ctx.s
    out = [t for t in out if not (len(s.expiry) and s.expiry[s.ses[t[0]]])]
    return out, tr, None


# ------------------------------------------------------------------------------------------------ Larry Swing 06 (IDXF)
@rule("book-lswing-06", "IDXF", {"A": dict(mode="A", _multiday=True), "B": dict(mode="B", _multiday=True)})
def lswing06(ctx, mode):
    """The plain pullback setups (lswing-04) traded on 1-minute bars: A = the book's gap rule, B = no gap rule."""
    s = ctx.s
    d = ctx.daily
    d = d[(d.index >= s.days[0])]
    c = d.close.values
    h, l, o = d.high.values, d.low.values, d.open.values
    s10, s20 = I.sma(c, 10), I.sma(c, 20)
    a = I.atr(h, l, c, 14)
    ses_of = {dt: i for i, dt in enumerate(s.days)}
    out = []
    day_low_trail = np.full(s.n, np.nan)
    day_high_trail = np.full(s.n, np.nan)
    for i in range(1, len(d)):
        dt = d.index[i]
        if dt in ses_of and np.isfinite(a[i - 1]):
            m0 = s.s0[ses_of[dt]]
            day_low_trail[m0] = l[i - 1] - 0.07 * a[i - 1]
            day_high_trail[m0] = h[i - 1] + 0.07 * a[i - 1]
    for i in range(25, len(d) - 1):
        L = c[i] > s10[i] and c[i] > s20[i] and s10[i] > s20[i] and h[i] < h[i - 1] < h[i - 2]
        S = c[i] < s10[i] and c[i] < s20[i] and s10[i] < s20[i] and l[i] > l[i - 1] > l[i - 2]
        if not (L or S) or not np.isfinite(a[i]):
            continue
        side = 1 if L else -1
        g = gid("ls6")
        for j in range(1, 6):
            if i + j >= len(d):
                break
            dt = d.index[i + j]
            if dt not in ses_of:
                continue
            si = ses_of[dt]
            m0, m1 = int(s.s0[si]), int(s.s1[si])
            prev_h, prev_l, at = h[i + j - 1], l[i + j - 1], a[i + j - 1]
            gap = (s.o[m0] - c[i + j - 1])
            start = m0 + 1
            lvl = prev_h + 0.07 * at if side > 0 else prev_l - 0.07 * at
            if mode == "A" and abs(gap) >= 0.6 * at:
                with_gap = gap * side > 0
                t_wait = 9 * 60 + 45 if with_gap else 9 * 60 + 20
                start = m0 + int(np.searchsorted(s.tod[m0:m1 + 1], t_wait))
                if start > m1:
                    continue
                lvl = (s.h[m0:start].max() + 0.07 * at) if side > 0 else (s.l[m0:start].min() - 0.07 * at)
            stop = max(lvl - 1.3 * at, prev_l - 0.07 * at) if side > 0 else min(lvl + 1.3 * at, prev_h + 0.07 * at)
            key = f"{g}|{j}"
            out.append((start, side, 1, float(lvl), m1, float(stop), float(lvl + side * 2.3 * at), P_AFTER_20D, NAN,
                        key, 0.5, float(lvl)))
            out.append((start, side, 1, float(lvl), m1, float(stop), NAN, P_AFTER_20D, NAN, key, 0.5, float(lvl)))
    return out, {1: day_low_trail, -1: day_high_trail}, None


# ------------------------------------------------------------------------------------------------ textbooks
@rule("book-fmfabozzi-01", "DLY", {f"x{x}": dict(x=x / 100) for x in (1, 2, 3, 5)})
def fabozzi01(ctx, x):
    P = DP(ctx)
    b, out = P.b, []
    c = b.c
    state, lo, hi = 0, c[0], c[0]
    sigL = np.zeros(P.n, bool)
    sigS = np.zeros(P.n, bool)
    for k in range(1, P.n - 1):
        lo, hi = min(lo, c[k]), max(hi, c[k])
        if state <= 0 and c[k] >= lo * (1 + x):
            state, hi = 1, c[k]
            sigL[k] = True
            out.append(P.mkt(k, 1, NAN))
        elif state >= 0 and c[k] <= hi * (1 - x):
            state, lo = -1, c[k]
            sigS[k] = True
            out.append(P.mkt(k, -1, NAN))
    return out, None, {1: _rows(ctx, P, sigS, bool), -1: _rows(ctx, P, sigL, bool)}


@rule("book-fmfabozzi-02", "DLY", {"b0": dict(bw=0.0, ema=False), "b1": dict(bw=0.01, ema=False),
                                   "b2": dict(bw=0.02, ema=False), "ema0": dict(bw=0.0, ema=True)})
def fabozzi02(ctx, bw, ema):
    P = DP(ctx)
    b, out = P.b, []
    m = I.ema(b.c, 200) if ema else I.sma(b.c, 200)
    up = (b.c > m * (1 + bw))
    dn = (b.c < m * (1 - bw))
    st = 0
    sL = np.zeros(P.n, bool)
    sS = np.zeros(P.n, bool)
    for k in range(201, P.n - 1):
        if up[k] and st <= 0:
            st = 1
            sL[k] = True
            out.append(P.mkt(k, 1, NAN))
        elif dn[k] and st >= 0:
            st = -1
            sS[k] = True
            out.append(P.mkt(k, -1, NAN))
    return out, None, {1: _rows(ctx, P, sS, bool), -1: _rows(ctx, P, sL, bool)}


@rule("book-fmchan-07", "DLY", {"h5": dict(h=5, stop=False), "h10": dict(h=10, stop=False), "h20": dict(h=20, stop=False),
                                "h10_stop": dict(h=10, stop=True)})
def chan07(ctx, h, stop):
    P = DP(ctx)
    b, out = P.b, []
    c = b.c
    lo10 = I.rolling_min(b.l, 10)
    hi10 = I.rolling_max(b.h, 10)
    L = np.zeros(P.n, bool)
    S = np.zeros(P.n, bool)
    for k in range(11, P.n - 1):
        ch = c[k] / c[k - 1] - 1
        if ch >= 0.01 and (b.l[k] <= lo10[k] or b.l[k - 1] <= lo10[k - 1]):
            L[k] = True
            out.append(P.mkt(k, 1, c[k] - 1.5 * P.atr[k] if stop else NAN, tmax=P.after_fill(h)))
        elif ch <= -0.01 and (b.h[k] >= hi10[k] or b.h[k - 1] >= hi10[k - 1]):
            S[k] = True
            out.append(P.mkt(k, -1, c[k] + 1.5 * P.atr[k] if stop else NAN, tmax=P.after_fill(h)))
    return out, None, {1: _rows(ctx, P, S, bool), -1: _rows(ctx, P, L, bool)}


def _least_width(y):
    """Slope minimising the vertical width of a parallel channel containing every point of y."""
    x = np.arange(len(y), dtype=float)
    lo, hi = -np.ptp(y), np.ptp(y)
    f = lambda m: np.ptp(y - m * x)
    for _ in range(60):
        a1, a2 = lo + (hi - lo) / 3, hi - (hi - lo) / 3
        if f(a1) < f(a2):
            hi = a2
        else:
            lo = a1
    m = (lo + hi) / 2
    r = y - m * x
    return m, r.min(), r.max()


def _optim(ctx, deep):
    P = DP(ctx)
    b, out = P.b, []
    c = b.c
    back_in = np.zeros(P.n, bool)
    proj_up = np.full(P.n, np.nan)
    proj_lo = np.full(P.n, np.nan)
    for k in range(21, P.n):
        m, rlo, rhi = _least_width(c[k - 20:k])
        proj_lo[k] = rlo + m * 20
        proj_up[k] = rhi + m * 20
    for k in range(21, P.n - 1):
        w = proj_up[k] - proj_lo[k]
        if c[k] > proj_up[k] + (0.25 * w if deep else 0):
            out.append(P.mkt(k, 1, proj_lo[k], tmax=P.after_fill(5)))
        elif c[k] < proj_lo[k] - (0.25 * w if deep else 0):
            out.append(P.mkt(k, -1, proj_up[k], tmax=P.after_fill(5)))
        back_in[k] = proj_lo[k] <= c[k] <= proj_up[k]
    return out, None, {1: _rows(ctx, P, back_in, bool), -1: _rows(ctx, P, back_in, bool)}


OPT = {"plain": dict(deep=False), "deep": dict(deep=True)}


@rule("book-fmoptim-01", "DLY", OPT)
def optim01(ctx, deep):
    return _optim(ctx, deep)


@rule("book-fmoptim-01", "MCXD", OPT, insts=("GOLD", "SILVER", "CRUDE"))
def optim01_m(ctx, deep):
    return _optim(ctx, deep)
