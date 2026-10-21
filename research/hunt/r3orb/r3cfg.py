"""R3 settings (PREREG.md): the ORB family deep study (ORB, ORB Fresh, ORB Sweep, Range Fade) on the 2021-2026 Dhan lake.

    lake files   $IRAALGO_LAKE (default <cache>/lake): opt_<UND>_WEEK_<YEAR>.parquet (Dhan "rollingoption": the NEAREST
                 expiry, on or after the day, ATM-10..ATM+10 strikes chosen each minute) and index_1m_<UND|INDIAVIX>_<YEAR>
                 fetched with `rclone copy gdrive:IraAlgo-data-lake/{options_rolling_1m,index_1m} ...` (see run.sh)
    caches       R3_OUT (default <cache>/r3orb): per-session pickles (big: never in git)
    results      research/hunt/r3orb/results (small JSON / markdown only)

Holdout (PREREG.md part A, "Holdout"): the indices on which these four arms' exact rules were never run. Design =
BANKNIFTY (the app's only ORB-family index) + NIFTY; holdout = FINNIFTY + MIDCPNIFTY + SENSEX. Nothing in R3 reads a
holdout index's option prices before `study.py holdout`, which refuses a second read.
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
OUT = Path(os.environ.get("R3_OUT", CACHE.root / "r3orb")).expanduser()
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"

DESIGN_UNDS = ("BANKNIFTY", "NIFTY")
HOLDOUT_UNDS = ("FINNIFTY", "MIDCPNIFTY", "SENSEX")
UNDS = DESIGN_UNDS + HOLDOUT_UNDS
BSE = {"SENSEX"}
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
# The app's BANKNIFTY stop / target is 40 premium points (OrbRules.STOP_POINTS / TARGET_POINTS; Sweep target 80). The
# other indices use the same rule scaled to their price level (PREREG.md A2): NIFTY / FINNIFTY ~ half of BANKNIFTY
# (R20 / R22 used 20 for NIFTY), MIDCPNIFTY ~ a quarter, SENSEX ~ 1.5x.
PTS = {"BANKNIFTY": 40.0, "NIFTY": 20.0, "FINNIFTY": 20.0, "MIDCPNIFTY": 10.0, "SENSEX": 60.0}
FIRST_DAY = date(2021, 8, 1)          # the lake's options start in Aug 2021 (MIDCPNIFTY 2022, SENSEX 2023)
LAST_DAY = date(2026, 10, 12)         # the lake ends Mon 12 Oct 2026; 13 Oct 2026 is a forward example only
YEARS = (2021, 2022, 2023, 2024, 2025, 2026)

LOTS = {  # NSE / BSE lot in force (research/obuy/config.py LOT_SCHEDULE)
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


def is_holdout_und(und: str) -> bool:
    return und in HOLDOUT_UNDS


TWINS = 1000
SEED = 3
BH_Q = 0.10
