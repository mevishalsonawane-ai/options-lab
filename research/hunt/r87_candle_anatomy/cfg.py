"""R87 settings (PREREG.md): candle anatomy on NIFTY, BANKNIFTY and SENSEX, then at most 3 option-buying rules.

    raw      $R87_RAW (default ~/.cache/iraalgo/r87/raw): Dhan chunks from fetch.py (never committed)
    built    $R87_OUT (default ~/.cache/iraalgo/r87/built): per-instrument minute tables and candle tables (never committed)
    results  research/hunt/r87_candle_anatomy/results (small JSON / CSV / markdown only)
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
RAW = Path(os.environ.get("R87_RAW", CACHE / "r87" / "raw")).expanduser()
OUT = Path(os.environ.get("R87_OUT", CACHE / "r87" / "built")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UNDS = ("NIFTY", "BANKNIFTY", "SENSEX")
BSE = {"SENSEX"}
TFS = (1, 5, 15)                      # candle sizes in minutes, anchored at 09:15
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "SENSEX": 100.0}

# ------------------------------------------------------------------------------------------------ split (PREREG 2)
START = date(2021, 8, 4)              # first index minute Dhan serves
DESIGN_END = date(2025, 10, 14)
FINAL_START = date(2025, 10, 15)      # locked holdout: the last ~12 months, read ONCE
END = date(2026, 10, 16)
FOLDS = ((date(2021, 8, 4), date(2022, 12, 31)), (date(2023, 1, 1), date(2023, 12, 31)),
         (date(2024, 1, 1), date(2024, 12, 31)), (date(2025, 1, 1), date(2025, 10, 14)))
RULE_START = date(2024, 11, 20)       # option rules: NSE weekly-expiry change (Boss, 15 Oct: nothing before it)
RULE_FOLDS = ((date(2025, 2, 1), date(2025, 4, 30)), (date(2025, 5, 1), date(2025, 7, 31)),
              (date(2025, 8, 1), date(2025, 10, 14)))

# ------------------------------------------------------------------------------------------------ session grid
OPEN_MIN = 9 * 60 + 15
NROWS = 375                           # 09:15 .. 15:29


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - OPEN_MIN


SQ_ROW = row(15, 10)
SQ_ROW_EXPIRY = row(15, 5)
TOD = ((row(9, 15), "open"), (row(10, 0), "morning"), (row(11, 30), "midday"), (row(13, 30), "afternoon"),
       (row(14, 45), "close"))

# lots in force (research/hunt/r51/r51cfg.py LOTS, the same table)
LOTS = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
    "SENSEX": [("2000-01-01", 10), ("2024-11-28", 20)],
}


def lot(und: str, d: date) -> int:
    q = LOTS[und][0][1]
    for s, n in LOTS[und]:
        if d.isoformat() >= s:
            q = n
    return q


# ------------------------------------------------------------------------------------------------ gates (PREREG)
MIN_N = 50                            # cells with fewer events are shown, not tested
MIN_N_GRID = 200
Q = 0.10
TWINS = 500
SEED = 87
