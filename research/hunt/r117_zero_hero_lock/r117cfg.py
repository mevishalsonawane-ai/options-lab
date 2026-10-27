"""R117 settings (PREREG.md, 27 Oct 2026): profit locks on the EXISTING FT3 and Hero tickets. Entries are not re-searched.

    lake       $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): copied with rclone from gdrive:IraAlgo-data-lake (never in git)
    FT3 days   R98_OUT (default ~/.cache/iraalgo/r98): `R98_LAKE=<lake> python3 -I research/hunt/r98/r98data.py build`
    Hero days  R54_OUT (default ~/.cache/iraalgo/r54x): `python3 research/hunt/r54/r54data.py build` (all lake years)
    caches     R117_OUT (default ~/.cache/iraalgo/r117): ticket lists (never in git)
    results    research/hunt/r117_zero_hero_lock/results (small JSON / markdown only)

Signal source: NOT the default (CLAUDE.md). FT3 picks from the option chain at the mark (the option's own close, its
Black-76 IV against the ATM's, its distance in straddles); Hero picks by the option's own price band. Both are labelled
option-price rules, on by Boss's choice. The locks read the held option's premium (allowed for stops and locks).
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
os.environ.setdefault("R54_OUT", str(CACHE / "r54x"))
OUT = Path(os.environ.get("R117_OUT", CACHE / "r117")).expanduser()
RESULTS = HERE / "results"

# R98 first (ft3_app, r98data, r98lib, hero2), then R55 (which puts R54 / R50 / R43 / R25 on the path). Neither family's
# folder holds a module with the other's names, and HERE goes first so R117's own modules win.
for p in (HUNT / "r98", HUNT / "r55", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

SIGNAL_SOURCE = ("option-price rule (NOT the default; on by Boss's choice): FT3 reads the option chain at the mark, "
                 "Hero the option's own price band; the locks read the held option's premium")

# periods (PREREG section 1)
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


# the rules (PREREG section 2). kind, parameters. 'm' = the fill price, 'best' = the best close since the fill.
RULES = {
    "L1": dict(kind="half_trail", at=3.0, g=0.50, name="half at 3x, trail the rest 50%"),
    "L2": dict(kind="half_trail", at=2.0, g=0.50, name="half at 2x, trail the rest 50%"),
    "L3": dict(kind="step", tiers=(4.0, 8.0, 16.0, 32.0), name="step lock: 2x once 4x, 4x once 8x, 8x once 16x, 16x once 32x"),
    "L4": dict(kind="armed_trail", arm=2.0, g=0.40, name="trail 40% from the peak, armed at 2x"),
    "L5": dict(kind="armed_trail", arm=2.0, g=0.50, name="trail 50% from the peak, armed at 2x"),
    "L6": dict(kind="time_lock", at=2.0, g=0.30, name="from 14:00, once 2x: trail 30% from the peak"),
    "L7": dict(kind="stop_then_wide", stop=0.25, arm=2.0, g=0.50, name="25% trail from the fill, widened to 50% once 2x"),
}
BASE = {
    "HOLD": dict(kind="hold", name="hold to 15:05"),
    "T0": dict(kind="trail", g=0.25, name="25% trail from the fill (R55 T0)"),
}
ALL_EXITS = {**BASE, **RULES}
FAMILIES = ("FT3", "HERO")
FAMILY_BASE = {"FT3": "HOLD", "HERO": "T0"}               # each arm's own exit today
FAMILY_REF = {"FT3": "T0", "HERO": "HOLD"}                # shown for reference only
FAMILY_RULES = {"FT3": ("L1", "L2", "L3", "L4", "L5", "L6", "L7"), "HERO": ("L3", "L4", "L5", "L6", "L7")}
TESTS = [(f, r) for f in FAMILIES for r in FAMILY_RULES[f]]   # 12

LATE_FROM = (14, 0)

# statistics (PREREG sections 3-4; never relaxed)
TWINS = 2000
BOOT = 5000
SEED = 117
GATE_P = 0.05
GATE_Q = 0.10
MIN_FINAL = 40
ENTRIES_LAST_DAY = "2026-10-27"
ZERO_HERO = ("R21", "R22", "R23", "R24", "R25", "R26", "R34", "R35", "R36", "R37", "R39", "R43", "R50", "R54", "R55",
             "R98")

# parity anchors (PREREG section 1)
FT3_PARITY = (478, 1805.25)                               # tickets, mean Rs (FORWARD_TEST.md, ft3_app.md)
HERO_PARITY = {"design": (248, -14507), "final": (107, 1559)}   # R55 12:30 / 15:05 / T0
