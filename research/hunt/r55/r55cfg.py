"""R55 settings (PREREG.md, 15 Oct 2026): the Rs 1,000 hero ticket with the 25% trail armed from the fill (T0) or only
after 2x / 3x / 5x (T2 / T3 / T5), or held (H), out at 15:05, 15:15 or held to expiry settlement.

    lake       $IRAALGO_LAKE: R54's files (opt_<UND>_WEEK_<YEAR>, index_1m_<UND|INDIAVIX>_<YEAR>, 2024-2026), from
               gdrive:IraAlgo-data-lake with rclone (never committed)
    days       R54_OUT: R54's day pickles (`python3 research/hunt/r54/r54data.py build`), never in git
    caches     R55_OUT (default <cache>/r55): trade lists (never in git)
    results    research/hunt/r55/results (small JSON / markdown only)

R54's settings module is imported first (it puts R54, R50, R43 and R25 on the path). R55 only adds what is its own.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
R54 = HERE.parent / "r54"
for p in (R54, HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))          # R55's own folder first, then R54's

import r54cfg as K54  # noqa: E402
from r54cfg import CALLOUTS, DESIGN_END, FINAL_START, LAKE_END, START, UNDS, BSE, hm, lot, period  # noqa: E402,F401

ROOT = K54.ROOT
OUT = Path(os.environ.get("R55_OUT", K54.CACHE.root / "r55")).expanduser()
RESULTS = HERE / "results"

ENTRIES = ("1230", "1430")                    # bar CLOSE times; 12:30 is the arm being built (primary)
EXITS = ("T0", "T2", "T3", "T5", "H")
ARM = {"T0": 0.0, "T2": 2.0, "T3": 3.0, "T5": 5.0, "H": None}   # 0 = armed at the fill (R50 tr25); None = no trail
ENDS = ("1505", "1515", "SETTLE")
TRAIL = K54.TRAIL                             # 25%

# expiry settlement (PREREG.md)
SETTLE_FROM = (15, 0)                         # the index's 1-minute bars starting 15:00 ... 15:29 are averaged
SETTLE_TO = (15, 29)
SETTLE_MIN_BARS = 20                          # fewer bars: the day's last index close is used, and the day is flagged
STT_EXERCISE = [(date(2000, 1, 1), 0.00125), (date(2026, 4, 1), 0.0015)]   # Budget 2026: 0.125% -> 0.15% from 1 Apr 2026
BROKERAGE = 20.0
GST = 0.18
SEBI = 10.0 / 1_00_00_000

TWINS = 500
SEED = 55
BOOT = 5000
GATE_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10


def stt_exercise(d: date) -> float:
    out = 0.0
    for s, r in STT_EXERCISE:
        if d >= s:
            out = r
    return out
