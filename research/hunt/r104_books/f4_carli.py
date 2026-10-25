"""R104 family F4: Carli's CCI divergence (PREREG.md; mechanics from research/books/TESTABLE_RULES.md). Committed before
this family's first run.

Readings fixed here:
- Swings: fractals with 2 bars each side (R95's confirmed pivots use 5 / 5 in carli-03, as R95 did).
- carli-01 / 02: the resistance line runs through the two most recent confirmed swing highs at or before L2; the
  "first opposite swing after L2" (for the 127.2% / 161.8% targets) must be confirmed by the entry bar, else no trade.
- Carli's half at T1 + stop to entry = two sub-lots (T1 with no break-even; T2 with break-even at T1).
- Gap filter: skip a long on a day that opened above the previous bar's high (short mirror).
- carli-02 on the index is a futures proxy held across sessions until T2 or the stop (no time cap in the book).
"""
from __future__ import annotations

import numpy as np

import r104data as DA
import r104ind as I
from f3_patterns import _daily_bars
from r104pat import NAN, Pat, fit_line, gid, line_at
from r104run import rule


def carli_setups(P, cci, n_sw=2, stale=20, pct_stop=0.001, use_line=True):
    """Yield (entry bar k, side, stop, T1, T2) for Carli's three-rule divergence with a trendline-break entry."""
    b = P.b
    hs, ls = P.swing_lists(n_sw)
    out = []
    for side, ext, opp in ((1, ls, hs), (-1, hs, ls)):
        for i in range(1, len(ext)):
            (b1, c1, v1), (b2, c2, v2) = ext[i - 1], ext[i]
            if side > 0:
                if not (v2 < v1 and cci[b2] > cci[b1] and cci[b2] <= -150 and np.nanmax(cci[b1:b2 + 1]) <= 0):
                    continue
                B = b.h[b1:b2 + 1].max()
                if B - v1 <= 0 or not (1.13 <= (B - v2) / (B - v1) <= 1.618):
                    continue
            else:
                if not (v2 > v1 and cci[b2] < cci[b1] and cci[b2] >= 150 and np.nanmin(cci[b1:b2 + 1]) >= 0):
                    continue
                B = b.l[b1:b2 + 1].min()
                if v1 - B <= 0 or not (1.13 <= (v2 - B) / (v1 - B) <= 1.618):
                    continue
            lines = [x for x in opp if x[0] <= b2 and x[1] <= c2]
            if len(lines) < 2:
                continue
            (x1, _, y1), (x2, _, y2) = lines[-2], lines[-1]
            ln = fit_line(x1, y1, x2, y2)
            stop = v2 * (1 - pct_stop) if side > 0 else v2 * (1 + pct_stop)
            for k in range(c2, min(b2 + stale + 1, P.n - 1)):
                lv = line_at(ln, k)
                if (side > 0 and b.c[k] > lv) or (side < 0 and b.c[k] < lv):
                    first_opp = [x for x in opp if x[0] > b2 and x[1] <= k]
                    if not first_opp:
                        break
                    H = first_opp[0][2]
                    leg = abs(H - v2)
                    T1 = v2 + side * 1.272 * leg
                    T2 = v2 + side * 1.618 * leg
                    if (T1 - b.c[k]) * side < abs(b.c[k] - stop):
                        break
                    out.append((k, side, stop, T1, T2))
                    break
    return out


def _intents(P, setups, gap_filter=True, intraday=False, tmax=-1):
    b, out = P.b, []
    for k, side, stop, T1, T2 in setups:
        if k + 1 >= P.n:
            continue
        if intraday and not P.ok_time(k):
            continue
        if gap_filter and P.ctx.market in ("DLY", "MCXD"):
            gap_up = b.o[k] > b.h[k - 1]
            gap_dn = b.o[k] < b.l[k - 1]
            if (side > 0 and gap_up) or (side < 0 and gap_dn):
                continue
        risk = abs(b.c[k] - stop)
        if risk <= 0:
            continue
        g = gid("c")
        be = abs(T1 - b.c[k]) / risk
        out.append(P.mkt(k, side, stop, T1, tmax=tmax, grp=g, w=0.5))
        out.append(P.mkt(k, side, stop, T2, tmax=tmax, be=be, grp=g, w=0.5))
    return out


