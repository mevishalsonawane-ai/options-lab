"""R168 engine: price one ORB-arm trade on the ONE contract bought (PREREG sections 1-4).

  scalp   R154's `r154engine.price` with the arm's strike (R106's numba simulator, R17 fills, dated Zerodha charges, dated
          lots, 15:15 square-off / 15:05 on expiry days, a stop and a target in one minute count as the stop).
  own     B0, the arm's own exits as the app trades them since 07 Oct (h20's "fixed" mode): ONE resting stop at
          max(floor_tick(real fill - 40), the profit lock); the lock ladder on the arm's target (25% / 50% / 75% of it ->
          breakeven / +25% / +50%), the breakeven floored at the fill plus the round trip's charges per unit, earned on
          the best minute HIGH before this minute and counted only below that best price; the stop fills at its level or
          the gap open (R17 stop fill); the target is the app's check on the minute high, sold at the next minute's open
          (R17 market sale); square-off 15:10 (15:05 on expiry days), sold at that minute's open.
  parity  `own` with obuy's app charges at today's rates (STT 0.15% every year) and h20's lock fills (a lock exit takes the
          market-sale slippage, only the -40 stop the stop slippage), for the parity check with R14 / h20 (PREREG 4).
          R17's fills and obuy's "liq" fills are the same formula at 1 lot (bps, 1 / 2 / 4 ticks from the 5 minutes'
          volume before the order), so only the charges and the lock fill differ.

Every level comes from the trade's own fill or the minutes before the one being decided: nothing looks ahead.
"""
from __future__ import annotations

from datetime import date

import numpy as np

import r168cfg as K

TICK = 0.05
_M: dict = {}


def mods() -> dict:
    """R154's module set (r154data puts R51 / R106 on the path), plus R154's engine."""
    if not _M:
        import r154data as DATA
        M = DATA.mods()
        import r154engine as Y
        from obuy.costs import Costs
        _M.update(M, DATA=DATA, Y=Y, APP=Costs("app"))
    return _M


def floor_tick(x: float) -> float:
    return float(np.rint(np.floor(x / TICK + 1e-9) * TICK * 100) / 100.0)


def _r2(x: float) -> float:
    return float(np.round(x * 100.0) / 100.0)


def _ticks(v_lots: float) -> float:
    return 1.0 if v_lots >= 20.0 else 2.0 if v_lots > 0.0 else 4.0


def _vol5(v: np.ndarray, r: int, qty: float) -> float:
    lo = max(r - 4, 0)
    x = v[lo:r + 1] if r >= 0 else v[:0]
    return float(np.nansum(x)) / qty


def buy_fill(raw: float, v: np.ndarray, ef: int, qty: float) -> float:
    """R17 buy: +5 bps and the half-spread ticks of the 5 minutes' volume before the fill."""
    return _r2(raw * 1.0005) + _ticks(_vol5(v, ef - 1, qty)) * TICK


def sell_fill(raw: float, v: np.ndarray, m: int, qty: float, stop: bool) -> float:
    """R17 sale: a market sale -5 bps and the ticks; a stop sale -10 bps and twice the ticks (never below 0)."""
    if stop:
        return max(_r2(raw * 0.999) - 2.0 * _ticks(_vol5(v, m - 1, qty)) * TICK, 0.0)
    return max(_r2(raw * 0.9995) - _ticks(_vol5(v, m - 1, qty)) * TICK, 0.0)


def round_trip(und: str, e: float, x: float, qty: float, d: date, mode: str = "dated") -> float:
    """Charges of a buy at e and a sale at x: dated Zerodha (R51's engine.charges) or obuy's app model ('parity')."""
    M = mods()
    if mode == "parity":
        bse = und in ("SENSEX", "BANKEX")
        return float(M["APP"].charge(True, np.array([e]), np.array([qty]), None, bse)[0]
                     + M["APP"].charge(False, np.array([x]), np.array([qty]), None, bse)[0])
    return float(M["E"].charges(und, np.array([e]), np.array([x]), qty, d)[0])


def breakeven(und: str, e: float, qty: float, d: date, mode: str = "dated") -> float:
    """ProfitLock.roundTripPerUnit: the fill plus the charges per unit of a buy at e and a sale at its breakeven."""
    first = round_trip(und, e, e, qty, d, mode) / qty
    return e + round_trip(und, e, e + first, qty, d, mode) / qty


