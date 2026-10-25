"""R104 families F1 (volume-confirmed breakouts) and F2 (Wyckoff tape / volume) entry rules (PREREG.md; mechanics from
research/books/TESTABLE_RULES.md). Committed before these families' first run.

Volume: MCX minis' own 1-minute volume (MCXI); the near index future's DAILY volume for the daily index rule (2012+).
Readings fixed here:
- "the slot's 20-day median" = the median volume of the same 5-minute slot (minute of day) over the previous 20
  sessions; a 3-bar test compares the 3 bars' total with the sum of their slots' medians.
- Wyckoff 04's "ATR(14, 5m) of the prior 50 bars" = the mean 5-minute true range of the 50 bars before the range.
- Wyckoff 07: ZigZag 1.5 x ATR(14, 5m) on the index; the rally is "confirmed" once price falls 0.5 ATR from its high.
- Woods 05: hold variants 1 / 3 / 5 sessions (chosen walk-forward).
- The price-only twin of Wyckoff 04 (same rule, no volume test) is reported beside it and is NOT a test (not in BH).
"""
from __future__ import annotations

import numpy as np

import r104ind as I
from f3_patterns import _daily_bars
from r104pat import NAN, Pat, gid
from r104run import rule


def slot_median(P, n=20):
    """Per bar: the median volume of the same slot (minute of day) over the previous n sessions."""
    b = P.b
    hist = {}
    out = np.full(P.n, np.nan)
    cur = None
    pend = []
    for k in range(P.n):
        if cur is None or b.ses[k] != cur:
            for t, v in pend:
                hist.setdefault(t, []).append(v)
            pend = []
            cur = b.ses[k]
        t = int(b.tod1[k])
        h = hist.get(t, [])
        if len(h) >= 5:
            out[k] = np.median(h[-n:])
        pend.append((t, b.v[k]))
    return out


@rule("book-wyckoff-02", "MCXI", {"5m": dict()}, insts=("CRUDE", "NATURALGAS", "GOLD", "SILVER"))
def wyckoff02(ctx):
    P = Pat(ctx, 5)
    b, out = P.b, []
    med = slot_median(P)
    dh, dl = P.day_hl_sofar()
    zmid = {}
    xL = np.zeros(P.n, bool)
    xS = np.zeros(P.n, bool)
    for k in range(15, P.n - 1):
        a = P.atr[k]
        if a != a or not P.same_ses(k - 12, k):
            continue
        z = slice(k - 2, k + 1)
        vol3, med3 = b.v[z].sum(), np.nansum(med[z])
        if not (med3 > 0 and vol3 >= 3 * med3):
            continue
        zh, zl = b.h[z].max(), b.l[z].min()
        if zh - zl > 0.5 * a:
            continue
        rose = b.c[k] - b.l[k - 12:k + 1].min() >= a and (b.h[k - 5:k + 1] >= dh[k - 5:k + 1] - 1e-9).any()
        fell = b.h[k - 12:k + 1].max() - b.c[k] >= a and (b.l[k - 5:k + 1] <= dl[k - 5:k + 1] + 1e-9).any()
        for side, ok in ((-1, rose), (1, fell)):
            if not ok:
                continue
            for j in range(k + 1, min(k + 7, P.n - 1)):
                if not P.same_ses(k, j):
                    break
                hit = b.c[j] < zl if side < 0 else b.c[j] > zh
                if hit and P.ok_time(j):
                    stop = zh + 0.1 * a if side < 0 else zl - 0.1 * a
                    out.append(P.mkt(j, side, stop, b.c[j] + side * 2 * a))
                    mid = (zh + zl) / 2
                    for m in range(j + 1, P.n):
                        if not P.same_ses(j, m):
                            break
                        if (side < 0 and b.c[m] > mid) or (side > 0 and b.c[m] < mid):
                            (xS if side < 0 else xL)[m] = True
                            break
                    break
    return out, None, {1: ctx.to_rows(b, xL, bool), -1: ctx.to_rows(b, xS, bool)}


def _wyckoff04(ctx, use_volume):
    P = Pat(ctx, 5)
    b, out = P.b, []
    med = slot_median(P)
    tr = I.true_range(b.h, b.l, b.c)
    xL = np.zeros(P.n, bool)
    xS = np.zeros(P.n, bool)
    done = set()
    for k in range(63, P.n - 1):
        if not P.same_ses(k - 12, k) or not P.ok_time(k, lo=9 * 60 + 15):
            continue
        rng = slice(k - 12, k)
        top, bot = b.h[rng].max(), b.l[rng].min()
        base = tr[k - 62:k - 12].mean()
        if top - bot > 1.0 * base:
            continue
        s = int(b.ses[k])
        for side in (1, -1):
            brk = b.c[k] > top if side > 0 else b.c[k] < bot
            if not brk or (s, side) in done:
                continue
            if use_volume:
                if not (b.v[k] >= 3 * b.v[rng].mean() and med[k] == med[k] and b.v[k] >= 1.5 * med[k]):
                    continue
            done.add((s, side))
            h = top - bot
            out.append(P.mkt(k, side, bot if side > 0 else top, b.c[k] + side * 2 * h))
            for m in range(k + 1, P.n):
                if not P.same_ses(k, m):
                    break
                if bot <= b.c[m] <= top:
                    (xL if side > 0 else xS)[m] = True
                    break
    return out, None, {1: ctx.to_rows(b, xL, bool), -1: ctx.to_rows(b, xS, bool)}


