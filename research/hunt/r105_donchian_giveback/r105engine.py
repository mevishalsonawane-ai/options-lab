"""R105 engine: R64's held-contract simulator (hunt/r64/r64engine.py `simulate`) plus the exit mechanics of PREREG
section 2. With every new input off it is R64's code line for line (test_r105.py proves it trade by trade).

New inputs (all off by default):
    be_rs        X4: stop to the breakeven price `be` once (best high - raw fill) x qty >= be_rs rupees (<= 0 off)
    tt_off, tt_pk  X5: from fill row + tt_off, if the best high has been >= fill x (1 + tt_pk), stop to `be` (tt_off < 0 off)
    gb_trig, gb_keep  X6: once the best high is >= fill x (1 + gb_trig), stop at fill + gb_keep x (best - fill)
    idx_mode     0 none; 1 = X7 / X8: a 1-minute index CLOSE back through `ilvl` (below it for a call, above for a put);
                 2 = X10: a 1-minute index LOW (call) / HIGH (put) through the low / high of the last completed 10-minute
                 index bar, trailed (only tightens). Either sells the option at the NEXT minute's open (reason 9).
    idx_arm      the index exit is armed only once the best high has reached fill x (1 + idx_arm) (0 = from the fill).
    sgn          +1 call, -1 put (index direction of the trade).
    I            (4, n) the index O H L C on the same minute grid.

Timing (no look-ahead): every lock and every arming decided at a minute's end acts from the next minute (R64's rule);
the index check at minute m uses minute m's bar and an arming state known at the end of m-1, and sells at m+1's open;
the 10-minute bar used at minute m ends at or before m-1.
"""
from __future__ import annotations

import numpy as np
from numba import njit

R_IDX = 9


@njit(cache=True)
def _ticks(v_lots):
    if v_lots >= 20.0:
        return 1.0
    if v_lots > 0.0:
        return 2.0
    return 4.0


@njit(cache=True)
def _r2(x):
    return np.round(x * 100.0) / 100.0


@njit(cache=True)
def _vol5(v, j, qty):
    """Contracts traded in the 5 minutes up to and including row j, in lots (R51 engine)."""
    s = 0.0
    for k in range(max(j - 4, 0), j + 1):
        if k >= 0 and v[k] == v[k]:
            s += v[k]
    return s / qty if qty > 0 else 0.0


