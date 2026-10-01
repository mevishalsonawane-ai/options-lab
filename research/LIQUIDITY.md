## Liquidity Swings (pivot 20, full range) + Liquidity Pools (2 contacts, 5 bars apart, 10 confirmation bars): buy on the break, sell at the next liquidity (research/liquidity_break.py)

Levels: indicator/liquidity.py, written from LuxAlgo's published descriptions (their source is theirs and could not be fetched here). BUY the ATM CE on a close above a high liquidity level, the ATM PE on a close below a low one; out when price touches the next liquidity level in the trade's direction, when a new level forms on that side, optionally on a close back through the broken level, else 15:10 (or held overnight on the 1-hour chart while the same contract trades). 1 lot of 30, real minute prices, 0.5 slippage a side, Rs 40 a round trip.

### 2025-02-17 .. 2026-02-23 (249 days)

| timeframe, levels, stop | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| 1-min, swing, no stop | 1688 (6.8/day) | 48% | -1.3 | Rs -74 | -3.31 | Rs -125,302 | -64,274 / -61,027 | 3/13 |
| 1-min, swing, stop on failed break | 1729 (6.9/day) | 33% | +0.1 | Rs -55 | -3.68 | Rs -94,318 | -50,890 / -43,428 | 3/13 |
| 1-min, pool, no stop | 2826 (11.3/day) | 45% | -3.8 | Rs -107 | -6.63 | Rs -303,716 | -174,697 / -129,019 | 0/13 |
| 1-min, pool, stop on failed break | 3203 (12.9/day) | 30% | -1.6 | Rs -82 | -8.96 | Rs -262,952 | -146,714 / -116,238 | 0/13 |
| 1-min, either, no stop | 3556 (14.3/day) | 46% | -2.6 | Rs -91 | -6.19 | Rs -324,777 | -179,654 / -145,123 | 0/13 |
| 1-min, either, stop on failed break | 4089 (16.4/day) | 31% | -0.9 | Rs -72 | -8.43 | Rs -296,012 | -159,623 / -136,389 | 0/13 |
| 1-min, both, no stop | 1622 (6.5/day) | 47% | -2.7 | Rs -92 | -4.74 | Rs -148,530 | -82,436 / -66,094 | 1/13 |
| 1-min, both, stop on failed break | 1674 (6.7/day) | 33% | -1.0 | Rs -67 | -5.78 | Rs -112,738 | -60,820 / -51,919 | 1/13 |
| 3-min, swing, no stop | 559 (2.2/day) | 51% | +3.4 | Rs -21 | -0.31 | Rs -11,618 | +3,248 / -14,866 | 6/13 |
| 3-min, swing, stop on failed break | 571 (2.3/day) | 39% | +2.3 | Rs -19 | -0.41 | Rs -10,768 | +1,129 / -11,897 | 4/13 |
| 3-min, pool, no stop | 1013 (4.1/day) | 53% | +2.6 | Rs -41 | -0.97 | Rs -41,512 | -16,921 / -24,591 | 3/13 |
| 3-min, pool, stop on failed break | 1105 (4.4/day) | 37% | -1.0 | Rs -59 | -2.36 | Rs -65,531 | -27,058 / -38,473 | 3/13 |
| 3-min, either, no stop | 1254 (5.0/day) | 53% | +5.4 | Rs -4 | -0.09 | Rs -4,484 | +11,995 / -16,479 | 4/13 |
| 3-min, either, stop on failed break | 1386 (5.6/day) | 37% | +0.8 | Rs -38 | -1.50 | Rs -52,201 | -23,598 / -28,604 | 3/13 |
| 3-min, both, no stop | 593 (2.4/day) | 56% | +1.3 | Rs -38 | -0.76 | Rs -22,403 | -17,004 / -5,399 | 6/13 |
| 3-min, both, stop on failed break | 608 (2.4/day) | 43% | -1.6 | Rs -58 | -1.90 | Rs -35,171 | -19,137 / -16,034 | 5/13 |
| 5-min, swing, no stop | 387 (1.6/day) | 55% | +7.6 | Rs -12 | -0.13 | Rs -4,492 | -6,824 / +2,331 | 5/13 |
| 5-min, swing, stop on failed break | 393 (1.6/day) | 40% | -1.5 | Rs -107 | -1.80 | Rs -42,028 | -31,004 / -11,024 | 3/13 |
| 5-min, pool, no stop | 663 (2.7/day) | 56% | +3.9 | Rs -44 | -0.72 | Rs -29,255 | -19,932 / -9,322 | 5/13 |
| 5-min, pool, stop on failed break | 713 (2.9/day) | 41% | -0.1 | Rs -64 | -1.66 | Rs -45,628 | -53,390 / +7,763 | 3/13 |
| 5-min, either, no stop | 815 (3.3/day) | 55% | +3.7 | Rs -52 | -0.93 | Rs -42,254 | -29,372 / -12,882 | 4/13 |
| 5-min, either, stop on failed break | 882 (3.5/day) | 39% | -3.2 | Rs -111 | -3.25 | Rs -97,490 | -84,022 / -13,467 | 3/13 |
| 5-min, both, no stop | 401 (1.6/day) | 60% | +9.8 | Rs +41 | 0.55 | Rs +16,546 | +4,336 / +12,210 | 8/13 |
| 5-min, both, stop on failed break | 411 (1.7/day) | 46% | +5.7 | Rs +8 | 0.16 | Rs +3,425 | -12,452 / +15,877 | 9/13 |
| 15-min, swing, no stop | 131 (0.5/day) | 57% | +12.7 | Rs -69 | -0.32 | Rs -8,980 | +11,211 / -20,191 | 7/13 |
| 15-min, swing, stop on failed break | 132 (0.5/day) | 55% | +20.1 | Rs +132 | 0.75 | Rs +17,461 | +19,294 / -1,833 | 8/13 |
| 15-min, pool, no stop | 231 (0.9/day) | 62% | +10.0 | Rs +19 | 0.13 | Rs +4,368 | +12,113 / -7,745 | 7/13 |
| 15-min, pool, stop on failed break | 243 (1.0/day) | 51% | +4.0 | Rs -8 | -0.07 | Rs -1,875 | +13,731 / -15,607 | 4/13 |
| 15-min, either, no stop | 283 (1.1/day) | 59% | +6.7 | Rs -62 | -0.46 | Rs -17,563 | +641 / -18,205 | 8/13 |
| 15-min, either, stop on failed break | 297 (1.2/day) | 50% | +4.3 | Rs -22 | -0.21 | Rs -6,386 | +6,684 / -13,070 | 6/13 |
| 15-min, both, no stop | 132 (0.5/day) | 67% | +17.0 | Rs +137 | 0.69 | Rs +18,058 | +16,090 / +1,968 | 7/13 |
| 15-min, both, stop on failed break | 134 (0.5/day) | 62% | +19.5 | Rs +211 | 1.47 | Rs +28,285 | +31,695 / -3,410 | 5/13 |

