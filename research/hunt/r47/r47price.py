"""R47 pricing: one fixed contract per leg from entry to exit, R17 'liq' fills, gap fills, overnight holds, debit
spreads and straddles, then budget sizing and the dated charges. Pure functions on a Store's arrays (no I/O).

Single legs are checked on every 1-minute row of the contract: a row that OPENS beyond the stop (or lock) fills at the
open (gaps, including the next session's open), else at the level; stop before target. Two-leg positions (debit
spreads, straddles) are checked on minute closes of their net value and exit at the next minute's opens.
Time exits fill at the open of the first row at/after the exit minute (within 5 minutes, same session). A contract
that reaches ATM+-EDGE is sold at the next row. A leg with no row where it must be priced (out of the lake's ATM+-10)
is valued conservatively: a bought leg at intrinsic value, a sold leg at a covered strike's price plus the strike
difference (an upper bound), both with the thin-market fill.
"""
from __future__ import annotations

from datetime import date

import numpy as np

import r47cfg as K
from obuy.costs import Costs

try:  # the pure-Python path gives the same numbers (tests run it on small inputs)
    from numba import njit
except Exception:  # pragma: no cover
    def njit(*a, **k):
        def deco(f):
            return f
        return deco if not (a and callable(a[0])) else a[0]

N = K.N
EPS = 1e-9
BIGG = np.int64(2 ** 40)
COSTS = Costs("dated")
# status codes
OK, NO_FILL, EXP_DAY, NO_TIME, PAST_END, BAD_VALUE = 0, 1, 2, 3, 4, 5
# exit reasons
R_STOP, R_TGT, R_LOCK, R_TIME, R_EXPIRY, R_EDGE, R_GAPOUT, R_NOROW = 1, 2, 3, 4, 5, 6, 7, 8
REASONS = {1: "stop", 2: "target", 3: "lock", 4: "time", 5: "expiry_sq", 6: "edge", 7: "gap_out", 8: "no_row"}


# ------------------------------------------------------------------------------------------------ fills (R17 'liq')
@njit(cache=True)
def _round2(x):
    return np.floor(x * 100.0 + 0.5) / 100.0


@njit(cache=True)
def _ticks(lots5):
    if lots5 >= 20.0:
        return 1.0
    if lots5 > 0.0:
        return 2.0
    return 4.0


@njit(cache=True)
def buy_fill(p, lots5):
    """Fills('liq').buy: +5 bps, rounded to the paisa, plus 1/2/4 ticks by the 5-minute traded lots."""
    return _round2(_round2(p * 1.0005) + _ticks(lots5) * K.TICK)


@njit(cache=True)
def sell_fill(p, lots5, stop):
    """Fills('liq').sell: -5 bps (stops -10 bps), minus 1/2/4 ticks (stops twice), never below 0."""
    x = _round2(p * (0.999 if stop else 0.9995)) - _ticks(lots5) * (2.0 if stop else 1.0) * K.TICK
    return max(_round2(x), 0.0)


# ------------------------------------------------------------------------------------------------ row helpers
@njit(cache=True)
def first_row(g, s, e, gq):
    """First row index in [s, e) with g >= gq (e if none)."""
    lo, hi = s, e
    while lo < hi:
        mid = (lo + hi) // 2
        if g[mid] < gq:
            lo = mid + 1
        else:
            hi = mid
    return lo


@njit(cache=True)
def lots5_before(g, v, s, e, r, lot):
    """Lots traded by the contract in the 5 minutes before row r's minute."""
    g0 = g[r] - 5
    tot = 0.0
    q = r - 1
    while q >= s and g[q] >= g0:
        tot += v[q]
        q -= 1
    return tot / lot


