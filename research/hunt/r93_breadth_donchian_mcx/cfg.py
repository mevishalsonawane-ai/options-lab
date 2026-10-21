"""R93 settings (PREREG.md, committed 4e5ba0f before any statistic). Pure constants and small helpers only.

    lake      $IRAALGO_LAKE (default ~/lake): copied with rclone from gdrive:IraAlgo-data-lake; never committed
    drivers   $R93_PROXY (default $IRAALGO_LAKE/r93_proxy): Dukascopy 1-minute candles from fetch_proxy.py; never committed
    cache     $IRAALGO_CACHE/r93 (default ~/.cache/iraalgo/r93): bars, trades, twin caches; never committed
    results   research/hunt/r93_breadth_donchian_mcx/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"
LAKE = Path(os.environ.get("IRAALGO_LAKE", Path.home() / "lake")).expanduser()
PROXY = Path(os.environ.get("R93_PROXY", LAKE / "r93_proxy")).expanduser()
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser() / "r93"

# ---------------------------------------------------------------------------------------------- periods (PREREG s6)
I_DESIGN = (date(2025, 8, 5), date(2026, 3, 31))
I_FOLDS = ((date(2025, 11, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 2, 28)),
           (date(2026, 3, 1), date(2026, 3, 31)))
I_HOLDOUT = (date(2026, 4, 1), date(2026, 10, 19))
D_DESIGN = (date(2012, 1, 1), date(2022, 12, 31))
D_FOLDS = tuple((date(y, 1, 1), date(y, 12, 31)) for y in (2019, 2020, 2021, 2022))
D_HOLDOUT = (date(2023, 1, 1), date(2026, 10, 16))

# ---------------------------------------------------------------------------------------------- families (PREREG s2)
# family -> (group, signal / price series, traded instrument, kind, units per point of the series' quote, cost key)
FAMILIES = {
    "GOLD": ("metals", "GOLDM", "GOLDM", "opt", 10.0, "gold"),
    "SILVER": ("metals", "SILVERM", "SILVERM", "opt", 5.0, "silver"),
    "COPPER": ("metals", "COPPER", "COPPER", "fut", 2500.0, "copper"),
    "ZINC": ("metals", "ZINC", "ZINCMINI", "fut", 1000.0, "zinc"),
    "ALUMINIUM": ("metals", "ALUMINIUM", "ALUMINI", "fut", 1000.0, "aluminium"),
    "CRUDE": ("energy", "CRUDEOILM", "CRUDEOILM", "opt", 10.0, "crude"),
    "NATGAS": ("energy", "NATGASMINI", "NATGASMINI", "opt", 250.0, "natgas"),
}
GROUPS = {"metals": ("GOLD", "SILVER", "COPPER", "ZINC", "ALUMINIUM"), "energy": ("CRUDE", "NATGAS")}
BREADTH_M = {"metals": 3, "energy": 2}           # absolute: at least M families of the group, the family included
# daily: the parent's near-month daily series; traded as the future at the mini lot where one exists
DAILY_SERIES = {"GOLD": ("GOLD", 10.0), "SILVER": ("SILVER", 5.0), "COPPER": ("COPPER", 2500.0),
                "ZINC": ("ZINC", 1000.0), "ALUMINIUM": ("ALUMINIUM", 1000.0), "CRUDE": ("CRUDEOIL", 10.0),
                "NATGAS": ("NATURALGAS", 250.0)}
# cross-market drivers (PREREG s3 I-C); zinc and aluminium have none
DRIVERS = {"GOLD": "XAUUSD", "SILVER": "XAGUSD", "CRUDE": "LIGHTCMDUSD", "NATGAS": "GASCMDUSD",
           "COPPER": "COPPERCMDUSD"}

# ---------------------------------------------------------------------------------------------- rules (PREREG s3-4)
N_CH = 20
BAR_MIN = 5
OPEN_MIN = 9 * 60
MIN_BAR_MINUTES = 3
FILL_FROM = 9 * 60 + 10           # fills from 09:10 (the 09:00 bar never trades)
LAST_FILL_BEFORE_CLOSE = 20       # last fill at close - 20 min
SQ_BEFORE_CLOSE = 15              # square-off at close - 15 min
FILL_WAIT = 3                     # the first printed minute within 3 minutes after the bar (R51 range(e, e + 3))
MAX_PER_SIDE = 2
TMAX = 30                         # minutes, out at the close of the 30th minute from the fill
NAN = float("nan")
# XL: stop -15% on the premium; (peak gain, locked gain), NaN = breakeven (fill + round-trip costs) (r71cfg EXITS["XL"])
STOP = 0.15
STEPS = ((0.30, NAN), (0.60, 0.30), (1.00, 0.60), (0.25, 0.05))
# daily (PREREG s4)
D_INIT_ATR = 2.0
D_TRAIL_ATR = 3.0
D_MAX_HOLD = 10
D_ATR_N = 14
ROLL_OI = 1.5
ROLL_SPACING_DAYS = 15

# ---------------------------------------------------------------------------------------------- costs (PREREG s5)
OPT_HALF = {"gold": (0.0030, 0.0060), "silver": (0.0030, 0.0060), "crude": (0.0030, 0.0060),
            "natgas": (0.0045, 0.0090)}              # (from 17:00 IST, before 17:00)
FUT_HALF = {"crude": 0.0003, "natgas": 0.0006, "gold": 0.0005, "silver": 0.0005, "copper": 0.0005, "zinc": 0.0005,
            "aluminium": 0.0005}
STOP_SLIP = 0.0002

# ---------------------------------------------------------------------------------------------- gates (PREREG s7)
TWINS = 500
SEED = 93
GATE_P = 0.05
GATE_Q = 0.10
GATE_MIN_TRADES = 40
STOP_MONTH = -13000.0
STOP_DD = 40000.0
INTRADAY = ("I-A", "I-B", "I-C")
DAILY = ("D-A", "D-B")
VARIANTS = INTRADAY + DAILY
LABEL = {"I-A": "plain Donchian 20 (5-minute)", "I-B": "complex breadth (5-minute)",
         "I-C": "cross-market confirmation (5-minute)", "D-A": "plain Donchian 20 (daily, futures)",
         "D-B": "complex breadth (daily, futures)"}


def opt_half(cost_key: str, tod: int, mult: float = 1.0) -> float:
    a, b = OPT_HALF[cost_key]
    return (a if tod >= 17 * 60 else b) * mult


def in_period(d: date, p) -> bool:
    return p[0] <= d <= p[1]
