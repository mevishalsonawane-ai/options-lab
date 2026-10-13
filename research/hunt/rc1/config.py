"""RC1 settings, exactly as pre-registered in PREREG.md (13 Oct 2026). Change nothing here without a new pre-registration."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import pandas as pd

FETCH_END = date(2026, 10, 12)
HOLDOUT_START = date(2025, 10, 13)
HOLDOUT_END = date(2026, 10, 12)
HOLDOUT_START_TS = pd.Timestamp("2025-10-13", tz="UTC")
HOLDOUT_END_TS = pd.Timestamp("2026-10-12 23:59:59", tz="UTC")

SPOT_FIRST_TRADE = date(2018, 3, 5)      # the 200-day average exists for both coins
WF_FIRST_OOS = date(2019, 10, 13)        # anchored yearly walk-forward, 6 OOS years to 12 Oct 2025
WF_END = date(2025, 10, 12)
PERP_START = date(2020, 1, 1)

ACCOUNTS = (25_000, 100_000)
PRIMARY_ACCOUNT = 100_000
SEED = 20261013
N_TWINS = 1000

TAX = 0.312            # 30% + 4% cess, VDA (and the top business slab for reading B)
TDS = 0.01             # on each spot sale's value, returned 365 days later
TDS_LOCK_DAYS = 365
CARRY_YEARS = 8        # business-loss carry-forward (reading B)
GST = 0.18
MM = 0.005             # maintenance margin used in the liquidation price
CONTRACT = {"BTC": 0.001, "ETH": 0.01}   # Delta Exchange India contract sizes


@dataclass(frozen=True)
class Cost:
    fee: float          # taker (or maker) fee, before GST
    half_spread: float

    @property
    def per_side(self) -> float:
        return self.fee * (1 + GST) + self.half_spread


SPOT_COST = {"low": Cost(0.0010, 0.0005), "base": Cost(0.0025, 0.0015), "high": Cost(0.0050, 0.0030)}
PERP_COST = {"low": Cost(0.0002, 0.0001), "base": Cost(0.0005, 0.0002), "high": Cost(0.0005, 0.0005)}
ZERO_COST = Cost(0.0, 0.0)

# idea settings
TREND_RULES = ("sma100", "sma200", "dc50")          # slower first: ties go to the slower rule
SLOWNESS = {"sma200": 0, "dc50": 1, "sma100": 2}
VOL_TARGET = 0.40
VOL_WINDOW = 30
VOL_BAND = 0.10
CARRY_IN = 0.10        # annualised mean of the last 9 prints
CARRY_OUT = 0.0
BRK_N, BRK_EXIT_N, ATR_N, ATR_K = 20, 10, 14, 2.0
