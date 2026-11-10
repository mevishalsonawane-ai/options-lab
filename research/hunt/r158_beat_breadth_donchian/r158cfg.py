"""R158 settings (PREREG.md): a network trained on Breadth Donchian's own exit (XL) at every 5-minute moment.

    examples  R156 / R157's inputs plus $R158_OUT/xl_<UND>.npz (the XL label per moment x side)
    results   research/hunt/r158_beat_breadth_donchian/results
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
POST = os.environ.get("R158_POST", "0") == "1"
if POST:
    os.environ["R157_POST"] = "1"
p = str(HERE.parent / "r157_scalp_network_plus")
if p not in sys.path:
    sys.path.insert(0, p)
import r157cfg as K7  # noqa: E402

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

K6, K4 = K7.K6, K7.K4
OUT = Path(os.environ.get("R158_OUT", K6.CACHE_ROOT / "r158")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UNDS = K6.UNDS
SEQ_CH = K7.SEQ_CH
SEEDS = (158, 159, 160)
SEED = 158
FOLDS = K6.FOLDS
DESIGN_FOLDS = K6.DESIGN_FOLDS
PERIODS = K6.PERIODS
MAX_PER_SIDE = 2                       # R72's taking rule: one open a side, 2 fills a side a day
TOP = {"NET-TOP5": 0.95, "NET-TOP1": 0.99}
STANDALONE = ("NET-EV", "NET-TOP5", "NET-TOP1")
PICKS = ("PICK", "PICK50")
TESTS = STANDALONE + PICKS
PUBLISHED = {"design": (3071, 216929), "final": (1202, 171366)}       # R72 B0, 1 lot
R151_PICK = dict(trades=652, win=0.489, net=219057)                    # R151's picker on R72, Apr - Oct 2026 (published)
TWINS = 2000
GATE_Q = 0.10
MIN_FINAL = 40
N_PRIOR = 2127                         # R157's tally
