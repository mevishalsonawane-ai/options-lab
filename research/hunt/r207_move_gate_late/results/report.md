# R207 / R208 results: the expected-move gate and the late-day break

Pre-registered in `PREREG.md` before any number. Options: the dhan-data build to 5 Oct 2026, today's rules (V5), 1 lot, dated charges and slippage. Index: 1-minute bars, 8 Oct 2021 - 5 Oct 2026. The option holdout is a SECOND LOOK (PREREG section 0): a rule that clears the gate is not "passed" until new days agree.

**0 of 15 tests clear their gate**.

The final run's design rows equal `design.json`.

## R207 Part 1: does the expected move say anything? (index-only, the arms' signals, descriptive)

Rank correlation of the expected move with the range the index really made in the next 30 minutes, and per third of the expected move (hundredths of a percent of the index).

| signals | era | signals | rank correlation | third | expected | real 30-min range | size of the 15-min move | 15-min move the signal's way | right after 15 min |
|---|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | OLD | 12,709 | 0.625 | low | 18.0 | 22.2 | 7.9 | 0.91 | 49.4% |
| R72 Breadth Donchian | OLD | 12,709 | 0.625 | mid | 27.5 | 31.8 | 11.2 | 0.94 | 51.9% |
| R72 Breadth Donchian | OLD | 12,709 | 0.625 | high | 50.8 | 49.9 | 18.4 | -1.51 | 46.5% |
| R72 Breadth Donchian | RECENT | 9,132 | 0.611 | low | 17.7 | 21.6 | 8.0 | 1.31 | 51.3% |
| R72 Breadth Donchian | RECENT | 9,132 | 0.611 | mid | 26.3 | 29.7 | 10.6 | 1.12 | 52.3% |
| R72 Breadth Donchian | RECENT | 9,132 | 0.611 | high | 46.4 | 45.7 | 16.9 | 0.78 | 51.1% |
| R51 Donchian 20 + Supertrend | OLD | 27,101 | 0.647 | low | 17.1 | 20.4 | 7.4 | 0.49 | 48.6% |
| R51 Donchian 20 + Supertrend | OLD | 27,101 | 0.647 | mid | 26.2 | 29.2 | 10.5 | 0.49 | 49.9% |
| R51 Donchian 20 + Supertrend | OLD | 27,101 | 0.647 | high | 47.7 | 48.9 | 18.1 | -0.69 | 49.0% |
| R51 Donchian 20 + Supertrend | RECENT | 19,102 | 0.641 | low | 17.0 | 19.7 | 7.0 | 0.63 | 49.3% |
| R51 Donchian 20 + Supertrend | RECENT | 19,102 | 0.641 | mid | 25.3 | 27.6 | 9.8 | 0.76 | 50.8% |
| R51 Donchian 20 + Supertrend | RECENT | 19,102 | 0.641 | high | 43.9 | 43.4 | 16.1 | 0.26 | 50.4% |
| Liquidity 15+5 | OLD | 1,598 | 0.635 | low | 20.7 | 27.0 | 9.0 | 0.49 | 50.7% |
| Liquidity 15+5 | OLD | 1,598 | 0.635 | mid | 31.8 | 35.5 | 12.7 | 1.84 | 55.1% |
| Liquidity 15+5 | OLD | 1,598 | 0.635 | high | 67.4 | 87.1 | 32.8 | -1.49 | 47.3% |
| Liquidity 15+5 | RECENT | 1,250 | 0.678 | low | 20.1 | 22.5 | 8.0 | 1.06 | 48.4% |
| Liquidity 15+5 | RECENT | 1,250 | 0.678 | mid | 30.5 | 35.6 | 12.7 | 2.65 | 56.2% |
| Liquidity 15+5 | RECENT | 1,250 | 0.678 | high | 59.0 | 52.6 | 20.1 | 2.52 | 54.9% |

## R207: the gate as a skip on today's rules, holdout 1 Apr - 5 Oct 2026 (a second look)

