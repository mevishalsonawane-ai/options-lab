# R106 report: fixed small rupee targets on the paper arms (every table)

Net after dated charges and R17 slippage, 1 lot. 'wf' = the walk-forward window Apr 2025 - Mar 2026 (inside design). Break-even win = average loss / (average win + average loss) of the rule's own trades. Exit-twin p: final period, 500 random-exit twins. BH q counts R106's tests plus 513 earlier ones.

BH count: 36 R106 tests + 513 earlier = 549.

## Liquidity 15+5

B0 parity (design): 881 trades, Rs 257,249, R101 published [881, 257249]: reproduced

| rule | period | trades | win | break-even win | net | per session | green days | worst day | max DD | without 5 best | exits (target / stop / other) | exit-twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 881 | 44% | 34% | Rs 257,249 | Rs 763 | 42% | -Rs 8,416 | -Rs 32,537 | Rs 175,714 | 0 / 31 / 850 |  |  |
|  | wf | 657 | 44% | 32% | Rs 238,064 | Rs 964 | 44% | -Rs 6,363 | -Rs 23,139 | Rs 156,528 | 0 / 23 / 634 |  |  |
|  | final | 357 | 37% | 36% | Rs 11,155 | Rs 84 | 41% | -Rs 11,405 | -Rs 61,062 | -Rs 51,376 | 0 / 12 / 345 |  |  |
|  | post | 15 | 27% | 55% | -Rs 7,105 | -Rs 1,776 | 25% | -Rs 6,815 | -Rs 8,243 | -Rs 10,146 | 0 / 2 / 13 |  |  |
| **T500** | design | 886 | 67% | 66% | Rs 4,829 | Rs 14 | 59% | -Rs 6,841 | -Rs 28,118 | Rs 2,296 | 561 / 10 / 315 |  |  |
|  | wf | 660 | 67% | 65% | Rs 16,847 | Rs 68 | 59% | -Rs 6,841 | -Rs 23,911 | Rs 14,314 | 417 / 7 / 236 |  |  |
|  | final | 358 | 58% | 67% | -Rs 48,904 | -Rs 368 | 46% | -Rs 8,044 | -Rs 50,623 | -Rs 52,273 | 193 / 7 / 158 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 15 | 53% | 71% | -Rs 4,432 | -Rs 1,108 | 50% | -Rs 5,193 | -Rs 6,621 | -Rs 6,945 | 8 / 2 / 5 |  |  |
| **T1000** | design | 886 | 55% | 52% | Rs 45,003 | Rs 134 | 56% | -Rs 6,363 | -Rs 22,992 | Rs 39,971 | 391 / 16 / 479 |  |  |
|  | wf | 660 | 55% | 51% | Rs 40,730 | Rs 165 | 56% | -Rs 6,363 | -Rs 21,973 | Rs 35,698 | 287 / 12 / 361 |  |  |
|  | final | 358 | 48% | 55% | -Rs 43,169 | -Rs 325 | 46% | -Rs 8,917 | -Rs 51,236 | -Rs 48,537 | 121 / 9 / 228 | 0.998 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 15 | 33% | 55% | -Rs 5,788 | -Rs 1,447 | 25% | -Rs 6,815 | -Rs 8,243 | -Rs 9,893 | 4 / 2 / 9 |  |  |
| **T1500** | design | 885 | 50% | 45% | Rs 83,523 | Rs 248 | 50% | -Rs 6,363 | -Rs 21,251 | Rs 75,861 | 275 / 19 / 591 |  |  |
|  | wf | 660 | 50% | 45% | Rs 70,035 | Rs 284 | 51% | -Rs 6,363 | -Rs 21,251 | Rs 62,374 | 204 / 15 / 441 |  |  |
|  | final | 358 | 44% | 49% | -Rs 40,324 | -Rs 303 | 44% | -Rs 11,405 | -Rs 58,946 | -Rs 47,847 | 81 / 10 / 267 | 0.988 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 15 | 27% | 45% | -Rs 5,569 | -Rs 1,392 | 25% | -Rs 6,815 | -Rs 8,243 | -Rs 10,146 | 3 / 2 / 10 |  |  |
| **T500S** | design | 887 | 45% | 51% | -Rs 50,878 | -Rs 151 | 46% | -Rs 5,077 | -Rs 50,881 | -Rs 53,411 | 384 / 453 / 50 |  |  |
|  | wf | 661 | 46% | 52% | -Rs 35,476 | -Rs 144 | 47% | -Rs 5,077 | -Rs 44,076 | -Rs 38,009 | 288 / 330 / 43 |  |  |
|  | final | 358 | 36% | 51% | -Rs 52,506 | -Rs 395 | 34% | -Rs 4,049 | -Rs 52,516 | -Rs 55,873 | 123 / 206 / 29 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 15 | 33% | 49% | -Rs 2,295 | -Rs 574 | 50% | -Rs 3,489 | -Rs 4,485 | -Rs 4,806 | 5 / 9 / 1 |  |  |
| **T1000S** | design | 887 | 49% | 48% | Rs 17,385 | Rs 52 | 51% | -Rs 5,041 | -Rs 27,317 | Rs 12,353 | 349 / 278 / 260 |  |  |
|  | wf | 661 | 50% | 48% | Rs 24,208 | Rs 98 | 52% | -Rs 5,041 | -Rs 16,841 | Rs 19,175 | 260 / 194 / 207 |  |  |
|  | final | 358 | 42% | 48% | -Rs 36,318 | -Rs 273 | 45% | -Rs 5,273 | -Rs 38,715 | -Rs 41,686 | 106 / 121 / 131 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 15 | 27% | 45% | -Rs 4,892 | -Rs 1,223 | 50% | -Rs 6,147 | -Rs 7,575 | -Rs 8,705 | 4 / 6 / 5 |  |  |
| **T1500S** | design | 886 | 48% | 45% | Rs 63,986 | Rs 190 | 48% | -Rs 5,995 | -Rs 24,761 | Rs 56,325 | 262 / 143 / 481 |  |  |
|  | wf | 661 | 48% | 44% | Rs 48,366 | Rs 196 | 49% | -Rs 5,995 | -Rs 24,761 | Rs 40,705 | 194 / 105 / 362 |  |  |
|  | final | 358 | 42% | 47% | -Rs 30,844 | -Rs 232 | 44% | -Rs 7,734 | -Rs 48,003 | -Rs 38,367 | 80 / 62 / 216 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 15 | 27% | 40% | -Rs 3,914 | -Rs 978 | 25% | -Rs 5,159 | -Rs 6,588 | -Rs 8,491 | 3 / 2 / 10 |  |  |
| **T1000R** | design | 1320 | 57% | 57% | -Rs 9,122 | -Rs 27 | 42% | -Rs 17,086 | -Rs 66,360 | -Rs 14,954 | 591 / 53 / 676 |  |  |
|  | wf | 984 | 57% | 57% | -Rs 2,704 | -Rs 11 | 42% | -Rs 17,086 | -Rs 66,360 | -Rs 8,536 | 435 / 39 / 510 |  |  |
|  | final | 493 | 49% | 55% | -Rs 59,379 | -Rs 446 | 39% | -Rs 11,992 | -Rs 72,005 | -Rs 64,747 | 177 / 17 / 299 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 20 | 40% | 56% | -Rs 5,360 | -Rs 1,340 | 25% | -Rs 6,815 | -Rs 8,243 | -Rs 10,366 | 5 / 2 / 13 |  |  |
| **T1000SR** | design | 1240 | 47% | 49% | -Rs 39,295 | -Rs 117 | 45% | -Rs 5,041 | -Rs 49,883 | -Rs 44,894 | 464 / 456 / 320 |  |  |
|  | wf | 927 | 47% | 49% | -Rs 25,677 | -Rs 104 | 46% | -Rs 5,041 | -Rs 37,083 | -Rs 31,275 | 341 / 327 / 259 |  |  |
|  | final | 474 | 42% | 49% | -Rs 50,014 | -Rs 376 | 37% | -Rs 5,273 | -Rs 53,204 | -Rs 55,382 | 147 / 175 / 152 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 20 | 25% | 46% | -Rs 7,937 | -Rs 1,984 | 0% | -Rs 6,147 | -Rs 7,937 | -Rs 12,943 | 5 / 10 / 5 |  |  |
| **DAY5K** | design | 975 | 48% | 49% | -Rs 15,392 | -Rs 46 | 48% | -Rs 4,998 | -Rs 31,060 | -Rs 20,990 | 372 / 350 / 253 |  |  |
|  | wf | 725 | 49% | 49% | Rs 14 | Rs 0 | 50% | -Rs 4,254 | -Rs 23,406 | -Rs 5,585 | 275 / 246 / 204 |  |  |
|  | final | 407 | 40% | 49% | -Rs 57,989 | -Rs 436 | 35% | -Rs 5,032 | -Rs 57,989 | -Rs 63,356 | 120 / 157 / 130 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 16 | 31% | 49% | -Rs 5,428 | -Rs 1,357 | 25% | -Rs 4,958 | -Rs 6,386 | -Rs 10,433 | 5 / 8 / 3 |  |  |

