"""R107 settings (PREREG.md): the GEX meter and the danger meter used to trade MORE (2 lots, or a 3rd same-direction
position) on the days they flag, on the paper arms, gated against today's rule, "always more" and random-day twins.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m (incl. INDIAVIX), index_daily, options_rolling_1m
    gex       $R107_GEX (default ~/.cache/iraalgo/gex): R28's per-minute GEX tables gex_<UND>.parquet, copied from
              gdrive:IraAlgo-data-lake/derived/r28_gex (never in git)
    stores    R51's Amendment A clean stores (R51_CLEAN=1), as R101 / R106
    trades    $R107_OUT (default ~/.cache/iraalgo/r107): priced trades (never in git)
    results   research/hunt/r107_meters_more/results (small JSON / markdown only)

Pure: periods, tests and gates. Importing it puts R106's folder (and through it R101 / R51 / R64) on the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R106 = HERE.parent / "r106_small_targets"
for p in (str(R106), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
GEX_DIR = Path(os.environ.get("R107_GEX", CACHE_ROOT / "gex")).expanduser()
OUT = Path(os.environ.get("R107_OUT", CACHE_ROOT / "r107")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods (PREREG section 1): identical to R101 / R106
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
WF_START = date(2025, 4, 1)
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)
PERIODS = ("design", "wf", "final")

ARMS = ("LIQ", "R72", "R51", "R52")
ARM_NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20",
            "R52": "R52 Open outside value", "BOOK": "the paper book (all four arms, same-direction cap)"}
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
# the paper book's same-minute order (PREREG section 3)
BOOK_ARM_ORDER = ("R72", "R51", "LIQ", "R52")

# GEX readings (PREREG section 2): grid rows of the bars that close at 09:20 and 10:00
ROW_0920 = 4
ROW_1000 = 44
FIRST_ROW_1000 = 45          # entries at row >= 45 use the 10:00 reading, rows 5-44 the 09:20 one

CAP_TODAY = 2                # SameDirectionCap.DEFAULT_MAX
CAP_MORE = 3
LOTS_MORE = 2

# the 11 tests (PREREG section 3): id -> (scope, meter, kind)
#   scope: an arm, or "BOOK" (all four arms with the cap); meter: "gex" / "danger"; kind: "size" or "slot";
#   flag: "neg" (negative GEX), "flagged" (careful / paper-only), "normal"
TESTS = {
    "G2-LIQ": dict(scope="LIQ", meter="gex", kind="size", flag="neg"),
    "G2-R72": dict(scope="R72", meter="gex", kind="size", flag="neg"),
    "G2-R51": dict(scope="R51", meter="gex", kind="size", flag="neg"),
    "G2-R52": dict(scope="R52", meter="gex", kind="size", flag="neg"),
    "D2-LIQ": dict(scope="LIQ", meter="danger", kind="size", flag="flagged"),
    "D2-R72": dict(scope="R72", meter="danger", kind="size", flag="flagged"),
    "D2-R51": dict(scope="R51", meter="danger", kind="size", flag="flagged"),
    "D2-R52": dict(scope="R52", meter="danger", kind="size", flag="flagged"),
    "G3": dict(scope="BOOK", meter="gex", kind="slot", flag="neg"),
    "D1": dict(scope="BOOK", meter="danger", kind="size", flag="normal"),
    "D3": dict(scope="BOOK", meter="danger", kind="slot", flag="flagged"),
}
TEST_IDS = tuple(TESTS)
LABEL = {
    "G2": "2 lots when the trade's index shows negative dealer GEX (09:20 / 10:00 reading)",
    "D2": "2 lots on the danger meter's flagged days (careful / paper-only)",
    "G3": "a 3rd same-direction position when the new entry's index shows negative GEX",
    "D1": "2 lots for every arm on the danger meter's normal days",
    "D3": "a 3rd same-direction position on the danger meter's flagged days",
}


def label(test: str) -> str:
    return LABEL[test.split("-")[0]]


# gates (PREREG section 6; never relaxed)
TWINS = 10_000
SEED = 107
GATE_Q = 0.10
MIN_CHANGED = 30
PRIOR_TESTS = {"R17": 3, "R28": 10, "R28b": 378, "R36": 4, "R37": 4, "R105": 20, "R105B": 40, "R105C": 60,
               "R105D": 4, "R105E": 6, "R105F": 8, "R106": 36}
N_PRIOR = sum(PRIOR_TESTS.values())  # 573, taken as p = 1 (none passed)

# the account rule (CLAUDE.md): reported, never relaxed
ACCOUNT_DD = 40_000.0
ACCOUNT_MONTH = -13_000.0


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    return None


def in_period(d: date, period: str) -> bool:
    if period == "design":
        return START <= d <= DESIGN_END
    if period == "wf":
        return WF_START <= d <= DESIGN_END
    if period == "final":
        return FINAL_START <= d <= FINAL_END
    raise ValueError(period)
