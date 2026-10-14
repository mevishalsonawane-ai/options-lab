# IraAlgo crypto OPTIONS data lake (Deribit + Delta Exchange India → Google Drive)

*Built 13 Oct 2026 for Boss ("Fetch options data too"). Boss will BUY BTC/ETH options only, always with a stop-loss and a
profit lock (CLAUDE.md crypto rule). This lake only collects data; nothing in the app trades crypto. Data lives on Drive
only. Git holds the tool (`research/data/crypto_options_lake.py`), its tests, this README and the catalog.*

## Where it is

Google Drive folder **`IraAlgo-data-crypto/options/`** (rclone remote `gdrive:IraAlgo-data-crypto`, the same folder as
the Binance lake from `claude/crypto-lake`).

```
IraAlgo-data-crypto/options/
  options_catalog.json  options_catalog.csv  README.md      ← same as research/data/crypto/options_catalog.* in git
  deribit/trades/{BTC|ETH}/{YYYY}/{YYYY-MM}.parquet          every Deribit option trade, one month per file
  deribit/instruments/{BTC|ETH}.parquet                      every option instrument since 2016, expired included
  deribit/dvol/{BTC|ETH}.parquet                             DVOL (Deribit's 30-day implied-vol index), 1-minute
  deribit/index/{btc_usd|eth_usd}-delivery.parquet           daily 08:00 UTC settlement (delivery) index price
  delta_india/products.parquet                               Delta India BTC/ETH option contract specs, live + expired
  delta_india/fees/fees-{YYYY-MM-DD}.json                    fee schedule as fetched that day
  delta_india/candles/{BTC|ETH}/{YYYY}/{YYYY-MM-DD}.parquet  1m candles of every option expiring that day
  delta_india/snapshots/{YYYY}/{YYYY-MM-DD}/{HHMMSS}.parquet live option chain snapshots (from the nightly job)
```

Delta candles are one file per underlying per **expiry day** (all strikes, both types, a `symbol` column), not one file
per symbol: Delta lists hundreds of strikes per day, and tens of thousands of tiny files would make Drive and rclone
crawl. Filter by `symbol` / `strike` / `option_type` after reading.

What exactly is there: `options_catalog.csv` (one row per file: rows, first/last time, checks, sha256, md5, bytes) and
`python -I research/data/crypto_options_lake.py status`. The current summary is under **What is on Drive** below.

## Columns (all times are UTC epoch **milliseconds**, int64)

| file | columns |
|---|---|
| deribit/trades | `timestamp, trade_id, trade_seq, instrument_name, underlying, expiry_ts, strike, option_type (C/P), price, mark_price, iv, index_price, amount, contracts, direction (buy/sell = taker side), tick_direction (0 plus, 1 zero-plus, 2 minus, 3 zero-minus), liquidation (M/T/MT or null), block_trade_id, block_trade_leg_count, block_rfq_id, combo_id, combo_trade_id, extra` |
| deribit/instruments | `instrument_name, instrument_id, option_type, strike, expiration_timestamp, creation_timestamp, settlement_period, contract_size, tick_size, tick_size_steps, min_trade_amount, maker/taker/block_trade_commission, …` |
| deribit/dvol | `timestamp, open, high, low, close` (vol points, e.g. 45.2 = 45.2%) |
| deribit/index | `timestamp (08:00 UTC), date, delivery_price` (USD) |
| delta_india/products | `id, symbol, contract_type, state, underlying, strike_price, expiry_ts, launch_ts, contract_value (coin per contract), contract_unit_currency, tick_size, settlement_price, maker/taker_commission_rate, premium_commission_cap, quoting/settling_asset, notional_inr_at_strike, inr_per_usd_at_fetch, raw (full API JSON)` |
| delta_india/candles | `timestamp, symbol, product_id, underlying, option_type, strike, expiry_ts, open, high, low, close, volume` (premium in USD per 1 coin; volume in contracts) |
| delta_india/snapshots | `snapshot_ts, ticker_ts, symbol, strike_price, expiry_ts, option_type, best_bid, best_ask, bid/ask_size, bid_iv, ask_iv, mark_iv, mark_price, mark_vol, spot_price, oi, oi_contracts, oi_value_usd, volume, turnover_usd, greek_delta/gamma/theta/vega/rho/spot` (IVs as fractions, 0.38 = 38%) |