| rule | twin p | q (R106 only) | q (with earlier) |
|---|---|---|---|
| T500 | 1.0 | 1.0 | 1.0 |
| T1000 | 0.998 | 1.0 | 1.0 |
| T1500 | 0.988 | 1.0 | 1.0 |
| T500S | 1.0 | 1.0 | 1.0 |
| T1000S | 1.0 | 1.0 | 1.0 |
| T1500S | 1.0 | 1.0 | 1.0 |
| T1000R | 1.0 | 1.0 | 1.0 |
| T1000SR | 1.0 | 1.0 | 1.0 |
| DAY5K | 1.0 | 1.0 | 1.0 |

## R72 Breadth Donchian

B0 parity (design): 3071 trades, Rs 216,929, R101 published [3071, 216929]: reproduced

| rule | period | trades | win | break-even win | net | per session | green days | worst day | max DD | without 5 best | exits (target / stop / other) | exit-twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 3071 | 47% | 44% | Rs 216,929 | Rs 644 | 46% | -Rs 58,613 | -Rs 199,077 | Rs 160,476 | 0 / 568 / 2503 |  |  |
|  | wf | 2238 | 49% | 44% | Rs 335,882 | Rs 1,360 | 48% | -Rs 25,837 | -Rs 86,529 | Rs 279,428 | 0 / 365 / 1873 |  |  |
|  | final | 1202 | 46% | 41% | Rs 171,366 | Rs 1,288 | 52% | -Rs 22,571 | -Rs 61,884 | Rs 112,623 | 0 / 174 / 1028 |  |  |
|  | post | 25 | 56% | 57% | -Rs 624 | -Rs 156 | 67% | -Rs 18,451 | -Rs 18,451 | -Rs 13,429 | 0 / 6 / 19 |  |  |
| **T500** | design | 3408 | 71% | 74% | -Rs 202,118 | -Rs 600 | 48% | -Rs 30,677 | -Rs 219,848 | -Rs 204,652 | 2368 / 394 / 646 |  |  |
|  | wf | 2479 | 72% | 73% | -Rs 37,377 | -Rs 151 | 49% | -Rs 26,729 | -Rs 90,914 | -Rs 39,911 | 1739 / 249 / 491 |  |  |
|  | final | 1285 | 67% | 73% | -Rs 137,073 | -Rs 1,031 | 50% | -Rs 29,151 | -Rs 156,508 | -Rs 139,603 | 834 / 135 / 316 | 0.996 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 29 | 83% | 78% | Rs 2,944 | Rs 736 | 67% | -Rs 6,584 | -Rs 6,584 | Rs 430 | 24 / 3 / 2 |  |  |
| **T1000** | design | 3329 | 58% | 60% | -Rs 160,150 | -Rs 475 | 45% | -Rs 31,350 | -Rs 212,695 | -Rs 165,184 | 1704 / 516 / 1109 |  |  |
|  | wf | 2420 | 59% | 59% | Rs 10,727 | Rs 43 | 48% | -Rs 31,350 | -Rs 124,001 | Rs 5,693 | 1254 / 337 / 829 |  |  |
|  | final | 1266 | 55% | 59% | -Rs 102,284 | -Rs 769 | 49% | -Rs 26,057 | -Rs 157,294 | -Rs 107,313 | 599 / 172 / 495 | 0.0279 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 29 | 69% | 68% | Rs 1,096 | Rs 274 | 67% | -Rs 16,573 | -Rs 16,573 | -Rs 3,923 | 18 / 6 / 5 |  |  |
| **T1500** | design | 3255 | 52% | 52% | -Rs 6,095 | -Rs 18 | 46% | -Rs 50,989 | -Rs 209,821 | -Rs 13,629 | 1295 / 546 / 1414 |  |  |
|  | wf | 2354 | 54% | 52% | Rs 142,418 | Rs 577 | 49% | -Rs 29,853 | -Rs 91,109 | Rs 134,884 | 934 / 350 / 1070 |  |  |
|  | final | 1248 | 49% | 51% | -Rs 61,743 | -Rs 464 | 49% | -Rs 23,060 | -Rs 152,344 | -Rs 69,272 | 432 / 185 / 631 | 0.002 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 0.27 > 0.10 |
|  | post | 29 | 48% | 56% | -Rs 6,706 | -Rs 1,676 | 67% | -Rs 18,451 | -Rs 18,451 | -Rs 14,221 | 11 / 7 / 11 |  |  |
| **T500S** | design | 3423 | 42% | 51% | -Rs 317,428 | -Rs 942 | 34% | -Rs 10,145 | -Rs 323,722 | -Rs 319,961 | 1425 / 1963 / 35 |  |  |
|  | wf | 2488 | 43% | 52% | -Rs 221,478 | -Rs 897 | 34% | -Rs 10,145 | -Rs 225,758 | -Rs 224,011 | 1055 / 1404 / 29 |  |  |
|  | final | 1289 | 37% | 52% | -Rs 193,060 | -Rs 1,452 | 29% | -Rs 7,000 | -Rs 193,060 | -Rs 195,588 | 471 / 782 / 36 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 29 | 34% | 50% | -Rs 4,460 | -Rs 1,115 | 33% | -Rs 2,992 | -Rs 5,969 | -Rs 6,971 | 10 / 19 / 0 |  |  |
| **T1000S** | design | 3337 | 46% | 50% | -Rs 278,367 | -Rs 826 | 45% | -Rs 13,992 | -Rs 278,367 | -Rs 283,400 | 1362 / 1537 / 438 |  |  |
|  | wf | 2424 | 48% | 51% | -Rs 119,221 | -Rs 483 | 48% | -Rs 13,992 | -Rs 119,221 | -Rs 124,255 | 1017 / 1046 / 361 |  |  |
|  | final | 1268 | 45% | 50% | -Rs 118,553 | -Rs 891 | 43% | -Rs 11,204 | -Rs 132,234 | -Rs 123,582 | 505 / 573 / 190 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 29 | 55% | 58% | -Rs 1,866 | -Rs 466 | 67% | -Rs 13,535 | -Rs 13,535 | -Rs 6,883 | 14 / 13 / 2 |  |  |
| **T1500S** | design | 3255 | 48% | 49% | -Rs 79,869 | -Rs 237 | 46% | -Rs 19,483 | -Rs 167,480 | -Rs 87,403 | 1182 / 1080 / 993 |  |  |
|  | wf | 2357 | 49% | 49% | Rs 25,093 | Rs 102 | 49% | -Rs 16,033 | -Rs 61,785 | Rs 17,560 | 851 / 716 / 790 |  |  |
|  | final | 1250 | 46% | 49% | -Rs 74,033 | -Rs 557 | 46% | -Rs 20,072 | -Rs 118,248 | -Rs 81,563 | 411 / 405 / 434 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 29 | 38% | 53% | -Rs 11,603 | -Rs 2,901 | 67% | -Rs 16,527 | -Rs 16,527 | -Rs 19,116 | 9 / 10 / 10 |  |  |
| **T1000R** | design | 4910 | 60% | 61% | -Rs 163,243 | -Rs 484 | 44% | -Rs 36,264 | -Rs 258,196 | -Rs 168,278 | 2619 / 736 / 1555 |  |  |
|  | wf | 3601 | 61% | 60% | Rs 53,161 | Rs 215 | 48% | -Rs 26,245 | -Rs 137,543 | Rs 48,126 | 1941 / 495 / 1165 |  |  |
|  | final | 1860 | 58% | 58% | -Rs 35,971 | -Rs 270 | 47% | -Rs 24,754 | -Rs 112,691 | -Rs 41,001 | 934 / 239 / 687 | 0.008 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 0.88 > 0.10 |
|  | post | 46 | 63% | 63% | Rs 65 | Rs 16 | 67% | -Rs 17,177 | -Rs 17,177 | -Rs 4,954 | 29 / 8 / 9 |  |  |
| **T1000SR** | design | 4563 | 46% | 50% | -Rs 373,100 | -Rs 1,107 | 35% | -Rs 14,974 | -Rs 373,100 | -Rs 378,135 | 1896 / 2151 / 516 |  |  |
|  | wf | 3347 | 48% | 51% | -Rs 180,295 | -Rs 730 | 37% | -Rs 13,992 | -Rs 180,295 | -Rs 185,329 | 1420 / 1496 / 431 |  |  |
|  | final | 1765 | 46% | 50% | -Rs 150,827 | -Rs 1,134 | 36% | -Rs 12,410 | -Rs 180,851 | -Rs 155,857 | 715 / 807 / 243 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 43 | 51% | 55% | -Rs 3,765 | -Rs 941 | 67% | -Rs 13,535 | -Rs 13,535 | -Rs 8,781 | 19 / 21 / 3 |  |  |
| **DAY5K** | design | 1561 | 45% | 51% | -Rs 168,520 | -Rs 500 | 41% | -Rs 10,327 | -Rs 172,527 | -Rs 173,550 | 636 / 749 / 176 |  |  |
|  | wf | 1141 | 48% | 51% | -Rs 76,632 | -Rs 310 | 43% | -Rs 10,327 | -Rs 76,632 | -Rs 81,662 | 481 / 514 / 146 |  |  |
|  | final | 624 | 49% | 51% | -Rs 15,853 | -Rs 119 | 47% | -Rs 7,676 | -Rs 39,965 | -Rs 20,882 | 280 / 269 / 75 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 15 | 47% | 59% | -Rs 4,492 | -Rs 1,123 | 33% | -Rs 8,508 | -Rs 9,501 | -Rs 9,503 | 7 / 8 / 0 |  |  |

