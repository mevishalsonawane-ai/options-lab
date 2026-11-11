"""R160 settings (PREREG.md): does the closing-auction gap get corrected the next morning?

    lake      $R160_LAKE: the Drive lake files listed in PREREG section 2 (index_1m/, index_daily/, fut/, opt/)
    results   research/hunt/r160_cas_gap/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
PREREG = HERE / "PREREG.md"
LAKE = Path(os.environ.get("R160_LAKE", Path.home() / ".cache" / "iraalgo" / "r160_lake")).expanduser()

# ---------------------------------------------------------------------------------------------------- periods (PREREG 3)
CAS_START = date(2026, 8, 3)
POST = (date(2026, 8, 3), date(2026, 10, 16))     # entry days; the next open is known to 19 Oct
PRE = (date(2025, 1, 1), date(2026, 7, 31))

# ---------------------------------------------------------------------------------------------------- minute grid
N = 375                                          # rows: the bar starting 09:15 + r (09:15 .. 15:29)


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


BASIS_ROWS = (row(14, 30), row(14, 59))          # inclusive
LATE_ROWS = (row(15, 20), row(15, 27))           # inclusive
R1514 = row(15, 14)
R0920 = row(9, 20)
ENTRY_ROW = row(15, 28)                          # T1 entry: the open of the 15:28 bar
EXIT_ROW = row(9, 16)
EXIT_LAST = row(9, 45)

OFFSETS = (-2, -1, 0, 1, 2)
MIN_OFFSETS = 3
MIN_BASIS_MIN = 10
MIN_LATE_MIN = 4

# ---------------------------------------------------------------------------------------------------- instruments
UNDS = ("NIFTY", "BANKNIFTY", "SENSEX")
PRIMARY = "NIFTY"
OPT_BOOK = {"NIFTY": "NIFTY_WEEK", "BANKNIFTY": "BANKNIFTY_MONTH", "SENSEX": "SENSEX_WEEK"}
BSE = {"SENSEX"}

# Expiry calendar (weekday the contract expires; moved to the previous trading day on a holiday).
# NSE moved its expiries to Tuesday from 1 Sep 2025; BSE's SENSEX weekly to Thursday from 1 Sep 2025.
# Only POST expiry days are used (secondary (b) and T1's skip), all after that date.
EXPIRY_WEEKDAY = {"NIFTY": 1, "SENSEX": 3, "BANKNIFTY": 1}
MONTHLY = {"BANKNIFTY"}

LOTS = {"NIFTY": [(date(2024, 11, 28), 75), (date(2025, 12, 31), 65)]}

# ---------------------------------------------------------------------------------------------------- tests (PREREG 5-8)
SEED = 160
PERMS = 100_000
BOOT = 10_000
TWINS = 2_000
HIT_MIN_BPS = 2.0
H1_MIN_BETA = 0.25
H1_MIN_HIT = 0.55
GATE_Q = 0.10
N_PRIOR = 2154                                   # R154's 1,682 + 472 entries from R155-R159 (PREREG 7)

# ---------------------------------------------------------------------------------------------------- trade T1 (PREREG 6)
T1_MIN_D = 5.0                                   # bps
HALF_SPREAD = 0.0016
STRESS = 1.5
FILL_GUARD = 0.20
T1_MIN_TRADES = 20
R146_NIGHT_NIFTY = dict(trades=84, per_trade=480, win=0.52)   # R146 holdout, Night (R3) proxy, NIFTY


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= s:
            out = n
    return out
