# Dhan historical data (research reference)

Market data downloaded from Dhan's v2 data APIs for our own analysis (zero-to-hero / Hero-arm studies, replays).
Not for redistribution. No credentials are stored anywhere in this tree.

- Fetched: 2026-10-06 (UTC dates of the requests) by `fetch.py`
  (scratchpad tool; endpoints and payloads per the official DhanHQ-py SDK). Staged 2026-10-06 14:34 IST.
- Total: 1892 parquet files, 3.29 GB; every file < 95 MB; all zstd parquet.
- `manifest.csv` (next to this README): path, size (bytes), sha256, rows, from, to (IST dates of first/last row).
- All paths below are under `dhan-data/`.

## Layout

| path | what |
|---|---|
| `options/<U>/<WEEK\|MONTH>/<CALL\|PUT>/<YYYY>.parquet` | index expired options, 1-minute, ATM-10..ATM+10, Dhan `POST /v2/charts/rollingoption` (expiryCode 1 = nearest expiry) |
| `candles/minute/<SEGMENT>/<SYMBOL>/<YYYY>.parquet` | 1-minute candles (`/v2/charts/intraday`), IDX_I = indices incl. INDIA VIX, NSE_EQ = companies |
| `candles/daily/<SEGMENT>/<SYMBOL>.parquet` | daily candles (`/v2/charts/historical`) |
| `futures/<SEGMENT>/<U>/<EXPIRY>_{minute,daily}.parquet` | live futures contracts at fetch time, with OI |
| `optionchain/<YYYY-MM-DD>/<U>.parquet` | option-chain snapshot (all listed expiries, column `expiry`), taken during that day's session |

An options year file holds the 30-day request windows that START in that year (windows run from 2020-08-01 in
30-day steps), so a few early-January days can sit in the previous year's file: always filter on `ts`.
Files over 95 MB are split as `<YYYY>_<YYYY>H1.parquet` etc.

## Columns and units

- `ts`: bar start, timezone-aware IST (Asia/Kolkata, +05:30). Daily bars are stamped 00:00 IST.
- `open, high, low, close` (float32, rupees); `volume` (int64); `oi` / `open_interest` (int64).
- **OI and volume are in units (quantity of the underlying), NOT lots/contracts.** Checked live on 2026-10-06:
  NIFTY 22650 CE rolling OI 19.38M at 11:03 IST vs option-chain OI 19.26M at 11:05 = ~296k lots of 65.
- options: `offset` (int8, strike steps from ATM at that minute; ATM = 0), `strike` (float32, the actual strike that
  offset pointed to THAT minute; it changes as spot moves), `spot` (float32, underlying at that minute), `iv` (float32, %).
  A file is one side (CALL or PUT) of the rolling series "nearest expiry, n strikes from the money". To follow one
  contract, group on (strike, side) within an expiry. Expiry days are not labelled; detect them (e.g. the ATM straddle
  at the 15:29 bar is almost all intrinsic value) or use an exchange calendar.
- Bars outside 09:15-15:29 IST appear in some older data (e.g. 2021 index minute data has 09:00-17:59 rows; some 2021
  option rows are stamped up to 19:15). They are kept as served; filter to the session.
- Daily candles start where Dhan's history starts (requests from 2000-01-01 in 5-year windows; earlier windows
  returned DH-907 "no data").

## Index options coverage

| u | flag | files | rows | mb | first | last |
|---|---|---|---|---|---|---|
| BANKEX | MONTH | 8 | 7295015 | 93 | 2023-10-05 | 2026-10-06 |
| BANKEX | WEEK | 4 | 3800785 | 63 | 2023-10-05 | 2024-11-29 |
| BANKNIFTY | MONTH | 12 | 20074127 | 374 | 2021-08-04 | 2026-10-06 |
| BANKNIFTY | WEEK | 8 | 12893741 | 253 | 2021-08-04 | 2024-11-29 |
| FINNIFTY | MONTH | 12 | 11834550 | 154 | 2021-08-05 | 2026-10-06 |
| FINNIFTY | WEEK | 8 | 10455966 | 162 | 2021-08-04 | 2024-11-29 |
| MIDCPNIFTY | MONTH | 10 | 9917508 | 146 | 2022-01-31 | 2026-10-06 |
| MIDCPNIFTY | WEEK | 4 | 5603557 | 90 | 2023-04-03 | 2024-11-29 |
| NIFTY | MONTH | 14 | 24105217 | 421 | 2020-08-03 | 2026-10-06 |
| NIFTY | WEEK | 14 | 24138767 | 428 | 2020-08-03 | 2026-10-06 |
| NIFTYNXT50 | MONTH | 4 | 1018958 | 8 | 2025-11-26 | 2026-10-06 |
| SENSEX | MONTH | 8 | 8380157 | 120 | 2023-05-15 | 2026-10-06 |
| SENSEX | WEEK | 8 | 12790868 | 239 | 2023-05-15 | 2026-10-06 |

