"""R41 settings (PREREG.md): the neural-network arm for the running studies. 14 Oct 2026.
Renumbered 14 Oct from R30 (the number R30 was already taken by R30 all parameters). The runs before the renumber
wrote their caches to <cache>/r30 and gdrive:IraAlgo-data-lake/derived/r30/ (shared folder name with R30 all
parameters); new runs use r41. The holdout lock (india.holdout_gate) also refuses a read whose result is already in
results/.

    caches   R41_OUT (default <cache>/r41): datasets, predictions, model weights (never in git; a copy goes to
             gdrive:IraAlgo-data-lake/derived/r41/)
    results  research/hunt/r41/results (small JSON only)

Periods are F1 India's and F1 crypto's committed splits, unchanged (PREREG.md section 2).
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from research.brain import config as BC  # noqa: E402

CACHE = BC.paths()
OUT = Path(os.environ.get("R41_OUT", CACHE.root / "r41")).expanduser()
RESULTS = HERE / "results"
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()     # the Dhan lake (Drive copy)
DRIVE = "gdrive:IraAlgo-data-lake/derived/r41"
CRYPTO_RAW = OUT / "crypto"              # BTCUSDT/<YYYY>/BTCUSDT-klines_1m-<YYYY-MM>.parquet (Drive copy)

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
BSE = {"SENSEX"}
COINS = ("BTCUSDT", "ETHUSDT")

# ---- India periods (F1 India, research/hunt/f1/f1cfg.py on claude/f1-india-factory) ---------------------------------
DESIGN = ((date(2021, 8, 1), date(2022, 12, 14)), (date(2024, 7, 15), date(2026, 4, 13)))
HOLDOUT = (date(2023, 1, 1), date(2024, 6, 30))
EMBARGO = ((date(2022, 12, 15), date(2022, 12, 31)), (date(2024, 7, 1), date(2024, 7, 14)))
FOLDS = {"2022": (date(2022, 1, 1), date(2022, 12, 14)), "2024H2": (date(2024, 7, 15), date(2024, 12, 31)),
         "2025": (date(2025, 1, 1), date(2025, 12, 31)), "2026": (date(2026, 1, 1), date(2026, 4, 13))}
HOLD_TRAIN = DESIGN[0]                   # the holdout models: design data strictly before the holdout

# ---- crypto periods (F1 crypto, research/hunt/f1c/f1ccfg.py), UTC ---------------------------------------------------
C_DESIGN = ((pd.Timestamp("2023-11-01"), pd.Timestamp("2025-04-01")),
            (pd.Timestamp("2025-11-01"), pd.Timestamp("2026-04-01")))          # [start, end)
C_HOLDOUT = (pd.Timestamp("2025-04-01"), pd.Timestamp("2025-10-01"))
C_FOLDS = {"2024H2": (pd.Timestamp("2024-07-01"), pd.Timestamp("2025-01-01")),
           "2025Q1": (pd.Timestamp("2025-01-01"), pd.Timestamp("2025-04-01")),
           "2025Q4-26Q1": (pd.Timestamp("2025-11-01"), pd.Timestamp("2026-04-01"))}
C_HOLD_TRAIN = C_DESIGN[0]


def part(d: date) -> str:
    """'design', 'holdout', 'embargo' or 'unused' for an India session date."""
    if HOLDOUT[0] <= d <= HOLDOUT[1]:
        return "holdout"
    if any(a <= d <= b for a, b in DESIGN):
        return "design"
    if any(a <= d <= b for a, b in EMBARGO):
        return "embargo"
    return "unused"


def c_part(ts: pd.Timestamp, horizon_min: int = 0) -> str:
    """Crypto: 'design' / 'holdout' only when the whole window [ts, ts + horizon] is inside one span."""
    end = ts + pd.Timedelta(minutes=horizon_min)
    if C_HOLDOUT[0] <= ts and end < C_HOLDOUT[1]:
        return "holdout"
    if any(a <= ts and end < b for a, b in C_DESIGN):
        return "design"
    return "unused"


# ---- grid rows (row t = the bar that STARTS at 09:15 + t) -----------------------------------------------------------
def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


HORIZONS = (15, 30, 60)
C_HORIZONS = (15, 60, 240)
Q1_ROWS = list(range(29, 360, 15))       # 09:44 bar (close 09:45) .. 15:14; a horizon keeps rows with t + h <= 374
Q3_ROW = row(13, 44)                     # close 13:45
Q3_LAST = row(15, 4)                     # the 15:04 bar (closes 15:05, the expiry square-off)
Q4_ROW = row(15, 19)                     # R27's signal bar (close 15:20)
EXIT_LAST = row(15, 19)                  # India exits close by 15:20
SEQ_N = 60
C_SEQ_N = 120

# ---- models (PREREG section 4) --------------------------------------------------------------------------------------
SEEDS = (0, 1, 2)
EPOCHS, PATIENCE, BATCH, LR, WD, DROPOUT = 25, 4, 512, 1e-3, 1e-4, 0.2
FIT_SECONDS = 600
VAL_SHARE = 0.2
MIN_TRAIN_SESSIONS = 150
C_MIN_TRAIN_DAYS = 120
NAN_DROP = 0.8
CLIP = 5.0
GBM = dict(max_iter=200, learning_rate=0.05, max_leaf_nodes=15, min_samples_leaf=50, l2_regularization=1.0,
           random_state=0, early_stopping=False)
BOOT = 1000
TWINS = 1000
SEED = 30

# ---- rules (PREREG section 6) ---------------------------------------------------------------------------------------
Q1_TOP = 0.10
Q1_STOP = 0.30
Q2_SKIP = 0.30
MIN_ARM_TRADES = 300
Q3_TOP = 0.20
Q3_MULT = 4.0
Q4_TOP = 0.20
Q5_TOP = 0.10
C_STOP_SIG, C_LOCK_SIG, C_TRAIL_SIG = 1.0, 1.0, 1.0
# crypto costs: F1C's constants (Delta India taker 0.05% + 18% GST per side, 2 bp slippage per side), stress x2
C_FEE, C_GST, C_SLIP, C_TAX = 0.0005, 0.18, 0.0002, 0.312
INR_PER_USD = 85.0
C_QTY = {"BTCUSDT": 0.01, "ETHUSDT": 0.1}
IST_WINDOW = (0, 9 * 60)                 # Boss's crypto window 00:00 - 09:00 IST, in minutes of the IST day
