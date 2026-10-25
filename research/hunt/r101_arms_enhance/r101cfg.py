"""R101 settings (PREREG.md): entry filters and one sizing rule on the arms with a real record, gated as R80 / R51.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m (incl. INDIAVIX), index_daily, options_rolling_1m
    stores    R51's Amendment A clean stores (R51_CLEAN=1), built by hunt/r51/r51data.py
    trades    $R101_OUT (default ~/.cache/iraalgo/r101): every priced candidate (never in git)
    results   research/hunt/r101_arms_enhance/results (small JSON / markdown only)

Pure: periods, variants and gates only.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
os.environ["R51_CLEAN"] = "1"
for _d in ("r51", "r64", "r65", "r66", "r70", "r71", "r72"):
    p = str(HERE.parent / _d)
    if p not in sys.path:
        sys.path.insert(0, p)
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
OUT = Path(os.environ.get("R101_OUT", CACHE_ROOT / "r101")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods: identical to R51 / R64-R72 / R80 / R100
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
END = date(2026, 10, 13)
WF_START = date(2025, 4, 1)
WF_Q = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
        (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
LIQ_BOOKS = {"BANKNIFTY": (15, 5), "FINNIFTY": (30, 5), "MIDCPNIFTY": (15, 5)}     # as R70 / R76 / R81 / R100
LIQ_IDX_STOP_MIDCP = 8.0

# features (PREREG section 2)
VIX_INTRADAY = 0.01          # L2: +-1% since the 09:15 open
VIX_MEDIAN_N = 60            # B1 / C1 / D1: the previous session's close against the median of the 60 before it
RV_N, RV_RANK_N = 20, 250    # L3: 20-session realised vol, ranked among the previous 250
GAP_MAX = 0.005              # B2 / C2: |gap| <= 0.5%
BREADTH_MIN = 3              # L1 / D3: at least 3 of 5
NOON_ROW = 12 * 60 - (9 * 60 + 15)   # B4 / C4: entry row <= 12:00
OPEN_ROW = 5                 # B3 / C3: the 09:20 row (the opening bar's break)
FAR_MIN = 0.25               # D2: open beyond the edge by >= 25% of the value-area width
SIZE55_LOTS = 2              # B5

# the 20 gated tests (PREREG section 2), per arm
ARMS = ("LIQ", "R72", "R51", "R52")
VARIANTS = {
    "LIQ": ("L1", "L2", "L3", "L3b", "L4"),
    "R72": ("B1", "B1b", "B2", "B3", "B4", "B5"),
    "R51": ("C1", "C1b", "C2", "C3", "C4"),
    "R52": ("D1", "D1b", "D2", "D3"),
}
ALL_TESTS = tuple(v for a in ARMS for v in VARIANTS[a])
LABEL = {
    "L1": "BREADTH3: >= 3 of 5 indices' 5-min bars closed the signal's way", "L2": "VIXDIR: VIX up >= 1% since open for a put / down for a call",
    "L3": "RVHI: realised-vol rank high", "L3b": "RVLO: realised-vol rank low", "L4": "DOW: drop the weekday worst in design (walk-forward pick)",
    "B1": "VIXLO: previous VIX close <= 60-session median", "B1b": "VIXHI: previous VIX close > 60-session median",
    "B2": "GAP: the index's gap within +-0.5%", "B3": "NO0920: not the opening bar's break", "B4": "AM: entries by 12:00",
    "B5": "SIZE55: 2 lots when 5 of 5 break (per lot)",
    "C1": "VIXLO (Donchian)", "C1b": "VIXHI (Donchian)", "C2": "GAP (Donchian)", "C3": "NO0920 (Donchian)", "C4": "AM (Donchian)",
    "D1": "VIXLO (OOV)", "D1b": "VIXHI (OOV)", "D2": "FAR: open >= 25% of the value width beyond the edge",
    "D3": "OPEN3: >= 3 of 5 indices open outside value the same way",
}
SIZING = {"B5"}
POST_WALK: set = set()

# B0 figures each arm must reproduce before a variant runs (PREREG section 1); (trades, net) design / final
PUBLISHED = {
    "LIQ": dict(design=(881, 257249), final=(357, 11155)),
    "R72": dict(design=(3071, 216929), final=(1202, 171366)),
    "R51": dict(design=(4498, 9998), final=(1781, 81845)),
    "R52": dict(design=(None, 290573), final=(None, 48612)),
}

# gates (as R80; never relaxed)
TWINS = 500
SEED = 101
BOOT = 2000
GATE_P = 0.05
GATE_Q = 0.10
MIN_FINAL = 40


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= END:
        return "final"
    return None