| arm | rule | trades removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | gate |
|---|---|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | **B: today's rule** | - | 596 | 60.4% | Rs 98,609 | -Rs 1,475 | Rs 36,477 | -Rs 13,690 | - | - |
| R72 Breadth Donchian | G-E33: skip when the expected move is in the lowest third (walk-forward) | 277 | 319 | 65.5% | Rs 76,202 (-Rs 22,407) | -Rs 1,729 | Rs 40,648 (+Rs 4,171) | -Rs 28,078 (-Rs 14,388) | 0.148 | fail |
| R72 Breadth Donchian | G-E50: skip when the expected move is below the median (walk-forward) | 380 | 216 | 68.1% | Rs 58,385 (-Rs 40,224) | -Rs 1,822 | Rs 45,047 (+Rs 8,570) | -Rs 33,735 (-Rs 20,045) | 0.144 | fail |
| R51 Donchian 20 + Supertrend | **B: today's rule** | - | 652 | 60.4% | Rs 43,680 | -Rs 1,429 | Rs 45,825 | -Rs 29,871 | - | - |
| R51 Donchian 20 + Supertrend | G-E33: skip when the expected move is in the lowest third (walk-forward) | 285 | 367 | 64.8% | Rs 46,927 (+Rs 3,247) | -Rs 1,729 | Rs 42,537 (-Rs 3,288) | -Rs 27,701 (+Rs 2,170) | 0.130 | fail |
| R51 Donchian 20 + Supertrend | G-E50: skip when the expected move is below the median (walk-forward) | 377 | 275 | 67.3% | Rs 44,316 (+Rs 636) | -Rs 1,869 | Rs 45,679 (-Rs 146) | -Rs 33,134 (-Rs 3,263) | 0.102 | fail |
| Liquidity 15+5 | **B: today's rule** | - | 330 | 43.9% | Rs 11,087 | -Rs 913 | Rs 59,441 | -Rs 32,670 | - | - |
| Liquidity 15+5 | G-E33: skip when the expected move is in the lowest third (walk-forward) | 153 | 177 | 45.2% | -Rs 17,808 (-Rs 28,895) | -Rs 1,099 | Rs 58,211 (-Rs 1,230) | -Rs 27,254 (+Rs 5,416) | 0.901 | fail |
| Liquidity 15+5 | G-E50: skip when the expected move is below the median (walk-forward) | 196 | 134 | 45.5% | -Rs 7,391 (-Rs 18,478) | -Rs 1,160 | Rs 44,829 (-Rs 14,612) | -Rs 24,805 (+Rs 7,865) | 0.729 | fail |
| Liquidity 15+5 | G-RT85: skip when the range so far is under 85% of the usual range by this time of day | 125 | 205 | 45.9% | Rs 81 (-Rs 11,006) | -Rs 1,056 | Rs 51,204 (-Rs 8,237) | -Rs 20,629 (+Rs 12,041) | 0.648 | fail |

## R207: the gate as a skip on today's rules, design 21 Nov 2024 - 31 Mar 2026

| arm | rule | trades removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | gate |
|---|---|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | **B: today's rule** | - | 1,561 | 65.7% | Rs 232,769 | -Rs 1,629 | Rs 100,582 | -Rs 26,420 | - | - |
| R72 Breadth Donchian | G-E33: skip when the expected move is in the lowest third (walk-forward) | 449 | 1,112 | 66.8% | Rs 162,428 (-Rs 70,341) | -Rs 1,851 | Rs 91,441 (-Rs 9,141) | -Rs 23,044 (+Rs 3,376) | 0.555 | - |
| R72 Breadth Donchian | G-E50: skip when the expected move is below the median (walk-forward) | 674 | 887 | 67.5% | Rs 152,835 (-Rs 79,934) | -Rs 1,905 | Rs 56,786 (-Rs 43,796) | -Rs 16,941 (+Rs 9,479) | 0.257 | - |
| R51 Donchian 20 + Supertrend | **B: today's rule** | - | 2,170 | 62.6% | Rs 147,686 | -Rs 1,579 | Rs 85,937 | -Rs 36,762 | - | - |
| R51 Donchian 20 + Supertrend | G-E33: skip when the expected move is in the lowest third (walk-forward) | 673 | 1,497 | 65.7% | Rs 182,059 (+Rs 34,373) | -Rs 1,803 | Rs 42,474 (-Rs 43,463) | -Rs 30,141 (+Rs 6,621) | 0.016 | - |
| R51 Donchian 20 + Supertrend | G-E50: skip when the expected move is below the median (walk-forward) | 993 | 1,177 | 66.9% | Rs 167,537 (+Rs 19,851) | -Rs 1,931 | Rs 36,780 (-Rs 49,157) | -Rs 20,083 (+Rs 16,679) | 0.018 | - |
| Liquidity 15+5 | **B: today's rule** | - | 878 | 50.9% | Rs 231,580 | -Rs 909 | Rs 25,771 | -Rs 9,714 | - | - |
| Liquidity 15+5 | G-E33: skip when the expected move is in the lowest third (walk-forward) | 261 | 617 | 53.2% | Rs 233,738 (+Rs 2,158) | -Rs 1,022 | Rs 25,559 (-Rs 212) | -Rs 3,066 (+Rs 6,648) | 0.010 | - |
| Liquidity 15+5 | G-E50: skip when the expected move is below the median (walk-forward) | 362 | 516 | 54.8% | Rs 230,341 (-Rs 1,239) | -Rs 1,071 | Rs 23,682 (-Rs 2,089) | -Rs 6,391 (+Rs 3,323) | 0.001 | - |
| Liquidity 15+5 | G-RT85: skip when the range so far is under 85% of the usual range by this time of day | 270 | 608 | 53.0% | Rs 211,293 (-Rs 20,287) | -Rs 981 | Rs 16,020 (-Rs 9,751) | -Rs 6,615 (+Rs 3,099) | 0.045 | - |

