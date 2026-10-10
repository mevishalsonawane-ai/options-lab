# HUNT h40: does end-of-day positioning data (FII / Pro / Client OI, build-up, delivery, FII-DII cash) pay a next-morning option buyer?

Written 2026-10-08. Code: `research/hunt/h40/`. The files are `PREREG.md`, `fetch.py`, `fetch_nsdl.py`, `consolidate.py`,
`feat.py`, `build.py`, `test.py` and `liqfilter.py`.

Data and logs are in `scratchpad/hunt/h40/`:
- `data/` holds the fetched data (192 MB);
- `test_pre.out`, `variants_pre.csv`, `part_a_pre.csv` and `wf.csv` hold the PRE results;
- `holdout.out`, `holdout.csv` and `holdout_families.csv` hold the holdout reads;
- `liqfilter_pre.log` holds the Liquidity filter results.

Everything is option BUYING only: 1-ITM nearest expiry, 1 lot, Rs 1,00,000.

## Verdict

**NO.** The public end-of-day positioning data that we fetched ourselves does not give a next-morning option buyer an
edge that survives costs and honest testing. Rs 5,000/day is far out of reach.

- **There is a small, real direction signal, and it comes from FII flows.**
  - The signals are FII cash buying (NSDL / NSE provisional), FII index-futures buying (fii_stats and
    participant OI), and "fade the clients' index-futures change".
  - Each one, followed the next morning, picks the right side more often than a coin flip.
  - In PRE, 84 variants beat a coin-flip side on the same days at BH q < 0.05. All 84 are FII-flow "follow"
    variants.
  - In the holdout, the FII-cash "follow" family still beat the coin flip in 62% of its variants.
- **The money is not there.**
  - In PRE, 9,600 variants were run and only 6% made money after costs.
  - The best profit-test q is 1.00 (BH). Hansen SPA p = 0.96 and White RC p = 0.87.
  - **0 variants passed the gates.**
  - The direction edge is smaller than the price of buying an option: a 1-ITM CE/PE bought at 09:16 loses about
    Rs 65-540 per trade on a coin-flip side.
- **Locked holdout (2025-10-01 to 2026-10-06, run once, info only).** The best PRE variant was: BANKNIFTY, buy CE
  the morning after clients cut index-futures longs a lot (z ≥ 1), PE after they add, hold to 15:10.
  - It fell from **+Rs 204/day net** in PRE to **+Rs 52/day net** (gross +Rs 106/day).
  - It made 75 trades, with a max drawdown of Rs -43k and 6 losing months out of 13.
  - Coin-flip p = 0.16, so it is not distinguishable from luck.
- **Liquidity 15+5 filter:** 160 filter variants, with 0 survivors (best BH q = 0.32).
  - The best PRE filter was "BANKNIFTY: take only trades the heavyweight-delivery surge agrees with".
  - In the holdout it **cut Rs 192/day** from the base plan (-Rs 25/day kept vs +Rs 167/day base).
- **Size:**
  - At the holdout rate, Rs 5,000/day needs about **95 BANKNIFTY lots**. Even at the PRE rate it needs 25.
  - A BANKNIFTY 1-ITM lot cost about Rs 21k of premium in the holdout, so Rs 1 lakh holds about 4-5 lots.
  - That is about **Rs 200-250/day at best**, with a drawdown that would take half the account.

## 1. Data: what we fetched ourselves (2020-01-01 to 2026-10-07)

All requests used a plain User-Agent naming research, one request at a time with 3 s between requests per stream
(at most 4 streams), and no cookies. Downloads were parsed in memory as CSV or XLS only. Zips were never extracted
to disk, and the per-day files were packed into parquet and deleted.

### Fetched

