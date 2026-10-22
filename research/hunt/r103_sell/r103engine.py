"""R103 engine (PREREG.md): price a 4-leg defined-risk short structure on one day's held contracts.

Pure: works on any object with `idx` (4, 375) index OHLC, `atm` (375,) rolling ATM strike and `con` {(side, strike):
(5, 375) O H L C V}, the R51 store's Day. Decisions read minute closes up to the decision minute only; fills take the
NEXT minute's open (look-ahead guard in tests/test_r103.py).
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
if str(ROOT / "research") not in sys.path:
    sys.path.insert(0, str(ROOT / "research"))

from obuy.costs import Costs  # noqa: E402

NROWS = 375
DECIDE_ROW = 5                    # 09:20 minute close
ENTRY_ROW = 6                     # 09:21 open
SQ_ROW = 355                      # 15:10
SQ_ROW_EXPIRY = 350               # 15:05 (the app's expiry square-off)
TICK = 0.05
BPS = 5                           # 'liq' fills: 5 bps on every order ...
THIN_LOTS = 20.0                  # ... plus 1 tick (>= 20 lots traded in the 5 minutes before), 2 (some), 4 (none)
MAX_STEPS = 10
FILL_LOOK = 5                     # minutes to look ahead for an open when the fill minute has none
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
COSTS = Costs("dated")

# The lot in force on a day: research/obuy/config.py LOT_SCHEDULE, plus the Jan 2026 BANKNIFTY change to 30
# (NSE/FAOP/70616, 3 Oct 2025; first contracts expiring Jan 2026), which that table does not carry yet.
LOTS = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
}

STRUCTS = {                       # (short distance a x S, wing distance w x S beyond the short strike)
    "IF": (0.0, 1.0),
    "IFW": (0.0, 1.5),
    "IC": (0.5, 1.0),
}
EXITS = {                         # stop at a loss of `stop` x credit; lock: armed at +arm x credit, out at +keep x credit
    "X1": dict(stop=0.5, arm=None, keep=None, target=None),
    "X2": dict(stop=0.5, arm=0.4, keep=0.2, target=0.6),
}


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def steps_of(x: float, step: float) -> int:
    """A distance in points -> whole strike steps, rounded half up, clipped to [1, MAX_STEPS]."""
    if not np.isfinite(x):
        return 0
    return int(min(MAX_STEPS, max(1, math.floor(x / step + 0.5))))


def last_close(con: np.ndarray, r: int) -> float:
    """The contract's last close at or before minute r (NaN if none that day)."""
    c = con[3, : r + 1]
    ok = np.flatnonzero(np.isfinite(c))
    return float(c[ok[-1]]) if len(ok) else float("nan")


def fill_px(con: np.ndarray, r: int) -> tuple[float, int]:
    """(price, minute) of a market order sent for minute r: that minute's open, else the next minute with an open (up to
    FILL_LOOK later), else the last close at or before r."""
    for k in range(r, min(NROWS, r + FILL_LOOK + 1)):
        o = con[0, k]
        if np.isfinite(o) and o > 0:
            return float(o), k
    return last_close(con, r), r


def vol5_lots(con: np.ndarray, r: int, lot_: int) -> float:
    v = con[4, max(0, r - 5): r]
    v = v[np.isfinite(v)]
    return float(v.sum()) / lot_ if lot_ else 0.0


def ticks(v_lots: float) -> float:
    return 1.0 if v_lots >= THIN_LOTS else (2.0 if v_lots > 0 else 4.0)


def slip(p: float, buy: bool, v_lots: float, mult: float = 1.0) -> float:
    """'liq' fills: 5 bps plus 1-4 ticks against us (x mult for the stress test)."""
    t = ticks(v_lots) * mult * TICK
    return round(p * (1 + BPS / 1e4) + t, 2) if buy else round(max(p * (1 - BPS / 1e4) - t, 0.0), 2)


def charges(price: float, qty: int, buy: bool, d: date) -> float:
    dn = np.array([(d - date(1970, 1, 1)).days])
    return float(COSTS.charge(buy, np.array([price]), np.array([qty]), dn, False)[0])


@dataclass
class Setup:
    k0: float                     # ATM strike at the decision minute
    s: float                      # straddle close (C + P) at the decision minute, points
    legs: tuple                   # ((side, strike, sign), ...); sign -1 = sold, +1 = bought


