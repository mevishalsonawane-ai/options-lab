# R185 report: absorption and exhaustion on two years of 1-minute data

*16 Nov 2026. Pre-registered in `PREREG.md` (committed and pushed before any number). Research only; nothing in the app changes.*

**Signal source:** the index's own 1-minute price (the underlying); each minute's buy / sell split estimated by BVC from the index price change, sized by the summed volume of the ATM-2..ATM+2 calls and puts (option-volume context, OVC); no rule reads an option's own price chart. Dhan has no index futures minute history for the year (expired futures are not served) and **no second-level history at all** (`/charts/intraday` with interval 1s returns nothing; `/charts/rollingoption` accepts 1, 5, 15 and 60 minutes only), so this is the brief's labelled fallback: **option-volume context (OVC)**. BVC infers the side of each minute's volume from the index's own price change, so it cannot see heavy selling that does not move the price - the heart of absorption. These rules are R184's definitions on estimated flow, not true order flow.

**Trade:** 1 lot of the ATM option (nearest expiry: weekly for NIFTY / SENSEX; for BANKNIFTY, FINNIFTY and MIDCPNIFTY the nearest is the monthly since Nov 2024), bought at the open of the minute after the signal; -15% premium stop, breakeven (fill + charges) from the minute after +10%, index stop beyond the extreme, 15 / 30-minute time stop, 15:14 (15:04 on expiry). Real dated charges, liquidity slippage (2x shown).

**Indices read:** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (order fixed: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX). BH m = 4,852 (4,512 after R184 + 320 variants + 20 tests), q ranked over the 20 tests read so far.

## Lots (Amendment A)

Every trade uses the true lot of the contract traded on its day (`r185lib.TRUE_LOTS`): NSE's F&O bhavcopies in the lake (`NewBrdLotQty` of the nearest expiry, `r185lots.py` -> `results/lot_sizes.json`); SENSEX from the lake's option volume / OI units; Dhan's instrument master agrees for the live contracts. The repo's shared schedule (`research/obuy/config.py`) is left unchanged; it differs on these trading days:

- NIFTY 2024-11-28 - 2024-12-26: true 25, schedule 75
- NIFTY 2025-01-24 - 2025-01-30: true 25, schedule 75
- BANKNIFTY 2024-11-28 - 2025-01-30: true 15, schedule 30
- BANKNIFTY 2025-12-31 - 2026-11-13: true 30, schedule 35
- FINNIFTY 2024-11-28 - 2025-01-30: true 25, schedule 65
- MIDCPNIFTY 2024-11-20 - 2024-11-27: true 50, schedule 75
- MIDCPNIFTY 2024-11-28 - 2025-01-30: true 50, schedule 120
- MIDCPNIFTY 2025-06-27 - 2025-12-30: true 140, schedule 120
- SENSEX 2024-11-28 - 2025-01-03 and 2025-01-22 - 2025-01-28: true 10, schedule 20

NIFTY's selection was run before the amendment with the schedule's lots (75 instead of 25 on 28 Nov - 26 Dec 2024 and 24 - 30 Jan 2025). Recomputed with the true lots (descriptive; the final year already used the true lots and is not re-read):
- ABS NIFTY: eligible 0 -> 0; choice `ABS w3 k2 b6 turn T30` -> `ABS w3 k2 b6 turn T30` (same); its selection net Rs -5,290 -> Rs -2,952.
- EXH NIFTY: eligible 0 -> 0; choice `EXH X30 k3 b6 flip+close T15` -> `EXH X30 k3 b3 flip+close T15` (DIFFERENT); its selection net Rs -54,523 -> Rs -56,853.

## Data

- **NIFTY**: 490 trading days 20 Nov 2024 - 13 Nov 2026 with >= 300 index minutes (dropped: 2025-10-21, 2026-11-08); strike step [50.0]; selection halves H1 109 days, H2 118 days.
  Gap fetch from Dhan (20 Oct - 13 Nov 2026): 18 days (2026-10-21 - 2026-11-13; short: 2026-11-08), 270,556 option rows (ATM-10..+10, CE / PE), 6,445 index minutes, 0 empty series-days, 30 missing near-ATM (+-2) option minutes. Raw files: `gdrive:IraAlgo-data-lake/dhan/NIFTY/ (and the lake folders options_rolling_1m/, index_1m/)`.
  Dhan vs the phone's 1-second recordings (20 days, 15 Oct - 13 Nov 2026): 72,623 of 72,655 option-minutes matched; median |diff| Rs 0.3, 95th pct Rs 1.75; 87% within 1%, 99% within 5%, 86% within Rs 1; correlation 0.99987. (The phone records the nearest expiry, so this also confirms Dhan's expiry code 1 is the nearest.)
- **BANKNIFTY**: 490 trading days 20 Nov 2024 - 13 Nov 2026 with >= 300 index minutes (dropped: 2025-10-21, 2026-11-08); strike step [100.0]; selection halves H1 109 days, H2 118 days.
  Gap fetch from Dhan (20 Oct - 13 Nov 2026): 18 days (2026-10-21 - 2026-11-13; short: 2026-11-08), 270,167 option rows (ATM-10..+10, CE / PE), 6,445 index minutes, 0 empty series-days, 31 missing near-ATM (+-2) option minutes. Raw files: `gdrive:IraAlgo-data-lake/dhan/BANKNIFTY/ (and the lake folders options_rolling_1m/, index_1m/)`.
  Dhan vs the phone's 1-second recordings (20 days, 15 Oct - 13 Nov 2026): 72,871 of 72,905 option-minutes matched; median |diff| Rs 0.1, 95th pct Rs 4.7; 95% within 1%, 100% within 5%, 68% within Rs 1; correlation 0.99994. (The phone records the nearest expiry, so this also confirms Dhan's expiry code 1 is the nearest.)
- **FINNIFTY**: 490 trading days 20 Nov 2024 - 13 Nov 2026 with >= 300 index minutes (dropped: 2025-10-21, 2026-11-08); strike step [50.0, 100.0, nan]; selection halves H1 109 days, H2 118 days.
  Gap fetch from Dhan (20 Oct - 13 Nov 2026): 18 days (2026-10-21 - 2026-11-13; short: 2026-11-08), 237,274 option rows (ATM-10..+10, CE / PE), 6,445 index minutes, 12 empty series-days, 1786 missing near-ATM (+-2) option minutes. Raw files: `gdrive:IraAlgo-data-lake/dhan/FINNIFTY/ (and the lake folders options_rolling_1m/, index_1m/)`.
- **MIDCPNIFTY**: 490 trading days 20 Nov 2024 - 13 Nov 2026 with >= 300 index minutes (dropped: 2025-10-21, 2026-11-08); strike step [25.0]; selection halves H1 109 days, H2 118 days.
  Gap fetch from Dhan (20 Oct - 13 Nov 2026): 18 days (2026-10-21 - 2026-11-13; short: 2026-11-08), 267,652 option rows (ATM-10..+10, CE / PE), 6,445 index minutes, 1 empty series-days, 140 missing near-ATM (+-2) option minutes. Raw files: `gdrive:IraAlgo-data-lake/dhan/MIDCPNIFTY/ (and the lake folders options_rolling_1m/, index_1m/)`.
- **SENSEX**: 490 trading days 20 Nov 2024 - 13 Nov 2026 with >= 300 index minutes (dropped: 2025-10-21, 2026-11-08); strike step [100.0]; selection halves H1 109 days, H2 118 days.
  Gap fetch from Dhan (20 Oct - 13 Nov 2026): 18 days (2026-10-21 - 2026-11-13; short: 2026-11-08), 270,134 option rows (ATM-10..+10, CE / PE), 6,445 index minutes, 0 empty series-days, 30 missing near-ATM (+-2) option minutes. Raw files: `gdrive:IraAlgo-data-lake/dhan/SENSEX/ (and the lake folders options_rolling_1m/, index_1m/)`.

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

## BANKNIFTY

### Selection (20 Nov 2024 - 19 Oct 2025)

