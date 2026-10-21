# R96 round 1: design results (2025-08-05 .. 2026-07-14)

Ideas in the cumulative ledger: 9. 1 lot, net after real MCX charges and spreads.

| rule | trades | win | avg win / loss | gross | costs | net | net at 2x spread | folds + (F1..F4 net) | worst month (below -13k) | max DD | w/o top 3 | twin mean | twin p | BH q | model / stale exits | median stop risk -> capital at 2% | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 squeeze release, ATM straddle | 6 | 33% | Rs 10,357 / -Rs 475 | Rs 20,137 | Rs 1,323 | **Rs 18,814** | Rs 18,128 | 1 of 4: -Rs 207 (2), -Rs 499 (1), Rs 20,251 (2), -Rs 732 (1) | -Rs 732 (0 of 6) | -Rs 918 | -Rs 1,713 | Rs 1,960 | 0.068 | 0.305 | 33% / 0% | Rs 1,529 -> Rs 76,457 | lead |
| S2 squeeze release, 1-ITM direction | 7 | 29% | Rs 5,103 / -Rs 5,345 | -Rs 15,237 | Rs 1,284 | **-Rs 16,521** | -Rs 17,370 | 1 of 4: -Rs 929 (2), -Rs 836 (1), Rs 10,198 (2), -Rs 24,955 (2) | -Rs 24,695 (1 of 7) | -Rs 24,956 | -Rs 26,725 | -Rs 2,574 | 0.563 | 0.844 | 29% / 0% | Rs 1,442 -> Rs 72,090 | dies |
| N1 NR7 breakout, 1-ITM | 58 | 52% | Rs 9,725 / -Rs 3,848 | Rs 2.02 lakh | Rs 17,774 | **Rs 1.84 lakh** | Rs 1.71 lakh | 2 of 4: -Rs 1,778 (12), Rs 1.55 lakh (13), -Rs 30,408 (17), Rs 60,856 (16) | -Rs 20,791 (2 of 12) | -Rs 51,030 | Rs 33,112 | Rs 1,933 | 0.006 | 0.054 | 38% / 5% | Rs 2,055 -> Rs 1.03 lakh | lead |
| W1 wide-range continuation, 1-ITM | 25 | 12% | Rs 2,705 / -Rs 10,329 | -Rs 2.11 lakh | Rs 8,505 | **-Rs 2.19 lakh** | -Rs 2.26 lakh | 1 of 4: Rs 801 (4), -Rs 1.19 lakh (7), -Rs 12,057 (7), -Rs 88,694 (7) | -Rs 98,573 (4 of 11) | -Rs 2.24 lakh | -Rs 2.27 lakh | Rs 31,991 | 1.000 | 1.000 | 52% / 0% | Rs 3,257 -> Rs 1.63 lakh | dies |
| P1 pullback in trend, 1-ITM | 35 | 40% | Rs 12,577 / -Rs 3,251 | Rs 1.20 lakh | Rs 11,715 | **Rs 1.08 lakh** | Rs 98,728 | 3 of 4: Rs 3,196 (10), Rs 54,599 (7), -Rs 10,433 (4), Rs 60,449 (14) | -Rs 14,621 (1 of 12) | -Rs 26,534 | -Rs 21,182 | Rs 31,941 | 0.164 | 0.467 | 54% / 6% | Rs 2,831 -> Rs 1.42 lakh | lead |
| V1 IV below HAR forecast, ATM straddle | 15 | 40% | Rs 17,721 / -Rs 16,996 | -Rs 35,894 | Rs 10,746 | **-Rs 46,640** | -Rs 54,902 | 1 of 4: Rs 7,897 (3), -Rs 824 (1), -Rs 42,155 (8), -Rs 11,558 (3) | -Rs 1.38 lakh (1 of 7) | -Rs 1.38 lakh | -Rs 1.50 lakh | -Rs 80,719 | 0.242 | 0.467 | 87% / 13% | Rs 6,266 -> Rs 3.13 lakh | dies |
| E1 event straddle (EIA / storage / CPI / NFP) | 103 | 17% | Rs 843 / -Rs 881 | -Rs 33,194 | Rs 28,222 | **-Rs 61,416** | -Rs 76,734 | 1 of 4: -Rs 7,676 (25), Rs 2,076 (21), -Rs 43,628 (28), -Rs 12,188 (29) | -Rs 34,696 (1 of 12) | -Rs 61,535 | -Rs 72,806 | -Rs 40,662 | 0.768 | 0.988 | 10% / 5% | Rs 1,993 -> Rs 99,632 | dies |
| E2 event follow, 1-ITM | 103 | 19% | Rs 3,033 / -Rs 1,080 | -Rs 14,421 | Rs 14,539 | **-Rs 28,960** | -Rs 36,914 | 1 of 4: -Rs 4,993 (26), -Rs 11,468 (21), -Rs 20,912 (27), Rs 8,413 (29) | -Rs 18,705 (3 of 12) | -Rs 54,329 | -Rs 81,936 | -Rs 55,254 | 0.259 | 0.467 | 6% / 2% | Rs 1,791 -> Rs 89,575 | dies |
| U1 US-session momentum (big first half-hour), 1-ITM | 236 | 31% | Rs 1,911 / -Rs 1,886 | -Rs 1.24 lakh | Rs 40,108 | **-Rs 1.64 lakh** | -Rs 1.88 lakh | 1 of 4: -Rs 77,085 (49), Rs 35,663 (59), -Rs 21,575 (58), -Rs 1.01 lakh (70) | -Rs 72,247 (4 of 12) | -Rs 1.76 lakh | -Rs 2.09 lakh | -Rs 1.04 lakh | 0.882 | 0.993 | 12% / 8% | Rs 2,464 -> Rs 1.23 lakh | dies |

