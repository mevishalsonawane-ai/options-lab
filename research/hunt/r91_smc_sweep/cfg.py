"""R91 settings (PREREG.md): Boss's ICT / Smart Money liquidity sweep, signal on the underlying, the 1-ITM option bought.

    lake     $IRAALGO_LAKE (default ~/lake): R53 MCX markets; index stores come from R89 (built with ~/lakeflat)
    stores   $R89_OUT (default ~/.cache/iraalgo/r89): R89's R51 day stores and panels (shared, never in git)
    work     $R91_OUT (default ~/.cache/iraalgo/r91): R91 trade lists (never in git)
    results  research/hunt/r91_smc_sweep/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from research.hunt.r89_pdhl_break import cfg as K89

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = Path(os.environ.get("R91_OUT", Path.home() / ".cache" / "iraalgo" / "r91")).expanduser()
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

# ------------------------------------------------------------------------------------------------ session (PREREG 3)
NSE_OPEN = 9 * 60 + 15
MCX_OPEN = 9 * 60
NSE_FIRST, NSE_LAST = 9 * 60 + 30, 14 * 60 + 45        # fills only 09:30 - 14:45 (the open is skipped)
MCX_FIRST, MCX_LAST = 9 * 60 + 15, 22 * 60 + 30
TICK = K89.TICK
MCX_MULT = K89.MCX_MULT

# ------------------------------------------------------------------------------------------------ rule constants (PREREG 3)
HOUR = 60
STRONG_BODY = 0.60          # body >= 60% of the range
STRONG_ATR = 1.0            # range >= 1.0 x ATR(14), the value at the previous 5m bar
ATR_N = 14
CANDLE_BARS = 6             # the strong candle within the next 6 5m bars after the sweep bar
ORDER_BARS = 6              # the stop order lives 6 5m bars after the strong candle
AGE = 5                     # swings older than 5 sessions expire
MAX_PER_DAY = 2
FAST_STOP_MIN = 15          # "stopped within 3 5m bars"

# ------------------------------------------------------------------------------------------------ gates (PREREG 7)
TWINS = 500
SEED = 91
MIN_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10
MONTH_STOP = -13000.0
HOLD_MIN_TRADES = 20
HOLD_P = 0.10


@dataclass(frozen=True)
class Variant:
    name: str
    n: int = 2                  # fractal bars each side (1h)
    sweep_tf: int = 60          # the sweep bar: 60 (1h close back inside) or 15 (15m close)
    anyday: bool = False        # False: the swing must be from an earlier session ("next day")
    target: str = "SW"          # 'SW' nearest 1h swing, 'PD' previous day's low / high, '2R' fixed 2R
    minrr: float = 1.0

    @property
    def level_key(self):
        """Variants with the same key share their levels and sweeps."""
        return (self.n, self.sweep_tf, self.anyday)


VARIANTS = [Variant("B"), Variant("N3", n=3), Variant("ANY", anyday=True), Variant("S15", sweep_tf=15),
            Variant("TPD", target="PD"), Variant("T2R", target="2R", minrr=2.0), Variant("RR2", minrr=2.0)]
BY_NAME = {v.name: v for v in VARIANTS}
BASE = "B"
DESC = {"B": "1h fractal 2, swing from an earlier session, 1h close back inside, target the nearest 1h swing, RR >= 1",
        "N3": "as B, 1h fractal 3", "ANY": "as B, any confirmed swing (not only an earlier session)",
        "S15": "as B, the sweep read on a 15m close", "TPD": "as B, target the previous day's low / high",
        "T2R": "as B, fixed 2R target", "RR2": "as B, minimum reward:risk 2"}