| rule | twin p | q (R106 only) | q (with earlier) |
|---|---|---|---|
| T500 | 0.996 | 1.0 | 1.0 |
| T1000 | 0.0279 | 0.1674 | 1.0 |
| T1500 | 0.002 | 0.018 | 0.2745 |
| T500S | 1.0 | 1.0 | 1.0 |
| T1000S | 1.0 | 1.0 | 1.0 |
| T1500S | 1.0 | 1.0 | 1.0 |
| T1000R | 0.008 | 0.0576 | 0.8784 |
| T1000SR | 1.0 | 1.0 | 1.0 |
| DAY5K | 1.0 | 1.0 | 1.0 |

## R51 Donchian 20

B0 parity (design): 4498 trades, Rs 9,998, R101 published [4498, 9998]: reproduced

| rule | period | trades | win | break-even win | net | per session | green days | worst day | max DD | without 5 best | exits (target / stop / other) | exit-twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 4498 | 45% | 45% | Rs 9,998 | Rs 30 | 47% | -Rs 56,647 | -Rs 224,592 | -Rs 57,344 | 0 / 815 / 3683 |  |  |
|  | wf | 3266 | 46% | 44% | Rs 175,789 | Rs 712 | 49% | -Rs 31,959 | -Rs 103,931 | Rs 108,447 | 0 / 524 / 2742 |  |  |
|  | final | 1781 | 44% | 42% | Rs 81,845 | Rs 615 | 47% | -Rs 23,012 | -Rs 91,626 | Rs 29,590 | 0 / 239 / 1542 |  |  |
|  | post | 49 | 47% | 46% | Rs 1,927 | Rs 482 | 50% | -Rs 13,478 | -Rs 20,847 | -Rs 16,014 | 0 / 12 / 37 |  |  |
| **T500** | design | 4782 | 71% | 75% | -Rs 346,770 | -Rs 1,029 | 44% | -Rs 26,689 | -Rs 361,868 | -Rs 349,304 | 3314 / 545 / 923 |  |  |
|  | wf | 3470 | 71% | 73% | -Rs 157,683 | -Rs 638 | 45% | -Rs 14,124 | -Rs 186,971 | -Rs 160,217 | 2396 / 347 / 727 |  |  |
|  | final | 1880 | 66% | 72% | -Rs 199,218 | -Rs 1,498 | 35% | -Rs 18,819 | -Rs 199,218 | -Rs 201,747 | 1212 / 189 / 479 | 0.9481 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 53 | 75% | 78% | -Rs 3,301 | -Rs 825 | 25% | -Rs 4,645 | -Rs 8,450 | -Rs 5,818 | 40 / 7 / 6 |  |  |
| **T1000** | design | 4699 | 58% | 61% | -Rs 311,891 | -Rs 925 | 45% | -Rs 27,457 | -Rs 332,857 | -Rs 316,925 | 2388 / 732 / 1579 |  |  |
|  | wf | 3409 | 58% | 60% | -Rs 137,881 | -Rs 558 | 46% | -Rs 27,457 | -Rs 164,703 | -Rs 142,915 | 1706 / 472 / 1231 |  |  |
|  | final | 1857 | 55% | 58% | -Rs 127,083 | -Rs 956 | 44% | -Rs 23,104 | -Rs 155,022 | -Rs 132,113 | 859 / 232 / 766 | 0.002 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 0.27 > 0.10 |
|  | post | 53 | 60% | 67% | -Rs 9,483 | -Rs 2,371 | 50% | -Rs 19,027 | -Rs 25,795 | -Rs 14,508 | 30 / 14 / 9 |  |  |
| **T1500** | design | 4633 | 51% | 53% | -Rs 282,254 | -Rs 838 | 45% | -Rs 35,770 | -Rs 338,138 | -Rs 289,788 | 1750 / 795 / 2088 |  |  |
|  | wf | 3356 | 52% | 53% | -Rs 84,154 | -Rs 341 | 47% | -Rs 35,770 | -Rs 162,198 | -Rs 91,688 | 1236 / 510 / 1610 |  |  |
|  | final | 1826 | 48% | 51% | -Rs 115,126 | -Rs 866 | 47% | -Rs 23,012 | -Rs 149,111 | -Rs 122,655 | 602 / 242 / 982 | 0.002 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 0.27 > 0.10 |
|  | post | 53 | 45% | 55% | -Rs 15,382 | -Rs 3,846 | 25% | -Rs 18,642 | -Rs 26,952 | -Rs 22,896 | 20 / 16 / 17 |  |  |
| **T500S** | design | 4808 | 42% | 51% | -Rs 446,099 | -Rs 1,324 | 32% | -Rs 11,267 | -Rs 452,189 | -Rs 448,632 | 1982 / 2766 / 60 |  |  |
|  | wf | 3488 | 42% | 51% | -Rs 304,341 | -Rs 1,232 | 34% | -Rs 11,267 | -Rs 307,415 | -Rs 306,875 | 1465 / 1975 / 48 |  |  |
|  | final | 1891 | 40% | 52% | -Rs 228,566 | -Rs 1,719 | 25% | -Rs 7,772 | -Rs 228,566 | -Rs 231,094 | 747 / 1100 / 44 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 53 | 36% | 51% | -Rs 7,948 | -Rs 1,987 | 0% | -Rs 2,476 | -Rs 7,948 | -Rs 10,465 | 19 / 34 / 0 |  |  |
| **T1000S** | design | 4712 | 46% | 50% | -Rs 390,085 | -Rs 1,158 | 40% | -Rs 12,988 | -Rs 392,144 | -Rs 395,120 | 1929 / 2204 / 579 |  |  |
|  | wf | 3419 | 47% | 51% | -Rs 221,743 | -Rs 898 | 42% | -Rs 12,722 | -Rs 221,743 | -Rs 226,778 | 1403 / 1532 / 484 |  |  |
|  | final | 1861 | 45% | 50% | -Rs 158,421 | -Rs 1,191 | 37% | -Rs 11,202 | -Rs 158,973 | -Rs 163,451 | 727 / 811 / 323 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 53 | 51% | 55% | -Rs 4,170 | -Rs 1,043 | 50% | -Rs 10,617 | -Rs 15,849 | -Rs 9,195 | 25 / 26 / 2 |  |  |
| **T1500S** | design | 4642 | 47% | 49% | -Rs 270,410 | -Rs 802 | 44% | -Rs 19,170 | -Rs 300,853 | -Rs 277,945 | 1613 / 1580 / 1449 |  |  |
|  | wf | 3364 | 47% | 49% | -Rs 142,682 | -Rs 578 | 45% | -Rs 17,694 | -Rs 168,441 | -Rs 150,216 | 1142 / 1082 / 1140 |  |  |
|  | final | 1830 | 45% | 48% | -Rs 128,452 | -Rs 966 | 41% | -Rs 19,525 | -Rs 142,406 | -Rs 135,981 | 572 / 569 / 689 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 53 | 40% | 51% | -Rs 16,632 | -Rs 4,158 | 25% | -Rs 12,305 | -Rs 19,272 | -Rs 24,145 | 18 / 24 / 11 |  |  |
| **T1000R** | design | 6386 | 59% | 61% | -Rs 367,315 | -Rs 1,090 | 45% | -Rs 31,846 | -Rs 401,847 | -Rs 372,349 | 3345 / 955 / 2086 |  |  |
|  | wf | 4657 | 59% | 61% | -Rs 146,672 | -Rs 594 | 46% | -Rs 31,846 | -Rs 235,557 | -Rs 151,707 | 2421 / 629 / 1607 |  |  |
|  | final | 2471 | 54% | 59% | -Rs 228,908 | -Rs 1,721 | 40% | -Rs 23,088 | -Rs 252,714 | -Rs 233,937 | 1151 / 307 / 1013 | 0.002 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 0.27 > 0.10 |
|  | post | 77 | 61% | 65% | -Rs 9,731 | -Rs 2,433 | 25% | -Rs 14,901 | -Rs 24,646 | -Rs 14,754 | 47 / 17 / 13 |  |  |
| **T1000SR** | design | 6128 | 46% | 50% | -Rs 503,371 | -Rs 1,494 | 36% | -Rs 13,980 | -Rs 506,227 | -Rs 508,406 | 2534 / 2907 / 687 |  |  |
|  | wf | 4478 | 47% | 51% | -Rs 301,320 | -Rs 1,220 | 39% | -Rs 12,714 | -Rs 301,320 | -Rs 306,354 | 1856 / 2053 / 569 |  |  |
|  | final | 2415 | 44% | 50% | -Rs 255,863 | -Rs 1,924 | 30% | -Rs 12,199 | -Rs 262,355 | -Rs 260,892 | 933 / 1092 / 390 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 72 | 47% | 53% | -Rs 8,127 | -Rs 2,032 | 50% | -Rs 13,703 | -Rs 17,934 | -Rs 13,151 | 33 / 38 / 1 |  |  |
| **DAY5K** | design | 1670 | 46% | 51% | -Rs 162,663 | -Rs 483 | 42% | -Rs 10,327 | -Rs 166,671 | -Rs 167,695 | 738 / 857 / 75 |  |  |
|  | wf | 1226 | 47% | 51% | -Rs 97,080 | -Rs 393 | 43% | -Rs 10,327 | -Rs 97,080 | -Rs 102,111 | 550 / 610 / 66 |  |  |
|  | final | 661 | 49% | 52% | -Rs 39,842 | -Rs 300 | 50% | -Rs 6,668 | -Rs 56,893 | -Rs 44,871 | 290 / 312 / 59 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 20 | 30% | 50% | -Rs 8,264 | -Rs 2,066 | 0% | -Rs 3,248 | -Rs 8,264 | -Rs 13,279 | 6 / 14 / 0 |  |  |

