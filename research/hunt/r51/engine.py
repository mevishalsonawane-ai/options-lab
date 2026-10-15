"""R51 trade engine: one HELD option contract, 1-minute bars, real fills and dated charges (PREREG.md "Measurement").

Entry: the contract's open at the entry row (the minute after the signal bar closes: next-bar fill); if that minute has
no trade, the first traded minute up to 2 minutes later, else no fill. Exits, checked minute by minute in this order:
  1. resting stop / lock on the minute's LOW (a gap through it fills at the open: the worse price), stop first;
  2. limit target on the minute's HIGH, at its price;
  3. an option-close stop (R41 style) on the minute's close, sold at the next open;
  4. the time stop (exit at the close of the last allowed minute; optionally only if not up `need` by then);
  5. a forced exit row from the strategy (an index rule: sold at that row's open);
  6. square-off at the open of the square-off row (15:10, 15:05 on expiry day, or the rule's own).
The lock and peak are set by a minute's high and apply from the next minute. A minute with no trade triggers nothing.
Real fills (R17 "liq" model, research/obuy/costs.py Fills('liq')): buys +5 bps + a half-spread of 1 / 2 / 4 ticks
(>= 20 lots / some / no volume in the 5 minutes before), market sells -5 bps - the half-spread, stop sells -10 bps - 2x
the half-spread, limit targets at their price. Charges: Costs('dated'), the rates in force on the trade date.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np
from numba import njit

import r51cfg as K
from obuy.costs import Costs

R_NOFILL, R_STOP, R_LOCK, R_TARGET, R_TIME, R_SQ, R_FORCED, R_MISSING, R_CSTOP = 0, 1, 2, 3, 4, 5, 6, 7, 8
REASONS = {R_NOFILL: "nofill", R_STOP: "stop", R_LOCK: "lock", R_TARGET: "target", R_TIME: "time", R_SQ: "squareoff",
           R_FORCED: "rule_exit", R_MISSING: "missing", R_CSTOP: "close_stop"}
COSTS = Costs("dated")
EPOCH = date(1970, 1, 1).toordinal()
NOLOCK = np.zeros((0, 2))


@dataclass(frozen=True)
class Exits:
    stop: float = -1.0            # option stop, fraction below the fill (0.25 = -25%); <0 off
    target: float = -1.0          # limit target, fraction above the fill; <0 off
    lock_trig: float = -1.0       # Boss lock: once +Rs lock_trig a lot ...
    lock_keep: float = 0.0        # ... keep at least +Rs lock_keep ...
    lock_trail: float = 0.0       # ... and trail Rs lock_trail under the best price (0 = no trail)
    steps: tuple = ()             # stepped lock ((peak gain, locked gain), ...) as fractions
    tmax: int = -1                # time stop: minutes held (exit at the close of minute e+tmax-1); <0 off
    need: float = -1.0            # time stop only if not up `need` (fraction) at that minute; <0 always
    close_stop: float = -1.0      # option-close stop: a minute close <= (1-close_stop) x fill, sold next open
    sq_row: int = -1              # own square-off row; <0 = 15:10 (15:05 on expiry day)

    def steps_arr(self):
        return np.array(self.steps, dtype=np.float64).reshape(-1, 2) if self.steps else NOLOCK


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
def _vol5(v, r, qty):
    s = 0.0
    for j in range(max(r - 4, 0), r + 1):
        if j >= 0 and v[j] == v[j]:
            s += v[j]
    return s / qty


@njit(cache=True)
def simulate(C, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty):
    """C: (5, 375) O H L C V of the held contract. e: entry row; sq: square-off row (exit at its open); fx: forced
    exit row (<0 none). Returns (raw_entry, raw_exit, real_entry, real_exit, entry_row, exit_row, reason)."""
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
    E = float(o[ef])
    real_e = _r2(E * 1.0005) + _ticks(_vol5(v, ef - 1, qty)) * tick
    stop = E * (1.0 - stop_f) if stop_f >= 0 else -1.0
    tgt = E * (1.0 + tgt_f) if tgt_f >= 0 else 1e18
    lock = -1.0
    peak = E
    last = E
    tend = ef + tmax - 1 if tmax > 0 else 10 ** 6
    end = sq
    if fx >= 0 and fx < end:
        end = fx
    m = ef
    pend_cstop = False
    while m < end:
        if pend_cstop:                                # close stop seen last minute: sell at this open
            if o[m] == o[m]:
                real_x = max(_r2(o[m] * 0.999) - 2.0 * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
                return E, float(o[m]), real_e, real_x, ef, m, 8
        if not (l[m] == l[m]):
            if m == tend:
                break
            m += 1
            continue
        lvl = stop if stop >= lock else lock
        if lvl > 0 and l[m] <= lvl:
            px = o[m] if (o[m] == o[m] and o[m] < lvl) else lvl
            rs = 2 if lock >= stop and lock > 0 else 1
            real_x = max(_r2(px * 0.999) - 2.0 * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
            return E, px, real_e, real_x, ef, m, rs
        if h[m] >= tgt:
            return E, tgt, real_e, tgt, ef, m, 3
        last = c[m]
        if h[m] > peak:
            peak = h[m]
        if lk_trig > 0 and (peak - E) * qty >= lk_trig:
            lv = E + lk_keep / qty
            if lk_trail > 0:
                lv = max(lv, peak - lk_trail / qty)
            if lv > lock:
                lock = lv
        if steps.shape[0] > 0:
            g = peak / E - 1.0
            for j in range(steps.shape[0]):
                if g >= steps[j, 0] - 1e-12:
                    lv = E * (1.0 + steps[j, 1])
                    if lv > lock:
                        lock = lv
        if cstop >= 0 and c[m] <= E * (1.0 - cstop):
            pend_cstop = True
        if m == tend:
            if need < 0 or c[m] < E * (1.0 + need):
                real_x = max(_r2(c[m] * 0.9995) - _ticks(_vol5(v, m, qty)) * tick, 0.0)
                return E, float(c[m]), real_e, real_x, ef, m, 4
        m += 1
    if m == tend and m < end:                         # the time-stop minute had no trade: its last known close
        real_x = max(_r2(last * 0.9995) - _ticks(_vol5(v, m, qty)) * tick, 0.0)
        return E, last, real_e, real_x, ef, m, 7
    xr = end
    rs = 6 if (fx >= 0 and fx < sq) else 5
    px = o[xr] if (xr < n and o[xr] == o[xr]) else nan
    if not (px == px):                                # no trade in the exit minute: the next one within 3, else last
        for j in range(xr + 1, min(xr + 4, n)):
            if o[j] == o[j]:
                px = o[j]
                break
    if not (px == px):
        px = last
        rs = 7
    real_x = max(_r2(px * 0.9995) - _ticks(_vol5(v, xr - 1, qty)) * tick, 0.0)
    return E, px, real_e, real_x, ef, xr, rs


@njit(cache=True)
def simulate_many(S, ci, e, sq, fx, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty):
    """S: (ncon, 5, 375) contracts of one day; ci, e, fx: per trade. Returns (n, 4) prices, entry/exit rows, reasons."""
    n = len(ci)
    out = np.full((n, 4), np.nan)
    er = np.full(n, -1, np.int32)
    xr = np.full(n, -1, np.int32)
    rs = np.zeros(n, np.int32)
    for i in range(n):
        if ci[i] < 0:
            continue
        a, b, c_, d_, r0, r1, r = simulate(S[ci[i]], e[i], sq, fx[i], stop_f, tgt_f, lk_trig, lk_keep, lk_trail,
                                           steps, tmax, need, cstop, tick, qty)
        out[i, 0], out[i, 1], out[i, 2], out[i, 3] = a, b, c_, d_
        er[i], xr[i], rs[i] = r0, r1, r
    return out, er, xr, rs


def charges(und: str, entry, exit_px, qty, day: date):
    entry, exit_px = np.atleast_1d(np.asarray(entry, float)), np.atleast_1d(np.asarray(exit_px, float))
    q = np.broadcast_to(np.asarray(qty, float), entry.shape)
    dn = np.full(entry.shape, day.toordinal() - EPOCH, dtype=np.int64)
    bse = und in K.BSE
    return COSTS.charge(True, entry, q, dn, bse) + COSTS.charge(False, exit_px, q, dn, bse)


def sq_row(day_obj, ex: Exits) -> int:
    if ex.sq_row >= 0:
        return min(ex.sq_row, K.SQ_ROW_EXPIRY) if day_obj.expiry else ex.sq_row
    return K.SQ_ROW_EXPIRY if day_obj.expiry else K.SQ_ROW


def strike_for(D, und: str, side: str, row: int, money: int = 0) -> float | None:
    """Strike `money` steps in the money (negative = out of the money) from the ATM strike at `row` (the rolling
    offset-0 strike of that minute, else the index close rounded to the step)."""
    step = K.STEP[und]
    k = D.atm[row] if D.atm is not None and 0 <= row < K.NROWS else np.nan
    if not np.isfinite(k):
        px = D.idx[3, row] if 0 <= row < K.NROWS else np.nan
        if not np.isfinite(px):
            return None
        k = round(px / step) * step
    return float(k - money * step) if side == "C" else float(k + money * step)


class DayStack:
    """A day's contracts stacked for numba: S (ncon, 5, 375) and (side, strike) -> index."""

    def __init__(self, D):
        keys = list(D.con.keys())
        self.key = {k: i for i, k in enumerate(keys)}
        self.S = np.stack([D.con[k] for k in keys]).astype(np.float64) if keys else np.zeros((0, 5, K.NROWS))

    def idx(self, side, strike):
        return self.key.get((side, float(strike)), -1) if strike is not None else -1


