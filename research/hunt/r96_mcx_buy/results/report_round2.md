# R96 round 2: design results (2025-08-05 .. 2026-07-14)

Ideas in the cumulative ledger: 17. 1 lot, net after real MCX charges and spreads.

| rule | trades | win | avg win / loss | gross | costs | net | net at 2x spread | folds + (F1..F4 net) | worst month (below -13k) | max DD | w/o top 3 | twin mean | twin p | BH q | model / stale exits | median stop risk -> capital at 2% | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R2A NR7 breakout, 1-ITM, edge take-profit (real fills) | 65 | 62% | Rs 1,167 / -Rs 2,029 | Rs 12,157 | Rs 16,225 | **-Rs 4,067** | -Rs 15,651 | 2 of 4: -Rs 1,849 (13), Rs 1,126 (13), -Rs 7,784 (20), Rs 4,440 (19) | -Rs 15,380 (1 of 12) | -Rs 29,245 | -Rs 25,099 | -Rs 26,029 | 0.271 | 0.577 | 3% / 0% (real-exit net -Rs 18,189) | Rs 2,096 -> Rs 1.05 lakh | dies |
| R2B NR7 breakout, 2-ITM, edge take-profit | 67 | 60% | Rs 367 / -Rs 824 | Rs 6,173 | Rs 13,735 | **-Rs 7,562** | -Rs 16,898 | 2 of 4: Rs 363 (17), Rs 273 (14), -Rs 4,366 (18), -Rs 3,831 (18) | -Rs 5,349 (0 of 12) | -Rs 12,891 | -Rs 12,296 | -Rs 13,458 | 0.365 | 0.690 | 0% / 0% (real-exit net -Rs 7,562) | Rs 2,252 -> Rs 1.13 lakh | dies |
| R2C Crabel inside-day NR7 breakout, 1-ITM | 13 | 54% | Rs 716 / -Rs 3,191 | -Rs 10,068 | Rs 4,062 | **-Rs 14,129** | -Rs 17,180 | 1 of 4: -Rs 2,397 (2), -Rs 392 (3), -Rs 14,605 (7), Rs 3,265 (1) | -Rs 13,913 (1 of 7) | -Rs 18,217 | -Rs 18,399 | -Rs 1,395 | 0.752 | 1.000 | 0% / 0% (real-exit net -Rs 14,129) | Rs 4,232 -> Rs 2.12 lakh | dies |
| R2D NR7 breakout on CRUDEOIL / NATURALGAS options | 43 | 65% | Rs 3,803 / -Rs 5,048 | Rs 50,618 | Rs 19,851 | **Rs 30,767** | Rs 15,000 | 3 of 4: Rs 5,863 (10), Rs 21,453 (9), -Rs 14,854 (14), Rs 18,305 (10) | -Rs 19,847 (2 of 12) | -Rs 22,322 | Rs 1,249 | -Rs 19,472 | 0.076 | 0.322 | 5% / 0% (real-exit net Rs 31,212) | Rs 9,055 -> Rs 4.53 lakh | lead |
| R2E NR7 breakout with a Rs 6,500 stop-risk cap | 48 | 65% | Rs 479 / -Rs 1,379 | -Rs 3,155 | Rs 5,444 | **-Rs 8,599** | -Rs 11,426 | 1 of 4: -Rs 1,849 (13), Rs 3,948 (9), -Rs 6,022 (15), -Rs 4,675 (11) | -Rs 5,815 (0 of 12) | -Rs 12,475 | -Rs 13,039 | -Rs 4,318 | 0.758 | 1.000 | 2% / 0% (real-exit net -Rs 10,195) | Rs 1,655 -> Rs 82,735 | dies |
| R2F relaxed squeeze release, ATM straddle, 6 books | 15 | 7% | Rs 45,807 / -Rs 1,966 | Rs 24,550 | Rs 6,271 | **Rs 18,279** | Rs 14,033 | 1 of 4: -Rs 6,576 (4), -Rs 3,148 (2), Rs 33,242 (6), -Rs 5,238 (3) | -Rs 4,227 (0 of 7) | -Rs 16,113 | -Rs 27,233 | -Rs 16,003 | 0.002 | 0.034 | 0% / 0% (real-exit net Rs 18,279) | Rs 8,190 -> Rs 4.09 lakh | lead |
| R2G NR7 day -> ATM straddle | 69 | 16% | Rs 242 / -Rs 1,067 | -Rs 25,470 | Rs 33,785 | **-Rs 59,254** | -Rs 83,408 | 0 of 4: -Rs 5,428 (13), -Rs 13,075 (17), -Rs 22,010 (22), -Rs 18,741 (17) | -Rs 12,845 (0 of 12) | -Rs 59,254 | -Rs 60,652 | -Rs 43,264 | 0.876 | 1.000 | 0% / 3% (real-exit net -Rs 59,254) | Rs 2,885 -> Rs 1.44 lakh | dies |
| R2H quiet-base (NR7 within 5 days) 20-day breakout, 1-ITM | 56 | 46% | Rs 751 / -Rs 4,265 | -Rs 94,314 | Rs 14,135 | **-Rs 1.08 lakh** | -Rs 1.19 lakh | 0 of 4: -Rs 23,652 (14), -Rs 53,443 (21), -Rs 3,525 (12), -Rs 27,829 (9) | -Rs 33,894 (3 of 11) | -Rs 1.08 lakh | -Rs 1.16 lakh | -Rs 7,916 | 1.000 | 1.000 | 11% / 0% (real-exit net -Rs 19,816) | Rs 3,031 -> Rs 1.52 lakh | dies |

