"""R44 trade simulator on 5-minute option bars (PREREG.md "Instrument and execution").

One ATM option bought at the open of the entry bar; stop -25% (bar low), target +50% (bar high), the stop first when
both are in one bar; the stepped lock (1b/1c) set by a bar's high applies from the next bar; time exit at the close of
the 12th bar; square-off at the open of the square-off bar. Raw fills (Boss costs) and real fills
(research/obuy/costs.py Fills("liq") rule: +-5 bps market, -10 bps stops, half-spread 1/2/4 ticks by the contract's
volume in the previous 5-minute bar, stops 2x; limit target at its price) are both returned.
"""
from __future__ import annotations

from datetime import date

import numpy as np
from numba import njit

import r44cfg as K
from obuy.costs import Costs

R_NOFILL, R_STOP, R_LOCK, R_TARGET, R_TIME, R_SQ, R_MISSING = 0, 1, 2, 3, 4, 5, 6
REASONS = {R_NOFILL: "nofill", R_STOP: "stop", R_LOCK: "lock", R_TARGET: "target", R_TIME: "time", R_SQ: "squareoff",
           R_MISSING: "missing"}
LOCK_PEAK = np.array([s[0] for s in K.LOCK_STEPS])
LOCK_GAIN = np.array([s[1] for s in K.LOCK_STEPS])
COSTS = Costs("dated")
EPOCH = date(1970, 1, 1).toordinal()


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
def _vol(v, b, qty):
    if b < 0 or not (v[b] == v[b]):
        return 0.0
    return v[b] / qty


@njit(cache=True)
def simulate(P, e, sq, use_lock, use_tgt, stop_frac, tgt_frac, tmax, lock_peak, lock_gain, tick, qty):
    """P: (5, nbars) O H L C V of the held contract. e: entry bar; sq: square-off bar (exit at its open).
    Returns (raw_entry, raw_exit, real_entry, real_exit, exit_bar, reason). raw_entry NaN = no fill."""
    o, h, l, c, v = P[0], P[1], P[2], P[3], P[4]
    nan = np.nan
    if e >= sq or not (o[e] == o[e]) or o[e] <= 0:
        return nan, nan, nan, nan, -1, R_NOFILL
    E = float(o[e])
    real_e = _r2(E * 1.0005) + _ticks(_vol(v, e - 1, qty)) * tick
    stop = E * (1.0 - stop_frac)
    tgt = E * (1.0 + tgt_frac) if use_tgt else 1e18
    lock = -1.0
    peak = E
    last = E
    last_t = min(e + tmax - 1, sq - 1)
    m = e
    while m <= last_t:
        if not (l[m] == l[m]):                       # no trade in the bar: nothing triggers
            if m == e + tmax - 1:
                break
            m += 1
            continue
        lvl = stop if stop >= lock else lock
        if l[m] <= lvl:                              # resting stop / lock (a gap through fills at the open)
            px = o[m] if (o[m] == o[m] and o[m] < lvl) else lvl
            rs = R_LOCK if lock >= stop else R_STOP
            real_x = max(_r2(px * 0.999) - 2.0 * _ticks(_vol(v, m - 1, qty)) * tick, 0.0)
            return E, px, real_e, real_x, m, rs
        if h[m] >= tgt:                              # limit target at its price
            return E, tgt, real_e, tgt, m, R_TARGET
        last = c[m]
        if use_lock:
            if h[m] > peak:
                peak = h[m]
            g = peak / E - 1.0
            for j in range(len(lock_peak)):
                if g >= lock_peak[j] - 1e-12:
                    lv = E * (1.0 + lock_gain[j])
                    if lv > lock:
                        lock = lv
        if m == e + tmax - 1:                        # time exit at this bar's close
            real_x = max(_r2(c[m] * 0.9995) - _ticks(_vol(v, m, qty)) * tick, 0.0)
            return E, c[m], real_e, real_x, m, R_TIME
        m += 1
    if e + tmax - 1 < sq:                            # the 12th bar had no trade: its last known close (counted)
        r = e + tmax - 1
        real_x = max(_r2(last * 0.9995) - _ticks(_vol(v, r, qty)) * tick, 0.0)
        return E, last, real_e, real_x, r, R_MISSING
    px = o[sq] if (sq < len(o) and o[sq] == o[sq]) else nan
    rs = R_SQ
    if not (px == px):                               # no open at the square-off bar: the last close (counted)
        px = last
        rs = R_MISSING
    real_x = max(_r2(px * 0.9995) - _ticks(_vol(v, sq - 1, qty)) * tick, 0.0)
    return E, px, real_e, real_x, sq, rs


