# R202 results: 21 price-action patterns, 1 lot, dated charges and R17 fills

Design (walk-forward, 5 folds) 1 Dec 2021 - 31 Mar 2026; holdout 1 Apr - 13 Nov 2026 (read once). q = BH over m = 4,850; q42 = within R202's 42 tests. Signal source: the index's 5-minute bars; 1-ITM option bought.

| test | design trades | win % | net | net w/o top 5 | worst month | max DD | folds + | F1 | F2 | F3 | F4 | F5 | twin p | q | q42 | holdout trades | win % | net | worst month | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| P01-X | 7,310 | 41.67 | -1,009,786 | -1,050,959 | -80,161 | 1,011,288 | 0/5 | -46,897 | -173,971 | -292,878 | -349,264 | -146,776 | 0.975 | 1 | 1 | 1330 | 42.71 | -155,273 | -42,932 | fail |
| P01-V | 7,400 | 48.46 | -1,045,491 | -1,073,261 | -62,735 | 1,045,491 | 0/5 | -90,202 | -151,933 | -287,673 | -384,437 | -131,246 | 0.975 | 1 | 1 | 1350 | 48.15 | -192,651 | -62,031 | fail |
| P02-X | 7,170 | 39.89 | -554,907 | -602,877 | -62,214 | 604,838 | 1/5 | -160,530 | -138,098 | -151,226 | -137,526 | 32,473 | 0.269 | 1 | 1 | 1368 | 43.27 | -161,837 | -63,800 | fail |
| P02-V | 7,260 | 48.93 | -599,301 | -634,590 | -76,299 | 622,003 | 0/5 | -180,189 | -145,313 | -117,759 | -150,442 | -5,598 | 0.224 | 1 | 1 | 1380 | 52.17 | -143,833 | -51,322 | fail |
| P03-X | 8,091 | 40.76 | -859,473 | -908,377 | -105,151 | 872,842 | 0/5 | -122,176 | -149,391 | -226,487 | -256,340 | -105,079 | 1.000 | 1 | 1 | 1429 | 42.83 | -130,714 | -63,562 | fail |
| P03-V | 8,164 | 47.92 | -863,727 | -895,258 | -86,053 | 872,319 | 0/5 | -132,855 | -159,953 | -224,854 | -268,732 | -77,332 | 1.000 | 1 | 1 | 1443 | 48.51 | -162,156 | -53,471 | fail |
| P04-X | 6,710 | 38.93 | -954,141 | -992,714 | -68,949 | 958,427 | 0/5 | -143,239 | -180,028 | -153,316 | -370,869 | -106,690 | 1.000 | 1 | 1 | 1144 | 41.61 | -164,067 | -55,244 | fail |
| P04-V | 6,764 | 46.45 | -906,276 | -937,264 | -55,689 | 927,004 | 0/5 | -130,692 | -173,792 | -200,820 | -325,309 | -75,664 | 1.000 | 1 | 1 | 1152 | 47.48 | -165,559 | -50,675 | fail |
| P06-X | 8,375 | 41.3 | -803,257 | -850,854 | -99,309 | 867,308 | 0/5 | -95,163 | -141,456 | -158,674 | -407,851 | -113 | 0.990 | 1 | 1 | 1539 | 43.14 | -175,847 | -85,122 | fail |
| P06-V | 8,440 | 49.18 | -830,770 | -867,418 | -60,003 | 841,465 | 0/5 | -94,890 | -160,170 | -159,183 | -385,649 | -30,877 | 0.985 | 1 | 1 | 1557 | 50.29 | -212,723 | -58,219 | fail |
| P07-X | 20 | 35.0 | -6,636 | -10,426 | -2,657 | 6,771 | 1/5 | -2,478 | 265 | -2,135 | -905 | -1,382 | 0.985 | 1 | 1 | 5 | 20.0 | -3,692 | -3,738 | fail |
| P07-V | 20 | 40.0 | -3,632 | -8,944 | -2,657 | 4,751 | 1/5 | -2,478 | 385 | -118 | -39 | -1,382 | 0.925 | 1 | 1 | 5 | 20.0 | -3,692 | -3,738 | fail |
| P09-X | 5,854 | 41.95 | -584,483 | -627,159 | -47,756 | 591,545 | 0/5 | -92,884 | -137,140 | -67,975 | -213,299 | -73,185 | 1.000 | 1 | 1 | 1083 | 38.13 | -191,186 | -59,994 | fail |
| P09-V | 5,879 | 48.17 | -650,269 | -677,781 | -46,215 | 650,269 | 0/5 | -94,288 | -137,956 | -91,307 | -262,626 | -64,092 | 1.000 | 1 | 1 | 1092 | 43.59 | -222,094 | -50,123 | fail |
| P10-X | 5,183 | 39.4 | -643,381 | -680,399 | -72,782 | 696,036 | 0/5 | -71,550 | -113,279 | -108,341 | -304,466 | -45,744 | 1.000 | 1 | 1 | 962 | 44.8 | -83,008 | -31,268 | fail |
| P10-V | 5,218 | 48.28 | -651,818 | -675,984 | -65,305 | 674,694 | 0/5 | -112,816 | -113,729 | -123,967 | -262,767 | -38,539 | 1.000 | 1 | 1 | 966 | 51.14 | -81,352 | -33,881 | fail |
| P11-X | 591 | 38.07 | -71,116 | -90,295 | -18,078 | 87,838 | 1/5 | -9,896 | -12,175 | -30,956 | -26,116 | 8,025 | 0.985 | 1 | 1 | 133 | 39.1 | -29,887 | -20,217 | fail |
| P11-V | 591 | 46.36 | -82,487 | -97,207 | -13,482 | 91,232 | 1/5 | -14,654 | -10,422 | -34,244 | -27,943 | 4,776 | 0.995 | 1 | 1 | 133 | 47.37 | -30,580 | -18,470 | fail |
| P12-X | 553 | 42.68 | -30,523 | -56,172 | -15,056 | 62,872 | 2/5 | 10,495 | -10,039 | -17,630 | -13,523 | 174 | 0.706 | 1 | 1 | 106 | 40.57 | -4,058 | -8,097 | fail |
| P12-V | 553 | 48.64 | -36,142 | -55,185 | -10,862 | 55,311 | 2/5 | 2,486 | -14,254 | -12,482 | -14,680 | 2,789 | 0.766 | 1 | 1 | 107 | 55.14 | 5,444 | -8,538 | fail |
| P13-X | 163 | 52.15 | 34,086 | 14,707 | -5,479 | 10,396 | 4/5 | 7,723 | -5,159 | 9,334 | 15,561 | 6,627 | 0.010 | 1 | 0.0331 | 28 | 57.14 | -4,063 | -7,566 | fail |
| P13-V | 163 | 60.12 | 4,464 | -8,434 | -3,773 | 8,541 | 2/5 | 3,918 | -3,358 | 7,036 | -2,360 | -772 | 0.199 | 1 | 0.965 | 28 | 60.71 | -1,892 | -5,494 | fail |
| P14-X | 147 | 43.54 | -3,881 | -20,731 | -2,981 | 20,282 | 3/5 | 1,238 | 2,409 | -7,893 | -8,586 | 8,952 | 0.269 | 1 | 1 | 20 | 65.0 | 3,932 | -438 | fail |
| P14-V | 147 | 48.98 | -15,470 | -24,003 | -3,116 | 19,559 | 1/5 | -276 | -69 | -7,110 | -9,341 | 1,325 | 0.587 | 1 | 1 | 20 | 65.0 | 6,031 | -521 | fail |
| P15-X | 571 | 40.63 | -65,952 | -95,169 | -11,504 | 79,744 | 0/5 | -4,362 | -15,003 | -4,557 | -41,113 | -917 | 1.000 | 1 | 1 | 78 | 47.44 | -20,350 | -9,988 | fail |
| P15-V | 572 | 46.33 | -76,972 | -92,230 | -10,031 | 78,473 | 0/5 | -4,658 | -17,045 | -6,722 | -35,751 | -12,797 | 1.000 | 1 | 1 | 78 | 55.13 | -12,059 | -8,121 | fail |
| P16-X | 422 | 41.0 | -3,749 | -24,439 | -15,894 | 28,782 | 3/5 | 10,111 | -19,501 | 4,437 | 9,763 | -8,559 | 0.801 | 1 | 1 | 106 | 46.23 | -8,493 | -11,238 | fail |
| P16-V | 425 | 53.18 | 6,648 | -11,054 | -12,125 | 24,886 | 3/5 | 11,802 | -17,079 | 1,167 | 15,708 | -4,950 | 0.463 | 1 | 1 | 106 | 57.55 | 1,097 | -6,491 | fail |
| P17-X | 3,197 | 41.82 | -278,851 | -313,951 | -50,368 | 291,080 | 0/5 | -20,051 | -24,558 | -18,097 | -108,633 | -107,512 | 0.726 | 1 | 1 | 623 | 42.05 | -83,102 | -34,818 | fail |
| P17-V | 3,208 | 49.56 | -284,013 | -307,441 | -38,202 | 297,415 | 0/5 | -22,601 | -33,365 | -49,057 | -107,856 | -71,133 | 0.711 | 1 | 1 | 624 | 47.76 | -73,970 | -19,410 | fail |
| P18-X | 3,778 | 39.84 | -581,356 | -618,105 | -101,811 | 645,359 | 0/5 | -100,797 | -82,430 | -82,394 | -307,752 | -7,983 | 1.000 | 1 | 1 | 823 | 45.2 | -21,227 | -29,148 | fail |
| P18-V | 3,913 | 50.32 | -474,588 | -501,588 | -77,629 | 514,045 | 0/5 | -99,001 | -70,299 | -107,920 | -177,894 | -19,474 | 1.000 | 1 | 1 | 845 | 53.25 | -52,930 | -24,888 | fail |
| P19-X | 4,652 | 39.21 | -524,678 | -560,574 | -51,995 | 557,605 | 0/5 | -56,591 | -78,163 | -142,834 | -185,183 | -61,908 | 0.005 | 5.98e-06 | 5.18e-08 | 852 | 42.96 | -41,248 | -35,649 | fail |
| P19-V | 4,656 | 46.61 | -565,530 | -587,113 | -48,019 | 586,225 | 0/5 | -72,490 | -80,453 | -134,275 | -218,465 | -59,846 | 0.005 | 0.000371 | 3.21e-06 | 856 | 50.23 | -29,121 | -24,072 | fail |
| P21-X | 2,385 | 38.28 | -417,194 | -444,007 | -37,475 | 424,546 | 0/5 | -67,527 | -59,970 | -98,840 | -125,804 | -65,052 | 1.000 | 1 | 1 | 407 | 40.29 | -106,945 | -33,767 | fail |
| P21-V | 2,419 | 47.21 | -376,311 | -402,827 | -36,243 | 388,966 | 0/5 | -60,084 | -63,694 | -91,093 | -111,724 | -49,715 | 1.000 | 1 | 1 | 412 | 47.57 | -116,197 | -38,129 | fail |
| P22-X | 8,952 | 40.47 | -1,042,169 | -1,086,355 | -86,534 | 1,061,932 | 0/5 | -143,872 | -190,072 | -227,533 | -353,963 | -126,728 | 1.000 | 1 | 1 | 1612 | 42.49 | -188,743 | -49,304 | fail |
| P22-V | 9,033 | 48.72 | -994,235 | -1,033,904 | -72,522 | 1,022,324 | 0/5 | -143,406 | -188,929 | -221,597 | -347,119 | -93,185 | 1.000 | 1 | 1 | 1629 | 48.74 | -248,448 | -63,597 | fail |
| P24-X | 1,910 | 40.16 | -280,788 | -307,461 | -23,142 | 286,933 | 0/5 | -11,281 | -79,269 | -95,128 | -87,222 | -7,888 | 0.025 | 1 | 0.117 | 341 | 37.24 | -78,667 | -24,559 | fail |
| P24-V | 1,927 | 44.58 | -353,583 | -376,596 | -25,286 | 353,583 | 0/5 | -19,131 | -82,515 | -96,686 | -127,490 | -27,760 | 0.512 | 1 | 1 | 345 | 42.61 | -80,711 | -21,772 | fail |
| P25-X | 4,154 | 41.86 | -359,779 | -406,287 | -59,751 | 429,904 | 1/5 | -37,701 | -87,980 | -161,094 | -124,955 | 51,951 | 0.005 | 4.14e-18 | 3.58e-20 | 793 | 37.96 | -204,593 | -72,237 | fail |
| P25-V | 4,170 | 47.53 | -446,788 | -474,662 | -57,860 | 475,054 | 1/5 | -56,762 | -102,248 | -133,104 | -164,932 | 10,257 | 0.005 | 1.78e-10 | 1.54e-12 | 801 | 42.82 | -178,173 | -70,754 | fail |

