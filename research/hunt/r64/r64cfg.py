"""R64 settings (PREREG.md): Boss's 30-60-100 profit lock and -10% stop on the new paper arms, BEFORE vs AFTER.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake; never
              committed
    stores    Donchian and OOV: R51's Amendment A clean stores (R51_CLEAN=1, ~/.cache/iraalgo/r51/clean);
              Solo 2.0: R57's stores (~/.cache/iraalgo/r57). Built by r51data.py / r57data.py, never in git.
    trades    $R64_OUT (default ~/.cache/iraalgo/r64): every priced trade (never in git)
    results   research/hunt/r64/results (small JSON / markdown only)

This module never imports r51cfg or r57cfg (they point the shared R51 engine at different stores, so each arm runs in
its own process: `r64study.py r51` for Donchian + OOV, `r64study.py solo2` for Solo 2.0). The variant table is pure.
"""
from __future__ import annotations

import math
import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R64_OUT", CACHE_ROOT / "r64")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

START = date(2024, 11, 20)              # Boss: nothing before 20 Nov 2024
DESIGN_END = date(2026, 3, 31)
FINAL_START = date(2026, 4, 1)
WF_START = date(2025, 4, 1)             # walk-forward quarters Apr 2025 - Mar 2026 (Solo 2.0: from Jul 2025)
SOLO_WF_START = date(2025, 7, 1)
WF_QUARTERS = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
               (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
BOSS_DAY = date(2026, 10, 16)           # the paper day Boss asked about (reported if the lake has it)

ARMS = ("Donchian", "OOV", "Solo2")
VARIANTS = ("B0", "A1", "A2", "A3", "A4")
VLABEL = {"B0": "BEFORE (current rules)", "A1": "AFTER A1: -10% + ladder, no target, own time exit",
          "A2": "AFTER A2: -10% + ladder, no target, hold to 15:15", "A3": "AFTER A3: -10% stop only (own target/lock)",
          "A4": "AFTER A4: own stop + ladder, no target, own time exit"}

STOP10 = 0.10
LADDER = ((0.30, math.nan), (0.60, 0.30), (1.00, 0.60))     # NaN = breakeven: fill + round-trip charges
SQ_1515 = (15, 15)

# gates (as R51; never relaxed)
TWINS = 500
SEED = 64
BOOT = 2000
GATE_P = 0.05
GATE_Q = 0.10
GATE_MIN_TRADES = 40
BIG = 0.30                              # a big mover: +30% above the fill at some minute before 15:15


def exit_spec(own: dict, variant: str) -> dict:
    """The exits of one variant from an arm's own exits (PREREG section 2). own / result keys: stop, target, steps,
    tmax, sq (hh, mm) - None / () / -1 meaning off. Pure."""
    o = dict(stop=own.get("stop"), target=own.get("target"), steps=tuple(own.get("steps", ())),
             tmax=own.get("tmax", -1), sq=own["sq"])
    if variant == "B0":
        return o
    if variant == "A1":
        return dict(o, stop=STOP10, target=None, steps=LADDER)
    if variant == "A2":
        return dict(o, stop=STOP10, target=None, steps=LADDER, tmax=-1, sq=SQ_1515)
    if variant == "A3":
        return dict(o, stop=STOP10)
    if variant == "A4":
        return dict(o, target=None, steps=LADDER)
    raise ValueError(variant)


# the arms' own exits (PREREG section 1; DonchianArm.EXITS, OovArm.EXITS, r57setups.EXITS)
SOLO_LAYER = ((0.40, 0.10),)
OWN = {
    "Donchian": dict(stop=0.15, target=0.30, tmax=30, sq=(15, 15)),
    "OOV": dict(stop=0.30, steps=SOLO_LAYER, tmax=60, sq=(15, 10)),
    "S1": dict(sq=(14, 30)),
    "S2": dict(stop=0.15, target=0.30, tmax=30, sq=(15, 15)),
    "S3": dict(stop=0.30, steps=SOLO_LAYER, tmax=60, sq=(15, 10)),
    "S4": dict(stop=0.25, target=0.50, tmax=60, steps=SOLO_LAYER, sq=(15, 10)),
}


def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if d >= FINAL_START:
        return "final"
    return None


def quarter_of(d: date) -> int | None:
    for i, (a, b) in enumerate(WF_QUARTERS):
        if a <= d <= b:
            return i
    return None
