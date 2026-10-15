"""RC2 crypto options (TODO C5): constants. Written 13 Oct 2026, before any P&L was computed (see PREREG.md).

Every number a rule or a cost uses lives here, so the tests and the write-up read the same values."""
import os

import pandas as pd

# Raw data stays outside git (CLAUDE.md: no raw data in the repo). Default: the session scratchpad.
RAW = os.environ.get("RC2_RAW", "/tmp/claude-0/-home-user-options-lab/d63b0f44-082f-5112-a406-4a532f7bf8c8/scratchpad/raw")
OUT = os.environ.get("RC2_OUT", os.path.join(os.path.dirname(__file__), "results"))
# Trade lists (derived, large) stay beside the raw data, not in git.
TRADES = os.environ.get("RC2_TRADES", os.path.join(RAW, "..", "rc2_trades"))

COINS = ("BTC", "ETH")
BAR = pd.Timedelta(minutes=5)

# Delta Exchange India (live API, 13 Oct 2026): daily BTC/ETH options settle at 12:00 UTC = 17:30 IST.
DELTA_EXPIRY_HOUR_UTC = 12
# Deribit daily options settle at 08:00 UTC = 13:30 IST.
DERIBIT_EXPIRY_HOUR_UTC = 8
# No position in today's Delta expiry is held past 11:50 UTC (17:20 IST): ten minutes before settlement.
LAST_EXIT_BEFORE_EXPIRY = pd.Timedelta(minutes=10)
# Lot size (contract_value) in coins.
LOT = {"BTC": 0.001, "ETH": 0.01}
TICK = {"BTC": 0.1, "ETH": 0.01}

# Fees (Delta India API, 13 Oct 2026: taker_commission_rate 0.0001, premium_commission_rate 0.035; 18% GST from the fee
# page). Fee per side = min(rate x notional, cap x premium) x 1.18. A settlement at expiry is charged the taker fee.
FEE_RATE = 0.0001
FEE_RATE_STRESS = 0.0003      # older press material quotes 0.03%; used as a cost-stress reading
FEE_CAP = 0.035
GST = 0.18

# Prices are in USD; the account is in rupees (Delta India wallets are INR). Same rate as STRAD_CRYPTO.md (8 Oct 2026).
USDINR = 96.78

# Splits. Delta India option history starts in spring 2024 (probe: first daily options with candles near 2024-04).
HOLDOUT_START = pd.Timestamp("2026-04-13 00:00", tz="UTC")
HOLDOUT_END = pd.Timestamp("2026-10-13 12:00", tz="UTC")

# Money (Boss's small-account brief): Rs 25k and Rs 1 lakh, premium paid per trade <= 2% of the account, one position
# per rule per coin, and a daily loss cap per rule of 5% of the account (an IST day).
ACCOUNTS = (25_000, 100_000)
RISK_PER_TRADE = 0.02
DAILY_LOSS_CAP = 0.05

# Tax readings (a CA must confirm which applies): VDA 30% + 4% cess on each winning trade with no loss set-off; business
# income 30% + 4% cess on each financial year's net profit (the top slab; a lower slab only makes it smaller).
TAX = 0.312
TDS = 0.01

# Slippage on exits that are market orders (stop, lock, time): one tick plus 1% of the price.
EXIT_SLIP_TICKS = 1
EXIT_SLIP_FRAC = 0.01
