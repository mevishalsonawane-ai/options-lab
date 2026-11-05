"""R146 settings (PREREG.md). Gap prediction at 15:20 and the BTST option test.

    release   $R146_RELEASE (default ~/.cache/iraalgo/release): the repo's `research-data` release files (gh api)
    yahoo     $R146_OUT/yahoo: daily bars from Yahoo's chart API (plain HTTPS)
    caches    $R146_OUT (default ~/.cache/iraalgo/r146): built stores (never in git)
    results   research/hunt/r146_gap_btst/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE = Path(os.environ.get("IRAALGO_CACHE", "~/.cache/iraalgo")).expanduser()
RELEASE = Path(os.environ.get("R146_RELEASE", CACHE / "release")).expanduser()
OUT = Path(os.environ.get("R146_OUT", CACHE / "r146")).expanduser()
YAHOO = OUT / "yahoo"
RESULTS = HERE / "results"
FLAG = RESULTS / "HOLDOUT_READ.flag"
PREREG = HERE / "PREREG.md"

# ---------------------------------------------------------------------------------------------------- periods (PREREG 2)
HOLD_START = date(2025, 3, 1)          # entry days on or after this: the locked holdout (both parts)
M_FIRST_TEST = date(2024, 8, 1)        # M walk-forward: first test month
D_FIRST_TEST_YEAR = 2012               # D walk-forward: first test year
D_START = date(2008, 1, 1)


def is_hold(d: date) -> bool:
    return d >= HOLD_START


# ---------------------------------------------------------------------------------------------------- minute grid
N = 375                                 # rows: the bar starting 09:15 + r
O, H, L, C = range(4)


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


R1519 = row(15, 19)
R1449 = row(14, 49)
R1419 = row(14, 19)
ENTRY_ROWS = (row(15, 20), row(15, 21), row(15, 22))
EXIT_ROW = {"X15": row(9, 15), "X16": row(9, 16), "X20": row(9, 20), "X30": row(9, 30)}
EXIT_LAST = row(9, 45)                  # a missing exit bar falls forward to this at the latest
STOP_WATCH_D = (row(15, 21), row(15, 29))
STOP_PCT = 0.30

# ---------------------------------------------------------------------------------------------------- instruments
M_UNDS = ("NIFTY", "BANKNIFTY")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
RELEASE_FILES = {
    "NIFTY": ["nifty_prev.parquet", "nifty_year.parquet"],
    "BANKNIFTY": ["banknifty_prev_year_wide.parquet", "banknifty_year_wide.parquet"],
}
INDEX_ONLY = {"FINNIFTY": "finnifty_index.parquet", "SENSEX": "sensex_index.parquet"}
EXPIRY_MOVED = {date(2025, 1, 29): date(2025, 1, 30)}   # research/brain/adapters/release.py
M_LAST_ENTRY = date(2026, 2, 20)        # VIX / FINNIFTY / SENSEX / BANKNIFTY end 23-27 Feb 2026

# Yahoo symbols (PREREG 1)
YH = {
    "NIFTY": "^NSEI", "BANKNIFTY": "^NSEBANK", "FINNIFTY": "NIFTY_FIN_SERVICE.NS", "MIDCPNIFTY": "NIFTY_MID_SELECT.NS",
    "SENSEX": "^BSESN", "INDIAVIX": "^INDIAVIX", "N225": "^N225", "HSI": "^HSI", "KS11": "^KS11", "GSPC": "^GSPC",
    "USDINR": "USDINR=X",
}
D_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
D_END = date(2026, 11, 4)
STALE_OPEN_MAX = 0.05                   # a year with more than 5% of opens == previous close is dropped

# The lot in force (R27's table: research/obuy/config.py + BANKNIFTY 30 from 31 Dec 2025)
LOTS = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
}


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


# ---------------------------------------------------------------------------------------------------- rules / models
LOC_UP, LOC_DOWN, BUZ_MIN = 0.6, 0.4, 0.5
Z_WINDOW, Z_MIN = 60, 20
P_EDGE = 0.05                           # T3 / T4: trade only when |p - 0.5| >= this
L30_MIN = 0.0005                        # T5: |last 30 minutes| >= 0.05%
LR_C = 0.1
GB = dict(max_depth=2, learning_rate=0.05, max_iter=150, min_samples_leaf=20, l2_regularization=1.0, random_state=146)
RIDGE_ALPHA = 10.0

# ---------------------------------------------------------------------------------------------------- costs
HALF_SPREAD = 0.0016                    # h24, both indices, on entry and exit
STRESS = 1.5
FILL_GUARD = 0.20                       # a fill bar whose open is > 20% from its close: the trade is dropped

# ---------------------------------------------------------------------------------------------------- tests
SEED = 146
PERMS = 2000
TWINS = 2000
