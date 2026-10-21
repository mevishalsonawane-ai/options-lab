# R96 round 5: design results (2025-08-05 .. 2026-07-14)

Ideas in the cumulative ledger: 42. 1 lot, net after real MCX charges and spreads.

| rule | trades | win | avg win / loss | gross | costs | net | net at 2x spread | folds + (F1..F4 net) | worst month (below -13k) | max DD | w/o top 3 | twin mean | twin p | BH q | model / stale exits | median stop risk -> capital at 2% | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R5A natural-gas EIA storage day, 1-ITM call from 17:00 IST, same session | 70 | 43% | Rs 2,208 / -Rs 2,742 | -Rs 30,317 | Rs 13,139 | **-Rs 43,455** | -Rs 51,876 | 1 of 4: -Rs 6,954 (20), -Rs 25,365 (12), -Rs 15,360 (20), Rs 4,224 (18) | -Rs 11,929 (0 of 12) | -Rs 59,232 | -Rs 64,041 | -Rs 45,856 | 0.473 | 0.764 | 0% / 1% (real-exit net -Rs 43,455) | Rs 3,955 -> Rs 1.98 lakh | dies |
| R5B NR7 breakout on gas and metals, only on cheap-IV days (R4G's filter) | 36 | 44% | Rs 3,102 / -Rs 4,311 | -Rs 24,410 | Rs 12,174 | **-Rs 36,585** | -Rs 45,904 | 1 of 4: Rs 0 (0), Rs 6,982 (4), -Rs 35,294 (21), -Rs 8,272 (11) | -Rs 19,552 (2 of 7) | -Rs 66,111 | -Rs 59,595 | -Rs 19,297 | 0.677 | 0.869 | 6% / 0% (real-exit net -Rs 32,133) | Rs 7,922 -> Rs 3.96 lakh | dies |
| R5C NR7 crude -> ATM straddle, only on cheap-IV days | 26 | 19% | Rs 783 / -Rs 1,554 | -Rs 13,897 | Rs 14,831 | **-Rs 28,728** | -Rs 39,478 | 1 of 4: -Rs 2,674 (4), -Rs 12,236 (6), Rs 957 (4), -Rs 14,775 (12) | -Rs 7,175 (0 of 10) | -Rs 28,728 | -Rs 32,270 | -Rs 17,089 | 0.762 | 0.872 | 0% / 0% (real-exit net -Rs 28,728) | Rs 6,602 -> Rs 3.30 lakh | dies |
| R5D EIA spike fade at 0.10 ATR (crude) | 15 | 20% | Rs 753 / -Rs 219 | Rs 1,749 | Rs 2,117 | **-Rs 368** | -Rs 1,492 | 1 of 4: -Rs 278 (7), -Rs 555 (5), Rs 465 (3), Rs 0 (0) | -Rs 798 (0 of 6) | -Rs 1,434 | -Rs 2,628 | -Rs 3,821 | 0.162 | 0.553 | 0% / 7% (real-exit net -Rs 368) | Rs 2,651 -> Rs 1.33 lakh | dies |
| R5E pre-event ATM straddle 17:00 IST -> release - 5 min (EIA crude, EIA storage) | 164 | 14% | Rs 2,186 / -Rs 1,018 | -Rs 35,058 | Rs 58,266 | **-Rs 93,324** | -Rs 1.29 lakh | 0 of 4: -Rs 21,523 (42), -Rs 22,969 (36), -Rs 17,975 (48), -Rs 30,857 (38) | -Rs 14,873 (2 of 12) | -Rs 95,120 | -Rs 1.27 lakh | -Rs 1.12 lakh | 0.283 | 0.560 | 2% / 3% (real-exit net -Rs 1.19 lakh) | Rs 4,237 -> Rs 2.12 lakh | dies |
| R5F gold / silver ratio breakout -> 1-ITM SILVERM, 5 sessions | 27 | 41% | Rs 3,340 / -Rs 5,244 | -Rs 27,127 | Rs 20,035 | **-Rs 47,162** | -Rs 63,829 | 0 of 4: -Rs 11,605 (5), -Rs 29,489 (12), Rs 0 (0), -Rs 6,068 (10) | -Rs 27,367 (1 of 7) | -Rs 52,186 | -Rs 69,360 | -Rs 24,468 | 0.794 | 0.878 | 11% / 0% (real-exit net Rs 14,705) | Rs 18,453 -> Rs 9.23 lakh | dies |

## Gates per rule

- R5A: failed net, net2, twin_p, bh_q, folds, max_dd, wo_top3, real_exits, history
- R5B: failed net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits
- R5C: failed trades, net, net2, twin_p, bh_q, folds, wo_top3, real_exits, bh_family
- R5D: failed trades, net, net2, twin_p, bh_q, folds, wo_top3, real_exits, bh_family
- R5E: failed net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits
- R5F: failed trades, net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits

## By book (design)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM | CRUDEOIL | NATURALGAS |
|---|---|---|---|---|---|---|
| R5A | - | 35: gross -Rs 6,178, net -Rs 9,474, win 40% | - | - | - | 35: gross -Rs 24,139, net -Rs 33,981, win 46% |
| R5B | - | 12: gross -Rs 5,175, net -Rs 6,580, win 42% | 7: gross -Rs 7,328, net -Rs 10,532, win 43% | 6: gross Rs 7,850, net Rs 4,569, win 50% | - | 11: gross -Rs 19,758, net -Rs 24,042, win 45% |
| R5C | 13: gross -Rs 1,537, net -Rs 3,905, win 15% | - | - | - | 13: gross -Rs 12,360, net -Rs 24,823, win 23% | - |
| R5D | 8: gross Rs 188, net -Rs 343, win 12% | - | - | - | 7: gross Rs 1,560, net -Rs 25, win 29% | - |
| R5E | 45: gross -Rs 2,477, net -Rs 9,027, win 9% | 37: gross -Rs 4,975, net -Rs 11,471, win 14% | - | - | 45: gross -Rs 4,481, net -Rs 31,374, win 18% | 37: gross -Rs 23,125, net -Rs 41,452, win 16% |
| R5F | - | - | - | 27: gross -Rs 27,127, net -Rs 47,162, win 41% | - | - |

## Long-history check of the directional daily signals (futures, 2012 - Jul 2025, 0.10% cost)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM | CRUDEOIL | NATURALGAS | pooled |
|---|---|---|---|---|---|---|---|
| R5A | - | 614: -0.31% (twin -0.17%, p 0.88) | - | - | - | 614: -0.31% (twin -0.17%, p 0.88) | 1228: -0.31% |
| R5F | - | - | - | 807: +0.04% (twin -0.01%, p 0.32) | - | - | 807: +0.04% |
