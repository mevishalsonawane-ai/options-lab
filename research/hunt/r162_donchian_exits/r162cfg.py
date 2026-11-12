"""R162 settings (PREREG.md): trade structure for a higher win rate on the two Donchian arms, 1-3 lots.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, index_daily, options_rolling_1m (WEEK)
    stores    R51's Amendment A clean stores (R51_CLEAN=1), built by `R51_CLEAN=1 python research/hunt/r51/r51data.py build`
    trades    $R162_OUT (default ~/.cache/iraalgo/r162): priced trades (never in git)
    results   research/hunt/r162_donchian_exits/results (small JSON / CSV / markdown only)

Pure: periods, rules, lots, tests and gates. Importing it puts R152's folder (and through it R112 / R106 / R101 / R51)
on the path. Plain-English notes, 12 Nov 2026.
"""
from __future__ import annotations

import math
import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
os.environ["R51_CLEAN"] = "1"
for _d in ("r152_donchian_2k",):
    p = str(HERE.parent / _d)
    if p not in sys.path:
        sys.path.insert(0, p)
import r152cfg as K152  # noqa: E402  (R152's periods, B0 parity and the R112 / R106 / R101 / R51 paths)

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R162_OUT", CACHE_ROOT / "r162")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods (PREREG section 3): R152's
START, DESIGN_END, WF_START, WF_Q = K152.START, K152.DESIGN_END, K152.WF_START, K152.WF_Q
FINAL_START, FINAL_END = K152.FINAL_START, K152.FINAL_END
ARMS = ("R72", "R51")
ARM_NAME = K152.ARM_NAME
PUBLISHED = {k: v for k, v in K152.PUBLISHED.items() if k[0] in ARMS}     # B0 at 1 lot (asserted)

LOTS = (1, 2, 3)
NAN = math.nan
L25 = ((0.30, NAN), (0.60, 0.30), (1.00, 0.60), (0.25, 0.05))             # today's ladder + L25 (r71cfg.LADDER_L25)

# the rules (PREREG section 2); kind picks how r162engine prices them
RULES = {
    "SO8": dict(kind="so", x=0.08), "SO10": dict(kind="so", x=0.10), "SO15": dict(kind="so", x=0.15),
    "BE10": dict(kind="be", x=0.10), "BE15": dict(kind="be", x=0.15), "BE20": dict(kind="be", x=0.20),
    "LF1": dict(kind="steps", add=((0.10, -0.05), (0.15, 0.03), (0.20, 0.08))),
    "LF2": dict(kind="steps", add=((0.10, 0.02), (0.15, 0.07), (0.20, 0.12), (0.25, 0.17))),
    "T20": dict(kind="time", tmax=20, need=-1.0), "T40": dict(kind="time", tmax=40, need=-1.0),
    "T45": dict(kind="time", tmax=45, need=-1.0), "NIP30": dict(kind="time", tmax=30, need=0.0),
    "ATM": dict(kind="strike", money=0), "ITM2": dict(kind="strike", money=2),
    "D60": dict(kind="strike", delta=0.60), "D70": dict(kind="strike", delta=0.70),
    "IXR": dict(kind="ix", keep_prem=False), "IXA": dict(kind="ix", keep_prem=True),
}
RULE_IDS = tuple(RULES)
FAMILY = {"so": "A scale-out", "be": "B breakeven", "steps": "C finer locks", "time": "D time stop",
          "strike": "E strike", "ix": "F index stop"}
LABEL = {
    "B0": "today's exits (-15% stop, ladder + L25, 30 min, 15:15, 1-ITM)",
    "SO8": "sell 1 lot at +8%, the rest on today's exits", "SO10": "sell 1 lot at +10%, the rest on today's exits",
    "SO15": "sell 1 lot at +15%, the rest on today's exits",
    "BE10": "stop to breakeven (net Rs 0) after +10%", "BE15": "stop to breakeven (net Rs 0) after +15%",
    "BE20": "stop to breakeven (net Rs 0) after +20%",
    "LF1": "early lock steps (+10% locks -5%, +15% +3%, +20% +8%)",
    "LF2": "tight lock steps (+10% locks +2%, +15% +7%, +20% +12%, +25% +17%)",
    "T20": "time stop 20 min", "T40": "time stop 40 min", "T45": "time stop 45 min",
    "NIP30": "30-min time stop only when not in profit",
    "ATM": "ATM strike", "ITM2": "2-ITM strike", "D60": "strike nearest delta 0.60", "D70": "strike nearest delta 0.70",
    "IXR": "index stop (signal bar's far end) instead of -15%", "IXA": "index stop added to -15%",
}


def lots_of(rule: str) -> tuple:
    """Scale-out needs at least 2 lots (PREREG A)."""
    return (2, 3) if RULES[rule]["kind"] == "so" else LOTS


TESTS = tuple((a, r, n) for a in ARMS for r in RULE_IDS for n in lots_of(r))   # 102

# strike rule details (PREREG E)
RATE = 0.065
TWIN_MONIES = (0, 1, 2, 3)

# twins and gates (PREREG sections 5 and 6)
TWINS = 500
SEED = 162
GATE_Q = 0.10
MIN_FINAL = 40
WIN_UP = 0.05
# earlier tests taken as p = 1: R153's m (24 + R152's 866) and the entries logged since (R154, R155, R160)
PRIOR_TESTS = {"R153 m (R153 24 + R152 866)": 890, "R154 / R155 / R160 entries": 357}
N_PRIOR = sum(PRIOR_TESTS.values())  # 1,247

# second look and the release item (PREREG sections 6, 7): R151's app pickers via R152's helpers
RELEASE = {"P-APP": ("hold", None), "P-N5": ("hold", 5), "P-N10": ("hold", 10), "P-IMM": ("hold", 0),
           "P-R151": ("remove", None)}


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    return None