| rule | twin p | q (R106 only) | q (with earlier) |
|---|---|---|---|
| T500 | 0.9481 | 1.0 | 1.0 |
| T1000 | 0.002 | 0.018 | 0.2745 |
| T1500 | 0.002 | 0.018 | 0.2745 |
| T500S | 1.0 | 1.0 | 1.0 |
| T1000S | 1.0 | 1.0 | 1.0 |
| T1500S | 1.0 | 1.0 | 1.0 |
| T1000R | 0.002 | 0.018 | 0.2745 |
| T1000SR | 1.0 | 1.0 | 1.0 |
| DAY5K | 1.0 | 1.0 | 1.0 |

## R52 Open outside value

B0 parity (design): 706 trades, Rs 290,573, R101 published [None, 290573]: reproduced

| rule | period | trades | win | break-even win | net | per session | green days | worst day | max DD | without 5 best | exits (target / stop / other) | exit-twin p | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 706 | 56% | 47% | Rs 290,573 | Rs 862 | 54% | -Rs 25,140 | -Rs 82,713 | Rs 230,722 | 0 / 72 / 634 |  |  |
|  | wf | 529 | 57% | 47% | Rs 247,057 | Rs 1,000 | 56% | -Rs 25,140 | -Rs 49,847 | Rs 195,434 | 0 / 49 / 480 |  |  |
|  | final | 293 | 52% | 47% | Rs 48,612 | Rs 366 | 55% | -Rs 16,554 | -Rs 47,194 | Rs 9,031 | 0 / 14 / 279 |  |  |
|  | post | 6 | 33% | 77% | -Rs 7,291 | -Rs 1,823 | 33% | -Rs 4,472 | -Rs 8,017 | -Rs 3,545 | 0 / 2 / 4 |  |  |
| **T500** | design | 706 | 86% | 86% | Rs 9,206 | Rs 27 | 76% | -Rs 16,928 | -Rs 78,956 | Rs 6,673 | 605 / 34 / 67 |  |  |
|  | wf | 529 | 88% | 85% | Rs 66,134 | Rs 268 | 80% | -Rs 12,638 | -Rs 19,040 | Rs 63,601 | 467 / 21 / 41 |  |  |
|  | final | 293 | 82% | 83% | -Rs 8,903 | -Rs 67 | 73% | -Rs 16,554 | -Rs 31,467 | -Rs 11,430 | 237 / 9 / 47 | 0.998 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 6 | 67% | 82% | -Rs 2,463 | -Rs 616 | 67% | -Rs 4,472 | -Rs 4,472 | -Rs 2,549 | 4 / 2 / 0 |  |  |
| **T1000** | design | 706 | 75% | 73% | Rs 56,613 | Rs 168 | 65% | -Rs 16,928 | -Rs 67,963 | Rs 51,581 | 518 / 58 / 130 |  |  |
|  | wf | 529 | 77% | 72% | Rs 82,814 | Rs 335 | 68% | -Rs 12,638 | -Rs 27,707 | Rs 77,782 | 395 / 41 / 93 |  |  |
|  | final | 293 | 71% | 69% | Rs 20,151 | Rs 152 | 65% | -Rs 16,554 | -Rs 41,522 | Rs 15,124 | 197 / 10 / 86 | 0.7186 | fail: not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 6 | 67% | 69% | -Rs 463 | -Rs 116 | 67% | -Rs 4,472 | -Rs 4,472 | -Rs 2,549 | 4 / 2 / 0 |  |  |
| **T1500** | design | 706 | 68% | 64% | Rs 102,322 | Rs 304 | 62% | -Rs 18,625 | -Rs 84,571 | Rs 94,789 | 436 / 65 / 205 |  |  |
|  | wf | 529 | 69% | 63% | Rs 119,054 | Rs 482 | 64% | -Rs 17,002 | -Rs 49,592 | Rs 111,522 | 329 / 44 / 156 |  |  |
|  | final | 293 | 63% | 60% | Rs 31,274 | Rs 235 | 63% | -Rs 16,554 | -Rs 44,118 | Rs 23,746 | 159 / 14 / 120 | 0.2375 | fail: not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 6 | 67% | 60% | Rs 1,537 | Rs 384 | 67% | -Rs 4,472 | -Rs 4,472 | -Rs 2,549 | 4 / 2 / 0 |  |  |
| **T500S** | design | 706 | 44% | 52% | -Rs 55,627 | -Rs 165 | 48% | -Rs 4,341 | -Rs 57,648 | -Rs 58,157 | 313 / 393 / 0 |  |  |
|  | wf | 529 | 45% | 52% | -Rs 39,256 | -Rs 159 | 49% | -Rs 3,323 | -Rs 41,277 | -Rs 41,786 | 239 / 290 / 0 |  |  |
|  | final | 293 | 43% | 54% | -Rs 37,206 | -Rs 280 | 52% | -Rs 7,096 | -Rs 37,725 | -Rs 39,730 | 125 / 166 / 2 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 6 | 0% | 100% | -Rs 2,989 | -Rs 747 | 0% | -Rs 1,494 | -Rs 2,989 | -Rs 500 | 0 / 6 / 0 |  |  |
| **T1000S** | design | 706 | 47% | 52% | -Rs 76,037 | -Rs 226 | 46% | -Rs 10,327 | -Rs 81,498 | -Rs 81,068 | 327 / 374 / 5 |  |  |
|  | wf | 529 | 49% | 52% | -Rs 37,643 | -Rs 152 | 48% | -Rs 10,327 | -Rs 42,102 | -Rs 42,674 | 257 / 267 / 5 |  |  |
|  | final | 293 | 48% | 53% | -Rs 30,200 | -Rs 227 | 53% | -Rs 5,656 | -Rs 35,229 | -Rs 35,227 | 135 / 149 / 9 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 6 | 33% | 50% | -Rs 1,994 | -Rs 498 | 33% | -Rs 1,998 | -Rs 2,998 | -Rs 1,000 | 2 / 4 / 0 |  |  |
| **T1500S** | design | 706 | 49% | 51% | -Rs 51,478 | -Rs 153 | 48% | -Rs 11,263 | -Rs 124,217 | -Rs 59,011 | 327 / 339 / 40 |  |  |
|  | wf | 529 | 51% | 52% | -Rs 11,433 | -Rs 46 | 51% | -Rs 11,263 | -Rs 70,341 | -Rs 18,966 | 253 / 242 / 34 |  |  |
|  | final | 293 | 52% | 52% | -Rs 163 | -Rs 1 | 53% | -Rs 8,296 | -Rs 34,857 | -Rs 7,689 | 132 / 124 / 37 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 6 | 33% | 50% | -Rs 2,985 | -Rs 746 | 33% | -Rs 2,996 | -Rs 4,492 | -Rs 1,499 | 2 / 4 / 0 |  |  |
| **T1000R** | design | 1935 | 73% | 72% | Rs 62,421 | Rs 185 | 49% | -Rs 19,110 | -Rs 85,596 | Rs 57,387 | 1368 / 132 / 435 |  |  |
|  | wf | 1440 | 72% | 71% | Rs 59,112 | Rs 239 | 48% | -Rs 17,081 | -Rs 47,658 | Rs 54,078 | 1004 / 93 / 343 |  |  |
|  | final | 690 | 67% | 70% | -Rs 68,974 | -Rs 519 | 50% | -Rs 16,554 | -Rs 93,466 | -Rs 74,003 | 429 / 38 / 223 | 0.3054 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 16 | 62% | 68% | -Rs 2,591 | -Rs 648 | 33% | -Rs 4,472 | -Rs 5,500 | -Rs 7,611 | 10 / 3 / 3 |  |  |
| **T1000SR** | design | 1272 | 47% | 51% | -Rs 107,454 | -Rs 319 | 38% | -Rs 10,322 | -Rs 127,512 | -Rs 112,486 | 593 / 662 / 17 |  |  |
|  | wf | 951 | 47% | 51% | -Rs 89,640 | -Rs 363 | 41% | -Rs 10,322 | -Rs 89,640 | -Rs 94,672 | 441 / 494 / 16 |  |  |
|  | final | 509 | 47% | 52% | -Rs 54,425 | -Rs 409 | 42% | -Rs 6,094 | -Rs 59,644 | -Rs 59,453 | 227 / 262 / 20 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 8 | 25% | 50% | -Rs 3,991 | -Rs 998 | 0% | -Rs 1,998 | -Rs 3,991 | -Rs 3,000 | 2 / 6 / 0 |  |  |
| **DAY5K** | design | 900 | 47% | 52% | -Rs 82,667 | -Rs 245 | 40% | -Rs 10,327 | -Rs 88,951 | -Rs 87,699 | 420 / 470 / 10 |  |  |
|  | wf | 671 | 49% | 52% | -Rs 50,132 | -Rs 203 | 43% | -Rs 10,327 | -Rs 51,403 | -Rs 55,163 | 321 / 340 / 10 |  |  |
|  | final | 391 | 49% | 53% | -Rs 33,021 | -Rs 248 | 44% | -Rs 5,656 | -Rs 42,688 | -Rs 38,048 | 184 / 197 / 10 | 1.0 | fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 8 | 25% | 50% | -Rs 3,991 | -Rs 998 | 0% | -Rs 1,998 | -Rs 3,991 | -Rs 3,000 | 2 / 6 / 0 |  |  |

