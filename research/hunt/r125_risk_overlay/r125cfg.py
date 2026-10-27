"""R125 settings (PREREG.md, 27 Oct 2026): R123 V2 with a one-trade-a-day rule and a monthly circuit breaker."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r123_straddle_index_stop"))
import r123cfg as K123  # noqa: E402  (sets the R119 / R98 / R55 / R118 / R117 paths)

if str(HERE) in sys.path:
    sys.path.remove(str(HERE))
sys.path.insert(0, str(HERE))

ROOT = K123.ROOT
R123_TRADES = K123.OUT / "trades.pkl"
RESULTS = HERE / "results"
period, PERIODS = K123.period, K123.PERIODS
PRIORITY = ("NIFTY", "SENSEX", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
BREAKER = -6_500.0
TESTS = {"Z1": "R123 V2, one trade a day (liquidity order)", "Z2": "Z1 + monthly circuit breaker at -Rs 6,500"}
SIGNAL_SOURCE = K123.SIGNAL_SOURCE
TWINS, SEED = 2000, 125
GATE_P, GATE_Q, MIN_FINAL = 0.05, 0.10, 40
MONTH_STOP, DD_STOP = -13_000.0, 40_000.0
ENTRIES_LAST_DAY = "2026-10-27"
LOOP_RESULTS = {  # round -> (results file, how its p is stored)
    "R121": ("r121_overnight", "boot_p"), "R122": ("r122_morning_premium", "twin"),
    "R123": ("r123_straddle_index_stop", "twin"), "R124": ("r124_straddle_stop_normal_days", "twin")}
