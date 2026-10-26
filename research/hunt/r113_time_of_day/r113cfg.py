"""R113 settings (PREREG.md): where each arm makes and loses its money, by time and day. Descriptive first.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): copied from gdrive:IraAlgo-data-lake on 26 Oct 2026
    trades    $R113_OUT/trades (default ~/.cache/iraalgo/r113/trades): one CSV per arm (never in git)
    results   research/hunt/r113_time_of_day/results (small JSON / markdown / PNG only)

Pure: periods, buckets and gates. 26 Oct 2026.
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
OUT = Path(os.environ.get("R113_OUT", CACHE_ROOT / "r113")).expanduser()
TRADES = OUT / "trades"
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods (PREREG section 1): identical to R101-R112
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
WF_START = date(2025, 4, 1)
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    return None


# entry / exit time buckets (minutes after midnight, [lo, hi)); PREREG section 2
NSE_BUCKETS = (("09:15-09:30", 555, 570), ("09:30-10:00", 570, 600), ("10:00-11:00", 600, 660),
               ("11:00-12:00", 660, 720), ("12:00-13:00", 720, 780), ("13:00-14:00", 780, 840),
               ("14:00-15:30", 840, 931))
MCX_BUCKETS = (("09:00-12:00", 540, 720), ("12:00-17:00", 720, 1020), ("17:00-19:00", 1020, 1140),
               ("19:00-21:00", 1140, 1260), ("21:00-23:55", 1260, 1436))
HOLD_BUCKETS = (("<=5m", 0, 5), ("5-15m", 5, 15), ("15-30m", 15, 30), ("30-60m", 30, 60), ("60-120m", 60, 120),
                (">120m", 120, 10 ** 9))
WEEKDAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
GAP_LINE = 0.25            # % (R102)
TREND_BODY = 0.6           # |close - open| / (high - low) >= 0.6: trend day (after the fact)
RANGE_BODY = 0.3           # <= 0.3: range day
FIRST_HOUR_END = 615       # 10:15: "first hour" entries 09:15-10:15

SMALL_N = 30               # a bucket with fewer trades is flagged "small"
BOOT = 2000
BAND = 0.90
SEED = 113

# Part D (PREREG section 5): entry filters, [lo, hi) on the fill minute; arms they may apply to
CORE_ARMS = ("R72", "R51", "R52", "LIQ")
RULES = {
    "MID": dict(drop=((690, 810),), arms=("R72", "R51", "R52", "LIQ"), label="no new entries 11:30-13:29"),
    "LATE": dict(drop=((840, 931),), arms=("R72", "R51", "R52"), label="no new entries at or after 14:00"),
    "NOON": dict(drop=((720, 931),), arms=("R72", "R51", "R52", "LIQ"), label="entries before 12:00 only"),
}
TWINS = 500
GATE_Q = 0.10
MIN_FINAL = 40
N_PRIOR = 5784 + 25 + 10 + 10      # to R108, plus R110, R111, R112 (all taken as p = 1)

ARM_NAME = {
    "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "R52": "R52 Open outside value",
    "LIQ": "Liquidity 15+5", "SOLO": "Solo 2.0", "MOM60": "IraThinker mom60 (R110)", "FT3": "FT3 near-ATM morning",
    "HERO": "Hero Rs 1,000 ticket", "ORB_ORB": "ORB", "ORB_FRESH": "ORB Fresh", "ORB_SWEEP": "ORB Sweep",
    "ORB_FADE": "Range Fade", "NIGHT_R3": "Night (R3)", "VIXDIV": "VIX divergence",
    "MCX_NG_EVE": "NG evening breakout", "MCX_SILVERM_AM": "SILVERM morning", "MCX_US_NIGHT_SILVER": "US-night silver",
    "MCX_TREND12M": "MCX 12-month trend", "PINE_ST_EMA_5M": "Pine Supertrend+EMA 5m",
    "PINE_ST_EMA_15M": "Pine Supertrend+EMA 15m", "PINE_MATRIX_5M": "Pine Matrix 5m",
}
MCX_UNDS = ("NATURALGAS", "NATGASMINI", "SILVERM", "SILVER", "SILVERMIC", "GOLD", "GOLDM", "CRUDEOIL", "CRUDEOILM",
            "COPPER", "ZINC", "ZINCMINI", "ALUMINIUM", "ALUMINI", "GOLDPETAL")
# a mini contract's day labels come from its parent's near-month future (same commodity, same session)
MCX_PARENT = {"SILVERMIC": "SILVERM", "ALUMINI": "ALUMINIUM", "GOLDPETAL": "GOLD", "ZINCMINI": "ZINC"}
