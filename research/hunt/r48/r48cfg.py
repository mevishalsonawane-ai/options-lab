"""R48 settings, fixed in PREREG.md (15 Oct 2026, amendment 1 the same day) before any P&L.

Signal source of every rule: the INDEX (index_1m closes, index_daily) - the default. The ATM straddle (option-chain
context) only sets the short strike's distance; stops and targets sit on the spread / premium value.
"""
from __future__ import annotations

from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"

DESIGN_START = date(2024, 11, 1)         # amendment 3 (Boss, 15 Oct): backtest only from Nov 2024
H1_END = date(2025, 4, 30)               # design halves: DESIGN_START .. H1_END, H1_END+1 .. DESIGN_END
DESIGN_END = date(2025, 10, 17)          # last design ENTRY day
EMBARGO_END = date(2025, 10, 31)         # 20-31 Oct 2025: no entries
HOLDOUT_START = date(2025, 11, 3)        # read once, by r48study.py holdout
BRAIN_HOLDOUT = date(2026, 4, 27)        # the Data Brain's untouched holdout starts here (holdout reported split)
LAST_YEAR = DESIGN_START                 # amendment 3: the whole design is the "last year"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
BSE = {"SENSEX"}

# decision minutes (orders fill at the next minute's open)
TIMES = {"0920": (9, 20), "1000": (10, 0), "1100": (11, 0), "1300": (13, 0)}
DTES = (0, 1, 2, 3, 4)

# A. selling
M_DIST = (0.5, 1.0, 1.5)                 # short strike distance, x the ATM straddle
WIDTHS = (2, 4, 8)                       # hedge width, strike steps
PTS = (None, 0.3, 0.5, 0.7)              # profit target, share of the credit captured
SLS = (None, 1.0, 2.0)                   # credit stop: cost to close >= (1 + k) x credit
ISL = (False, True)                      # index stop
HOLD = ("day", "exp")                    # same day 15:15 / hold to expiry 15:05 (DTE 0: both are 15:05)
SIGNALS = ("gap", "ema", "twap", "orb")
DIRS = ("with", "fade")
VIX = ("all", "low", "high")
EVENTS = ("all", "skip")

# B. buying (amendment 4: buying only, wider grid)
BUY_TIMES = {"0920": (9, 20), "0930": (9, 30), "1000": (10, 0), "1030": (10, 30), "1100": (11, 0),
             "1200": (12, 0), "1300": (13, 0), "1400": (14, 0)}
BUY_DTES = (0, 1, 2, 3, 4, "any")        # "any": every session, nearest expiry whatever its DTE
BUY_DIRS = ("with", "fade")
ITM = (0, 2, 4, 6)                       # steps in the money
TGTS = (0.03, 0.05, 0.10, 0.15, 0.20, 0.30)
BSTOPS = (None, 0.10, 0.20, 0.33, 0.50)
BTIME = (15, 30, 60, 120, "eod")         # minutes after the fill, or 15:15 (15:05 on expiry)

# slippage (PREREG): 5 bps + n ticks of Rs 0.05; n = 1 / 2 / 4 by the contract's 5-minute volume (>= 20 lots, less,
# none); +1 tick for strikes >= 6 steps from ATM; ticks doubled on stop exits
TICK = 0.05
BPS = 5
THIN_LOTS = 20
FAR_STEPS = 6
MIN_SELL = 0.05
SETTLE_MAX = 1.0                         # a long hedge priced <= Rs 1 at the 15:05 expiry square-off is left to settle

# margin (PREREG, stated assumption)
ACCOUNT = 100_000
EXPOSURE = 0.02
EXPIRY_ELM = 0.02
ELM_FROM = date(2024, 11, 20)

# candidate rule
MIN_TRADES = 43                          # amendment 3 (was 100 on the full period)
MIN_WIN = 0.65
YEAR_SHARE = 0.75
YEAR_MIN_N = 10
LAST_YEAR_MIN_N = 43
WORST_DAY = -13_000
MAX_DD = -40_000
N_CAND = 10
MIN_PRICED = 0.90                        # amendment 2: priced on >= 90% of its sessions

# holdout gates
H_WIN = 0.60
H_MIN_N = 40
H_P = 0.05
H_Q = 0.10
N_TWINS = 500
N_BOOT = 10_000
SEED = 48

# event days (research/swing_deep.py lists: RBI MPC decisions, Union Budgets, general-election results)
BUDGET = ["2022-02-01", "2023-02-01", "2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01"]
RBI = ["2021-08-06", "2021-10-08", "2021-12-08", "2022-02-10", "2022-04-08", "2022-05-04", "2022-06-08", "2022-08-05",
       "2022-09-30", "2022-12-07", "2023-02-08", "2023-04-06", "2023-06-08", "2023-08-10", "2023-10-06", "2023-12-08",
       "2024-02-08", "2024-04-05", "2024-06-07", "2024-08-08", "2024-10-09", "2024-12-06", "2025-02-07", "2025-04-09",
       "2025-06-06", "2025-08-06", "2025-10-01", "2025-12-05", "2026-02-06", "2026-04-08", "2026-06-05", "2026-08-07",
       "2026-10-01"]
ELECTION = ["2024-06-04"]
EVENT_DAYS = frozenset(date.fromisoformat(x) for x in BUDGET + RBI + ELECTION)

# tail days reported (PREREG)
GAP_DAYS = (date(2022, 2, 24), date(2024, 6, 4), date(2025, 4, 7))
