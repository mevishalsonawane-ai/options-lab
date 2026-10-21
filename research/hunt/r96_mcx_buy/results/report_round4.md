# R96 round 4: design results (2025-08-05 .. 2026-07-14)

Ideas in the cumulative ledger: 36. 1 lot, net after real MCX charges and spreads.

| rule | trades | win | avg win / loss | gross | costs | net | net at 2x spread | folds + (F1..F4 net) | worst month (below -13k) | max DD | w/o top 3 | twin mean | twin p | BH q | model / stale exits | median stop risk -> capital at 2% | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R4A NR7 crude, morning break still holding -> entry at the US open (R3A x R3G) | 38 | 76% | Rs 2,251 / -Rs 3,226 | Rs 44,070 | Rs 7,824 | **Rs 36,246** | Rs 31,337 | 4 of 4: Rs 13,007 (11), Rs 1,701 (9), Rs 4,522 (8), Rs 17,016 (10) | -Rs 8,767 (0 of 11) | -Rs 14,327 | Rs 8,729 | Rs 9,825 | 0.198 | 0.508 | 5% / 0% (real-exit net Rs 18,165) | Rs 4,843 -> Rs 2.42 lakh | lead |
| R4B NR7 crude, only breaks agreeing with the 12-month trend (TSMOM filter) | 15 | 80% | Rs 1,631 / -Rs 2,681 | Rs 17,061 | Rs 5,528 | **Rs 11,534** | Rs 7,341 | 3 of 4: -Rs 6,692 (2), Rs 638 (3), Rs 11,008 (6), Rs 6,581 (4) | -Rs 6,692 (0 of 7) | -Rs 6,692 | -Rs 35 | -Rs 6,137 | 0.178 | 0.508 | 0% / 0% (real-exit net Rs 11,534) | Rs 6,222 -> Rs 3.11 lakh | lead |
| R4C EIA spike fade, 1-ITM against the first 5 minutes (crude) | 2 | 0% | Rs 0 / -Rs 133 | Rs 18 | Rs 285 | **-Rs 267** | -Rs 419 | 0 of 4: Rs 0 (0), -Rs 267 (2), Rs 0 (0), Rs 0 (0) | -Rs 267 (0 of 1) | -Rs 267 | Rs 0 | -Rs 386 | 0.090 | 0.404 | 0% / 0% (real-exit net -Rs 267) | Rs 5,202 -> Rs 2.60 lakh | lead |
| R4D MCX 17:00-17:30 IST momentum, 1-ITM, same session (GOLDM, SILVERM) | 214 | 24% | Rs 1,861 / -Rs 1,279 | -Rs 44,042 | Rs 66,367 | **-Rs 1.10 lakh** | -Rs 1.55 lakh | 1 of 4: -Rs 9,172 (47), Rs 3,383 (37), -Rs 68,097 (46), -Rs 36,523 (84) | -Rs 36,953 (3 of 12) | -Rs 1.10 lakh | -Rs 1.38 lakh | -Rs 1.01 lakh | 0.683 | 0.847 | 1% / 5% (real-exit net -Rs 1.11 lakh) | Rs 12,385 -> Rs 6.19 lakh | dies |
| R4E NR7 crude, 2-ITM strike | 47 | 55% | Rs 627 / -Rs 819 | Rs 12,968 | Rs 13,869 | **-Rs 901** | -Rs 11,108 | 2 of 4: -Rs 2,059 (16), Rs 148 (10), Rs 1,168 (9), -Rs 158 (12) | -Rs 3,726 (0 of 12) | -Rs 7,538 | -Rs 7,822 | -Rs 12,479 | 0.293 | 0.528 | 0% / 0% (real-exit net -Rs 901) | Rs 4,398 -> Rs 2.20 lakh | dies |
| R4F NR7 crude, only breaks agreeing with the 1-month trend | 21 | 67% | Rs 1,637 / -Rs 2,932 | Rs 8,263 | Rs 5,862 | **Rs 2,400** | -Rs 1,807 | 2 of 4: -Rs 6,692 (2), Rs 1,594 (7), Rs 9,841 (6), -Rs 2,343 (6) | -Rs 8,449 (0 of 8) | -Rs 10,084 | -Rs 9,708 | -Rs 9,854 | 0.273 | 0.518 | 0% / 0% (real-exit net Rs 2,400) | Rs 6,222 -> Rs 3.11 lakh | lead |
| R4G NR7 crude, only when the NR7 day's ATM IV is below its 20-session median | 28 | 86% | Rs 2,290 / -Rs 1,393 | Rs 57,902 | Rs 8,520 | **Rs 49,381** | Rs 43,142 | 3 of 4: Rs 26,036 (6), -Rs 3,584 (6), Rs 8,574 (4), Rs 18,356 (12) | -Rs 4,030 (0 of 10) | -Rs 5,573 | Rs 24,733 | -Rs 5,700 | 0.002 | 0.036 | 7% / 0% (real-exit net Rs 31,253) | Rs 4,877 -> Rs 2.44 lakh | lead |

