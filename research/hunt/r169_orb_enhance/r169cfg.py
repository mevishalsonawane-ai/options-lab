"""R169 settings (PREREG.md): periods, the pre-registered constants and gates. Importing it puts R3's engine folder
(research/hunt/r3orb) and the repository's research folder on the path.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake
    store     R3's session store rebuilt to 19 Oct 2026: $R169_OUT/sess_BANKNIFTY.pkl (never in git)
    network   R123's N1 prediction rows: $R123_PREDS (default ~/.cache/iraalgo/r123), from derived/r123/cleaned
    results   research/hunt/r169_orb_enhance/results (small JSON / CSV / markdown only)

Plain-English notes, 13 Nov 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R169_OUT", CACHE_ROOT / "r169")).expanduser()
os.environ.setdefault("R3_OUT", str(OUT))
for p in (ROOT / "research", ROOT / "research" / "hunt" / "r3orb", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
import r3cfg as K3  # noqa: E402

PREDS = Path(os.environ.get("R123_PREDS", CACHE_ROOT / "r123")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UND = "BANKNIFTY"
ARMS = ("orb", "orb_fresh", "orb_sweep")
ARM_NAME = {"orb": "ORB", "orb_fresh": "ORB Fresh", "orb_sweep": "ORB Sweep"}
LOTS = (1, 2, 3)

HISTORY = (date(2021, 8, 1), date(2024, 11, 19))
DESIGN = (date(2024, 11, 20), date(2026, 3, 31))
WF_Q = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
        (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
WF = (WF_Q[0][0], WF_Q[-1][1])
FINAL = (date(2026, 4, 1), date(2026, 10, 12))
POST = (date(2026, 10, 13), date(2026, 10, 19))
STORE_END = date(2026, 10, 19)

# R144b's design figures (R3 engine, Costs('app'), 1 lot) that R169 must reproduce first (PREREG section 1)
PARITY = {"orb": (320, -41427), "orb_fresh": (320, -41427), "orb_sweep": (454, -112523)}

# PREREG section 3 constants
BIG_GAP, SMALL_GAP = 0.6, 0.3          # % (F5)
ROOM_PTS = 30.0                        # BANKNIFTY points (F8)
BE10 = 0.10                            # X1
T40, T20, TS_GAIN = 40, 20, 0.05       # X2 / X3: minutes, +5% over the fill
G3_SHARE = 0.5                         # X4: armed once +50% of the target
LIQ_STOP, LIQ_TIME = 0.15, 20          # L1
PICK_MIN = 150                         # F1: training trades needed
BUSY_N, BUSY_NEED = 20, 10             # F3

# gates (PREREG section 4)
WIN_UP = 0.05
TWINS = 2000
SEED = 169
P_MAX, Q_MAX = 0.05, 0.10
MIN_FINAL = 40
DD_LIMIT, MONTH_LIMIT = 40_000.0, 13_000.0
M_PRIOR = 2664                         # the repository's BH count through R167
N_TESTS = 207


def in_(d: date, w: tuple) -> bool:
    return w[0] <= d <= w[1]
