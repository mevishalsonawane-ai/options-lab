# R96 round 3: design results (2025-08-05 .. 2026-07-14)

Ideas in the cumulative ledger: 27. 1 lot, net after real MCX charges and spreads.

| rule | trades | win | avg win / loss | gross | costs | net | net at 2x spread | folds + (F1..F4 net) | worst month (below -13k) | max DD | w/o top 3 | twin mean | twin p | BH q | model / stale exits | median stop risk -> capital at 2% | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R3A NR7 breakout on crude only (CRUDEOILM + CRUDEOIL) | 46 | 76% | Rs 2,010 / -Rs 1,954 | Rs 62,779 | Rs 13,904 | **Rs 48,875** | Rs 38,655 | 4 of 4: Rs 12,721 (14), Rs 4,353 (10), Rs 13,445 (10), Rs 18,356 (12) | -Rs 9,718 (0 of 12) | -Rs 11,854 | Rs 23,093 | -Rs 7,067 | 0.014 | 0.126 | 4% / 0% (real-exit net Rs 30,747) | Rs 4,657 -> Rs 2.33 lakh | lead |
| R3B NR4 breakout on crude | 77 | 60% | Rs 2,051 / -Rs 3,072 | Rs 23,361 | Rs 24,247 | **-Rs 886** | -Rs 18,941 | 2 of 4: Rs 6,780 (19), -Rs 9,338 (16), Rs 3,127 (18), -Rs 1,455 (24) | -Rs 30,883 (3 of 12) | -Rs 30,883 | -Rs 27,460 | -Rs 28,426 | 0.226 | 0.524 | 1% / 0% (real-exit net -Rs 17,418) | Rs 5,356 -> Rs 2.68 lakh | dies |
| R3C NR7 breakout, future trail instead of the premium ladder (energy) | 43 | 65% | Rs 3,956 / -Rs 5,048 | Rs 54,923 | Rs 19,875 | **Rs 35,048** | Rs 19,261 | 3 of 4: Rs 5,863 (10), Rs 21,453 (9), -Rs 14,854 (14), Rs 22,585 (10) | -Rs 19,847 (2 of 12) | -Rs 22,322 | Rs 5,529 | -Rs 19,042 | 0.066 | 0.341 | 5% / 0% (real-exit net Rs 35,492) | Rs 9,055 -> Rs 4.53 lakh | lead |
| R3D NR7 breakout in a low-volatility regime (energy) | 27 | 59% | Rs 4,254 / -Rs 4,642 | Rs 25,544 | Rs 8,553 | **Rs 16,991** | Rs 10,529 | 3 of 4: Rs 11,916 (9), Rs 10,054 (6), -Rs 8,769 (7), Rs 3,791 (5) | -Rs 8,475 (0 of 12) | -Rs 12,272 | -Rs 11,062 | -Rs 6,547 | 0.178 | 0.512 | 4% / 0% (real-exit net Rs 459) | Rs 7,944 -> Rs 3.97 lakh | lead |
| R3E weekly NR7 breakout, 5 sessions (energy) | 10 | 50% | Rs 6,242 / -Rs 7,476 | -Rs 2,513 | Rs 3,660 | **-Rs 6,173** | -Rs 9,028 | 2 of 4: Rs 8,897 (2), -Rs 20,593 (3), -Rs 8,613 (1), Rs 14,136 (4) | -Rs 15,275 (1 of 7) | -Rs 29,206 | -Rs 30,675 | -Rs 4,445 | 0.535 | 0.831 | 10% / 0% (real-exit net -Rs 19,866) | Rs 8,394 -> Rs 4.20 lakh | dies |
| R3F NR7 breakout in the first two hours only (energy) | 23 | 61% | Rs 3,517 / -Rs 4,876 | Rs 16,468 | Rs 11,115 | **Rs 5,353** | -Rs 3,622 | 2 of 4: -Rs 1,643 (5), Rs 9,368 (5), -Rs 19,988 (7), Rs 17,616 (6) | -Rs 22,200 (1 of 11) | -Rs 23,450 | -Rs 12,611 | -Rs 7,487 | 0.309 | 0.557 | 4% / 0% (real-exit net Rs 22,330) | Rs 9,149 -> Rs 4.57 lakh | lead |
| R3G NR7 morning break still holding at the US open (energy) | 32 | 69% | Rs 4,623 / -Rs 5,865 | Rs 54,078 | Rs 11,013 | **Rs 43,065** | Rs 35,087 | 3 of 4: Rs 12,665 (7), Rs 9,243 (7), -Rs 5,080 (10), Rs 26,236 (8) | -Rs 21,647 (2 of 12) | -Rs 30,617 | Rs 10,950 | Rs 8,676 | 0.190 | 0.512 | 3% / 0% (real-exit net Rs 26,583) | Rs 9,143 -> Rs 4.57 lakh | lead |
| R3H EIA-day 11:00-11:30 ET momentum, 1-ITM (crude) | 90 | 34% | Rs 1,539 / -Rs 982 | Rs 6,893 | Rs 17,132 | **-Rs 10,239** | -Rs 20,519 | 2 of 4: -Rs 2,372 (22), Rs 5,465 (22), -Rs 16,314 (26), Rs 2,982 (20) | -Rs 8,018 (0 of 12) | -Rs 27,827 | -Rs 25,893 | -Rs 7,367 | 0.585 | 0.831 | 0% / 1% (real-exit net -Rs 10,239) | Rs 3,891 -> Rs 1.95 lakh | dies |
| R3I last-half-hour intraday momentum, 1-ITM (4 minis) | 223 | 30% | Rs 733 / -Rs 812 | -Rs 39,120 | Rs 39,980 | **-Rs 79,100** | -Rs 1.03 lakh | 0 of 4: -Rs 1,855 (45), -Rs 14,460 (69), -Rs 41,856 (54), -Rs 20,929 (55) | -Rs 38,431 (1 of 11) | -Rs 90,178 | -Rs 98,645 | -Rs 68,872 | 0.639 | 0.862 | 2% / 9% (real-exit net -Rs 17,719) | Rs 2,737 -> Rs 1.37 lakh | dies |
| R3J US-open range breakout in a high-volatility regime (crude) | 14 | 64% | Rs 2,129 / -Rs 2,350 | Rs 12,168 | Rs 4,763 | **Rs 7,406** | Rs 4,135 | 1 of 4: Rs 0 (0), Rs 0 (0), Rs 7,406 (14), Rs 0 (0) | Rs 7,406 (0 of 1) | -Rs 9,711 | -Rs 6,115 | -Rs 5,090 | 0.126 | 0.485 | 0% / 0% (real-exit net Rs 7,406) | Rs 8,666 -> Rs 4.33 lakh | dies |

