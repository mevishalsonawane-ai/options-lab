# R73 report: Donchian with a trend filter or a 1-minute candle wait (tables)

Final period 1 Apr - 2026-10-13 (133 sessions), read once (2026-10-16T14:49:58+00:00). Design 20 Nov 2024 - 31 Mar 2026. 1 lot per trade, net after dated charges and R17 slippage. Signal source: the index (Donchian 20 on 5-minute bars, every filter on the index).

C's pick on design (exits P): T2 + W2H; walk-forward quarter picks: T4+W3HD, T2+W3HD, T5+W3HD, T5+W2H.

## Final period, exits P (paper today: -15%, A4 ladder + L25, 30 min)

| rule | trades | net | win | avg win / loss | worst day | max DD | green days | days | design net | WF net | twin p (q) | skip p (q) | losers avoided | winners missed (big +30%) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Donchian 20 (baseline)** | 1,781 | Rs 81,845 | 44% | Rs 1,609 / -Rs 1,183 | -Rs 23,012 | Rs 91,626 | 47% | 133 | Rs 9,998 | Rs 1,75,789 | - | - | - | - | baseline |
| T1 5-min close beyond its EMA 20 | 1,781 | Rs 79,224 | 44% | Rs 1,611 / -Rs 1,184 | -Rs 23,012 | Rs 91,626 | 47% | 133 | Rs 10,019 | Rs 1,75,810 | 0.004 (0.0131) | 1.0 (1.0) | 1 / Rs 907 | 1 / Rs 162 (0 / Rs 0) | PASS |
| T2 5-min close beyond its EMA 50 | 1,691 | Rs 94,768 | 44% | Rs 1,642 / -Rs 1,190 | -Rs 20,307 | Rs 97,928 | 47% | 133 | Rs 79,607 | Rs 2,43,221 | 0.004 (0.0131) | 0.1397 (0.2515) | 114 / Rs 1,30,357 | 97 / Rs 1,42,473 (7 / Rs 30,049) | PASS |
| T3 15-min EMA 20 rising (falling for puts) | 1,505 | -Rs 44,549 | 43% | Rs 1,563 / -Rs 1,217 | -Rs 19,790 | Rs 1,52,997 | 41% | 133 | -Rs 1,07,467 | Rs 59,381 | 0.5788 (0.6579) | 1.0 (1.0) | 327 / Rs 3,79,425 | 309 / Rs 5,18,671 (27 / Rs 1,22,416) | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0 |
| T4 1-hour EMA 20 rising (falling for puts) | 1,064 | -Rs 1,01,478 | 40% | Rs 1,535 / -Rs 1,201 | -Rs 20,318 | Rs 1,58,117 | 37% | 133 | -Rs 57,001 | Rs 50,749 | 0.9441 (0.9461) | 1.0 (1.0) | 514 / Rs 6,03,036 | 448 / Rs 7,74,056 (39 / Rs 1,92,338) | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0 |
| T5 ADX 14 above 20 | 1,496 | Rs 41,716 | 45% | Rs 1,561 / -Rs 1,209 | -Rs 36,006 | Rs 93,384 | 45% | 132 | -Rs 87,955 | Rs 63,545 | 0.0878 (0.1374) | 0.8283 (1.0) | 306 / Rs 3,27,012 | 259 / Rs 4,18,853 (21 / Rs 1,06,577) | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0 |
| T6 ADX 14 above 25 | 1,167 | -Rs 31,371 | 43% | Rs 1,514 / -Rs 1,178 | -Rs 24,693 | Rs 91,392 | 40% | 132 | -Rs 1,74,950 | -Rs 7,946 | 0.7864 (0.8475) | 0.996 (1.0) | 550 / Rs 6,35,673 | 448 / Rs 7,49,314 (36 / Rs 1,71,124) | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| T7 Supertrend (10, 3) on 5-min bars with the break | 1,670 | Rs 1,34,743 | 45% | Rs 1,651 / -Rs 1,197 | -Rs 21,578 | Rs 81,683 | 46% | 133 | Rs 50,425 | Rs 2,13,748 | 0.002 (0.0131) | 0.006 (0.018) | 162 / Rs 1,70,175 | 111 / Rs 1,61,923 (5 / Rs 23,400) | PASS |
| T8 close beyond the day's average typical price (time-weighted VWAP stand-in; the index has no volume) | 1,667 | Rs 28,458 | 44% | Rs 1,538 / -Rs 1,162 | -Rs 21,178 | Rs 83,640 | 44% | 133 | Rs 38,372 | Rs 2,09,841 | 0.1038 (0.1495) | 0.996 (1.0) | 153 / Rs 1,88,616 | 128 / Rs 2,55,423 (15 / Rs 62,448) | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net |
| W2H wait 2 one-minute candles; all close beyond the broken channel edge | 1,562 | Rs 1,24,449 | 45% | Rs 1,615 / -Rs 1,180 | -Rs 20,892 | Rs 88,689 | 47% | 133 | -Rs 24,689 | Rs 70,338 | 0.008 (0.018) | 0.02 (0.045) | 353 / Rs 4,24,607 | 149 / Rs 2,08,071 (8 / Rs 30,691) | FAIL: design net <= 0 |
| W2D wait 2 one-minute candles; the last closes beyond the signal bar's close | 1,268 | Rs 1,44,478 | 45% | Rs 1,631 / -Rs 1,133 | -Rs 15,846 | Rs 49,692 | 45% | 133 | -Rs 72,910 | Rs 37,937 | 0.012 (0.0216) | 0.006 (0.018) | 624 / Rs 7,92,956 | 314 / Rs 4,11,219 (13 / Rs 50,560) | FAIL: design net <= 0 |
| W2HD wait 2 one-minute candles; both (break holds and price kept going) | 1,237 | Rs 1,51,531 | 45% | Rs 1,643 / -Rs 1,131 | -Rs 16,739 | Rs 48,718 | 44% | 133 | -Rs 43,188 | Rs 47,387 | 0.012 (0.0216) | 0.008 (0.0206) | 645 / Rs 8,19,466 | 335 / Rs 4,48,082 (14 / Rs 54,062) | FAIL: design net <= 0 |
| W3H wait 3 one-minute candles; all close beyond the broken channel edge | 1,515 | Rs 1,59,749 | 47% | Rs 1,572 / -Rs 1,178 | -Rs 20,873 | Rs 67,585 | 44% | 133 | -Rs 64,912 | Rs 68,266 | 0.002 (0.0131) | 0.002 (0.0144) | 415 / Rs 5,08,676 | 177 / Rs 2,36,632 (8 / Rs 30,691) | FAIL: design net <= 0 |
| W3D wait 3 one-minute candles; the last closes beyond the signal bar's close | 1,262 | Rs 1,52,103 | 45% | Rs 1,634 / -Rs 1,110 | -Rs 16,884 | Rs 40,210 | 49% | 132 | -Rs 94,611 | -Rs 11,265 | 0.0279 (0.0457) | 0.006 (0.018) | 645 / Rs 8,24,851 | 316 / Rs 4,21,298 (15 / Rs 55,957) | FAIL: design net <= 0, walk-forward net <= 0 |
| W3HD wait 3 one-minute candles; both (break holds and price kept going) | 1,197 | Rs 1,73,909 | 46% | Rs 1,643 / -Rs 1,124 | -Rs 20,482 | Rs 36,968 | 49% | 132 | -Rs 73,544 | -Rs 22,014 | 0.004 (0.0131) | 0.002 (0.0144) | 693 / Rs 8,76,033 | 342 / Rs 4,69,488 (19 / Rs 71,858) | FAIL: design net <= 0, walk-forward net <= 0 |
| W5H wait 5 one-minute candles; all close beyond the broken channel edge | 1,406 | Rs 1,63,945 | 45% | Rs 1,632 / -Rs 1,142 | -Rs 22,419 | Rs 79,808 | 47% | 133 | -Rs 1,26,378 | Rs 75,559 | 0.004 (0.0131) | 0.004 (0.018) | 519 / Rs 6,40,039 | 218 / Rs 2,82,431 (9 / Rs 38,306) | FAIL: design net <= 0 |
| W5D wait 5 one-minute candles; the last closes beyond the signal bar's close | 1,191 | Rs 26,965 | 44% | Rs 1,508 / -Rs 1,148 | -Rs 18,236 | Rs 69,222 | 44% | 133 | -Rs 3,17,522 | -Rs 84,090 | 0.5649 (0.6579) | 0.8104 (1.0) | 682 / Rs 8,76,129 | 313 / Rs 4,40,578 (16 / Rs 80,799) | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| W5HD wait 5 one-minute candles; both (break holds and price kept going) | 1,097 | Rs 53,618 | 44% | Rs 1,547 / -Rs 1,139 | -Rs 20,295 | Rs 44,962 | 45% | 132 | -Rs 1,90,929 | -Rs 48,804 | 0.475 (0.6333) | 0.5289 (0.8655) | 737 / Rs 9,38,252 | 374 / Rs 5,31,945 (20 / Rs 96,700) | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C = T2 + W2H | 1,493 | Rs 1,16,431 | 44% | Rs 1,661 / -Rs 1,176 | -Rs 20,892 | Rs 1,01,112 | 45% | 133 | -Rs 29,805 | -Rs 99,953 | 0.008 (0.018) | 0.0559 (0.1118) | 407 / Rs 4,83,572 | 218 / Rs 2,96,787 (11 / Rs 46,127) | FAIL: design net <= 0, walk-forward net <= 0 |

