"""R104 family F7: Aziz's VWAP / ABCD / bull-flag setups (PREREG.md; mechanics from research/books/TESTABLE_RULES.md).
Committed before this family's first run.

VWAP: MCX = the session's real volume-weighted typical price; the NSE cash index has no volume, so the index uses the
session TWAP of the typical price as a labelled proxy. Book clauses that need index volume are dropped on the index
(labelled in PREREG_RULES.md). Readings fixed here:
- "Half / a quarter / a third at a level, stop to entry" = sub-lots: the first sub-lot's target is the level; the
  others move their stop to the entry once the price reaches it. Rule-level close exits (VWAP or EMA breaks, new 5-minute
  extremes) apply to every sub-lot.
- Aziz's "Open" = 09:15-10:15 NSE, "Late-Morning" 10:15-11:45; MCX equivalents start at 09:00 (the second and third
  hours = 10:00-12:00).
- ABCD (aziz-05): the book's "1-minute close below C" stop is modelled as a resting stop at C (exits a little more
  often; labelled).
- Daily ATR(14): NSE from index_daily (previous sessions), MCX from the series' own sessions.
"""
from __future__ import annotations

import numpy as np

import r104ind as I
from f3_patterns import _daily_hl_by_session, both
from r104pat import NAN, Pat, gid
from r104run import rule


def vwap_minutes(ctx):
    """Per minute: the session's VWAP (MCX) or TWAP of the typical price (index), up to and including the minute."""
    s = ctx.s
    tp = (s.h + s.l + s.c) / 3.0
    w = s.v if (ctx.market == "MCXI" and np.nansum(s.v) > 0) else np.ones(s.n)
    out = np.empty(s.n)
    for i in range(len(s.days)):
        a, z = s.s0[i], s.s1[i] + 1
        cw = np.cumsum(w[a:z])
        cpv = np.cumsum(tp[a:z] * w[a:z])
        with np.errstate(invalid="ignore", divide="ignore"):
            v = cpv / cw
        # zero-volume opening minutes: fall back to the TWAP until volume prints
        tw = np.cumsum(tp[a:z]) / np.arange(1, z - a + 1)
        out[a:z] = np.where(np.isfinite(v), v, tw)
    return out


def at_bars(b, x):
    return x[b.i1]


def daily_atr_by_session(ctx):
    """Per session index: the previous sessions' daily ATR(14)."""
    if ctx.market in ("IDX", "IDXF") and ctx.daily is not None:
        d = ctx.daily
        a = I.atr(d.high.values, d.low.values, d.close.values, 14)
        m = dict(zip(d.index, np.r_[np.nan, a[:-1]]))
        return {i: m.get(dd, np.nan) for i, dd in enumerate(ctx.s.days)}
    dd = _daily_hl_by_session(ctx)
    keys = sorted(dd)
    a = I.atr(np.array([dd[i][1] for i in keys]), np.array([dd[i][2] for i in keys]),
              np.array([dd[i][3] for i in keys]), 14)
    return {i: (a[i - 1] if i >= 1 else np.nan) for i in keys}


def prev_levels(ctx):
    """Per session: (previous close, previous high, previous low)."""
    dd = _daily_hl_by_session(ctx)
    return {i: (dd[i - 1][3], dd[i - 1][1], dd[i - 1][2]) if i >= 1 else (np.nan,) * 3 for i in dd}


def _win(ctx, P, k, nse, mcx):
    t = P.dec_tod(k)
    lo, hi = nse if ctx.market == "IDX" else mcx
    return lo <= t <= hi and P.ok_time(k, lo=0 if ctx.market != "IDX" else None, hi=24 * 60 if ctx.market != "IDX" else None)


