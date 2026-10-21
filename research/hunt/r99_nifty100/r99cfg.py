"""R99 settings: "catch the 100+ rupee move" on NIFTY weekly options (Boss, 21 Oct 2026). Pure constants only.

"100 rupees" = 100 points of the ATM option premium (Rs 100 on the quote an option buyer watches; x the lot in
rupees). Signal source of every rule: the NIFTY index (1-minute bars); India VIX is context. Trade: the ATM weekly
NIFTY option, priced by R51's held-contract engine (real prints, lakeclean foreign / stray / stale prints removed,
R17 fills, dated charges, dated lots) unchanged. Window and split: R51's (Boss, 15 Oct 2026: "Nov 2024 onwards"):
DESIGN 21 Nov 2024 - 31 Mar 2026; FINAL TEST 1 Apr - 13 Oct 2026 (read once by R99; other NIFTY studies have read it
for their own rules, so it is not fresh).
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE.parent / "r51"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
os.environ.setdefault("R51_CLEAN", "1")
os.environ.setdefault("R51_STALE", "1")

import r51cfg as K51  # noqa: E402

RESULTS = HERE / "results"
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser() / "r99"
UND = "NIFTY"
START, DESIGN_END, FINAL_START, END = K51.START, K51.DESIGN_END, K51.FINAL_START, K51.END
# fold 0 (21 Nov 2024 - 31 Mar 2025) is training only for the model; folds 1-4 are R51's design walk-forward quarters
FOLDS = ((K51.START, date(2025, 3, 31)),) + tuple(K51.WF_FOLDS)
FIRST_ROW, LAST_ROW = K51.row(9, 20), K51.row(14, 30)
SAMPLE_EVERY = 5
LABELS = ((100.0, 40.0), (50.0, 25.0))      # (target, stop) in premium points
SEED = 99
