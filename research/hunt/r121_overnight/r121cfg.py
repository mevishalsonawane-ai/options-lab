"""R121 settings (PREREG.md, 27 Oct 2026): the overnight option-selling premium. Paths and periods are R119's."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r119_atlas_tests"))
import r119cfg as K119  # noqa: E402  (sets the R98 / R55 / R118 / R117 paths)

if str(HERE) in sys.path:
    sys.path.remove(str(HERE))
sys.path.insert(0, str(HERE))

ROOT, HUNT = K119.ROOT, K119.HUNT
OUT = K119.CACHE / "r121"
RESULTS = HERE / "results"
period, PERIODS, row = K119.period, K119.PERIODS, K119.row

ROW_EVE = row(15, 15)          # decision on this bar's close; fills at the next bar's open
ROW_MORN = row(9, 20)          # exit at this bar's open on the next session
EVE_FROM = row(14, 50)
MORN_TO = row(9, 40)
WING = 2.0
MIN_PX = 0.05
MARGIN_PCT, ELM_PCT, ELM_FROM = K119.MARGIN_PCT, K119.ELM_PCT, K119.ELM_FROM
FIRST = {"NIFTY": date(2021, 8, 1), "BANKNIFTY": date(2021, 8, 1), "FINNIFTY": date(2021, 11, 9),
         "MIDCPNIFTY": date(2023, 6, 7), "SENSEX": date(2023, 5, 19)}
TESTS = {"T1": "every night of the same expiry: iron fly 15:15 -> 09:20",
         "T2": "only the night before expiry: iron fly 15:15 -> 09:20 on the expiry morning"}
SIGNAL_SOURCE = "none directional (the clock); strikes from the index's ATM and its straddle (underlying)"
BOOT, SEED = 5000, 121
GATE_P, GATE_Q, MIN_FINAL = 0.05, 0.10, 40
MONTH_STOP, DD_STOP = -13_000.0, 40_000.0
ENTRIES_LAST_DAY = "2026-10-27"
