# R96 round 6: design results (2025-08-05 .. 2026-07-14)

Ideas in the cumulative ledger: 46. 1 lot, net after real MCX charges and spreads.

| rule | trades | win | avg win / loss | gross | costs | net | net at 2x spread | folds + (F1..F4 net) | worst month (below -13k) | max DD | w/o top 3 | twin mean | twin p | BH q | model / stale exits | median stop risk -> capital at 2% | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R6A NR7 breakout on the untouched COPPER and ZINC option books (R3A's rule) | 13 | 31% | Rs 3,708 / -Rs 11,090 | -Rs 63,383 | Rs 21,594 | **-Rs 84,978** | -Rs 1.04 lakh | 1 of 4: -Rs 6,410 (1), Rs 9,226 (5), -Rs 60,043 (4), -Rs 27,751 (3) | -Rs 48,265 (2 of 8) | -Rs 88,393 | -Rs 99,569 | -Rs 53,902 | 0.830 | 0.932 | 15% / 0% (real-exit net -Rs 36,803) | Rs 32,082 -> Rs 16.04 lakh | dies |
| R6B NR7 breakout on COPPER and ZINC, only on cheap-IV days (R4G's filter) | 7 | 43% | Rs 2,045 / -Rs 19,461 | -Rs 60,916 | Rs 10,791 | **-Rs 71,707** | -Rs 81,442 | 1 of 4: Rs 0 (0), Rs 2,996 (2), -Rs 48,265 (3), -Rs 26,438 (2) | -Rs 48,265 (2 of 5) | -Rs 74,703 | -Rs 77,842 | -Rs 37,296 | 0.900 | 0.941 | 14% / 0% (real-exit net -Rs 24,131) | Rs 31,458 -> Rs 15.73 lakh | dies |
| R6C first-half-hour momentum -> last-hour 1-ITM (COPPER, ZINC) | 48 | 40% | Rs 1,318 / -Rs 2,378 | Rs 642 | Rs 44,575 | **-Rs 43,932** | -Rs 80,948 | 1 of 4: -Rs 16,943 (11), -Rs 18,604 (16), -Rs 16,858 (11), Rs 8,473 (10) | -Rs 15,058 (1 of 11) | -Rs 52,405 | -Rs 57,879 | -Rs 48,015 | 0.425 | 0.738 | 2% / 69% (real-exit net -Rs 32,798) | Rs 25,992 -> Rs 13.00 lakh | dies |
| R6D 20-day Donchian breakout (first day) -> 1-ITM, 5 sessions (COPPER, ZINC) | 21 | 48% | Rs 10,944 / -Rs 16,801 | -Rs 34,398 | Rs 40,975 | **-Rs 75,373** | -Rs 1.13 lakh | 2 of 4: Rs 5,722 (7), -Rs 92,073 (8), -Rs 670 (2), Rs 11,648 (4) | -Rs 94,425 (2 of 10) | -Rs 1.19 lakh | -Rs 1.44 lakh | -Rs 83,915 | 0.433 | 0.738 | 14% / 10% (real-exit net -Rs 1,268) | Rs 29,803 -> Rs 14.90 lakh | dies |

## Gates per rule

- R6A: failed trades, net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits, bh_family, bh_q_all_mcx
- R6B: failed trades, net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits, bh_family, bh_q_all_mcx
- R6C: failed net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits, bh_q_all_mcx
- R6D: failed trades, net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits, bh_q_all_mcx

## By book (design)

| rule | COPPER | ZINC |
|---|---|---|
| R6A | 13: gross -Rs 63,383, net -Rs 84,978, win 31% | - |
| R6B | 7: gross -Rs 60,916, net -Rs 71,707, win 43% | - |
| R6C | 47: gross Rs 642, net -Rs 42,096, win 40% | 1: gross Rs 0, net -Rs 1,836, win 0% |
| R6D | 20: gross -Rs 33,398, net -Rs 71,724, win 50% | 1: gross -Rs 1,000, net -Rs 3,649, win 0% |

## Long-history check of the directional daily signals (futures, 2012 - Jul 2025, 0.10% cost)

| rule | COPPER | ZINC | pooled |
|---|---|---|---|
| R6A | 364: +0.17% (twin +0.14%, p 0.42) | 355: +0.28% (twin +0.14%, p 0.12) | 719: +0.22% |
| R6D | 391: +0.03% (twin -0.02%, p 0.33) | 392: +0.11% (twin +0.04%, p 0.28) | 783: +0.07% |
