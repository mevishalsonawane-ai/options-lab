# R89, 20 Oct 2026: profit and win rate of a strategy built on R88: RESULT

Boss: "If we create a strategy based on the above findings, what will the profit and win rate be?"
Write-up: `research/HUNT_R89_SIZE_STRADDLE.md` (pre-registered `research/hunt/r89/PREREG.md`, 5f4f988, before any P&L).

- R88 forecasts candle SIZE, not direction, so the strategy tested is a straddle (buy ATM call + ATM put) when a big
  15-minute candle is expected, held 15 or 60 minutes, on 5 indices and GOLDM, SILVERM, CRUDEOIL, NATURALGAS, with real
  option minutes, spreads and charges.
- **It loses: 0 of 18 versions profitable.** Design (Jun 2025 - May 2026): 4,302 straddles, -Rs 12.8 lakh, win rate
  2-40%. Out of sample (Jun - Oct 2026): 2,214 straddles, -Rs 4.4 lakh, win rate 3-35% (indices 23-35%, crude and
  natural gas 3-11%).
- Before costs it about breaks even (options already price the bigger move); costs (4 orders) make every version
  negative; random timing loses about the same. Nothing goes to paper.

---

# R88, 20 Oct 2026: what moves the candle (every candle, every parameter): RESULT

Boss: "deep study each and every candle, the behaviour, and the other parameters which affect the candle movement".
Write-up: `research/HUNT_R88_WHAT_MOVES_THE_CANDLE.md`. Pre-registered (`research/hunt/r88/PREREG.md`, 8eb9206) before
any statistic; holdout 1 Jun - 19 Oct 2026 read once. 5 indices (Nov 2024 on) and 10 MCX contracts (Aug 2025 on), 5m
and 15m candles of the underlying, 22 parameters including the full option chain.

- **Size: predictable.** 225 of 305 size effects held out of sample: big candles follow big candles (1.5x), volatile
  hours follow volatile hours (1.4-1.7x), high VIX/IV, high PCR, option-volume rushes and expiry days mean bigger
  candles; calm, call-heavy, near-the-high markets smaller ones.
- **Direction: not predictable.** 0 of 8 design direction effects held; the all-parameters model's AUC is 0.485-0.535
  and its best calls move less than an option round trip.
