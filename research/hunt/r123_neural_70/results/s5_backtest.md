# R123 S5 'morning put pick' (single network N1): full backtest

28 Oct 2026. 1 lot at today's lots, after dated charges and liquidity-aware slippage. Decisions at 09:20 / 09:25 / 09:30;
the network picks the cell and side with the best expected net (>= Rs 300). Trade lists: Drive `derived/r123/cleaned/trades_N1-S5_*.parquet`.
**Status: failed the gates (holdout 41 trades < 100). Not on paper (CLAUDE.md: ideas that failed the gates stay notes only).**

## Design walk-forward (out-of-sample folds, 1 Jan 2023 - 15 Mar 2026, 792 sessions)

- 555 trades on 244 days; win 53%; avg net Rs 172 (2x slippage Rs 156); total Rs 95,711
- avg win Rs 1,799, avg loss Rs -1,660; exits: target 257, stop 176, time 122
- puts 494 (Rs 97,771), calls 61 (Rs -2,060)

| period | trades | win | avg net | total |
|---|---|---|---|---|
| 2023 | 33 | 42% | 15 | 487 |
| 2024 | 476 | 54% | 211 | 100,652 |
| 2025 | 29 | 45% | -194 | -5,617 |
| 2026 | 17 | 47% | 11 | 188 |

| index | trades | win | total |
|---|---|---|---|
| BANKNIFTY | 102 | 57% | 24,912 |
| FINNIFTY | 117 | 57% | 49,465 |
| MIDCPNIFTY | 77 | 53% | 15,248 |
| NIFTY | 137 | 52% | 20,889 |
| SENSEX | 122 | 47% | -14,804 |

## Holdout (1 Apr - 19 Oct 2026, 137 sessions, read once)

- 41 trades on 27 days; win 61%; avg net Rs 579 (2x slippage Rs 563); total Rs 23,735
- avg win Rs 1,666, avg loss Rs -1,119; exits: target 18, stop 4, time 19
- puts 40 (Rs 24,320), calls 1 (Rs -585)

| period | trades | win | avg net | total |
|---|---|---|---|---|
| 2026-04 | 2 | 0% | -2,077 | -4,154 |
| 2026-05 | 8 | 75% | 1,147 | 9,178 |
| 2026-06 | 5 | 60% | 913 | 4,566 |
| 2026-07 | 4 | 75% | 715 | 2,859 |
| 2026-08 | 6 | 50% | -19 | -113 |
| 2026-09 | 11 | 64% | 750 | 8,253 |
| 2026-10 | 5 | 60% | 629 | 3,145 |

| index | trades | win | total |
|---|---|---|---|
| BANKNIFTY | 2 | 100% | 4,003 |
| FINNIFTY | 9 | 44% | -770 |
| MIDCPNIFTY | 4 | 50% | -190 |
| NIFTY | 18 | 56% | 9,719 |
| SENSEX | 8 | 88% | 10,973 |

Every holdout trade:

| day | index | entry | side | target / stop / time | exit | net Rs |
|---|---|---|---|---|---|---|
| 2026-04-15 | FINNIFTY | 09:25 | put | +2000 / -2000 / 60m | time | -1,074 |
| 2026-04-20 | FINNIFTY | 09:20 | put | +2000 / -2000 / 30m | stop | -3,080 |
| 2026-05-04 | FINNIFTY | 09:20 | put | +1000 / -2000 / 60m | stop | -2,032 |
| 2026-05-06 | SENSEX | 09:25 | put | +2000 / -2000 / 60m | target | 2,001 |
| 2026-05-06 | NIFTY | 09:25 | put | +2000 / -2000 / 60m | target | 2,003 |
| 2026-05-19 | NIFTY | 09:25 | put | +2000 / -2000 / 60m | target | 2,003 |
| 2026-05-21 | SENSEX | 09:20 | put | +2000 / -2000 / 60m | target | 2,001 |
| 2026-05-21 | FINNIFTY | 09:25 | put | +2000 / -2000 / 60m | time | -798 |
| 2026-05-29 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | target | 2,001 |
| 2026-05-29 | SENSEX | 09:20 | put | +2000 / -2000 / 60m | target | 2,001 |
| 2026-06-03 | SENSEX | 09:20 | put | +2000 / -2000 / 60m | target | 2,000 |
| 2026-06-03 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | target | 2,001 |
| 2026-06-03 | MIDCPNIFTY | 09:20 | put | +2000 / -2000 / 30m | target | 2,003 |
| 2026-06-16 | NIFTY | 09:25 | put | +2000 / -2000 / 60m | time | -1,091 |
| 2026-06-25 | MIDCPNIFTY | 09:25 | put | +2000 / -2000 / 60m | time | -347 |
| 2026-07-07 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | time | -1,256 |
| 2026-07-21 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | time | 1,318 |
| 2026-07-27 | FINNIFTY | 09:20 | put | +2000 / -2000 / 60m | time | 794 |
| 2026-07-28 | FINNIFTY | 09:20 | put | +2000 / -2000 / 60m | target | 2,002 |
| 2026-08-04 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | time | -998 |
| 2026-08-20 | MIDCPNIFTY | 09:25 | put | +2000 / -2000 / 60m | stop | -1,998 |
| 2026-08-25 | FINNIFTY | 09:20 | call | +2000 / -2000 / 60m | time | -585 |
| 2026-08-25 | MIDCPNIFTY | 09:20 | put | +2000 / -2000 / 60m | time | 152 |
| 2026-08-27 | SENSEX | 09:20 | put | +2000 / -2000 / 60m | time | 1,314 |
| 2026-08-27 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | target | 2,000 |
| 2026-09-03 | SENSEX | 09:20 | put | +2000 / -2000 / 60m | time | -670 |
| 2026-09-03 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | time | -696 |
| 2026-09-08 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | time | -651 |
| 2026-09-10 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | time | 243 |
| 2026-09-24 | BANKNIFTY | 09:20 | put | +2000 / -2000 / 60m | target | 2,001 |
| 2026-09-24 | SENSEX | 09:20 | put | +2000 / -2000 / 60m | time | 325 |
| 2026-09-25 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | time | -305 |
| 2026-09-25 | FINNIFTY | 09:25 | put | +2000 / -2000 / 60m | target | 2,002 |
| 2026-09-28 | FINNIFTY | 09:20 | put | +2000 / -2000 / 60m | target | 2,000 |
| 2026-09-28 | BANKNIFTY | 09:20 | put | +2000 / -2000 / 60m | target | 2,001 |
| 2026-09-28 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | target | 2,001 |
| 2026-10-01 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | time | -329 |
| 2026-10-05 | NIFTY | 09:25 | put | +2000 / -2000 / 60m | time | 1,468 |
| 2026-10-12 | NIFTY | 09:20 | put | +2000 / -2000 / 60m | target | 2,003 |
| 2026-10-12 | SENSEX | 09:20 | put | +2000 / -2000 / 60m | target | 2,001 |
| 2026-10-12 | NIFTY | 09:25 | put | +2000 / -2000 / 60m | stop | -1,997 |
