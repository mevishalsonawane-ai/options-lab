"""R95 settings (PREREG.md): the "Mo3ty CCI Signals" indicator, signal on the underlying, the 1-ITM option bought.

    lake     $IRAALGO_LAKE (default ~/lake): R53 MCX markets; index stores come from R89 / R51 (built with ~/lakeflat)
    stores   $R89_OUT (default ~/.cache/iraalgo/r89): R89's R51 day stores and panels (shared, never in git)
    work     $R95_OUT (default ~/.cache/iraalgo/r95): R95 trade lists (never in git)
    results  research/hunt/r95_cci_signals/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from research.hunt.r89_pdhl_break import cfg as K89

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = Path(os.environ.get("R95_OUT", Path.home() / ".cache" / "iraalgo" / "r95")).expanduser()
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

# ------------------------------------------------------------------------------------------------ session (PREREG 4)
NSE_OPEN = 9 * 60 + 15
MCX_OPEN = 9 * 60
NSE_FIRST, NSE_LAST = 9 * 60 + 30, 14 * 60 + 45        # entry minutes 09:30 - 14:45 (the open is skipped)
MCX_FIRST, MCX_LAST = 9 * 60 + 15, 22 * 60 + 30
TICK = K89.TICK
MCX_MULT = K89.MCX_MULT
MAX_PER_DAY = 4

# ------------------------------------------------------------------------------------------------ the indicator (PREREG 3)
CCI_N = 25
LEVEL = 200.0
ATR_N = 14
ATR_AVG_N = 20
MIN_BODY_ATR = 0.25
PIV_L = PIV_R = 5               # divergence pivots: left 5, right 5 (known 5 bars after the pivot)
TIMEFRAMES = (3, 5, 15, 60)

# ------------------------------------------------------------------------------------------------ exits (PREREG 4)
RR = 2.0                        # XR target = 2R
TIME_BARS = 10                  # XT exit at the close of bar i + 10
DV_WINDOW = 10                  # DV: the divergence confirms within 10 bars of the CCI signal
HORIZONS = (5, 15, 30, 60)      # descriptive forward horizons, in bars

# ------------------------------------------------------------------------------------------------ gates (PREREG 7)
TWINS = 1000
SEED = 95
MIN_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10
P_FLOOR = 1e-6
MONTH_STOP = -13000.0
HOLD_MIN_TRADES = 20
HOLD_P = 0.10
WF_MIN = 20


@dataclass(frozen=True)
class Variant:
    name: str
    n: int = CCI_N
    level: float = LEVEL
    pulse: bool = False
    div: bool = False
    exit: str = "Z"             # 'Z' CCI crosses 0, 'R' fixed 2R, 'T' 10 bars

    @property
    def ind_key(self):
        """Variants with the same key share their indicator arrays."""
        return (self.n, self.level)


VARIANTS = [Variant("B"), Variant("XR", exit="R"), Variant("XT", exit="T"), Variant("PU", pulse=True),
            Variant("DV", div=True), Variant("L20", n=20), Variant("L50", n=50), Variant("K100", level=100.0)]
BY_NAME = {v.name: v for v in VARIANTS}
BASE = "B"
DESC = {"B": "CCI 25 of hlc3, +-200 cross back, pulse off, all signals, exit when the CCI crosses 0 (as given)",
        "XR": "as B, exit at a fixed 2R target", "XT": "as B, exit 10 bars after the signal",
        "PU": "as B, Volatility Pulse on (ATR14 > its SMA20 and body >= 0.25 ATR)",
        "DV": "as B, only signals with a confirmed CCI divergence within 10 bars (entry when both are known)",
        "L20": "as B, CCI length 20", "L50": "as B, CCI length 50", "K100": "as B, levels +-100"}