@both("book-aziz-02", mcx=("CRUDE", "GOLD", "SILVER"), variants={"5m": dict()})
def aziz02(ctx):
    P = Pat(ctx, 5)
    b, out = P.b, []
    vw = at_bars(b, vwap_minutes(ctx))
    lv = prev_levels(ctx)
    sma200 = I.sma(b.c, 200)
    dh, dl = P.day_hl_sofar()
    state = {}
    done = set()
    xL = np.zeros(P.n, bool)
    xS = np.zeros(P.n, bool)
    for k in range(1, P.n - 1):
        s = int(b.ses[k])
        if b.ses[k - 1] != s or s not in state:
            state[s] = []
        below, above = b.c[k] < vw[k], b.c[k] > vw[k]
        st = state[s]
        prev = st[-1] if st else None
        cur = "B" if below else ("A" if above else prev)
        xL[k] = b.c[k] < vw[k] or (k > 0 and b.l[k] < b.l[k - 1] and P.same_ses(k - 1, k))
        xS[k] = b.c[k] > vw[k] or (k > 0 and b.h[k] > b.h[k - 1] and P.same_ses(k - 1, k))
        if s not in done and _win(ctx, P, k, (10 * 60 + 15, 11 * 60 + 45), (10 * 60, 12 * 60)):
            seq = "".join(st)
            pc, ph, pl = lv[s]
            if below and "BA" in seq and prev == "A":           # sold off, reclaimed, lost again -> short
                lod = dl[k]
                if (b.c[k] - lod) >= 2 * (vw[k] - b.c[k]) and lod < b.c[k]:
                    lvls = [x for x in (pc, pl, sma200[k]) if x == x and x < lod]
                    g = gid("a2")
                    out.append(P.mkt(k, -1, NAN, lod, grp=g, w=0.5))
                    out.append(P.mkt(k, -1, NAN, max(lvls) if lvls else NAN, grp=g, w=0.5))
                    done.add(s)
            if above and "AB" in seq and prev == "B":
                hod = dh[k]
                if (hod - b.c[k]) >= 2 * (b.c[k] - vw[k]) and hod > b.c[k]:
                    lvls = [x for x in (pc, ph, sma200[k]) if x == x and x > hod]
                    g = gid("a2")
                    out.append(P.mkt(k, 1, NAN, hod, grp=g, w=0.5))
                    out.append(P.mkt(k, 1, NAN, min(lvls) if lvls else NAN, grp=g, w=0.5))
                    done.add(s)
        if cur is not None and (not st or st[-1] != cur):
            st.append(cur)
    return out, None, {1: ctx.to_rows(b, xL, bool), -1: ctx.to_rows(b, xS, bool)}


@rule("book-aziz-03", "IDX", {"5m": dict()})
def aziz03(ctx):
    s = ctx.s
    P5 = Pat(ctx, 5)
    P1 = Pat(ctx, 1)
    b5, b1 = P5.b, P1.b
    vwm = vwap_minutes(ctx)
    e9 = I.ema(s.c, 9)
    dh, dl = P5.day_hl_sofar()
    out = []
    used = set()
    xL5 = np.zeros(P5.n, bool)
    xS5 = np.zeros(P5.n, bool)
    xL5[1:] = (b5.l[1:] < b5.l[:-1]) & (b5.ses[1:] == b5.ses[:-1])
    xS5[1:] = (b5.h[1:] > b5.h[:-1]) & (b5.ses[1:] == b5.ses[:-1])
    for k in range(2, P5.n - 1):
        s_ = int(b5.ses[k])
        if not P5.same_ses(k - 1, k) or P5.dec_tod(k) > 10 * 60 + 45:
            continue
        v = vwm[b5.i1[k]]
        for side in (1, -1):
            if (s_, side) in used:
                continue
            if side > 0:
                cond = b5.c[k] < v and b5.l[k - 1] <= dl[k - 1] + 1e-9 and b5.l[k] >= b5.l[k - 1]
            else:
                cond = b5.c[k] > v and b5.h[k - 1] >= dh[k - 1] - 1e-9 and b5.h[k] <= b5.h[k - 1]
            if not cond:
                continue
            # first 1-minute close through the 1-minute EMA 9 after that bar, before 10:45
            for m in range(b5.i1[k] + 1, min(b5.i1[k] + 60, s.n - 1)):
                if s.ses[m] != s_ or s.tod[m] + 1 > 10 * 60 + 45:
                    break
                if (side > 0 and s.c[m] > e9[m]) or (side < 0 and s.c[m] < e9[m]):
                    # below the low of the day, or the previous 5-minute bar's low if closer
                    stop = max(dl[k], b5.l[k - 1]) - 0.05 if side > 0 else min(dh[k], b5.h[k - 1]) + 0.05
                    vm = vwm[m]
                    e = s.c[m]
                    risk = abs(e - stop)
                    if risk <= 0 or abs(vm - e) < 2 * risk or (vm - e) * side <= 0:
                        break
                    g = gid("a3")
                    tm = int(np.searchsorted(s.tod[s.s0[s_]:s.s1[s_] + 1], 11 * 60 + 45)) + int(s.s0[s_])
                    be = abs(vm - e) / risk
                    out.append((m + 1, side, 0, NAN, -1, stop, vm, tm, NAN, g, 0.5, e))
                    out.append((m + 1, side, 0, NAN, -1, stop, NAN, tm, be, g, 0.5, e))
                    used.add((s_, side))
                    break
    xf = {1: ctx.to_rows(b5, xL5, bool), -1: ctx.to_rows(b5, xS5, bool)}
    return out, None, xf


