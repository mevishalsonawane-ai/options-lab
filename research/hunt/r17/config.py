"""R17 exits study: the one place to switch data source, periods and costs (PREREG.md).

Re-run on the full Dhan lake later by changing ONE thing: `R17_SOURCE=lake` (or SOURCE below). Everything else
(arms, variants, tests) is the same code. A lake run is a NEW experiment: it gets its own experiment-log entry and its
own locked holdout (the last 12 months of the lake, ARCHITECTURE.md L6), read once like this one.
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

SOURCE = os.environ.get("R17_SOURCE", "release")          # "release" (research-data, 2 years) | "lake" (Dhan, 6 years)

# Scratch folder for raw data and intermediate grids (never in git). R17_SCRATCH overrides.
SCRATCH = Path(os.environ.get("R17_SCRATCH", Path.home() / ".cache" / "iraalgo" / "r17")).expanduser()
RAW = Path(os.environ.get("R17_RAW", SCRATCH / "data"))   # the release files (research.brain.adapters.release.fetch)
LAKE = Path(os.environ.get("IRAALGO_LAKE", Path.home() / ".cache" / "iraalgo" / "lake")).expanduser()
GRIDS = SCRATCH / f"grids_{SOURCE}"
HOLD_FLAG = SCRATCH / f"HOLD_READ_{SOURCE}"               # written by holdout.py: a second read is refused
RESULTS = Path(__file__).resolve().parent / "results"

UNDS = ("BANKNIFTY", "NIFTY")

# Locked holdouts. release: R16's (last ~6 months of each set). lake: the last 12 months of whatever the lake holds.
HOLDOUT = {
    "release": {"BANKNIFTY": date(2025, 8, 25), "NIFTY": date(2025, 10, 13)},
    "lake": None,          # filled at run time: last session - 365 days (see periods())
}
# Design walk-forward folds: consecutive blocks of the design period, each judged on its own (no fitting happens in
# R17: every variant's settings are fixed in PREREG.md, so "walk-forward" = the delta must hold block after block).
N_FOLDS = 4

# Costs (as R12): app paper fills (+5 bps buy, -5 bps market sell, -10 bps resting stop), app charges (STT 0.15%),
# plus the h24 real half-spread 0.16% on entry and on exit for NIFTY / BANKNIFTY. Stress: 2x spread.
HALF_SPREAD = {"NIFTY": 0.0016, "BANKNIFTY": 0.0016}
STRESS_SPREAD = 2.0

# Lots (exchange lot in force on the day; obuy.config.LOT_SCHEDULE). 1 lot per trade, as the arms trade on paper.
BH_Q = 0.10                # design candidate bar (BH over every arm x variant tried)
HOLD_P = 0.10              # holdout bar (one-sided, BH over the candidates read)
SEED = 17


def periods(und: str, sessions: list[date]) -> tuple[date, date]:
    """(first holdout session, last session) for this source."""
    h = HOLDOUT[SOURCE]
    if h is None:
        last = sessions[-1]
        return date.fromordinal(last.toordinal() - 365), last
    return h[und], sessions[-1]