| rule | twin p | q (R106 only) | q (with earlier) |
|---|---|---|---|
| T500 | 0.998 | 1.0 | 1.0 |
| T1000 | 0.7186 | 1.0 | 1.0 |
| T1500 | 0.2375 | 1.0 | 1.0 |
| T500S | 1.0 | 1.0 | 1.0 |
| T1000S | 1.0 | 1.0 | 1.0 |
| T1500S | 1.0 | 1.0 | 1.0 |
| T1000R | 0.3054 | 1.0 | 1.0 |
| T1000SR | 1.0 | 1.0 | 1.0 |
| DAY5K | 1.0 | 1.0 | 1.0 |

## All arms together, design: '5 trades x Rs 1,000' (DAY5K across arms) vs today's rules

| | sessions | traded | median day | mean day | days >= Rs 5,000 | green | red | worst day | best day | 10th pct | 90th pct |
|---|---|---|---|---|---|---|---|---|---|---|---|
| DAY5K all arms | 337 | 337 | -Rs 991 | -Rs 516 | 13% | 44% | 56% | -Rs 11,598 | Rs 5,019 | -Rs 4,994 | Rs 5,006 |
| today's rules (B0) all arms | 337 | 337 | -Rs 1,285 | Rs 2,299 | 34% | 47% | 53% | -Rs 121,774 | Rs 149,586 | -Rs 24,568 | Rs 37,085 |
| DAY5K, walk-forward window | 247 | 247 | -Rs 988 | -Rs 424 | 15% | 46% | 54% | -Rs 11,598 | Rs 5,016 | -Rs 4,993 | Rs 5,006 |
| B0, walk-forward window | 247 | 247 | -Rs 415 | Rs 4,036 | 37% | 49% | 51% | -Rs 63,037 | Rs 128,902 | -Rs 21,673 | Rs 35,930 |

