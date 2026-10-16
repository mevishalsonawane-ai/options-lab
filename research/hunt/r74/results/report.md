# R74 report: five untried families (tables)

Final period 1 Apr - 2026-10-13 (133 sessions), read once at 2026-10-16T16:14:42+00:00. Tests gated: 10 (BH across all). Passed: B2-O.

## Final period, every gated test

| test | setting | final trades | final net | win | avg win / loss | worst day | max DD | green days | trades / month | design net | walk-forward net | twin p | BH q | twin mean (no skill) | corr. with Donchian | same trade as Donchian | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| M1-O Late-day momentum on high-volatility days | k=0.5, XL | 203 | -Rs 42,725 | 34% | Rs 1,629 / -Rs 1,178 | -Rs 11,415 | Rs 94,246 | 30% | 31.5 | Rs 63,683 | Rs 63,683 | 1.0 | 1.0 | Rs 48,026 | -0.041 | 1% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net |
| M1-RT Late-day momentum on high-volatility days | k=0.5, XL | 203 | -Rs 42,725 | 34% | Rs 1,629 / -Rs 1,178 | -Rs 11,415 | Rs 94,246 | 30% | 31.5 | Rs 63,683 | Rs 99,214 | 1.0 | 1.0 | Rs 48,026 | -0.041 | 1% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net |
| V1-O VIX / index divergence, faded | k=0.002, XL | 65 | -Rs 23,860 | 31% | Rs 1,660 / -Rs 1,268 | -Rs 5,679 | Rs 30,160 | 32% | 10.1 | -Rs 25,287 | -Rs 25,287 | 0.521 | 1.0 | -Rs 22,636 | 0.093 | 11% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| V1-RT VIX / index divergence, faded | k=0.002, XI | 65 | -Rs 21,326 | 25% | Rs 2,436 / -Rs 1,231 | -Rs 6,693 | Rs 34,809 | 28% | 10.1 | -Rs 17,513 | -Rs 40,593 | 0.3852 | 1.0 | -Rs 24,848 | 0.145 | 11% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| V2-O VIX-confirmed Donchian break | x=0.0, XL | 1382 | Rs 119,125 | 44% | Rs 1,614 / -Rs 1,097 | -Rs 22,440 | Rs 40,877 | 41% | 214.6 | -Rs 366,813 | -Rs 366,813 | 0.0639 | 0.3195 | Rs 25,593 | 0.585 | 75% | FAIL: twin p >= 0.05, BH q > 0.10, design net <= 0, walk-forward net <= 0 |
| V2-RT VIX-confirmed Donchian break | x=0.01, XL | 770 | Rs 53,786 | 46% | Rs 1,544 / -Rs 1,198 | -Rs 16,149 | Rs 57,874 | 46% | 119.6 | -Rs 69,077 | Rs 41,649 | 0.5928 | 1.0 | Rs 64,184 | 0.346 | 63% | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0 |
| B1-O Stock-breadth thrust (VWAP) | T=0.7, XL | 76 | -Rs 20,507 | 41% | Rs 1,432 / -Rs 1,442 | -Rs 12,545 | Rs 21,530 | 37% | 11.8 | Rs 32,667 | Rs 32,667 | 0.7565 | 1.0 | -Rs 8,797 | -0.132 | 49% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net |
| B1-RT Stock-breadth thrust (VWAP) | T=0.7, XB | 76 | -Rs 20,761 | 41% | Rs 1,424 / -Rs 1,442 | -Rs 12,545 | Rs 21,785 | 37% | 11.8 | Rs 35,776 | Rs 10,204 | 0.7685 | 1.0 | -Rs 9,471 | -0.132 | 49% | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net |
| B2-O Volume-confirmed Donchian break | m=1.5, XL | 369 | Rs 135,342 | 48% | Rs 2,156 / -Rs 1,283 | -Rs 13,774 | Rs 21,640 | 42% | 57.3 | Rs 6,792 | Rs 6,792 | 0.006 | 0.06 | Rs 43,696 | 0.331 | 69% | PASS: all gates |
| B2-RT Volume-confirmed Donchian break | m=2.0, XI | 144 | Rs 28,712 | 43% | Rs 2,585 / -Rs 1,604 | -Rs 11,932 | Rs 32,628 | 43% | 22.4 | Rs 34,067 | Rs 72,715 | 0.8723 | 1.0 | Rs 66,119 | 0.194 | 61% | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net |

## Donchian on the same days (same engine, stores and costs)

| Donchian 20 (reference, not gated) | design trades | design net | final trades | final net | win | avg win / loss | worst day | max DD | green days | trades / month |
|---|---|---|---|---|---|---|---|---|---|---|
| XB -15% / +30% / 30 min | 4518 | Rs 8,281 | 1787 | Rs 55,439 | 44% | Rs 1,566 / -Rs 1,188 | -Rs 23,012 | Rs 92,452 | 47% | 277.5 |
| XL -15% + ladder + L25, 30 min | 4498 | Rs 9,998 | 1781 | Rs 81,845 | 44% | Rs 1,609 / -Rs 1,183 | -Rs 23,012 | Rs 91,626 | 47% | 276.6 |

