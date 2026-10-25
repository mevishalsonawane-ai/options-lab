"""R104 family F10: indicator and intraday trend rules (PREREG.md; mechanics from research/books/TESTABLE_RULES.md).
Committed before this family's first run.

Readings fixed here where a rule is silent:
- Daily state for intraday rules (Hougaard's 3-bar swing): the previous sessions' bars only; inside today the state
  flips the minute price trades through the 3-bar high / low (as the rule says), known from that minute on.
- Hougaard 10: the 60-minute pullback = the last two confirmed 60-minute swing highs AND lows both falling (lower high,
  lower low) inside a daily UP state; stop = the lowest low since the first of those two swing highs; target = that
  first swing high.
- Heitkoetter 01 / 09: the line is drawn once per session leg (no re-anchoring); a new session low (high) restarts it.
- Sadowski 09: each RSI(2) cross is one unit, at most 3 open units a side; the opposite cross closes them all.
- Ozenbas 04: event days = Budget / RBI / results rows of events.csv (2024+); earlier days are no-event days.
- Douglas 04: daily swings are 3-bar fractals (1 bar each side) on the previous sessions' daily bars.
"""
from __future__ import annotations

import numpy as np

import r104data as DA
import r104ind as I
from f3_patterns import _daily_bars, _daily_hl_by_session, both
from r104pat import NAN, Pat, fit_line, gid, line_at
from r104run import rule


def _fractal_signals(b):
    """Hougaard / David Paul 4-bar fractal: +1 when close > high[t-1] and > high[t-3]; -1 mirror."""
    n = len(b.c)
    sig = np.zeros(n, int)
    for t in range(3, n):
        if b.c[t] > b.h[t - 1] and b.c[t] > b.h[t - 3]:
            sig[t] = 1
        elif b.c[t] < b.l[t - 1] and b.c[t] < b.l[t - 3]:
            sig[t] = -1
    return sig


def daily_swing_state(ctx):
    """Per minute: Hougaard's daily 3-bar swing state (+1 UP / -1 DOWN / 0 unknown)."""
    s = ctx.s
    dd = _daily_hl_by_session(ctx)
    nses = len(s.days)
    out = np.zeros(s.n)
    st = 0
    for i in range(nses):
        a, z = s.s0[i], s.s1[i] + 1
        if i >= 3:
            H3 = max(dd[j][1] for j in range(i - 3, i))
            L3 = min(dd[j][2] for j in range(i - 3, i))
        else:
            H3 = L3 = None
        for m in range(a, z):
            out[m] = st                         # known at the minute's open
            if H3 is not None:
                if s.h[m] > H3:
                    st = 1
                elif s.l[m] < L3:
                    st = -1
        # state carries to the next session
    return out


@both("book-tom-01", mcx=("GOLD", "SILVER", "CRUDE"),
      variants={"15m": dict(tf=15, bias=False), "60m": dict(tf=60, bias=False), "5m": dict(tf=5, bias=False),
                "15m_bias": dict(tf=15, bias=True)})
def tom01(ctx, tf, bias):
    P = Pat(ctx, tf)
    b, out = P.b, []
    sig = _fractal_signals(b)
    st = daily_swing_state(ctx) if bias else None
    for k in range(3, P.n - 1):
        if sig[k] == 0 or not P.ok_time(k):
            continue
        side = int(sig[k])
        if bias and st[b.i1[k]] != side:
            continue
        stop = b.l[k - 3:k + 1].min() if side > 0 else b.h[k - 3:k + 1].max()
        out.append(P.mkt(k, side, stop))
    xf = {1: ctx.to_rows(b, sig == -1, bool), -1: ctx.to_rows(b, sig == 1, bool)}
    return out, None, xf


