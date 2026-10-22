"""R104 engine (PREREG.md): once the index is k x S from its 09:20 close (S = the 09:20 ATM straddle), buy the ATM option
on the side of the move; exits on the index (XI) or the premium (XP). Pure functions on an R51 store Day (idx (4, 375),
atm (375,), con {(side, strike): (5, 375) O H L C V}). Decisions read minute closes up to the decision minute only;
fills take the next minute's open. Stale minutes (no print) are marked with Black-Scholes at the last print's implied
volatility, floored at intrinsic (PREREG "Stale prices").
"""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r103_sell"))
import r103engine as E  # noqa: E402
import r103stale as ST  # noqa: E402

ANCHOR_ROW = 5                    # 09:20 close
FIRST_ROW, LAST_TRIG_ROW = 6, 315  # triggers 09:21 .. 14:30
KS = (0.5, 1.0)
EXITS = ("XI", "XP")
XI_BACK, XI_ON, XI_SAFETY = 0.25, 0.5, 0.5      # stop at (k - 0.25) S, target at (k + 0.5) S, safety at 50% of entry
XP_STOP, XP_ARM, XP_KEEP = 0.70, 1.30, 1.10
GEO_ON, GEO_BACK = 0.5, 0.25                     # continuation counter: +0.5 S before -0.25 S (random walk: 1/3)


def anchor(D, und: str):
    """(I0, S, K0) at 09:20, or None."""
    i0 = D.idx[3, ANCHOR_ROW]
    if not np.isfinite(i0):
        return None
    k0 = D.atm[ANCHOR_ROW] if D.atm is not None and np.isfinite(D.atm[ANCHOR_ROW]) else math.floor(i0 / E.STEP[und] + 0.5) * E.STEP[und]
    ce, pe = D.con.get(("C", float(k0))), D.con.get(("P", float(k0)))
    if ce is None or pe is None:
        return None
    c, p = ce[3, ANCHOR_ROW], pe[3, ANCHOR_ROW]
    if not (np.isfinite(c) and np.isfinite(p)) or c <= 0 or p <= 0:
        return None
    return float(i0), float(c + p), float(k0)


def trigger(D, i0: float, s: float, k: float, last: int = LAST_TRIG_ROW) -> tuple[int, int] | None:
    """(row, direction +1/-1) of the first minute close in rows FIRST_ROW..last with |index - I0| >= k S."""
    c = D.idx[3, FIRST_ROW: last + 1]
    hit = np.flatnonzero(np.isfinite(c) & (np.abs(c - i0) >= k * s))
    if not len(hit):
        return None
    r = FIRST_ROW + int(hit[0])
    return r, (1 if D.idx[3, r] > i0 else -1)


def strike_at(D, und: str, r: int) -> float:
    a = D.atm[r] if D.atm is not None and np.isfinite(D.atm[r]) else np.nan
    if not np.isfinite(a):
        a = math.floor(D.idx[3, r] / E.STEP[und] + 0.5) * E.STEP[und]
    return float(a)


def marks(D, con: np.ndarray, side: str, k: float, d: date, expiry: date | None, r0: int, r1: int) -> np.ndarray:
    """Marks for minutes r0..r1: the close where the contract printed; else Black-Scholes at the last print's IV with
    the index at that minute, floored at intrinsic (the last close when no IV can be solved)."""
    out = np.empty(r1 - r0 + 1)
    last_r, vol = -1, None
    for j, m in enumerate(range(r0, r1 + 1)):
        c = con[3, m]
        spot = D.idx[3, m]
        if np.isfinite(c):
            out[j] = c
            last_r, vol = m, None
            continue
        if last_r < 0:
            ok = np.flatnonzero(np.isfinite(con[3, : m + 1]))
            if not len(ok):
                out[j] = np.nan
                continue
            last_r = int(ok[-1])
        p0 = float(con[3, last_r])
        intr = max(spot - k, 0.0) if side == "C" else max(k - spot, 0.0)
        if not np.isfinite(spot):
            out[j] = out[j - 1] if j else p0
            continue
        if vol is None and expiry is not None and np.isfinite(D.idx[3, last_r]):
            vol = ST.implied_vol(p0, float(D.idx[3, last_r]), k, ST.years_left(d, expiry, last_r), side == "C") or 0.0
        if vol:
            out[j] = max(ST.bs(float(spot), k, ST.years_left(d, expiry, m), vol, side == "C"), intr, E.TICK)
        else:
            out[j] = max(p0, intr)
    return out