Things to know before using the data:
- **Deribit prices are in the coin** (BTC or ETH per 1 coin of underlying). USD premium = `price × index_price`.
  `amount` is in coins. Deribit options expire at **08:00 UTC**.
- **Deribit IV sentinels:** `iv = 0` means Deribit could not solve the IV (deep in-the-money trades at or under intrinsic);
  `iv = 999` is its cap. Filter `0 < iv <= 500` for analysis (the catalog counts both per month).
- `extra` is JSON for any field Deribit adds later, so nothing from the API is dropped.
- A deep in-the-money **put can cost more than 1 coin** (a 200,000 put with BTC at 83,000 is worth ~1.4 BTC). The
  price check is therefore "call < 1 coin, put × index ≤ strike" (see Checks).
- **Delta India options are priced in USD** per 1 coin, one contract = `contract_value` coins (BTC 0.001, ETH 0.01),
  settled in INR at the exchange's rate. They expire at **17:30 IST = 12:00 UTC**. The candle stamped exactly at expiry
  (the settlement print) and the flat candles after it are dropped.
- Delta's candle API also returns minutes with no trade (volume 0, price carried); use `volume > 0` for traded minutes.
- Delta candles are fetched from the product's launch, at most the last 120 days before expiry.
- DVOL: Deribit keeps **1-minute DVOL for only ~6 months**; `dvol/{coin}.parquet` (1m) starts 10 Apr 2026 and grows on every
  run, `dvol/{coin}_1h.parquet` (hourly) goes back to DVOL's start, 24 Mar 2021.

## Read it

```bash
rclone copy gdrive:IraAlgo-data-crypto/options ~/crypto-options --checksum --transfers 8 -P          # everything
rclone copy gdrive:IraAlgo-data-crypto/options/deribit/trades/BTC ~/co/deribit/trades/BTC -P        # one coin
rclone check gdrive:IraAlgo-data-crypto/options ~/crypto-options --one-way                         # verify
```

```python
import pandas as pd
t = pd.read_parquet("~/co/deribit/trades/BTC/2026/2026-09.parquet")
t = t[(t.iv > 0) & (t.iv <= 500)]
t["premium_usd"] = t.price * t.index_price
t["time"] = pd.to_datetime(t.timestamp, unit="ms", utc=True)
# all trades of one contract:
t[t.instrument_name == "BTC-26SEP26-110000-C"]
# a whole year at once (pyarrow dataset reads the folder):
import pyarrow.dataset as ds
d = ds.dataset("~/co/deribit/trades/BTC/2025", format="parquet").to_table(
        filter=(ds.field("option_type") == "C") & (ds.field("strike") == 100000)).to_pandas()
```

## Run / resume

Needs Python 3 with pandas + pyarrow, and rclone with the `gdrive` remote (env `RCLONE_CONFIG_GDRIVE_*`, as for the
Binance lake). Every command is idempotent: files already on Drive with the same md5 (per the catalog) are skipped, so
**to resume, run the same command again**.

```bash
python -I research/data/crypto_options_lake.py run --workers 6             # everything, Boss's priority order
python -I research/data/crypto_options_lake.py trades --currency BTC --since 2016-11 --until 2024-09 --workers 6
python -I research/data/crypto_options_lake.py instruments; python -I research/data/crypto_options_lake.py dvol
python -I research/data/crypto_options_lake.py index
python -I research/data/crypto_options_lake.py delta-products              # products + fees
python -I research/data/crypto_options_lake.py delta-candles --workers 4   # newest expiry first; --since YYYY-MM
python -I research/data/crypto_options_lake.py crosscheck --day 2026-10-12 # Delta vs Deribit IV
python -I research/data/crypto_options_lake.py requa --currency BTC        # re-run the checks on files on Drive
python -I research/data/crypto_options_lake.py status
```