## Final period, exits Q (the same without L25)

| rule | trades | net | win | avg win / loss | worst day | max DD | green days | days | design net | WF net | twin p (q) | skip p (q) | losers avoided | winners missed (big +30%) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Donchian 20 (baseline)** | 1,781 | Rs 78,777 | 44% | Rs 1,627 / -Rs 1,175 | -Rs 23,012 | Rs 95,720 | 46% | 133 | Rs 7,474 | Rs 1,73,115 | - | - | - | - | baseline |
| T1 5-min close beyond its EMA 20 | 1,781 | Rs 76,156 | 43% | Rs 1,629 / -Rs 1,176 | -Rs 23,012 | Rs 95,720 | 45% | 133 | Rs 7,494 | Rs 1,73,136 | 0.006 (0.018) | 1.0 (1.0) | 1 / Rs 907 | 1 / Rs 162 (0 / Rs 0) | PASS |
| T2 5-min close beyond its EMA 50 | 1,691 | Rs 94,202 | 44% | Rs 1,657 / -Rs 1,184 | -Rs 20,307 | Rs 99,756 | 47% | 133 | Rs 80,071 | Rs 2,42,482 | 0.004 (0.0131) | 0.1018 (0.1929) | 118 / Rs 1,31,616 | 93 / Rs 1,40,149 (7 / Rs 30,049) | PASS |
| T3 15-min EMA 20 rising (falling for puts) | 1,505 | -Rs 42,067 | 42% | Rs 1,577 / -Rs 1,213 | -Rs 19,790 | Rs 1,51,807 | 42% | 133 | -Rs 1,09,834 | Rs 52,853 | 0.5569 (0.6579) | 1.0 (1.0) | 333 / Rs 3,81,240 | 303 / Rs 5,15,183 (27 / Rs 1,22,416) | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0 |
| T4 1-hour EMA 20 rising (falling for puts) | 1,064 | -Rs 1,02,764 | 40% | Rs 1,544 / -Rs 1,197 | -Rs 20,318 | Rs 1,59,594 | 37% | 133 | -Rs 60,421 | Rs 47,784 | 0.9461 (0.9461) | 1.0 (1.0) | 520 / Rs 6,04,972 | 442 / Rs 7,74,652 (39 / Rs 1,92,338) | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0 |
| T5 ADX 14 above 20 | 1,496 | Rs 40,859 | 44% | Rs 1,578 / -Rs 1,200 | -Rs 36,378 | Rs 95,644 | 45% | 132 | -Rs 94,535 | Rs 57,915 | 0.0918 (0.1377) | 0.8044 (1.0) | 308 / Rs 3,28,223 | 257 / Rs 4,17,693 (21 / Rs 1,06,577) | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0 |
| T6 ADX 14 above 25 | 1,167 | -Rs 33,871 | 42% | Rs 1,527 / -Rs 1,168 | -Rs 25,065 | Rs 93,293 | 40% | 132 | -Rs 1,85,880 | -Rs 17,373 | 0.8004 (0.8475) | 0.996 (1.0) | 555 / Rs 6,37,976 | 443 / Rs 7,49,909 (36 / Rs 1,71,124) | FAIL: final net <= 0, twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| T7 Supertrend (10, 3) on 5-min bars with the break | 1,670 | Rs 1,34,308 | 44% | Rs 1,666 / -Rs 1,190 | -Rs 21,578 | Rs 84,202 | 45% | 133 | Rs 46,658 | Rs 2,09,693 | 0.002 (0.0131) | 0.006 (0.018) | 166 / Rs 1,71,989 | 107 / Rs 1,60,024 (5 / Rs 23,400) | PASS |
| T8 close beyond the day's average typical price (time-weighted VWAP stand-in; the index has no volume) | 1,667 | Rs 26,703 | 43% | Rs 1,555 / -Rs 1,154 | -Rs 21,178 | Rs 85,170 | 44% | 133 | Rs 40,741 | Rs 2,09,844 | 0.1118 (0.1548) | 0.994 (1.0) | 154 / Rs 1,89,162 | 127 / Rs 2,54,655 (15 / Rs 62,448) | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net |
| W2H wait 2 one-minute candles; all close beyond the broken channel edge | 1,562 | Rs 1,25,751 | 45% | Rs 1,631 / -Rs 1,176 | -Rs 20,892 | Rs 91,994 | 47% | 133 | -Rs 21,184 | Rs 71,270 | 0.008 (0.018) | 0.014 (0.0336) | 355 / Rs 4,25,683 | 147 / Rs 2,07,600 (8 / Rs 30,691) | FAIL: design net <= 0 |
| W2D wait 2 one-minute candles; the last closes beyond the signal bar's close | 1,268 | Rs 1,43,977 | 45% | Rs 1,647 / -Rs 1,130 | -Rs 15,846 | Rs 53,971 | 45% | 133 | -Rs 73,028 | Rs 36,203 | 0.012 (0.0216) | 0.006 (0.018) | 628 / Rs 7,94,714 | 310 / Rs 4,09,352 (13 / Rs 50,560) | FAIL: design net <= 0 |
| W2HD wait 2 one-minute candles; both (break holds and price kept going) | 1,237 | Rs 1,48,763 | 45% | Rs 1,652 / -Rs 1,131 | -Rs 16,739 | Rs 52,996 | 44% | 133 | -Rs 39,291 | Rs 45,755 | 0.012 (0.0216) | 0.008 (0.0206) | 649 / Rs 8,21,224 | 331 / Rs 4,46,214 (14 / Rs 54,062) | FAIL: design net <= 0 |
| W3H wait 3 one-minute candles; all close beyond the broken channel edge | 1,515 | Rs 1,60,798 | 46% | Rs 1,590 / -Rs 1,168 | -Rs 20,873 | Rs 70,077 | 44% | 133 | -Rs 75,894 | Rs 58,232 | 0.002 (0.0131) | 0.002 (0.0144) | 418 / Rs 5,09,767 | 174 / Rs 2,35,847 (8 / Rs 30,691) | FAIL: design net <= 0 |
| W3D wait 3 one-minute candles; the last closes beyond the signal bar's close | 1,262 | Rs 1,57,529 | 45% | Rs 1,655 / -Rs 1,104 | -Rs 16,884 | Rs 42,278 | 49% | 132 | -Rs 93,165 | -Rs 17,046 | 0.024 (0.0411) | 0.002 (0.0144) | 649 / Rs 8,26,609 | 312 / Rs 4,19,430 (15 / Rs 55,957) | FAIL: design net <= 0, walk-forward net <= 0 |
| W3HD wait 3 one-minute candles; both (break holds and price kept going) | 1,197 | Rs 1,76,328 | 46% | Rs 1,660 / -Rs 1,118 | -Rs 20,482 | Rs 39,037 | 49% | 132 | -Rs 74,232 | -Rs 28,790 | 0.004 (0.0131) | 0.002 (0.0144) | 697 / Rs 8,77,791 | 338 / Rs 4,67,620 (19 / Rs 71,858) | FAIL: design net <= 0, walk-forward net <= 0 |
| W5H wait 5 one-minute candles; all close beyond the broken channel edge | 1,406 | Rs 1,62,820 | 45% | Rs 1,644 / -Rs 1,139 | -Rs 22,419 | Rs 83,077 | 47% | 133 | -Rs 1,39,305 | Rs 65,285 | 0.004 (0.0131) | 0.004 (0.018) | 523 / Rs 6,41,797 | 214 / Rs 2,81,254 (9 / Rs 38,306) | FAIL: design net <= 0 |
| W5D wait 5 one-minute candles; the last closes beyond the signal bar's close | 1,191 | Rs 24,548 | 44% | Rs 1,518 / -Rs 1,144 | -Rs 18,236 | Rs 71,308 | 44% | 133 | -Rs 3,27,949 | -Rs 94,762 | 0.5848 (0.6579) | 0.8084 (1.0) | 685 / Rs 8,77,872 | 310 / Rs 4,38,933 (16 / Rs 80,799) | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| W5HD wait 5 one-minute candles; both (break holds and price kept going) | 1,097 | Rs 51,460 | 44% | Rs 1,559 / -Rs 1,134 | -Rs 20,295 | Rs 47,512 | 45% | 132 | -Rs 2,02,868 | -Rs 58,863 | 0.497 (0.639) | 0.5289 (0.8655) | 741 / Rs 9,40,010 | 370 / Rs 5,29,987 (20 / Rs 96,700) | FAIL: twin p >= 0.05, BH q > 0.10, top 5 >= net, design net <= 0, walk-forward net <= 0 |
| C = T2 + W2H | 1,493 | Rs 1,21,005 | 44% | Rs 1,673 / -Rs 1,173 | -Rs 20,892 | Rs 1,01,145 | 45% | 133 | -Rs 24,138 | -Rs 99,236 | 0.008 (0.018) | 0.0319 (0.0676) | 411 / Rs 4,84,831 | 214 / Rs 2,94,462 (11 / Rs 46,127) | FAIL: design net <= 0, walk-forward net <= 0 |