**Absorption**: 32 versions, 0 with net > 0, **0 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `ABS w3 k2 b6 turn T15` (FALLBACK - nothing passed the selection): 141 trades, win 27%, Rs -25,918 (H1 34 trades Rs -5,327, mean Rs -157 vs twins Rs -129; H2 107 trades Rs -20,591, mean Rs -192 vs twins Rs -120); descriptive twin p 0.857 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| ABS w3 k3 b3 none T15 | 2 | Rs -42 | 1 / Rs -36 / Rs -36 / Rs -123 | 1 / Rs -6 / Rs -6 / Rs -244 |  |
| ABS w3 k3 b3 none T30 | 2 | Rs -42 | 1 / Rs -36 / Rs -36 / Rs -257 | 1 / Rs -6 / Rs -6 / Rs -202 |  |
| ABS w5 k2 b3 none T15 | 1 | Rs -426 | 1 / Rs -426 / Rs -426 / Rs -37 | 0 / Rs 0 / - / - |  |
| ABS w5 k2 b3 none T30 | 1 | Rs -426 | 1 / Rs -426 / Rs -426 / Rs -151 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b3 none T15 | 1 | Rs -426 | 1 / Rs -426 / Rs -426 / Rs -87 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b3 none T30 | 1 | Rs -426 | 1 / Rs -426 / Rs -426 / Rs -53 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b6 turn T15 | 5 | Rs -653 | 2 / Rs -664 / Rs -332 / Rs -111 | 3 / Rs 11 / Rs 4 / Rs -70 |  |
| ABS w5 k3 b6 none T15 | 11 | Rs -711 | 4 / Rs -214 / Rs -54 / Rs -108 | 7 / Rs -497 / Rs -71 / Rs -144 |  |
| ABS w3 k2 b3 none T30 | 8 | Rs -965 | 5 / Rs -1,261 / Rs -252 / Rs -133 | 3 / Rs 296 / Rs 99 / Rs -192 |  |
| ABS w3 k3 b3 turn T15 | 2 | Rs -1,117 | 1 / Rs -752 / Rs -752 / Rs -210 | 1 / Rs -365 / Rs -365 / Rs 186 |  |
| ABS w3 k3 b3 turn T30 | 2 | Rs -1,117 | 1 / Rs -752 / Rs -752 / Rs -270 | 1 / Rs -365 / Rs -365 / Rs 54 |  |
| ABS w5 k2 b3 turn T15 | 1 | Rs -1,142 | 1 / Rs -1,142 / Rs -1,142 / Rs -79 | 0 / Rs 0 / - / - |  |
| ABS w5 k2 b3 turn T30 | 1 | Rs -1,142 | 1 / Rs -1,142 / Rs -1,142 / Rs 3 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b3 turn T15 | 1 | Rs -1,142 | 1 / Rs -1,142 / Rs -1,142 / Rs -129 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b3 turn T30 | 1 | Rs -1,142 | 1 / Rs -1,142 / Rs -1,142 / Rs -181 | 0 / Rs 0 / - / - |  |
| ABS w3 k2 b3 none T15 | 8 | Rs -1,194 | 5 / Rs -1,261 / Rs -252 / Rs -103 | 3 / Rs 67 / Rs 22 / Rs -110 |  |
| ABS w3 k2 b3 turn T15 | 4 | Rs -1,287 | 3 / Rs -922 / Rs -307 / Rs -140 | 1 / Rs -365 / Rs -365 / Rs 109 |  |
| ABS w3 k2 b3 turn T30 | 4 | Rs -1,287 | 3 / Rs -922 / Rs -307 / Rs -121 | 1 / Rs -365 / Rs -365 / Rs 74 |  |
| ABS w5 k3 b6 turn T30 | 5 | Rs -1,369 | 2 / Rs -1,406 / Rs -703 / Rs -201 | 3 / Rs 37 / Rs 12 / Rs -209 |  |
| ABS w5 k3 b6 none T30 | 11 | Rs -1,374 | 4 / Rs -727 / Rs -182 / Rs -121 | 7 / Rs -647 / Rs -92 / Rs -191 |  |
| ABS w5 k2 b6 turn T15 | 43 | Rs -2,237 | 17 / Rs -1,689 / Rs -99 / Rs -60 | 26 / Rs -548 / Rs -21 / Rs -122 |  |
| ABS w5 k2 b6 turn T30 | 43 | Rs -3,524 | 17 / Rs -2,431 / Rs -143 / Rs -97 | 26 / Rs -1,093 / Rs -42 / Rs -179 |  |
| ABS w3 k3 b6 turn T15 | 32 | Rs -5,267 | 13 / Rs -1,983 / Rs -153 / Rs -52 | 19 / Rs -3,284 / Rs -173 / Rs -126 |  |
| ABS w3 k3 b6 none T15 | 51 | Rs -6,651 | 17 / Rs -1,036 / Rs -61 / Rs -109 | 34 / Rs -5,615 / Rs -165 / Rs -154 |  |
| ABS w3 k3 b6 turn T30 | 32 | Rs -7,438 | 13 / Rs -1,983 / Rs -153 / Rs -110 | 19 / Rs -5,455 / Rs -287 / Rs -200 |  |
| ABS w3 k3 b6 none T30 | 51 | Rs -8,323 | 17 / Rs -1,337 / Rs -79 / Rs -121 | 34 / Rs -6,986 / Rs -206 / Rs -170 |  |
| ABS w5 k2 b6 none T15 | 78 | Rs -9,402 | 23 / Rs -802 / Rs -35 / Rs -86 | 55 / Rs -8,600 / Rs -156 / Rs -158 |  |
| ABS w5 k2 b6 none T30 | 76 | Rs -11,991 | 23 / Rs -1,502 / Rs -65 / Rs -116 | 53 / Rs -10,489 / Rs -198 / Rs -153 |  |
| ABS w3 k2 b6 turn T15 | 141 | Rs -25,918 | 34 / Rs -5,327 / Rs -157 / Rs -129 | 107 / Rs -20,591 / Rs -192 / Rs -120 |  |
| ABS w3 k2 b6 turn T30 | 140 | Rs -28,609 | 34 / Rs -8,473 / Rs -249 / Rs -137 | 106 / Rs -20,136 / Rs -190 / Rs -134 |  |
| ABS w3 k2 b6 none T15 | 200 | Rs -31,604 | 42 / Rs -6,149 / Rs -146 / Rs -101 | 158 / Rs -25,455 / Rs -161 / Rs -144 |  |
| ABS w3 k2 b6 none T30 | 198 | Rs -32,171 | 42 / Rs -8,926 / Rs -212 / Rs -106 | 156 / Rs -23,245 / Rs -149 / Rs -143 |  |

**Exhaustion**: 32 versions, 0 with net > 0, **0 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `EXH X15 k2 b3 flip+close T15` (FALLBACK - nothing passed the selection): 889 trades, win 37%, Rs -79,918 (H1 426 trades Rs -18,334, mean Rs -43 vs twins Rs -124; H2 463 trades Rs -61,584, mean Rs -133 vs twins Rs -135); descriptive twin p 0.064 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| EXH X15 k2 b3 flip+close T15 | 889 | Rs -79,918 | 426 / Rs -18,334 / Rs -43 / Rs -124 | 463 / Rs -61,584 / Rs -133 / Rs -135 |  |
| EXH X15 k2 b3 flip T15 | 890 | Rs -84,534 | 427 / Rs -20,608 / Rs -48 / Rs -123 | 463 / Rs -63,926 / Rs -138 / Rs -126 |  |
| EXH X15 k3 b3 flip T15 | 758 | Rs -85,489 | 357 / Rs -26,385 / Rs -74 / Rs -118 | 401 / Rs -59,104 / Rs -147 / Rs -132 |  |
| EXH X30 k2 b3 flip+close T15 | 880 | Rs -86,395 | 422 / Rs -15,819 / Rs -38 / Rs -122 | 458 / Rs -70,576 / Rs -154 / Rs -134 |  |
| EXH X15 k3 b3 flip+close T15 | 750 | Rs -87,918 | 352 / Rs -27,343 / Rs -78 / Rs -117 | 398 / Rs -60,575 / Rs -152 / Rs -138 |  |
| EXH X30 k2 b3 flip T15 | 881 | Rs -88,273 | 423 / Rs -17,458 / Rs -41 / Rs -116 | 458 / Rs -70,815 / Rs -155 / Rs -133 |  |
| EXH X15 k2 b3 flip+close T30 | 885 | Rs -89,950 | 424 / Rs -21,102 / Rs -50 / Rs -117 | 461 / Rs -68,848 / Rs -149 / Rs -127 |  |
| EXH X30 k3 b3 flip+close T15 | 723 | Rs -91,413 | 339 / Rs -28,130 / Rs -83 / Rs -117 | 384 / Rs -63,283 / Rs -165 / Rs -131 |  |
| EXH X30 k3 b3 flip T15 | 732 | Rs -92,619 | 344 / Rs -25,416 / Rs -74 / Rs -122 | 388 / Rs -67,203 / Rs -173 / Rs -133 |  |
| EXH X15 k2 b3 flip T30 | 887 | Rs -92,761 | 425 / Rs -24,348 / Rs -57 / Rs -123 | 462 / Rs -68,413 / Rs -148 / Rs -135 |  |
| EXH X15 k3 b3 flip+close T30 | 739 | Rs -93,586 | 346 / Rs -35,023 / Rs -101 / Rs -115 | 393 / Rs -58,563 / Rs -149 / Rs -144 |  |
| EXH X30 k2 b3 flip+close T30 | 876 | Rs -93,742 | 421 / Rs -28,675 / Rs -68 / Rs -122 | 455 / Rs -65,067 / Rs -143 / Rs -132 |  |
| EXH X15 k3 b3 flip T30 | 746 | Rs -94,375 | 350 / Rs -34,229 / Rs -98 / Rs -120 | 396 / Rs -60,146 / Rs -152 / Rs -137 |  |
| EXH X30 k3 b6 flip+close T15 | 758 | Rs -100,662 | 363 / Rs -37,429 / Rs -103 / Rs -114 | 395 / Rs -63,233 / Rs -160 / Rs -128 |  |
| EXH X15 k3 b6 flip T15 | 796 | Rs -101,265 | 378 / Rs -37,949 / Rs -100 / Rs -119 | 418 / Rs -63,316 / Rs -152 / Rs -124 |  |
| EXH X30 k3 b6 flip T15 | 770 | Rs -101,623 | 370 / Rs -35,051 / Rs -95 / Rs -112 | 400 / Rs -66,572 / Rs -166 / Rs -123 |  |
| EXH X30 k2 b3 flip T30 | 879 | Rs -102,127 | 422 / Rs -31,791 / Rs -75 / Rs -130 | 457 / Rs -70,336 / Rs -154 / Rs -128 |  |
| EXH X30 k3 b3 flip+close T30 | 713 | Rs -102,590 | 334 / Rs -39,919 / Rs -120 / Rs -124 | 379 / Rs -62,671 / Rs -165 / Rs -136 |  |
| EXH X15 k3 b6 flip+close T15 | 783 | Rs -103,220 | 371 / Rs -38,417 / Rs -104 / Rs -114 | 412 / Rs -64,803 / Rs -157 / Rs -130 |  |
| EXH X30 k3 b3 flip T30 | 721 | Rs -105,292 | 338 / Rs -36,075 / Rs -107 / Rs -124 | 383 / Rs -69,217 / Rs -181 / Rs -136 |  |
| EXH X30 k2 b6 flip T15 | 889 | Rs -105,675 | 428 / Rs -25,172 / Rs -59 / Rs -121 | 461 / Rs -80,503 / Rs -175 / Rs -123 |  |
| EXH X15 k2 b6 flip T15 | 896 | Rs -109,458 | 430 / Rs -29,576 / Rs -69 / Rs -125 | 466 / Rs -79,882 / Rs -171 / Rs -124 |  |
| EXH X15 k2 b6 flip+close T15 | 895 | Rs -110,779 | 429 / Rs -33,236 / Rs -78 / Rs -124 | 466 / Rs -77,543 / Rs -166 / Rs -131 |  |
| EXH X15 k3 b6 flip T30 | 783 | Rs -111,726 | 375 / Rs -37,331 / Rs -100 / Rs -117 | 408 / Rs -74,395 / Rs -182 / Rs -132 |  |
| EXH X30 k2 b6 flip+close T15 | 886 | Rs -112,556 | 427 / Rs -29,809 / Rs -70 / Rs -124 | 459 / Rs -82,747 / Rs -180 / Rs -129 |  |
| EXH X15 k3 b6 flip+close T30 | 770 | Rs -113,702 | 368 / Rs -39,968 / Rs -109 / Rs -114 | 402 / Rs -73,734 / Rs -183 / Rs -124 |  |
| EXH X15 k2 b6 flip+close T30 | 890 | Rs -113,801 | 428 / Rs -44,868 / Rs -105 / Rs -119 | 462 / Rs -68,933 / Rs -149 / Rs -122 |  |
| EXH X30 k3 b6 flip+close T30 | 747 | Rs -114,216 | 359 / Rs -43,760 / Rs -122 / Rs -111 | 388 / Rs -70,456 / Rs -182 / Rs -125 |  |
| EXH X30 k3 b6 flip T30 | 759 | Rs -116,033 | 366 / Rs -36,298 / Rs -99 / Rs -124 | 393 / Rs -79,735 / Rs -203 / Rs -136 |  |
| EXH X15 k2 b6 flip T30 | 892 | Rs -118,612 | 429 / Rs -41,946 / Rs -98 / Rs -120 | 463 / Rs -76,666 / Rs -166 / Rs -126 |  |
| EXH X30 k2 b6 flip+close T30 | 879 | Rs -126,678 | 425 / Rs -47,946 / Rs -113 / Rs -124 | 454 / Rs -78,732 / Rs -173 / Rs -123 |  |
| EXH X30 k2 b6 flip T30 | 884 | Rs -130,367 | 426 / Rs -45,600 / Rs -107 / Rs -124 | 458 / Rs -84,767 / Rs -185 / Rs -117 |  |

