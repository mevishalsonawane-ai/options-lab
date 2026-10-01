## Scalping XAUUSD: the bars and a rule search (research/gold_scalp.py)

Dukascopy XAUUSD 1-minute, 2023-10-02 .. 2026-09-25. Fitting years: Oct 2023 - Sep 2024, Oct 2024 - Sep 2025; held out: Oct 2025 - Sep 2026.

### Part 1: the bars, by UTC hour (Oct 2023 - Sep 2026)

Cost of one trade: $0.37 an ounce. 'Moves > cost' = the share of 5-minute bars whose high-low range is more than twice the cost (room for a target past the cost). Autocorrelation > 0: moves tend to continue; < 0: to reverse.

| hour (UTC) | IST | median 1-min move | median 5-min range | 5-min bars with range > 2x cost | mean 5-min drift | autocorr 1-min | autocorr 5-min |
|---|---|---|---|---|---|---|---|
| 00:00 | 05:30 | $0.37 | $2.03 | 86% | +0.035 | +0.008 | +0.031 |
| 01:00 | 06:30 | $0.53 | $2.93 | 95% | +0.058 | -0.007 | +0.060 |
| 02:00 | 07:30 | $0.41 | $2.31 | 91% | -0.046 | -0.007 | +0.089 |
| 03:00 | 08:30 | $0.33 | $1.88 | 86% | +0.024 | -0.021 | -0.033 |
| 04:00 | 09:30 | $0.27 | $1.55 | 80% | +0.022 | -0.025 | -0.107 |
| 05:00 | 10:30 | $0.37 | $2.07 | 87% | -0.058 | +0.016 | +0.056 |
| 06:00 | 11:30 | $0.43 | $2.36 | 93% | +0.046 | +0.014 | -0.070 |
| 07:00 | 12:30 | $0.44 | $2.39 | 96% | +0.009 | -0.017 | +0.052 |
| 08:00 | 13:30 | $0.44 | $2.31 | 96% | -0.001 | -0.014 | -0.084 |
| 09:00 | 14:30 | $0.40 | $2.13 | 94% | -0.035 | -0.003 | -0.016 |
| 10:00 | 15:30 | $0.38 | $2.03 | 92% | +0.014 | -0.020 | -0.020 |
| 11:00 | 16:30 | $0.40 | $2.16 | 94% | +0.054 | +0.055 | +0.050 |
| 12:00 | 17:30 | $0.53 | $2.96 | 96% | +0.042 | -0.030 | +0.003 |
| 13:00 | 18:30 | $0.76 | $4.10 | 99% | -0.036 | -0.021 | -0.017 |
| 14:00 | 19:30 | $0.76 | $4.02 | 100% | +0.003 | -0.019 | -0.005 |
| 15:00 | 20:30 | $0.63 | $3.24 | 99% | -0.053 | +0.014 | +0.049 |
| 16:00 | 21:30 | $0.48 | $2.50 | 98% | +0.028 | +0.023 | +0.002 |
| 17:00 | 22:30 | $0.41 | $2.18 | 96% | -0.032 | -0.013 | -0.036 |
| 18:00 | 23:30 | $0.36 | $1.89 | 92% | -0.020 | +0.018 | -0.006 |
| 19:00 | 00:30 | $0.34 | $1.78 | 88% | +0.011 | -0.024 | +0.015 |
| 20:00 | 01:30 | $0.27 | $1.42 | 79% | -0.006 | -0.012 | +0.007 |
| 21:00 | 02:30 | $0.18 | $0.98 | 60% | +0.009 | -0.056 | -0.063 |
| 22:00 | 03:30 | $0.28 | $1.67 | 77% | +0.129 | -0.065 | -0.013 |
| 23:00 | 04:30 | $0.26 | $1.40 | 73% | +0.142 | -0.018 | -0.014 |

### Part 2: every rule, best fitting years first

108 rule versions; 2 positive over three years; 0 positive in BOTH the fitting years and the held-out year with 200+ trades.