@both("book-aziz-04", mcx=("CRUDE", "GOLD"), variants={"5m": dict()})
def aziz04(ctx):
    P = Pat(ctx, 5)
    b, out = P.b, []
    vw = at_bars(b, vwap_minutes(ctx))
    e9, e20 = I.ema(b.c, 9), I.ema(b.c, 20)
    done = set()
    for k in range(25, P.n - 1):
        lo_hi = ((11 * 60, 13 * 60 + 30), (11 * 60, 13 * 60 + 30))
        if not _win(ctx, P, k, lo_hi[0], lo_hi[1]) or not P.same_ses(k - 6, k):
            continue
        s = int(b.ses[k])
        a = P.atr[k]
        w = slice(k - 6, k)
        if (s, -1) not in done and (b.c[w] < vw[w]).all() and e20[k] < e20[k - 1] and \
                (b.h[k] >= e9[k] or b.h[k] >= e20[k]) and b.c[k] < min(e9[k], e20[k]):
            g = gid("a4")
            out.append(P.mkt(k, -1, NAN, e20[k] - a, grp=g, w=0.25))
            out.append(P.mkt(k, -1, NAN, NAN, be=(b.c[k] - (e20[k] - a)) / max(a, 1e-9), grp=g, w=0.75))
            done.add((s, -1))
        if (s, 1) not in done and (b.c[w] > vw[w]).all() and e20[k] > e20[k - 1] and \
                (b.l[k] <= e9[k] or b.l[k] <= e20[k]) and b.c[k] > max(e9[k], e20[k]):
            g = gid("a4")
            out.append(P.mkt(k, 1, NAN, e20[k] + a, grp=g, w=0.25))
            out.append(P.mkt(k, 1, NAN, NAN, be=((e20[k] + a) - b.c[k]) / max(a, 1e-9), grp=g, w=0.75))
            done.add((s, 1))
    # time exit 15:00 (NSE); MCX keeps the session square-off
    xLa = (b.c < e20)
    xSa = (b.c > e20)
    if ctx.market == "IDX":
        late = np.array([P.dec_tod(k) >= 15 * 60 for k in range(P.n)])
        xLa = xLa | late
        xSa = xSa | late
    return out, None, {1: ctx.to_rows(b, xLa, bool), -1: ctx.to_rows(b, xSa, bool)}


