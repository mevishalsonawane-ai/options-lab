"""R54 settings (PREREG.md, 15 Oct 2026): the Rs 1,000 hero ticket at 12:30 / 13:30 / 14:00 / 14:30, out at 15:05 / 15:10 /
15:15, with Boss's 25% trail or held to the end time.

    lake       $IRAALGO_LAKE (default <cache>/lake): opt_<UND>_WEEK_<YEAR>.parquet, index_1m_<UND|INDIAVIX>_<YEAR>.parquet
               (2024-2026), copied with rclone from gdrive:IraAlgo-data-lake (never committed)
    caches     R54_OUT (default <cache>/r54): day pickles and trade lists (never in git)
    results    research/hunt/r54/results (small JSON / markdown only)

R50's settings module is imported first: it sets the paths of R25 / R43, the steps and lots in force on each date and the
window (20 Nov 2024 - 13 Oct 2026, design to 31 Mar 2026). R54 only adds what is its own.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
R50 = HERE.parent / "r50"
for p in (R50, HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))          # R54's own folder first, then R50's (which puts R25 / R43 on the path)

import r50cfg as K50  # noqa: E402
from r50cfg import (BLOCKS, DESIGN_END, FINAL_START, LAKE_END, START, block, lot, period)  # noqa: E402,F401

ROOT = K50.ROOT
CACHE = K50.CACHE
LAKE = K50.LAKE
OUT = Path(os.environ.get("R54_OUT", CACHE.root / "r54")).expanduser()
RESULTS = HERE / "results"
K50.K43.OUT = OUT                       # R43's builder writes nothing; kept off R50's cache all the same

UNDS = ("NIFTY", "SENSEX")              # each on its own expiry days only
BSE = K50.BSE

BUDGET = 1000.0                          # premium of the 1 lot, per side (charges on top)
BAND_LO, BAND_HI = 5.0, 30.0             # R50's "cheap" band, capped at BUDGET / lot
FOREIGN_TOL = 0.03                       # R47 amendment 2: option rows whose own spot is > 3% off the index are dropped
BAD_PRINT = 0.20                         # R47 amendment 1: the stray-print guard
TRAIL = 0.25                             # Boss's 25% trail (R50 tr25)

ENTRIES = ("1230", "1330", "1400", "1430")      # bar CLOSE times
ENDS = ("1505", "1510", "1515")                 # the bar that STARTS then is sold at its open
EXITS = ("tr25", "hold")
TWIN_WIN = ("1200", "1445")                     # R50's fixed-time twin window (bar close times)
LATE_FROM = "1500"                               # buckets from here may widen (results/liquidity.json)
LATE_BASE = ("1400", "1459")                     # the base range (bar start times)
LIQ_FROM, LIQ_TO = "1230", "1529"

TWINS = 500
SEED = 54
BOOT = 5000
GATE_TRADES = 40
GATE_P = 0.05
GATE_Q = 0.10

CALLOUTS = (("SENSEX", "2026-04-02"), ("NIFTY", "2026-10-13"))


def hm(s: str) -> tuple[int, int]:
    return int(s[:2]), int(s[2:])
