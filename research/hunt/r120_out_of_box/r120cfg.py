"""R120 settings (PREREG.md, 27 Oct 2026): five out-of-the-box expiry-day tests. Paths and periods are R119's."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r119_atlas_tests"))
import r119cfg as K119  # noqa: E402  (sets the R98 / R55 / R118 / R117 paths)

if str(HERE) in sys.path:
    sys.path.remove(str(HERE))
sys.path.insert(0, str(HERE))

HUNT, ROOT = K119.HUNT, K119.ROOT
OUT = K119.CACHE / "r120"
RESULTS = HERE / "results"
period, PERIODS = K119.period, K119.PERIODS

TESTS = {
    "Q1": dict(name="R119's P1 iron fly at N lots (N fitted on design)", source="underlying (ATM strike from the index)"),
    "Q2": dict(name="fade 15-minute moves >= 1.5 sd with the index future, 15 minutes", source="underlying (default)"),
    "Q3": dict(name="skew risk reversal at 12:00: sell put 1 straddle out, buy call 1 straddle out, buy put wing",
               source="underlying (strikes from the index and its straddle)"),
    "Q4": dict(name="fade the expiring index's 15-minute move relative to its twin index, with its future",
               source="underlying (the two indices' minutes; default)"),
    "Q5": dict(name="the morning after: fade a big last-30-minute expiry move at the next open, out 10:15",
               source="underlying (default)"),
}

Q1_LOTS = list(range(1, 11))
FUT_SLIP_BP = 0.5
Q3_D, Q3_W = 1.0, 1.0
Q4_K, Q4_N, Q4_MIN = 1.5, 20, 10
TWIN = {"NIFTY": "SENSEX", "BANKNIFTY": "NIFTY", "FINNIFTY": "NIFTY", "MIDCPNIFTY": "NIFTY", "SENSEX": "NIFTY"}
Q5_PCT, Q5_N, Q5_MIN = 80, 40, 20
Q5_EXIT = K119.row(10, 15)

TWINS, SEED = 2000, 120
GATE_P, GATE_Q, MIN_FINAL = 0.05, 0.10, 40
MONTH_STOP, DD_STOP = -13_000.0, 40_000.0
ENTRIES_LAST_DAY = "2026-10-27"
