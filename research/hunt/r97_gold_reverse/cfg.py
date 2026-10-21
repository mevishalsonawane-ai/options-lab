"""R97 settings: reverse-engineering Boss's "gold moves 200-300 every day" (21 Oct 2026). Pure constants only.

    lake      $IRAALGO_LAKE (default ~/lake): copied with rclone from gdrive:IraAlgo-data-lake; never committed
    cache     $IRAALGO_CACHE (default ~/.cache/iraalgo): R53 markets, R96 books, R97's XAUUSD minutes; never committed
    results   research/hunt/r97_gold_reverse/results (small JSON / markdown only)

Signal source of everything here: the MCX gold future (the option book's near future, per-minute median of the option
rows' `spot`, close-only; R53 Market). XAUUSD (Dukascopy spot) and SILVER's near future are context only. The trade
is always an option BUY on GOLD / GOLDM.
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser() / "r97"
XAU_FILE = CACHE / "xau_bid_1m.parquet"

# periods: the same as R96 (its PREREG s2), never changed
DESIGN = (date(2025, 8, 5), date(2026, 7, 14))
HOLDOUT = (date(2026, 7, 15), date(2026, 10, 19))      # LOCKED; read once, only for a rule passing every design gate
FOLDS = ((date(2025, 8, 5), date(2025, 10, 31)), (date(2025, 11, 1), date(2026, 1, 31)),
         (date(2026, 2, 1), date(2026, 4, 30)), (date(2026, 5, 1), date(2026, 7, 14)))

# books: name -> (signal series, cost key, rupees per point of the option quote, events)
BOOKS = {"GOLDM": ("GOLDM", "gold", 10.0, ("cpi", "nfp")), "GOLD": ("GOLD", "gold", 100.0, ("cpi", "nfp"))}

# step 1 grids (points = rupees per 10 g of the future quote; GOLD and GOLDM are both quoted per 10 g)
SWING_SIZES = (200, 300, 500, 1000)       # "a swing of at least S points"
RETRACE = (100, 200, 300)                 # zig-zag reversal: a swing ends when price comes back X points
TRIGGERS = (50, 100)                      # "entry after the first T points of the move"
CLOCK = (("09:00-12:00", 540, 720), ("12:00-15:00", 720, 900), ("15:00-17:00", 900, 1020),
         ("17:00-18:00", 1020, 1080), ("18:00-19:30 US open", 1080, 1170), ("19:30-21:00", 1170, 1260),
         ("21:00-close", 1260, 1440))
EVENT_WINDOW = (-15, 30)                  # a swing "at a US release" starts 15 min before to 30 min after it
RANDOM_SEED = 97
