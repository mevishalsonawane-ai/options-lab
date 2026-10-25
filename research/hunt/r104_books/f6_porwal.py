"""R104 family F6: Porwal's Indian setups (PREREG.md; mechanics from research/books/TESTABLE_RULES.md).
Committed before this family's first run.

Readings fixed here: thirds are three sub-lots (porwal-01: targets 1.0x / 1.5x / 2.0x the mother range, lots 2 and 3
move their stop to the entry once the price has gone 1.0x the range); porwal-04 targets are the older daily swing highs
above the entry (a third each; the last third trails under the latest swing low); daily holds are capped at 10 sessions
where the book says so. Swings: 3-bar fractals (1 bar each side) where the book says 3-bar fractal.
"""
from __future__ import annotations

import numpy as np

import r104ind as I
from f3_patterns import _daily_bars, _daily_hl_by_session, both
from r104pat import NAN, Pat, fit_line, gid, line_at
from r104run import rule


def _htf_ema_trend(ctx, tf_hi=60):
    Ph = Pat(ctx, tf_hi)
    tr = Ph.trend.astype(float)
    rows = ctx.to_rows(Ph.b, tr)
    out = np.zeros(ctx.s.n)
    cur = 0.0
    for i in range(ctx.s.n):
        if rows[i] == rows[i]:
            cur = rows[i]
        out[i] = cur
    return out


@both("book-porwal-01", mcx=("GOLD", "SILVER", "CRUDE"),
      variants={"15m": dict(tf=15, trend=False), "60m": dict(tf=60, trend=False), "15m_trend": dict(tf=15, trend=True)})
def porwal01(ctx, tf, trend):
    P = Pat(ctx, tf)
    b, out = P.b, []
    ht = _htf_ema_trend(ctx) if trend else None
    for ib in range(1, P.n - 1):
        M = ib - 1
        if not P.same_ses(M, ib):
            continue
        if not (b.h[ib] <= b.h[M] and b.l[ib] >= b.l[M]):
            continue
        mr = b.h[M] - b.l[M]
        if mr <= 0:
            continue
        for k in range(ib + 1, min(ib + 5, P.n - 1)):
            if not P.same_ses(ib, k):
                break
            side = 1 if b.c[k] > b.h[M] else (-1 if b.c[k] < b.l[M] else 0)
            if side == 0:
                continue
            if P.ok_time(k) and (not trend or ht[b.i1[k]] == side):
                stop = b.l[M] if side > 0 else b.h[M]
                e = b.c[k]
                risk = abs(e - stop)
                be = mr / risk if risk > 0 else NAN
                g = gid("p1")
                for j, m in enumerate((1.0, 1.5, 2.0)):
                    out.append(P.mkt(k, side, stop, e + side * m * mr, be=NAN if j == 0 else be, grp=g, w=1 / 3))
            break
    return out, None, None


def _daily_P(ctx):
    return Pat(ctx, 1, bars=_daily_bars(ctx))


@rule("book-porwal-02", "DLY", {"base": dict(confirm=False), "confirm": dict(confirm=True)})
def porwal02(ctx, confirm):
    P = _daily_P(ctx)
    b, out = P.b, []
    r = I.rsi(b.c, 14)
    kk, _ = I.stoch(b.h, b.l, b.c, 14, 3, 3)
    e20 = I.ema(b.c, 20)
    for k in range(20, P.n - 1):
        if P.rng[k] <= 0 or P.body[k] > 0.1 * P.rng[k]:
            continue
        lo10 = b.l[k] == b.l[k - 9:k + 1].min()
        hi10 = b.h[k] == b.h[k - 9:k + 1].max()
        os_ = (r[k] < 30) or (kk[k] < 20)
        ob_ = (r[k] > 70) or (kk[k] > 80)
        if lo10 and os_:
            stop = b.l[k] - P.tick
            lvl = b.h[k] + P.tick
            if not confirm:
                out.append(P.stop_order(k, 1, lvl, 3, stop, lvl + 2 * (lvl - stop), tmax=P.after_fill(10)))
            else:
                # confirmation = a close above the doji's high within 3 sessions, entered at the next open
                for j in range(k + 1, min(k + 4, P.n - 1)):
                    if b.c[j] > b.h[k]:
                        out.append(P.mkt(j, 1, stop, b.c[j] + 2 * (b.c[j] - stop), tmax=P.after_fill(10)))
                        break
        if hi10 and ob_:
            stop = b.h[k] + P.tick
            lvl = b.l[k] - P.tick
            if not confirm:
                out.append(P.stop_order(k, -1, lvl, 3, stop, lvl - 2 * (stop - lvl), tmax=P.after_fill(10)))
            else:
                for j in range(k + 1, min(k + 4, P.n - 1)):
                    if b.c[j] < b.l[k]:
                        out.append(P.mkt(j, -1, stop, b.c[j] - 2 * (stop - b.c[j]), tmax=P.after_fill(10)))
                        break
    xf = {1: ctx.to_rows(b, b.c < e20, bool), -1: ctx.to_rows(b, b.c > e20, bool)}
    return out, None, xf


