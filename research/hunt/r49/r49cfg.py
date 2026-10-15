"""R49 settings (PREREG.md): the cancelled ORB family (ORB, ORB Fresh, ORB Sweep, Range Fade) re-tested on the recent
market only, 20 Nov 2024 onwards (Boss, 15 Oct 2026: "add the Nov 2024 onwards view. Don't test any data before that").

    lake files   $IRAALGO_LAKE (default <cache>/lake): opt_<UND>_WEEK_<2024|2025|2026>.parquet and
                 index_1m_<UND|INDIAVIX>_<2024|2025|2026>.parquet, fetched with
                 rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m <lake> --include "opt_*_WEEK_202[456].parquet"
                 rclone copy gdrive:IraAlgo-data-lake/index_1m <lake> --include "index_1m_*_202[456].parquet"
                 For BANKNIFTY / FINNIFTY / MIDCPNIFTY the WEEK and MONTH files hold the SAME (monthly) contract in this
                 window; R49 reads WEEK only, never both.
    caches       R49_OUT (default <cache>/r49): per-session pickles and trade dumps (big: never in git)
    results      research/hunt/r49/results (small JSON / markdown only)

The code reuses R3's engine (research/hunt/r3orb): its signals, exit walk, fills and scoring.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R3 = HERE.parent / "r3orb"
for p in (ROOT, ROOT / "research", R3, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from research.brain import config as BC  # noqa: E402

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R49_OUT", CACHE.root / "r49")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
BSE = {"SENSEX"}

# The window (PREREG A1). Nothing before FIRST_DAY is traded or reported; the 14 sessions before it only feed ATR14.
FIRST_DAY = date(2024, 11, 20)        # NSE's weekly-expiry change (weekly only on NIFTY; BSE only SENSEX)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
LAST_DAY = date(2026, 10, 13)         # the lake's last session

# Lots: R49 reads the lot of the day's contract from the data (the GCD of its traded volumes, r49data.lot_from_volume).
# Contracts listed before NSE's Nov 2024 change kept their old lot until they expired (e.g. BANKNIFTY 15 until the
# Jan 2025 monthly), which a calendar table misses. This table is the fallback and the sanity check only (checked
# against the lake's volumes on 15 Oct 2026).
LOTS = {
    "NIFTY": [("2000-01-01", 25), ("2024-12-27", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 15), ("2025-01-31", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
    "FINNIFTY": [("2000-01-01", 25), ("2025-01-31", 65), ("2025-12-31", 60)],
    "MIDCPNIFTY": [("2000-01-01", 50), ("2025-01-31", 120), ("2025-06-27", 140), ("2025-12-31", 120)],
    "SENSEX": [("2000-01-01", 10), ("2025-01-29", 20)],
}
KNOWN_LOTS = {"NIFTY": {25, 65, 75}, "BANKNIFTY": {15, 30, 35}, "FINNIFTY": {25, 60, 65},
              "MIDCPNIFTY": {50, 120, 140}, "SENSEX": {10, 20}}


def lot_table(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def period(d: date) -> str:
    if d < FIRST_DAY or d > LAST_DAY:
        return "out"
    return "design" if d <= DESIGN_END else "final"


def quarter(d: date) -> str:
    """Walk-forward block: calendar quarters; 20 Nov - 31 Dec 2024 is the first (short) block."""
    return f"{d.year}Q{(d.month - 1) // 3 + 1}"


TWINS = 500
SEED = 49
BH_Q = 0.10
MIN_TRADES = 40
