"""R46 settings (PREREG.md): another AI's "Option 1" (BTC EMA(8)/EMA(21) cross + ADX(14) > 15 + neural net > 0.65,
buy the Delta Exchange India BTC ATM call, 60-minute exit, 50% premium stop) re-tested honestly. 15 Oct 2026.

Research only. Nothing in the app trades crypto. Every number used by the rules, fills and costs lives here, so the
tests, the run and the write-up read the same values.

    raw lake   R46_LAKE (default: the session scratchpad copy of gdrive:IraAlgo-data-crypto)
               spot_BTC/<YYYY>/BTCUSDT-klines_1m-<YYYY-MM>.parquet      Binance BTCUSDT spot 1-minute bars (the signal)
               perp_BTC/<YYYY>/BTCUSDT-klines_1m-<YYYY-MM>.parquet      Binance BTCUSDT perp 1-minute bars (futures
                                                                         baseline, and the basis input of the MLP)
               delta_india/candles/BTC/<YYYY>/<YYYY-MM-DD>.parquet      Delta India BTC option 1-minute trade candles,
                                                                         one file per expiry day (12:00 UTC)
    cache      R46_OUT (default <lake>/../r46): prepared option arrays, trade pools (never in git)
    results    research/hunt/r46/results (small JSON / Markdown only, no market data)
"""
from __future__ import annotations

import os
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
_SCRATCH = "/tmp/claude-0/-home-user-options-lab/29a5ac4e-a3c1-551a-8232-71f956f99d9f/scratchpad/lake"
LAKE = Path(os.environ.get("R46_LAKE", _SCRATCH)).expanduser()
OUT = Path(os.environ.get("R46_OUT", LAKE.parent / "r46")).expanduser()
RESULTS = HERE / "results"
HOLD_LOG = RESULTS / "HOLDOUT_READ.json"

# --- periods (PREREG section 3), UTC ----------------------------------------------------------------------------------
DATA_START = pd.Timestamp("2023-10-01 00:00")      # BTC bars start here (indicator warm-up: Oct-Dec 2023)
DESIGN = (pd.Timestamp("2024-01-01 00:00"), pd.Timestamp("2026-04-01 00:00"))      # [start, end): 27 months
HOLDOUT = (pd.Timestamp("2026-04-01 00:00"), pd.Timestamp("2026-10-13 00:00"))     # to the end of the BTC bars
CLAIM = (pd.Timestamp("2026-09-01 00:00"), pd.Timestamp("2026-10-13 00:00"))       # the other AI's window (inside
                                                                                    # the holdout; not a test)
IST = pd.Timedelta(hours=5, minutes=30)

# --- the signal (on BTC spot = the underlying; PREREG section 4) ------------------------------------------------------
BAR_MIN = 5
EMA_FAST, EMA_SLOW = 8, 21
ADX_N = 14
ADX_MIN = 15.0
NN_THRESHOLD = 0.65
MAX_PER_DAY = 2                 # trades per IST day (00:00-24:00 IST = 18:30-18:30 UTC), counted at entry
BOSS_WINDOW_IST = (0, 9)        # extra reading only: entries 00:00-09:00 IST (Boss, 13 Oct, brain config WINDOW)

# --- the option (Delta Exchange India BTC daily options, settle 12:00 UTC = 17:30 IST) --------------------------------
EXPIRY_H_UTC = 12
MIN_LEFT = pd.Timedelta(minutes=90)        # today's expiry only if >= 90 min are left at the entry minute, else the
                                           # next day's (60 min hold + 10 min before settlement + 20 min spare)
LAST_EXIT_BEFORE_EXPIRY = pd.Timedelta(minutes=10)
ATM_BAND = 0.01                 # the chosen strike is within 1% of spot
FILL_WINDOW_MIN = 5             # the first real trade (volume > 0) of the strike in the 5 minutes from the entry minute
HOLD_MIN = 60
LOT_BTC = 0.001                 # Delta India contract_value: 1 contract = 0.001 BTC
CLAIM_CONTRACTS = 1000          # the other AI's size: "1000 contracts ~ $300/trade" (= 1 BTC of underlying)
TICK = 0.1