_STACKS: dict = {}


def stack(und: str, D) -> DayStack:
    k = (und, D.d)
    if k not in _STACKS:
        if len(_STACKS) > 1200:
            _STACKS.clear()
        _STACKS[k] = DayStack(D)
    return _STACKS[k]


def run_day(und: str, D, sides, strikes, entries, ex: Exits, forced=None):
    """Simulate several trades of one day. sides: 'C'/'P'; strikes floats; entries: entry rows; forced: exit rows or
    -1. Returns a list of dicts (one per trade, None if no fill)."""
    st = stack(und, D)
    n = len(entries)
    ci = np.array([st.idx(s, k) for s, k in zip(sides, strikes)], dtype=np.int64)
    fx = np.full(n, -1, np.int64) if forced is None else np.asarray(forced, np.int64)
    qty = float(K.lot(und, D.d))
    sq = sq_row(D, ex)
    res, er, xr, rs = simulate_many(st.S, ci, np.asarray(entries, np.int64), sq, fx, ex.stop, ex.target, ex.lock_trig,
                                    ex.lock_keep, ex.lock_trail, ex.steps_arr(), ex.tmax, ex.need, ex.close_stop,
                                    K.TICK, qty)
    out = []
    ch = charges(und, np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3]), qty, D.d) if n else np.zeros(0)
    for i in range(n):
        if rs[i] == R_NOFILL:
            out.append(None)
            continue
        net = (res[i, 3] - res[i, 2]) * qty - ch[i]
        out.append(dict(und=und, day=D.d, side=sides[i], strike=float(strikes[i]), er=int(er[i]), xr=int(xr[i]),
                        E=float(res[i, 0]), X=float(res[i, 1]), rE=float(res[i, 2]), rX=float(res[i, 3]), qty=qty,
                        why=REASONS[int(rs[i])], net=float(net), gross=float((res[i, 1] - res[i, 0]) * qty)))
    return out