## Why each failed

- **P01-X** (1 Support bounce, X): design net -1,009,786 not > 0; design net without top 5 -1,050,959 not > 0; 0 of 5 folds positive (< 3); twin p 0.975 >= 0.05; BH q 1 > 0.1; design worst month -80,161 < -13,000; design max drawdown 1,011,288 > 40,000; holdout net -155,273 not > 0; holdout worst month -42,932 < -13,000; holdout max drawdown 199,372 > 40,000
- **P01-V** (1 Support bounce, V): design net -1,045,491 not > 0; design net without top 5 -1,073,261 not > 0; 0 of 5 folds positive (< 3); twin p 0.975 >= 0.05; BH q 1 > 0.1; design worst month -62,735 < -13,000; design max drawdown 1,045,491 > 40,000; holdout net -192,651 not > 0; holdout worst month -62,031 < -13,000; holdout max drawdown 226,308 > 40,000
- **P02-X** (2 Resistance rejection, X): design net -554,907 not > 0; design net without top 5 -602,877 not > 0; 1 of 5 folds positive (< 3); twin p 0.269 >= 0.05; BH q 1 > 0.1; design worst month -62,214 < -13,000; design max drawdown 604,838 > 40,000; holdout net -161,837 not > 0; holdout worst month -63,800 < -13,000; holdout max drawdown 194,055 > 40,000
- **P02-V** (2 Resistance rejection, V): design net -599,301 not > 0; design net without top 5 -634,590 not > 0; 0 of 5 folds positive (< 3); twin p 0.224 >= 0.05; BH q 1 > 0.1; design worst month -76,299 < -13,000; design max drawdown 622,003 > 40,000; holdout net -143,833 not > 0; holdout worst month -51,322 < -13,000; holdout max drawdown 153,266 > 40,000
- **P03-X** (3 Trend (pullback to EMA20 with HH/HL or LH/LL), X): design net -859,473 not > 0; design net without top 5 -908,377 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -105,151 < -13,000; design max drawdown 872,842 > 40,000; holdout net -130,714 not > 0; holdout worst month -63,562 < -13,000; holdout max drawdown 149,747 > 40,000
- **P03-V** (3 Trend (pullback to EMA20 with HH/HL or LH/LL), V): design net -863,727 not > 0; design net without top 5 -895,258 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -86,053 < -13,000; design max drawdown 872,319 > 40,000; holdout net -162,156 not > 0; holdout worst month -53,471 < -13,000; holdout max drawdown 167,831 > 40,000
- **P04-X** (4 Trendline bounce, X): design net -954,141 not > 0; design net without top 5 -992,714 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -68,949 < -13,000; design max drawdown 958,427 > 40,000; holdout net -164,067 not > 0; holdout worst month -55,244 < -13,000; holdout max drawdown 166,017 > 40,000
- **P04-V** (4 Trendline bounce, V): design net -906,276 not > 0; design net without top 5 -937,264 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -55,689 < -13,000; design max drawdown 927,004 > 40,000; holdout net -165,559 not > 0; holdout worst month -50,675 < -13,000; holdout max drawdown 167,743 > 40,000
- **P06-X** (6 Retest of a broken level, X): design net -803,257 not > 0; design net without top 5 -850,854 not > 0; 0 of 5 folds positive (< 3); twin p 0.990 >= 0.05; BH q 1 > 0.1; design worst month -99,309 < -13,000; design max drawdown 867,308 > 40,000; holdout net -175,847 not > 0; holdout worst month -85,122 < -13,000; holdout max drawdown 217,895 > 40,000
- **P06-V** (6 Retest of a broken level, V): design net -830,770 not > 0; design net without top 5 -867,418 not > 0; 0 of 5 folds positive (< 3); twin p 0.985 >= 0.05; BH q 1 > 0.1; design worst month -60,003 < -13,000; design max drawdown 841,465 > 40,000; holdout net -212,723 not > 0; holdout worst month -58,219 < -13,000; holdout max drawdown 224,133 > 40,000
- **P07-X** (7 Consolidation (tight box) breakout, X): design 20 trades < 100; design net -6,636 not > 0; design net without top 5 -10,426 not > 0; 1 of 5 folds positive (< 3); twin p 0.985 >= 0.05; BH q 1 > 0.1; holdout 5 trades < 40; holdout net -3,692 not > 0
- **P07-V** (7 Consolidation (tight box) breakout, V): design 20 trades < 100; design net -3,632 not > 0; design net without top 5 -8,944 not > 0; 1 of 5 folds positive (< 3); twin p 0.925 >= 0.05; BH q 1 > 0.1; holdout 5 trades < 40; holdout net -3,692 not > 0
- **P09-X** (9 Higher high / higher low (buy the new HL), X): design net -584,483 not > 0; design net without top 5 -627,159 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -47,756 < -13,000; design max drawdown 591,545 > 40,000; holdout net -191,186 not > 0; holdout worst month -59,994 < -13,000; holdout max drawdown 231,920 > 40,000
- **P09-V** (9 Higher high / higher low (buy the new HL), V): design net -650,269 not > 0; design net without top 5 -677,781 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -46,215 < -13,000; design max drawdown 650,269 > 40,000; holdout net -222,094 not > 0; holdout worst month -50,123 < -13,000; holdout max drawdown 231,218 > 40,000
- **P10-X** (10 Lower high / lower low (sell the new LH), X): design net -643,381 not > 0; design net without top 5 -680,399 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -72,782 < -13,000; design max drawdown 696,036 > 40,000; holdout net -83,008 not > 0; holdout worst month -31,268 < -13,000; holdout max drawdown 99,390 > 40,000
- **P10-V** (10 Lower high / lower low (sell the new LH), V): design net -651,818 not > 0; design net without top 5 -675,984 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -65,305 < -13,000; design max drawdown 674,694 > 40,000; holdout net -81,352 not > 0; holdout worst month -33,881 < -13,000; holdout max drawdown 96,314 > 40,000
- **P11-X** (11 Double top, X): design net -71,116 not > 0; design net without top 5 -90,295 not > 0; 1 of 5 folds positive (< 3); twin p 0.985 >= 0.05; BH q 1 > 0.1; design worst month -18,078 < -13,000; design max drawdown 87,838 > 40,000; holdout net -29,887 not > 0; holdout worst month -20,217 < -13,000; holdout max drawdown 44,884 > 40,000
- **P11-V** (11 Double top, V): design net -82,487 not > 0; design net without top 5 -97,207 not > 0; 1 of 5 folds positive (< 3); twin p 0.995 >= 0.05; BH q 1 > 0.1; design worst month -13,482 < -13,000; design max drawdown 91,232 > 40,000; holdout net -30,580 not > 0; holdout worst month -18,470 < -13,000
- **P12-X** (12 Double bottom, X): design net -30,523 not > 0; design net without top 5 -56,172 not > 0; 2 of 5 folds positive (< 3); twin p 0.706 >= 0.05; BH q 1 > 0.1; design worst month -15,056 < -13,000; design max drawdown 62,872 > 40,000; holdout net -4,058 not > 0
- **P12-V** (12 Double bottom, V): design net -36,142 not > 0; design net without top 5 -55,185 not > 0; 2 of 5 folds positive (< 3); twin p 0.766 >= 0.05; BH q 1 > 0.1; design max drawdown 55,311 > 40,000
- **P13-X** (13 Head and shoulders, X): BH q 1 > 0.1; holdout 28 trades < 40; holdout net -4,063 not > 0
- **P13-V** (13 Head and shoulders, V): design net without top 5 -8,434 not > 0; 2 of 5 folds positive (< 3); twin p 0.199 >= 0.05; BH q 1 > 0.1; holdout 28 trades < 40; holdout net -1,892 not > 0
- **P14-X** (14 Inverse head and shoulders, X): design net -3,881 not > 0; design net without top 5 -20,731 not > 0; twin p 0.269 >= 0.05; BH q 1 > 0.1; holdout 20 trades < 40
- **P14-V** (14 Inverse head and shoulders, V): design net -15,470 not > 0; design net without top 5 -24,003 not > 0; 1 of 5 folds positive (< 3); twin p 0.587 >= 0.05; BH q 1 > 0.1; holdout 20 trades < 40
- **P15-X** (15 Ascending triangle, X): design net -65,952 not > 0; design net without top 5 -95,169 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design max drawdown 79,744 > 40,000; holdout net -20,350 not > 0
- **P15-V** (15 Ascending triangle, V): design net -76,972 not > 0; design net without top 5 -92,230 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design max drawdown 78,473 > 40,000; holdout net -12,059 not > 0
- **P16-X** (16 Descending triangle, X): design net -3,749 not > 0; design net without top 5 -24,439 not > 0; twin p 0.801 >= 0.05; BH q 1 > 0.1; design worst month -15,894 < -13,000; holdout net -8,493 not > 0
- **P16-V** (16 Descending triangle, V): design net without top 5 -11,054 not > 0; twin p 0.463 >= 0.05; BH q 1 > 0.1
- **P17-X** (17 Symmetrical triangle, X): design net -278,851 not > 0; design net without top 5 -313,951 not > 0; 0 of 5 folds positive (< 3); twin p 0.726 >= 0.05; BH q 1 > 0.1; design worst month -50,368 < -13,000; design max drawdown 291,080 > 40,000; holdout net -83,102 not > 0; holdout worst month -34,818 < -13,000; holdout max drawdown 99,300 > 40,000
- **P17-V** (17 Symmetrical triangle, V): design net -284,013 not > 0; design net without top 5 -307,441 not > 0; 0 of 5 folds positive (< 3); twin p 0.711 >= 0.05; BH q 1 > 0.1; design worst month -38,202 < -13,000; design max drawdown 297,415 > 40,000; holdout net -73,970 not > 0; holdout worst month -19,410 < -13,000; holdout max drawdown 88,424 > 40,000
- **P18-X** (18 Flag, X): design net -581,356 not > 0; design net without top 5 -618,105 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -101,811 < -13,000; design max drawdown 645,359 > 40,000; holdout net -21,227 not > 0; holdout worst month -29,148 < -13,000; holdout max drawdown 72,729 > 40,000
- **P18-V** (18 Flag, V): design net -474,588 not > 0; design net without top 5 -501,588 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -77,629 < -13,000; design max drawdown 514,045 > 40,000; holdout net -52,930 not > 0; holdout worst month -24,888 < -13,000; holdout max drawdown 87,861 > 40,000
- **P19-X** (19 Wedge (rising = put, falling = call), X): design net -524,678 not > 0; design net without top 5 -560,574 not > 0; 0 of 5 folds positive (< 3); design worst month -51,995 < -13,000; design max drawdown 557,605 > 40,000; holdout net -41,248 not > 0; holdout worst month -35,649 < -13,000; holdout max drawdown 84,645 > 40,000
- **P19-V** (19 Wedge (rising = put, falling = call), V): design net -565,530 not > 0; design net without top 5 -587,113 not > 0; 0 of 5 folds positive (< 3); design worst month -48,019 < -13,000; design max drawdown 586,225 > 40,000; holdout net -29,121 not > 0; holdout worst month -24,072 < -13,000; holdout max drawdown 67,651 > 40,000
- **P21-X** (21 Order block, X): design net -417,194 not > 0; design net without top 5 -444,007 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -37,475 < -13,000; design max drawdown 424,546 > 40,000; holdout net -106,945 not > 0; holdout worst month -33,767 < -13,000; holdout max drawdown 123,313 > 40,000
- **P21-V** (21 Order block, V): design net -376,311 not > 0; design net without top 5 -402,827 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -36,243 < -13,000; design max drawdown 388,966 > 40,000; holdout net -116,197 not > 0; holdout worst month -38,129 < -13,000; holdout max drawdown 124,907 > 40,000
- **P22-X** (22 Fair value gap, X): design net -1,042,169 not > 0; design net without top 5 -1,086,355 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -86,534 < -13,000; design max drawdown 1,061,932 > 40,000; holdout net -188,743 not > 0; holdout worst month -49,304 < -13,000; holdout max drawdown 227,690 > 40,000
- **P22-V** (22 Fair value gap, V): design net -994,235 not > 0; design net without top 5 -1,033,904 not > 0; 0 of 5 folds positive (< 3); twin p 1.000 >= 0.05; BH q 1 > 0.1; design worst month -72,522 < -13,000; design max drawdown 1,022,324 > 40,000; holdout net -248,448 not > 0; holdout worst month -63,597 < -13,000; holdout max drawdown 281,311 > 40,000
- **P24-X** (24 Equal highs / lows sweep, X): design net -280,788 not > 0; design net without top 5 -307,461 not > 0; 0 of 5 folds positive (< 3); BH q 1 > 0.1; design worst month -23,142 < -13,000; design max drawdown 286,933 > 40,000; holdout net -78,667 not > 0; holdout worst month -24,559 < -13,000; holdout max drawdown 78,667 > 40,000
- **P24-V** (24 Equal highs / lows sweep, V): design net -353,583 not > 0; design net without top 5 -376,596 not > 0; 0 of 5 folds positive (< 3); twin p 0.512 >= 0.05; BH q 1 > 0.1; design worst month -25,286 < -13,000; design max drawdown 353,583 > 40,000; holdout net -80,711 not > 0; holdout worst month -21,772 < -13,000; holdout max drawdown 84,882 > 40,000
- **P25-X** (25 Candlestick rejection (long wick), X): design net -359,779 not > 0; design net without top 5 -406,287 not > 0; 1 of 5 folds positive (< 3); design worst month -59,751 < -13,000; design max drawdown 429,904 > 40,000; holdout net -204,593 not > 0; holdout worst month -72,237 < -13,000; holdout max drawdown 207,609 > 40,000
- **P25-V** (25 Candlestick rejection (long wick), V): design net -446,788 not > 0; design net without top 5 -474,662 not > 0; 1 of 5 folds positive (< 3); design worst month -57,860 < -13,000; design max drawdown 475,054 > 40,000; holdout net -178,173 not > 0; holdout worst month -70,754 < -13,000; holdout max drawdown 189,822 > 40,000

