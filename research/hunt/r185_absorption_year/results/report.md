# R185 report: absorption and exhaustion on two years of 1-minute data

*16 Nov 2026. Pre-registered in `PREREG.md` (committed and pushed before any number). Research only; nothing in the app changes.*

**Signal source:** the index's own 1-minute price (the underlying); each minute's buy / sell split estimated by BVC from the index price change, sized by the summed volume of the ATM-2..ATM+2 calls and puts (option-volume context, OVC); no rule reads an option's own price chart. Dhan has no index futures minute history for the year (expired futures are not served) and **no second-level history at all** (`/charts/intraday` with interval 1s returns nothing; `/charts/rollingoption` accepts 1, 5, 15 and 60 minutes only), so this is the brief's labelled fallback: **option-volume context (OVC)**. BVC infers the side of each minute's volume from the index's own price change, so it cannot see heavy selling that does not move the price - the heart of absorption. These rules are R184's definitions on estimated flow, not true order flow.

**Trade:** 1 lot of the ATM option (nearest expiry: weekly for NIFTY / SENSEX; for BANKNIFTY, FINNIFTY and MIDCPNIFTY the nearest is the monthly since Nov 2024), bought at the open of the minute after the signal; -15% premium stop, breakeven (fill + charges) from the minute after +10%, index stop beyond the extreme, 15 / 30-minute time stop, 15:14 (15:04 on expiry). Real dated charges, liquidity slippage (2x shown).

**Indices read:** NIFTY (order fixed: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX). BH m = 4,852 (4,512 after R184 + 320 variants + 20 tests), q ranked over the 4 tests read so far.

## Data

- **NIFTY**: 490 trading days 20 Nov 2024 - 13 Nov 2026 with >= 300 index minutes (dropped: 2025-10-21, 2026-11-08); strike step [50.0]; selection halves H1 109 days, H2 118 days.
  Gap fetch from Dhan (20 Oct - 13 Nov 2026): 18 days (2026-10-21 - 2026-11-13; short: 2026-11-08), 270,556 option rows (ATM-10..+10, CE / PE), 6,445 index minutes, 0 empty series-days, 30 missing near-ATM (+-2) option minutes. Raw files: `gdrive:IraAlgo-data-lake/dhan/NIFTY/ (and the lake folders options_rolling_1m/, index_1m/)`.
  Dhan vs the phone's 1-second recordings (20 days, 15 Oct - 13 Nov 2026): 72,623 of 72,655 option-minutes matched; median |diff| Rs 0.3, 95th pct Rs 1.75; 87% within 1%, 99% within 5%, 86% within Rs 1; correlation 0.99987. (The phone records the nearest expiry, so this also confirms Dhan's expiry code 1 is the nearest.)

## NIFTY

### Selection (20 Nov 2024 - 19 Oct 2025)

