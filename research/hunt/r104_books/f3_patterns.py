"""R104 family F3: price-action bar and chart patterns with the books' own stops (PREREG.md; mechanics from
research/books/TESTABLE_RULES.md, rule by rule). Committed before this family's first run.

Signal source: the underlying's bars (index spot / MCX future / crypto perp). IDX buys the option the rule names
(1-ITM unless the rule says ATM); MCXI / CRY trade the future / perp. Readings where a rule is silent (each is fixed
here before any result):
- "trend" = EMA 20 slope (PREREG 5); "downtrend into the bar" = EMA 20 slope trend -1 on the bar before.
- swing = fractal with 2 bars each side (3 where the rule says 3-bar fractal); patterns use swings CONFIRMED by the
  decision bar.
- Hikkake stop: the false-break bar C's low (the lowest low from C to the entry is not known when the order rests; C is
  the extreme in almost every case).
- Woods 10 (failed outside-bar break): the break must come within 6 bars of the outside bar and the close back inside
  within 3 bars of the break.
- Hougaard 13: a counter-rally = the close 1 bar back above the close 6 bars back, with at least 3 of those 5 bars green.
- Hougaard 14: "strong" green / red bar = body >= 60% of range.
- Chart patterns (wedges, triangles, flags, pennants) use regression lines through the pattern's highs and lows (flags,
  pennants) or lines through the first and last swing of each side (triangles, wedges, trendlines).
- MCX versions run on the rule's MCX instruments in the book (the mini contracts' own bars).
"""
from __future__ import annotations

import numpy as np

import r104ind as I
from r104pat import NAN, Pat, fit_line, gid, line_at, regress
from r104run import rule

T5_15 = {"5m": dict(tf=5), "15m": dict(tf=15)}
T15 = {"15m": dict(tf=15)}


def both(rid, mcx=None, variants=None, money=1, crypto=False):
    """Register one function for IDX and, when the book lists them, MCXI (and CRY)."""
    def deco(fn):
        rule(rid, "IDX", variants, money=money)(fn)
        if mcx:
            rule(rid, "MCXI", variants, insts=mcx)(fn)
        if crypto:
            rule(rid, "CRY", variants)(fn)
        return fn
    return deco


def _lock(P, intents):
    """Crypto: every trade carries the standard profit lock (PREREG 5): break-even at +1R and the 50% trail is
    approximated by the break-even move (be=1) when the rule has none."""
    if P.ctx.market != "CRY":
        return intents
    out = []
    for t in intents:
        t = list(t)
        if t[8] != t[8]:
            t[8] = 1.0
        out.append(tuple(t))
    return out


