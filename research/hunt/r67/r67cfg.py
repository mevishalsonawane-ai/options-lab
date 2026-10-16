"""R67 settings (PREREG.md): Boss's "stop from -15% to -10% once the option is +25%" on top of the A4 ladder.

Run by R65's study and report with this config:

    export HUNT_CFG=r67cfg HUNT_CFG_DIR=research/hunt/r67
    python research/hunt/r65/r65study.py design
    python research/hunt/r65/r65study.py final       # the ONE final read (refuses a second)
    python research/hunt/r65/r65study.py report

Trades go to $R67_OUT (default ~/.cache/iraalgo/r67, never in git); results/ holds small JSON / markdown. Pure.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r65"))
import r65cfg as R65  # noqa: E402

R64 = R65.R64
NAME = "R67"
OUT = Path(os.environ.get("R67_OUT", R64.CACHE_ROOT / "r67")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

ARMS = R65.ARMS
OWN = R65.OWN
VARIANTS = ("B0", "A4", "M25", "M20", "M15")
TRAILS = VARIANTS[2:]
EXAMPLES = TRAILS
REFS = ("N0",)
PLAIN = {v: "A4" for v in TRAILS}         # the plain counterpart: the same ladder without the new step
TRIGGER = {"M25": 0.25, "M20": 0.20, "M15": 0.15}
KEEP = -0.10                               # the stop rises to 10% below the fill (-10%)
VLABEL = dict(R65.VLABEL, M25="M25 Boss: A4 + stop -10% once +25%", M20="M20 A4 + stop -10% once +20%",
              M15="M15 A4 + stop -10% once +15%")

TWINS = R65.TWINS
SEED = 67
BOOT = R65.BOOT
BIG = R65.BIG


def exit_spec(own: dict, variant: str) -> dict:
    """B0 / A4 / N0 as R65; M*: A4 plus one lock step (trigger, -10%). Pure."""
    if variant in TRIGGER:
        a4 = R65.exit_spec(own, "A4")
        return dict(a4, steps=a4["steps"] + ((TRIGGER[variant], KEEP),))
    return R65.exit_spec(own, variant)
