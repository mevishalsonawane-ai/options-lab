# R191 portfolio of existing streams: summary for Boss

Written 17 Nov 2026. Money at 1 lot per trade after dated Zerodha charges and slippage (R190's priced trades), option BUYING only, booked on the day each trade closes.

**Outcome: NO - 0 of 8 pre-registered portfolios passed the RELIABLE gate.**

B pick (chosen on era B alone, before eras C and A were run): **none (no variant passed era B)**.

Gate (fixed before any number, PREREG.md section 7): in each era A, B, C: 40+ trades, net > 0, net without the top 5 trades > 0, month-block random-side twin p < 0.05; BH q <= 0.10 with m = 4,770; and in B and C max drawdown < Rs 40,000 and every month > -Rs 13,000.

Era A has Night on NIFTY only (no Liquidity / R72 / BANKNIFTY option data), so in era A every variant is its Night rules on NIFTY. Eras C and A were already seen by R190 at the stream level; these portfolio rules were not.

## All variants at a glance

| variant | era | trades | win % | winning months | net | max DD | worst month | verdict |
|---|---|---|---|---|---|---|---|---|
| P1 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P1 | B | 1597 | 38.5% | 61% | Rs 198,731 | Rs 47,794 | -Rs 25,323 |  |
| P1 | C | 1674 | 43.9% | 84% | Rs 448,019 | Rs 104,864 | -Rs 75,107 |  |
| P2 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P2 | B | 4100 | 40.2% | 47% | Rs 14,216 | Rs 132,420 | -Rs 42,140 |  |
| P2 | C | 4079 | 48.6% | 88% | Rs 1,137,658 | Rs 102,300 | -Rs 72,408 |  |
| P3 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P3 | B | 1435 | 36.2% | 53% | Rs 109,679 | Rs 34,757 | -Rs 18,222 |  |
| P3 | C | 1570 | 43.2% | 80% | Rs 361,110 | Rs 84,372 | -Rs 65,867 |  |
| P4 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P4 | B | 1199 | 34.7% | 44% | Rs 53,502 | Rs 60,100 | -Rs 11,887 |  |
| P4 | C | 1238 | 42.0% | 68% | Rs 322,743 | Rs 57,749 | -Rs 16,178 |  |
| P5 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P5 | B | 1046 | 35.0% | 47% | Rs 49,741 | Rs 59,244 | -Rs 11,887 |  |
| P5 | C | 1045 | 41.6% | 58% | Rs 259,358 | Rs 56,902 | -Rs 13,277 |  |
| P6 | A | 57 | 54.4% | 40% | Rs 10,875 | Rs 12,302 | -Rs 7,647 | not reliable |
| P6 | B | 625 | 36.3% | 46% | -Rs 1,218 | Rs 40,816 | -Rs 9,783 |  |
| P6 | C | 756 | 43.9% | 50% | Rs 176,494 | Rs 41,151 | -Rs 12,019 |  |
| P7 | A | 57 | 54.4% | 40% | Rs 10,875 | Rs 12,302 | -Rs 7,647 | not reliable |
| P7 | B | 1195 | 35.6% | 20% | -Rs 170,900 | Rs 170,900 | -Rs 12,933 |  |
| P7 | C | 1712 | 47.4% | 44% | Rs 573,458 | Rs 86,135 | -Rs 23,561 |  |
| P8 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P8 | B | 1063 | 33.8% | 50% | Rs 14,248 | Rs 72,852 | -Rs 11,887 |  |
| P8 | C | 1152 | 41.4% | 72% | Rs 323,014 | Rs 53,328 | -Rs 12,454 |  |

## P1: Night book + Liquidity, 1 lot each, no caps

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2939 |
| B | 1597 | 38.5% | 61% (22/36) | Rs 198,731 | Rs 1,751 / -Rs 895 | Rs 130,462 | Rs 47,794 | -Rs 25,323 | 0.0155 |
| C | 1674 | 43.9% | 84% (21/25) | Rs 448,019 | Rs 2,315 / -Rs 1,335 | Rs 354,914 | Rs 104,864 | -Rs 75,107 | 0.0040 |

**Verdict: NOT RELIABLE.** BH p 0.235, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.294 >= 0.05; era B: max drawdown Rs 47,794 >= Rs 40k; era B: worst month Rs -25,323 <= -Rs 13k; era C: max drawdown Rs 104,864 >= Rs 40k; era C: worst month Rs -75,107 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 498 | 55.0% | Rs 124,990 | -Rs 25,256 | Rs 34,960 |
| B | Liquidity 15+5 | 1099 | 31.0% | Rs 73,741 | -Rs 13,213 | Rs 51,118 |
| C | Night (R3) book | 351 | 53.0% | Rs 192,726 | -Rs 37,286 | Rs 80,780 |
| C | Liquidity 15+5 | 1323 | 41.5% | Rs 255,293 | -Rs 37,821 | Rs 61,062 |

## P2: Night book + Liquidity + R72 picker, no caps

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2714 |
| B | 4100 | 40.2% | 47% (17/36) | Rs 14,216 | Rs 1,263 / -Rs 845 | -Rs 54,984 | Rs 132,420 | -Rs 42,140 | 0.4673 |
| C | 4079 | 48.6% | 88% (22/25) | Rs 1,137,658 | Rs 2,087 / -Rs 1,430 | Rs 1,044,553 | Rs 102,300 | -Rs 72,408 | 0.0005 |

**Verdict: NOT RELIABLE.** BH p 0.46, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.271 >= 0.05; era B: net without the top 5 trades -54,984 <= 0; era B: twin p 0.467 >= 0.05; era B: max drawdown Rs 132,420 >= Rs 40k; era B: worst month Rs -42,140 <= -Rs 13k; era C: max drawdown Rs 102,300 >= Rs 40k; era C: worst month Rs -72,408 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| A | R72 + R151 picker | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 498 | 55.0% | Rs 124,990 | -Rs 25,256 | Rs 34,960 |
| B | Liquidity 15+5 | 1099 | 31.0% | Rs 73,741 | -Rs 13,213 | Rs 51,118 |
| B | R72 + R151 picker | 2503 | 41.4% | -Rs 184,515 | -Rs 37,319 | Rs 247,894 |
| C | Night (R3) book | 351 | 53.0% | Rs 192,726 | -Rs 37,286 | Rs 80,780 |
| C | Liquidity 15+5 | 1323 | 41.5% | Rs 255,293 | -Rs 37,821 | Rs 61,062 |
| C | R72 + R151 picker | 2405 | 51.9% | Rs 689,639 | -Rs 19,993 | Rs 110,062 |

## P3: one Night a night + Liquidity

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2939 |
| B | 1435 | 36.2% | 53% (19/36) | Rs 109,679 | Rs 1,665 / -Rs 824 | Rs 43,715 | Rs 34,757 | -Rs 18,222 | 0.0670 |
| C | 1570 | 43.2% | 80% (20/25) | Rs 361,110 | Rs 2,196 / -Rs 1,264 | Rs 269,268 | Rs 84,372 | -Rs 65,867 | 0.0035 |

**Verdict: NOT RELIABLE.** BH p 0.237, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.294 >= 0.05; era B: twin p 0.067 >= 0.05; era B: worst month Rs -18,222 <= -Rs 13k; era C: max drawdown Rs 84,372 >= Rs 40k; era C: worst month Rs -65,867 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 336 | 53.0% | Rs 35,938 | -Rs 12,897 | Rs 27,456 |
| B | Liquidity 15+5 | 1099 | 31.0% | Rs 73,741 | -Rs 13,213 | Rs 51,118 |
| C | Night (R3) book | 247 | 52.2% | Rs 105,817 | -Rs 28,046 | Rs 60,288 |
| C | Liquidity 15+5 | 1323 | 41.5% | Rs 255,293 | -Rs 37,821 | Rs 61,062 |

## P4: P3 + month halt at -Rs 8k

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2329 |
| B | 1199 | 34.7% | 44% (16/36) | Rs 53,502 | Rs 1,621 / -Rs 793 | -Rs 12,059 | Rs 60,100 | -Rs 11,887 | 0.1174 |
| C | 1238 | 42.0% | 68% (17/25) | Rs 322,743 | Rs 2,313 / -Rs 1,226 | Rs 230,901 | Rs 57,749 | -Rs 16,178 | 0.0055 |

**Verdict: NOT RELIABLE.** BH p 0.207, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.233 >= 0.05; era B: net without the top 5 trades -12,059 <= 0; era B: twin p 0.117 >= 0.05; era B: max drawdown Rs 60,100 >= Rs 40k; era C: max drawdown Rs 57,749 >= Rs 40k; era C: worst month Rs -16,178 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 274 | 52.2% | Rs 15,695 | -Rs 12,897 | Rs 40,140 |
| B | Liquidity 15+5 | 925 | 29.5% | Rs 37,807 | -Rs 10,751 | Rs 67,137 |
| C | Night (R3) book | 195 | 53.3% | Rs 118,872 | -Rs 16,152 | Rs 35,094 |
| C | Liquidity 15+5 | 1043 | 39.9% | Rs 203,870 | -Rs 18,282 | Rs 44,480 |

## P5: P4 + drawdown brake at -Rs 25k

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2269 |
| B | 1046 | 35.0% | 47% (15/32) | Rs 49,741 | Rs 1,602 / -Rs 789 | -Rs 15,821 | Rs 59,244 | -Rs 11,887 | 0.1204 |
| C | 1045 | 41.6% | 58% (14/24) | Rs 259,358 | Rs 2,302 / -Rs 1,216 | Rs 167,516 | Rs 56,902 | -Rs 13,277 | 0.0050 |

**Verdict: NOT RELIABLE.** BH p 0.199, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.227 >= 0.05; era B: net without the top 5 trades -15,821 <= 0; era B: twin p 0.120 >= 0.05; era B: max drawdown Rs 59,244 >= Rs 40k; era C: max drawdown Rs 56,902 >= Rs 40k; era C: worst month Rs -13,277 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 231 | 52.8% | Rs 13,181 | -Rs 12,897 | Rs 25,426 |
| B | Liquidity 15+5 | 815 | 29.9% | Rs 36,561 | -Rs 10,751 | Rs 68,384 |
| C | Night (R3) book | 153 | 51.0% | Rs 66,456 | -Rs 15,153 | Rs 28,049 |
| C | Liquidity 15+5 | 892 | 40.0% | Rs 192,902 | -Rs 18,282 | Rs 54,685 |

## P6: P4 + equity-curve filter (last 20 > 0)

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 57 | 54.4% | 40% (4/10) | Rs 10,875 | Rs 2,232 / -Rs 2,243 | -Rs 18,149 | Rs 12,302 | -Rs 7,647 | 0.2754 |
| B | 625 | 36.3% | 46% (16/35) | -Rs 1,218 | Rs 1,632 / -Rs 934 | -Rs 55,080 | Rs 40,816 | -Rs 9,783 | 0.3458 |
| C | 756 | 43.9% | 50% (12/24) | Rs 176,494 | Rs 2,215 / -Rs 1,318 | Rs 93,648 | Rs 41,151 | -Rs 12,019 | 0.1104 |

**Verdict: NOT RELIABLE.** BH p 0.352, q 1. Failed: era A: net without the top 5 trades -18,149 <= 0; era A: twin p 0.275 >= 0.05; era B: net -1,218 <= 0; era B: net without the top 5 trades -55,080 <= 0; era B: twin p 0.346 >= 0.05; era B: max drawdown Rs 40,816 >= Rs 40k; era C: twin p 0.110 >= 0.05; era C: max drawdown Rs 41,151 >= Rs 40k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 57 | 54.4% | Rs 10,875 | -Rs 7,647 | Rs 12,302 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 189 | 50.8% | -Rs 13,718 | -Rs 9,398 | Rs 31,555 |
| B | Liquidity 15+5 | 436 | 30.1% | Rs 12,500 | -Rs 8,958 | Rs 35,487 |
| C | Night (R3) book | 158 | 52.5% | Rs 41,921 | -Rs 10,218 | Rs 28,525 |
| C | Liquidity 15+5 | 598 | 41.6% | Rs 134,573 | -Rs 11,818 | Rs 44,671 |

## P7: one Night a night + Liquidity + R72 picker + month halt + equity-curve filter

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 57 | 54.4% | 40% (4/10) | Rs 10,875 | Rs 2,232 / -Rs 2,243 | -Rs 18,149 | Rs 12,302 | -Rs 7,647 | 0.2759 |
| B | 1195 | 35.6% | 20% (7/35) | -Rs 170,900 | Rs 1,184 / -Rs 878 | -Rs 216,123 | Rs 170,900 | -Rs 12,933 | 0.9730 |
| C | 1712 | 47.4% | 44% (11/25) | Rs 573,458 | Rs 2,244 / -Rs 1,383 | Rs 488,889 | Rs 86,135 | -Rs 23,561 | 0.0035 |

**Verdict: NOT RELIABLE.** BH p 0.975, q 1. Failed: era A: net without the top 5 trades -18,149 <= 0; era A: twin p 0.276 >= 0.05; era B: net -170,900 <= 0; era B: net without the top 5 trades -216,123 <= 0; era B: twin p 0.973 >= 0.05; era B: max drawdown Rs 170,900 >= Rs 40k; era C: max drawdown Rs 86,135 >= Rs 40k; era C: worst month Rs -23,561 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 57 | 54.4% | Rs 10,875 | -Rs 7,647 | Rs 12,302 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| A | R72 + R151 picker | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 148 | 48.6% | -Rs 41,383 | -Rs 9,398 | Rs 41,619 |
| B | Liquidity 15+5 | 357 | 27.4% | -Rs 21,746 | -Rs 8,358 | Rs 37,774 |
| B | R72 + R151 picker | 690 | 37.1% | -Rs 107,771 | -Rs 13,896 | Rs 120,536 |
| C | Night (R3) book | 135 | 51.9% | Rs 41,495 | -Rs 13,204 | Rs 25,641 |
| C | Liquidity 15+5 | 584 | 41.4% | Rs 133,361 | -Rs 11,919 | Rs 47,751 |
| C | R72 + R151 picker | 993 | 50.2% | Rs 398,602 | -Rs 21,301 | Rs 61,847 |

## P8: P4 + skip Night / Liquidity on high-VIX days

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2284 |
| B | 1063 | 33.8% | 50% (18/36) | Rs 14,248 | Rs 1,513 / -Rs 751 | -Rs 44,543 | Rs 72,852 | -Rs 11,887 | 0.2189 |
| C | 1152 | 41.4% | 72% (18/25) | Rs 323,014 | Rs 2,349 / -Rs 1,181 | Rs 231,172 | Rs 53,328 | -Rs 12,454 | 0.0020 |

**Verdict: NOT RELIABLE.** BH p 0.221, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.228 >= 0.05; era B: net without the top 5 trades -44,543 <= 0; era B: twin p 0.219 >= 0.05; era B: max drawdown Rs 72,852 >= Rs 40k; era C: max drawdown Rs 53,328 >= Rs 40k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 248 | 51.2% | Rs 9,403 | -Rs 8,072 | Rs 40,140 |
| B | Liquidity 15+5 | 815 | 28.5% | Rs 4,845 | -Rs 9,834 | Rs 59,053 |
| C | Night (R3) book | 182 | 54.4% | Rs 138,807 | -Rs 10,600 | Rs 20,618 |
| C | Liquidity 15+5 | 970 | 39.0% | Rs 184,206 | -Rs 18,282 | Rs 41,655 |
