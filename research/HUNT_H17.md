# HUNT h17: Liquidity 15+5 with FIXED lots at Rs 1,00,000 (no Kelly, no growing size)

Code: `research/hunt/h17/` (`PREREG.md` written before any h17 P&L; `run.py pre` = choice on data before 2025-10-01,
`run.py hold` = one holdout run). Logs/CSVs: `scratchpad/hunt/h17/` (`pre.log`, `hold.log`, `pre_variants.csv`,
`pre_size_scan.csv`, `holdout_all_posthoc.csv`, `choice.json`).

Option BUYING only. Signals, books, strike (1-ITM, nearest monthly) and exits: the app's Liquidity 15+5, unchanged.
Execution: h14 limit entry (entry print +0.5%, rests 3 min, cancel, no chase), h10 impact (κ 0.02 central, 0.04 pessimistic).
Per-trade tables reused from h15 (`research/hunt/h15/tab.py`); h17 replays them with fixed lots, a free-cash check
(skip the signal if capital minus open premium cannot pay for the fixed lots) and the daily stops. Validated: with
unlimited cash the replay equals the table sum to the rupee (BN 1 lot: Rs 81,322 pre-holdout).

## Verdict (plain language)

**Recommended fixed plan for Rs 1 lakh:**
1. Trade the app's Liquidity 15+5 on BANKNIFTY (15m+5m) **1 lot** and MIDCPNIFTY (15m+5m) **1 lot**, 1-ITM nearest
   monthly, limit entry at +0.5% for 3 minutes, no chase. App exits unchanged.
2. Never add lots as the account grows. If free cash cannot pay for the lot, skip the signal.
3. No daily loss-stop or profit-lock: they did not help before the holdout.
4. Expect drawdowns of Rs 40-75k. Stop and review if capital falls below Rs 50k.

**Honest Rs/day at Rs 1 lakh: about Rs 100-300/day net (Rs 250-700 gross). That is Rs 2-6k a month, not Rs 5,000/day.**
- Pre-holdout average (Oct 2021 to Sep 2025): Rs 111/day net, Rs 239 gross.
- Monthly-only era (Dec 2024 to Sep 2025): Rs 170/day net.
- Holdout year: Rs 308/day net, Rs 711 gross.

Rs 5,000/day at fixed lots that fit Rs 1 lakh: **NO**. Even 4 BN lots, which the account cannot reliably fund, made
Rs 600-1,000/day.

## Pre-registered choice (pre-holdout data only)

- Rule: P(capital < Rs 50k within 1 year) < 5% (bootstrap of all pre-holdout days) and net > 0 at κ 0.04. Then pick
  the best net Rs/day, preferring the simpler variant within 5%.
- Only **1 BN lot** passes, alone or with MIDCP 1. BN 2 lots: P(<50k) = 6.1%. BN 3: 16%. BN 4: 26%.
- **Choice: BN1+M1/none.** Score Rs 111/day. BN1+M1/L scored Rs 115/day; it is within 5%, so the simpler variant was
  taken.
- **Biggest fixed size with P(capital < 50k in 1 year) < 5% = 1 BN lot**, for every book set and discipline.
- Caveat: bootstrapping only the monthly-only era (Dec 2024 to Sep 2025) gives P(<50k) ≤ 1.6% even for BN 3-4 lots.
  The pre-registered test includes 2021-22, when the edge lost money (BN 1 lot: Rs -157/day in Q4 2021 and Rs -25/day
  in 2022). It is the stricter test.
- **Walk-forward (anchored):**
  - Test years 2023 and 2024: no variant was eligible on the data before them (everything lost in 2021-22).
  - Test year 2025 (Jan-Sep): the pick was BN1+M1/none, Rs 181/day.
- **Multiple testing:**
  - BH q over the 24 is ≥ 0.12 for "mean daily net > 0" over 2021-25. Individually this is weak because 2021-22 lost.
  - White RC p = 0.048, SPA p = 0.10.
  - Random entries: inherited from h15 (same tables and exits; every h15 variant beat random entries, BH q ≤ 0.006).
    Not re-run here because the pool tables were deleted to save disk.
- **Daily discipline (loss-stop / profit-lock):** no help before the holdout. It cut Rs/day for 2+ BN lots (BN2
  Rs 196 → 161; BN3 300 → 257) because it skipped recovery trades. In the holdout it helped slightly, which is
  post-hoc. Treat it as neutral and optional.

