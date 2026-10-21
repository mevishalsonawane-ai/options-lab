"""R56 settings (PREREG.md): Boss's own manual NIFTY opening-range-breakout plan (Boss, 15 Oct 2026), backtested on the
market since NSE's Nov 2024 change only.

    lake files   $IRAALGO_LAKE (default <cache>/lake): opt_NIFTY_WEEK_<2024|2025|2026>.parquet and
                 index_1m_NIFTY_<2024|2025|2026>.parquet, fetched with
                 rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m <lake> --include "opt_NIFTY_WEEK_202[456].parquet"
                 rclone copy gdrive:IraAlgo-data-lake/index_1m <lake> --include "index_1m_NIFTY_202[456].parquet"
                 (never committed; deleted after the run)
    caches       R56_OUT (default <cache>/r56): the session pickle (big: never in git)
    results      research/hunt/r56/results (small JSON / markdown / one small per-trade CSV only)
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
from research.obuy.config import LOT_SCHEDULE  # noqa: E402

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R56_OUT", CACHE.root / "r56")).expanduser()
RESULTS = HERE / "results"

UND = "NIFTY"
STEP = 50.0
KNOWN_LOTS = {25, 65, 75}

# Window and split (PREREG A1 / A2). Nothing before FIRST_DAY is traded or reported.
FIRST_DAY = date(2024, 11, 20)       # NSE's Nov 2024 change (20 Nov 2024 was itself a holiday)
DESIGN_END = date(2026, 3, 31)
RECENT_START = date(2026, 4, 1)

# Grid rows: row t = the 1-minute bar that STARTS at 09:15 + t (375 rows); row t is known once that minute is over.
RANGE_ROWS = 15                      # 09:15 .. 09:29 (15 one-minute bars)
FIRST_SIG = 15                       # the 09:30 bar is the first that can signal
LAST_SIG = 344                       # the 14:59 bar (its close is known at 15:00): "no signal by 15:00 -> no trade"
SQ_ROW = 360                         # square-off at the open of the 15:15 minute
SQ_EXPIRY = 350                      # 15:05 on an expiry day (CLAUDE.md)
FILL_TRIES = 3                       # entry fills at the first option print within 3 minutes after the signal
TARGET_RS = 1000.0                   # +Rs 1,000 gross on 1 lot triggers the exit
HARD_STOP_RS = 1000.0                # context variant only: a resting Rs 1,000 stop instead of the range stop
FOREIGN_TOL = 0.03                   # R47 amendment 2: an option row whose spot is > 3% off the index is dropped
BAD_PRINT = 0.20                     # R47 amendment 1: stray-print guard on fills and on the target check
TICK = 0.05

TWINS = 1000
SEED = 56


def period(d: date) -> str:
    if d < FIRST_DAY:
        return "out"
    return "design" if d <= DESIGN_END else "recent"


def lot_table(d: date) -> int:
    """research/obuy/config.py LOT_SCHEDULE (the repo's NSE lot table): the fallback when the data cannot tell."""
    out = 0
    for s, n in LOT_SCHEDULE[UND]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def minute(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


def hm(row: int) -> str:
    m = row + 9 * 60 + 15
    return f"{m // 60:02d}:{m % 60:02d}"