## Combined with Donchian (final)

| book (final, 1 lot each) | net | max DD | worst day |
|---|---|---|---|
| Donchian XB alone | Rs 55,439 | Rs 92,452 | -Rs 23,012 |
| Donchian + M1-O | Rs 12,714 | Rs 101,101 | -Rs 23,012 |
| Donchian + M1-RT | Rs 12,714 | Rs 101,101 | -Rs 23,012 |
| Donchian + V1-O | Rs 31,579 | Rs 102,414 | -Rs 28,540 |
| Donchian + V1-RT | Rs 34,113 | Rs 100,218 | -Rs 29,705 |
| Donchian + V2-O | Rs 174,564 | Rs 113,624 | -Rs 37,727 |
| Donchian + V2-RT | Rs 109,225 | Rs 140,800 | -Rs 31,173 |
| Donchian + B1-O | Rs 34,932 | Rs 91,020 | -Rs 23,012 |
| Donchian + B1-RT | Rs 34,678 | Rs 91,020 | -Rs 23,012 |
| Donchian + B2-O | Rs 190,781 | Rs 79,230 | -Rs 27,779 |
| Donchian + B2-RT | Rs 84,151 | Rs 99,904 | -Rs 23,012 |

## Walk-forward (design)

| rule | walk-forward picks (4 folds) | walk-forward trades | walk-forward net | final pick |
|---|---|---|---|---|
| M1 Late-day momentum on high-volatility days | 1.0|XL, 1.0|XB, 1.0|XL, 1.0|XL | 359 | Rs 99,214 | 0.5|XL |
| V1 VIX / index divergence, faded | 0.002|XB, 0.002|XI, 0.002|XB, 0.004|XB | 160 | -Rs 40,593 | 0.002|XI |
| V2 VIX-confirmed Donchian break | 0.01|XL, 0.01|XB, 0.01|XL, 0.01|XL | 1153 | Rs 41,649 | 0.01|XL |
| B1 Stock-breadth thrust (VWAP) | 0.7|XB, 0.7|XI, 0.7|XI, 0.7|XI | 220 | Rs 10,204 | 0.7|XB |
| B2 Volume-confirmed Donchian break | 2.0|XI, 2.0|XI, 2.0|XI, 2.0|XI | 329 | Rs 72,715 | 2.0|XI |

## Every design grid point

