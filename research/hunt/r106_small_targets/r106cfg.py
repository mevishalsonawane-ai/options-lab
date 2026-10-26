"""R106 settings (PREREG.md): fixed small rupee targets (and 1:1 rupee stops, re-entries, a day goal) on the paper arms.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, index_daily, options_rolling_1m (WEEK)
    stores    R51's Amendment A clean stores (R51_CLEAN=1); the post window (14-19 Oct) uses its own stores built to
              19 Oct (R106_POST=1), so the stores R101 used stay exactly as they were
    trades    $R106_OUT (default ~/.cache/iraalgo/r106): priced candidates and trades (never in git)
    results   research/hunt/r106_small_targets/results (small JSON / markdown only)

Pure: periods, rules and gates. Importing it puts R101's folder (and through it R51 / R64 / R71 / R72) on the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R101 = HERE.parent / "r101_arms_enhance"
for p in (str(R101), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

POST = os.environ.get("R106_POST", "0") == "1"
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R106_OUT", CACHE_ROOT / "r106")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
POST_FLAG = RESULTS / "POST_READ"

# periods (PREREG section 3): identical to R101 plus the post window
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
WF_START = date(2025, 4, 1)
WF_Q = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
        (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)
POST_START = date(2026, 10, 14)
POST_END = date(2026, 10, 19)

ARMS = ("LIQ", "R72", "R51", "R52")
ARM_NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20",
            "R52": "R52 Open outside value", "SOLO": "Solo 2.0"}

# the arms' last entry rows for a re-entry (PREREG section 2): minutes after 09:15
def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


LAST_ENTRY = {"LIQ": row(14, 0), "R72": row(15, 10), "R51": row(15, 10), "R52": row(15, 10)}
MAX_FILLS_PER_INDEX_DAY = 5          # RE5
DAY_MAX_TRADES = 5                   # DAY5K
DAY_GOAL = 5000.0                    # DAY5K: closed trades' net that day

# the 9 rules (PREREG section 2): target (Rs net per lot), stop mode, re-entry, day goal
RULES = {
    "T500": dict(target=500.0, stop="own", re=False, goal=False),
    "T1000": dict(target=1000.0, stop="own", re=False, goal=False),
    "T1500": dict(target=1500.0, stop="own", re=False, goal=False),
    "T500S": dict(target=500.0, stop="1:1", re=False, goal=False),
    "T1000S": dict(target=1000.0, stop="1:1", re=False, goal=False),
    "T1500S": dict(target=1500.0, stop="1:1", re=False, goal=False),
    "T1000R": dict(target=1000.0, stop="own", re=True, goal=False),
    "T1000SR": dict(target=1000.0, stop="1:1", re=True, goal=False),
    "DAY5K": dict(target=1000.0, stop="1:1", re=True, goal=True),
}
RULE_IDS = tuple(RULES)
LABEL = {
    "T500": "sell at +Rs 500, own stop", "T1000": "sell at +Rs 1,000, own stop", "T1500": "sell at +Rs 1,500, own stop",
    "T500S": "+Rs 500 / -Rs 500 (1:1)", "T1000S": "+Rs 1,000 / -Rs 1,000 (1:1)", "T1500S": "+Rs 1,500 / -Rs 1,500 (1:1)",
    "T1000R": "+Rs 1,000, own stop, re-enter after a target (<= 5 an index a day)",
    "T1000SR": "+Rs 1,000 / -Rs 1,000, re-enter after a target (<= 5 an index a day)",
    "DAY5K": "+Rs 1,000 / -Rs 1,000, re-enter, stop the day at 5 trades or +Rs 5,000",
}

# gates (PREREG section 5; never relaxed)
TWINS = 500
SEED = 106
GATE_Q = 0.10
MIN_FINAL = 40
EQUAL_NET = 0.05                     # "equal net": within 5% of B0's
SMALLER_RISK = 0.20                  # "clearly smaller": worst day and drawdown each >= 20% smaller
PRIOR_TESTS = {"R64": 15, "R65": 30, "R66": 15, "R67": 15, "R68": 21, "R69": 15, "R70": 4, "R71": 16, "R72": 12,
               "R73": 72, "R74": 11, "R75": 40, "R101": 20, "R104": 227}
N_PRIOR = sum(PRIOR_TESTS.values())  # 513, taken as p = 1 (none passed)


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    if POST_START <= d <= POST_END:
        return "post"
    return None
