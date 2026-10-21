"""R29 trade simulator (PREREG.md "Trade simulation"): one option bought at the next minute's open, exits X1-X4,
checked minute by minute on the option's own 1-minute bars. numba; no look-ahead beyond the trade's own path.

Fills follow research/obuy/costs.py Fills("liq"): market orders +-5 bps, stop sells -10 bps, plus a half-spread of 1 / 2 /
4 ticks (>= 20 lots / some / none traded in the 5 minutes before the order; stops 2x). Charges: Costs("app").
"""
from __future__ import annotations

import numpy as np
from numba import njit

import r29cfg as K
from obuy.costs import Costs

TICK = 0.05
COSTS = Costs("app")

# exit families: (stop_frac, lock_arm_rs, lock_gap_rs, trail_arm_frac, trail_gap_frac, target_frac, time_max_min)
INF = 1e18
EXITS = {
    "X1": (0.30, 1000.0, 500.0, INF, 0.0, INF, 60),
    "X2": (0.25, INF, 0.0, 0.20, 0.15, INF, 90),
    "X3": (0.20, INF, 0.0, INF, 0.0, 0.40, 30),
    "X4": (0.40, INF, 0.0, INF, 0.0, INF, 30),
}
EXIT_NAMES = tuple(EXITS)
# reasons
R_STOP, R_LOCK, R_TARGET, R_TIME, R_SQ, R_NOFILL, R_MISSING = 1, 2, 3, 4, 5, 0, 6
CUT_LOOK = 120


@njit(cache=True)
def _ticks(v5_lots):
    if v5_lots >= 20.0:
        return 1.0
    if v5_lots > 0.0:
        return 2.0
    return 4.0


@njit(cache=True)
def _v5(v, t, qty):
    s = 0.0
    for j in range(max(0, t - 4), t + 1):
        if v[j] == v[j]:
            s += v[j]
    return s / qty


@njit(cache=True)
def _r2(x):
    return np.round(x * 100.0) / 100.0


@njit(cache=True)
def simulate(P, t_sig, sq, stop_frac, lock_arm, lock_gap, trail_arm, trail_gap, tgt_frac, tmax, qty, spread_mult):
    """P: (5, 375) O H L C V of the held strike. t_sig: signal row; entry at the open of t_sig + 1. sq: square-off row
    (exit at its open). Returns (entry, exit, exit_row, reason, post_high) where post_high is the best high in the
    CUT_LOOK minutes after the exit (before sq), for 'big winners cut short'. entry NaN = no fill."""
    o, h, l, c, v = P[0], P[1], P[2], P[3], P[4]
    e = t_sig + 1
    nan = np.nan
    if e >= sq or not (o[e] == o[e]) or o[e] <= 0:
        return nan, nan, -1, R_NOFILL, nan
    tk = _ticks(_v5(v, t_sig, qty)) * spread_mult
    entry = _r2(o[e] * 1.0005) + tk * TICK
    stop = entry * (1.0 - stop_frac)
    tgt = entry * (1.0 + tgt_frac) if tgt_frac < 1e17 else 1e18
    peak = entry
    last = o[e]
    exit_px = nan
    exit_row = -1
    reason = R_SQ
    m = e
    while m < sq:
        if not (l[m] == l[m]):                          # no bar: carry, no exit triggered
            m += 1
            continue
        if l[m] <= stop:                                # resting stop
            px = o[m] if o[m] <= stop else stop
            tk2 = _ticks(_v5(v, m - 1, qty)) * spread_mult * 2.0
            exit_px = max(_r2(px * 0.999) - tk2 * TICK, 0.0)
            exit_row = m
            reason = R_LOCK if stop > entry * (1.0 - stop_frac) + 1e-9 else R_STOP
            break
        if h[m] >= tgt + TICK - 1e-9:                   # limit target, a tick through
            exit_px = tgt
            exit_row = m
            reason = R_TARGET
            break
        last = c[m]
        if c[m] > peak:
            peak = c[m]
        if (peak - entry) * qty >= lock_arm - 1e-9:
            lv = peak - lock_gap / qty
            if lv > stop:
                stop = lv
        if peak >= entry * (1.0 + trail_arm) - 1e-9:
            lv = peak * (1.0 - trail_gap)
            if lv > stop:
                stop = lv
        if m - e + 1 >= tmax:                           # time stop: market sell at the next open
            reason = R_TIME
            m += 1
            break
        m += 1
    if exit_row < 0:
        r = min(m, sq)
        px = o[r] if (r < len(o) and o[r] == o[r]) else nan
        if not (px == px):                              # no open at the exit minute: the last close (counted)
            px = last
            reason = R_MISSING
        tk2 = _ticks(_v5(v, r - 1, qty)) * spread_mult
        exit_px = max(_r2(px * 0.9995) - tk2 * TICK, 0.0)
        exit_row = r
    ph = nan
    for j in range(exit_row + 1, min(exit_row + 1 + CUT_LOOK, sq)):
        if h[j] == h[j] and (not (ph == ph) or h[j] > ph):
            ph = h[j]
    return entry, exit_px, exit_row, reason, ph


def charges(entry, exit_px, qty, bse=False):
    entry, exit_px, qty = np.asarray(entry, float), np.asarray(exit_px, float), np.asarray(qty, float)
    ok = np.isfinite(entry)
    e = np.where(ok, entry, 0.0)
    x = np.where(ok & np.isfinite(exit_px), exit_px, 0.0)
    return COSTS.charge(True, e, qty, bse=bse) + COSTS.charge(False, x, qty, bse=bse)


def net(entry, exit_px, qty, bse=False):
    entry, exit_px = np.asarray(entry, float), np.asarray(exit_px, float)
    return (exit_px - entry) * qty - charges(entry, exit_px, qty, bse)


def run(P, t_sig, sq, exit_name, qty, spread_mult=1.0):
    a = EXITS[exit_name]
    return simulate(P, int(t_sig), int(sq), a[0], a[1], a[2], a[3], a[4], a[5], a[6], float(qty), float(spread_mult))


def strike_for(op, d: int, t: int, side: str, itm: int, step: float):
    """The strike bought at signal row t: ATM (offset 0 at t) or `itm` strikes in the money. None if no ATM at t."""
    a = op.atm.get(d)
    if a is None:
        return None
    k = a[t]
    if not np.isfinite(k):
        lo = max(0, t - 3)
        w = a[lo:t + 1]
        w = w[np.isfinite(w)]
        if not len(w):
            return None
        k = w[-1]
    return float(k - itm * step) if side == "C" else float(k + itm * step)


def sq_row(op, d: int) -> int:
    return K.SQ_EXPIRY if d in op.expiry else K.SQ_NORMAL
