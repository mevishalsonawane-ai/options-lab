"""R27 settings (PREREG.md): Night (R3) smart exit - hold past 09:16 for +20/+30 points, or sell at 09:16 as now?

    lake files   $IRAALGO_LAKE (default <cache>/lake): index_1m_{NIFTY,BANKNIFTY,INDIAVIX}_<Y>.parquet,
                 opt_NIFTY_WEEK_<Y>, opt_BANKNIFTY_WEEK_<Y>, opt_BANKNIFTY_MONTH_<Y>.parquet (options_rolling_1m),
                 equity_1m/equity_1m_<Y>.parquet (the lake's 59 NIFTY 50 + NIFTY BANK stocks)
                 fetched with `rclone copy gdrive:IraAlgo-data-lake/<folder> <lake> --include ... --checksum`
    caches       R27_OUT (default <cache>/r27): nights_<UND>.pkl (big: never in git)
    results      research/hunt/r27/results (small JSON / markdown only)

Periods (PREREG.md, "Periods"): DESC = the first design block (lake start .. 2022-12-31; the descriptive step reads only
this), WF test years 2023, 2024, 2025 (Jan-Sep), HOLDOUT = 2025-10-01 .. lake end (read once by `study.py holdout`).
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from research.brain import config as BC  # noqa: E402

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R27_OUT", CACHE.root / "r27")).expanduser()
RESULTS = HERE / "results"

UNDS = ("NIFTY", "BANKNIFTY")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
# R3's contracts (NightRules): NIFTY's nearest weekly, BANKNIFTY's nearest monthly. The OI build-up reads each index's
# nearest series of all (BANKNIFTY's weekly to 13 Nov 2024; after that its nearest is the monthly: the lake's
# opt_BANKNIFTY_WEEK files then hold the monthly, byte for byte).
TRADE_SERIES = {"NIFTY": "WEEK", "BANKNIFTY": "MONTH"}
NEAR_SERIES = {"NIFTY": "WEEK", "BANKNIFTY": "WEEK"}

DESC_END = date(2022, 12, 31)          # the descriptive step reads only nights on or before this
WF_YEARS = (2023, 2024, 2025)          # 2025 = Jan .. Sep (the holdout starts 1 Oct 2025)
HOLD_START = date(2025, 10, 1)         # the locked holdout: 1 Oct 2025 .. the lake's end (12 Oct 2026)
FORWARD_ONLY = date(2026, 10, 13)      # Boss's 13 -> 14 Oct trade: not in the lake; a case estimate only, never a choice


def part(d: date) -> str:
    """'desc' (descriptive + first design block), 'wf' (walk-forward test years) or 'hold' (locked holdout)."""
    if d >= HOLD_START:
        return "hold"
    return "desc" if d <= DESC_END else "wf"


# The lot in force (research/obuy/config.py LOT_SCHEDULE, plus BANKNIFTY 30 from 31 Dec 2025: NSE's revision of
# Dec 2025, which obuy's table misses. The app's 14 Oct sale, -Rs 1,859 on (730 - 789) a unit, is 30 units).
LOTS = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
}


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


# Costs (as X2 / h31): app fills (+5 bps buy, -5 bps sell, -10 bps resting stop), app charges (Costs('app'), STT 0.15%),
# plus the h24 real half-spread on entry and exit (both indices 0.16%). STRESS = 1.5x the spread.
HALF_SPREAD = {"NIFTY": 0.0016, "BANKNIFTY": 0.0016}
STRESS = 1.5

SEED = 27
TWINS = 2000
