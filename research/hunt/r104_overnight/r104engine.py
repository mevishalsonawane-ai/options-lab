"""R104 engine (PREREG.md): a 4-leg defined-risk short structure sold at 15:15 on day d0, held to d1.

Reuses R103's engine (research/hunt/r103_sell/r103engine.py) for strikes, slippage, charges, lots and Black-Scholes.
Days are R51 store Days (idx, atm, con). A leg's value at a minute of d1 is its close if it printed within STALE_AGE
minutes, else Black-Scholes at that minute's index level with the IV of its own last print (on d1, or on d0 if it has
not printed on d1 yet). Decisions read closes up to the decision minute; fills take the next minute's open.
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r103_sell"))
import r103engine as E  # noqa: E402

DECIDE_ROW = 359                  # 15:14 close on d0
ENTRY_ROW = 360                   # 15:15 open
O1_ROW = 5                        # 09:20 open on d1
EXITS = {"O1": dict(stop=0.5, out="open920"), "O2": dict(stop=0.5, out="close")}


def leg_value(D0, D1, side: str, k: float, r: int, d0: date, d1: date, expiry: date) -> float:
    """The leg's value at minute r of d1 (see module doc)."""
    key = (side, float(k))
    c1 = D1.con.get(key)
    if c1 is not None:
        px, pr = E.last_print(c1, r)
        if pr >= 0:
            if r - pr <= E.STALE_AGE:
                return px
            iv = E.last_iv(D1, side, k, pr, d1, expiry)
            if np.isfinite(iv):
                return float(E.BS.price(E.spot_at(D1, r), k, E.t_years(d1, r, expiry), iv, side == "C", E.RATE))
            return px
    c0 = D0.con.get(key)
    if c0 is None:
        return float("nan")
    px, pr = E.last_print(c0, E.NROWS - 1)
    if pr < 0:
        return float("nan")
    iv = E.last_iv(D0, side, k, pr, d0, expiry)
    if not np.isfinite(iv):
        return px
    return float(E.BS.price(E.spot_at(D1, r), k, E.t_years(d1, r, expiry), iv, side == "C", E.RATE))


def fill_d1(D0, D1, side: str, k: float, r: int, d0: date, d1: date, expiry: date) -> float:
    """A market order on d1 sent for minute r: the minute's open, the next open within FILL_LOOK, else the value."""
    c1 = D1.con.get((side, float(k)))
    if c1 is not None:
        for m in range(r, min(E.NROWS, r + E.FILL_LOOK + 1)):
            o = c1[0, m]
            if np.isfinite(o) and o > 0:
                return float(o)
    return leg_value(D0, D1, side, k, r, d0, d1, expiry)


def run(D0, D1, und: str, d0: date, d1: date, struct: str, exit_: str, expiry: date, d1_is_expiry: bool,
        mult: float = 1.0) -> dict | None:
    st = E.setup(D0, und, struct, DECIDE_ROW)
    if st is None:
        return None
    q = E.lot(und, d0)
    entry, ch = [], 0.0
    for side, k, sg in st.legs:
        con = D0.con[(side, float(k))]
        p, fr = E.fill_px(con, ENTRY_ROW)
        if fr == ENTRY_ROW and not (np.isfinite(con[0, ENTRY_ROW]) and con[0, ENTRY_ROW] > 0):
            p = E.repaired(D0, side, k, ENTRY_ROW, d0, expiry)
        if not np.isfinite(p) or p <= 0:
            return None
        f = E.slip(p, sg > 0, E.vol5_lots(con, ENTRY_ROW, q), mult)
        entry.append(f)
        ch += E.charges(f, q, sg > 0, d0)
    credit = sum(-sg * f for (_, _, sg), f in zip(st.legs, entry)) * q
    if credit <= 0:
        return None
    sq = (E.SQ_ROW_EXPIRY if d1_is_expiry else E.SQ_ROW) if EXITS[exit_]["out"] == "close" else O1_ROW
    sgs = np.array([l[2] for l in st.legs], float)
    why, xr = "time", sq
    for r in range(0, sq):                                   # decisions on d1's minute closes 09:15 .. sq-1
        vals = np.array([leg_value(D0, D1, s, k, r, d0, d1, expiry) for s, k, _ in st.legs])
        if not np.all(np.isfinite(vals)):
            continue
        pnl = float(((vals - np.array(entry)) * sgs).sum() * q)
        if pnl <= -EXITS[exit_]["stop"] * credit:
            why, xr = "stop", r + 1
            break
    exit_px = []
    for (side, k, s_), f in zip(st.legs, entry):
        p = fill_d1(D0, D1, side, k, xr, d0, d1, expiry)
        if not np.isfinite(p) or p < 0:
            return None
        c1 = D1.con.get((side, float(k)))
        v = E.vol5_lots(c1, xr, q) if c1 is not None else 0.0
        x = E.slip(p, s_ < 0, v, mult * (2.0 if why == "stop" else 1.0))
        exit_px.append(x)
        ch += E.charges(x, q, s_ < 0, d1)
    gross = sum(s_ * (x - f) for (_, _, s_), f, x in zip(st.legs, entry, exit_px)) * q
    wing = max(abs(st.legs[2][1] - st.legs[0][1]), abs(st.legs[1][1] - st.legs[3][1]))
    return dict(und=und, day=d0, d1=d1, struct=struct, exit=exit_, friday=d0.weekday() == 4, k0=st.k0, s=round(st.s, 2),
                lot=q, legs=[(a, b, c) for a, b, c in st.legs], entry=entry, exitpx=exit_px, xr=xr, why=why,
                credit=round(credit, 2), max_loss=round(wing * q - credit, 2), gross=round(gross, 2), charges=round(ch, 2),
                net=round(gross - ch, 2))
