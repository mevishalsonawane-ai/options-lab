"""R22 settings (PREREG.md part A1). One switch picks the data: R22_SOURCE=release (default) or lake.

    R22_SOURCE=release  the `research-data` release (BANKNIFTY / NIFTY 1-minute index + options, FINNIFTY index)
    R22_SOURCE=lake     the 6-year Dhan lake through R17's adapter, once it is published; holdout = last 12 months

The switch is passed on to R17's loader (R17_SOURCE), so the day caches, fills and charges are R17's. Outputs that hold
minute data or per-event rows stay in the cache (<cache>/r22 or r22_lake), never in git; tables go to results/ (release)
or results/lake/. 13 Oct 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date

SOURCE = os.environ.get("R22_SOURCE", "release")
if SOURCE not in ("release", "lake"):
    raise SystemExit(f"R22_SOURCE must be release or lake, not {SOURCE!r}")
os.environ["R17_SOURCE"] = SOURCE

HERE = os.path.dirname(os.path.abspath(__file__))
for p in (os.path.join(HERE, "..", "r17"), os.path.join(HERE, "..", "r20"), HERE):
    p = os.path.abspath(p)
    if p not in sys.path:
        sys.path.insert(0, p)

import config as K17  # noqa: E402  (R17: paths, adapter, holdout_start; puts the repo on sys.path)

RESULTS = os.path.join(HERE, "results") if SOURCE == "release" else os.path.join(HERE, "results", "lake")
OUT = os.path.join(str(K17.CACHE.root), "r22" if SOURCE == "release" else "r22_lake")
FLAG = os.path.join(OUT, "HOLDOUT_READ")
RELEASE_HOLDOUT = {"BANKNIFTY": date(2025, 8, 25), "NIFTY": date(2025, 10, 13), "FINNIFTY": date(2025, 10, 13)}
TRADE_UNDS = ("BANKNIFTY", "NIFTY")
EVENT_UNDS = ("BANKNIFTY", "NIFTY", "FINNIFTY")
SEED = 2022


def holdout_start(und: str, sessions: list) -> date:
    """The first holdout session date: R16-R20c's cuts on the release, the last 12 months on the lake."""
    if SOURCE == "release":
        return RELEASE_HOLDOUT[und]
    return K17.holdout_start(und, sessions)