**Absorption**: 32 versions, 1 with net > 0, **0 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `ABS w3 k2 b6 turn T30` (FALLBACK - nothing passed the selection): 319 trades, win 25%, Rs -5,290 (H1 87 trades Rs -2,827, mean Rs -32 vs twins Rs -119; H2 232 trades Rs -2,463, mean Rs -11 vs twins Rs -112); descriptive twin p 0.049 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| ABS w3 k3 b3 turn T30 | 4 | Rs 615 | 1 / Rs -126 / Rs -126 / Rs -150 | 3 / Rs 741 / Rs 247 / Rs -101 |  |
| ABS w5 k3 b3 turn T15 | 2 | Rs -26 | 2 / Rs -26 / Rs -13 / Rs -109 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b3 turn T30 | 2 | Rs -26 | 2 / Rs -26 / Rs -13 / Rs -182 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b3 none T15 | 2 | Rs -138 | 2 / Rs -138 / Rs -69 / Rs -248 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b3 none T30 | 2 | Rs -138 | 2 / Rs -138 / Rs -69 / Rs -198 | 0 / Rs 0 / - / - |  |
| ABS w3 k3 b3 turn T15 | 4 | Rs -489 | 1 / Rs -126 / Rs -126 / Rs -315 | 3 / Rs -363 / Rs -121 / Rs -126 |  |
| ABS w5 k2 b3 turn T15 | 5 | Rs -748 | 4 / Rs -361 / Rs -90 / Rs -105 | 1 / Rs -387 / Rs -387 / Rs -177 |  |
| ABS w5 k2 b3 turn T30 | 5 | Rs -748 | 4 / Rs -361 / Rs -90 / Rs -245 | 1 / Rs -387 / Rs -387 / Rs -145 |  |
| ABS w3 k3 b3 none T15 | 8 | Rs -1,354 | 1 / Rs -8 / Rs -8 / Rs -134 | 7 / Rs -1,346 / Rs -192 / Rs -94 |  |
| ABS w3 k3 b3 none T30 | 8 | Rs -1,354 | 1 / Rs -8 / Rs -8 / Rs -297 | 7 / Rs -1,346 / Rs -192 / Rs -110 |  |
| ABS w5 k2 b3 none T15 | 12 | Rs -2,583 | 5 / Rs -461 / Rs -92 / Rs -169 | 7 / Rs -2,122 / Rs -303 / Rs -137 |  |
| ABS w5 k2 b3 none T30 | 12 | Rs -2,583 | 5 / Rs -461 / Rs -92 / Rs -216 | 7 / Rs -2,122 / Rs -303 / Rs -142 |  |
| ABS w3 k2 b3 turn T30 | 28 | Rs -3,056 | 12 / Rs -3,705 / Rs -309 / Rs -116 | 16 / Rs 649 / Rs 41 / Rs -62 |  |
| ABS w3 k2 b3 turn T15 | 28 | Rs -3,961 | 12 / Rs -2,516 / Rs -210 / Rs -65 | 16 / Rs -1,445 / Rs -90 / Rs -102 |  |
| ABS w5 k3 b6 turn T30 | 49 | Rs -4,962 | 19 / Rs -468 / Rs -25 / Rs -150 | 30 / Rs -4,494 / Rs -150 / Rs -139 |  |
| ABS w3 k2 b6 turn T30 | 319 | Rs -5,290 | 87 / Rs -2,827 / Rs -32 / Rs -119 | 232 / Rs -2,463 / Rs -11 / Rs -112 |  |
| ABS w5 k3 b6 turn T15 | 51 | Rs -5,957 | 19 / Rs -302 / Rs -16 / Rs -116 | 32 / Rs -5,655 / Rs -177 / Rs -116 |  |
| ABS w5 k3 b6 none T15 | 72 | Rs -6,389 | 25 / Rs 3,147 / Rs 126 / Rs -124 | 47 / Rs -9,536 / Rs -203 / Rs -104 |  |
| ABS w3 k2 b3 none T15 | 57 | Rs -6,565 | 19 / Rs 1,090 / Rs 57 / Rs -108 | 38 / Rs -7,655 / Rs -201 / Rs -106 |  |
| ABS w3 k2 b3 none T30 | 57 | Rs -8,063 | 19 / Rs -1,154 / Rs -61 / Rs -136 | 38 / Rs -6,909 / Rs -182 / Rs -123 |  |
| ABS w5 k3 b6 none T30 | 70 | Rs -9,138 | 25 / Rs 1,828 / Rs 73 / Rs -187 | 45 / Rs -10,966 / Rs -244 / Rs -147 |  |
| ABS w3 k3 b6 turn T15 | 116 | Rs -9,301 | 34 / Rs -2,015 / Rs -59 / Rs -132 | 82 / Rs -7,286 / Rs -89 / Rs -109 |  |
| ABS w3 k3 b6 none T15 | 169 | Rs -9,350 | 50 / Rs 179 / Rs 4 / Rs -130 | 119 / Rs -9,529 / Rs -80 / Rs -91 |  |
| ABS w3 k3 b6 turn T30 | 112 | Rs -10,276 | 34 / Rs -2,346 / Rs -69 / Rs -153 | 78 / Rs -7,930 / Rs -102 / Rs -139 |  |
| ABS w5 k2 b6 turn T30 | 163 | Rs -10,424 | 52 / Rs -3,530 / Rs -68 / Rs -145 | 111 / Rs -6,894 / Rs -62 / Rs -124 |  |
| ABS w5 k2 b6 turn T15 | 167 | Rs -10,942 | 52 / Rs -1,672 / Rs -32 / Rs -83 | 115 / Rs -9,270 / Rs -81 / Rs -102 |  |
| ABS w3 k3 b6 none T30 | 164 | Rs -12,828 | 50 / Rs -1,986 / Rs -40 / Rs -153 | 114 / Rs -10,842 / Rs -95 / Rs -129 |  |
| ABS w5 k2 b6 none T30 | 215 | Rs -13,418 | 64 / Rs -3,489 / Rs -54 / Rs -138 | 151 / Rs -9,929 / Rs -66 / Rs -109 |  |
| ABS w3 k2 b6 turn T15 | 325 | Rs -18,079 | 88 / Rs -7,529 / Rs -86 / Rs -104 | 237 / Rs -10,550 / Rs -44 / Rs -100 |  |
| ABS w5 k2 b6 none T15 | 220 | Rs -20,110 | 64 / Rs -2,232 / Rs -35 / Rs -102 | 156 / Rs -17,878 / Rs -115 / Rs -99 |  |
| ABS w3 k2 b6 none T30 | 391 | Rs -20,993 | 108 / Rs -8,513 / Rs -79 / Rs -112 | 283 / Rs -12,480 / Rs -44 / Rs -121 |  |
| ABS w3 k2 b6 none T15 | 396 | Rs -34,805 | 110 / Rs -15,925 / Rs -145 / Rs -100 | 286 / Rs -18,880 / Rs -66 / Rs -101 |  |