## Gates per rule

- R3A: failed bh_q
- R3B: failed net, net2, twin_p, bh_q, folds, worst_month, wo_top3, real_exits
- R3C: failed twin_p, bh_q, worst_month
- R3D: failed trades, twin_p, bh_q, wo_top3
- R3E: failed trades, net, net2, twin_p, bh_q, folds, worst_month, wo_top3, real_exits
- R3F: failed trades, net2, twin_p, bh_q, folds, worst_month, wo_top3
- R3G: failed twin_p, bh_q, worst_month
- R3H: failed net, net2, twin_p, bh_q, folds, wo_top3, real_exits
- R3I: failed net, net2, twin_p, bh_q, folds, worst_month, max_dd, wo_top3, real_exits
- R3J: failed trades, twin_p, bh_q, folds, wo_top3

## By book (design)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM | CRUDEOIL | NATURALGAS |
|---|---|---|---|---|---|---|
| R3A | 23: gross Rs 5,860, net Rs 3,705, win 78% | - | - | - | 23: gross Rs 56,918, net Rs 45,170, win 74% | - |
| R3B | 38: gross Rs 731, net -Rs 2,916, win 61% | - | - | - | 39: gross Rs 22,630, net Rs 2,030, win 59% | - |
| R3C | - | - | - | - | 23: gross Rs 56,918, net Rs 45,170, win 74% | 20: gross -Rs 1,995, net -Rs 10,123, win 55% |
| R3D | - | - | - | - | 12: gross Rs 27,648, net Rs 24,385, win 67% | 15: gross -Rs 2,105, net -Rs 7,394, win 53% |
| R3E | - | - | - | - | 6: gross Rs 16,587, net Rs 14,241, win 67% | 4: gross -Rs 19,100, net -Rs 20,414, win 25% |
| R3F | - | - | - | - | 12: gross Rs 18,830, net Rs 12,873, win 67% | 11: gross -Rs 2,362, net -Rs 7,520, win 55% |
| R3G | - | - | - | - | 19: gross Rs 39,528, net Rs 33,169, win 74% | 13: gross Rs 14,550, net Rs 9,895, win 62% |
| R3H | 45: gross Rs 493, net -Rs 2,818, win 33% | - | - | - | 45: gross Rs 6,400, net -Rs 7,422, win 36% | - |
| R3I | 73: gross -Rs 1,132, net -Rs 6,804, win 18% | 66: gross -Rs 2,888, net -Rs 9,471, win 26% | 46: gross -Rs 3,288, net -Rs 15,960, win 41% | 38: gross -Rs 31,813, net -Rs 46,865, win 45% | - | - |
| R3J | 7: gross Rs 168, net -Rs 535, win 57% | - | - | - | 7: gross Rs 12,000, net Rs 7,941, win 71% | - |

## Long-history check of the directional daily signals (futures, 2012 - Jul 2025, 0.10% cost)

| rule | CRUDEOILM | NATGASMINI | GOLDM | SILVERM | CRUDEOIL | NATURALGAS | pooled |
|---|---|---|---|---|---|---|---|
| R3A | 329: +0.58% (twin +0.41%, p 0.26) | - | - | - | 329: +0.58% (twin +0.41%, p 0.26) | - | 658: +0.58% |
| R3B | 645: +0.59% (twin +0.40%, p 0.10) | - | - | - | 645: +0.59% (twin +0.40%, p 0.10) | - | 1290: +0.59% |
| R3C | - | - | - | - | 329: +0.58% (twin +0.41%, p 0.26) | 358: +1.08% (twin +0.54%, p 0.02) | 687: +0.84% |
| R3D | - | - | - | - | 175: +0.74% (twin +0.42%, p 0.19) | 187: +0.88% (twin +0.48%, p 0.15) | 362: +0.81% |
| R3E | - | - | - | - | 59: +2.11% (twin +0.25%, p 0.06) | 66: +1.10% (twin +0.28%, p 0.13) | 125: +1.58% |
| R3F | - | - | - | - | 329: +0.58% (twin +0.41%, p 0.26) | 358: +1.08% (twin +0.54%, p 0.02) | 687: +0.84% |
| R3G | - | - | - | - | 329: +0.58% (twin +0.41%, p 0.26) | 358: +1.08% (twin +0.54%, p 0.02) | 687: +0.84% |
