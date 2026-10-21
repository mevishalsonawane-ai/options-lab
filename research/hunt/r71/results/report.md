# R71 report: chart patterns on the index (tables)

Final period 1 Apr - 2026-10-13 (133 sessions), read once at 2026-10-16T13:55:02+00:00. Tests gated: 16 (BH across all). Passed: none.

## Final period, every gated test

| test | setting | final trades | final net | win | avg win / loss | worst day | max DD | green days | trades / month | design net | walk-forward net | twin p | BH q | twin mean (no skill) | corr. with Donchian | same trade as Donchian | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P1-O Inside bar | k=3, XL | 1075 | -Rs 123,667 | 42% | Rs 1,170 / -Rs 1,058 | -Rs 11,375 | Rs 142,459 | 30% | 167.0 | -Rs 299,782 | -Rs 299,782 | 0.7166 | 0.986 | -Rs 97,876 | 0.119 | 31% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P1-RT Inside bar | k=6, XB | 403 | -Rs 43,603 | 39% | Rs 1,278 / -Rs 993 | -Rs 8,929 | Rs 51,685 | 36% | 62.6 | -Rs 74,277 | -Rs 40,963 | 0.8204 | 0.986 | -Rs 14,624 | 0.134 | 45% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P2-O NR-N bar | n=7, XL | 1341 | -Rs 168,820 | 39% | Rs 1,291 / -Rs 1,037 | -Rs 21,758 | Rs 214,246 | 32% | 208.3 | -Rs 650,195 | -Rs 650,195 | 0.5309 | 0.986 | -Rs 168,198 | 0.309 | 19% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P2-RT NR-N bar | n=7, XI | 1286 | -Rs 49,790 | 35% | Rs 1,987 / -Rs 1,144 | -Rs 21,694 | Rs 151,125 | 32% | 199.7 | -Rs 591,344 | -Rs 375,731 | 0.2635 | 0.8432 | -Rs 91,813 | 0.35 | 18% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P3-O Squeeze release | s_min=6, XL | 305 | -Rs 41,281 | 40% | Rs 1,315 / -Rs 1,089 | -Rs 11,394 | Rs 65,810 | 38% | 47.4 | Rs 57,763 | Rs 57,763 | 0.9082 | 0.986 | -Rs 11,771 | 0.286 | 67% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net |
| P3-RT Squeeze release | s_min=12, XB | 152 | -Rs 39,967 | 38% | Rs 1,198 / -Rs 1,164 | -Rs 9,694 | Rs 55,958 | 39% | 23.6 | Rs 63,228 | Rs 59,559 | 0.982 | 0.986 | -Rs 7,829 | 0.22 | 66% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net |
| P4-O Flag | K=2.0, XL | 32 | -Rs 442 | 44% | Rs 1,147 / -Rs 917 | -Rs 2,618 | Rs 8,511 | 46% | 5.0 | -Rs 27,378 | -Rs 27,378 | 0.7944 | 0.986 | Rs 7,264 | -0.063 | 44% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, fewer than 40 trades, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P4-RT Flag | K=2.0, XI | 32 | Rs 5,805 | 53% | Rs 1,527 / -Rs 1,344 | -Rs 3,946 | Rs 8,556 | 57% | 5.0 | -Rs 12,110 | -Rs 3,480 | 0.8224 | 0.986 | Rs 17,798 | -0.049 | 44% | FAIL: twin p >= 0.05, BH q > 0.10, fewer than 40 trades, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P5-O Double bottom / top | T_=0.25, XL | 502 | -Rs 12,091 | 42% | Rs 1,321 / -Rs 1,000 | -Rs 10,553 | Rs 59,040 | 41% | 78.0 | -Rs 94,311 | -Rs 94,311 | 0.2096 | 0.8432 | -Rs 39,205 | 0.049 | 28% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P5-RT Double bottom / top | T_=0.25, XB | 502 | -Rs 20,669 | 42% | Rs 1,301 / -Rs 999 | -Rs 9,030 | Rs 70,370 | 40% | 78.0 | -Rs 89,511 | -Rs 49,081 | 0.2535 | 0.8432 | -Rs 41,962 | 0.032 | 28% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P6-O Heikin-Ashi flip in trend | L=20, XL | 357 | -Rs 79,173 | 44% | Rs 1,243 / -Rs 1,385 | -Rs 17,305 | Rs 88,217 | 43% | 55.4 | -Rs 8,797 | -Rs 8,797 | 0.8084 | 0.986 | -Rs 50,371 | 0.328 | 48% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P6-RT Heikin-Ashi flip in trend | L=50, XL | 323 | -Rs 78,971 | 44% | Rs 1,240 / -Rs 1,395 | -Rs 26,160 | Rs 91,454 | 40% | 50.2 | -Rs 7,772 | -Rs 10,965 | 0.8244 | 0.986 | -Rs 50,143 | 0.344 | 46% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P7-O Three-bar reversal at the day's extreme | start_b=6, XL | 855 | -Rs 167,689 | 38% | Rs 1,085 / -Rs 989 | -Rs 20,918 | Rs 182,990 | 39% | 132.8 | -Rs 321,930 | -Rs 321,930 | 0.002 | 0.016 | -Rs 315,898 | -0.135 | 4% | FAIL: final net <= 0, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P7-RT Three-bar reversal at the day's extreme | start_b=15, XI | 675 | -Rs 83,990 | 35% | Rs 1,738 / -Rs 1,119 | -Rs 18,038 | Rs 102,893 | 43% | 104.8 | -Rs 111,487 | -Rs 196,420 | 0.002 | 0.016 | -Rs 353,702 | -0.023 | 5% | FAIL: final net <= 0, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| P8-O Swing structure (HH + HL) | W=2, XL | 374 | -Rs 40,109 | 45% | Rs 1,260 / -Rs 1,223 | -Rs 13,949 | Rs 56,346 | 41% | 58.1 | Rs 2,320 | Rs 2,320 | 0.8842 | 0.986 | -Rs 3,988 | 0.203 | 41% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net |
| P8-RT Swing structure (HH + HL) | W=2, XI | 372 | -Rs 34,242 | 41% | Rs 1,854 / -Rs 1,437 | -Rs 13,045 | Rs 75,561 | 39% | 57.8 | Rs 104,022 | Rs 60,676 | 0.986 | 0.986 | Rs 41,639 | 0.239 | 41% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net |

