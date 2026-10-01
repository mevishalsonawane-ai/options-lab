## Liquidity found / broken -> wait 5 candles -> read the direction -> buy call or put (research/liquidity_wait5.py)

Levels as in LIQUIDITY.md (swings pivot 20 full range; pools 2 contacts / 5 bars / 10 confirm). Readers: net (5th close vs event close), colour (more green or red), hhhl (higher highs+lows vs lower), held (breaks only: still beyond the level). CONTROL = the same readers after an ordinary candle. 1 lot, real option prices, costs included.

### 2025-02-17 .. 2026-02-23 (249 days)

| timeframe, event, levels, reader | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half |
|---|---|---|---|---|---|---|---|
| 3-min, found, swing, net | 706 (2.8/day) | 48% | -1.1 | Rs -97 | -1.82 | Rs -68,167 | -8,183 / -59,984 |
| 3-min, found, swing, colour | 706 (2.8/day) | 48% | -4.2 | Rs -151 | -2.86 | Rs -106,534 | -25,756 / -80,778 |
| 3-min, found, swing, hhhl | 584 (2.3/day) | 49% | -0.3 | Rs -75 | -1.23 | Rs -43,940 | +7,658 / -51,598 |
| 3-min, found, pool, net | 987 (4.0/day) | 49% | +1.8 | Rs -51 | -1.29 | Rs -50,745 | +11,501 / -62,247 |
| 3-min, found, pool, colour | 986 (4.0/day) | 48% | -0.2 | Rs -85 | -2.16 | Rs -84,296 | -12,606 / -71,690 |
| 3-min, found, pool, hhhl | 869 (3.5/day) | 51% | +3.2 | Rs -32 | -0.76 | Rs -27,742 | +23,120 / -50,862 |
| 3-min, found, either, net | 1293 (5.2/day) | 49% | +1.5 | Rs -61 | -1.69 | Rs -79,428 | +1,676 / -81,104 |
| 3-min, found, either, colour | 1287 (5.2/day) | 48% | -0.4 | Rs -95 | -2.64 | Rs -122,199 | -14,944 / -107,254 |
| 3-min, found, either, hhhl | 1138 (4.6/day) | 52% | +2.2 | Rs -50 | -1.28 | Rs -56,467 | +19,231 / -75,698 |
| 3-min, CONTROL (ordinary candle), net | 1240 (5.0/day) | 49% | -0.1 | Rs -88 | -2.37 | Rs -109,549 | +3,218 / -112,767 |
| 3-min, CONTROL (ordinary candle), hhhl | 1114 (4.5/day) | 50% | +0.5 | Rs -64 | -1.59 | Rs -71,501 | +37,650 / -109,151 |
| 3-min, found, both, net | 357 (1.4/day) | 51% | +0.7 | Rs -41 | -0.68 | Rs -14,754 | +2,482 / -17,237 |
| 3-min, found, both, colour | 358 (1.4/day) | 50% | -1.9 | Rs -83 | -1.38 | Rs -29,739 | -11,748 / -17,990 |
| 3-min, found, both, hhhl | 292 (1.2/day) | 53% | +0.7 | Rs -44 | -0.65 | Rs -12,745 | -4,127 / -8,618 |
| 3-min, break, swing, net | 511 (2.1/day) | 48% | +2.0 | Rs -85 | -1.28 | Rs -43,210 | +20,370 / -63,580 |
| 3-min, break, swing, colour | 505 (2.0/day) | 45% | -2.4 | Rs -137 | -2.06 | Rs -69,394 | +2,615 / -72,008 |
| 3-min, break, swing, hhhl | 439 (1.8/day) | 49% | +0.1 | Rs -111 | -1.53 | Rs -48,730 | +18,846 / -67,576 |
| 3-min, break, swing, held | 518 (2.1/day) | 47% | +3.7 | Rs -52 | -0.77 | Rs -27,102 | +19,122 / -46,224 |
| 3-min, break, pool, net | 864 (3.5/day) | 48% | -3.0 | Rs -121 | -2.46 | Rs -104,785 | -28,754 / -76,031 |
| 3-min, break, pool, colour | 862 (3.5/day) | 48% | +1.0 | Rs -68 | -1.36 | Rs -58,679 | -2,413 / -56,266 |
| 3-min, break, pool, hhhl | 733 (2.9/day) | 49% | +0.9 | Rs -71 | -1.23 | Rs -51,715 | +14,118 / -65,832 |
| 3-min, break, pool, held | 874 (3.5/day) | 48% | -0.5 | Rs -89 | -1.75 | Rs -77,632 | -8,878 / -68,754 |
| 3-min, break, either, net | 978 (3.9/day) | 48% | -2.0 | Rs -106 | -2.34 | Rs -104,059 | -14,587 / -89,472 |
| 3-min, break, either, colour | 970 (3.9/day) | 48% | +1.3 | Rs -64 | -1.37 | Rs -62,114 | +5,467 / -67,581 |
| 3-min, break, either, hhhl | 846 (3.4/day) | 50% | +2.4 | Rs -50 | -0.96 | Rs -42,231 | +35,642 / -77,872 |
| 3-min, break, either, held | 990 (4.0/day) | 48% | +0.1 | Rs -79 | -1.69 | Rs -77,926 | +5,894 / -83,820 |
| 3-min, CONTROL (ordinary candle), net | 840 (3.4/day) | 47% | +2.0 | Rs -71 | -1.49 | Rs -59,553 | -6,801 / -52,752 |
| 3-min, CONTROL (ordinary candle), hhhl | 744 (3.0/day) | 45% | +1.7 | Rs -74 | -1.43 | Rs -55,428 | +10,760 / -66,188 |
| 3-min, break, both, net | 498 (2.0/day) | 48% | -2.4 | Rs -129 | -1.84 | Rs -64,182 | -4,684 / -59,498 |
| 3-min, break, both, colour | 498 (2.0/day) | 46% | -2.3 | Rs -114 | -1.64 | Rs -56,628 | -3,228 / -53,400 |
| 3-min, break, both, hhhl | 425 (1.7/day) | 49% | -0.7 | Rs -98 | -1.21 | Rs -41,790 | +20,177 / -61,967 |
| 3-min, break, both, held | 508 (2.0/day) | 49% | +1.5 | Rs -68 | -0.93 | Rs -34,298 | +12,548 / -46,846 |
| 5-min, found, swing, net | 412 (1.7/day) | 49% | -0.3 | Rs -97 | -1.16 | Rs -40,076 | -5,999 / -34,078 |
| 5-min, found, swing, colour | 417 (1.7/day) | 50% | -0.9 | Rs -116 | -1.43 | Rs -48,423 | -22,357 / -26,066 |
| 5-min, found, swing, hhhl | 345 (1.4/day) | 50% | +2.0 | Rs -76 | -0.79 | Rs -26,155 | +7,792 / -33,948 |
| 5-min, found, pool, net | 574 (2.3/day) | 47% | -1.4 | Rs -112 | -1.78 | Rs -64,302 | -9,690 / -54,612 |
| 5-min, found, pool, colour | 580 (2.3/day) | 47% | +3.1 | Rs -68 | -1.10 | Rs -39,274 | +30,519 / -69,793 |
| 5-min, found, pool, hhhl | 507 (2.0/day) | 47% | +1.2 | Rs -100 | -1.48 | Rs -50,610 | +3,196 / -53,806 |
| 5-min, found, either, net | 759 (3.0/day) | 47% | -3.0 | Rs -146 | -2.50 | Rs -110,568 | -25,014 / -85,554 |
| 5-min, found, either, colour | 776 (3.1/day) | 48% | +3.0 | Rs -67 | -1.19 | Rs -51,740 | +10,692 / -62,432 |
| 5-min, found, either, hhhl | 683 (2.7/day) | 47% | -0.9 | Rs -136 | -2.18 | Rs -92,802 | -15,464 / -77,338 |
| 5-min, CONTROL (ordinary candle), net | 725 (2.9/day) | 49% | -2.1 | Rs -107 | -1.85 | Rs -77,592 | -18,358 / -59,234 |
| 5-min, CONTROL (ordinary candle), hhhl | 665 (2.7/day) | 49% | -1.5 | Rs -95 | -1.57 | Rs -63,002 | -24,175 / -38,827 |
| 5-min, found, both, net | 187 (0.8/day) | 45% | -12.2 | Rs -270 | -2.94 | Rs -50,568 | -30,886 / -19,682 |
| 5-min, found, both, colour | 190 (0.8/day) | 43% | -14.6 | Rs -312 | -3.42 | Rs -59,213 | -26,591 / -32,622 |
| 5-min, found, both, hhhl | 160 (0.6/day) | 46% | -7.4 | Rs -217 | -2.11 | Rs -34,662 | -21,204 / -13,458 |
| 5-min, break, swing, net | 302 (1.2/day) | 43% | -9.5 | Rs -247 | -2.31 | Rs -74,724 | -22,445 / -52,279 |
| 5-min, break, swing, colour | 301 (1.2/day) | 46% | -0.2 | Rs -109 | -1.03 | Rs -32,777 | +1,196 / -33,973 |
| 5-min, break, swing, hhhl | 255 (1.0/day) | 43% | -7.7 | Rs -216 | -1.87 | Rs -55,093 | -17,665 / -37,428 |
| 5-min, break, swing, held | 306 (1.2/day) | 45% | -7.3 | Rs -223 | -2.12 | Rs -68,234 | -24,297 / -43,936 |
| 5-min, break, pool, net | 498 (2.0/day) | 47% | -6.1 | Rs -195 | -2.59 | Rs -97,057 | -18,574 / -78,483 |
| 5-min, break, pool, colour | 499 (2.0/day) | 49% | -0.7 | Rs -124 | -1.63 | Rs -61,778 | +2,523 / -64,301 |
| 5-min, break, pool, hhhl | 437 (1.8/day) | 51% | +4.2 | Rs -55 | -0.67 | Rs -24,035 | +21,568 / -45,603 |
| 5-min, break, pool, held | 511 (2.1/day) | 50% | +0.2 | Rs -95 | -1.29 | Rs -48,662 | -2,659 / -46,003 |
| 5-min, break, either, net | 568 (2.3/day) | 46% | -7.0 | Rs -198 | -2.79 | Rs -112,408 | -22,985 / -89,423 |
| 5-min, break, either, colour | 567 (2.3/day) | 48% | -2.3 | Rs -137 | -1.91 | Rs -77,752 | -508 / -77,245 |
| 5-min, break, either, hhhl | 502 (2.0/day) | 49% | -1.0 | Rs -122 | -1.62 | Rs -61,406 | +13,001 / -74,408 |
| 5-min, break, either, held | 585 (2.3/day) | 49% | -2.6 | Rs -130 | -1.88 | Rs -75,798 | -18,337 / -57,461 |
| 5-min, CONTROL (ordinary candle), net | 502 (2.0/day) | 47% | -1.5 | Rs -125 | -1.73 | Rs -62,561 | +5,425 / -67,986 |
| 5-min, CONTROL (ordinary candle), hhhl | 444 (1.8/day) | 47% | -0.4 | Rs -95 | -1.16 | Rs -42,022 | +22,881 / -64,903 |
| 5-min, break, both, net | 297 (1.2/day) | 45% | -8.8 | Rs -258 | -2.55 | Rs -76,588 | -33,655 / -42,933 |
| 5-min, break, both, colour | 298 (1.2/day) | 48% | +0.8 | Rs -117 | -1.17 | Rs -34,774 | -17,984 / -16,789 |
| 5-min, break, both, hhhl | 258 (1.0/day) | 50% | +2.5 | Rs -82 | -0.73 | Rs -21,111 | -7,702 / -13,409 |
| 5-min, break, both, held | 302 (1.2/day) | 49% | -3.0 | Rs -160 | -1.58 | Rs -48,419 | -19,954 / -28,465 |
| 15-min, found, swing, net | 68 (0.3/day) | 40% | -28.1 | Rs -458 | -1.94 | Rs -31,122 | -9,124 / -21,998 |
| 15-min, found, swing, colour | 67 (0.3/day) | 39% | -24.8 | Rs -459 | -1.88 | Rs -30,770 | -21,217 / -9,554 |
| 15-min, found, swing, hhhl | 52 (0.2/day) | 40% | -28.7 | Rs -558 | -2.19 | Rs -29,039 | -12,453 / -16,587 |
| 15-min, found, pool, net | 188 (0.8/day) | 44% | -0.2 | Rs -156 | -1.02 | Rs -29,401 | -7,222 / -22,179 |
| 15-min, found, pool, colour | 189 (0.8/day) | 47% | +11.2 | Rs -28 | -0.19 | Rs -5,298 | +7,529 / -12,827 |
| 15-min, found, pool, hhhl | 166 (0.7/day) | 46% | +6.8 | Rs -111 | -0.68 | Rs -18,376 | -3,556 / -14,820 |
| 15-min, found, either, net | 235 (0.9/day) | 43% | -8.4 | Rs -266 | -1.98 | Rs -62,467 | -19,500 / -42,967 |
| 15-min, found, either, colour | 234 (0.9/day) | 46% | +1.9 | Rs -148 | -1.10 | Rs -34,713 | -11,845 / -22,868 |
| 15-min, found, either, hhhl | 202 (0.8/day) | 45% | -0.2 | Rs -207 | -1.42 | Rs -41,881 | -15,989 / -25,893 |
| 15-min, CONTROL (ordinary candle), net | 253 (1.0/day) | 36% | -37.2 | Rs -555 | -4.51 | Rs -140,317 | -75,038 / -65,279 |
| 15-min, CONTROL (ordinary candle), hhhl | 226 (0.9/day) | 40% | -25.8 | Rs -404 | -2.89 | Rs -91,391 | -59,894 / -31,498 |
| 15-min, found, both, net | 57 (0.2/day) | 39% | -15.2 | Rs -367 | -1.60 | Rs -20,922 | -14,898 / -6,024 |
| 15-min, found, both, colour | 58 (0.2/day) | 43% | -1.3 | Rs -151 | -0.67 | Rs -8,756 | -10,565 / +1,808 |
| 15-min, found, both, hhhl | 48 (0.2/day) | 42% | -21.5 | Rs -419 | -1.81 | Rs -20,104 | -10,477 / -9,627 |
| 15-min, break, swing, net | 99 (0.4/day) | 36% | -5.9 | Rs -325 | -1.41 | Rs -32,208 | -29,632 / -2,576 |
| 15-min, break, swing, colour | 101 (0.4/day) | 42% | -5.3 | Rs -311 | -1.47 | Rs -31,460 | -17,750 / -13,710 |
| 15-min, break, swing, hhhl | 87 (0.3/day) | 43% | +6.6 | Rs -179 | -0.72 | Rs -15,568 | -22,342 / +6,774 |
| 15-min, break, swing, held | 101 (0.4/day) | 39% | -7.3 | Rs -348 | -1.51 | Rs -35,102 | -22,117 / -12,985 |
| 15-min, break, pool, net | 159 (0.6/day) | 45% | -8.1 | Rs -310 | -2.11 | Rs -49,226 | -26,352 / -22,874 |
| 15-min, break, pool, colour | 157 (0.6/day) | 44% | -9.0 | Rs -305 | -2.07 | Rs -47,950 | -20,695 / -27,254 |
| 15-min, break, pool, hhhl | 132 (0.5/day) | 45% | +2.5 | Rs -228 | -1.41 | Rs -30,076 | -24,582 / -5,494 |
| 15-min, break, pool, held | 161 (0.6/day) | 46% | +3.3 | Rs -188 | -1.19 | Rs -30,223 | -16,025 / -14,198 |
| 15-min, break, either, net | 182 (0.7/day) | 44% | -3.9 | Rs -260 | -1.69 | Rs -47,369 | -32,926 / -14,443 |
| 15-min, break, either, colour | 182 (0.7/day) | 44% | -8.0 | Rs -307 | -2.14 | Rs -55,956 | -22,771 / -33,186 |
| 15-min, break, either, hhhl | 156 (0.6/day) | 46% | +7.0 | Rs -167 | -0.98 | Rs -26,034 | -29,149 / +3,116 |
| 15-min, break, either, held | 185 (0.7/day) | 44% | +3.9 | Rs -164 | -1.01 | Rs -30,431 | -21,845 / -8,586 |
| 15-min, CONTROL (ordinary candle), net | 110 (0.4/day) | 45% | +6.3 | Rs -160 | -0.89 | Rs -17,572 | -26,700 / +9,128 |
| 15-min, CONTROL (ordinary candle), hhhl | 98 (0.4/day) | 42% | -8.9 | Rs -372 | -2.13 | Rs -36,475 | -28,254 / -8,220 |
| 15-min, break, both, net | 94 (0.4/day) | 41% | -11.9 | Rs -387 | -2.06 | Rs -36,377 | -17,635 / -18,742 |
| 15-min, break, both, colour | 94 (0.4/day) | 41% | -12.0 | Rs -390 | -2.11 | Rs -36,634 | -18,523 / -18,111 |
| 15-min, break, both, hhhl | 80 (0.3/day) | 42% | +2.8 | Rs -211 | -0.96 | Rs -16,868 | -17,094 / +227 |
| 15-min, break, both, held | 95 (0.4/day) | 43% | -2.9 | Rs -289 | -1.34 | Rs -27,444 | -11,837 / -15,608 |
### 2024-02-13 .. 2025-02-14 (249 days)

