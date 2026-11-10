"""R156 settings (PREREG.md): a neural network trained on the scalp's own result at every 5-minute moment.

    stores    R154's (early store + R51's clean stores), R155's OI store, the lake's India VIX minutes
    examples  $R156_OUT (default ~/.cache/iraalgo/r156): ex_<UND>.npz (inputs + labels), forecasts (never in git)
    results   research/hunt/r156_scalp_network/results (small JSON / markdown only)

Importing it puts R155's folder (and through it R154 / R152 / ... / R51) on the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
POST = os.environ.get("R156_POST", "0") == "1"
if POST:
    os.environ["R155_POST"] = "1"
p = str(HERE.parent / "r155_scalp_filters")
if p not in sys.path:
    sys.path.insert(0, p)
import r155cfg as K5  # noqa: E402

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

K4 = K5.K4
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R156_OUT", CACHE_ROOT / "r156")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UNDS = K4.UNDS
RULE_IDS = K4.RULE_IDS                      # 24
RULES = K4.RULES
NR = len(RULE_IDS)
A_IDX = tuple(i for i, r in enumerate(RULE_IDS) if RULES[r]["reading"] == "A")
B_IDX = tuple(i for i, r in enumerate(RULE_IDS) if RULES[r]["reading"] == "B")

# ------------------------------------------------------------------------------------------------ examples (PREREG 1-2)
GRID = tuple(range(4, K4.LAST_ENTRY, 5))    # decision rows s = 09:19, 09:24, ..., 14:44 (entry row s + 1 <= 14:45)
SEQ_LEN = 60
SEQ_CH = 5
SCALE_N = 20                                # sessions behind the index's scale
SCALE_MIN = 5
CLIP = 10.0
N_TAB = 43
SIZES = (10.0, 12.0, 15.0)

# ------------------------------------------------------------------------------------------------ network (PREREG 3)
SEEDS = (156, 157, 158)
EPOCHS = 6
BATCH = 2048
LR = 1e-3
WD = 1e-5
DROPOUT = 0.2
VAL_SHARE = 0.10
FOLDS = (                                   # (name, first scored day, last scored day); trained on every day before
    ("2023", date(2023, 1, 1), date(2023, 12, 31)),
    ("2024", date(2024, 1, 1), date(2024, 12, 31)),
    ("2025", date(2025, 1, 1), date(2025, 12, 31)),
    ("2026q1", date(2026, 1, 1), date(2026, 3, 31)),
    ("final", date(2026, 4, 1), date(2026, 10, 19)),          # the final and the post window share one model
)
DESIGN_FOLDS = ("2023", "2024", "2025", "2026q1")

# ------------------------------------------------------------------------------------------------ trading rules, periods, gates
MAX_PER_DAY = K4.MAX_PER_DAY
TOP_Q = 0.90
PERIODS = {"O1": (date(2023, 1, 1), date(2025, 3, 31)), "P2": (K4.P2_START, K4.P2_END),
           "P3": (K4.FINAL_START, K4.FINAL_END), "P4": (K4.POST_START, K4.POST_END)}
TESTS = tuple(f"EV-{r}" for r in RULE_IDS) + tuple(f"TOP-{r}" for r in RULE_IDS) + ("BEST-A", "BEST-B", "BEST")   # 51
TWINS = 2000
SEED = 156
GATE_Q = 0.10
MIN_FINAL = 40
N_PRIOR = 1962                              # R155's tally

TAB_GROUPS = {                              # for the shuffle test (PREREG 6): column ranges of the table
    "support / resistance": tuple(range(0, 11)),
    "trend (EMA, Supertrend, average price)": (11, 12, 13),
    "OI": (14, 15, 16, 17),
    "candle wicks": tuple(range(18, 25)),
    "time and calendar": (25, 33, 34),
    "VIX, scale, day so far, premium": (27, 28, 29, 30, 31, 32, 35, 36, 37),
    "index and side": (26, 38, 39, 40, 41, 42),
}
