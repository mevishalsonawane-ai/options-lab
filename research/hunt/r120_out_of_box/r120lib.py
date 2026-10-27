"""R120 engine (PREREG.md): five out-of-the-box expiry-day tests.

    Q1  R119's P1 iron fly at N lots (flat brokerage spread over N lots; N fitted on DESIGN only)
    Q2  R119's P5 fade signal, traded with the index future (index proxy) instead of an option
    Q3  skew risk reversal at 12:00: sell P(K0 - d), buy C(K0 + d), buy P(K0 - d - w); out 15:05
    Q4  the expiring index's 15-minute move RELATIVE to a non-expiring twin index, faded with its future
    Q5  the morning after: fade a big last-30-minute expiry move at the next session's open, out at 10:15

Option pricing is R119's engine; futures use R104's dated index-futures charges and half-spread, + 0.5 bp slippage.
Grid: row t = the 1-minute bar that STARTS at 09:15 + t; decisions on row t's close fill at row t + 1's open.
"""
from __future__ import annotations

import math
import sys
from collections import deque
from datetime import date

import numpy as np

import r120cfg as K
import r119cfg as K119
import r119lib as L19
import r98data as R
import r98lib as L98
import r50lib as L50
import r25lib as R25

sys.path.insert(0, str(K.HUNT / "r104_books"))
import r104cfg as K104  # noqa: E402

EPS = 1e-9
TICK = 0.05


# ------------------------------------------------------------------------------------------------ Q1: sized iron fly
def leg_prices(ctx, legs, sig_row, exit_row, mult=1.0):
    """[(sign, entry price, exit price, stale)] for the legs at 1 lot's slippage (R119's fills), or None (no entry)."""
    out = []
    for lg in legs:
        f = L19.fill_leg(ctx, lg, sig_row + 1, lg.sign > 0, mult, allow_model=False)
        if f is None or f[0] < K119.MIN_PX:
            return None
        g = L19.fill_leg(ctx, lg, exit_row, lg.sign < 0, mult, allow_model=True)
        px, stale = (g[0], g[2]) if g is not None else (0.0, True)
        out.append((lg.sign, f[0], px, stale))
    return out


