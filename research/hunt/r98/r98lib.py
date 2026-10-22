"""R98 helpers shared by the census and the decision rows (PREREG.md sections 2 and 4)."""
from __future__ import annotations

import numpy as np

import r98data as R

N = R.N
T_CHEAP0 = R.minute(10, 0)       # census: entry minutes 10:00 ..
T_CHEAP1 = R.minute(15, 0)       # .. 15:00
T_END = R.minute(15, 20)         # outcome window ends with the 15:20 bar (broker auto square-off)
LO, HI = 1.0, 10.0               # a "cheap" option: close in Rs 1-10
TICK = 0.05


def ffill(a: np.ndarray, limit: int = 3) -> np.ndarray:
    """Forward-fill NaN for at most `limit` rows (a price is 'current' for 3 minutes after its last trade)."""
    out = a.copy()
    last, age = np.nan, limit + 1
    for i in range(len(out)):
        if np.isnan(out[i]):
            age += 1
            if age <= limit:
                out[i] = last
        else:
            last, age = out[i], 0
    return out


def spot(day: R.Day) -> np.ndarray:
    return ffill(day.ix[:, 3], 5)


def straddle(day: R.Day) -> tuple[np.ndarray, np.ndarray]:
    """ATM straddle (call + put close at the strike nearest spot) per minute, and that strike. NaN when either leg has
    no price in the last 3 minutes."""
    sp = spot(day)
    step = R.STEP[day.und]
    em, katm = np.full(N, np.nan), np.full(N, np.nan)
    cache = {}
    for t in range(N):
        if np.isnan(sp[t]):
            continue
        k = round(sp[t] / step) * step
        katm[t] = k
        legs = []
        for s in ("C", "P"):
            key = (float(k), s)
            if key not in cache:
                a = day.opts.get(key)
                cache[key] = ffill(a[:, R.C]) if a is not None else None
            legs.append(cache[key][t] if cache[key] is not None else np.nan)
        em[t] = legs[0] + legs[1]
    return em, katm


def future_max(x: np.ndarray, end: int = T_END) -> np.ndarray:
    """fm[t] = nanmax(x[t+1 .. end]) (NaN if nothing printed)."""
    fm = np.full(N, np.nan)
    run = np.nan
    for t in range(end, -1, -1):
        fm[t] = run
        if t <= end and not np.isnan(x[t]):
            run = x[t] if np.isnan(run) else max(run, x[t])
    return fm


def future_argmax(x: np.ndarray, t0: int, end: int = T_END) -> int:
    seg = x[t0 + 1:end + 1]
    if seg.size == 0 or np.all(np.isnan(seg)):
        return -1
    return t0 + 1 + int(np.nanargmax(seg))


def otm(k: float, side: str, s: float) -> bool:
    return k > s if side == "C" else k < s


def traded(a: np.ndarray, t: int) -> bool:
    v = a[max(t - 2, 0):t + 1, R.V]
    return bool(np.nansum(v) > 0)


def is_monthly(day: R.Day, all_days: list) -> bool:
    """A monthly expiry: the last expiry of its calendar month for an index that also has weeklies that month, or any
    expiry of an index with only monthlies (BANKNIFTY / FINNIFTY / MIDCPNIFTY after Nov 2024)."""
    same = [d.day for d in all_days if (d.day.year, d.day.month) == (day.day.year, day.day.month)]
    return day.day == max(same)


# ---------------------------------------------------------------------------------------------------------- charges
BUDGET = 5000.0
BROKER = 20.0
GST = 0.18
STT = 0.0015
STAMP = 0.00003
EXCH = {"NSE": 0.000355, "BSE": 0.000325}


def exch(und: str) -> float:
    return EXCH["BSE" if und == "SENSEX" else "NSE"]


def net_pnl(und: str, entry: float, exit_px: float, budget: float = BUDGET) -> float:
    """Net rupees of one ticket: buy `budget` worth at `entry`, sell at `exit_px` minus a tick; an exit under Rs 0.10
    is expiry worthless (no sell order). Fractional quantity (lot rounding ignored, PREREG 4)."""
    q = budget / entry
    buy = budget
    ex = exch(und)
    cost_buy = BROKER * (1 + GST) + buy * ex * (1 + GST) + buy * STAMP
    if exit_px < 0.10:
        return -buy - cost_buy
    sell = q * max(exit_px - TICK, 0.0)
    cost_sell = BROKER * (1 + GST) + sell * ex * (1 + GST) + sell * STT
    return sell - buy - cost_buy - cost_sell
