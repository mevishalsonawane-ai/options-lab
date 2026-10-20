"""R88 settings (PREREG.md): what moves the candle, 5 indices and 10 MCX books.

    lake     $R88_LAKE (default /root/out): the built data lake (lake_build.py output or a Drive download, flat or by
             dataset folder). Never committed.
    cache    $R88_CACHE (default ~/.cache/iraalgo/r88): per-instrument minute and candle tables. Never committed.
    results  research/hunt/r88/results (small JSON / CSV / markdown only)
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
LAKE = Path(os.environ.get("R88_LAKE", "/root/out")).expanduser()
CACHE = Path(os.environ.get("R88_CACHE", Path.home() / ".cache" / "iraalgo" / "r88")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

INDICES = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
BOOK_FLAG = {"NIFTY": "WEEK", "SENSEX": "WEEK", "BANKNIFTY": "MONTH", "FINNIFTY": "MONTH", "MIDCPNIFTY": "MONTH"}
LEADER = {"NIFTY": "BANKNIFTY", "BANKNIFTY": "NIFTY", "FINNIFTY": "NIFTY", "MIDCPNIFTY": "NIFTY", "SENSEX": "NIFTY"}
MCX = ("GOLD", "GOLDM", "SILVER", "SILVERM", "CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGASMINI", "COPPER", "ZINC")
ALL = INDICES + MCX
TFS = (5, 15)

# PREREG section 1
INDEX_START, MCX_START = date(2024, 11, 20), date(2025, 8, 5)
DESIGN_END, HOLD_START, END = date(2026, 5, 31), date(2026, 6, 1), date(2026, 10, 19)

NSE_OPEN, NSE_ROWS = 9 * 60 + 15, 375          # 09:15 .. 15:29
MCX_OPEN = 9 * 60                              # 09:00 .. close by the US clock (research/data/mcx.py)
FOREIGN_TOL = 0.03                             # option rows whose spot is >3% from the index close are dropped
BASE_DAYS, BASE_MIN = 20, 10                   # baseline: median range of the slot over 20 sessions, >= 10 needed
RV_N = 12                                      # realised volatility over the last 12 candles
VIX_DAYS = 60                                  # VIX / ATM IV z-score window (sessions)
OR_MIN = 15                                    # opening range: the first 15 minutes
EXPIRY_JUMP, EXPIRY_GAP = 2.0, 4               # straddle jump that marks a contract roll; at most one per 4 sessions

# PREREG section 5/6 gates
Q_DESIGN, Q_HOLD = 0.05, 0.10
SEED = 88
COST_POINTS = {"NIFTY": 1.9, "SENSEX": 6.4}    # option round trip in index points (R87)


def is_mcx(sym: str) -> bool:
    return sym in MCX


def start(sym: str) -> date:
    return MCX_START if is_mcx(sym) else INDEX_START
