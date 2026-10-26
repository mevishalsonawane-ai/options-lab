"""R109 settings (PREREG.md). IraThinker's mom60 and BANKNIFTY Monday short strangle, tested on the lake's real prints.

    lake      $R109_LAKE (default /root/lake): index_1m/, index_daily/, options_rolling_1m/, participants/bhav_index/,
              copied with rclone from gdrive:IraAlgo-data-lake on 26 Oct 2026; never committed
    cache     $R109_OUT (default ~/.cache/iraalgo/r109): held-contract stores and trade tables (never in git)
    results   research/hunt/r109_irathinker/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE, HERE.parent / "r51", HERE.parent / "r102_deep", HERE.parent):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

LAKE = Path(os.environ.get("R109_LAKE", "/root/lake")).expanduser()
OUT = Path(os.environ.get("R109_OUT", Path.home() / ".cache" / "iraalgo" / "r109")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# ------------------------------------------------------------------------------------------------ periods (PREREG 3)
WARM_START = date(2021, 7, 1)          # index bars only (realised vol warm-up)
START = date(2021, 8, 1)               # first option day read
HIST_END = date(2024, 11, 19)
DESIGN_START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
WF_START = date(2025, 4, 1)
WF_Q = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
        (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)
POST_END = date(2026, 10, 19)          # the lake's last session
END = POST_END


def period_of(d: date) -> str:
    if d <= HIST_END:
        return "history"
    if d <= DESIGN_END:
        return "design"
    if d <= FINAL_END:
        return "final"
    return "post"


# ------------------------------------------------------------------------------------------------ market
OPEN_MIN = 9 * 60 + 15
NROWS = 375                            # 09:15 .. 15:29


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - OPEN_MIN


STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
BSE = {"SENSEX"}
TICK = 0.05
LOTS = {   # research/hunt/r51/r51cfg.py LOTS (the lot in force on each date)
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35), ("2025-12-31", 30)],
    "FINNIFTY": [("2000-01-01", 40), ("2024-07-24", 25), ("2024-11-28", 65), ("2025-12-31", 60)],
    "SENSEX": [("2000-01-01", 10), ("2024-11-28", 20)],
    "MIDCPNIFTY": [("2000-01-01", 75), ("2024-11-28", 120), ("2025-06-27", 140), ("2025-12-31", 120)],
}


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


# ------------------------------------------------------------------------------------------------ mom60 (Mom60.java)
HOLD = 60                              # minutes
STOP_MULT = 0.70                       # premium stop at 70% of entry
SPACING = 60                           # minutes entry to entry
FIRST_SIG_ROW = row(10, 15)            # ret60 needs the bar that started 60 minutes earlier (09:15)
LAST_SIG_ROW = row(14, 29)             # entry (the next minute) at or before 14:30
FILL_LOOK_ENTRY = 2                    # entry: the first open in the 3 minutes from the entry minute (R64)
FILL_LOOK_EXIT = 5                     # exit: the first open in the next 5 minutes, else stale (R103)
THRESH = {"BANKNIFTY": 0.50, "FINNIFTY": 0.5025, "SENSEX": 0.4735}   # % (the studies' train q90)

# ------------------------------------------------------------------------------------------------ strangle (Strangle.java)
STR_DECIDE = row(9, 30)                # the 09:30 decision bar (its close)
STR_OFFSET = 200.0
STR_EXIT_ROW = row(15, 29)             # buy back at the 15:29 minute's open
STR_STRIKE_STEP = 100.0
MARGIN_FRAC = 0.13                     # margin estimate: 13% of spot x lot (PREREG 6; IraThinker's Rs 2.14 lakh)

# ------------------------------------------------------------------------------------------------ fills and costs
BPS = 5
BPS_STOP = 10
THIN_LOTS = 20.0
RATE = 0.065

# ------------------------------------------------------------------------------------------------ tests (PREREG 4)
TESTS = {
    "m60-bn": dict(kind="mom60", und="BANKNIFTY", series="MONTH", thr="BANKNIFTY", family="mom60"),
    "m60-fin": dict(kind="mom60", und="FINNIFTY", series="MONTH", thr="FINNIFTY", family="mom60"),
    "m60-sx": dict(kind="mom60", und="SENSEX", series="MONTH", thr="SENSEX", family="mom60"),
    "m60-nf": dict(kind="mom60", und="NIFTY", series="MONTH", thr="hist", family="out-of-family"),
    "m60-mid": dict(kind="mom60", und="MIDCPNIFTY", series="MONTH", thr="hist", family="out-of-family"),
    "m60-nf-w": dict(kind="mom60", und="NIFTY", series="WEEK", thr="hist", family="weekly-variant"),
    "m60-sx-w": dict(kind="mom60", und="SENSEX", series="WEEK", thr="SENSEX", family="weekly-variant"),
    "strangle-bn": dict(kind="strangle", und="BANKNIFTY", series="MONTH", thr=None, family="strangle"),
}

# ------------------------------------------------------------------------------------------------ gates (PREREG 5)
SEED = 109
POOL = 50
DRAWS = 400_000
BOOT = 10_000
GATE_P = 0.05
GATE_Q = 0.10
MIN_DESIGN = 40
MIN_FINAL = 20
ACCOUNT_DD = 40_000.0
ACCOUNT_MONTH = -13_000.0
N_PRIOR_R1_R63 = 8_461
N_PRIOR_R64_R107 = 5_777
N_PRIOR_R108 = 7
N_PRIOR = N_PRIOR_R1_R63 + N_PRIOR_R64_R107 + N_PRIOR_R108      # 14,245
N_ALL_VARIANTS = 8_961_492                                       # every logged variant of R1-R108 (sensitivity)

# IraThinker's own claims (the alert footers and Boss's brief), for the comparison table
CLAIMS = {
    "m60-bn": "train +2.29% / val +0.99% / test +4.75% per trade, win ~50%, 1-2 trades a day",
    "m60-fin": "+2.33% per trade, PF 1.40, n=713",
    "m60-sx": "+2.03% per trade, PF 1.31, n=593, win ~44%",
    "strangle-bn": "+16.42% of premium pooled, win 77.8%, worst week -285% of premium",
}