### Final year (20 Oct 2025 - 19 Oct 2026, read once)

Read at 2026-11-16T03:42:08.703549+00:00 after `FINAL_READ_BANKNIFTY` (2026-11-16T03:41:25Z); 246 days.

**Absorption `ABS w3 k2 b6 turn T15`** - verdict: **no evidence (nothing passed the selection)**

- Year: 203 trades on 91 days (287 signals; unpriced none), win 46%, Rs -21,339 (2x slippage Rs -25,407), mean Rs -105 a trade (avg win Rs 376, avg loss Rs -520), worst day Rs -3,342, worst month Rs -9,840 (2025-12), max drawdown Rs 24,038.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -129 a trade (10-90%: Rs -220 to Rs -41); p 0.348, BH q 1.00. Mean > 0 (day bootstrap): p 0.993, q 1.00.
- Smallest edge detectable (80% power): Rs 101 a trade at 5%, Rs 200 at the BH level.
- By side: calls 92 (Rs -3,498), puts 111 (Rs -17,840). By exit: breakeven 6 (Rs -39), index_stop 53 (Rs -41,429), prem_stop 14 (Rs -7,023), time 130 (Rs 27,153).
- Gates: failed - nothing passed the selection; not positive in every period (or the year at 2x slippage); not above the twins in early, final and the year; BH q twins 1.00 / zero 1.00 > 0.10.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 106 | 50% | Rs -8,016 | Rs -10,064 | Rs -76 | Rs -132 | Rs -9,840 | Rs 15,220 |
| final (1 Apr - 19 Oct 2026) | 97 | 42% | Rs -13,322 | Rs -15,344 | Rs -137 | Rs -129 | Rs -6,208 | Rs 14,696 |
| last 6 months (20 Apr - 19 Oct 2026) | 97 | 42% | Rs -13,322 | Rs -15,344 | Rs -137 | Rs -129 | Rs -6,208 | Rs 14,696 |
| year (20 Oct 2025 - 19 Oct 2026) | 203 | 46% | Rs -21,339 | Rs -25,407 | Rs -105 | Rs -131 | Rs -9,840 | Rs 24,038 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 1 trades, win 0%, Rs -604.

**Exhaustion `EXH X15 k2 b3 flip+close T15`** - verdict: **no evidence (nothing passed the selection)**

- Year: 980 trades on 246 days (2532 signals; unpriced none), win 36%, Rs -100,340 (2x slippage Rs -129,055), mean Rs -102 a trade (avg win Rs 800, avg loss Rs -620), worst day Rs -5,462, worst month Rs -23,061 (2025-12), max drawdown Rs 103,412.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -148 a trade (10-90%: Rs -184 to Rs -110); p 0.058, BH q 1.00. Mean > 0 (day bootstrap): p 1.000, q 1.00.
- Smallest edge detectable (80% power): Rs 73 a trade at 5%, Rs 144 at the BH level.
- By side: calls 511 (Rs -57,241), puts 469 (Rs -43,100). By exit: breakeven 26 (Rs -378), index_stop 458 (Rs -342,905), prem_stop 4 (Rs -2,316), time 492 (Rs 245,259).
- Gates: failed - nothing passed the selection; not positive in every period (or the year at 2x slippage); BH q twins 1.00 / zero 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 432 | 35% | Rs -48,454 | Rs -60,429 | Rs -112 | Rs -150 | Rs -23,061 | Rs 63,026 |
| final (1 Apr - 19 Oct 2026) | 548 | 37% | Rs -51,887 | Rs -68,626 | Rs -95 | Rs -148 | Rs -17,067 | Rs 57,561 |
| last 6 months (20 Apr - 19 Oct 2026) | 504 | 37% | Rs -48,736 | Rs -63,261 | Rs -97 | Rs -147 | Rs -17,067 | Rs 52,173 |
| year (20 Oct 2025 - 19 Oct 2026) | 980 | 36% | Rs -100,340 | Rs -129,055 | Rs -102 | Rs -149 | Rs -23,061 | Rs 103,412 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 68 trades, win 32%, Rs -11,511.

## FINNIFTY

### Selection (20 Nov 2024 - 19 Oct 2025)

**Absorption**: 32 versions, 4 with net > 0, **1 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `ABS w5 k2 b6 turn T30` (passed the selection): 118 trades, win 37%, Rs 17,629 (H1 32 trades Rs 12,014, mean Rs 375 vs twins Rs -107; H2 86 trades Rs 5,615, mean Rs 65 vs twins Rs -107); descriptive twin p 0.003 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| ABS w5 k2 b6 turn T30 | 118 | Rs 17,629 | 32 / Rs 12,014 / Rs 375 / Rs -107 | 86 / Rs 5,615 / Rs 65 / Rs -107 | yes |
| ABS w5 k3 b6 turn T30 | 41 | Rs 2,378 | 9 / Rs -2,051 / Rs -228 / Rs -123 | 32 / Rs 4,429 / Rs 138 / Rs -163 |  |
| ABS w5 k2 b3 turn T30 | 21 | Rs 539 | 5 / Rs 799 / Rs 160 / Rs -93 | 16 / Rs -260 / Rs -16 / Rs -75 |  |
| ABS w3 k3 b3 turn T30 | 13 | Rs 146 | 2 / Rs -995 / Rs -497 / Rs -174 | 11 / Rs 1,141 / Rs 104 / Rs -77 |  |
| ABS w5 k2 b3 turn T15 | 21 | Rs -384 | 5 / Rs 2,010 / Rs 402 / Rs -75 | 16 / Rs -2,394 / Rs -150 / Rs -61 |  |
| ABS w3 k3 b3 turn T15 | 13 | Rs -1,593 | 2 / Rs -974 / Rs -487 / Rs -114 | 11 / Rs -619 / Rs -56 / Rs -116 |  |
| ABS w3 k2 b3 turn T30 | 36 | Rs -2,208 | 6 / Rs 1,401 / Rs 234 / Rs -105 | 30 / Rs -3,609 / Rs -120 / Rs -80 |  |
| ABS w5 k2 b3 none T15 | 36 | Rs -2,782 | 8 / Rs 3,585 / Rs 448 / Rs -100 | 28 / Rs -6,367 / Rs -227 / Rs -94 |  |
| ABS w5 k3 b3 turn T30 | 6 | Rs -2,824 | 1 / Rs -661 / Rs -661 / Rs -144 | 5 / Rs -2,163 / Rs -432 / Rs -125 |  |
| ABS w5 k3 b3 turn T15 | 6 | Rs -3,344 | 1 / Rs -661 / Rs -661 / Rs -136 | 5 / Rs -2,683 / Rs -537 / Rs -111 |  |
| ABS w5 k2 b3 none T30 | 36 | Rs -3,709 | 8 / Rs 1,558 / Rs 195 / Rs -98 | 28 / Rs -5,267 / Rs -188 / Rs -98 |  |
| ABS w5 k2 b6 turn T15 | 124 | Rs -4,076 | 33 / Rs 3,317 / Rs 100 / Rs -91 | 91 / Rs -7,393 / Rs -81 / Rs -123 |  |
| ABS w5 k3 b3 none T15 | 11 | Rs -4,739 | 1 / Rs -6 / Rs -6 / Rs -88 | 10 / Rs -4,733 / Rs -473 / Rs -148 |  |
| ABS w3 k2 b3 turn T15 | 36 | Rs -4,895 | 6 / Rs 566 / Rs 94 / Rs -80 | 30 / Rs -5,461 / Rs -182 / Rs -105 |  |
| ABS w5 k3 b3 none T30 | 11 | Rs -5,822 | 1 / Rs -6 / Rs -6 / Rs -76 | 10 / Rs -5,816 / Rs -582 / Rs -70 |  |
| ABS w3 k3 b3 none T15 | 24 | Rs -6,589 | 3 / Rs -4,426 / Rs -1,475 / Rs 6 | 21 / Rs -2,163 / Rs -103 / Rs -105 |  |
| ABS w3 k3 b3 none T30 | 24 | Rs -7,223 | 3 / Rs -4,646 / Rs -1,549 / Rs -93 | 21 / Rs -2,577 / Rs -123 / Rs -101 |  |
| ABS w5 k3 b6 turn T15 | 41 | Rs -7,406 | 9 / Rs -1,360 / Rs -151 / Rs -59 | 32 / Rs -6,046 / Rs -189 / Rs -119 |  |
| ABS w5 k3 b6 none T30 | 64 | Rs -7,833 | 15 / Rs -1,842 / Rs -123 / Rs -91 | 49 / Rs -5,991 / Rs -122 / Rs -115 |  |
| ABS w5 k2 b6 none T30 | 180 | Rs -9,546 | 43 / Rs 8,452 / Rs 197 / Rs -108 | 137 / Rs -17,998 / Rs -131 / Rs -116 |  |
| ABS w3 k3 b6 turn T30 | 93 | Rs -9,601 | 21 / Rs -2,735 / Rs -130 / Rs -109 | 72 / Rs -6,866 / Rs -95 / Rs -130 |  |
| ABS w5 k3 b6 none T15 | 65 | Rs -10,878 | 15 / Rs -980 / Rs -65 / Rs -83 | 50 / Rs -9,898 / Rs -198 / Rs -124 |  |
| ABS w3 k2 b3 none T30 | 71 | Rs -12,762 | 13 / Rs -4,005 / Rs -308 / Rs -63 | 58 / Rs -8,757 / Rs -151 / Rs -101 |  |
| ABS w3 k2 b3 none T15 | 72 | Rs -13,262 | 13 / Rs -3,445 / Rs -265 / Rs -50 | 59 / Rs -9,817 / Rs -166 / Rs -95 |  |
| ABS w3 k3 b6 none T30 | 142 | Rs -14,498 | 31 / Rs -4,766 / Rs -154 / Rs -100 | 111 / Rs -9,732 / Rs -88 / Rs -146 |  |
| ABS w3 k2 b6 turn T30 | 253 | Rs -14,983 | 61 / Rs 9,176 / Rs 150 / Rs -110 | 192 / Rs -24,159 / Rs -126 / Rs -152 |  |
| ABS w3 k3 b6 turn T15 | 95 | Rs -18,008 | 22 / Rs -2,143 / Rs -97 / Rs -110 | 73 / Rs -15,865 / Rs -217 / Rs -120 |  |
| ABS w5 k2 b6 none T15 | 188 | Rs -18,766 | 43 / Rs 3,074 / Rs 72 / Rs -100 | 145 / Rs -21,840 / Rs -151 / Rs -126 |  |
| ABS w3 k3 b6 none T15 | 144 | Rs -18,867 | 32 / Rs -5,462 / Rs -171 / Rs -99 | 112 / Rs -13,405 / Rs -120 / Rs -148 |  |
| ABS w3 k2 b6 none T30 | 358 | Rs -29,617 | 93 / Rs 4,201 / Rs 45 / Rs -114 | 265 / Rs -33,818 / Rs -128 / Rs -134 |  |
| ABS w3 k2 b6 turn T15 | 272 | Rs -42,607 | 63 / Rs -1,559 / Rs -25 / Rs -111 | 209 / Rs -41,048 / Rs -196 / Rs -134 |  |
| ABS w3 k2 b6 none T15 | 373 | Rs -44,322 | 97 / Rs -4,241 / Rs -44 / Rs -88 | 276 / Rs -40,081 / Rs -145 / Rs -128 |  |

