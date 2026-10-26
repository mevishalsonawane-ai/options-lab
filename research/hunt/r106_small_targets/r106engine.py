"""R106 engine: R64's held-contract simulator (hunt/r64/r64engine.py) with a PER-TRADE rupee target and rupee stop.

PREREG section 2. The target is a resting limit at the premium X where (X - real entry) x qty - charges = +T; the 1:1
stop is a resting stop at the premium S whose stop sale nets -T after charges. Both are solved from each trade's own
real entry fill (known at the fill, so nothing looks ahead). With no rupee target / stop (tgt_abs = stop_abs = -1)
`simulate` is R64's line for line (test_r106.py proves it on random contracts).

Callers import r101cfg first (it points r51cfg at the clean stores).
"""
from __future__ import annotations

import numpy as np
from numba import njit

import r101cfg  # noqa: F401,E402  (R51_CLEAN=1 and the R51 / R64 paths)
import engine as E                      # hunt/r51/engine.py: fills, ticks, charges, stacks, reasons
import r51cfg as K
import r64engine as G

_ticks, _r2, _vol5 = E._ticks, E._r2, E._vol5
TICK = 0.05


@njit(cache=True)
def simulate(C, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
             stop_abs, tgt_abs, fb=1.0005, fs=0.9995, fst=0.999, tk=1.0):
    """r64engine.simulate plus stop_abs (a premium; > 0 replaces the fractional stop) and tgt_abs (a premium; > 0 is a
    limit target, the nearer of it and the fractional target acts). Same returns."""
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
    if tgt_abs > 0 and tgt_abs < tgt:
        tgt = tgt_abs
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


# ------------------------------------------------------------------------------------------------ rupee levels (pure)
def _ceil_tick(x: float) -> float:
    return float(np.ceil(round(x / TICK, 6)) * TICK)


def target_price(real_e: float, qty: float, T: float, charges_fn) -> float:
    """The limit premium X (on the tick grid, rounded up) whose sale nets at least +T: (X - real_e) x qty - charges(real_e,
    X) >= T. charges_fn(entry, exit) -> rupees for the round trip."""
    x = real_e + T / qty
    for _ in range(6):
        x = real_e + (T + charges_fn(real_e, x)) / qty
    return _ceil_tick(x)


def stop_price(real_e: float, qty: float, T: float, charges_fn, ticks_e: float, fst: float = 0.999, tk: float = 1.0) -> float:
    """The stop premium S (on the tick grid, rounded up so the planned loss is at most T) whose stop sale (R17: x fst minus
    twice the half-spread of `ticks_e` ticks) nets -T after charges. -1 when the whole premium is worth less than T."""
    rx = real_e - T / qty
    for _ in range(6):
        rx = real_e + (charges_fn(real_e, max(rx, 0.0)) - T) / qty
    if rx <= 0:
        return -1.0
    s = (rx + 2.0 * tk * ticks_e * TICK) / fst
    return _ceil_tick(s)


# ------------------------------------------------------------------------------------------------ pricing one day
def entry_info(C, e: int, sq: int, qty: float):
    """(raw fill, real fill, fill row, half-spread ticks at the fill) or (nan, nan, -1, nan) with no fill."""
    raw, real, ef = G.entry_fill(C, int(e), int(sq), K.TICK, qty, E.SLIP[0], E.SLIP[3])
    if ef < 0:
        return np.nan, np.nan, -1, np.nan
    return raw, real, ef, float(_ticks(_vol5(C[4], ef - 1, qty)))


