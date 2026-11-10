"""R157 settings (PREREG.md): R156's scalp network with cross-index input, 1 and 3 lots, run exits and a top-1% rule.

    examples  R156's ($R156_OUT/ex_<UND>.npz) plus $R157_OUT/x_<UND>.npz (cross-index sequence, labels for 28 exits x 2 lots)
    results   research/hunt/r157_scalp_network_plus/results
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
POST = os.environ.get("R157_POST", "0") == "1"
if POST:
    os.environ["R156_POST"] = "1"
p = str(HERE.parent / "r156_scalp_network")
if p not in sys.path:
    sys.path.insert(0, p)
import r156cfg as K6  # noqa: E402

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

K4 = K6.K4
OUT = Path(os.environ.get("R157_OUT", K6.CACHE_ROOT / "r157")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UNDS = K6.UNDS
RUN = {"R10t30": dict(p=10.0, tmax=30), "R10t60": dict(p=10.0, tmax=60), "R15t30": dict(p=15.0, tmax=30),
       "R15t60": dict(p=15.0, tmax=60)}
RULE_IDS = K6.RULE_IDS + tuple(RUN)          # 28: R154's 24 fixed exits, then the 4 run exits
NR = len(RULE_IDS)
LOTS = (1, 3)
NCOL = NR * len(LOTS)                        # 56 label columns: 1 lot's 28, then 3 lots' 28
SEQ_CH = K6.SEQ_CH + 2 * (len(UNDS) - 1)     # 13
GROUPS = {"fixed, option points": K6.A_IDX, "fixed, index points": K6.B_IDX, "run exits": tuple(range(24, 28))}


def col(rule: str, lots: int) -> int:
    return LOTS.index(lots) * NR + RULE_IDS.index(rule)


TOP_Q = 0.99
TESTS = tuple(f"{kind}-{r}@{n}" for n in LOTS for kind in ("EV", "TOP99") for r in RULE_IDS) + tuple(f"BEST@{n}" for n in LOTS)
TWINS = 2000
SEED = 157
SEEDS = (157, 158, 159)
GATE_Q = 0.10
MIN_FINAL = 40
N_PRIOR = 2013                               # R156's tally
FOLDS = K6.FOLDS
DESIGN_FOLDS = K6.DESIGN_FOLDS
PERIODS = K6.PERIODS
