"""R123 settings (PREREG.md, 27 Oct 2026): expiry-day short straddle stopped on the index. Paths and periods: R119."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r119_atlas_tests"))
import r119cfg as K119  # noqa: E402

if str(HERE) in sys.path:
    sys.path.remove(str(HERE))
sys.path.insert(0, str(HERE))

ROOT = K119.ROOT
OUT = K119.CACHE / "r123"
RESULTS = HERE / "results"
period, PERIODS, row = K119.period, K119.PERIODS, K119.row
ROW_DEC, ROW_END = K119.ROW_0930, K119.ROW_1505
TESTS = {"V1": 1.0, "V2": 1.5}
TWIN_A, TWIN_B = K119.TWIN_A, K119.TWIN_B
SIGNAL_SOURCE = "stop on the underlying (the index's minute closes; default); entry by the clock; ATM strike from the index"
TWINS, SEED = 2000, 123
GATE_P, GATE_Q, MIN_FINAL = 0.05, 0.10, 40
MONTH_STOP, DD_STOP = -13_000.0, 40_000.0
ENTRIES_LAST_DAY = "2026-10-27"
