"""R117 settings (PREREG.md): quant risk tools on the paper book, all on top of the app's CAP2.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m (incl. INDIAVIX), index_daily, options_rolling_1m WEEK
    stores    R51's Amendment A clean stores (R51_CLEAN=1), as R101 / R106 / R107 / R108
    cache     $R117_OUT (default ~/.cache/iraalgo/r117): walks and trades (never in git)
    results   research/hunt/r117_quant_risk/results (small JSON / markdown only)

Pure: periods, rules' parameters, gates. Importing it puts R108's folder (and through it R106 / R101 / R51) on the path.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
R108 = HERE.parent / "r108_same_direction_cap"
for p in (str(R108), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

import r108cfg as K8  # noqa: E402

CACHE_ROOT = K8.CACHE_ROOT
OUT = Path(os.environ.get("R117_OUT", CACHE_ROOT / "r117")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
THRESHOLDS = RESULTS / "thresholds.json"

# periods (PREREG section 1): identical to R108
START, DESIGN_END, WF_START, FINAL_START, FINAL_END = K8.START, K8.DESIGN_END, K8.WF_START, K8.FINAL_START, K8.FINAL_END
PERIODS = K8.PERIODS
in_period = K8.in_period
ARMS = K8.ARMS
ARM_NAME = K8.ARM_NAME

# the six rules (PREREG section 2), parameters fixed before any number
BASE = "CAP2"
TESTS = ("VIXCAP", "RVCAP", "EQ10", "ARMEQ", "DAYLOCK", "COMBO")
LABEL = {
    "CAP2": "today's app book: at most 2 a way across all indices, R72's last place kept",
    "VIXCAP": "cap 1 on days the India VIX open is at or above the design's 67th percentile",
    "RVCAP": "cap 1 on days NIFTY's 20-session realised volatility (to yesterday) is at or above the design's 67th percentile",
    "EQ10": "cap 1 on days the book's own net over its previous 10 sessions is below 0",
    "ARMEQ": "an arm sits out on days its last 20 research trades (closed earlier) sum below 0",
    "DAYLOCK": "no new entry once the day's closed net reaches +Rs 6,500",
    "COMBO": "VIXCAP and EQ10 together (cap 1 when either fires)",
}
PCTL = 67.0                 # V67 / R67
RV_WINDOW = 20              # sessions of daily log returns, up to yesterday
EQ_WINDOW = 10              # sessions of the book's own net
ARM_WINDOW = 20             # the arm's last trades
DAYLOCK_AT = 6_500.0
VIX_LATEST_FIRST_ROW = 4    # the VIX open must come from 09:15-09:19 (entries start at 09:20)

# gates (PREREG section 5; never relaxed)
TWINS = 100_000
SEED = 117
GATE_Q = 0.10
MIN_CHANGED = 30
NET_SLACK_RS = 15_000.0
NET_SLACK_SHARE = 0.15
N_PRIOR = K8.N_PRIOR + 86          # R108's 5,777 + R108-R116's logged entries
ACCOUNT_DD = K8.ACCOUNT_DD
ACCOUNT_MONTH = K8.ACCOUNT_MONTH

# Part A
BOOT_PATHS = 20_000
BOOT_BLOCK = 5
BOOT_HORIZON = 120

# Amendment 1 (27 Oct, before the final read): the paired day-by-day check (r117lib.paired_days)
PAIRED_BOOT = 20_000
PAIRED_P = 0.10
PAIRED_GATED = ("DAYLOCK",)
