"""R155 settings (PREREG.md): R150 / R151 / R144c / R125 / R115 filters, OI and candle wicks on R154's 10-15 point scalps.

    stores    R154's (early store + R51's clean stores); OI store $R155_OUT/oi_<UND>.pkl (r155oi.py)
    trades    $R155_OUT (default ~/.cache/iraalgo/r155): compact trades and features (never in git)
    results   research/hunt/r155_scalp_filters/results (small JSON / markdown only)

Importing it puts R154's folder (and through it R152 / R112 / R106 / R101 / R51), R150's and R73's on the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
POST = os.environ.get("R155_POST", "0") == "1"
if POST:
    os.environ["R154_POST"] = "1"
for _d in ("r154_scalp_10_15", "r150_sr_levels", "r73"):
    p = str(HERE.parent / _d)
    if p not in sys.path:
        sys.path.insert(0, p)
import r154cfg as K4  # noqa: E402  (R152 / R112 / R106 / R101 / R51 paths)

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R155_OUT", CACHE_ROOT / "r155")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
POST_FLAG = RESULTS / "POST_READ"

UNDS = K4.UNDS
ENTRIES = K4.ENTRIES
RULE_IDS = K4.RULE_IDS
RULES = K4.RULES

# ------------------------------------------------------------------------------------------------ filter settings
WALL = 0.0025                 # a level within 0.25% ahead
TOUCHES = 3
K5, K15 = 6, 12               # pivot bars each side: 5-minute levels (R150 B), 15-minute major levels (R150 A1)
WINDOWS5 = {"W5": 5, "W20": 20, "W50": 50}
WINDOW15 = 63
B_LOOK = 5                    # B20: the move from s - 5 to s
EMA_N = 20
ST_N, ST_MULT = 10, 3.0
OI_LOOK = 15                  # OIB / PCR / FT3: the last 15 minutes
OI_STALE = 5                  # a reading at most 5 minutes old
OIB_RANGE = 3                 # ATM +-3 strikes
WICK = 0.5
BODY = 0.6
STACK_OF = ("F6", "F8", "F9", "F10", "F11", "F12", "F17")
STACK_MIN = 5
PICK_C = 0.1
PICK_MIN_TRAIN = 300
PICK_FOLDS = (date(2022, 1, 1), date(2023, 1, 1), date(2024, 1, 1), date(2025, 1, 1)) + tuple(q[0] for q in K4.P2_Q) \
    + (K4.FINAL_START,)

FILTERS = ("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "F13", "F14", "F15", "F16", "F17",
           "F18", "F19", "F20")
FNAME = {
    "F0": "no filter (R154's scalp)",
    "F1": "W5: no 5-min S/R level (5 sessions) within 0.25% ahead",
    "F2": "W20: no 5-min S/R level (20 sessions) within 0.25% ahead",
    "F3": "W50: no 5-min S/R level (50 sessions) within 0.25% ahead",
    "F4": "W3M: no 15-min major level (3 months) within 0.25% ahead",
    "F5": "PIV: no floor pivot (P, R1, S1, R2, S2) within 0.25% ahead",
    "F6": "ALLSR: no W20 / W50 / W3M / pivot line within 0.25% ahead (all lines at once)",
    "F7": "B20: the last 5 minutes crossed a 20-session level the trade's way",
    "F8": "EMA20: last 5-min close on the trade's side of EMA20",
    "F9": "ST: Supertrend (10, 3) on 5-min bars agrees",
    "F10": "TWAP: index on the trade's side of the session average price",
    "F11": "OIB: 15-min OI build-up (ATM +-3) agrees",
    "F12": "PCR: 15-min PCR change agrees",
    "F13": "FT3: not (option price and OI both fell in 15 min)",
    "F14": "WKA5: last 5-min wick against the trade < 50% of the bar",
    "F15": "WKA1: last 1-min wick against the trade < 50% of the bar",
    "F16": "WKF5: last 5-min wick for the trade >= 50% (rejection candle)",
    "F17": "CLOC: last 5-min close in the trade-side third of the bar",
    "F18": "BODY: last 5-min body >= 60% of the bar, coloured the trade's way",
    "F19": "STACK: at least 5 of 7 (ALLSR, EMA20, ST, TWAP, OIB, PCR, CLOC) agree",
    "F20": "PICK: logistic picker on every feature (walk-forward)",
}
FAMILY = {**{f: "S/R" for f in ("F1", "F2", "F3", "F4", "F5", "F6", "F7")}, "F8": "trend", "F9": "trend", "F10": "trend",
          "F11": "OI", "F12": "OI", "F13": "OI", **{f: "wick" for f in ("F14", "F15", "F16", "F17", "F18")},
          "F19": "all", "F20": "all"}
TESTS = tuple((f, e, rd) for f in FILTERS for e in ENTRIES for rd in ("A", "B"))        # 280

# ------------------------------------------------------------------------------------------------ gates (PREREG 3)
TWINS = 2000
SEED = 155
GATE_Q = 0.10
MIN_FINAL_PER_RULE = 40
N_PRIOR = 1682                # R154's tally (890 earlier + R154's 792)


def rules_of(reading: str) -> list:
    return [r for r in RULE_IDS if RULES[r]["reading"] == reading]
