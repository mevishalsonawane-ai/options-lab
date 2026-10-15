"""F1 Strategy Factory, CRYPTO part: settings (PREREG.md). Research only; nothing in the app trades crypto. 14 Oct 2026.

Data: the crypto brain's cube, features and labels ($IRAALGO_CACHE/crypto, refreshed from the Drive crypto lake) and the
options lake (Delta Exchange India 1-minute option candles, Deribit trades). F1C's own tables go to <cache>/f1c (never
git) and to Drive (gdrive:IraAlgo-data-crypto/derived/f1c_factory). Results (statistics, no market data) go to results/.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from research.brain.crypto import config as CC  # noqa: E402

CACHE = CC.paths()
OUT = Path(os.environ.get("F1C_OUT", CACHE.root / "f1c")).expanduser()
RESULTS = HERE / "results"
DRIVE = "gdrive:IraAlgo-data-crypto/derived/f1c_factory"
HOLD_LOG = OUT / "HOLDOUT_READ.json"

COINS = ("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT")
OPTION_COINS = ("BTCUSDT", "ETHUSDT")             # Delta India lists BTC and ETH options only

# --- periods (PREREG section 2) ---------------------------------------------------------------------------------------
# Holdout read by earlier crypto work (every one of them):
#   RC1  13 Oct 2025 - 12 Oct 2026 (perps to 30 Sep 2026)      RC2  13 Apr - 13 Oct 2026
#   NN_CRYPTO and STRAD_CRYPTO  1 Apr - 7 Oct 2026             crypto brain C1/C2  1 Apr 2026 on: locked, NOT read
# The most recent 6 months were read (RC1, RC2, NN, STRAD), so F1C uses the clean earlier block 1 Apr - 30 Sep 2025:
# no earlier study read it as a holdout (it was design / walk-forward data for C1, C2, RC1, RC2, NN and STRAD).
# Apr 2026 on stays untouched (the brain's own lock, kept for C2's pending read).
HOLDOUT = (pd.Timestamp("2025-04-01 00:00"), pd.Timestamp("2025-10-01 00:00"))          # [start, end)
# Design: Nov 2023 (after the brain's 31-day warm-up from Oct 2023) to the holdout, and from 1 Nov 2025 (a 31-day
# embargo after the holdout, so no feature window reads a holdout minute) to the brain's lock (1 Apr 2026).
DESIGN = ((pd.Timestamp("2023-11-01 00:00"), HOLDOUT[0]),
          (pd.Timestamp("2025-11-01 00:00"), CC.HOLDOUT_START))
# The screen (step 2) fits on SCREEN and checks on SELECT; both are design data.
SCREEN = ((pd.Timestamp("2023-11-01 00:00"), pd.Timestamp("2024-10-01 00:00")),)
SELECT = ((pd.Timestamp("2024-10-01 00:00"), HOLDOUT[0]), DESIGN[1])
# Walk-forward folds: calendar quarters of design data (two partial: Nov-Dec 2023, Nov-Dec 2025)
QUARTERS = ("2023Q4", "2024Q1", "2024Q2", "2024Q3", "2024Q4", "2025Q1", "2025Q4", "2026Q1")


def _in(ts, spans) -> "pd.Series":
    t = pd.DatetimeIndex(ts)
    out = pd.Series(False, index=range(len(t)))
    for a, b in spans:
        out |= pd.Series((t >= a) & (t < b))
    return out.to_numpy()


def is_design(ts, horizon_min: int = 0):
    """True where [t, t + horizon] lies inside one design span (a label or trade never crosses into the holdout or
    the brain's lock)."""
    t = pd.DatetimeIndex(ts)
    end = t + pd.Timedelta(minutes=horizon_min)
    ok = None
    for a, b in DESIGN:
        x = (t >= a) & (end < b)
        ok = x if ok is None else ok | x
    return ok


def is_holdout(ts, horizon_min: int = 0):
    t = pd.DatetimeIndex(ts)
    return (t >= HOLDOUT[0]) & (t + pd.Timedelta(minutes=horizon_min) < HOLDOUT[1])


def quarter(ts) -> "pd.Index":
    return pd.DatetimeIndex(ts).to_period("Q").astype(str)


def months_needed() -> tuple[date, date]:
    return date(2023, 10, 1), date(2026, 3, 31)


# --- Boss's window (CLAUDE.md, 13 Oct 2026): every F1C trade is inside 00:00-09:00 IST and flat by 09:00 IST ---------
WIN_START_UTC = (18, 30)        # 00:00 IST
WIN_END_UTC = (3, 30)           # 09:00 IST
IST = pd.Timedelta(hours=5, minutes=30)

# --- money (PREREG section 4) -----------------------------------------------------------------------------------------
ACCOUNT = 100_000.0             # Rs; also reported at Rs 25,000
ACCOUNT_SMALL = 25_000.0
INR_PER_USD = 85.0              # fixed (delta_india_fees.json); only lot rounding depends on it
RISK_PER_TRADE = 0.01           # perps: 1% of the account lost at the stop
MAX_LEVERAGE = 3.0              # perps: notional never above 3x the account
OPT_PREMIUM_FRAC = 0.02         # options: at most 2% of the account in premium a trade (RC2)
DAY_LOSS_CAP = 0.03             # a rule stops for the IST day after losing 3% of the account that day
LOT_COIN = {"BTCUSDT": 0.001, "ETHUSDT": 0.01, "SOLUSDT": 0.1, "BNBUSDT": 0.01, "XRPUSDT": 1.0}   # Delta India perps

# --- costs: Delta Exchange India (INR-settled), per side, fractions of notional ---------------------------------------
PERP_FEE = 0.0005               # taker 0.05%
GST = 0.18                      # on fees
PERP_SLIP = {"BTCUSDT": 0.0002, "ETHUSDT": 0.0002, "SOLUSDT": 0.0005, "BNBUSDT": 0.0005, "XRPUSDT": 0.0005}
STRESS_SLIP = 2.0               # stress: slippage x2 (and options: spread x1.5, fee 0.03%, RC2)
# --- India crypto tax (modelling assumptions; a CA must confirm) -------------------------------------------------------
TAX = 0.312                     # 30% + 4% cess on each profitable trade's gain (VDA, sec 115BBH): fees not deductible,
                                # losses never set off, no carry-forward (reading A, the primary number)
TDS = 0.01                      # 1% of each SELL's value withheld (perps: the exit of a long, the entry of a short;
                                # options: the exit sale), credited back only after the year's return (~365 days here)
TDS_REFUND_DAYS = 365
