"""MCX event calendar (MB1): the IST minute of each scheduled US release that moves MCX commodities.

  EIA crude inventories   Wednesday 10:30 ET (official moved dates)   R83's calendar (research/hunt/r83/events.py)
  EIA gas storage         Thursday 10:30 ET (holiday weeks skipped)   R83's calendar
  US CPI, NFP             08:30 ET on the dates below                  R82's lists (research/hunt/r82/intraday.py, bls.gov)
  FOMC statement          14:00 ET                                     R82's list; at or after the MCX close: a flag only

The dates are copied from R82 (its module imports the app's indicator code, which this package does not need).
A release time is fixed by the calendar before the day starts, so event columns are known in advance (no look-ahead).
"""
from __future__ import annotations

from datetime import date

import pandas as pd

from research.hunt.r83 import events as R83
from research.hunt.r83.common import et_to_ist_min

FOMC = """2024-01-31 2024-03-20 2024-05-01 2024-06-12 2024-07-31 2024-09-18 2024-11-07 2024-12-18 2025-01-29 2025-03-19
2025-05-07 2025-06-18 2025-07-30 2025-09-17 2025-10-29 2025-12-10 2026-01-28 2026-03-18 2026-04-29 2026-06-17 2026-07-29
2026-09-16 2026-10-28"""
CPI = """2024-06-12 2024-07-11 2024-08-14 2024-09-11 2024-10-10 2024-11-13 2024-12-11 2025-01-15 2025-02-12 2025-03-12
2025-04-10 2025-05-13 2025-06-11 2025-07-15 2025-08-12 2025-09-11 2025-10-24 2025-12-18 2026-01-13 2026-02-13 2026-03-11
2026-04-10 2026-05-12 2026-06-10 2026-07-14 2026-08-12 2026-09-11 2026-10-14"""
NFP = """2024-06-07 2024-07-05 2024-08-02 2024-09-06 2024-10-04 2024-11-01 2024-12-06 2025-01-10 2025-02-07 2025-03-07
2025-04-04 2025-05-02 2025-06-06 2025-07-03 2025-08-01 2025-09-05 2025-11-20 2025-12-16 2026-01-09 2026-02-11 2026-03-06
2026-04-03 2026-05-08 2026-06-05 2026-07-02 2026-08-07 2026-09-04 2026-10-02"""

KINDS = ("eia", "storage", "cpi", "nfp", "fomc")
# which events a symbol's event specialist reads (PREREG section 4, L1-E)
FOR_SYMBOL = {"CRUDEOIL": ("eia", "cpi", "nfp"), "CRUDEOILM": ("eia", "cpi", "nfp"),
              "NATURALGAS": ("storage", "cpi", "nfp"), "NATGASMINI": ("storage", "cpi", "nfp")}
DEFAULT = ("cpi", "nfp")


def _listed(s: str, start: date, end: date, hh: int, mm: int) -> dict:
    out = {}
    for x in s.split():
        d = date.fromisoformat(x)
        if start <= d <= end:
            out[d] = et_to_ist_min(d, hh, mm)
    return out


def calendar(start: date, end: date) -> dict[str, dict]:
    """{kind: {IST date: release minute of the IST day}} for start..end."""
    return {"eia": R83.eia_events(start, end), "storage": R83.storage_events(start, end),
            "cpi": _listed(CPI, start, end, 8, 30), "nfp": _listed(NFP, start, end, 8, 30),
            "fomc": _listed(FOMC, start, end, 14, 0)}


def minutes_to_event(ts: pd.DatetimeIndex, cal: dict[str, dict], kinds=KINDS) -> dict[str, "pd.Series"]:
    """Per minute and kind: minutes from the bar's end to the release that day (negative after it); NaN on days
    without that release. Known from the calendar, so usable at every row."""
    ts = pd.DatetimeIndex(ts)
    d = pd.Series(ts.date, index=range(len(ts)))
    mod = pd.Series(ts.hour * 60 + ts.minute + 1, index=d.index)
    out = {}
    for k in kinds:
        rel = d.map(cal.get(k, {})).astype(float)
        out[k] = rel - mod
    return out
