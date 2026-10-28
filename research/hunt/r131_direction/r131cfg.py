"""R131 settings, fixed in PREREG.md (28 Oct 2026) before any result. Paths come from the environment so tests can
run without the lake."""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
R123_OUT = Path(os.environ.get("R123_OUT", CACHE_ROOT / "r123")).expanduser()
OUT = Path(os.environ.get("R131_OUT", CACHE_ROOT / "r131")).expanduser()
RESULTS = HERE / "results"
DRIVE = "gdrive:IraAlgo-data-lake/derived/r131"
EVENTS_CSV = ROOT / "research" / "brain" / "calendar_data" / "events.csv"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
NSE_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")        # the ones with an NSE bhavcopy chain
LOT = {"NIFTY": 65, "BANKNIFTY": 35, "FINNIFTY": 60, "MIDCPNIFTY": 120, "SENSEX": 20}
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}

# ---- periods (PREREG section 5) ---------------------------------------------------------------------------------
DAILY_START = date(2012, 4, 2)
INTRA_START = date(2021, 8, 2)
FOLDS = {"F1": (date(2023, 1, 1), date(2023, 12, 31)), "F2": (date(2024, 1, 1), date(2024, 12, 31)),
         "F3": (date(2025, 1, 1), date(2025, 12, 31)), "F4": (date(2026, 1, 1), date(2026, 3, 15))}
DESIGN_END = date(2026, 3, 15)
HOLDOUT = (date(2026, 4, 1), date(2026, 10, 19))
EMBARGO_DAYS = 10

# ---- horizons (PREREG section 2) --------------------------------------------------------------------------------
DAILY_H = ("GAP", "C2C", "PRE")
INTRA_H = ("DAY", "R11", "R13", "M60")
HORIZONS = DAILY_H + INTRA_H
TRADEABLE = {"GAP", "C2C", "DAY", "R11", "R13", "M60"}
TINY = {"GAP": 0.10, "PRE": 0.10, "C2C": 0.10, "DAY": 0.10, "R11": 0.05, "R13": 0.05, "M60": 0.05}   # % of price
N = 375


def row(hh: int, mm: int) -> int:
    """Grid row of the 1-minute bar that STARTS at hh:mm (0 = 09:15). A decision 'at 09:30' is the 09:29 bar's close."""
    return (hh * 60 + mm) - (9 * 60 + 15)


INTRA_ROWS = {"DAY": (row(9, 29),), "R11": (row(10, 59),), "R13": (row(12, 59),),
              "M60": (row(9, 59), row(10, 59), row(11, 59), row(12, 59), row(13, 59))}
EXIT_LAST = row(15, 19)          # money check: square-off at the 15:19 bar's close
EXIT_LAST_EXPIRY = row(15, 4)

# ---- feature sets (PREREG section 3) ----------------------------------------------------------------------------
SOURCES = ("BRD", "PART", "FUT", "CHAIN", "GLOB", "CAL")
SETS = ("IDX",) + tuple("IDX+" + s for s in SOURCES) + ("ALL",)
PREFIX = {"IDX": ("u_", "idx_", "vix_", "ri_"), "BRD": ("brd_", "rb_", "eq_"), "PART": ("part_", "fpi_"),
          "FUT": ("fut_",), "CHAIN": ("ch_", "rc_"), "GLOB": ("glob_", "mcx_"), "CAL": ("cal_", "rk_")}

# ---- models (PREREG section 4) ----------------------------------------------------------------------------------
MODELS = ("LR", "GBM", "MLP")
LR_C = 0.05
CLIP = 5.0
GBM = dict(max_iter=200, learning_rate=0.05, max_leaf_nodes=15, min_samples_leaf=50, l2_regularization=1.0,
           random_state=0, early_stopping=False)
MLP = dict(hidden_layer_sizes=(32, 16), alpha=1e-3, learning_rate_init=1e-3, max_iter=200, early_stopping=True,
           validation_fraction=0.2, random_state=0)
BASELINES = ("B0", "B1", "B2")
COVERAGES = (1.0, 0.5, 0.2, 0.1)

# ---- gates (PREREG section 6) -----------------------------------------------------------------------------------
GATE_HIT = 0.60
GATE_N = 100
GATE_Q = 0.10
GATE_FOLDS = 3
FOLD_MIN_N = 10

# ---- money check (PREREG section 7) -----------------------------------------------------------------------------
MONEY_SET, MONEY_MODEL, MONEY_K = "ALL", "GBM", 0.2
FUT_SLIP = 0.0001
FUT_EXCH = 0.000019
