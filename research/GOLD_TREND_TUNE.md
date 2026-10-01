## Tuning the 4-hour Supertrend (research/gold_trend_tune.py)

288 versions; 288 positive in each fitting year; 38 beat the baseline's fitting profit / drawdown; of those, 0 also beat it in the held-out year on both profit and drawdown.

Baseline:

| setting | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | fitting drawdown | held-out drawdown | 3-year drawdown | fitting profit / drawdown |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 4h (10, 3), exit flip, filter none | +44,309 | +83,583 | +124,208 | +252,100 | 52 | 56% | 2.53 | -22,563 | -49,887 | -49,887 | 5.67 |

Top 25 by the fitting years (held-out columns read afterwards):

| setting | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | fitting drawdown | held-out drawdown | 3-year drawdown | fitting profit / drawdown |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 3h (20, 3), exit flip, filter daily EMA 200 | +50,021 | +95,583 | +48,141 | +193,745 | 61 | 56% | 1.56 | -19,839 | -110,227 | -110,227 | 7.34 |
| 4h (20, 2.5), exit flip, filter daily EMA 200 | +47,952 | +93,418 | -1,894 | +139,475 | 65 | 54% | 1.51 | -19,396 | -140,698 | -140,698 | 7.29 |
| 3h (14, 3), exit flip, filter daily EMA 200 | +41,099 | +95,752 | +57,393 | +194,245 | 62 | 55% | 1.73 | -18,827 | -118,339 | -118,339 | 7.27 |
| 3h (10, 3), exit flip, filter daily EMA 200 | +46,248 | +96,515 | +40,223 | +182,986 | 62 | 52% | 1.59 | -19,650 | -135,229 | -135,229 | 7.27 |
| 3h (20, 3), exit flip, filter none | +48,032 | +95,583 | +71,357 | +214,973 | 65 | 55% | 1.71 | -19,839 | -92,180 | -92,180 | 7.24 |
| 4h (20, 2.5), exit flip, filter none | +46,500 | +93,418 | +15,607 | +155,524 | 69 | 54% | 1.65 | -19,396 | -131,706 | -131,706 | 7.21 |
| 3h (10, 3), exit flip, filter none | +44,259 | +96,515 | +63,440 | +204,214 | 66 | 52% | 1.75 | -19,650 | -117,182 | -117,182 | 7.16 |
| 3h (14, 3), exit flip, filter none | +39,110 | +95,752 | +80,610 | +215,473 | 66 | 55% | 1.89 | -18,827 | -100,292 | -100,292 | 7.16 |
| 3h (20, 3), exit flip, filter daily EMA 50 | +49,977 | +89,861 | +47,540 | +187,378 | 57 | 56% | 1.53 | -19,839 | -110,827 | -110,827 | 7.05 |
| 4h (10, 2.5), exit flip, filter daily EMA 200 | +45,768 | +90,711 | +21,974 | +158,453 | 63 | 52% | 1.41 | -19,396 | -129,140 | -129,140 | 7.04 |
| 4h (20, 2.5), exit flip, filter daily EMA 50 | +47,691 | +88,777 | +27,860 | +164,328 | 59 | 58% | 1.85 | -19,396 | -110,943 | -110,943 | 7.04 |
| 4h (10, 2.5), exit flip, filter none | +44,316 | +90,711 | +41,803 | +176,830 | 67 | 52% | 1.54 | -19,396 | -117,821 | -117,821 | 6.96 |
| 3h (14, 3), exit flip, filter daily EMA 50 | +41,055 | +89,418 | +57,699 | +188,172 | 58 | 55% | 1.70 | -18,827 | -118,033 | -118,033 | 6.93 |
| 4h (10, 2.5), exit flip, filter daily EMA 50 | +45,507 | +86,880 | +33,099 | +165,487 | 58 | 55% | 1.48 | -19,396 | -118,015 | -118,015 | 6.83 |
| 4h (14, 2.5), exit flip, filter daily EMA 200 | +44,309 | +91,908 | +17,728 | +153,945 | 64 | 52% | 1.36 | -20,797 | -135,244 | -135,244 | 6.55 |
| 4h (14, 2.5), exit flip, filter none | +42,857 | +91,908 | +37,557 | +172,322 | 68 | 51% | 1.50 | -20,797 | -123,924 | -123,924 | 6.48 |
| 3h (10, 3), exit flip, filter daily EMA 50 | +46,204 | +90,185 | +53,341 | +189,730 | 57 | 54% | 1.67 | -21,219 | -122,111 | -122,111 | 6.43 |
| 4h (14, 2.5), exit flip, filter daily EMA 50 | +44,048 | +87,644 | +30,346 | +162,038 | 59 | 54% | 1.45 | -20,781 | -122,626 | -122,626 | 6.34 |
| 3h (7, 3.5), exit flip, filter daily EMA 200 | +44,464 | +103,181 | +91,702 | +239,347 | 47 | 51% | 2.45 | -23,465 | -65,805 | -65,805 | 6.29 |
| 3h (7, 3.5), exit flip, filter none | +41,727 | +103,181 | +110,854 | +255,762 | 51 | 51% | 2.55 | -23,465 | -57,988 | -57,988 | 6.18 |
| 3h (14, 3.5), exit flip, filter daily EMA 200 | +42,752 | +99,722 | +113,964 | +256,438 | 47 | 60% | 2.61 | -23,345 | -67,216 | -67,216 | 6.10 |
| 4h (7, 2.5), exit flip, filter daily EMA 200 | +38,324 | +79,954 | +58,711 | +176,989 | 64 | 50% | 1.69 | -19,396 | -90,905 | -90,905 | 6.10 |
| 3h (7, 3.5), exit flip, filter daily EMA 50 | +44,420 | +96,960 | +91,980 | +233,360 | 45 | 56% | 2.36 | -23,465 | -65,526 | -65,526 | 6.03 |
| 4h (7, 2.5), exit flip, filter none | +36,872 | +79,954 | +78,539 | +195,366 | 68 | 50% | 1.83 | -19,396 | -79,585 | -79,585 | 6.02 |
| 3h (14, 3.5), exit flip, filter none | +40,015 | +99,722 | +133,227 | +272,964 | 51 | 57% | 2.63 | -23,345 | -67,216 | -67,216 | 5.99 |