| source | URL pattern | days | kept |
|---|---|---|---|
| NSE F&O bhavcopy (old `fo{DDMONYYYY}bhav.csv.zip` to 2024-07-05, UDiFF `BhavCopy_NSE_FO_0_0_0_{YYYYMMDD}_F_0000.csv.zip` after) | nsearchives.nseindia.com | 1,680 of 1,681 (2021-03-30 is 404 in both formats) | all index and stock FUTURES rows (OHLC, settle, contracts, turnover, OI, chg OI): `fo_futures.parquet`, 0.95 M rows. Index options: the nearest 3 expiries within ±8% of the future (`fo_idxopt_near.parquet`, 2.5 M rows) plus OI / chg-OI / volume totals per symbol × expiry × CE/PE over all strikes (`fo_idxopt_agg.parquet`). Stock options: totals per stock × CE/PE (`fo_stkopt_agg.parquet`) |
| NSE CM `sec_bhavdata_full_{DDMMYYYY}.csv` (delivery qty / %) | nsearchives | 1,680 | every EQ/BE/BZ row (OHLC, volume, turnover, delivery qty and %): `cm_eq_delivery.parquet`, 3.5 M rows |
| NSE FII derivatives statistics `fii_stats_{DD-Mon-YYYY}.xls` | nsearchives | 1,681 | raw xls (9 KB each), parsed: index futures buy / sell value and OI (index-wise split from about 2025) |
| NSE participant-wise OI `fao_participant_oi_{DDMMYYYY}.csv` | nsearchives | h27's 1,680 raw files reused, plus 68 gap fills (Jan-Mar 2020) | Client / DII / FII / Pro: index futures long/short, index call/put long/short, stock futures |
| **FII cash history: NSDL FPI "Daily Trends" archive** (public ASP.NET form, one month per request) | fpi.nsdl.co.in/web/Reports/Archive.aspx | 2019-12 to 2026-10, all months | Equity / Stock-Exchange gross buy, gross sell and net per reporting date (`nsdl_fpi_equity.csv`). A reporting date covers the previous trading day's trades. That mapping is checked: corr with NSE provisional FII net is 0.71, vs 0.43 unshifted |
| FII + DII cash (NSE provisional figures), third-party research dataset (Mendeley Data `ygnt7ddjdw`, xlsx, 2018-01 to 2024-12) | data.mendeley.com public API | 1,721 days | date, FII and DII buy / sell / net (`mendeley_fiidii.csv`). This is the only DII history obtained. It is not official, but it matches NSDL (z-score corr 0.85) |
| India VIX | already in Dhan data | | not re-fetched (VIX rules were tested in h31 / OBUY) |

Participant-wise **volume** (`fao_participant_vol`) works (200), but it was not bulk-fetched: OI changes carry the same
flow and time was limited.

### Refused or not usable

| source | result |
|---|---|
| NSE `api/fiidiiTradeReact` (FII/DII cash) | 200, but the **latest day only** (07-Oct-2026 FII -6,121 cr, DII +4,597 cr). No date parameter. `api/fiidii-trend-data?date=` gives 404 |
| BSE `api.bseindia.com/.../FIIDIIData` | **403 Access Denied** (Akamai). Not retried |
| BSE FII/DII page | JS page, nothing in the HTML |
| SEBI "Trends in MF transactions" (DII proxy) | the current month shows (daily MF equity buy / sell). The date search POST was **blocked by SEBI's firewall ("Unauthorized Request Blocked")**, so it was recorded and not pursued |
| Transient TLS resets | about 15-25 per stream (`SSL_ERROR_SYSCALL`). All were retried successfully |

Disk: `scratchpad/hunt/h40` uses 202 MB in total, with no raw zips left.

## 2. Timing (no look-ahead)

All these files appear after the close (fii_stats, bhavcopies and participant OI from about 17:00-21:00 IST; NSDL
the next day). So every feature for day D uses only files dated D-1 or earlier, and trades enter on D at 09:16 (T1)
or 09:30 (T2).

- Overnight holds from 15:20 were **not** tested: none of this data exists at 15:20 of the same day.
- NSDL FII cash is mapped to its trade date and treated as known that evening, as a proxy for NSE's provisional
  figure, which is public that evening.
- The strict version (S12L: one more day of lag) was run too, and it lost the direction effect: only 5.9% of its
  variants had p < 0.05, which is chance. So the FII-cash information is used up within a day.

## 3. Features (pre-registered; z = against the previous 60 sessions)

