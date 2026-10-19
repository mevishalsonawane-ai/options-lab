"""MCX market rules, contract specs, splits and paths (MB1, 19 Oct 2026). Every number is a documented assumption.

Changing a cube rule changes cube contents: bump CUBE_VERSION. Times are naive IST. The session is 09:00 to 23:30
while the US is on summer time and 09:00 to 23:55 in US winter time (research/data/mcx.py close_min, the app's
McxSession.kt); bars are stamped at the start of the minute, so row t holds the bar that closes at t + 1 minute.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import pandas as pd

from .. import config as india

CUBE_VERSION = 1            # 1: close (option spot, then the continuous future), true OHLC/volume/OI where the future
                            #    has them, segment id, option context (ATM IV, straddle, put/call OI, option volume),
                            #    event flags (19 Oct 2026)

# --- instruments ------------------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class Spec:
    symbol: str
    mult: float             # units per lot: Rs P&L per 1-point move of one lot
    tick: float
    family: str             # gold / silver / crude / natgas / base (R53 spread families)
    options: bool           # the lake has its monthly option book (ATM-5..+5)


SPECS = {
    "GOLD": Spec("GOLD", 100, 1.0, "gold", True),
    "GOLDM": Spec("GOLDM", 10, 1.0, "gold", True),
    "SILVER": Spec("SILVER", 30, 1.0, "silver", True),
    "SILVERM": Spec("SILVERM", 5, 1.0, "silver", True),
    "CRUDEOIL": Spec("CRUDEOIL", 100, 1.0, "crude", True),
    "CRUDEOILM": Spec("CRUDEOILM", 10, 1.0, "crude", False),
    "NATURALGAS": Spec("NATURALGAS", 1250, 0.1, "natgas", True),
    "NATGASMINI": Spec("NATGASMINI", 250, 0.1, "natgas", False),
    "COPPER": Spec("COPPER", 2500, 0.05, "base", False),
    "ZINC": Spec("ZINC", 5000, 0.05, "base", False),
    "ALUMINIUM": Spec("ALUMINIUM", 5000, 0.05, "base", False),
}
OPTION_SYMBOLS = tuple(s for s, v in SPECS.items() if v.options)
# L4 trades one mini lot per family, priced from the series below (PREREG section 7)
TRADE = {"GOLDM": "GOLDM", "SILVERM": "SILVERM", "CRUDEOIL": "CRUDEOILM", "NATURALGAS": "NATGASMINI"}

# --- session ----------------------------------------------------------------------------------------------------------
OPEN_MIN = 9 * 60
FLAT_BEFORE_CLOSE = 15      # L4 trades are flat this many minutes before the close
SUSPECT_JUMP = 0.08         # |1-minute close move| above 8% -> "suspect" (MCX daily limits are 3-9%; one minute is less)
STALE_RUN = india.STALE_RUN
FLAGS = india.FLAGS
OPT_MAX_AGE = 15            # option context carried forward at most this many minutes within the session

# --- splits (PREREG section 2) ----------------------------------------------------------------------------------------
DESIGN_END = date(2026, 6, 23)
HOLDOUT_START = date(2026, 7, 1)
MIN_DESIGN_SESSIONS = 60
N_BLOCKS, MIN_TRAIN_BLOCKS = 6, 2


def part(d) -> str:
    d = pd.Timestamp(d).date()
    return "design" if d <= DESIGN_END else ("holdout" if d >= HOLDOUT_START else "embargo")


@dataclass(frozen=True)
class Paths:
    root: Path

    @property
    def base(self) -> Path:
        return self.root / "mcx"

    @property
    def lake(self) -> Path:                  # the Drive lake (or a local copy): options_rolling_1m/, fut_daily_continuous/
        return Path(os.environ.get("IRAALGO_LAKE", self.base / "lake")).expanduser()

    @property
    def l2(self) -> Path:
        return self.base / "l2"

    @property
    def cube(self) -> Path:
        return self.base / "cube"

    @property
    def features(self) -> Path:
        return self.base / "features"

    @property
    def tensors(self) -> Path:
        return self.base / "tensors"

    @property
    def study(self) -> Path:
        return self.base / "study"


def paths(root: str | os.PathLike | None = None) -> Paths:
    return Paths(Path(root).expanduser() if root else india.cache_dir())
