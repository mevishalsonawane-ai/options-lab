"""R154 settings (PREREG.md): 10-15 point scalps on the index options, two readings (premium / index points).

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, index_daily, options_rolling_1m (WEEK)
    stores    from 20 Nov 2024: R51's Amendment A clean stores (R51_CLEAN=1; R106's clean_post for 14-19 Oct);
              before it: R154's early store ($R154_OUT/early, built by r154data.py with R51's own code)
    trades    $R154_OUT (default ~/.cache/iraalgo/r154): priced trades (never in git)
    results   research/hunt/r154_scalp_10_15/results (small JSON / markdown only)

Pure: periods, exit rules, entries and gates. Importing it puts R152's folder (and through it R112 / R106 / R101 / R51) on
the path.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
POST = os.environ.get("R154_POST", "0") == "1"
if POST:
    os.environ["R152_POST"] = "1"            # -> R106_POST=1: R106's clean_post stores (to 19 Oct)
os.environ["R51_CLEAN"] = "1"
for _d in ("r152_donchian_2k",):
    p = str(HERE.parent / _d)
    if p not in sys.path:
        sys.path.insert(0, p)
import r152cfg  # noqa: E402,F401  (r112, r106, r101, r51 on the path)

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R154_OUT", CACHE_ROOT / "r154")).expanduser()
EARLY = OUT / "early"
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
POST_FLAG = RESULTS / "POST_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

# ------------------------------------------------------------------------------------------------ periods (PREREG 4)
EARLY_WARM = date(2021, 6, 1)        # index bars read for warm-up only
EARLY_START = date(2021, 8, 1)       # the lake's first option month
ARM_START = date(2024, 11, 20)       # R51's clean stores (and the arms) start here
P1_END = date(2025, 3, 31)
P2_START = date(2025, 4, 1)
P2_END = date(2026, 3, 31)
P2_Q = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
        (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)
POST_START = date(2026, 10, 14)
POST_END = date(2026, 10, 19)
FIRST_OPTION_DAY = {"MIDCPNIFTY": date(2023, 1, 1)}   # PREREG 1: its 2022 option file is a few thin monthly days
WARMUP_SESSIONS = 30                 # E3 / E4: the first 30 sessions of each index are warm-up only


def period_of(d: date) -> str | None:
    """'P1', 'P2', 'P3' (final), 'P4' (post) or None."""
    if EARLY_START <= d <= P1_END:
        return "P1"
    if P2_START <= d <= P2_END:
        return "P2"
    if FINAL_START <= d <= FINAL_END:
        return "P3"
    if POST_START <= d <= POST_END:
        return "P4"
    return None


# ------------------------------------------------------------------------------------------------ minute grid
OPEN_MIN = 9 * 60 + 15
NROWS = 375


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - OPEN_MIN


SQ = row(15, 15)                     # Boss's square-off
SQ_EXPIRY = row(15, 5)               # expiry days (CLAUDE.md)
FIRST_ENTRY = row(9, 20)
FIRST_ENTRY_OR = row(9, 31)
LAST_ENTRY = row(14, 45)
OR_ROWS = 15                         # 09:15 - 09:29
TWAP_FIRST_END = row(9, 44)          # E2: first 5-minute bar end that may signal
MAX_PER_DAY = 5                      # plain entries: trades per entry x index a day
RANDOM_PER_DAY = 5
ITM = 1                              # the arms' strike rule: 1 strike in the money

# ------------------------------------------------------------------------------------------------ exits (PREREG 2)
SIZES = (10.0, 12.0, 15.0)
STOP_MULT = (1.0, 1.5)
TIMES = (15, 30)


def _fmt(x: float) -> str:
    return f"{x:g}"


def rule_id(reading: str, p: float, s: float, t: int) -> str:
    return f"{reading}{_fmt(p)}s{_fmt(s)}t{t}"


RULES = {}
for _rd in ("A", "B"):
    for _p in SIZES:
        for _m in STOP_MULT:
            for _t in TIMES:
                RULES[rule_id(_rd, _p, _p * _m, _t)] = dict(reading=_rd, target=_p, stop=_p * _m, tmax=_t)
RULE_IDS = tuple(RULES)              # 24

READING = {"A": "premium points (target / stop on the option's premium)",
           "B": "index points (target / stop on the index; the option sold at the next minute's open)"}

# ------------------------------------------------------------------------------------------------ entries (PREREG 3)
PLAIN = ("E1", "E2", "E3", "E4")
ARMS = ("R72", "R51", "LIQ")
ENTRIES = PLAIN + ARMS
ENTRY_UNDS = {**{e: UNDS for e in PLAIN}, "R72": UNDS, "R51": UNDS, "LIQ": ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")}
ENTRY_NAME = {
    "E1": "E1 OR15: first-15-minute range break (1-min close)",
    "E2": "E2 TWAPX: 5-min close crosses the session average price (VWAP stand-in; the index has no volume)",
    "E3": "E3 EMA921: 9 / 21 EMA cross on 5-min closes",
    "E4": "E4 PBB: 1-min close beyond the previous 1-min high / low, with the 5-min 21 EMA trend",
    "R72": "R72 Breadth Donchian (4 of 5) entries",
    "R51": "R51 Donchian 20 + Supertrend entries",
    "LIQ": "Liquidity 15+5 entries",
    "RND": "random entries (baseline): 5 random minutes a day, coin-flip side",
}
SIGNAL_SRC = {
    "E1": "index 1-min bars (09:15-09:29 range, 1-min closes)",
    "E2": "index 5-min bars + the session's equal-weight average of (H+L+C)/3 (VWAP stand-in)",
    "E3": "index 5-min closes (EMA 9 / 21)",
    "E4": "index 1-min bars + 5-min closes (EMA 21 trend)",
    "R72": "index 5-min bars of all five indices (Donchian 20, 4 of 5 break)",
    "R51": "index 5-min bars (Donchian 20) + Supertrend (10, 3)",
    "LIQ": "index 15/30-min + 5-min bars (liquidity pool breaks)",
}
TESTS = tuple((e, u, r) for e in ENTRIES for u in ENTRY_UNDS[e] for r in RULE_IDS)       # 792
LOTS = (1, 2, 3)

# B0 parity at 1 lot over the whole arm design (20 Nov 2024 - 31 Mar 2026) and the final: (trades, net)
PUBLISHED = {
    ("R72", "design"): (3071, 216929), ("R72", "final"): (1202, 171366),
    ("R51", "design"): (4208, 50425), ("R51", "final"): (1670, 134743),
    ("LIQ", "design"): (881, 257249), ("LIQ", "final"): (357, 11155),
}

# ------------------------------------------------------------------------------------------------ gates (PREREG 6)
TWINS = 500
SEED = 154
GATE_Q = 0.10
MIN_FINAL = 40
N_PRIOR = 890                        # R153's tally (R106 549 + 299 later entries + R115 / R150 / R151 + R152 18 + R153 24)
N_STRICT_EXTRA = 1_100_700 + 21      # SCALP17's screened combos + R45's tests (shown only)