@njit(cache=True)
def simulate(C, e, sq, fx, stop_f, tgt_f, steps, tmax, need, cstop, tick, qty, be,
             be_rs, tt_off, tt_pk, gb_trig, gb_keep, idx_mode, idx_arm, ilvl, sgn, I,
             fb=1.0005, fs=0.9995, fst=0.999, tk=1.0):
    """Returns (raw entry, raw exit, real entry, real exit, fill row, exit row, reason) as R64's simulate."""
    o, h, l, c, v = C[0], C[1], C[2], C[3], C[4]
    nan = np.nan
    n = o.shape[0]
    ef = -1
    for j in range(e, min(e + 3, sq)):
        if o[j] == o[j] and o[j] > 0:
            ef = j
            break
    if ef < 0:
        return nan, nan, nan, nan, -1, -1, 0
    E_ = float(o[ef])
    real_e = _r2(E_ * fb) + tk * _ticks(_vol5(v, ef - 1, qty)) * tick
    stop = E_ * (1.0 - stop_f) if stop_f >= 0 else -1.0
    tgt = E_ * (1.0 + tgt_f) if tgt_f >= 0 else 1e18
    lock = -1.0
    peak = E_
    last = E_
    tend = ef + tmax - 1 if tmax > 0 else 10 ** 6
    end = sq
    if fx >= 0 and fx < end:
        end = fx
    m = ef
    pend_cstop = False
    pend_idx = False
    armed = idx_mode > 0 and idx_arm <= 0.0
    trail = -1e18 if sgn > 0 else 1e18
    while m < end:
        if pend_cstop:
            if o[m] == o[m]:
                real_x = max(_r2(o[m] * fst) - 2.0 * tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
                return E_, float(o[m]), real_e, real_x, ef, m, 8
        if pend_idx:
            if o[m] == o[m]:
                real_x = max(_r2(o[m] * fs) - tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
                return E_, float(o[m]), real_e, real_x, ef, m, R_IDX
        if not (l[m] == l[m]):
            if m == tend:
                break
            m += 1
            continue
        lvl = stop if stop >= lock else lock
        if lvl > 0 and l[m] <= lvl:
            px = o[m] if (o[m] == o[m] and o[m] < lvl) else lvl
            rs = 2 if lock >= stop and lock > 0 else 1
            real_x = max(_r2(px * fst) - 2.0 * tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
            return E_, px, real_e, real_x, ef, m, rs
        if h[m] >= tgt:
            return E_, tgt, real_e, tgt, ef, m, 3
        # the index exit: minute m's index bar, arming known at the end of m-1
        if idx_mode > 0 and armed and not pend_idx:
            if idx_mode == 1:
                ic = I[3, m]
                if ic == ic and ((sgn > 0 and ic < ilvl) or (sgn < 0 and ic > ilvl)):
                    pend_idx = True
            else:
                k = m // 10 - 1
                if k >= 0:
                    bl = 1e18
                    bh = -1e18
                    for r in range(10 * k, 10 * k + 10):
                        if I[2, r] == I[2, r] and I[2, r] < bl:
                            bl = I[2, r]
                        if I[1, r] == I[1, r] and I[1, r] > bh:
                            bh = I[1, r]
                    if sgn > 0 and bl < 1e17 and bl > trail:
                        trail = bl
                    if sgn < 0 and bh > -1e17 and bh < trail:
                        trail = bh
                if sgn > 0 and I[2, m] == I[2, m] and trail > -1e17 and I[2, m] <= trail:
                    pend_idx = True
                if sgn < 0 and I[1, m] == I[1, m] and trail < 1e17 and I[1, m] >= trail:
                    pend_idx = True
        last = c[m]
        if h[m] > peak:
            peak = h[m]
        g = peak / E_ - 1.0
        if steps.shape[0] > 0:
            for j in range(steps.shape[0]):
                if g >= steps[j, 0] - 1e-12:
                    lv = E_ * (1.0 + steps[j, 1]) if steps[j, 1] == steps[j, 1] else be
                    if lv > lock:
                        lock = lv
        if be_rs > 0 and (peak - E_) * qty >= be_rs - 1e-9:
            if be > lock:
                lock = be
        if tt_off >= 0 and m >= ef + tt_off and g >= tt_pk - 1e-12:
            if be > lock:
                lock = be
        if gb_trig > 0 and g >= gb_trig - 1e-12:
            lv = E_ + gb_keep * (peak - E_)
            if lv > lock:
                lock = lv
        if idx_mode > 0 and not armed and g >= idx_arm - 1e-12:
            armed = True
        if cstop >= 0 and c[m] <= E_ * (1.0 - cstop):
            pend_cstop = True
        if m == tend:
            if need < 0 or c[m] < E_ * (1.0 + need):
                real_x = max(_r2(c[m] * fs) - tk * _ticks(_vol5(v, m, qty)) * tick, 0.0)
                return E_, float(c[m]), real_e, real_x, ef, m, 4
        m += 1
    if m == tend and m < end:
        real_x = max(_r2(last * fs) - tk * _ticks(_vol5(v, m, qty)) * tick, 0.0)
        return E_, last, real_e, real_x, ef, m, 7
    xr = end
    rs = 6 if (fx >= 0 and fx < sq) else 5
    if pend_idx and xr < n and o[xr] == o[xr]:
        rs = R_IDX
    px = o[xr] if (xr < n and o[xr] == o[xr]) else nan
    if not (px == px):
        for j in range(xr + 1, min(xr + 4, n)):
            if o[j] == o[j]:
                px = o[j]
                break
    if not (px == px):
        px = last
        rs = 7
    real_x = max(_r2(px * fs) - tk * _ticks(_vol5(v, xr - 1, qty)) * tick, 0.0)
    return E_, px, real_e, real_x, ef, xr, rs


@njit(cache=True)
def peak_to_exit(C, ef, xr):
    """The best premium high from the fill row to the exit row (inclusive): the cohort's peak (PREREG section 4)."""
    if ef < 0:
        return np.nan
    pk = C[0, ef]
    for m in range(ef, min(xr + 1, C.shape[1])):
        if C[1, m] == C[1, m] and C[1, m] > pk:
            pk = C[1, m]
    return pk


@njit(cache=True)
def simulate_many(S, ci, e, sq, fx, stop_f, tgt_f, steps, tmax, need, cstop, tick, qty, be,
                  be_rs, tt_off, tt_pk, gb_trig, gb_keep, idx_mode, idx_arm, ilvl, sgn, I,
                  fb=1.0005, fs=0.9995, fst=0.999, tk=1.0):
    """Per trade i: S[ci[i]] the held contract, e[i] the entry row, fx[i] a forced row, be[i], ilvl[i], sgn[i].
    Returns (n, 5) (raw entry, raw exit, real entry, real exit, peak premium high), fill rows, exit rows, reasons."""
    n = len(ci)
    out = np.full((n, 5), np.nan)
    er = np.full(n, -1, np.int32)
    xr = np.full(n, -1, np.int32)
    rs = np.zeros(n, np.int32)
    for i in range(n):
        if ci[i] < 0:
            continue
        a, b, c_, d_, r0, r1, r = simulate(S[ci[i]], e[i], sq, fx[i], stop_f, tgt_f, steps, tmax, need, cstop,
                                           tick, qty, be[i], be_rs, tt_off, tt_pk, gb_trig, gb_keep, idx_mode,
                                           idx_arm, ilvl[i], sgn[i], I, fb, fs, fst, tk)
        out[i, 0], out[i, 1], out[i, 2], out[i, 3] = a, b, c_, d_
        out[i, 4] = peak_to_exit(S[ci[i]], r0, r1)
        er[i], xr[i], rs[i] = r0, r1, r
    return out, er, xr, rs