| timeframe, event, levels, reader | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half |
|---|---|---|---|---|---|---|---|
| 3-min, found, swing, net | 709 (2.8/day) | 49% | -2.8 | Rs -150 | -2.26 | Rs -106,465 | -84,372 / -22,092 |
| 3-min, found, swing, colour | 707 (2.8/day) | 47% | -3.7 | Rs -169 | -2.47 | Rs -119,448 | -51,001 / -68,447 |
| 3-min, found, swing, hhhl | 607 (2.4/day) | 50% | -0.1 | Rs -106 | -1.51 | Rs -64,151 | -52,103 / -12,048 |
| 3-min, found, pool, net | 1049 (4.2/day) | 46% | -9.1 | Rs -211 | -3.41 | Rs -221,596 | -122,317 / -99,279 |
| 3-min, found, pool, colour | 1050 (4.2/day) | 46% | -5.3 | Rs -183 | -3.07 | Rs -192,038 | -103,475 / -88,562 |
| 3-min, found, pool, hhhl | 900 (3.6/day) | 47% | -10.8 | Rs -239 | -3.48 | Rs -215,439 | -129,796 / -85,644 |
| 3-min, found, either, net | 1335 (5.4/day) | 46% | -9.0 | Rs -227 | -4.29 | Rs -303,682 | -205,213 / -98,469 |
| 3-min, found, either, colour | 1348 (5.4/day) | 46% | -5.4 | Rs -188 | -3.60 | Rs -253,819 | -140,127 / -113,692 |
| 3-min, found, either, hhhl | 1205 (4.8/day) | 47% | -7.6 | Rs -208 | -3.72 | Rs -251,054 | -156,906 / -94,148 |
| 3-min, CONTROL (ordinary candle), net | 1222 (4.9/day) | 48% | +3.6 | Rs -42 | -0.84 | Rs -51,172 | -17,628 / -33,544 |
| 3-min, CONTROL (ordinary candle), hhhl | 1076 (4.3/day) | 47% | -0.3 | Rs -91 | -1.67 | Rs -98,399 | -10,908 / -87,490 |
| 3-min, found, both, net | 329 (1.3/day) | 45% | -14.1 | Rs -239 | -2.63 | Rs -78,768 | -26,499 / -52,269 |
| 3-min, found, both, colour | 330 (1.3/day) | 45% | -3.3 | Rs -115 | -1.42 | Rs -37,807 | -16,936 / -20,871 |
| 3-min, found, both, hhhl | 265 (1.1/day) | 46% | -13.8 | Rs -213 | -2.04 | Rs -56,473 | -28,892 / -27,581 |
| 3-min, break, swing, net | 487 (2.0/day) | 48% | -0.9 | Rs -181 | -1.75 | Rs -88,153 | -81,632 / -6,520 |
| 3-min, break, swing, colour | 484 (1.9/day) | 50% | +5.1 | Rs -78 | -0.73 | Rs -37,606 | -35,889 / -1,716 |
| 3-min, break, swing, hhhl | 420 (1.7/day) | 47% | -4.1 | Rs -237 | -2.10 | Rs -99,502 | -55,235 / -44,267 |
| 3-min, break, swing, held | 495 (2.0/day) | 48% | -3.7 | Rs -225 | -2.27 | Rs -111,193 | -97,318 / -13,875 |
| 3-min, break, pool, net | 819 (3.3/day) | 48% | +2.4 | Rs -77 | -1.01 | Rs -63,436 | -9,366 / -54,070 |
| 3-min, break, pool, colour | 816 (3.3/day) | 48% | +3.6 | Rs -75 | -1.02 | Rs -61,324 | +8,738 / -70,062 |
| 3-min, break, pool, hhhl | 721 (2.9/day) | 49% | +1.5 | Rs -98 | -1.17 | Rs -70,972 | +9,150 / -80,122 |
| 3-min, break, pool, held | 844 (3.4/day) | 48% | -1.2 | Rs -124 | -1.73 | Rs -104,867 | -26,729 / -78,138 |
| 3-min, break, either, net | 946 (3.8/day) | 48% | +0.5 | Rs -118 | -1.65 | Rs -111,499 | -43,742 / -67,756 |
| 3-min, break, either, colour | 947 (3.8/day) | 48% | +2.6 | Rs -92 | -1.32 | Rs -87,285 | -23,189 / -64,096 |
| 3-min, break, either, hhhl | 841 (3.4/day) | 48% | +0.2 | Rs -131 | -1.69 | Rs -110,275 | -10,581 / -99,694 |
| 3-min, break, either, held | 977 (3.9/day) | 48% | -1.0 | Rs -135 | -2.01 | Rs -131,597 | -52,794 / -78,803 |
| 3-min, CONTROL (ordinary candle), net | 844 (3.4/day) | 47% | -4.3 | Rs -139 | -1.85 | Rs -117,113 | -67,591 / -49,522 |
| 3-min, CONTROL (ordinary candle), hhhl | 727 (2.9/day) | 47% | -6.9 | Rs -201 | -2.43 | Rs -145,963 | -74,626 / -71,337 |
| 3-min, break, both, net | 465 (1.9/day) | 48% | -1.0 | Rs -132 | -1.22 | Rs -61,263 | -37,059 / -24,204 |
| 3-min, break, both, colour | 461 (1.9/day) | 48% | +4.5 | Rs -64 | -0.62 | Rs -29,726 | -18,203 / -11,523 |
| 3-min, break, both, hhhl | 416 (1.7/day) | 48% | -4.3 | Rs -194 | -1.68 | Rs -80,572 | -42,647 / -37,925 |
| 3-min, break, both, held | 472 (1.9/day) | 48% | -4.2 | Rs -173 | -1.69 | Rs -81,612 | -42,244 / -39,368 |
| 5-min, found, swing, net | 414 (1.7/day) | 49% | +3.1 | Rs -62 | -0.56 | Rs -25,593 | -13,761 / -11,833 |
| 5-min, found, swing, colour | 413 (1.7/day) | 48% | -0.3 | Rs -90 | -0.78 | Rs -37,348 | -49,339 / +11,991 |
| 5-min, found, swing, hhhl | 362 (1.5/day) | 49% | -1.2 | Rs -128 | -1.08 | Rs -46,156 | -20,219 / -25,937 |
| 5-min, found, pool, net | 598 (2.4/day) | 48% | +1.6 | Rs -96 | -1.14 | Rs -57,308 | -28,664 / -28,644 |
| 5-min, found, pool, colour | 609 (2.4/day) | 48% | -0.5 | Rs -140 | -1.69 | Rs -85,422 | -77,341 / -8,081 |
| 5-min, found, pool, hhhl | 517 (2.1/day) | 51% | +7.1 | Rs -32 | -0.37 | Rs -16,769 | -17,542 / +773 |
| 5-min, found, either, net | 789 (3.2/day) | 47% | +3.9 | Rs -81 | -1.07 | Rs -64,233 | -41,628 / -22,605 |
| 5-min, found, either, colour | 792 (3.2/day) | 47% | +0.1 | Rs -123 | -1.57 | Rs -97,461 | -87,053 / -10,408 |
| 5-min, found, either, hhhl | 701 (2.8/day) | 49% | +2.9 | Rs -92 | -1.12 | Rs -64,700 | -44,197 / -20,503 |
| 5-min, CONTROL (ordinary candle), net | 729 (2.9/day) | 47% | -5.5 | Rs -218 | -2.10 | Rs -158,679 | -111,593 / -47,086 |
| 5-min, CONTROL (ordinary candle), hhhl | 664 (2.7/day) | 47% | -3.4 | Rs -168 | -1.42 | Rs -111,452 | -26,264 / -85,189 |
| 5-min, found, both, net | 201 (0.8/day) | 45% | -4.7 | Rs -96 | -0.77 | Rs -19,384 | -18,227 / -1,157 |
| 5-min, found, both, colour | 204 (0.8/day) | 51% | -0.5 | Rs -23 | -0.20 | Rs -4,720 | -29,883 / +25,162 |
| 5-min, found, both, hhhl | 161 (0.6/day) | 54% | +7.0 | Rs +89 | 0.63 | Rs +14,310 | +9,324 / +4,986 |
| 5-min, break, swing, net | 316 (1.3/day) | 52% | +7.6 | Rs -29 | -0.13 | Rs -9,014 | -15,162 / +6,148 |
| 5-min, break, swing, colour | 311 (1.2/day) | 51% | +0.2 | Rs -138 | -0.65 | Rs -42,795 | -39,817 / -2,978 |
| 5-min, break, swing, hhhl | 273 (1.1/day) | 51% | +2.9 | Rs -173 | -0.74 | Rs -47,329 | -34,588 / -12,741 |
| 5-min, break, swing, held | 317 (1.3/day) | 53% | +15.5 | Rs +66 | 0.31 | Rs +20,860 | -3,750 / +24,610 |
| 5-min, break, pool, net | 492 (2.0/day) | 47% | -5.6 | Rs -217 | -1.58 | Rs -106,602 | -89,068 / -17,534 |
| 5-min, break, pool, colour | 481 (1.9/day) | 45% | -2.4 | Rs -156 | -1.25 | Rs -75,001 | -19,862 / -55,139 |
| 5-min, break, pool, hhhl | 427 (1.7/day) | 48% | -8.9 | Rs -274 | -1.80 | Rs -116,899 | -49,698 / -67,201 |
| 5-min, break, pool, held | 489 (2.0/day) | 46% | -11.9 | Rs -304 | -2.19 | Rs -148,862 | -117,246 / -31,615 |
| 5-min, break, either, net | 566 (2.3/day) | 47% | -2.8 | Rs -169 | -1.24 | Rs -95,602 | -72,060 / -23,541 |
| 5-min, break, either, colour | 553 (2.2/day) | 46% | -4.8 | Rs -186 | -1.35 | Rs -102,916 | -55,862 / -47,054 |
| 5-min, break, either, hhhl | 499 (2.0/day) | 48% | -4.5 | Rs -220 | -1.47 | Rs -109,879 | -45,785 / -64,093 |
| 5-min, break, either, held | 573 (2.3/day) | 47% | -3.4 | Rs -187 | -1.37 | Rs -107,130 | -87,508 / -19,622 |
| 5-min, CONTROL (ordinary candle), net | 508 (2.0/day) | 44% | -2.5 | Rs -166 | -1.41 | Rs -84,370 | -20,034 / -64,336 |
| 5-min, CONTROL (ordinary candle), hhhl | 443 (1.8/day) | 44% | -1.9 | Rs -155 | -1.24 | Rs -68,622 | +974 / -69,597 |
| 5-min, break, both, net | 286 (1.1/day) | 47% | -3.2 | Rs -200 | -0.99 | Rs -57,100 | -70,094 / +12,994 |
| 5-min, break, both, colour | 279 (1.1/day) | 48% | +3.6 | Rs -82 | -0.46 | Rs -22,766 | -4,698 / -18,068 |
| 5-min, break, both, hhhl | 245 (1.0/day) | 50% | -4.4 | Rs -246 | -1.09 | Rs -60,385 | -38,624 / -21,761 |
| 5-min, break, both, held | 284 (1.1/day) | 47% | -15.1 | Rs -366 | -1.79 | Rs -103,897 | -94,778 / -9,118 |
| 15-min, found, swing, net | 65 (0.3/day) | 40% | -23.5 | Rs -493 | -1.20 | Rs -32,044 | -28,316 / -3,728 |
| 15-min, found, swing, colour | 66 (0.3/day) | 44% | +11.5 | Rs +83 | 0.16 | Rs +5,500 | +20,731 / -15,231 |
| 15-min, found, swing, hhhl | 54 (0.2/day) | 44% | +16.5 | Rs +178 | 0.30 | Rs +9,626 | +26,323 / -16,698 |
| 15-min, found, pool, net | 185 (0.7/day) | 50% | +20.1 | Rs +84 | 0.39 | Rs +15,518 | +18,733 / -3,215 |
| 15-min, found, pool, colour | 184 (0.7/day) | 52% | +27.0 | Rs +121 | 0.57 | Rs +22,191 | -2,345 / +24,536 |
| 15-min, found, pool, hhhl | 158 (0.6/day) | 52% | +20.3 | Rs +84 | 0.37 | Rs +13,291 | +3,579 / +9,712 |
| 15-min, found, either, net | 218 (0.9/day) | 47% | +6.5 | Rs -52 | -0.25 | Rs -11,270 | +2,611 / -13,882 |
| 15-min, found, either, colour | 216 (0.9/day) | 49% | +22.8 | Rs +116 | 0.50 | Rs +25,125 | +22,535 / +2,590 |
| 15-min, found, either, hhhl | 183 (0.7/day) | 48% | +14.4 | Rs +57 | 0.23 | Rs +10,488 | +34,626 / -24,139 |
| 15-min, CONTROL (ordinary candle), net | 259 (1.0/day) | 46% | -20.6 | Rs -434 | -2.62 | Rs -112,328 | -12,858 / -99,471 |
| 15-min, CONTROL (ordinary candle), hhhl | 225 (0.9/day) | 48% | -17.4 | Rs -408 | -2.22 | Rs -91,886 | -3,450 / -88,435 |
| 15-min, found, both, net | 43 (0.2/day) | 53% | +41.7 | Rs +473 | 1.04 | Rs +20,337 | +17,163 / +3,174 |
| 15-min, found, both, colour | 43 (0.2/day) | 60% | +55.6 | Rs +588 | 1.30 | Rs +25,265 | +21,315 / +3,950 |
| 15-min, found, both, hhhl | 38 (0.2/day) | 55% | +15.3 | Rs +161 | 0.41 | Rs +6,134 | +2,607 / +3,527 |
| 15-min, break, swing, net | 103 (0.4/day) | 44% | -4.0 | Rs -348 | -0.79 | Rs -35,812 | -23,217 / -12,595 |
| 15-min, break, swing, colour | 102 (0.4/day) | 46% | -9.9 | Rs -380 | -0.86 | Rs -38,780 | -16,958 / -21,822 |
| 15-min, break, swing, hhhl | 96 (0.4/day) | 49% | +1.7 | Rs -232 | -0.49 | Rs -22,266 | -22,328 / +62 |
| 15-min, break, swing, held | 103 (0.4/day) | 45% | +1.2 | Rs -346 | -0.79 | Rs -35,679 | -17,903 / -17,776 |
| 15-min, break, pool, net | 153 (0.6/day) | 48% | +16.4 | Rs -112 | -0.35 | Rs -17,150 | -42,377 / +25,227 |
| 15-min, break, pool, colour | 151 (0.6/day) | 50% | +17.0 | Rs -55 | -0.17 | Rs -8,316 | -20,376 / +12,061 |
| 15-min, break, pool, hhhl | 136 (0.5/day) | 51% | +7.9 | Rs -182 | -0.52 | Rs -24,813 | -28,890 / +4,077 |
| 15-min, break, pool, held | 152 (0.6/day) | 50% | +8.7 | Rs -221 | -0.70 | Rs -33,596 | -14,146 / -19,450 |
| 15-min, break, either, net | 191 (0.8/day) | 46% | +3.5 | Rs -255 | -0.93 | Rs -48,791 | -43,543 / -5,248 |
| 15-min, break, either, colour | 188 (0.8/day) | 49% | +10.6 | Rs -104 | -0.37 | Rs -19,583 | -8,693 / -10,890 |
| 15-min, break, either, hhhl | 174 (0.7/day) | 49% | +1.5 | Rs -241 | -0.81 | Rs -41,993 | -28,898 / -13,095 |
| 15-min, break, either, held | 190 (0.8/day) | 47% | +3.5 | Rs -261 | -0.94 | Rs -49,566 | -11,718 / -37,848 |
| 15-min, CONTROL (ordinary candle), net | 125 (0.5/day) | 44% | -3.9 | Rs -168 | -0.50 | Rs -20,998 | +14,701 / -35,698 |
| 15-min, CONTROL (ordinary candle), hhhl | 104 (0.4/day) | 45% | +9.3 | Rs +53 | 0.11 | Rs +5,536 | +49,539 / -44,003 |
| 15-min, break, both, net | 85 (0.3/day) | 45% | +17.6 | Rs -276 | -0.59 | Rs -23,464 | -30,478 / +7,014 |
| 15-min, break, both, colour | 85 (0.3/day) | 45% | +0.1 | Rs -476 | -1.03 | Rs -40,443 | -30,220 / -10,222 |
| 15-min, break, both, hhhl | 75 (0.3/day) | 48% | +7.9 | Rs -332 | -0.62 | Rs -24,921 | -27,759 / +2,838 |
| 15-min, break, both, held | 85 (0.3/day) | 48% | +11.2 | Rs -357 | -0.76 | Rs -30,337 | -28,489 / -1,848 |