DAY5K trades: 1678, win 45%, net -Rs 173,993, max DD -Rs 209,831; B0 trades: 9156, net Rs 774,750, max DD -Rs 444,695. By arm: {'LIQ': 215, 'R72': 546, 'R51': 621, 'R52': 296}.

## All arms together, final: '5 trades x Rs 1,000' (DAY5K across arms) vs today's rules

| | sessions | traded | median day | mean day | days >= Rs 5,000 | green | red | worst day | best day | 10th pct | 90th pct |
|---|---|---|---|---|---|---|---|---|---|---|---|
| DAY5K all arms | 133 | 133 | -Rs 651 | -Rs 110 | 13% | 48% | 52% | -Rs 8,185 | Rs 5,022 | -Rs 4,993 | Rs 5,004 |
| today's rules (B0) all arms | 133 | 133 | Rs 41 | Rs 2,353 | 39% | 50% | 50% | -Rs 69,940 | Rs 100,254 | -Rs 23,377 | Rs 29,607 |

DAY5K trades: 665, win 51%, net -Rs 14,673, max DD -Rs 48,895; B0 trades: 3633, net Rs 312,978, max DD -Rs 170,942. By arm: {'LIQ': 76, 'R72': 281, 'R51': 219, 'R52': 89}.

## All arms together, post: '5 trades x Rs 1,000' (DAY5K across arms) vs today's rules

