"""Modelled option premiums for the rules (PREREG amendment A1: Dhan's option endpoint was too slow to fetch the
1-ITM option minutes for Nov 2024 - Oct 2026). LABELLED everywhere as "modelled premium".

Premium = Black-Scholes (research/brain/bs.py, r = 6.5%, no dividend) on the index minute, with
  sigma = INDIA VIX (same minute, last known) / 100 x scale, scale = the index's 20-session realised volatility of daily
          closes / NIFTY's, over the 20 sessions BEFORE the day (1 for NIFTY; point in time);
  T     = calendar time from the minute to 15:30 on the next expiry (research/brain/calendar; SENSEX after its last
          listed expiry: the next Thursday, its weekday since Sep 2025), floored at 1 minute.
A minute's option O H L C = the premium at the index's O H L C (a call's high at the index high, a put's at the low).
Volume is set high, so fills pay 1 tick + 5 bps (stops 2 ticks + 10 bps), as a liquid ATM-area index option.
What the model misses (stated): the volatility smile / skew, IV moves that VIX does not show (event crush, expiry-day
IV), and real spreads on fast minutes. sim.price_trade's checks are unchanged.
"""
from __future__ import annotations

import sys
from datetime import date, datetime, timedelta

import numpy as np
import pandas as pd

from . import cfg

sys.path.insert(0, str(cfg.ROOT))
from research.brain import bs  # noqa: E402
from research.brain import config as BC  # noqa: E402

YEAR_MIN = 365.0 * 24 * 60


def expiry_dates(und: str, frm: date, to: date) -> list[date]:
    from research.brain import calendar as cal
    e = cal.expiries(und, frm, to + timedelta(days=45))
    out = sorted(set(e["date"])) if len(e) else []
    if und == "SENSEX":                         # calendar ends at its last listed expiry: Thursdays after it
        last = out[-1] if out else frm
        d = last + timedelta(days=1)
        while d <= to + timedelta(days=45):
            if d.weekday() == 3:
                out.append(d)
            d += timedelta(days=1)
    return out


def next_expiry(exps: list[date], d: date) -> date | None:
    for e in exps:
        if e >= d:
            return e
    return None


def iv_scale(daily: dict[str, pd.Series], und: str, d: date, n: int = 20) -> float:
    """daily: und -> Series of daily closes indexed by date. Realised-vol ratio to NIFTY over the n sessions before d."""
    if und == "NIFTY":
        return 1.0

    def rv(s):
        s = s[s.index < d].tail(n + 1)
        r = np.log(s).diff().dropna()
        return float(r.std()) if len(r) >= n // 2 else np.nan
    a, b = rv(daily[und]), rv(daily["NIFTY"])
    return a / b if np.isfinite(a) and np.isfinite(b) and b > 0 else 1.0


def contract(side: str, K: float, d: date, ohlc: np.ndarray, vix: np.ndarray, scale: float, exp: date) -> np.ndarray:
    """(5, 375) modelled O H L C V of one contract on day d. ohlc: (4, 375) index O H L C (NaN where missing);
    vix: (375,) VIX close (last known)."""
    mins = np.arange(cfg.NROWS)
    t0 = datetime(d.year, d.month, d.day, 9, 15)
    tx = datetime(exp.year, exp.month, exp.day, 15, 30)
    T = np.maximum(((tx - t0).total_seconds() / 60 - mins - 1) / YEAR_MIN, 1.0 / YEAR_MIN)
    sig = vix / 100.0 * scale
    call = side == "C"
    out = np.full((5, cfg.NROWS), np.nan)
    with np.errstate(invalid="ignore", divide="ignore", over="ignore"):
        px = [bs.price(ohlc[j], K, T, sig, call, BC.RISK_FREE, BC.DIV_YIELD) for j in range(4)]
    o, hi_i, lo_i, c = px
    out[0], out[3] = o, c
    out[1] = hi_i if call else lo_i
    out[2] = lo_i if call else hi_i
    out[4] = 1e9
    bad = ~np.isfinite(sig) | ~np.isfinite(ohlc[3])
    out[:, bad] = np.nan
    return out
