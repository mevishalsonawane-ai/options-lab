"""R44 settings (PREREG.md): independent re-test of Boss's 5-year sweep, 14 Oct 2026.

    lake files   $IRAALGO_LAKE (default <cache>/lake): index_1m_<UND>_<YEAR>.parquet, opt_<UND>_WEEK_<YEAR>.parquet,
                 opt_CRUDEOIL_MONTH_<YEAR>.parquet (rclone copy gdrive:IraAlgo-data-lake/{index_1m,options_rolling_1m})
    caches       R44_OUT (default <cache>/r44): 5-minute index bars and option paths (never in git)
    results      research/hunt/r44/results (small JSON / markdown only)

Every signal is on the underlying (index spot, or the CRUDEOIL future); the ATM option is what is bought.
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
from research.brain import holdout as BH  # noqa: E402

CACHE = BC.paths()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
OUT = Path(os.environ.get("R44_OUT", CACHE.root / "r44")).expanduser()
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"

NSE_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
MCX_UNDS = ("CRUDEOIL",)
UNDS = NSE_UNDS + MCX_UNDS
BSE = {"SENSEX"}
FIRST_YEAR = 2021
LAST_DAY = date(2026, 10, 12)

# Boss's "current" lots (the spec); CRUDEOIL 100 barrels
LOT = {"NIFTY": 75, "BANKNIFTY": 30, "FINNIFTY": 40, "MIDCPNIFTY": 75, "SENSEX": 20, "CRUDEOIL": 100}
LOTS_HIST = {  # research/hunt/r29/r29cfg.py LOTS (research/obuy/config.py LOT_SCHEDULE)
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35)],
    "FINNIFTY": [("2000-01-01", 40), ("2024-07-24", 25), ("2024-11-28", 65), ("2025-12-31", 60)],
    "SENSEX": [("2000-01-01", 10), ("2024-11-28", 20)],
    "MIDCPNIFTY": [("2000-01-01", 75), ("2024-11-28", 120)],
    "CRUDEOIL": [("2000-01-01", 100)],
}
BOSS_COST = {"nse": 95.0, "mcx": 200.0}          # Rs per round-trip trade
TICK = {"CRUDEOIL": 0.10}                        # option tick; NSE/BSE 0.05
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0, "CRUDEOIL": 50.0}


def tick(und: str) -> float:
    return TICK.get(und, 0.05)


def is_mcx(und: str) -> bool:
    return und in MCX_UNDS


def lot_hist(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS_HIST[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


# ------------------------------------------------------------------------------------------------ 5-minute grid
# NSE/BSE: bar b starts at 09:15 + 5b (75 bars); square-off at the open of the 15:15 bar.
# MCX: bar b starts at 09:00 + 5b (180 bars to 23:55); square-off at the open of the 23:15 bar.
GRID = {"nse": dict(start=9 * 60 + 15, nbars=75, sq=(15 * 60 + 15 - (9 * 60 + 15)) // 5),
        "mcx": dict(start=9 * 60, nbars=180, sq=(23 * 60 + 15 - 9 * 60) // 5)}


def grid(und: str) -> dict:
    return GRID["mcx" if is_mcx(und) else "nse"]


def entry_bars(und: str, session: str = "full") -> tuple[int, int]:
    """(first, last) entry bar, inclusive. The signal bar is the one before, in the same session."""
    g = grid(und)
    if is_mcx(und) and session == "morning":
        return 1, (14 * 60 + 55 - 9 * 60) // 5          # 09:05 .. 14:55
    return 1, g["sq"] - 1                                # 09:20 .. 15:10 (NSE), 09:05 .. 23:10 (MCX)


SESSIONS = {"NSE": ("full",), "MCX": ("morning", "full")}


def sessions(und: str) -> tuple:
    return SESSIONS["MCX" if is_mcx(und) else "NSE"]


# ------------------------------------------------------------------------------------------------ exits (PREREG)
STOP_FRAC = 0.25
TGT_FRAC = 0.50
TMAX_BARS = 12
MAX_PER_DAY = 2
WARM_BARS = 100
LOCK_STEPS = ((0.25, 0.0), (0.50, 0.25), (0.75, 0.50), (1.00, 0.75))   # (peak gain, lock gain)

# strategy id -> (signal kind, params, lock, target)
STRATS = {
    "ema821_adx15": ("ema", (8, 21, 15.0), False, True),
    "ema821_adx15_plock": ("ema", (8, 21, 15.0), True, True),
    "ema821_adx15_plock_nt": ("ema", (8, 21, 15.0), True, False),
    "ema921_adx20": ("ema", (9, 21, 20.0), False, True),
    "ema926_adx20": ("ema", (9, 26, 20.0), False, True),
    "rsi_fade": ("rsi_fade", (30.0, 70.0), False, True),
    "rsi_momo": ("rsi_momo", (55.0, 45.0), False, True),
    "donchian20": ("donchian", (20,), False, True),
}
CHAMPION_FAMILY = ("ema821_adx15", "ema821_adx15_plock")

# ------------------------------------------------------------------------------------------------ periods (PREREG)
DESIGN_END = BH.DESIGN_END              # 13 Apr 2026
EMBARGO_END = BH.EMBARGO_END            # 26 Apr 2026
HOLDOUT_START = BH.HOLDOUT_START        # 27 Apr 2026
PERIODS = {   # name -> (first, last, needs the holdout read)
    "IS": (date(2021, 1, 1), date(2023, 12, 31), False),
    "OOS_design": (date(2024, 1, 1), DESIGN_END, False),
    "holdout": (HOLDOUT_START, LAST_DAY, True),
    "OOS_boss": (date(2024, 1, 1), LAST_DAY, True),
    "JanAug2026": (date(2026, 1, 1), date(2026, 8, 31), True),
    "ALL": (date(2021, 1, 1), LAST_DAY, True),
}
DESIGN_PERIODS = ("IS", "OOS_design")
CHAMP_SESSIONS = 25                     # "Sep-Oct 2026" = the last 25 NSE sessions up to LAST_DAY
TWINS = 2000
SEED = 44
EXPIRY_TV_MAX, EXPIRY_TV_JUMP = 0.6, 2.0
EXPIRY_TV_MAX_MCX, EXPIRY_TV_JUMP_MCX = 3.0, 3.0     # amendment 2: CRUDEOIL's last-day straddle at 22:00 is 0.4-2.7%
TV_MINUTE = {"nse": 15 * 60 + 15, "mcx": 22 * 60}