## Net by index (design)

| test | NIFTY | BANKNIFTY | FINNIFTY | MIDCPNIFTY | SENSEX |
|---|---:|---:|---:|---:|---:|
| P01-X | -155,647 | -234,305 | -187,438 | -291,324 | -141,072 |
| P01-V | -161,669 | -268,598 | -203,075 | -269,782 | -142,367 |
| P02-X | -168,376 | -187,311 | -81,695 | -75,990 | -41,535 |
| P02-V | -132,222 | -210,588 | -112,885 | -106,136 | -37,470 |
| P03-X | -132,020 | -182,755 | -201,473 | -249,718 | -93,508 |
| P03-V | -116,686 | -233,750 | -242,291 | -212,385 | -58,615 |
| P04-X | -163,163 | -283,222 | -258,198 | -179,977 | -69,581 |
| P04-V | -148,873 | -274,455 | -248,180 | -169,883 | -64,885 |
| P06-X | -190,811 | -234,700 | -151,122 | -93,647 | -132,978 |
| P06-V | -184,606 | -207,751 | -204,649 | -115,534 | -118,230 |
| P07-X | -634 | -613 | -2,779 | -1,437 | -1,173 |
| P07-V | -721 | 360 | -2,536 | 438 | -1,173 |
| P09-X | -70,106 | -147,708 | -150,278 | -194,691 | -21,700 |
| P09-V | -87,278 | -159,024 | -188,161 | -182,929 | -32,877 |
| P10-X | -111,783 | -143,199 | -206,389 | -174,665 | -7,345 |
| P10-V | -117,886 | -185,819 | -192,916 | -143,398 | -11,801 |
| P11-X | -20,666 | -22,781 | -10,849 | -14,702 | -2,118 |
| P11-V | -16,074 | -26,254 | -11,565 | -26,546 | -2,046 |
| P12-X | -11,813 | -14,371 | 128 | -15,689 | 11,222 |
| P12-V | -8,305 | -22,964 | -7,107 | -9,060 | 11,295 |
| P13-X | 11,367 | -6,790 | 9,096 | 11,018 | 9,394 |
| P13-V | 2,346 | -2,154 | -1,292 | 3,778 | 1,786 |
| P14-X | 32 | -4,358 | -5,030 | 6,704 | -1,229 |
| P14-V | -3,330 | -4,666 | -5,294 | -572 | -1,607 |
| P15-X | -20,998 | -2,570 | -16,993 | -15,057 | -10,334 |
| P15-V | -20,475 | -8,330 | -23,342 | -15,011 | -9,814 |
| P16-X | -136 | -4,744 | 12,758 | -5,997 | -5,630 |
| P16-V | 3,655 | -8,022 | 10,962 | 6,268 | -6,214 |
| P17-X | -53,759 | -52,985 | -56,750 | -68,450 | -46,906 |
| P17-V | -64,392 | -23,919 | -50,270 | -75,785 | -69,646 |
| P18-X | -173,778 | -72,602 | -94,719 | -155,350 | -84,907 |
| P18-V | -137,182 | -62,836 | -105,099 | -99,510 | -69,961 |
| P19-X | -99,646 | -119,181 | -48,577 | -171,633 | -85,641 |
| P19-V | -98,480 | -153,849 | -85,581 | -164,580 | -63,041 |
| P21-X | -105,460 | -84,614 | -75,133 | -89,025 | -62,962 |
| P21-V | -82,767 | -90,034 | -50,591 | -91,825 | -61,094 |
| P22-X | -232,118 | -292,068 | -222,945 | -180,837 | -114,201 |
| P22-V | -245,369 | -287,208 | -200,417 | -142,649 | -118,592 |
| P24-X | -30,831 | -53,989 | -99,090 | -61,386 | -35,493 |
| P24-V | -52,243 | -61,186 | -117,227 | -87,681 | -35,246 |
| P25-X | -55,910 | -90,389 | -120,284 | -39,553 | -53,644 |
| P25-V | -73,035 | -120,888 | -146,541 | -42,218 | -64,106 |
