"""R202 settings (PREREG.md): the 25 price-action concepts as arm candidates. Plain-English notes, 21 Nov 2026.

    raw      ~/.cache/iraalgo/r202/raw    the Drive lake files copied with rclone (never in git)
    lake     ~/.cache/iraalgo/r202/lake   one flat folder in the lake's layout (r202data.py lake)
    stores   ~/.cache/iraalgo/r202/r51/clean   R51's day stores (Amendment A cleaning), 1 Dec 2021 - 13 Nov 2026
    results  research/hunt/r202_price_action25/results (small JSON / markdown only)

Importing this module sets the environment R51's stack reads; import it before any of them.
Signal source of every rule: the index's 5-minute bars. Options are only bought (1 strike in the money).
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
HUNT = HERE.parent
HOME = Path.home() / ".cache" / "iraalgo"
OUT = Path(os.environ.get("R202_OUT", HOME / "r202")).expanduser()
RAW = OUT / "raw"
LAKE = OUT / "lake"
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"

os.environ["IRAALGO_LAKE"] = str(LAKE)
os.environ["IRAALGO_CACHE"] = str(OUT / "cache")
os.environ["R51_OUT"] = str(OUT / "r51")
os.environ["R51_CLEAN"] = "1"
os.environ.pop("R51_STALE", None)
for p in (str(ROOT), str(ROOT / "research"), str(HUNT / "r51"), str(HUNT / "r186_fixed_profit"), str(HUNT / "r176_step_trail_500"),
          str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STORE = (date(2021, 10, 1), date(2021, 12, 1), date(2026, 11, 13))     # warm-up, first option day, last day

DESIGN = (date(2021, 12, 1), date(2026, 3, 31))
HOLDOUT = (date(2026, 4, 1), date(2026, 11, 13))
FOLDS = {"F1": (date(2021, 12, 1), date(2022, 12, 31)), "F2": (date(2023, 1, 1), date(2023, 12, 31)),
         "F3": (date(2024, 1, 1), date(2024, 12, 31)), "F4": (date(2025, 1, 1), date(2025, 12, 31)),
         "F5": (date(2026, 1, 1), date(2026, 3, 31))}

EXITS = ("X", "V")                         # X: plain XB; V: XL + breakeven at +10% + L500 (V5 as the arms)
MONEY = 1                                  # 1 strike in the money
MAX_DAY = 3
LEVEL_SESS = 5                             # sessions before today the series carries

TWINS = 200
SEED = 202
GATE_P = 0.05
GATE_Q = 0.10
MIN_DESIGN, MIN_HOLDOUT = 100, 40
MIN_FOLDS_POS = 3
ACC_DD = 40000.0
MONTH_CAP = -13000.0
TOP_N = 5
N_PRIOR = 4808                             # entry files at the branch base (origin/main a3cc6b95)
N_TESTS = 42
M_TOTAL = N_PRIOR + N_TESTS                # 4,850
TWIN_FIRST, TWIN_LAST = 30, 315            # random entry rows 09:45 .. 14:30