## Donchian on the same days (same engine, stores and costs)

| Donchian 20 (reference, not gated) | design trades | design net | final trades | final net | win | avg win / loss | worst day | max DD | green days | trades / month |
|---|---|---|---|---|---|---|---|---|---|---|
| XB -15% / +30% / 30 min | 4518 | Rs 8,281 | 1787 | Rs 55,439 | 44% | Rs 1,566 / -Rs 1,188 | -Rs 23,012 | Rs 92,452 | 47% | 277.5 |
| XL -15% + ladder + L25, 30 min | 4498 | Rs 9,998 | 1781 | Rs 81,845 | 44% | Rs 1,609 / -Rs 1,183 | -Rs 23,012 | Rs 91,626 | 47% | 276.6 |

## Combined with Donchian (final)

| book (final, 1 lot each) | net | max DD | worst day |
|---|---|---|---|
| Donchian XB alone | Rs 55,439 | Rs 92,452 | -Rs 23,012 |
| Donchian + P1-O | -Rs 68,228 | Rs 172,711 | -Rs 23,981 |
| Donchian + P1-RT | Rs 11,836 | Rs 105,606 | -Rs 24,778 |
| Donchian + P2-O | -Rs 113,381 | Rs 227,667 | -Rs 33,885 |
| Donchian + P2-RT | Rs 5,649 | Rs 194,767 | -Rs 33,821 |
| Donchian + P3-O | Rs 14,157 | Rs 112,638 | -Rs 23,012 |
| Donchian + P3-RT | Rs 15,472 | Rs 108,827 | -Rs 23,012 |
| Donchian + P4-O | Rs 54,997 | Rs 90,141 | -Rs 23,012 |
| Donchian + P4-RT | Rs 61,244 | Rs 90,198 | -Rs 23,012 |
| Donchian + P5-O | Rs 43,348 | Rs 102,615 | -Rs 23,049 |
| Donchian + P5-RT | Rs 34,770 | Rs 102,019 | -Rs 23,049 |
| Donchian + P6-O | -Rs 23,734 | Rs 174,183 | -Rs 24,739 |
| Donchian + P6-RT | -Rs 23,532 | Rs 183,525 | -Rs 28,274 |
| Donchian + P7-O | -Rs 112,250 | Rs 178,973 | -Rs 33,045 |
| Donchian + P7-RT | -Rs 28,551 | Rs 148,145 | -Rs 29,696 |
| Donchian + P8-O | Rs 15,330 | Rs 128,340 | -Rs 25,685 |
| Donchian + P8-RT | Rs 21,197 | Rs 111,985 | -Rs 31,842 |

