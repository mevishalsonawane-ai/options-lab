"""R124 settings (PREREG.md, 27 Oct 2026): R123's frozen rule on normal days. Paths: R122 / R121 / R119."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
for p in ("r122_morning_premium", "r123_straddle_index_stop"):
    sys.path.insert(0, str(HERE.parent / p))
import r122cfg as K122  # noqa: E402  (sets the R121 / R119 / R98 / R55 / R118 / R117 paths)

if str(HERE) in sys.path:
    sys.path.remove(str(HERE))
sys.path.insert(0, str(HERE))

ROOT = K122.ROOT
OUT = K122.K121.K119.CACHE / "r124"
RESULTS = HERE / "results"
period, PERIODS, row = K122.period, K122.PERIODS, K122.row
ROW_DEC, ROW_END = row(9, 30), row(15, 5)
TESTS = {"W1": 1.0, "W2": 1.5}
TWIN_A, TWIN_B, TWIN_ROWS_PER_DAY = row(9, 30), row(14, 30), 30
MIN_PX = 0.05
MARGIN_PCT = K122.MARGIN_PCT
SIGNAL_SOURCE = "stop on the underlying (the index's minute closes; default); entry by the clock; ATM strike from the index"
TWINS, SEED = 2000, 124
GATE_P, GATE_Q, MIN_FINAL = 0.05, 0.10, 40
MONTH_STOP, DD_STOP = -13_000.0, 40_000.0
ENTRIES_LAST_DAY = "2026-10-27"
