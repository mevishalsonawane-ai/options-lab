"""R30 settings (PREREG.md): every parameter at once for CE / PE buying, 14 Oct 2026.

Reuses R29's lake tables (index minutes, option O/H/L/C/V by strike, expiry days) from R29_OUT, and reads the lake's
option Parquet again for OI / IV. Big tables live in R30_OUT (default <cache>/r30), never in git.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R29 = ROOT / "research" / "hunt" / "r29"
for p in (ROOT, ROOT / "research", R29, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r29cfg as K29  # noqa: E402

LAKE = K29.LAKE
OUT29 = K29.OUT
OUT = Path(os.environ.get("R30_OUT", K29.CACHE.root / "r30")).expanduser()
RESULTS = HERE / "results"
UNDS = K29.UNDS
STEP = K29.STEP
BSE = K29.BSE
lot = K29.lot

# F1's locked holdout: never loaded by R30 (PREREG.md)
F1_HOLD = (date(2023, 1, 1), date(2024, 6, 30))
FIRST, LAST = date(2021, 8, 1), date(2026, 10, 12)

FOLDS = [  # (name, train_end_exclusive, test_start, test_end)
    ("F1", date(2023, 1, 1), date(2024, 7, 1), date(2024, 12, 31)),
    ("F2", date(2025, 1, 1), date(2025, 1, 1), date(2025, 12, 31)),
    ("F3", date(2026, 1, 1), date(2026, 1, 1), date(2026, 10, 12)),
]

GRID = list(range(14, 330, 5))          # rows of 5-minute bar closes 09:30 .. 14:45 (row m closes at 09:15 + m + 1)
assert len(GRID) == 64 and GRID[0] == 14 and GRID[-1] == 329
SEED = 30
TWINS = 1000
MAX_PER_DAY = 3


def allowed(d: date) -> bool:
    return FIRST <= d <= LAST and not (F1_HOLD[0] <= d <= F1_HOLD[1])


def check_day(d: date):
    if F1_HOLD[0] <= d <= F1_HOLD[1]:
        raise PermissionError(f"{d} is in F1's locked holdout (1 Jan 2023 - 30 Jun 2024): R30 never loads it")
