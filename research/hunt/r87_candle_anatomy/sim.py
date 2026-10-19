"""Option-buying simulator for R87's candidate rules (PREREG section 8). Signal on the index candle; trade = BUY the
1-ITM call (up) or put (down) of the nearest expiry, 1 lot in force.

Fills ('liq', as research/obuy/costs.py Fills): market buy = open x (1 + 5 bps) + half-spread ticks, market sell = open
x (1 - 5 bps) - ticks; a resting stop sells at the stop (or the open when the minute opens below it) x (1 - 10 bps) -
2 x ticks. Ticks (Rs 0.05) from the contract's traded volume in the 5 minutes before the order: >= 20 lots 1 tick,
1-20 lots 2, none 4. Charges: research/obuy/costs.py Costs("dated") (STT, exchange, SEBI, stamp, GST, Rs 20 brokerage
a side, the rates in force on the trade date). Pure functions over one day's arrays.

Exits, checked minute by minute from the entry minute:
  1. premium stop: the option's minute low <= fill x (1 - stop)
  2. profit lock: once the option's high has reached fill x (1 + LOCK_TRIG) (from the NEXT minute on), the floor is
     max(fill x (1 + LOCK_KEEP), peak - LOCK_TRAIL x fill); a minute low <= floor sells at the floor
  3. underlying stop: an index minute CLOSE beyond the signal candle's far end (long: below its low, short: above its
     high) -> sell at the next minute's open
  4. time: after `hold` minutes -> sell at the open of minute entry + hold
  5. square-off: 15:10 (15:05 on the instrument's expiry day) -> sell at that minute's open
A market exit in a minute with no print takes the next print within 3 minutes, else the last known close ("stale").
"""
from __future__ import annotations

import sys
from datetime import date

import numpy as np

from . import cfg

sys.path.insert(0, str(cfg.ROOT / "research"))
from obuy.costs import Costs  # noqa: E402

COSTS = Costs("dated")
TICK = 0.05
LOCK_TRIG, LOCK_KEEP, LOCK_TRAIL = 0.20, 0.10, 0.10
BPS_MKT, BPS_STOP = 0.0005, 0.0010


def ticks(v: np.ndarray, r: int, qty: float) -> float:
    s = np.nansum(v[max(r - 5, 0):r]) / qty if r > 0 else 0.0
    return 1.0 if s >= 20 else (2.0 if s > 0 else 4.0)


def charges(und: str, entry: float, exit_: float, qty: float, d: date) -> float:
    dn = np.array([(d - date(1970, 1, 1)).days])
    bse = und in cfg.BSE
    return float(COSTS.charge(True, np.array([entry]), np.array([qty]), dn, bse)[0]
                 + COSTS.charge(False, np.array([exit_]), np.array([qty]), dn, bse)[0])


def _market_px(o, c, r, n):
    for j in range(r, min(r + 4, n)):
        if np.isfinite(o[j]):
            return o[j], j, False
    last = c[:r][np.isfinite(c[:r])]
    return (float(last[-1]) if len(last) else np.nan), r, True


def simulate(A: np.ndarray, idx_c: np.ndarray, e: int, long: bool, und_stop: float, hold: int, stop: float,
             sq: int, qty: float) -> dict | None:
    """A: (5, 375) option O H L C V of the held contract; idx_c: (375,) index closes; e: the entry row (the minute
    after the signal candle's last minute). Returns None when there is no print within 3 minutes of e (no trade)."""
    o, h, l, c, v = A
    n = len(o)
    ef = -1
    for j in range(e, min(e + 3, sq)):
        if np.isfinite(o[j]) and o[j] > 0:
            ef = j
            break
    if ef < 0:
        return None
    raw_e = o[ef]
    fill = raw_e * (1 + BPS_MKT) + ticks(v, ef, qty) * TICK
    stop_px = fill * (1 - stop)
    peak, armed = -np.inf, False
    end = min(ef + hold, sq)
    why, xr, px, kind = "time", end, np.nan, "mkt"
    j = ef
    while j < end:
        if np.isfinite(l[j]):
            floor = max(fill * (1 + LOCK_KEEP), peak - LOCK_TRAIL * fill) if armed else -np.inf
            lvl = max(stop_px, floor)
            if l[j] <= lvl:
                why = "lock" if armed and floor >= stop_px else "stop"
                px = min(o[j], lvl) if np.isfinite(o[j]) else lvl
                xr, kind = j, "stop"
                break
            peak = max(peak, h[j])
            if peak >= fill * (1 + LOCK_TRIG):
                armed = True
        ic = idx_c[j]
        if np.isfinite(ic) and ((long and ic < und_stop) or ((not long) and ic > und_stop)):
            why, xr = "und_stop", j + 1
            break
        j += 1
    if j >= end and why == "time":
        why = "time" if end < sq else "squareoff"
    stale = False
    if kind == "mkt":
        if xr >= n:
            xr = n - 1
        px, xr, stale = _market_px(o, c, xr, n)
        if not np.isfinite(px):
            return None
        real_x = max(px * (1 - BPS_MKT) - ticks(v, xr, qty) * TICK, 0.0)
    else:
        real_x = max(px * (1 - BPS_STOP) - 2 * ticks(v, xr, qty) * TICK, 0.0)
    return dict(er=ef, xr=int(xr), E=float(raw_e), X=float(px), fill=float(fill), real_x=float(real_x), why=why,
                stale=stale)


def strike_1itm(und: str, atm: float, close: float, long: bool) -> float:
    step = cfg.STEP[und]
    k = atm if np.isfinite(atm) else round(close / step) * step
    return float(k - step) if long else float(k + step)


def price_trade(und: str, d: date, con: dict, idx_c: np.ndarray, atm: np.ndarray, sig_row: int, long: bool,
                und_stop: float, hold: int, stop: float, expiry: bool) -> dict | None:
    """One trade: the signal candle's last minute is sig_row; entry at sig_row + 1."""
    e = sig_row + 1
    sq = cfg.SQ_ROW_EXPIRY if expiry else cfg.SQ_ROW
    if e >= sq:
        return None
    k = strike_1itm(und, atm[sig_row], idx_c[sig_row], long)
    A = con.get(("C" if long else "P", k))
    if A is None:
        return None
    qty = float(cfg.lot(und, d))
    r = simulate(A, idx_c, e, long, und_stop, hold, stop, sq, qty)
    if r is None:
        return None
    ch = charges(und, r["fill"], r["real_x"], qty, d)
    r.update(und=und, day=d, side="C" if long else "P", strike=k, qty=qty, charges=ch,
             net=(r["real_x"] - r["fill"]) * qty - ch, gross=(r["X"] - r["E"]) * qty)
    return r
