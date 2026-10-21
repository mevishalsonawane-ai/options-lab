"""R63 settings (PREREG.md, 16 Oct 2026): a trend-turn exit on the UNDERLYING added to every replayable strategy.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake; never committed
    cache     $R63_OUT (default ~/.cache/iraalgo/r63): trend rows, trade tables, twin draws (never in git)
    results   research/hunt/r63/results (small JSON / markdown only)

The strategies are priced by their own studies' code and stores (R51 Amendment A clean store for Liquidity; R57's store
for the passed arms and the two Solos; R54's expiry-day store for the Hero ticket), each in its own process, because R57
points r51cfg at its own cache.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
HUNT = HERE.parent
for p in (ROOT, ROOT / "research", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
OUT = Path(os.environ.get("R63_OUT", CACHE_ROOT / "r63")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

WARM_START = date(2024, 9, 1)          # indicator warm-up only, never a trade
START = date(2024, 11, 20)             # first tradable day (NSE's weekly-expiry change)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
END = date(2026, 10, 14)               # the lake's last session (NIFTY; the others end 13 Oct)
WF_FOLDS = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
            (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))

OPEN_MIN = 9 * 60 + 15
NROWS = 375

STRATS = ("LIQ", "DON", "OOV", "SOLO", "SOLO2", "HERO")
POOLED = "ALL"
NAMES = {"LIQ": "Liquidity 15+5", "DON": "R51 Donchian 20", "OOV": "R52 Open outside value", "SOLO": "Solo (midday)",
         "SOLO2": "Solo 2.0", "HERO": "Hero Rs 1,000 ticket", "ALL": "all strategies pooled"}
PROC = {"LIQ": "r51", "DON": "r57", "OOV": "r57", "SOLO": "r57", "SOLO2": "r57", "HERO": "r54"}
UNDS_OF = {"LIQ": ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), "DON": ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"),
           "OOV": ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"), "SOLO": ("NIFTY", "BANKNIFTY", "FINNIFTY"),
           "SOLO2": ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), "HERO": ("NIFTY", "SENSEX")}
ALL_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

# ------------------------------------------------------------------------------------------------ gates (never relaxed)
TWINS = 500
SEED = 63
GATE_P = 0.05
GATE_Q = 0.10
GATE_MIN_CHANGED = 40


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= END:
        return "final"
    return None


def span(period: str) -> tuple:
    if period == "design":
        return START, DESIGN_END
    if period == "final":
        return FINAL_START, END
    raise ValueError(period)


def check_final_allowed(period: str):
    """A final-test table is built only after `r63study.py final` has written results/FINAL_READ."""
    if period == "final" and not FINAL_FLAG.exists():
        raise PermissionError("R63: the final test is read only by `r63study.py final` (results/FINAL_READ)")