- **With the candle:** the indices move together (NIFTY explains 51-90% of the others' candles); VIX falls on up
  candles; on MCX the PCR falls on up candles.
- **When:** the first 15 minutes on NSE and the US-open hours on MCX are 2-4x a normal candle; SENSEX expiry days
  +25-29%.
- Nothing in the app changed; no rule proposed. Checks: all 459 tests in research/hunt/r88 (incl. the look-ahead
  guard), research/brain and research/data pass with the pinned library versions; features catalog check and
  experiments validate pass.

---

# Data fill, 19–20 Oct 2026: RESULT

**Done.** MCX (the priority), index and stock-option data are in the lake on Google Drive up to **19 Oct 2026**,
checked with `rclone check` (0 differences). Boss asked mid-run (20 Oct) for data **from November 2024 only**, so every
request after that asked only for windows ending on or after 1 Nov 2024.

- Dhan token: worked all through (checked with one small call first). Nothing was printed, logged or committed.
- The lake: `gdrive:IraAlgo-data-lake` (folders below). Nothing raw is in git. `research/data/CATALOG.json` lists every
  file (198 files) with rows, dates and sha256; `MANIFEST.txt` on Drive the same.

## What was fetched

| what | instruments | range | rows | coverage |
|---|---|---|---|---|
| **MCX futures, 1 minute** (`fut_1m_live_mcx/`) | GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI, COPPER, ZINC, ALUMINIUM: 55 contracts with their expiry dates | each contract from its listing (earliest 20 Oct 2025) to 19 Oct 23:30 | 2,493,209 (was 1,434,299 for 6 commodities) | every contract Dhan lists; 0 failed requests |
| **MCX continuous futures** (`fut_1m_mcx_continuous/`, `fut_mcx_rolls/`, `fut_mcx_contracts/`) | the same 11, one contract a day, roll recorded (day, from/to contract, gap) | as above | 974,604 minutes, 8 rolls | near month from: SILVERM 27 Feb 2026, SILVER 30 Jun, GOLDM 3 Jul, GOLD 15 Jul, NATURALGAS and NATGASMINI 26 Aug, COPPER, ZINC and ALUMINIUM 28 Aug, CRUDEOIL and CRUDEOILM 18 Sep |
| **MCX options**, ATM±5, nearest monthly (`options_rolling_1m/opt_<SYM>_MONTH_*`) | GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, NATURALGAS, and new: CRUDEOILM, NATGASMINI, COPPER, ZINC (ALUMINIUM has no options) | 5 Aug 2025 → 19 Oct 2026 | 28.0M in total; new books 11.8M | 100% of what Dhan serves; 0 failed chunks |
| Index | NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (+BANKEX, INDIAVIX index): daily, 1-minute, live futures, options ATM±10 WEEK and MONTH | 14 Oct → 19 Oct appended | +1,125 minutes per index; +47k to +63k option rows per book | 100% |
| Stock options (`stock_options_rolling_1m/`) | 59 stocks (NIFTY 50 + NIFTY BANK), nearest monthly | ATM..ATM±3, 1 Nov 2024 → 19 Oct 2026 | 162.9M → 254.1M | 100% at ATM..±3 from Nov 2024 |

Coverage files: `research/data/STOCK_OPTIONS_COVERAGE_SINCE_2024-11.json` (Boss's range) and
`STOCK_OPTIONS_COVERAGE.json` (all windows from Aug 2021, a lower bound).

## What remains, and why

- **Expired MCX futures before 14 Oct 2026.** Dhan's scrip master lists only live contracts, so 1-minute futures
  history starts at the listing of the contracts live now. The continuous series is therefore near-month only from
  the dates above (earlier days hold a far month and are marked by `near_share`). For older MCX prices: the daily
  continuous series, or the MCX option files' `spot` (the near future, from Aug 2025). New:
  `research/data/FUT_CONTRACTS.json` records every contract seen, so future runs keep asking for contracts after they
  expire. CRUDEOIL Oct-26 (expired 19 Oct) was still served on 20 Oct; whether Dhan keeps serving it once it leaves the
  master is not known yet.
- **Stock options ATM±4 and ±5: Dhan does not provide them.** Every such request is answered empty (checked by hand).
- **Before Nov 2024**, stock options ATM±2 and ±3 stay partial, by Boss's 20 Oct instruction.
- **MCX daily continuous (Dhan expiryCode 0)** ends 16 Oct: Dhan had not published MCX 19 Oct dailies when fetched.
- Equity minutes and stock futures were not part of this task (still to 12/13 Oct).

## Bad ticks in the new data (`research/data/NEWDATA_CHECKS.json`)

Spike check (`spikes.py`) and lakeclean (`research/hunt/lakeclean.py`: foreign rows, stray opens, stale prints):

- MCX futures (2.49M minutes): 0 spikes. 69% of contract-minutes are padded (flat, zero volume), almost all far months.
- MCX option books, new rows 13–19 Oct: spikes 0–0.025%; stray or stale 0–0.28% (CRUDEOIL highest).
- New MCX books (all rows): spikes 0.001–0.035%; stray or stale 0.04–0.40% in 2026; 2025 up to 1.8% (COPPER, thin).
- Index books, 13–19 Oct: spikes 0–0.057%; stray or stale 0.01–0.02%, except NIFTY WEEK 0.95% and SENSEX WEEK 0.86%
  (expiry-week prices).
- Stock options from Nov 2024 (159M rows): spikes 0.047%; stray or stale 0.39%; 1.0% of rows have a `spot` more than
  3% away from the book's median spot at that minute.
- VALIDATION.md rerun: index closes still 99.8–100% against NSE and Yahoo; stock-option high/low match NSE bhavcopy
  65%/64% (228 sample days; was 68%/70% on 96 days), so stock-option minutes still need care.

## Code (branch `claude/data-fill-1019`)

- `lake_fetch.py`: 11 MCX futures, 4 new MCX option books, groups `fut_1m_mcx`, `fut_1m_index`, `mcxopt_<SYM>`;
  contract registry; **newest-day fix** (Dhan returns a day's minutes only when `toDate` is a later day; this also
  explains the "missing 14 Oct" of the last run); option windows that time out are asked again in 10-day pieces
  (`LAKE_SPLIT_FIRST=1` does so at once).
- `dhan_client.py`: `DHAN_RETRIES`. `lake_build.py`, `spikes.py`: the new MCX symbols.
- New: `mcx.py` (MCX loader, session 09:00–23:30/23:55 by the US clock, continuous futures without look-ahead,
  back-adjustment), `newdata_checks.py`; tests `test_mcx.py`, `test_newdata_checks.py`, `test_stkopt_coverage.py`.
- Checks: research/data 263 tests, research/brain 139 tests (with the pinned library versions; with pandas 3.0 five brain
  tests fail on main too), `features catalog --check` and `experiments.validate` pass.

Note for the app (not changed here): the comment in `android/engine/.../mcx/McxSession.kt` says US summer time starts
on 8 Mar 2027; the second Sunday of March 2027 is 14 Mar. The code uses the time-zone rules, so only the comment is off.

CI: the Python jobs, engine/ira-core tests and Build APK pass. One app UI test fails, on `main` as well (run 1657,
commit 9dbbfde) and with no Android change on this branch: `ControlScreensLayoutTest.framePaper[phone-font2.0-*]`,
"' · Dussehra' is cut off at the side". It depends on the date (the Dussehra holiday label shows this week) and needs an
app layout fix, which is outside this data task.
