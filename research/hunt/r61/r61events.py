"""R61 pre-listed event calendar (PREREG section 4, block CX). Frozen with the pre-registration: no date is added after
a result is seen.

- RBI policy and Union Budget days: the lists of research/obuy/strategies/gc_common.py (_RBI, _BUDGET), copied here and
  checked equal by test_r61.py. Both are announced during NSE hours (RBI 10:00, Budget 11:00), and their dates are
  known weeks ahead, so the flag is known before any trade that day.
- US FOMC decisions and US CPI releases: research/hunt/strad_crypto/events.py (FOMC, CPI). Both come out after the NSE
  close (FOMC 14:00 ET, CPI 08:30 ET = evening IST), so the session they hit is the FIRST NSE session after the date.
  (No Nov 2025 CPI date is listed: the US shutdown shifted the releases; the list has 24 Oct and 18 Dec 2025.)
"""
from __future__ import annotations

from datetime import date

BUDGET = ["2021-02-01", "2022-02-01", "2023-02-01", "2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01"]
RBI = ["2020-08-06", "2020-10-09", "2020-12-04",
       "2021-02-05", "2021-04-07", "2021-06-04", "2021-08-06", "2021-10-08", "2021-12-08",
       "2022-02-10", "2022-04-08", "2022-06-08", "2022-08-05", "2022-09-30", "2022-12-07",
       "2023-02-08", "2023-04-06", "2023-06-08", "2023-08-10", "2023-10-06", "2023-12-08",
       "2024-02-08", "2024-04-05", "2024-06-07", "2024-08-08", "2024-10-09", "2024-12-06",
       "2025-02-07", "2025-04-09", "2025-06-06", "2025-08-06", "2025-10-01", "2025-12-05",
       "2026-02-06", "2026-04-08", "2026-06-05", "2026-08-07", "2026-10-01", "2022-05-04"]
# the app's EventCalendar.kt has 2026-08-06 for the Aug 2026 RBI policy; the research list (above) is used and the
# difference is reported in HUNT_R61_SOLO_3.md


def _d(xs) -> set:
    return {date.fromisoformat(x) for x in xs}


def us_lists() -> tuple:
    import importlib.util
    from pathlib import Path
    p = Path(__file__).resolve().parents[1] / "strad_crypto" / "events.py"
    spec = importlib.util.spec_from_file_location("r61_us_events", p)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return list(m.FOMC), list(m.CPI)


def flags(sessions: list) -> dict:
    """{session: (event_rbi_budget, post_us_event)} for the sorted NSE sessions given."""
    india = _d(RBI) | _d(BUDGET)
    fomc, cpi = us_lists()
    us = sorted(_d(fomc) | _d(cpi))
    ss = sorted(sessions)
    out = {}
    for i, d in enumerate(ss):
        prev = ss[i - 1] if i else None
        # a US release on date e (after the NSE close) hits the first session after e: prev <= e < d
        post = any((prev is None or e >= prev) and e < d for e in us) if prev is not None else False
        out[d] = (1.0 if d in india else 0.0, 1.0 if post else 0.0)
    return out
