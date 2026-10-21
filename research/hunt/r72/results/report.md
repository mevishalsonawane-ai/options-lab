# R72 report: cross-index signals (tables)

Final period 1 Apr - 2026-10-13 (133 sessions), read once at 2026-10-16T14:18:47+00:00. Tests gated: 12 (BH across all). Passed: C1-O.

## Final period, every gated test

| test | setting | final trades | final net | win | avg win / loss | worst day | max DD | green days | trades / month | design net | walk-forward net | twin p | BH q | twin mean (no skill) | corr. with Donchian | same trade as Donchian | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1-O Breadth breakout | M=4, XL | 1202 | Rs 171,366 | 46% | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | Rs 61,884 | 52% | 186.7 | Rs 216,929 | Rs 216,929 | 0.008 | 0.032 | Rs 20,729 | 0.795 | 77% | PASS: all gates |
| C1-RT Breadth breakout | M=4, XI | 1153 | Rs 173,315 | 45% | Rs 2,285 / -Rs 1,622 | -Rs 36,808 | Rs 80,262 | 50% | 179.1 | Rs 225,481 | Rs 183,268 | 0.6387 | 0.9721 | Rs 200,025 | 0.713 | 74% | FAIL: twin p >= 0.05, BH q > 0.10 |
| C2-O Laggard catch-up | K=3, XL | 613 | -Rs 100,746 | 40% | Rs 1,441 / -Rs 1,240 | -Rs 18,686 | Rs 125,695 | 41% | 95.2 | -Rs 243,309 | -Rs 243,309 | 0.8942 | 0.9721 | -Rs 43,699 | 0.28 | 22% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C2-RT Laggard catch-up | K=3, XL | 613 | -Rs 100,746 | 40% | Rs 1,441 / -Rs 1,240 | -Rs 18,686 | Rs 125,695 | 41% | 95.2 | -Rs 243,309 | -Rs 103,842 | 0.8942 | 0.9721 | -Rs 43,699 | 0.28 | 22% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C3-O Lonely breakout fade | K=3, XL | 499 | -Rs 160,188 | 41% | Rs 1,075 / -Rs 1,278 | -Rs 13,034 | Rs 161,171 | 34% | 77.5 | -Rs 366,441 | -Rs 366,441 | 0.3952 | 0.9485 | -Rs 171,233 | -0.153 | 0% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C3-RT Lonely breakout fade | K=6, XI | 398 | -Rs 48,623 | 23% | Rs 1,268 / -Rs 540 | -Rs 5,820 | Rs 60,444 | 31% | 61.8 | -Rs 187,474 | -Rs 138,030 | 0.1836 | 0.5508 | -Rs 69,224 | -0.06 | 0% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C4-O Leader at a fixed time | T_bar=11, XL | 55 | -Rs 30,969 | 36% | Rs 929 / -Rs 1,416 | -Rs 5,430 | Rs 30,969 | 36% | 8.5 | -Rs 23,835 | -Rs 23,835 | 0.9721 | 0.9721 | -Rs 6,585 | 0.074 | 31% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C4-RT Leader at a fixed time | T_bar=11, XL | 55 | -Rs 30,969 | 36% | Rs 929 / -Rs 1,416 | -Rs 5,430 | Rs 30,969 | 36% | 8.5 | -Rs 23,835 | -Rs 36,885 | 0.9721 | 0.9721 | -Rs 6,585 | 0.074 | 31% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C5-O Breadth thrust | M=4, XL | 1797 | -Rs 330,727 | 39% | Rs 1,275 / -Rs 1,117 | -Rs 25,280 | Rs 330,727 | 32% | 279.1 | -Rs 578,603 | -Rs 578,603 | 0.7485 | 0.9721 | -Rs 283,324 | 0.097 | 17% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C5-RT Breadth thrust | M=5, XL | 1374 | -Rs 271,443 | 39% | Rs 1,315 / -Rs 1,153 | -Rs 37,071 | Rs 271,443 | 35% | 213.4 | -Rs 346,669 | -Rs 299,242 | 0.8543 | 0.9721 | -Rs 205,091 | 0.126 | 21% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C6-O Divergence at the day's extreme (SMT) | R_=3, XL | 348 | Rs 35,463 | 42% | Rs 1,381 / -Rs 822 | -Rs 4,176 | Rs 33,446 | 50% | 54.0 | -Rs 168,737 | -Rs 168,737 | 0.002 | 0.012 | -Rs 92,699 | -0.004 | 3% | FAIL: top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C6-RT Divergence at the day's extreme (SMT) | R_=4, XI | 164 | Rs 33,733 | 39% | Rs 2,353 / -Rs 1,169 | -Rs 5,286 | Rs 11,783 | 47% | 25.5 | Rs 17,241 | -Rs 42,798 | 0.002 | 0.012 | -Rs 60,090 | 0.084 | 2% | FAIL: top 5 >= net, walk-forward net <= 0 |

