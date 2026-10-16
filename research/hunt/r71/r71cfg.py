"""R71 settings (PREREG.md): eight chart-pattern rules on the index, the 1-ITM option bought, gated as Donchian.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake; never
              committed
    stores    R51's Amendment A clean stores (R51_CLEAN=1, ~/.cache/iraalgo/r51/clean), built by r51data.py
    trades    $R71_OUT (default ~/.cache/iraalgo/r71): every priced trade (never in git)
    results   research/hunt/r71/results (small JSON / markdown only)

Pure: periods, exits and gates only (the exits are turned into R51 `Exits` by r71study.py).
"""
from __future__ import annotations

import math
import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R71_OUT", CACHE_ROOT / "r71")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods: identical to R51 / R64-R66 (Donchian's study)
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
WF_FOLDS = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
            (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))

# trade-taking: exactly as Donchian (R51 / DonchianArm)
FIRST_ROW, LAST_ROW_HM = 5, (15, 10)        # entries 09:20-15:10
MONEY = 1                                   # 1-ITM
MAX_PER_SIDE = 2

# exits (PREREG section 2); NaN locked gain = breakeven (fill + round-trip charges), R64's engine
NAN = float("nan")
LADDER_L25 = ((0.30, NAN), (0.60, 0.30), (1.00, 0.60), (0.25, 0.05))
EXITS = {
    "XB": dict(stop=0.15, target=0.30, steps=(), tmax=30, sq=(15, 15), index_stop=False),
    "XL": dict(stop=0.15, target=None, steps=LADDER_L25, tmax=30, sq=(15, 15), index_stop=False),
    "XI": dict(stop=0.25, target=None, steps=LADDER_L25, tmax=60, sq=(15, 15), index_stop=True),
}
EXIT_ORDER = ("XB", "XL", "XI")
ORIG_EXIT = "XL"

# gates (as R51; never relaxed)
TWINS = 500
SEED = 71
GATE_P = 0.05
GATE_Q = 0.10
GATE_MIN_TRADES = 40
WF_MIN = 20                                 # a fold's pick needs >= 20 trades before the fold, else the original
FINAL_MIN = 30                              # the re-tune's final pick needs >= 30 design trades, else the original
DUPLICATE_CORR = 0.6
OVERLAP_ROWS = 15


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if d >= FINAL_START:
        return "final"
    return None


def fold_of(d: date) -> int | None:
    for i, (a, b) in enumerate(WF_FOLDS):
        if a <= d <= b:
            return i
    return None


def is_breakeven(g: float) -> bool:
    return isinstance(g, float) and math.isnan(g)
