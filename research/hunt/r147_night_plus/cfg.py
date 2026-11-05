"""R147 settings (PREREG.md): Night (R3) on 5 indices, gap atlas, filters and contracts, on the Drive lake.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake (never in git)
    caches    $R147_OUT (default ~/.cache/iraalgo/r147)
    results   research/hunt/r147_night_plus/results (small JSON / markdown / the index-only atlas CSVs)
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE = Path(os.environ.get("IRAALGO_CACHE", "~/.cache/iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE / "lake")).expanduser()
OUT = Path(os.environ.get("R147_OUT", CACHE / "r147")).expanduser()
YAHOO = CACHE / "r146" / "yahoo"            # R146's daily Yahoo files (Hang Seng, S&P 500)
EVENTS = ROOT / "research" / "hunt" / "r98" / "events.csv"
RESULTS = HERE / "results"
FLAG = RESULTS / "HOLDOUT_READ.flag"
PREREG = HERE / "PREREG.md"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
BASE_UNDS = ("NIFTY", "BANKNIFTY")          # Night as the app runs it
NEW_UNDS = ("FINNIFTY", "MIDCPNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
NEAR_SERIES = {u: "WEEK" for u in UNDS}     # OI build-up and expiry days: the nearest series
TRADE_SERIES = {"NIFTY": "WEEK", "BANKNIFTY": "MONTH", "FINNIFTY": "WEEK", "MIDCPNIFTY": "WEEK", "SENSEX": "WEEK"}
BSE = {"SENSEX"}
HALF_SPREAD = {"NIFTY": 0.0016, "BANKNIFTY": 0.0016, "FINNIFTY": 0.0042, "MIDCPNIFTY": 0.0021, "SENSEX": 0.0020}
STRESS = 1.5

# periods (PREREG 3)
DESIGN_YEARS = (2022, 2023, 2024)
HOLD_START = date(2025, 1, 1)


def part(d: date) -> str:
    if d >= HOLD_START:
        return "hold"
    return "design" if d.year in DESIGN_YEARS else "warm"


# the lot in force: R27 (NIFTY / BANKNIFTY) and research/obuy/config.py (the others)
LOTS = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
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


# grid
N = 375
O, H, L, C = range(4)


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


R1519, R1515, R1529, R1419, R1449 = row(15, 19), row(15, 15), row(15, 29), row(14, 19), row(14, 49)
ENTRY_ROWS = (row(15, 20), row(15, 21), row(15, 22))
X16 = row(9, 16)
EXIT_LAST = row(9, 45)
MORN = 31                                    # next-session rows kept: 09:15 .. 09:45

# Night (NightRules)
LOC_UP, LOC_DOWN, BREADTH_MID, BUZ_MIN = 0.6, 0.4, 0.5, 0.5
Z_WINDOW, Z_MIN = 60, 20
BREADTH_MIN = 40
TV_MAX, TV_JUMP = 0.6, 2.0                   # expiry day rule (r25 / r27)
FOREIGN = 0.03                               # option row spot > 3% from the index: another index's row (r47)
FILL_GUARD = 0.20

# filters (PREREG 5)
F3_MOVE = 0.015
F6_VIX = 18.0
F7_UP, F7_DOWN = 0.8, 0.2
F8_RATIO, F8_WINDOW = 1.2, 60

SEED = 147
TWINS = 2000
