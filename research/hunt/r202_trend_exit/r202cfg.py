"""R202 / R203 settings (PREREG.md, PREREG_R203.md): extra exits and loss-cutting ideas for R72 Breadth Donchian,
R51 Donchian 20 and Liquidity 15+5, on top of the rules they run today (V5). Plain-English notes, 20 Nov 2026.

    lake      ~/.cache/iraalgo/r202/lake (r202data.py: the dhan-data branch in the lake's layout, to 5 Oct 2026)
    stores    ~/.cache/iraalgo/r202/r51/clean (R51's Amendment A clean stores, built here)
    caches    ~/.cache/iraalgo/r202 (never git)
    results   research/hunt/r202_trend_exit/results (small JSON / markdown only)

Importing this module sets the environment the R176 / R106 / R51 stack reads; import it before any of them.
Signal source of every rule: the index. Options are only bought.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
HUNT = HERE.parent
for p in (str(ROOT), str(ROOT / "research"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

HOME = Path.home() / ".cache" / "iraalgo"
OUT = Path(os.environ.get("R202_OUT", HOME / "r202")).expanduser()
LAKE = Path(os.environ.get("R202_LAKE", OUT / "lake")).expanduser()
RESULTS = HERE / "results"


def set_env():
    """The stack's environment (before it is imported)."""
    os.environ["IRAALGO_LAKE"] = str(LAKE)
    os.environ["IRAALGO_CACHE"] = str(OUT / "cache")
    os.environ["R51_OUT"] = str(OUT / "r51")
    os.environ["R51_CLEAN"] = "1"
    os.environ.pop("R51_STALE", None)
    os.environ["R176_POST"] = "0"


# warm-up, first option day, last full session of dhan-data
STORE = (date(2024, 9, 1), date(2024, 11, 20), date(2026, 10, 5))

# ------------------------------------------------------------------------------------------------ windows (PREREG 2)
SPAN = (date(2024, 11, 21), date(2026, 10, 5))
DESIGN = (date(2024, 11, 21), date(2026, 3, 31))
HOLDOUT = (date(2026, 4, 1), date(2026, 10, 5))
LAST_YEAR = (date(2025, 11, 24), date(2026, 10, 5))
FOLDS = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
         (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
WINDOWS = {"design": DESIGN, "holdout": HOLDOUT, "total": SPAN, "last_year": LAST_YEAR}
WINDOW_NAME = {"design": "design 21 Nov 2024 - 31 Mar 2026", "holdout": "holdout 1 Apr - 5 Oct 2026 (read once)",
               "total": "total 21 Nov 2024 - 5 Oct 2026", "last_year": "last year 24 Nov 2025 - 5 Oct 2026 (descriptive)"}
# walk-forward skip quarters (PREREG 4): first days
QUARTERS = (date(2025, 4, 1), date(2025, 7, 1), date(2025, 10, 1), date(2026, 1, 1), date(2026, 4, 1), date(2026, 7, 1),
            date(2026, 10, 1))
WF_MIN_N = 60

# ------------------------------------------------------------------------------------------------ arms and rules
ARMS = ("R72", "R51", "LIQ")
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "LIQ": "Liquidity 15+5"}
LOCK = {"R72": "L500", "R51": "L500", "LIQ": "LBE"}          # today's rule (V5) on top of breakeven at +10%
BE_X = 0.10
PICK_THR = {"R72": 0.4656, "R51": 0.4469}
LIQ_UNDS = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
MARKET = "NIFTY"

TREND = ("X1", "X2", "X3", "X4", "X5", "X6", "X7", "X8", "X9")          # R202 (exits)
WF_SKIPS = ("W-hour", "W-vix", "W-gap", "W-dow", "W-rv", "W-und", "W-trend")
LOSS = WF_SKIPS + ("S10", "DC3", "DC5", "L2")                             # R203
EXIT_RULES = TREND + ("S10",)                                             # re-priced and re-walked; exit twins
SKIP_RULES = WF_SKIPS + ("DC3", "DC5", "L2")                              # applied after the walk; skip twins
RULES = TREND + LOSS
STUDY = {r: ("R202" if r in TREND else "R203") for r in RULES}
LABEL = {
    "B": "today's rule (V5), the baseline",
    "X1": "sell on a Donchian-20 break the other way (R51's opposite signal)",
    "X2": "sell on an R72 breadth break the other way",
    "X3": "sell on an opposite signal from any of the three arms",
    "X4": "sell after two 5-min closes back through the entry level",
    "X5": "sell after two 5-min closes on the wrong side of EMA20",
    "X6": "sell after two 5-min closes on the wrong side of the day's average price",
    "X7": "sell when the 5-min Supertrend(10, 3) flips against the trade",
    "X8": "sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars)",
    "X9": "sell when NIFTY's 15-min close is on the wrong side of its EMA20",
    "W-hour": "skip the worst entry-time bucket (walk-forward)",
    "W-vix": "skip the worst VIX regime (walk-forward)",
    "W-gap": "skip the worst opening-gap bucket (walk-forward)",
    "W-dow": "skip the worst weekday (walk-forward)",
    "W-rv": "skip the worst realised-volatility third (walk-forward)",
    "W-und": "skip the worst index (walk-forward)",
    "W-trend": "skip the worst trend-strength bucket (walk-forward)",
    "S10": "entry stop at -10% instead of -15%",
    "DC3": "no new entry after the arm is down Rs 3,000 closed that day",
    "DC5": "no new entry after the arm is down Rs 5,000 closed that day",
    "L2": "no new entry on an index after two losers in a row on it that day",
}
S10_STOP = 0.10
DAY_CAP = {"DC3": 3000.0, "DC5": 5000.0}
EMA_N, ST_N, ST_MULT, BREADTH_MIN = 20, 10, 3.0, 4
TESTS = tuple((a, r) for r in RULES for a in ARMS)                        # 60

# ------------------------------------------------------------------------------------------------ gate (PREREG 6)
TWINS = 2000
SEED = 202
GATE_P = 0.05
GATE_Q = 0.10
MIN_CHANGED = 40
NEAR = 0.05                                # "within 5% of B's net"
SMALLER = 0.20                             # "... with drawdown and worst month each at least 20% smaller"
FOLDS_NEEDED = 3
N_PRIOR = 4808                             # entry files at the branch base (origin/main 82fbc3b4)
M_TOTAL = N_PRIOR + len(TESTS)             # 4,868
ACC_DD = 40000.0
MONTH_CAP = -13000.0
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"
NROWS = 375
