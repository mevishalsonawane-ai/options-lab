"""R64 engine: R51's held-contract simulator (hunt/r51/engine.py `simulate`) with one addition, a BREAKEVEN step.

PREREG.md section 2: once the option has traded +30% above the fill, the stop rises to the fill price plus the round-trip
charges. R51's stepped lock only knows "a fraction above the raw fill", so a step whose locked gain is NaN here means
"the breakeven level `be`" (an absolute price per trade: real entry + charges(entry, entry) / quantity). Everything else
is R51's code line for line; test_r64.py proves the two give the same numbers when no breakeven step is present.

Callers import r51cfg / r57cfg first (whichever study's stores they price on); this module only reads K.TICK and lots.
"""
from __future__ import annotations

import numpy as np
from numba import njit

import engine as E                      # hunt/r51/engine.py: fills, ticks, charges, stacks, reasons
import r51cfg as K

NAN = float("nan")
# Boss's ladder (PREREG.md section 2): (peak gain, locked gain); NaN = breakeven (fill + round-trip charges)
LADDER = ((0.30, NAN), (0.60, 0.30), (1.00, 0.60))
_ticks, _r2, _vol5 = E._ticks, E._r2, E._vol5


@njit(cache=True)
def entry_fill(C, e, sq, tick, qty, fb=1.0005, tk=1.0):
    """The entry part of simulate: (raw fill, real fill, fill row) or (nan, nan, -1) when nothing trades in time."""
    o, v = C[0], C[4]
    for j in range(e, min(e + 3, sq)):
        if o[j] == o[j] and o[j] > 0:
            E_ = float(o[j])
            return E_, _r2(E_ * fb) + tk * _ticks(_vol5(v, j - 1, qty)) * tick, j
    return np.nan, np.nan, -1


@njit(cache=True)
def simulate(C, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
             fb=1.0005, fs=0.9995, fst=0.999, tk=1.0):
    """hunt/r51/engine.py `simulate` plus `be`: the price a NaN-gain step locks (breakeven). Same returns."""
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
def peak_gain(C, ef, last_row):
    """The highest high of the held contract from the fill row through `last_row` (inclusive) over the raw fill, - 1:
    the 'big mover' test (PREREG section 6). NaN if no fill."""
    if ef < 0:
        return np.nan
    E_ = C[0, ef]
    pk = E_
    for m in range(ef, min(last_row + 1, C.shape[1])):
        if C[1, m] == C[1, m] and C[1, m] > pk:
            pk = C[1, m]
    return pk / E_ - 1.0


@njit(cache=True)
def simulate_many(S, ci, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be,
                  peak_row, fb=1.0005, fs=0.9995, fst=0.999, tk=1.0):
    """As R51's, with a per-trade breakeven level `be` and the peak gain to `peak_row`. Returns (n, 5) prices
    (raw entry, raw exit, real entry, real exit, peak gain), entry / exit rows, reasons."""
    n = len(ci)
    out = np.full((n, 5), np.nan)
    er = np.full(n, -1, np.int32)
    xr = np.full(n, -1, np.int32)
    rs = np.zeros(n, np.int32)
    for i in range(n):
        if ci[i] < 0:
            continue
        a, b, c_, d_, r0, r1, r = simulate(S[ci[i]], e[i], sq, fx[i], stop_f, tgt_f, lk_trig, lk_keep, lk_trail,
                                           steps, tmax, need, cstop, tick, qty, be[i], fb, fs, fst, tk)
        out[i, 0], out[i, 1], out[i, 2], out[i, 3] = a, b, c_, d_
        out[i, 4] = peak_gain(S[ci[i]], r0, peak_row)
        er[i], xr[i], rs[i] = r0, r1, r
    return out, er, xr, rs


def be_levels(und, D, S, ci, e, sq, qty) -> np.ndarray:
    """The breakeven price of each trade: real entry + round-trip charges at the entry price / quantity (0 = no fill)."""
    n = len(ci)
    be = np.zeros(n)
    rE = np.zeros(n)
    for i in range(n):
        if ci[i] >= 0:
            _, re_, ef = entry_fill(S[ci[i]], int(e[i]), int(sq), K.TICK, qty, *_ENTRY_SLIP())
            if ef >= 0:
                rE[i] = re_
    ok = rE > 0
    if ok.any():
        ch = E.charges(und, rE[ok], rE[ok], qty, D.d)
        be[ok] = rE[ok] + ch / qty
    return be


