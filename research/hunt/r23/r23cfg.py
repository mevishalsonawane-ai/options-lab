"""R23 settings (PREREG.md part A1). One switch picks the data: R23_SOURCE=release (default) or lake.

    R23_SOURCE=release  the `research-data` release; EVERY session is design data (its holdout was used up by R16-R22)
    R23_SOURCE=lake     the 6-year Dhan lake through R17's adapter, once published: design up to 2026-04-13, embargo
                        2026-04-14 .. 2026-04-26, a FRESH holdout from 2026-04-27 (R18 PREREG amendment 1), read once

The switch is passed on to R22 / R17 (R22_SOURCE, R17_SOURCE), so the day caches, fills and charges are theirs. Per-day
rows (anatomy, flow) stay in the cache (<cache>/r23 or r23_lake), never in git; tables and the baselines' / finalists'
per-trade lists (no market data) go to results/ (release) or results/lake/.
13 Oct 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date

SOURCE = os.environ.get("R23_SOURCE", "release")
if SOURCE not in ("release", "lake"):
    raise SystemExit(f"R23_SOURCE must be release or lake, not {SOURCE!r}")
os.environ["R22_SOURCE"] = SOURCE
os.environ["R17_SOURCE"] = SOURCE

HERE = os.path.dirname(os.path.abspath(__file__))
for p in (os.path.join(HERE, "..", "r17"), os.path.join(HERE, "..", "r20"), os.path.join(HERE, "..", "r22"), HERE):
    p = os.path.abspath(p)
    if p not in sys.path:
        sys.path.insert(0, p)

import r22cfg as CFG22  # noqa: E402  (puts R17 / R20 on sys.path; R17's config sets the repo root)

RESULTS = os.path.join(HERE, "results") if SOURCE == "release" else os.path.join(HERE, "results", "lake")
OUT = os.path.join(str(CFG22.K17.CACHE.root), "r23" if SOURCE == "release" else "r23_lake")
LAKE_FLAG = os.path.join(OUT, "LAKE_HOLDOUT_READ")
TRADE_UNDS = ("BANKNIFTY", "NIFTY")
EVENT_UNDS = ("BANKNIFTY", "NIFTY", "FINNIFTY")
SEED = 2023
N_BLOCKS = 4

# The lake's fresh holdout (R18 PREREG amendment 1): every release session up to LAST_EXAMINED has been looked at.
LAST_EXAMINED = date(2026, 4, 13)
LAKE_HOLDOUT_START = date(2026, 4, 27)
MIN_HOLD_SESSIONS = 100


def period_of(d: date) -> str:
    """'design', 'embargo' or 'holdout' for one session date under the current source."""
    if SOURCE == "release":
        return "design"                        # the release holdout is used up: all release sessions are design data
    if d <= LAST_EXAMINED:
        return "design"
    if d < LAKE_HOLDOUT_START:
        return "embargo"
    return "holdout"
