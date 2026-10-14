"""The locked holdout of the Data Brain (14 Oct 2026). Fixed before any brain model was fitted.

Every release session up to 13 Apr 2026 (NIFTY's last release session) has been examined by R16 and R18. R18's lake
amendment (research/hunt/r18/PREREG.md, amendment 1; research/hunt/r18/config.py) set, for every lake study:
  design   = sessions up to DESIGN_END (13 Apr 2026)
  embargo  = 14 .. 26 Apr 2026, used by neither
  holdout  = HOLDOUT_START (27 Apr 2026) onwards, both indices (here: every index), read ONCE, by a later step
The brain builds cubes and features for every day (the app needs today's features), but labels are written only for
design days, and every training or evaluation frame passes `assert_design` first. Nothing in this package reads a
label of the embargo or the holdout.
"""
from __future__ import annotations

from datetime import date

import pandas as pd

DESIGN_END = date(2026, 4, 13)
EMBARGO_END = date(2026, 4, 26)
HOLDOUT_START = date(2026, 4, 27)


def is_design(d: date) -> bool:
    return d <= DESIGN_END


def assert_design(ts) -> None:
    """Raise if any timestamp / date is after DESIGN_END (embargo or locked holdout)."""
    s = pd.to_datetime(pd.Series(ts))
    if len(s) and s.max().date() > DESIGN_END:
        raise ValueError(f"refused: data up to {s.max().date()} reaches past the design end {DESIGN_END} "
                         f"(embargo to {EMBARGO_END}, locked holdout from {HOLDOUT_START})")
