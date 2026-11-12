"""R166 settings (PREREG.md): six checks on the two Donchian arms, Nov 2024 onward, 1 lot.

    stores    R51's Amendment A clean stores (R51_CLEAN=1), as R152 / R162
    trades    $R166_OUT (default ~/.cache/iraalgo/r166): priced trades (never in git)
    results   research/hunt/r166_donchian_six/results (small JSON / CSV / markdown only)

Importing it puts R162's folder (and through it R152 / R112 / R106 / R101 / R51) on the path. 12 Nov 2026.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
_R162 = str(HERE.parent / "r162_donchian_exits")
if _R162 not in sys.path:
    sys.path.insert(0, _R162)
import r162cfg as K162  # noqa: E402

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

OUT = Path(os.environ.get("R166_OUT", K162.CACHE_ROOT / "r166")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
START, DESIGN_END, WF_START, WF_Q = K162.START, K162.DESIGN_END, K162.WF_START, K162.WF_Q
FINAL_START, FINAL_END = K162.FINAL_START, K162.FINAL_END
ARMS = K162.ARMS
ARM_NAME = K162.ARM_NAME

FAMILY = {"A": "delta", "B": "support / resistance", "C": "fake breakout", "D": "fake volume",
          "E": "fake buyer / seller", "F": "traps"}
RULES = {
    "D60C2": dict(fam="A", kind="strike"), "D60W": dict(fam="A", kind="strike"),
    "SR1": dict(fam="B", kind="filter"), "SR2": dict(fam="B", kind="filter"),
    "FT3": dict(fam="C", kind="exit", n=3), "FT5": dict(fam="C", kind="exit", n=5),
    "V1": dict(fam="D", kind="filter"), "V2": dict(fam="D", kind="filter"),
    "E1": dict(fam="E", kind="filter"), "E2": dict(fam="E", kind="filter"),
    "F1": dict(fam="F", kind="filter"), "F2": dict(fam="F", kind="filter"),
}
RULE_IDS = tuple(RULES)
LABEL = {
    "B0": "today's rules (1-ITM, -15% stop, ladder + L25, 30 min, 15:15)",
    "D60C2": "strike nearest delta 0.60, at most 2-ITM", "D60W": "delta 0.60 on NIFTY / SENSEX only, 1-ITM elsewhere",
    "SR1": "skip when the previous day's high / low is within 0.25% ahead",
    "SR2": "calls only above the previous day's high, puts only below its low",
    "FT3": "sell at +3 min if the index has not gone past the signal bar", "FT5": "the same at +5 min",
    "V1": "skip when the option's signal-bar volume is below its usual (ratio < 1)",
    "V2": "skip a volume climax in the option (ratio >= 3)",
    "E1": "skip when the option's implied vol fell over the last 6 minutes",
    "E2": "skip when the opposite ATM option got dearer over the last 6 minutes",
    "F1": "skip when the index broke the other way within 30 minutes",
    "F2": "skip when the signal bar is an outside (sweep) bar",
}
TESTS = tuple((a, r) for a in ARMS for r in RULE_IDS)      # 12 rules x 2 arms = 24 (PREREG Amendment 1)
LOTS = 1
TWINS = 500
SEED = 166
GATE_Q = 0.10
MIN_FINAL = 40
N_PRIOR = 2164 + 102            # R160's m and R162's 102 (PREREG 4)
WEEKLY = ("NIFTY", "SENSEX")
