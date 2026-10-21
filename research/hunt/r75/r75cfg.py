"""R75 settings (PREREG.md): Donchian, less loss. Seven levers (L1-L7, 20 grid points) on two paper baselines:
B = Donchian 20 + R73's Supertrend (10, 3) agreement, C = R72's C1 Breadth Donchian; A (plain Donchian) reported only.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake
              (index_1m incl. INDIAVIX, options_rolling_1m WEEK); never committed
    stores    R51's Amendment A clean stores (R51_CLEAN=1, ~/.cache/iraalgo/r51/clean), built by r51data.py
    trades    $R75_OUT (default ~/.cache/iraalgo/r75): every priced trade (never in git)
    results   research/hunt/r75/results (small JSON / markdown only)

r73rules.py in this folder is R73's file copied verbatim from origin/claude/r73-donchian-trend-wait (9a2c911), used
read-only for the Supertrend filter until R73 lands on integ. Pure.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
for _p in ("r71", "r66", "r65", "r64"):
    if str(HERE.parent / _p) not in sys.path:
        sys.path.insert(0, str(HERE.parent / _p))
from r71cfg import (CACHE_ROOT, DESIGN_END, FINAL_START, FIRST_ROW, LAST_ROW_HM, MAX_PER_SIDE, MONEY,  # noqa: E402,F401
                    START, WF_FOLDS)
import r66cfg as R66  # noqa: E402

OUT = Path(os.environ.get("R75_OUT", CACHE_ROOT / "r75")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# paper exits P (R66: A4 ladder + L25; Donchian's -15%, 30 minutes, square-off 15:15)
EXITS_P = R66.exit_spec(R66.OWN["Donchian"], "L25")

BASES = ("B", "C")                          # levers run on these; A is reported only
BASE_NAMES = {"A": "Donchian 20 (paper exits)", "B": "Donchian 20 + Supertrend (10, 3) agreement",
              "C": "Breadth Donchian (C1, 4 of 5 indices)"}
# PREREG section 1: the published numbers each baseline must reproduce (to the rupee)
EXPECT = {("A", "design"): 9998, ("A", "final"): 81845, ("B", "design"): 50425, ("B", "final"): 134743,
          ("C", "design"): 216929, ("C", "final"): 171366}

# PREREG section 3: the 20 grid points
LEVERS = ("L1-K2", "L1-K3", "L1-X5", "L1-X10", "L2-NEG", "L2-WEAK", "L3-F10", "L3-F25", "L3-L60", "L4-VIX", "L4-OR",
          "L4-PD", "L5-ATM", "L5-ITM2", "L6-EXP", "L6-GAP", "L6-GAPH", "L7-VOL", "L7-DD20", "L7-DD40")
TESTS = [f"{b}:{lv}" for b in BASES for lv in LEVERS]
NAMES = {
    "L1-K2": "daily cap: no new entries after 2 losses in a row (arm)",
    "L1-K3": "daily cap: no new entries after 3 losses in a row (arm)",
    "L1-X5": "daily cap: no new entries once the arm's day is -Rs 5,000",
    "L1-X10": "daily cap: no new entries once the arm's day is -Rs 10,000",
    "L2-NEG": "drop indices with a negative net on earlier data",
    "L2-WEAK": "drop the weakest index on earlier data",
    "L3-F10": "no entries before 09:30",
    "L3-F25": "no entries before 09:45",
    "L3-L60": "no entries after 14:10",
    "L4-VIX": "skip the day when India VIX is in its lowest 30% (120 sessions)",
    "L4-OR": "skip from 09:45 if the opening 30-min range < 0.5 x daily ATR",
    "L4-PD": "skip the day after a narrow day (range < 0.7 x daily ATR)",
    "L5-ATM": "buy ATM instead of 1-ITM",
    "L5-ITM2": "buy 2-ITM instead of 1-ITM",
    "L6-EXP": "skip every index on any index's expiry day",
    "L6-GAP": "skip an index on a big-gap day (gap > 0.5 x daily ATR)",
    "L6-GAPH": "half size on an index's big-gap day",
    "L7-VOL": "half size when the index's 10-day realised vol > 1.25 x its 60-day median",
    "L7-DD20": "half size while the arm is Rs 20,000 below its peak",
    "L7-DD40": "half size while the arm is Rs 40,000 below its peak",
}
L1_SPEC = {"L1-K2": ("k", 2), "L1-K3": ("k", 3), "L1-X5": ("x", 5000.0), "L1-X10": ("x", 10000.0)}
L3_SPEC = {"L3-F10": (15, None), "L3-F25": (30, None), "L3-L60": (None, 295)}   # (first row allowed, last row allowed)
OR_ROWS = 30                                # 09:15-09:44
OR_ATR = 0.5
PD_ATR = 0.7
GAP_ATR = 0.5
VIX_LOOK, VIX_Q = 120, 0.30
VOL_N, VOL_LOOK, VOL_X = 10, 60, 1.25
DD_SPEC = {"L7-DD20": 20000.0, "L7-DD40": 40000.0}
ATR_N = 14
HALF = 0.5
MONEY_SPEC = {"L5-ATM": 0, "L5-ITM2": 2}

# PREREG section 5 (never relaxed)
TWINS = 500
SEED = 75
GATE_P = 0.05
GATE_Q = 0.10
GATE_AFFECTED = 40
WF_NEED = 3                                 # better in >= 3 of the 4 quarters
DD_KEEP = 1.10                              # (a): max DD <= 1.10 x the baseline's
DD_CUT = 0.95                               # (b): max DD <= 0.95 x the baseline's
WORST_CUT = 0.05                            # (b): or worst day better by 5% of its size
NET_GIVE, NET_GIVE_RS = 0.10, 5000.0        # (b): net >= base - max(10% |base|, Rs 5,000)
MONTH_STOP, DD_STOP = 13000.0, 40000.0      # account stop (CLAUDE.md)
BIG = 0.30
