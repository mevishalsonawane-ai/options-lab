## The owner's two XAUUSD strategies (research/gold_session_strats.py)

Dukascopy XAUUSD 1-minute, 2023-10-02 .. 2026-09-25; ask = bid + 0.30, $7 a lot. USD per standard lot after costs. Fitting years Oct 2023 - Sep 2024, Oct 2024 - Sep 2025; held out Oct 2025 - Sep 2026. The DXY / yields check and the news-calendar rule could not be tested (no such data).

| strategy | direction | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | max drawdown | avg month at 0.01 lot |
|---|---|---|---|---|---|---|---|---|---|---|
| 1: EMA 9/21 + RSI, M5, exit opposite cross | buys only | -6,872 | +22,224 | -26,036 | -10,685 | 1036 | 28% | -0.22 | -66,183 | -2.97 |
| 1: EMA 9/21 + RSI, M5, exit opposite cross | both ways | -22,582 | -27,048 | -35,862 | -85,491 | 2116 | 25% | -1.21 | -111,094 | -23.75 |
| 1: EMA 9/21 + RSI, M5, exit opposite cross, H1 bias | buys only | -10,295 | +7,777 | -41,751 | -44,269 | 989 | 25% | -1.17 | -67,294 | -12.30 |
| 1: EMA 9/21 + RSI, M5, exit opposite cross, H1 bias | both ways | -9,926 | +16,527 | -50,701 | -44,101 | 1665 | 25% | -0.79 | -75,343 | -12.25 |
| 1: EMA 9/21 + RSI, M5, exit 1R | buys only | -11,403 | -17,748 | -28,757 | -57,908 | 860 | 48% | -1.33 | -66,242 | -16.09 |
| 1: EMA 9/21 + RSI, M5, exit 1R | both ways | -32,930 | -64,206 | +27,784 | -69,351 | 1740 | 47% | -1.20 | -134,530 | -19.26 |
| 1: EMA 9/21 + RSI, M5, exit 1R, H1 bias | buys only | -8,471 | -15,010 | -41,924 | -65,405 | 773 | 45% | -2.07 | -77,567 | -18.17 |
| 1: EMA 9/21 + RSI, M5, exit 1R, H1 bias | both ways | -19,824 | -22,395 | -2,917 | -45,136 | 1296 | 46% | -1.00 | -78,721 | -12.54 |
| 1: EMA 9/21 + RSI, M5, exit 2R | buys only | -13,270 | -11,667 | -43,934 | -68,872 | 729 | 36% | -1.36 | -76,498 | -19.13 |
| 1: EMA 9/21 + RSI, M5, exit 2R | both ways | -36,576 | -56,481 | -19,208 | -112,266 | 1489 | 33% | -1.65 | -143,164 | -31.18 |
| 1: EMA 9/21 + RSI, M5, exit 2R, H1 bias | buys only | -6,372 | +3,433 | -46,517 | -49,455 | 704 | 35% | -1.31 | -64,846 | -13.74 |
| 1: EMA 9/21 + RSI, M5, exit 2R, H1 bias | both ways | -14,194 | +15,835 | -8,784 | -7,144 | 1190 | 34% | -0.12 | -41,973 | -1.98 |
| 1: EMA 9/21 + RSI, M15, exit opposite cross | buys only | -5,714 | +27,246 | -40,162 | -18,630 | 403 | 31% | -0.43 | -61,112 | -5.18 |
| 1: EMA 9/21 + RSI, M15, exit opposite cross | both ways | -23,120 | +8,727 | -5,167 | -19,560 | 832 | 28% | -0.30 | -54,125 | -5.43 |
| 1: EMA 9/21 + RSI, M15, exit opposite cross, H1 bias | buys only | +7,547 | +12,700 | -6,791 | +13,456 | 316 | 33% | 0.41 | -31,536 | +3.74 |
| 1: EMA 9/21 + RSI, M15, exit opposite cross, H1 bias | both ways | +10,574 | +31,733 | +20,761 | +63,068 | 530 | 32% | 1.21 | -35,098 | +17.52 |
| 1: EMA 9/21 + RSI, M15, exit 1R | buys only | -4,210 | -18,803 | -40,307 | -63,319 | 354 | 48% | -1.85 | -73,214 | -17.59 |
| 1: EMA 9/21 + RSI, M15, exit 1R | both ways | -28,237 | -44,418 | -50,563 | -123,218 | 735 | 44% | -2.55 | -135,473 | -34.23 |
| 1: EMA 9/21 + RSI, M15, exit 1R, H1 bias | buys only | +3,608 | -2,015 | -9,063 | -7,470 | 266 | 54% | -0.30 | -25,845 | -2.08 |
| 1: EMA 9/21 + RSI, M15, exit 1R, H1 bias | both ways | +3,004 | +11,225 | -33,430 | -19,200 | 457 | 52% | -0.52 | -49,560 | -5.33 |
| 1: EMA 9/21 + RSI, M15, exit 2R | buys only | -5,115 | +1,699 | -38,308 | -41,724 | 339 | 40% | -1.05 | -68,708 | -11.59 |
| 1: EMA 9/21 + RSI, M15, exit 2R | both ways | -29,771 | -10,352 | -30,448 | -70,571 | 704 | 37% | -1.17 | -77,522 | -19.60 |
| 1: EMA 9/21 + RSI, M15, exit 2R, H1 bias | buys only | +860 | +6,458 | +8,560 | +15,879 | 265 | 45% | 0.52 | -24,398 | +4.41 |
| 1: EMA 9/21 + RSI, M15, exit 2R, H1 bias | both ways | -561 | +22,991 | +5,990 | +28,420 | 453 | 44% | 0.63 | -49,931 | +7.89 |
| 2: Asian-range sweep, M5, target 100% of the range | buys only | -6,384 | +949 | +9,393 | +3,957 | 269 | 13% | 0.11 | -23,922 | +1.10 |
| 2: Asian-range sweep, M5, target 100% of the range | both ways | -15,944 | -9,560 | -5,249 | -30,753 | 622 | 11% | -0.79 | -54,057 | -8.54 |
| 2: Asian-range sweep, M5, target 200% of the range | buys only | -8,970 | -3,553 | -12,730 | -25,253 | 269 | 10% | -0.92 | -31,937 | -7.01 |
| 2: Asian-range sweep, M5, target 200% of the range | both ways | -17,104 | -9,217 | -19,874 | -46,195 | 622 | 9% | -1.39 | -56,656 | -12.83 |
| 2: Asian-range sweep, M15, target 100% of the range | buys only | -6,114 | -3,141 | -5,902 | -15,156 | 257 | 17% | -0.55 | -25,594 | -4.21 |
| 2: Asian-range sweep, M15, target 100% of the range | both ways | -17,737 | -7,891 | -35,631 | -61,258 | 595 | 16% | -1.85 | -65,327 | -17.02 |
| 2: Asian-range sweep, M15, target 200% of the range | buys only | -4,161 | -5,725 | -10,689 | -20,576 | 257 | 14% | -0.72 | -26,092 | -5.72 |
| 2: Asian-range sweep, M15, target 200% of the range | both ways | -15,985 | -7,727 | -38,004 | -61,716 | 595 | 13% | -1.73 | -65,711 | -17.14 |
### Verdict

Of 32 versions (24 of option 1, 8 of option 2), 26 lose over the three years. The best buys-only version, EMA 9/21
+ RSI on M15 with the H1 bias and a 2R target, made +$15.9k a lot (t 0.52, drawdown -$24.4k); the best of all,
the same with the H1 bias and the opposite-cross exit traded both ways (which IraGoldAlgo does not do), made
+$63.1k (t 1.21). None has a t above 2, so none is distinguishable from luck after costs. The Asian-range sweep wins
only 9-17% of its trades: the stop just past the sweep is hit far more often than the 100-200% expansion is reached.
IraGoldAlgo's 1-hour liquidity rule (+$119.5k, t 3.09, research/GOLD_1H_PLUS.md) stays far ahead; nothing here goes
into the app.
