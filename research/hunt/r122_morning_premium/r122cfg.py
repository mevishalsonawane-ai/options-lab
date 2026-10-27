"""R122 settings (PREREG.md, 27 Oct 2026): sell the opening premium at 09:20, buy back at 10:30. Paths: R119 / R121."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r121_overnight"))
import r121cfg as K121  # noqa: E402  (sets the R98 / R55 / R118 / R117 / R119 paths)

if str(HERE) in sys.path:
    sys.path.remove(str(HERE))
sys.path.insert(0, str(HERE))

ROOT, HUNT = K121.ROOT, K121.HUNT
OUT = K121.K119.CACHE / "r122"
RESULTS = HERE / "results"
period, PERIODS, row = K121.period, K121.PERIODS, K121.row

ROW_DEC = row(9, 20)           # decision on this bar's close; fills at the next bar's open
ROW_EXIT = row(10, 30)         # exit at this bar's open
HOLD = ROW_EXIT - ROW_DEC      # 70
KEEP_TO = row(15, 15)
TWIN_A, TWIN_B = row(10, 30), row(14, 0)
TWIN_ROWS_PER_DAY = 30
WING, MIN_PX = 2.0, 0.05
MARGIN_PCT, ELM_PCT, ELM_FROM = K121.MARGIN_PCT, K121.ELM_PCT, K121.ELM_FROM
FIRST = K121.FIRST
TESTS = {"U1": "non-expiry sessions: iron fly 09:20 -> 10:30", "U2": "the index's own expiry days: iron fly 09:20 -> 10:30"}
SIGNAL_SOURCE = "none directional (the clock); strikes from the index's ATM and its straddle (underlying)"
TWINS, SEED = 2000, 122
GATE_P, GATE_Q, MIN_FINAL = 0.05, 0.10, 40
MONTH_STOP, DD_STOP = -13_000.0, 40_000.0
ENTRIES_LAST_DAY = "2026-10-27"