def exit_fill(D, con, side, k, d, expiry, xr: int, stop: bool, q: int, mult: float) -> float:
    """Sell at minute xr: the traded open (up to 5 minutes later), else the model mark with thin slippage."""
    if np.isfinite(con[0, xr: xr + E.FILL_LOOK + 1]).any():
        p, kk = E.fill_px(con, xr)
        return E.slip(p, False, E.vol5_lots(con, kk, q), mult * (2.0 if stop else 1.0))
    m = marks(D, con, side, k, d, expiry, xr, xr)[0]
    return E.slip(m, False, 0.0, mult * (2.0 if stop else 1.0))


def geometry(D, i0: float, s: float, r: int, sgn: int, k: float, sq: int) -> int | None:
    """1 if the index went GEO_ON x S further in the trade's direction before coming GEO_BACK x S back (minute closes
    after r, to sq), 0 if back first, None if neither."""
    base = D.idx[3, r]
    for m in range(r + 1, sq + 1):
        v = D.idx[3, m]
        if not np.isfinite(v):
            continue
        x = sgn * (v - base)
        if x >= GEO_ON * s:
            return 1
        if x <= -GEO_BACK * s:
            return 0
    return None


def run(D, und: str, d: date, k: float, exit_: str, expiry_day: bool, expiry: date | None, mult: float = 1.0,
        force: tuple[int, int] | None = None, fade: bool = False, exit_at: int | None = None) -> dict | None:
    """One trade. force=(row, dir) replaces the trigger (random-time twin); fade=True buys the OPPOSITE option at the
    same minute; exit_at forces the exit decision minute (the fade twin holds over the follow trade's minutes)."""
    a = anchor(D, und)
    if a is None:
        return None
    i0, s, _ = a
    sq = E.SQ_ROW_EXPIRY if expiry_day else E.SQ_ROW
    t = force or trigger(D, i0, s, k)
    if t is None:
        return None
    r, sgn = t
    er = r + 1
    if er >= sq:
        return None
    side = ("C" if sgn > 0 else "P") if not fade else ("P" if sgn > 0 else "C")
    kk = strike_at(D, und, r)
    con = D.con.get((side, kk))
    if con is None or not np.isfinite(con[0, er: er + E.FILL_LOOK + 1]).any():
        return None
    q = E.lot(und, d)
    p_in, fr = E.fill_px(con, er)
    entry = E.slip(p_in, True, E.vol5_lots(con, fr, q), mult)
    m = marks(D, con, side, kk, d, expiry, er, sq)
    why, xr = "time", sq
    if exit_at is not None:
        why, xr = "twin", min(exit_at, sq)
    else:
        armed = False
        for j in range(0, len(m) - 1):                  # decisions at er..sq-1, fills next minute
            row = er + j
            v = m[j]
            if not np.isfinite(v):
                continue
            if exit_ == "XI":
                x = D.idx[3, row]
                dist = sgn * (x - i0) if np.isfinite(x) else np.nan
                if v <= XI_SAFETY * entry:
                    why, xr = "safety", row + 1
                    break
                if np.isfinite(dist) and dist <= (k - XI_BACK) * s:
                    why, xr = "stop", row + 1
                    break
                if np.isfinite(dist) and dist >= (k + XI_ON) * s:
                    why, xr = "target", row + 1
                    break
            else:
                if v <= XP_STOP * entry:
                    why, xr = "stop", row + 1
                    break
                if v >= XP_ARM * entry:
                    armed = True
                elif armed and v <= XP_KEEP * entry:
                    why, xr = "lock", row + 1
                    break
    x_px = exit_fill(D, con, side, kk, d, expiry, xr, why in ("stop", "safety"), q, mult)
    ch = E.charges(entry, q, True, d) + E.charges(x_px, q, False, d)
    gross = (x_px - entry) * q
    return dict(und=und, day=d, k=k, exit=exit_, expiry=expiry_day, side=side, strike=kk, dir=sgn, s=round(s, 2), i0=i0,
                trig=r, er=er, xr=xr, why=why, entry=entry, exitpx=x_px, lot=q, margin=round(entry * q, 2),
                credit=round(-entry * q, 2), max_loss=round(entry * q, 2), gross=round(gross, 2), charges=round(ch, 2),
                net=round(gross - ch, 2), geo=geometry(D, i0, s, r, sgn, k, sq) if force is None and not fade else None)
