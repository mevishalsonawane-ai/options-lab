"""R69 settings (PREREG.md): Boss's "+5% stop at +25%, then +1 point of stop per +1 point of gain" on top of A4.

    export HUNT_CFG=r69cfg HUNT_CFG_DIR=research/hunt/r69
    python research/hunt/r65/r65study.py design | final | report

Trades go to $R69_OUT (default ~/.cache/iraalgo/r69, never in git). Pure.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r65"))
import r65cfg as R65  # noqa: E402

R64 = R65.R64
NAME = "R69"
OUT = Path(os.environ.get("R69_OUT", R64.CACHE_ROOT / "r69")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

ARMS = R65.ARMS
OWN = R65.OWN
VARIANTS = ("B0", "A4", "X25", "X20", "X30")
TRAILS = VARIANTS[2:]
EXAMPLES = TRAILS
REFS = ("N0",)
PLAIN = {v: "A4" for v in TRAILS}
START = {"X25": 0.25, "X20": 0.20, "X30": 0.30}
KEEP0 = 0.05                              # the first stop: +5%
STEP = 0.01                               # then +1 point of stop per +1 point of gain
TOP = 10.0
VLABEL = dict(R65.VLABEL, X25="X25 Boss: +5% at +25%, then +1 per +1", X20="X20 +5% at +20%, then +1 per +1",
              X30="X30 +5% at +30%, then +1 per +1")

TWINS = R65.TWINS
SEED = 69
BOOT = R65.BOOT
BIG = R65.BIG


def trail(start: float) -> tuple:
    """((start, +5%), (start + 1 pt, +6%), ... up to +1000%): the stop sits (start - 5%) below the best. Pure."""
    n = int(round((TOP - start) / STEP)) + 1
    return tuple((round(start + k * STEP, 6), round(KEEP0 + k * STEP, 6)) for k in range(n))


def exit_spec(own: dict, variant: str) -> dict:
    """B0 / A4 / N0 as R65; X*: A4 plus the 1-point trail from its start. Pure."""
    if variant in START:
        a4 = R65.exit_spec(own, "A4")
        return dict(a4, steps=a4["steps"] + trail(START[variant]))
    return R65.exit_spec(own, variant)
