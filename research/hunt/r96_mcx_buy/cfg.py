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
# round 2 (PREREG_ROUND2 s3): the big energy books for R2D / R2F
BOOKS["CRUDEOIL"] = ("CRUDEOIL", "crude", 100.0, ("eia",))
BOOKS["NATURALGAS"] = ("NATURALGAS", "natgas", 1250.0, ("storage",))
BIG_ENERGY = ("CRUDEOIL", "NATURALGAS")
ALL_BOOKS = BOOK_ORDER + BIG_ENERGY

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
GATE_MODEL_SHARE = 0.10          # round 2 gate 8: model-priced exits <= 10% of trades ...
                                 # ... and the net of the real-print-exit trades > 0
R2_MAX_RISK = 6500.0             # R2E: skip a trade whose 1-lot stop risk (40% of the premium + Rs 100) is above this
R2_RISK_COST = 100.0
# round 3 (PREREG_ROUND3): book sets
CRUDE = ("CRUDEOILM", "CRUDEOIL")
ENERGY = BIG_ENERGY                                         # CRUDEOIL, NATURALGAS (R2D's books)
R3_LOWVOL_WIN = 250              # R3D: ATR14 / close in the bottom third of its range over the last 250 sessions
R3_LOWVOL_FRAC = 1.0 / 3.0
R3_TRAIL = (1.0, 1.5)            # R3C: breakeven on the future after +1 ATR14, then a 1.5 x ATR14 chandelier
R3_EARLY_TOD = 11 * 60 + 15      # R3F: breaks between 09:15 and 11:15 only
R3_HIVOL_FRAC = 2.0 / 3.0        # R3J: 5-day mean Garman-Klass variance in the top third of its last-250 range
R3_LH_MIN = 45                   # R3I: signal at session close - 45 min, out at the square-off (close - 15)
R3_LH_LOOK, R3_LH_PAST, R3_LH_Q = 60, 20, 2.0 / 3.0      # R3I: |move| >= the 67th pct of the last 60 sessions' moves
R3_LH_KATR = 0.25                # R3I: underlying stop 0.25 x ATR14 behind the signal price
# R3K / R3L (Boss, 21 Oct: "add trailing profit lock and trailing stoploss same as breadth donchian"): R93's daily
# exit set on the future (r93_breadth_donchian_mcx cfg D_INIT_ATR / D_TRAIL_ATR / D_MAX_HOLD, engine.sim_daily)
R93_INIT_ATR = 2.0               # initial stop: entry -/+ 2 x ATR14
R93_TRAIL_ATR = 3.0              # chandelier lock: best daily close -/+ 3 x ATR14, never loosening
R93_MAX_HOLD = 10                # sessions (entry day = 1), capped before the option expiry day
R93_FUT_HALF = 0.0003            # crude futures half-spread (R93 FUT_HALF["crude"]) ...
R93_STOP_SLIP = 0.0002           # ... + 0.02% extra on stop / lock exits (R93 STOP_SLIP)
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
