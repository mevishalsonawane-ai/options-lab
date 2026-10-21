"""R92 settings (PREREG.md): WHEN does the ORB family (ORB, ORB Fresh, ORB Sweep, Range Fade) make money?
Boss, 21 Oct 2026: "Find if there is any specific time where the ORB family makes money."

    lake files   $IRAALGO_LAKE (default <cache>/lake): opt_<UND>_WEEK_<YEAR>.parquet and index_1m_<UND|INDIAVIX>_<YEAR>,
                 fetched on 21 Oct 2026 with
                 rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m <lake> --include "opt_*_WEEK_*"
                 rclone copy gdrive:IraAlgo-data-lake/index_1m <lake>
    caches       R92_OUT (default <cache>/r92): session pickles and outcome tables (big: never in git)
    results      research/hunt/r92_orb_time/results (small JSON / markdown only)

Reuses R3's engine (research/hunt/r3orb: signals, the exit walk, fills, scoring) and R49's dated charges and lots read
from the data (research/hunt/r49).
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R3 = HERE.parent / "r3orb"
R49 = HERE.parent / "r49"
for p in (ROOT, ROOT / "research", R3, R49, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from research.brain import config as BC  # noqa: E402

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R92_OUT", CACHE.root / "r92")).expanduser()
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
BSE = {"SENSEX"}
# R3's hour row used these two indices (design); its holdout indices were never split by time (PREREG A5).
R3_HOUR_UNDS = ("BANKNIFTY", "NIFTY")
FRESH_UNDS = ("FINNIFTY", "MIDCPNIFTY", "SENSEX")

# Window and split (PREREG A2)
FIRST_DAY = date(2021, 8, 1)          # the lake's options start Aug 2021 (MIDCPNIFTY Feb 2022, SENSEX May 2023)
DESIGN_END = date(2026, 3, 31)
HOLDOUT_START = date(2026, 4, 1)
LAST_DAY = date(2026, 10, 19)         # the lake's last session (fetched 21 Oct 2026)
UNSEEN_START = date(2026, 10, 13)     # no ORB-family study read these sessions (R3 ended 12 Oct, R49 13 Oct NIFTY)


def period(d: date) -> str:
    if d < FIRST_DAY or d > LAST_DAY:
        return "out"
    return "design" if d <= DESIGN_END else "holdout"


# Lots: the day's contract lot read from the data (R49's rule, r49data.lot_from_volume) over every lot these indices
# have had since 2021; the calendar table is the fallback (R3's before 20 Nov 2024, R49's from then on).
KNOWN_LOTS = {"NIFTY": {25, 50, 65, 75}, "BANKNIFTY": {15, 25, 30, 35}, "FINNIFTY": {25, 40, 60, 65},
              "MIDCPNIFTY": {50, 75, 120, 140}, "SENSEX": {10, 15, 20}}
R49_FROM = date(2024, 11, 20)

# Statistics (PREREG A7)
TWINS = 500
SEED = 92
BH_Q = 0.10
MIN_DESIGN_TRADES = 100
MIN_HOLDOUT_TRADES = 30
MONTH_STOP = -13000.0
