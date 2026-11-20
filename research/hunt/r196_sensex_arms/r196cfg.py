"""R196 settings (PREREG.md): does SENSEX earn its place in R72, R51 and Liquidity 15+5 with today's exits (V5)?
Plain-English notes, 20 Nov 2026.

    raw       ~/.cache/iraalgo/sx/raw    the Drive lake files copied with rclone (2024 - 2026; never in git)
    lake      ~/.cache/iraalgo/sx/lake   one flat folder in R190's layout, built by r196data.py
    out       ~/.cache/iraalgo/sx/out    R51 stores and caches (never in git)
    results   research/hunt/r196_sensex_arms/results (small JSON / markdown only)

Importing this module points R193's settings (and through them the R176 / R106 / R51 stack) at R196's lake and caches;
import it before r193cfg or any of the stack. Signal source of every rule: the index. Options are only bought.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
HUNT = HERE.parent
SX = Path(os.environ.get("R196_HOME", Path.home() / ".cache" / "iraalgo" / "sx")).expanduser()
RAW = SX / "raw"
LAKE = SX / "lake"
OUT = SX / "out"
RESULTS = HERE / "results"
W2_FLAG = RESULTS / "W2_READ"

# R193's settings read these when imported (r193cfg.OUT / LAKE)
os.environ["R193_OUT"] = str(OUT)
os.environ["R193_LAKE"] = str(LAKE)
for p in (str(ROOT), str(ROOT / "research"), str(HERE), str(HUNT / "r193_arms_nov24")):
    if p not in sys.path:
        sys.path.insert(0, p)

# R196's store window (PREREG 2): warm-up from 1 Jan 2024 (only 2024 - 2026 copied), signals from 1 Mar 2024
STORE = (date(2024, 1, 1), date(2024, 3, 1), date(2026, 11, 13))

# ------------------------------------------------------------------------------------------------ windows (PREREG 4)
SPAN = (date(2024, 11, 1), date(2026, 11, 13))
WINDOWS = {"W1": (date(2024, 11, 1), date(2026, 3, 31)),
           "W2": (date(2026, 4, 1), date(2026, 11, 13)),
           "total": SPAN}
WINDOW_NAME = {"W1": "W1 1 Nov 2024 - 31 Mar 2026 (design, walk-forward)",
               "W2": "W2 1 Apr - 13 Nov 2026 (holdout, read once)",
               "total": "total 1 Nov 2024 - 13 Nov 2026"}
QUARTERS = {
    "W1": (("2024 Nov-Dec", date(2024, 11, 1), date(2024, 12, 31)),
           ("2025 Q1", date(2025, 1, 1), date(2025, 3, 31)),
           ("2025 Q2", date(2025, 4, 1), date(2025, 6, 30)),
           ("2025 Q3", date(2025, 7, 1), date(2025, 9, 30)),
           ("2025 Q4", date(2025, 10, 1), date(2025, 12, 31)),
           ("2026 Q1", date(2026, 1, 1), date(2026, 3, 31))),
    "W2": (("2026 Q2", date(2026, 4, 1), date(2026, 6, 30)),
           ("2026 Q3", date(2026, 7, 1), date(2026, 9, 30)),
           ("2026 Oct-13 Nov", date(2026, 10, 1), date(2026, 11, 13))),
}
WF_MIN_POSITIVE = 4                        # of W1's 6 quarters

# ------------------------------------------------------------------------------------------------ tests (PREREG 5)
SX_IDX = "SENSEX"
TESTS = ("R72-SX", "R51-SX", "LIQ-SX")
TEST_ARM = {"R72-SX": "R72", "R51-SX": "R51", "LIQ-SX": "LIQ"}
# the test's exits = R193's V5 of the arm: (breakeven, lock, picker)
TEST_RULE = {"R72-SX": (True, "L500", True), "R51-SX": (True, "L500", True), "LIQ-SX": (True, "LBE", False)}
LABEL = {"R72-SX": "R72 Breadth Donchian on SENSEX, live rule (picker + V5)",
         "R51-SX": "R51 Donchian 20 on SENSEX, live rule (picker + V5)",
         "LIQ-SX": "Liquidity 15+5 on SENSEX (new books 15 + 5 min, index stop 50 pts, V5)"}
PICK_THR = {"R72": 0.4656, "R51": 0.4469}
LIQ_SX_BOOKS = (15, 5)
LIQ_SX_IDX_STOP = 50.0                     # points (PREREG 5: ~0.06% of SENSEX, as H11-2)
LIQ_OTHERS = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")

# twin entry windows (rows from 09:15): R51 / R72 09:20 - 15:10, Liquidity 09:20 - 14:00
OPEN_MIN = 9 * 60 + 15


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - OPEN_MIN


TWIN_WINDOW = {"R72": (row(9, 20), row(15, 10)), "R51": (row(9, 20), row(15, 10)), "LIQ": (row(9, 20), row(14, 0))}
TWIN_MONEY = 1                             # 1-ITM

# ------------------------------------------------------------------------------------------------ gate (PREREG 7)
TWINS = 2000
SEED = 196
GATE_P = 0.05
GATE_Q = 0.10
MIN_TRADES = 40
TOP_N = 5
N_PRIOR = 4808                             # entry files at the branch base (origin/main 82fbc3b4)
M_TOTAL = N_PRIOR + len(TESTS)             # 4,811
ACC_DD = 40000.0
MONTH_CAP = -13000.0

# ------------------------------------------------------------------------------------------------ reproduction (PREREG 3)
R193_PUB = {  # R193 results/span.json, total 1 Nov 2024 - 13 Nov 2026, 1 lot: (trades, net)
    "A:R72": (4571, 390101), "R72-V5": (2352, 358078),
    "A:R51": (6269, 191394), "R51-V5": (3082, 203580),
    "A:LIQ": (1323, 255293), "LIQ-V5": (1323, 220469),
}
