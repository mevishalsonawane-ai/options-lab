"""R108 settings (PREREG.md): the same-direction cap as the app runs it, and 7 alternatives.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, index_daily, options_rolling_1m WEEK (Drive)
    stores    R51's Amendment A clean stores (R51_CLEAN=1), as R101 / R106 / R107
    cache     $R108_OUT (default ~/.cache/iraalgo/r108): walks and trades (never in git)
    results   research/hunt/r108_same_direction_cap/results (small JSON / markdown only)

Pure: periods, rules, gates. Importing it puts R106's folder (and through it R101 / R51 / R64) on the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R106 = HERE.parent / "r106_small_targets"
for p in (str(R106), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R108_OUT", CACHE_ROOT / "r108")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods (PREREG section 1): identical to R101 / R106 / R107
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
WF_START = date(2025, 4, 1)
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)
PERIODS = ("design", "wf", "final")

ARMS = ("LIQ", "R72", "R51", "R52")
ARM_NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20",
            "R52": "R52 Open outside value"}
# each arm's premium stop (PREREG section 4, RISK): DonchianArm / Breadth -15%, Liquidity -15%, OovArm -30%
STOP_SHARE = {"LIQ": 0.15, "R72": 0.15, "R51": 0.15, "R52": 0.30}
CHARGES_PER_LOT = 60.0               # BossRisk.CHARGES_PER_LOT

BASES = ("ALONE", "NOCAP", "CAP2")
TESTS = ("CAP3", "IDX1", "IDX1C3", "RISK", "EDGE", "STOPS", "EXEMPT")
LABEL = {
    "ALONE": "each arm's own research record, summed (no book rules)",
    "NOCAP": "the paper book with no same-direction cap",
    "CAP2": "the app's cap: at most 2 a way across all indices, R72's last place kept (today)",
    "CAP3": "the same cap at 3",
    "IDX1": "at most 1 a way per index, across arms (no cross-index count)",
    "IDX1C3": "at most 1 a way per index and at most 3 a way in all",
    "RISK": "open stop-risk a way at most Rs 6,500 (instead of a count)",
    "EDGE": "cap 2, the arm with the best per-trade net so far decides first (no kept place)",
    "STOPS": "no cap; day -Rs 6,500, week -Rs 20,000 and month -Rs 13,000 loss stops only",
    "EXEMPT": "cap 2 on R72 and R51 only; R52 and Liquidity outside the cap",
}
# R107's model (for the attribution of the lead only): its order, no kept place, refused entries free every arm's book,
# no "one option, one arm" rule
R107_ORDER = ("R72", "R51", "LIQ", "R52")

# gates (PREREG section 6; never relaxed)
TWINS = 100_000
SEED = 108
GATE_Q = 0.10
MIN_CHANGED = 30
# every family R64-R107: the larger of its entry count and its variants_tried (R105 at its documented 138)
PRIOR_TESTS = {64: 15, 65: 30, 66: 15, 67: 15, 68: 21, 69: 15, 70: 4, 71: 16, 72: 12, 73: 72, 74: 11, 75: 40, 76: 6,
               77: 8, 78: 8, 79: 7, 80: 10, 81: 66, 82: 21, 83: 13, 84: 8, 85: 5, 86: 15, 87: 4771, 88: 4, 89: 20,
               90: 11, 91: 7, 92: 32, 93: 5, 94: 24, 95: 10, 96: 46, 97: 8, 98: 72, 99: 48, 100: 15, 101: 20, 102: 36,
               103: 24, 104: 6, 105: 138, 106: 36, 107: 11}
N_PRIOR = sum(PRIOR_TESTS.values())  # 5,777

# the account rule (CLAUDE.md): reported, never relaxed
ACCOUNT_DD = 40_000.0
ACCOUNT_MONTH = -13_000.0

# the phone's record (release diag-2026-10 of the private data repo, read 26 Oct; untrusted, rupees redacted)
PHONE = {
    "2026-10-21": ["Liquidity: 2 puts open (NIFTY / BANKNIFTY by R72)"],
    "2026-10-22": ["Liquidity: 2 puts open (MIDCPNIFTY by Liquidity 5m, FINNIFTY by R52)"],
    "2026-10-26": ["09:25 Liquidity 5m FINNIFTY: 2 puts open (NIFTY / BANKNIFTY by R72)",
                   "09:25 Liquidity 5m MIDCPNIFTY: 2 puts open (NIFTY / BANKNIFTY by R72)",
                   "10:00 Liquidity 5m MIDCPNIFTY: the last call place kept for R72 (1 call open, FINNIFTY by Liquidity 5m)"],
}


def in_period(d: date, period: str) -> bool:
    if period == "design":
        return START <= d <= DESIGN_END
    if period == "wf":
        return WF_START <= d <= DESIGN_END
    if period == "final":
        return FINAL_START <= d <= FINAL_END
    raise ValueError(period)