## Cost of waiting (final, signals taken by both, exits P)

| wait | signals | index points moved before the entry (mean / median) | option fill paid, Rs per lot (mean / median) | % (mean / median) | same strike |
|---|---|---|---|---|---|
| W2H wait 2 one-minute candles; all close beyond the broken channel edge | 1,279 | 7.2 / 3.6 | -Rs 17 / Rs 0 | 0.0% / 0.0% | 74% |
| W2D wait 2 one-minute candles; the last closes beyond the signal bar's close | 843 | 18.2 / 10.2 | -Rs 55 / Rs 48 | -0.2% / 0.3% | 72% |
| W2HD wait 2 one-minute candles; both (break holds and price kept going) | 801 | 18.6 / 10.8 | -Rs 66 / Rs 38 | -0.3% / 0.2% | 71% |
| W3H wait 3 one-minute candles; all close beyond the broken channel edge | 1,189 | 7.9 / 4.2 | Rs 7 / Rs 2 | 0.2% / 0.0% | 70% |
| W3D wait 3 one-minute candles; the last closes beyond the signal bar's close | 820 | 21.0 / 12.5 | -Rs 56 / Rs 41 | -0.2% / 0.3% | 69% |
| W3HD wait 3 one-minute candles; both (break holds and price kept going) | 746 | 22.0 / 13.8 | -Rs 64 / Rs 45 | -0.3% / 0.3% | 67% |
| W5H wait 5 one-minute candles; all close beyond the broken channel edge | 1,044 | 10.0 / 5.7 | Rs 39 / Rs 21 | 0.2% / 0.1% | 66% |
| W5D wait 5 one-minute candles; the last closes beyond the signal bar's close | 786 | 25.7 / 14.5 | -Rs 1 / Rs 120 | 0.1% / 0.7% | 66% |
| W5HD wait 5 one-minute candles; both (break holds and price kept going) | 670 | 27.6 / 16.2 | -Rs 16 / Rs 124 | -0.0% / 0.7% | 63% |
| C = T2 + W2H | 1,156 | 7.3 / 3.6 | -Rs 16 / Rs 0 | 0.0% / 0.0% | 73% |

