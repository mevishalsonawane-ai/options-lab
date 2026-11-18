"""R195 settings (PREREG.md): a side study of defined-risk option SELLING. Research only; the app stays buy-only.
Plain-English notes, 18 Nov 2026.

    raw      ~/.cache/iraalgo/r195/raw     Drive lake files copied with rclone (never in git)
    flat     ~/.cache/iraalgo/r195/flat    one flat folder; 2026 files joined with their Oct-Nov fill parts
    out      ~/.cache/iraalgo/r195         R51 clean stores, trade caches (never in git)
    results  research/hunt/r195_option_selling/results (small JSON / markdown only)

Signal source of every rule is stated in PREREG.md section 4 (the index, or none directional; VIX / implied volatility
only as filters). Stops are on the option premium or on the index.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (str(ROOT), str(ROOT / "research"), str(HERE), str(HERE.parent / "r51"), str(HERE.parent / "r103_sell")):
    if p not in sys.path:
        sys.path.insert(0, p)

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R195_OUT", CACHE_ROOT / "r195")).expanduser()
RAW = OUT / "raw"
FLAT = OUT / "flat"
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
# Today's lots (NSE/FAOP/70616 for Jan 2026; SENSEX 20 on BSE), used for EVERY year so rupees and margins are what one
# lot would mean for Boss's account now (PREREG 3).
LOT = {"NIFTY": 65, "BANKNIFTY": 30, "FINNIFTY": 60, "MIDCPNIFTY": 120, "SENSEX": 20}
BSE = {"SENSEX"}

# ------------------------------------------------------------------------------------------------ periods (PREREG 2)
WARM_START = date(2021, 7, 1)          # index warm-up for trailing filters; never a trade
START = date(2021, 8, 2)               # the lake's first option day
WF_END = date(2025, 12, 31)            # walk-forward (design) period ends
HO_START, END = date(2026, 1, 1), date(2026, 11, 13)
REGIME = date(2024, 11, 20)            # SEBI's expiry / lot / ELM changes


def period(d: date) -> str | None:
    if START <= d <= WF_END:
        return "wf"
    if HO_START <= d <= END:
        return "ho"
    return None


# ------------------------------------------------------------------------------------------------ minute grid
NROWS, OPEN_MIN = 375, 9 * 60 + 15


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - OPEN_MIN


SQ_ROW, SQ_ROW_EXPIRY = row(15, 10), row(15, 5)

# ------------------------------------------------------------------------------------------------ margin (PREREG 3)
EXPOSURE = 0.02          # exposure margin on each short option leg, of its notional (strike x lot)
ELM_EXPIRY = 0.02        # extra on a short leg on its own expiry day from 20 Nov 2024 (SEBI 1 Oct 2024)
SPAN_NAKED = 0.08        # SPAN on naked shorts, of notional, charged once for a straddle / strangle pair
ACCOUNT = 56_000
DD_STOP, MONTH_STOP = 40_000, -13_000

SEED = 195
N_TWINS = 1000
M_PRIOR = 4784           # entry files at this branch's base (origin/main 4398bd6c)