## R207: the gate as a skip on today's rules, last year 24 Nov 2025 - 5 Oct 2026 (descriptive)

| arm | rule | trades removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | gate |
|---|---|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | **B: today's rule** | - | 938 | 64.8% | Rs 195,249 | -Rs 1,600 | Rs 42,286 | -Rs 13,690 | - | - |
| R72 Breadth Donchian | G-E33: skip when the expected move is in the lowest third (walk-forward) | 310 | 628 | 68.8% | Rs 158,221 (-Rs 37,028) | -Rs 1,842 | Rs 46,457 (+Rs 4,171) | -Rs 28,078 (-Rs 14,388) | n/a | - |
| R72 Breadth Donchian | G-E50: skip when the expected move is below the median (walk-forward) | 447 | 491 | 70.5% | Rs 134,075 (-Rs 61,174) | -Rs 1,955 | Rs 50,857 (+Rs 8,571) | -Rs 33,735 (-Rs 20,045) | n/a | - |
| R51 Donchian 20 + Supertrend | **B: today's rule** | - | 1,165 | 63.1% | Rs 143,844 | -Rs 1,519 | Rs 54,101 | -Rs 29,871 | - | - |
| R51 Donchian 20 + Supertrend | G-E33: skip when the expected move is in the lowest third (walk-forward) | 371 | 794 | 66.8% | Rs 140,358 (-Rs 3,486) | -Rs 1,782 | Rs 50,813 (-Rs 3,288) | -Rs 27,701 (+Rs 2,170) | n/a | - |
| R51 Donchian 20 + Supertrend | G-E50: skip when the expected move is below the median (walk-forward) | 539 | 626 | 68.8% | Rs 143,550 (-Rs 294) | -Rs 1,934 | Rs 51,444 (-Rs 2,657) | -Rs 33,134 (-Rs 3,263) | n/a | - |
| Liquidity 15+5 | **B: today's rule** | - | 590 | 44.4% | Rs 106,212 | -Rs 928 | Rs 60,281 | -Rs 32,670 | - | - |
| Liquidity 15+5 | G-E33: skip when the expected move is in the lowest third (walk-forward) | 205 | 385 | 47.0% | Rs 81,694 (-Rs 24,518) | -Rs 1,073 | Rs 59,051 (-Rs 1,230) | -Rs 27,254 (+Rs 5,416) | n/a | - |
| Liquidity 15+5 | G-E50: skip when the expected move is below the median (walk-forward) | 283 | 307 | 49.2% | Rs 101,418 (-Rs 4,794) | -Rs 1,154 | Rs 46,345 (-Rs 13,936) | -Rs 24,805 (+Rs 7,865) | n/a | - |
| Liquidity 15+5 | G-RT85: skip when the range so far is under 85% of the usual range by this time of day | 201 | 389 | 46.8% | Rs 85,258 (-Rs 20,954) | -Rs 1,032 | Rs 52,673 (-Rs 7,608) | -Rs 20,629 (+Rs 12,041) | n/a | - |