## Design period (20 Nov 2024 - 31 Mar 2026)

| rule | exits | trades | net | win | worst day | max DD | green days | WF net | skip p (design, info) |
|---|---|---|---|---|---|---|---|---|---|
| Donchian 20 | P | 4,498 | Rs 9,998 | 45% | -Rs 56,647 | Rs 2,24,592 | 47% | Rs 1,75,789 | - |
| T1 5-min close beyond its EMA 20 | P | 4,498 | Rs 10,019 | 45% | -Rs 56,647 | Rs 2,24,592 | 47% | Rs 1,75,810 | 0.002 |
| T2 5-min close beyond its EMA 50 | P | 4,302 | Rs 79,607 | 45% | -Rs 56,647 | Rs 1,95,926 | 47% | Rs 2,43,221 | 0.01 |
| T3 15-min EMA 20 rising (falling for puts) | P | 3,775 | -Rs 1,07,467 | 45% | -Rs 36,520 | Rs 2,20,756 | 44% | Rs 59,381 | 0.988 |
| T4 1-hour EMA 20 rising (falling for puts) | P | 2,722 | -Rs 57,001 | 45% | -Rs 36,520 | Rs 1,95,826 | 40% | Rs 50,749 | 0.8164 |
| T5 ADX 14 above 20 | P | 3,600 | -Rs 87,955 | 45% | -Rs 52,942 | Rs 2,16,921 | 43% | Rs 63,545 | 0.9501 |
| T6 ADX 14 above 25 | P | 2,780 | -Rs 1,74,950 | 43% | -Rs 49,122 | Rs 2,45,939 | 41% | -Rs 7,946 | 0.992 |
| T7 Supertrend (10, 3) on 5-min bars with the break | P | 4,208 | Rs 50,425 | 44% | -Rs 49,738 | Rs 2,14,239 | 45% | Rs 2,13,748 | 0.1238 |
| T8 close beyond the day's average typical price (time-weighted VWAP stand-in; the index has no volume) | P | 4,264 | Rs 38,372 | 45% | -Rs 56,126 | Rs 2,16,297 | 47% | Rs 2,09,841 | 0.1936 |
| W2H wait 2 one-minute candles; all close beyond the broken channel edge | P | 4,000 | -Rs 24,689 | 45% | -Rs 61,227 | Rs 1,78,116 | 44% | Rs 70,338 | 0.7485 |
| W2D wait 2 one-minute candles; the last closes beyond the signal bar's close | P | 3,315 | -Rs 72,910 | 45% | -Rs 37,964 | Rs 1,45,227 | 42% | Rs 37,937 | 0.8942 |
| W2HD wait 2 one-minute candles; both (break holds and price kept going) | P | 3,240 | -Rs 43,188 | 45% | -Rs 37,964 | Rs 1,64,102 | 44% | Rs 47,387 | 0.7964 |
| W3H wait 3 one-minute candles; all close beyond the broken channel edge | P | 3,832 | -Rs 64,912 | 46% | -Rs 58,139 | Rs 1,74,254 | 46% | Rs 68,266 | 0.9361 |
| W3D wait 3 one-minute candles; the last closes beyond the signal bar's close | P | 3,242 | -Rs 94,611 | 45% | -Rs 41,948 | Rs 1,70,305 | 43% | -Rs 11,265 | 0.9481 |
| W3HD wait 3 one-minute candles; both (break holds and price kept going) | P | 3,090 | -Rs 73,544 | 45% | -Rs 41,948 | Rs 1,68,270 | 45% | -Rs 22,014 | 0.8902 |
| W5H wait 5 one-minute candles; all close beyond the broken channel edge | P | 3,604 | -Rs 1,26,378 | 45% | -Rs 48,992 | Rs 2,50,453 | 47% | Rs 75,559 | 0.994 |
| W5D wait 5 one-minute candles; the last closes beyond the signal bar's close | P | 3,163 | -Rs 3,17,522 | 43% | -Rs 30,466 | Rs 3,21,072 | 43% | -Rs 84,090 | 1.0 |
| W5HD wait 5 one-minute candles; both (break holds and price kept going) | P | 2,910 | -Rs 1,90,929 | 44% | -Rs 34,713 | Rs 2,00,654 | 44% | -Rs 48,804 | 0.998 |
| C = T2 + W2H | P | 3,881 | -Rs 29,805 | 45% | -Rs 61,227 | Rs 1,66,163 | 45% | -Rs 99,953 | 0.7745 |
| Donchian 20 | Q | 4,498 | Rs 7,474 | 44% | -Rs 56,647 | Rs 2,27,485 | 46% | Rs 1,73,115 | - |
| T1 5-min close beyond its EMA 20 | Q | 4,498 | Rs 7,494 | 44% | -Rs 56,647 | Rs 2,27,485 | 46% | Rs 1,73,136 | 0.002 |
| T2 5-min close beyond its EMA 50 | Q | 4,302 | Rs 80,071 | 44% | -Rs 56,647 | Rs 1,97,496 | 47% | Rs 2,42,482 | 0.008 |
| T3 15-min EMA 20 rising (falling for puts) | Q | 3,775 | -Rs 1,09,834 | 44% | -Rs 36,520 | Rs 2,18,719 | 43% | Rs 52,853 | 0.988 |
| T4 1-hour EMA 20 rising (falling for puts) | Q | 2,722 | -Rs 60,421 | 45% | -Rs 36,520 | Rs 2,03,228 | 40% | Rs 47,784 | 0.8323 |
| T5 ADX 14 above 20 | Q | 3,600 | -Rs 94,535 | 44% | -Rs 52,942 | Rs 2,20,527 | 42% | Rs 57,915 | 0.9601 |
| T6 ADX 14 above 25 | Q | 2,780 | -Rs 1,85,880 | 42% | -Rs 49,122 | Rs 2,49,723 | 41% | -Rs 17,373 | 0.994 |
| T7 Supertrend (10, 3) on 5-min bars with the break | Q | 4,208 | Rs 46,658 | 44% | -Rs 49,738 | Rs 2,15,894 | 44% | Rs 2,09,693 | 0.1277 |
| T8 close beyond the day's average typical price (time-weighted VWAP stand-in; the index has no volume) | Q | 4,264 | Rs 40,741 | 44% | -Rs 56,126 | Rs 2,16,136 | 46% | Rs 2,09,844 | 0.1457 |
| W2H wait 2 one-minute candles; all close beyond the broken channel edge | Q | 4,000 | -Rs 21,184 | 44% | -Rs 61,227 | Rs 1,66,379 | 45% | Rs 71,270 | 0.6906 |
| W2D wait 2 one-minute candles; the last closes beyond the signal bar's close | Q | 3,315 | -Rs 73,028 | 44% | -Rs 37,964 | Rs 1,44,201 | 42% | Rs 36,203 | 0.8902 |
| W2HD wait 2 one-minute candles; both (break holds and price kept going) | Q | 3,239 | -Rs 39,291 | 44% | -Rs 37,964 | Rs 1,56,039 | 43% | Rs 45,755 | 0.7665 |
| W3H wait 3 one-minute candles; all close beyond the broken channel edge | Q | 3,832 | -Rs 75,894 | 45% | -Rs 58,139 | Rs 1,82,194 | 45% | Rs 58,232 | 0.9521 |
| W3D wait 3 one-minute candles; the last closes beyond the signal bar's close | Q | 3,242 | -Rs 93,165 | 45% | -Rs 41,948 | Rs 1,73,635 | 43% | -Rs 17,046 | 0.9381 |
| W3HD wait 3 one-minute candles; both (break holds and price kept going) | Q | 3,090 | -Rs 74,232 | 45% | -Rs 41,948 | Rs 1,74,903 | 45% | -Rs 28,790 | 0.8942 |
| W5H wait 5 one-minute candles; all close beyond the broken channel edge | Q | 3,604 | -Rs 1,39,305 | 44% | -Rs 48,992 | Rs 2,61,966 | 46% | Rs 65,285 | 0.996 |
| W5D wait 5 one-minute candles; the last closes beyond the signal bar's close | Q | 3,162 | -Rs 3,27,949 | 42% | -Rs 30,466 | Rs 3,32,172 | 43% | -Rs 94,762 | 1.0 |
| W5HD wait 5 one-minute candles; both (break holds and price kept going) | Q | 2,910 | -Rs 2,02,868 | 43% | -Rs 34,713 | Rs 2,08,976 | 45% | -Rs 58,863 | 0.998 |
| C = T2 + W2H | Q | 3,881 | -Rs 24,138 | 44% | -Rs 61,227 | Rs 1,54,408 | 45% | -Rs 99,236 | 0.7285 |