**Exhaustion**: 32 versions, 0 with net > 0, **0 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `EXH X15 k3 b3 flip T30` (FALLBACK - nothing passed the selection): 758 trades, win 32%, Rs -37,890 (H1 344 trades Rs -12,002, mean Rs -35 vs twins Rs -119; H2 414 trades Rs -25,888, mean Rs -62 vs twins Rs -111); descriptive twin p 0.022 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| EXH X15 k3 b3 flip T30 | 758 | Rs -37,890 | 344 / Rs -12,002 / Rs -35 / Rs -119 | 414 / Rs -25,888 / Rs -62 / Rs -111 |  |
| EXH X15 k3 b3 flip+close T30 | 741 | Rs -37,931 | 335 / Rs -10,371 / Rs -31 / Rs -123 | 406 / Rs -27,560 / Rs -68 / Rs -115 |  |
| EXH X15 k3 b3 flip+close T15 | 754 | Rs -52,252 | 339 / Rs -18,030 / Rs -53 / Rs -112 | 415 / Rs -34,222 / Rs -82 / Rs -127 |  |
| EXH X15 k3 b3 flip T15 | 771 | Rs -52,465 | 348 / Rs -18,358 / Rs -53 / Rs -113 | 423 / Rs -34,107 / Rs -81 / Rs -124 |  |
| EXH X30 k3 b3 flip+close T30 | 710 | Rs -52,603 | 318 / Rs -23,780 / Rs -75 / Rs -116 | 392 / Rs -28,823 / Rs -74 / Rs -113 |  |
| EXH X30 k3 b3 flip T30 | 725 | Rs -53,028 | 326 / Rs -25,928 / Rs -80 / Rs -123 | 399 / Rs -27,100 / Rs -68 / Rs -129 |  |
| EXH X30 k3 b3 flip+close T15 | 719 | Rs -56,968 | 321 / Rs -20,616 / Rs -64 / Rs -118 | 398 / Rs -36,352 / Rs -91 / Rs -126 |  |
| EXH X30 k3 b3 flip T15 | 734 | Rs -57,330 | 329 / Rs -21,090 / Rs -64 / Rs -114 | 405 / Rs -36,240 / Rs -90 / Rs -117 |  |
| EXH X15 k3 b6 flip+close T30 | 767 | Rs -64,598 | 358 / Rs -28,096 / Rs -78 / Rs -125 | 409 / Rs -36,502 / Rs -89 / Rs -122 |  |
| EXH X15 k2 b3 flip+close T30 | 876 | Rs -72,056 | 413 / Rs -27,233 / Rs -66 / Rs -123 | 463 / Rs -44,823 / Rs -97 / Rs -108 |  |
| EXH X15 k2 b3 flip T30 | 879 | Rs -73,968 | 416 / Rs -32,761 / Rs -79 / Rs -117 | 463 / Rs -41,207 / Rs -89 / Rs -112 |  |
| EXH X15 k3 b6 flip T30 | 797 | Rs -75,883 | 372 / Rs -38,636 / Rs -104 / Rs -117 | 425 / Rs -37,247 / Rs -88 / Rs -111 |  |
| EXH X15 k3 b6 flip+close T15 | 787 | Rs -76,295 | 366 / Rs -36,639 / Rs -100 / Rs -116 | 421 / Rs -39,656 / Rs -94 / Rs -126 |  |
| EXH X30 k3 b6 flip+close T30 | 740 | Rs -77,328 | 344 / Rs -42,946 / Rs -125 / Rs -125 | 396 / Rs -34,382 / Rs -87 / Rs -118 |  |
| EXH X30 k3 b6 flip+close T15 | 752 | Rs -79,212 | 350 / Rs -42,249 / Rs -121 / Rs -111 | 402 / Rs -36,963 / Rs -92 / Rs -125 |  |
| EXH X15 k3 b6 flip T15 | 812 | Rs -82,225 | 379 / Rs -41,149 / Rs -109 / Rs -112 | 433 / Rs -41,076 / Rs -95 / Rs -124 |  |
| EXH X30 k2 b3 flip T30 | 860 | Rs -82,506 | 401 / Rs -27,419 / Rs -68 / Rs -119 | 459 / Rs -55,087 / Rs -120 / Rs -116 |  |
| EXH X30 k2 b3 flip+close T30 | 854 | Rs -86,358 | 398 / Rs -27,733 / Rs -70 / Rs -119 | 456 / Rs -58,625 / Rs -129 / Rs -110 |  |
| EXH X30 k3 b6 flip T15 | 775 | Rs -88,894 | 359 / Rs -47,509 / Rs -132 / Rs -114 | 416 / Rs -41,385 / Rs -100 / Rs -127 |  |
| EXH X15 k2 b6 flip+close T30 | 887 | Rs -92,269 | 423 / Rs -36,600 / Rs -86 / Rs -123 | 464 / Rs -55,669 / Rs -120 / Rs -115 |  |
| EXH X30 k3 b6 flip T30 | 764 | Rs -94,161 | 354 / Rs -53,973 / Rs -152 / Rs -124 | 410 / Rs -40,188 / Rs -98 / Rs -126 |  |
| EXH X30 k2 b3 flip+close T15 | 859 | Rs -98,047 | 400 / Rs -33,132 / Rs -83 / Rs -116 | 459 / Rs -64,915 / Rs -141 / Rs -119 |  |
| EXH X15 k2 b6 flip T30 | 894 | Rs -98,671 | 427 / Rs -43,129 / Rs -101 / Rs -128 | 467 / Rs -55,542 / Rs -119 / Rs -116 |  |
| EXH X15 k2 b3 flip T15 | 881 | Rs -100,275 | 417 / Rs -41,621 / Rs -100 / Rs -110 | 464 / Rs -58,654 / Rs -126 / Rs -115 |  |
| EXH X30 k2 b3 flip T15 | 862 | Rs -100,708 | 402 / Rs -33,810 / Rs -84 / Rs -110 | 460 / Rs -66,898 / Rs -145 / Rs -120 |  |
| EXH X30 k2 b6 flip+close T30 | 874 | Rs -101,108 | 419 / Rs -43,668 / Rs -104 / Rs -131 | 455 / Rs -57,440 / Rs -126 / Rs -120 |  |
| EXH X15 k2 b3 flip+close T15 | 878 | Rs -101,807 | 414 / Rs -38,361 / Rs -93 / Rs -116 | 464 / Rs -63,446 / Rs -137 / Rs -117 |  |
| EXH X30 k2 b6 flip T30 | 884 | Rs -115,710 | 422 / Rs -45,499 / Rs -108 / Rs -121 | 462 / Rs -70,211 / Rs -152 / Rs -125 |  |
| EXH X15 k2 b6 flip+close T15 | 889 | Rs -116,921 | 424 / Rs -53,686 / Rs -127 / Rs -119 | 465 / Rs -63,235 / Rs -136 / Rs -123 |  |
| EXH X15 k2 b6 flip T15 | 895 | Rs -118,034 | 428 / Rs -51,650 / Rs -121 / Rs -120 | 467 / Rs -66,384 / Rs -142 / Rs -120 |  |
| EXH X30 k2 b6 flip+close T15 | 879 | Rs -138,322 | 420 / Rs -56,123 / Rs -134 / Rs -116 | 459 / Rs -82,199 / Rs -179 / Rs -126 |  |
| EXH X30 k2 b6 flip T15 | 887 | Rs -146,159 | 423 / Rs -56,210 / Rs -133 / Rs -118 | 464 / Rs -89,949 / Rs -194 / Rs -126 |  |

### Final year (20 Oct 2025 - 19 Oct 2026, read once)

Read at 2026-11-16T03:44:46.213348+00:00 after `FINAL_READ_FINNIFTY` (2026-11-16T03:43:25Z); 246 days.

**Absorption `ABS w5 k2 b6 turn T30`** - verdict: **worth a forward test**

