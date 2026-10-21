"""R68 settings (PREREG.md + Amendment 1): Boss's staircase (-10% at +25%, then +5 points of stop per +5 points of
gain) in three readings: on the option's profit (W, WA, WT), on progress toward the target (WP), and with a target that
moves up too (WM).

    export HUNT_CFG=r68cfg HUNT_CFG_DIR=research/hunt/r68
    python research/hunt/r65/r65study.py design | final | report

Trades go to $R68_OUT (default ~/.cache/iraalgo/r68, never in git). Pure, except `before_variant`, which switches
r68engine's moving target on for WM only.
"""
from __future__ import annotations

import math
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r65"))
import r65cfg as R65  # noqa: E402

R64 = R65.R64
NAME = "R68"
OUT = Path(os.environ.get("R68_OUT", R64.CACHE_ROOT / "r68")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"

ARMS = R65.ARMS
OWN = R65.OWN
VARIANTS = ("B0", "A4", "W", "WA", "WT", "WP", "WM")
TRAILS = VARIANTS[2:]
EXAMPLES = TRAILS
REFS = ("N0",)
PLAIN = {"W": "A4", "WA": "A4", "WT": "B0", "WP": "A4", "WM": "A4"}
VLABEL = dict(R65.VLABEL, W="W Boss's staircase (-10% at +25%, +5 pts per +5%), no target",
              WA="WA staircase + A4 ladder", WT="WT staircase + own fixed target",
              WP="WP staircase on progress to the target", WM="WM staircase + target that moves up")

FIRST, STEP, START_LOCK, TOP = 0.25, 0.05, -0.10, 10.0
T_DEFAULT = 0.30                          # the target of an arm without one (PREREG Amendment 1)
MT = (FIRST, STEP)                        # WM: the target rises STEP at each staircase step from FIRST

TWINS = R65.TWINS
SEED = 68
BOOT = R65.BOOT
BIG = R65.BIG


def _lock(x: float) -> float:
    x = round(x, 6)
    return math.nan if abs(x) < 1e-9 else x          # 0 = breakeven + charges (R64's breakeven step)


def staircase() -> tuple:
    """Reading 1: ((+25%, -10%), (+30%, -5%), (+35%, breakeven), (+40%, +5%), ... (+1000%, +965%)). Pure."""
    n = int(round((TOP - FIRST) / STEP)) + 1
    return tuple((round(FIRST + k * STEP, 6), _lock(START_LOCK + k * STEP)) for k in range(n))


def target_of(own: dict) -> float:
    return own["target"] if own.get("target") is not None else T_DEFAULT


def progress_staircase(T: float) -> tuple:
    """Reading 2: triggers at 25%, 30%, 35%, ... of the target T; the stop -10% then +5 points a step, never closer than 5
    points below its trigger. Pure."""
    out, k = [], 0
    while True:
        g = round(T * (FIRST + k * STEP), 6)
        if g > TOP:
            break
        lk = min(START_LOCK + k * STEP, g - 0.05)
        out.append((g, _lock(lk)))
        k += 1
    return tuple(out)


def exit_spec(own: dict, variant: str) -> dict:
    """Every variant keeps the arm's own stop, time exit and square-off. Pure."""
    if variant in ("B0", "A4", "N0"):
        return R65.exit_spec(own, variant)
    o = R65.exit_spec(own, "B0")
    base = tuple(own.get("steps", ()))            # the arm's own +10%-at-+40% lock (never binds above +40%)
    if variant == "W":
        return dict(o, target=None, steps=base + staircase())
    if variant == "WA":
        return dict(o, target=None, steps=base + R64.LADDER + staircase())
    if variant == "WT":
        return dict(o, steps=base + staircase())
    if variant == "WP":
        return dict(o, target=None, steps=base + progress_staircase(target_of(own)))
    if variant == "WM":
        return dict(o, target=target_of(own), steps=base + staircase())
    raise ValueError(variant)


def before_variant(variant: str):
    """Called by r65study before pricing a variant: the moving target is on for WM only."""
    import r68engine
    r68engine.install()
    r68engine.MOVING = MT if variant == "WM" else None