def _porwal03(ctx):
    P = _daily_P(ctx)
    b, out = P.b, []
    sm = I.sma(b.c, 20)
    e20 = I.ema(b.c, 20)
    v = ctx.s.v
    for k in range(25, P.n - 1):
        d1, d2 = k - 1, k
        down = b.c[d1] < sm[d1] and sm[d1] < sm[d1 - 5]
        up = b.c[d1] > sm[d1] and sm[d1] > sm[d1 - 5]
        if down and P.red[d1] and P.green[d2] and b.o[d2] <= b.c[d1] and b.c[d2] >= b.o[d1]:
            stop = b.l[d2] - P.tick
            lvl = b.h[d2] + P.tick
            out.append(P.stop_order(k, 1, lvl, 3, stop, lvl + 2 * (lvl - stop), tmax=P.after_fill(10)))
        if up and P.green[d1] and P.red[d2] and b.o[d2] > b.c[d1] and b.c[d2] < b.o[d1] and v[d2] > v[d1]:
            stop = b.h[d2] + P.tick
            lvl = b.l[d2] - P.tick
            out.append(P.stop_order(k, -1, lvl, 3, stop, lvl - 2 * (stop - lvl), tmax=P.after_fill(10)))
    xf = {1: ctx.to_rows(b, b.c < e20, bool), -1: ctx.to_rows(b, b.c > e20, bool)}
    return out, None, xf


@rule("book-porwal-03", "DLY")
def porwal03(ctx):
    return _porwal03(ctx)


@rule("book-porwal-03", "MCXD", insts=("GOLD", "SILVER", "CRUDE"))
def porwal03_m(ctx):
    return _porwal03(ctx)


@rule("book-porwal-04", "DLY")
def porwal04(ctx):
    P = Pat(ctx, 1, swing_n=1, bars=_daily_bars(ctx))
    b, out = P.b, []
    hs, ls = P.swing_lists(1)
    v = ctx.s.v
    vavg = I.sma(np.nan_to_num(v), 20)
    sh, shi, sl, sli = P.swings(1)
    hi_i = lo_i = 0
    used = set()
    for k in range(20, P.n - 1):
        while hi_i < len(hs) and hs[hi_i][1] <= k:
            hi_i += 1
        while lo_i < len(ls) and ls[lo_i][1] <= k:
            lo_i += 1
        a = P.atr[k]
        if a != a:
            continue
        for side in (1, -1):
            src, idx = (hs, hi_i) if side > 0 else (ls, lo_i)
            if idx < 2:
                continue
            X1, X2 = src[idx - 2], src[idx - 1]
            if (X1[0], side) in used or X2[0] - X1[0] < 10:
                continue
            falling = X1[2] > X2[2] if side > 0 else X1[2] < X2[2]
            if not falling:
                continue
            ln = fit_line(X1[0], X1[2], X2[0], X2[2])
            slope_atr = abs(ln[0]) / a
            if not (0.5 <= slope_atr <= 1.5):
                continue
            crossed = any((b.c[j] > line_at(ln, j)) if side > 0 else (b.c[j] < line_at(ln, j)) for j in range(X2[0] + 1, k + 1))
            beyond = b.c[k] > X2[2] if side > 0 else b.c[k] < X2[2]
            first = not ((b.c[k - 1] > X2[2]) if side > 0 else (b.c[k - 1] < X2[2]))
            if not (crossed and beyond and first):
                continue
            if not (P.rng[k] > P.rng[k - 3:k].max()):
                continue
            if not (np.isfinite(v[k]) and v[k] > vavg[k] > 0):
                continue
            used.add((X1[0], side))
            stop = X2[2] - side * 0.25 * a
            e = b.c[k]
            risk = abs(e - stop)
            older = [x[2] for x in reversed(src[:idx - 1]) if (x[2] > e if side > 0 else x[2] < e)]
            older = sorted(older)[:3] if side > 0 else sorted(older, reverse=True)[:3]
            if not older or abs(older[0] - e) < 2 * risk:
                continue
            g = gid("p4")
            tg = older + [NAN] * (3 - len(older))
            for j in range(3):
                out.append(P.mkt(k, side, stop, tg[j], tmax=P.after_fill(60), grp=g, w=1 / 3))   # 60-session backstop
    trail = {1: ctx.to_rows(b, sl), -1: ctx.to_rows(b, sh)}
    return out, trail, None


@rule("book-porwal-08", "IDX", {"adx20": dict(adx_lo=20, tgt=False), "adx30": dict(adx_lo=30, tgt=False),
                                 "adx20_2R": dict(adx_lo=20, tgt=True)})