- Year: 205 trades on 119 days (379 signals; unpriced {'no_strike': 3, 'no_price': 3}), win 41%, Rs 20,097 (2x slippage Rs 13,329), mean Rs 98 a trade (avg win Rs 1,030, avg loss Rs -562), worst day Rs -2,622, worst month Rs -7,854 (2026-03), max drawdown Rs 12,691.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -58 a trade (10-90%: Rs -161 to Rs 49); p 0.037, BH q 1.00. Mean > 0 (day bootstrap): p 0.082, q 1.00.
- Smallest edge detectable (80% power): Rs 181 a trade at 5%, Rs 359 at the BH level.
- By side: calls 82 (Rs 6,868), puts 123 (Rs 13,229). By exit: breakeven 8 (Rs -2,125), index_stop 67 (Rs -49,841), prem_stop 5 (Rs -2,041), time 125 (Rs 74,103).
- Gates: failed - BH q twins 1.00 / zero 1.00 > 0.10.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 106 | 39% | Rs 6,033 | Rs 2,695 | Rs 57 | Rs -50 | Rs -7,854 | Rs 9,657 |
| final (1 Apr - 19 Oct 2026) | 99 | 44% | Rs 14,064 | Rs 10,633 | Rs 142 | Rs -68 | Rs -3,450 | Rs 4,984 |
| last 6 months (20 Apr - 19 Oct 2026) | 98 | 44% | Rs 13,699 | Rs 10,306 | Rs 140 | Rs -67 | Rs -3,450 | Rs 4,984 |
| year (20 Oct 2025 - 19 Oct 2026) | 205 | 41% | Rs 20,097 | Rs 13,329 | Rs 98 | Rs -58 | Rs -7,854 | Rs 12,691 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 7 trades, win 43%, Rs 1,596.

**Exhaustion `EXH X15 k3 b3 flip T30`** - verdict: **no evidence (nothing passed the selection)**

- Year: 819 trades on 238 days (1301 signals; unpriced {'no_price': 15, 'no_strike': 14}), win 31%, Rs -45,338 (2x slippage Rs -76,887), mean Rs -55 a trade (avg win Rs 1,106, avg loss Rs -583), worst day Rs -7,515, worst month Rs -20,832 (2026-01), max drawdown Rs 60,113.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -78 a trade (10-90%: Rs -130 to Rs -21); p 0.336, BH q 1.00. Mean > 0 (day bootstrap): p 0.921, q 1.00.
- Smallest edge detectable (80% power): Rs 99 a trade at 5%, Rs 196 at the BH level.
- By side: calls 437 (Rs -31,777), puts 382 (Rs -13,561). By exit: breakeven 42 (Rs -2,976), index_stop 475 (Rs -272,946), prem_stop 12 (Rs -16,827), time 290 (Rs 247,412).
- Gates: failed - nothing passed the selection; not positive in every period (or the year at 2x slippage); not above the twins in early, final and the year; BH q twins 1.00 / zero 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 368 | 32% | Rs -34,456 | Rs -47,501 | Rs -94 | Rs -82 | Rs -20,832 | Rs 43,844 |
| final (1 Apr - 19 Oct 2026) | 451 | 31% | Rs -10,882 | Rs -29,386 | Rs -24 | Rs -78 | Rs -14,005 | Rs 35,288 |
| last 6 months (20 Apr - 19 Oct 2026) | 430 | 31% | Rs -11,058 | Rs -28,374 | Rs -26 | Rs -79 | Rs -14,005 | Rs 35,288 |
| year (20 Oct 2025 - 19 Oct 2026) | 819 | 31% | Rs -45,338 | Rs -76,887 | Rs -55 | Rs -80 | Rs -20,832 | Rs 60,113 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 56 trades, win 30%, Rs 12,340.

## MIDCPNIFTY

### Selection (20 Nov 2024 - 19 Oct 2025)

**Absorption**: 32 versions, 6 with net > 0, **0 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `ABS w3 k2 b6 none T15` (FALLBACK - nothing passed the selection): 208 trades, win 34%, Rs -16,752 (H1 78 trades Rs -10,054, mean Rs -129 vs twins Rs -142; H2 130 trades Rs -6,698, mean Rs -52 vs twins Rs -147); descriptive twin p 0.141 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| ABS w5 k3 b6 none T15 | 16 | Rs 3,269 | 5 / Rs 891 / Rs 178 / Rs -75 | 11 / Rs 2,378 / Rs 216 / Rs -170 |  |
| ABS w3 k3 b3 none T15 | 5 | Rs 2,651 | 2 / Rs 4,148 / Rs 2,074 / Rs -208 | 3 / Rs -1,497 / Rs -499 / Rs -262 |  |
| ABS w5 k3 b6 turn T15 | 7 | Rs 2,560 | 2 / Rs 731 / Rs 365 / Rs 14 | 5 / Rs 1,829 / Rs 366 / Rs -260 |  |
| ABS w5 k3 b6 none T30 | 16 | Rs 1,633 | 5 / Rs -1,203 / Rs -241 / Rs -129 | 11 / Rs 2,836 / Rs 258 / Rs -259 |  |
| ABS w3 k3 b3 none T30 | 5 | Rs 1,371 | 2 / Rs 2,868 / Rs 1,434 / Rs -173 | 3 / Rs -1,497 / Rs -499 / Rs -108 |  |
| ABS w5 k3 b6 turn T30 | 7 | Rs 788 | 2 / Rs -557 / Rs -278 / Rs -74 | 5 / Rs 1,345 / Rs 269 / Rs -233 |  |
| ABS w5 k3 b3 turn T15 | 2 | Rs -407 | 1 / Rs 574 / Rs 574 / Rs -151 | 1 / Rs -981 / Rs -981 / Rs 65 |  |
| ABS w5 k2 b3 turn T15 | 4 | Rs -541 | 2 / Rs 176 / Rs 88 / Rs -101 | 2 / Rs -717 / Rs -359 / Rs -119 |  |
| ABS w5 k3 b3 none T15 | 5 | Rs -1,270 | 2 / Rs 1,364 / Rs 682 / Rs -200 | 3 / Rs -2,634 / Rs -878 / Rs -198 |  |
| ABS w3 k2 b3 none T15 | 17 | Rs -1,452 | 7 / Rs -3,485 / Rs -498 / Rs -137 | 10 / Rs 2,033 / Rs 203 / Rs -213 |  |
| ABS w5 k2 b3 none T15 | 9 | Rs -1,767 | 3 / Rs 1,374 / Rs 458 / Rs -85 | 6 / Rs -3,141 / Rs -524 / Rs -192 |  |
| ABS w5 k3 b3 turn T30 | 2 | Rs -1,944 | 1 / Rs -963 / Rs -963 / Rs -89 | 1 / Rs -981 / Rs -981 / Rs -77 |  |
| ABS w3 k2 b3 turn T15 | 12 | Rs -2,802 | 4 / Rs -3,077 / Rs -769 / Rs -123 | 8 / Rs 275 / Rs 34 / Rs -146 |  |
| ABS w5 k2 b3 turn T30 | 4 | Rs -2,857 | 2 / Rs -1,942 / Rs -971 / Rs -184 | 2 / Rs -915 / Rs -458 / Rs -30 |  |
| ABS w3 k3 b3 turn T15 | 3 | Rs -3,211 | 1 / Rs -2,358 / Rs -2,358 / Rs -162 | 2 / Rs -853 / Rs -427 / Rs -174 |  |
| ABS w3 k3 b3 turn T30 | 3 | Rs -3,409 | 1 / Rs -2,358 / Rs -2,358 / Rs -184 | 2 / Rs -1,051 / Rs -526 / Rs -241 |  |
| ABS w5 k3 b3 none T30 | 5 | Rs -4,074 | 2 / Rs -1,440 / Rs -720 / Rs -84 | 3 / Rs -2,634 / Rs -878 / Rs -170 |  |
| ABS w3 k3 b6 turn T30 | 31 | Rs -4,291 | 11 / Rs -3,676 / Rs -334 / Rs -123 | 20 / Rs -615 / Rs -31 / Rs -200 |  |
| ABS w3 k2 b3 turn T30 | 12 | Rs -4,677 | 4 / Rs -3,443 / Rs -861 / Rs -146 | 8 / Rs -1,234 / Rs -154 / Rs -191 |  |
| ABS w5 k2 b3 none T30 | 9 | Rs -5,553 | 3 / Rs -1,718 / Rs -572 / Rs -132 | 6 / Rs -3,835 / Rs -639 / Rs -183 |  |
| ABS w5 k2 b6 turn T30 | 48 | Rs -5,709 | 12 / Rs -3,507 / Rs -292 / Rs -176 | 36 / Rs -2,202 / Rs -61 / Rs -165 |  |
| ABS w5 k2 b6 none T15 | 85 | Rs -7,924 | 28 / Rs -3,702 / Rs -132 / Rs -151 | 57 / Rs -4,222 / Rs -74 / Rs -165 |  |
| ABS w5 k2 b6 turn T15 | 50 | Rs -8,375 | 13 / Rs -1,225 / Rs -94 / Rs -102 | 37 / Rs -7,150 / Rs -193 / Rs -134 |  |
| ABS w3 k2 b3 none T30 | 17 | Rs -9,430 | 7 / Rs -8,235 / Rs -1,176 / Rs -150 | 10 / Rs -1,195 / Rs -120 / Rs -239 |  |
| ABS w3 k3 b6 turn T15 | 31 | Rs -9,645 | 11 / Rs -4,830 / Rs -439 / Rs -99 | 20 / Rs -4,815 / Rs -241 / Rs -208 |  |
| ABS w3 k3 b6 none T30 | 57 | Rs -11,441 | 20 / Rs -3,949 / Rs -197 / Rs -156 | 37 / Rs -7,492 / Rs -202 / Rs -201 |  |
| ABS w5 k2 b6 none T30 | 83 | Rs -11,822 | 27 / Rs -6,441 / Rs -239 / Rs -123 | 56 / Rs -5,381 / Rs -96 / Rs -158 |  |
| ABS w3 k3 b6 none T15 | 57 | Rs -15,133 | 20 / Rs -3,428 / Rs -171 / Rs -176 | 37 / Rs -11,705 / Rs -316 / Rs -126 |  |
| ABS w3 k2 b6 none T15 | 208 | Rs -16,752 | 78 / Rs -10,054 / Rs -129 / Rs -142 | 130 / Rs -6,698 / Rs -52 / Rs -147 |  |
| ABS w3 k2 b6 turn T15 | 141 | Rs -18,001 | 49 / Rs -6,080 / Rs -124 / Rs -127 | 92 / Rs -11,921 / Rs -130 / Rs -156 |  |
| ABS w3 k2 b6 turn T30 | 136 | Rs -19,403 | 48 / Rs -6,908 / Rs -144 / Rs -131 | 88 / Rs -12,495 / Rs -142 / Rs -175 |  |
| ABS w3 k2 b6 none T30 | 202 | Rs -22,523 | 76 / Rs -17,274 / Rs -227 / Rs -150 | 126 / Rs -5,249 / Rs -42 / Rs -178 |  |

