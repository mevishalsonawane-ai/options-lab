"""R177-EMA settings (PREREG.md): the 9/21 EMA crossover on 15-minute index bars, with ADX(14) > 25, the 1-hour trend +
VWAP, and a 2 x ATR(14) index stop, alone and together (Boss, 15 Nov 2026: "What if all 3 are together").

    data      the repo's `research-data` release, read with R171's loader (R171_DATA, default ~/.cache/iraalgo/r171_release;
              its store pickle in R171_CACHE, default ~/.cache/iraalgo/r171). Never committed.
    cache     $R177E_OUT (default ~/.cache/iraalgo/r177e): priced trades and twin tables (never in git)
    results   research/hunt/r177_ema_regime/results (small JSON / markdown only)

Signal source for every rule: the index spot (CLAUDE.md default); the option is only traded. Plain-English notes,
15 Nov 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
R171 = HERE.parent / "r171_liquidity_gates"
R51 = HERE.parent / "r51"
R44 = HERE.parent / "r44"
for p in (str(ROOT), str(ROOT / "research"), str(R51), str(R171), str(R44), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R177E_OUT", CACHE_ROOT / "r177e")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

UNDS = ("NIFTY", "BANKNIFTY")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
TICK = 0.05

# ------------------------------------------------------------------------------------------------ grid (PREREG 2)
N = 375                  # 1-minute rows 09:15 .. 15:29
BAR = 15                 # minutes a signal bar
NB = 25                  # 15-minute bars a day
NH = 7                   # 1-hour bars a day (the last one is the 15:15-15:29 part bar)
LAST_SIGNAL_BAR = 22     # signal bars 0..22: entries 09:30 .. 15:00
SQ_ROW = 355             # 15:10 square-off (r51cfg.SQ_ROW, the app's)
FILL_SLACK = 3           # the order minute or up to 2 minutes later (R51)
WARM_SESSIONS = 15

EMA_FAST, EMA_SLOW = 9, 21
ADX_N, ADX_MIN = 14, 25.0
ATR_N, ATR_MULT = 14, 2.0
R_TARGET = 2.0           # ALL-2R: target at entry + 2R, R = 2 x ATR
H1_EMA_SLOW, H1_EMA_FAST = 21, 9

# ------------------------------------------------------------------------------------------------ rule sets (PREREG 2)
# id -> dict(adx, trend: None | 'A' | 'B', vwap: None | 'vwap' | 'twap', orange, stop, target, money)
RULES = {
    "PLAIN": dict(adx=False, trend=None, vwap=None, orange=False, stop=False, target=False, money=0),
    "ADX": dict(adx=True, trend=None, vwap=None, orange=False, stop=False, target=False, money=0),
    "TREND": dict(adx=False, trend="A", vwap="vwap", orange=False, stop=False, target=False, money=0),
    "ATR": dict(adx=False, trend=None, vwap=None, orange=False, stop=True, target=False, money=0),
    "ALL": dict(adx=True, trend="A", vwap="vwap", orange=False, stop=True, target=False, money=0),
    "ALL-H1B": dict(adx=True, trend="B", vwap="vwap", orange=False, stop=True, target=False, money=0),
    "ALL-TWAP": dict(adx=True, trend="A", vwap="twap", orange=False, stop=True, target=False, money=0),
    "ALL-OR": dict(adx=True, trend="A", vwap="vwap", orange=True, stop=True, target=False, money=0),
    "ALL-2R": dict(adx=True, trend="A", vwap="vwap", orange=False, stop=True, target=True, money=0),
    "ALL-ITM": dict(adx=True, trend="A", vwap="vwap", orange=False, stop=True, target=False, money=1),
}
RULE_NAME = {
    "PLAIN": "plain 9/21 crossover (no filter)",
    "ADX": "filter 1 alone: ADX(14) > 25",
    "TREND": "filter 2 alone: 1-hour trend + VWAP",
    "ATR": "filter 3 alone: 2 x ATR index stop",
    "ALL": "all three together (the PDF's full rule)",
    "ALL-H1B": "all three, 1-hour trend as EMA 9 > 21",
    "ALL-TWAP": "all three, TWAP instead of the volume-weighted VWAP",
    "ALL-OR": "all three + clearing the opening range",
    "ALL-2R": "all three + a 2R target",
    "ALL-ITM": "all three, 1-ITM strike",
}


def exit_mode(rule: str) -> tuple:
    """(stop, target, money): the part of a rule that decides a trade's exit and strike (the twins' table key)."""
    r = RULES[rule]
    return bool(r["stop"]), bool(r["target"]), int(r["money"])


# ------------------------------------------------------------------------------------------------ cells and tests
IDX_SCOPES = ("NIFTY", "BANKNIFTY", "both")
SIDE_SCOPES = ("C", "P", "both")
SIDE_NAME = {"C": "calls", "P": "puts", "both": "calls + puts"}
LOTS = (1, 2, 3)
TESTS = tuple((r, i, s, n) for r in RULES for i in IDX_SCOPES for s in SIDE_SCOPES for n in LOTS)   # 270

# ------------------------------------------------------------------------------------------------ periods (PREREG 5)
START = date(2024, 5, 6)
DESIGN_END = date(2025, 8, 22)
FINAL_START = date(2025, 8, 25)
END = date(2026, 2, 23)
FOLDS = ((date(2024, 5, 6), date(2024, 8, 31)), (date(2024, 9, 1), date(2024, 12, 31)),
         (date(2025, 1, 1), date(2025, 4, 30)), (date(2025, 5, 1), date(2025, 8, 22)))
PERIODS = ("design", "final", "full")
PERIOD_NAME = {"design": "design (6 May 2024 - 22 Aug 2025)", "final": "last 6 months = final (25 Aug 2025 - 23 Feb 2026)",
               "full": "full period (6 May 2024 - 23 Feb 2026)"}


def span_of(period: str) -> tuple:
    return {"design": (START, DESIGN_END), "final": (FINAL_START, END), "full": (START, END)}[period]


def in_period(d: date, period: str) -> bool:
    lo, hi = span_of(period)
    return lo <= d <= hi


# ------------------------------------------------------------------------------------------------ twins and gates (6, 7)
TWINS = 2000
SEED = 177
GATE_P = 0.05
GATE_Q = 0.10
MIN_FINAL = 40
MAX_DD = 40000.0
WORST_MONTH = -13000.0
N_PRIOR = 4680           # entry files in research/experiments/entries at the branch base (origin/main 694d71f02)
M_TOTAL = N_PRIOR + len(TESTS)   # 4,950


def month_of(d: date) -> str:
    return f"{d.year:04d}-{d.month:02d}"