## R207: the gate as a skip on today's rules, total 21 Nov 2024 - 5 Oct 2026

| arm | rule | trades removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p | gate |
|---|---|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | **B: today's rule** | - | 2,157 | 64.3% | Rs 331,379 | -Rs 1,582 | Rs 100,582 | -Rs 26,420 | - | - |
| R72 Breadth Donchian | G-E33: skip when the expected move is in the lowest third (walk-forward) | 726 | 1,431 | 66.5% | Rs 238,629 (-Rs 92,750) | -Rs 1,823 | Rs 91,441 (-Rs 9,141) | -Rs 28,078 (-Rs 1,658) | n/a | - |
| R72 Breadth Donchian | G-E50: skip when the expected move is below the median (walk-forward) | 1,054 | 1,103 | 67.6% | Rs 211,220 (-Rs 120,159) | -Rs 1,889 | Rs 56,786 (-Rs 43,796) | -Rs 33,735 (-Rs 7,315) | n/a | - |
| R51 Donchian 20 + Supertrend | **B: today's rule** | - | 2,822 | 62.1% | Rs 191,366 | -Rs 1,543 | Rs 85,937 | -Rs 36,762 | - | - |
| R51 Donchian 20 + Supertrend | G-E33: skip when the expected move is in the lowest third (walk-forward) | 958 | 1,864 | 65.5% | Rs 228,986 (+Rs 37,620) | -Rs 1,788 | Rs 50,813 (-Rs 35,124) | -Rs 30,141 (+Rs 6,621) | n/a | - |
| R51 Donchian 20 + Supertrend | G-E50: skip when the expected move is below the median (walk-forward) | 1,370 | 1,452 | 66.9% | Rs 211,853 (+Rs 20,487) | -Rs 1,919 | Rs 51,444 (-Rs 34,493) | -Rs 33,134 (+Rs 3,628) | n/a | - |
| Liquidity 15+5 | **B: today's rule** | - | 1,208 | 49.0% | Rs 242,666 | -Rs 910 | Rs 60,281 | -Rs 32,670 | - | - |
| Liquidity 15+5 | G-E33: skip when the expected move is in the lowest third (walk-forward) | 414 | 794 | 51.4% | Rs 215,930 (-Rs 26,736) | -Rs 1,041 | Rs 59,051 (-Rs 1,230) | -Rs 27,254 (+Rs 5,416) | n/a | - |
| Liquidity 15+5 | G-E50: skip when the expected move is below the median (walk-forward) | 558 | 650 | 52.9% | Rs 222,950 (-Rs 19,716) | -Rs 1,092 | Rs 46,345 (-Rs 13,936) | -Rs 24,805 (+Rs 7,865) | n/a | - |
| Liquidity 15+5 | G-RT85: skip when the range so far is under 85% of the usual range by this time of day | 395 | 813 | 51.2% | Rs 211,373 (-Rs 31,293) | -Rs 1,002 | Rs 52,673 (-Rs 7,608) | -Rs 20,629 (+Rs 12,041) | n/a | - |

Why each one fails or clears:

- **R72 Breadth Donchian, G-E33** (fail): design: net 162,428 not better than B's 232,769; holdout: net 76,202 not better than B's 98,609; holdout: max drawdown 40,648 above B's 36,477; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.1484 >= 0.05; BH q 1 > 0.1. The removed trades had made Rs 70,342 in design and Rs 22,408 in the holdout. BH q 1 (m = 4,904; within these 15: 0.444); design twin p 0.555.
- **R51 Donchian 20 + Supertrend, G-E33** (fail): walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.1304 >= 0.05; BH q 1 > 0.1. The removed trades had made -Rs 34,374 in design and -Rs 3,246 in the holdout. BH q 1 (m = 4,904; within these 15: 0.444); design twin p 0.016.
- **Liquidity 15+5, G-E33** (fail): holdout: net -17,808 not better than B's 11,087; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.9011 >= 0.05; BH q 1 > 0.1. The removed trades had made -Rs 2,158 in design and Rs 28,895 in the holdout. BH q 1 (m = 4,904; within these 15: 0.976); design twin p 0.010.
- **R72 Breadth Donchian, G-E50** (fail): design: net 152,835 not better than B's 232,769; holdout: net 58,385 not better than B's 98,609; holdout: max drawdown 45,047 above B's 36,477; walk-forward: better in 0 of 4 design quarters < 3; holdout twin p 0.1439 >= 0.05; BH q 1 > 0.1. The removed trades had made Rs 79,934 in design and Rs 40,224 in the holdout. BH q 1 (m = 4,904; within these 15: 0.444); design twin p 0.257.
- **R51 Donchian 20 + Supertrend, G-E50** (fail): walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.1024 >= 0.05; BH q 1 > 0.1. The removed trades had made -Rs 19,851 in design and -Rs 636 in the holdout. BH q 1 (m = 4,904; within these 15: 0.444); design twin p 0.018.
- **Liquidity 15+5, G-E50** (fail): design: net 230,341 not better than B's 231,580; holdout: net -7,391 not better than B's 11,087; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.7291 >= 0.05; BH q 1 > 0.1. The removed trades had made Rs 1,239 in design and Rs 18,477 in the holdout. BH q 1 (m = 4,904; within these 15: 0.976); design twin p 0.001.
- **Liquidity 15+5, G-RT85** (fail): design: net 211,293 not better than B's 231,580; holdout: net 81 not better than B's 11,087; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.6477 >= 0.05; BH q 1 > 0.1. The removed trades had made Rs 20,287 in design and Rs 11,006 in the holdout. BH q 1 (m = 4,904; within these 15: 0.976); design twin p 0.045.

## R208 3a: late breaks (15:00 - 15:10) on the index, fresh days 1 Nov 2021 - 31 Mar 2022 (read once)

| id | signals | late signals (days) | right after 15 min (95% interval) | bootstrap p | earlier signals right | average move at 15 min | right to the 15:25 close | verdict |
|---|---|---|---|---|---|---|---|---|
| L-I51 | R51 Donchian 20 + Supertrend | 218 (50) | 58.3% (45.6 - 70.5%) | 0.102 | 49.8% of 4,439 | 4.4 hundredths of a percent | 57.8% | does not hold |
| L-I72 | R72 Breadth Donchian | 123 (23) | 56.9% (38.9 - 73.4%) | 0.231 | 50.3% of 2,612 | 4.85 hundredths of a percent | 61.0% | does not hold |

## R208 3b: the late-day break as a standalone option rule (1 lot, real costs)