## Donchian on the same days (same engine, stores and costs)

| Donchian 20 (reference, not gated) | design trades | design net | final trades | final net | win | avg win / loss | worst day | max DD | green days | trades / month |
|---|---|---|---|---|---|---|---|---|---|---|
| XB -15% / +30% / 30 min | 4518 | Rs 8,281 | 1787 | Rs 55,439 | 44% | Rs 1,566 / -Rs 1,188 | -Rs 23,012 | Rs 92,452 | 47% | 277.5 |
| XL -15% + ladder + L25, 30 min | 4498 | Rs 9,998 | 1781 | Rs 81,845 | 44% | Rs 1,609 / -Rs 1,183 | -Rs 23,012 | Rs 91,626 | 47% | 276.6 |

## Combined with Donchian (final)

| book (final, 1 lot each) | net | max DD | worst day |
|---|---|---|---|
| Donchian XB alone | Rs 55,439 | Rs 92,452 | -Rs 23,012 |
| Donchian + C1-O | Rs 226,805 | Rs 151,164 | -Rs 43,740 |
| Donchian + C1-RT | Rs 228,754 | Rs 163,188 | -Rs 47,062 |
| Donchian + C2-O | -Rs 45,307 | Rs 169,645 | -Rs 37,483 |
| Donchian + C2-RT | -Rs 45,307 | Rs 169,645 | -Rs 37,483 |
| Donchian + C3-O | -Rs 104,749 | Rs 181,539 | -Rs 23,025 |
| Donchian + C3-RT | Rs 6,815 | Rs 118,304 | -Rs 23,691 |
| Donchian + C4-O | Rs 24,470 | Rs 101,239 | -Rs 23,012 |
| Donchian + C4-RT | Rs 24,470 | Rs 101,239 | -Rs 23,012 |
| Donchian + C5-O | -Rs 275,288 | Rs 281,129 | -Rs 31,193 |
| Donchian + C5-RT | -Rs 216,005 | Rs 237,877 | -Rs 39,729 |
| Donchian + C6-O | Rs 90,901 | Rs 81,588 | -Rs 19,659 |
| Donchian + C6-RT | Rs 89,172 | Rs 91,128 | -Rs 22,879 |

## Walk-forward (design)

| rule | walk-forward picks (4 folds) | walk-forward trades | walk-forward net | final pick |
|---|---|---|---|---|
| C1 Breadth breakout | 5|XL, 4|XB, 5|XL, 5|XL | 1691 | Rs 183,268 | 4|XI |
| C2 Laggard catch-up | 6|XL, 3|XL, 3|XL, 3|XI | 1229 | -Rs 103,842 | 3|XL |
| C3 Lonely breakout fade | 6|XI, 6|XI, 6|XI, 6|XI | 761 | -Rs 138,030 | 6|XI |
| C4 Leader at a fixed time | 23|XB, 11|XI, 11|XL, 11|XL | 128 | -Rs 36,885 | 11|XL |
| C5 Breadth thrust | 5|XB, 5|XB, 5|XL, 5|XB | 2677 | -Rs 299,242 | 5|XL |
| C6 Divergence at the day's extreme (SMT) | 4|XI, 4|XB, 4|XB, 4|XB | 273 | -Rs 42,798 | 4|XI |

## Every design grid point

