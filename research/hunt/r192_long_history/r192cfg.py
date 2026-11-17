"""R192 settings (PREREG.md): longer history and replicated patterns. Plain-English notes, 17 Nov 2026.

    lake      ~/.cache/iraalgo/r190/lake          R190's flat copy of the Drive lake (index minutes, daily closes)
    store     ~/.cache/iraalgo/r190/r51/clean/day_NIFTY.pkl   R51's clean NIFTY option store (Aug 2020 on)
    out       ~/.cache/iraalgo/r192               R192's tables (never in git)
    results   research/hunt/r192_long_history/results   small JSON / markdown only

Signal source of every rule: the NIFTY index (and India VIX daily closes as a filter); options are only traded.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
HUNT = HERE.parent
R190 = HUNT / "r190_best_hunt"
for p in (str(ROOT), str(ROOT / "research"), str(HERE), str(R190)):
    if p not in sys.path:
        sys.path.insert(0, p)

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
R190_OUT = Path(os.environ.get("R190_OUT", CACHE_ROOT / "r190")).expanduser()
LAKE = R190_OUT / "lake"
STORE = R190_OUT / "r51" / "clean" / "day_NIFTY.pkl"
OUT = Path(os.environ.get("R192_OUT", CACHE_ROOT / "r192")).expanduser()
RESULTS = HERE / "results"

# ------------------------------------------------------------------------------------------------ eras (PREREG 2)
ERAS = {"S0": (date(2008, 1, 1), date(2017, 3, 31)),
        "S": (date(2017, 4, 3), date(2020, 7, 31)),
        "A": (date(2020, 8, 3), date(2021, 7, 30)),
        "B": (date(2021, 12, 1), date(2024, 10, 31)),
        "C": (date(2024, 11, 1), date(2026, 11, 13))}
SYN_ERAS, REAL_ERAS = ("S0", "S"), ("A", "B", "C")
ERA_NAME = {"S0": "S0: Jan 2008 - Mar 2017 (SYNTHETIC, daily bars)", "S": "S: Apr 2017 - Jul 2020 (SYNTHETIC, minutes)",
            "A": "A: Aug 2020 - Jul 2021 (real)", "B": "B: Dec 2021 - Oct 2024 (real)",
            "C": "C: Nov 2024 - 13 Nov 2026 (real)"}
MINUTE_START = date(2017, 4, 3)
K4_SEL = (date(2017, 4, 3), date(2018, 12, 31))      # K4's filter is chosen here only
K4_S = (date(2019, 1, 1), date(2020, 7, 31))          # K4's era S after the choice
WEEKLY_FROM = date(2019, 2, 14)                       # first NIFTY weekly expiry (synthetic calendar)


def era_of(d: date) -> str | None:
    for k, (lo, hi) in ERAS.items():
        if lo <= d <= hi:
            return k
    return None


# ------------------------------------------------------------------------------------------------ grid
N = 375                                    # 09:15 .. 15:29
OPEN_MIN = 9 * 60 + 15


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - OPEN_MIN


R0916, R0920, R0930, R1500, R1505, R1520 = row(9, 16), row(9, 20), row(9, 30), row(15, 0), row(15, 5), row(15, 20)
FILL_SLACK = 3                             # the order minute or up to 2 minutes later (R51 / R190)
EXIT_SLACK = 10                            # a sale with no print at its minute: the next print up to 10 minutes later
STEP = 50.0
TICK = 0.05

# ------------------------------------------------------------------------------------------------ the rules (PREREG 4)
LOC_HI, LOC_LO = 0.6, 0.4                  # K4 base (R17 strong close)
GAP_FADE = (0.003, 0.010)                  # K5
GAP_GO = 0.005                             # K6
STOP_FRAC = 0.70                           # K5 / K6: option close <= 70% of the entry fill
VIX_SPIKE, VIX_BACK = 1.25, 10             # K7
HI_DAYS = 252                              # K8
SMA_DAYS, RV_DAYS, RV_MED_DAYS = 200, 20, 252   # K9 (and K4 F2)
BIG_MOVE = 0.015                           # K10 / K11 (and K4 F4)
K4_F3_PCTL, K4_F3_DAYS = 80.0, 252
K4_F5 = (0.8, 0.2)
K4_MIN_SEL = 60
K4_FILTERS = ("F0", "F1", "F2", "F3", "F4", "F5")

# timings (hold types) and which idea uses which
#   ON  15:20 -> next session 09:16;  CC1 15:20 -> next session 15:20;  CC2 15:20 -> 2 sessions on 15:20
#   D2  09:20 -> next session 15:20;  G5 K5's intraday fade (target + stop);  G6 K6's intraday follow (stop)
TIMING = {"K1": "CC2", "K2": "CC1", "K3": "ON", "K4": "ON", "K5": "G5", "K6": "G6", "K7": "D2", "K8": "CC1",
          "K9": "ON", "K10": "CC1", "K11": "CC1"}
IDEAS = tuple(TIMING)
DAILY_OK = ("K1", "K2", "K3", "K7", "K8", "K9", "K10", "K11")
NAMES = {
    "K1": "Turn of the month: call, last session 15:20 -> 2nd session of the new month 15:20",
    "K2": "Pre-holiday: call, 15:20 the session before -> 15:20 on the pre-holiday session",
    "K3": "Overnight drift: call every night, 15:20 -> 09:16",
    "K4": "Night proxy (strong close) + one filter chosen on 2017-18, 15:20 -> 09:16",
    "K5": "Gap fade 0.3-1.0% at 09:20, target the previous close, stop -30%, out 15:20",
    "K6": "Gap follow >= 0.5% confirmed at 09:30, stop -30%, out 15:20",
    "K7": "India VIX spike (+25% vs 10-day mean on a down day): call 09:20 -> next session 15:20",
    "K8": "52-week closing high / low: call / put 15:20 -> next session 15:20",
    "K9": "200-day trend with a low-volatility filter: call / put 15:20 -> 09:16",
    "K10": "Reversal after a 1.5% day: opposite side 15:20 -> next session 15:20",
    "K11": "Continuation after a 1.5% day: same side 15:20 -> next session 15:20",
}

# ------------------------------------------------------------------------------------------------ pricing
RATE = 0.065                               # synthetic forward rate
SYN_LOT = 75                               # synthetic eras: fixed lot (label)
SYN_VOL_LOTS = 20.0                        # synthetic fills: the liquid R17 case (1 tick)
A_GRID = tuple(round(0.50 + 0.01 * i, 2) for i in range(101))
QLOTS = [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)]

# ------------------------------------------------------------------------------------------------ gate (PREREG 6-7)
ACCOUNT = 56000.0
MONTH_CAP = -13000.0
ACC_DD = 40000.0
TWINS = 5000
SEED = 192
GATE_P = 0.05
GATE_Q = 0.10
MIN_TRADES = 40
N_PRIOR = 4755                             # entry files at the branch base (origin/main d1b1d57d8)
N_R192 = 16                                # K1-K11 with K4's six menu members counted separately
M_TOTAL = N_PRIOR + N_R192                 # 4,771