| rule | grid point | design trades | design net | win | worst day | max DD | fold nets (Apr-Jun 25, Jul-Sep, Oct-Dec, Jan-Mar 26) |
|---|---|---|---|---|---|---|---|
| M1 | k=0.5, XB | 641 | Rs 48,862 | 50% | -Rs 20,155 | Rs 106,651 | -Rs 18,659 / Rs 37,915 / -Rs 30,943 / Rs 128,748 |
| M1 | k=0.5, XL (original) (final pick) | 641 | Rs 63,683 | 50% | -Rs 20,155 | Rs 106,194 | -Rs 20,973 / Rs 40,207 / -Rs 31,880 / Rs 144,144 |
| M1 | k=0.5, XI | 641 | Rs 37,514 | 51% | -Rs 23,999 | Rs 122,298 | -Rs 29,772 / Rs 37,439 / -Rs 35,937 / Rs 143,868 |
| M1 | k=1.0, XB | 469 | Rs 36,154 | 49% | -Rs 20,155 | Rs 85,505 | -Rs 23,550 / Rs 31,743 / -Rs 25,600 / Rs 104,244 |
| M1 | k=1.0, XL | 469 | Rs 53,401 | 49% | -Rs 20,155 | Rs 85,133 | -Rs 25,864 / Rs 34,812 / -Rs 25,333 / Rs 118,668 |
| M1 | k=1.0, XI | 469 | Rs 32,578 | 50% | -Rs 23,999 | Rs 104,137 | -Rs 34,694 / Rs 32,474 / -Rs 26,337 / Rs 119,089 |
| V1 | k=0.002, XB | 253 | -Rs 25,724 | 39% | -Rs 16,208 | Rs 68,225 | -Rs 43,240 / -Rs 8,542 / -Rs 12,245 / Rs 39,964 |
| V1 | k=0.002, XL (original) | 253 | -Rs 25,287 | 40% | -Rs 16,208 | Rs 74,566 | -Rs 46,947 / -Rs 8,542 / -Rs 12,245 / Rs 46,743 |
| V1 | k=0.002, XI (final pick) | 253 | -Rs 17,513 | 30% | -Rs 21,234 | Rs 84,211 | -Rs 38,289 / -Rs 12,081 / -Rs 22,067 / Rs 58,340 |
| V1 | k=0.004, XB | 160 | -Rs 37,348 | 33% | -Rs 13,600 | Rs 65,683 | -Rs 32,394 / -Rs 6,066 / -Rs 7,723 / Rs 26,973 |
| V1 | k=0.004, XL | 160 | -Rs 39,447 | 34% | -Rs 13,600 | Rs 67,782 | -Rs 32,394 / -Rs 6,066 / -Rs 7,723 / Rs 26,973 |
| V1 | k=0.004, XI | 160 | -Rs 41,192 | 28% | -Rs 17,407 | Rs 85,387 | -Rs 36,083 / -Rs 11,021 / -Rs 14,138 / Rs 43,115 |
| V2 | x=0.0, XB | 3476 | -Rs 378,295 | 41% | -Rs 34,425 | Rs 469,117 | -Rs 49,909 / -Rs 20,332 / -Rs 113,401 / Rs 31,550 |
| V2 | x=0.0, XL (original) | 3447 | -Rs 366,813 | 42% | -Rs 34,425 | Rs 452,508 | -Rs 56,766 / -Rs 7,917 / -Rs 108,139 / Rs 30,931 |
| V2 | x=0.0, XI | 3246 | -Rs 492,472 | 42% | -Rs 41,322 | Rs 550,824 | -Rs 52,786 / -Rs 2,991 / -Rs 88,329 / -Rs 1,112 |
| V2 | x=0.01, XB | 1678 | -Rs 100,680 | 43% | -Rs 46,816 | Rs 129,576 | Rs 39,374 / Rs 36,225 / -Rs 81,322 / Rs 24,881 |
| V2 | x=0.01, XL (final pick) | 1646 | -Rs 69,077 | 43% | -Rs 46,816 | Rs 128,040 | Rs 37,994 / Rs 45,267 / -Rs 80,647 / Rs 48,076 |
| V2 | x=0.01, XI | 1553 | -Rs 191,021 | 43% | -Rs 44,494 | Rs 229,003 | Rs 79,563 / Rs 51,531 / -Rs 104,584 / Rs 9,339 |
| B1 | T=0.7, XB (final pick) | 342 | Rs 35,776 | 49% | -Rs 13,124 | Rs 39,137 | Rs 43,801 / Rs 813 / -Rs 11,216 / -Rs 8,157 |
| B1 | T=0.7, XL (original) | 342 | Rs 32,667 | 49% | -Rs 13,124 | Rs 42,422 | Rs 45,024 / -Rs 1,249 / -Rs 10,742 / -Rs 9,884 |
| B1 | T=0.7, XI | 342 | Rs 25,753 | 48% | -Rs 18,385 | Rs 100,492 | Rs 93,696 / Rs 18,513 / -Rs 28,571 / -Rs 23,539 |
| B1 | T=0.8, XB | 39 | -Rs 10,974 | 31% | -Rs 10,549 | Rs 30,388 | Rs 8,949 / -Rs 128 / Rs 0 / Rs 3,220 |
| B1 | T=0.8, XL | 39 | -Rs 7,546 | 31% | -Rs 10,549 | Rs 30,388 | Rs 13,237 / -Rs 128 / Rs 0 / Rs 3,220 |
| B1 | T=0.8, XI | 39 | Rs 29,986 | 51% | -Rs 17,063 | Rs 37,300 | Rs 43,315 / Rs 1,064 / Rs 0 / Rs 11,057 |
| B2 | m=1.5, XB | 1172 | -Rs 36,968 | 45% | -Rs 21,226 | Rs 121,996 | Rs 31,360 / Rs 8,231 / -Rs 41,914 / Rs 70,877 |
| B2 | m=1.5, XL (original) | 1139 | Rs 6,792 | 46% | -Rs 21,226 | Rs 121,163 | Rs 50,595 / Rs 6,869 / -Rs 42,449 / Rs 97,427 |
| B2 | m=1.5, XI | 1087 | -Rs 26,633 | 46% | -Rs 28,537 | Rs 169,737 | Rs 89,605 / Rs 15,749 / -Rs 40,096 / Rs 61,959 |
| B2 | m=2.0, XB | 473 | -Rs 15,052 | 44% | -Rs 21,226 | Rs 74,610 | Rs 38,930 / Rs 406 / -Rs 30,180 / Rs 30,628 |
| B2 | m=2.0, XL | 466 | -Rs 16,329 | 45% | -Rs 21,226 | Rs 75,727 | Rs 39,965 / -Rs 1,024 / -Rs 29,561 / Rs 30,208 |
| B2 | m=2.0, XI (final pick) | 440 | Rs 34,067 | 46% | -Rs 23,614 | Rs 71,565 | Rs 67,172 / -Rs 5,980 / -Rs 22,872 / Rs 34,396 |