@rule("book-aziz-05", "IDX", {"1m": dict()})
def aziz05(ctx):
    s = ctx.s
    P5 = Pat(ctx, 5)
    b5 = P5.b
    vwm = vwap_minutes(ctx)
    datr = daily_atr_by_session(ctx)
    out = []
    xL5 = np.zeros(P5.n, bool)
    xS5 = np.zeros(P5.n, bool)
    xL5[1:] = (b5.l[1:] < b5.l[:-1]) & (b5.ses[1:] == b5.ses[:-1])
    xS5[1:] = (b5.h[1:] > b5.h[:-1]) & (b5.ses[1:] == b5.ses[:-1])
    for i in range(len(s.days)):
        a0, z = int(s.s0[i]), int(s.s1[i]) + 1
        A = datr.get(i, np.nan)
        if A != A:
            continue
        for side in (1, -1):
            px_h = s.h[a0:z] if side > 0 else -s.l[a0:z]
            px_l = s.l[a0:z] if side > 0 else -s.h[a0:z]
            closes = s.c[a0:z] if side > 0 else -s.c[a0:z]
            Aval, Bval, Bi, Cval, Ci = np.inf, -np.inf, -1, np.inf, -1
            hod = -np.inf
            run_lo = np.inf
            nolow = 0
            for j in range(z - a0):
                m = a0 + j
                tod = int(s.tod[m]) + 1
                if tod > 11 * 60 + 45:
                    break
                run_lo = min(run_lo, px_l[j])
                if px_h[j] > hod:
                    hod = px_h[j]
                    if hod - run_lo >= 0.5 * A:
                        Aval, Bval, Bi = run_lo, hod, j
                        Cval, Ci, nolow = np.inf, -1, 0
                    continue
                if Bi < 0:
                    continue
                if px_l[j] < Cval:
                    Cval, Ci, nolow = px_l[j], j, 0
                else:
                    nolow += 1
                if Cval <= Aval:
                    Bi = -1
                    continue
                vw = vwm[m] if side > 0 else -vwm[m]
                if nolow >= 3 and Cval >= vw and j >= 1 and px_h[j] > px_h[j - 1] and closes[j] <= Cval + 0.25 * (Bval - Cval) \
                        and 9 * 60 + 30 <= tod and m + 1 < z:
                    e = s.c[m]
                    stop = Cval if side > 0 else -Cval
                    tgtB = Bval if side > 0 else -Bval
                    risk = abs(e - stop)
                    if risk <= 0:
                        break
                    g = gid("a5")
                    be = abs(tgtB - e) / risk
                    # stop: a 1-minute CLOSE beyond C; modelled as a close-based level via the stop at C (touch)
                    out.append((m + 1, side, 0, NAN, -1, float(stop), float(tgtB), -1, NAN, g, 0.5, float(e)))
                    out.append((m + 1, side, 0, NAN, -1, float(stop), NAN, -1, float(be), g, 0.5, float(e)))
                    break
    xf = {1: ctx.to_rows(b5, xL5, bool), -1: ctx.to_rows(b5, xS5, bool)}
    return out, None, xf


@both("book-aziz-06", mcx=("CRUDE", "NATURALGAS"), variants={"1m": dict()})
def aziz06(ctx):
    s = ctx.s
    P5 = Pat(ctx, 5)
    b5 = P5.b
    use_vol = ctx.market == "MCXI"
    out = []
    end_t = 10 * 60 + 15
    for i in range(len(s.days)):
        a0, z = int(s.s0[i]), int(s.s1[i]) + 1
        flags = 0
        hod = -np.inf
        hod_hist = []
        j = a0
        k5 = int(np.searchsorted(b5.i0, a0))
        while j < z - 1 and flags < 2:
            if int(s.tod[j]) + 1 > end_t:
                break
            hod = max(hod, s.h[j])
            hod_hist.append(hod)
            # a pole: the current 5-minute bar (or the 2 before it) made a new high of day >= 1 ATR(5m) above the
            # low of those 3 bars
            kb = int(np.searchsorted(b5.i1, j))
            if kb >= P5.n or kb < 2:
                j += 1
                continue
            a = P5.atr[kb - 1]
            lo3 = b5.l[max(kb - 2, 0):kb + 1].min()
            if a != a or not (s.h[j] >= hod and hod - lo3 >= a):
                j += 1
                continue
            pole_hi = s.h[j]
            pole = pole_hi - lo3
            # flag: 3-12 one-minute bars below the pole high, retracing <= half the pole
            found = False
            for L in range(3, 13):
                f0, f1 = j + 1, j + L          # flag bars f0..f1
                m = f1 + 1                     # breakout minute
                if m >= z or int(s.tod[m]) + 1 > end_t:
                    break
                fh, fl = s.h[f0:f1 + 1].max(), s.l[f0:f1 + 1].min()
                if fh >= pole_hi or pole_hi - fl > 0.5 * pole:
                    break
                if s.h[m] > fh:
                    km = int(np.searchsorted(b5.i1, m))
                    joint = km >= 1 and km < P5.n and s.h[b5.i0[km]:m + 1].max() > b5.h[km - 1]
                    volok = (not use_vol) or (s.v[m] > s.v[f0:f1 + 1].mean())
                    if joint and volok:
                        g = gid("a6")
                        lvl = fh
                        stop = fl - 0.05
                        out.append((m, 1, 1, float(lvl), m, float(stop), float(max(hod, pole_hi) + 1e-9), -1, NAN, g, 0.5,
                                    float(lvl)))
                        risk = lvl - stop
                        out.append((m, 1, 1, float(lvl), m, float(stop), NAN, -1,
                                    float((pole_hi - lvl) / risk) if risk > 0 else NAN, g, 0.5, float(lvl)))
                        flags += 1
                        found = True
                    break
            j = (m + 1) if found else j + 1
    # the rest exits on a new 1-minute low
    xl = np.zeros(s.n, bool)
    xl[1:] = (s.l[1:] < s.l[:-1]) & (s.ses[1:] == s.ses[:-1])
    xf = {1: np.r_[False, xl[:-1]], -1: None}
    return out, None, xf


