"""R76 engine (PREREG.md section 3): R64's held-contract simulator (hunt/r64/r64engine.py `simulate`) plus one input,
an INDEX trigger. 18 Oct 2026.

`trg` is one flag per minute: 1 where the index reached the trigger level in that minute (X% of the way from the entry
index price to the trade's index target). The first flagged minute at or after the fill ARMS the lock; the lock
(`tkeep` above the raw fill, e.g. +5%) then acts from the next minute, exactly like R51 / R64's premium steps (set at
the end of a minute, hit on a later minute's option low, a gap through it fills at the worse open, locks only rise).
Rows before the fill and after the exit are never read. With no flag (or tkeep < 0) the numbers are R64's, line for
line (test_r76.py proves it).

Callers import r51cfg first (R51's clean stores); this module reads K.TICK, lots and E's fills and charges.
"""
from __future__ import annotations

import numpy as np
from numba import njit

import engine as E                      # hunt/r51/engine.py: fills, ticks, charges, stacks, reasons
import r51cfg as K
import r64engine as G

_ticks, _r2, _vol5 = E._ticks, E._r2, E._vol5


def trigger_flags(idx: np.ndarray, ef: int, sgn: int, i0: float, tg: float, frac: float) -> np.ndarray:
    """Per-minute index-trigger flags (PREREG section 2). idx: (4, n) index O H L C; ef: the option's fill row; sgn: +1
    call / -1 put; i0: entry index price; tg: the index target; frac: the share of the way (0.25 = 25%). A call is
    flagged where the index high >= i0 + frac * (tg - i0), a put where the low <= it; only rows >= ef. No target ahead
    (NaN, or i0 already at / past it) or no fill: no flags. Pure."""
    n = idx.shape[1]
    out = np.zeros(n)
    if ef < 0 or not (np.isfinite(tg) and np.isfinite(i0)) or sgn * (tg - i0) <= 0:
        return out
    lvl = i0 + frac * (tg - i0)
    with np.errstate(invalid="ignore"):
        hit = idx[1] >= lvl if sgn > 0 else idx[2] <= lvl          # NaN minutes compare False
    out[ef:] = hit[ef:].astype(float)
    return out


@njit(cache=True)
def simulate(C, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
             trg, tkeep, fb=1.0005, fs=0.9995, fst=0.999, tk=1.0):
    """hunt/r64/r64engine.py `simulate` plus the index trigger `trg` (per-minute flags) and `tkeep` (the locked gain over
    the raw fill once armed; < 0 off). Same returns."""
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
    armed = False                                   # R76: the index trigger fired in an EARLIER minute
    tlv = E_ * (1.0 + tkeep) if tkeep >= 0 else -1.0
    while m < end:
        if pend_cstop:
            if o[m] == o[m]:
                real_x = max(_r2(o[m] * fst) - 2.0 * tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
                return E_, float(o[m]), real_e, real_x, ef, m, 8
        if armed and tlv > lock:                    # R76: acts from the minute after the trigger minute
            lock = tlv
        if not (l[m] == l[m]):
            if tkeep >= 0 and trg[m] > 0:           # R76: an index minute with no option bar still arms
                armed = True
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
        if tkeep >= 0 and trg[m] > 0:               # R76: judged on this minute's index bar, acts from the next
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


def entry_index(idx: np.ndarray, ef: int, close: float) -> float:
    """PREREG: the index open of the fill minute, else the signal bar's close. Pure."""
    x = idx[0, ef] if 0 <= ef < idx.shape[1] else np.nan
    return float(x) if np.isfinite(x) else float(close)


def price_trade(und: str, D, t: dict, ex: E.Exits, frac: float | None, keep: float = 0.05):
    """One trade (a B0 trade dict: side, strike, e0, fx0, sgn, close, target) priced with `ex` plus the index trigger at
    `frac` of the way to the target (None = no trigger). Returns the R64 run_day dict plus `trig` (the trigger row, or
    -1), or None if it cannot fill."""
    st = E.stack(und, D)
    ci = st.idx(t["side"], t["strike"])
    if ci < 0:
        return None
    S = st.S[ci]
    qty = float(K.lot(und, D.d))
    sq = E.sq_row(D, ex)
    _, _, ef = G.entry_fill(S, int(t["e0"]), int(sq), K.TICK, qty, E.SLIP[0], E.SLIP[3])
    if frac is None or ef < 0:
        trg, tkeep = np.zeros(S.shape[1]), -1.0
    else:
        i0 = entry_index(D.idx, ef, t["close"])
        trg, tkeep = trigger_flags(D.idx, ef, int(t["sgn"]), i0, float(t["target"]), frac), keep
    a, b, rE, rX, er, xr, rs = simulate(S, int(t["e0"]), int(sq), int(t["fx0"]), ex.stop, ex.target, ex.lock_trig,
                                         ex.lock_keep, ex.lock_trail, ex.steps_arr(), ex.tmax, ex.need, ex.close_stop,
                                         K.TICK, qty, 0.0, trg, tkeep, *E.SLIP)
    if rs == E.R_NOFILL:
        return None
    ch = float(E.charges(und, np.array([rE]), np.array([rX]), qty, D.d)[0])
    hits = np.flatnonzero(trg[: max(int(xr), 0)] > 0)
    return dict(und=und, day=D.d, side=t["side"], strike=float(t["strike"]), er=int(er), xr=int(xr), E=float(a),
                X=float(b), rE=float(rE), rX=float(rX), qty=qty, why=E.REASONS[int(rs)], net=float((rX - rE) * qty - ch),
                gross=float((b - a) * qty), peak=float(G.peak_gain(S, int(er), K.row(15, 15))),
                ret=float(b / a - 1.0), trig=int(hits[0]) if len(hits) else -1)
