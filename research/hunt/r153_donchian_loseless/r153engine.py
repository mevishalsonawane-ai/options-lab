"""R153 engine: R152's pricing (R106's rupee levels for N lots, the nearer of the Rs 2,000 stop and -15% acts) plus
LOCK2K (PREREG section 2): once the premium has reached the price whose sale nets +Rs 2,000, a lock rests at the price
whose sale nets +Rs 1,000. Both prices are solved from the trade's own real entry fill and charges (known at the fill),
and passed to R64's simulator as its rupee lock (trigger and keep in premium x qty above the raw fill), so nothing looks
ahead. A lock exit is a stop sale (R17 stop slippage); it is labelled why = 'lock' (the ladder's own locks too).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r152_donchian_2k"))
import r152cfg  # noqa: E402,F401  (paths, R51_CLEAN=1)
import r152engine as Y  # noqa: E402
import r106engine as X  # noqa: E402
import engine as E  # noqa: E402
import r51cfg as K51  # noqa: E402
import r64engine as G  # noqa: E402

LOCK_TRIG = 2000.0
LOCK_KEEP = 1000.0


def lock_levels(raw: float, real: float, qty: float, ch, trig: float = LOCK_TRIG, keep: float = LOCK_KEEP) -> tuple:
    """(lk_trig, lk_keep) in rupees above the raw fill for the simulator: (X_trig - raw) x qty and (X_keep - raw) x qty,
    where X_* are the premiums whose sale nets +trig / +keep after charges."""
    xt = X.target_price(real, qty, trig, ch)
    xk = X.target_price(real, qty, keep, ch)
    return (xt - raw) * qty, (xk - raw) * qty


def price(und: str, D, cands: list, ex: E.Exits, stop=None, lock: bool = False, lots: int = 1) -> list:
    """Price candidates of one day with the arm's exits, an optional Rs `stop` (R152's nearer-stop rule) and LOCK2K."""
    if not lock:
        return Y.price(und, D, cands, ex, None, stop, lots)
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
        raw, real, ef, tks = X.entry_info(C, c["er"], sq, qty)
        if ef < 0:
            out.append(None)
            continue
        be = real + ch(real, real) / qty if G.needs_be(ex) else 0.0
        _, stop_abs, stop_f, kind = Y.levels(raw, real, qty, ex, None, stop, ch, tks)
        lt, lk = lock_levels(raw, real, qty, ch)
        a, b, rE, rX, r0, r1, r = X.simulate(C, int(c["er"]), int(sq), int(fx if fx is not None else -1), stop_f,
                                             ex.target, lt, lk, 0.0, stp, ex.tmax, ex.need, ex.close_stop, K51.TICK,
                                             qty, be, stop_abs, -1.0, *E.SLIP)
        if r == E.R_NOFILL:
            out.append(None)
            continue
        why = E.REASONS[int(r)]
        if why == "stop" and kind == "rupee":
            why = "stop2k"
        net = (rX - rE) * qty - ch(rE, rX)
        out.append(dict(und=und, day=D.d, side=c["side"], strike=float(c["strike"]), er=int(r0), xr=int(r1), E=float(a),
                        X=float(b), rE=float(rE), rX=float(rX), qty=qty, lots=lots, why=why, net=float(net),
                        stop_px=float(stop_abs), stop_kind=kind, lock_trig=float(lt), lock_keep=float(lk)))
    return out