- A Deribit month is checkpointed every 10 pages in `<work>/…/YYYY-MM.parquet.parts/` (`cursor.json` = last
  timestamp); a crash on the same box continues from there. A fresh box restarts that month only.
- The current month is marked `complete=false` and is rebuilt on every run until the month ends.
- DVOL resumes from the last timestamp of the file on Drive and appends.
- Delta products are merged by product id with the copy on Drive, so expired contracts are kept even if the API stops
  listing them.
- Two processes (e.g. Deribit and Delta) may run at once: the catalog is merged under a lock file on every save.
- Pacing: Deribit ≈3 requests/s, Delta ≈5/s, with back-off on HTTP 429 and Deribit error 10028.

## Nightly Delta India chain snapshot

Delta only serves candles (OHLCV) for history; bid/ask, IV, OI and Greeks exist only live. `snapshot` saves the whole
BTC/ETH option chain once; run it daily to build that history from now on. It needs no API key. It writes to Drive only
through rclone, and it never touches the catalog (so a box with an old catalog copy cannot overwrite the one on Drive).

Run it on any box that has the `gdrive` remote, e.g. cron (17:00 IST = 11:30 UTC, half an hour before the daily expiry):

```
30 11 * * *  cd ~/options-lab && python3 -I research/data/crypto_options_lake.py snapshot >> ~/snapshot.log 2>&1
```

Or as a GitHub Action, which needs ONE secret Boss would add (`RCLONE_GDRIVE_TOKEN` = the Drive token JSON of a remote
limited to this folder). Not added to `.github/` by this change; copy it there when wanted:

```yaml
name: crypto-options-snapshot
on:
  schedule: [{cron: "30 11 * * *"}]
  workflow_dispatch: {}
permissions: {contents: read}
jobs:
  snap:
    runs-on: ubuntu-latest
    env:
      RCLONE_CONFIG_GDRIVE_TYPE: drive
      RCLONE_CONFIG_GDRIVE_SCOPE: drive
      RCLONE_CONFIG_GDRIVE_TOKEN: ${{ secrets.RCLONE_GDRIVE_TOKEN }}
    steps:
      - uses: actions/checkout@v4
        if: env.RCLONE_CONFIG_GDRIVE_TOKEN != ''           # skips quietly until Boss adds the secret
      - uses: actions/setup-python@v5
        if: env.RCLONE_CONFIG_GDRIVE_TOKEN != ''
        with: {python-version: "3.12"}
      - if: env.RCLONE_CONFIG_GDRIVE_TOKEN != ''
        run: pip install pandas pyarrow && curl -sSf https://rclone.org/install.sh | sudo bash
      - if: env.RCLONE_CONFIG_GDRIVE_TOKEN != ''
        run: python -I research/data/crypto_options_lake.py snapshot
```

## Checks (per file, in the catalog)

- **Trades:** rows, `dup_ids` (must be 0, trade_id is unique per file), `not_monotonic` (0), `outside_window` (0),
  `unparsed_names` (0), `price_nonpositive` (0), `price_above_bound` (0: call price < 1 coin, put price × index ≤
  strike × 1.02), `price_ge_coin` (informational), `iv_out_of_range` (IV outside (0, 500]; split into `iv_zero` and
  `iv_over_500`), `days_without_trades` and the first 20 `empty_days`, trades per day min/max, block trades,
  liquidations, `saturated_pages` (pages that were all one millisecond; would mean a possible gap; expected 0).
  `ok` = all the must-be-zero checks; `iv_ok` = no IV outside (0, 500].
- **Delta candles:** products, products with candles, rows dropped at/after expiry, non-positive prices.
- **Cross-check** (`options_crosscheck.json`): one day, Delta India option candles vs Deribit trades on the same strike
  and expiry date; Delta IV is solved with Black-Scholes from the candle close, Deribit's index as spot and Delta's own
  expiry time.