@both("book-tom-02", mcx=("GOLD", "CRUDE"), variants={"60m": dict(tf=60), "15m": dict(tf=15)})
def tom02(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    sm = I.sma(b.c, 89)
    kk, _d = I.stoch(b.h, b.l, b.c, 14, 3, 3)
    sig = _fractal_signals(b)
    hs, ls = P.swing_lists(2)
    pend = []                                   # (side, until bar, stop)
    hi_i = lo_i = 0
    for k in range(3, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            j = hi_i
            if j >= 1:
                (b1, _c1, v1), (b2, _c2, v2) = hs[j - 1], hs[j]
                if v2 < v1 and kk[b2] > kk[b1] and sm[b2] == sm[b2] and b.c[b2] < sm[b2]:
                    pend.append((-1, k + 10, v2))
            hi_i += 1
        while lo_i < len(ls) and ls[lo_i][1] <= k:
            j = lo_i
            if j >= 1:
                (b1, _c1, v1), (b2, _c2, v2) = ls[j - 1], ls[j]
                if v2 > v1 and kk[b2] < kk[b1] and sm[b2] == sm[b2] and b.c[b2] > sm[b2]:
                    pend.append((1, k + 10, v2))
            lo_i += 1
        pend = [p for p in pend if p[1] >= k]
        if sig[k] == 0 or not P.ok_time(k):
            continue
        for p in list(pend):
            if p[0] == sig[k]:
                out.append(P.mkt(k, p[0], p[2]))
                pend.remove(p)
                break
    xf = {1: ctx.to_rows(b, sig == -1, bool), -1: ctx.to_rows(b, sig == 1, bool)}
    return out, None, xf


@both("book-tom-04", mcx=("CRUDE", "GOLD"))
def tom04(ctx):
    s = ctx.s
    dd = _daily_hl_by_session(ctx)
    P1 = Pat(ctx, 1)
    out = []
    # daily ATR from the session bars
    keys = sorted(dd)
    hi = np.array([dd[i][1] for i in keys])
    lo = np.array([dd[i][2] for i in keys])
    cl = np.array([dd[i][3] for i in keys])
    atr = I.atr(hi, lo, cl, 14)
    lo_t = 9 * 60 + 20 if ctx.market == "IDX" else 9 * 60 + 15
    for i in keys:
        if i < 15 or atr[i - 1] != atr[i - 1]:
            continue
        H3, L3 = hi[i - 3:i].max(), lo[i - 3:i].min()
        a = atr[i - 1]
        a0 = s.s0[i]
        ts = np.searchsorted(s.tod[a0:s.s1[i] + 1], lo_t)
        start = a0 + int(ts)
        if start > s.s1[i]:
            continue
        hi_t = 14 * 60 + 30 if ctx.market == "IDX" else int(s.tod[s.sq[i]] - 30)
        te = a0 + int(np.searchsorted(s.tod[a0:s.s1[i] + 1], hi_t, side="right")) - 1
        g = gid("t4")
        stopL = max(L3, H3 - a)
        stopS = min(H3, L3 + a)
        out.append((start, 1, 1, float(H3), te, float(stopL), NAN, -1, NAN, g + "|L", 1.0, float(H3)))
        out.append((start, -1, 1, float(L3), te, float(stopS), NAN, -1, NAN, g + "|S", 1.0, float(L3)))
    return out, None, None


@both("book-tom-10", mcx=("GOLD", "CRUDE"), variants={"15m": dict()})
def tom10(ctx):
    P = Pat(ctx, 15)
    Ph = Pat(ctx, 60)
    b, out = P.b, []
    sig = _fractal_signals(b)
    st = daily_swing_state(ctx)
    hh, hl = Ph.swing_lists(2)
    for k in range(3, P.n - 1):
        if sig[k] == 0 or not P.ok_time(k):
            continue
        side = int(sig[k])
        row = b.i1[k]
        if st[row] != side:
            continue
        H = [x for x in hh if Ph.b.i1[x[1]] < row][-2:]
        L = [x for x in hl if Ph.b.i1[x[1]] < row][-2:]
        if len(H) < 2 or len(L) < 2:
            continue
        if side > 0 and H[1][2] < H[0][2] and L[1][2] < L[0][2]:
            i0 = Ph.b.i0[H[0][0]]
            stop = ctx.s.l[i0:row + 1].min()
            if H[0][2] > b.c[k]:
                out.append(P.mkt(k, 1, stop, H[0][2]))
        if side < 0 and H[1][2] > H[0][2] and L[1][2] > L[0][2]:
            i0 = Ph.b.i0[L[0][0]]
            stop = ctx.s.h[i0:row + 1].max()
            if L[0][2] < b.c[k]:
                out.append(P.mkt(k, -1, stop, L[0][2]))
    return out, None, None


@rule("book-ozenbas-04", "IDX", {"1m": dict()})
def ozenbas04(ctx):
    s = ctx.s
    ev = DA.events()
    evd = set(ev[ev.kind.isin(["budget", "rbi", "results"])].date)
    P = Pat(ctx, 1)
    c = s.c
    mv = np.full(s.n, np.nan)
    mv[5:] = c[5:] - c[:-5]
    same = np.r_[np.zeros(5, bool), s.ses[5:] == s.ses[:-5]]
    mv[~same] = np.nan
    # trailing 20-session average |5-min move| at the same minute of day
    by_tod = {}
    base = np.full(s.n, np.nan)
    for i in range(len(s.days)):
        for m in range(s.s0[i], s.s1[i] + 1):
            t = int(s.tod[m])
            q = by_tod.setdefault(t, [])
            if len(q) >= 10:
                base[m] = np.mean(q[-20:])
        for m in range(s.s0[i], s.s1[i] + 1):
            if mv[m] == mv[m]:
                by_tod.setdefault(int(s.tod[m]), []).append(abs(mv[m]))
    out = []
    for m in range(5, s.n - 1):
        t = int(s.tod[m]) + 1
        if not (9 * 60 + 30 <= t <= 14 * 60 + 30) or mv[m] != mv[m] or base[m] != base[m]:
            continue
        if abs(mv[m]) <= 3 * base[m] or s.ses[m + 1] != s.ses[m]:
            continue
        d = s.days[s.ses[m]]
        side = int(np.sign(mv[m])) * (1 if d in evd else -1)
        stop = c[m] - side * abs(mv[m])
        out.append((m + 1, side, 0, NAN, -1, float(stop), NAN, m + 1 + 30, NAN, gid(), 1.0, float(c[m])))
    return out, None, None


@rule("book-ozenbas-05", "IDX", {"1m": dict()})
def ozenbas05(ctx):
    s = ctx.s
    c = s.c
    hi15 = I.rolling_max(np.r_[np.nan, c[:-1]], 15)
    lo15 = I.rolling_min(np.r_[np.nan, c[:-1]], 15)
    lw15 = I.rolling_min(s.l, 15)
    hw15 = I.rolling_max(s.h, 15)
    same = np.r_[np.zeros(15, bool), s.ses[15:] == s.ses[:-15]]
    up = (c > hi15) & same
    dn = (c < lo15) & same
    out = []
    for m in range(16, s.n - 1):
        t = int(s.tod[m]) + 1
        if not (9 * 60 + 30 <= t <= 14 * 60 + 30) or s.ses[m + 1] != s.ses[m]:
            continue
        if up[m]:
            out.append((m + 1, 1, 0, NAN, -1, float(lw15[m]), NAN, -1, NAN, gid(), 1.0, float(c[m])))
        elif dn[m]:
            out.append((m + 1, -1, 0, NAN, -1, float(hw15[m]), NAN, -1, NAN, gid(), 1.0, float(c[m])))
    xf = {1: np.r_[False, dn[:-1]], -1: np.r_[False, up[:-1]]}
    return out, None, xf


@rule("book-sadowski-03", "IDX", {"60m": dict(tf=60), "15m": dict(tf=15)})
def sad03(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    e = P.ema50
    sh, shi, sl, sli = P.swings(2)
    for k in range(20, P.n - 1):
        if not P.ok_time(k):
            continue
        a = P.atr[k - 1]
        w = slice(k - 8, k)
        if a != a or b.h[w].max() - b.l[w].min() > 1.5 * a:
            continue
        ov = True
        for j in range(k - 7, k):
            o_ = min(b.h[j], b.h[j - 1]) - max(b.l[j], b.l[j - 1])
            if P.rng[j] <= 0 or o_ < 0.5 * P.rng[j]:
                ov = False
                break
        if not ov:
            continue
        top, bot = b.h[w].max(), b.l[w].min()
        down = b.c[k - 1] < e[k - 1] and e[k - 1] < e[k - 11]
        up = b.c[k - 1] > e[k - 1] and e[k - 1] > e[k - 11]
        if down and b.c[k] < bot and b.c[k - 1] >= bot:
            stop = sh[k] if sh[k] == sh[k] else top
            out.append(P.mkt(k, -1, stop, P.r_target(-1, b.c[k], stop, 3)))
        if up and b.c[k] > top and b.c[k - 1] > top and b.c[k - 2] <= top:
            out.append(P.mkt(k, 1, bot, P.r_target(1, b.c[k], bot, 3)))
    trail = {1: ctx.to_rows(b, sl), -1: ctx.to_rows(b, sh)}
    return out, trail, None


@both("book-sadowski-07", mcx=("CRUDE", "GOLD"), variants={"60m": dict(tf=60), "15m": dict(tf=15)})
def sad07(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    mid, up, lo = I.bollinger(b.c, 20, 2.0)
    bw = (up - lo) / mid
    sq = np.zeros(P.n, bool)
    for k in range(120, P.n):
        w = bw[k - 119:k + 1]
        if np.isfinite(bw[k]) and bw[k] <= np.nanpercentile(w, 10):
            sq[k] = True
    recent = np.zeros(P.n, bool)
    for k in range(P.n):
        recent[k] = sq[max(0, k - 3):k + 1].any()
    for k in range(121, P.n - 1):
        if not recent[k] or not P.ok_time(k):
            continue
        if b.o[k] > mid[k] and b.c[k] > mid[k] and not (b.o[k - 1] > mid[k - 1] and b.c[k - 1] > mid[k - 1]):
            out.append(P.mkt(k, 1, b.l[k - 1], up[k]))
        if b.o[k] < mid[k] and b.c[k] < mid[k] and not (b.o[k - 1] < mid[k - 1] and b.c[k - 1] < mid[k - 1]):
            out.append(P.mkt(k, -1, b.h[k - 1], lo[k]))
    return out, None, None


def _rsi2(ctx, P, daily=False):
    b, out = P.b, []
    r = I.rsi(b.c, 2)
    a = P.atr
    upx = I.crossed_above(r, np.full(P.n, 90.0))
    dnx = I.crossed_below(r, np.full(P.n, 10.0))
    units = {1: 0, -1: 0}
    for k in range(3, P.n - 1):
        if upx[k]:
            units[-1] = 0
        if dnx[k]:
            units[1] = 0
        if not daily and not P.ok_time(k):
            continue
        if a[k] != a[k]:
            continue
        side = 1 if upx[k] else (-1 if dnx[k] else 0)
        if side == 0 or units[side] >= 3:
            continue
        units[side] += 1
        out.append(P.mkt(k, side, b.c[k] - side * 1.5 * a[k]))
    xf = {1: ctx.to_rows(b, dnx, bool), -1: ctx.to_rows(b, upx, bool)}
    return out, None, xf


@rule("book-sadowski-09", "IDX", {"15m": dict(_one_pos=False, _max_side_day=99)})
def sad09(ctx):
    return _rsi2(ctx, Pat(ctx, 15))


@rule("book-sadowski-09", "DLY", {"daily": dict(_one_pos=False, _max_side_day=99)})
def sad09_d(ctx):
    return _rsi2(ctx, Pat(ctx, 1, bars=_daily_bars(ctx)), daily=True)


def _trendlines(P):
    """Heitkoetter's intraday lines per session: (side, a1, a2, line, swing level beyond, start bar after a2)."""
    b = P.b
    lines = []
    for side in (1, -1):
        a1 = None
        for k in range(P.n):
            if k == 0 or b.ses[k] != b.ses[k - 1]:
                a1, a2 = k, None
                continue
            if side > 0 and b.l[k] < b.l[a1]:
                a1, a2 = k, None
                continue
            if side < 0 and b.h[k] > b.h[a1]:
                a1, a2 = k, None
                continue
            if a2 is None and k > a1 + 1:
                if side > 0 and b.l[k] <= b.l[k - 1] and b.l[k] > b.l[a1]:
                    a2 = k
                    ln = fit_line(a1, b.l[a1], a2, b.l[a2])
                    lvl = b.h[a1:a2 + 1].max()
                    lines.append((side, a1, a2, ln, lvl))
                if side < 0 and b.h[k] >= b.h[k - 1] and b.h[k] < b.h[a1]:
                    a2 = k
                    ln = fit_line(a1, b.h[a1], a2, b.h[a2])
                    lvl = b.l[a1:a2 + 1].min()
                    lines.append((side, a1, a2, ln, lvl))
    return lines


@rule("book-heitkoetter-01", "IDX", {"5m": dict(tf=5), "15m": dict(tf=15)})
def hk01(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    xL = np.zeros(P.n, bool)
    xS = np.zeros(P.n, bool)
    for side, a1, a2, ln, lvl in _trendlines(P):
        for k in range(a2 + 1, P.n - 1):
            if not P.same_ses(a2, k):
                break
            lv = line_at(ln, k)
            if (side > 0 and b.c[k] < lv) or (side < 0 and b.c[k] > lv):
                break
            if (side > 0 and b.c[k] > lvl) or (side < 0 and b.c[k] < lvl):
                if P.ok_time(k):
                    out.append(P.mkt(k, side, line_at(ln, k + 1) - side * 1e-9))
                    # the line exit: a later close through the projected line
                    for m in range(k + 1, P.n):
                        if not P.same_ses(k, m):
                            break
                        if (side > 0 and b.c[m] < line_at(ln, m)) or (side < 0 and b.c[m] > line_at(ln, m)):
                            (xL if side > 0 else xS)[m] = True
                            break
                break
    # the stop is the close-based line break (rule exit); no intrabar stop
    out = [t[:5] + (NAN,) + t[6:] for t in out]
    return out, None, {1: ctx.to_rows(b, xL, bool), -1: ctx.to_rows(b, xS, bool)}


@both("book-heitkoetter-04", mcx=("GOLD", "SILVER", "CRUDE"))
def hk04(ctx):
    P = Pat(ctx, 15)
    b, out = P.b, []
    hh = I.rolling_max(b.h, 20)
    ll = I.rolling_min(b.l, 20)
    for k in range(20, P.n - 1):
        if not P.ok_time(k, lo=9 * 60 + 15 if ctx.market == "IDX" else None):
            continue
        g = gid("t20")
        H, L = hh[k], ll[k]
        out.append(P.stop_order(k, 1, H, 1, H * (1 - 0.0027), H * (1 + 0.0041), tmax=P.after_fill(45), grp=g + "|L"))
        out.append(P.stop_order(k, -1, L, 1, L * (1 + 0.0027), L * (1 - 0.0041), tmax=P.after_fill(45), grp=g + "|S"))
    return out, None, None


@rule("book-heitkoetter-09", "IDX", {"5m": dict(tf=5)})
def hk09(ctx, tf):
    P = Pat(ctx, tf)
    b, out = P.b, []
    xL = np.zeros(P.n, bool)
    xS = np.zeros(P.n, bool)
    for side, a1, a2, ln, lvl in _trendlines(P):
        # trade WITH the line: a short at a touch of the down line (side -1 line), long at the up line
        for k in range(a2 + 1, P.n - 1):
            if not P.same_ses(a2, k):
                break
            a = P.atr[k]
            lv = line_at(ln, k)
            if a != a:
                continue
            if (side > 0 and b.c[k] < lv - 0.1 * a) or (side < 0 and b.c[k] > lv + 0.1 * a):
                break
            ext = b.h[a1:k + 1].max() if side > 0 else b.l[a1:k + 1].min()
            # channel line parallel through the extreme since anchor 1
            off = ext - line_at(ln, int(np.argmax(b.h[a1:k + 1]) + a1 if side > 0 else np.argmin(b.l[a1:k + 1]) + a1))
            chan = lv + off
            if abs(chan - lv) < 2 * a:
                continue
            touch = (b.l[k] <= lv and b.c[k] > lv) if side > 0 else (b.h[k] >= lv and b.c[k] < lv)
            if touch and P.ok_time(k):
                out.append(P.mkt(k, side, NAN, chan))
                for m in range(k + 1, P.n):
                    if not P.same_ses(k, m):
                        break
                    lm = line_at(ln, m)
                    if (side > 0 and b.c[m] < lm - 0.1 * a) or (side < 0 and b.c[m] > lm + 0.1 * a):
                        (xL if side > 0 else xS)[m] = True
                        break
                break
    return out, None, {1: ctx.to_rows(b, xL, bool), -1: ctx.to_rows(b, xS, bool)}


@both("book-douglas-04", mcx=("CRUDE", "GOLD"), variants={"30m": dict()})
def douglas04(ctx):
    P = Pat(ctx, 30)
    b, out = P.b, []
    dd = _daily_hl_by_session(ctx)
    keys = sorted(dd)
    dh = np.array([dd[i][1] for i in keys])
    dl = np.array([dd[i][2] for i in keys])
    # daily 3-bar fractal swings, confirmed one day after
    dsh = [(i, dh[i]) for i in range(1, len(keys) - 1) if dh[i] > dh[i - 1] and dh[i] > dh[i + 1]]
    dsl = [(i, dl[i]) for i in range(1, len(keys) - 1) if dl[i] < dl[i - 1] and dl[i] < dl[i + 1]]
    hs, ls = P.swing_lists(2)
    done = set()
    lo_t = 9 * 60 + 45 if ctx.market == "IDX" else None
    for k in range(2, P.n - 1):
        if not P.ok_time(k, lo=lo_t):
            continue
        s = int(b.ses[k])
        H = [v for i, v in dsh if i + 1 < s][-2:]
        L = [v for i, v in dsl if i + 1 < s][-2:]
        if len(H) < 2 or len(L) < 2:
            continue
        trend = 1 if (H[1] > H[0] and L[1] > L[0]) else (-1 if (H[1] < H[0] and L[1] < L[0]) else 0)
        if trend == 0 or (s, trend) in done:
            continue
        a = P.atr[k]
        if a != a:
            continue
        if trend > 0:
            sw = [x for x in ls if x[1] <= k and x[2] > L[1]]
            tg = [x for x in hs if x[1] <= k]
            if not sw or not tg:
                continue
            lv = sw[-1][2]
            if b.l[k] <= lv and b.c[k] > lv:
                stop = lv - 0.25 * a
                tgt = tg[-1][2]
                if tgt - b.c[k] >= 3 * (b.c[k] - stop):
                    out.append(P.mkt(k, 1, stop, tgt))
                    done.add((s, 1))
        else:
            sw = [x for x in hs if x[1] <= k and x[2] < H[1]]
            tg = [x for x in ls if x[1] <= k]
            if not sw or not tg:
                continue
            lv = sw[-1][2]
            if b.h[k] >= lv and b.c[k] < lv:
                stop = lv + 0.25 * a
                tgt = tg[-1][2]
                if b.c[k] - tgt >= 3 * (stop - b.c[k]):
                    out.append(P.mkt(k, -1, stop, tgt))
                    done.add((s, -1))
    return out, None, None


@rule("book-fmschmidt-01", "IDX", {"5m": dict()})
def schmidt01(ctx):
    P = Pat(ctx, 5)
    b, out = P.b, []
    c = b.c
    up = np.zeros(P.n, bool)
    dn = np.zeros(P.n, bool)
    up[2:] = (c[2:] > c[1:-1]) & (c[1:-1] < c[:-2])
    dn[2:] = (c[2:] < c[1:-1]) & (c[1:-1] > c[:-2])
    for k in range(2, P.n - 1):
        if not P.ok_time(k) or not P.same_ses(k - 2, k):
            continue
        if up[k]:
            out.append(P.mkt(k, 1, min(b.l[k - 1], b.l[k])))
        elif dn[k]:
            out.append(P.mkt(k, -1, max(b.h[k - 1], b.h[k])))
    return out, None, {1: ctx.to_rows(P.b, dn, bool), -1: ctx.to_rows(P.b, up, bool)}


@rule("book-fmchan-01", "IDXF", {"nostop": dict(stop=False), "stop1sd": dict(stop=True)})
def chan01(ctx, stop):
    P = Pat(ctx, 5)
    b, out = P.b, []
    m = I.sma(b.c, 20)
    sd = I.rolling_std(b.c, 20)
    inside = np.abs(b.c - m) <= sd
    xL = inside.copy()
    xS = inside.copy()
    if stop:
        xL |= b.c < m - 3 * sd                    # 1 SD beyond the -2 SD entry band, close-based
        xS |= b.c > m + 3 * sd
    for k in range(21, P.n - 1):
        if not P.ok_time(k, lo=9 * 60 + 30, hi=14 * 60 + 55) or sd[k] != sd[k]:
            continue
        if b.c[k] > m[k] + 2 * sd[k]:
            out.append(P.mkt(k, -1, NAN))
        elif b.c[k] < m[k] - 2 * sd[k]:
            out.append(P.mkt(k, 1, NAN))
    return out, None, {1: ctx.to_rows(b, xL, bool), -1: ctx.to_rows(b, xS, bool)}