@njit(cache=True)
def _spot_at(spot, G):
    """Index price at minute G (its open; else the last close before it in the same session)."""
    x = spot[G]
    if x == x:
        return x
    d0 = (G // N) * N
    q = G - 1
    while q >= d0:
        if spot[q] == spot[q]:
            return spot[q]
        q -= 1
    return np.nan


@njit(cache=True)
def fallback_value(cid, sign, G, g, c, cstart, cend, cstrike, cside, offcid, spot, spotc):
    """Conservative value of a leg with no row at minute G. Bought leg: intrinsic (a lower bound). Sold leg: the price
    of a covered strike K' on the same side at/before G that session, plus max(0, side*(K'-K)) (an upper bound)."""
    S = _spot_at(spot, G)
    if not (S == S):
        S = _spot_at(spotc, G)
    K_ = cstrike[cid]
    sd = cside[cid]
    intr = max(0.0, sd * (S - K_)) if S == S else 0.0
    if sign > 0:
        return intr
    di = G // N
    m = G - di * N
    si = 0 if sd > 0 else 1
    best = np.inf
    mm = m
    while mm >= 0 and best == np.inf:
        for k in range(offcid.shape[3]):
            cc = offcid[di, mm, si, k]
            if cc < 0 or cc == cid:
                continue
            r = first_row(g, cstart[cc], cend[cc], di * N + mm)
            if r < cend[cc] and g[r] == di * N + mm:
                ub = c[r] + max(0.0, sd * (cstrike[cc] - K_))
                if ub < best:
                    best = ub
        mm -= 1
    if best == np.inf:
        best = intr + (0.02 * S if S == S else 0.0)
    return best


# ------------------------------------------------------------------------------------------------ bad prints
# Measurement amendment 1 (PREREG): thin contracts (FINNIFTY 2021-22 above all) carry stray prints, e.g. a 09:15 open
# of 500 for a contract trading near 60. A fill is never allowed to profit from such a print: when a row's open is more
# than BAD_PRINT away from the median of the contract's closes on the rows before, at and after it (and more than 2
# ticks), the fill takes the WORSE of the open and that median. A high more than BAD_PRINT above that median cannot hit
# a target or arm a lock above it. Stops still trigger on any low (a stray low can only hurt).
@njit(cache=True)
def ref_close(c, s, e, r):
    """Median of the closes of rows r-1, r, r+1 of the contract (those that exist)."""
    a = c[r - 1] if r - 1 >= s else c[r]
    b = c[r]
    d = c[r + 1] if r + 1 < e else c[r]
    return max(min(a, b), min(max(a, b), d))


@njit(cache=True)
def _stray(p, ref):
    return abs(p - ref) > 2 * K.TICK + 1e-9 and (ref <= 0 or abs(p / ref - 1.0) > K.BAD_PRINT)


@njit(cache=True)
def open_for_sell(o, c, s, e, r):
    ref = ref_close(c, s, e, r)
    return min(o[r], ref) if _stray(o[r], ref) else o[r]


@njit(cache=True)
def open_for_buy(o, c, s, e, r):
    ref = ref_close(c, s, e, r)
    return max(o[r], ref) if _stray(o[r], ref) else o[r]


@njit(cache=True)
def confirmed_high(h, c, s, e, r):
    return min(h[r], ref_close(c, s, e, r) * (1.0 + K.BAD_PRINT))


# ------------------------------------------------------------------------------------------------ one position
@njit(cache=True)
def _exit_leg_at(cid, sign, G, lot, reason_stop, g, o, v, c, cstart, cend, cstrike, cside, offcid, spot, spotc, wait):
    """Fill of closing one leg at the open of its first row at/after minute G (within `wait` minutes, same session),
    else at the fallback value. Returns (price, used_fallback)."""
    s, e = cstart[cid], cend[cid]
    r = first_row(g, s, e, G)
    if r < e and g[r] <= G + wait and g[r] // N == G // N:
        l5 = lots5_before(g, v, s, e, r, lot)
        if sign > 0:
            return sell_fill(open_for_sell(o, c, s, e, r), l5, reason_stop), False
        return buy_fill(open_for_buy(o, c, s, e, r), l5), False
    val = fallback_value(cid, sign, G, g, c, cstart, cend, cstrike, cside, offcid, spot, spotc)
    if sign > 0:
        return sell_fill(val, 0.0, True), True
    return buy_fill(val, 0.0), True


@njit(cache=True)
def sim_position(g_dec, cid0, cid1, sg0, sg1, exit_g, stop, tgt, arm, keep, lot, pos, last_g,
                 g, o, h, l, c, v, off, cstart, cend, cstrike, cside, cexp, offcid, spot, spotc, edge, fill_wait,
                 exp_sq):
    """Returns (status, g_in, g_out, ent0, ent1, ex0, ex1, l5_0, l5_1, reason, fallback)."""
    nleg = 1 if cid1 < 0 else 2
    cids = (cid0, cid1)
    sgs = (sg0, sg1)
    ent = np.zeros(2)
    l5 = np.zeros(2)
    rin = np.zeros(2, dtype=np.int64)
    g_in = np.int64(0)
    dday = g_dec // N
    for k in range(nleg):
        cc = cids[k]
        s, e = cstart[cc], cend[cc]
        r = first_row(g, s, e, g_dec + 1)
        if r >= e or g[r] > g_dec + fill_wait or g[r] // N != dday:
            return NO_FILL, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, False
        rin[k] = r
        l5[k] = lots5_before(g, v, s, e, r, lot)
        ent[k] = buy_fill(open_for_buy(o, c, s, e, r), l5[k]) if sgs[k] > 0 else sell_fill(open_for_sell(o, c, s, e, r), l5[k], False)
        if g[r] > g_in:
            g_in = g[r]
    hard = exit_g
    hard_reason = R_TIME
    for k in range(nleg):
        ex = cexp[cids[k]]
        if ex < BIGG:
            if pos and ex == dday:
                return EXP_DAY, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, False
            eg = ex * N + exp_sq
            if eg < hard:
                hard = eg
                hard_reason = R_EXPIRY
    if hard <= g_in:
        return NO_TIME, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, False
    if hard > last_g:
        return PAST_END, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, False
    V0 = ent[0] * sgs[0] + (ent[1] * sgs[1] if nleg == 2 else 0.0)
    if V0 <= EPS:
        return BAD_VALUE, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, False
    stop_l = V0 * (1.0 - stop) if stop > 0 else -1.0
    tgt_l = V0 * (1.0 + tgt) if tgt > 0 else np.inf
    arm_l = V0 * (1.0 + arm) if arm > 0 else np.inf
    keep_l = V0 * (1.0 + keep)
    ex = np.zeros(2)
    fb = False
    if nleg == 1:
        s, e = cstart[cid0], cend[cid0]
        r = rin[0]
        armed = False
        while r < e and g[r] < hard:
            if r > rin[0]:
                dprev = g[r - 1] // N
                dnow = g[r] // N
                if dnow > dprev:                        # a new session: is the contract there at its open?
                    G = (dprev + 1) * N
                    if G >= hard:
                        break
                    if dnow > dprev + 1 or g[r] - dnow * N > 2:
                        val = fallback_value(cid0, 1, G, g, c, cstart, cend, cstrike, cside, offcid, spot, spotc)
                        return OK, g_in, G, ent[0], 0.0, sell_fill(val, 0.0, True), 0.0, l5[0], 0.0, R_GAPOUT, True
                ob = open_for_sell(o, c, s, e, r)
                if ob <= stop_l:
                    return OK, g_in, g[r], ent[0], 0.0, sell_fill(ob, lots5_before(g, v, s, e, r, lot), True), 0.0, l5[0], 0.0, R_STOP, False
                if armed and ob <= keep_l:
                    return OK, g_in, g[r], ent[0], 0.0, sell_fill(ob, lots5_before(g, v, s, e, r, lot), True), 0.0, l5[0], 0.0, R_LOCK, False
                if ob >= tgt_l:
                    return OK, g_in, g[r], ent[0], 0.0, sell_fill(ob, lots5_before(g, v, s, e, r, lot), False), 0.0, l5[0], 0.0, R_TGT, False
            if l[r] <= stop_l:
                return OK, g_in, g[r], ent[0], 0.0, sell_fill(stop_l, lots5_before(g, v, s, e, r, lot), True), 0.0, l5[0], 0.0, R_STOP, False
            if armed and l[r] <= keep_l:
                return OK, g_in, g[r], ent[0], 0.0, sell_fill(keep_l, lots5_before(g, v, s, e, r, lot), True), 0.0, l5[0], 0.0, R_LOCK, False
            hc = confirmed_high(h, c, s, e, r)
            if hc >= tgt_l:
                return OK, g_in, g[r], ent[0], 0.0, sell_fill(tgt_l, lots5_before(g, v, s, e, r, lot), False), 0.0, l5[0], 0.0, R_TGT, False
            if hc >= arm_l:
                armed = True
            if abs(off[r]) >= edge:
                px, f = _exit_leg_at(cid0, 1, g[r] + 1, lot, False, g, o, v, c, cstart, cend, cstrike, cside, offcid, spot, spotc, fill_wait)
                return OK, g_in, g[r] + 1, ent[0], 0.0, px, 0.0, l5[0], 0.0, R_EDGE, f
            r += 1
        px, f = _exit_leg_at(cid0, 1, hard, lot, False, g, o, v, c, cstart, cend, cstrike, cside, offcid, spot, spotc, 5)
        return OK, g_in, hard, ent[0], 0.0, px, 0.0, l5[0], 0.0, (R_NOROW if f else hard_reason), f
    # ---- two legs: net value on minute closes
    p = np.zeros(2, dtype=np.int64)
    lastc = np.full(2, np.nan)
    lastd = np.full(2, -1, dtype=np.int64)
    lastoff = np.zeros(2, dtype=np.int64)
    for k in range(2):
        p[k] = rin[k]
    curd = dday
    armed = False
    trig = 0
    Gt = np.int64(0)
    while True:
        Gn = BIGG
        for k in range(2):
            cc = cids[k]
            if p[k] < cend[cc] and g[p[k]] < Gn:
                Gn = g[p[k]]
        if Gn >= hard:
            break
        dn = Gn // N
        if dn > curd:                                    # new session: both legs must be there at its open
            G = (curd + 1) * N
            if G >= hard:
                break
            miss = False
            for k in range(2):
                cc = cids[k]
                if p[k] >= cend[cc] or g[p[k]] // N != curd + 1 or g[p[k]] - (curd + 1) * N > 2:
                    miss = True
            if miss:
                trig = R_GAPOUT
                Gt = G
                break
            curd = dn
        for k in range(2):
            cc = cids[k]
            if p[k] < cend[cc] and g[p[k]] == Gn:
                lastc[k] = c[p[k]]
                lastd[k] = dn
                lastoff[k] = off[p[k]]
                p[k] += 1
        if lastd[0] == dn and lastd[1] == dn:
            V = sgs[0] * lastc[0] + sgs[1] * lastc[1]
            if V <= stop_l:
                trig = R_STOP
            elif armed and V <= keep_l:
                trig = R_LOCK
            elif V >= tgt_l:
                trig = R_TGT
            elif abs(lastoff[0]) >= edge or abs(lastoff[1]) >= edge:
                trig = R_EDGE
            if V >= arm_l:
                armed = True
            if trig:
                Gt = Gn + 1
                break
    if trig == 0:
        Gt = hard
        reason = hard_reason
        wait = 5
    else:
        reason = trig
        wait = fill_wait
    for k in range(2):
        px, f = _exit_leg_at(cids[k], sgs[k], Gt, lot, trig == R_STOP or trig == R_LOCK, g, o, v, c, cstart, cend,
                             cstrike, cside, offcid, spot, spotc, wait)
        ex[k] = px
        if f:
            fb = True
    if fb and reason != R_GAPOUT:
        reason = R_NOROW
    return OK, g_in, Gt, ent[0], ent[1], ex[0], ex[1], l5[0], l5[1], reason, fb


@njit(cache=True)
def sim_batch(g_dec, cid0, cid1, sg0, sg1, exit_g, stop, tgt, arm, keep, lot, pos, last_g,
              g, o, h, l, c, v, off, cstart, cend, cstrike, cside, cexp, offcid, spot, spotc, edge, fill_wait, exp_sq,
              sequential):
    """Every event of one rule. Events must be sorted by g_dec. sequential: an event decided before the previous
    taken position's exit minute is skipped (one position at a time)."""
    n = len(g_dec)
    st = np.full(n, -1, dtype=np.int64)
    gi = np.zeros(n, dtype=np.int64)
    go = np.zeros(n, dtype=np.int64)
    e0 = np.zeros(n)
    e1 = np.zeros(n)
    x0 = np.zeros(n)
    x1 = np.zeros(n)
    a0 = np.zeros(n)
    a1 = np.zeros(n)
    rs = np.zeros(n, dtype=np.int64)
    fb = np.zeros(n, dtype=np.bool_)
    busy = np.int64(-1)
    for i in range(n):
        if sequential and g_dec[i] < busy:
            continue
        res = sim_position(g_dec[i], cid0[i], cid1[i], sg0[i], sg1[i], exit_g[i], stop, tgt, arm, keep, lot[i],
                           pos[i], last_g, g, o, h, l, c, v, off, cstart, cend, cstrike, cside, cexp, offcid, spot,
                           spotc, edge, fill_wait, exp_sq)
        st[i], gi[i], go[i], e0[i], e1[i], x0[i], x1[i], a0[i], a1[i], rs[i], fb[i] = res
        if res[0] == OK:
            busy = res[2]
    return st, gi, go, e0, e1, x0, x1, a0, a1, rs, fb


# ------------------------------------------------------------------------------------------------ sizing + charges
def size_lots(V0, lot, l5_min):
    """Lots for a premium budget: floor(BUDGET / (V0 x lot)), at least 1, at most LOT_CAP and at most VOL_CAP of the
    lots traded in the 5 minutes before entry (the thinner leg), at least 1."""
    V0 = np.asarray(V0, dtype=np.float64)
    n = np.floor(K.BUDGET / np.maximum(V0 * lot, 1e-9))
    n = np.clip(n, 1, K.LOT_CAP)
    cap = np.maximum(np.floor(K.VOL_CAP * np.asarray(l5_min, dtype=np.float64)), 1)
    return np.minimum(n, cap).astype(np.int64)


def charges(buy, price, qty, dn, bse):
    """Costs('dated') of one order per element; `buy` is an array of booleans (buy order / sell order)."""
    buy = np.asarray(buy, dtype=bool)
    return np.where(buy, COSTS.charge(True, price, qty, dn, bse), COSTS.charge(False, price, qty, dn, bse))


def settle(day_ordinals, lot, sg0, sg1, e0, e1, x0, x1, l5_0, l5_1, two_leg, bse, exit_ordinals=None):
    """Net Rs per position after sizing and Costs('dated') on every order (entry and exit, each leg)."""
    day_ordinals = np.asarray(day_ordinals, dtype=np.int64)
    exit_ordinals = day_ordinals if exit_ordinals is None else np.asarray(exit_ordinals, dtype=np.int64)
    two = np.asarray(two_leg, dtype=bool)
    sg0 = np.asarray(sg0, dtype=np.float64)
    sg1 = np.where(two, np.asarray(sg1, dtype=np.float64), 0.0)
    V0 = sg0 * e0 + sg1 * e1
    l5min = np.where(two, np.minimum(np.where(sg0 > 0, l5_0, np.inf), np.where(sg1 > 0, l5_1, np.inf)), l5_0)
    l5min = np.where(np.isfinite(l5min), l5min, l5_0)
    lots = size_lots(V0, lot, l5min)
    qty = lots * np.asarray(lot, dtype=np.int64)
    gross = (sg0 * (x0 - e0) + sg1 * (x1 - e1)) * qty
    ep = date(1970, 1, 1).toordinal()
    dn_in, dn_out = day_ordinals - ep, exit_ordinals - ep
    ch = charges(sg0 > 0, e0, qty, dn_in, bse) + charges(sg0 < 0, x0, qty, dn_out, bse)
    ch2 = charges(sg1 > 0, e1, qty, dn_in, bse) + charges(sg1 < 0, x1, qty, dn_out, bse)
    ch = ch + np.where(two, ch2, 0.0)
    return gross - ch, lots, V0 * qty, ch