def twin_totals(und: str, days: dict, plan: list, ex: Exits, n_twins: int, rng, money: int = 0,
                forced_fn=None) -> np.ndarray:
    """Random-entry twins for one index. plan: [(day, side, count, first_row, last_row)] - same days, same number of
    entries a day and side, uniform entry rows in the rule's window, same strike rule and exits. forced_fn(D, side,
    rows) -> forced exit rows (the rule's index exits from the twin's own entry), or None. Returns (n_twins,) nets."""
    tot = np.zeros(n_twins)
    for d, side, cnt, first, last in plan:
        D = days[d]
        if last < first:
            continue
        rows = rng.integers(first, last + 1, size=(n_twins, cnt))
        flat = rows.ravel()
        st = stack(und, D)
        strikes = [strike_for(D, und, side, int(r) - 1, money) for r in flat]
        ci = np.array([st.idx(side, k) for k in strikes], dtype=np.int64)
        fx = np.full(len(flat), -1, np.int64) if forced_fn is None else np.asarray(forced_fn(D, side, flat), np.int64)
        qty = float(K.lot(und, d))
        res, er, xr, rs = simulate_many(st.S, ci, flat.astype(np.int64), sq_row(D, ex), fx, ex.stop, ex.target,
                                        ex.lock_trig, ex.lock_keep, ex.lock_trail, ex.steps_arr(), ex.tmax, ex.need,
                                        ex.close_stop, K.TICK, qty)
        ok = rs != R_NOFILL
        rE, rX = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
        net = np.where(ok, (rX - rE) * qty - charges(und, rE, rX, qty, d), 0.0)
        tot += net.reshape(n_twins, cnt).sum(axis=1)
    return tot