## Gates per rule

- S1: failed trades, twin_p, bh_q, folds, wo_top3
- S2: failed trades, net, net2, twin_p, bh_q, folds, worst_month, wo_top3
- N1: failed folds, worst_month, max_dd
- W1: failed trades, net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3
- P1: failed twin_p, bh_q, worst_month, wo_top3, history
- V1: failed trades, net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3
- E1: failed net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3
- E2: failed net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3
- U1: failed net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3

## By book (design)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM |
|---|---|---|---|---|
| S1 | 4: gross Rs 20,699, net Rs 19,732, win 50% | 2: gross -Rs 562, net -Rs 918, win 0% | - | - |
| S2 | 4: gross Rs 8,811, net Rs 8,435, win 50% | 2: gross -Rs 36, net -Rs 261, win 0% | - | 1: gross -Rs 24,011, net -Rs 24,695, win 0% |
| N1 | 21: gross Rs 16,779, net Rs 14,683, win 48% | 18: gross Rs 603, net -Rs 1,770, win 44% | 10: gross -Rs 58,317, net -Rs 63,230, win 40% | 9: gross Rs 2.43 lakh, net Rs 2.34 lakh, win 89% |
| W1 | 8: gross -Rs 10,216, net -Rs 11,072, win 12% | 8: gross -Rs 936, net -Rs 2,200, win 25% | 2: gross -Rs 30,100, net -Rs 30,879, win 0% | 7: gross -Rs 1.69 lakh, net -Rs 1.75 lakh, win 0% |
| P1 | 13: gross -Rs 12,669, net -Rs 13,860, win 15% | 9: gross Rs 2,876, net Rs 1,575, win 33% | 6: gross Rs 61,315, net Rs 56,662, win 67% | 7: gross Rs 68,005, net Rs 63,435, win 71% |
| V1 | 5: gross Rs 5,486, net Rs 4,417, win 80% | 3: gross -Rs 5,185, net -Rs 6,211, win 0% | 7: gross -Rs 36,194, net -Rs 44,846, win 29% | - |
| E1 | 44: gross -Rs 1,207, net -Rs 7,553, win 9% | 37: gross -Rs 513, net -Rs 6,956, win 22% | 10: gross -Rs 4,287, net -Rs 9,803, win 30% | 12: gross -Rs 27,187, net -Rs 37,104, win 17% |
| E2 | 44: gross -Rs 2,049, net -Rs 5,285, win 11% | 36: gross Rs 213, net -Rs 3,121, win 33% | 13: gross -Rs 7,609, net -Rs 11,448, win 15% | 10: gross -Rs 4,976, net -Rs 9,105, win 10% |
| U1 | 81: gross -Rs 6,519, net -Rs 12,867, win 30% | 76: gross -Rs 4,513, net -Rs 12,042, win 34% | 42: gross -Rs 60,403, net -Rs 71,862, win 29% | 37: gross -Rs 52,613, net -Rs 67,386, win 32% |

## Long-history check of the directional daily signals (futures, 2012 - Jul 2025, 0.10% cost)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM | pooled |
|---|---|---|---|---|---|
| S2 | 29: +0.05% (twin +0.07%, p 0.49) | 20: +1.07% (twin -0.32%, p 0.17) | 22: -0.67% (twin -0.07%, p 0.95) | 25: +0.33% (twin +0.05%, p 0.31) | 96: +0.17% |
| N1 | 329: +0.58% (twin +0.41%, p 0.26) | 358: +1.08% (twin +0.54%, p 0.02) | 378: +0.19% (twin +0.06%, p 0.04) | 371: +0.48% (twin +0.30%, p 0.08) | 1436: +0.58% |
| W1 | 110: +1.12% (twin -0.15%, p 0.01) | 118: -0.69% (twin -0.11%, p 0.86) | 170: -0.01% (twin -0.12%, p 0.21) | 185: +0.32% (twin -0.09%, p 0.03) | 583: +0.17% |
| P1 | 371: -0.20% (twin -0.13%, p 0.58) | 356: -0.04% (twin +0.12%, p 0.68) | 394: -0.29% (twin -0.05%, p 1.00) | 388: -0.40% (twin -0.02%, p 1.00) | 1509: -0.24% |
