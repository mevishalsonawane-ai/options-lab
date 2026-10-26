"""R112 settings (PREREG.md): the two Donchian arms, entries until 10:00 and +Rs 1,500 / -Rs 1,000 an order.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, index_daily, options_rolling_1m WEEK (Drive)
    stores    R51's Amendment A clean stores (R51_CLEAN=1); the post window (14-19 Oct) uses R106's stores built to
              19 Oct in their own folder (R112_POST=1 -> R106_POST=1), so the stores R101 used stay as they were
    cache     $R112_OUT (default ~/.cache/iraalgo/r112): priced trades (never in git)
    results   research/hunt/r112_donchian_morning/results (small JSON / markdown only)

Pure: periods, rules and gates. Importing it puts R108's and R106's folders (and through them R101 / R51 / R64 / R73)
on the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
POST = os.environ.get("R112_POST", "0") == "1"
if POST:
    os.environ["R106_POST"] = "1"            # R106's post stores (clean_post, to 19 Oct)
for _d in ("r73", "r108_same_direction_cap", "r106_small_targets"):
    p = str(HERE.parent / _d)
    if p not in sys.path:
        sys.path.insert(0, p)
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R112_OUT", CACHE_ROOT / "r112")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
POST_FLAG = RESULTS / "POST_READ"

# periods (PREREG section 3): identical to R101 / R106 / R108
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
WF_START = date(2025, 4, 1)
WF_Q = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
        (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)
POST_START = date(2026, 10, 14)
POST_END = date(2026, 10, 19)


def row(hh: int, mm: int) -> int:
    """Minute row from 09:15 (row 0 = 09:15)."""
    return hh * 60 + mm - (9 * 60 + 15)


# the arms (PREREG section 1); 'R51' here is R51 Donchian 20 WITH its Supertrend (10, 3) filter, as on paper
ARMS = ("R72", "R51")
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend"}

# parity anchors (PREREG section 1): (trades, net) published by R101 (R72, plain R51) and R73 T7-P (R51 + Supertrend,
# research/hunt/r73/results/design.json variants T7-P and final.json tests T7-P; same signals, exits and engine)
PUBLISHED = {
    "R72": dict(design=(3071, 216929), final=(1202, 171366)),
    "R51plain": dict(design=(4498, 9998), final=(1781, 81845)),
    "R51": dict(design=(4208, 50425), final=(1670, 134743)),
}

# the rules (PREREG section 2)
CUT_A = row(10, 0)          # reading A: the signal bar closes by 10:00 (entry row <= 45, the 09:55 bar)
CUT_B = row(9, 55)          # reading B: the last entry is the 09:50 bar (it closes 09:55; bought ~09:55) ...
CLOSE_B = row(10, 0)        # ... and everything still open is sold at the open of the 10:00 minute
TARGET = 1500.0             # +Rs 1,500 net an order (1 lot, after charges)
STOP = 1000.0               # -Rs 1,000 net an order (replaces the -15% premium stop)

RULES = {
    "B0": dict(cut=None, ts=False, close=None),
    "CUT10": dict(cut=CUT_A, ts=False, close=None),
    "TS": dict(cut=None, ts=True, close=None),
    "M10": dict(cut=CUT_A, ts=True, close=None),
    "CUT10X": dict(cut=CUT_B, ts=False, close=CLOSE_B),
    "M10X": dict(cut=CUT_B, ts=True, close=CLOSE_B),
}
TESTS = ("CUT10", "TS", "M10", "CUT10X", "M10X")
LABEL = {
    "B0": "today's rules: entries all day (09:20-15:10), today's exits",
    "CUT10": "entries until 10:00 only, today's exits",
    "TS": "entries all day, +Rs 1,500 / -Rs 1,000 an order",
    "M10": "entries until 10:00, +Rs 1,500 / -Rs 1,000 an order (Boss's rule, reading A)",
    "CUT10X": "entries until 09:55, everything closed at 10:00, today's exits",
    "M10X": "entries until 09:55, +Rs 1,500 / -Rs 1,000, everything closed at 10:00 (Boss's rule, reading B)",
}
# twins (PREREG section 5): the all-day rule with the same exits (entry twin); whether the exits changed (exit twin)
ENTRY_BASE = {"CUT10": "B0", "CUT10X": "B0", "M10": "TS", "M10X": "TS"}
EXIT_TWIN = {"TS", "M10", "CUT10X", "M10X"}

# gates (PREREG section 5; never relaxed)
TWINS = 100_000
SEED = 112
GATE_Q = 0.10
MIN_FINAL = 40
EQUAL_NET = 0.05
SMALLER_RISK = 0.20
# R64-R107 as R108 counted them (5,777), R108 7, R110 25, R111 10: all taken as p = 1 (none passed)
PRIOR_TESTS = {"R64-R107": 5777, "R108": 7, "R110": 25, "R111": 10}
N_PRIOR = sum(PRIOR_TESTS.values())  # 5,819

# the account rule (CLAUDE.md), reported
MONTH_LIMIT = -13_000.0
DD_LIMIT = 40_000.0


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    if POST_START <= d <= POST_END:
        return "post"
    return None
