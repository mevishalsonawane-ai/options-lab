"""R62b trade simulation (PREREG sections 4, 6, 7): buy the 1-ITM nearest-expiry option on an index signal, hold that
one contract (R51's engine), sell it at the next minute's open after the index reaches the stop or target level, at the
time exit or at the square-off; and the random-entry twins.

Signal on the underlying, trade the option: every stop, target and time exit here is read from the index; the option
premium is only the price paid and received. Adapted from R62's r62sim.py (branch claude/r62-volume-profile-smc) with
R62b's time exit and risk:reward filter.
"""
from __future__ import annotations

import numpy as np

import r62bcfg as K

K51 = K.point_r51()
import engine as E  # noqa: E402  (R51's engine)
from numba import njit  # noqa: E402

WHY = {0: "squareoff", 1: "stop", 2: "target", 3: "time"}


@njit(cache=True)
def scan_exit(h, l, f0, fend, side, stop, tgt):
    """First minute in [f0, fend) whose low (long) reaches the stop or high reaches the target, on the index.
    Returns (the row the option is sold at: that minute + 1, capped at fend; why 1 stop / 2 target / 0 none).
    A minute reaching both counts as the stop. Missing minutes trigger nothing."""
    for m in range(f0, fend):
        hm, lm = h[m], l[m]
        if not (hm == hm and lm == lm):
            continue
        if side > 0:
            if lm <= stop:
                return min(m + 1, fend), 1
            if hm >= tgt:
                return min(m + 1, fend), 2
        else:
            if hm >= stop:
                return min(m + 1, fend), 1
            if lm <= tgt:
                return min(m + 1, fend), 2
    return fend, 0


def exit_row(h, l, ef: int, sq: int, hold: int, side: int, stop: float, tgt: float) -> tuple[int, int]:
    """(row the option is sold at, why 0 square-off / 1 stop / 2 target / 3 time). The time exit sells at the open of
    row ef + hold when that is before the square-off."""
    fend = min(sq, ef + hold)
    fx, why = scan_exit(h, l, ef, fend, side, stop, tgt)
    if why == 0 and fend < sq:
        return fend, 3
    return fx, why


def _first_print(con, e, sq):
    for j in range(e, min(e + 3, sq)):
        if np.isfinite(con[0, j]) and con[0, j] > 0:
            return j
    return -1


def _last(c, r):
    seg = c[:max(r, 1)]
    seg = seg[np.isfinite(seg)]
    return float(seg[-1]) if len(seg) else np.nan


def index_trade(und: str, D, side: int, s: int, stop: float, tgt: float, hold: int,
                rr_min: float | None = None) -> dict | None:
    """One trade: buy the 1-ITM CE (side +1) / PE (-1) chosen at signal row s, filled from s + 1 (the first print in
    that minute and the next 2). None when it cannot fill, when the index at the fill is already beyond the stop or the
    target, or (rr_min) when the reward:risk at the fill is below rr_min."""
    sq = E.sq_row(D, E.Exits(sq_row=K.SQ_IDX))
    sd = "C" if side > 0 else "P"
    k = E.strike_for(D, und, sd, s, K.ITM_MONEY)
    if k is None:
        return None
    con = D.contract(sd, k)
    if con is None:
        return None
    ef = _first_print(con, s + 1, sq)
    if ef < 0:
        return None
    ref = D.idx[0, ef] if np.isfinite(D.idx[0, ef]) else D.idx[3, ef - 1]
    if not np.isfinite(ref):
        return None
    risk, reward = side * (ref - stop), side * (tgt - ref)
    if not (risk > 0 and reward > 0):
        return None
    if rr_min is not None and reward / risk < rr_min:
        return None
    fx, why = exit_row(D.idx[1], D.idx[2], ef, sq, hold, side, stop, tgt)
    qty = float(K51.lot(und, D.d))
    C64 = con.astype(np.float64)
    out = {}
    for tag, slip, mult in (("", E.SLIP1, 1.0), ("2", E.SLIP2, 2.0)):
        res = E.simulate(C64, ef, sq, fx if fx < sq else -1, -1.0, -1.0, -1.0, 0.0, 0.0, E.NOLOCK, -1, -1.0, -1.0,
                         K51.TICK, qty, *slip)
        if res[6] == E.R_NOFILL:
            return None
        rE, rX = res[2], res[3]
        ch = float(E.charges(und, rE, rX, qty, D.d)[0]) * mult
        out["net" + tag] = (rX - rE) * qty - ch
        if not tag:
            out.update(E=float(res[0]), X=float(res[1]), rE=float(rE), rX=float(rX), er=int(res[4]), xr=int(res[5]),
                       why_opt=E.REASONS[int(res[6])], charges=ch)
    xr = out["xr"]
    px_x = D.idx[0, xr] if xr < K.NROWS and np.isfinite(D.idx[0, xr]) else _last(D.idx[3], xr)
    out.update(und=und, day=D.d, side=side, strike=float(k), qty=qty, ref=float(ref), stop=float(stop), tgt=float(tgt),
               why=WHY[why] if fx < sq else "squareoff", r_mult=float(side * (px_x - ref) / risk),
               risk_pts=float(risk), rr=float(reward / risk), hold=hold)
    return out


