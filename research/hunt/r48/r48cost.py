"""R48 fills, charges, expiry settlement and margin (PREREG.md "How every trade is priced").

Charges reuse research/obuy/costs.py Costs("dated") (STT on every SOLD option premium at the dated rate, exchange
NSE / BSE, SEBI, stamp on buys, GST, Rs 20 brokerage) per order. Slippage is ours: 5 bps + n ticks, see fill().
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))       # research/

from obuy import config as OC  # noqa: E402
from obuy.costs import Costs  # noqa: E402

import r48cfg as K  # noqa: E402

COSTS = Costs("dated")
EPOCH = date(1970, 1, 1)


def dn(d: date) -> int:
    return (d - EPOCH).days


def lot(und: str, d: date) -> int:
    """Lot size in force on d (research/obuy/config.LOT_SCHEDULE)."""
    q = None
    for frm, n in OC.LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(frm):
            q = n
    return int(q)


def ticks(vol5_lots: float, far: bool, stop: bool, tick_mult: float = 1.0) -> float:
    """Half-spread in ticks: 1 if the contract traded >= 20 lots in the 5 minutes before the order, 2 if less, 4 if
    nothing; +1 far OTM (>= 6 steps from ATM); doubled on stop exits; tick_mult (sensitivity) on top."""
    n = 1.0 if vol5_lots >= K.THIN_LOTS else (2.0 if vol5_lots > 0 else 4.0)
    if far:
        n += 1.0
    if stop:
        n *= 2.0
    return n * tick_mult


def fill(px: float, buy: bool, vol5_lots: float, far: bool = False, stop: bool = False, tick_mult: float = 1.0) -> float:
    """The price we get: moved against us by 5 bps plus the ticks. A sell never fills below Rs 0.05."""
    t = ticks(vol5_lots, far, stop, tick_mult) * K.TICK
    if buy:
        return round(px * (1 + K.BPS / 1e4) + t, 2)
    return round(max(px * (1 - K.BPS / 1e4) - t, K.MIN_SELL), 2)


def charge(buy: bool, px: float, qty: int, d: date, und: str) -> float:
    """Charges of one order (one leg, one side), Rs."""
    return float(COSTS.charge(buy, np.array([px]), np.array([qty]), np.array([dn(d)]), und in K.BSE)[0])


def exercise_stt(intrinsic: float, qty: int, d: date) -> float:
    """STT on an in-the-money long option left to expire (paid by the holder): 0.125% of intrinsic value; 0.15% from
    1 Apr 2026 (stated assumption, PREREG)."""
    if intrinsic <= 0:
        return 0.0
    rate = 0.0015 if d >= date(2026, 4, 1) else 0.00125
    return round(intrinsic * qty * rate, 2)


def settle_value(side: int, strike: float, settle_spot: float) -> float:
    """Intrinsic value at expiry of a call (side 0) or put (side 1)."""
    return max(settle_spot - strike, 0.0) if side == 0 else max(strike - settle_spot, 0.0)


def margin(und: str, d: date, spot: float, qty: int, width_pts: float, n_short: int, expiry_day: bool,
           premium: float = 0.0) -> float:
    """Margin of one position per the PREREG assumption. Selling (n_short > 0): hedged SPAN = max loss of the wider side
    (width x lot) + 2% exposure on the short notional, counted once + 2% expiry-day ELM from 20 Nov 2024. Buying
    (n_short == 0): the premium paid."""
    if n_short == 0:
        return premium * qty
    notional = spot * qty
    m = width_pts * qty + K.EXPOSURE * notional
    if expiry_day and d >= K.ELM_FROM:
        m += K.EXPIRY_ELM * notional
    return m
