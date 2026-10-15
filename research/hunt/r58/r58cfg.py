"""R58 settings (PREREG.md): buying ATM stock options on the top 10 NIFTY 50 stocks, Nov 2024 onwards only.

    lake      $IRAALGO_LAKE (default <cache>/lake), filled with rclone from gdrive:IraAlgo-data-lake:
                equity_1m/equity_1m_202[456].parquet, equity_daily/equity_daily.parquet,
                index_1m/index_1m_NIFTY_202[456].parquet, nse_bhavcopy/202[456]-*.csv.zip,
                stock_options_rolling_1m/stkopt_<SYM>_MONTH.parquet (one stock at a time; deleted after its build)
    caches    R58_OUT (default <cache>/r58): per-stock books and trade dumps (big: never in git)
    results   research/hunt/r58/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from research.brain import config as BC  # noqa: E402

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R58_OUT", CACHE.root / "r58")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# PREREG section 2 (results/universe.json has the ranking and the coverage numbers)
UNIVERSE = ("RELIANCE", "SBIN", "MARUTI", "ICICIBANK", "INFY", "LT", "KOTAKBANK", "M&M", "TRENT", "TATASTEEL")
NO_PM1 = {"LT", "TATASTEEL"}           # +-1 coverage < 95%: no 1-ITM variants on these

WARM_FROM = date(2024, 9, 1)            # indicators only
FIRST_DAY = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
LAST_DAY = date(2026, 10, 12)           # the last stock-minute session in the lake

MIN_BARS = 300                          # a stock session needs >= 300 one-minute bars (drops Muhurat hours)
NMIN = 375                              # rows 0..374 = 09:15..15:29
SQ = 360                                # 15:15
SQ_EXPIRY = 350                         # 15:05 (context run without the cut-off only)
FILL_TRIES = 3
STRAY = 0.03                            # option rows whose spot is > 3% off the stock that minute are dropped
CA_JUMP = 0.03                          # ex-date / bad-day detection on the spot / stock ratio
BAND = 1.25                             # band exit: stock >= 1.25 strike steps from the held strike
RATE = 0.065                            # Black-Scholes fallback (model exits only)

TWINS = 500
SEED = 58
BH_Q = 0.10
MIN_TRADES = 40
QUARTERS = ("2025Q1", "2025Q2", "2025Q3", "2025Q4", "2026Q1")   # 2025Q1 includes 20 Nov - 31 Dec 2024


def period(d: date) -> str:
    if d < FIRST_DAY or d > LAST_DAY:
        return "out"
    return "design" if d <= DESIGN_END else "final"


def quarter(d: date) -> str:
    if d < date(2025, 1, 1):
        return "2025Q1"
    return f"{d.year}Q{(d.month - 1) // 3 + 1}"


def row(hh: int, mm: int) -> int:
    return (hh - 9) * 60 + mm - 15