| rule | grid point | design trades | design net | win | worst day | max DD | fold nets (Apr-Jun 25, Jul-Sep, Oct-Dec, Jan-Mar 26) |
|---|---|---|---|---|---|---|---|
| C1 | M=4, XB | 3106 | Rs 206,567 | 47% | -Rs 58,613 | Rs 201,658 | Rs 113,083 / Rs 78,864 / Rs 14,952 / Rs 128,197 |
| C1 | M=4, XL (original) | 3071 | Rs 216,929 | 47% | -Rs 58,613 | Rs 199,077 | Rs 85,063 / Rs 70,225 / Rs 9,774 / Rs 170,821 |
| C1 | M=4, XI (final pick) | 2910 | Rs 225,481 | 47% | -Rs 37,973 | Rs 246,044 | Rs 106,749 / Rs 78,936 / Rs 48,957 / Rs 197,896 |
| C1 | M=5, XB | 2025 | Rs 115,832 | 48% | -Rs 58,613 | Rs 145,316 | Rs 2,175 / Rs 129,021 / Rs 6,743 / Rs 61,268 |
| C1 | M=5, XL | 1997 | Rs 172,555 | 48% | -Rs 58,613 | Rs 140,815 | Rs 4,006 / Rs 140,322 / Rs 7,261 / Rs 93,137 |
| C1 | M=5, XI | 1918 | Rs 157,810 | 49% | -Rs 37,973 | Rs 162,298 | Rs 17,134 / Rs 139,332 / Rs 21,821 / Rs 90,410 |
| C2 | K=3, XB | 1717 | -Rs 308,481 | 39% | -Rs 17,309 | Rs 314,850 | -Rs 78,462 / -Rs 16,631 / -Rs 39,922 / Rs 2,107 |
| C2 | K=3, XL (original) (final pick) | 1716 | -Rs 243,309 | 39% | -Rs 17,309 | Rs 266,792 | -Rs 60,223 / Rs 10,353 / -Rs 35,349 / Rs 19,221 |
| C2 | K=3, XI | 1678 | -Rs 253,236 | 36% | -Rs 20,767 | Rs 276,977 | -Rs 63,211 / Rs 11,401 / -Rs 16,116 / Rs 7,725 |
| C2 | K=6, XB | 1686 | -Rs 356,820 | 38% | -Rs 16,840 | Rs 372,038 | -Rs 98,316 / -Rs 37,386 / -Rs 49,176 / -Rs 7,152 |
| C2 | K=6, XL | 1686 | -Rs 328,607 | 39% | -Rs 16,840 | Rs 339,998 | -Rs 86,570 / -Rs 38,310 / -Rs 41,889 / -Rs 4,539 |
| C2 | K=6, XI | 1695 | -Rs 300,991 | 34% | -Rs 16,659 | Rs 326,608 | -Rs 83,420 / Rs 5,468 / -Rs 32,825 / Rs 14,038 |
| C3 | K=3, XB | 1223 | -Rs 366,239 | 41% | -Rs 12,904 | Rs 367,188 | -Rs 71,455 / -Rs 61,449 / -Rs 54,586 / -Rs 56,596 |
| C3 | K=3, XL (original) | 1223 | -Rs 366,441 | 41% | -Rs 12,904 | Rs 366,441 | -Rs 73,251 / -Rs 60,124 / -Rs 56,090 / -Rs 54,388 |
| C3 | K=3, XI | 1269 | -Rs 233,153 | 20% | -Rs 8,420 | Rs 247,049 | -Rs 66,674 / -Rs 37,709 / -Rs 56,800 / -Rs 15,003 |
| C3 | K=6, XB | 967 | -Rs 282,083 | 42% | -Rs 12,904 | Rs 296,489 | -Rs 30,340 / -Rs 54,357 / -Rs 48,788 / -Rs 38,643 |
| C3 | K=6, XL | 967 | -Rs 277,808 | 42% | -Rs 12,904 | Rs 291,092 | -Rs 32,404 / -Rs 49,332 / -Rs 48,913 / -Rs 37,849 |
| C3 | K=6, XI (final pick) | 1010 | -Rs 187,474 | 20% | -Rs 7,392 | Rs 198,493 | -Rs 39,426 / -Rs 27,114 / -Rs 59,895 / -Rs 11,595 |
| C4 | T_bar=11, XB | 163 | -Rs 25,882 | 42% | -Rs 5,578 | Rs 32,631 | Rs 4,330 / -Rs 1,421 / -Rs 3,609 / -Rs 8,707 |
| C4 | T_bar=11, XL (original) (final pick) | 163 | -Rs 23,835 | 42% | -Rs 5,578 | Rs 30,584 | Rs 6,290 / -Rs 558 / -Rs 4,363 / -Rs 8,707 |
| C4 | T_bar=11, XI | 163 | -Rs 37,560 | 42% | -Rs 7,664 | Rs 51,453 | Rs 6,117 / -Rs 17,258 / Rs 3,543 / -Rs 17,787 |
| C4 | T_bar=23, XB | 180 | -Rs 51,238 | 45% | -Rs 6,030 | Rs 55,069 | -Rs 6,557 / -Rs 11,266 / -Rs 6,096 / -Rs 16,807 |
| C4 | T_bar=23, XL | 180 | -Rs 52,363 | 45% | -Rs 6,030 | Rs 54,876 | -Rs 6,700 / -Rs 11,266 / -Rs 5,862 / -Rs 16,321 |
| C4 | T_bar=23, XI | 180 | -Rs 56,115 | 45% | -Rs 8,192 | Rs 58,724 | -Rs 13,213 / -Rs 16,848 / Rs 7,181 / -Rs 10,399 |
| C5 | M=4, XB | 4541 | -Rs 597,760 | 41% | -Rs 49,884 | Rs 597,760 | -Rs 96,509 / -Rs 51,090 / -Rs 152,056 / -Rs 105,424 |
| C5 | M=4, XL (original) | 4540 | -Rs 578,603 | 42% | -Rs 49,884 | Rs 578,603 | -Rs 68,028 / -Rs 48,310 / -Rs 155,267 / -Rs 86,640 |
| C5 | M=4, XI | 4614 | -Rs 452,699 | 27% | -Rs 50,178 | Rs 452,699 | -Rs 78,055 / -Rs 55,946 / -Rs 35,439 / -Rs 29,360 |
| C5 | M=5, XB | 3554 | -Rs 347,844 | 42% | -Rs 36,406 | Rs 349,481 | -Rs 44,701 / -Rs 48,429 / -Rs 116,030 / -Rs 79,813 |
| C5 | M=5, XL (final pick) | 3553 | -Rs 346,669 | 42% | -Rs 37,168 | Rs 347,807 | -Rs 15,702 / -Rs 44,536 / -Rs 126,299 / -Rs 68,524 |
| C5 | M=5, XI | 3615 | -Rs 401,676 | 28% | -Rs 45,656 | Rs 401,676 | -Rs 45,331 / -Rs 42,071 / -Rs 51,315 / -Rs 78,223 |
| C6 | R_=3, XB | 844 | -Rs 156,363 | 38% | -Rs 11,282 | Rs 170,818 | -Rs 39,267 / -Rs 44,445 / -Rs 12,636 / -Rs 11,987 |
| C6 | R_=3, XL (original) | 844 | -Rs 168,737 | 38% | -Rs 11,282 | Rs 183,192 | -Rs 38,848 / -Rs 47,480 / -Rs 12,493 / -Rs 15,707 |
| C6 | R_=3, XI | 855 | -Rs 49,713 | 37% | -Rs 12,676 | Rs 107,929 | -Rs 29,372 / -Rs 36,596 / -Rs 3,138 / Rs 36,953 |
| C6 | R_=4, XB | 351 | -Rs 24,441 | 40% | -Rs 6,462 | Rs 40,719 | Rs 1,249 / -Rs 3,576 / Rs 3,482 / -Rs 22,254 |
| C6 | R_=4, XL | 351 | -Rs 31,194 | 40% | -Rs 6,462 | Rs 42,938 | Rs 1,668 / -Rs 6,214 / Rs 3,482 / -Rs 22,254 |
| C6 | R_=4, XI (final pick) | 361 | Rs 17,241 | 37% | -Rs 6,314 | Rs 47,934 | -Rs 20,449 / -Rs 1,012 / Rs 8,233 / Rs 21,595 |
