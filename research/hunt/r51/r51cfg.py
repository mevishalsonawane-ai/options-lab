"""R51 settings (PREREG.md): every other cancelled / paused / failed index-option strategy, re-tested on the recent
market only (Boss, 15 Oct 2026: "add the Nov 2024 onwards view. Don't test any data before that").

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake
              (index_1m, options_rolling_1m); never committed
    cache     $R51_OUT (default ~/.cache/iraalgo/r51): per-index day stores of held contracts (never in git)
    results   research/hunt/r51/results (small JSON / markdown only)

Window: trades only from 20 Nov 2024 (NSE's weekly-expiry change) to 13 Oct 2026 (the lake's end). Index bars from
1 Sep 2024 are read only to warm up indicators; no trade is ever taken or reported before 20 Nov 2024.
Split (committed in PREREG.md before any P&L): DESIGN 20 Nov 2024 - 31 Mar 2026; FINAL TEST 1 Apr - 13 Oct 2026,
read ONCE by `study.py final` (guarded by results/FINAL_READ).
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

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
OUT = Path(os.environ.get("R51_OUT", CACHE_ROOT / "r51")).expanduser()
# Amendment A (15 Oct 2026): R51_CLEAN=1 builds and reads the stores with foreign rows and stray prints removed
# (research/hunt/lakeclean.py), in their own folder so the original stores and trade caches are never mixed with them.
CLEAN = os.environ.get("R51_CLEAN", "0") == "1"
# Stale-print step (18 Oct 2026, research/hunt/lakestale/PREREG.md): R51_CLEAN=1 R51_STALE=1 also drops stale repeated
# zero-volume prints (lakeclean step 3), in a folder of its own so the Amendment A stores stay reproducible.
STALE = CLEAN and os.environ.get("R51_STALE", "0") == "1"
if CLEAN:
    OUT = OUT / ("clean_stale" if STALE else "clean")
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# ------------------------------------------------------------------------------------------------ window and split
WARM_START = date(2024, 9, 1)          # indicator warm-up only, never a trade
START = date(2024, 11, 20)             # first tradable day (NSE weekly-expiry change)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
END = date(2026, 10, 13)               # the lake's last session

# design walk-forward folds (out-of-fold quarters; training is always every design day before the fold)
WF_FOLDS = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
            (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
BSE = {"SENSEX"}
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
TICK = 0.05
# Lots in force on each date: research/obuy/config.py LOT_SCHEDULE plus NSE's Dec 2025 revision (BANKNIFTY 30 from
# 31 Dec 2025, as research/hunt/r27/r27cfg.py found from the app's own fills) and MIDCPNIFTY's Jun 2025 (140) and
# Dec 2025 (120) revisions, which obuy's table misses.
LOTS = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
    "FINNIFTY": [("2000-01-01", 40), ("2024-07-24", 25), ("2024-11-28", 65), ("2025-12-31", 60)],
    "SENSEX": [("2000-01-01", 10), ("2024-11-28", 20)],
    "MIDCPNIFTY": [("2000-01-01", 75), ("2024-11-28", 120), ("2025-06-27", 140), ("2025-12-31", 120)],
}

# NSE / BSE minute grid: row r = 09:15 + r minutes, 375 rows (09:15 .. 15:29)
OPEN_MIN = 9 * 60 + 15
NROWS = 375


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - OPEN_MIN


SQ_ROW = row(15, 10)                   # the app's square-off
SQ_ROW_EXPIRY = row(15, 5)             # expiry-day square-off (CLAUDE.md)

# ------------------------------------------------------------------------------------------------ gates (never relaxed)
TWINS = 500
SEED = 51
GATE_P = 0.05
GATE_Q = 0.10
GATE_MIN_TRADES = 40


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def period_of(d: date) -> str | None:
    """'design', 'final' or None (outside the window)."""
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= END:
        return "final"
    return None
