"""R29 settings (PREREG.md): multi-EMA (ribbon) patterns on the Dhan lake, 14 Oct 2026.

    lake files   $IRAALGO_LAKE (default <cache>/lake): index_1m_<UND|INDIAVIX>_<YEAR>.parquet, opt_<UND>_WEEK_<YEAR>.parquet
                 fetched with `rclone copy gdrive:IraAlgo-data-lake/{index_1m,options_rolling_1m} ...` (run.sh)
    caches       R29_OUT (default <cache>/r29): index minutes, option tables, events, trade tables (big: never in git;
                 the big tables go to Drive gdrive:IraAlgo-data-lake/derived/r29)
    results      research/hunt/r29/results (small JSON / markdown only)

Splits (PREREG.md): discovery 2021-22, selection 2024, walk-forward 2025 and 2026, LOCKED holdout 2023 (read once by
`study.py holdout`).
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

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R29_OUT", CACHE.root / "r29")).expanduser()
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
BSE = {"SENSEX"}

PARTS = {                      # part -> years (calendar)
    "discovery": (2021, 2022),
    "selection": (2024,),
    "wf2025": (2025,),
    "wf2026": (2026,),
    "holdout": (2023,),
}
DESIGN = ("discovery", "selection")
LAST_DAY = date(2026, 10, 12)

LOTS = {  # research/obuy/config.py LOT_SCHEDULE
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35)],
    "FINNIFTY": [("2000-01-01", 40), ("2024-07-24", 25), ("2024-11-28", 65), ("2025-12-31", 60)],
    "SENSEX": [("2000-01-01", 10), ("2024-11-28", 20)],
    "MIDCPNIFTY": [("2000-01-01", 75), ("2024-11-28", 120)],
}


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def part_of(d: date) -> str:
    for p, ys in PARTS.items():
        if d.year in ys:
            return p
    raise ValueError(d)


# session grid: row m = the 1-minute bar starting 09:15 + m
N = 375
SIG_FIRST, SIG_LAST = 14, 329          # signal bars closing 09:30 .. 14:45 (row m closes at 09:15 + m + 1)
SQ_NORMAL, SQ_EXPIRY = 365, 350        # square-off at the open of 15:20 / 15:05
WARM_SESSIONS = 60                     # events only after 60 sessions of index history
MAX_PER_DAY = 3
TWINS_MINE = 20
TWINS = 1000
SEED = 29
TFS = (1, 3, 5, 15)