Requests started 2020-08-01 for every series (a probe of 2020-07 returned nothing); the first/last columns show
what Dhan actually served (e.g. BANKNIFTY/FINNIFTY only from 2021-08, NIFTYNXT50 only from 2025-11 although asked
from 2024-04). Weekly series of BANKNIFTY/FINNIFTY/MIDCPNIFTY/BANKEX were requested only to 2024-11-30 (weeklies
discontinued Nov 2024); SENSEX from 2023-05, MIDCPNIFTY from 2022-01, BANKEX from 2023-10. Dhan served ATM+-10
for every index series; far strikes of early or illiquid windows come back empty (no rows). `coverage.csv` (next to
this README) has per series: trading days, detected expiry days and the number of days each offset has data. The current (still open) 30-day
window was fetched mid-session on the fetch day: its last day is partial.

## Candles

| kind | seg | instruments | files | rows | mb | first | last |
|---|---|---|---|---|---|---|---|
| daily | IDX_I | 173 | 173 | 364265 | 10 | 2000-01-03 | 2026-10-05 |
| daily | NSE_EQ | 214 | 214 | 894274 | 23 | 2000-01-03 | 2026-10-05 |
| minute | IDX_I | 10 | 53 | 4495597 | 82 | 2021-10-08 | 2026-10-06 |
| minute | NSE_EQ | 214 | 640 | 39349757 | 573 | 2024-10-07 | 2026-10-06 |

Key indices:

| kind | sym | rows | first | last |
|---|---|---|---|---|
| daily | BANKEX | 5144 | 2006-01-02 | 2026-10-05 |
| daily | BANKNIFTY | 5144 | 2006-01-02 | 2026-10-05 |
| daily | FINNIFTY | 5145 | 2006-01-02 | 2026-10-05 |
| daily | INDIA_VIX | 4669 | 2007-11-01 | 2026-10-05 |
| daily | MIDCPNIFTY | 1160 | 2022-01-31 | 2026-10-05 |
| daily | NIFTY | 5145 | 2006-01-02 | 2026-10-05 |
| daily | NIFTYNXT50 | 5145 | 2006-01-02 | 2026-10-05 |
| daily | SENSEX | 5144 | 2006-01-02 | 2026-10-05 |
| minute | BANKEX | 496290 | 2021-10-08 | 2026-10-06 |
| minute | BANKNIFTY | 514902 | 2021-10-08 | 2026-10-06 |
| minute | FINNIFTY | 514895 | 2021-10-08 | 2026-10-06 |
| minute | INDIA_VIX | 519446 | 2021-10-08 | 2026-10-06 |
| minute | MIDCPNIFTY | 459223 | 2022-01-31 | 2026-10-06 |
| minute | NIFTY | 514902 | 2021-10-08 | 2026-10-06 |
| minute | NIFTYNXT50 | 514898 | 2021-10-08 | 2026-10-06 |
| minute | SENSEX | 496469 | 2021-10-08 | 2026-10-06 |

Index/VIX minute candles cover the last ~5 years (Dhan's limit; 90-day requests); company minute candles only the
last 2 years and only F&O indices + VIX have minute data (disk limits on the fetch machine; daily candles exist for
every index Dhan lists and every company fetched). 477 futures files, 221 option-chain files.

## What is missing / caveats

- Company (stock) expired options were NOT downloaded (lowest priority, switched off for disk space; Dhan serves only
  ATM+-3 for them, verified on RELIANCE).
- Futures: every live index future, and only the nearest contract for each company (disk).
- Expired futures are not served by Dhan's API; only contracts live on the fetch day are here.
- Index constituents used to pick companies are a static list (Dhan's instrument master has no membership data) plus
  every F&O stock in Dhan's master on the fetch day; renamed/delisted symbols (e.g. TATAMOTORS, LTIM, JINDALSTL in the
  static list) are absent.
- 16 request(s) ended in failure in the fetch log (retried; see coverage tables for what landed).