def price(und: str, D, cands: list, ex: E.Exits, T: float | None = None, stop_mode: str = "own", forced=None) -> list:
    """Price candidates (dicts with side, strike, er[, fx]) of one day with the arm's exits `ex` plus a rupee target T
    (None = none) and stop mode 'own' (the arm's) or '1:1' (rupee stop -T). Returns trade dicts (None = no fill) with
    the R64 fields plus 'tgt_px' / 'stop_px' (the rupee levels used; -1 none)."""
    st = E.stack(und, D)
    qty = float(K.lot(und, D.d))
    sq = E.sq_row(D, ex)
    stp = ex.steps_arr()
    out = []
    ch = lambda a, b: float(E.charges(und, np.array([a]), np.array([b]), qty, D.d)[0])  # noqa: E731
    fxs = [c.get("fx", -1) for c in cands] if forced is None else list(forced)
    for c, fx in zip(cands, fxs):
        ci = st.idx(c["side"], c["strike"])
        if ci < 0:
            out.append(None)
            continue
        C = st.S[ci]
        raw, real, ef, tks = entry_info(C, c["er"], sq, qty)
        if ef < 0:
            out.append(None)
            continue
        be = 0.0
        if G.needs_be(ex):
            be = real + ch(real, real) / qty
        tgt_abs = target_price(real, qty, T, ch) if T is not None else -1.0
        stop_abs, stop_f = -1.0, ex.stop
        if stop_mode == "1:1" and T is not None:
            stop_abs = stop_price(real, qty, T, ch, tks, E.SLIP[2], E.SLIP[3])
            stop_f = -1.0
        a, b, rE, rX, r0, r1, r = simulate(C, int(c["er"]), int(sq), int(fx if fx is not None else -1), stop_f, ex.target,
                                           ex.lock_trig, ex.lock_keep, ex.lock_trail, stp, ex.tmax, ex.need,
                                           ex.close_stop, K.TICK, qty, be, stop_abs, tgt_abs, *E.SLIP)
        if r == E.R_NOFILL:
            out.append(None)
            continue
        net = (rX - rE) * qty - ch(rE, rX)
        out.append(dict(und=und, day=D.d, side=c["side"], strike=float(c["strike"]), er=int(r0), xr=int(r1), E=float(a),
                        X=float(b), rE=float(rE), rX=float(rX), qty=qty, why=E.REASONS[int(r)], net=float(net),
                        tgt_px=float(tgt_abs), stop_px=float(stop_abs)))
    return out


@njit(cache=True)
def _sim_rows(C, e, sq, fxs, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
              fb, fs, fst, tk):
    n = fxs.shape[0]
    rE = np.zeros(n)
    rX = np.zeros(n)
    ok = np.zeros(n, np.bool_)
    for i in range(n):
        a, b, c_, d_, r0, r1, r = simulate(C, e, sq, fxs[i], stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax,
                                           need, cstop, tick, qty, be, -1.0, -1.0, fb, fs, fst, tk)
        if r != 0:
            rE[i], rX[i], ok[i] = c_, d_, True
    return rE, rX, ok


def forced_exit_nets(und: str, D, trade: dict, ex: E.Exits, rows: np.ndarray, fx0: int = -1) -> np.ndarray:
    """Net of selling `trade` (from its own entry row `er_sig`) at the open of each row in `rows`, with the arm's own exits
    still active before it (the random-exit twin, PREREG section 5). fx0: the trade's own forced (index) exit row, which
    still applies when earlier."""
    st = E.stack(und, D)
    C = st.S[st.idx(trade["side"], trade["strike"])]
    qty = float(K.lot(und, D.d))
    sq = E.sq_row(D, ex)
    rows = np.asarray(rows, np.int64)
    fxs = rows if fx0 < 0 else np.minimum(rows, fx0)
    be = 0.0
    if G.needs_be(ex):
        _, real, ef, _ = entry_info(C, trade["er_sig"], sq, qty)
        be = real + float(E.charges(und, np.array([real]), np.array([real]), qty, D.d)[0]) / qty
    rE, rX, ok = _sim_rows(C, int(trade["er_sig"]), int(sq), fxs, ex.stop, ex.target, ex.lock_trig, ex.lock_keep,
                           ex.lock_trail, ex.steps_arr(), ex.tmax, ex.need, ex.close_stop, K.TICK, qty, be, *E.SLIP)
    net = (rX - rE) * qty - E.charges(und, rE, rX, qty, D.d)
    return np.where(ok, net, 0.0)
