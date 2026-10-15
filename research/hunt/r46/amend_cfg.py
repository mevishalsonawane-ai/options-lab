"""R46 amendments (15 Oct 2026), settings. Research only; nothing in the app trades crypto.

Boss asked for three LABELLED amendments after R46 was finished. R46's own numbers (results/design.json,
results/holdout.json) stay as they were; everything here is new and written to results/amend_*.json.

  1. CoinSwitch PRO costs beside Delta's (fees from third-party pages, NOT verified; GST on the fee with and without).
  2. v2 "Ensemble": the other AI's second version (EMA/ADX entry + an MLP/GB/RF ensemble on the CALL's own chart).
  3. "Buy the BTC ATM option after a 25% drop in one 15-minute bar": reproduce their claim, find why it is wrong, and
     re-test it honestly on held contracts with a stop and a profit lock.

Views (Boss, 15 Oct: only 20 Nov 2024 onwards). PRIMARY: design 20 Nov 2024 - 31 Mar 2026, final test 1 Apr - 13 Oct
2026. The final-test span is R46's holdout span, which R46 already read once (15 Oct 01:08 UTC); these spans were fixed
in this file before any amendment result was computed. The full span (from 1 Jan 2024) is shown beside it.
"""
from __future__ import annotations

import pandas as pd

from . import r46cfg as K

# --- views --------------------------------------------------------------------------------------------------------------
NOV_START = pd.Timestamp("2024-11-20 00:00")
FINAL = (pd.Timestamp("2026-04-01 00:00"), pd.Timestamp("2026-10-13 00:00"))      # = R46's holdout span
VIEWS = {
    "nov24": dict(design=(NOV_START, FINAL[0]), final=FINAL, data_from=NOV_START),   # PRIMARY (Boss, 15 Oct)
    "full": dict(design=(K.DESIGN[0], FINAL[0]), final=FINAL, data_from=K.DESIGN[0]),
}
PRIMARY = "nov24"
CLAIM_V2 = (pd.Timestamp("2026-09-01 00:00"), pd.Timestamp("2026-10-13 00:00"))      # their Sep-Oct 2026 window
CLAIM_DIP = (pd.Timestamp("2024-09-01 00:00"), pd.Timestamp("2026-10-13 00:00"))     # their Sep 2024 - Oct 2026

# --- amendment 1: costs -------------------------------------------------------------------------------------------------
# CoinSwitch PRO (Boss trades there): BTC options ~0.015% (charged here on the underlying notional, like Delta, and with
# no cap: the dearer reading), futures 0.02% maker / 0.05% taker. Third-party figures, unverified. CoinSwitch PRO routes
# BTC options under "dtrade", most likely Delta's own book, so the spread model and the one-minute-later fill stay.
CS_OPT_RATE = 0.00015
CS_FUT_MAKER = 0.0002
CS_FUT_TAKER = 0.0005
# scheme: (fee kind, gst share on the fee). "delta" is R46's own (dated schedule with the premium cap, + 18% GST).
OPT_SCHEMES = {"delta": ("delta", K.GST), "cs_gst": ("cs", K.GST), "cs_nogst": ("cs", 0.0)}
PERP_SCHEMES = {"delta": (K.PERP_FEE, K.GST), "cs_taker_gst": (CS_FUT_TAKER, K.GST),
                "cs_taker_nogst": (CS_FUT_TAKER, 0.0), "cs_maker_nogst": (CS_FUT_MAKER, 0.0)}
MAIN_SCHEME = "delta"           # the gate uses Delta's costs, as R46 did; CoinSwitch is shown beside it

# --- the rolling ATM series (what a "btc_atm" file holds) ---------------------------------------------------------------
# At every minute: the nearest daily expiry with >= 90 minutes left (R46's rule, so a 60-minute hold always fits), the
# strike nearest BTC spot (within 1%) among that expiry's contracts that have traded at least once, and that contract's
# last traded price. The series rolls to the next expiry 90 minutes before each 12:00 UTC settlement.
LOOKBACK_MIN = 180              # minutes of each contract's own history kept before its first own-window minute

# --- amendment 2: v2 ensemble -------------------------------------------------------------------------------------------
ENS_FEATURES = ("ret1", "ret3", "ret6", "ret12", "vol12", "rsi14", "spot_ret1", "spot_ret6", "spot_vol12", "dte_h",
                "hour", "ce_spot")
ENS_THRESHOLD = 0.65
ENS_LABEL_BARS = 12             # "CE premium up after 60 minutes" = 12 five-minute bars
ENS_EMBARGO_BARS = 12           # purge rows whose label ends after (block start - embargo)
ENS_MLP = dict(hidden=(64, 32), dropout=0.2, epochs=20, batch=1024, lr=1e-3, val_frac=0.15, patience=3, seed=0)
# GradientBoosting with their settings (200 trees, depth 4, lr 0.05) fitted with histogram split finding
# (sklearn HistGradientBoostingClassifier): the same boosted trees, ~10x faster on 100-300k rows. No early stopping.
ENS_GB = dict(max_iter=200, max_depth=4, learning_rate=0.05, early_stopping=False, random_state=0)
ENS_RF = dict(n_estimators=200, max_depth=10, random_state=0, n_jobs=4)
ENS_MIN_TRAIN = 10_000          # rows needed before a block may be traded
ENS_RANDOM_TEST = 0.20          # the random split's test share (the leaky way)
ENS_EXITS = ("L1", "L2")        # step lock and trailing lock (R46), 50% stop; a no-lock version is never tested


def blocks(view: str) -> list[pd.Timestamp]:
    """Walk-forward block starts: retrain at each, test until the next. The first block leaves ~2.4 months (nov24) or
    3 months (full) of training data; then calendar quarters."""
    v = VIEWS[view]
    first = pd.Timestamp("2025-02-01") if view == "nov24" else pd.Timestamp("2024-04-01")
    qs = list(pd.date_range(first, v["final"][1], freq="QS"))
    out = [first] + [q for q in qs if q > first]
    return out + [v["final"][1]]


# --- amendment 3: the 25% dip buy -----------------------------------------------------------------------------------------
DIP_BAR_MIN = 15
DIP_DROP = -0.25
DIP_MIN_PREM = 50.0             # USD per 1 BTC of underlying (Delta quotes premium per BTC)
DIP_HOLD_BARS = 4               # their exit: 4 bars = 60 minutes
DIP_NOTIONAL = 300.0            # USD of premium per trade (their size)
DIP_THEIR_COST = 5.0            # USD per trade (their flat cost)
DIP_FILL_FROM = 1               # the first real trade from 1 minute after the bar close ...
DIP_FILL_WINDOW = 5             # ... within 5 minutes, else no trade
DIP_EXITS = ("L1", "L2")        # every tested version has the 50% stop and a profit lock
