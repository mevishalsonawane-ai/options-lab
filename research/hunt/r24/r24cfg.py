"""R24 settings (PREREG.md). Research only, 13 Oct 2026.

Data: the brain's cube built from the Dhan lake (`python -m research.brain.cube build --source lake`), in the brain
cache ($IRAALGO_CACHE, default ~/.cache/iraalgo). The what-if table goes to <cache>/whatif (never git), and to Drive
(`gdrive:IraAlgo-data-lake/derived/r24_whatif/`) with its catalog. Results (tables, no market data) go to results/.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (str(ROOT), str(ROOT / "research"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

from research.brain import config as BC  # noqa: E402

CACHE = BC.paths()
WHATIF = Path(os.environ.get("R24_WHATIF", CACHE.root / "whatif")).expanduser()
OUT = Path(os.environ.get("R24_OUT", CACHE.root / "r24")).expanduser()
RESULTS = HERE / "results"
DRIVE = "gdrive:IraAlgo-data-lake/derived/r24_whatif"
HOLD_FLAG = OUT / "HOLDOUT_READ"

UNDS = ("NIFTY", "BANKNIFTY")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
OFFSETS = tuple(range(-3, 4))
SIDES = (1, -1)                       # +1 call, -1 put

# Periods (PREREG.md, the lake notes / R18 amendment 1)
LAST_EXAMINED = date(2026, 4, 13)
HOLDOUT_START = date(2026, 4, 27)
MIN_HOLD_SESSIONS = 100
N_BLOCKS = 4

# The minute grid: row r = the bar starting 09:15 + r. Entry (fill) bars 09:20 .. 14:45.
ENTRY_FIRST, ENTRY_LAST = 5, 330
FILL_WINDOW = 3                       # the fill may land in the entry minute .. +2
ROW_1505, ROW_1510, ROW_1520 = 350, 355, 365
HORIZONS = (15, 30, 60)

# Costs (PREREG.md): half-spread share of the price by moneyness in ITM steps (+ ITM, 0 ATM, - OTM)
HS_BY_MNY = {3: 0.0016, 2: 0.0016, 1: 0.0016, 0: 0.0016, -1: 0.0020, -2: 0.0025, -3: 0.0035}
TICK = 0.05

# Step 2
GRID_EVERY = 5                        # back-tracking rows: entries every 5 minutes (09:20, 09:25, .. 14:45)
BIG = 50.0                            # net % at the 15:05 exit: >= +50 big winner, <= -50 big loser
MIN_SUPPORT = 0.005
MIN_DAYS = 100
MAX_ITEMS = 3

# Step 3
MAX_RULES = 8
MIN_LIFT = 1.3
OVERLAP = 0.70
FAMILIES = ("orb", "sweep", "liq")
TWINS_ENTRY = 1000
TWINS_PATTERN = 200
PATTERN_SUPPORT_BAND = 0.25
BH_Q = 0.05
TWIN_P = 0.05
MIN_HOLD_TRADES = 30
ACCOUNT_DD = 40_000.0
SEED = 24


def period_of(d: date) -> str:
    if d <= LAST_EXAMINED:
        return "design"
    if d < HOLDOUT_START:
        return "embargo"
    return "holdout"


def mny_of(side: int, offset: int) -> int:
    """ITM steps: + in the money, 0 ATM, - out of the money (a call below the ATM strike is in the money)."""
    return -offset if side > 0 else offset


def offset_of(side: int, mny: int) -> int:
    return -mny if side > 0 else mny
