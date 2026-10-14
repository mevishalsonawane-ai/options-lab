"""Trading calendar: the minute grid, option expiries, inferred holidays and curated events (budget, RBI, ...).

Expiry dates come first from the contracts actually present in the data (calendar_data/expiries.csv, written by
`python -m research.brain.cube expiries` from the research-data release). Outside that span they fall back to the NSE
weekday rules below, marked source="rule". Expiry dates are scheduled weeks ahead, so using them at minute t is not
look-ahead.

Events: calendar_data/events.csv (date, und, kind, name, source). Add rows for RBI, budget, results, US CPI, ... and
every feature that reads events picks them up. Rows describe scheduled events known in advance only.
"""
from __future__ import annotations

from datetime import date, datetime, time, timedelta
from functools import lru_cache
from pathlib import Path

import numpy as np
import pandas as pd

from . import config

HERE = Path(__file__).resolve().parent
EXPIRIES_CSV = HERE / "calendar_data" / "expiries.csv"
EVENTS_CSV = HERE / "calendar_data" / "events.csv"


def grid(day: date) -> pd.DatetimeIndex:
    """The 375 minute stamps (bar starts, naive IST) of one NSE session."""
    h, m = config.SESSION_OPEN
    start = pd.Timestamp(datetime.combine(day, time(h, m)))
    return pd.date_range(start, periods=config.SESSION_MINUTES, freq="min")


def expiry_ts(d: date) -> pd.Timestamp:
    h, m = config.EXPIRY_CLOSE
    return pd.Timestamp(datetime.combine(d, time(h, m)))


# --- expiries ---------------------------------------------------------------------------------------------------------
# NSE weekday rules used only where no contract was seen in the data. (start, end, weekday, "weekly"|"last"):
# weekday 0 = Monday. A holiday moves the expiry to the previous session.
RULES = {
    "BANKNIFTY": [
        (date(2016, 1, 1), date(2023, 9, 3), 3, "weekly"),
        (date(2023, 9, 4), date(2024, 11, 13), 2, "weekly"),
        (date(2024, 11, 14), date(2024, 12, 31), 2, "last"),
        (date(2025, 1, 1), date(2025, 8, 31), 3, "last"),
        (date(2025, 9, 1), date(2099, 12, 31), 1, "last"),
    ],
    "NIFTY": [
        (date(2016, 1, 1), date(2025, 8, 31), 3, "weekly"),
        (date(2025, 9, 1), date(2099, 12, 31), 1, "weekly"),
    ],
}


@lru_cache(maxsize=None)
def observed_expiries(und: str) -> tuple[date, ...]:
    if not EXPIRIES_CSV.exists():
        return ()
    e = pd.read_csv(EXPIRIES_CSV, parse_dates=["date"])
    return tuple(sorted(e.loc[e.und == und, "date"].dt.date))


def _rule_expiries(und: str, frm: date, to: date, sessions: set[date] | None) -> list[date]:
    out = []
    for a, b, wd, kind in RULES.get(und, []):
        lo, hi = max(a, frm), min(b, to)
        if lo > hi:
            continue
        cands = []
        d = lo
        while d <= hi:
            if d.weekday() == wd:
                cands.append(d)
            d += timedelta(days=1)
        if kind == "last":
            by_month: dict[tuple[int, int], date] = {}
            for d in cands:
                by_month[(d.year, d.month)] = max(by_month.get((d.year, d.month), d), d)
            cands = sorted(by_month.values())
        for d in cands:
            if sessions:
                while d not in sessions and d > lo - timedelta(days=7):
                    d -= timedelta(days=1)
            out.append(d)
    return out


def expiries(und: str, frm: date, to: date, sessions: set[date] | None = None) -> pd.DataFrame:
    """Expiry dates in [frm, to] with their source ("data" or "rule")."""
    obs = [d for d in observed_expiries(und)]
    rows = [(d, "data") for d in obs if frm <= d <= to]
    if obs:
        lo, hi = obs[0], obs[-1]
        rule = [d for d in _rule_expiries(und, frm, to, sessions) if d < lo or d > hi]
    else:
        rule = _rule_expiries(und, frm, to, sessions)
    rows += [(d, "rule") for d in rule]
    return pd.DataFrame(sorted(rows), columns=["date", "source"])


def next_expiry(und: str, day: date, sessions: set[date] | None = None) -> tuple[date | None, str]:
    e = expiries(und, day, day + timedelta(days=45), sessions)
    if e.empty:
        return None, ""
    return e.date.iloc[0], e.source.iloc[0]


# --- holidays and events ----------------------------------------------------------------------------------------------
def holidays_inferred(sessions: list[date]) -> list[date]:
    """Weekdays between the first and last session with no session in the data. These are exchange holidays OR missing
    data; VALIDATION.md (data lake) is the place to tell them apart."""
    s = set(sessions)
    out, d = [], min(sessions)
    while d <= max(sessions):
        if d.weekday() < 5 and d not in s:
            out.append(d)
        d += timedelta(days=1)
    return out


@lru_cache(maxsize=None)
def events() -> pd.DataFrame:
    e = pd.read_csv(EVENTS_CSV, parse_dates=["date"])
    e["date"] = e.date.dt.date
    return e


def events_on(und: str, day: date) -> list[str]:
    e = events()
    return sorted(e.loc[(e.date == day) & e.und.isin(["ALL", und]), "kind"].unique())


def write_observed_expiries(frames: dict[str, pd.DataFrame]) -> Path:
    """frames: und -> DataFrame(day, expiry) of the nearest contract seen per day. Writes calendar_data/expiries.csv."""
    rows = []
    for und, f in frames.items():
        f = f.dropna()
        for d in sorted(set(f.expiry)):
            rows.append(dict(date=d, und=und, source="research-data release contracts"))
    out = pd.DataFrame(rows)
    if EXPIRIES_CSV.exists():                         # keep other indices' rows (expiry_infer.py: lake-inferred days)
        keep = pd.read_csv(EXPIRIES_CSV)
        out = pd.concat([keep[~keep.und.isin(list(frames))], out.astype({"date": str})], ignore_index=True)
    out = out.sort_values(["und", "date"])
    EXPIRIES_CSV.parent.mkdir(parents=True, exist_ok=True)
    out.to_csv(EXPIRIES_CSV, index=False)
    observed_expiries.cache_clear()
    return EXPIRIES_CSV


def dte_days(day: date, exp: date | None) -> float:
    return float((exp - day).days) if exp else np.nan