**Exhaustion**: 32 versions, 0 with net > 0, **0 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `EXH X30 k3 b6 flip+close T15` (FALLBACK - nothing passed the selection): 797 trades, win 35%, Rs -54,523 (H1 370 trades Rs -25,746, mean Rs -70 vs twins Rs -100; H2 427 trades Rs -28,776, mean Rs -67 vs twins Rs -99); descriptive twin p 0.206 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| EXH X30 k3 b6 flip+close T15 | 797 | Rs -54,522 | 370 / Rs -25,746 / Rs -70 / Rs -100 | 427 / Rs -28,776 / Rs -67 / Rs -99 |  |
| EXH X30 k3 b3 flip+close T15 | 777 | Rs -57,802 | 357 / Rs -27,308 / Rs -76 / Rs -92 | 420 / Rs -30,494 / Rs -73 / Rs -100 |  |
| EXH X15 k3 b3 flip+close T15 | 804 | Rs -60,976 | 371 / Rs -31,695 / Rs -85 / Rs -100 | 433 / Rs -29,281 / Rs -68 / Rs -100 |  |
| EXH X15 k3 b6 flip+close T15 | 822 | Rs -63,707 | 382 / Rs -34,574 / Rs -90 / Rs -88 | 440 / Rs -29,133 / Rs -66 / Rs -90 |  |
| EXH X30 k3 b3 flip T15 | 795 | Rs -66,869 | 367 / Rs -37,820 / Rs -103 / Rs -100 | 428 / Rs -29,049 / Rs -68 / Rs -91 |  |
| EXH X15 k3 b3 flip T15 | 816 | Rs -67,183 | 379 / Rs -38,478 / Rs -102 / Rs -102 | 437 / Rs -28,705 / Rs -66 / Rs -88 |  |
| EXH X30 k3 b6 flip T15 | 822 | Rs -68,505 | 383 / Rs -39,170 / Rs -102 / Rs -91 | 439 / Rs -29,335 / Rs -67 / Rs -93 |  |
| EXH X15 k3 b6 flip T15 | 838 | Rs -72,726 | 392 / Rs -45,751 / Rs -117 / Rs -89 | 446 / Rs -26,975 / Rs -60 / Rs -92 |  |
| EXH X15 k3 b3 flip+close T30 | 790 | Rs -79,485 | 366 / Rs -47,973 / Rs -131 / Rs -96 | 424 / Rs -31,512 / Rs -74 / Rs -106 |  |
| EXH X30 k3 b3 flip+close T30 | 764 | Rs -80,391 | 352 / Rs -47,450 / Rs -135 / Rs -99 | 412 / Rs -32,941 / Rs -80 / Rs -108 |  |
| EXH X30 k3 b6 flip+close T30 | 780 | Rs -82,013 | 363 / Rs -45,908 / Rs -126 / Rs -103 | 417 / Rs -36,105 / Rs -87 / Rs -113 |  |
| EXH X30 k3 b6 flip T30 | 806 | Rs -82,702 | 377 / Rs -48,991 / Rs -130 / Rs -97 | 429 / Rs -33,711 / Rs -79 / Rs -100 |  |
| EXH X15 k3 b6 flip+close T30 | 803 | Rs -85,200 | 374 / Rs -55,771 / Rs -149 / Rs -105 | 429 / Rs -29,429 / Rs -69 / Rs -101 |  |
| EXH X30 k3 b3 flip T30 | 783 | Rs -86,552 | 363 / Rs -51,535 / Rs -142 / Rs -115 | 420 / Rs -35,017 / Rs -83 / Rs -106 |  |
| EXH X15 k3 b3 flip T30 | 805 | Rs -87,268 | 375 / Rs -49,214 / Rs -131 / Rs -96 | 430 / Rs -38,054 / Rs -88 / Rs -102 |  |
| EXH X15 k3 b6 flip T30 | 823 | Rs -87,843 | 385 / Rs -57,192 / Rs -149 / Rs -99 | 438 / Rs -30,651 / Rs -70 / Rs -111 |  |
| EXH X30 k2 b6 flip+close T15 | 900 | Rs -91,726 | 433 / Rs -46,264 / Rs -107 / Rs -85 | 467 / Rs -45,462 / Rs -97 / Rs -90 |  |
| EXH X30 k2 b3 flip+close T15 | 896 | Rs -93,274 | 431 / Rs -49,473 / Rs -115 / Rs -90 | 465 / Rs -43,801 / Rs -94 / Rs -84 |  |
| EXH X30 k2 b3 flip T15 | 899 | Rs -95,894 | 431 / Rs -52,913 / Rs -123 / Rs -82 | 468 / Rs -42,981 / Rs -92 / Rs -89 |  |
| EXH X30 k2 b6 flip T15 | 902 | Rs -97,084 | 433 / Rs -52,871 / Rs -122 / Rs -86 | 469 / Rs -44,213 / Rs -94 / Rs -92 |  |
| EXH X15 k2 b3 flip+close T15 | 902 | Rs -103,628 | 435 / Rs -48,955 / Rs -112 / Rs -84 | 467 / Rs -54,673 / Rs -117 / Rs -87 |  |
| EXH X15 k2 b3 flip T15 | 904 | Rs -105,849 | 435 / Rs -57,400 / Rs -132 / Rs -94 | 469 / Rs -48,449 / Rs -103 / Rs -90 |  |
| EXH X15 k2 b6 flip+close T30 | 900 | Rs -107,295 | 433 / Rs -48,893 / Rs -113 / Rs -75 | 467 / Rs -58,402 / Rs -125 / Rs -92 |  |
| EXH X15 k2 b6 flip+close T15 | 902 | Rs -108,658 | 435 / Rs -52,926 / Rs -122 / Rs -90 | 467 / Rs -55,732 / Rs -119 / Rs -86 |  |
| EXH X15 k2 b6 flip T15 | 904 | Rs -110,525 | 435 / Rs -59,788 / Rs -137 / Rs -90 | 469 / Rs -50,737 / Rs -108 / Rs -76 |  |
| EXH X15 k2 b3 flip+close T30 | 899 | Rs -110,720 | 432 / Rs -46,670 / Rs -108 / Rs -74 | 467 / Rs -64,050 / Rs -137 / Rs -89 |  |
| EXH X15 k2 b6 flip T30 | 903 | Rs -120,286 | 434 / Rs -59,940 / Rs -138 / Rs -80 | 469 / Rs -60,346 / Rs -129 / Rs -94 |  |
| EXH X15 k2 b3 flip T30 | 902 | Rs -121,012 | 433 / Rs -56,586 / Rs -131 / Rs -76 | 469 / Rs -64,426 / Rs -137 / Rs -94 |  |
| EXH X30 k2 b6 flip+close T30 | 896 | Rs -122,373 | 431 / Rs -57,409 / Rs -133 / Rs -79 | 465 / Rs -64,964 / Rs -140 / Rs -99 |  |
| EXH X30 k2 b3 flip+close T30 | 892 | Rs -127,731 | 428 / Rs -63,038 / Rs -147 / Rs -75 | 464 / Rs -64,693 / Rs -139 / Rs -92 |  |
| EXH X30 k2 b6 flip T30 | 899 | Rs -131,721 | 432 / Rs -63,784 / Rs -148 / Rs -91 | 467 / Rs -67,937 / Rs -146 / Rs -105 |  |
| EXH X30 k2 b3 flip T30 | 896 | Rs -133,961 | 429 / Rs -66,251 / Rs -154 / Rs -87 | 467 / Rs -67,710 / Rs -145 / Rs -83 |  |