**Exhaustion**: 32 versions, 0 with net > 0, **0 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `EXH X15 k3 b3 flip+close T30` (FALLBACK - nothing passed the selection): 690 trades, win 31%, Rs -38,333 (H1 338 trades Rs -1,337, mean Rs -4 vs twins Rs -159; H2 352 trades Rs -36,996, mean Rs -105 vs twins Rs -174); descriptive twin p 0.005 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| EXH X15 k3 b3 flip+close T30 | 690 | Rs -38,333 | 338 / Rs -1,337 / Rs -4 / Rs -159 | 352 / Rs -36,996 / Rs -105 / Rs -174 |  |
| EXH X15 k3 b3 flip T30 | 698 | Rs -44,223 | 344 / Rs -238 / Rs -1 / Rs -158 | 354 / Rs -43,985 / Rs -124 / Rs -169 |  |
| EXH X30 k3 b3 flip+close T30 | 653 | Rs -45,190 | 312 / Rs -4,639 / Rs -15 / Rs -152 | 341 / Rs -40,551 / Rs -119 / Rs -169 |  |
| EXH X30 k3 b3 flip T30 | 665 | Rs -47,646 | 320 / Rs -2,859 / Rs -9 / Rs -158 | 345 / Rs -44,787 / Rs -130 / Rs -177 |  |
| EXH X15 k3 b3 flip+close T15 | 696 | Rs -47,946 | 340 / Rs -8,601 / Rs -25 / Rs -119 | 356 / Rs -39,345 / Rs -110 / Rs -174 |  |
| EXH X30 k3 b3 flip+close T15 | 660 | Rs -53,799 | 316 / Rs -12,044 / Rs -38 / Rs -134 | 344 / Rs -41,755 / Rs -121 / Rs -163 |  |
| EXH X15 k3 b6 flip+close T15 | 739 | Rs -56,132 | 361 / Rs -7,558 / Rs -21 / Rs -135 | 378 / Rs -48,574 / Rs -128 / Rs -171 |  |
| EXH X15 k3 b3 flip T15 | 707 | Rs -57,429 | 347 / Rs -10,484 / Rs -30 / Rs -135 | 360 / Rs -46,945 / Rs -130 / Rs -167 |  |
| EXH X30 k3 b3 flip T15 | 674 | Rs -58,978 | 325 / Rs -12,487 / Rs -38 / Rs -137 | 349 / Rs -46,491 / Rs -133 / Rs -162 |  |
| EXH X30 k3 b6 flip+close T15 | 703 | Rs -60,074 | 339 / Rs -12,891 / Rs -38 / Rs -141 | 364 / Rs -47,183 / Rs -130 / Rs -168 |  |
| EXH X30 k3 b6 flip T15 | 734 | Rs -61,683 | 355 / Rs -11,889 / Rs -34 / Rs -135 | 379 / Rs -49,794 / Rs -131 / Rs -172 |  |
| EXH X15 k3 b6 flip T15 | 763 | Rs -65,200 | 372 / Rs -9,183 / Rs -25 / Rs -128 | 391 / Rs -56,017 / Rs -143 / Rs -176 |  |
| EXH X15 k3 b6 flip+close T30 | 729 | Rs -75,254 | 356 / Rs -27,090 / Rs -76 / Rs -176 | 373 / Rs -48,164 / Rs -129 / Rs -187 |  |
| EXH X30 k3 b6 flip+close T30 | 693 | Rs -83,214 | 333 / Rs -30,903 / Rs -93 / Rs -163 | 360 / Rs -52,311 / Rs -145 / Rs -180 |  |
| EXH X30 k3 b6 flip T30 | 723 | Rs -91,807 | 348 / Rs -32,253 / Rs -93 / Rs -161 | 375 / Rs -59,554 / Rs -159 / Rs -187 |  |
| EXH X15 k3 b6 flip T30 | 752 | Rs -93,123 | 367 / Rs -32,746 / Rs -89 / Rs -172 | 385 / Rs -60,377 / Rs -157 / Rs -186 |  |
| EXH X30 k2 b6 flip+close T15 | 878 | Rs -106,417 | 428 / Rs -55,770 / Rs -130 / Rs -136 | 450 / Rs -50,647 / Rs -112 / Rs -154 |  |
| EXH X30 k2 b3 flip+close T30 | 843 | Rs -108,409 | 410 / Rs -32,239 / Rs -79 / Rs -172 | 433 / Rs -76,170 / Rs -176 / Rs -166 |  |
| EXH X30 k2 b6 flip T15 | 886 | Rs -109,308 | 431 / Rs -64,858 / Rs -150 / Rs -141 | 455 / Rs -44,450 / Rs -98 / Rs -152 |  |
| EXH X30 k2 b3 flip+close T15 | 854 | Rs -109,640 | 415 / Rs -42,884 / Rs -103 / Rs -132 | 439 / Rs -66,756 / Rs -152 / Rs -161 |  |
| EXH X15 k2 b6 flip T15 | 896 | Rs -110,321 | 435 / Rs -65,436 / Rs -150 / Rs -147 | 461 / Rs -44,885 / Rs -97 / Rs -160 |  |
| EXH X30 k2 b3 flip T30 | 849 | Rs -113,372 | 412 / Rs -38,340 / Rs -93 / Rs -175 | 437 / Rs -75,032 / Rs -172 / Rs -162 |  |
| EXH X15 k2 b6 flip+close T15 | 892 | Rs -113,829 | 434 / Rs -64,835 / Rs -149 / Rs -147 | 458 / Rs -48,994 / Rs -107 / Rs -157 |  |
| EXH X30 k2 b3 flip T15 | 860 | Rs -118,041 | 418 / Rs -47,664 / Rs -114 / Rs -132 | 442 / Rs -70,377 / Rs -159 / Rs -165 |  |
| EXH X15 k2 b3 flip+close T15 | 876 | Rs -123,152 | 426 / Rs -52,344 / Rs -123 / Rs -146 | 450 / Rs -70,808 / Rs -157 / Rs -159 |  |
| EXH X15 k2 b3 flip T15 | 879 | Rs -129,351 | 427 / Rs -56,506 / Rs -132 / Rs -139 | 452 / Rs -72,845 / Rs -161 / Rs -168 |  |
| EXH X15 k2 b3 flip+close T30 | 868 | Rs -132,021 | 423 / Rs -53,716 / Rs -127 / Rs -171 | 445 / Rs -78,305 / Rs -176 / Rs -164 |  |
| EXH X15 k2 b3 flip T30 | 869 | Rs -134,624 | 423 / Rs -56,558 / Rs -134 / Rs -171 | 446 / Rs -78,066 / Rs -175 / Rs -172 |  |
| EXH X30 k2 b6 flip+close T30 | 869 | Rs -144,288 | 424 / Rs -53,287 / Rs -126 / Rs -169 | 445 / Rs -91,001 / Rs -204 / Rs -172 |  |
| EXH X30 k2 b6 flip T30 | 878 | Rs -148,104 | 427 / Rs -62,933 / Rs -147 / Rs -181 | 451 / Rs -85,171 / Rs -189 / Rs -158 |  |
| EXH X15 k2 b6 flip+close T30 | 884 | Rs -163,601 | 430 / Rs -78,172 / Rs -182 / Rs -184 | 454 / Rs -85,429 / Rs -188 / Rs -152 |  |
| EXH X15 k2 b6 flip T30 | 889 | Rs -169,434 | 432 / Rs -84,012 / Rs -194 / Rs -184 | 457 / Rs -85,422 / Rs -187 / Rs -145 |  |

### Final year (20 Oct 2025 - 19 Oct 2026, read once)

Read at 2026-11-16T03:48:18.228609+00:00 after `FINAL_READ_MIDCPNIFTY` (2026-11-16T03:47:40Z); 246 days.

**Absorption `ABS w3 k2 b6 none T15`** - verdict: **no evidence (nothing passed the selection)**

- Year: 445 trades on 177 days (1054 signals; unpriced none), win 38%, Rs -43,349 (2x slippage Rs -62,464), mean Rs -97 a trade (avg win Rs 704, avg loss Rs -598), worst day Rs -4,704, worst month Rs -10,880 (2026-09), max drawdown Rs 43,587.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -134 a trade (10-90%: Rs -202 to Rs -64); p 0.240, BH q 1.00. Mean > 0 (day bootstrap): p 0.994, q 1.00.
- Smallest edge detectable (80% power): Rs 96 a trade at 5%, Rs 191 at the BH level.
- By side: calls 214 (Rs -42,611), puts 231 (Rs -738). By exit: breakeven 18 (Rs -1,048), index_stop 181 (Rs -135,337), prem_stop 10 (Rs -5,297), time 236 (Rs 98,333).
- Gates: failed - nothing passed the selection; not positive in every period (or the year at 2x slippage); BH q twins 1.00 / zero 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in year.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 146 | 38% | Rs -14,842 | Rs -20,963 | Rs -102 | Rs -128 | Rs -7,389 | Rs 20,020 |
| final (1 Apr - 19 Oct 2026) | 299 | 38% | Rs -28,507 | Rs -41,501 | Rs -95 | Rs -135 | Rs -10,880 | Rs 30,861 |
| last 6 months (20 Apr - 19 Oct 2026) | 285 | 39% | Rs -24,896 | Rs -36,942 | Rs -87 | Rs -136 | Rs -10,880 | Rs 30,861 |
| year (20 Oct 2025 - 19 Oct 2026) | 445 | 38% | Rs -43,349 | Rs -62,464 | Rs -97 | Rs -132 | Rs -10,880 | Rs 43,587 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 27 trades, win 30%, Rs -10,886.

**Exhaustion `EXH X15 k3 b3 flip+close T30`** - verdict: **no evidence (nothing passed the selection)**