| id | feature |
|---|---|
| S01 | FII index-futures long ratio L/(L+S) |
| S02 | change in FII index-futures net contracts |
| S03 | change in FII index-options net bullish (call long-short minus put long-short) |
| S04 | change in Pro index-futures net |
| S05 | change in Client index-futures net, faded |
| S06 | FII index-futures net buy value of the day (fii_stats) |
| S07 | stock-futures build-up breadth: (#long build-up - #short build-up) / about 185 F&O stocks |
| S08 | own index future: long / short build-up |
| S09 | own near-expiry put-minus-call OI change (put writing = bullish) |
| S10 | rollover% and roll cost in the last 5 sessions before the monthly expiry, vs the prior 6 expiries |
| S11 | delivery-volume surges in 12 heavyweights (surge up minus surge down) |
| S12 | FII cash net (NSDL) |
| S12L | FII cash net (NSDL), with one more day of lag |
| S13 | FII cash net (NSE provisional, Mendeley) |
| S14 | DII cash net (Mendeley) |
| S15 | vote of S01 S02 S03 S06 S07 S12 |

- Thresholds were 0.5 and 1.0; both the follow and the fade sense were run.
- Features move together as expected. S02 vs S06 correlate at 0.85, and NSDL vs NSE provisional FII cash at 0.85. DII
  cash vs FII cash is -0.53 to -0.59.

## 4. Part A: does the data predict the next day? (index level, PRE, 400 tests, BH)

| target | tests passing BH q<.05 | typical size |
|---|---|---|
| gap (D-1 15:09 → D 09:15) | 26 / 80 | rho 0.12-0.29 (build-up breadth, own futures build-up, put-call OI change, FII options) |
| open → 10:15 | 2 / 80 | about 0.1 (MIDCP S01 +0.11, SENSEX S07 -0.15) |
| open → 11:15 | 0 / 80 | |
| open → 15:09 | 0 / 80 | all \|rho\| < 0.1; mostly 0.02-0.06 |
| \|open → 15:09\| (size) | 7 / 80 | 0.10-0.12: big FII cash or futures days are followed by bigger BANKNIFTY / FINNIFTY days, but not in a known direction |

**What this means:**
- The data "predicts" the **gap**, but that is partly mechanical. The bhavcopy close includes 15:09-15:30 of D-1,
  and the gap is measured from 15:09. Either way, the gap is gone before a 09:16 buyer can enter.
- After the open, there is almost nothing left to trade.

## 5. Part B: option trades (PRE, 9,600 declared variants)

The setup:
- 16 features × thresholds × 2 senses × 5 indices × 2 entries (09:16, 09:30) × 16 exits.
- Time exits: 10:15, 11:15, 15:10.
- Point exits: targets +15/+20/+25/+30 premium points with stops -10/-15/-20, checked on the option's 1-minute
  HIGH/LOW, with the stop first.
- Liquidity-arm exits.
- NET = app fills + app charges + the h24 real half-spread on entry and exit. STRESS = 1.5× the spread.

**Coin-flip hurdle** (mean of CE and PE, net Rs per trade, PRE). Even the cheapest exits lose about Rs 65-130 a trade.
Holding to 15:10 costs about Rs 240-540.

| exit | NIFTY T1 | BANKNIFTY T1 | FINNIFTY T1 | MIDCP T1 | SENSEX T1 |
|---|---|---|---|---|---|
| P30 / S10 | -104 | -90 | -100 | -131 | -82 |
| LIQ | -62 | -169 | -140 | -182 | -88 |
| 10:15 | -182 | -147 | -157 | -273 | -193 |
| 15:10 | -265 | -313 | -285 | -543 | -335 |

**Gates:**

| test | result |
|---|---|
| variants with net > 0 | 6% (median Rs -129 per trade) |
| mean net > 0, BH over 9,600 | **0** (best q = 1.00) |
| beats the coin-flip side, BH q<.05 | 84. All are FII-flow follow variants: S02 / S05 / S06 / S12 / S13 / S01, mostly BANKNIFTY and NIFTY, mostly point exits. Their median net is only +Rs 2.3k over about 4 years |
| Hansen SPA / White RC vs not trading | **p = 0.96 / 0.87** |
| promoted | **none** |

Share of variants with raw coin-flip p < .05, by feature (5% = chance):

| S12 FII cash | S01 | S06 | S05 | S02 | S13 | S15 | S03 | S08 | S07 | S09 | S12L | S14 | S10 | S04 | S11 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 23% | 17% | 16% | 16% | 14% | 13% | 11% | 8% | 7% | 7% | 7% | 6% | 6% | 6% | 4% | 4% |

**Read:**
- FII flows carry a little real next-day direction.
- Stock build-up breadth, delivery surges, rollover, PCR change, Pro positioning and DII flows do not.

Top PRE variants. Rs/day is counted over all sessions of that index, 1 lot.

| variant | trades | gross/day | net/day | net at 1.5× spread | hit | max DD | years + | coin-flip p | BH q (profit) |
|---|---|---|---|---|---|---|---|---|---|
| S05 fade client futures, z≥1, BANKNIFTY, 09:16, out 15:10 | 245 | 229 | **204** | 201k total | 48% | -36.8k | 4/5 | 0.000 | 1.0 |
| same, 09:30 entry | 246 | 212 | 187 | | 50% | -32.2k | 4/5 | 0.000 | 1.0 |
| S12 FII cash z≥1, BANKNIFTY, 09:16, LIQ exits | 263 | 184 | 156 | | 26% | -21.7k | 4/5 | 0.003 | 1.0 |
| S13 FII cash (NSE prov.) z≥1, BANKNIFTY, LIQ | 214 | 151 | 131 | | 22% | -17.9k | 4/4 | 0.013 | 1.0 |
| S06 FII futures buy value z≥1, BANKNIFTY, 15:10 | 256 | 157 | 131 | | 44% | -43.0k | 4/5 | 0.004 | 1.0 |

The best variant by year: 2021 +41.0k, 2022 +93.0k, 2023 +40.5k, 2024 -15.5k, 2025 (Jan-Sep) +45.9k.

**Walk-forward by year** (each year trades the best variant on all earlier years, ≥ 40 trades):

| year | pick | trades | net | gross | coin-flip same days |
|---|---|---|---|---|---|
| 2022 | S14 fade DII cash, BANKNIFTY, 15:10 | 136 | +69.5k | +83.2k | -34.4k |
| 2023 | same | 131 | -24.5k | -14.0k | -35.7k |
| 2024 | S05 fade client futures, BANKNIFTY, 15:10 | 51 | -15.5k | -11.4k | -11.0k |
| 2025 (Jan-Sep) | same | 32 | +45.9k | +51.1k | -2.7k |
| **total** | | 350 | **+75.3k** (net at 1.5× spread +70.4k) | +109.0k | -83.8k |

The walk-forward was positive, but it is two good years and two bad years. That is about Rs 75/day over 1,000
sessions, with SPA p = 0.96.

## 6. Locked holdout (2025-10-01 to 2026-10-06, run once)

There was no survivor. As pre-registered, the best PRE variant was run once; it is also the 2025 walk-forward pick.

| | PRE | HOLDOUT |
|---|---|---|
| rule | BANKNIFTY 1-ITM, buy at 09:16; CE if clients' index-futures net fell (z ≤ -1) the day before, PE if it rose (z ≥ +1); out 15:10 | same |
| trades / sessions | 245 / 1,005 | 75 / 248 |
| gross Rs/day | 229 | **106** |
| net Rs/day (real spread) | 204 | **52** |
| net at 1.5× spread, total | +201.1k | +10.2k |
| coin-flip side, same days (net) | -40.9k | -32.5k |
| coin-flip p | 0.000 | **0.16** |
| hit rate | 48% | 49% |
| max drawdown, 1 lot | -36.8k | **-42.9k** |
| worst day / worst month | -11.1k / -18.1k | -10.8k / -14.5k |
| losing months | 22 / 48 | 6 / 13 |
| bootstrap P(losing month) | 0.40 | 0.49 |
| median premium per lot | Rs 7.6k | **Rs 21.4k** (35-lot BANKNIFTY) |
| lots for Rs 5,000/day | 25 | **95** |
| lots Rs 1 lakh can hold | 13 | 4-5 |

**Info only (chosen after PRE, disclosed): FII-flow follow families in the holdout** (320 variants each):

| family | beat coin-flip PRE → HOLD | net > 0 PRE → HOLD |
|---|---|---|
| S12 FII cash (NSDL) | 91% → **62%** | 31% → 20% |
| S02 FII futures net change | 70% → 47% | 9% → 13% |
| S06 FII futures buy value | 77% → 44% | 10% → 2% |
| S05 fade client futures | 64% → 41% | 10% → 8% |
| S01 FII long ratio | 84% → 24% | 5% → 0.3% |
| S12L (strict lag) | 60% → 39% | 7% → 10% |

Only the FII cash direction kept some edge out of sample, and even then 80% of its variants lost money.

**Liquidity 15+5 filter, holdout (info, best PRE filter):** "BANKNIFTY, keep only trades the heavyweight delivery
surge agrees with" kept 62 of 248 trades. Kept Rs/day was -25, against +167 for the unfiltered base: **-Rs 192/day**.

## 7. Honest count

- Part A: 400 tests.
- Part B: 9,600 declared variants.
- Part C: 160 variants.
- Holdout:
  - 1 option variant (the best PRE variant, which is also the WF pick);
  - 1 Liquidity filter;
  - 1,920 × 2 info-only family reads, disclosed as added after PRE.
- The PRE runs were done once on the complete data, after `PREREG.md` was written. Code was smoke-tested on partial
  data without reading the results.

## 8. Plain answer for the Boss

- The free public positioning data **can** be collected, and we now have 6¾ years of it locally:
  - F&O bhavcopy;
  - delivery %;
  - FII derivative stats;
  - participant OI;
  - FII cash from NSDL;
  - DII cash to 2024.
- What it tells you is mostly **already in the next morning's gap**.
- What is left after 09:16 is a slight lean in direction. It is strongest when FIIs bought or sold heavily in cash
  the day before.
- That lean is far too small to beat what an option buyer pays every trade: Rs 65-540 of theta, spread and charges.
- After 9,600 honest tries:
  - nothing passed;
  - the best rule fell from Rs 204/day to Rs 52/day per lot in the holdout;
  - it needed 95 lots for Rs 5,000/day, while Rs 1 lakh holds 4-5.
- As a filter on the Liquidity plan it hurt.
- **Rs 5,000/day from positioning data: NO.** It is worth keeping only as context. For example, "big FII cash
  selling yesterday → slightly favour PE today" can be tracked on paper, not traded at size.
