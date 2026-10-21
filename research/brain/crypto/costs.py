"""Crypto trading costs, the way the hunt studies count Indian ones: every evaluation pays real fees, slippage and (for
perpetuals) funding, and is stressed at 2x slippage.

    round_trip(sym, venue="perp", hold_min=60, side=+1, funding=..., stress=1.0) -> cost as a fraction of notional

  fees      config.FEES (Binance VIP 0, no BNB discount): taker on both legs unless maker=True
  slippage  config.SLIP_BPS per leg beyond the bar close (an assumption: the archive has no bid/ask), x stress
  funding   perps only: for each settlement inside the holding window a long pays rate x notional, a short receives
            it (negative rates reverse that). `funding` is the rate assumed for the window's settlements (use the
            last settled rate known at entry, never a later one).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import config


def settlements_between(entry: pd.Timestamp, exit_: pd.Timestamp, hours=config.FUNDING_HOURS) -> int:
    """Funding settlements in (entry, exit]: a position open at the settlement instant pays it."""
    if exit_ <= entry:
        return 0
    days = pd.date_range(pd.Timestamp(entry).normalize(), pd.Timestamp(exit_).normalize(), freq="D")
    n = 0
    for d in days:
        for h in hours:
            s = d + pd.Timedelta(hours=h)
            if entry < s <= exit_:
                n += 1
    return n


def settlements_count(entry_ts, hold_min: int) -> np.ndarray:
    """Vectorised settlements_between for the fixed 00/08/16 UTC schedule (every 8 hours from midnight)."""
    step = config.FUNDING_INTERVAL_H * 60
    a = (pd.DatetimeIndex(entry_ts).values.astype("datetime64[m]").astype(np.int64))
    b = a + int(hold_min)
    return (np.floor_divide(b, step) - np.floor_divide(a, step)).astype(float)


def round_trip(sym: str, venue: str = "perp", hold_min: int = 60, side: int = 1, funding: float = 0.0,
               entry: pd.Timestamp | None = None, maker: bool = False, stress: float = 1.0) -> float:
    """Total cost of one entry + exit as a fraction of notional (positive = a cost)."""
    fee = config.FEES[venue]["maker" if maker else "taker"]
    slip = config.SLIP_BPS.get(sym, 2.0) * 1e-4 * stress
    cost = 2 * (fee + slip)
    if venue == "perp":
        if entry is not None:
            n = settlements_between(entry, entry + pd.Timedelta(minutes=hold_min))
        else:
            n = hold_min / (config.FUNDING_INTERVAL_H * 60)          # expected number when the entry time is unknown
        cost += side * funding * n
    return float(cost)


def net_returns(gross_pct: np.ndarray, sym: str, side: np.ndarray, hold_min: int, funding: np.ndarray | None = None,
                entry_ts=None, venue: str = "perp", stress: float = 1.0) -> np.ndarray:
    """Per-trade net return, % of notional: side x gross move minus round_trip (vectorised over trades)."""
    gross_pct = np.asarray(gross_pct, float)
    side = np.asarray(side, float)
    fee = config.FEES[venue]["taker"]
    slip = config.SLIP_BPS.get(sym, 2.0) * 1e-4 * stress
    base = 2 * (fee + slip) * 100
    fcost = np.zeros(len(gross_pct))
    if venue == "perp" and funding is not None:
        if entry_ts is not None:
            n = settlements_count(entry_ts, hold_min)
        else:
            n = np.full(len(gross_pct), hold_min / (config.FUNDING_INTERVAL_H * 60))
        fcost = side * np.nan_to_num(np.asarray(funding, float)) * n * 100
    return side * gross_pct - base - fcost
