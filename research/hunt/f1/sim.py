"""F1 arm simulator: buy one option at the open after a decision minute and manage it with Boss's exits (PREREG 3).

X1 "quick": stop -15% (resting SL-M), lock from +Rs 1,000 a lot (stop Rs 500 under the best price), time stop at 30
minutes unless +5%, square-off 15:10 (15:05 on expiry day). X2 "runner": the same without the time stop.

Inside a bar (conservative): the stop (the higher of the hard stop and the lock) is checked first, against the bar's
low; the lock uses the best price BEFORE the bar; a bar where the contract did not trade checks nothing. Fills:
`research/obuy/costs.py` mode 'liq' (5 bps market, 10 bps stop, plus 1/2/4 ticks by the last 5 minutes' volume in
lots, double for a stop). Charges: Costs('app') per order (today's rates; BSE for SENSEX).
"""
from __future__ import annotations

import numpy as np
from numba import njit

from research.obuy.costs import Costs

TICK = 0.05
COSTS = Costs("app")
R_NONE, R_STOP, R_LOCK, R_TIME, R_SQ, R_LAST = 0, 1, 2, 3, 4, 5
REASONS = {R_NONE: "no fill", R_STOP: "stop", R_LOCK: "lock", R_TIME: "time", R_SQ: "square-off",
           R_LAST: "last trade (strike left the chain)"}


@njit(cache=True)
def _ticks(v5_lots):
    if v5_lots >= 20.0:
        return 1.0
    if v5_lots > 0.0:
        return 2.0
    return 4.0


@njit(cache=True)
def _buy(p, v5):
    return round(p * 1.0005, 2) + _ticks(v5) * TICK


@njit(cache=True)
def _sell(p, v5, stop):
    if stop:
        x = round(p * 0.999, 2) - 2.0 * _ticks(v5) * TICK
    else:
        x = round(p * 0.9995, 2) - _ticks(v5) * TICK
    return max(x, 0.0)


@njit(cache=True)
def _v5(V, r, lot):
    s = 0.0
    for k in range(max(0, r - 5), r):
        if np.isfinite(V[k]):
            s += V[k]
    return s / lot


@njit(cache=True)
def simulate(O, H, L, C, V, t, sq, lot, time_stop, stop_pct, act_rs, give_rs, ts_min, ts_gain):
    """One trade. O..V: the contract's 1-minute bars (NaN where it did not trade). t: decision row. sq: square-off row.
    Returns (fill, exit_px, reason, entry_row, exit_row, best_after_exit): prices per unit after slippage."""
    n = len(O)
    e = -1
    for r in range(t + 1, min(t + 4, n)):
        if np.isfinite(O[r]) and O[r] > 0:
            e = r
            break
    if e < 0 or e >= sq:
        return np.nan, np.nan, R_NONE, -1, -1, np.nan
    P = O[e]
    F = _buy(P, _v5(V, e, lot))
    stop = np.floor(F * (1.0 - stop_pct) / TICK + 1e-9) * TICK
    act, give = act_rs / lot, give_rs / lot
    peak = P
    last = P
    xp, reason, xr = np.nan, R_NONE, -1
    ts_done = False
    for r in range(e, sq + 1):
        traded = np.isfinite(O[r]) and np.isfinite(L[r])
        if r == sq:
            if traded:
                xp, reason = _sell(O[r], _v5(V, r, lot), False), R_SQ
            else:
                xp, reason = _sell(last, 0.0, False), R_LAST
            xr = r
            break
        if not traded:
            continue
        trig = stop
        is_lock = False
        if peak - F >= act - 1e-9:
            lt = np.ceil((peak - give) / TICK - 1e-9) * TICK
            lt = min(lt, peak)
            if lt > trig:
                trig, is_lock = lt, True
        if L[r] <= trig:
            px = min(O[r], trig) if r > e else trig
            xp, reason, xr = _sell(px, _v5(V, r, lot), True), (R_LOCK if is_lock else R_STOP), r
            break
        if H[r] > peak:
            peak = H[r]
        last = C[r]
        if time_stop and not ts_done and r >= e + ts_min - 1:      # the first traded bar 30 minutes after the fill
            ts_done = True
            if C[r] < F * (1.0 + ts_gain):
                xp, reason, xr = _sell(C[r], _v5(V, r + 1, lot), False), R_TIME, r
                break
    best = np.nan
    for r in range(xr + 1, sq + 1):
        if np.isfinite(H[r]) and (np.isnan(best) or H[r] > best):
            best = H[r]
    return F, xp, reason, e, xr, best


def net_rs(fill, exit_px, lot, bse=False):
    """Net rupees a lot after charges (vectorised); NaN where there was no fill."""
    fill, exit_px = np.asarray(fill, float), np.asarray(exit_px, float)
    ok = np.isfinite(fill) & np.isfinite(exit_px)
    f, x = np.where(ok, fill, 0.0), np.where(ok, exit_px, 0.0)
    q = np.full(f.shape, float(lot))
    ch = COSTS.charge(True, f, q, None, bse) + COSTS.charge(False, x, q, None, bse)
    return np.where(ok, (x - f) * lot - ch, np.nan)
