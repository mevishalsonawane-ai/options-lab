"""R57 "Solo 2.0" settings (PREREG.md). Research only; nothing here changes the app.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake; never
              committed
    cache     $R57_OUT (default ~/.cache/iraalgo/r57): per-index day stores of held contracts, features, labels
    results   research/hunt/r57/results (small JSON / markdown only)

R57 reuses R51's held-contract engine (hunt/r51/engine.py) unchanged; r51cfg is pointed at R57's cache and window here,
before engine / r51data are imported, so both studies price a trade the same way.
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
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
OUT = Path(os.environ.get("R57_OUT", CACHE_ROOT / "r57")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# ------------------------------------------------------------------------------------------------ window and split
WARM_START = date(2024, 9, 1)          # indicator warm-up only, never a trade
START = date(2024, 11, 20)             # first tradable day (NSE weekly-expiry change; 21 Nov is the first session)
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
END = date(2026, 10, 14)               # the lake's last session (NIFTY; the others end 13 Oct)

# purged walk-forward: monthly test folds (design) and the final test's monthly refits
WF_MONTHS = [(2025, m) for m in range(7, 13)] + [(2026, m) for m in (1, 2, 3)]
FINAL_MONTHS = [(2026, m) for m in range(4, 11)]
EMBARGO_SESSIONS = 1

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")    # the NSE F&O indices the paper account can trade
SOLO_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY")             # plain SoloMidday as the app runs it

# ------------------------------------------------------------------------------------------------ gates (never relaxed)
TWINS = 500
SEED = 57
GATE_P = 0.05
GATE_Q = 0.10
GATE_MIN_TRADES = 40

# data cleaning
FOREIGN_TOL = 0.03                     # R47: an option row whose spot is > 3% off the index is another underlying's
SPIKE_JUMP = 1.5                       # lake spikes.py: > 50% away from both neighbours, which are within 50%

# ------------------------------------------------------------------------------------------------ point r51 at R57
import r51cfg as _K51                  # noqa: E402

_K51.LAKE = LAKE
_K51.OUT = OUT
_K51.WARM_START = WARM_START
_K51.START = START
_K51.DESIGN_END = DESIGN_END
_K51.FINAL_START = FINAL_START
_K51.END = END

STEP = _K51.STEP
NROWS = _K51.NROWS
OPEN_MIN = _K51.OPEN_MIN
row = _K51.row
lot = _K51.lot


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= END:
        return "final"
    return None


def month_key(d: date) -> tuple:
    return (d.year, d.month)
