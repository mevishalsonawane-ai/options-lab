"""R96 settings (PREREG_ROUND1.md, committed before any statistic). Pure constants and small helpers only.

    lake      $IRAALGO_LAKE (default ~/lake): copied with rclone from gdrive:IraAlgo-data-lake; never committed
    cache     $IRAALGO_CACHE (default ~/.cache/iraalgo): R53 markets (r53/mcx) and R96's own caches (r96); never committed
    results   research/hunt/r96_mcx_buy/results (small JSON / markdown only)

Signal source of every rule: the MCX future (the option book's near future, minute closes; daily rules: the book's own
near-month daily continuous series, back-adjusted for rolls). The trade is always an option BUY (call, put or both).
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
LEDGER = HERE / "LEDGER.md"
LAKE = Path(os.environ.get("IRAALGO_LAKE", Path.home() / "lake")).expanduser()
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser() / "r96"

# ---------------------------------------------------------------------------------------------- periods (PREREG s2)
DATA_START = date(2025, 8, 5)
DESIGN = (date(2025, 8, 5), date(2026, 7, 14))
HOLDOUT = (date(2026, 7, 15), date(2026, 10, 19))          # LOCKED for every round; read once per passing candidate
FOLDS = ((date(2025, 8, 5), date(2025, 10, 31)), (date(2025, 11, 1), date(2026, 1, 31)),
         (date(2026, 2, 1), date(2026, 4, 30)), (date(2026, 5, 1), date(2026, 7, 14)))
HISTORY = (date(2012, 1, 1), date(2025, 7, 31))            # long-history underlying check (no options then)

# ---------------------------------------------------------------------------------------------- books (PREREG s3)
# book -> (daily signal series, cost key, units per point of the option quote (lot multiplier), events read)
BOOKS = {
    "CRUDEOILM": ("CRUDEOIL", "crude", 10.0, ("eia",)),
    "NATGASMINI": ("NATURALGAS", "natgas", 250.0, ("storage",)),
    "GOLDM": ("GOLDM", "gold", 10.0, ("cpi", "nfp")),
    "SILVERM": ("SILVERM", "silver", 5.0, ("cpi", "nfp")),
}
BOOK_ORDER = ("CRUDEOILM", "NATGASMINI", "GOLDM", "SILVERM")

# ---------------------------------------------------------------------------------------------- costs (PREREG s5)
OPT_HALF = {"gold": (0.0030, 0.0060), "silver": (0.0030, 0.0060), "crude": (0.0030, 0.0060),
            "natgas": (0.0045, 0.0090)}              # (from 17:00 IST, before 17:00): R93 / R85
MODEL_EXIT_HALF_MULT = 2.0                          # an exit priced by the model (strike outside ATM+/-3) pays 2x

# ---------------------------------------------------------------------------------------------- common trade rules
ENTRY_TOD = 9 * 60 + 15          # daily rules: first printed minute at or after 09:15 of the next session
FILL_WAIT = 15                   # minutes allowed to find the first print
SQ_BEFORE_CLOSE = 15             # never hold into the last 15 minutes of the session before the option expiry day
MIN_SESSIONS_TO_EXPIRY = 3       # no entry when fewer than 3 sessions remain before the option expiry day
PREM_STOP = 0.40                 # hard stop: premium -40% of the fill (single legs)
STRAD_STOP = 0.30                # hard stop: straddle value -30% (multi-day straddles)
# profit lock ladder on the premium (peak gain -> locked gain); NaN = breakeven (fill + round-trip costs)
NAN = float("nan")
LADDER = ((0.30, NAN), (0.60, 0.30), (1.00, 0.60))
TRAIL_FROM = 1.00                # beyond +100%: lock = peak gain - TRAIL_GAP (whichever is higher)
TRAIL_GAP = 0.40
# event straddle: shorter ladder (one hour)
EV_STOP = 0.25
EV_LADDER = ((0.15, NAN), (0.30, 0.15), (0.50, 0.30))
EV_TRAIL_FROM = 0.50
EV_TRAIL_GAP = 0.20
RISK_PCT = 0.02                  # sizing: 1 lot; capital needed for the stop risk to be 2% (and 1%) is reported

# ---------------------------------------------------------------------------------------------- gates (PREREG s7)
TWINS = 500
SEED = 96
GATE_MIN_TRADES = 30
GATE_P = 0.05
GATE_Q = 0.10
GATE_FOLDS_POS = 3               # of 4
STOP_MONTH = -13000.0
STOP_DD = -40000.0
GATE_WO_TOP = 3
HOLD_MIN_TRADES = 10
HOLD_P = 0.10


def opt_half(cost_key: str, tod: int, mult: float = 1.0) -> float:
    a, b = OPT_HALF[cost_key]
    return (a if tod >= 17 * 60 else b) * mult


def in_period(d, p) -> bool:
    d = d.date() if hasattr(d, "date") and callable(d.date) else d
    return p[0] <= d <= p[1]


def fold_of(d) -> int:
    for k, p in enumerate(FOLDS):
        if in_period(d, p):
            return k
    return -1