| | sessions | traded | median day | mean day | days >= Rs 5,000 | green | red | worst day | best day | 10th pct | 90th pct |
|---|---|---|---|---|---|---|---|---|---|---|---|
| DAY5K all arms | 4 | 4 | -Rs 3,994 | -Rs 3,558 | 0% | 0% | 100% | -Rs 5,251 | -Rs 993 | -Rs 5,174 | -Rs 1,594 |
| today's rules (B0) all arms | 4 | 4 | -Rs 4,419 | -Rs 3,273 | 25% | 50% | 50% | -Rs 43,216 | Rs 38,962 | -Rs 33,954 | Rs 28,325 |

DAY5K trades: 20, win 15%, net -Rs 14,232, max DD -Rs 14,232; B0 trades: 95, net -Rs 13,092, max DD -Rs 55,558. By arm: {'LIQ': 0, 'R72': 3, 'R51': 9, 'R52': 8}.

## Charges and break-even (typical 1-ITM premium the arms paid in design; lots and charges of 15 Oct 2026)

| index | lot | premium | lot value | entry slippage | round-trip charges | +500: move / 1:1 stop | +1,000: move / 1:1 stop | +1,500: move / 1:1 stop | break-even win with -15% stop (500 / 1,000 / 1,500) |
|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | 30 | Rs 654.3 | Rs 19,629 | Rs 11 | Rs 94 | +3.1% / -1.9% | +5.6% / -4.4% | +8.2% / -7.0% | 86% / 75% / 67% |
| FINNIFTY | 60 | Rs 315.0 | Rs 18,903 | Rs 13 | Rs 92 | +3.2% / -1.9% | +5.9% / -4.6% | +8.5% / -7.2% | 86% / 75% / 66% |
| MIDCPNIFTY | 120 | Rs 215.0 | Rs 25,800 | Rs 19 | Rs 108 | +2.4% / -1.2% | +4.4% / -3.2% | +6.3% / -5.1% | 89% / 80% / 73% |
| NIFTY | 65 | Rs 150.1 | Rs 9,756 | Rs 8 | Rs 70 | +6.0% / -4.1% | +11.1% / -9.2% | +16.2% / -14.4% | 76% / 61% / 51% |
| SENSEX | 20 | Rs 463.0 | Rs 9,259 | Rs 6 | Rs 68 | +6.2% / -4.5% | +11.6% / -9.9% | +17.0% / -15.3% | 75% / 60% / 50% |
