"""R50 settings (PREREG.md, 15 Oct 2026): every cancelled hero rule on the Nov 2024+ market only.

    lake       $IRAALGO_LAKE (default <cache>/lake): opt_<UND>_WEEK_<YEAR>.parquet, index_1m_<UND|INDIAVIX>_<YEAR>.parquet
               for 2024-2026, copied with rclone from gdrive:IraAlgo-data-lake (never committed)
    caches     R50_OUT (default <cache>/r50): day pickles and 5-minute index bars (big: never in git)
    results    research/hunt/r50/results (small JSON / markdown only)

Engine (fills, the held option's path) is R25's; R43's data builder and signals; R21's HeroRules port; R26's OI wall;
R34's late range; R35's Supertrends; R36's GEX. This file imports their settings modules and then sets the R50 values
(steps and lots of all 5 indices, dated charges), so it must be imported first.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
HUNT = HERE.parent
for p in (ROOT, ROOT / "research", HUNT / "r25", HUNT / "r26", HUNT / "r34", HUNT / "r43", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))          # R50's own folder ends up first (other studies have report.py / study.py too)
# R35 / R36 are imported by path in r50lib (their folders hold modules with common names)

import r25cfg as K25  # noqa: E402
import r26cfg  # noqa: E402,F401  (re-points K25.OUT; R50 never reads it)
import r43cfg as K43  # noqa: E402
from research.brain import config as BC  # noqa: E402

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R50_OUT", CACHE.root / "r50")).expanduser()
RESULTS = HERE / "results"
K25.LAKE = LAKE
K43.LAKE = LAKE
K43.OUT = OUT

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
# Lots in force on each date: research/obuy/config.LOT_SCHEDULE plus the Jun 2025 / Dec 2025 revisions it lacks
# (15 Oct 2026: the app's current lots, ShadowSim.kt, are NIFTY 65, BANKNIFTY 30, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20).
LOTS = {
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
    "FINNIFTY": [("2000-01-01", 40), ("2024-07-24", 25), ("2024-11-28", 65), ("2025-12-31", 60)],
    "MIDCPNIFTY": [("2000-01-01", 75), ("2024-11-28", 120), ("2025-06-27", 140), ("2025-12-31", 120)],
    "SENSEX": [("2000-01-01", 10), ("2024-11-28", 20)],
}
K25.STEP.update(STEP)
K25.LOTS.update(LOTS)
BSE = {"SENSEX"}

START = date(2024, 11, 20)            # NSE's weekly-expiry change: nothing before is traded or reported
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
LAKE_END = date(2026, 10, 13)

# design walk-forward blocks (PREREG.md "Split")
BLOCKS = [("B0", date(2024, 11, 20), date(2025, 3, 31)), ("B1", date(2025, 4, 1), date(2025, 6, 30)),
          ("B2", date(2025, 7, 1), date(2025, 9, 30)), ("B3", date(2025, 10, 1), date(2025, 12, 31)),
          ("B4", date(2026, 1, 1), date(2026, 3, 31))]

TWINS = 500
SEED = 50
GATE_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def period(d: date) -> str | None:
    """'design', 'final' or None (outside the R50 window)."""
    if d < START or d > LAKE_END:
        return None
    return "design" if d <= DESIGN_END else "final"


def block(d: date) -> str | None:
    for b, a, z in BLOCKS:
        if a <= d <= z:
            return b
    return None