## Decision (PREREG section 4)

- Recommended, exits P: T7-P; exits Q: T7-Q.
- Loss-reduction trade-offs (information only): T8-P, W5D-P, W5HD-P, T8-Q, W5D-Q, W5HD-Q.

## Extra (labelled, not gated): the best design variant on top of R72's C1 Breadth Donchian (exits P)

| book | period | trades | net | win | avg win / loss | worst day | max DD | green days | days |
|---|---|---|---|---|---|---|---|---|---|
| C1 Breadth Donchian | design | 3,071 | Rs 2,16,929 | 47% | Rs 1,715 / -Rs 1,374 | -Rs 58,613 | Rs 1,99,077 | 46% | 323 |
| C1 + T2 | design | 2,994 | Rs 2,22,607 | 47% | Rs 1,715 / -Rs 1,366 | -Rs 58,613 | Rs 1,74,321 | 46% | 323 |
| C1 Breadth Donchian | final | 1,202 | Rs 1,71,366 | 46% | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | Rs 61,884 | 52% | 129 |
| C1 + T2 | final | 1,158 | Rs 1,60,479 | 45% | Rs 1,795 / -Rs 1,216 | -Rs 22,571 | Rs 73,023 | 51% | 129 |

Final: C1 + T2 skipped 74 C1 trades (net Rs 9,053); random-skip twin p 0.6467.


