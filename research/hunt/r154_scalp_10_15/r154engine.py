"""R154 engine: one scalp trade on the ONE contract bought, with R106's numba simulator (r106engine.simulate).

PREREG section 2.
  Reading A (premium points): a resting limit at real fill + P and a resting stop at real fill - S on the premium; the
      simulator fills the limit at its price and the stop with R17's stop fill (a gap through it at the minute's open);
      a stop and a target in the same minute count as the stop (the simulator checks the low first).
  Reading B (index points): from the index level at the fill (the index open of the fill minute, else the previous
      close), the first minute whose index high / low reaches +P in the trade's favour or S against marks the exit; the
      option is sold at the NEXT minute's open (a market sale, R17 fill). Stop first when both are seen in one minute.
  Both: a time stop T minutes after the fill (sold at the close of the T-th minute) and the square-off (15:15; 15:05 on
  expiry days).
Every level comes from the trade's own fill or the index up to that minute, so nothing looks ahead.
"""
from __future__ import annotations

import numpy as np
from numba import njit

import r154cfg as K
import r154data as DATA

TICK = 0.05
NO_STEPS = np.zeros((0, 2))
WHY = {1: "stop", 3: "target", 4: "time", 5: "squareoff", 6: "forced", 7: "missing"}


def sq_row(D) -> int:
    return K.SQ_EXPIRY if D.expiry else K.SQ


def tick_up(x: float) -> float:
    return float(np.ceil(round(x / TICK, 6)) * TICK)


def tick_down(x: float) -> float:
    return float(np.floor(round(x / TICK, 6)) * TICK)


@njit(cache=True)
def index_trigger(idx, ef, tend, sq, side_sgn, tgt_pts, stop_pts):
    """(row of the first minute in ef..min(tend, sq-1) whose index high / low reaches the target or the stop, kind:
    1 stop / 3 target / 0 none, reference level). Stop first when both fall in one minute."""
    ref = idx[0, ef]
    if not (ref == ref):
        ref = np.nan
        for j in range(ef - 1, -1, -1):
            if idx[3, j] == idx[3, j]:
                ref = idx[3, j]
                break
    if not (ref == ref):
        return -1, 0, ref
    last = min(tend, sq - 1)
    for m in range(ef, last + 1):
        hi, lo = idx[1, m], idx[2, m]
        if not (hi == hi and lo == lo):
            continue
        if side_sgn > 0:
            if lo <= ref - stop_pts:
                return m, 1, ref
            if hi >= ref + tgt_pts:
                return m, 3, ref
        else:
            if hi >= ref + stop_pts:
                return m, 1, ref
            if lo <= ref - tgt_pts:
                return m, 3, ref
    return -1, 0, ref


def charges(und: str, a: float, b: float, qty: float, d) -> float:
    E = DATA.mods()["E"]
    return float(E.charges(und, np.array([a]), np.array([b]), qty, d)[0])


def price(und: str, D, er: int, side: str, rule: dict, lots: int = 1, strike: float | None = None) -> dict | None:
    """Price one scalp entered at row `er` (the 1-ITM strike from the minute before unless `strike` is given) under
    `rule` (K.RULES value) for `lots` lots. Returns the trade dict or None (no contract / no fill / too late)."""
    M = DATA.mods()
    E, X, R = M["E"], M["X"], M["r51cfg"]
    sq = sq_row(D)
    if er >= sq:
        return None
    k = strike if strike is not None else E.strike_for(D, und, side, er - 1, K.ITM)
    if k is None:
        return None
    st = E.stack(und, D)
    ci = st.idx(side, k)
    if ci < 0:
        return None
    C = st.S[ci]
    qty = float(R.lot(und, D.d)) * lots
    raw, real, ef, tks = X.entry_info(C, int(er), int(sq), qty)
    if ef < 0:
        return None
    T = int(rule["tmax"])
    fx, kind, ref = -1, 0, np.nan
    if rule["reading"] == "A":
        tgt_abs = tick_up(real + rule["target"])
        stop_abs = tick_down(real - rule["stop"])
        if stop_abs <= 0:
            stop_abs = TICK
    else:
        tgt_abs, stop_abs = -1.0, -1.0
        m, kind, ref = index_trigger(D.idx, int(ef), int(ef + T - 1), int(sq), 1 if side == "C" else -1,
                                     float(rule["target"]), float(rule["stop"]))
        if m >= 0:
            fx = m + 1
    a, b, rE, rX, r0, r1, r = X.simulate(C, int(er), int(sq), int(fx), -1.0, -1.0, 0.0, 0.0, 0.0, NO_STEPS, T, -1.0,
                                         -1.0, TICK, qty, 0.0, float(stop_abs), float(tgt_abs), *E.SLIP)
    if r == E.R_NOFILL:
        return None
    why = WHY.get(int(r), "?")
    if why == "forced":
        why = "target" if kind == 3 else "stop"
    ch = charges(und, rE, rX, qty, D.d)
    net = (rX - rE) * qty - ch
    return dict(und=und, day=D.d, side=side, strike=float(k), er=int(r0), xr=int(r1), E=float(a), X=float(b),
                rE=float(rE), rX=float(rX), qty=qty, lots=lots, why=why, net=float(net), charges=float(ch),
                pts=float(rX - rE), tmax=T, sq=int(sq), expiry=bool(D.expiry), idx_ref=float(ref))


def twin_nets(t: dict, D) -> np.ndarray:
    """Net of selling trade `t` instead at the open of each row strictly after its fill and no later than the end of its
    time-stop window (fill + T, or the square-off if sooner): the random-exit twin's choices (PREREG gate 2)."""
    M = DATA.mods()
    E, X = M["E"], M["X"]
    hi = min(t["er"] + t["tmax"], t["sq"])
    rows = np.arange(t["er"] + 1, hi + 1, dtype=np.int64)
    if not len(rows):
        return np.array([t["net"]])
    st = E.stack(t["und"], D)
    C = st.S[st.idx(t["side"], t["strike"])]
    rE, rX, ok = X._sim_rows(C, int(t["er"]), int(t["sq"]), rows, -1.0, -1.0, 0.0, 0.0, 0.0, NO_STEPS, -1, -1.0, -1.0,
                             TICK, t["qty"], 0.0, *E.SLIP)
    net = (rX - rE) * t["qty"] - E.charges(t["und"], rE, rX, t["qty"], t["day"])
    return np.where(ok, net, t["net"])