## Holdout (2025-10-01 .. 2026-10-05, 249 days, run ONCE): BN1+M1/none from Rs 1 lakh

| | κ 0.02 | κ 0.04 | κ 0.01 |
|---|---|---|---|
| net Rs/day | **308** | 187 | 378 |
| gross Rs/day | 711 | 668 | 718 |
| ending capital | **Rs 1.77 L** | 1.46 L | 1.94 L |
| max drawdown | Rs 76.4k (76% of 1 L) | Rs 87.0k | Rs 68.6k |
| worst day / worst month | -10.6k / -42.8k (Apr 2026) | -11.2k / -46.6k | -10.3k / -41.6k |
| losing months | 6/13 | 7/13 | 4/13 |

- At κ 0.02: P(losing month) 44%. Lowest capital Rs 91k. 481 trades, 1 skipped for cash.
- **Monthly net (κ 0.02):**

  | month | net Rs |
  |---|---|
  | Oct | +2.3k |
  | Nov | -4.3k |
  | Dec | +1.5k |
  | Jan | +36.7k |
  | Feb | +41.3k |
  | Mar | +4.5k |
  | Apr | -42.8k |
  | May | -10.4k |
  | Jun | -0.1k |
  | Jul | +21.1k |
  | Aug | -0.3k |
  | Sep | +28.8k |

- **Bootstrap of holdout days (start Rs 1 L):**

  | | 1 year | 2 years |
  |---|---|---|
  | P(capital < 25k) | 3.4% | 6.5% |
  | P(capital < 50k) | 19% | 23% |
  | P(loss) | 21% | 12% |
  | median ending capital | Rs 1.70 L | Rs 2.39 L |

- MIDCP lost money in era M and, in the holdout, carried big swings. BN 1 lot alone in the holdout (post-hoc) made
  Rs 214/day with a Rs 28k drawdown.

## Per-variant table, pre-holdout (κ 0.02; replay from Rs 1 lakh on 2021-10-01 unless noted)

Columns:
- **score**: Rs/day, restarting at Rs 1 lakh each calendar year.
- **net/gross**: Rs/day over the continuous replay.
- **era M net**: Rs/day, Dec 2024 to Sep 2025.
- **end cap**: ending capital, Rs.
- **max DD**: Rs, and % of Rs 1 lakh.
- **worst day / worst month**: Rs.
- **P(lose mo)**: probability of a losing month.
- **P<25k / P<50k**: from the bootstrap of all pre-holdout days, over 1 and 2 years.
- **skips**: signals skipped for cash.

