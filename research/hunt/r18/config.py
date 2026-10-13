"""R18 exits study: the one place to switch data source, periods and costs (PREREG.md).

Re-run on the full Dhan lake later by changing ONE thing: `R18_SOURCE=lake` (or SOURCE below). Everything else
(arms, variants, tests) is the same code. A lake run is a NEW experiment: it gets its own experiment-log entry and its
own locked holdout, read once like this one. That holdout is FRESH (PREREG.md amendment 1): sessions from
LAKE_HOLDOUT_START only, after an embargo following the last date any release study (R16, R18) has examined.
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

SOURCE = os.environ.get("R18_SOURCE", "release")          # "release" (research-data, 2 years) | "lake" (Dhan, 6 years)

# Scratch folder for raw data and intermediate grids (never in git). R18_SCRATCH overrides.
SCRATCH = Path(os.environ.get("R18_SCRATCH", Path.home() / ".cache" / "iraalgo" / "r18")).expanduser()
RAW = Path(os.environ.get("R18_RAW", SCRATCH / "data"))   # the release files (research.brain.adapters.release.fetch)
LAKE = Path(os.environ.get("IRAALGO_LAKE", Path.home() / ".cache" / "iraalgo" / "lake")).expanduser()
GRIDS = SCRATCH / f"grids_{SOURCE}"
HOLD_FLAG = SCRATCH / f"HOLD_READ_{SOURCE}"               # written by holdout.py: a second read is refused
RESULTS = Path(__file__).resolve().parent / "results"

UNDS = ("BANKNIFTY", "NIFTY")

# Locked holdouts.
# release: R16's (last ~6 months of each set). R16 had ALREADY READ these sessions, so for R18 this is a REUSED holdout
#   (PREREG.md amendment 1): its numbers are descriptive, not a clean out-of-sample confirmation.
# lake (amendment 1, 13 Oct 2026): a fresh holdout. Every release session up to LAST_EXAMINED (NIFTY's last release
#   session; BANKNIFTY's ends 23 Feb 2026) has been looked at by R16 and R18, design and holdout alike. The lake's
#   design may use those dates; the sessions in the embargo (LAST_EXAMINED, LAKE_HOLDOUT_START) are used by neither; the
#   holdout is LAKE_HOLDOUT_START onwards, both indices, never sessions anyone has examined.
LAST_EXAMINED = date(2026, 4, 13)
LAKE_HOLDOUT_START = date(2026, 4, 27)          # two calendar weeks (about 8 sessions) of embargo after LAST_EXAMINED
MIN_HOLD_SESSIONS = {"release": 0, "lake": 100}  # the lake holdout read waits until it has this many sessions per index
HOLDOUT = {
    "release": {"BANKNIFTY": date(2025, 8, 25), "NIFTY": date(2025, 10, 13)},
    "lake": {"BANKNIFTY": LAKE_HOLDOUT_START, "NIFTY": LAKE_HOLDOUT_START},
}
DESIGN_LAST = {"release": None, "lake": LAST_EXAMINED}     # None: design = every session before the holdout
# Design walk-forward folds: consecutive blocks of the design period, each judged on its own (no fitting happens in
# R18: every variant's settings are fixed in PREREG.md, so "walk-forward" = the delta must hold block after block).
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
    return HOLDOUT[SOURCE][und], sessions[-1]


def is_design(d: date, und: str) -> bool:
    """A design session: before the holdout and not in the embargo."""
    last = DESIGN_LAST[SOURCE]
    return d < HOLDOUT[SOURCE][und] and (last is None or d <= last)
