"""R59b settings (PREREG.md, 15 Oct 2026): audit of another AI's "Bollinger Band Mean Reversion" on stock options.

Reuses R59's code (research/hunt/r59): its data loader, held-contract book, fills, stop, charges, twins and frozen
per-stock spread (research/hunt/r59/results/spread.json). Only the signals, the profit-lock variant, gross before
costs and the literal decomposition are new here.

    lake       $R59_LAKE (as R59): stkopt/, equity_1m/, bhav/ copied from gdrive:IraAlgo-data-lake (never committed)
    results    research/hunt/r59b/results (small JSON / markdown only)
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
R59 = HERE.parent / "r59"
for p in (str(HERE.parents[1]), str(R59), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

import r59cfg as K59  # noqa: E402

ROOT = K59.ROOT
RESULTS = HERE / "results"
FINAL_LOCK = RESULTS / "final_read.json"
SPREAD_JSON = R59 / "results" / "spread.json"     # R59's design-only Roll estimates, frozen in commit 68e9ef6

STOCKS = K59.STOCKS
LIT_START, LIT_END = K59.LIT_START, K59.LIT_END   # their claim's span (literal check only)
START = K59.START                                 # 20 Nov 2024: Boss's data rule
DESIGN_END = K59.DESIGN_END                       # 31 Mar 2026
FINAL_START = K59.FINAL_START                     # 1 Apr 2026 - latest, read once
FINAL_END = date(2099, 1, 1)                      # "latest": whatever the lake holds

# the band (theirs, unchanged)
BB_N = 20
BB_K = 2.0
BB_DDOF = 1                                       # pandas rolling std default
MIN_PREM = K59.MIN_PREM                           # Rs 5
THEIR_NOTIONAL, THEIR_COST, THEIR_STEP = K59.THEIR_NOTIONAL, K59.THEIR_COST, K59.THEIR_STEP

# exits
VARIANTS = ("NS", "S25", "S25L")
FAMILIES = ("A", "B")
LOCK_AT = 0.20                                    # S25L: once a row's high reaches +20% over the fill ...
                                                  # ... the stop moves to the fill (breakeven) from the next row

# tests
TWINS = K59.TWINS
SEED = 591
GATE_TRADES, GATE_P, GATE_Q = K59.GATE_TRADES, K59.GATE_P, K59.GATE_Q
TUNE_T = 2.0                                      # gross t-stat needed in design AND final to call tuning worth trying