@rule("book-aziz-07", "IDX", {"1m": dict()})
def aziz07(ctx):
    s = ctx.s
    P5 = Pat(ctx, 5)
    b5 = P5.b
    vwm = vwap_minutes(ctx)
    datr = daily_atr_by_session(ctx)
    lv = prev_levels(ctx)
    sma50 = I.sma(b5.c, 50)
    out = []
    for i in range(1, len(s.days)):
        a0, z = int(s.s0[i]), int(s.s1[i]) + 1
        pc, ph, pl = lv[i]
        A = datr.get(i, np.nan)
        if pc != pc or A != A or s.o[a0] < pc * 1.005:
            continue
        first15 = a0 + int(np.searchsorted(s.tod[a0:z], 9 * 60 + 30))
        hod15 = s.h[a0:first15].max() if first15 > a0 else np.nan
        if not (hod15 > s.o[a0]):                      # the opening spike: a new high above the open by 09:30
            continue
        # after the spike: trade below VWAP, then a base of >= 15 bars with range < 0.3 ATR
        below = np.flatnonzero(s.c[first15:z] < vwm[first15:z])
        if not len(below):
            continue
        j0 = first15 + int(below[0])
        for e in range(j0 + 15, z - 1):
            if int(s.tod[e]) + 1 > 11 * 60 + 45:
                break
            base = slice(e - 15, e)
            bh, bl = s.h[base].max(), s.l[base].min()
            if bh - bl >= 0.3 * A:
                continue
            k5 = int(np.searchsorted(b5.i1, e - 1)) - 1
            sup = bl >= pc or (k5 >= 0 and sma50[k5] == sma50[k5] and bl >= sma50[k5])
            if not sup:
                continue
            if s.c[e] > bh:
                stop = bl - 0.05
                hod = s.h[a0:e + 1].max()
                tgts = [x for x in (vwm[e], hod, ph) if x == x and x > s.c[e]]
                tgts = sorted(tgts)
                while len(tgts) < 3:
                    tgts.append(NAN)
                risk = s.c[e] - stop
                be = (tgts[0] - s.c[e]) / risk if tgts[0] == tgts[0] and risk > 0 else NAN
                tm = a0 + int(np.searchsorted(s.tod[a0:z], 11 * 60 + 45))
                g = gid("a7")
                for j, t in enumerate(tgts[:3]):
                    out.append((e + 1, 1, 0, NAN, -1, float(stop), float(t), tm, NAN if j == 0 else be, g, 1 / 3,
                                float(s.c[e])))
                break
    xL5 = np.zeros(P5.n, bool)
    xL5[1:] = (b5.l[1:] < b5.l[:-1]) & (b5.ses[1:] == b5.ses[:-1])
    return out, None, {1: ctx.to_rows(b5, xL5, bool), -1: None}
