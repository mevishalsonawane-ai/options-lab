"""R202 / R203 settings (PREREG.md, PREREG_R203.md): extra exits and loss-cutting ideas for R72 Breadth Donchian,
R51 Donchian 20 and Liquidity 15+5, on top of the rules they run today (V5). Plain-English notes, 20 Nov 2026.

    lake      ~/.cache/iraalgo/r202/lake (r202data.py: the dhan-data branch in the lake's layout, to 5 Oct 2026)
    stores    ~/.cache/iraalgo/r202/r51/clean (R51's Amendment A clean stores, built here)
    caches    ~/.cache/iraalgo/r202 (never git)
    results   research/hunt/r202_trend_exit/results (small JSON / markdown only)

Importing this module sets the environment the R176 / R106 / R51 stack reads; import it before any of them.
Signal source of every rule: the index. Options are only bought.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
HUNT = HERE.parent
for p in (str(ROOT), str(ROOT / "research"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

HOME = Path.home() / ".cache" / "iraalgo"
OUT = Path(os.environ.get("R202_OUT", HOME / "r202")).expanduser()
LAKE = Path(os.environ.get("R202_LAKE", OUT / "lake")).expanduser()
RESULTS = HERE / "results"


def set_env():
    """The stack's environment (before it is imported)."""
    os.environ["IRAALGO_LAKE"] = str(LAKE)
    os.environ["IRAALGO_CACHE"] = str(OUT / "cache")
    os.environ["R51_OUT"] = str(OUT / "r51")
    os.environ["R51_CLEAN"] = "1"
    os.environ.pop("R51_STALE", None)
    os.environ["R176_POST"] = "0"


# warm-up, first option day, last full session of dhan-data
STORE = (date(2024, 9, 1), date(2024, 11, 20), date(2026, 10, 5))
