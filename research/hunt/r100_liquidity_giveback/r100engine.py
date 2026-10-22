"""R100 engine (PREREG.md sections 2-3): R64's held-contract simulator (hunt/r64/r64engine.py `simulate`) plus one
input, KEEP ROWS, and an index-breakeven exit row. 22 Oct 2026.

A keep row is (from_minute, start, keep, kind):
    from_minute  the row counts only from this many minutes after the fill row (0 = from the fill)
    start        kind 0 and 2: the option's best gain over the raw fill (0.20 = +20%); kind 1: the best open profit in
                 rupees for the trade's quantity before charges ((peak - fill) x qty >= start)
    keep         kinds 0 / 1: the share of the best gain kept: the lock is fill + keep x (peak - fill); NaN = breakeven
                 (`be`). Kind 2 (amendment A2, a trailing stop-loss): the stop trails the best price by this share,
                 `peak x (1 - keep)` (0.15: 15% under the best price).
Every kind 0 / 1 lock is floored at `be` (breakeven after charges, R64's level), so a give-back lock never books a
certain small loss as a "lock". A kind 2 trailing stop is a stop-loss and is not floored.
As R64's steps, a lock is set at the END of a minute (after that minute's high updates the
best price) and acts from the NEXT minute: hit on a later minute's low, a gap through it fills at the worse open, and
locks only ever rise. With no keep rows the numbers are R64's, line for line (tests/test_r100.py proves it).

The index breakeven (G5) is not a premium lock: `index_be_row` gives the row whose open sells the trade, and the caller
passes min(B0's own index-exit row, that row) as the forced exit, exactly like B0's index exits.

Callers import r51cfg first (R51's clean stores); this module reads K.TICK, lots and E's fills and charges.
"""
from __future__ import annotations

import numpy as np
from numba import njit

import engine as E                      # hunt/r51/engine.py: fills, ticks, charges, stacks, reasons
import r51cfg as K
import r64engine as G

_ticks, _r2, _vol5 = E._ticks, E._r2, E._vol5
NOKEEP = np.zeros((0, 4))


@njit(cache=True)
def keep_level(rows, E_, peak, qty, be, minutes):
    """The highest lock the keep rows give for a best price `peak` after `minutes` minutes held, or -1. Pure."""
    lock = -1.0
    for j in range(rows.shape[0]):
        if minutes < rows[j, 0]:
            continue
        if rows[j, 3] == 1:
            ok = (peak - E_) * qty >= rows[j, 1] - 1e-9
        else:
            ok = peak / E_ - 1.0 >= rows[j, 1] - 1e-12
        if not ok:
            continue
        k = rows[j, 2]
        if rows[j, 3] == 2:                         # A2: a trailing stop-loss, peak x (1 - k), not floored
            lv = peak * (1.0 - k)
        else:
            lv = be if not (k == k) else E_ + k * (peak - E_)
            if be > lv:
                lv = be
        if lv > lock:
            lock = lv
    return lock


@njit(cache=True)
def simulate(C, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
             rows, fb=1.0005, fs=0.9995, fst=0.999, tk=1.0):
    """hunt/r64/r64engine.py `simulate` plus the keep rows `rows` ((n, 4); empty = R64). Same returns."""
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
        if steps.shape[0] > 0:
            g = peak / E_ - 1.0
            for j in range(steps.shape[0]):
                if g >= steps[j, 0] - 1e-12:
                    lv = E_ * (1.0 + steps[j, 1]) if steps[j, 1] == steps[j, 1] else be
                    if lv > lock:
                        lock = lv
        if rows.shape[0] > 0:                       # R100: the keep rows, judged at this minute's end
            lv = keep_level(rows, E_, peak, qty, be, float(m - ef))
            if lv > lock:
                lock = lv
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


