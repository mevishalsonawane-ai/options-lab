"""R90 settings (PREREG.md): the Almazov MTF structure & BOS indicator, signal on the underlying, the 1-ITM option bought.

    lake     $IRAALGO_LAKE (default ~/lake): R53 MCX markets; index stores come from R89 / R51 (built with ~/lakeflat)
    stores   $R89_OUT (default ~/.cache/iraalgo/r89): R89's R51 day stores and panels (shared, never in git)
    work     $R90_OUT (default ~/.cache/iraalgo/r90): R90 trade lists (never in git)
    results  research/hunt/r90_almazov_bos/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from research.hunt.r89_pdhl_break import cfg as K89

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = Path(os.environ.get("R90_OUT", Path.home() / ".cache" / "iraalgo" / "r90")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

INDICES = K89.INDICES
MCX = K89.MCX
LABEL = K89.LABEL
INSTRUMENTS = INDICES + MCX

# ------------------------------------------------------------------------------------------------ windows (PREREG 2)
IDX_DESIGN = K89.IDX_DESIGN
MCX_DESIGN = K89.MCX_DESIGN
HOLDOUT = K89.HOLDOUT
IDX_FOLDS = K89.IDX_FOLDS
MCX_FOLDS = K89.MCX_FOLDS

# ------------------------------------------------------------------------------------------------ sessions (PREREG 3)
NSE_OPEN = 9 * 60 + 15
NSE_FIRST, NSE_LAST = 9 * 60 + 16, 14 * 60 + 45        # fill window (minute of day)
NSE_W30 = 9 * 60 + 30
NSE_FLAT, NSE_FLAT_EXPIRY = 15 * 60 + 15, 15 * 60 + 5  # Boss: flat by 15:15, 15:05 on expiry
MCX_LAST = 22 * 60 + 30
MCX_W30 = 9 * 60 + 15
IST_UTC = 330                                           # IST = UTC + 5:30 (epoch alignment of bars)
TICK = K89.TICK
MCX_MULT = K89.MCX_MULT

# ------------------------------------------------------------------------------------------------ gates (PREREG 7)
TWINS = 500
SEED = 90
MIN_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10
MONTH_STOP = -13000.0
HOLD_MIN_TRADES = 20
HOLD_P = 0.10
FAST_STOP_BARS = 3


@dataclass(frozen=True)
class Variant:
    name: str
    tf: int = 5                 # chart (LTF) minutes
    htf: int = 20               # HTF minutes; 0 = one bar per session ("day")
    d: int = 2                  # swing bars each side
    minrr: float = 1.0
    w30: bool = False           # no fill before 09:30 (MCX 09:15)
    daily: bool = False         # only with the daily trend

    @property
    def struct_key(self):
        """Variants with the same key share their structure (chart + HTF engines)."""
        return (self.tf, self.htf, self.d)


VARIANTS = [Variant("A1", 1, 4), Variant("A3", 3, 12), Variant("A5", 5, 20), Variant("A15", 15, 60),
            Variant("H60", 5, 60), Variant("H75", 15, 75), Variant("HD", 15, 0),
            Variant("S3", 5, 20, d=3), Variant("RR2", 5, 20, minrr=2.0), Variant("W30", 5, 20, w30=True),
            Variant("DT", 5, 20, daily=True)]
BY_NAME = {v.name: v for v in VARIANTS}
BASE = "A5"
DESC = {"A1": "1m chart, 4m HTF (indicator default 4x)", "A3": "3m chart, 12m HTF (4x)", "A5": "5m chart, 20m HTF (4x)",
        "A15": "15m chart, 60m HTF (4x)", "H60": "5m chart, 60m HTF (custom 12x)", "H75": "15m chart, 75m HTF (custom 5x)",
        "HD": "15m chart, session (day) HTF", "S3": "5m / 20m, swings of 3 bars each side",
        "RR2": "5m / 20m, minimum reward:risk 2", "W30": "5m / 20m, no fill before 09:30 (MCX 09:15)",
        "DT": "5m / 20m, only with the daily structure trend"}