def _carli_daily(ctx):
    P = Pat(ctx, 1, bars=_daily_bars(ctx))
    cci = I.cci(P.b.h, P.b.l, P.b.c, 20)
    return _intents(P, carli_setups(P, cci)), None, None


@rule("book-carli-01", "DLY")
def carli01(ctx):
    return _carli_daily(ctx)


@rule("book-carli-01", "MCXD", insts=("GOLD", "SILVER", "CRUDE"))
def carli01_m(ctx):
    return _carli_daily(ctx)


@rule("book-carli-02", "IDXF", {"half": dict(_multiday=True)})
def carli02(ctx):
    P = Pat(ctx, 180, bars=DA.half_session_bars(ctx.s))
    cci = I.cci(P.b.h, P.b.l, P.b.c, 20)
    return _intents(P, carli_setups(P, cci), gap_filter=False), None, None


@rule("book-carli-02", "MCXI", {"4h": dict(_multiday=True)}, insts=("GOLD", "SILVER", "CRUDE", "NATURALGAS"))
def carli02_m(ctx):
    P = Pat(ctx, 240)
    cci = I.cci(P.b.h, P.b.l, P.b.c, 20)
    return _intents(P, carli_setups(P, cci), gap_filter=False), None, None


@rule("book-carli-03", "IDX", {"15A": dict(tf=15, ex="A"), "15B": dict(tf=15, ex="B"),
                                "60A": dict(tf=60, ex="A"), "60B": dict(tf=60, ex="B")})
def carli03(ctx, tf, ex):
    """R95-style confirmed regular divergence (pivots 5 / 5), kept only with Carli's three filters."""
    P = Pat(ctx, tf, swing_n=5)
    b, out = P.b, []
    cci = I.cci(b.h, b.l, b.c, 20)
    hs, ls = P.swing_lists(5)
    for side, ext in ((1, ls), (-1, hs)):
        for i in range(1, len(ext)):
            (b1, c1, v1), (b2, c2, v2) = ext[i - 1], ext[i]
            k = c2
            if k >= P.n - 1 or not P.ok_time(k, hi=14 * 60 + 45):
                continue
            if b2 - b1 > 60:
                continue
            if side > 0:
                ok = v2 < v1 and cci[b2] > cci[b1] and cci[b2] <= -150 and np.nanmax(cci[b1:b2 + 1]) <= 0
                B = b.h[b1:b2 + 1].max()
                ok = ok and B - v1 > 0 and 1.13 <= (B - v2) / (B - v1) <= 1.618
            else:
                ok = v2 > v1 and cci[b2] < cci[b1] and cci[b2] >= 150 and np.nanmin(cci[b1:b2 + 1]) >= 0
                B = b.l[b1:b2 + 1].min()
                ok = ok and v1 - B > 0 and 1.13 <= (v2 - B) / (v1 - B) <= 1.618
            if not ok:
                continue
            if ex == "A":
                out.append(P.mkt(k, side, v2 - side * P.tick))
            else:
                stop = v2 * (1 - side * 0.001)
                leg = abs(B - v2)
                T1, T2 = v2 + side * 1.272 * leg, v2 + side * 1.618 * leg
                risk = abs(b.c[k] - stop)
                if risk <= 0 or (T1 - b.c[k]) * side <= 0:
                    continue
                g = gid("c3")
                out.append(P.mkt(k, side, stop, T1, grp=g, w=0.5))
                out.append(P.mkt(k, side, stop, T2, be=abs(T1 - b.c[k]) / risk, grp=g, w=0.5))
    xf = None
    if ex == "A":
        xf = {1: ctx.to_rows(b, cci >= 0, bool), -1: ctx.to_rows(b, cci <= 0, bool)}
    return out, None, xf
