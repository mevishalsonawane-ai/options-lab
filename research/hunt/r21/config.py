"""R21 settings (PREREG.md). One switch picks the data: R21_SOURCE=release (default) or lake.

    R21_SOURCE=release  the `research-data` release files in <cache>/raw/research-data (research/brain/adapters/release.py):
                        expiry-day chains for NIFTY (53 weeklies, 9 Apr 2025 - 13 Apr 2026) and BANKNIFTY (11 monthlies,
                        Feb 2025 - Jan 2026), from `expiry_days.parquet`
    R21_SOURCE=lake     the 6-year Dhan lake in $IRAALGO_LAKE (research/brain/adapters/lake.py), once it is published.
                        Re-run: `R21_SOURCE=lake python3 research/hunt/r21/run.py all` (no code change)

The holdout cut dates follow the source: the release keeps R16/R17's cuts; the lake uses its last 12 months.
Caches go to R21_OUT (default <cache>/r21); results and tables go to research/hunt/r21/results.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
if str(ROOT / "research") not in sys.path:
    sys.path.insert(0, str(ROOT / "research"))

from research.brain import config as BC  # noqa: E402

SOURCE = os.environ.get("R21_SOURCE", "release")
CACHE = BC.paths()
OUT = Path(os.environ.get("R21_OUT", CACHE.root / "r21")).expanduser()
RESULTS = HERE / "results"

UNDS = ("NIFTY", "BANKNIFTY")             # the app's NSE expiries in the data (SENSEX / FINNIFTY: no option chains here)
# Release cut dates = R16/R17's. On the lake (6 years) the holdout is the last 12 months of each index (ARCHITECTURE.md L6).
RELEASE_HOLDOUT = {"BANKNIFTY": date(2025, 8, 25), "NIFTY": date(2025, 10, 13)}
LAKE_HOLDOUT_DAYS = 365
FORWARD_ONLY = date(2026, 10, 13)         # Boss's day: never used for any choice (not in the data either)

TWINS = 1000                              # random-time twins per variant
SEED = 21
