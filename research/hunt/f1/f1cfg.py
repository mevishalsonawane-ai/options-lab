"""F1 Strategy Factory (India) settings: PREREG.md (committed 33fc686 before any outcome). Research only, 14 Oct 2026.

Data: the brain's cube and feature store ($IRAALGO_CACHE, default ~/.cache/iraalgo), built from the Dhan lake with
`python -m research.brain.refresh --source lake`. F1's own tables go to <cache>/f1 (never git) and to Drive
(gdrive:IraAlgo-data-lake/derived/f1_factory). Results (statistics, no market data) go to results/.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (str(ROOT), str(ROOT / "research")):
    if p not in sys.path:
        sys.path.insert(0, p)

from research.brain import config as BC  # noqa: E402

CACHE = BC.paths()
OUT = Path(os.environ.get("F1_OUT", CACHE.root / "f1")).expanduser()
RESULTS = HERE / "results"
DRIVE = "gdrive:IraAlgo-data-lake/derived/f1_factory"
HOLD_FLAG = OUT / "HOLDOUT_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")       # MIDCPNIFTY dropped (PREREG amendment 2)
BSE = {"SENSEX"}
STEP = {u: BC.STRIKE_STEP[u] for u in UNDS}
LOT = {"NIFTY": 65, "BANKNIFTY": 35, "FINNIFTY": 60, "SENSEX": 20}   # today's lots (PREREG 3)

# Periods (PREREG 2)
DESIGN = ((date(2021, 8, 1), date(2022, 12, 14)), (date(2024, 7, 15), date(2026, 4, 13)))
HOLDOUT = (date(2023, 1, 1), date(2024, 6, 30))
FIT = ((date(2021, 8, 1), date(2022, 12, 14)), (date(2024, 7, 15), date(2024, 12, 31)))
SELECT = ((date(2025, 1, 1), date(2026, 4, 13)),)
FOLDS = {"2022": (date(2022, 1, 1), date(2022, 12, 14)), "2024H2": (date(2024, 7, 15), date(2024, 12, 31)),
         "2025": (date(2025, 1, 1), date(2025, 12, 31)), "2026": (date(2026, 1, 1), date(2026, 4, 13))}


def within(d: date, spans) -> bool:
    return any(a <= d <= b for a, b in spans)


def part(d: date) -> str:
    """design | holdout | none (embargo or never used)."""
    if within(d, DESIGN):
        return "design"
    if HOLDOUT[0] <= d <= HOLDOUT[1]:
        return "holdout"
    return "none"


def fold(d: date) -> str | None:
    for k, (a, b) in FOLDS.items():
        if a <= d <= b:
            return k
    return None


# Minute rows: row r = the bar starting 09:15 + r. A decision at row t fills at the open of t + 1.
SCREEN_ROWS = tuple(range(15, 316, 15))      # 09:30 .. 14:30 every 15 minutes (21)
RULE_ROWS = tuple(range(15, 316, 5))         # 09:30 .. 14:30 every 5 minutes (61)
FILL_WINDOW = 3
ROW_1505, ROW_1510 = 350, 355
HORIZONS = (15, 30, 60)

# Arms (PREREG 3)
STOP_PCT = 0.15
LOCK_ACT, LOCK_GIVE = 1000.0, 500.0          # rupees a lot (Boss: +Rs 1,000 then keep Rs 500, trail Rs 500)
TIME_STOP_MIN, TIME_STOP_GAIN = 30, 0.05
CUT_SHORT_RS = 2000.0
STRIKES = {"atm": 0, "itm1": 1}
EXITS = ("x1", "x2")
SIDES = {"ce": 1, "pe": -1}

# Screen (PREREG 4)
MAX_NAN = 0.80
MAX_LEVELS = 6
TOP_K = 12
BH_Q = 0.10
MIN_IC = 0.02

# Rules and gates (PREREG 5, 6)
MAX_RULES = 20
N_TWINS = 500
MIN_TRADES = 40
MAX_DD = 20000.0
QUINT = 0.20
SEED = 20261014