def setup(D, und: str, struct: str, decide: int = DECIDE_ROW) -> Setup | None:
    """The structure's legs from the decision minute's ATM and straddle; None when a leg's contract is missing."""
    step = STEP[und]
    k0 = D.atm[decide] if D.atm is not None and np.isfinite(D.atm[decide]) else np.nan
    if not np.isfinite(k0):
        px = D.idx[3, decide]
        if not np.isfinite(px):
            return None
        k0 = math.floor(px / step + 0.5) * step
    ce, pe = D.con.get(("C", float(k0))), D.con.get(("P", float(k0)))
    if ce is None or pe is None:
        return None
    c, p = ce[3, decide], pe[3, decide]
    if not (np.isfinite(c) and np.isfinite(p)) or c <= 0 or p <= 0:
        return None
    s = float(c + p)
    a_x, w_x = STRUCTS[struct]
    a = 0 if a_x == 0 else steps_of(a_x * s, step)
    w = steps_of(w_x * s, step)
    if a + w > MAX_STEPS:
        w = MAX_STEPS - a
        if w < 1:
            return None
    legs = (("C", k0 + a * step, -1), ("P", k0 - a * step, -1), ("C", k0 + (a + w) * step, 1), ("P", k0 - (a + w) * step, 1))
    for side, k, _ in legs:
        con = D.con.get((side, float(k)))
        if con is None or not np.isfinite(last_close(con, decide)):
            return None
    return Setup(float(k0), s, legs)


def ffill_row(c: np.ndarray, start: float) -> np.ndarray:
    """Forward-fill NaNs in a 1-D array, NaNs before the first value taking `start`."""
    ok = np.isfinite(c)
    idx = np.where(ok, np.arange(len(c)), -1)
    np.maximum.accumulate(idx, out=idx)
    out = np.where(idx >= 0, c[np.clip(idx, 0, None)], start)
    return out


def marks(D, legs, r0: int, r1: int) -> np.ndarray:
    """(legs, minutes r0..r1) closes, each forward-filled from its last close at or before the minute."""
    out = np.empty((len(legs), r1 - r0 + 1))
    for i, (side, k, _) in enumerate(legs):
        con = D.con[(side, float(k))]
        out[i] = ffill_row(con[3, r0: r1 + 1].astype(float), last_close(con, r0))
    return out


def run(D, und: str, d: date, struct: str, exit_: str, expiry: bool, mult: float = 1.0, decide: int = DECIDE_ROW) -> dict | None:
    """One trade: entry orders at decide + 1, exits checked on each minute close, filled at the next minute's open."""
    st = setup(D, und, struct, decide)
    if st is None:
        return None
    q = lot(und, d)
    er = decide + 1
    sq = SQ_ROW_EXPIRY if expiry else SQ_ROW
    if er >= sq:
        return None
    entry, ch = [], 0.0
    for side, k, sg in st.legs:
        con = D.con[(side, float(k))]
        p, _ = fill_px(con, er)
        if not np.isfinite(p) or p <= 0:
            return None
        f = slip(p, sg > 0, vol5_lots(con, er, q), mult)
        entry.append(f)
        ch += charges(f, q, sg > 0, d)
    credit_pts = sum(-sg * f for (_, _, sg), f in zip(st.legs, entry))
    if credit_pts <= 0:
        return None
    credit = credit_pts * q
    m = marks(D, st.legs, er, sq)
    sg = np.array([l[2] for l in st.legs], float)
    pnl = ((m - np.array(entry)[:, None]) * sg[:, None]).sum(axis=0) * q        # gross, at each minute close er..sq
    ex = EXITS[exit_]
    why, xr = "time", sq
    armed = False
    for j in range(0, len(pnl) - 1):                                           # decisions at er..sq-1, fills next minute
        v = pnl[j]
        if v <= -ex["stop"] * credit:
            why, xr = "stop", er + j + 1
            break
        if ex["target"] is not None and v >= ex["target"] * credit:
            why, xr = "target", er + j + 1
            break
        if ex["arm"] is not None:
            if v >= ex["arm"] * credit:
                armed = True
            elif armed and v <= ex["keep"] * credit:
                why, xr = "lock", er + j + 1
                break
    exit_px = []
    for (side, k, s_), f in zip(st.legs, entry):
        con = D.con[(side, float(k))]
        p, _ = fill_px(con, xr)
        if not np.isfinite(p) or p < 0:
            p = last_close(con, xr)
        x = slip(p, s_ < 0, vol5_lots(con, xr, q), mult * (2.0 if why == "stop" else 1.0))
        exit_px.append(x)
        ch += charges(x, q, s_ < 0, d)
    gross = sum(s_ * (x - f) for (_, _, s_), f, x in zip(st.legs, entry, exit_px)) * q
    wing = max(abs(st.legs[2][1] - st.legs[0][1]), abs(st.legs[1][1] - st.legs[3][1]))
    return dict(und=und, day=d, struct=struct, exit=exit_, expiry=expiry, k0=st.k0, s=round(st.s, 2), lot=q,
                legs=[(a, b, c) for a, b, c in st.legs], entry=entry, exitpx=exit_px, er=er, xr=xr, why=why,
                credit=round(credit, 2), max_loss=round(wing * q - credit, 2), gross=round(gross, 2),
                charges=round(ch, 2), net=round(gross - ch, 2))
