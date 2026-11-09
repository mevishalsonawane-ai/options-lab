"""R152 engine: R106's rupee-level pricing (r106engine.simulate, target_price, stop_price) for a position of N lots.

PREREG section 2. Differences from r106engine.price, and only these:
  - qty = lots x the lot size of the day (charges and R17 slippage of the whole quantity);
  - the rupee stop does NOT replace the arm's -15% stop: the nearer (higher premium) of the two acts (Boss, 7 Nov:
    "its own stops ... stay, whichever comes first"); a trade whose rupee stop is not nearer is priced exactly as B0;
  - each trade records which stop was nearer ('stop_kind' 'rupee' / 'own') and a stop exit by the rupee level is
    labelled why = 'stop2k'.
Every level is solved from the trade's own real entry fill (known at the fill), so nothing looks ahead.

Callers import r152cfg first (it points r51cfg at the clean stores).
"""
from __future__ import annotations

import numpy as np

import r152cfg  # noqa: F401  (paths, R51_CLEAN=1)
import r106engine as X
import engine as E                      # hunt/r51/engine.py
import r51cfg as K51
import r64engine as G


def levels(raw: float, real: float, qty: float, ex: E.Exits, tgt, stop, ch, ticks_e: float) -> tuple:
    """(tgt_abs, stop_abs, stop_f, kind) for simulate: the rupee target premium (-1 none), the stop premium that
    replaces the fractional stop (-1 = keep the arm's own), the fractional stop to pass, and which stop is nearer."""
    tgt_abs = X.target_price(real, qty, tgt, ch) if tgt is not None else -1.0
    if stop is None:
        return tgt_abs, -1.0, ex.stop, "own"
    s_r = X.stop_price(real, qty, stop, ch, ticks_e, E.SLIP[2], E.SLIP[3])
    own = raw * (1.0 - ex.stop) if ex.stop >= 0 else -1.0
    if s_r > 0 and s_r > own:
        return tgt_abs, s_r, -1.0, "rupee"
    return tgt_abs, -1.0, ex.stop, "own"


def price(und: str, D, cands: list, ex: E.Exits, tgt=None, stop=None, lots: int = 1) -> list:
    """Price candidates (dicts with side, strike, er[, fx]) of one day: the arm's exits `ex` plus a rupee target `tgt`
    and / or a rupee stop `stop` (None = none), for `lots` lots. Trade dicts (None = no fill) as r106engine.price plus
    'lots', 'stop_kind'."""
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
        tgt_abs, stop_abs, stop_f, kind = levels(raw, real, qty, ex, tgt, stop, ch, tks)
        a, b, rE, rX, r0, r1, r = X.simulate(C, int(c["er"]), int(sq), int(fx if fx is not None else -1), stop_f,
                                             ex.target, ex.lock_trig, ex.lock_keep, ex.lock_trail, stp, ex.tmax, ex.need,
                                             ex.close_stop, K51.TICK, qty, be, stop_abs, tgt_abs, *E.SLIP)
        if r == E.R_NOFILL:
            out.append(None)
            continue
        why = E.REASONS[int(r)]
        if why == "stop" and kind == "rupee":
            why = "stop2k"
        net = (rX - rE) * qty - ch(rE, rX)
        out.append(dict(und=und, day=D.d, side=c["side"], strike=float(c["strike"]), er=int(r0), xr=int(r1), E=float(a),
                        X=float(b), rE=float(rE), rX=float(rX), qty=qty, lots=lots, why=why, net=float(net),
                        tgt_px=float(tgt_abs), stop_px=float(stop_abs), stop_kind=kind))
    return out


def forced_exit_nets(und: str, D, trade: dict, ex: E.Exits, rows: np.ndarray, lots: int = 1, fx0: int = -1) -> np.ndarray:
    """r106engine.forced_exit_nets for `lots` lots: the net of selling `trade` (from its signal entry row `er_sig`) at the
    open of each row in `rows`, the arm's own exits still active before it (the random-exit twin, PREREG section 5)."""
    st = E.stack(und, D)
    C = st.S[st.idx(trade["side"], trade["strike"])]
    qty = float(K51.lot(und, D.d)) * lots
    sq = E.sq_row(D, ex)
    rows = np.asarray(rows, np.int64)
    fxs = rows if fx0 < 0 else np.minimum(rows, fx0)
    be = 0.0
    if G.needs_be(ex):
        _, real, _, _ = X.entry_info(C, trade["er_sig"], sq, qty)
        be = real + float(E.charges(und, np.array([real]), np.array([real]), qty, D.d)[0]) / qty
    rE, rX, ok = X._sim_rows(C, int(trade["er_sig"]), int(sq), fxs, ex.stop, ex.target, ex.lock_trig, ex.lock_keep,
                             ex.lock_trail, ex.steps_arr(), ex.tmax, ex.need, ex.close_stop, K51.TICK, qty, be, *E.SLIP)
    net = (rX - rE) * qty - E.charges(und, rE, rX, qty, D.d)
    return np.where(ok, net, 0.0)
