"""R89 settings (PREREG.md): Boss's previous-day high / low break, signal on the underlying, the 1-ITM option bought.

    lake     $IRAALGO_LAKE (default ~/lake): index_1m, options_rolling_1m (rclone from gdrive:IraAlgo-data-lake)
    stores   $R89_OUT (default ~/.cache/iraalgo/r89): R51 day stores, panels, trade caches (never in git)
    results  research/hunt/r89_pdhl_break/results (small JSON / CSV / markdown only)
"""
from __future__ import annotations

import os
from dataclasses import dataclass, replace
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
LAKE = Path(os.environ.get("IRAALGO_LAKE", Path.home() / "lake")).expanduser()
OUT = Path(os.environ.get("R89_OUT", Path.home() / ".cache" / "iraalgo" / "r89")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

INDICES = ("NIFTY", "BANKNIFTY", "SENSEX", "FINNIFTY", "MIDCPNIFTY")
MCX = ("GOLDM", "SILVERM", "CRUDEOILM", "NATGASMINI")
LABEL = {"CRUDEOILM": "CRUDEOIL (mini)", "NATGASMINI": "NATURALGAS (mini)"}
INSTRUMENTS = INDICES + MCX

# ------------------------------------------------------------------------------------------------ windows (PREREG 2)
IDX_DESIGN = (date(2024, 11, 20), date(2026, 3, 31))
MCX_DESIGN = (date(2025, 8, 5), date(2026, 3, 31))
HOLDOUT = (date(2026, 4, 1), date(2026, 10, 19))
OLD = (date(2021, 8, 2), date(2024, 11, 19))          # index underlying only, descriptive
IDX_FOLDS = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
             (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
MCX_FOLDS = ((date(2025, 11, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 2, 28)),
             (date(2026, 3, 1), date(2026, 3, 31)))

# ------------------------------------------------------------------------------------------------ sessions
NSE_OPEN = 9 * 60 + 15
MCX_OPEN = 9 * 60
NSE_FIRST, NSE_LAST = 9 * 60 + 16, 14 * 60 + 45        # trigger window (minute of day)
NSE_WIN_FIRST = 9 * 60 + 30
MCX_LAST = 22 * 60 + 30
MCX_WIN_FIRST = 9 * 60 + 15
NSE_SQ, NSE_SQ_EXPIRY = 15 * 60 + 10, 15 * 60 + 5
WINDOW_CANDLES = 15                                     # signal candle within 15 TF candles of the break
SWING_SESSIONS = 5                                      # swing candidates: today + the previous 5 sessions
MAX_PER_DAY = 3
TICK = {"NIFTY": 0.05, "BANKNIFTY": 0.05, "SENSEX": 0.05, "FINNIFTY": 0.05, "MIDCPNIFTY": 0.05,
        "GOLDM": 1.0, "SILVERM": 1.0, "CRUDEOILM": 1.0, "NATGASMINI": 0.1}

# ------------------------------------------------------------------------------------------------ MCX books (R53 / R85)
MCX_MULT = {"GOLDM": 10.0, "SILVERM": 5.0, "CRUDEOILM": 10.0, "NATGASMINI": 250.0}
MCX_FAM = {"GOLDM": "gold", "SILVERM": "silver", "CRUDEOILM": "crude", "NATGASMINI": "natgas"}
MCX_HALF_BOOK = {"GOLDM": "GOLDM", "SILVERM": "SILVERM", "CRUDEOILM": "CRUDEOIL", "NATGASMINI": "NATURALGAS"}

# ------------------------------------------------------------------------------------------------ sizing
RISK_RS = 2000.0
MAX_LOTS = 3
DELTA = 0.6

# ------------------------------------------------------------------------------------------------ gates (PREREG 7)
TWINS = 500
SEED = 89
MIN_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10
MONTH_STOP = -13000.0
HOLD_MIN_TRADES = 20
HOLD_P = 0.10


@dataclass(frozen=True)
class Variant:
    name: str
    reading: str = "C"          # 'C' break and go, 'R' break and reclaim
    tf: int = 1
    sr: bool = False
    wick: bool = False
    htf: bool = False
    n: int = 5
    minrr: float = 1.0
    win: bool = False
    first_only: bool = False

    @property
    def gen_key(self):
        """Variants with the same key share their raw triggers (filters are applied afterwards)."""
        return (self.reading, self.tf, self.sr, self.wick)


def variants() -> list:
    out = []
    for rd in ("C", "R"):
        b = Variant(f"{rd}-B", rd)
        out += [b, replace(b, name=f"{rd}-SR", sr=True), replace(b, name=f"{rd}-WK", wick=True),
                replace(b, name=f"{rd}-T3", tf=3), replace(b, name=f"{rd}-T5", tf=5),
                replace(b, name=f"{rd}-HTF", htf=True), replace(b, name=f"{rd}-N3", n=3),
                replace(b, name=f"{rd}-RR2", minrr=2.0), replace(b, name=f"{rd}-WIN", win=True),
                replace(b, name=f"{rd}-F1", first_only=True)]
    return out


VARIANTS = variants()
BY_NAME = {v.name: v for v in VARIANTS}