def porwal08(ctx, adx_lo, tgt):
    P = Pat(ctx, 5)
    b, out = P.b, []
    e14 = I.ema(b.c, 14)
    pdi, mdi, adx = I.dmi(b.h, b.l, b.c, 14)
    sw = I.rolling_min(b.l, 5)
    swh = I.rolling_max(b.h, 5)
    dd = ctx.daily
    prev_up = {}
    if dd is not None and len(dd) > 2:
        days = list(dd.index)
        o, c = dd.open.values, dd.close.values
        for i in range(2, len(days)):
            prev_up[days[i]] = (1 if (c[i - 1] > o[i - 1] and c[i - 1] > c[i - 2]) else
                                -1 if (c[i - 1] < o[i - 1] and c[i - 1] < c[i - 2]) else 0)
    for k in range(20, P.n - 1):
        if not P.ok_time(k) or adx[k] != adx[k]:
            continue
        if not (adx_lo < adx[k] < 50):
            continue
        pdt = prev_up.get(ctx.day(int(b.i1[k])), 0)
        a = P.atr[k]
        if pdi[k] > mdi[k] and b.c[k] > e14[k] and pdt > 0 and b.l[k] <= e14[k] * 1.001:
            stop = min(sw[k - 1], e14[k] - 0.25 * a)
            out.append(P.mkt(k, 1, stop, P.r_target(1, b.c[k], stop, 2) if tgt else NAN))
        if mdi[k] > pdi[k] and b.c[k] < e14[k] and pdt < 0 and b.h[k] >= e14[k] * 0.999:
            stop = max(swh[k - 1], e14[k] + 0.25 * a)
            out.append(P.mkt(k, -1, stop, P.r_target(-1, b.c[k], stop, 2) if tgt else NAN))
    xf = {1: ctx.to_rows(b, b.c < e14, bool), -1: ctx.to_rows(b, b.c > e14, bool)}
    return out, None, xf


@both("book-porwal-09", mcx=("GOLD", "SILVER", "CRUDE"),
      variants={"15m_HL": dict(tf=15, mode="hl"), "15m_HH": dict(tf=15, mode="hh"), "30m_HL": dict(tf=30, mode="hl")})
def porwal09(ctx, tf, mode):
    P = Pat(ctx, tf, swing_n=1)
    b, out = P.b, []
    hs, ls = P.swing_lists(1)
    ev = sorted([(c, "H", v, bar) for bar, c, v in hs] + [(c, "L", v, bar) for bar, c, v in ls])
    H, L = [], []
    armed = []          # (side, H1 target, L2 stop level, HH level, bar armed)
    ptr = 0
    for k in range(2, P.n - 1):
        while ptr < len(ev) and ev[ptr][0] <= k:
            conf, kind, v, bar = ev[ptr]
            (H if kind == "H" else L).append((bar, v))
            ptr += 1
            if kind == "L" and mode == "hl":
                for a_ in list(armed):
                    if a_[0] > 0 and v > a_[2] and bar > a_[4]:
                        a_[5] = (bar, v)
            if kind == "H" and mode == "hl":
                for a_ in list(armed):
                    if a_[0] < 0 and v < a_[2] and bar > a_[4]:
                        a_[5] = (bar, v)
        if len(H) >= 2 and len(L) >= 2:
            (h1b, h1), (h2b, h2) = H[-2], H[-1]
            (l1b, l1), (l2b, l2) = L[-2], L[-1]
            if h1 > h2 and l1 > l2 and b.c[k] > h2 and b.c[k - 1] <= h2:
                if mode == "hh":
                    if P.ok_time(k):
                        stop = l2 - P.tick
                        t = min(h1, P.r_target(1, b.c[k], stop, 2)) if h1 > b.c[k] else P.r_target(1, b.c[k], stop, 2)
                        out.append(P.mkt(k, 1, stop, t))
                else:
                    armed.append([1, h1, l2, h2, k, None])
            if h1 < h2 and l1 < l2 and b.c[k] < l2 and b.c[k - 1] >= l2:
                if mode == "hh":
                    if P.ok_time(k):
                        stop = h2 + P.tick
                        t = max(l1, P.r_target(-1, b.c[k], stop, 2)) if l1 < b.c[k] else P.r_target(-1, b.c[k], stop, 2)
                        out.append(P.mkt(k, -1, stop, t))
                else:
                    armed.append([-1, l1, h2, l2, k, None])
        for a_ in list(armed):
            if k - a_[4] > 40:
                armed.remove(a_)
                continue
            if a_[5] is None:
                continue
            side, t1, stl, _hh, _k0, (sb, sv) = a_
            if k <= sb + 1:
                continue
            trig = b.c[k] > b.h[sb] if side > 0 else b.c[k] < b.l[sb]
            if trig:
                armed.remove(a_)
                if P.ok_time(k):
                    stop = stl - side * P.tick
                    r2 = P.r_target(side, b.c[k], stop, 2)
                    t = (min(t1, r2) if side > 0 else max(t1, r2)) if (t1 - b.c[k]) * side > 0 else r2
                    out.append(P.mkt(k, side, stop, t))
    return out, None, None