@njit(cache=True)
def _twin_index(S, ci_row, io, ih, il, ic, rows, side, sp, tp, sq, hold, tick, qty, fb, fs, fst, tk):
    """Twins of one trade: rows (n, tries) random fill rows. Returns (n, 2) real entry / exit prices (NaN: no fill)."""
    n, tries = rows.shape
    out = np.full((n, 2), np.nan)
    for i in range(n):
        for k in range(tries):
            e = rows[i, k]
            c = ci_row[e - 1]
            if c < 0:
                continue
            con = S[c]
            ef = -1
            for j in range(e, min(e + 3, sq)):
                if con[0, j] == con[0, j] and con[0, j] > 0:
                    ef = j
                    break
            if ef < 0:
                continue
            ref = io[ef] if io[ef] == io[ef] else ic[ef - 1]
            if not (ref == ref):
                continue
            stop = ref * (1.0 - side * sp)
            tgt = ref * (1.0 + side * tp)
            fend = min(sq, ef + hold)
            fx, why = scan_exit(ih, il, ef, fend, side, stop, tgt)
            if why == 0 and fend < sq:
                fx = fend
            r = E.simulate(con, ef, sq, fx if fx < sq else -1, -1.0, -1.0, -1.0, 0.0, 0.0, np.zeros((0, 2)), -1, -1.0,
                           -1.0, tick, qty, fb, fs, fst, tk)
            if r[6] == 0:
                continue
            out[i, 0], out[i, 1] = r[2], r[3]
            break
    return out


def twin_index_nets(t: dict, D, n: int, rng, tries: int = 5) -> np.ndarray:
    """Random-entry twins of one trade (PREREG 7): same index, session and side, a random fill row in 09:25-14:30,
    1-ITM at the row before, the same stop / target distances in % of the index, the same time exit and square-off."""
    und, side = t["und"], int(t["side"])
    sd = "C" if side > 0 else "P"
    st = E.stack(und, D)
    sq = E.sq_row(D, E.Exits(sq_row=K.SQ_IDX))
    ci_row = np.full(K.NROWS, -1, np.int64)
    for r in range(K.row(9, 24), K.row(14, 30)):
        ci_row[r] = st.idx(sd, E.strike_for(D, und, sd, r, K.ITM_MONEY))
    sp = side * (t["ref"] - t["stop"]) / t["ref"]
    tp = side * (t["tgt"] - t["ref"]) / t["ref"]
    rows = rng.integers(K.row(9, 25), K.row(14, 30) + 1, size=(n, tries))
    qty = float(K51.lot(und, D.d))
    res = _twin_index(st.S, ci_row, D.idx[0], D.idx[1], D.idx[2], D.idx[3], rows.astype(np.int64), side, sp, tp, sq,
                      int(t["hold"]), K51.TICK, qty, *E.SLIP1)
    ok = np.isfinite(res[:, 0])
    rE, rX = np.nan_to_num(res[:, 0]), np.nan_to_num(res[:, 1])
    ch = E.charges(und, rE, rX, qty, D.d)
    return np.where(ok, (rX - rE) * qty - ch, 0.0)
