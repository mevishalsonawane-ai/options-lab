"""Crypto market rules, contract specs, costs and the locked holdout. Every number here is a documented assumption.

Changing a cube rule changes cube contents: bump CUBE_VERSION. Changing a cost changes study results: say so in the
study doc. Dates are UTC; crypto trades 24x7, so there are no sessions, holidays or (for perpetuals) expiries.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import pandas as pd

from .. import config as india

CUBE_VERSION = 2            # 1: spot + perp OHLCV, mark/index/premium, basis, funding (+ time to next), OI, L/S, DVOL
                            # 2: 5-minute metrics placed at the END of their period (stamp + 5 min), 13 Oct 2026 review
METRICS_PERIOD_MIN = 5      # a metrics row stamped T describes [T, T + 5 min): its OI value uses the mark price at T + 5

# --- the grid ---------------------------------------------------------------------------------------------------------
# One row per UTC minute, 00:00 .. 23:59 (1440 rows a day). Bars are stamped at the START of the minute (Binance
# open_time), so row t holds the bar that closes at t + 1 minute and is known once that minute is over. Stored
# timestamps are naive UTC. Cube files are partitioned by UTC date; the "analysis day" used by day-based features
# (previous day high/low, day of week) is set by DAY_ANCHOR (markets.py): UTC midnight by default, or IST midnight.
MINUTES_PER_DAY = 1440
TZ = "UTC"

# --- symbols and contract specs (Binance, checked against the exchangeInfo field names; values as of Oct 2026) ------
# tick: price step. step_qty: quantity step. Linear USDT-M perpetuals: 1 contract = 1 coin, P&L in USDT.
# round: round-number levels (small, big). bin: volume-profile bin in USD.
@dataclass(frozen=True)
class Spec:
    symbol: str
    spot_tick: float
    perp_tick: float
    step_qty: float
    round_small: float
    round_big: float
    bin: float
    has_dvol: bool          # Deribit DVOL exists (BTC, ETH)


SPECS = {
    "BTCUSDT": Spec("BTCUSDT", 0.01, 0.10, 0.001, 1000.0, 5000.0, 25.0, True),
    "ETHUSDT": Spec("ETHUSDT", 0.01, 0.01, 0.001, 100.0, 500.0, 2.0, True),
    "SOLUSDT": Spec("SOLUSDT", 0.01, 0.01, 0.01, 10.0, 50.0, 0.1, False),
    "BNBUSDT": Spec("BNBUSDT", 0.01, 0.01, 0.01, 10.0, 50.0, 0.5, False),
    "XRPUSDT": Spec("XRPUSDT", 0.0001, 0.0001, 0.1, 0.1, 0.5, 0.001, False),
}

# --- costs (costs.py) -------------------------------------------------------------------------------------------------
# Binance regular-user (VIP 0) fees, no BNB discount: spot 0.10% maker and taker; USD-M futures 0.02% maker, 0.05%
# taker. Slippage: there is no bid/ask in the archive, so a taker fill is assumed to cost SLIP_BPS beyond the minute
# close (about one tick plus impact for a small order on BTC/ETH; more on the smaller coins), stressed x2 in the
# studies. Funding: a perpetual position open at a settlement pays (long) or receives the rate x notional.
FEES = {"spot": dict(maker=0.0010, taker=0.0010), "perp": dict(maker=0.0002, taker=0.0005)}
SLIP_BPS = {"BTCUSDT": 1.0, "ETHUSDT": 1.0, "SOLUSDT": 2.0, "BNBUSDT": 2.0, "XRPUSDT": 2.0}
FUNDING_INTERVAL_H = 8      # BTC/ETH/SOL/BNB/XRP USDT perps settle at 00:00, 08:00, 16:00 UTC
FUNDING_HOURS = (0, 8, 16)

# --- quality ----------------------------------------------------------------------------------------------------------
SUSPECT_JUMP = 0.10         # |1-minute close-to-close move| above 10% -> "suspect" (BTC's worst 1-minute moves are ~5%)
STALE_RUN = india.STALE_RUN
FLAGS = india.FLAGS
# How long a slow series (funding, OI metrics, DVOL) may be carried forward before it is NaN, in minutes.
MAX_AGE = dict(funding=FUNDING_INTERVAL_H * 60 + 60, metrics_5m=15, metrics_1h=120, dvol=120, hourly=120)

# --- the locked holdout (13 Oct 2026) ---------------------------------------------------------------------------------
# Everything from 1 Apr 2026 00:00 UTC on is the crypto holdout: the last ~6 months before this brain was built. It is
# fixed by date (not "the last 6 months"), so new data never moves it. Design work reads only rows before it; the
# study code refuses anything later, and the sample fetcher does not download it. Read it once per pre-registered idea.
HOLDOUT_START = pd.Timestamp("2026-04-01 00:00:00")
DESIGN_END = HOLDOUT_START - pd.Timedelta(minutes=1)


def holdout_day() -> date:
    return HOLDOUT_START.date()


@dataclass(frozen=True)
class Paths:
    root: Path

    @property
    def base(self) -> Path:
        return self.root / "crypto"

    @property
    def raw(self) -> Path:                   # Binance archive mirror (data.binance.vision layout) or a Drive pull
        return Path(os.environ.get("IRAALGO_CRYPTO_RAW", self.base / "raw")).expanduser()

    def cube(self, sym: str) -> Path:
        return self.base / "cube" / sym

    def features(self, sym: str) -> Path:
        return self.base / "features" / sym

    def labels(self, sym: str) -> Path:
        return self.base / "labels" / sym

    @property
    def study(self) -> Path:
        return self.base / "study"


def paths(root: str | os.PathLike | None = None) -> Paths:
    return Paths(Path(root).expanduser() if root else india.cache_dir())