- Year: 834 trades on 243 days (1209 signals; unpriced none), win 30%, Rs -113,568 (2x slippage Rs -155,068), mean Rs -136 a trade (avg win Rs 1,401, avg loss Rs -779), worst day Rs -5,611, worst month Rs -29,435 (2025-12), max drawdown Rs 114,470.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -143 a trade (10-90%: Rs -204 to Rs -84); p 0.443, BH q 1.00. Mean > 0 (day bootstrap): p 0.998, q 1.00.
- Smallest edge detectable (80% power): Rs 114 a trade at 5%, Rs 226 at the BH level.
- By side: calls 430 (Rs -71,352), puts 404 (Rs -42,216). By exit: breakeven 40 (Rs -1,280), index_stop 493 (Rs -420,063), prem_stop 13 (Rs -10,323), time 288 (Rs 318,098).
- Gates: failed - nothing passed the selection; not positive in every period (or the year at 2x slippage); not above the twins in early, final and the year; BH q twins 1.00 / zero 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 364 | 29% | Rs -61,141 | Rs -79,309 | Rs -168 | Rs -145 | Rs -29,435 | Rs 74,485 |
| final (1 Apr - 19 Oct 2026) | 470 | 30% | Rs -52,427 | Rs -75,759 | Rs -112 | Rs -141 | Rs -18,124 | Rs 55,239 |
| last 6 months (20 Apr - 19 Oct 2026) | 438 | 30% | Rs -49,916 | Rs -70,810 | Rs -114 | Rs -142 | Rs -18,124 | Rs 51,346 |
| year (20 Oct 2025 - 19 Oct 2026) | 834 | 30% | Rs -113,568 | Rs -155,068 | Rs -136 | Rs -142 | Rs -29,435 | Rs 114,470 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 56 trades, win 25%, Rs -11,126.

## SENSEX

### Selection (20 Nov 2024 - 19 Oct 2025)

**Absorption**: 32 versions, 0 with net > 0, **0 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `ABS w3 k3 b6 turn T30` (FALLBACK - nothing passed the selection): 175 trades, win 14%, Rs -9,714 (H1 62 trades Rs 107, mean Rs 2 vs twins Rs -102; H2 113 trades Rs -9,821, mean Rs -87 vs twins Rs -114); descriptive twin p 0.198 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| ABS w5 k3 b3 turn T15 | 1 | Rs -138 | 1 / Rs -138 / Rs -138 / Rs -217 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b3 turn T30 | 1 | Rs -138 | 1 / Rs -138 / Rs -138 / Rs -228 | 0 / Rs 0 / - / - |  |
| ABS w5 k3 b3 none T15 | 5 | Rs -221 | 1 / Rs -3 / Rs -3 / Rs -302 | 4 / Rs -218 / Rs -55 / Rs -101 |  |
| ABS w5 k3 b3 none T30 | 5 | Rs -233 | 1 / Rs -3 / Rs -3 / Rs -213 | 4 / Rs -230 / Rs -58 / Rs -63 |  |
| ABS w3 k3 b3 none T30 | 15 | Rs -441 | 7 / Rs 1,079 / Rs 154 / Rs -96 | 8 / Rs -1,520 / Rs -190 / Rs -111 |  |
| ABS w3 k3 b3 none T15 | 16 | Rs -952 | 8 / Rs 377 / Rs 47 / Rs -120 | 8 / Rs -1,329 / Rs -166 / Rs -136 |  |
| ABS w3 k3 b3 turn T30 | 7 | Rs -1,775 | 3 / Rs -910 / Rs -303 / Rs -57 | 4 / Rs -865 / Rs -216 / Rs -43 |  |
| ABS w3 k3 b3 turn T15 | 7 | Rs -2,496 | 3 / Rs -1,853 / Rs -618 / Rs -170 | 4 / Rs -643 / Rs -161 / Rs -79 |  |
| ABS w5 k2 b3 turn T30 | 17 | Rs -2,851 | 9 / Rs -1,786 / Rs -198 / Rs -169 | 8 / Rs -1,065 / Rs -133 / Rs -52 |  |
| ABS w5 k2 b3 turn T15 | 17 | Rs -2,968 | 9 / Rs -2,760 / Rs -307 / Rs -159 | 8 / Rs -208 / Rs -26 / Rs -86 |  |
| ABS w5 k2 b3 none T15 | 42 | Rs -6,254 | 17 / Rs -3,386 / Rs -199 / Rs -146 | 25 / Rs -2,868 / Rs -115 / Rs -90 |  |
| ABS w5 k2 b3 none T30 | 42 | Rs -6,702 | 17 / Rs -3,034 / Rs -178 / Rs -129 | 25 / Rs -3,668 / Rs -147 / Rs -98 |  |
| ABS w5 k3 b6 turn T15 | 70 | Rs -7,362 | 19 / Rs -471 / Rs -25 / Rs -120 | 51 / Rs -6,891 / Rs -135 / Rs -88 |  |
| ABS w3 k2 b3 turn T15 | 57 | Rs -8,071 | 20 / Rs -2,904 / Rs -145 / Rs -79 | 37 / Rs -5,167 / Rs -140 / Rs -116 |  |
| ABS w5 k3 b6 turn T30 | 69 | Rs -8,807 | 18 / Rs -901 / Rs -50 / Rs -103 | 51 / Rs -7,906 / Rs -155 / Rs -145 |  |
| ABS w3 k2 b3 turn T30 | 57 | Rs -9,388 | 20 / Rs -2,329 / Rs -116 / Rs -86 | 37 / Rs -7,059 / Rs -191 / Rs -133 |  |
| ABS w3 k3 b6 turn T30 | 175 | Rs -9,714 | 62 / Rs 107 / Rs 2 / Rs -102 | 113 / Rs -9,821 / Rs -87 / Rs -114 |  |
| ABS w3 k2 b3 none T15 | 88 | Rs -10,075 | 28 / Rs -1,761 / Rs -63 / Rs -95 | 60 / Rs -8,314 / Rs -139 / Rs -112 |  |
| ABS w3 k2 b3 none T30 | 88 | Rs -10,727 | 28 / Rs -1,006 / Rs -36 / Rs -93 | 60 / Rs -9,721 / Rs -162 / Rs -134 |  |
| ABS w5 k3 b6 none T15 | 108 | Rs -11,324 | 33 / Rs -1,263 / Rs -38 / Rs -88 | 75 / Rs -10,061 / Rs -134 / Rs -110 |  |
| ABS w5 k3 b6 none T30 | 104 | Rs -11,334 | 32 / Rs -1,144 / Rs -36 / Rs -108 | 72 / Rs -10,190 / Rs -142 / Rs -107 |  |
| ABS w5 k2 b6 turn T15 | 193 | Rs -13,417 | 63 / Rs -5,495 / Rs -87 / Rs -92 | 130 / Rs -7,922 / Rs -61 / Rs -102 |  |
| ABS w5 k2 b6 turn T30 | 187 | Rs -13,534 | 63 / Rs -2,845 / Rs -45 / Rs -96 | 124 / Rs -10,689 / Rs -86 / Rs -112 |  |
| ABS w3 k3 b6 turn T15 | 183 | Rs -15,660 | 64 / Rs -2,095 / Rs -33 / Rs -88 | 119 / Rs -13,565 / Rs -114 / Rs -95 |  |
| ABS w3 k3 b6 none T30 | 248 | Rs -22,507 | 83 / Rs -3,272 / Rs -39 / Rs -114 | 165 / Rs -19,235 / Rs -117 / Rs -122 |  |
| ABS w3 k3 b6 none T15 | 253 | Rs -23,170 | 84 / Rs -5,502 / Rs -66 / Rs -88 | 169 / Rs -17,668 / Rs -104 / Rs -110 |  |
| ABS w5 k2 b6 none T15 | 271 | Rs -23,969 | 86 / Rs -8,183 / Rs -95 / Rs -73 | 185 / Rs -15,786 / Rs -85 / Rs -96 |  |
| ABS w5 k2 b6 none T30 | 265 | Rs -24,376 | 86 / Rs -5,022 / Rs -58 / Rs -90 | 179 / Rs -19,354 / Rs -108 / Rs -98 |  |
| ABS w3 k2 b6 turn T15 | 400 | Rs -38,496 | 136 / Rs -6,888 / Rs -51 / Rs -84 | 264 / Rs -31,608 / Rs -120 / Rs -88 |  |
| ABS w3 k2 b6 turn T30 | 389 | Rs -40,323 | 133 / Rs -8,652 / Rs -65 / Rs -88 | 256 / Rs -31,671 / Rs -124 / Rs -97 |  |
| ABS w3 k2 b6 none T15 | 508 | Rs -46,709 | 179 / Rs -12,565 / Rs -70 / Rs -97 | 329 / Rs -34,144 / Rs -104 / Rs -94 |  |
| ABS w3 k2 b6 none T30 | 497 | Rs -63,007 | 175 / Rs -16,912 / Rs -97 / Rs -106 | 322 / Rs -46,095 / Rs -143 / Rs -103 |  |

**Exhaustion**: 32 versions, 0 with net > 0, **0 eligible** (both halves >= 30 trades, net > 0 and above the twins). Chosen: `EXH X15 k3 b6 flip+close T30` (FALLBACK - nothing passed the selection): 836 trades, win 29%, Rs -65,877 (H1 401 trades Rs -41,457, mean Rs -103 vs twins Rs -83; H2 435 trades Rs -24,419, mean Rs -56 vs twins Rs -95); descriptive twin p 0.440 (biased by the choice).

| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |
|---|---|---|---|---|---|
| EXH X15 k3 b6 flip+close T30 | 836 | Rs -65,876 | 401 / Rs -41,457 / Rs -103 / Rs -83 | 435 / Rs -24,419 / Rs -56 / Rs -95 |  |
| EXH X30 k3 b6 flip+close T30 | 803 | Rs -66,951 | 387 / Rs -36,563 / Rs -94 / Rs -79 | 416 / Rs -30,388 / Rs -73 / Rs -97 |  |
| EXH X15 k3 b3 flip T30 | 848 | Rs -67,786 | 398 / Rs -28,814 / Rs -72 / Rs -77 | 450 / Rs -38,972 / Rs -87 / Rs -93 |  |
| EXH X15 k3 b6 flip T30 | 854 | Rs -67,940 | 409 / Rs -46,115 / Rs -113 / Rs -70 | 445 / Rs -21,825 / Rs -49 / Rs -98 |  |
| EXH X15 k3 b3 flip T15 | 852 | Rs -69,757 | 400 / Rs -36,422 / Rs -91 / Rs -78 | 452 / Rs -33,335 / Rs -74 / Rs -90 |  |
| EXH X15 k3 b3 flip+close T30 | 836 | Rs -71,052 | 395 / Rs -30,518 / Rs -77 / Rs -80 | 441 / Rs -40,534 / Rs -92 / Rs -102 |  |
| EXH X30 k3 b6 flip T30 | 823 | Rs -72,957 | 393 / Rs -44,581 / Rs -113 / Rs -78 | 430 / Rs -28,376 / Rs -66 / Rs -96 |  |
| EXH X30 k3 b3 flip T30 | 821 | Rs -75,459 | 383 / Rs -35,153 / Rs -92 / Rs -81 | 438 / Rs -40,306 / Rs -92 / Rs -100 |  |
| EXH X30 k3 b3 flip T15 | 831 | Rs -76,534 | 387 / Rs -35,429 / Rs -92 / Rs -79 | 444 / Rs -41,105 / Rs -93 / Rs -91 |  |
| EXH X30 k3 b3 flip+close T30 | 804 | Rs -76,795 | 379 / Rs -35,463 / Rs -94 / Rs -78 | 425 / Rs -41,332 / Rs -97 / Rs -96 |  |
| EXH X15 k3 b3 flip+close T15 | 843 | Rs -77,735 | 398 / Rs -40,997 / Rs -103 / Rs -84 | 445 / Rs -36,738 / Rs -83 / Rs -91 |  |
| EXH X30 k3 b3 flip+close T15 | 817 | Rs -83,145 | 384 / Rs -41,580 / Rs -108 / Rs -77 | 433 / Rs -41,565 / Rs -96 / Rs -93 |  |
| EXH X30 k3 b6 flip+close T15 | 822 | Rs -86,411 | 393 / Rs -42,174 / Rs -107 / Rs -80 | 429 / Rs -44,237 / Rs -103 / Rs -93 |  |
| EXH X15 k3 b6 flip+close T15 | 846 | Rs -86,867 | 405 / Rs -48,437 / Rs -120 / Rs -77 | 441 / Rs -38,430 / Rs -87 / Rs -94 |  |
| EXH X15 k3 b6 flip T15 | 862 | Rs -88,215 | 412 / Rs -51,002 / Rs -124 / Rs -73 | 450 / Rs -37,213 / Rs -83 / Rs -90 |  |
| EXH X30 k3 b6 flip T15 | 841 | Rs -89,163 | 399 / Rs -46,085 / Rs -116 / Rs -78 | 442 / Rs -43,078 / Rs -98 / Rs -94 |  |
| EXH X15 k2 b3 flip T15 | 904 | Rs -92,515 | 434 / Rs -46,512 / Rs -107 / Rs -77 | 470 / Rs -46,003 / Rs -98 / Rs -85 |  |
| EXH X30 k2 b6 flip+close T30 | 892 | Rs -92,638 | 425 / Rs -27,562 / Rs -65 / Rs -77 | 467 / Rs -65,076 / Rs -139 / Rs -93 |  |
| EXH X30 k2 b3 flip T30 | 896 | Rs -94,242 | 428 / Rs -28,855 / Rs -67 / Rs -78 | 468 / Rs -65,387 / Rs -140 / Rs -87 |  |
| EXH X15 k2 b3 flip+close T15 | 904 | Rs -94,376 | 434 / Rs -45,750 / Rs -105 / Rs -82 | 470 / Rs -48,626 / Rs -104 / Rs -82 |  |
| EXH X30 k2 b3 flip T15 | 900 | Rs -95,311 | 431 / Rs -42,752 / Rs -99 / Rs -75 | 469 / Rs -52,559 / Rs -112 / Rs -87 |  |
| EXH X15 k2 b3 flip T30 | 901 | Rs -95,646 | 432 / Rs -33,322 / Rs -77 / Rs -70 | 469 / Rs -62,324 / Rs -133 / Rs -90 |  |
| EXH X30 k2 b3 flip+close T30 | 893 | Rs -95,784 | 425 / Rs -29,946 / Rs -70 / Rs -74 | 468 / Rs -65,838 / Rs -141 / Rs -96 |  |
| EXH X30 k2 b3 flip+close T15 | 897 | Rs -98,775 | 428 / Rs -43,101 / Rs -101 / Rs -73 | 469 / Rs -55,674 / Rs -119 / Rs -88 |  |
| EXH X15 k2 b6 flip+close T30 | 899 | Rs -101,566 | 430 / Rs -31,709 / Rs -74 / Rs -68 | 469 / Rs -69,857 / Rs -149 / Rs -89 |  |
| EXH X30 k2 b6 flip+close T15 | 897 | Rs -101,797 | 428 / Rs -40,289 / Rs -94 / Rs -75 | 469 / Rs -61,508 / Rs -131 / Rs -83 |  |
| EXH X30 k2 b6 flip T30 | 895 | Rs -102,844 | 428 / Rs -38,664 / Rs -90 / Rs -72 | 467 / Rs -64,180 / Rs -137 / Rs -93 |  |
| EXH X15 k2 b6 flip T30 | 901 | Rs -104,140 | 432 / Rs -43,470 / Rs -101 / Rs -71 | 469 / Rs -60,670 / Rs -129 / Rs -93 |  |
| EXH X15 k2 b3 flip+close T30 | 901 | Rs -104,259 | 432 / Rs -36,142 / Rs -84 / Rs -66 | 469 / Rs -68,117 / Rs -145 / Rs -95 |  |
| EXH X15 k2 b6 flip+close T15 | 904 | Rs -106,534 | 433 / Rs -49,219 / Rs -114 / Rs -83 | 471 / Rs -57,315 / Rs -122 / Rs -82 |  |
| EXH X30 k2 b6 flip T15 | 900 | Rs -107,277 | 431 / Rs -46,543 / Rs -108 / Rs -82 | 469 / Rs -60,734 / Rs -130 / Rs -86 |  |
| EXH X15 k2 b6 flip T15 | 905 | Rs -109,919 | 435 / Rs -56,263 / Rs -129 / Rs -81 | 470 / Rs -53,656 / Rs -114 / Rs -85 |  |

### Final year (20 Oct 2025 - 19 Oct 2026, read once)

Read at 2026-11-16T03:58:12.522859+00:00 after `FINAL_READ_SENSEX` (2026-11-16T03:57:40Z); 246 days.

**Absorption `ABS w3 k3 b6 turn T30`** - verdict: **no evidence (nothing passed the selection)**

- Year: 165 trades on 85 days (200 signals; unpriced none), win 27%, Rs -8,838 (2x slippage Rs -10,258), mean Rs -54 a trade (avg win Rs 760, avg loss Rs -359), worst day Rs -2,452, worst month Rs -11,142 (2026-08), max drawdown Rs 22,919.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -68 a trade (10-90%: Rs -164 to Rs 37); p 0.413, BH q 1.00. Mean > 0 (day bootstrap): p 0.773, q 1.00.
- Smallest edge detectable (80% power): Rs 163 a trade at 5%, Rs 324 at the BH level.
- By side: calls 86 (Rs -7,120), puts 79 (Rs -1,718). By exit: breakeven 36 (Rs -446), index_stop 33 (Rs -24,484), prem_stop 33 (Rs -12,711), time 63 (Rs 28,803).
- Gates: failed - nothing passed the selection; not positive in every period (or the year at 2x slippage); not above the twins in early, final and the year; BH q twins 1.00 / zero 1.00 > 0.10.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 67 | 30% | Rs 3,757 | Rs 3,270 | Rs 56 | Rs -66 | Rs -640 | Rs 4,989 |
| final (1 Apr - 19 Oct 2026) | 98 | 26% | Rs -12,595 | Rs -13,528 | Rs -128 | Rs -64 | Rs -11,142 | Rs 18,146 |
| last 6 months (20 Apr - 19 Oct 2026) | 97 | 26% | Rs -12,453 | Rs -13,380 | Rs -128 | Rs -64 | Rs -11,142 | Rs 18,004 |
| year (20 Oct 2025 - 19 Oct 2026) | 165 | 27% | Rs -8,838 | Rs -10,258 | Rs -54 | Rs -65 | Rs -11,142 | Rs 22,919 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 0 trades, win -, Rs 0.

**Exhaustion `EXH X15 k3 b6 flip+close T30`** - verdict: **no evidence (nothing passed the selection)**

- Year: 891 trades on 246 days (1700 signals; unpriced none), win 31%, Rs -2,711 (2x slippage Rs -13,198), mean Rs -3 a trade (avg win Rs 1,013, avg loss Rs -469), worst day Rs -4,325, worst month Rs -11,277 (2026-01), max drawdown Rs 32,213.
- Random twins (1,000 sets, same minute / side / stop distance on other days): mean Rs -87 a trade (10-90%: Rs -128 to Rs -45); p 0.007, BH q 1.00. Mean > 0 (day bootstrap): p 0.537, q 1.00.
- Smallest edge detectable (80% power): Rs 78 a trade at 5%, Rs 155 at the BH level.
- By side: calls 500 (Rs -17,461), puts 391 (Rs 14,750). By exit: breakeven 183 (Rs -2,404), index_stop 236 (Rs -196,576), prem_stop 99 (Rs -58,568), time 373 (Rs 254,837).
- Gates: failed - nothing passed the selection; not positive in every period (or the year at 2x slippage); BH q twins 1.00 / zero 1.00 > 0.10.

| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |
|---|---|---|---|---|---|---|---|---|
| early (20 Oct 2025 - 31 Mar 2026) | 379 | 34% | Rs 13,346 | Rs 8,825 | Rs 35 | Rs -88 | Rs -11,277 | Rs 22,737 |
| final (1 Apr - 19 Oct 2026) | 512 | 29% | Rs -16,057 | Rs -22,023 | Rs -31 | Rs -90 | Rs -9,696 | Rs 32,213 |
| last 6 months (20 Apr - 19 Oct 2026) | 470 | 30% | Rs -16,467 | Rs -21,767 | Rs -35 | Rs -88 | Rs -9,696 | Rs 32,213 |
| year (20 Oct 2025 - 19 Oct 2026) | 891 | 31% | Rs -2,711 | Rs -13,198 | Rs -3 | Rs -90 | Rs -11,277 | Rs 32,213 |

- Recent weeks (20 Oct - 13 Nov 2026, 17 days, descriptive): 65 trades, win 25%, Rs -4,328.

## What this does not show

- True absorption (aggressive volume that fails to move price) needs real buy / sell volume; Dhan has neither futures minute volume for past years nor any second-level history. The phone's own recorder (R182 / R184) is the only source of tick-rule flow and covers weeks, not a year.
- One lot, one index at a time; no portfolio of indices.

- Indices not read yet: none (same frozen rules; their tests count in m = 4,852 either way).
- Nothing in the app changes. Full write-up: `results/report.md`.