| rule | direction | fitting (t) | held out (t) | 3 years | trades | win | per trade | max drawdown |
|---|---|---|---|---|---|---|---|---|
| M5 big bar follow, target 1 / stop 1.5 ATR | buys only | -5,025 (-1.08) | +8,836 (0.89) | +3,810 | 350 | 57% | +10.9 | -6,272 |
| M5 big bar fade, target 1 / stop 1.5 ATR | buys only | -5,073 (-0.94) | +979 (0.07) | -4,095 | 379 | 57% | -10.8 | -15,725 |
| M5 big bar follow, target 1.5 / stop 1 ATR | buys only | -5,645 (-1.22) | -2,811 (-0.27) | -8,456 | 350 | 38% | -24.2 | -10,209 |
| M5 big bar fade, target 1 / stop 1 ATR | buys only | -5,906 (-1.35) | +8,630 (0.71) | +2,724 | 379 | 47% | +7.2 | -12,940 |
| M5 big bar fade, target 1.5 / stop 1 ATR | buys only | -7,093 (-1.35) | +187 (0.01) | -6,907 | 378 | 38% | -18.3 | -13,940 |
| M5 big bar follow, target 1 / stop 1 ATR | buys only | -7,114 (-1.87) | +4,472 (0.53) | -2,642 | 350 | 46% | -7.5 | -9,974 |
| M5 big bar fade, target 1 / stop 1.5 ATR | both ways | -12,087 (-1.69) | -9,550 (-0.53) | -21,637 | 729 | 56% | -29.7 | -28,507 |
| M5 big bar follow, target 1.5 / stop 1 ATR | both ways | -12,623 (-1.80) | -10,190 (-0.57) | -22,814 | 729 | 37% | -31.3 | -33,086 |
| M5 big bar follow, target 1 / stop 1 ATR | both ways | -14,324 (-2.47) | -11,299 (-0.76) | -25,623 | 729 | 45% | -35.1 | -34,130 |
| M5 big bar fade, target 1 / stop 1 ATR | both ways | -14,791 (-2.55) | -3,860 (-0.26) | -18,651 | 729 | 46% | -25.6 | -24,288 |
| M1 session ORB (07:00 / 13:30, 15 min), target 1 / stop 1.5 ATR | buys only | -15,701 (-4.34) | -27,266 (-3.81) | -42,967 | 719 | 48% | -59.8 | -43,134 |
| M1 session ORB (07:00 / 13:30, 15 min), target 1 / stop 1 ATR | buys only | -17,659 (-6.31) | -24,068 (-4.32) | -41,727 | 719 | 36% | -58.0 | -42,083 |
| M5 big bar follow, target 1 / stop 1.5 ATR | both ways | -17,975 (-2.50) | +2,723 (0.15) | -15,252 | 729 | 55% | -20.9 | -25,466 |
| M5 session ORB (07:00 / 13:30, 15 min), target 1 / stop 1 ATR | buys only | -18,758 (-3.14) | -16,092 (-1.25) | -34,851 | 723 | 42% | -48.2 | -35,011 |
| M5 big bar fade, target 1.5 / stop 1 ATR | both ways | -19,348 (-2.82) | -18,233 (-1.03) | -37,581 | 728 | 36% | -51.6 | -43,045 |
| M5 session ORB (07:00 / 13:30, 15 min), target 1 / stop 1.5 ATR | buys only | -19,501 (-2.60) | -16,653 (-1.08) | -36,154 | 723 | 54% | -50.0 | -37,063 |
| M1 session ORB (07:00 / 13:30, 15 min), target 1.5 / stop 1 ATR | buys only | -19,736 (-6.11) | -29,212 (-4.57) | -48,949 | 719 | 26% | -68.1 | -48,978 |
| M1 big bar fade, target 1 / stop 1.5 ATR | buys only | -21,914 (-4.23) | -28,504 (-2.18) | -50,418 | 1700 | 52% | -29.7 | -51,367 |
| M5 session ORB (07:00 / 13:30, 15 min), target 1.5 / stop 1 ATR | buys only | -22,420 (-3.19) | -16,822 (-1.05) | -39,242 | 723 | 34% | -54.3 | -40,228 |
| M1 big bar fade, target 1.5 / stop 1 ATR | buys only | -23,236 (-4.56) | -14,766 (-1.10) | -38,002 | 1700 | 32% | -22.4 | -38,776 |
| M1 big bar fade, target 1 / stop 1 ATR | buys only | -23,242 (-5.50) | -23,441 (-2.18) | -46,683 | 1700 | 40% | -27.5 | -47,822 |
| M5 session ORB (07:00 / 13:30, 15 min), target 1 / stop 1 ATR | both ways | -28,115 (-3.35) | -27,877 (-1.60) | -55,992 | 1401 | 44% | -40.0 | -56,101 |
| M5 session ORB (07:00 / 13:30, 15 min), target 1.5 / stop 1 ATR | both ways | -32,175 (-3.21) | -25,980 (-1.22) | -58,155 | 1401 | 35% | -41.5 | -58,321 |
| M5 session ORB (07:00 / 13:30, 15 min), target 1 / stop 1.5 ATR | both ways | -34,247 (-3.28) | -22,927 (-1.06) | -57,174 | 1401 | 55% | -40.8 | -57,573 |
| M1 session ORB (07:00 / 13:30, 15 min), target 1 / stop 1.5 ATR | both ways | -35,270 (-6.84) | -43,786 (-4.14) | -79,056 | 1419 | 48% | -55.7 | -79,492 |
| M1 session ORB (07:00 / 13:30, 15 min), target 1.5 / stop 1 ATR | both ways | -36,653 (-7.73) | -46,523 (-4.92) | -83,176 | 1419 | 27% | -58.6 | -83,773 |
| M1 session ORB (07:00 / 13:30, 15 min), target 1 / stop 1 ATR | both ways | -36,731 (-9.18) | -35,668 (-4.33) | -72,400 | 1419 | 36% | -51.0 | -72,774 |
| M1 big bar follow, target 1.5 / stop 1 ATR | buys only | -38,634 (-8.79) | -18,790 (-1.98) | -57,424 | 1536 | 28% | -37.4 | -60,278 |
| M1 big bar follow, target 1 / stop 1 ATR | buys only | -41,421 (-11.08) | -30,377 (-3.93) | -71,798 | 1536 | 33% | -46.7 | -73,925 |
| M1 big bar follow, target 1 / stop 1.5 ATR | buys only | -43,131 (-9.02) | -31,734 (-3.28) | -74,865 | 1535 | 45% | -48.8 | -77,123 |
| M1 big bar fade, target 1.5 / stop 1 ATR | both ways | -55,630 (-8.21) | -27,325 (-1.66) | -82,955 | 3236 | 31% | -25.6 | -82,971 |
| M1 big bar fade, target 1 / stop 1 ATR | both ways | -55,941 (-9.83) | -46,179 (-3.49) | -102,119 | 3236 | 38% | -31.6 | -102,072 |
| M1 big bar fade, target 1 / stop 1.5 ATR | both ways | -58,492 (-8.30) | -52,431 (-3.22) | -110,923 | 3236 | 50% | -34.3 | -110,950 |
| M5 fade 20, target 1 / stop 1.5 ATR | buys only | -73,030 (-4.79) | -5,479 (-0.16) | -78,509 | 3244 | 56% | -24.2 | -105,460 |
| M5 fade 20, target 1.5 / stop 1 ATR | buys only | -81,419 (-5.09) | -1,789 (-0.05) | -83,208 | 3733 | 36% | -22.3 | -101,741 |
| M5 breakout 20, target 1.5 / stop 1 ATR | buys only | -85,260 (-5.92) | -123,870 (-4.44) | -209,130 | 3609 | 36% | -57.9 | -211,309 |
| M5 breakout 20, target 1 / stop 1.5 ATR | buys only | -87,082 (-5.55) | -120,053 (-3.83) | -207,135 | 3985 | 54% | -52.0 | -214,443 |
| M1 big bar follow, target 1.5 / stop 1 ATR | both ways | -90,596 (-14.09) | -49,526 (-3.12) | -140,123 | 3236 | 27% | -43.3 | -142,676 |
| M1 big bar follow, target 1 / stop 1 ATR | both ways | -91,215 (-16.57) | -65,233 (-4.95) | -156,448 | 3236 | 33% | -48.3 | -157,487 |
| M5 fade 20, target 1 / stop 1 ATR | buys only | -91,467 (-6.80) | +19,326 (0.65) | -72,141 | 3797 | 45% | -19.0 | -116,215 |
| M1 big bar follow, target 1 / stop 1.5 ATR | both ways | -94,310 (-13.31) | -65,585 (-3.92) | -159,895 | 3235 | 45% | -49.4 | -161,050 |
| M5 fade 10, target 1 / stop 1.5 ATR | buys only | -100,788 (-5.62) | -46,258 (-1.19) | -147,046 | 4757 | 55% | -30.9 | -169,277 |
| M5 breakout 20, target 1 / stop 1 ATR | buys only | -103,642 (-7.91) | -105,382 (-4.05) | -209,024 | 4148 | 44% | -50.4 | -215,906 |
| M5 breakout 10, target 1.5 / stop 1 ATR | buys only | -104,697 (-6.17) | -150,816 (-4.44) | -255,513 | 4997 | 36% | -51.1 | -255,555 |
| M5 breakout 10, target 1 / stop 1.5 ATR | buys only | -109,115 (-5.89) | -119,253 (-3.14) | -228,368 | 5521 | 54% | -41.4 | -229,538 |
| M5 breakout 10, target 1 / stop 1 ATR | buys only | -127,160 (-8.19) | -117,792 (-3.68) | -244,953 | 5751 | 44% | -42.6 | -246,114 |
| M5 rsi2 follow, target 1 / stop 1.5 ATR | buys only | -127,188 (-6.23) | -107,530 (-2.58) | -234,718 | 6629 | 55% | -35.4 | -237,729 |
| M5 fade 10, target 1.5 / stop 1 ATR | buys only | -127,717 (-6.77) | -30,443 (-0.74) | -158,160 | 5505 | 36% | -28.7 | -170,452 |
| M5 rsi2 follow, target 1.5 / stop 1 ATR | buys only | -133,153 (-7.17) | -155,405 (-4.01) | -288,558 | 6037 | 36% | -47.8 | -288,441 |
| M5 fade 10, target 1 / stop 1 ATR | buys only | -135,050 (-8.44) | -10,749 (-0.31) | -145,799 | 5619 | 44% | -25.9 | -170,462 |
| M5 rsi2 revert, target 1 / stop 1.5 ATR | buys only | -141,643 (-6.96) | -79,263 (-1.79) | -220,906 | 6134 | 54% | -36.0 | -224,002 |
| M5 fade 20, target 1 / stop 1.5 ATR | both ways | -158,810 (-7.41) | +12,370 (0.28) | -146,440 | 6985 | 55% | -21.0 | -217,093 |
| M5 breakout 20, target 1.5 / stop 1 ATR | both ways | -160,177 (-7.78) | -201,628 (-4.69) | -361,805 | 6749 | 35% | -53.6 | -362,239 |
| M5 rsi2 follow, target 1 / stop 1 ATR | buys only | -161,185 (-9.31) | -143,723 (-4.01) | -304,908 | 7069 | 44% | -43.1 | -306,063 |
| M5 breakout 20, target 1 / stop 1.5 ATR | both ways | -184,073 (-8.23) | -197,600 (-4.22) | -381,673 | 7427 | 54% | -51.4 | -386,295 |
| M5 rsi2 revert, target 1.5 / stop 1 ATR | buys only | -186,092 (-8.74) | -95,100 (-2.06) | -281,192 | 7185 | 35% | -39.1 | -288,752 |
| M5 rsi2 revert, target 1 / stop 1 ATR | buys only | -189,559 (-10.22) | -40,438 (-1.02) | -229,997 | 7637 | 44% | -30.1 | -240,309 |
| M5 breakout 20, target 1 / stop 1 ATR | both ways | -194,437 (-10.52) | -201,556 (-5.16) | -395,992 | 7706 | 43% | -51.4 | -396,535 |
| M5 fade 20, target 1 / stop 1 ATR | both ways | -199,134 (-10.44) | +13,534 (0.34) | -185,599 | 8258 | 44% | -22.5 | -252,324 |
| M5 fade 20, target 1.5 / stop 1 ATR | both ways | -202,973 (-9.07) | +19,884 (0.42) | -183,088 | 8113 | 36% | -22.6 | -254,015 |
| M5 breakout 10, target 1.5 / stop 1 ATR | both ways | -224,180 (-9.30) | -219,536 (-4.29) | -443,716 | 9572 | 36% | -46.4 | -444,448 |
| M5 fade 10, target 1 / stop 1.5 ATR | both ways | -228,114 (-9.06) | -40,686 (-0.78) | -268,799 | 9930 | 54% | -27.1 | -292,852 |
| M5 breakout 10, target 1 / stop 1.5 ATR | both ways | -243,055 (-9.19) | -213,170 (-3.84) | -456,225 | 10575 | 54% | -43.1 | -457,794 |
| M5 breakout 10, target 1 / stop 1 ATR | both ways | -255,093 (-11.63) | -232,539 (-4.98) | -487,632 | 10990 | 44% | -44.4 | -488,066 |
| M5 rsi2 follow, target 1.5 / stop 1 ATR | both ways | -270,177 (-9.99) | -240,573 (-4.12) | -510,750 | 12019 | 36% | -42.5 | -511,235 |
| M5 rsi2 follow, target 1 / stop 1.5 ATR | both ways | -276,957 (-9.45) | -182,730 (-2.94) | -459,687 | 13114 | 55% | -35.1 | -465,290 |
| M5 fade 10, target 1.5 / stop 1 ATR | both ways | -294,574 (-11.15) | -53,068 (-0.95) | -347,642 | 11559 | 36% | -30.1 | -361,773 |
| M5 fade 10, target 1 / stop 1 ATR | both ways | -295,061 (-13.05) | -49,464 (-1.05) | -344,525 | 11819 | 44% | -29.2 | -361,074 |
| M5 rsi2 revert, target 1 / stop 1.5 ATR | both ways | -309,247 (-10.97) | -121,789 (-2.05) | -431,037 | 12392 | 54% | -34.8 | -435,688 |
| M5 rsi2 follow, target 1 / stop 1 ATR | both ways | -332,261 (-13.37) | -280,940 (-5.28) | -613,201 | 14060 | 44% | -43.6 | -614,149 |
| M1 fade 20, target 1 / stop 1.5 ATR | buys only | -387,276 (-23.85) | -154,392 (-4.39) | -541,668 | 18659 | 49% | -29.0 | -546,276 |
| M5 rsi2 revert, target 1 / stop 1 ATR | both ways | -394,796 (-15.31) | -98,014 (-1.82) | -492,811 | 15405 | 43% | -32.0 | -500,549 |
| M5 rsi2 revert, target 1.5 / stop 1 ATR | both ways | -396,282 (-13.46) | -128,014 (-2.05) | -524,296 | 14549 | 35% | -36.0 | -533,991 |
| M1 breakout 20, target 1.5 / stop 1 ATR | buys only | -437,214 (-31.53) | -316,960 (-10.58) | -754,173 | 18056 | 28% | -41.8 | -754,173 |
| M1 breakout 20, target 1 / stop 1.5 ATR | buys only | -461,999 (-29.54) | -365,776 (-11.05) | -827,775 | 18773 | 47% | -44.1 | -828,147 |
| M1 fade 20, target 1.5 / stop 1 ATR | buys only | -464,424 (-27.87) | -130,429 (-3.54) | -594,853 | 22089 | 30% | -26.9 | -600,234 |
| M1 fade 20, target 1 / stop 1 ATR | buys only | -475,861 (-33.50) | -175,050 (-5.71) | -650,911 | 22405 | 37% | -29.1 | -653,942 |
| M1 breakout 20, target 1 / stop 1 ATR | buys only | -505,669 (-39.78) | -391,596 (-14.27) | -897,264 | 20368 | 34% | -44.1 | -897,225 |
| M1 fade 10, target 1 / stop 1.5 ATR | buys only | -578,597 (-30.23) | -233,678 (-5.70) | -812,275 | 26833 | 48% | -30.3 | -816,155 |
| M1 breakout 10, target 1.5 / stop 1 ATR | buys only | -615,324 (-37.23) | -423,769 (-11.73) | -1,039,093 | 25369 | 28% | -41.0 | -1,039,480 |
| M1 breakout 10, target 1 / stop 1.5 ATR | buys only | -655,587 (-35.24) | -473,652 (-12.03) | -1,129,239 | 26349 | 47% | -42.9 | -1,129,796 |
| M1 fade 10, target 1.5 / stop 1 ATR | buys only | -682,974 (-34.68) | -233,325 (-5.41) | -916,299 | 32023 | 30% | -28.6 | -922,281 |
| M1 fade 10, target 1 / stop 1 ATR | buys only | -709,974 (-42.21) | -258,003 (-7.14) | -967,976 | 32707 | 36% | -29.6 | -971,416 |
| M1 breakout 10, target 1 / stop 1 ATR | buys only | -714,305 (-47.11) | -522,085 (-15.75) | -1,236,390 | 28694 | 34% | -43.1 | -1,236,861 |
| M1 rsi2 revert, target 1 / stop 1.5 ATR | buys only | -721,451 (-34.53) | -319,008 (-7.10) | -1,040,459 | 32469 | 48% | -32.0 | -1,041,817 |
| M1 rsi2 follow, target 1.5 / stop 1 ATR | buys only | -740,458 (-40.93) | -533,728 (-13.35) | -1,274,186 | 30736 | 28% | -41.5 | -1,275,516 |
| M1 rsi2 follow, target 1 / stop 1.5 ATR | buys only | -780,742 (-38.35) | -574,431 (-13.07) | -1,355,173 | 31531 | 47% | -43.0 | -1,355,894 |
| M1 fade 20, target 1 / stop 1.5 ATR | both ways | -845,071 (-37.60) | -295,800 (-6.31) | -1,140,871 | 38078 | 48% | -30.0 | -1,145,159 |
| M1 rsi2 follow, target 1 / stop 1 ATR | buys only | -856,648 (-51.46) | -626,698 (-16.88) | -1,483,346 | 35070 | 34% | -42.3 | -1,484,517 |
| M1 rsi2 revert, target 1.5 / stop 1 ATR | buys only | -864,950 (-40.42) | -323,334 (-6.80) | -1,188,284 | 39252 | 29% | -30.3 | -1,189,567 |
| M1 breakout 20, target 1.5 / stop 1 ATR | both ways | -900,444 (-44.87) | -602,704 (-13.33) | -1,503,147 | 35578 | 27% | -42.2 | -1,503,752 |
| M1 rsi2 revert, target 1 / stop 1 ATR | buys only | -934,360 (-50.08) | -378,457 (-9.42) | -1,312,817 | 41619 | 36% | -31.5 | -1,313,365 |
| M1 breakout 20, target 1 / stop 1.5 ATR | both ways | -957,226 (-42.17) | -726,233 (-14.69) | -1,683,459 | 36928 | 46% | -45.6 | -1,683,525 |
| M1 fade 20, target 1.5 / stop 1 ATR | both ways | -1,018,685 (-44.23) | -280,147 (-5.62) | -1,298,832 | 45570 | 30% | -28.5 | -1,301,188 |
| M1 breakout 20, target 1 / stop 1 ATR | both ways | -1,019,187 (-55.54) | -736,499 (-18.09) | -1,755,686 | 39987 | 34% | -43.9 | -1,755,636 |
| M1 fade 20, target 1 / stop 1 ATR | both ways | -1,035,586 (-52.49) | -363,576 (-8.70) | -1,399,162 | 46225 | 36% | -30.3 | -1,401,743 |
| M1 fade 10, target 1 / stop 1.5 ATR | both ways | -1,221,863 (-45.89) | -445,459 (-8.03) | -1,667,322 | 54101 | 48% | -30.8 | -1,673,092 |
| M1 breakout 10, target 1.5 / stop 1 ATR | both ways | -1,268,230 (-53.25) | -837,024 (-15.64) | -2,105,254 | 50540 | 28% | -41.7 | -2,105,786 |
| M1 breakout 10, target 1 / stop 1.5 ATR | both ways | -1,353,705 (-50.43) | -953,647 (-16.35) | -2,307,353 | 52421 | 46% | -44.0 | -2,307,285 |
| M1 breakout 10, target 1 / stop 1 ATR | both ways | -1,446,293 (-66.41) | -1,014,938 (-20.94) | -2,461,231 | 57016 | 34% | -43.2 | -2,461,403 |
| M1 fade 10, target 1.5 / stop 1 ATR | both ways | -1,459,842 (-53.38) | -501,860 (-8.55) | -1,961,702 | 65126 | 29% | -30.1 | -1,965,142 |
| M1 rsi2 revert, target 1 / stop 1.5 ATR | both ways | -1,499,928 (-51.69) | -577,520 (-9.43) | -2,077,448 | 65111 | 47% | -31.9 | -2,077,807 |
| M1 fade 10, target 1 / stop 1 ATR | both ways | -1,503,502 (-64.09) | -541,044 (-10.87) | -2,044,545 | 66445 | 36% | -30.8 | -2,048,207 |
| M1 rsi2 follow, target 1.5 / stop 1 ATR | both ways | -1,522,571 (-58.43) | -985,059 (-16.68) | -2,507,630 | 61559 | 28% | -40.7 | -2,509,502 |
| M1 rsi2 follow, target 1 / stop 1.5 ATR | both ways | -1,595,259 (-54.32) | -1,132,563 (-17.52) | -2,727,822 | 63159 | 47% | -43.2 | -2,727,882 |
| M1 rsi2 follow, target 1 / stop 1 ATR | both ways | -1,741,660 (-72.74) | -1,189,021 (-22.01) | -2,930,682 | 70246 | 34% | -41.7 | -2,932,074 |
| M1 rsi2 revert, target 1.5 / stop 1 ATR | both ways | -1,834,518 (-61.69) | -611,639 (-9.39) | -2,446,157 | 79368 | 29% | -30.8 | -2,447,940 |
| M1 rsi2 revert, target 1 / stop 1 ATR | both ways | -1,945,220 (-74.83) | -719,208 (-12.94) | -2,664,428 | 83824 | 35% | -31.8 | -2,665,062 |
### Verdict

No scalping rule survives costs. Gold's 1- and 5-minute moves are close to random (lag-1 autocorrelation within
±0.11 in every hour), and each trade pays about $0.37 an ounce ($37 a lot), a large share of a typical move: the
1-minute rules lose $0.5-1.4 million a lot over the fitting years alone, and none of the 108 versions is positive in
both the fitting years and the held-out year. The one pattern the bars show, an up-drift in the hours after the
daily reopen, is tested in research/GOLD_REOPEN.md.