def index_be_row(idx: np.ndarray, ef: int, sgn: int, i0: float, units_pts: float) -> int:
    """G5 (PREREG section 3): the row whose OPEN sells the trade under the index breakeven, or -1. Armed on the first
    1-minute index bar at or after the fill row `ef` that reached `units_pts` past `i0` in the trade's direction (call:
    high >= i0 + u; put: low <= i0 - u); from the NEXT minute, the first bar back at i0 (call: low <= i0; put: high >=
    i0) sells at the following minute's open. NaN minutes never arm or fire. Pure."""
    n = idx.shape[1]
    if ef < 0 or ef >= n or not (np.isfinite(i0) and units_pts > 0):
        return -1
    hi, lo = idx[1], idx[2]
    with np.errstate(invalid="ignore"):
        arm = (hi >= i0 + units_pts) if sgn > 0 else (lo <= i0 - units_pts)
        back = (lo <= i0) if sgn > 0 else (hi >= i0)
    a = np.flatnonzero(arm[ef:])
    if not len(a):
        return -1
    j = ef + int(a[0])
    b = np.flatnonzero(back[j + 1:])
    if not len(b):
        return -1
    r = j + 1 + int(b[0]) + 1
    return r if r < n else -1


def entry_index(idx: np.ndarray, ef: int, close: float) -> float:
    """R76's I0: the index open of the fill minute, else the signal bar's close. Pure."""
    x = idx[0, ef] if 0 <= ef < idx.shape[1] else np.nan
    return float(x) if np.isfinite(x) else float(close)


def first_forced(fx0: int, fx1: int) -> int:
    """The earlier of two forced-exit rows (-1 = none). Pure."""
    xs = [x for x in (fx0, fx1) if x >= 0]
    return min(xs) if xs else -1


def price_trade(und: str, D, t: dict, ex: E.Exits, rows: np.ndarray = NOKEEP, idx_units: float | None = None,
                forced: int | None = None):
    """One B0 trade (dict with side, strike, e0, fx0, sgn, close) priced with `ex`, the keep `rows` and, for G5, the
    index breakeven at `idx_units` points. `forced` overrides the forced row (twins). Returns R64's run_day dict plus
    `fx` (the forced row used), or None if it cannot fill."""
    st = E.stack(und, D)
    ci = st.idx(t["side"], t["strike"])
    if ci < 0:
        return None
    S = st.S[ci]
    qty = float(K.lot(und, D.d))
    sq = E.sq_row(D, ex)
    _, rE0, ef = G.entry_fill(S, int(t["e0"]), int(sq), K.TICK, qty, E.SLIP[0], E.SLIP[3])
    if ef < 0:
        return None
    fx = int(t["fx0"]) if forced is None else int(forced)
    if idx_units is not None:
        i0 = entry_index(D.idx, ef, float(t["close"]))
        fx = first_forced(fx, index_be_row(D.idx, ef, int(t["sgn"]), i0, idx_units))
    be = 0.0
    if rows.shape[0] > 0 or G.needs_be(ex):
        be = float(rE0 + E.charges(und, np.array([rE0]), np.array([rE0]), qty, D.d)[0] / qty)
    a, b, rE, rX, er, xr, rs = simulate(S, int(t["e0"]), int(sq), fx, ex.stop, ex.target, ex.lock_trig, ex.lock_keep,
                                        ex.lock_trail, ex.steps_arr(), ex.tmax, ex.need, ex.close_stop, K.TICK, qty,
                                        be, rows, *E.SLIP)
    if rs == E.R_NOFILL:
        return None
    ch = float(E.charges(und, np.array([rE]), np.array([rX]), qty, D.d)[0])
    return dict(und=und, day=D.d, side=t["side"], strike=float(t["strike"]), er=int(er), xr=int(xr), E=float(a),
                X=float(b), rE=float(rE), rX=float(rX), qty=qty, why=E.REASONS[int(rs)], net=float((rX - rE) * qty - ch),
                gross=float((b - a) * qty), peak=float(G.peak_gain(S, int(er), K.row(15, 15))),
                held_peak=float(G.peak_gain(S, int(er), max(int(er), int(xr) - 1))), ret=float(b / a - 1.0), fx=int(fx))