- 1-min: 3102 swing levels, 5322 pools; median bars from a level forming to its break 45
- 3-min: 1039 swing levels, 1933 pools; median bars from a level forming to its break 44
- 5-min: 615 swing levels, 1141 pools; median bars from a level forming to its break 41
- 5-min, either, no stop: exits next liquidity 57%, new liquidity 34%, 15:10 9%; median hold 34 min
- 15-min: 206 swing levels, 424 pools; median bars from a level forming to its break 38
|---|---|---|---|---|---|---|---|---|
| 60-min, swing, no stop | 36 (0.1/day) | 42% | -67.8 | Rs -1,247 | -3.04 | Rs -44,892 | -17,643 / -27,249 | 2/12 |
| 60-min, swing, stop on failed break | 36 (0.1/day) | 39% | -45.7 | Rs -909 | -2.94 | Rs -32,725 | -15,902 / -16,824 | 2/12 |
| 60-min, swing, no stop, held overnight | 36 (0.1/day) | 42% | -21.6 | Rs -1,213 | -0.83 | Rs -43,678 | -6,210 / -37,468 | 3/12 |
| 60-min, swing, stop on failed break, held overnight | 36 (0.1/day) | 36% | -15.5 | Rs -372 | -0.28 | Rs -13,398 | +2,103 / -15,501 | 2/12 |
| 60-min, pool, no stop | 73 (0.3/day) | 45% | -23.2 | Rs -687 | -2.35 | Rs -50,119 | -27,064 / -23,055 | 2/13 |
| 60-min, pool, stop on failed break | 73 (0.3/day) | 36% | -30.9 | Rs -651 | -3.02 | Rs -47,518 | -24,541 / -22,977 | 2/13 |
| 60-min, pool, no stop, held overnight | 68 (0.3/day) | 51% | +17.6 | Rs -239 | -0.24 | Rs -16,225 | +6,084 / -22,309 | 5/13 |
| 60-min, pool, stop on failed break, held overnight | 72 (0.3/day) | 33% | -3.2 | Rs -33 | -0.04 | Rs -2,382 | +9,721 / -12,102 | 5/13 |
| 60-min, either, no stop | 83 (0.3/day) | 43% | -30.5 | Rs -823 | -3.04 | Rs -68,344 | -35,485 / -32,858 | 2/13 |
| 60-min, either, stop on failed break | 83 (0.3/day) | 36% | -29.4 | Rs -661 | -3.36 | Rs -54,828 | -27,696 / -27,133 | 2/13 |
| 60-min, either, no stop, held overnight | 77 (0.3/day) | 49% | +5.9 | Rs -639 | -0.72 | Rs -49,202 | -5,142 / -44,060 | 5/13 |
| 60-min, either, stop on failed break, held overnight | 81 (0.3/day) | 37% | +1.1 | Rs -59 | -0.08 | Rs -4,782 | +8,257 / -13,038 | 4/13 |
| 60-min, both, no stop | 39 (0.2/day) | 44% | -61.4 | Rs -1,231 | -2.88 | Rs -48,016 | -24,215 / -23,801 | 2/11 |
| 60-min, both, stop on failed break | 39 (0.2/day) | 36% | -54.7 | Rs -1,015 | -3.47 | Rs -39,579 | -21,602 / -17,977 | 2/11 |
| 60-min, both, no stop, held overnight | 39 (0.2/day) | 46% | -26.9 | Rs -739 | -0.55 | Rs -28,840 | -12,810 / -16,030 | 3/11 |
| 60-min, both, stop on failed break, held overnight | 39 (0.2/day) | 28% | -37.9 | Rs -614 | -0.50 | Rs -23,955 | -5,348 / -18,607 | 2/11 |

