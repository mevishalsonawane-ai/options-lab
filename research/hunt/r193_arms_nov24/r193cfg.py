"""R193 settings (PREREG.md): R72, R51 and Liquidity 15+5 from 1 Nov 2024 to 13 Nov 2026 with the arms' current exits
(V4 / V5), R151's picker and FB-R72-A10, against each arm's own exits. Plain-English notes, 17 Nov 2026.

    lake      ~/.cache/iraalgo/r190/lake (R190's flat copy, with the 20 Oct - 13 Nov 2026 fill)
    stores    ~/.cache/iraalgo/r193/r51/clean (links to R190's R51 clean stores, 3 Aug 2020 - 13 Nov 2026)
    caches    ~/.cache/iraalgo/r193 (never git)
    results   research/hunt/r193_arms_nov24/results (small JSON / markdown only)

Importing this module sets the environment the R176 / R106 / R51 stack reads; import it before any of them.
Signal source of every rule: the index. Options are only bought.
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
OUT = Path(os.environ.get("R193_OUT", HOME / "r193")).expanduser()
LAKE = Path(os.environ.get("R193_LAKE", HOME / "r190" / "lake")).expanduser()
RESULTS = HERE / "results"
SPAN_FLAG = RESULTS / "SPAN_READ"


def set_env():
    """The stack's environment (before it is imported): R190's lake, R193's store links, caches under r193."""
    os.environ["IRAALGO_LAKE"] = str(LAKE)
    os.environ["IRAALGO_CACHE"] = str(OUT / "cache")
    os.environ["R51_OUT"] = str(OUT / "r51")
    os.environ["R51_CLEAN"] = "1"
    os.environ.pop("R51_STALE", None)
    os.environ["R176_POST"] = "0"          # R51_OUT/clean directly (the R190 stores reach 13 Nov)


# R190's store window (warm-up, first option day, last day)
STORE = (date(2020, 6, 1), date(2020, 8, 3), date(2026, 11, 13))

# ------------------------------------------------------------------------------------------------ windows (PREREG 2)
SPAN = (date(2024, 11, 1), date(2026, 11, 13))
WINDOWS = {"W1": (date(2024, 11, 1), date(2026, 3, 31)),
           "W2": (date(2026, 4, 1), date(2026, 11, 13)),
           "total": SPAN}
WINDOW_NAME = {"W1": "W1 1 Nov 2024 - 31 Mar 2026 (picker and A10 in-sample)",
               "W2": "W2 1 Apr - 13 Nov 2026 (picker out-of-sample; A10 unseen only from 14 Oct)",
               "total": "total 1 Nov 2024 - 13 Nov 2026"}


def window_of(d: date) -> str:
    for w in ("W1", "W2"):
        lo, hi = WINDOWS[w]
        if lo <= d <= hi:
            return w
    return ""


# ------------------------------------------------------------------------------------------------ variants (PREREG 3)
ARMS = ("R72", "R51", "LIQ")
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "LIQ": "Liquidity 15+5"}
BE_X = 0.10
A10_CUT = 1.3206
PICK_THR = {"R72": 0.4656, "R51": 0.4469}
# id -> (arm, breakeven, lock, picker, a10)
VARIANTS = {
    "R72-P": ("R72", False, "", True, False),
    "R72-V4": ("R72", True, "", True, False),
    "R72-V5": ("R72", True, "L500", True, False),
    "R72-V5A10": ("R72", True, "L500", True, True),
    "R51-P": ("R51", False, "", True, False),
    "R51-V4": ("R51", True, "", True, False),
    "R51-V5": ("R51", True, "L500", True, False),
    "R51-V5A10": ("R51", True, "L500", True, True),
    "LIQ-V4": ("LIQ", True, "", False, False),
    "LIQ-V5": ("LIQ", True, "LBE", False, False),
}
LABEL = {
    "A": "own exits, no picker (baseline)",
    "R72-P": "R72 + picker", "R72-V4": "R72 picker + breakeven at +10% (V4)",
    "R72-V5": "R72 V4 + Rs 500 step lock (V5, live)", "R72-V5A10": "R72 V5 + FB-R72-A10",
    "R51-P": "R51 + picker", "R51-V4": "R51 picker + breakeven at +10% (V4)",
    "R51-V5": "R51 V4 + Rs 500 step lock (V5, live)", "R51-V5A10": "R51 V5 + FB-R72-A10",
    "LIQ-V4": "Liquidity + breakeven at +10% (V4)", "LIQ-V5": "Liquidity V4 + breakeven at +Rs 1,000 (live)",
}
TESTS = tuple(VARIANTS)                    # 10

# ------------------------------------------------------------------------------------------------ gate (PREREG 6)
TWINS = 2000
SEED = 193
GATE_P = 0.05
GATE_Q = 0.10
MIN_TRADES = 40
TOP_N = 5
N_PRIOR = 4755                             # entry files at the branch base (origin/main d1b1d57d8)
M_TOTAL = N_PRIOR + len(TESTS)             # 4,765
ACC_DD = 40000.0
MONTH_CAP = -13000.0

# ------------------------------------------------------------------------------------------------ funded lots (PREREG 4)
ACCOUNT = 56000.0
MAX_LOTS = 3

# ------------------------------------------------------------------------------------------------ reproduction (PREREG 1a)
R186_YEAR = (date(2025, 10, 20), date(2026, 10, 19))
R186_PUB = {  # (variant, arm) -> (trades, net) at 1 lot, R186 results/year.json
    ("A", "R72"): (2281, 298253), ("A", "R51"): (3084, 221238), ("A", "LIQ"): (688, 117923),
    ("BASE", "R72"): (1069, 324629), ("BASE", "R51"): (1367, 249903), ("BASE", "LIQ"): (688, 100222),
    ("L500", "R72"): (1097, 213110), ("L500", "R51"): (1430, 162959),
    ("LBE1000", "LIQ"): (688, 98239),
}
R190_PUB = {"D1": (4571, 390101), "D3": (3721, 478594), "L1": (1323, 255293)}
