"""Market rules the brain's layers need, in one place (13 Oct 2026: the crypto brain).

The Indian brain was written for one market: NSE, 09:15-15:29 IST, weekdays, expiries. The crypto brain needs another:
24x7, UTC, no sessions, holidays or expiries for perpetuals, funding every 8 hours. A `Market` says what a day's minute
grid is and which calendar day a minute belongs to ("analysis day": previous-day levels, day of week). The Indian code
keeps its own calendar.py; NSE here just points at it, so both markets are described the same way.

    from research.brain.markets import MARKETS
    MARKETS["crypto"].grid(date(2026, 1, 5))         # 1440 naive-UTC minutes, 00:00 .. 23:59
    MARKETS["crypto-ist"].analysis_day(ts)            # the IST calendar date of each (naive UTC) minute

Time-of-day regions for crypto (`region_flags`) use each city's own clock (so daylight saving moves them in UTC):
Asia = Tokyo 09:00-17:00, Europe = London 08:00-16:30, US = New York 09:30-16:00 (the cash-equity hours, when
crypto volume is highest). They overlap; a minute can be in none (the late-US / pre-Asia lull).
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class Market:
    name: str
    tz: str                     # the timezone stored timestamps are in (naive)
    open_hm: tuple[int, int]    # first bar of a day, in tz
    minutes: int                # bars per day
    weekdays: tuple[int, ...] | None   # trading weekdays (Mon=0); None = every day
    day_offset_min: int = 0     # analysis day = calendar date of (ts + offset): 330 puts the day boundary at IST midnight
    expiries: bool = False
    funding_hours: tuple[int, ...] = ()

    def grid(self, day: date) -> pd.DatetimeIndex:
        if self.name == "nse":
            from . import calendar
            return calendar.grid(day)
        start = pd.Timestamp(day) + pd.Timedelta(hours=self.open_hm[0], minutes=self.open_hm[1])
        return pd.date_range(start, periods=self.minutes, freq="min", name="ts")

    def trades_on(self, day: date) -> bool:
        return self.weekdays is None or day.weekday() in self.weekdays

    def analysis_day(self, ts) -> np.ndarray:
        """Calendar date (datetime64[D]) each minute belongs to for day-based features."""
        t = pd.DatetimeIndex(ts) + pd.Timedelta(minutes=self.day_offset_min)
        return t.normalize().values.astype("datetime64[D]")


MARKETS = {
    "nse": Market("nse", "Asia/Kolkata", (9, 15), 375, (0, 1, 2, 3, 4), expiries=True),
    "crypto": Market("crypto", "UTC", (0, 0), 1440, None, 0, funding_hours=(0, 8, 16)),
    "crypto-ist": Market("crypto-ist", "UTC", (0, 0), 1440, None, 330, funding_hours=(0, 8, 16)),
}

REGIONS = {"asia": ("Asia/Tokyo", (9, 0), (17, 0)), "europe": ("Europe/London", (8, 0), (16, 30)),
           "us": ("America/New_York", (9, 30), (16, 0))}


def window_flags(ts_utc, window) -> tuple[np.ndarray, np.ndarray]:
    """For naive-UTC minute starts and a window (tz, (h0, m0), (h1, m1)) on that tz's clock, every day: (inside,
    minutes from the bar's END to the window's end, NaN outside). Clock only, so point-in-time by construction."""
    tz, (h0, m0), (h1, m1) = window
    lt = pd.DatetimeIndex(ts_utc).tz_localize("UTC").tz_convert(tz)
    mins = np.asarray(lt.hour * 60 + lt.minute)
    a, b = h0 * 60 + m0, h1 * 60 + m1
    inside = (mins >= a) & (mins < b) if a < b else (mins >= a) | (mins < b)
    left = np.where(inside, (b - (mins + 1)) % 1440, np.nan).astype(float)
    return inside, left


def region_flags(ts_utc, weekdays_only: bool = True) -> dict[str, np.ndarray]:
    """For naive-UTC minute starts: True where the minute is inside each region's local business hours (on that
    region's local weekdays when weekdays_only). Depends only on the clock, so it is point-in-time by construction."""
    t = pd.DatetimeIndex(ts_utc).tz_localize("UTC")
    out = {}
    for name, (tz, (h0, m0), (h1, m1)) in REGIONS.items():
        lt = t.tz_convert(tz)
        mins = lt.hour * 60 + lt.minute
        inside = (mins >= h0 * 60 + m0) & (mins < h1 * 60 + m1)
        if weekdays_only:
            inside &= lt.weekday < 5
        out[name] = np.asarray(inside)
    return out
