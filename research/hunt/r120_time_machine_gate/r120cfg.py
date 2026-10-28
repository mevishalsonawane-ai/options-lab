"""R120 settings (PREREG.md, 28 Oct 2026): the time machine's verdict as a pre-entry gate on every arm's trades.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): copied with rclone from gdrive:IraAlgo-data-lake (never in git)
    trades    $R120_OUT/trades (default ~/.cache/iraalgo/r120/trades): one CSV per arm with the ask fields (never in git)
    results   research/hunt/r120_time_machine_gate/results (small JSON / markdown only)

Pure: periods, rules, gates. Every time-machine setting is the app's (tm.py), none is R120's.
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
OUT = Path(os.environ.get("R120_OUT", CACHE_ROOT / "r120")).expanduser()
TRADES = OUT / "trades"
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
ENTRIES = ROOT / "research" / "experiments" / "entries"
ENTRIES_LAST_DAY = "2026-10-27"   # every family logged to 27 Oct (R119 included); R120's own entries are not counted

# periods (PREREG section 2), as R101-R119
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


def in_wf(d: date) -> bool:
    return WF_START <= d <= DESIGN_END


# rules (PREREG section 3): which verdicts are TAKEN
RULES = {
    "SKIP_AGAINST": dict(take=("edge", "coin flip", "unknown"), label="skip entries the time machine calls 'against'"),
    "EDGE_ONLY": dict(take=("edge",), label="take only entries the time machine calls 'edge'"),
}

# arms planned (PREREG section 1); every one counts 2 tests in the family, tested or not
ARMS = ("R72", "R51", "R52", "LIQ", "SOLO", "HERO", "MOM60", "ORB_ORB", "ORB_FRESH", "ORB_SWEEP", "ORB_FADE", "VIXDIV",
        "PINE_ST_EMA_5M", "PINE_ST_EMA_15M", "PINE_MATRIX_5M", "MCX_NG_EVE", "MCX_SILVERM_AM", "MCX_US_NIGHT_SILVER",
        "MCX_TREND12M")
NOT_TESTABLE = {
    "FT3": "the app passes no stop and no target, so its time machine always says 'unknown'",
    "NIGHT_R3": "held overnight: the app skips the replay ('no intraday replay')",
}
M_R120 = 2 * len(ARMS)

TWINS = 2000
SEED = 120
GATE_P = 0.05
GATE_Q = 0.10
MIN_FINAL = 40

ARM_NAME = {
    "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "R52": "R52 Open outside value",
    "LIQ": "Liquidity 15+5", "SOLO": "Solo 2.0", "MOM60": "IraThinker mom60 (ntfy)", "HERO": "Hero Rs 1,000 ticket",
    "ORB_ORB": "ORB", "ORB_FRESH": "ORB Fresh", "ORB_SWEEP": "ORB Sweep", "ORB_FADE": "Range Fade",
    "VIXDIV": "VIX divergence", "PINE_ST_EMA_5M": "Pine Supertrend+EMA 5m", "PINE_ST_EMA_15M": "Pine Supertrend+EMA 15m",
    "PINE_MATRIX_5M": "Pine Matrix 5m", "MCX_NG_EVE": "NG evening breakout", "MCX_SILVERM_AM": "SILVERM morning",
    "MCX_US_NIGHT_SILVER": "US-night silver", "MCX_TREND12M": "MCX 12-month trend", "FT3": "FT3 near-ATM morning",
    "NIGHT_R3": "Night (R3)",
}
