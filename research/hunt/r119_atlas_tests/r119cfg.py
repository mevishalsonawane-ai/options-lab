"""R119 settings (PREREG.md, 27 Oct 2026): R118's stable expiry-day patterns as six fixed strategies.

    days     R98_OUT (default ~/.cache/iraalgo/r98): `R98_LAKE=<lake> python3 -I research/hunt/r98/r98data.py build`
    caches   R119_OUT (default ~/.cache/iraalgo/r119): trade lists (never in git)
    results  research/hunt/r119_atlas_tests/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
ROOT = HUNT.parents[1]
CACHE = Path(os.environ.get("IRAALGO_CACHE", "~/.cache/iraalgo")).expanduser()
os.environ.setdefault("R98_LAKE", str(CACHE / "lake"))
os.environ.setdefault("R98_OUT", str(CACHE / "r98"))
OUT = Path(os.environ.get("R119_OUT", CACHE / "r119")).expanduser()
RESULTS = HERE / "results"
for p in (HUNT / "r98", HUNT / "r55", HUNT / "r118_expiry_atlas", HUNT / "r117_zero_hero_lock", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))


def row(hh: int, mm: int) -> int:
    """The bar that STARTS at hh:mm (09:15 = 0)."""
    return hh * 60 + mm - (9 * 60 + 15)


ROW_0930, ROW_1200, ROW_1500 = row(9, 30), row(12, 0), row(15, 0)
ROW_1505, ROW_1520 = row(15, 5), row(15, 20)
FILL_BARS = 3
MIN_PX = 0.05                       # under this at exit: worthless, no order
WING = 2.0                          # P1 / P2: wings 2 straddles out
LONGSHOT_P, LONGSHOT_MIN_PX, LONGSHOT_WING = 0.03, 1.0, 2
CALL_DIST = 1.25
FADE_MARKS = list(range(row(11, 0), row(14, 30) + 1, 15))
FADE_K, FADE_HOLD = 1.5, 15
TWIN_A, TWIN_B = row(9, 30), row(14, 30)
TWIN3_A, TWIN3_B = row(11, 0), row(14, 40)

MARGIN_PCT, ELM_PCT, ELM_FROM = 0.025, 0.02, date(2024, 11, 20)     # R103 Amendment 1

STRATS = {
    "P1": dict(name="iron fly at 09:30 (wings 2 straddles), out 15:05", source="underlying (ATM strike from the index)"),
    "P2": dict(name="iron fly at 12:00 (wings 2 straddles), out 15:05", source="underlying (ATM strike from the index)"),
    "P3": dict(name="long ATM straddle 15:00 -> 15:20 (past the app's 15:05)", source="underlying (ATM strike from the index)"),
    "P4": dict(name="short longshot spreads at 12:00 (p_itm ~3%, 2-strike wing), out 15:05",
               source="option chain: Black-76 odds from the option's own price (NON-DEFAULT, labelled)"),
    "P5": dict(name="fade 15-minute moves >= 1.5 sd with the ATM option, 15 minutes",
               source="underlying (the index's 15-minute move; default)"),
    "P6": dict(name="buy the OTM call 1.25 straddles out at 12:00, out 15:05",
               source="underlying (strike from the index and its straddle)"),
}

DESIGN_END = date(2023, 12, 31)
WF_START, WF_END = date(2024, 1, 1), date(2025, 12, 31)
FINAL_START, FINAL_END = date(2026, 1, 1), date(2026, 10, 19)
PERIODS = ("design", "wf", "final")


def period(d: date) -> str | None:
    if d <= DESIGN_END:
        return "design"
    if WF_START <= d <= WF_END:
        return "wf"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    return None


TWINS, SEED = 2000, 119
GATE_P, GATE_Q, MIN_FINAL = 0.05, 0.10, 40
MONTH_STOP = -13_000.0
ENTRIES_LAST_DAY = "2026-10-27"
