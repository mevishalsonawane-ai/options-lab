"""R66 settings (PREREG.md): Boss's "stop to +5% once the option is +25%" on top of today's A4 ladder.

Run by R65's study and report with this config (HUNT_CFG=r66cfg):

    HUNT_CFG=r66cfg python research/hunt/r65/r65study.py design
    HUNT_CFG=r66cfg python research/hunt/r65/r65study.py final       # the ONE final read (refuses a second)
    HUNT_CFG=r66cfg python research/hunt/r65/r65study.py report

Trades go to $R66_OUT (default ~/.cache/iraalgo/r66, never in git); results/ holds small JSON / markdown. Pure.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r65"))
import r65cfg as R65  # noqa: E402

R64 = R65.R64
NAME = "R66"
OUT = Path(os.environ.get("R66_OUT", R64.CACHE_ROOT / "r66")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

ARMS = R65.ARMS
OWN = R65.OWN
VARIANTS = ("B0", "A4", "L25", "L20", "L30")
TRAILS = VARIANTS[2:]
EXAMPLES = TRAILS
REFS = ("N0",)
PLAIN = {v: "A4" for v in TRAILS}         # the plain counterpart: the same ladder without the new step
TRIGGER = {"L25": 0.25, "L20": 0.20, "L30": 0.30}
KEEP = 0.05                                # the stop rises to +5% above the fill
VLABEL = dict(R65.VLABEL, L25="L25 Boss: A4 + stop +5% once +25%", L20="L20 A4 + stop +5% once +20%",
              L30="L30 A4 + stop +5% once +30%")

TWINS = R65.TWINS
SEED = 66
BOOT = R65.BOOT
BIG = R65.BIG


def exit_spec(own: dict, variant: str) -> dict:
    """B0 / A4 / N0 as R65; L*: A4 plus one lock step (trigger, +5%). Pure."""
    if variant in TRIGGER:
        a4 = R65.exit_spec(own, "A4")
        return dict(a4, steps=a4["steps"] + ((TRIGGER[variant], KEEP),))
    return R65.exit_spec(own, variant)