## Walk-forward (design)

| rule | walk-forward picks (4 folds) | walk-forward trades | walk-forward net | final pick |
|---|---|---|---|---|
| P1 Inside bar | 6|XB, 6|XB, 6|XB, 6|XB | 725 | -Rs 40,963 | 6|XB |
| P2 NR-N bar | 4|XB, 4|XB, 7|XI, 7|XI | 3040 | -Rs 375,731 | 7|XI |
| P3 Squeeze release | 12|XI, 12|XB, 12|XB, 12|XB | 278 | Rs 59,559 | 12|XB |
| P4 Flag | 2.0|XB, 2.0|XI, 2.0|XI, 2.0|XI | 70 | -Rs 3,480 | 2.0|XI |
| P5 Double bottom / top | 0.25|XB, 0.25|XB, 0.25|XB, 0.25|XB | 841 | -Rs 49,081 | 0.25|XB |
| P6 Heikin-Ashi flip in trend | 50|XB, 50|XL, 50|XL, 20|XI | 603 | -Rs 10,965 | 50|XL |
| P7 Three-bar reversal at the day's extreme | 15|XI, 6|XI, 6|XI, 15|XI | 1430 | -Rs 196,420 | 15|XI |
| P8 Swing structure (HH + HL) | 2|XI, 2|XI, 2|XI, 2|XI | 767 | Rs 60,676 | 2|XI |

## Every design grid point

