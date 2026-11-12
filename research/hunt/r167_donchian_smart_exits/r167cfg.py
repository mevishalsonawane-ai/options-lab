"""R167 settings (PREREG.md + Amendment A): smart (adaptive) exits on the two Donchian arms, 1-3 lots.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m (5 indices + INDIAVIX), index_daily,
              options_rolling_1m WEEK (with oi)
    stores    R51's Amendment A clean stores (R51_CLEAN=1): `R51_CLEAN=1 python research/hunt/r51/r51data.py build`
    cache     $R167_OUT (default ~/.cache/iraalgo/r167): day measurements, OI, priced trades (never in git)
    results   research/hunt/r167_donchian_smart_exits/results (small JSON / CSV / markdown only)

Pure: periods, rules, versions, tests and gates. Importing it puts R152's folder (and through it R112 / R106 / R101 /
R51) on the path. Plain-English notes, 12 Nov 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
os.environ["R51_CLEAN"] = "1"
for _d in ("r152_donchian_2k",):
    p = str(HERE.parent / _d)
    if p not in sys.path:
        sys.path.insert(0, p)
import r152cfg as K152  # noqa: E402  (R152's periods, B0 parity and the R112 / R106 / R101 / R51 paths)

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R167_OUT", CACHE_ROOT / "r167")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

# periods (PREREG section 4): R152's
START, DESIGN_END, WF_START, WF_Q = K152.START, K152.DESIGN_END, K152.WF_START, K152.WF_Q
FINAL_START, FINAL_END = K152.FINAL_START, K152.FINAL_END
ARMS = ("R72", "R51")
ARM_NAME = K152.ARM_NAME
PUBLISHED = {k: v for k, v in K152.PUBLISHED.items() if k[0] in ARMS}     # B0 at 1 lot (asserted)
LOTS = (1, 2, 3)

# rules (PREREG section 3 + Amendment A). 'free' = what the F version replaces in today's exits:
#   'stop+time' -> the -15% stop and the 30-minute time stop; 'time' -> the 30-minute time stop; 'flowtime' -> the time
#   stop is replaced by FLOW's extended one; 'item' -> CTX-F prices with the chosen F item's own exits.
RULES = {
    "SR": dict(free="stop+time", label="support / resistance stop and target"),
    "VOLX": dict(free="stop+time", label="index ATR Chandelier (3 ATR trail, 4 ATR target)"),
    "VOLP": dict(free="stop+time", label="premium Chandelier (3 x the option's ATR)"),
    "FLOW": dict(free="flowtime", label="volume spike / OI build-up against -> tight trail"),
    "MOM": dict(free="time", label="1-minute Supertrend turns against"),
    "CTX": dict(free="item", label="exit rule chosen by regime (VIX x trend), design-trained"),
    "ML": dict(free="time", label="walk-forward exit model (P favourable move < 25th pct)"),
    "G1": dict(free=None, label="once +Rs 1,500, lock breakeven (net Rs 0)"),
    "G2": dict(free=None, label="once +Rs 1,500, floor +Rs 500 trailing Rs 1,000 below the peak"),
    "G3": dict(free=None, label="once +Rs 1,500, 3-minute index-low trail"),
}
GIVEBACK = ("G1", "G2", "G3")
LEARNED = ("CTX", "ML")


def versions(rule: str) -> tuple:
    """T (tighten-only, live-eligible after shadow) and F (free, paper / research only); the giveback rules are T only."""
    return ("T",) if RULES[rule]["free"] is None else ("T", "F")


VARIANTS = tuple((r, v) for r in RULES for v in versions(r))                 # 17
TESTS = tuple((a, r, v, n) for a in ARMS for r, v in VARIANTS for n in LOTS)  # 102
CTX_MENU_T = ("B0", "SR", "VOLX", "VOLP", "FLOW", "MOM")
CTX_MENU_F = CTX_MENU_T

VERSION_LABEL = {"T": "tighten-only (live-eligible after a shadow phase)",
                 "F": "free (may widen): paper / research only"}

# twins and gates (PREREG sections 6, 7)
TWINS = 500
SEED = 167
GATE_Q = 0.10
MIN_FINAL = 40
WIN_UP = 0.05
# earlier tests taken as p = 1 (PREREG section 7 / Amendment A)
PRIOR_TESTS = {"R160's m (through R160)": 2164, "R161": 168, "R162": 102, "R163": 8, "R164": 18, "R165": 78,
               "R166": 24}
N_PRIOR = sum(PRIOR_TESTS.values())  # 2,562
M_TOTAL = N_PRIOR + len(TESTS)       # 2,664


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    return None


def train_window(d: date, scored: str) -> tuple:
    """The training days (lo, hi) of the CTX / ML model that scores day d. scored 'design' -> all design days
    (in-sample, labelled); 'wf' -> the design days before d's walk-forward quarter; 'final' -> all design days."""
    if scored == "wf":
        q = wf_quarter(d)
        if q is None:
            raise ValueError(f"{d} is not in a walk-forward quarter")
        return (START, q[0] - timedelta(days=1))
    return (START, DESIGN_END)


def wf_quarter(d: date):
    """The walk-forward quarter (start, end) holding d, or None."""
    for a, b in WF_Q:
        if a <= d <= b:
            return (a, b)
    return None