### Final year (20 Oct 2025 - 19 Oct 2026, read once)

Read at 2026-11-16T03:21:16.103529+00:00 after `FINAL_READ_NIFTY` (2026-11-16T03:20:34Z); 246 days.

**Absorption `ABS w3 k2 b6 turn T30`** - verdict: **no evidence (nothing passed the selection)**

- Year: 451 trades on 158 days (923 signals; unpriced none), win 26%, Rs -50,042 (2x slippage Rs -57,176), mean Rs -111 a trade (avg win Rs 801, avg loss Rs -434), worst day Rs -4,282, worst month Rs -13,085 (2026-06), max drawdown Rs 51,770.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -105 a trade (10-90%: Rs -161 to Rs -43); p 0.544, BH q 1.00. Mean > 0 (day bootstrap): p 0.996, q 1.00.
- Smallest edge detectable (80% power): Rs 88 a trade at 5%, Rs 176 at the BH level.
- By side: calls 239 (Rs -41,323), puts 212 (Rs -8,719). By exit: breakeven 93 (Rs -1,498), index_stop 108 (Rs -86,848), prem_stop 76 (Rs -42,115), time 174 (Rs 80,419).
- Gates: failed - nothing passed the selection; not positive in every period (or the year at 2x slippage); not above the twins in early, final and the year; BH q twins 1.00 / zero 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, six, year.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 176 | 29% | Rs -8,526 | Rs -11,416 | Rs -48 | Rs -102 | Rs -7,017 | Rs 10,225 |
| final (1 Apr - 19 Oct 2026) | 275 | 24% | Rs -41,516 | Rs -45,760 | Rs -151 | Rs -107 | Rs -13,085 | Rs 44,513 |
| last 6 months (20 Apr - 19 Oct 2026) | 266 | 25% | Rs -41,541 | Rs -45,627 | Rs -156 | Rs -107 | Rs -13,085 | Rs 43,614 |
| year (20 Oct 2025 - 19 Oct 2026) | 451 | 26% | Rs -50,042 | Rs -57,176 | Rs -111 | Rs -105 | Rs -13,085 | Rs 51,770 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 8 trades, win 12%, Rs -4,070.