- 60-min: 59 swing levels, 121 pools; median bars from a level forming to its break 28
### 2024-02-13 .. 2025-02-14 (249 days)

| timeframe, levels, stop | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| 1-min, swing, no stop | 1686 (6.8/day) | 47% | -0.8 | Rs -86 | -2.29 | Rs -145,176 | -86,936 / -58,240 | 3/13 |
| 1-min, swing, stop on failed break | 1723 (6.9/day) | 32% | +0.5 | Rs -67 | -2.81 | Rs -115,558 | -55,169 / -60,389 | 3/13 |
| 1-min, pool, no stop | 2762 (11.1/day) | 47% | -4.0 | Rs -111 | -4.29 | Rs -306,650 | -181,701 / -124,949 | 1/13 |
| 1-min, pool, stop on failed break | 3078 (12.4/day) | 31% | -1.9 | Rs -85 | -5.35 | Rs -260,235 | -145,360 / -114,875 | 1/13 |
| 1-min, either, no stop | 3463 (13.9/day) | 47% | -3.2 | Rs -108 | -4.59 | Rs -373,082 | -208,565 / -164,517 | 1/13 |
| 1-min, either, stop on failed break | 3919 (15.7/day) | 31% | -1.3 | Rs -82 | -5.93 | Rs -320,210 | -170,541 / -149,669 | 0/13 |
| 1-min, both, no stop | 1556 (6.2/day) | 50% | -1.0 | Rs -63 | -1.80 | Rs -98,309 | -99,262 / +954 | 4/13 |
| 1-min, both, stop on failed break | 1598 (6.4/day) | 34% | -0.8 | Rs -64 | -2.76 | Rs -101,630 | -58,330 / -43,300 | 4/13 |
| 3-min, swing, no stop | 542 (2.2/day) | 53% | -3.2 | Rs -200 | -1.86 | Rs -108,287 | -59,212 / -49,075 | 3/13 |
| 3-min, swing, stop on failed break | 555 (2.2/day) | 39% | +0.5 | Rs -86 | -1.19 | Rs -47,837 | -26,368 / -21,468 | 4/13 |
| 3-min, pool, no stop | 975 (3.9/day) | 53% | -3.7 | Rs -150 | -2.26 | Rs -145,882 | -49,799 / -96,084 | 3/13 |
| 3-min, pool, stop on failed break | 1068 (4.3/day) | 38% | +0.3 | Rs -54 | -1.24 | Rs -57,405 | -9,858 / -47,547 | 3/13 |
| 3-min, either, no stop | 1209 (4.9/day) | 52% | -3.0 | Rs -163 | -2.67 | Rs -197,313 | -69,643 / -127,669 | 1/13 |
| 3-min, either, stop on failed break | 1348 (5.4/day) | 37% | +0.3 | Rs -67 | -1.69 | Rs -90,759 | -14,222 / -76,536 | 1/13 |
| 3-min, both, no stop | 555 (2.2/day) | 58% | -2.0 | Rs -112 | -1.27 | Rs -62,068 | -45,987 / -16,081 | 4/13 |
| 3-min, both, stop on failed break | 561 (2.3/day) | 42% | +0.2 | Rs -47 | -0.81 | Rs -26,318 | -27,261 / +943 | 4/13 |
| 5-min, swing, no stop | 390 (1.6/day) | 52% | +0.5 | Rs -134 | -0.68 | Rs -52,166 | -63,556 / +11,391 | 4/13 |
| 5-min, swing, stop on failed break | 408 (1.6/day) | 41% | +7.4 | Rs +64 | 0.51 | Rs +26,252 | -11,064 / +37,316 | 4/13 |
| 5-min, pool, no stop | 640 (2.6/day) | 52% | -6.5 | Rs -222 | -1.81 | Rs -142,182 | -105,768 / -36,413 | 3/13 |
| 5-min, pool, stop on failed break | 709 (2.8/day) | 39% | -1.1 | Rs -53 | -0.71 | Rs -37,746 | -31,169 / -6,577 | 6/13 |
| 5-min, either, no stop | 793 (3.2/day) | 52% | -2.9 | Rs -176 | -1.58 | Rs -139,780 | -76,489 / -63,291 | 3/13 |
| 5-min, either, stop on failed break | 887 (3.6/day) | 38% | +0.3 | Rs -55 | -0.80 | Rs -48,887 | -22,831 / -26,056 | 5/13 |
| 5-min, both, no stop | 369 (1.5/day) | 54% | -0.8 | Rs -109 | -0.61 | Rs -40,084 | -69,418 / +29,333 | 6/13 |
| 5-min, both, stop on failed break | 383 (1.5/day) | 44% | +5.5 | Rs +86 | 0.76 | Rs +33,032 | -3,078 / +36,110 | 9/13 |
| 15-min, swing, no stop | 133 (0.5/day) | 52% | +7.1 | Rs -319 | -0.78 | Rs -42,406 | -13,488 / -28,918 | 5/13 |
| 15-min, swing, stop on failed break | 135 (0.5/day) | 41% | -0.4 | Rs -315 | -0.88 | Rs -42,501 | -37,303 / -5,198 | 5/13 |
| 15-min, pool, no stop | 224 (0.9/day) | 53% | -4.4 | Rs -329 | -1.26 | Rs -73,679 | -39,362 / -34,317 | 4/13 |
| 15-min, pool, stop on failed break | 231 (0.9/day) | 43% | -3.9 | Rs -332 | -1.48 | Rs -76,754 | -61,331 / -15,422 | 5/13 |
| 15-min, either, no stop | 287 (1.2/day) | 54% | -1.9 | Rs -345 | -1.54 | Rs -99,015 | -56,230 / -42,785 | 4/13 |
| 15-min, either, stop on failed break | 300 (1.2/day) | 43% | -5.0 | Rs -336 | -1.80 | Rs -100,914 | -74,384 / -26,529 | 4/13 |
| 15-min, both, no stop | 110 (0.4/day) | 59% | +26.4 | Rs +336 | 0.98 | Rs +36,916 | +46,285 / -9,369 | 8/13 |
| 15-min, both, stop on failed break | 112 (0.4/day) | 51% | +16.8 | Rs +226 | 0.73 | Rs +25,364 | +18,614 / +6,749 | 9/13 |