def own_exit(C: np.ndarray, er: int, sq: int, qty: float, target: float, be_fn, stop_pts: float = K.OWN_STOP,
             lock_stop_fill: bool = True) -> dict | None:
    """B0 on one contract C (5, 375): entered at the first print in rows er .. er+2 (before sq), sold by the arm's own
    exits; sq = the square-off row (its open is the last sale). be_fn(real fill) -> the breakeven price. Returns
    dict(E, X, rE, rX, er, xr, why) or None with no fill."""
    o, h, l, c, v = C[0], C[1], C[2], C[3], C[4]
    ef = -1
    for j in range(er, min(er + 3, sq)):
        if o[j] == o[j] and o[j] > 0:
            ef = j
            break
    if ef < 0:
        return None
    raw = float(o[ef])
    e = buy_fill(raw, v, ef, qty)
    if not (e > K.MIN_PREMIUM):
        return dict(E=raw, X=np.nan, rE=e, rX=np.nan, er=ef, xr=ef, why="refused")
    t = floor_tick(e - stop_pts)
    trig = t if (t > TICK + 1e-9 and t < e) else -1.0
    tgt = e + target
    be = be_fn(e)
    rungs = [(f * target, g * target) for f, g in ((0.25, 0.0), (0.5, 0.25), (0.75, 0.5))]
    peak = e
    n = len(o)
    decide = -1          # the minute a target / square-off decision is taken (sold at the next minute's open)
    why = ""
    for m in range(ef, n):
        if not (l[m] == l[m]):
            if m >= sq - 1:
                decide, why = m, "squareoff"
                break
            continue
        lock = -1.0
        for a, b in rungs:
            if peak >= e + a - 1e-9:
                lv = max(e + b, be)
                if lv < peak and lv > lock:
                    lock = lv
        rest = max(trig, lock)
        if rest > 0 and l[m] <= rest:
            is_stop = trig >= lock
            px = min(rest, float(o[m])) if o[m] == o[m] else rest
            x = sell_fill(px, v, m, qty, stop=is_stop or lock_stop_fill)
            return dict(E=raw, X=px, rE=e, rX=x, er=ef, xr=m, why="stop" if is_stop else "lock")
        if m >= sq - 1:
            decide, why = m, "squareoff"
            break
        if h[m] >= tgt:
            decide, why = m, "target"
            break
        if h[m] > peak:
            peak = float(h[m])
    if decide < 0:
        decide, why = n - 1, "missing"
    col = -1
    for j in range(decide + 1, n):
        if o[j] == o[j]:
            col = j
            break
    if col < 0:
        last = int(np.max(np.nonzero(c == c)[0])) if (c == c).any() else ef
        px, col, why = float(c[last]), last, "missing" if why != "squareoff" else why
    else:
        px = float(o[col])
    x = sell_fill(px, v, col, qty, stop=False)
    return dict(E=raw, X=px, rE=e, rX=x, er=ef, xr=col, why=why)


def own_sq(D) -> int:
    return K.OWN_SQ_EXPIRY if D.expiry else K.OWN_SQ


def contract(und: str, D, side: str, strike: float):
    st = mods()["E"].stack(und, D)
    ci = st.idx(side, strike)
    return None if ci < 0 else st.S[ci]


def price_own(und: str, D, er: int, side: str, strike: float, arm: str, lots: int = 1, mode: str = "dated") -> dict | None:
    """B0: one trade of `arm` with its own exits (mode 'dated' = R168's own costs, 'parity' = R14 / h20's)."""
    C = contract(und, D, side, strike)
    if C is None:
        return None
    qty = float(mods()["r51cfg"].lot(und, D.d)) * lots
    tp = K.ARM_RULES[arm][5]
    r = own_exit(C, er, own_sq(D), qty, tp, lambda e: breakeven(und, e, qty, D.d, mode),
                 lock_stop_fill=(mode != "parity"))
    if r is None:
        return None
    if r["why"] == "refused":
        return dict(r, und=und, day=D.d, side=side, strike=float(strike), qty=qty, lots=lots, net=0.0, charges=0.0)
    ch = round_trip(und, r["rE"], r["rX"], qty, D.d, mode)
    return dict(r, und=und, day=D.d, side=side, strike=float(strike), qty=qty, lots=lots, charges=ch,
                net=(r["rX"] - r["rE"]) * qty - ch, pts=r["rX"] - r["rE"], expiry=bool(D.expiry))


def price_scalp(und: str, D, er: int, side: str, strike: float, rule_id: str, lots: int = 1) -> dict | None:
    """One trade with a scalp exit (R154's price, the arm's strike)."""
    return mods()["Y"].price(und, D, er, side, K.RULES[rule_id], lots, strike=strike)