# ================================================================================================ Woods
@both("book-woods-01", mcx=("CRUDE", "GOLD"), variants=T5_15)
def woods01(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(4, P.n - 1):
        if not P.ok_time(k):
            continue
        for side in (1, -1):
            if P.trend[k - 1] != side:
                continue
            pull = P.red[k - 3:k] if side > 0 else P.green[k - 3:k]
            sig = P.green[k] if side > 0 else P.red[k]
            if not (pull.all() and sig and P.same_ses(k - 3, k)):
                continue
            lvl = b.h[k] + P.tick if side > 0 else b.l[k] - P.tick
            stop = b.l[k - 3:k + 1].min() if side > 0 else b.h[k - 3:k + 1].max()
            out.append(P.stop_order(k, side, lvl, 1, stop, P.target_swing(k, side, lvl)))
    return _lock(P, out), None, None


@both("book-woods-02", mcx=("CRUDE",), variants=T5_15)
def woods02(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for c in range(2, P.n - 1):
        B, A = c - 1, c - 2
        if not (P.same_ses(A, c) and P.ok_time(c)):
            continue
        if not (b.h[B] < b.h[A] and b.l[B] > b.l[A]):
            continue
        if b.h[c] < b.h[B] and b.l[c] < b.l[B]:          # downside false break -> buy above B
            lvl = b.h[B] + P.tick
            out.append(P.stop_order(c, 1, lvl, 3, b.l[c], P.target_swing(c, 1, lvl)))
        if b.h[c] > b.h[B] and b.l[c] > b.l[B]:          # upside false break -> sell below B
            lvl = b.l[B] - P.tick
            out.append(P.stop_order(c, -1, lvl, 3, b.h[c], P.target_swing(c, -1, lvl)))
    return out, None, None


@both("book-woods-03", variants=T5_15)
def woods03(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(1, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 1, k)):
            continue
        if P.trend[k] > 0 and b.l[k] < b.l[k - 1] and b.c[k] > b.c[k - 1]:
            lvl = b.h[k] + P.tick
            out.append(P.stop_order(k, 1, lvl, 1, b.l[k], P.target_swing(k, 1, lvl)))
        if P.trend[k] < 0 and b.h[k] > b.h[k - 1] and b.c[k] < b.c[k - 1]:
            lvl = b.l[k] - P.tick
            out.append(P.stop_order(k, -1, lvl, 1, b.h[k], P.target_swing(k, -1, lvl)))
    return out, None, None


@both("book-woods-06", variants=T5_15)
def woods06(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    for k in range(1, P.n - 1):
        if not P.ok_time(k) or P.rng[k] <= 0:
            continue
        ses = b.ses[k]
        lw = min(b.o[k], b.c[k]) - b.l[k]
        uw = b.h[k] - max(b.o[k], b.c[k])
        a = P.atr[k]
        if a != a:
            continue
        if lw >= 2 / 3 * P.rng[k]:
            lv = [x[2] for x in P.recent(k, "L", sessions=1, limit=10)]
            if any(abs(b.l[k] - v) <= 0.1 * a for v in lv):
                lvl = b.h[k] + P.tick
                out.append(P.stop_order(k, 1, lvl, 1, b.l[k], P.target_swing(k, 1, lvl)))
        if uw >= 2 / 3 * P.rng[k]:
            hv = [x[2] for x in P.recent(k, "H", sessions=1, limit=10)]
            if any(abs(b.h[k] - v) <= 0.1 * a for v in hv):
                lvl = b.l[k] - P.tick
                out.append(P.stop_order(k, -1, lvl, 1, b.h[k], P.target_swing(k, -1, lvl)))
    return out, None, None


@both("book-woods-07", variants={"5m": dict(tf=5, bias=False), "15m": dict(tf=15, bias=False),
                                 "5m_bias": dict(tf=5, bias=True), "15m_bias": dict(tf=15, bias=True)})
def woods07(ctx, tf, bias):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(1, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 1, k)) or P.rng[k] <= 0 or P.rng[k - 1] <= 0:
            continue
        s1 = P.body[k - 1] >= 0.6 * P.rng[k - 1]
        s2 = P.body[k] >= 0.6 * P.rng[k]
        if not (s1 and s2):
            continue
        if P.red[k - 1] and P.green[k] and (not bias or P.trend[k] > 0):
            lvl = max(b.h[k - 1], b.h[k]) + P.tick
            out.append(P.stop_order(k, 1, lvl, 1, min(b.l[k - 1], b.l[k]), P.target_swing(k, 1, lvl)))
        if P.green[k - 1] and P.red[k] and (not bias or P.trend[k] < 0):
            lvl = min(b.l[k - 1], b.l[k]) - P.tick
            out.append(P.stop_order(k, -1, lvl, 1, max(b.h[k - 1], b.h[k]), P.target_swing(k, -1, lvl)))
    return out, None, None


@both("book-woods-09", variants=T15)
def woods09(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(1, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 1, k)):
            continue
        if not (b.h[k] <= b.h[k - 1] and b.l[k] >= b.l[k - 1] and P.rng[k] < P.rng[k - 1]):
            continue
        if P.trend[k] > 0:
            lvl = b.h[k] + P.tick
            out.append(P.stop_order(k, 1, lvl, 2, b.l[k], P.target_swing(k, 1, lvl)))
        elif P.trend[k] < 0:
            lvl = b.l[k] - P.tick
            out.append(P.stop_order(k, -1, lvl, 2, b.h[k], P.target_swing(k, -1, lvl)))
    return out, None, None


@both("book-woods-10", variants=T5_15)
def woods10(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(1, P.n - 1):
        if not P.same_ses(k - 1, k) or P.rng[k] <= 0:
            continue
        if not (b.h[k] > b.h[k - 1] and b.l[k] < b.l[k - 1]):
            continue
        doji = P.body[k] <= 0.3 * P.rng[k]
        counter = (P.green[k] and P.trend[k] < 0) or (P.red[k] and P.trend[k] > 0)
        if not (doji or counter):
            continue
        hi, lo = b.h[k], b.l[k]
        for j in range(k + 1, min(k + 7, P.n - 1)):
            if not P.same_ses(k, j):
                break
            if b.h[j] > hi:                               # up break: sell the first close back inside
                ext = b.h[j]
                for m in range(j, min(j + 4, P.n - 1)):
                    if not P.same_ses(k, m):
                        break
                    ext = max(ext, b.h[m])
                    if b.c[m] < hi and P.ok_time(m):
                        out.append(P.mkt(m, -1, ext, lo))
                        break
                break
            if b.l[j] < lo:
                ext = b.l[j]
                for m in range(j, min(j + 4, P.n - 1)):
                    if not P.same_ses(k, m):
                        break
                    ext = min(ext, b.l[m])
                    if b.c[m] > lo and P.ok_time(m):
                        out.append(P.mkt(m, 1, ext, hi))
                        break
                break
    return out, None, None


@both("book-woods-11", variants=T5_15)
def woods11(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(1, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 1, k)) or P.rng[k] <= 0:
            continue
        if not (b.h[k] > b.h[k - 1] and b.l[k] < b.l[k - 1]):
            continue
        pos = (b.c[k] - b.l[k]) / P.rng[k]
        if pos >= 0.8:
            lvl = b.h[k] + P.tick
            out.append(P.stop_order(k, 1, lvl, 2, b.l[k], P.target_swing(k, 1, lvl)))
        elif pos <= 0.2:
            lvl = b.l[k] - P.tick
            out.append(P.stop_order(k, -1, lvl, 2, b.h[k], P.target_swing(k, -1, lvl)))
    return out, None, None


def _nr7_trend(ctx, tf, daily=False):
    P = Pat(ctx, tf) if not daily else Pat(ctx, 1, bars=_daily_bars(ctx))
    b, out = P.b, []
    for k in range(7, P.n - 1):
        if not daily and not P.ok_time(k):
            continue
        if P.rng[k] > P.rng[k - 6:k].min():
            continue
        tmax = -1
        if daily:
            tmax = min(P.start(k) + 1 + 5, P.n - 1)         # 5 sessions after the entry day
        if P.trend[k] > 0:
            lvl = b.h[k] + P.tick
            out.append(P.stop_order(k, 1, lvl, 2, b.l[k], NAN if daily else P.target_swing(k, 1, lvl), tmax=tmax))
        elif P.trend[k] < 0:
            lvl = b.l[k] - P.tick
            out.append(P.stop_order(k, -1, lvl, 2, b.h[k], NAN if daily else P.target_swing(k, -1, lvl), tmax=tmax))
    return out, None, None


def _daily_bars(ctx):
    """A daily Series as Bars (one bar per row)."""
    import r104data as DA
    s = ctx.s
    idx = np.arange(s.n)
    return DA.Bars(1440, s.o, s.h, s.l, s.c, s.v, idx, idx, s.ses, np.zeros(s.n, np.int64), np.ones(s.n, bool))


@both("book-woods-12", mcx=("CRUDE",), variants=T15)
def woods12(ctx, tf):
    return _nr7_trend(ctx, tf)


@rule("book-woods-12", "DLY")
def woods12_d(ctx):
    return _nr7_trend(ctx, 1, daily=True)


def _id_nr4(ctx, tf, daily=False):
    P = Pat(ctx, tf) if not daily else Pat(ctx, 1, bars=_daily_bars(ctx))
    b, out = P.b, []
    for k in range(4, P.n - 1):
        if not daily and not (P.ok_time(k) and P.same_ses(k - 1, k)):
            continue
        if not (b.h[k] <= b.h[k - 1] and b.l[k] >= b.l[k - 1] and P.rng[k] <= P.rng[k - 3:k].min()):
            continue
        tmax = min(P.start(k) + 1 + 3, P.n - 1) if daily else -1
        g = gid("oco")
        out.append(P.stop_order(k, 1, b.h[k] + P.tick, 1, b.l[k], tmax=tmax, grp=g + "|L"))
        out.append(P.stop_order(k, -1, b.l[k] - P.tick, 1, b.h[k], tmax=tmax, grp=g + "|S"))
    return out, None, None


@both("book-woods-13", variants=T15)
def woods13(ctx, tf):
    return _id_nr4(ctx, tf)


@rule("book-woods-13", "DLY")
def woods13_d(ctx):
    return _id_nr4(ctx, 1, daily=True)


@both("book-woods-14", variants=T15)
def woods14(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(1, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 1, k)):
            continue
        if P.trend[k] > 0 and P.red[k - 1] and P.green[k] and b.o[k] <= b.c[k - 1] and b.c[k] >= b.o[k - 1]:
            lvl = b.h[k] + P.tick
            out.append(P.stop_order(k, 1, lvl, 1, min(b.l[k], b.l[k - 1]), P.target_swing(k, 1, lvl)))
        if P.trend[k] < 0 and P.green[k - 1] and P.red[k] and b.o[k] >= b.c[k - 1] and b.c[k] <= b.o[k - 1]:
            lvl = b.l[k] - P.tick
            out.append(P.stop_order(k, -1, lvl, 1, max(b.h[k], b.h[k - 1]), P.target_swing(k, -1, lvl)))
    return out, None, None


@both("book-woods-15", variants=T5_15)
def woods15(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(23, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 2, k)):
            continue
        a = P.atr[k - 3]
        if a != a or min(P.rng[k - 2:k + 1]) <= 0:
            continue
        j = [k - 2, k - 1, k]
        top = all(P.green[x] and (b.c[x] - b.l[x]) >= 0.75 * P.rng[x] for x in j)
        inside = all(min(b.o[x - 1], b.c[x - 1]) <= b.o[x] <= max(b.o[x - 1], b.c[x - 1]) for x in j[1:])
        if top and inside and b.c[k - 23] - b.c[k - 3] >= 3 * a:
            lvl = b.h[k] + P.tick
            out.append(P.stop_order(k, 1, lvl, 1, b.l[k - 2], P.target_swing(k, 1, lvl)))
        bot = all(P.red[x] and (b.h[x] - b.c[x]) >= 0.75 * P.rng[x] for x in j)
        if bot and inside and b.c[k - 3] - b.c[k - 23] >= 3 * a:
            lvl = b.l[k] - P.tick
            out.append(P.stop_order(k, -1, lvl, 1, b.h[k - 2], P.target_swing(k, -1, lvl)))
    return out, None, None


def _hs(ctx, tf, n_sw, book, daily=False):
    """Head and shoulders / inverse. book 'woods': stop the right shoulder, after a trend by EMA 50 at the left shoulder;
    book 'atb': middle highest by >= 0.5 ATR, shoulders within 0.5 ATR, stop the midpoint between the right shoulder and
    the neckline. Target = head-to-neckline height from the break. Entry at the open after the first close beyond."""
    P = Pat(ctx, tf, swing_n=n_sw) if not daily else Pat(ctx, 1, swing_n=n_sw, bars=_daily_bars(ctx))
    b, out = P.b, []
    hs, ls = P.swing_lists()
    used = set()
    for inv, (tops, bots) in ((True, (ls, hs)), (False, (hs, ls))):
        # inv: three swing LOWS (inverse H&S, long)
        for i in range(2, len(tops)):
            (b1, c1, v1), (b2, c2, v2), (b3, c3, v3) = tops[i - 2], tops[i - 1], tops[i]
            sgn = -1 if inv else 1                       # the head is the most extreme
            if not (sgn * (v2 - v1) > 0 and sgn * (v2 - v3) > 0):
                continue
            a = P.atr[b2]
            if a != a:
                continue
            margin = (v2 - max(v1, v3)) if sgn > 0 else (min(v1, v3) - v2)
            if book == "atb" and not (margin >= 0.5 * a and abs(v1 - v3) <= 0.5 * a):
                continue
            if book == "woods":
                e = P.ema50[b1]
                if e != e or (inv and not b.c[b1] < e) or (not inv and not b.c[b1] > e):
                    continue
            nk = [x for x in bots if b1 < x[0] < b2] , [x for x in bots if b2 < x[0] < b3]
            if not nk[0] or not nk[1]:
                continue
            n1 = nk[0][-1]
            n2 = nk[1][-1]
            line = fit_line(n1[0], n1[2], n2[0], n2[2])
            height = abs(v2 - line_at(line, b2))
            for k in range(c3, min(c3 + 30, P.n - 1)):
                if not daily and not P.same_ses(c3, k):
                    break
                nl = line_at(line, k)
                if (inv and b.c[k] > nl) or (not inv and b.c[k] < nl):
                    if (b3, inv) in used:
                        break
                    used.add((b3, inv))
                    if not daily and not P.ok_time(k):
                        break
                    side = 1 if inv else -1
                    if book == "woods":
                        stop = v3
                    else:
                        stop = (v3 + nl) / 2.0
                    tgt = b.c[k] + side * height
                    tmax = min(P.start(k) + 5, P.n - 1) if daily else -1
                    out.append(P.mkt(k, side, stop, tgt, tmax=tmax))
                    break
    return out, None, None


@both("book-woods-16", mcx=("GOLD",), variants={"15m": dict(tf=15)})
def woods16(ctx, tf):
    return _hs(ctx, tf, 3, "woods")


@rule("book-woods-16", "DLY")
def woods16_d(ctx):
    return _hs(ctx, 1, 3, "woods", daily=True)


@both("book-woods-17", variants={"15m_bottom": dict(tf=15, mid=False), "15m_mid": dict(tf=15, mid=True)})
def woods17(ctx, tf, mid):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(60, P.n - 1):
        if not P.ok_time(k):
            continue
        w = slice(k - 8, k)
        if not P.same_ses(k - 8, k):
            continue
        top, bot = b.h[w].max(), b.l[w].min()
        a = P.atr[k - 1]
        if a != a or top - bot > 1.5 * a:
            continue
        e = P.ema50
        up = b.c[k - 9] > e[k - 9] and e[k - 9] > e[k - 14]
        dn = b.c[k - 9] < e[k - 9] and e[k - 9] < e[k - 14]
        nt = int(np.sum(b.h[w] >= top - 0.1 * a))
        nb = int(np.sum(b.l[w] <= bot + 0.1 * a))
        if nt < 2 or nb < 2:
            continue
        hgt = top - bot
        if up and b.c[k] > top:
            out.append(P.mkt(k, 1, (top + bot) / 2 if mid else bot, top + hgt))
        if dn and b.c[k] < bot:
            out.append(P.mkt(k, -1, (top + bot) / 2 if mid else top, bot - hgt))
    return out, None, None


@both("book-woods-18", variants=T15)
def woods18(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    hi_i = lo_i = 0
    done = set()
    for k in range(1, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            hi_i += 1
        while lo_i < len(ls) and ls[lo_i][1] <= k:
            lo_i += 1
        if hi_i < 3 or lo_i < 3 or not P.ok_time(k):
            continue
        H = hs[hi_i - 3:hi_i]
        L = ls[lo_i - 3:lo_i]
        a = P.atr[k]
        if a != a or H[0][0] in done:
            continue
        if not (b.ses[H[0][0]] == b.ses[k] or b.ses[H[0][0]] == b.ses[k] - 1):
            continue
        for side in (1, -1):
            if P.trend[H[0][0]] != side:
                continue
            if side > 0:
                lows_up = L[0][2] < L[1][2] < L[2][2]
                flat = max(x[2] for x in H) - min(x[2] for x in H) <= 0.25 * a
                fall = H[0][2] > H[1][2] > H[2][2]
                if not (lows_up and (flat or fall)):
                    continue
                upper = max(x[2] for x in H) if flat else line_at(fit_line(H[0][0], H[0][2], H[2][0], H[2][2]), k)
                if b.c[k] > upper and b.c[k - 1] <= upper:
                    done.add(H[0][0])
                    out.append(P.mkt(k, 1, L[2][2], b.c[k] + (H[0][2] - L[0][2])))
            else:
                highs_dn = H[0][2] > H[1][2] > H[2][2]
                flat = max(x[2] for x in L) - min(x[2] for x in L) <= 0.25 * a
                rise = L[0][2] < L[1][2] < L[2][2]
                if not (highs_dn and (flat or rise)):
                    continue
                lower = min(x[2] for x in L) if flat else line_at(fit_line(L[0][0], L[0][2], L[2][0], L[2][2]), k)
                if b.c[k] < lower and b.c[k - 1] >= lower:
                    done.add(H[0][0])
                    out.append(P.mkt(k, -1, H[2][2], b.c[k] - (H[0][2] - L[0][2])))
    return out, None, None


@both("book-woods-19", variants={"5m": dict(tf=5)})
def woods19(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    ph, pl = P.prev_session_hl()
    # per session: the previous session's swing levels confirmed before this session's first bar
    first = {}
    for k in range(P.n):
        first.setdefault(int(b.ses[k]), k)
    lev_h, lev_l = {}, {}
    for s, k0 in first.items():
        lev_h[s] = [x[2] for x in P.recent(k0, "H", limit=30) if b.ses[x[0]] == s - 1][:3]
        lev_l[s] = [x[2] for x in P.recent(k0, "L", limit=30) if b.ses[x[0]] == s - 1][:3]
    run_hi, run_lo = -np.inf, np.inf
    for k in range(1, P.n - 1):
        s = int(b.ses[k])
        if first[s] == k:
            run_hi, run_lo = -np.inf, np.inf
            continue
        run_hi, run_lo = max(run_hi, b.c[k - 1]), min(run_lo, b.c[k - 1])   # closes of earlier bars this session
        prev_hi, prev_lo = run_hi, run_lo
        a = P.atr[k]
        if a != a or not P.ok_time(k):
            continue
        for L in lev_h[s]:
            if prev_hi > L and b.l[k] <= L + 0.1 * a and P.green[k] and b.c[k] > L:
                lvl = b.h[k] + P.tick
                t_ = ph[k] if ph[k] > lvl else P.target_swing(k, 1, lvl)
                out.append(P.stop_order(k, 1, lvl, 1, L - 0.25 * a, t_))
                break
        for L in lev_l[s]:
            if prev_lo < L and b.h[k] >= L - 0.1 * a and P.red[k] and b.c[k] < L:
                lvl = b.l[k] - P.tick
                t_ = pl[k] if pl[k] < lvl else P.target_swing(k, -1, lvl)
                out.append(P.stop_order(k, -1, lvl, 1, L + 0.25 * a, t_))
                break
    return out, None, None


@both("book-woods-20", variants=T15)
def woods20(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    slope, mid, se = I.linreg_channel(b.c, 50)
    for k in range(50, P.n - 1):
        a = P.atr[k]
        if a != a or not P.ok_time(k):
            continue
        lo, hi = mid[k] - 2 * se[k], mid[k] + 2 * se[k]
        if slope[k] * 10 > 0.1 * a and b.l[k] <= lo and P.green[k]:
            lvl = b.h[k] + P.tick
            out.append(P.stop_order(k, 1, lvl, 1, b.l[k] - P.tick, hi))
        if slope[k] * 10 < -0.1 * a and b.h[k] >= hi and P.red[k]:
            lvl = b.l[k] - P.tick
            out.append(P.stop_order(k, -1, lvl, 1, b.h[k] + P.tick, lo))
    return out, None, None


# ================================================================================================ Hougaard (Trader Tom)
def _bars_tf(ctx, tf):
    return Pat(ctx, tf)


def _sma_trend_htf(ctx, tf_hi, n):
    """Per minute row: +1 if the last COMPLETED tf_hi bar closed above its SMA(n), -1 below (NaN-safe), as an array
    over the series' minutes."""
    Ph = Pat(ctx, tf_hi)
    sm = I.sma(Ph.b.c, n)
    st = np.where(Ph.b.c > sm, 1, np.where(Ph.b.c < sm, -1, 0)).astype(float)
    st[np.isnan(sm)] = 0
    rows = ctx.to_rows(Ph.b, st)
    # forward-fill within the series (a completed bar's state holds until the next)
    out = np.zeros(ctx.s.n)
    cur = 0.0
    for i in range(ctx.s.n):
        if rows[i] == rows[i]:
            cur = rows[i]
        out[i] = cur
    return out


@both("book-tom-05", variants={"10m": dict(tf=10), "5m": dict(tf=5)})
def tom05(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    htf = _sma_trend_htf(ctx, 60, 89)
    for k in range(1, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 1, k)):
            continue
        tr = htf[b.i1[k]]
        if tr > 0 and P.green[k] and P.red[k - 1] and b.o[k] <= b.c[k - 1] and b.c[k] >= b.o[k - 1]:
            out.append(P.stop_order(k, 1, b.h[k], 2, b.l[k], be=1.0))
        if tr < 0 and P.red[k] and P.green[k - 1] and b.o[k] >= b.c[k - 1] and b.c[k] <= b.o[k - 1]:
            out.append(P.stop_order(k, -1, b.l[k], 2, b.h[k], be=1.0))
    # after break-even: trail at the last completed bar's low (high)
    trail = {1: ctx.to_rows(b, b.l), -1: ctx.to_rows(b, b.h)}
    return out, trail, None


rule("book-tom-05", "IDX", {"10m": dict(tf=10, _tab=True), "5m": dict(tf=5, _tab=True)})(tom05)


@both("book-tom-06", variants={"5m": dict(tf=5)})
def tom06(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    htf = _sma_trend_htf(ctx, 60, 89)
    used = set()
    for k in range(1, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 1, k)):
            continue
        a = P.atr[k]
        if a != a or (b.ses[k]) in used:
            continue
        tr = htf[b.i1[k]]
        if tr > 0 and P.green[k] and P.red[k - 1] and b.o[k] <= b.c[k - 1] and b.c[k] >= b.o[k - 1]:
            lvl = b.l[k]                                  # the failed long's stop -> sell there
            stop = b.h[k] + 0.25 * a
            risk = stop - lvl
            out.append(P.stop_order(k, -1, lvl, 3, stop, lvl - 1.5 * risk, tmax=P.tmax_minutes(k, 30 + 3 * tf)))
        if tr < 0 and P.red[k] and P.green[k - 1] and b.o[k] >= b.c[k - 1] and b.c[k] <= b.o[k - 1]:
            lvl = b.h[k]
            stop = b.l[k] - 0.25 * a
            risk = lvl - stop
            out.append(P.stop_order(k, 1, lvl, 3, stop, lvl + 1.5 * risk, tmax=P.tmax_minutes(k, 30 + 3 * tf)))
    return out, None, None


rule("book-tom-06", "IDX", {"5m": dict(tf=5, _max_side_day=1)})(tom06)


@both("book-tom-07", mcx=("GOLD", "CRUDE"), variants={"15m": dict(tf=15), "5m": dict(tf=5)})
def tom07(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    sh, shi, sl, sli = P.swings(2)
    for k in range(1, P.n - 1):
        if not P.ok_time(k):
            continue
        a = P.atr[k]
        if a != a or sh[k - 1] != sh[k - 1] or sl[k - 1] != sl[k - 1]:
            continue
        if sh[k - 1] - sl[k - 1] < a:
            continue
        L, H = sl[k - 1], sh[k - 1]
        # fake-down: this bar or the previous traded below L and this bar closes back above L
        if min(b.l[k], b.l[k - 1]) < L and b.c[k] > L and b.c[k - 1] <= L + 1e9 and P.same_ses(k - 1, k):
            lo = min(b.l[k], b.l[k - 1])
            if b.c[k - 1] > L and b.l[k - 1] < L:          # already closed back above last bar: that was the signal
                pass
            else:
                out.append(P.mkt(k, 1, lo - P.tick, H if H > b.c[k] else NAN))
        if max(b.h[k], b.h[k - 1]) > H and b.c[k] < H and P.same_ses(k - 1, k):
            hi = max(b.h[k], b.h[k - 1])
            if b.c[k - 1] < H and b.h[k - 1] > H:
                pass
            else:
                out.append(P.mkt(k, -1, hi + P.tick, L if L < b.c[k] else NAN))
    return out, None, None


@both("book-tom-08", variants={"1m": dict()})
def tom08(ctx):
    P = Pat(ctx, 1)
    b, out = P.b, []
    hs, ls = P.swing_lists(2)
    # first-hour range vs its 20-session median
    fh = {}
    for k in range(P.n):
        if P.dec_tod(k) <= 10 * 60 + 15:
            s = int(b.ses[k])
            lo, hi = fh.get(s, (np.inf, -np.inf))
            fh[s] = (min(lo, b.l[k]), max(hi, b.h[k]))
    ses_ids = sorted(fh)
    rng = np.array([fh[s][1] - fh[s][0] for s in ses_ids])
    med = {s: (np.median(rng[max(0, i - 20):i]) if i >= 5 else np.nan) for i, s in enumerate(ses_ids)}
    hi_i = lo_i = 0
    last_sig = -10
    for k in range(3, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            hi_i += 1
        while lo_i < len(ls) and ls[lo_i][1] <= k:
            lo_i += 1
        if hi_i < 3 or lo_i < 1 or k - last_sig < 5 or not P.ok_time(k):
            continue
        s = int(b.ses[k])
        if not (s in fh and fh[s][1] - fh[s][0] >= med.get(s, np.inf)) or P.dec_tod(k) <= 10 * 60 + 15:
            continue
        for side in (1, -1):
            src = hs if side > 0 else ls
            i_ = hi_i if side > 0 else lo_i
            if i_ < 3:
                continue
            X = src[i_ - 3:i_]
            if any(b.ses[x[0]] != s for x in X):
                continue
            desc = X[0][2] > X[1][2] > X[2][2] if side > 0 else X[0][2] < X[1][2] < X[2][2]
            if not desc:
                continue
            line = fit_line(X[0][0], X[0][2], X[2][0], X[2][2])
            if abs(line_at(line, X[1][0]) - X[1][2]) > 0.0005 * X[1][2]:
                continue
            # fake-down: after the last line touch, price traded below the prior opposite swing and turned
            opp = (ls if side > 0 else hs)
            prior = [x for x in opp if x[1] <= k and x[0] < X[2][0]]
            if not prior:
                continue
            pl = prior[-1][2]
            seg = slice(X[2][0], k + 1)
            ext = b.l[seg].min() if side > 0 else b.h[seg].max()
            faked = ext < pl if side > 0 else ext > pl
            lv, lv1 = line_at(line, k), line_at(line, k - 1)
            cross = (b.c[k] > lv and b.c[k - 1] <= lv1) if side > 0 else (b.c[k] < lv and b.c[k - 1] >= lv1)
            if faked and cross:
                stop = ext - P.tick if side > 0 else ext + P.tick
                out.append(P.mkt(k, side, stop, P.r_target(side, b.c[k], stop, 2.0), tmax=P.tmax_minutes(k, 30)))
                last_sig = k
    return out, None, None


@both("book-tom-09", variants=T15)
def tom09(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists(2)
    ev = sorted([(c, "H", v, bar) for bar, c, v in hs] + [(c, "L", v, bar) for bar, c, v in ls])
    seqH, seqL = [], []
    for conf, kind, v, bar in ev:
        (seqH if kind == "H" else seqL).append(v)
        if kind != "L" or len(seqH) < 3 or len(seqL) < 3:
            continue
        k = conf
        if k >= P.n - 1 or not P.ok_time(k):
            continue
        h1, h2, h3 = seqH[-3:]
        l1, l2, l3 = seqL[-3:]
        if h2 > h1 and l2 > l1 and h3 < h2 and l3 < l2:        # uptrend, then a lower high and a lower low
            g = gid("t9")
            risk_s = h3 - l3
            out.append(P.stop_order(k, -1, l3 - P.tick, 999, h3, l3 - 2 * risk_s, grp=g + "|S"))
            out.append(P.stop_order(k, 1, h3 + P.tick, 999, l3, h3 + 2 * risk_s, grp=g + "|L"))
    return out, None, None


def _tom_doji(ctx, tf, kind):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(9, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 8, k)) or P.rng[k] <= 0:
            continue
        cl = b.c[k - 8:k]
        lower = int(np.sum(np.diff(b.c[k - 9:k]) < 0))
        higher = int(np.sum(np.diff(b.c[k - 9:k]) > 0))
        doji = P.body[k] <= 0.1 * P.rng[k]
        pos = (b.c[k] - b.l[k]) / P.rng[k]
        if kind == "dragon" and lower >= 5 and doji and pos >= 0.9:
            stop = b.l[k]
            lvl = b.h[k] + P.tick
            out.append(P.stop_order(k, 1, lvl, 3, stop, P.r_target(1, lvl, stop, 2)))
        if kind == "grave" and higher >= 5 and doji and pos <= 0.1:
            stop = b.h[k]
            lvl = b.l[k] - P.tick
            out.append(P.stop_order(k, -1, lvl, 3, stop, P.r_target(-1, lvl, stop, 2)))
    return out, None, None


@both("book-tom-11", variants=T15)
def tom11(ctx, tf):
    return _tom_doji(ctx, tf, "dragon")


@both("book-tom-12", variants=T15)
def tom12(ctx, tf):
    return _tom_doji(ctx, tf, "grave")


@both("book-tom-13", variants=T15)
def tom13(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    sm = I.sma(b.c, 89)
    for k in range(50, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 6, k)) or P.rng[k] <= 0:
            continue
        uw = b.h[k] - max(b.o[k], b.c[k])
        lw = min(b.o[k], b.c[k]) - b.l[k]
        spin = P.body[k] <= 0.3 * P.rng[k] and uw >= 0.25 * P.rng[k] and lw >= 0.25 * P.rng[k]
        if not spin or sm[k] != sm[k] or sm[k - 40] != sm[k - 40]:
            continue
        down = b.c[k] < sm[k] and sm[k] < sm[k - 40]
        up = b.c[k] > sm[k] and sm[k] > sm[k - 40]
        greens = int(P.green[k - 5:k].sum())
        reds = int(P.red[k - 5:k].sum())
        if down and b.c[k - 1] > b.c[k - 6] and greens >= 3:
            stop = b.h[k - 5:k + 1].max()
            out.append(P.stop_order(k, -1, b.l[k] - P.tick, 5, stop, b.l[k - 6:k].min()))
        if up and b.c[k - 1] < b.c[k - 6] and reds >= 3:
            stop = b.l[k - 5:k + 1].min()
            out.append(P.stop_order(k, 1, b.h[k] + P.tick, 5, stop, b.h[k - 6:k].max()))
    return out, None, None


def _umbrella(ctx, tf, daily=False):
    P = Pat(ctx, tf) if not daily else Pat(ctx, 1, bars=_daily_bars(ctx))
    b, out = P.b, []
    for k in range(3, P.n - 1):
        if not daily and not (P.ok_time(k) and P.same_ses(k - 2, k)):
            continue
        r = P.rng[k]
        if r <= 0:
            continue
        uw = b.h[k] - max(b.o[k], b.c[k])
        lw = min(b.o[k], b.c[k]) - b.l[k]
        if not (P.body[k] <= 0.3 * r and uw <= 0.1 * r and lw >= 2 * P.body[k]):
            continue
        strong = lambda j, g: (P.green[j] if g else P.red[j]) and P.rng[j] > 0 and P.body[j] >= 0.6 * P.rng[j]
        tmax = None
        for side, prior in ((-1, strong(k - 2, True) and strong(k - 1, True)), (1, strong(k - 2, False) and strong(k - 1, False))):
            if not prior:
                continue
            for j in range(k + 1, min(k + 4, P.n - 1)):
                if not daily and not (P.same_ses(k, j) and P.ok_time(j)):
                    break
                hit = b.c[j] < b.l[k] if side < 0 else b.c[j] > b.h[k]
                if hit:
                    stop = b.h[k] if side < 0 else b.l[k]
                    tm = min(P.start(j) + 5, P.n - 1) if daily else -1
                    out.append(P.mkt(j, side, stop, P.r_target(side, b.c[j], stop, 2), tmax=tm))
                    break
    return out, None, None


@both("book-tom-14", variants=T15)
def tom14(ctx, tf):
    return _umbrella(ctx, tf)


@rule("book-tom-14", "DLY")
def tom14_d(ctx):
    return _umbrella(ctx, 1, daily=True)


@both("book-tom-15", variants=T5_15)
def tom15(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    sm = I.sma(b.c, 89)
    for k in range(3, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 2, k)) or P.rng[k] <= 0 or sm[k] != sm[k]:
            continue
        a = P.atr[k]
        uw = b.h[k] - max(b.o[k], b.c[k])
        lw = min(b.o[k], b.c[k]) - b.l[k]
        maru = uw <= 0.05 * P.rng[k] and lw <= 0.05 * P.rng[k] and P.body[k] >= a
        if not maru:
            continue
        if b.c[k] < sm[k] and P.red[k] and P.green[k - 2] and P.green[k - 1]:
            stop = b.h[k - 2:k + 1].max()
            out.append(P.mkt(k, -1, stop, P.r_target(-1, b.c[k], stop, 2), tmax=P.tmax_minutes(k, 30)))
        if b.c[k] > sm[k] and P.green[k] and P.red[k - 2] and P.red[k - 1]:
            stop = b.l[k - 2:k + 1].min()
            out.append(P.mkt(k, 1, stop, P.r_target(1, b.c[k], stop, 2), tmax=P.tmax_minutes(k, 30)))
    return out, None, None


@both("book-tom-16", mcx=("GOLD", "CRUDE"), variants=T15)
def tom16(ctx, tf):
    P = Pat(ctx, tf)
    Ph = Pat(ctx, 60)
    b, out = P.b, []
    hs, ls = P.swing_lists(2)
    hh, hl = Ph.swing_lists(2)
    # 60-minute structure at each 15m bar: last two confirmed 60m swing highs and lows
    def htf_state(i1):
        H = [x for x in hh if Ph.b.i1[x[1]] < i1][-2:]
        L = [x for x in hl if Ph.b.i1[x[1]] < i1][-2:]
        if len(H) < 2 or len(L) < 2:
            return 0
        if H[1][2] > H[0][2] and L[1][2] > L[0][2]:
            return 1
        if H[1][2] < H[0][2] and L[1][2] < L[0][2]:
            return -1
        return 0
    seen = set()
    for j in range(2, len(hs)):
        bar, conf, v = hs[j]
        if conf >= P.n - 1 or not P.ok_time(conf):
            continue
        prevH = hs[j - 1]
        lows_between = [x for x in ls if prevH[0] < x[0] < bar]
        if not lows_between or v <= prevH[2]:
            continue
        pull = prevH[2] - lows_between[-1][2]
        if pull <= 0 or htf_state(b.i1[conf]) != 1:
            continue
        lvl = v - pull
        out.append(P.limit_order(conf, 1, lvl, P.s.s1[P.s.ses[b.i1[conf]]], lvl - pull, v))
    for j in range(2, len(ls)):
        bar, conf, v = ls[j]
        if conf >= P.n - 1 or not P.ok_time(conf):
            continue
        prevL = ls[j - 1]
        highs_between = [x for x in hs if prevL[0] < x[0] < bar]
        if not highs_between or v >= prevL[2]:
            continue
        pull = highs_between[-1][2] - prevL[2]
        if pull <= 0 or htf_state(b.i1[conf]) != -1:
            continue
        lvl = v + pull
        out.append(P.limit_order(conf, -1, lvl, P.s.s1[P.s.ses[b.i1[conf]]], lvl + pull, v))
    return out, None, None


# ================================================================================================ Advanced Trading Book
def _swing_low_near(P, k, price, tol, sessions=5):
    return any(abs(x[2] - price) <= tol for x in P.recent(k, "L", sessions=sessions, limit=400))


def _swing_high_near(P, k, price, tol, sessions=5):
    return any(abs(x[2] - price) <= tol for x in P.recent(k, "H", sessions=sessions, limit=400))


@both("book-atb-01", mcx=("GOLD", "CRUDE"), variants={"15m": dict(tf=15), "5m": dict(tf=5)})
def atb01(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for H in range(1, P.n - 2):
        k = H + 1                                        # the confirmation bar
        if not (P.ok_time(k) and P.same_ses(H, k)) or P.rng[H] <= 0:
            continue
        a = P.atr[H]
        if a != a or P.rng[H] < 0.8 * a:
            continue
        uw = b.h[H] - max(b.o[H], b.c[H])
        lw = min(b.o[H], b.c[H]) - b.l[H]
        if P.trend[H - 1] < 0 and lw >= 2 * P.body[H] and uw <= 0.25 * P.rng[H] and \
                _swing_low_near(P, H, b.l[H], 0.25 * a) and b.c[k] > b.h[H]:
            stop = b.l[H] - P.tick
            out.append(P.mkt(k, 1, stop, P.r_target(1, b.c[k], stop, 3)))
        if P.trend[H - 1] > 0 and uw >= 2 * P.body[H] and lw <= 0.25 * P.rng[H] and \
                _swing_high_near(P, H, b.h[H], 0.25 * a) and b.c[k] < b.l[H]:
            stop = b.h[H] + P.tick
            out.append(P.mkt(k, -1, stop, P.r_target(-1, b.c[k], stop, 3)))
    return out, None, None


@both("book-atb-02", mcx=("GOLD",), variants=T15)
def atb02(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(2, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 1, k)):
            continue
        b1, b2 = k - 1, k
        if P.trend[b1 - 1] < 0 and P.red[b1] and P.green[b2] and b.o[b2] <= b.c[b1] and b.c[b2] >= b.o[b1] \
                and P.body[b2] >= 1.5 * P.body[b1]:
            stop = b.l[b2] - P.tick
            out.append(P.mkt(k, 1, stop, P.r_target(1, b.c[k], stop, 3)))
        if P.trend[b1 - 1] > 0 and P.green[b1] and P.red[b2] and b.o[b2] >= b.c[b1] and b.c[b2] <= b.o[b1] \
                and P.body[b2] >= 1.5 * P.body[b1]:
            stop = b.h[b2] + P.tick
            out.append(P.mkt(k, -1, stop, P.r_target(-1, b.c[k], stop, 3)))
    return out, None, None


@both("book-atb-03", variants=T15)
def atb03(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(3, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 2, k)):
            continue
        b1, b2, b3 = k - 2, k - 1, k
        a = P.atr[b1]
        if a != a:
            continue
        mid1 = (b.o[b1] + b.c[b1]) / 2
        if P.trend[b1 - 1] < 0 and P.red[b1] and P.body[b1] >= a and P.body[b2] <= 0.3 * P.body[b1] and \
                P.green[b3] and b.c[b3] > mid1:
            stop = b.l[b2] - P.tick
            out.append(P.mkt(k, 1, stop, P.r_target(1, b.c[k], stop, 3)))
        if P.trend[b1 - 1] > 0 and P.green[b1] and P.body[b1] >= a and P.body[b2] <= 0.3 * P.body[b1] and \
                P.red[b3] and b.c[b3] < mid1:
            stop = b.h[b2] + P.tick
            out.append(P.mkt(k, -1, stop, P.r_target(-1, b.c[k], stop, 3)))
    return out, None, None


@both("book-atb-04", mcx=("GOLD",), variants=T15)
def atb04(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    rs = I.rsi(b.c, 14)
    for k in range(4, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - 2, k)):
            continue
        j = (k - 2, k - 1, k)
        a = P.atr[k - 2]
        if a != a or min(P.rng[x] for x in j) <= 0:
            continue
        wick = lambda x: (b.h[x] - max(b.o[x], b.c[x])) <= 0.25 * P.rng[x] and (min(b.o[x], b.c[x]) - b.l[x]) <= 0.25 * P.rng[x]
        big = all(P.body[x] >= 0.6 * a and wick(x) for x in j)
        if not big:
            continue
        if P.trend[k - 3] < 0 and all(P.green[x] for x in j) and all(b.o[x] > b.o[x - 1] and b.c[x] > b.c[x - 1] for x in j[1:]) \
                and rs[k] > 50 and np.nanmin(rs[k - 3:k]) <= 50:
            stop = b.l[k - 2] - P.tick
            out.append(P.mkt(k, 1, stop, P.r_target(1, b.c[k], stop, 3)))
        if P.trend[k - 3] > 0 and all(P.red[x] for x in j) and all(b.o[x] < b.o[x - 1] and b.c[x] < b.c[x - 1] for x in j[1:]) \
                and rs[k] < 50 and np.nanmax(rs[k - 3:k]) >= 50:
            stop = b.h[k - 2] + P.tick
            out.append(P.mkt(k, -1, stop, P.r_target(-1, b.c[k], stop, 3)))
    return out, None, None


@both("book-atb-05", variants=T15)
def atb05(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for D in range(1, P.n - 4):
        k = D + 3
        if not (P.ok_time(k) and P.same_ses(D, k)) or P.rng[D] <= 0:
            continue
        a = P.atr[D]
        if a != a or P.body[D] > 0.1 * P.rng[D] or P.rng[D] < 0.8 * a:
            continue
        if b.c[k] > b.h[D]:
            stop = b.l[D]
            out.append(P.mkt(k, 1, stop, P.r_target(1, b.c[k], stop, 3)))
        elif b.c[k] < b.l[D]:
            stop = b.h[D]
            out.append(P.mkt(k, -1, stop, P.r_target(-1, b.c[k], stop, 3)))
    return out, None, None


def _line_from_swings(X):
    return fit_line(X[0][0], X[0][2], X[-1][0], X[-1][2])


@both("book-atb-06", mcx=("CRUDE",), variants={"15m": dict(tf=15)})
def atb06(ctx, tf):
    """Falling line through >= 3 falling 15m swing highs; 15m close above it = break; within 12 five-minute bars a 5m
    bar trades back within 0.1 ATR of the line and closes green above it: buy the next 5m open. Long only."""
    P = Pat(ctx, tf)
    P5 = Pat(ctx, 5)
    b, b5, out = P.b, P5.b, []
    hs, ls = P.swing_lists()
    hi_i = 0
    used = set()
    min_per_bar = tf
    for k in range(1, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            hi_i += 1
        if hi_i < 3:
            continue
        X = hs[hi_i - 3:hi_i]
        if not (X[0][2] > X[1][2] > X[2][2]) or X[0][0] in used:
            continue
        a = P.atr[k]
        if a != a:
            continue
        line = _line_from_swings(X)
        if abs(line_at(line, X[1][0]) - X[1][2]) > 0.1 * a:
            continue
        seg = range(X[0][0], X[2][0] + 1)
        if any(b.c[j] > line_at(line, j) for j in seg):
            continue
        if not (b.c[k] > line_at(line, k) and b.c[k - 1] <= line_at(line, k - 1)):
            continue
        used.add(X[0][0])
        # 5m bars after the break, same session
        j0 = int(np.searchsorted(b5.i0, b.i1[k] + 1))
        for j in range(j0, min(j0 + 12, P5.n - 1)):
            if b5.ses[j] != b.ses[k]:
                break
            x15 = k + (b5.i1[j] - b.i1[k]) / float(min_per_bar)      # the line in 15m-bar units
            lv = line_at(line, x15)
            if b5.l[j] <= lv + 0.1 * a and P5.green[j] and b5.c[j] > lv and P5.ok_time(j):
                stop = b5.l[j] - P.tick
                out.append(P5.mkt(j, 1, stop, P.r_target(1, b5.c[j], stop, 3)))
                break
    return out, None, None


def _converging(X, Y):
    """Lines through the first and last of the swing highs X and lows Y."""
    return _line_from_swings(X), _line_from_swings(Y)


@both("book-atb-07", mcx=("CRUDE", "GOLD"), variants=T15)
def atb07(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    hi_i = lo_i = 0
    used = set()
    for k in range(25, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            hi_i += 1
        while lo_i < len(ls) and ls[lo_i][1] <= k:
            lo_i += 1
        if hi_i < 2 or lo_i < 2:
            continue
        X, Y = hs[hi_i - 2:hi_i], ls[lo_i - 2:lo_i]
        a = P.atr[k]
        if a != a or (X[0][0], Y[0][0]) in used:
            continue
        first = min(X[0][0], Y[0][0])
        if first < 21 or not (P.same_ses(first, k) or b.ses[first] == b.ses[k] - 1):
            continue
        if not (X[0][2] > X[1][2] and Y[0][2] < Y[1][2]):
            continue
        up, lo = _converging(X, Y)
        height = X[0][2] - Y[0][2]
        for side in (1, -1):
            prior = (b.c[first] - b.c[first - 20]) * side
            if prior < 2 * a:
                continue
            ln = up if side > 0 else lo
            brk = (b.c[k] > line_at(ln, k) and b.c[k - 1] <= line_at(ln, k - 1)) if side > 0 else \
                  (b.c[k] < line_at(ln, k) and b.c[k - 1] >= line_at(ln, k - 1))
            if not brk:
                continue
            used.add((X[0][0], Y[0][0]))
            lvl_break = line_at(ln, k)
            for j in range(k + 1, min(k + 9, P.n - 1)):
                if not P.same_ses(k, j):
                    break
                lv = line_at(ln, j)
                back = b.l[j] <= lv + 0.1 * a if side > 0 else b.h[j] >= lv - 0.1 * a
                if back and j + 1 < P.n and P.same_ses(j, j + 1):
                    m = j + 1
                    conf = (P.green[m] and b.c[m] > line_at(ln, m)) if side > 0 else (P.red[m] and b.c[m] < line_at(ln, m))
                    if conf and P.ok_time(m):
                        stop = Y[-1][2] if side > 0 else X[-1][2]
                        out.append(P.mkt(m, side, stop, lvl_break + side * height))
                    break
            break
    return out, None, None


@both("book-atb-08", mcx=("CRUDE",), variants=T15)
def atb08(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    hi_i = lo_i = 0
    used = set()
    for k in range(25, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            hi_i += 1
        while lo_i < len(ls) and ls[lo_i][1] <= k:
            lo_i += 1
        if hi_i < 2 or lo_i < 2:
            continue
        X, Y = hs[hi_i - 2:hi_i], ls[lo_i - 2:lo_i]
        key = (X[0][0], Y[0][0])
        if key in used:
            continue
        a = P.atr[k]
        first = min(X[0][0], Y[0][0])
        if a != a or first < 21 or not (P.same_ses(first, k) or b.ses[first] == b.ses[k] - 1):
            continue
        sh_, sl_ = _converging(X, Y)
        # rising wedge in a downtrend (sell) or falling wedge in an uptrend (buy)
        for side in (-1, 1):
            if side < 0:
                ok = X[1][2] > X[0][2] and Y[1][2] > Y[0][2] and sl_[0] > sh_[0] > 0 and P.trend[first] < 0
                ln, last_ext, opp_ext = sl_, Y[-1][2], X[-1][2]
                broke_line = b.c[k] < line_at(ln, k)
            else:
                ok = X[1][2] < X[0][2] and Y[1][2] < Y[0][2] and sh_[0] < sl_[0] < 0 and P.trend[first] > 0
                ln, last_ext, opp_ext = sh_, X[-1][2], Y[-1][2]
                broke_line = b.c[k] > line_at(ln, k)
            if not (ok and broke_line):
                continue
            used.add(key)
            height = X[0][2] - Y[0][2]
            # step 2: a close beyond the last swing inside the wedge; step 3: a retest of it within 8 bars
            j2 = None
            for j in range(k, min(k + 12, P.n - 1)):
                if not P.same_ses(k, j):
                    break
                if (side < 0 and b.c[j] < last_ext) or (side > 0 and b.c[j] > last_ext):
                    j2 = j
                    break
            if j2 is None:
                break
            for j in range(j2 + 1, min(j2 + 9, P.n - 1)):
                if not P.same_ses(j2, j):
                    break
                if (side < 0 and b.c[j] > last_ext) or (side > 0 and b.c[j] < last_ext):
                    break                                 # cancelled
                near = (b.h[j] >= last_ext - 0.1 * a) if side < 0 else (b.l[j] <= last_ext + 0.1 * a)
                conf = (P.red[j] and b.c[j] < last_ext) if side < 0 else (P.green[j] and b.c[j] > last_ext)
                if near and conf and P.ok_time(j):
                    out.append(P.mkt(j, side, opp_ext, b.c[j] + side * height))
                    break
            break
    return out, None, None


@both("book-atb-09", mcx=("CRUDE",), variants=T15)
def atb09(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    hi_i = lo_i = 0
    used = set()
    for k in range(25, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            hi_i += 1
        while lo_i < len(ls) and ls[lo_i][1] <= k:
            lo_i += 1
        if hi_i < 2 or lo_i < 2 or not P.ok_time(k):
            continue
        X, Y = hs[hi_i - 2:hi_i], ls[lo_i - 2:lo_i]
        a = P.atr[k]
        first = min(X[0][0], Y[0][0])
        if a != a or k - first < 8 or (X[0][0], Y[0][0]) in used:
            continue
        if not (P.same_ses(first, k) or b.ses[first] == b.ses[k] - 1):
            continue
        # descending triangle in a downtrend: falling highs, flat lows -> sell the close below the base
        if P.trend[first] < 0 and X[0][2] > X[1][2] and abs(Y[0][2] - Y[1][2]) <= 0.3 * a:
            base = min(Y[0][2], Y[1][2])
            if b.c[k] < base and b.c[k - 1] >= base:
                used.add((X[0][0], Y[0][0]))
                out.append(P.mkt(k, -1, X[-1][2], base - (X[0][2] - base)))
        if P.trend[first] > 0 and Y[0][2] < Y[1][2] and abs(X[0][2] - X[1][2]) <= 0.3 * a:
            top = max(X[0][2], X[1][2])
            if b.c[k] > top and b.c[k - 1] <= top:
                used.add((X[0][0], Y[0][0]))
                out.append(P.mkt(k, 1, Y[-1][2], top + (top - Y[0][2])))
    return out, None, None


def _poles(P, k, side, lens):
    """Candidate (pole end p, pole length) whose flag runs p+1..k-1 with len in lens."""
    b = P.b
    for L in lens:
        p = k - 1 - L
        if p < 6 or not P.same_ses(p - 5, k):
            continue
        a = P.atr[p]
        if a != a:
            continue
        if side > 0:
            pole = b.h[p] - b.l[p - 5:p + 1].min()
            if b.h[p] < b.h[p - 5:p + 1].max() or pole < 2 * a:
                continue
        else:
            pole = b.h[p - 5:p + 1].max() - b.l[p]
            if b.l[p] > b.l[p - 5:p + 1].min() or pole < 2 * a:
                continue
        yield p, pole


def _flag_lines(P, p, k):
    b = P.b
    xs = np.arange(p + 1, k)
    return regress(xs, b.h[p + 1:k]), regress(xs, b.l[p + 1:k])


def _flag(ctx, tf, kind):
    """kind 'flag' (atb-10: parallel channel against the pole, retrace <= 50%) or 'pennant' (atb-16: converging,
    inside the pole's last 50%). Break in the pole's direction at a close: enter the next open."""
    P = Pat(ctx, tf)
    b, out = P.b, []
    used = set()
    lens = range(4, 16) if kind == "flag" else range(4, 13)
    for k in range(10, P.n - 1):
        if not P.ok_time(k):
            continue
        for side in (1, -1):
            for p, pole in _poles(P, k, side, lens):
                if (p, side) in used:
                    continue
                hl, ll = _flag_lines(P, p, k)
                fh, fl = b.h[p + 1:k], b.l[p + 1:k]
                if side > 0:
                    if fh.max() > b.h[p] or fl.min() < b.h[p] - 0.5 * pole:
                        continue
                else:
                    if fl.min() < b.l[p] or fh.max() > b.l[p] + 0.5 * pole:
                        continue
                sh_, sl_ = hl[0], ll[0]
                if kind == "flag":
                    against = (sh_ < 0 and sl_ < 0) if side > 0 else (sh_ > 0 and sl_ > 0)
                    par = abs(sh_ - sl_) <= 0.3 * max(abs(sh_), abs(sl_))
                    if not (against and par):
                        continue
                else:
                    if not (sh_ < 0 < sl_):
                        continue
                upper = line_at(hl, k)
                lower = line_at(ll, k)
                brk = b.c[k] > upper if side > 0 else b.c[k] < lower
                if not brk:
                    continue
                used.add((p, side))
                stop = lower if side > 0 else upper
                lvl = upper if side > 0 else lower
                out.append(P.mkt(k, side, stop, lvl + side * pole))
                break
    return _lock(P, out), None, None


@both("book-atb-10", mcx=("CRUDE",), variants=T5_15, crypto=True)
def atb10(ctx, tf):
    return _flag(ctx, tf, "flag")


@both("book-atb-16", mcx=("CRUDE",), variants=T5_15)
def atb16(ctx, tf):
    return _flag(ctx, tf, "pennant")


def _boxes(P, k):
    """A box ending at bar k-1: >= 6 bars inside <= 1.2 ATR after a >= 2 ATR move within the 10 bars before it.
    Returns (top, bottom, prior move sign) or None."""
    b = P.b
    for L in (6, 8, 10, 12):
        s0 = k - L
        if s0 < 11 or not P.same_ses(s0 - 10, k):
            continue
        a = P.atr[s0]
        if a != a:
            continue
        top, bot = b.h[s0:k].max(), b.l[s0:k].min()
        if top - bot > 1.2 * a:
            continue
        mv = b.c[s0 - 1] - b.c[s0 - 11]
        if abs(mv) < 2 * a:
            continue
        return top, bot, (1 if mv > 0 else -1)
    return None


@both("book-atb-11", mcx=("CRUDE", "GOLD"), variants={"mid": dict(tf=15, stop="mid"), "retest": dict(tf=15, stop="retest")})
def atb11(ctx, tf, stop):
    P = Pat(ctx, tf)
    P5 = Pat(ctx, 5)
    b, b5, out = P.b, P5.b, []
    for k in range(12, P.n - 1):
        bx = _boxes(P, k)
        if bx is None:
            continue
        top, bot, mv = bx
        a = P.atr[k]
        for side in (1, -1):
            lvl = top if side > 0 else bot
            if not ((side > 0 and b.c[k] > top) or (side < 0 and b.c[k] < bot)):
                continue
            j0 = int(np.searchsorted(b5.i0, b.i1[k] + 1))
            for j in range(j0, min(j0 + 12, P5.n - 1)):
                if b5.ses[j] != b.ses[k]:
                    break
                near = b5.l[j] <= lvl + 0.1 * a if side > 0 else b5.h[j] >= lvl - 0.1 * a
                conf = (P5.green[j] and b5.c[j] > lvl) if side > 0 else (P5.red[j] and b5.c[j] < lvl)
                if near and conf and P5.ok_time(j):
                    st = (top + bot) / 2 if stop == "mid" else (b5.l[j] - P.tick if side > 0 else b5.h[j] + P.tick)
                    out.append(P5.mkt(j, side, st, lvl + side * (top - bot)))
                    break
    return out, None, None


@both("book-atb-12", mcx=("CRUDE",), variants={"5m": dict(tf=5), "15m": dict(tf=15)})
def atb12(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    used = set()
    for A in range(10, P.n - 1):
        for side in (1, -1):
            for p, pole in _poles(P, A, side, range(4, 16)):
                if (p, side) in used:
                    continue
                hl, ll = _flag_lines(P, p, A)
                sh_, sl_ = hl[0], ll[0]
                against = (sh_ < 0 and sl_ < 0) if side > 0 else (sh_ > 0 and sl_ > 0)
                if not against or abs(sh_ - sl_) > 0.3 * max(abs(sh_), abs(sl_)):
                    continue
                fh = b.h[p + 1:A].max() if side > 0 else b.l[p + 1:A].min()
                if not ((side > 0 and b.c[A] > fh) or (side < 0 and b.c[A] < fh)):
                    continue
                used.add((p, side))
                # B: the first opposite-colour bar within 6 bars; C: the next bar in the trade's colour
                for B in range(A + 1, min(A + 7, P.n - 1)):
                    if not P.same_ses(A, B):
                        break
                    if (side > 0 and b.c[B] < fh) or (side < 0 and b.c[B] > fh):
                        break                             # closed back inside: cancel
                    opp = P.red[B] if side > 0 else P.green[B]
                    if opp:
                        C = B + 1
                        if C >= P.n - 1 or not P.same_ses(B, C):
                            break
                        ok = P.green[C] if side > 0 else P.red[C]
                        if ok and P.ok_time(C):
                            stop = b.l[B] - P.tick if side > 0 else b.h[B] + P.tick
                            out.append(P.mkt(C, side, stop, fh + side * pole))
                        break
                break
    return out, None, None


@both("book-atb-13", mcx=("GOLD", "CRUDE"), variants=T15)
def atb13(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    used = set()
    for tops, bots, side in ((hs, ls, -1), (ls, hs, 1)):
        for i in range(1, len(tops)):
            (b1, c1, v1), (b2, c2, v2) = tops[i - 1], tops[i]
            a = P.atr[b2]
            if a != a or b2 - b1 < 4 or abs(v1 - v2) > 0.25 * a:
                continue
            mids = [x for x in bots if b1 < x[0] < b2]
            if not mids:
                continue
            neck = min(x[2] for x in mids) if side < 0 else max(x[2] for x in mids)
            if side < 0 and max(v1, v2) - neck < a or side > 0 and neck - min(v1, v2) < a:
                continue
            ext = max(v1, v2) if side < 0 else min(v1, v2)
            for k in range(c2, min(c2 + 30, P.n - 1)):
                if not P.same_ses(c2, k):
                    break
                if (side < 0 and b.c[k] < neck) or (side > 0 and b.c[k] > neck):
                    if P.ok_time(k) and (b1, b2) not in used:
                        used.add((b1, b2))
                        out.append(P.mkt(k, side, (neck + ext) / 2, neck + side * abs(ext - neck)))
                    break
    return out, None, None


@both("book-atb-14", mcx=("GOLD", "CRUDE", "NATURALGAS"), variants=T15)
def atb14(ctx, tf):
    return _hs(ctx, tf, 2, "atb")


@both("book-atb-15", mcx=("CRUDE",), variants={"cont": dict(tf=15, mode="cont"), "rev": dict(tf=15, mode="rev")},
      crypto=True)
def atb15(ctx, tf, mode):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    hi_i = lo_i = 0
    used = set()
    for k in range(25, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            hi_i += 1
        while lo_i < len(ls) and ls[lo_i][1] <= k:
            lo_i += 1
        if hi_i < 2 or lo_i < 2 or not P.ok_time(k):
            continue
        X, Y = hs[hi_i - 2:hi_i], ls[lo_i - 2:lo_i]
        key = (X[0][0], Y[0][0])
        first = min(X[0][0], Y[0][0])
        a = P.atr[k]
        if key in used or a != a or first < 21 or not (P.same_ses(first, k) or b.ses[first] == b.ses[k] - 1):
            continue
        hl_, ll_ = _converging(X, Y)
        prior = b.c[first] - b.c[first - 20]
        # falling wedge (buy): highs and lows falling, upper falling faster
        if X[1][2] < X[0][2] and Y[1][2] < Y[0][2] and hl_[0] < ll_[0] < 0:
            want = prior > 0 if mode == "cont" else prior < 0
            up = line_at(hl_, k)
            if want and b.c[k] > up and b.c[k - 1] <= line_at(hl_, k - 1):
                used.add(key)
                out.append(P.mkt(k, 1, line_at(ll_, k), X[0][2]))
        if X[1][2] > X[0][2] and Y[1][2] > Y[0][2] and ll_[0] > hl_[0] > 0:
            want = prior < 0 if mode == "cont" else prior > 0
            lo = line_at(ll_, k)
            if want and b.c[k] < lo and b.c[k - 1] >= line_at(ll_, k - 1):
                used.add(key)
                out.append(P.mkt(k, -1, line_at(hl_, k), Y[0][2]))
    return _lock(P, out), None, None


@both("book-atb-17", mcx=("GOLD", "CRUDE"), variants=T15)
def atb17(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(12, P.n - 1):
        if not P.ok_time(k):
            continue
        bx = _boxes(P, k)
        if bx is None:
            continue
        top, bot, mv = bx
        if mv > 0 and b.c[k] > top:
            out.append(P.mkt(k, 1, bot - P.tick, top + (top - bot)))
        if mv < 0 and b.c[k] < bot:
            out.append(P.mkt(k, -1, top + P.tick, bot - (top - bot)))
    return out, None, None


@both("book-atb-18", mcx=("GOLD",), variants=T15)
def atb18(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    used = set()
    for tops, bots, side in ((hs, ls, -1), (ls, hs, 1)):
        for i in range(2, len(tops)):
            T3 = tops[i - 2:i + 1]
            vals = [x[2] for x in T3]
            a = P.atr[T3[-1][0]]
            if a != a or max(vals) - min(vals) > 0.25 * a:
                continue
            mids = [x for x in bots if T3[0][0] < x[0] < T3[2][0]]
            if not mids:
                continue
            sup = min(x[2] for x in mids) if side < 0 else max(x[2] for x in mids)
            ext = max(vals) if side < 0 else min(vals)
            for k in range(T3[2][1], min(T3[2][1] + 30, P.n - 1)):
                if not P.same_ses(T3[2][1], k):
                    break
                if (side < 0 and b.c[k] < sup) or (side > 0 and b.c[k] > sup):
                    if P.ok_time(k) and T3[0][0] not in used:
                        used.add(T3[0][0])
                        out.append(P.mkt(k, side, (sup + ext) / 2, sup + side * abs(ext - sup)))
                    break
    return out, None, None


@both("book-atb-19", mcx=("GOLD",), variants={"15m": dict(tf=15), "60m": dict(tf=60)}, crypto=True)
def atb19(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists()
    used = set()
    for side, src in ((1, hs), (-1, ls)):
        for bar, conf, R in src:
            if bar in used:
                continue
            a = P.atr[bar]
            if a != a:
                continue
            # cup: >= 15 bars, depth >= 1.5 ATR, right side back within 0.25 ATR of the rim
            k = bar + 15
            ext = R
            back = None
            while k < min(bar + 120, P.n - 1):
                seg = b.l[bar + 1:k + 1] if side > 0 else b.h[bar + 1:k + 1]
                ext = seg.min() if side > 0 else seg.max()
                depth = (R - ext) * side
                near = (b.h[k] >= R - 0.25 * a and b.h[k] <= R) if side > 0 else (b.l[k] <= R + 0.25 * a and b.l[k] >= R)
                if depth >= 1.5 * a and near:
                    back = k
                    break
                if (side > 0 and b.h[k] > R) or (side < 0 and b.l[k] < R):
                    break
                k += 1
            if back is None:
                continue
            depth = (R - ext) * side
            # handle: 3-10 bars below the rim retracing <= 50% of the depth, then the first close beyond the rim
            for m in range(back + 3, min(back + 12, P.n - 1)):
                h_seg = slice(back + 1, m)
                hlo = b.l[h_seg].min() if side > 0 else b.h[h_seg].max()
                if (R - hlo) * side > 0.5 * depth:
                    break
                if (side > 0 and b.c[m] > R) or (side < 0 and b.c[m] < R):
                    if P.ok_time(m) and m - back - 1 >= 3:
                        used.add(bar)
                        out.append(P.mkt(m, side, hlo - side * P.tick, R + side * depth))
                    break
    return _lock(P, out), None, None


# ================================================================================================ Sadowski candles
@both("book-sadowski-04", variants={"N1": dict(tf=15, N=1), "N2": dict(tf=15, N=2), "N3": dict(tf=15, N=3)})
def sad04(ctx, tf, N):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(N, P.n - 1):
        if not (P.ok_time(k) and P.same_ses(k - N, k)):
            continue
        lo_b = min(min(b.o[j], b.c[j]) for j in range(k - N, k))
        hi_b = max(max(b.o[j], b.c[j]) for j in range(k - N, k))
        if P.green[k] and b.o[k] <= lo_b and b.c[k] >= hi_b:
            out.append(P.mkt(k, 1, b.l[k]))
        if P.red[k] and b.o[k] >= hi_b and b.c[k] <= lo_b:
            out.append(P.mkt(k, -1, b.h[k]))
    trail = {1: ctx.to_rows(b, b.l), -1: ctx.to_rows(b, b.h)}
    return out, trail, None


@both("book-sadowski-05", variants=T15)
def sad05(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    for k in range(1, P.n - 1):
        if not P.ok_time(k) or P.rng[k] <= 0:
            continue
        lw = min(b.o[k], b.c[k]) - b.l[k]
        uw = b.h[k] - max(b.o[k], b.c[k])
        if lw >= 2 * P.body[k] and min(b.o[k], b.c[k]) >= b.l[k] + 2 / 3 * P.rng[k]:
            out.append(P.mkt(k, 1, b.l[k]))
        if uw >= 2 * P.body[k] and max(b.o[k], b.c[k]) <= b.l[k] + 1 / 3 * P.rng[k]:
            out.append(P.mkt(k, -1, b.h[k]))
    trail = {1: ctx.to_rows(b, b.l), -1: ctx.to_rows(b, b.h)}
    return out, trail, None


@both("book-sadowski-06", variants=T15)
def sad06(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    hs, ls = P.swing_lists(3)
    hi_i = lo_i = 0
    for k in range(1, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            hi_i += 1
        while lo_i < len(ls) and ls[lo_i][1] <= k:
            lo_i += 1
        if hi_i < 2 or lo_i < 2 or not P.ok_time(k):
            continue
        H, L = hs[hi_i - 2:hi_i], ls[lo_i - 2:lo_i]
        end_row = int(P.s.s0[P.s.ses[b.i1[k]]] + (14 * 60 + 30 - P.s.tod[P.s.s0[P.s.ses[b.i1[k]]]]))
        end_row = min(end_row, int(P.s.s1[P.s.ses[b.i1[k]]]))
        if H[1][2] > H[0][2] and L[1][2] > L[0][2] and b.c[k] > H[1][2] and b.c[k - 1] <= H[1][2]:
            newh = b.h[k]
            leg = newh - L[1][2]
            lv = (P.ema20[k], H[1][2], newh - 0.5 * leg)
            stop = L[1][2] - P.tick
            g = gid("s6")
            for x in lv:
                if x == x and stop < x < b.c[k]:
                    out.append(P.limit_order(k, 1, x, end_row, stop, newh + leg, grp=g, w=1 / 3))
        if H[1][2] < H[0][2] and L[1][2] < L[0][2] and b.c[k] < L[1][2] and b.c[k - 1] >= L[1][2]:
            newl = b.l[k]
            leg = H[1][2] - newl
            lv = (P.ema20[k], L[1][2], newl + 0.5 * leg)
            stop = H[1][2] + P.tick
            g = gid("s6")
            for x in lv:
                if x == x and b.c[k] < x < stop:
                    out.append(P.limit_order(k, -1, x, end_row, stop, newl - leg, grp=g, w=1 / 3))
    return out, None, None


def _daily_hl_by_session(ctx):
    """Per session index: (open, high, low, close) of that session from the minute series."""
    s = ctx.s
    out = {}
    for i in range(len(s.days)):
        a, z = s.s0[i], s.s1[i] + 1
        out[i] = (s.o[a], s.h[a:z].max(), s.l[a:z].min(), s.c[z - 1])
    return out


@both("book-sadowski-08", mcx=("CRUDE", "GOLD", "SILVER", "NATURALGAS"),
      variants={"NR4": dict(nr=4, _tab=True), "NR7": dict(nr=7, _tab=True)})
def sad08(ctx, nr):
    P = Pat(ctx, 5)
    b, out = P.b, []
    dd = _daily_hl_by_session(ctx)
    rngs = {i: dd[i][1] - dd[i][2] for i in dd}
    sh, shi, sl, sli = P.swings(2)
    done = set()
    for k in range(1, P.n - 1):
        s = int(b.ses[k])
        if s < nr or s in done or not P.ok_time(k, lo=9 * 60 + 20 if ctx.market == "IDX" else None):
            continue
        y = s - 1
        if rngs[y] > min(rngs[j] for j in range(y - nr + 1, y + 1)):
            continue
        yh, yl = dd[y][1], dd[y][2]
        if b.c[k] > yh:
            out.append(P.mkt(k, 1, yl, be=1.0))
            done.add(s)
        elif b.c[k] < yl:
            out.append(P.mkt(k, -1, yh, be=1.0))
            done.add(s)
    trail = {1: ctx.to_rows(b, sl), -1: ctx.to_rows(b, sh)}
    return out, trail, None
