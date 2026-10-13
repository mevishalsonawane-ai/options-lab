# Data lake (Dhan API v2)

Historical market data for IraAlgo research, downloaded from Dhan API v2 on 2026-10-12. It is stored as
Parquet (zstd) files meant to be **GitHub Release assets** (tag `data-20261012`), **not** in git. This folder holds
only code and metadata:

| file | what |
|---|---|
| `CATALOG.json` | every data file: rows, first/last timestamp, bytes, sha256, source endpoint, columns |
| `STOCK_OPTIONS_COVERAGE.json` | fraction of stock-option chunks fetched, per stock and ATM offset |
| `VALIDATION.md` / `VALIDATION.json` | checks against NSE bhavcopy, NSE index closes and Yahoo; gaps, duplicates, bad prices |
| `fetch_release.py` | download assets of the release and verify sha256 (`--list`, `--match opt_NIFTY_WEEK`) |
| `publish_release.py` | create the release and upload the files (run where release creation is allowed) |
| `dhan_client.py` | tiny Dhan v2 client: token from env, sent only as headers, redacted from logs, backoff on 429/5xx |
| `lake_fetch.py` | resumable raw download (one small Parquet per request chunk, `.empty`/`.err` markers) |
| `lake_build.py` | raw chunks → release files, with cleaning rules below and per-file QA |
| `bhav_fetch.py` | sample of NSE F&O bhavcopies, NSE all-index closes and Yahoo index history for validation |
| `validate.py`, `make_catalog.py` | write `VALIDATION.md` and `CATALOG.json` |

## Getting the data

```bash
python research/data/fetch_release.py --list                      # what exists
python research/data/fetch_release.py --match index_1m_NIFTY --dest ~/lake
python research/data/fetch_release.py --dest ~/lake                # everything
```

```python
import pandas as pd
df = pd.read_parquet("~/lake/opt_NIFTY_WEEK_2024.parquet")        # ts is tz-aware IST
atm_ce = df[(df.offset == 0) & (df.side == "C")]
```

## Files

All timestamps are **IST (Asia/Kolkata)**. Minute bars are stamped at the **start** of the minute (09:15 is the first
NSE/BSE minute, 15:29 the last). Daily files have a naive `date` column.

| file pattern | content | columns |
|---|---|---|
| `index_daily.parquet` | daily OHLC: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, BANKEX, INDIAVIX | symbol, date, open, high, low, close, volume |
| `index_1m_<SYM>_<YEAR>.parquet` | 1-minute index OHLC, same 7 indices | ts, open, high, low, close, volume |
| `opt_<UND>_<WEEK\|MONTH>_<YEAR>.parquet` | expired + live options, rolling by ATM offset, 1-minute, nearest expiry (expiry code 1) | ts, offset (-10..+10), side (C/P), open, high, low, close, iv, volume, strike, oi, spot |
| `fut_daily_continuous.parquet` | continuous **near-month** index (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, BANKEX) & MCX (CRUDEOIL, NATURALGAS, GOLD, GOLDM, SILVER, SILVERM) futures, daily | symbol, expiry_code (=0), date, OHLC, volume, oi |
| `fut_1m_live_index.parquet`, `fut_1m_live_mcx.parquet` | 1-minute futures for contracts **live on the download date** only | symbol, expiry, security_id, ts, OHLC, volume, oi |
| `equity_daily.parquet` | daily OHLCV, current NIFTY 50 + NIFTY BANK constituents (59 stocks) | symbol, date, OHLC, volume |
| `equity_1m_<YEAR>.parquet` | 1-minute OHLCV for the same 59 stocks, 2017 → | symbol, ts, OHLC, volume |
| `stock_fut_daily_continuous.parquet` | continuous near-month stock futures, daily, same stocks | symbol, expiry_code (=0), date, OHLC, volume, oi |
| `fut_1m_live_stock.parquet` | 1-minute stock futures, live contracts only | symbol, expiry, security_id, ts, OHLC, volume, oi |
| `stkopt_<SYM>_MONTH.parquet` | stock options, rolling ATM-5..ATM+5, nearest monthly expiry, 1-minute, Aug 2021 →. **Partial**, see below | as the index option files |
| `opt_<MCXSYM>_MONTH_<YEAR>.parquet` | MCX options (CRUDEOIL, NATURALGAS, GOLD, GOLDM, SILVER, SILVERM), ATM-5..ATM+5, 1-minute, from mid-2025 | as the index option files |

### How the option series work

Dhan's `/charts/rollingoption` returns, for each minute, the contract that was `ATM+k` **at that minute** for the
nearest expiry of the chosen type. So the `strike` column moves as spot moves, and the contract rolls to the next
expiry after expiry. `spot` is the underlying value at that minute, `oi` is open interest in units (lots × lot size),
`iv` is Dhan's implied volatility (%). Use `strike` + `side` + the date to identify the contract.
`WEEK` = nearest weekly expiry; `MONTH` = nearest monthly expiry. NSE discontinued BANKNIFTY, FINNIFTY and MIDCPNIFTY
weeklies in Nov 2024. After that date, Dhan's `WEEK` series for those indices follows the nearest available
(monthly) expiry.

## Known gaps in this download (2026-10-12/13)