| variant | score | net/gross | era M net | end cap | max DD | worst day / mo | P(lose mo) | P<25k 1y/2y | P<50k 1y/2y | skips |
|---|---|---|---|---|---|---|---|---|---|---|
| BN1/none | 82 | 82/152 | 230 | 1.81 L | 35.5k (36%) | -4.5k/-8.9k | 50% | 0.0/0.1% | 0.2/1.5% | 0 |
| BN1/L | 82 | 82/152 | 230 | 1.81 L | 35.5k | same | 50% | 0.0/0.1% | 0.2/1.5% | 0 |
| BN1/LP | 79 | 79/148 | 232 | 1.78 L | 36.6k | same | 50% | 0.0/0.1% | 0.2/1.4% | 0 |
| BN2/none | 196 | 203/310 | 495 | 3.01 L | 65.0k (65%) | -8.9k/-17.2k | 48% | 1.0/2.5% | 6.1/9.9% | 1 |
| BN2/L | 161 | 179/285 | 364 | 2.77 L | 65.0k | -8.9k/-16.0k | 49% | 1.1/3.1% | 7.6/12.2% | 1 |
| BN2/LP | 163 | 182/285 | 362 | 2.80 L | 65.0k | -8.9k/-16.0k | 49% | 1.2/2.8% | 7.2/11.6% | 1 |
| BN3/none | 300 | 320/465 | 654 | 4.17 L | 96.5k (96%) | -13.4k/-25.5k | 47% | 4.5/8.2% | 15.9/20.4% | 9 |
| BN3/L | 257 | 279/422 | 469 | 3.76 L | 96.5k | same | 49% | 5.1/9.3% | 17.4/22.9% | 9 |
| BN3/LP | 255 | 276/416 | 471 | 3.73 L | 96.5k | same | 49% | 4.9/8.8% | 17.2/22.5% | 9 |
| BN4/none | 381 | -89/-74 (ruined) | 884 | 0.12 L | 134k | -17.8k/-31.4k | 44% | 8.0/13.2% | 25.5/29.7% | 672 |
| BN4/L | 321 | ruined | 611 | 0.12 L | 134k | same | 44% | 7.6/13.0% | 25.1/29.5% | 672 |
| BN4/LP | 327 | ruined | 607 | 0.12 L | 134k | same | 44% | 7.2/12.1% | 24.3/28.6% | 672 |
| **BN1+M1/none (choice)** | **111** | **111/239** | 170 | 2.10 L | 39.9k (40%) | -8.8k/-17.1k | 52% | 0.3/1.5% | 4.1/9.3% | 0 |
| BN1+M1/L | 115 | 115/241 | 189 | 2.14 L | 39.9k | -6.5k/-15.6k | 52% | 0.2/1.3% | 3.4/8.4% | 0 |
| BN1+M1/LP | 97 | 98/222 | 198 | 1.97 L | 42.4k | -6.5k/-15.6k | 52% | 0.2/1.3% | 3.7/9.6% | 0 |
| BN2+M1/none | 212 | 232/396 | 414 | 3.29 L | 65.0k | -11.1k/-20.0k | 49% | 2.3/5.4% | 10.0/15.1% | 1 |
| BN2+M1/L | 161 | 193/353 | 174 | 2.90 L | 65.0k | -8.9k/-18.4k | 51% | 2.6/6.8% | 12.1/18.2% | 1 |
| BN2+M1/LP | 149 | 180/335 | 174 | 2.78 L | 65.0k | -8.9k/-17.8k | 51% | 2.6/6.4% | 12.2/18.3% | 1 |
| BN3+M1/none | 334 | 349/551 | 633 | 4.45 L | 96.5k | -13.5k/-25.5k | 48% | 5.7/9.7% | 18.3/22.7% | 9 |
| BN3+M1/L | 262 | 296/495 | 284 | 3.92 L | 96.5k | same | 50% | 6.1/10.7% | 20.0/25.0% | 9 |
| BN3+M1/LP | 251 | 284/479 | 286 | 3.81 L | 96.5k | same | 50% | 6.3/10.8% | 20.1/25.0% | 9 |
| BN4+M1/none | 410 | ruined | 836 | 0.03 L | 143k | -17.8k/-31.4k | 53% | 9.8/14.4% | 26.9/30.9% | 1,026 |
| BN4+M1/L | 337 | ruined | 438 | 0.03 L | 143k | same | 53% | 9.2/14.0% | 26.4/30.6% | 1,026 |
| BN4+M1/LP | 335 | ruined | 429 | 0.03 L | 143k | same | 53% | 8.7/13.0% | 25.7/29.4% | 1,026 |

Notes on the table:
- BN 4 lots, started at Rs 1 lakh in Oct 2021, lost the account in 2021-22. After that it could not afford 4 lots
  (672 skips), so "fixed 4 lots" is not survivable from Rs 1 lakh if the bad years come first.
- **Size scan, BN lots 1-8** (`pre_size_scan.csv`): P(<50k in 1 year) rises steadily, from 0.2% at 1 lot through 6%
  at 2, 16% at 3, 26% at 4 and 32% at 5, to about 37% at 7-8 lots, where skips are 27-32%.

## Post-hoc holdout, all 24 variants (κ 0.02; NOT used for anything)

Net Rs/day in the holdout:

| lots | BN only | BN + MIDCP 1 |
|---|---|---|
| 1 | 214 | 308 |
| 2 | 460 | 528 |
| 3 | 728 | 674 |
| 4 | 717 (22 skips) | 863 |

- Best overall was BN4/LP at Rs 1,055/day (3.63 L ending capital, but a Rs 1.09 L drawdown).
- If a real Rs 1 lakh account had started with 2-4 lots in a year like 2021-22, it would have been wiped out (see
  BN4 above).

## Honesty notes

- 24 variants, all counted. The choice used only pre-2025-10-01 data, and the holdout ran once.
- The daily-stop thresholds were tied to size (Rs 5k for ≤2 BN lots, Rs 10k for 3-4). A profit-lock on its own was not
  tested separately; this is disclosed in PREREG.
- Known beforehand: h10-h16 results, including their holdouts (disclosed in PREREG).
- Simplification: a signal skipped for cash does not free up a later signal that the arm's one-position rule had
  filtered out at 1 lot.
- Inherited caveats (h4/h10/h14/h15): the exits were tuned through Feb 2026; there is no order-book data; κ is the
  biggest unknown.
