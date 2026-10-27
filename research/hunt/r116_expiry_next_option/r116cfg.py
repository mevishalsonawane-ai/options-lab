"""R116 settings (PREREG.md): on an index's own expiry day, keep the arms' index signals and buy the 1-ITM option of the
NEXT expiry, against skipping the day (today's rule) and against the same-day (expiring) option.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake): index_1m, options_rolling_1m WEEK and MONTH (Drive)
    release   $R116_REL (default ~/.cache/iraalgo/rel): the `research-data` GitHub release archives that hold "the nearest
              expiry AFTER the day" (so the next expiry on an expiry day): nifty_prev / nifty_year,
              banknifty_prev_year_wide / banknifty_year_wide
    stores    R51's Amendment A clean stores (R51_CLEAN=1), built by research/hunt/r51/r51data.py build
    cache     $R116_OUT (default ~/.cache/iraalgo/r116): next-expiry contracts and trades (never in git)
    results   research/hunt/r116_expiry_next_option/results (small JSON only)

Pure: paths, periods, worlds, tests and gates. Importing it puts R115's folder (and through it R112 / R106 / R101 / R51)
on the path. 27 Oct 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
_R115 = HERE.parent / "r115_price_action_arms"
for p in (str(_R115), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE_ROOT / "lake")).expanduser()
REL = Path(os.environ.get("R116_REL", CACHE_ROOT / "rel")).expanduser()
OUT = Path(os.environ.get("R116_OUT", CACHE_ROOT / "r116")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
ENTRIES = ROOT / "research" / "experiments" / "entries"

# periods (PREREG section 4): identical to R101 / R106 / R112 / R113 / R115
START = date(2024, 11, 20)
DESIGN_END = date(2026, 3, 31)
WF_START = date(2025, 4, 1)
FINAL_START = date(2026, 4, 1)
FINAL_END = date(2026, 10, 13)
OPEN_SLICE_ROW = 15            # fills before 09:30 = the "09:15-09:30 entries" (R113)

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
WEEKLY = {"NIFTY", "SENSEX"}                     # the indices with weekly expiries since 20 Nov 2024
ARCHIVE = {                                      # release files holding "the nearest expiry after the day"
    "NIFTY": ("nifty_prev.parquet", "nifty_year.parquet"),
    "BANKNIFTY": ("banknifty_prev_year_wide.parquet", "banknifty_year_wide.parquet"),
}
LAKE_MONTH = {"NIFTY", "SENSEX"}                 # MONTH differs from WEEK only here (BN / FIN / MIDCP: the same file)

ARMS = ("R72", "R51", "R52", "LIQ")
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "R52": "R52 Open outside value",
            "LIQ": "Liquidity 15+5"}

# worlds (PREREG section 3): which Day objects an expiry day gets
#   S  every expiry day traded with the same-day (expiring) contracts the store already holds
#   N  expiry days whose NEXT expiry has 1-minute prints (exact next weekly / next monthly), traded on those contracts
#   M  NIFTY / SENSEX weekly-expiry days where only the lake's monthly (2-4 weeks out) exists: a stand-in, reported only
WORLDS = ("S", "N", "M")

# the gated family (PREREG section 5): (arm, world)
TESTS = {f"{w}{a}": (a, w) for w in ("N", "S") for a in ARMS}

# gates (never relaxed)
TWINS = 2000
SEED = 116
GATE_Q = 0.10
MIN_FINAL = 40                 # final trades needed before a verdict is possible at all
MONTH_LIMIT = -13_000.0
# every family in research/experiments/entries/ dated up to 27 Oct 2026 (R115 included), each the larger of its entry
# count and its summed variants_tried (r115lib.count_prior); frozen in the pre-registration, re-checked by the tests
N_PRIOR_ALL = 9_300_047
M_ALL = N_PRIOR_ALL + len(TESTS)

# the 2% per-trade risk cap (BossRisk.kt): risk per lot = lot x entry x stop share + Rs 60 charges
RISK_PCT = 0.02
STOP_SHARE = 0.15
CHARGES_PER_LOT = 60.0


def in_period(d: date, period: str) -> bool:
    if period == "design":
        return START <= d <= DESIGN_END
    if period == "wf":
        return WF_START <= d <= DESIGN_END
    if period == "final":
        return FINAL_START <= d <= FINAL_END
    raise ValueError(period)