* **Stock options are incomplete.** The download stopped at Dhan's **100,000 data requests per day** cap (DH-905 on
  every data call from about 03:00 UTC on 13 Oct, quota resets at 00:00 IST). The access token expired before the
  reset. Coverage of the planned 30-day chunks: ATM 78%, each of ±1..±5 about 23%. 10 stocks are ≥90% complete
  at every offset ATM-5..+5 (alphabetically ADANIENT..BSE). 27 of 59 have ≥90% of ATM. TATASTEEL has 47% of ATM only, and
  **TCS, TECHM, TITAN, TMPV, TRENT, ULTRACEMCO, UNIONBANK and YESBANK have no option file at all** (51 files exist).
  Per stock and offset: `STOCK_OPTIONS_COVERAGE.json` (fraction of chunks fetched, 1.0 = complete).
  Quality: index options match NSE bhavcopy contract-day high/low 87–98%, but stock options match only about 69%
  (median day exact, tails ±10%). Treat stock-option minutes with care (see VALIDATION.md section 3).
  **To finish:** set a fresh token and run
  `python -I research/data/lake_fetch.py $RAW stkopt` (needs the same `$RAW` folder to resume; on a fresh machine it
  starts over, about 83k requests = one day's quota), then `lake_build.py ... stkopt`, `validate.py`, `make_catalog.py`.
* Chunks that still failed after retries (HTTP 429/504), left as gaps: HINDUNILVR equity 1-min, 7 windows (2015–2018);
  FINNIFTY options, 8 series-months; live stock futures 1-min, 26 windows (INFY, ITC, INDUSINDBK, UNIONBANK, …).
  Re-running the fetch command retries exactly these.

## Cleaning rules (nothing is filled)

* Minute bars outside 09:15–15:29 IST are dropped for NSE/BSE. In 2021 and early 2022, Dhan pads index minute data with flat
  pre-open bars from 07:06 (copies of the previous close, volume 0).
* Minute bars on dates that are not a trading day in the same exchange's Dhan daily series are dropped. This removes NSE
  weekend mock-trading sessions (2021–22) and the partial download day. Real special sessions such as Muhurat and
  Budget Saturdays are kept.
* Rows with a zero, negative or missing price are dropped and counted (for example MIDCPNIFTY futures in early 2022).
* Identical duplicate rows: one kept. Same key with different values: **all copies removed** and counted (for example
  SHRIRAMFIN 2005–2012, where Dhan interleaves two pre-merger price histories).
* Missing minutes stay missing (options often do not trade every minute). Per-file counts of everything dropped are in
  each `CATALOG.json` entry's `qa` field and summarised in `VALIDATION.md`.

## What Dhan does not provide (or refused)

* **Historical bid/ask, depth, or tick data.** Only OHLCV(+OI, IV) bars. Bid/ask exists only live (option-chain
  snapshot, market feed).
* **Expired futures contracts at 1 minute.** `/charts/intraday` serves only contracts live today, so futures minutes
  only go back to each live contract's listing. For history use `fut_daily_continuous`, or the option files' `spot`.
* **Index minutes before 2017 / options before Aug–Oct 2021.** Intraday history is about 5 years for most indices
  (NIFTY back to Apr 2017). `rollingoption` starts in Aug–Oct 2021 for NSE and BSE, and in mid-2025 for MCX.
* **Next/far-month continuous futures.** `/charts/historical` returns the identical near-month series for expiryCode
  0, 1 and 2, so only code 0 is kept. That series rolls to the next contract **on** expiry day.
* `rollingoption` covers ATM±10 only, nearest expiry (code 1) here; requests are limited to about 30 days each, and
  intraday to 90 days.
* Equity history uses **current** index constituents, so it has survivorship bias. Prices appear
  corporate-action adjusted.

## Re-creating the lake

```bash
export DHAN_ACCESS_TOKEN=... DHAN_CLIENT_ID=...     # never commit these
RAW=/path/raw OUT=/path/out
LAKE_THREADS=40 python -I research/data/lake_fetch.py $RAW idx_daily idx_1m fut_daily fut_1m eq_daily \
   opt_NIFTY_WEEK opt_BANKNIFTY_WEEK opt_NIFTY_MONTH opt_BANKNIFTY_MONTH opt_FINNIFTY_WEEK opt_FINNIFTY_MONTH \
   opt_MIDCPNIFTY_WEEK opt_MIDCPNIFTY_MONTH mcxopt opt_SENSEX_WEEK opt_SENSEX_MONTH \
   eq_1m stkfut_daily stkfut_1m stkopt
python -I research/data/lake_build.py $RAW $OUT idx_daily idx_1m fut_daily fut_1m eq_daily opt \
   eq_1m stkfut_daily stkfut_1m stkopt
python -I research/data/bhav_fetch.py /path/bhav 5
python -I research/data/validate.py $OUT /path/bhav
python -I research/data/make_catalog.py $OUT data-YYYYMMDD YYYY-MM-DD
GH_TOKEN=... python research/data/publish_release.py $OUT
```

About 125,000 Dhan requests (83,000 of them stock options). That is **more than one day's quota (100,000 data
requests/day, retries included)**, so plan two days or two tokens. Dhan answers about 3.5 index-option requests per
second at 40 threads (stock options 1–2/s; slower in Indian market hours) and rate-limits above 5/s. Failed chunks (`.err`) are retried just by running the same
command again.
