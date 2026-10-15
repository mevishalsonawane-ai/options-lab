"""R25 settings (PREREG.md): the deep hero-zero study on the 6-year Dhan lake.

    lake files   $IRAALGO_LAKE (default <cache>/lake): opt_<UND>_WEEK_<YEAR>.parquet, index_1m_<UND|INDIAVIX>_<YEAR>.parquet
                 fetched with `rclone copy gdrive:IraAlgo-data-lake/{options_rolling_1m,index_1m} ...` (see run.sh)
    caches       R25_OUT (default <cache>/r25): the per-expiry-day pickles, census and anatomy tables (big: never in git)
    results      research/hunt/r25/results (small JSON / markdown only)

The holdout is the OLDEST block of the lake (PREREG.md, "Holdout"): every expiry day up to 31 Dec 2022. Nothing in
R25 reads it before `study.py holdout`, which refuses a second read.
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
OUT = Path(os.environ.get("R25_OUT", CACHE.root / "r25")).expanduser()
RESULTS = HERE / "results"

UNDS = ("NIFTY", "BANKNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "SENSEX": 100.0}
BSE = {"SENSEX"}
# BANKNIFTY weeklies ended with the 13 Nov 2024 expiry (SEBI, Nov 2024); later BANKNIFTY expiries are monthlies and are
# not part of this study (PREREG.md).
LAST_WEEKLY = {"BANKNIFTY": date(2024, 11, 13)}

# The locked holdout (PREREG.md): every expiry day on or before this date. Design = every expiry day after it.
HOLDOUT_END = date(2022, 12, 31)
FORWARD_ONLY = date(2026, 10, 13)        # Boss's day: not in the lake (it ends 12 Oct 2026); never used for any choice

LOTS = {  # NSE / BSE lot in force (research/obuy/config.py LOT_SCHEDULE)
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35)],
    "SENSEX": [("2000-01-01", 10), ("2024-11-28", 20)],
}


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def is_holdout(d: date) -> bool:
    return d <= HOLDOUT_END


TWINS = 1000
SEED = 25