### By exit (averages over the grid)

| group | versions | avg 3 years | avg fitting | avg held-out | avg 3-year drawdown |
|---|---|---|---|---|---|
| flip | 144 | +195,249 | +128,197 | +67,052 | -90,458 |
| touch | 144 | +135,466 | +85,944 | +49,522 | -72,239 |

### By filter (averages over the grid)

| group | versions | avg 3 years | avg fitting | avg held-out | avg 3-year drawdown |
|---|---|---|---|---|---|
| none | 96 | +163,355 | +107,148 | +56,208 | -92,344 |
| daily EMA 50 | 96 | +163,581 | +105,004 | +58,577 | -76,988 |
| daily EMA 200 | 96 | +169,135 | +109,060 | +60,075 | -74,714 |

### By chart (averages over the grid)

| group | versions | avg 3 years | avg fitting | avg held-out | avg 3-year drawdown |
|---|---|---|---|---|---|
| 3h | 96 | +155,219 | +106,375 | +48,844 | -79,236 |
| 4h | 96 | +167,444 | +106,317 | +61,128 | -76,933 |
| 6h | 96 | +173,409 | +108,520 | +64,889 | -87,878 |
### Verdict on the settings

No retuned setting is better. The 38 versions that beat the baseline (4h, ATR 10, multiplier 3, exit on the flip, no
filter) in the fitting years all did worse in the held-out year, in profit or in drawdown: the best fitting version
(3h, 20, 3, daily EMA 200) made +$48.1k there against the baseline's +$124.2k, with -$110.2k drawdown against -$49.9k.
Exiting the moment the price touches the line (instead of waiting for the 4-hour close) cuts the drawdown on average but
cuts the profit more. The daily EMA filters change little. The baseline stays.

## Profit locks on the 4-hour Supertrend (research/gold_trend_lock.py)

Sorted by the fitting years' profit / drawdown (the held-out year read afterwards). USD per standard lot; drawdowns marked to market every hour, swap included.