- 1-min: 3118 swing levels, 5271 pools; median bars from a level forming to its break 43
- 3-min: 997 swing levels, 1970 pools; median bars from a level forming to its break 45
- 5-min: 615 swing levels, 1193 pools; median bars from a level forming to its break 40
- 5-min, either, no stop: exits next liquidity 56%, new liquidity 34%, 15:10 10%; median hold 44 min
- 15-min: 202 swing levels, 384 pools; median bars from a level forming to its break 35
|---|---|---|---|---|---|---|---|---|
| 60-min, swing, no stop | 32 (0.1/day) | 53% | +6.3 | Rs -211 | -0.23 | Rs -6,739 | +22,061 / -28,800 | 6/11 |
| 60-min, swing, stop on failed break | 32 (0.1/day) | 50% | +23.5 | Rs +26 | 0.03 | Rs +845 | +21,134 / -20,288 | 5/11 |
| 60-min, swing, no stop, held overnight | 30 (0.1/day) | 57% | -69.6 | Rs -319 | -0.23 | Rs -9,568 | -5,405 / -4,164 | 7/11 |
| 60-min, swing, stop on failed break, held overnight | 32 (0.1/day) | 47% | +7.8 | Rs -124 | -0.14 | Rs -3,954 | +8,733 / -12,688 | 5/11 |
| 60-min, pool, no stop | 58 (0.2/day) | 50% | +34.0 | Rs +100 | 0.20 | Rs +5,801 | +18,912 / -13,112 | 7/13 |
| 60-min, pool, stop on failed break | 58 (0.2/day) | 47% | +33.8 | Rs +195 | 0.41 | Rs +11,295 | +21,972 / -10,677 | 6/13 |
| 60-min, pool, no stop, held overnight | 57 (0.2/day) | 63% | +78.0 | Rs +931 | 0.83 | Rs +53,086 | +38,748 / +14,338 | 8/13 |
| 60-min, pool, stop on failed break, held overnight | 58 (0.2/day) | 47% | +53.6 | Rs +640 | 0.65 | Rs +37,136 | +26,976 / +10,159 | 6/13 |
| 60-min, either, no stop | 72 (0.3/day) | 50% | +22.9 | Rs -120 | -0.25 | Rs -8,654 | +25,525 / -34,179 | 7/13 |
| 60-min, either, stop on failed break | 72 (0.3/day) | 46% | +22.6 | Rs -26 | -0.06 | Rs -1,872 | +26,833 / -28,705 | 6/13 |
| 60-min, either, no stop, held overnight | 69 (0.3/day) | 61% | +11.5 | Rs +403 | 0.39 | Rs +27,811 | +17,014 / +10,797 | 6/13 |
| 60-min, either, stop on failed break, held overnight | 72 (0.3/day) | 44% | +23.7 | Rs +255 | 0.31 | Rs +18,336 | +17,240 / +1,095 | 6/13 |
| 60-min, both, no stop | 26 (0.1/day) | 54% | +28.4 | Rs +312 | 0.35 | Rs +8,113 | +12,837 / -4,724 | 7/12 |
| 60-min, both, stop on failed break | 26 (0.1/day) | 54% | +30.4 | Rs +376 | 0.44 | Rs +9,778 | +13,662 / -3,884 | 7/12 |
| 60-min, both, no stop, held overnight | 26 (0.1/day) | 62% | +68.3 | Rs +501 | 0.43 | Rs +13,027 | +9,924 / +3,103 | 7/12 |
| 60-min, both, stop on failed break, held overnight | 26 (0.1/day) | 54% | +50.4 | Rs +405 | 0.45 | Rs +10,535 | +16,108 / -5,573 | 7/12 |

- 60-min: 54 swing levels, 89 pools; median bars from a level forming to its break 40