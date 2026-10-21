"""R93 trade engine (PREREG.md sections 4-5). Pure given its inputs (a minute grid and printed option legs).

Option trade (R72's exit set XL on MCX prints), one held contract:
- the 1-ITM strike from the near future's close at the signal bar's last minute; filled at the OPEN of the first
  printed minute in [bar end, bar end + 3 min); none -> no trade;
- every printed minute from the fill on: first the stop check (level = max(stop -15%, lock); the minute's low at or
  below it -> out at the level, or at the open when the minute opened below it), then the peak and the lock update
  (+25% -> +5%; +30% -> breakeven = fill + round-trip costs per unit; +60% -> +30%; +100% -> +60%), so a minute never
  locks and stops on its own high (conservative, as research/hunt/r64/r64engine.py);
- out at the close of the 30th minute from the fill, or at the square-off (close - 15 min), whichever first; the sell
  price there is R53 price_exit (the close if printed, else the next print's open within 10 minutes, else stale).
Futures trade (copper, zinc, aluminium): filled at the close of the first minute after the bar (within 3 minutes) plus
the half-spread; out at the first minute close back through the signal's channel mid (stop slippage), else at the time
exit / square-off close.
Costs: Zerodha MCX option / futures charges (R53) + half-spreads (cfg.OPT_HALF / FUT_HALF), times `slip` (gate 7: 2).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C53
from research.hunt.r93_breadth_donchian_mcx import cfg as K

ONE_MIN = np.timedelta64(1, "m")


# ------------------------------------------------------------------------------------------------ costs
def opt_cost(cost_key: str, buy_rs: float, sell_rs: float, tod_in: int, tod_out: int, slip: float = 1.0) -> float:
    return (C53.mcx_opt_charges(buy_rs, sell_rs) + K.opt_half(cost_key, tod_in, slip) * buy_rs
            + K.opt_half(cost_key, tod_out, slip) * sell_rs)


def fut_cost(cost_key: str, buy_rs: float, sell_rs: float, stop: bool, slip: float = 1.0) -> float:
    half = K.FUT_HALF[cost_key] * slip
    return (C53.mcx_fut_charges(buy_rs, sell_rs) + half * (buy_rs + sell_rs)
            + (K.STOP_SLIP * slip * sell_rs if stop else 0.0))


def breakeven(fill: float, units: float, cost_key: str, tod: int) -> float:
    """Fill + the round-trip costs of a sale at the fill price, per unit (R64's breakeven step)."""
    return fill + opt_cost(cost_key, fill * units, fill * units, tod, tod) / units


# ------------------------------------------------------------------------------------------------ option (XL)
def xl_walk(fill: float, rows: np.ndarray, be: float, stop: float = K.STOP, steps=K.STEPS):
    """rows: printed minutes [o, h, l, c] from the fill minute to the last minute allowed (inclusive). Returns
    (k, price, why) of the stop / lock exit, or (None, None, None) when neither hit."""
    lvl_stop = fill * (1 - stop)
    lock = -1.0
    peak = fill
    for k in range(len(rows)):
        o_, h_, l_ = rows[k, 0], rows[k, 1], rows[k, 2]
        lvl = max(lvl_stop, lock)
        if l_ <= lvl:
            px = o_ if (np.isfinite(o_) and o_ < lvl) else lvl
            return k, float(px), ("lock" if lock >= lvl_stop and lock > 0 else "stop")
        if h_ > peak:
            peak = h_
        g = peak / fill - 1.0
        for trig, keep in steps:
            if g >= trig - 1e-12:
                lv = be if not np.isfinite(keep) else fill * (1 + keep)
                if lv > lock:
                    lock = lv
    return None, None, None


def price_exit(grid_ts, grid_day, ii, V, t: int):
    """R53 price_exit on grid index t: its close if printed, else the next print's open within 10 grid minutes the same
    day, else the last printed close at or before t (stale). Returns (price, grid index, stale)."""
    q = int(np.searchsorted(ii, t))
    if q < len(ii) and ii[q] == t:
        return float(V[q, 3]), int(t), False
    if q < len(ii) and ii[q] <= t + 10 and grid_day[ii[q]] == grid_day[t]:
        return float(V[q, 0]), int(ii[q]), False
    if q > 0:
        return float(V[q - 1, 3]), int(t), True
    return float("nan"), int(t), True


def last_at_or_before(grid_ts, t64) -> int:
    return int(np.searchsorted(grid_ts, t64, side="right")) - 1


def sim_option(m, bar_end, last_i: int, side: int, units: float, cost_key: str, sq_t, slip: float = 1.0):
    """One option buy on R53 Market m. bar_end: the signal bar's end (datetime64, = the first minute after it);
    last_i: grid index of the signal bar's last minute (strike source); sq_t: square-off time (datetime64).
    Returns a trade dict or None (no strike, no print in time, expiry day)."""
    if m.is_exp[last_i]:
        return None
    cp = 0 if side > 0 else 1
    k = m.strike(cp, -1 if side > 0 else 1, last_i)
    if not np.isfinite(k):
        return None
    lg = m.leg(m.day[last_i], cp, k)
    if lg is None:
        return None
    ii, V = lg
    lim = bar_end + K.FILL_WAIT * ONE_MIN
    a = int(np.searchsorted(ii, int(np.searchsorted(m.ts, bar_end))))
    if a >= len(ii) or not (bar_end <= m.ts[ii[a]] < lim) or m.ts[ii[a]] >= sq_t:
        return None
    fill = float(V[a, 0])
    if not fill > 0:
        return None
    t_in = m.ts[ii[a]]
    tod_in = int(m.tod[ii[a]])
    end_t = min(t_in + (K.TMAX - 1) * ONE_MIN, sq_t - ONE_MIN)
    z = int(np.searchsorted(ii, last_at_or_before(m.ts, end_t), side="right"))
    be = breakeven(fill, units, cost_key, tod_in)
    kx, px, why = xl_walk(fill, V[a:z, :4], be)
    stale = False
    if kx is not None:
        t_out = int(ii[a + kx])
    else:
        t_end = last_at_or_before(m.ts, end_t)
        px, t_out, stale = price_exit(m.ts, m.day, ii, V, t_end)
        why = "time" if end_t < sq_t - ONE_MIN else "squareoff"
        if not np.isfinite(px):
            return None
    buy, sell = fill * units, px * units
    cost = opt_cost(cost_key, buy, sell, tod_in, int(m.tod[t_out]), slip)
    return dict(entry_ts=pd.Timestamp(t_in), exit_ts=pd.Timestamp(m.ts[t_out]), side=int(side), strike=float(k),
                fill=fill, exit=float(px), why=why, stale=bool(stale), gross=sell - buy, cost=cost, net=sell - buy - cost)


# ------------------------------------------------------------------------------------------------ futures (intraday)
def sim_future(ts, F, bar_end, side: int, mid: float, units: float, cost_key: str, sq_t, slip: float = 1.0):
    """Futures on a close-only minute series (ts sorted datetime64, F closes). Filled at the close of the first minute
    in [bar_end, bar_end + 3 min); stop on a minute close back through `mid`; time exit / square-off at the close."""
    a = int(np.searchsorted(ts, bar_end))
    if a >= len(ts) or not (ts[a] < bar_end + K.FILL_WAIT * ONE_MIN) or ts[a] >= sq_t:
        return None
    fill = float(F[a])
    if not fill > 0:
        return None
    end_t = min(ts[a] + (K.TMAX - 1) * ONE_MIN, sq_t - ONE_MIN)
    z = last_at_or_before(ts, end_t)
    path = F[a + 1:z + 1]
    hit = np.flatnonzero(side * (path - mid) <= 0) if np.isfinite(mid) else np.array([], int)
    if len(hit):
        b, why = a + 1 + int(hit[0]), "fut_stop"
    else:
        b, why = max(z, a), ("time" if end_t < sq_t - ONE_MIN else "squareoff")
    px = float(F[b])
    gross = side * (px - fill) * units
    buy, sell = (fill, px) if side > 0 else (px, fill)
    cost = fut_cost(cost_key, buy * units, sell * units, why == "fut_stop", slip)
    return dict(entry_ts=pd.Timestamp(ts[a]), exit_ts=pd.Timestamp(ts[b]), side=int(side), strike=float("nan"),
                fill=fill, exit=px, why=why, stale=False, gross=gross, cost=cost, net=gross - cost)


# ------------------------------------------------------------------------------------------------ daily (futures)
def sim_daily(d: pd.DataFrame, i: int, side: int, units: float, cost_key: str, slip: float = 1.0):
    """Daily futures trade on family frame d (rules.daily_series): signal at row i's close, filled at open[i + 1];
    initial stop entry -/+ 2 x ATR(i); chandelier lock best close -/+ 3 x ATR (never loosening); checked on closes,
    filled at that close with stop slippage; out after 10 sessions at the close, or at the close before a roll row
    (the expiry calendar is public), or at the data's last close ("open")."""
    n = len(d)
    a = i + 1
    if a >= n:
        return None
    atr = d.atr.values
    A0 = atr[i]
    if not np.isfinite(A0) or A0 <= 0:
        return None
    o, c, roll = d.open.values, d.close.values, d.roll.values
    entry = float(o[a])
    lvl = entry - side * K.D_INIT_ATR * A0
    best = entry
    last = min(a + K.D_MAX_HOLD - 1, n - 1)
    b, why = last, ("time" if a + K.D_MAX_HOLD - 1 <= n - 1 else "open")
    for k in range(a, last + 1):
        if side * (c[k] - lvl) <= 0:
            b, why = k, ("lock" if side * (lvl - entry) > 0 else "stop")
            break
        if k + 1 < n and roll[k + 1]:
            b, why = k, "roll"
            break
        best = max(best, c[k]) if side > 0 else min(best, c[k])
        if np.isfinite(atr[k]):
            nl = best - side * K.D_TRAIL_ATR * atr[k]
            lvl = max(lvl, nl) if side > 0 else min(lvl, nl)
    px = float(c[b])
    gross = side * (px - entry) * units
    buy, sell = (entry, px) if side > 0 else (px, entry)
    cost = fut_cost(cost_key, buy * units, sell * units, why in ("stop", "lock"), slip)
    return dict(entry_ts=pd.Timestamp(d.date.values[a]), exit_ts=pd.Timestamp(d.date.values[b]), side=int(side),
                strike=float("nan"), fill=entry, exit=px, why=why, stale=False, gross=gross, cost=cost,
                net=gross - cost, a=int(a), b=int(b))
