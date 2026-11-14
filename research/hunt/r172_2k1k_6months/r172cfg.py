"""R172 settings (PREREG.md): the last 6 months (20 Apr - 19 Oct 2026) of Liquidity 15+5, R72 and R51 (+ Supertrend) with
and without Boss's fixed +Rs 2,000 / -Rs 1,000 for the whole position (before charges), 1-3 lots, with and without R151's
pickers. A reporting backtest of Boss's chosen rule (14 Nov 2026), not a gate test.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied 14 Nov 2026; ends 19 Oct 2026
    stores    R51's Amendment A clean stores built to 19 Oct (R106's clean_post folder): importing this module sets
              R152_POST=1 (-> R106_POST=1) before R152 / R106 are imported
    results   research/hunt/r172_2k1k_6months/results (small JSON / markdown only)

Pure: window, months, lots, amounts. Importing it puts R152's folder (and through it R112 / R106 / R101 / R51) on the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

os.environ.setdefault("R152_POST", "1")          # the stores built to 19 Oct (the lake's end)
os.environ["R51_CLEAN"] = "1"

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R152 = HERE.parent / "r152_donchian_2k"
for p in (str(R152), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import r152cfg  # noqa: E402,F401  (R112 / R106 / R101 / R51 paths)

RESULTS = HERE / "results"

# window (PREREG section 1): the lake ends 19 Oct 2026; the last 6 months
START = date(2026, 4, 20)
END = date(2026, 10, 19)
MONTHS = ("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10")
# engine check (PREREG section 1): the published finals over 1 Apr - 13 Oct 2026 at 1 lot, today's rules
CHECK_START = date(2026, 4, 1)
CHECK_END = date(2026, 10, 13)
PUBLISHED = {"LIQ": (357, 11155), "R72": (1202, 171366), "R51": (1670, 134743)}
PICKER_PUBLISHED = {"R72": (652, 219057), "R51": (693, 159040)}

ARMS = ("LIQ", "R72", "R51")
ARM_NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend"}
PICKER_ARMS = ("R72", "R51")
LOTS = (1, 2, 3)

TARGET = 2000.0          # Boss, 14 Nov: +Rs 2,000 for the whole position, before charges
STOP = 1000.0            # -Rs 1,000, only when tighter than the arm's own stop
TICK = 0.05
VARIANTS = ("A", "A+2k1k", "B", "B+2k1k")
LABEL = {
    "A": "today's rules",
    "A+2k1k": "today's rules + Rs 2,000 target / Rs 1,000 stop",
    "B": "today's rules + R151 picker (as live)",
    "B+2k1k": "today's rules + R151 picker + Rs 2,000 target / Rs 1,000 stop",
}


def month_of(d: date) -> str:
    return f"{d.year:04d}-{d.month:02d}"


def in_window(d: date) -> bool:
    return START <= d <= END
