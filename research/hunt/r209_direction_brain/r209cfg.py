"""R209 settings (PREREG.md): a direction brain over all five indices, India VIX and the option chain. Plain-English
notes, 20 Nov 2026.

    raw       ~/.cache/iraalgo/dhan_git/dhan-data (git archive of the dhan-data branch; never committed)
    out       ~/.cache/iraalgo/r209 (the input table, predictions, models; never in git)
    results   research/hunt/r209_direction_brain/results (small JSON / markdown only)
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
ROOT = HERE.parents[2]
for p in (str(HERE),):
    if p not in sys.path:
        sys.path.insert(0, p)

HOME = Path.home() / ".cache" / "iraalgo"
SRC = HOME / "dhan_git" / "dhan-data"
OUT = HOME / "r209"
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
VIX = "INDIA_VIX"
FIRST_DAY, LAST_DAY = date(2021, 10, 8), date(2026, 10, 5)
TRAIN_FROM = date(2022, 1, 1)
HOLDOUT = (date(2026, 4, 1), date(2026, 10, 5))
QUARTERS = tuple(date(y, m, 1) for y in (2023, 2024, 2025, 2026) for m in (1, 4, 7, 10))[:13]   # 2023Q1 .. 2026Q1
NROWS = 375
DECISION = tuple(range(5, 356, 5))       # 09:20 .. 15:10
OFFS = 5                                 # ATM-5 .. ATM+5 for OI / volume
SKEW_OFF = 2

MODELS = ("LOG", "GBM", "NET")
GBM = dict(max_iter=300, learning_rate=0.05, max_leaf_nodes=31, min_samples_leaf=500, l2_regularization=1.0,
           early_stopping=False, random_state=209)
NET = dict(hidden_layer_sizes=(64, 32), activation="relu", alpha=1e-3, early_stopping=True, validation_fraction=0.1,
           max_iter=200, random_state=209)
LOG_C = 1.0
T3_GRID = (0.45, 0.48, 0.50, 0.52)

BOOT = 2000
SEED = 209
GATE_P, GATE_Q = 0.05, 0.10
AUC_MIN = 0.530
TOP = 0.20
TOP_ACC = 0.55
TOP_QUARTERS = 9
T2_EDGE = 0.03
N_PRIOR = 4904
TESTS = ("T1-LOG", "T1-GBM", "T1-NET", "T2-R72", "T2-R51", "T2-LIQ", "T3-R72", "T3-LIQ")
M_TOTAL = N_PRIOR + len(TESTS)           # 4,912