**Exhaustion `EXH X30 k3 b6 flip+close T15`** - verdict: **no evidence (nothing passed the selection)**

- Year: 888 trades on 246 days (1444 signals; unpriced none), win 38%, Rs -30,656 (2x slippage Rs -45,644), mean Rs -34 a trade (avg win Rs 635, avg loss Rs -448), worst day Rs -4,085, worst month Rs -7,207 (2026-09), max drawdown Rs 36,009.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -90 a trade (10-90%: Rs -125 to Rs -56); p 0.022, BH q 1.00. Mean > 0 (day bootstrap): p 0.920, q 1.00.
- Smallest edge detectable (80% power): Rs 61 a trade at 5%, Rs 122 at the BH level.
- By side: calls 500 (Rs -20,116), puts 388 (Rs -10,540). By exit: breakeven 115 (Rs -1,546), index_stop 177 (Rs -147,050), prem_stop 80 (Rs -48,551), time 516 (Rs 166,491).
- Gates: failed - nothing passed the selection; not positive in every period (or the year at 2x slippage); BH q twins 1.00 / zero 1.00 > 0.10.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 381 | 38% | Rs -18,861 | Rs -25,534 | Rs -50 | Rs -92 | Rs -5,812 | Rs 25,178 |
| final (1 Apr - 19 Oct 2026) | 507 | 38% | Rs -11,796 | Rs -20,110 | Rs -23 | Rs -90 | Rs -7,207 | Rs 31,124 |
| last 6 months (20 Apr - 19 Oct 2026) | 469 | 38% | Rs -16,151 | Rs -23,631 | Rs -34 | Rs -90 | Rs -7,207 | Rs 31,124 |
| year (20 Oct 2025 - 19 Oct 2026) | 888 | 38% | Rs -30,656 | Rs -45,644 | Rs -34 | Rs -91 | Rs -7,207 | Rs 36,009 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 54 trades, win 22%, Rs -15,610.

## What this does not show

- True absorption (aggressive volume that fails to move price) needs real buy / sell volume; Dhan has neither futures minute volume for past years nor any second-level history. The phone's own recorder (R182 / R184) is the only source of tick-rule flow and covers weeks, not a year.
- One lot, one index at a time; no portfolio of indices.

- Indices not read yet: BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (same frozen rules; their tests count in m = 4,852 either way).
- Nothing in the app changes. Full write-up: `results/report.md`.
