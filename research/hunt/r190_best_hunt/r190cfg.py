"""R190 settings (PREREG.md): the best strategy hunt, reliability first. Plain-English notes, 17 Nov 2026.

    raw       ~/.cache/iraalgo/r190/raw   the Drive lake files copied with rclone (never in git)
    dhan      <scratch>/r190data/repo/dhan-data   NIFTY WEEK options 2020-21 from the dhan-data branch (git archive)
    lake      ~/.cache/iraalgo/r190/lake  one flat folder in the lake's layout, built by r190data.py
    out       ~/.cache/iraalgo/r190       stores, trade caches (never in git)
    results   research/hunt/r190_best_hunt/results (small JSON / markdown only)

Signal source of every rule: the index (CLAUDE.md default); options are only traded (Night's OI check is context).
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

_SCRATCH = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R190_OUT", CACHE_ROOT / "r190")).expanduser()
RAW = OUT / "raw"
LAKE = OUT / "lake"
DHAN = Path(os.environ.get("R190_DHAN", f"{_SCRATCH}/r190data/repo/dhan-data")).expanduser()
RESULTS = HERE / "results"
FLAG_A = RESULTS / "ERA_A_READ.flag"
ERA_A_JSON = RESULTS / "era_a.json"
PREREG = HERE / "PREREG.md"

# ------------------------------------------------------------------------------------------------ eras (PREREG 2)
LAKE_OPT_START = date(2021, 8, 2)          # the lake's first option day; dhan-data rows before it form era A
ERAS = {"A": (date(2020, 8, 3), date(2021, 7, 30)),
        "B": (date(2021, 12, 1), date(2024, 10, 31)),
        "C": (date(2024, 11, 1), date(2026, 11, 13))}
ERA_NAME = {"A": "A: Aug 2020 - Jul 2021 (oldest, read once)", "B": "B: Dec 2021 - Oct 2024",
            "C": "C: Nov 2024 - 13 Nov 2026"}


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


R0945, R1450, R1520, R1505 = row(9, 45), row(14, 50), row(15, 20), row(15, 5)
MARKS = tuple(row(10, 15) + 30 * i for i in range(11))     # 10:15 .. 15:15
LAST_ENTRY_MARK = row(14, 45)
FILL_SLACK = 3                             # the order minute or up to 2 minutes later (R51)
EXIT_SLACK = 10                            # a sale with no print at its minute: the next print up to 10 minutes later
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
TICK = 0.05

# ------------------------------------------------------------------------------------------------ the new rules
MOM_LOOKBACK = 60                          # sessions
MOM_PCTL = 60.0                            # top 40%: at or above the 60th percentile
NOISE_DAYS = 14
SMA_DAYS = 50
VIX_DAYS, VIX_PCTL = 252, 90.0

# ------------------------------------------------------------------------------------------------ sizing, gates
ACCOUNT = 56000.0
MONTH_CAP = -13000.0
ACC_DD = 40000.0
TWINS = 2000
SEED = 190
GATE_P = 0.05
GATE_Q = 0.10
MIN_TRADES = 40
N_PRIOR = 4749                             # entry files at the branch base (origin/main 30c4b3c62)
CANDIDATES = ("N1B", "N1N", "N2", "L1", "D1", "D2", "D3", "M1", "M2", "V1", "O1", "O2", "O3")
M_TOTAL = N_PRIOR + len(CANDIDATES)        # 4,762
NAMES = {
    "N1B": "Night (R3) book: NIFTY + BANKNIFTY, 1 lot each",
    "N1N": "Night (R3) on NIFTY alone",
    "N2": "Night for Rs 56k: one position a night, month cap -Rs 13k",
    "L1": "Liquidity 15+5 (BANKNIFTY, FINNIFTY, MIDCPNIFTY), as built",
    "D1": "R72 Breadth Donchian, today's rules",
    "D2": "R72 + R151 picker (live by Boss's exception)",
    "D3": "R72 + FB-R72-A10 (skip after a very wide day)",
    "M1": "End-of-day momentum (r1 + r2), NIFTY ATM, 14:50 -> 15:20",
    "M2": "End-of-day momentum (r2 alone), NIFTY ATM, 14:50 -> 15:20",
    "V1": "Noise-area breakout over TWAP, NIFTY ATM, 30-min checks",
    "O1": "M1 + trend (50-day average) + VIX overlay",
    "O2": "M2 + trend (50-day average) + VIX overlay",
    "O3": "V1 + trend (50-day average) + VIX overlay",
}
NIGHT_UNDS = ("NIFTY", "BANKNIFTY")
