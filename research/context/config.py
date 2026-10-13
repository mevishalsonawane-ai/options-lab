"""Settings for the market-context studies (TODO I6, I10, I11, I12; 13 Oct 2026).

ONE SWITCH for the data: SOURCE.
  "release" (default today) - the GitHub release `research-data` (BANKNIFTY Feb 2024 - Feb 2026, NIFTY Apr 2024 - Apr 2026),
  "lake"    - the 6-year Dhan data lake once it is published (research/data/CATALOG.json + fetch_release.py),
  "auto"    - the lake where it has the day, the release for the rest.
Set it with the environment variable IRAALGO_CONTEXT_SOURCE; nothing else changes. Every script re-runs from scratch on the
new source (its cache is keyed by the source). No broker API is ever called from here.
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

SOURCE = os.environ.get("IRAALGO_CONTEXT_SOURCE", "release")

UNDS = ("BANKNIFTY", "NIFTY")
HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"                     # small summary tables only (committed)


def cache() -> Path:
    from research.brain.config import cache_dir
    p = cache_dir() / "context" / SOURCE
    p.mkdir(parents=True, exist_ok=True)
    return p


# The locked holdout: the last 12 months of each underlying's data (ARCHITECTURE.md L6 gate 2), read ONCE per idea.
HOLDOUT_MONTHS = 12


def holdout_from(last: date) -> date:
    return date(last.year - 1, last.month, min(last.day, 28))


# The app's arms as the research replays them (BANKNIFTY only: that is where they trade).
ARM_UND = "BANKNIFTY"
BH_Q = 0.10
