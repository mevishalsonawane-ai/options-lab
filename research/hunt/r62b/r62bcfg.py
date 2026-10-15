"""R62b settings (PREREG.md): EmreKb's "Market Structure Break & Order Block" on the five index options, Nov 2024 on.

    lake     $R62B_LAKE_FLAT (default ~/.cache/iraalgo/lake_flat): index_1m_<U>_<Y> and opt_<U>_WEEK_<Y>, flat, as R51
             reads them (rclone from gdrive:IraAlgo-data-lake; never in git)
    cache    $R62B_OUT (default ~/.cache/iraalgo/r62b): day stores, grids, candidates, trade dumps (never in git)
    results  research/hunt/r62b/results (small JSON / markdown only)

R51's store and engine are used unchanged, pointed at R62b's lake and cache here BEFORE any R51 module that reads its
settings at import is imported. R51_CLEAN=1 (Amendment A: foreign rows and stray prints removed) is always on.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R51 = HERE.parent / "r51"
for p in (ROOT, ROOT / "research", R51, HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R62B_OUT", CACHE_ROOT / "r62b")).expanduser()
LAKE_FLAT = Path(os.environ.get("R62B_LAKE_FLAT", CACHE_ROOT / "lake_flat")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# ------------------------------------------------------------------------------------------------ window and split
WARM_START = date(2024, 9, 1)            # zigzag / market warm-up only, never a trade
START = date(2024, 11, 20)               # nothing traded before this (Boss, 14-15 Oct)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
END = date(2026, 10, 14)                 # upper bound; the lake's last session decides
QUARTERS = ("2025Q1", "2025Q2", "2025Q3", "2025Q4", "2026Q1")   # 2025Q1 includes 20 Nov - 31 Dec 2024

IDX = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

# ------------------------------------------------------------------------------------------------ grid and rules
NROWS = 375                              # rows 0..374 = 09:15..15:29
MIN_BARS = 300                           # a session needs >= 300 one-minute bars


def row(hh: int, mm: int) -> int:
    return (hh - 9) * 60 + mm - 15


ZIGZAG_LEN = 9                           # the script's default; the video does not change it
FIB = 0.273                              # the video's setting (the script's default is 0.33)
FIB_SENS = (0.33, 0.50)                  # information only, never gated
TFS = (5, 15)                            # signal timeframes (minutes)
TF_HTF = 60                              # trend filter: 1-hour structure
STOP_BUFFER = 0.0002                     # "just outside" the swing: 0.02% of price
RR_MIN = 1.5                             # `rr` target variant: (T - entry) / (entry - S) >= 1.5
HOLD_BARS = 12                           # time exit: 12 signal bars after the fill
SIG_FIRST = row(9, 24)                   # signal minute 09:24 .. 14:29 (fill 09:25 .. 14:30)
SIG_LAST = row(14, 29)
SQ_IDX = row(15, 15)                     # square-off (15:05 on expiry days, R51 sq_row)
ITM_MONEY = 1                            # 1-ITM (CLAUDE.md default strike rule)

VARIANTS = tuple(f"{tf}m-{c}-{h}-{t}" for tf in TFS for c in ("tch", "rej") for h in ("x", "h") for t in ("st", "rr"))

TWINS = 500
SEED = 621
GATE_P, GATE_Q, GATE_MIN_TRADES = 0.05, 0.10, 40
CLAIMED_WIN = (0.75, 0.80)


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= END:
        return "final"
    return None


def quarter(d: date) -> str:
    if d < date(2025, 1, 1):
        return "2025Q1"
    return f"{d.year}Q{(d.month - 1) // 3 + 1}"


def parse_variant(v: str) -> dict:
    tf, c, h, t = v.split("-")
    return dict(tf=int(tf[:-1]), conf=c, htf=h == "h", target=t)


# ------------------------------------------------------------------------------------------------ the period guard
_FINAL_OPEN = False


def open_final():
    global _FINAL_OPEN
    _FINAL_OPEN = True


def check_range(lo: date, hi: date):
    if lo < START:
        raise PermissionError(f"R62b trades nothing before {START} (asked for {lo})")
    if hi > DESIGN_END and not _FINAL_OPEN:
        raise PermissionError(f"R62b: {hi} is in the final test (from {FINAL_START}); read only by `r62bstudy.py final`")


# ------------------------------------------------------------------------------------------------ point R51 at R62b
def point_r51():
    """R51's store and engine, on R62b's flat lake and cache, with Amendment A cleaning. Call before importing r51data /
    engine."""
    os.environ["R51_CLEAN"] = "1"
    import r51cfg as K51
    K51.LAKE = LAKE_FLAT
    K51.CLEAN = True
    K51.OUT = OUT / "idx"
    K51.END = END
    return K51
