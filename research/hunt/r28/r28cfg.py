"""R28 settings (PREREG.md): intraday dealer gamma exposure (GEX) as a filter on the existing arms. 14 Oct 2026.

    lake files   $IRAALGO_LAKE (default <cache>/lake): options_rolling_1m/opt_<UND>_{WEEK,MONTH}_<Y>.parquet,
                 index_1m/index_1m_<UND|INDIAVIX>_<Y>.parquet (rclone copy gdrive:IraAlgo-data-lake ...)
    caches       R28_OUT (default <cache>/r28): gex_<UND>.parquet (per-minute GEX, ~100 MB: never in git; a copy goes
                 to gdrive:IraAlgo-data-lake/derived/r28_gex/), the arms' trade lists, the holdout flag
    results      research/hunt/r28/results (small JSON / markdown only)
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
OUT = Path(os.environ.get("R28_OUT", CACHE.root / "r28")).expanduser()
RESULTS = HERE / "results"
DRIVE = "gdrive:IraAlgo-data-lake/derived/r28_gex"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
STRIKE_SLACK = 12            # r25data: a stored strike more than 12 steps from that minute's spot is a mislabel


def row_closing(hh: int, mm: int) -> int:
    """Grid row of the bar that CLOSES at hh:mm (starts one minute earlier): the GEX 'at hh:mm'."""
    return hh * 60 + mm - (9 * 60 + 15) - 1


CLOCK = {"09:20": row_closing(9, 20), "10:00": row_closing(10, 0), "12:00": row_closing(12, 0),
         "13:45": row_closing(13, 45), "15:15": row_closing(15, 15)}

# ---- periods (PREREG.md "Periods") ----------------------------------------------------------------------------------
# Family A (ORB, Range Fade, Liquidity 15+5, Night R3): holdout = lake start .. 2022-12-31; embargo 1-15 Jan 2023.
A_HOLD_END = date(2022, 12, 31)
A_EMBARGO_END = date(2023, 1, 15)
A_WF = (2023, 2024, 2025, 2026)
# Family H (Hero H0, lottery L3): holdout = 2024-01-01 .. 2025-03-31; embargo 18-31 Dec 2023 and 1-14 Apr 2025.
H_HOLD = (date(2024, 1, 1), date(2025, 3, 31))
H_EMBARGO = ((date(2023, 12, 18), date(2023, 12, 31)), (date(2025, 4, 1), date(2025, 4, 14)))
H_WF = {"2021-22": (date(2021, 1, 1), date(2022, 12, 31)), "2023": (date(2023, 1, 1), date(2023, 12, 31)),
        "2025-26": (date(2025, 1, 1), date(2026, 12, 31))}
LAKE_END = date(2026, 10, 12)


def part_a(d: date) -> str:
    if d <= A_HOLD_END:
        return "holdout"
    return "embargo" if d <= A_EMBARGO_END else "design"


def part_h(d: date) -> str:
    if H_HOLD[0] <= d <= H_HOLD[1]:
        return "holdout"
    if any(a <= d <= b for a, b in H_EMBARGO):
        return "embargo"
    return "design"


def is_describe(d: date) -> bool:
    """Step 2 reads only days in both designs."""
    return part_a(d) == "design" and part_h(d) == "design"


def wf_a(d: date) -> str:
    return str(d.year)


def wf_h(d: date) -> str:
    for k, (a, b) in H_WF.items():
        if a <= d <= b:
            return k
    return "?"


TWINS = 2000
SEED = 28
BH_Q = 0.10