| rule | grid point | design trades | design net | win | worst day | max DD | fold nets (Apr-Jun 25, Jul-Sep, Oct-Dec, Jan-Mar 26) |
|---|---|---|---|---|---|---|---|
| P1 | k=3, XB | 2733 | -Rs 293,054 | 40% | -Rs 24,775 | Rs 363,803 | -Rs 30,996 / -Rs 27,327 / -Rs 141,187 / -Rs 5,065 |
| P1 | k=3, XL (original) | 2733 | -Rs 299,782 | 40% | -Rs 24,775 | Rs 377,919 | -Rs 34,608 / -Rs 30,395 / -Rs 139,030 / -Rs 633 |
| P1 | k=3, XI | 2628 | -Rs 362,357 | 40% | -Rs 26,517 | Rs 390,522 | -Rs 9,034 / -Rs 26,780 / -Rs 120,395 / -Rs 27,926 |
| P1 | k=6, XB (final pick) | 1014 | -Rs 74,277 | 42% | -Rs 8,848 | Rs 110,667 | -Rs 37,025 / Rs 3,312 / -Rs 1,900 / -Rs 5,350 |
| P1 | k=6, XL | 1014 | -Rs 86,247 | 42% | -Rs 8,848 | Rs 111,629 | -Rs 27,983 / -Rs 2,631 / -Rs 2,706 / -Rs 5,517 |
| P1 | k=6, XI | 1003 | -Rs 96,715 | 41% | -Rs 15,327 | Rs 135,743 | -Rs 8,639 / Rs 6,835 / Rs 894 / Rs 12,057 |
| P2 | n=7, XB | 3534 | -Rs 626,420 | 40% | -Rs 23,921 | Rs 695,828 | -Rs 134,938 / -Rs 68,934 / -Rs 80,692 / -Rs 86,381 |
| P2 | n=7, XL (original) | 3533 | -Rs 650,195 | 40% | -Rs 23,921 | Rs 715,450 | -Rs 154,321 / -Rs 76,303 / -Rs 78,942 / -Rs 78,776 |
| P2 | n=7, XI (final pick) | 3379 | -Rs 591,344 | 37% | -Rs 22,430 | Rs 629,525 | -Rs 137,409 / -Rs 27,238 / -Rs 46,839 / -Rs 111,699 |
| P2 | n=4, XB | 5013 | -Rs 684,630 | 41% | -Rs 28,762 | Rs 702,921 | -Rs 99,945 / -Rs 117,249 / -Rs 100,900 / -Rs 143,897 |
| P2 | n=4, XL | 5011 | -Rs 722,276 | 41% | -Rs 28,762 | Rs 743,381 | -Rs 132,588 / -Rs 125,372 / -Rs 101,494 / -Rs 114,092 |
| P2 | n=4, XI | 4882 | -Rs 618,285 | 39% | -Rs 32,936 | Rs 660,799 | -Rs 158,149 / -Rs 100,447 / -Rs 73,071 / -Rs 52,531 |
| P3 | s_min=6, XB | 803 | Rs 62,605 | 44% | -Rs 10,165 | Rs 47,778 | Rs 5,347 / Rs 17,592 / Rs 1,599 / Rs 63,040 |
| P3 | s_min=6, XL (original) | 803 | Rs 57,763 | 44% | -Rs 10,165 | Rs 47,194 | -Rs 2,651 / Rs 22,419 / Rs 1,668 / Rs 65,472 |
| P3 | s_min=6, XI | 802 | -Rs 2,209 | 43% | -Rs 10,671 | Rs 63,359 | -Rs 6,490 / Rs 29,823 / -Rs 27,507 / Rs 37,791 |
| P3 | s_min=12, XB (final pick) | 372 | Rs 63,228 | 45% | -Rs 7,511 | Rs 33,866 | Rs 17,291 / Rs 2,798 / Rs 9,453 / Rs 42,265 |
| P3 | s_min=12, XL | 372 | Rs 60,220 | 46% | -Rs 6,892 | Rs 32,493 | Rs 13,868 / Rs 4,155 / Rs 8,344 / Rs 46,588 |
| P3 | s_min=12, XI | 372 | Rs 31,848 | 42% | -Rs 6,623 | Rs 33,053 | Rs 5,042 / Rs 10,899 / -Rs 6,678 / Rs 30,310 |
| P4 | K=2.0, XB | 93 | -Rs 24,519 | 35% | -Rs 7,317 | Rs 24,932 | -Rs 1,945 / -Rs 3,346 / -Rs 785 / -Rs 4,592 |
| P4 | K=2.0, XL (original) | 93 | -Rs 27,378 | 35% | -Rs 7,317 | Rs 27,777 | -Rs 4,444 / -Rs 2,557 / -Rs 799 / -Rs 4,592 |
| P4 | K=2.0, XI (final pick) | 93 | -Rs 12,110 | 44% | -Rs 8,081 | Rs 30,360 | Rs 11,890 / -Rs 3,096 / Rs 2,680 / -Rs 1,119 |
| P4 | K=1.5, XB | 133 | -Rs 36,008 | 37% | -Rs 7,317 | Rs 36,008 | -Rs 3,105 / -Rs 8,934 / Rs 3,749 / -Rs 6,002 |
| P4 | K=1.5, XL | 133 | -Rs 41,972 | 37% | -Rs 7,317 | Rs 41,972 | -Rs 7,187 / -Rs 8,145 / Rs 3,735 / -Rs 6,002 |
| P4 | K=1.5, XI | 133 | -Rs 33,794 | 40% | -Rs 8,081 | Rs 46,147 | Rs 9,642 / -Rs 7,327 / Rs 3,103 / -Rs 667 |
| P5 | T_=0.25, XB (final pick) | 1144 | -Rs 89,511 | 41% | -Rs 13,145 | Rs 106,249 | -Rs 19,719 / Rs 7,199 / -Rs 42,369 / Rs 5,807 |
| P5 | T_=0.25, XL (original) | 1144 | -Rs 94,311 | 41% | -Rs 13,145 | Rs 115,211 | -Rs 17,823 / Rs 5,396 / -Rs 43,616 / Rs 8,929 |
| P5 | T_=0.25, XI | 1125 | -Rs 119,494 | 40% | -Rs 13,634 | Rs 149,689 | -Rs 10,278 / -Rs 13,300 / -Rs 33,188 / Rs 18,960 |
| P5 | T_=0.5, XB | 2113 | -Rs 256,747 | 39% | -Rs 14,625 | Rs 279,088 | Rs 9,677 / -Rs 20,271 / -Rs 108,354 / -Rs 7,118 |
| P5 | T_=0.5, XL | 2113 | -Rs 267,589 | 39% | -Rs 13,799 | Rs 290,873 | Rs 17,981 / -Rs 25,727 / -Rs 108,891 / -Rs 2,475 |
| P5 | T_=0.5, XI | 2060 | -Rs 301,473 | 39% | -Rs 28,705 | Rs 336,163 | Rs 22,112 / -Rs 48,810 / -Rs 86,227 / Rs 26,538 |
| P6 | L=20, XB | 868 | -Rs 35,153 | 44% | -Rs 14,672 | Rs 111,831 | -Rs 7,675 / Rs 17,025 / -Rs 25,571 / Rs 41,641 |
| P6 | L=20, XL (original) | 868 | -Rs 8,797 | 45% | -Rs 14,672 | Rs 100,929 | Rs 3,498 / Rs 20,016 / -Rs 29,222 / Rs 59,312 |
| P6 | L=20, XI | 866 | -Rs 33,728 | 44% | -Rs 15,871 | Rs 115,674 | Rs 6,482 / -Rs 3,084 / -Rs 21,793 / Rs 33,951 |
| P6 | L=50, XB | 797 | -Rs 21,982 | 45% | -Rs 14,067 | Rs 104,711 | -Rs 3,112 / Rs 13,516 / -Rs 49,553 / Rs 61,174 |
| P6 | L=50, XL (final pick) | 797 | -Rs 7,772 | 45% | -Rs 14,067 | Rs 104,363 | Rs 8,422 / Rs 11,845 / -Rs 53,648 / Rs 75,618 |
| P6 | L=50, XI | 795 | -Rs 80,153 | 43% | -Rs 18,033 | Rs 150,531 | Rs 1,706 / -Rs 16,002 / -Rs 49,233 / Rs 50,826 |
| P7 | start_b=6, XB | 2185 | -Rs 321,384 | 41% | -Rs 33,065 | Rs 337,867 | -Rs 27,288 / -Rs 23,413 / -Rs 100,325 / -Rs 144,625 |
| P7 | start_b=6, XL (original) | 2185 | -Rs 321,930 | 41% | -Rs 33,065 | Rs 347,908 | -Rs 49,540 / -Rs 12,016 / -Rs 96,316 / -Rs 137,228 |
| P7 | start_b=6, XI | 2191 | -Rs 148,645 | 38% | -Rs 28,445 | Rs 211,794 | -Rs 13,786 / Rs 13,321 / -Rs 96,066 / -Rs 82,880 |
| P7 | start_b=15, XB | 1718 | -Rs 234,524 | 42% | -Rs 22,086 | Rs 247,281 | -Rs 33,627 / -Rs 28,733 / -Rs 58,187 / -Rs 99,211 |
| P7 | start_b=15, XL | 1718 | -Rs 234,705 | 42% | -Rs 22,086 | Rs 244,781 | -Rs 55,436 / -Rs 11,838 / -Rs 56,191 / -Rs 93,423 |
| P7 | start_b=15, XI (final pick) | 1718 | -Rs 111,487 | 38% | -Rs 28,445 | Rs 188,277 | -Rs 43,672 / Rs 26,503 / -Rs 59,623 / -Rs 70,004 |
| P8 | W=2, XB | 1071 | -Rs 8,599 | 45% | -Rs 18,037 | Rs 92,981 | -Rs 25,777 / -Rs 16,346 / Rs 51,976 / Rs 3,457 |
| P8 | W=2, XL (original) | 1070 | Rs 2,320 | 45% | -Rs 18,037 | Rs 73,520 | -Rs 13,940 / -Rs 13,451 / Rs 49,298 / -Rs 738 |
| P8 | W=2, XI (final pick) | 1049 | Rs 104,022 | 47% | -Rs 23,057 | Rs 88,231 | Rs 13,220 / -Rs 21,473 / Rs 54,963 / Rs 13,966 |
| P8 | W=3, XB | 908 | -Rs 67,605 | 43% | -Rs 12,276 | Rs 114,699 | -Rs 9,108 / -Rs 7,001 / -Rs 9,890 / -Rs 48,044 |
| P8 | W=3, XL | 907 | -Rs 51,776 | 43% | -Rs 12,276 | Rs 106,581 | -Rs 12,277 / Rs 4,484 / -Rs 8,142 / -Rs 48,146 |
| P8 | W=3, XI | 890 | -Rs 15,202 | 45% | -Rs 16,804 | Rs 111,123 | Rs 9,441 / -Rs 3,898 / Rs 717 / -Rs 35,239 |