def net_at_lots(ctx, prices, n: int, mult: float = 1.0) -> float:
    """Net Rs of the legs at n lots per leg: quantity n x lot, 1 extra tick (x mult) per 5 lots beyond the first 5 on
    every order (PREREG Q1), dated charges per order (Rs 20 brokerage stays per order)."""
    q = n * ctx.lot
    extra = ((n - 1) // 5) * TICK * mult
    d = ctx.day.day
    cash = 0.0
    for sign, pin, pout, _ in prices:
        e = pin + extra if sign > 0 else max(pin - extra, 0.0)                 # long buys / short sells at entry
        cash -= sign * e * q
        cash -= L50.charge(sign > 0, e, q, ctx.und, d)
        if pout < K119.MIN_PX:
            continue
        x = max(pout - extra, 0.0) if sign > 0 else pout + extra
        if x < K119.MIN_PX:
            continue
        cash += sign * x * q
        cash -= L50.charge(sign < 0, x, q, ctx.und, d)
    return round(cash, 2)


def fly_legs(ctx, t):
    k0, s = ctx.k0(t), ctx.em[t]
    if not (k0 == k0 and s == s and s > 0):
        return None
    w = min(max(round(K119.WING * s / ctx.step), 1), 10) * ctx.step
    return [L19.Leg(k0, "C", -1), L19.Leg(k0, "P", -1), L19.Leg(k0 + w, "C", 1), L19.Leg(k0 - w, "P", 1)]


def q1_frontier(ctx, t=K119.ROW_0930, mult=1.0):
    """{n: net} for n = 1..10 lots of R119's P1 decided at row t, or None."""
    legs = fly_legs(ctx, t)
    if legs is None:
        return None
    pr = leg_prices(ctx, legs, t, K119.ROW_1505, mult)
    if pr is None:
        return None
    return {n: net_at_lots(ctx, pr, n, mult) for n in K.Q1_LOTS}


def choose_n(design: dict) -> int:
    """PREREG Q1: design = {n: (mean, months_at_stop)}. The n with mean > 0 and the fewest months at the stop (ties:
    the smaller n); none > 0: 1."""
    ok = [(m_stop, n) for n, (mean, m_stop) in design.items() if mean > 0]
    return min(ok)[1] if ok else 1


# ------------------------------------------------------------------------------------------------ futures
def fut_fill(p: float, buy: bool, mult: float = 1.0) -> float:
    hs = K104.index_half_spread(p) + K.FUT_SLIP_BP * 1e-4 * p
    return p + hs * mult if buy else p - hs * mult


def fut_trade(und: str, d: date, lot: int, p_in: float, p_out: float, direction: int, mult: float = 1.0,
              charge_day: date | None = None) -> float:
    """Net Rs of 1 lot of the index future: direction +1 long / -1 short, raw prices p_in -> p_out (index proxy)."""
    if not (p_in == p_in and p_out == p_out) or lot <= 0:
        return math.nan
    if direction > 0:
        b, s = fut_fill(p_in, True, mult), fut_fill(p_out, False, mult)
    else:
        s, b = fut_fill(p_in, False, mult), fut_fill(p_out, True, mult)
    gross = (s - b) * lot
    return round(gross - K104.index_fut_charges(b * lot, s * lot, charge_day or d), 2)


def open_at(ix: np.ndarray, r: int) -> float:
    """The index's open at row r, else the first open in the next 2 rows, else the close before r."""
    for rr in range(r, min(r + 3, R.N)):
        o = ix[rr, R.O]
        if o == o:
            return float(o)
    c = ix[:r, R.C]
    ok = c[~np.isnan(c)]
    return float(ok[-1]) if len(ok) else math.nan


def q2_trades(ctx, mult=1.0):
    """[(sig_row, exit_row, move, net against, net with)] for R119's P5 signals traded with the future."""
    out = []
    for t, mv in L19.fade_signals(ctx):
        x = min(t + 1 + K119.FADE_HOLD, K119.ROW_1505)
        pin, pout = open_at(ctx.day.ix, t + 1), open_at(ctx.day.ix, x)
        a = fut_trade(ctx.und, ctx.day.day, ctx.lot, pin, pout, -mv, mult)
        w = fut_trade(ctx.und, ctx.day.day, ctx.lot, pin, pout, mv, mult)
        if a == a:
            out.append((t, x, mv, a, w))
    return out


# ------------------------------------------------------------------------------------------------ Q3: risk reversal
def rr_legs(ctx, t=K119.ROW_1200):
    k0, s = ctx.k0(t), ctx.em[t]
    if not (k0 == k0 and s == s and s > 0):
        return None
    d = max(round(K.Q3_D * s / ctx.step), 1) * ctx.step
    w = max(round(K.Q3_W * s / ctx.step), 1) * ctx.step
    return [L19.Leg(k0 - d, "P", -1), L19.Leg(k0 + d, "C", 1), L19.Leg(k0 - d - w, "P", 1)]


def q3_trade(ctx, t=K119.ROW_1200, mult=1.0):
    legs = rr_legs(ctx, t)
    return L19.run_trade(ctx, "Q3", legs, t, K119.ROW_1505, mult) if legs else None


# ------------------------------------------------------------------------------------------------ Q4: twins
def ffill5(c):
    return L98.ffill(np.asarray(c, float), 5)


def rel_moves(sp: np.ndarray, tw: np.ndarray) -> dict:
    """{mark: the expiring index's 15-minute % move minus the twin's} at Q2's bar closes."""
    out = {}
    for t in K119.FADE_MARKS:
        a0, a1, b0, b1 = sp[t - 15], sp[t], tw[t - 15], tw[t]
        if all(x == x and x > 0 for x in (a0, a1, b0, b1)):
            out[t] = (a1 / a0 - 1) * 100 - (b1 / b0 - 1) * 100
    return out


def q4_signals(rel: dict, hist: dict) -> list[tuple[int, int]]:
    """[(row, sign of rel)] where |rel| >= Q4_K x sd of that mark's rel over the previous expiry days (>= Q4_MIN of
    them; up to Q4_N), one position at a time. hist = {mark: deque of earlier days' rel} (point-in-time)."""
    out, free = [], 0
    for t in K119.FADE_MARKS:
        if t < free or t not in rel:
            continue
        h = hist.get(t)
        if h is None or len(h) < K.Q4_MIN:
            continue
        sd = float(np.std(np.asarray(h, float), ddof=1))
        if sd > 0 and abs(rel[t]) >= K.Q4_K * sd:
            out.append((t, 1 if rel[t] > 0 else -1))
            free = min(t + 1 + K119.FADE_HOLD, K119.ROW_1505)
    return out


def push_hist(hist: dict, rel: dict):
    for t, v in rel.items():
        hist.setdefault(t, deque(maxlen=K.Q4_N)).append(v)


# ------------------------------------------------------------------------------------------------ Q5: the morning after
def m30(ix: np.ndarray) -> float:
    c = ix[:, R.C]
    a = c[K119.ROW_1500]
    ok = c[~np.isnan(c)]
    if not (a == a and len(ok)):
        return math.nan
    return (float(ok[-1]) / float(a) - 1) * 100


def q5_threshold(hist: deque) -> float | None:
    if len(hist) < K.Q5_MIN:
        return None
    return float(np.percentile(np.abs(np.asarray(hist, float)), K.Q5_PCT))