## Gates per rule

- R4A: failed twin_p, bh_q, bh_family
- R4B: failed trades, twin_p, bh_q, wo_top3, bh_family
- R4C: failed trades, net, net2, twin_p, bh_q, folds, wo_top3, real_exits
- R4D: failed net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits
- R4E: failed net, net2, twin_p, bh_q, folds, wo_top3, real_exits, bh_family
- R4F: failed trades, net2, twin_p, bh_q, folds, wo_top3, bh_family
- R4G: failed trades, bh_family

## By book (design)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM | CRUDEOIL | NATURALGAS |
|---|---|---|---|---|---|---|
| R4A | 19: gross Rs 4,542, net Rs 3,077, win 79% | - | - | - | 19: gross Rs 39,528, net Rs 33,169, win 74% | - |
| R4B | 7: gross Rs 1,682, net Rs 928, win 86% | - | - | - | 8: gross Rs 15,380, net Rs 10,605, win 75% | - |
| R4C | 1: gross Rs 8, net -Rs 57, win 0% | - | - | - | 1: gross Rs 10, net -Rs 210, win 0% | - |
| R4D | - | - | 118: gross -Rs 28,324, net -Rs 60,428, win 23% | 96: gross -Rs 15,718, net -Rs 49,981, win 26% | - | - |
| R4E | 24: gross Rs 1,458, net -Rs 869, win 54% | - | - | - | 23: gross Rs 11,510, net -Rs 32, win 57% | - |
| R4F | 10: gross Rs 1,023, net Rs 122, win 70% | - | - | - | 11: gross Rs 7,240, net Rs 2,278, win 64% | - |
| R4G | 14: gross Rs 5,323, net Rs 4,005, win 86% | - | - | - | 14: gross Rs 52,578, net Rs 45,377, win 86% | - |

## Long-history check of the directional daily signals (futures, 2012 - Jul 2025, 0.10% cost)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM | CRUDEOIL | NATURALGAS | pooled |
|---|---|---|---|---|---|---|---|
| R4A | 329: +0.58% (twin +0.41%, p 0.26) | - | - | - | 329: +0.58% (twin +0.41%, p 0.26) | - | 658: +0.58% |
| R4B | 158: +0.53% (twin +0.35%, p 0.31) | - | - | - | 158: +0.53% (twin +0.35%, p 0.31) | - | 316: +0.53% |
| R4E | 329: +0.58% (twin +0.41%, p 0.26) | - | - | - | 329: +0.58% (twin +0.41%, p 0.26) | - | 658: +0.58% |
| R4F | 187: +0.84% (twin +0.56%, p 0.18) | - | - | - | 187: +0.84% (twin +0.56%, p 0.18) | - | 374: +0.84% |
