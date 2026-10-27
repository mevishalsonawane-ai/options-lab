"""R115 settings (PREREG.md): price-action filters and one exit on the four paper arms.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, index_daily, options_rolling_1m WEEK (Drive)
    stores    R51's Amendment A clean stores (R51_CLEAN=1), built by research/hunt/r51/r51data.py build
    results   research/hunt/r115_price_action_arms/results (small JSON only; trades never in git)

Pure: periods, tests and gates. Importing it puts R112's folder (and through it R106 / R101 / R73 / R51) on the path.
27 Oct 2026.
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
_R112 = HERE.parent / "r112_donchian_morning"
for p in (str(_R112), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods (PREREG section 4): identical to R101 / R106 / R112 / R113
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
WF_START = date(2025, 4, 1)
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)
OPEN_SLICE_ROW = 15            # fills before 09:30 (row 15) = the "09:15-09:30 entries" (R113)

ARMS = ("R72", "R51", "R52", "LIQ")
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "R52": "R52 Open outside value",
            "LIQ": "Liquidity 15+5"}

# the family (PREREG section 3): id -> (arm, kind, rule)
TESTS = {
    "Q72": ("R72", "filter", "quality"), "Q51": ("R51", "filter", "quality"),
    "O72": ("R72", "filter", "no_outside"), "O51": ("R51", "filter", "no_outside"),
    "F72": ("R72", "filter", "fresh"), "F51": ("R51", "filter", "fresh"),
    "N72": ("R72", "filter", "compression"), "N51": ("R51", "filter", "compression"),
    "X72": ("R72", "exit", "failed_break"), "X51": ("R51", "exit", "failed_break"),
    "QL": ("LIQ", "filter", "quality"), "QO": ("R52", "filter", "quality"),
}
LABEL = {
    "quality": "strong break bar (body >= 60% of range, close in the top / bottom 25% the trade's way)",
    "no_outside": "no outside-bar break",
    "fresh": "fresh break only (previous session's high / low not already taken earlier today)",
    "compression": "previous session an NR4 or inside day (keep only)",
    "failed_break": "exit when a 5-min index bar closes back inside the channel",
}
# reported only (PREREG section 3): the Q rules with the 09:20 break exempt
REPORTED = {"Q72e": ("R72", "filter", "quality_ex0920"), "Q51e": ("R51", "filter", "quality_ex0920")}

BODY_MIN = 0.60
CLOSE_ZONE = 0.25
N_CH = 20

# parity anchors (PREREG section 2): (trades, net)
PUBLISHED = {
    ("R72", "design"): (3071, 216929), ("R72", "final"): (1202, 171366),
    ("R51", "design"): (4208, 50425), ("R51", "final"): (1670, 134743),
    ("R52", "design"): (706, 290573), ("R52", "final"): (293, 48612),
    ("LIQ", "design"): (881, 257249), ("LIQ", "final"): (357, 11155),
}

# gates (PREREG section 5; never relaxed)
TWINS = 2000
SEED = 115
GATE_Q = 0.10
MIN_FINAL = 40
MIN_CHANGED = 30
# every family in research/experiments/entries/ dated up to 26 Oct 2026 (2,126 entries), each counted as the larger of
# its entry count and its summed variants_tried (r115lib.count_prior recomputes it)
N_PRIOR_ALL = 9_300_034
M_ALL = N_PRIOR_ALL + len(TESTS)
N_PRIOR_R64 = 5841            # the repo's usual count (R64-R114, as R114), reported only
M_R64 = N_PRIOR_R64 + len(TESTS)
MONTH_LIMIT = -13_000.0


def in_period(d: date, period: str) -> bool:
    if period == "design":
        return START <= d <= DESIGN_END
    if period == "wf":
        return WF_START <= d <= DESIGN_END
    if period == "final":
        return FINAL_START <= d <= FINAL_END
    raise ValueError(period)
