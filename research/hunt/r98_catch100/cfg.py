"""R98 settings: "catch the 100+ rupee move in any condition" (Boss, 21 Oct 2026). Pure constants only.

"100 rupees" is read as 100 points of the quote Boss watches (rupees per 10 g): either 100 points on the GOLDM future,
or 100 points on the GOLDM option premium (both are tested; R98 notes N1). Signal source of every rule: the MCX gold
future (GOLDM near future, minute closes); XAUUSD / SILVER are context. Periods, folds and gates: R96 / R97's.
"""
from __future__ import annotations

import os
from pathlib import Path

from research.hunt.r97_gold_reverse import cfg as K97

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser() / "r98"
DESIGN, HOLDOUT, FOLDS = K97.DESIGN, K97.HOLDOUT, K97.FOLDS

BOOK = "GOLDM"
UNITS = 10.0                 # rupees per point of the GOLDM quote (1 lot = 100 g)
TICK = 1.0
TARGET = 100.0               # the 100-point move
STOPS = (50.0, 100.0, 150.0)
HOLDS = (30, 60, 120)        # minutes
FIRST_TOD, LAST_TOD = 9 * 60 + 15, 22 * 60 + 30
SAMPLE_EVERY = 5             # descriptive grid: an entry every 5 minutes, both sides
SEED = 98
