"""R176 settings (PREREG.md): Boss's Rs 500 step trail per lot (15 Nov 2026) on Liquidity 15+5, R72 Breadth Donchian and
R51 Donchian 20 (+ Supertrend), 1-3 lots, with and without R151's pickers, side by side with today's rules.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake); ends 19 Oct 2026
    stores    R176_POST=0 (default): R51's Amendment A clean stores built to 13 Oct (design, where B0 reproduces);
              R176_POST=1: the stores built to 19 Oct (R106's clean_post; final and the 6-month window, as R172).
              Set before this module is imported (it sets R152_POST before R152 / R106 are imported).
    cache     $R176_OUT (default ~/.cache/iraalgo/r176): priced trades (never in git)
    results   research/hunt/r176_step_trail_500/results (small JSON / markdown only)

Pure: periods, amounts, tests, gates, the step level. Importing it puts R152's folder (and through it R112 / R106 / R101 /
R51) on the path. Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import math
import os
import sys
from datetime import date
from pathlib import Path

POST = os.environ.get("R176_POST", "0") == "1"
os.environ["R152_POST"] = "1" if POST else "0"
os.environ["R51_CLEAN"] = "1"

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R152 = HERE.parent / "r152_donchian_2k"
for p in (str(R152), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r152cfg as K152  # noqa: E402  (R152's periods and the R112 / R106 / R101 / R51 paths)

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R176_OUT", CACHE_ROOT / "r176")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods (PREREG section 3): R152's design / walk-forward / final, and R172's last 6 months
START, DESIGN_END, WF_START, WF_Q = K152.START, K152.DESIGN_END, K152.WF_START, K152.WF_Q
FINAL_START, FINAL_END = K152.FINAL_START, K152.FINAL_END
W6_START, W6_END = date(2026, 4, 20), date(2026, 10, 19)
PERIODS = ("design", "wf", "final", "six")
PERIOD_NAME = {"design": "design (Nov 2024 - Mar 2026)", "wf": "walk-forward (Apr 2025 - Mar 2026)",
               "final": "final (1 Apr - 13 Oct 2026)", "six": "last 6 months (20 Apr - 19 Oct 2026)"}
STORES_OF = {"design": False, "final": True, "six": True}          # phase -> needs R176_POST=1

ARMS = ("LIQ", "R72", "R51")
ARM_NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend"}
PICKER_ARMS = ("R72", "R51")
LOTS = (1, 2, 3)

# parity (PREREG section 1), 1 lot, today's rules: (trades, net)
PARITY = {
    ("LIQ", "design"): (881, 257249), ("R72", "design"): (3071, 216929), ("R51", "design"): (4208, 50425),
    ("LIQ", "final"): (357, 11155), ("R72", "final"): (1200, 170336), ("R51", "final"): (1668, 131546),
}
PICKER_PARITY_FINAL = {"R72": (651, 220118), "R51": (692, 160101)}

# the rule (PREREG section 2), per lot, before charges
STEP = 500.0             # Rs a lot: the entry stop, the step and the gap
TICK = 0.05
VARIANTS = ("A", "S", "B", "B+S")
LABEL = {
    "A": "today's rules",
    "S": "today's rules + Rs 500 step trail (Boss's rule)",
    "B": "today's rules + R151 picker (as live)",
    "B+S": "R151 picker + Rs 500 step trail",
}
TESTS = tuple((a, n) for a in ARMS for n in LOTS)                  # 9

# twins and gates (PREREG sections 6, 7)
TWINS = 500
SEED = 176
GATE_Q = 0.10
MIN_FINAL = 40
MAX_DD = 40000.0
WORST_MONTH = -13000.0
N_PRIOR = 3925 + 45      # R173 Amendment B's count of every earlier test + R175's 45 (pre-registered, never run)
M_TOTAL = N_PRIOR + len(TESTS)       # 3,979


def ceil_tick(x: float, tick: float = TICK) -> float:
    """Round a sell-stop premium UP to the tick (the safe side: a higher stop locks at least the stated rupees)."""
    return float(math.ceil(round(x / tick, 6)) * tick)


def step_amount(lots: int) -> float:
    return STEP * lots


def step_locked(best: float, lots: int) -> float | None:
    """Rupees (before charges, whole position) the step trail locks at a best open P&L of `best`; None below +1,000 L.
    (floor(best / 500L) - 1) x 500L: +500L at 1,000L, +1,000L at 1,500L, ..."""
    u = step_amount(lots)
    if not (best == best) or best < 2.0 * u - 1e-9:
        return None
    return (math.floor(best / u + 1e-9) - 1.0) * u


def month_of(d: date) -> str:
    return f"{d.year:04d}-{d.month:02d}"


def months_between(lo: date, hi: date) -> tuple:
    out, y, m = [], lo.year, lo.month
    while (y, m) <= (hi.year, hi.month):
        out.append(f"{y:04d}-{m:02d}")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return tuple(out)


W6_MONTHS = months_between(W6_START, W6_END)


def span_of(period: str) -> tuple:
    return {"design": (START, DESIGN_END), "wf": (WF_START, DESIGN_END), "final": (FINAL_START, FINAL_END),
            "six": (W6_START, W6_END)}[period]
