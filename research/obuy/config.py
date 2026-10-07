"""Paths and market constants for the option-buying lab (research/obuy).

Paths default to this session's scratchpad; override with the environment variable OBUY_SCRATCH (the folder that
holds dhan/repo/dhan-data, jx/, liqx/) or OBUY_DATA / OBUY_CACHE individually.
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps when run with python -I

SCRATCH = os.environ.get(
    "OBUY_SCRATCH", "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad")
DATA = os.environ.get("OBUY_DATA", os.path.join(SCRATCH, "dhan/repo/dhan-data"))   # untrusted: read-only, run python -I
CACHE = os.environ.get("OBUY_CACHE", os.path.join(SCRATCH, "obuy_cache"))
JX = os.path.join(SCRATCH, "jx")          # jarvis_exits.py caches: ix_<U>.pkl = index minutes + lot + expiry flag per day
LIQX = os.path.join(SCRATCH, "liqx")      # liquidity_exits_3060.py caches: liqx_<U>.pkl = Liquidity 15+5 signals per day

OPEN_M, LAST_M = 9 * 60 + 15, 15 * 60 + 29          # 555 .. 929: the session's 1-minute bar starts
W = LAST_M - OPEN_M + 1                              # 375 columns per day in every path matrix
TICK = 0.05

STEP = {"NIFTY": 50, "BANKNIFTY": 100, "FINNIFTY": 50, "SENSEX": 100, "MIDCPNIFTY": 25, "BANKEX": 100}
BSE = {"SENSEX", "BANKEX"}
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"]

# Exchange lot sizes (contracts in force for the nearest expiry on that date; NSE/BSE circulars). Used only when a
# day's lot cannot be read from the data (the OI-move gcd, as Lots.lotFromChain) - see data.Index.lot.
LOT_SCHEDULE = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35)],
    "FINNIFTY": [("2000-01-01", 40), ("2024-07-24", 25), ("2024-11-28", 65), ("2025-12-31", 60)],
    "SENSEX": [("2000-01-01", 10), ("2024-11-28", 20)],
    "MIDCPNIFTY": [("2000-01-01", 75), ("2024-11-28", 120)],
    "BANKEX": [("2000-01-01", 15), ("2024-11-28", 30)],
}


def hm(m: int) -> str:
    return f"{m // 60:02d}:{m % 60:02d}"


def mins(h: int, m: int) -> int:
    return h * 60 + m
