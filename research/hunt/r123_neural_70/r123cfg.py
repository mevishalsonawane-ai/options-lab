"""R123 settings (PREREG.md): one network over the index + option data, and 5 plain rules from it. 28 Oct 2026.

    lake     $IRAALGO_LAKE (default <cache>/lake): index_1m (5 indices + INDIAVIX), options_rolling_1m (WEEK files)
    cache    $R123_OUT (default <cache>/r123): datasets, predictions, model weights (never in git; a copy goes to
             gdrive:IraAlgo-data-lake/derived/r123/)
    results  research/hunt/r123_neural_70/results (small JSON / markdown only)

Pure settings: periods, the grid, the rules and the gates. Nothing here is tuned after a result.
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

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
OUT = Path(os.environ.get("R123_OUT", CACHE_ROOT / "r123")).expanduser()
RESULTS = HERE / "results"
DRIVE = "gdrive:IraAlgo-data-lake/derived/r123"
EVENTS_CSV = ROOT / "research" / "brain" / "calendar_data" / "events.csv"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
BSE = {"SENSEX"}
# today's lots (research/obuy/config.LOT_SCHEDULE, last entry), as Boss trades now (PREREG section 4)
LOT = {"NIFTY": 65, "BANKNIFTY": 35, "FINNIFTY": 60, "MIDCPNIFTY": 120, "SENSEX": 20}

# ---- periods (PREREG section 3) -------------------------------------------------------------------------------------
DESIGN = (date(2021, 8, 1), date(2026, 3, 15))
EMBARGO = (date(2026, 3, 16), date(2026, 3, 31))
HOLDOUT = (date(2026, 4, 1), date(2026, 10, 19))
FOLDS = {"F1": (date(2023, 1, 1), date(2023, 12, 31)), "F2": (date(2024, 1, 1), date(2024, 12, 31)),
         "F3": (date(2025, 1, 1), date(2025, 12, 31)), "F4": (date(2026, 1, 1), date(2026, 3, 15))}
FOLD_EMBARGO_DAYS = 10


def part(d: date) -> str:
    if HOLDOUT[0] <= d <= HOLDOUT[1]:
        return "holdout"
    if DESIGN[0] <= d <= DESIGN[1]:
        return "design"
    if EMBARGO[0] <= d <= EMBARGO[1]:
        return "embargo"
    return "unused"


def fold_of(d: date) -> str | None:
    for k, (a, b) in FOLDS.items():
        if a <= d <= b:
            return k
    return None


# ---- grid rows (row r = the bar that STARTS at 09:15 + r; a decision at row t acts at the open of row t + 1) ---------
N = 375


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


DECISION_ROWS = list(range(row(9, 19), row(14, 54) + 1, 5))      # closes 09:20 .. 14:55 (68 rows)
MORNING_ROWS = (row(9, 19), row(9, 24), row(9, 29))              # S5: decisions at 09:20, 09:25, 09:30
EXIT_LAST = row(15, 19)                                           # square-off: the 15:19 bar's close (15:20)
EXIT_LAST_EXPIRY = row(15, 4)                                     # expiry days: 15:04 bar's close (15:05)
SEQ_N = 120

# ---- the label grid (PREREG section 4) -------------------------------------------------------------------------------
XS = (1000.0, 2000.0)
YS = (1000.0, 2000.0)
HS = (30, 60)
CELLS = [(x, y, h) for x in XS for y in YS for h in HS]          # 8 cells
SIDES = ("C", "P")
CS = [(c, s) for c in range(len(CELLS)) for s in SIDES]           # 16 cell-sides, column j = 2 * c + side
WIN, LOSS, NEITHER, UNDEF = 0, 1, 2, -1


def cell_id(x: float, y: float, h: int) -> int:
    return CELLS.index((float(x), float(y), int(h)))


# ---- models (PREREG section 5) ---------------------------------------------------------------------------------------
SEEDS = (0, 1, 2)
EPOCHS, PATIENCE, BATCH, LR, WD, DROPOUT = 20, 3, 1024, 1e-3, 1e-4, 0.2
VAL_SHARE = 0.2
CLIP = 5.0
NAN_DROP = 0.8
GBM = dict(max_iter=200, learning_rate=0.05, max_leaf_nodes=15, min_samples_leaf=50, l2_regularization=1.0,
           random_state=0, early_stopping=False)
BOOT = 1000
TWINS = 1000
SEED = 123

# ---- rules (PREREG section 6) ----------------------------------------------------------------------------------------
DAY_MAX = 5
RULES = {
    "S1": dict(kind="p", cell=cell_id(1000, 2000, 60), thr=0.70, room=None, rows=None,
               label="70% rule: +Rs 1,000 / -Rs 2,000 / 60 min, enter when P(+X first) >= 0.70"),
    "S2": dict(kind="p", cell=cell_id(1000, 2000, 60), thr=0.70, room=2000.0, rows=None,
               label="S1 + the range head says the 60-min move is worth >= Rs 2,000 on the ATM option"),
    "S3": dict(kind="p", cell=cell_id(1000, 1000, 30), thr=0.60, room=None, rows=None,
               label="1:1 at 60%: +Rs 1,000 / -Rs 1,000 / 30 min, enter when P(+X first) >= 0.60"),
    "S4": dict(kind="ev", cell=None, thr=300.0, room=None, rows=None,
               label="best expectancy over the 16 cell-sides, enter when EV >= Rs 300"),
    "S5": dict(kind="ev", cell=None, thr=300.0, room=None, rows=MORNING_ROWS,
               label="S4 at the 09:20 / 09:25 / 09:30 decisions only (R113)"),
}
RULE_IDS = tuple(RULES)
ATM_DELTA = 0.5

# gates (never relaxed)
G_WF_N, G_WF_FOLDS, G_Q = 300, 3, 0.10
G_HO_N = 100
ACCOUNT_DD, ACCOUNT_MONTH = -40000.0, -13000.0
TARGET_WIN, TARGET_AVG = 0.65, 800.0
CALIB_BINS = (0.5, 0.6, 0.7, 0.8, 1.0001)
