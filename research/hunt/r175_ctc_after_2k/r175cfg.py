"""R175 settings (PREREG.md): after +Rs 2,000 open profit, move the stop to the entry price (cost-to-cost), on Liquidity
15+5, R72 Breadth Donchian and R51 Donchian 20 (+ Supertrend), 1-3 lots, with and without R151's pickers.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied 15 Nov 2026; ends 19 Oct 2026
    stores    R51's Amendment A clean stores built to 19 Oct (R106's clean_post folder): importing this module sets
              R152_POST=1 (-> R106_POST=1) before R152 / R106 are imported (as R172)
    cache     $R175_OUT (default ~/.cache/iraalgo/r175): priced trades (never in git)
    results   research/hunt/r175_ctc_after_2k/results (small JSON / CSV / markdown only)

Pure: periods, variants, amounts, tests and gates. Importing it puts R152's folder (and through it R112 / R106 / R101 /
R51) on the path. Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

os.environ.setdefault("R152_POST", "1")          # the stores built to 19 Oct (the lake's end), as R172
os.environ["R51_CLEAN"] = "1"

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R152 = HERE.parent / "r152_donchian_2k"
for p in (str(R152), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r152cfg as K152  # noqa: E402  (R152's periods and the R112 / R106 / R101 / R51 paths)

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R175_OUT", CACHE_ROOT / "r175")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods (PREREG section 3): R152's design / walk-forward / final, and R172's last 6 months
START, DESIGN_END, WF_START, WF_Q = K152.START, K152.DESIGN_END, K152.WF_START, K152.WF_Q
FINAL_START, FINAL_END = K152.FINAL_START, K152.FINAL_END
W6_START, W6_END = date(2026, 4, 20), date(2026, 10, 19)
W6_MONTHS = ("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10")

ARMS = ("LIQ", "R72", "R51")
ARM_NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend"}
PICKER_ARMS = ("R72", "R51")
LOTS = (1, 2, 3)

# parity (PREREG section 1), 1 lot, today's rules, on the stores built to 19 Oct
PARITY = {
    ("LIQ", "design"): (881, 257249), ("R72", "design"): (3071, 216929), ("R51", "design"): (4208, 50425),
    ("LIQ", "final"): (357, 11155), ("R72", "final"): (1200, 170336), ("R51", "final"): (1668, 131546),
}

# the rules (PREREG section 2). trigger: whole-position open profit before charges, (high - real fill) x qty.
TRIGGER = 2000.0
LOCK_PLUS = 500.0        # V3
TRAIL = 1000.0           # V5
TICK = 0.05
VARIANTS = ("V1", "V2", "V3", "V4", "V5")
LABEL = {
    "A": "today's rules",
    "V1": "+Rs 2,000 -> stop to the entry price (Boss's rule)",
    "V2": "+Rs 2,000 -> stop to entry + charges (net Rs 0)",
    "V3": "+Rs 2,000 -> stop to entry + Rs 500",
    "V4": "+Rs 2,000 a lot -> stop to the entry price",
    "V5": "+Rs 2,000 -> stop to entry, then trail Rs 1,000 under the best",
}
SHORT = {"A": "today", "V1": "V1 CTC", "V2": "V2 CTC+charges", "V3": "V3 +Rs 500", "V4": "V4 per lot",
         "V5": "V5 CTC+trail"}
TESTS = tuple((a, v, n) for a in ARMS for v in VARIANTS for n in LOTS)      # 45

# twins and gates (PREREG sections 6, 7)
TWINS = 500
SEED = 175
GATE_Q = 0.10
WIN_UP = 0.05
MIN_FINAL = 40
N_PRIOR = 3925                       # R173 Amendment B's count (every earlier study); R172 was not a test
M_TOTAL = N_PRIOR + len(TESTS)       # 3,970


def trigger_amount(variant: str, lots: int) -> float:
    """Rs of whole-position open profit (before charges) that moves the stop: Rs 2,000, or Rs 2,000 a lot for V4."""
    return TRIGGER * lots if variant == "V4" else TRIGGER


def month_of(d: date) -> str:
    return f"{d.year:04d}-{d.month:02d}"


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    return None
