"""R176 engine: R106's held-contract simulator with Boss's Rs 500 step trail per lot (15 Nov 2026), as the app implements it.

    L = lots; P&L = (price - real entry fill) x qty before charges; best = the highest open P&L so far (minute highs)
    entry stop: real - 500 L / qty, rounded up to the tick, only where it is above the arm's own -15% stop
    step trail: once best >= 1,000 L, stop = real + (floor(best / 500 L) - 1) x 500 L / qty, rounded up to the tick
    the stop only moves up (highest of the entry stop, the step trail and the ladder / L25 locks); no target
    every other exit of the arm stays; whichever comes first; every stop is sold as a stop (R17 stop fill)

`simulate_step` is r106engine.simulate line for line with tgt_abs = -1, plus the step trail (reason 9 = 'step'). With the
rule off (step_amt <= 0, stop_abs = -1) it returns exactly what r106engine.simulate returns (test_r176.py). A new step
level is set at the end of the minute whose high reaches it and acts from the next minute: no look-ahead.

Callers import r176cfg first (stores and paths).
"""
from __future__ import annotations

import numpy as np
from numba import njit

import r176cfg as K
import r106engine as X
import engine as E                      # hunt/r51/engine.py
import r51cfg as K51
import r64engine as G

_ticks, _r2, _vol5 = E._ticks, E._r2, E._vol5
R_STEP = 9
REASONS = {**E.REASONS, R_STEP: "step"}


@njit(cache=True)
def _ceil_tick(x, tick):
    q = np.floor(x / tick * 1e6 + 0.5) / 1e6
    return np.ceil(q) * tick


@njit(cache=True)
def simulate_step(C, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
                  stop_abs, step_amt, fb=1.0005, fs=0.9995, fst=0.999, tk=1.0, gap_amt=-1.0):
    """r106engine.simulate (no rupee target) plus the step trail: step_amt = Rs 500 x lots for the whole position
    (<= 0: off); gap_amt = the gap under each step (<= 0: = step_amt, R176's rule; R177 Amendment B: Rs 1,000 x lots). Same returns; reason 9 = a step-trail stop."""
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
    if stop_abs > 0:
        stop = stop_abs
    tgt = E_ * (1.0 + tgt_f) if tgt_f >= 0 else 1e18
    lock = -1.0
    trail = -1.0
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
        rs = 2 if lock >= stop and lock > 0 else 1
        if trail > lvl:
            lvl = trail
            rs = 9
        if lvl > 0 and l[m] <= lvl:
            px = o[m] if (o[m] == o[m] and o[m] < lvl) else lvl
            real_x = max(_r2(px * fst) - 2.0 * tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
            return E_, px, real_e, real_x, ef, m, rs
        if h[m] >= tgt:
            return E_, tgt, real_e, tgt, ef, m, 3
        last = c[m]
        if h[m] > peak:
            peak = h[m]
        if step_amt > 0:
            best = (peak - real_e) * qty
            if best >= 2.0 * step_amt - 1e-9:
                g = gap_amt if gap_amt > 0 else step_amt
                lv = _ceil_tick(real_e + (np.floor(best / step_amt + 1e-9) * step_amt - g) / qty, tick)
                if lv > trail:
                    trail = lv
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


# ------------------------------------------------------------------------------------------------ levels (pure)
def entry_stop_px(real: float, qty: float, lots: int, per_lot: float = K.STEP) -> float:
    """The entry stop premium: real - per_lot x L / qty rounded UP to the tick (planned loss <= per_lot a lot before
    charges; Rs 500, or Rs 1,000 in Amendment B); -1 when the level is <= 0."""
    s = K.ceil_tick(real - K.entry_amount(lots, per_lot) / qty)
    return s if s > 0 else -1.0


def step_px(real: float, qty: float, best: float, lots: int, gap_per_lot: float = K.STEP) -> float:
    """The step-trail premium at a best open P&L of `best` (rupees, whole position); -1 below +1,000 L."""
    locked = K.step_locked(best, lots, gap_per_lot)
    return -1.0 if locked is None else K.ceil_tick(real + locked / qty)


def levels(raw: float, real: float, qty: float, ex: E.Exits, on: bool, lots: int, per_lot: float = K.STEP) -> tuple:
    """(stop_abs, stop_f, step_amt, kind) for simulate_step. on=False: the arm's own exits only. The entry stop replaces
    the arm's fractional stop only when it is the higher (tighter) premium; the step trail is on whenever on=True."""
    if not on:
        return -1.0, ex.stop, -1.0, "own"
    s = entry_stop_px(real, qty, lots, per_lot)
    own = raw * (1.0 - ex.stop) if ex.stop >= 0 else -1.0
    if s > 0 and s > own:
        return s, -1.0, K.step_amount(lots), "rupee"
    return -1.0, ex.stop, K.step_amount(lots), "own"


def label(code: int, kind: str) -> str:
    """Exit reason name: 'stop500' = the rule's entry stop (Rs 500 or, in Amendment B, Rs 1,000 a lot), 'step' = the
    step trail, else the arm's own reason."""
    why = REASONS[int(code)]
    if why == "stop" and kind == "rupee":
        return "stop500"
    return why


def price(und: str, D, cands: list, ex: E.Exits, on: bool, lots: int = 1, per_lot: float = K.STEP,
          gap_per_lot: float = K.STEP) -> list:
    """Price one day's candidates (dicts with side, strike, er[, fx]) for `lots` lots, with (on=True) or without Boss's
    step trail. Trade dicts (None = no fill) as r152engine.price plus 'stop_kind'."""
    st = E.stack(und, D)
    qty = float(K51.lot(und, D.d)) * lots
    sq = E.sq_row(D, ex)
    stp = ex.steps_arr()
    out = []
    ch = lambda a, b: float(E.charges(und, np.array([a]), np.array([b]), qty, D.d)[0])  # noqa: E731
    for c in cands:
        fx = c.get("fx", -1)
        ci = st.idx(c["side"], c["strike"])
        if ci < 0:
            out.append(None)
            continue
        C = st.S[ci]
        raw, real, ef, _ = X.entry_info(C, c["er"], sq, qty)
        if ef < 0:
            out.append(None)
            continue
        be = real + ch(real, real) / qty if G.needs_be(ex) else 0.0
        stop_abs, stop_f, step_amt, kind = levels(raw, real, qty, ex, on, lots, per_lot)
        a, b, rE, rX, r0, r1, r = simulate_step(C, int(c["er"]), int(sq), int(fx if fx is not None else -1), stop_f,
                                                ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail, stp, ex.tmax,
                                                ex.need, ex.close_stop, K51.TICK, qty, be, stop_abs, step_amt, *E.SLIP,
                                                gap_per_lot * lots)
        if r == E.R_NOFILL:
            out.append(None)
            continue
        net = (rX - rE) * qty - ch(rE, rX)
        out.append(dict(und=und, day=D.d, side=c["side"], strike=float(c["strike"]), er=int(r0), xr=int(r1), E=float(a),
                        X=float(b), rE=float(rE), rX=float(rX), qty=qty, lots=lots, why=label(r, kind), net=float(net),
                        gross=float((rX - rE) * qty), stop_px=float(stop_abs), stop_kind=kind))
    return out