@rule("book-wyckoff-04", "MCXI", {"5m": dict()}, insts=("CRUDE", "NATURALGAS"))
def wyckoff04(ctx):
    return _wyckoff04(ctx, True)


@rule("book-wyckoff-04~priceonly", "MCXI", {"5m": dict()}, insts=("CRUDE", "NATURALGAS"))
def wyckoff04_twin(ctx):
    return _wyckoff04(ctx, False)


@rule("book-wyckoff-07", "IDX", {"5m": dict()})
def wyckoff07(ctx):
    P = Pat(ctx, 5)
    b, out = P.b, []
    pb, cb, pv, pt = I.zigzag(b.h, b.l, b.c, 1.5 * np.nan_to_num(P.atr, nan=np.inf))
    # down-legs: a pivot high followed by a pivot low (confirmed); mirror for up-legs
    for i in range(1, len(pb)):
        if pt[i - 1] == 1 and pt[i] == -1:
            side = -1
        elif pt[i - 1] == -1 and pt[i] == 1:
            side = 1
        else:
            continue
        D = abs(pv[i - 1] - pv[i])
        a = P.atr[pb[i]]
        if a != a or D < 1.5 * a:
            continue
        leg_end, start_bar = pv[i], pb[i]
        ext = leg_end
        armed = False
        for k in range(cb[i], min(cb[i] + 60, P.n - 1)):
            if side < 0:
                ext = max(ext, b.h[k])
                if ext - leg_end >= 0.5 * D:
                    break                                # the rally recovered half: no setup
                if ext - b.l[k] >= 0.5 * a:
                    armed = True
                if armed and b.c[k] < b.l[start_bar]:
                    if P.ok_time(k):
                        out.append(P.mkt(k, -1, ext + 0.1 * a, leg_end - 0.5 * D))
                    break
            else:
                ext = min(ext, b.l[k])
                if leg_end - ext >= 0.5 * D:
                    break
                if b.h[k] - ext >= 0.5 * a:
                    armed = True
                if armed and b.c[k] > b.h[start_bar]:
                    if P.ok_time(k):
                        out.append(P.mkt(k, 1, ext - 0.1 * a, leg_end + 0.5 * D))
                    break
    return out, None, None


@rule("book-porwal-05", "MCXI", {"N20": dict(N=20, barstop=False), "N40": dict(N=40, barstop=False),
                                  "N20_bar": dict(N=20, barstop=True)}, insts=("GOLD", "SILVER", "CRUDE", "NATURALGAS"))
def porwal05(ctx, N, barstop):
    P = Pat(ctx, 5)
    b, out = P.b, []
    v20 = I.sma(b.v, 20)
    lo10 = I.rolling_min(b.l, 10)
    hi10 = I.rolling_max(b.h, 10)
    for k in range(N + 1, P.n - 1):
        if not P.ok_time(k):
            continue
        a = P.atr[k - 1]
        w = slice(k - N, k)
        top, bot = b.h[w].max(), b.l[w].min()
        if a != a or top - bot > 1.5 * a * np.sqrt(N / 20.0) or P.rng[k] <= 0:
            continue
        strong = P.body[k] >= 0.6 * P.rng[k] and b.v[k] >= 1.5 * v20[k - 1]
        if not strong:
            continue
        if b.c[k] > top and b.c[k] - top <= 2 * a and P.green[k]:
            stop = b.l[k] if barstop else bot
            out.append(P.mkt(k, 1, stop, P.r_target(1, b.c[k], stop, 2)))
        if b.c[k] < bot and bot - b.c[k] <= 2 * a and P.red[k]:
            stop = b.h[k] if barstop else top
            out.append(P.mkt(k, -1, stop, P.r_target(-1, b.c[k], stop, 2)))
    trail = {1: ctx.to_rows(b, lo10), -1: ctx.to_rows(b, hi10)}
    return out, trail, None


def _woods05(ctx, hold):
    P = Pat(ctx, 1, bars=_daily_bars(ctx))
    b, out = P.b, []
    v = ctx.s.v
    v20 = I.sma(np.nan_to_num(v), 20)
    for k in range(21, P.n - 1):
        if not (np.isfinite(v[k]) and v[k] > v20[k - 1] > 0) or P.rng[k] <= 0:
            continue
        pos = (b.c[k] - b.l[k]) / P.rng[k]
        if b.o[k] < b.l[k - 1] and pos >= 0.75 and b.h[k] < b.c[k - 1]:
            out.append(P.stop_order(k, 1, b.h[k] + P.tick, 1, b.l[k], tmax=P.after_fill(hold)))
        if b.o[k] > b.h[k - 1] and pos <= 0.25 and b.l[k] > b.c[k - 1]:
            out.append(P.stop_order(k, -1, b.l[k] - P.tick, 1, b.h[k], tmax=P.after_fill(hold)))
    return out, None, None


HOLDS = {"h1": dict(hold=1), "h3": dict(hold=3), "h5": dict(hold=5)}


@rule("book-woods-05", "DLY", HOLDS)
def woods05(ctx, hold):
    return _woods05(ctx, hold)


@rule("book-woods-05", "MCXD", HOLDS, insts=("GOLD", "CRUDE"))
def woods05_m(ctx, hold):
    return _woods05(ctx, hold)
