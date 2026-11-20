"""R207 / R208 settings (PREREG.md): the expected-move gate (R207, 7 skip tests on today's rules) and the late-day break
(R208: 2 index tests on fresh days, 6 standalone option rules). Pure numbers; the two runners set their own environment
(r207opt.py: R202's option build; r207idx.py: R206's index-only build). Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
ROOT = HERE.parents[2]
R202 = HUNT / "r202_trend_exit"
R206 = HUNT / "r206_direction"
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"
FRESH_FLAG = RESULTS / "FRESH_READ"
NROWS = 375

ARMS = ("R72", "R51", "LIQ")
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "LIQ": "Liquidity 15+5"}

# ---- R207: the expected-move gate (PREREG 2)
PREV_N, PREV_MIN = 20, 10                  # earlier sessions used / needed
FWD_MIN = 30                               # "the next 30 minutes"
WF_SESSIONS, WF_MIN_TRADES = 60, 100       # the walk-forward reference: the arm's trades of the previous 60 trade days
GATES = {"G-E33": ("x", 1.0 / 3.0), "G-E50": ("x", 0.5)}
RT_CUT = 0.85                              # G-RT85 (Liquidity only)
GATE_TESTS = tuple((a, g) for g in GATES for a in ARMS) + (("LIQ", "G-RT85"),)        # 7
GATE_LABEL = {"G-E33": "skip when the expected move is in the lowest third (walk-forward)",
              "G-E50": "skip when the expected move is below the median (walk-forward)",
              "G-RT85": "skip when the range so far is under 85% of the usual range by this time of day"}

# ---- R208: the late-day break (PREREG 3)
LATE_FIRST, LATE_LAST = 345, 355           # entry minutes 15:00 .. 15:10
SQ = {"15": 360, "20": 365, "25": 370}     # square-off rows 15:15, 15:20, 15:25
LATE_ARMS = ("R72", "R51")
LATE_TESTS = tuple((a, t) for a in LATE_ARMS for t in SQ)                               # 6
FRESH = (date(2021, 11, 1), date(2022, 3, 31))
INDEX_TESTS = ("L-I51", "L-I72")
INDEX_SRC = {"L-I51": "R51", "L-I72": "R72"}
MIN_SIGNALS = 100
LEVEL = 0.5

# ---- statistics and the gates
TWINS = 2000
BOOT = 5000
SEED_GATE, SEED_LATE = 207, 208
GATE_P, GATE_Q = 0.05, 0.10
MIN_TRADES = 40
FOLDS_NEEDED = 3
N_PRIOR = 4889                             # entry files on the branch before R207
N_TESTS = len(GATE_TESTS) + len(LATE_TESTS) + len(INDEX_TESTS)                          # 15
M_TOTAL = N_PRIOR + N_TESTS                # 4,904