## Gates per rule

- R2A: failed net, net2, twin_p, bh_q, folds, worst_month, wo_top3, real_exits
- R2B: failed net, net2, twin_p, bh_q, folds, wo_top3, real_exits
- R2C: failed trades, net, net2, twin_p, bh_q, folds, worst_month, wo_top3, real_exits
- R2D: failed twin_p, bh_q, worst_month
- R2E: failed net, net2, twin_p, bh_q, folds, wo_top3, real_exits
- R2F: failed trades, folds, wo_top3
- R2G: failed net, net2, twin_p, bh_q, folds, max_dd, wo_top3, real_exits
- R2H: failed net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits

## By book (design)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM | CRUDEOIL | NATURALGAS |
|---|---|---|---|---|---|---|
| R2A | 23: gross Rs 5,860, net Rs 3,705, win 78% | 21: gross -Rs 1,921, net -Rs 4,434, win 52% | 10: gross -Rs 3,003, net -Rs 8,007, win 60% | 11: gross Rs 11,220, net Rs 4,668, win 45% | - | - |
| R2B | 24: gross Rs 1,458, net -Rs 869, win 54% | 23: gross -Rs 725, net -Rs 3,709, win 70% | 12: gross Rs 497, net -Rs 3,638, win 58% | 8: gross Rs 4,942, net Rs 655, win 50% | - | - |
| R2C | 5: gross Rs 927, net Rs 387, win 80% | 2: gross -Rs 200, net -Rs 398, win 50% | 3: gross -Rs 9,810, net -Rs 11,470, win 33% | 3: gross -Rs 985, net -Rs 2,648, win 33% | - | - |
| R2D | - | - | - | - | 23: gross Rs 56,918, net Rs 45,170, win 74% | 20: gross -Rs 6,300, net -Rs 14,403, win 55% |
| R2E | 23: gross Rs 5,860, net Rs 3,705, win 78% | 21: gross -Rs 1,921, net -Rs 4,434, win 52% | 2: gross -Rs 5,737, net -Rs 6,147, win 50% | 2: gross -Rs 1,358, net -Rs 1,723, win 50% | - | - |
| R2F | 4: gross -Rs 2,303, net -Rs 2,863, win 0% | 3: gross -Rs 1,713, net -Rs 2,354, win 0% | 1: gross -Rs 40, net -Rs 1,104, win 0% | - | 4: gross Rs 39,230, net Rs 37,255, win 25% | 3: gross -Rs 10,625, net -Rs 12,654, win 0% |
| R2G | 24: gross -Rs 2,445, net -Rs 6,682, win 17% | 21: gross -Rs 5,575, net -Rs 10,391, win 29% | 13: gross -Rs 13,945, net -Rs 24,239, win 0% | 11: gross -Rs 3,505, net -Rs 17,943, win 9% | - | - |
| R2H | 17: gross Rs 741, net -Rs 931, win 59% | 18: gross -Rs 588, net -Rs 3,071, win 56% | 8: gross -Rs 36,091, net -Rs 39,286, win 12% | 13: gross -Rs 58,376, net -Rs 65,162, win 38% | - | - |

## Long-history check of the directional daily signals (futures, 2012 - Jul 2025, 0.10% cost)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM | CRUDEOIL | NATURALGAS | pooled |
|---|---|---|---|---|---|---|---|
| R2A | 329: +0.58% (twin +0.41%, p 0.26) | 358: +1.08% (twin +0.54%, p 0.02) | 378: +0.19% (twin +0.06%, p 0.04) | 371: +0.48% (twin +0.30%, p 0.08) | - | - | 1436: +0.58% |
| R2B | 329: +0.58% (twin +0.41%, p 0.26) | 358: +1.08% (twin +0.54%, p 0.02) | 378: +0.19% (twin +0.06%, p 0.04) | 371: +0.48% (twin +0.30%, p 0.08) | - | - | 1436: +0.58% |
| R2C | 88: +0.26% (twin +0.44%, p 0.63) | 118: +1.63% (twin +0.54%, p 0.01) | 143: +0.06% (twin +0.07%, p 0.54) | 166: +0.73% (twin +0.32%, p 0.03) | - | - | 515: +0.67% |
| R2D | - | - | - | - | 329: +0.58% (twin +0.41%, p 0.26) | 358: +1.08% (twin +0.54%, p 0.02) | 687: +0.84% |
| R2E | 329: +0.58% (twin +0.41%, p 0.26) | 358: +1.08% (twin +0.54%, p 0.02) | 378: +0.19% (twin +0.06%, p 0.04) | 371: +0.48% (twin +0.30%, p 0.08) | - | - | 1436: +0.58% |
| R2H | 435: +0.20% (twin +0.03%, p 0.31) | 436: +0.54% (twin +0.09%, p 0.07) | 420: +0.15% (twin -0.03%, p 0.01) | 442: -0.01% (twin +0.02%, p 0.61) | - | - | 1733: +0.22% |
