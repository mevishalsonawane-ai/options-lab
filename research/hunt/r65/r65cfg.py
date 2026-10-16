"""R65 settings (PREREG.md): Boss's step trail on the premium (+10% -> stop +5%, +15% -> +10%, ...) on the new paper
arms, against the research rules (B0) and the app's current R64 A4 lock.

    stores    as R64 (R51's Amendment A clean stores for Donchian / OOV, R57's for Solo 2.0); never in git
    trades    $R65_OUT (default ~/.cache/iraalgo/r65): every priced trade (never in git)
    results   research/hunt/r65/results (small JSON / markdown only)

Pure: the arms' own exits and the dates come from hunt/r64/r64cfg.py; this module only adds the trail variants.
"""
from __future__ import annotations

import math
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r64"))
import r64cfg as R64  # noqa: E402

OUT = Path(os.environ.get("R65_OUT", R64.CACHE_ROOT / "r65")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

NAME = "R65"
ARMS = R64.ARMS
OWN = R64.OWN
VARIANTS = ("B0", "A4", "S5", "S5n", "S75", "S75n", "S10", "S10n", "T5", "T5n")
TRAILS = VARIANTS[2:]
EXAMPLES = ("S5", "S5n", "S10", "T5")    # report: the variants whose biggest give-up / save are shown
REFS = ("N0",)                           # plain counterpart of the "n" variants: own rules, no target, no trail
PLAIN = {"S5": "B0", "S75": "B0", "S10": "B0", "T5": "B0", "S5n": "N0", "S75n": "N0", "S10n": "N0", "T5n": "N0"}
VLABEL = {"B0": "B0 research rules", "A4": "A4 app today (30/60/100 ladder, no target)",
          "S5": "S5 Boss: +5% at +10%, trail 5 pts, target kept", "S5n": "S5n Boss's rule, no target",
          "S75": "S75 +2.5% at +10%, trail 7.5 pts, target kept", "S75n": "S75n trail 7.5 pts, no target",
          "S10": "S10 breakeven at +10%, trail 10 pts, target kept", "S10n": "S10n trail 10 pts, no target",
          "T5": "T5 -5% stop moved up 5 pts below best, target kept", "T5n": "T5n -5% stop moved up, no target",
          "N0": "N0 own rules, no target (reference)"}

STEP = 0.05                              # Boss: steps of 5 points of the entry price
FIRST = 0.10                             # first step at +10% (T5: +5%)
TOP = 10.0                               # steps run to +1000% (PREREG section 2)
GAP = {"S5": 0.05, "S5n": 0.05, "S75": 0.075, "S75n": 0.075, "S10": 0.10, "S10n": 0.10, "T5": 0.05, "T5n": 0.05}
T5_STOP = 0.05

TWINS = 500
SEED = 65
BOOT = 2000
BIG = 0.30


def trail_steps(gap: float, first: float = FIRST) -> tuple:
    """((best gain, locked gain), ...) for a step trail: from `first` every 5 points, locked = best - gap; a locked gain
    of 0 or less is breakeven + charges (NaN, R64's breakeven step). Pure."""
    out = []
    n = int(round((TOP - first) / STEP)) + 1
    for k in range(n):
        g = round(first + k * STEP, 6)
        lk = round(g - gap, 6)
        out.append((g, math.nan if lk <= 1e-9 else lk))
    return tuple(out)


def exit_spec(own: dict, variant: str) -> dict:
    """The exits of one variant from an arm's own exits (keys: stop, target, steps, tmax, sq; None / () / -1 = off).
    Every variant keeps the arm's own time exit and square-off. Pure."""
    o = dict(stop=own.get("stop"), target=own.get("target"), steps=tuple(own.get("steps", ())),
             tmax=own.get("tmax", -1), sq=own["sq"])
    if variant == "B0":
        return o
    if variant == "A4":
        return R64.exit_spec(own, "A4")
    if variant == "N0":
        return dict(o, target=None)
    if variant in GAP:
        r = dict(o, steps=o["steps"] + trail_steps(GAP[variant], 0.05 if variant.startswith("T") else FIRST))
        if variant.startswith("T"):
            r["stop"] = T5_STOP
        if variant.endswith("n"):
            r["target"] = None
        return r
    raise ValueError(variant)
