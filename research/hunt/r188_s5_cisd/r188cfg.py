"""R188 settings (PREREG.md). Plain-English notes, 17 Nov 2026.

Caches (never in git): $R188_OUT (default <cache>/r188): the lake view (lake/), R123's saved outputs (r123/), the
reference trades (ref/), the extracted option bars (bars/), the trade tables (trades/).

The lake view: <out>/lake/index_1m and <out>/lake/options_rolling_1m hold links to the local flat lake, the 2021-2023
index files and the 20 Oct - 13 Nov gap files (a gap file is linked as opt_<U>_WEEK_gap1113_2026.parquet, so its name
still ends in its year). IRAALGO_LAKE is pointed at it BEFORE R123's settings are imported.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R188_OUT", CACHE / "r188")).expanduser()
os.environ.setdefault("IRAALGO_LAKE", str(OUT / "lake"))
os.environ.setdefault("R123_PREDS", str(OUT / "r123"))
for p in (HUNT / "r132_ict_exits", HUNT / "r131_ict_ten", HUNT / "r124_big_moves", HUNT / "r123_neural_70",
          HUNT / "r134_forward", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r123cfg as K3  # noqa: E402

RESULTS = HERE / "results"
BARS = OUT / "bars"
TRADES = OUT / "trades"
R123 = OUT / "r123"
REF = OUT / "ref"

UNDS = K3.UNDS
LOT, STEP = K3.LOT, K3.STEP
N = K3.N
row = K3.row

# ---- periods (PREREG section 4) -------------------------------------------------------------------------------------
DESIGN = (date(2023, 1, 1), date(2026, 3, 15))          # R123 folds F1-F4
FINAL = (date(2026, 4, 1), date(2026, 10, 19))          # R123's holdout period (seen for the base)
NEW = (date(2026, 10, 21), date(2026, 11, 13))          # the gap month (descriptive)
END = NEW[1]


def period_of(d: date) -> str | None:
    if K3.fold_of(d) is not None:
        return "design"
    if FINAL[0] <= d <= FINAL[1]:
        return "final"
    if NEW[0] <= d <= NEW[1]:
        return "new"
    return None


# ---- the arms ------------------------------------------------------------------------------------------------------
MORNING_ROWS = K3.MORNING_ROWS            # decisions at 09:20, 09:25, 09:30
EV_MIN = 300.0
DAY_MAX = K3.DAY_MAX
APP_SQ = row(15, 14)                      # the app's square-off: the paper book's MIS at 15:15 (the 15:14 bar's close)
RES_SQ = K3.EXIT_LAST                     # research: 15:20
SQ_EXPIRY = K3.EXIT_LAST_EXPIRY           # S5 on expiry day: 15:05 (app and research)
LOCK_FROM, LOCK_KEEP = 1000.0, 0.60
CISD = "S3-V2"
CISD_LAST_ENTRY = row(15, 0)

# ---- levers (PREREG section 3) ---------------------------------------------------------------------------------------
EXIT_LEVERS = ("BE10", "L500", "LBE1000", "IDX", "LK500", "ST1500")
ENTRY_LEVERS = ("FT3PO", "VIX313", "EXP", "ADX20")
LEVERS = {"S5": EXIT_LEVERS + ENTRY_LEVERS, "CISD": EXIT_LEVERS + ENTRY_LEVERS + ("NTHR",)}
TESTS = [(a, x) for a in ("S5", "CISD") for x in LEVERS[a] + ("COMBO",)]
M_PRIOR = 4902                            # after R187
M_TOTAL = M_PRIOR + len(TESTS)            # 4,925
NTHR_GRID = (-100.0, -50.0, 50.0, 100.0, 150.0, 200.0)
VIX_CUT = -0.0313
ADX_CUT = 20.0
BE_X = 0.10
FT3_WIN = 15
TWINS = 500
SEED = 188
MIN_FINAL_N = 30
ACCOUNT_DD, ACCOUNT_MONTH = -40000.0, -13000.0