@njit(cache=True)
def simulate_many(Pall, pi, e, sq, use_lock, use_tgt, stop_frac, tgt_frac, tmax, lock_peak, lock_gain, tick, qty):
    n = len(pi)
    out = np.empty((n, 4))
    xb = np.empty(n, np.int32)
    rs = np.empty(n, np.int32)
    for i in range(n):
        a, b, c_, d_, x, r = simulate(Pall[pi[i]], e[i], sq, use_lock, use_tgt, stop_frac, tgt_frac, tmax,
                                      lock_peak, lock_gain, tick, qty)
        out[i, 0] = a
        out[i, 1] = b
        out[i, 2] = c_
        out[i, 3] = d_
        xb[i] = x
        rs[i] = r
    return out, xb, rs


def run_one(P, e, sq, strat, und):
    """One trade (for tests): P (5, nbars)."""
    _, _, lock, tgt = K.STRATS[strat]
    return simulate(np.asarray(P, np.float64), int(e), int(sq), lock, tgt, K.STOP_FRAC, K.TGT_FRAC, K.TMAX_BARS,
                    LOCK_PEAK, LOCK_GAIN, K.tick(und), float(K.LOT[und]))


def run_batch(D, pi, e, strat):
    _, _, lock, tgt = K.STRATS[strat]
    und = D.und
    return simulate_many(D.P, np.asarray(pi, np.int64), np.asarray(e, np.int64), K.grid(und)["sq"], lock, tgt,
                         K.STOP_FRAC, K.TGT_FRAC, K.TMAX_BARS, LOCK_PEAK, LOCK_GAIN, K.tick(und), float(K.LOT[und]))


# ------------------------------------------------------------------------------------------------ costs
def mcx_charges(buy_px, sell_px, qty):
    """Zerodha MCX option charges for one round trip (research/hunt/nn_crude/common.py zerodha_costs 'opt')."""
    tb = np.asarray(buy_px, float) * qty
    ts = np.asarray(sell_px, float) * qty
    brok = 40.0
    ctt, exch, stamp = 0.0005 * ts, 0.000418 * (tb + ts), 0.00003 * tb
    sebi = (tb + ts) * 10 / 1e7
    gst = 0.18 * (brok + exch + sebi)
    return brok + ctt + exch + sebi + stamp + gst


def charges(und, entry, exit_px, qty, day_ord):
    entry, exit_px = np.asarray(entry, float), np.asarray(exit_px, float)
    qty = np.broadcast_to(np.asarray(qty, float), entry.shape)
    if K.is_mcx(und):
        return mcx_charges(entry, exit_px, qty)
    dn = np.broadcast_to(np.asarray(day_ord, np.int64) - EPOCH, entry.shape)
    bse = und in K.BSE
    return COSTS.charge(True, entry, qty, dn, bse) + COSTS.charge(False, exit_px, qty, dn, bse)


def nets(und, res, day_ord, qty=None):
    """(boss_net, real_net) per trade from simulate_many's (n, 4) output; qty defaults to the spec lot."""
    q = float(K.LOT[und]) if qty is None else np.asarray(qty, float)
    boss = (res[:, 1] - res[:, 0]) * q - K.BOSS_COST["mcx" if K.is_mcx(und) else "nse"]
    real = (res[:, 3] - res[:, 2]) * q - charges(und, res[:, 2], res[:, 3], q, day_ord)
    return boss, real
