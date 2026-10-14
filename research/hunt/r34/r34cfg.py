"""R34 settings (PREREG.md): the NIFTY zero-to-hero hunt with new signal families (option fair value from realized
volatility, dealer-gamma pinning, intraday momentum) and a walk-forward ML ranker built on R25's / R26's engine.

Uses R26's day pickles (<cache>/r26/days_NIFTY.pkl, built with R25's loader) and research/brain/bs.py.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R25 = ROOT / "research" / "hunt" / "r25"
R26 = ROOT / "research" / "hunt" / "r26"
for p in (ROOT, ROOT / "research", R25, R26, HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))           # HERE first: R34's modules are never shadowed

import r26cfg as K26  # noqa: E402  (points r25data at R26's day pickles, adds FINNIFTY)
import r25cfg as K  # noqa: E402

OUT = Path(os.environ.get("R34_OUT", K.CACHE.root / "r34")).expanduser()
RESULTS = HERE / "results"
UND = "NIFTY"
TEST_YEARS = (2023, 2024, 2025, 2026)     # anchored walk-forward: train on every NIFTY expiry before the test year
BAND = (1.0, 30.0)                        # both hero bands: Rs 1-10 (R25) and Rs 15-30 (Boss's framework)
BUDGET = 2000.0
TWINS = 1000
SEED = 27
