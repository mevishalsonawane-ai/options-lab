# IraAlgo crypto data lake (Binance public archive → Google Drive)

*Built 13 Oct 2026 for Boss ("Import crypto data and save it in Google Drive"). Data lives on Drive only. Git holds the
tool (`research/data/crypto_lake.py`), its tests and this catalog, never the data.*

**Window: the last 3 years (2023-10 onwards)** (Boss, 13 Oct: "download only last 3 yrs"); aggTrades: the last 12
months. `--since YYYY-MM` changes it.

| tier | symbols | datasets |
|---|---|---|
| 1 | BTCUSDT, ETHUSDT | spot 1m, futures 1m, funding, mark 1m, index 1m, metrics (5-min OI + long/short) |
| 2 | SOLUSDT, BNBUSDT, XRPUSDT | spot 1m, futures 1m, funding |
| 3 | BTCUSDT, ETHUSDT | futures aggTrades, last 12 months |
| 4 | top 50 of **all** USD-M perpetuals by last month's quote volume (`top_symbols.csv`, `--top N`) | futures 1m, funding, spot 1m where a spot pair exists |

Tier 4's top 50 (ranked on Sep 2026) includes Binance's TradFi perpetuals: gold XAUUSDT, silver XAGUSDT, crude CLUSDT and
BZUSDT, and stock perps such as NVDAUSDT, MSTRUSDT, MUUSDT and SKHYNIXUSDT. Those have no spot pair, so only futures and
funding exist for them, and their history starts at their (recent) listing.

## Where it is

Google Drive folder **`IraAlgo-data-crypto`** (rclone remote `gdrive:IraAlgo-data-crypto`). It is a sibling of the Dhan
lake's `IraAlgo-data-lake`.

```
IraAlgo-data-crypto/
  catalog.json  catalog.csv  catalog_series.csv  README.md   ← the same files as research/data/crypto/ in git
  spot/klines_1m/{SYMBOL}/{YYYY}/{SYMBOL}-klines_1m-{YYYY-MM}.parquet
  futures_um/klines_1m/{SYMBOL}/{YYYY}/…         USD-M perpetual futures 1m klines
  futures_um/markprice_1m/{SYMBOL}/{YYYY}/…      mark-price 1m klines
  futures_um/indexprice_1m/{SYMBOL}/{YYYY}/…     index-price 1m klines
  futures_um/funding/{SYMBOL}/{YYYY}/…           funding rate, one row per funding event
  futures_um/metrics/{SYMBOL}/{YYYY}/…           open interest + long/short ratios, every 5 min
  futures_um/aggtrades/{SYMBOL}/{YYYY}/…         aggregated trades (tier 3, recent 12 months, if present)
```

What exactly is there is in `catalog.json` / `catalog.csv` (one row per monthly file: rows, first/last time, gaps,
sha256, md5, bytes) and `catalog_series.csv` (one row per series). `python -I research/data/crypto_lake.py status`
prints a summary.

## Columns (all times are UTC epoch **milliseconds**, int64)

| dataset | columns |
|---|---|
| klines_1m, markprice_1m, indexprice_1m | `open_time, open, high, low, close, volume, close_time, quote_volume, count, taker_buy_volume, taker_buy_quote_volume` (mark/index: volume columns are 0, `count` = samples) |
| funding | `calc_time, funding_interval_hours, last_funding_rate` |
| metrics | `create_time, sum_open_interest, sum_open_interest_value, count_toptrader_long_short_ratio, sum_toptrader_long_short_ratio, count_long_short_ratio, sum_taker_long_short_vol_ratio` (empty fields are NaN) |
| aggtrades | `agg_trade_id, price, quantity, first_trade_id, last_trade_id, transact_time, is_buyer_maker` |

## Download

Set up rclone once (`rclone config`, a Google Drive remote named `gdrive`), then:

