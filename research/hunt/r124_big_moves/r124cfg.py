"""R124 settings (PREREG.md): every 30-point move traced back, a network that sees it coming, and 4 option rules.

Reuses R123's data pipeline, cleaning, costs and multi-level network (research/hunt/r123_neural_70). Periods are
R123's. Caches: $R124_OUT (default <cache>/r124); small results in results/.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
R123 = HERE.parent / "r123_neural_70"
for p in (str(R123), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

import r123cfg as K3  # noqa: E402

ROOT = K3.ROOT
OUT = Path(os.environ.get("R124_OUT", K3.CACHE_ROOT / "r124")).expanduser()
RESULTS = HERE / "results"
DRIVE = "gdrive:IraAlgo-data-lake/derived/r124"

UNDS = K3.UNDS
N = K3.N
DESIGN, EMBARGO, HOLDOUT, FOLDS = K3.DESIGN, K3.EMBARGO, K3.HOLDOUT, K3.FOLDS
part, fold_of, row = K3.part, K3.fold_of, K3.row
LOT, STEP, BSE = K3.LOT, K3.STEP, K3.BSE

# ---- the move (PREREG section 2) ---------------------------------------------------------------------------------
NIFTY_PTS = 30.0
REVERSAL_PTS = 10.0
CONTROL_GAP = 5                      # control minutes are > 5 minutes from any big-move start
SLOT = 30                            # time-of-day slot (minutes)
HS = (15, 30)
UP, DOWN, NEITHER, UNCLEAR = 0, 1, 2, -1

FACTORS = {
    "F1": "time of day (30-minute slot)",
    "F2": "compression: last-30-min range / 20-day average daily range",
    "F3": "last 5 min move, in the coming move's direction (%)",
    "F4": "last 15 min move, in the coming move's direction (%)",
    "F5": "distance to the day's open (in move sizes)",
    "F6": "distance to the previous day's high (in move sizes)",
    "F7": "distance to the previous day's low (in move sizes)",
    "F8": "distance to the previous close (in move sizes)",
    "F9": "distance to the opening range (09:15-09:29) high or low, the nearer (in move sizes)",
    "F10": "distance to the nearest round 100 (in move sizes)",
    "F11": "India VIX level",
    "F12": "India VIX 30-minute change (%)",
    "F13": "other indices' last 15 min move, in the coming move's direction (%)",
    "F14": "PCR change over 15 minutes",
    "F15": "call OI change over 15 minutes (%)",
    "F16": "put OI change over 15 minutes (%)",
    "F17": "ATM straddle as % of spot",
    "F18": "expiry day",
    "F19": "opening gap size (|%|)",
    "F20": "minutes since the last confirmed big move ended",
}
SIGNED = ("F3", "F4", "F13")         # use the coming move's direction: description only, never a forecast input

# ---- part C rules (PREREG section 6) ---------------------------------------------------------------------------
TOP_Q = 0.90
DIR_SHARE = 0.60
RULES = {
    "M1": dict(h=15, kind="side", rows=None, label="move coming in 15 min (top 10%) + direction >= 60%: ATM call/put"),
    "M2": dict(h=30, kind="side", rows=None, label="as M1 for 30 min"),
    "M3": dict(h=15, kind="side", rows=K3.MORNING_ROWS, label="M1 at 09:20 / 09:25 / 09:30 only"),
    "M4": dict(h=15, kind="straddle", rows=None, label="move coming in 15 min (top 10%): ATM straddle"),
}
RULE_IDS = tuple(RULES)
DAY_MAX = 5
TWINS, BOOT, SEED = 1000, 1000, 124
G_WF_N, G_WF_FOLDS, G_Q, G_HO_N = 300, 3, 0.10, 100
ACCOUNT_DD, ACCOUNT_MONTH = -40000.0, -13000.0
