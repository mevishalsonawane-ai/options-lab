"""R206 settings (PREREG.md): how reliable is the arms' direction call on the index, and is a call other signals agree with
more reliable? Index-only: no option file is read. Plain-English notes, 20 Nov 2026.

    lake      ~/.cache/iraalgo/r206/lake (r206data.py: the dhan-data branch's index minutes, 8 Oct 2021 - 5 Oct 2026)
    stores    ~/.cache/iraalgo/r206/r51/clean (R51's day stores built from index files only)
    results   research/hunt/r206_direction/results (small JSON / markdown only)

Importing this module sets the environment the R51 stack reads; import it before any of them.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
HUNT = HERE.parent
for p in (str(ROOT), str(ROOT / "research"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

HOME = Path.home() / ".cache" / "iraalgo"
OUT = Path(os.environ.get("R206_OUT", HOME / "r206")).expanduser()
LAKE = OUT / "lake"
RESULTS = HERE / "results"
OLD_FLAG = RESULTS / "OLD_READ"


def set_env():
    os.environ["IRAALGO_LAKE"] = str(LAKE)
    os.environ["IRAALGO_CACHE"] = str(OUT / "cache")
    os.environ["R51_OUT"] = str(OUT / "r51")
    os.environ["R51_CLEAN"] = "1"
    os.environ.pop("R51_STALE", None)
    os.environ["R176_POST"] = "0"


FIRST_DAY, LAST_DAY = date(2021, 10, 8), date(2026, 10, 5)
STORE = (FIRST_DAY, FIRST_DAY, LAST_DAY)                     # warm-up, "first option day" (unused: no options), last day

ERAS = {"old": (date(2022, 4, 1), date(2024, 11, 19)), "recent": (date(2024, 11, 21), LAST_DAY)}
ERA_NAME = {"old": "OLD 1 Apr 2022 - 19 Nov 2024 (the test)", "recent": "RECENT 21 Nov 2024 - 5 Oct 2026 (where the lead was seen)"}
YEARS = {"old": {"2022": (date(2022, 4, 1), date(2022, 12, 31)), "2023": (date(2023, 1, 1), date(2023, 12, 31)),
                 "2024": (date(2024, 1, 1), date(2024, 11, 19))},
         "recent": {"2024-25": (date(2024, 11, 21), date(2025, 12, 31)), "2026": (date(2026, 1, 1), LAST_DAY)}}

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
MARKET = "NIFTY"
LIQ_BOOKS = {"BANKNIFTY": (15, 5), "FINNIFTY": (30, 5), "MIDCPNIFTY": (15, 5)}
SOURCES = ("R72", "R51", "LIQ")
SRC_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "LIQ": "Liquidity 15+5"}
NROWS = 375
FIRST_ROW, LAST_ROW = 5, 355                                 # entry minutes 09:20 .. 15:10
H1, H2 = 15, 30                                              # horizons in minutes (primary, secondary)
EMA_N = 20

# id -> (source, label of group A, label of group B, what it asks)
TESTS = {
    "H1": ("R51", "r72", "an R72 signal on the same index, minute and side"),
    "H2": ("LIQ", "donch", "an R51 or R72 signal on the same index, minute and side"),
    "H3": ("R72", "five", "all 5 indices broke (against 4 of 5)"),
    "H4": ("R72", "r51", "an R51 signal on the same index, minute and side"),
    "H5": ("R72", "day", "with the day (index above its first open for a call, below for a put)"),
    "H6": ("LIQ", "day", "with the day"),
    "H7": ("R72", "mkt", "with the market (NIFTY's last 15-minute close on the trade's side of its EMA20)"),
    "H8": ("LIQ", "mkt", "with the market"),
}

BOOT = 5000
SEED = 206
GATE_P = 0.05
GATE_Q = 0.10
MIN_N = 200
MIN_EDGE = 0.02                            # A's share right at least 2 points above the source's own
YEARS_NEEDED = 2
N_PRIOR = 4880                             # entry files on the branch before R206
M_TOTAL = N_PRIOR + len(TESTS)             # 4,888