# --- costs ------------------------------------------------------------------------------------------------------------
GST = 0.18
# Delta India fee schedules by expiry date (delta_india/fees/fees-2026-10-13.json, from the products API). Where the
# current schedule and an older one both cover a date, the dearer (older) one is used (PREREG section 6).
FEE_SCHEDULES = (  # (first expiry, last expiry, rate x notional, cap x premium)
    ("2023-12-28", "2025-07-21", 0.0003, 0.10),
    ("2025-07-22", "2025-09-11", 0.00015, 0.10),
    ("2025-09-12", "2025-12-11", 0.00015, 0.05),
    ("2025-12-12", "2026-12-25", 0.0001, 0.035),
)
# Spread model: full spread = a x spot + b x mid (RC2 costs.py; the wider of the two live-chain fits, coefficient by
# coefficient). Buys pay mid + half spread; exits get level - half spread - 1 tick - 1% (market orders, RC2).
SPREAD_AB = (max(0.000098, 1.8418994746696997e-05), max(0.0179, 0.010365955499149005))
EXIT_SLIP_FRAC = 0.01
STRESS_SPREAD = 1.5             # stress reading (reported, not a gate)

# Perp baseline (Delta India BTC perp stands in as Binance BTCUSDT perp): taker 0.05% + 18% GST, slippage 0.02%/side
PERP_FEE = 0.0005
PERP_SLIP = 0.0002

# India crypto tax, reading A (modelling assumption; a CA must confirm): 30% + 4% cess on each winning trade's gain,
# fees not deductible, no loss set-off, no carry-forward; 1% TDS on each sale's value (cash flow, refunded after the
# year's return).
TAX = 0.312
TDS = 0.01
INR_PER_USD = 85.0              # the fees file's rate (F1C)

# --- exits: every trade has a stop-loss AND a profit lock (CLAUDE.md crypto rule) -------------------------------------
# Option exits, levels as multiples of the entry mid. step: ladder from a 1.25x peak, each +0.25x of peak locks the
# rung below (1.25 -> 1.00, 1.50 -> 1.25, 1.75 -> 1.50, 2.00 -> 1.75, ...). trail: from a 1.30x peak, keep 50% of the
# peak gain (lock = 1 + 0.5 x (peak - 1), so at least 1.15x).
OPT_EXITS = {
    "L1": dict(stop=0.50, lock="step", step=0.25, first=1.25),
    "L2": dict(stop=0.50, lock="trail", start=1.30, keep=0.50),
    "L3": dict(stop=0.30, lock="step", step=0.25, first=1.25),
}
# Perp baseline exit (fractions of the entry price): stop -0.40%; from a +0.30% peak keep 50% of the peak gain.
PERP_EXIT = dict(stop=0.004, lock="trail", start=1.003, keep=0.50)

# --- our MLP (variant B; PREREG section 5) ----------------------------------------------------------------------------
NN_FEATURES = ("ret_1", "ret_3", "ret_12", "ret_48", "rv_12", "rv_288", "adx", "di_diff", "ema_gap", "volz",
               "dist_hi", "basis")
NN_HIDDEN = (64, 32)
NN_MIN_TRAIN_MONTHS = 6
NN_EPOCHS = 30
NN_PATIENCE = 3
NN_BATCH = 1024
NN_LR = 1e-3
NN_L2 = 1e-4
NN_VAL_FRAC = 0.15
NN_SEED = 0

# --- statistics -------------------------------------------------------------------------------------------------------
TWIN_DRAWS = 1000
MIN_TRADES = 40
P_MAX = 0.05
Q_MAX = 0.10
