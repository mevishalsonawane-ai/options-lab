"""R114 settings (PREREG.md): R72 and R51 + Supertrend mornings only (entries by 09:55), closed at 10:00, inside the
app's full four-arm capped paper book (R108's model).

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, index_daily, options_rolling_1m WEEK (Drive)
    stores    R51's Amendment A clean stores (R51_CLEAN=1); the post window (14-19 Oct) reads R106's stores built to
              19 Oct in their own folder (R114_POST=1 -> R112_POST=1 -> R106_POST=1)
    cache     $R114_OUT (default ~/.cache/iraalgo/r114): walks and trades (never in git)
    results   research/hunt/r114_donchian_close10/results (small JSON / markdown only)

Pure: periods, rules and gates. Importing it puts R112's, R108's and R106's folders on the path.
First committed as "R113" in 749734f; renumbered 26 Oct 2026 before any number (PREREG.md).
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
POST = os.environ.get("R114_POST", "0") == "1"
if POST:
    os.environ["R112_POST"] = "1"            # -> R106_POST: the stores built to 19 Oct (clean_post)
_R112 = HERE.parent / "r112_donchian_morning"
for p in (str(_R112), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r112cfg as K12                         # noqa: E402  (puts r73, r108, r106 and through them r101 / r51 on the path)

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R114_OUT", CACHE_ROOT / "r114")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
POST_FLAG = RESULTS / "POST_READ"

# periods (PREREG section 3): identical to R101 / R106 / R108 / R112
START, DESIGN_END, WF_START = K12.START, K12.DESIGN_END, K12.WF_START
FINAL_START, FINAL_END = K12.FINAL_START, K12.FINAL_END
POST_START, POST_END = K12.POST_START, K12.POST_END
row = K12.row

# the book's arms, in the app's order (R108)
ARMS = ("LIQ", "R72", "R51", "R52")
DONCHIAN = ("R72", "R51")
ARM_NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend",
            "R52": "R52 Open outside value"}

# the rules (PREREG sections 2 and 5): (last entry row = the signal bar's close, forced close row) for R72 and R51 only;
# Liquidity and R52 keep today's rules in every book
RULES = {
    "BOOK0": (None, None),                    # today's rules (base, not a test)
    "X10": (row(9, 55), row(10, 0)),          # THE test: entries by the 09:55 bar close, all sold at the 10:00 open
    "C0945": (row(9, 40), row(9, 45)),        # neighbouring clocks, reported only
    "C1015": (row(10, 10), row(10, 15)),
    "C1030": (row(10, 25), row(10, 30)),
    "C1100": (row(10, 55), row(11, 0)),
}
TEST = "X10"
CLOCKS = ("C0945", "C1015", "C1030", "C1100")
LABEL = {
    "BOOK0": "today's book: all four arms, entries 09:20-15:10, today's exits, cap 2 a way",
    "X10": "R72 and R51: entries by 09:55, all sold at 10:00; Liquidity and R52 as today; cap 2 a way",
    "C0945": "the same with entries by 09:40, sold at 09:45",
    "C1015": "the same with entries by 10:10, sold at 10:15",
    "C1030": "the same with entries by 10:25, sold at 10:30",
    "C1100": "the same with entries by 10:55, sold at 11:00",
}
CAPS = ("CAP2", "NOCAP")

# parity anchors (PREREG section 1): R108's published four-arm books with PLAIN R51 (trades, net); None = not published
R108_PUBLISHED = {
    ("CAP2", "design"): (3456, -128786), ("CAP2", "wf"): (None, 25383), ("CAP2", "final"): (1384, 15955),
    ("NOCAP", "design"): (6520, 230649), ("NOCAP", "final"): (2538, 138560),
}

# gates (PREREG section 4 and 4a; never relaxed)
TWINS = 100_000
SEED = 113                                    # as first written (renumbering changed only the name)
GATE_Q = 0.10
MIN_CHANGED = 30
PRIOR_TESTS = {"R64-R111 (as R112)": 5819, "R112": 10, "R113": 11}
N_PRIOR = sum(PRIOR_TESTS.values())           # 5,840
M_TOTAL = N_PRIOR + 1                         # 5,841 with R114's one test

# the account rule (CLAUDE.md)
MONTH_LIMIT = -13_000.0
DD_LIMIT = 40_000.0


def in_period(d: date, period: str) -> bool:
    if period == "design":
        return START <= d <= DESIGN_END
    if period == "wf":
        return WF_START <= d <= DESIGN_END
    if period == "final":
        return FINAL_START <= d <= FINAL_END
    if period == "post":
        return POST_START <= d <= POST_END
    raise ValueError(period)
