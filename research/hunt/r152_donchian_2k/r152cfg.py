"""R152 settings (PREREG.md): the two Donchian arms sold at +Rs 2,000 and / or -Rs 2,000 for the whole position, 1-3 lots.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, index_daily, options_rolling_1m (WEEK)
    stores    R51's Amendment A clean stores (R51_CLEAN=1); the post window (14-19 Oct) uses R106's stores built to 19 Oct
              (R152_POST=1 -> R106_POST=1)
    trades    $R152_OUT (default ~/.cache/iraalgo/r152): priced trades (never in git)
    results   research/hunt/r152_donchian_2k/results (small JSON / markdown only)

Pure: periods, rules, lots and gates. Importing it puts R112's folder (and through it R106 / R101 / R73 / R51) on the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
POST = os.environ.get("R152_POST", "0") == "1"
if POST:
    os.environ["R106_POST"] = "1"            # R106's post stores (clean_post, to 19 Oct)
os.environ["R51_CLEAN"] = "1"
for _d in ("r112_donchian_morning", "r106_small_targets"):
    p = str(HERE.parent / _d)
    if p not in sys.path:
        sys.path.insert(0, p)
import r112cfg  # noqa: E402,F401  (puts r73, r108, r106 and through them r101 / r51 on the path)
import r106cfg  # noqa: E402,F401  (puts r101 on the path)

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R152_OUT", CACHE_ROOT / "r152")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
POST_FLAG = RESULTS / "POST_READ"

# periods (PREREG section 3): identical to R106
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
WF_START = date(2025, 4, 1)
WF_Q = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
        (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)
POST_START = date(2026, 10, 14)
POST_END = date(2026, 10, 19)

ARMS = ("R72", "R51")
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 (+ Supertrend 10, 3)"}

# B0 parity at 1 lot (PREREG section 1): (trades, net). R72 = R101 / R106; R51 = R115's Supertrend R51 (today's rules);
# R51plain = R106's R51 before the Supertrend filter (an engine check only).
PUBLISHED = {
    ("R72", "design"): (3071, 216929), ("R72", "final"): (1202, 171366),
    ("R51", "design"): (4208, 50425), ("R51", "final"): (1670, 134743),
    ("R51plain", "design"): (4498, 9998), ("R51plain", "final"): (1781, 81845),
}

AMOUNT = 2000.0                      # Boss, 7 Nov: Rs 2,000 for the whole position
LOTS = (1, 2, 3)
# the 3 rules (PREREG section 2): rupee target / rupee stop for the position (None = none; the arm's -15% stop always stays)
RULES = {
    "T2000": dict(target=AMOUNT, stop=None),
    "T2000S": dict(target=AMOUNT, stop=AMOUNT),
    "S2000": dict(target=None, stop=AMOUNT),
}
RULE_IDS = tuple(RULES)
LABEL = {
    "B0": "today's rules (-15% stop, ladder + L25, 30 min, 15:15)",
    "T2000": "sell at +Rs 2,000, own stop",
    "T2000S": "sell at +Rs 2,000 or -Rs 2,000 (or -15% if nearer)",
    "S2000": "sell at -Rs 2,000 (or -15% if nearer), no target",
}
TESTS = tuple((a, r, n) for a in ARMS for r in RULE_IDS for n in LOTS)      # 18

# gates (PREREG section 5; R106's, never relaxed)
TWINS = 500
SEED = 152
GATE_Q = 0.10
MIN_FINAL = 40
EQUAL_NET = 0.05
SMALLER_RISK = 0.20
# earlier tests taken as p = 1: R106's 36 + its 513, and the 299 later arm exit / filter entries (PREREG section 5)
PRIOR_TESTS = {"R106 (36 + 513 earlier)": 549, "trade-management 26 Oct - 6 Nov": 156, "daily-filters": 54,
               "sizing-execution": 24, "flow-oi": 12, "R115 pattern filters": 10, "R150 levels": 23, "R151 pickers": 20}
N_PRIOR = sum(PRIOR_TESTS.values())  # 848

# second look (PREREG section 4): R151's app pickers on Apr - Oct 2026
R151 = HERE.parent / "r151_donchian_winrate60"
PICKER = {"R72": ("r72_picker_model.json", 0.4656, (652, 219057)), "R51": ("r51_picker_model.json", 0.4469, (693, 159040))}


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    if POST_START <= d <= POST_END:
        return "post"
    return None
