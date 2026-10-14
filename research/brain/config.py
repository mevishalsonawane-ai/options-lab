"""Paths, the minute grid and the pricing assumptions shared by the cube and the feature store.

Everything here is a documented assumption. Changing any of it changes cube contents, so bump CUBE_VERSION.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

# Bump when the cube's columns or rules change: days built with an older version are rebuilt by `cube build`.
CUBE_VERSION = 2              # 2: futures OHLC, roll, vol_precision, minute ATM IV and max-OI strikes

# --- the grid -------------------------------------------------------------------------------------------------------
# NSE cash/F&O session 09:15-15:30 IST. Bars are stamped at the START of the minute, so the grid is 09:15 .. 15:29
# (375 rows). Row t holds the bar that closes at t + 1 minute; everything in row t is known once that minute is over.
SESSION_OPEN = (9, 15)
SESSION_MINUTES = 375
TZ = "Asia/Kolkata"            # all stored timestamps are naive IST

# --- option chain ---------------------------------------------------------------------------------------------------
CHAIN_OFFSETS = range(-10, 11)  # ATM-10 .. ATM+10 strikes
STRIKE_STEP = {"BANKNIFTY": 100.0, "NIFTY": 50.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0,
               "BANKEX": 100.0}

# --- Black-Scholes assumptions (documented in README "IV and Greeks") -----------------------------------------------
# r: a flat 6.5% a year. India's 91-day T-bill ranged about 5.4-7.1% over 2024-2026; at a few days to expiry the
#    price effect of a 1-point error in r is far below the bid-ask spread.
# q: 0. Index dividend yield (~1% for BANKNIFTY) is ignored; with weekly/monthly expiries its effect is < 0.1 IV point.
# Expiry time: 15:30 IST on the expiry date. T = calendar minutes from the END of bar t to expiry / (365 * 1440).
# Underlying: the index close of the same minute (spot, not the forward).
RISK_FREE = 0.065
DIV_YIELD = 0.0
EXPIRY_CLOSE = (15, 30)
IV_MIN, IV_MAX = 0.01, 3.0      # 1% .. 300% a year; outside this the IV is NaN
IV_MIN_PRICE = 0.10             # no IV below two ticks: such a price carries no volatility information
IV_MIN_VEGA = 0.05              # no IV when 1 IV point moves the price by less than one tick (deep ITM near expiry)

# --- quality-flag thresholds ----------------------------------------------------------------------------------------
STALE_RUN = 3                   # index: >= this many flat bars in a row equal to the previous close -> "stale"
SUSPECT_JUMP = 0.03             # index: |1-minute close-to-close move| above 3% -> "suspect"
FLAGS = ("ok", "stale", "gap", "suspect")


def cache_dir() -> Path:
    """Local cache, outside git. Override with IRAALGO_CACHE."""
    return Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()


@dataclass(frozen=True)
class Paths:
    root: Path

    @property
    def raw_release(self) -> Path:          # research-data release assets
        return self.root / "raw" / "research-data"

    @property
    def lake(self) -> Path:                 # Dhan data lake (research/data/fetch_release.py --dest here)
        return Path(os.environ.get("IRAALGO_LAKE", self.root / "lake")).expanduser()

    def cube(self, und: str) -> Path:
        return self.root / "cube" / und

    def features(self, und: str) -> Path:
        return self.root / "features" / und

    def labels(self, und: str) -> Path:
        return self.root / "labels" / und


def paths(root: str | os.PathLike | None = None) -> Paths:
    return Paths(Path(root).expanduser() if root else cache_dir())