| version | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | fitting drawdown | held-out drawdown | 3-year drawdown | fitting profit / drawdown |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| giveback 3 ATR, wait | +40,801 | +76,122 | +90,705 | +207,628 | 52 | 69% | 3.52 | -14,717 | -33,803 | -33,803 | 7.94 |
| giveback 2 ATR, wait | +21,072 | +71,311 | +50,424 | +142,807 | 52 | 75% | 4.13 | -12,155 | -28,990 | -28,990 | 7.60 |
| lock half 4 ATR, wait | +47,347 | +85,865 | +62,084 | +195,296 | 52 | 63% | 2.84 | -18,689 | -49,302 | -49,302 | 7.13 |
| lock half 3 ATR, wait | +22,643 | +71,692 | +63,900 | +158,235 | 52 | 77% | 2.61 | -13,274 | -38,342 | -38,342 | 7.11 |
| lock half 2 ATR, wait | +17,055 | +74,053 | +84,917 | +176,024 | 52 | 88% | 3.06 | -13,274 | -29,711 | -29,711 | 6.86 |
| giveback 3 ATR, rejoin | +52,677 | +94,954 | +82,509 | +230,140 | 100 | 53% | 2.00 | -21,565 | -62,173 | -62,173 | 6.85 |
| breakeven 3 ATR, wait | +50,344 | +89,760 | +127,263 | +267,366 | 52 | 54% | 2.71 | -21,396 | -49,887 | -49,887 | 6.55 |
| breakeven 2 ATR, wait | +38,772 | +86,389 | +123,667 | +248,827 | 52 | 46% | 2.57 | -19,635 | -55,405 | -55,405 | 6.37 |
| lock half 6 ATR, wait | +48,100 | +91,725 | +150,888 | +290,713 | 52 | 58% | 2.88 | -22,563 | -49,302 | -49,302 | 6.20 |
| breakeven 1 ATR, wait | +29,901 | +90,721 | +70,518 | +191,140 | 52 | 44% | 3.00 | -19,635 | -47,342 | -47,342 | 6.14 |
| giveback 4 ATR, wait | +59,936 | +76,329 | +185,651 | +321,917 | 52 | 63% | 2.79 | -22,809 | -39,157 | -39,157 | 5.97 |
| giveback 5 ATR, wait | +57,928 | +79,509 | +173,328 | +310,766 | 52 | 60% | 2.74 | -23,117 | -43,970 | -43,970 | 5.95 |
| breakeven 4 ATR, wait | +48,134 | +86,795 | +124,208 | +259,136 | 52 | 54% | 2.61 | -23,044 | -49,887 | -49,887 | 5.86 |
| lock half 6 ATR, rejoin | +47,295 | +81,805 | +137,715 | +266,816 | 56 | 54% | 2.58 | -22,563 | -49,302 | -49,302 | 5.72 |
| no lock (baseline) | +44,309 | +83,583 | +124,208 | +252,100 | 52 | 56% | 2.53 | -22,563 | -49,887 | -49,887 | 5.67 |
| giveback 5 ATR, rejoin | +49,688 | +81,202 | +162,084 | +292,974 | 59 | 54% | 2.52 | -23,117 | -43,970 | -43,970 | 5.66 |
| giveback 2 ATR, rejoin | +39,888 | +89,995 | +51,968 | +181,850 | 160 | 56% | 1.79 | -23,357 | -63,649 | -63,649 | 5.56 |
| lock half 4 ATR, rejoin | +40,635 | +77,677 | +108,916 | +227,229 | 74 | 57% | 2.30 | -21,591 | -55,485 | -55,485 | 5.48 |
| breakeven 4 ATR, rejoin | +48,134 | +77,859 | +124,208 | +250,200 | 53 | 53% | 2.50 | -23,044 | -49,887 | -49,887 | 5.47 |
| breakeven 3 ATR, rejoin | +49,141 | +77,664 | +121,956 | +248,760 | 57 | 49% | 2.47 | -24,555 | -51,554 | -51,554 | 5.16 |
| giveback 4 ATR, rejoin | +54,951 | +83,639 | +164,442 | +303,032 | 66 | 55% | 2.51 | -27,787 | -46,602 | -46,602 | 4.99 |
| breakeven 2 ATR, rejoin | +50,118 | +75,992 | +115,990 | +242,099 | 66 | 42% | 2.41 | -25,696 | -55,247 | -55,247 | 4.91 |
| lock half 2 ATR, rejoin | +34,493 | +67,715 | +108,063 | +210,271 | 129 | 71% | 2.05 | -21,591 | -55,485 | -55,485 | 4.73 |
| breakeven 1 ATR, rejoin | +48,795 | +68,145 | +111,804 | +228,744 | 85 | 36% | 2.27 | -26,828 | -58,099 | -58,099 | 4.36 |
| lock half 3 ATR, rejoin | +33,320 | +71,019 | +108,872 | +213,212 | 95 | 62% | 2.12 | -26,573 | -55,485 | -55,485 | 3.93 |

14 of 24 locks beat the baseline in the fitting years; of those 5 also matched or beat it in the held-out year on both profit and drawdown, 11 on drawdown alone.
### Verdict on the locks

A giveback lock is the one change that holds up: once a buy is 1 ATR in profit (the 4-hour ATR(14) at the entry), sell
if the price falls 3 / 4 / 5 ATRs from its highest point since the entry, and buy again only after the trend has flipped
down and up. All three cut the held-out drawdown (-$33.8k / -$39.2k / -$44.0k against -$49.9k); the strict fitting-years
pick is 3 ATR (held-out profit +$90.7k, less than the baseline's +$124.2k); 4 ATR, the middle of the 3-5 plateau, is
better on both counts in every year but one: +$59.9k, +$76.3k, +$185.7k = +$321.9k, t 2.79, 63% won, drawdown -$39.2k,
against +$252.1k and -$49.9k. Note 4 was preferred over 3 with the held-out year in view, so its held-out lead is partly
chosen; the drawdown cut is the robust part. Breakeven stops and "keep half" locks did not help out of sample, and
rejoining the trend after a lock exit made everything worse. IraGoldAlgo's trend arm uses Supertrend(10, 3) on 4-hour
candles with the 4-ATR giveback.
