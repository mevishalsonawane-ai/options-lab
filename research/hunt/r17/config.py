"""R17 settings (PREREG.md). One switch picks the data: R17_SOURCE=release (default) or lake.

    R17_SOURCE=release  the `research-data` release files in <cache>/raw/research-data (research/brain/adapters/release.py)
    R17_SOURCE=lake     the Dhan lake in $IRAALGO_LAKE (research/brain/adapters/lake.py), once it is published

The holdout cut dates follow the source: the release keeps R16's cuts; the lake uses its last 12 months.
Outputs (caches, the holdout flag) go to R17_OUT (default <cache>/r17); results and tables go to research/hunt/r17/results.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "research"))

from research.brain import config as BC  # noqa: E402

SOURCE = os.environ.get("R17_SOURCE", "release")
CACHE = BC.paths()
OUT = Path(os.environ.get("R17_OUT", CACHE.root / "r17")).expanduser()
RESULTS = HERE / "results"

# Release cut dates = R16's. On the lake (6 years) the holdout is the last 12 months of each index (ARCHITECTURE.md L6).
RELEASE_HOLDOUT = {"BANKNIFTY": date(2025, 8, 25), "NIFTY": date(2025, 10, 13)}
LAKE_HOLDOUT_DAYS = 365

WARMUP_SESSIONS = 60        # the expected-range model predicts nothing before this many sessions
DANGER_WARMUP = 120         # the danger meter's trailing window and warm-up
EMBARGO = 5                 # sessions between the training rows and the month predicted

STOP_FIXED = 40.0           # ORB family premium stop (points)
X_GRID = [0.10, 0.15, 0.20, 0.25, 0.30, 0.40, 0.50]
K_GRID = [0.5, 0.75, 1.0, 1.25, 1.5]
FORMS = ["smart", "tight"]
TWINS = 1000
SEED = 17


def adapter():
    if SOURCE == "lake":
        from research.brain.adapters.lake import LakeAdapter
        return LakeAdapter(CACHE.lake)
    from research.brain.adapters.release import ReleaseAdapter
    return ReleaseAdapter(CACHE.raw_release)


def holdout_start(und: str, sessions: list[date]) -> date:
    if SOURCE == "lake":
        last = sessions[-1]
        return date(last.year - 1, last.month, last.day) if not (last.month == 2 and last.day == 29) else date(last.year - 1, 3, 1)
    return RELEASE_HOLDOUT[und]
