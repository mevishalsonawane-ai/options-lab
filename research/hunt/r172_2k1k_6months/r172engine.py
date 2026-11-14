"""R172 engine: R152's N-lot pricing (R106's simulator) with Boss's 14 Nov rule, as the app implements it:

    P&L of the whole position before charges = (option price - entry fill) x qty
    target: a resting limit at entry fill + 2,000 / qty (rounded up to the tick), filled at its price
    stop:   a stop at entry fill - 1,000 / qty (rounded up to the tick), acting only when above the arm's own -15% stop
            (the tighter one); sold as every stop here (R17 stop fill, a gap fills at the minute's open)
    every other exit of the arm stays; whichever comes first

Differences from r152engine.price, and only these: the levels are before charges (r152 solved them net of charges), and
the exit reasons 'target2k' / 'stop1k' mark the rupee levels. With no rupee level the pricing is R152's (and so R101's)
line for line (test_r172.py). Every level is solved from the trade's own real entry fill (known at the fill): no look-ahead.

Callers import r172cfg first (stores and paths).
"""
from __future__ import annotations

import numpy as np

import r172cfg as K
import r106engine as X
import engine as E                      # hunt/r51/engine.py
import r51cfg as K51
import r64engine as G


def _ceil_tick(x: float) -> float:
    return float(np.ceil(round(x / K.TICK, 6)) * K.TICK)


def target_px(real: float, qty: float, amount: float = K.TARGET) -> float:
    """The limit premium whose sale makes at least +amount before charges: ceil_tick(real + amount / qty)."""
    return _ceil_tick(real + amount / qty)


def stop_px(real: float, qty: float, amount: float = K.STOP) -> float:
    """The stop premium at which the position is down `amount` before charges, rounded UP to the tick (so the planned loss
    is at most `amount`); -1 when the whole position is worth less than `amount`."""
    s = _ceil_tick(real - amount / qty)
    return s if s > 0 else -1.0


def levels(raw: float, real: float, qty: float, ex: E.Exits, on: bool) -> tuple:
    """(tgt_abs, stop_abs, stop_f, kind) for the simulator. on=False: the arm's own exits only. The Rs 1,000 stop replaces
    the arm's fractional stop only when it is the higher (tighter) premium; otherwise the arm's own stop stays."""
    if not on:
        return -1.0, -1.0, ex.stop, "own"
    t = target_px(real, qty)
    s = stop_px(real, qty)
    own = raw * (1.0 - ex.stop) if ex.stop >= 0 else -1.0
    if s > 0 and s > own:
        return t, s, -1.0, "rupee"
    return t, -1.0, ex.stop, "own"


def price(und: str, D, cands: list, ex: E.Exits, on: bool, lots: int = 1) -> list:
    """Price one day's candidates (dicts with side, strike, er[, fx]) for `lots` lots, with (on=True) or without Boss's
    +Rs 2,000 / -Rs 1,000. Trade dicts (None = no fill) as r152engine.price."""
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
        tgt_abs, stop_abs, stop_f, kind = levels(raw, real, qty, ex, on)
        a, b, rE, rX, r0, r1, r = X.simulate(C, int(c["er"]), int(sq), int(fx if fx is not None else -1), stop_f,
                                             ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail, stp, ex.tmax, ex.need,
                                             ex.close_stop, K51.TICK, qty, be, stop_abs, tgt_abs, *E.SLIP)
        if r == E.R_NOFILL:
            out.append(None)
            continue
        why = E.REASONS[int(r)]
        if why == "stop" and kind == "rupee":
            why = "stop1k"
        elif why == "target" and tgt_abs > 0 and abs(b - tgt_abs) < 1e-9:
            why = "target2k"
        net = (rX - rE) * qty - ch(rE, rX)
        out.append(dict(und=und, day=D.d, side=c["side"], strike=float(c["strike"]), er=int(r0), xr=int(r1), E=float(a),
                        X=float(b), rE=float(rE), rX=float(rX), qty=qty, lots=lots, why=why, net=float(net),
                        gross=float((rX - rE) * qty), tgt_px=float(tgt_abs), stop_px=float(stop_abs), stop_kind=kind))
    return out
