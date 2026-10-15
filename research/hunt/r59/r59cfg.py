"""R59 settings (PREREG.md, 15 Oct 2026): audit of another AI's "S2 Mean Reversion Dip Buy" on stock options.

    lake       $R59_LAKE (default <scratchpad>/lake): stkopt/stkopt_<SYM>_MONTH.parquet, equity_1m/equity_1m_<YEAR>.parquet,
               bhav/<YYYY-MM-DD>.csv.zip, copied from gdrive:IraAlgo-data-lake with rclone (never committed)
    results    research/hunt/r59/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RESEARCH = ROOT / "research"
for p in (str(RESEARCH), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

LAKE = Path(os.environ.get("R59_LAKE", "/tmp/r59lake")).expanduser()
RESULTS = HERE / "results"
FINAL_LOCK = RESULTS / "final_read.json"
SPREAD_JSON = RESULTS / "spread.json"

STOCKS = ("RELIANCE", "HDFCBANK", "ICICIBANK", "INFY", "TCS", "SBIN", "AXISBANK", "KOTAKBANK", "ITC", "TMPV")
BHAV_ALIAS = {"TATAMOTORS": "TMPV"}          # the bhavcopy's name for Tata Motors until 27 Oct 2025
SIDES = ("C", "P")

# windows
LIT_START = date(2024, 11, 4)                # their claim's span (literal reproduction only)
LIT_END = date(2026, 10, 14)
START = date(2024, 11, 20)                   # honest tests
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)

# grid
N = 375                                      # minutes 09:15 .. 15:29
BAR = 15
NBARS = N // BAR                             # 25 bars a session
EXIT_CAP = 370                               # 15:25
HOLD = 60
WAIT = 5                                     # entry / exit row search window (minutes)
FRESH_OPT = 5                                # the option's last trade in bar t must be in its last 5 minutes
FRESH_EQ = 3                                 # the stock's last trade in bar t must be in its last 3 minutes

# their rule
DROP = -20.0                                 # % in one 15-minute bar
MIN_PREM = 5.0
THEIR_NOTIONAL = 10_000.0
THEIR_COST = 95.0
THEIR_STEP = 4

# B: the signal on the stock
STOCK_MOVE = 0.01                            # 1.0% in one 15-minute bar against the option

# cleaning
FOREIGN_TOL = 0.03
BAD_PRINT = 0.20
CA_RATIO_JUMP = 0.03
CA_SPOT_JUMP = 0.20
CA_PAD = 5                                   # sessions before / after a corporate action left out
MIN_SESSION_MIN = 300
EXPIRY_SKIP = 2                              # sessions E-2, E-1 and E are skipped

# pricing
TICK = 0.05
BPS = 0.0005
BPS_STOP = 0.001
STOP = 0.25                                  # S25: 25% below the fill
VARIANTS = ("NS", "S25")
FAMILIES = ("A", "B")

# tests
TWINS = 500
SEED = 59
REDRAW = 10
GATE_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10
ROLL_MIN_PAIRS = 30


def mi(h: int, m: int) -> int:
    return h * 60 + m - (9 * 60 + 15)


def period(d: date) -> str:
    if d < START:
        return "pre"
    return "design" if d <= DESIGN_END else "final"