## The same signals, taken by both (exits P): Donchian's net there vs the rule's

| rule | design: signals / Donchian / rule | final: signals / Donchian / rule |
|---|---|---|
| T1 5-min close beyond its EMA 20 | 4,497 / Rs 9,800 / Rs 9,800 | 1,779 / Rs 82,590 / Rs 82,590 |
| T2 5-min close beyond its EMA 50 | 4,097 / Rs 1,17,071 / Rs 1,17,071 | 1,570 / Rs 69,728 / Rs 69,728 |
| T3 15-min EMA 20 rising (falling for puts) | 3,032 / Rs 20,007 / Rs 20,007 | 1,145 / -Rs 57,402 / -Rs 57,402 |
| T4 1-hour EMA 20 rising (falling for puts) | 2,146 / Rs 59,078 / Rs 59,078 | 819 / -Rs 89,175 / -Rs 89,175 |
| T5 ADX 14 above 20 | 2,822 / Rs 9,909 / Rs 9,909 | 1,216 / -Rs 9,996 / -Rs 9,996 |
| T6 ADX 14 above 25 | 1,803 / -Rs 76,176 / -Rs 76,176 | 783 / -Rs 31,796 / -Rs 31,796 |
| T7 Supertrend (10, 3) on 5-min bars with the break | 3,839 / Rs 1,58,655 / Rs 1,58,655 | 1,508 / Rs 90,096 / Rs 90,096 |
| T8 close beyond the day's average typical price (time-weighted VWAP stand-in; the index has no volume) | 3,963 / -Rs 2,513 / -Rs 2,513 | 1,500 / Rs 15,039 / Rs 15,039 |
| W2H wait 2 one-minute candles; all close beyond the broken channel edge | 3,358 / Rs 5,68,622 / Rs 63,165 | 1,279 / Rs 2,98,380 / Rs 1,39,846 |
| W2D wait 2 one-minute candles; the last closes beyond the signal bar's close | 2,227 / Rs 10,61,428 / Rs 1,18,006 | 843 / Rs 4,63,582 / Rs 1,45,456 |
| W2HD wait 2 one-minute candles; both (break holds and price kept going) | 2,145 / Rs 10,46,448 / Rs 1,18,593 | 801 / Rs 4,53,229 / Rs 1,43,572 |
| W3H wait 3 one-minute candles; all close beyond the broken channel edge | 3,086 / Rs 6,94,960 / -Rs 10,730 | 1,189 / Rs 3,53,889 / Rs 1,70,923 |
| W3D wait 3 one-minute candles; the last closes beyond the signal bar's close | 2,187 / Rs 12,01,058 / Rs 46,879 | 820 / Rs 4,85,398 / Rs 1,26,582 |
| W3HD wait 3 one-minute candles; both (break holds and price kept going) | 2,015 / Rs 11,47,097 / Rs 55,728 | 746 / Rs 4,88,390 / Rs 1,43,101 |
| W5H wait 5 one-minute candles; all close beyond the broken channel edge | 2,737 / Rs 9,55,316 / -Rs 66,989 | 1,044 / Rs 4,39,453 / Rs 1,86,041 |
| W5D wait 5 one-minute candles; the last closes beyond the signal bar's close | 2,240 / Rs 14,65,068 / -Rs 71,306 | 786 / Rs 5,17,396 / Rs 38,252 |
| W5HD wait 5 one-minute candles; both (break holds and price kept going) | 1,916 / Rs 13,54,104 / -Rs 28,061 | 670 / Rs 4,88,152 / Rs 64,198 |
| C = T2 + W2H | 3,130 / Rs 5,79,914 / Rs 1,04,567 | 1,156 / Rs 2,68,631 / Rs 1,27,697 |

## Extra (labelled, not gated, run after the final read): T7 on top of C1 (exits P)

| book | period | trades | net | win | avg win / loss | worst day | max DD | green days | days |
|---|---|---|---|---|---|---|---|---|---|
| C1 Breadth Donchian | design | 3,071 | Rs 2,16,929 | 47% | Rs 1,715 / -Rs 1,374 | -Rs 58,613 | Rs 1,99,077 | 46% | 323 |
| C1 + T7 | design | 2,965 | Rs 2,56,207 | 47% | Rs 1,734 / -Rs 1,376 | -Rs 51,838 | Rs 1,95,247 | 46% | 322 |
| C1 Breadth Donchian | final | 1,202 | Rs 1,71,366 | 46% | Rs 1,748 / -Rs 1,217 | -Rs 22,571 | Rs 61,884 | 52% | 129 |
| C1 + T7 | final | 1,163 | Rs 1,65,758 | 46% | Rs 1,761 / -Rs 1,213 | -Rs 22,571 | Rs 68,021 | 51% | 129 |

design: random-skip twin p 0.016; final: random-skip twin p 0.4611.
