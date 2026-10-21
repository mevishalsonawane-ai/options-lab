"""R68 engine: R64's simulator (hunt/r64/r64engine.py `simulate`) plus a MOVING TARGET for variant WM (PREREG
Amendment 1): the fixed target starts at tgt_f and rises `mt_step` at every staircase step reached from `mt_first`
(set by a minute's high, so it acts from the next minute). With mt_first < 0 it is R64's code line for line
(test_r68.py checks parity). `install()` routes r64engine.simulate_many through here; MOVING picks the mode.
"""
from __future__ import annotations

import numpy as np
from numba import njit

import r64engine as G

E = G.E
_ticks, _r2, _vol5 = G._ticks, G._r2, G._vol5
MOVING = None                 # (first, step) for WM, else None
_ORIG = G.simulate_many


@njit(cache=True)
def simulate(C, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
             mt_first, mt_step, fb=1.0005, fs=0.9995, fst=0.999, tk=1.0):
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
    while m < end:
        if pend_cstop:
            if o[m] == o[m]:
                real_x = max(_r2(o[m] * fst) - 2.0 * tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
                return E_, float(o[m]), real_e, real_x, ef, m, 8
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
        last = c[m]
        if h[m] > peak:
            peak = h[m]
        if lk_trig > 0 and (peak - E_) * qty >= lk_trig:
            lv = E_ + lk_keep / qty
            if lk_trail > 0:
                lv = max(lv, peak - lk_trail / qty)
            if lv > lock:
                lock = lv
        g = peak / E_ - 1.0
        if steps.shape[0] > 0:
            for j in range(steps.shape[0]):
                if g >= steps[j, 0] - 1e-12:
                    lv = E_ * (1.0 + steps[j, 1]) if steps[j, 1] == steps[j, 1] else be
                    if lv > lock:
                        lock = lv
        if mt_first >= 0 and tgt_f >= 0 and g >= mt_first - 1e-12:
            k = int(np.floor((g - mt_first) / mt_step + 1e-9)) + 1
            tgt = E_ * (1.0 + tgt_f + mt_step * k)
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
def _many(S, ci, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
          peak_row, mt_first, mt_step, fb, fs, fst, tk):
    n = len(ci)
    out = np.full((n, 5), np.nan)
    er = np.full(n, -1, np.int32)
    xr = np.full(n, -1, np.int32)
    rs = np.zeros(n, np.int32)
    for i in range(n):
        if ci[i] < 0:
            continue
        a, b, c_, d_, r0, r1, r = simulate(S[ci[i]], e[i], sq, fx[i], stop_f, tgt_f, lk_trig, lk_keep, lk_trail,
                                           steps, tmax, need, cstop, tick, qty, be[i], mt_first, mt_step,
                                           fb, fs, fst, tk)
        out[i, 0], out[i, 1], out[i, 2], out[i, 3] = a, b, c_, d_
        out[i, 4] = G.peak_gain(S[ci[i]], r0, peak_row)
        er[i], xr[i], rs[i] = r0, r1, r
    return out, er, xr, rs


def simulate_many(S, ci, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
                  peak_row, fb=1.0005, fs=0.9995, fst=0.999, tk=1.0):
    """Drop-in for r64engine.simulate_many: R64's own when MOVING is None, else the moving target."""
    if MOVING is None:
        return _ORIG(S, ci, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick,
                     qty, be, peak_row, fb, fs, fst, tk)
    return _many(S, ci, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty,
                 be, peak_row, MOVING[0], MOVING[1], fb, fs, fst, tk)


def install():
    G.simulate_many = simulate_many
