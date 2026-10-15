"""R52 settings (PREREG.md, 15 Oct 2026): the older rejected NSE/BSE index-option rules re-tested on Nov 2024 onwards.

    lake files   $IRAALGO_LAKE (default <cache>/lake): index_1m_<UND|INDIAVIX>_<YEAR>.parquet and
                 opt_<UND>_WEEK_<YEAR>.parquet from gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m), copied with
                 rclone, never committed. WEEK and MONTH are the same contract for BANKNIFTY / FINNIFTY / MIDCPNIFTY in
                 this window (NSE ended their weeklies in Nov 2024), so only WEEK is read for every index.
    caches       R52_OUT (default <cache>/r52): per-index session pickles (big: never in git)
    results      research/hunt/r52/results (small JSON / markdown only)

Window (Boss, 15 Oct 2026: "add the Nov 2024 onwards view. Don't test any data before that"):
    DESIGN      20 Nov 2024 .. 31 Mar 2026 (walk-forward folds below)
    FINAL TEST  1 Apr 2026 .. 13 Oct 2026, read ONCE by `r52study.py final` (it writes FINAL_FLAG and refuses a second
                read). Sessions before 20 Nov 2024 are loaded only as indicator warm-up: no trade is ever taken on them.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from research.brain import config as BC  # noqa: E402

CACHE = BC.cache_dir()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE / "lake")).expanduser()
OUT = Path(os.environ.get("R52_OUT", CACHE / "r52")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

FIRST_TRADE = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
LAST_DAY = date(2026, 10, 13)
WARMUP_FROM = date(2024, 9, 1)        # index-only sessions kept for indicators (never traded)

# Walk-forward on design: each fold is tested with settings chosen on the sessions before it (expanding window).
FOLDS = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
         (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
BSE = {"SENSEX"}
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
LOTS = {  # NSE / BSE lot in force (research/obuy/config.py LOT_SCHEDULE)
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35)],
    "FINNIFTY": [("2000-01-01", 40), ("2024-07-24", 25), ("2024-11-28", 65), ("2025-12-31", 60)],
    "SENSEX": [("2000-01-01", 10), ("2024-11-28", 20)],
    "MIDCPNIFTY": [("2000-01-01", 75), ("2024-11-28", 120)],
}

TWINS = 500
SEED = 52
BH_Q = 0.10
P_MAX = 0.05
MIN_FINAL_TRADES = 40


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def part_of(d: date) -> str:
    """'warmup' (never traded), 'design' or 'final'."""
    if d < FIRST_TRADE:
        return "warmup"
    return "design" if d <= DESIGN_END else "final"