def _ENTRY_SLIP():
    return E.SLIP[0], E.SLIP[3]


def needs_be(ex: E.Exits) -> bool:
    return any(not np.isfinite(g) for _, g in ex.steps)


def price(und: str, D, ci, entries, ex: E.Exits, forced=None, peak_row: int = -1):
    """Price trades of one day on the stack's contract indices `ci`: (res (n, 5), er, xr, rs, charges (n,))."""
    st = E.stack(und, D)
    n = len(ci)
    ci = np.asarray(ci, np.int64)
    e = np.asarray(entries, np.int64)
    fx = np.full(n, -1, np.int64) if forced is None else np.asarray(forced, np.int64)
    qty = float(K.lot(und, D.d))
    sq = E.sq_row(D, ex)
    be = be_levels(und, D, st.S, ci, e, sq, qty) if needs_be(ex) else np.zeros(n)
    pr = peak_row if peak_row >= 0 else K.row(15, 15)
    res, er, xr, rs = simulate_many(st.S, ci, e, sq, fx, ex.stop, ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail,
                                    ex.steps_arr(), ex.tmax, ex.need, ex.close_stop, K.TICK, qty, be, pr, *E.SLIP)
    rE, rX = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
    ch = E.charges(und, rE, rX, qty, D.d) if n else np.zeros(0)
    return res, er, xr, rs, ch, qty


def run_day(und: str, D, sides, strikes, entries, ex: E.Exits, forced=None):
    """R51 run_day on R64's engine: a list of trade dicts (None = no fill), plus 'peak' (big-mover test) and 'ret'
    (raw exit / raw fill - 1)."""
    st = E.stack(und, D)
    ci = [st.idx(s, k) for s, k in zip(sides, strikes)]
    res, er, xr, rs, ch, qty = price(und, D, ci, entries, ex, forced)
    out = []
    for i in range(len(ci)):
        if rs[i] == E.R_NOFILL:
            out.append(None)
            continue
        net = (res[i, 3] - res[i, 2]) * qty - ch[i]
        out.append(dict(und=und, day=D.d, side=sides[i], strike=float(strikes[i]), er=int(er[i]), xr=int(xr[i]),
                        E=float(res[i, 0]), X=float(res[i, 1]), rE=float(res[i, 2]), rX=float(res[i, 3]), qty=qty,
                        why=E.REASONS[int(rs[i])], net=float(net), gross=float((res[i, 1] - res[i, 0]) * qty),
                        peak=float(res[i, 4]), ret=float(res[i, 1] / res[i, 0] - 1.0)))
    return out


def take(und: str, D, cands: list, ex: E.Exits, max_per_side=None):
    """hunt/r51/common.py `take` with scope 'side' on R64's engine: one open position a side, `max_per_side` fills a
    side a day. cands: dicts with er, side, strike (time order)."""
    if not cands:
        return []
    res = run_day(und, D, [c["side"] for c in cands], [c["strike"] for c in cands], [c["er"] for c in cands], ex,
                  [c.get("fx", -1) for c in cands])
    out, busy, n_side = [], {}, {}
    for c, r in zip(cands, res):
        if r is None:
            continue
        if c["er"] <= busy.get(c["side"], -1):
            continue
        if max_per_side is not None and n_side.get(c["side"], 0) >= max_per_side:
            continue
        busy[c["side"]] = r["xr"]
        n_side[c["side"]] = n_side.get(c["side"], 0) + 1
        r.update({k: v for k, v in c.items() if k not in r and k != "fx"})
        out.append(r)
    return out


def twin_nets(und: str, D, sides, strikes, rows, ex: E.Exits, forced=None) -> np.ndarray:
    """Net of each twin entry (0 when it cannot fill): same engine, same charges."""
    st = E.stack(und, D)
    ci = [st.idx(s, k) if k is not None else -1 for s, k in zip(sides, strikes)]
    res, er, xr, rs, ch, qty = price(und, D, ci, rows, ex, forced)
    ok = rs != E.R_NOFILL
    rE, rX = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
    return np.where(ok, (rX - rE) * qty - ch, 0.0)
