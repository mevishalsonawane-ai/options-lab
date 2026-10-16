"""R73 settings (PREREG.md): Donchian 20 with a trend filter or a 1-minute candle wait before the option is bought.

    stores    R51's Amendment A clean stores (R51_CLEAN=1, ~/.cache/iraalgo/r51/clean), built by r51data.py
    trades    $R73_OUT (default ~/.cache/iraalgo/r73): every priced trade (never in git)
    results   research/hunt/r73/results (small JSON / markdown only)

Periods, trade-taking and gates are R71's (r71cfg, the same as R51 / R64-R66); exits are R66's paper set (P = A4 + L25)
and the same without L25 (Q = A4). Pure.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
for _p in ("r71", "r66", "r65", "r64"):
    if str(HERE.parent / _p) not in sys.path:
        sys.path.insert(0, str(HERE.parent / _p))
from r71cfg import (CACHE_ROOT, DESIGN_END, FINAL_START, FIRST_ROW, GATE_MIN_TRADES, GATE_P, GATE_Q,  # noqa: E402,F401
                    LAST_ROW_HM, MAX_PER_SIDE, MONEY, ROOT, START, WF_FOLDS)
import r66cfg as R66  # noqa: E402

OUT = Path(os.environ.get("R73_OUT", CACHE_ROOT / "r73")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# PREREG section 1: P = paper today (A4 ladder + L25, the primary set), Q = A4 only. Donchian's own exits (-15%,
# 30 minutes, square-off 15:15) underneath both.
EXIT_SETS = {"P": R66.exit_spec(R66.OWN["Donchian"], "L25"), "Q": R66.exit_spec(R66.OWN["Donchian"], "A4")}
# R71 / R66's plain Donchian exits (-15% / +30% / 30 min), priced only to check the baseline numbers match
CHECK_SETS = {"XB": R66.exit_spec(R66.OWN["Donchian"], "B0")}

TWINS = 500
SEED = 72                                   # PREREG section 3 (frozen as written)
BIG = 0.30                                  # a "big winner": closed at +30% or more on the premium

# the numbers R66 / R71 / R72 published for the same code (test: the baseline must reproduce them)
EXPECT = {("XB", "design"): 8281, ("P", "design"): 9998, ("Q", "design"): 7474,
          ("XB", "final"): 55439, ("P", "final"): 81845, ("Q", "final"): 78777,
          ("C1", "design"): 216929, ("C1", "final"): 171366}
