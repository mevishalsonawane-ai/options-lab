# Data lake (Dhan API v2)

Historical market data for IraAlgo research, downloaded from Dhan API v2 on 2026-10-13/14 (UTC; data up to
2026-10-12), with index data appended on 2026-10-14/15 (to 13 or 14 Oct, see Known gaps). It is stored as Parquet (zstd) files on **Google Drive** (below; a GitHub Release with tag `data-20261014`
is optional), **not** in git. This folder holds only code and metadata:

| file | what |
|---|---|
| `CATALOG.json` | every data file: rows, first/last timestamp, bytes, sha256, source endpoint, columns |
| `STOCK_OPTIONS_COVERAGE.json` | fraction of stock-option chunks fetched, per stock and ATM offset |
| `VALIDATION.md` / `VALIDATION.json` | checks against NSE bhavcopy, NSE index closes and Yahoo; gaps, duplicates, bad prices |
| `fetch_release.py` | download assets of the release and verify sha256 (`--list`, `--match opt_NIFTY_WEEK`) |
| `publish_release.py` | create the release and upload the files (run where release creation is allowed) |
| `publish_gdrive.py` | upload the built files to Google Drive with rclone, per dataset, then `rclone check` |
| `dhan_client.py` | tiny Dhan v2 client: token from env, sent only as headers, redacted from logs, backoff on 429/5xx |
| `lake_fetch.py` | resumable raw download (one small Parquet per request chunk, `.empty`/`.err` markers); `--only-missing LAKE_DIR` turns published files back into raw chunks so only real gaps are requested; `--before`/`--since DATE` |
| `lake_build.py` | raw chunks → release files, with cleaning rules below and per-file QA |
| `bhav_fetch.py` | sample of NSE F&O bhavcopies, NSE all-index closes and Yahoo index history for validation |
| `validate.py`, `make_catalog.py` | write `VALIDATION.md` and `CATALOG.json` (`make_catalog.py --restore-qa LAKE_DIR` rebuilds `_qa/` for files downloaded from Drive) |
| `stkopt_coverage.py` | write `STOCK_OPTIONS_COVERAGE.json` from the raw stock-option chunks |

## Where the data lives: Google Drive `gdrive:IraAlgo-data-lake`

The lake is kept in the Google Drive folder **IraAlgo-data-lake**, one sub-folder per dataset
(`index_1m/`, `options_rolling_1m/`, …, `nse_bhavcopy/`). The folder root holds `MANIFEST.txt` (path, rows, sha256,
bytes of every file), `STATUS.txt` (what is there and what is missing), `CATALOG.json` and `VALIDATION.*`.
**As of 2026-10-15 every file in `CATALOG.json` is on Drive** (187 Parquet files, 12 dataset folders
including `nse_bhavcopy/`, all verified with `rclone check`). Stock options are partial (see Known gaps). The folders
`brain_models/`, `derived/` and `participants/` on Drive were uploaded separately and are not described here.

Download with [rclone](https://rclone.org) (remote `gdrive` = Google Drive, set up with `rclone config`):

```bash
rclone copy gdrive:IraAlgo-data-lake ~/lake --checksum --transfers 4 -P     # everything
rclone copy gdrive:IraAlgo-data-lake/index_1m ~/lake/index_1m -P            # one dataset
rclone check gdrive:IraAlgo-data-lake ~/lake --one-way                      # verify a download
```

Upload after a build (each dataset is copied with `--checksum`, then checked, so finished datasets stay safe):

```bash
python -I research/data/publish_gdrive.py $OUT --extra nse_bhavcopy=/path/bhav \
   --meta research/data/CATALOG.json research/data/VALIDATION.md research/data/VALIDATION.json \
   research/data/STOCK_OPTIONS_COVERAGE.json
```

## Getting the data from a GitHub Release (if one is published)

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

## Known gaps in this download (updated 2026-10-15)

* **Index data, appended on 14/15 Oct.** Index daily (incl. INDIAVIX), index 1-minute (7 indices), continuous futures
  daily, live index and MCX futures 1-minute: **complete to 14 Oct 2026**. Index options (rolling ATM±10):
  NIFTY WEEK (incl. the **13 Oct expiry day**), NIFTY MONTH and BANKNIFTY WEEK to **14 Oct**; BANKNIFTY MONTH,
  SENSEX, FINNIFTY and MIDCPNIFTY (WEEK and MONTH) and the six MCX option series only to **13 Oct**. Their last chunk
  was fetched after 00:00 IST on 15 Oct, and Dhan's `/charts/rollingoption` returned no 14 Oct minutes at that hour; the
  token expired (HTTP 401 DH-901) at about 04:20 UTC on 15 Oct before they could be asked again.
  The 14 Oct minutes only enter a build once Dhan's daily series has 14 Oct (cleaning rule below); the daily candle
  appeared between 18:35 UTC on 14 Oct and 04:15 UTC on 15 Oct.
  Equity minutes/daily, stock futures and live stock futures were **not** refreshed (still to 12/13 Oct).
* **Stock options are still incomplete.** Fetched share of the planned 30-day chunks (`STOCK_OPTIONS_COVERAGE.json`,
  per stock and offset, 1.0 = complete): ATM 99.4% (every stock ≥94.5%; ADANIENT was 79%), ATM±1 100%,
  ATM±2 23%, ATM±3..±5 none. About 57,000 chunks remain. Stock-option files end on 12 Oct except where the newest
  window was fetched again (405 of 1,298 series), which end on 14 Oct.
  Why: Dhan rate-limited the account (HTTP 429 DH-904) on every data endpoint for most of 14 Oct, even at one
  request per second; when it lifted, stock-option windows often timed out (HTTP 504), so a night gave only about
  3,500 chunks (most of them windows that were empty last time, asked again); then the token expired.
  Quality: index options match NSE bhavcopy contract-day high/low 86–98%, but stock options match only about 68%
  (median day exact, tails ±10%). Treat stock-option minutes with care (see VALIDATION.md section 3).
  **To finish (no refetch of what is on Drive):** download the lake from Drive to `$OUT`, then
  `python -I research/data/make_catalog.py --restore-qa $OUT` and
  `python -I research/data/lake_fetch.py $RAW --only-missing $OUT stkopt` (resumable; stops with exit 3 on DH-905 or
  an expired token). Before building with newest windows still unfetched, write stand-ins from the published rows
  (`lake_fetch.seed(RAW, OUT, "stkopt", upto_last=True)`) so the build keeps them. Then `lake_build.py $RAW $OUT stkopt`,
  `stkopt_coverage.py`, `validate.py`, `make_catalog.py`, `publish_gdrive.py`.
* QA counts of rebuilt option and index files add the published files' dropped-row counts to those of the
  re-fetched newest window, so the window that was fetched twice is counted twice (for example NIFTY WEEK
  `outside_session` 44,353 instead of about 40,993); the data rows themselves are not affected.
* Every other dataset was fetched with no failed chunks left (the gaps of the 12 Oct download, in HINDUNILVR equity
  minutes, FINNIFTY options and live stock futures, are filled).

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
`lake_fetch.py` stops with exit code 3 after 50 answers in a row with DH-905 (Dhan refuses every data call, usually
the daily cap; re-run after 00:00 IST) or HTTP 401 / DH-901 (expired token), so such a run does not turn every
remaining chunk into an `.err`.
