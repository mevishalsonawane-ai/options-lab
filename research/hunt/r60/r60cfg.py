"""R60 settings (PREREG.md): Solo on SENSEX, MIDCPNIFTY and the MCX commodities. Research only.

    lake (index)  $R60_LAKE_FLAT (default ~/.cache/iraalgo/lake_flat): index_1m_* and opt_<UND>_WEEK_* files, flat, as
                  R51 / R57 read them
    lake (MCX)    $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): options_rolling_1m/opt_<SYM>_MONTH_*, as R53 reads them
    cache         $R60_OUT (default ~/.cache/iraalgo/r60): day stores, markets, candidates (never in git)
    results       research/hunt/r60/results (small JSON / markdown only)

The index half re-uses R57's code (hunt/r57) unchanged. R57's settings are pointed at R60's cache, window and index list
here, BEFORE any R57 module that reads them at import time is imported, so SENSEX gets its own index one-hot.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R57 = HERE.parent / "r57"
R51 = HERE.parent / "r51"
for p in (ROOT, ROOT / "research", R51, R57, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R60_OUT", CACHE_ROOT / "r60")).expanduser()
LAKE_FLAT = Path(os.environ.get("R60_LAKE_FLAT", CACHE_ROOT / "lake_flat")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# ------------------------------------------------------------------------------------------------ window and split
START = date(2024, 11, 20)              # nothing traded before this (Boss, 15 Oct)
MCX_START = date(2025, 8, 5)            # first MCX option minute in the lake
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
END = date(2026, 10, 13)                # the lake's last session for SENSEX, MIDCPNIFTY and the MCX options

IDX_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
MCX_SYMS = ("CRUDEOIL", "GOLD", "GOLDM", "NATURALGAS", "SILVER", "SILVERM")       # A-Z (ties in strength go A-Z)
NO_OPTION_DATA = ("CRUDEOILM", "NATGASMINI", "GOLDPETAL", "SILVERMIC")

IDX_WF_MONTHS = [(2025, m) for m in range(7, 13)] + [(2026, m) for m in (1, 2, 3)]
MCX_WF_MONTHS = [(2025, 11), (2025, 12), (2026, 1), (2026, 2), (2026, 3)]
FINAL_MONTHS = [(2026, m) for m in range(4, 11)]

TWINS = 500
SEED = 60
GATE_P, GATE_Q, GATE_MIN_TRADES = 0.05, 0.10, 40


def period_of(d: date, mcx: bool = False) -> str | None:
    lo = MCX_START if mcx else START
    if lo <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= END:
        return "final"
    return None


# ------------------------------------------------------------------------------------------------ the period guard
_FINAL_OPEN = False


def open_final():
    global _FINAL_OPEN
    _FINAL_OPEN = True


def final_open() -> bool:
    return _FINAL_OPEN


def check_range(lo: date, hi: date, mcx: bool = False):
    first = MCX_START if mcx else START
    if lo < first:
        raise PermissionError(f"R60 trades nothing before {first} (asked for {lo})")
    if hi > DESIGN_END and not _FINAL_OPEN:
        raise PermissionError(f"R60: {hi} is in the final test (from {FINAL_START}); read only by `r60study.py final`")


# ------------------------------------------------------------------------------------------------ point R57 at R60
def point_r57():
    """Point R57 (and through it R51) at R60's cache, flat lake, window and five indices. Call before importing
    r57data / r57model / r57setups / r57study."""
    os.environ.setdefault("R57_OUT", str(OUT / "idx"))
    import r57cfg as K57
    import r51cfg as K51
    K57.LAKE = K51.LAKE = LAKE_FLAT
    K57.OUT = K51.OUT = OUT / "idx"
    K57.END = K51.END = END
    K57.UNDS = IDX_UNDS
    K57.SEED = SEED
    K57.TWINS = TWINS
    K57.RESULTS = RESULTS / "idx"
    K57.FINAL_FLAG = FINAL_FLAG
    return K57