```bash
rclone copy gdrive:IraAlgo-data-crypto ~/crypto --checksum --transfers 8 -P                      # everything
rclone copy gdrive:IraAlgo-data-crypto/spot/klines_1m/BTCUSDT ~/crypto/spot/klines_1m/BTCUSDT -P  # one series
rclone copy gdrive:IraAlgo-data-crypto ~/crypto --include "futures_um/funding/**" -P             # one dataset
rclone check gdrive:IraAlgo-data-crypto ~/crypto --one-way                                      # verify a download
```

```python
import pandas as pd
df = pd.read_parquet("~/crypto/spot/klines_1m/BTCUSDT")          # all years; or one month's file
df["ts"] = pd.to_datetime(df["open_time"], unit="ms", utc=True)
```

The `sha256` in the catalog lets you verify a file without rclone (`sha256sum`).

## Source and checks

- Source: <https://data.binance.vision> (monthly zips, plus daily zips for the current month and for `metrics`, which
  only exists daily). Binance's REST API is geo-blocked from the cloud boxes (HTTP 451); the archive is not. Every zip's
  sha256 is checked against its `.CHECKSUM` before it is read (one retry; a second mismatch fails the month).
- Some 2025+ spot files use **microsecond** timestamps; they are converted to ms (`ts_unit_fixed` in the catalog).
- Per month: rows vs expected minutes (or 5-min slots for metrics), `missing` and the first 20 gap ranges
  (**gaps are listed, never filled**), exact duplicate rows dropped (`dup_exact`), conflicting duplicate timestamps
  dropped keeping the first (`dup_conflict`), rows outside the month dropped (`outside_dropped`), sort order, OHLC
  sanity (`ohlc_bad`), off-grid timestamps (`off_grid`). `ok` is false if any conflict, bad OHLC or off-grid row exists.
- Once per year per kline series: the 1m close of the 23:59 candle on the 15th is compared with the close in Binance's
  own 1d klines file (`daily_check`).
- `complete: false` marks a month that is still running (the current month, from daily zips) or a daily-only month
  with missing days. The next run replaces it when the monthly zip appears.
- The first month of each series starts at its listing minute, so listing-day minutes are not counted as gaps.
- Funding has no daily files in the archive, so the current month's funding appears only after Binance publishes the
  monthly zip (early next month).

## Re-running, resuming, adding

```bash
python -I research/data/crypto_lake.py run --tier 1              # BTC/ETH: spot+futures 1m, funding, mark, index, metrics
python -I research/data/crypto_lake.py run --tier 2              # SOL/BNB/XRP: spot+futures 1m, funding
python -I research/data/crypto_lake.py run --tier 3              # BTC/ETH futures aggTrades, last 12 months
python -I research/data/crypto_lake.py run --tier 4 --top 50     # top 50 perpetuals of all symbols
python -I research/data/crypto_lake.py run --tier 1 --since 2017-01   # older history, if ever wanted
python -I research/data/crypto_lake.py run --series um/funding/SOLUSDT --dry-run   # plan one series
python -I research/data/crypto_lake.py status
```

Threads: `--parallel` series at once (default 4), `--workers` months per series (default 6), and 4 zip downloads
per month. Uploads to Drive (about 1–2 files a second) are the bottleneck, not downloads. Needs `rclone` (with the `gdrive` remote, e.g. from `RCLONE_CONFIG_GDRIVE_*` env vars), `pandas` and `pyarrow`. Runs are
idempotent: a month is skipped when the catalog has it with the same source zips **and** Drive holds a file with the
same size and md5. Months are built in batches (24 at a time; aggTrades 1 at a time) in a temp folder, copied with
`rclone copy --checksum`, verified with `rclone check --one-way`, recorded in the catalog, then deleted locally. If
the local catalog is missing (a fresh box), it is fetched from Drive first. Failed
months go to `failures` in the catalog and are retried on the next run. After a run, commit the updated
`research/data/crypto/catalog.json` and `catalog.csv`.