| cell | window | trades | win % | net | average win / loss | max drawdown | worst month | bootstrap p (mean day > 0) | gate |
|---|---|---|---|---|---|---|---|---|---|
| LATE R72 Breadth Donchian, square-off 15:15 | design 21 Nov 2024 - 31 Mar 2026 | 278 | 47.5% | -Rs 21,278 | Rs 658 / -Rs 741 | Rs 36,739 | -Rs 18,730 | 0.786 | - |
| LATE R72 Breadth Donchian, square-off 15:15 | holdout 1 Apr - 5 Oct 2026 (a second look) | 101 | 33.7% | -Rs 26,543 | Rs 573 / -Rs 687 | Rs 32,137 | -Rs 16,757 | 0.970 | fail |
| LATE R72 Breadth Donchian, square-off 15:15 | last year 24 Nov 2025 - 5 Oct 2026 (descriptive) | 185 | 42.7% | -Rs 33,182 | Rs 602 / -Rs 762 | Rs 45,905 | -Rs 18,730 | n/a | - |
| LATE R72 Breadth Donchian, square-off 15:20 | design 21 Nov 2024 - 31 Mar 2026 | 278 | 45.0% | -Rs 49,654 | Rs 764 / -Rs 949 | Rs 49,654 | -Rs 25,003 | 0.939 | - |
| LATE R72 Breadth Donchian, square-off 15:20 | holdout 1 Apr - 5 Oct 2026 (a second look) | 101 | 33.7% | -Rs 34,172 | Rs 790 / -Rs 911 | Rs 35,328 | -Rs 15,929 | 0.978 | fail |
| LATE R72 Breadth Donchian, square-off 15:20 | last year 24 Nov 2025 - 5 Oct 2026 (descriptive) | 185 | 40.5% | -Rs 48,837 | Rs 803 / -Rs 991 | Rs 62,628 | -Rs 25,003 | n/a | - |
| LATE R72 Breadth Donchian, square-off 15:25 | design 21 Nov 2024 - 31 Mar 2026 | 278 | 48.6% | -Rs 36,830 | Rs 779 / -Rs 993 | Rs 42,218 | -Rs 25,470 | 0.867 | - |
| LATE R72 Breadth Donchian, square-off 15:25 | holdout 1 Apr - 5 Oct 2026 (a second look) | 101 | 45.5% | -Rs 24,565 | Rs 767 / -Rs 1,088 | Rs 25,568 | -Rs 10,573 | 0.901 | fail |
| LATE R72 Breadth Donchian, square-off 15:25 | last year 24 Nov 2025 - 5 Oct 2026 (descriptive) | 185 | 49.2% | -Rs 38,642 | Rs 769 / -Rs 1,156 | Rs 51,132 | -Rs 25,470 | n/a | - |
| LATE R51 Donchian 20 + Supertrend, square-off 15:15 | design 21 Nov 2024 - 31 Mar 2026 | 520 | 43.5% | -Rs 64,179 | Rs 715 / -Rs 768 | Rs 75,479 | -Rs 27,260 | 0.953 | - |
| LATE R51 Donchian 20 + Supertrend, square-off 15:15 | holdout 1 Apr - 5 Oct 2026 (a second look) | 183 | 37.7% | -Rs 23,563 | Rs 736 / -Rs 652 | Rs 37,350 | -Rs 8,861 | 0.904 | fail |
| LATE R51 Donchian 20 + Supertrend, square-off 15:15 | last year 24 Nov 2025 - 5 Oct 2026 (descriptive) | 329 | 41.6% | -Rs 45,426 | Rs 736 / -Rs 762 | Rs 50,823 | -Rs 27,260 | n/a | - |
| LATE R51 Donchian 20 + Supertrend, square-off 15:20 | design 21 Nov 2024 - 31 Mar 2026 | 520 | 42.3% | -Rs 94,087 | Rs 792 / -Rs 894 | Rs 94,087 | -Rs 35,189 | 0.993 | - |
| LATE R51 Donchian 20 + Supertrend, square-off 15:20 | holdout 1 Apr - 5 Oct 2026 (a second look) | 183 | 41.5% | -Rs 27,492 | Rs 855 / -Rs 864 | Rs 42,617 | -Rs 9,293 | 0.909 | fail |
| LATE R51 Donchian 20 + Supertrend, square-off 15:20 | last year 24 Nov 2025 - 5 Oct 2026 (descriptive) | 329 | 42.0% | -Rs 65,472 | Rs 857 / -Rs 962 | Rs 67,858 | -Rs 35,189 | n/a | - |
| LATE R51 Donchian 20 + Supertrend, square-off 15:25 | design 21 Nov 2024 - 31 Mar 2026 | 520 | 45.0% | -Rs 69,019 | Rs 849 / -Rs 936 | Rs 69,019 | -Rs 33,134 | 0.954 | - |
| LATE R51 Donchian 20 + Supertrend, square-off 15:25 | holdout 1 Apr - 5 Oct 2026 (a second look) | 183 | 48.1% | -Rs 16,013 | Rs 833 / -Rs 940 | Rs 30,170 | -Rs 7,799 | 0.772 | fail |
| LATE R51 Donchian 20 + Supertrend, square-off 15:25 | last year 24 Nov 2025 - 5 Oct 2026 (descriptive) | 329 | 47.4% | -Rs 49,206 | Rs 843 / -Rs 1,045 | Rs 51,570 | -Rs 33,134 | n/a | - |

Today's arms' own trades with a late entry (after the picker and the day caps; descriptive): R72 Breadth Donchian design 42 trades / Rs 24,156, holdout 9 trades / -Rs 2,890, total 51 trades / Rs 21,266, last_year 13 trades / -Rs 878; R51 Donchian 20 + Supertrend design 14 trades / Rs 2,568, holdout 0 trades / Rs 0, total 14 trades / Rs 2,568, last_year 4 trades / -Rs 2,625.