## What is on Drive (13 Oct 2026; `status` prints the live numbers)

| data | range | files | rows | size |
|---|---|---|---|---|
| Deribit BTC option trades | 29 Nov 2016 → 13 Oct 2026 (every month) | 120 | 24,795,116 | 625 MB |
| Deribit ETH option trades | 21 Mar 2019 → 13 Oct 2026 (every month) | 92 | 16,452,911 | 358 MB |
| Deribit instruments | BTC 124,536 (expiries Jul 2016 → Sep 2027), ETH 126,437 | 2 | 250,973 | 2 MB |
| Deribit DVOL | 1m: 10 Apr 2026 →; 1h: 24 Mar 2021 → | 4 | 632,547 | 6 MB |
| Deribit delivery index | BTC 18 Mar 2016 →, ETH 29 Mar 2019 → (daily) | 2 | 5,114 | <1 MB |
| Delta India products | 150,762 BTC/ETH options, expiries 28 Dec 2023 → 25 Dec 2026 (807 live) | 1 | 150,762 | 6 MB |
| Delta India fees | fetched 13 Oct 2026 (also `research/data/crypto/delta_india_fees.json`) | 1 | | |
| Delta India 1m candles, BTC | expiry days 12 Jan 2024 → 13 Oct 2026 (993 of 1,018 days have candles) | 1,018 | 570,210,234 | 937 MB |
| Delta India 1m candles, ETH | expiry days 6 Feb 2024 → 13 Oct 2026 (961 of 982 days have candles) | 982 | 312,357,834 | 477 MB |

**How far back Delta India goes:** the product list starts with expiries on 28 Dec 2023. The candle API serves 1-minute
candles from the first options in **mid-January 2024** (BTC expiry 13 Jan 2024, ETH 6 Feb 2024) to today, for each
option's whole life (capped here at its last 120 days). Of 149,955 expired BTC/ETH options, 135,206 have candles.
Minutes without trades are filled with the last price and volume 0. 8 expiry days carry a few candles with a close of
0 (8,428 of 882M rows, mostly 5–7 Aug 2024); they are kept and those days have `ok = false`.

**Checks, all months:** no duplicate trade_ids (0 dropped at page edges, 0 left), time order strictly kept, 0 saturated
pages (no possible paging gap), every instrument name parsed. Days without trades exist only in each coin's launch
months (BTC Nov 2016 – Feb 2017, ETH Mar 2019). IV outside (0, 500]: BTC 95,859 of 24.8M trades (0.39%: 89,507 with
IV 0 and 6,352 over 500), ETH 72,315 of 16.5M (0.44%). Price above its no-arbitrage bound: 19 BTC trades in 8 months of
2017–2019 (e.g. 6 trades of a 3,400 call at exactly 1.0 BTC on 11 Jun 2017, IV 500); kept in the data, and those
months have `ok = false`. 12,025 BTC and 17,901 ETH trades cost ≥ 1 coin; they are deep in-the-money puts, which is
legal. The current month (Oct 2026) is `complete = false` and is rebuilt on the next run.

**Cross-check (12 Oct 2026, BTC, `options_crosscheck.json`):** 212 Delta India contracts had a Deribit twin (same strike,
same expiry date); 60 of them were compared minute by minute. Over 6,814 traded Delta minutes with a Deribit trade
within ±30 minutes, the Delta IV minus the Deribit IV had a median of +0.3 vol points and a mean absolute gap of 4.0
points; 79% were within 5 points and 93% within 10. The two venues price alike; Delta's expiry is 4 hours later
(12:00 vs 08:00 UTC).

**Gaps / not available:** Deribit 1-minute DVOL before Apr 2026 (hourly only); Deribit has no historical order book or
quotes in this public API (trades carry `mark_price`, `iv` and `index_price`); Delta India has no history of bid/ask,
IV, OI or Greeks (the nightly snapshot builds it from now) and no candles before Jan 2024.
