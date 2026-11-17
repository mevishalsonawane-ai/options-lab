# R190 best strategy hunt: full report

Pre-registration: `PREREG.md` (committed before any number). Code: `r190data.py` (flat lake), `r190night.py` (Night via R147's builder), `r190arms.py` (Liquidity and R72 via R115 / R101's engine on R51's clean stores), `r190rules.py` (new rules), `r190study.py` (eras, twins), `r190report.py` (this file).

BH family: m = 4,762 (4,749 earlier entries + 13 R190 candidates); R190's q uses each candidate's era-A normal-tail twin p (untestable = 1).

## Data, checks and caveats

- Data: the Drive lake (copied 17 Nov 2026, incl. the 20 Oct - 13 Nov 2026 fill) plus dhan-data NIFTY WEEK options 3 Aug 2020 - 30 Jul 2021 (same endpoint; 7,875 / 7,875 rows identical on 3 Aug 2021). Era A has NIFTY only; BANKNIFTY / FINNIFTY options start Aug 2021, MIDCPNIFTY 2022, SENSEX 2023.
- Era A is not virgin: the Oct 2026 h-series hunt (h22-h47) used NIFTY options from Aug 2020 as design data, including h31's close-following overnight rules that Night descends from. For Night, era A is a consistency check, not out-of-sample proof. Era A was still locked here (flag committed in 823379785) and read once.
- Engine parity: Liquidity / R72 on R190's stores reproduce the published windows: R144a fresh R72: 1169 trades -Rs 67,223 (published [1169, -67223]); R144a fresh LIQ: 244 trades -Rs 25,503 (published None); R143 unseen R72: 3395 trades -Rs 306,931 (published [3395, -306931]); R143 unseen LIQ: 894 trades Rs 83,307 (published [894, 83000]). Night NIFTY on 2022-24 reproduces R147's design exactly (247 trades, Rs 200 a trade at app costs); BANKNIFTY gives 257 trades and Rs 306 a trade against R147's 256 and Rs 296 (one extra priced night; not chased).
- R72 + picker on Apr - 13 Oct 2026: 651 trades +Rs 2.20 lakh against R151's published 652 / +Rs 2.19 lakh (R72 1,200 vs 1,202): the store now flags 13 Oct 2026 as an expiry day (the old lake end could not).
- D2's era-C numbers are mostly in-sample (the frozen picker was trained on Nov 2024 - Mar 2026 trades).
- V1 uses TWAP, not VWAP (no index volume; no futures volume history). Intraday fills: R17 liq; Night: app bps + h24 half-spread; all with dated charges. Max-fit lots size each trade to Rs 56,000 of premium.
- Clarification of PREREG's ATM (no look-ahead): M / V buy the strike that was ATM on the decision minute (14:49 for M; the minute before each mark for V), as R51's engine does, not the 14:50 label. The Night book's max-fit column sizes each leg to Rs 56,000 on its own (two legs on one night would need more cash).
- Liquidity / R72 twins were not run (era A untestable; the gate's twin test is era A only).

D2 (picker): R151 picker numbers (threshold 0.4656) on R151's own features.

## N1B: Night (R3) book: NIFTY + BANKNIFTY, 1 lot each

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | -Rs 4,879 | Rs 15,034 | -Rs 7,136 |
| B | 498 | 55.0% | Rs 124,990 | Rs 1,975 / -Rs 1,858 | Rs 70,545 | -Rs 15,507 | Rs 34,960 | -Rs 31,592 |
| C | 351 | 53.0% | Rs 192,726 | Rs 3,583 / -Rs 2,871 | Rs 111,548 | -Rs 15,106 | Rs 80,780 | -Rs 37,286 |

**twice the slippage**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 15,331 | Rs 2,171 / -Rs 2,398 | -Rs 16,255 | -Rs 4,935 | Rs 15,523 | -Rs 7,567 |
| B | 498 | 54.4% | Rs 103,487 | Rs 1,951 / -Rs 1,873 | Rs 49,480 | -Rs 15,632 | Rs 36,367 | -Rs 32,382 |
| C | 351 | 51.9% | Rs 168,370 | Rs 3,587 / -Rs 2,866 | Rs 87,776 | -Rs 15,212 | Rs 82,475 | -Rs 37,945 |

**max lots that fit Rs 56k**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 126,758 | Rs 11,898 / -Rs 11,620 | -Rs 27,954 | -Rs 22,779 | Rs 52,753 | -Rs 28,277 |
| B | 498 | 55.8% | Rs 958,830 | Rs 11,927 / -Rs 10,713 | Rs 629,848 | -Rs 61,193 | Rs 195,023 | -Rs 99,578 |
| C | 351 | 53.3% | Rs 815,926 | Rs 13,044 / -Rs 9,898 | Rs 447,204 | -Rs 60,485 | Rs 183,626 | -Rs 142,634 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| A | 0.0730 | 6.42e-02 | -Rs 20,101 | 2020: Rs 13,734, 2021: Rs 4,351 | 5 of 12 |
| B | 0.0005 | 8.41e-06 | -Rs 113,168 | 2021: Rs 30,353, 2022: Rs 54,929, 2023: Rs 31,161, 2024: Rs 8,547 | 22 of 35 |
| C | 0.0005 | 7.58e-05 | -Rs 116,108 | 2024: Rs 36,492, 2025: Rs 83,635, 2026: Rs 72,598 | 16 of 25 |

By index (1 lot): era B: BANKNIFTY 254 tr 54% Rs 69,863, NIFTY 244 tr 57% Rs 55,126; era C: BANKNIFTY 196 tr 53% Rs 127,476, NIFTY 155 tr 53% Rs 65,250

BH q 1; era-A twin p 0.07296351824087956.
**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -13,803 <= 0; era A twin p 0.073 >= 0.05; BH q 1 > 0.1.

## N1N: Night (R3) on NIFTY alone

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | -Rs 4,879 | Rs 15,034 | -Rs 7,136 |
| B | 244 | 56.6% | Rs 55,126 | Rs 1,868 / -Rs 1,911 | Rs 9,506 | -Rs 6,829 | Rs 17,341 | -Rs 17,341 |
| C | 155 | 52.9% | Rs 65,250 | Rs 3,532 / -Rs 3,074 | -Rs 67 | -Rs 11,341 | Rs 41,709 | -Rs 19,000 |

**twice the slippage**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 15,331 | Rs 2,171 / -Rs 2,398 | -Rs 16,255 | -Rs 4,935 | Rs 15,523 | -Rs 7,567 |
| B | 244 | 55.7% | Rs 48,433 | Rs 1,864 / -Rs 1,899 | Rs 3,111 | -Rs 6,855 | Rs 18,224 | -Rs 17,525 |
| C | 155 | 52.3% | Rs 58,112 | Rs 3,525 / -Rs 3,073 | -Rs 6,802 | -Rs 11,386 | Rs 42,080 | -Rs 19,282 |

**max lots that fit Rs 56k**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 126,758 | Rs 11,898 / -Rs 11,620 | -Rs 27,954 | -Rs 22,779 | Rs 52,753 | -Rs 28,277 |
| B | 244 | 56.6% | Rs 597,211 | Rs 15,891 / -Rs 15,054 | Rs 268,228 | -Rs 43,550 | Rs 148,682 | -Rs 75,356 |
| C | 155 | 52.9% | Rs 468,340 | Rs 18,263 / -Rs 14,100 | Rs 99,618 | -Rs 36,179 | Rs 152,020 | -Rs 113,649 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| A | 0.0700 | 6.51e-02 | -Rs 19,941 | 2020: Rs 13,734, 2021: Rs 4,351 | 5 of 12 |
| B | 0.0075 | 4.96e-03 | -Rs 41,694 | 2021: Rs 16,194, 2022: Rs 4,827, 2023: Rs 25,518, 2024: Rs 8,588 | 20 of 35 |
| C | 0.0285 | 2.64e-02 | -Rs 42,469 | 2024: Rs 11,441, 2025: Rs 17,981, 2026: Rs 35,828 | 15 of 25 |

BH q 1; era-A twin p 0.06996501749125437.
**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -13,803 <= 0; era C: net without the top 5 trades -67 <= 0; era A twin p 0.070 >= 0.05; BH q 1 > 0.1.

## N2: Night for Rs 56k: one position a night, month cap -Rs 13k

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | -Rs 4,879 | Rs 15,034 | -Rs 7,136 |
| B | 336 | 53.0% | Rs 35,938 | Rs 1,844 / -Rs 1,850 | -Rs 12,540 | -Rs 9,398 | Rs 27,456 | -Rs 19,233 |
| C | 238 | 53.4% | Rs 132,014 | Rs 3,467 / -Rs 2,778 | Rs 58,202 | -Rs 11,341 | Rs 35,390 | -Rs 15,503 |

**twice the slippage**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 15,331 | Rs 2,171 / -Rs 2,398 | -Rs 16,255 | -Rs 4,935 | Rs 15,523 | -Rs 7,567 |
| B | 336 | 52.4% | Rs 24,088 | Rs 1,828 / -Rs 1,860 | -Rs 24,058 | -Rs 9,464 | Rs 30,197 | -Rs 19,747 |
| C | 238 | 52.1% | Rs 117,364 | Rs 3,485 / -Rs 2,762 | Rs 43,977 | -Rs 11,386 | Rs 36,024 | -Rs 15,992 |

**max lots that fit Rs 56k**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 126,758 | Rs 11,898 / -Rs 11,620 | -Rs 27,954 | -Rs 22,779 | Rs 52,753 | -Rs 28,277 |
| B | 336 | 53.6% | Rs 558,078 | Rs 13,686 / -Rs 12,214 | Rs 229,095 | -Rs 43,550 | Rs 194,939 | -Rs 82,111 |
| C | 238 | 53.4% | Rs 725,897 | Rs 14,920 / -Rs 10,531 | Rs 357,176 | -Rs 36,179 | Rs 120,009 | -Rs 82,929 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| A | 0.0740 | 6.75e-02 | -Rs 19,693 | 2020: Rs 13,734, 2021: Rs 4,351 | 5 of 12 |
| B | 0.0100 | 1.30e-02 | -Rs 67,151 | 2021: Rs 7,206, 2022: Rs 16,712, 2023: Rs 8,698, 2024: Rs 3,322 | 17 of 35 |
| C | 0.0025 | 1.08e-03 | -Rs 74,566 | 2024: Rs 30,771, 2025: Rs 43,012, 2026: Rs 58,230 | 15 of 25 |

By index (1 lot): era B: BANKNIFTY 92 tr 43% -Rs 19,188, NIFTY 244 tr 57% Rs 55,126; era C: BANKNIFTY 88 tr 52% Rs 48,830, NIFTY 150 tr 54% Rs 83,184

BH q 1; era-A twin p 0.07396301849075462.
**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -13,803 <= 0; era B: net without the top 5 trades -12,540 <= 0; era A twin p 0.074 >= 0.05; BH q 1 > 0.1.

## L1: Liquidity 15+5 (BANKNIFTY, FINNIFTY, MIDCPNIFTY), as built

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | no data (untestable) | | | | | | | |
| B | 1099 | 31.0% | Rs 73,741 | Rs 1,572 / -Rs 610 | Rs 10,378 | -Rs 5,742 | Rs 51,118 | -Rs 13,213 |
| C | 1323 | 41.5% | Rs 255,293 | Rs 1,885 / -Rs 1,007 | Rs 168,104 | -Rs 11,405 | Rs 61,062 | -Rs 37,821 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| B | - | - | - | 2021: -Rs 8,958, 2022: -Rs 8,308, 2023: Rs 11,484, 2024: Rs 79,524 | 16 of 35 |
| C | - | - | - | 2024: -Rs 8,514, 2025: Rs 141,266, 2026: Rs 122,542 | 12 of 25 |

By index (1 lot): era B: BANKNIFTY 521 tr 30% Rs 24,974, FINNIFTY 356 tr 30% -Rs 3,305, MIDCPNIFTY 222 tr 36% Rs 52,071; era C: BANKNIFTY 500 tr 41% Rs 127,925, FINNIFTY 380 tr 42% Rs 90,690, MIDCPNIFTY 443 tr 41% Rs 36,679

BH q 1; era-A twin p None.
**Verdict: NOT RELIABLE.** Failed: era A: untestable (no data); era A twin p: untestable; BH q 1 > 0.1.

## D1: R72 Breadth Donchian, today's rules

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | no data (untestable) | | | | | | | |
| B | 4389 | 39.8% | -Rs 407,315 | Rs 888 / -Rs 743 | -Rs 455,331 | -Rs 16,757 | Rs 451,132 | -Rs 54,175 |
| C | 4571 | 46.5% | Rs 390,101 | Rs 1,711 / -Rs 1,327 | Rs 326,344 | -Rs 58,613 | Rs 199,077 | -Rs 78,467 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| B | - | - | - | 2021: -Rs 6,889, 2022: -Rs 77,702, 2023: -Rs 89,681, 2024: -Rs 233,044 | 8 of 35 |
| C | - | - | - | 2024: Rs 5,940, 2025: Rs 63,279, 2026: Rs 320,882 | 16 of 25 |

By index (1 lot): era B: BANKNIFTY 1277 tr 39% -Rs 123,210, FINNIFTY 883 tr 40% -Rs 92,107, MIDCPNIFTY 366 tr 39% -Rs 46,349, NIFTY 1287 tr 42% -Rs 90,368, SENSEX 576 tr 38% -Rs 55,281; era C: BANKNIFTY 1021 tr 46% Rs 145,349, FINNIFTY 990 tr 49% Rs 139,452, MIDCPNIFTY 799 tr 47% Rs 33,943, NIFTY 880 tr 46% Rs 47,972, SENSEX 881 tr 44% Rs 23,386

BH q 1; era-A twin p None.
**Verdict: NOT RELIABLE.** Failed: era A: untestable (no data); era B: net -407,315 <= 0; era B: net without the top 5 trades -455,331 <= 0; era A twin p: untestable; BH q 1 > 0.1.

## D2: R72 + R151 picker (live by Boss's exception)

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | no data (untestable) | | | | | | | |
| B | 2503 | 41.4% | -Rs 184,515 | Rs 973 / -Rs 812 | -Rs 232,531 | -Rs 13,602 | Rs 247,894 | -Rs 37,319 |
| C | 2405 | 51.9% | Rs 689,639 | Rs 1,953 / -Rs 1,508 | Rs 620,864 | -Rs 44,106 | Rs 110,062 | -Rs 19,993 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| B | - | - | - | 2021: Rs 11,710, 2022: -Rs 62,642, 2023: -Rs 28,909, 2024: -Rs 104,673 | 11 of 35 |
| C | - | - | - | 2024: Rs 44,316, 2025: Rs 266,508, 2026: Rs 378,815 | 17 of 25 |

By index (1 lot): era B: BANKNIFTY 781 tr 41% -Rs 52,582, FINNIFTY 492 tr 40% -Rs 60,666, MIDCPNIFTY 206 tr 40% -Rs 8,774, NIFTY 781 tr 44% -Rs 34,751, SENSEX 243 tr 38% -Rs 27,742; era C: BANKNIFTY 523 tr 52% Rs 186,889, FINNIFTY 501 tr 55% Rs 181,642, MIDCPNIFTY 465 tr 49% Rs 81,853, NIFTY 459 tr 53% Rs 135,647, SENSEX 457 tr 50% Rs 103,609

BH q 1; era-A twin p None.
**Verdict: NOT RELIABLE.** Failed: era A: untestable (no data); era B: net -184,515 <= 0; era B: net without the top 5 trades -232,531 <= 0; era A twin p: untestable; BH q 1 > 0.1.

## D3: R72 + FB-R72-A10 (skip after a very wide day)

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | no data (untestable) | | | | | | | |
| B | 3484 | 41.5% | -Rs 210,414 | Rs 852 / -Rs 708 | -Rs 250,976 | -Rs 13,602 | Rs 263,801 | -Rs 34,263 |
| C | 3721 | 47.6% | Rs 478,594 | Rs 1,691 / -Rs 1,289 | Rs 414,837 | -Rs 58,613 | Rs 147,672 | -Rs 65,007 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| B | - | - | - | 2021: -Rs 594, 2022: -Rs 41,283, 2023: -Rs 37,013, 2024: -Rs 131,523 | 11 of 35 |
| C | - | - | - | 2024: Rs 25,861, 2025: Rs 129,277, 2026: Rs 323,456 | 17 of 25 |

By index (1 lot): era B: BANKNIFTY 1011 tr 41% -Rs 62,317, FINNIFTY 707 tr 42% -Rs 45,667, MIDCPNIFTY 281 tr 38% -Rs 27,273, NIFTY 1035 tr 44% -Rs 37,475, SENSEX 450 tr 39% -Rs 37,683; era C: BANKNIFTY 843 tr 48% Rs 162,055, FINNIFTY 833 tr 49% Rs 140,389, MIDCPNIFTY 633 tr 47% Rs 68,414, NIFTY 711 tr 48% Rs 81,042, SENSEX 701 tr 45% Rs 26,694

BH q 1; era-A twin p None.
**Verdict: NOT RELIABLE.** Failed: era A: untestable (no data); era B: net -210,414 <= 0; era B: net without the top 5 trades -250,976 <= 0; era A twin p: untestable; BH q 1 > 0.1.

## M1: End-of-day momentum (r1 + r2), NIFTY ATM, 14:50 -> 15:20

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 65 | 43.1% | -Rs 3,026 | Rs 1,137 / -Rs 942 | -Rs 20,665 | -Rs 3,294 | Rs 8,719 | -Rs 5,434 |
| B | 206 | 45.1% | -Rs 20,878 | Rs 708 / -Rs 767 | -Rs 36,793 | -Rs 4,507 | Rs 30,613 | -Rs 8,606 |
| C | 141 | 36.9% | -Rs 27,783 | Rs 1,180 / -Rs 1,002 | -Rs 49,655 | -Rs 5,021 | Rs 31,593 | -Rs 11,299 |

**twice the slippage**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 65 | 43.1% | -Rs 4,008 | Rs 1,122 / -Rs 957 | -Rs 21,553 | -Rs 3,311 | Rs 8,917 | -Rs 5,585 |
| B | 206 | 44.7% | -Rs 22,850 | Rs 706 / -Rs 770 | -Rs 38,704 | -Rs 4,516 | Rs 31,892 | -Rs 8,680 |
| C | 141 | 36.2% | -Rs 30,026 | Rs 1,187 / -Rs 1,006 | -Rs 51,805 | -Rs 5,049 | Rs 33,761 | -Rs 11,436 |

**max lots that fit Rs 56k**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 65 | 46.2% | -Rs 13,328 | Rs 7,141 / -Rs 6,502 | -Rs 107,445 | -Rs 27,770 | Rs 48,698 | -Rs 32,869 |
| B | 206 | 46.1% | -Rs 16,466 | Rs 8,745 / -Rs 7,633 | -Rs 176,072 | -Rs 44,646 | Rs 233,798 | -Rs 85,021 |
| C | 141 | 38.3% | -Rs 81,132 | Rs 7,409 / -Rs 5,531 | -Rs 206,118 | -Rs 23,598 | Rs 142,350 | -Rs 53,356 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| A | 0.3423 | 3.36e-01 | -Rs 7,555 | 2020: -Rs 2,261, 2021: -Rs 765 | 5 of 12 |
| B | 0.6532 | 6.53e-01 | -Rs 15,162 | 2021: -Rs 995, 2022: -Rs 6,019, 2023: -Rs 8,923, 2024: -Rs 4,939 | 12 of 35 |
| C | 0.6722 | 6.76e-01 | -Rs 21,087 | 2024: -Rs 2,291, 2025: -Rs 15,378, 2026: -Rs 10,113 | 8 of 25 |

BH q 1; era-A twin p 0.3423288355822089.
**Verdict: NOT RELIABLE.** Failed: era A: net -3,026 <= 0; era A: net without the top 5 trades -20,665 <= 0; era B: net -20,878 <= 0; era B: net without the top 5 trades -36,793 <= 0; era C: net -27,783 <= 0; era C: net without the top 5 trades -49,655 <= 0; era A twin p 0.342 >= 0.05; BH q 1 > 0.1.

## M2: End-of-day momentum (r2 alone), NIFTY ATM, 14:50 -> 15:20

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 76 | 32.9% | -Rs 15,802 | Rs 1,641 / -Rs 1,114 | -Rs 38,496 | -Rs 3,561 | Rs 15,802 | -Rs 5,910 |
| B | 212 | 42.9% | -Rs 19,522 | Rs 879 / -Rs 823 | -Rs 35,979 | -Rs 4,507 | Rs 25,435 | -Rs 7,513 |
| C | 169 | 40.2% | -Rs 27,428 | Rs 1,040 / -Rs 972 | -Rs 47,410 | -Rs 4,891 | Rs 36,536 | -Rs 11,914 |

**twice the slippage**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 76 | 32.9% | -Rs 16,920 | Rs 1,625 / -Rs 1,128 | -Rs 39,510 | -Rs 3,572 | Rs 16,920 | -Rs 5,976 |
| B | 212 | 42.5% | -Rs 21,559 | Rs 879 / -Rs 825 | -Rs 37,956 | -Rs 4,516 | Rs 26,391 | -Rs 7,600 |
| C | 169 | 40.2% | -Rs 30,113 | Rs 1,024 / -Rs 987 | -Rs 50,002 | -Rs 4,917 | Rs 37,467 | -Rs 12,087 |

**max lots that fit Rs 56k**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 76 | 35.5% | -Rs 175,319 | Rs 9,908 / -Rs 9,038 | -Rs 306,605 | -Rs 31,668 | Rs 175,319 | -Rs 52,245 |
| B | 212 | 44.8% | -Rs 21,777 | Rs 10,200 / -Rs 8,468 | -Rs 199,414 | -Rs 44,646 | Rs 161,607 | -Rs 70,608 |
| C | 169 | 42.0% | -Rs 95,194 | Rs 6,306 / -Rs 5,540 | -Rs 211,350 | -Rs 23,598 | Rs 170,002 | -Rs 55,089 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| A | 0.7466 | 7.46e-01 | -Rs 8,222 | 2020: -Rs 10,343, 2021: -Rs 5,459 | 4 of 12 |
| B | 0.5932 | 6.06e-01 | -Rs 15,735 | 2021: Rs 652, 2022: -Rs 9,423, 2023: -Rs 95, 2024: -Rs 10,655 | 14 of 35 |
| C | 0.5847 | 5.81e-01 | -Rs 24,152 | 2024: -Rs 2,372, 2025: -Rs 5,282, 2026: -Rs 19,773 | 11 of 25 |

BH q 1; era-A twin p 0.7466266866566716.
**Verdict: NOT RELIABLE.** Failed: era A: net -15,802 <= 0; era A: net without the top 5 trades -38,496 <= 0; era B: net -19,522 <= 0; era B: net without the top 5 trades -35,979 <= 0; era C: net -27,428 <= 0; era C: net without the top 5 trades -47,410 <= 0; era A twin p 0.747 >= 0.05; BH q 1 > 0.1.

## V1: Noise-area breakout over TWAP, NIFTY ATM, 30-min checks

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 261 | 27.2% | Rs 65,303 | Rs 3,750 / -Rs 1,058 | -Rs 22,691 | -Rs 6,344 | Rs 25,908 | -Rs 7,820 |
| B | 756 | 27.5% | Rs 44,150 | Rs 2,412 / -Rs 835 | -Rs 30,538 | -Rs 4,631 | Rs 66,874 | -Rs 13,728 |
| C | 550 | 30.4% | -Rs 74,700 | Rs 3,310 / -Rs 1,638 | -Rs 159,141 | -Rs 11,095 | Rs 113,025 | -Rs 32,005 |

**twice the slippage**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 261 | 26.8% | Rs 61,623 | Rs 3,788 / -Rs 1,066 | -Rs 26,248 | -Rs 6,378 | Rs 26,510 | -Rs 8,144 |
| B | 756 | 27.2% | Rs 37,386 | Rs 2,425 / -Rs 840 | -Rs 37,222 | -Rs 4,643 | Rs 70,342 | -Rs 13,926 |
| C | 550 | 30.4% | -Rs 83,019 | Rs 3,294 / -Rs 1,653 | -Rs 167,334 | -Rs 11,122 | Rs 120,223 | -Rs 32,321 |

**max lots that fit Rs 56k**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 261 | 28.7% | Rs 872,540 | Rs 37,526 / -Rs 10,441 | -Rs 400,032 | -Rs 84,721 | Rs 296,865 | -Rs 83,939 |
| B | 756 | 28.4% | Rs 1,521,726 | Rs 39,518 / -Rs 12,892 | Rs 99,809 | -Rs 136,846 | Rs 536,984 | -Rs 157,818 |
| C | 550 | 30.7% | Rs 225,765 | Rs 29,067 / -Rs 12,301 | -Rs 668,196 | -Rs 119,293 | Rs 766,076 | -Rs 159,408 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| A | 0.0080 | 3.38e-03 | -Rs 8,808 | 2020: Rs 36,605, 2021: Rs 28,698 | 8 of 12 |
| B | 0.0085 | 4.33e-03 | -Rs 41,456 | 2021: Rs 3,073, 2022: Rs 24,635, 2023: -Rs 31,460, 2024: Rs 47,902 | 16 of 35 |
| C | 0.6837 | 6.79e-01 | -Rs 54,282 | 2024: -Rs 2,858, 2025: -Rs 53,671, 2026: -Rs 18,171 | 9 of 25 |

BH q 1; era-A twin p 0.0079960019990005.
**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -22,691 <= 0; era B: net without the top 5 trades -30,538 <= 0; era C: net -74,700 <= 0; era C: net without the top 5 trades -159,141 <= 0; BH q 1 > 0.1.

## O1: M1 + trend (50-day average) + VIX overlay

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 40 | 42.5% | -Rs 1,530 | Rs 683 / -Rs 571 | -Rs 8,439 | -Rs 2,141 | Rs 8,214 | -Rs 1,946 |
| B | 115 | 43.5% | -Rs 12,679 | Rs 758 / -Rs 778 | -Rs 28,220 | -Rs 4,507 | Rs 20,402 | -Rs 7,052 |
| C | 84 | 38.1% | -Rs 4,725 | Rs 1,358 / -Rs 926 | -Rs 26,598 | -Rs 5,021 | Rs 18,564 | -Rs 8,891 |

**twice the slippage**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 40 | 42.5% | -Rs 2,114 | Rs 668 / -Rs 585 | -Rs 8,943 | -Rs 2,161 | Rs 8,551 | -Rs 2,024 |
| B | 115 | 43.5% | -Rs 13,781 | Rs 749 / -Rs 788 | -Rs 29,263 | -Rs 4,516 | Rs 20,997 | -Rs 7,095 |
| C | 84 | 36.9% | -Rs 6,006 | Rs 1,386 / -Rs 924 | -Rs 27,785 | -Rs 5,049 | Rs 18,892 | -Rs 8,981 |

**max lots that fit Rs 56k**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 40 | 47.5% | -Rs 4,238 | Rs 4,553 / -Rs 4,321 | -Rs 49,974 | -Rs 15,061 | Rs 53,038 | -Rs 10,857 |
| B | 115 | 44.3% | -Rs 8,847 | Rs 9,299 / -Rs 7,549 | -Rs 166,440 | -Rs 44,646 | Rs 160,974 | -Rs 71,519 |
| C | 84 | 38.1% | Rs 20,992 | Rs 9,107 / -Rs 5,201 | -Rs 103,995 | -Rs 19,271 | Rs 80,969 | -Rs 42,562 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| A | 0.3568 | 3.68e-01 | -Rs 4,485 | 2020: Rs 1,188, 2021: -Rs 2,719 | 5 of 11 |
| B | 0.6567 | 6.49e-01 | -Rs 8,489 | 2021: Rs 422, 2022: -Rs 2,072, 2023: -Rs 5,007, 2024: -Rs 6,021 | 12 of 32 |
| C | 0.2414 | 2.49e-01 | -Rs 12,378 | 2024: Rs 2,662, 2025: -Rs 13,463, 2026: Rs 6,076 | 8 of 23 |

BH q 1; era-A twin p 0.3568215892053973.
**Verdict: NOT RELIABLE.** Failed: era A: net -1,530 <= 0; era A: net without the top 5 trades -8,439 <= 0; era B: net -12,679 <= 0; era B: net without the top 5 trades -28,220 <= 0; era C: net -4,725 <= 0; era C: net without the top 5 trades -26,598 <= 0; era A twin p 0.357 >= 0.05; BH q 1 > 0.1.

## O2: M2 + trend (50-day average) + VIX overlay

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 37 | 37.8% | -Rs 5,058 | Rs 958 / -Rs 803 | -Rs 14,421 | -Rs 2,141 | Rs 10,546 | -Rs 2,063 |
| B | 106 | 43.4% | -Rs 13,269 | Rs 948 / -Rs 948 | -Rs 29,407 | -Rs 4,507 | Rs 21,895 | -Rs 6,764 |
| C | 77 | 39.0% | -Rs 8,596 | Rs 1,165 / -Rs 926 | -Rs 27,117 | -Rs 3,102 | Rs 15,986 | -Rs 8,891 |

**twice the slippage**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 37 | 37.8% | -Rs 5,596 | Rs 943 / -Rs 818 | -Rs 14,881 | -Rs 2,161 | Rs 10,757 | -Rs 2,128 |
| B | 106 | 43.4% | -Rs 14,267 | Rs 938 / -Rs 957 | -Rs 30,345 | -Rs 4,516 | Rs 22,309 | -Rs 6,832 |
| C | 77 | 39.0% | -Rs 9,765 | Rs 1,149 / -Rs 941 | -Rs 28,189 | -Rs 3,117 | Rs 17,031 | -Rs 8,981 |

**max lots that fit Rs 56k**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 37 | 43.2% | -Rs 15,888 | Rs 7,813 / -Rs 6,709 | -Rs 105,474 | -Rs 15,061 | Rs 72,468 | -Rs 24,161 |
| B | 106 | 44.3% | Rs 5,745 | Rs 12,041 / -Rs 9,495 | -Rs 168,250 | -Rs 44,646 | Rs 171,167 | -Rs 62,223 |
| C | 77 | 40.3% | -Rs 52,490 | Rs 7,079 / -Rs 5,912 | -Rs 154,962 | -Rs 19,271 | Rs 89,878 | -Rs 42,562 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| A | 0.5542 | 5.60e-01 | -Rs 3,861 | 2020: Rs 1,569, 2021: -Rs 6,627 | 5 of 12 |
| B | 0.7016 | 6.99e-01 | -Rs 8,015 | 2021: Rs 2,164, 2022: -Rs 5,575, 2023: -Rs 276, 2024: -Rs 9,582 | 15 of 33 |
| C | 0.4108 | 4.20e-01 | -Rs 10,778 | 2024: Rs 2,911, 2025: -Rs 11,510, 2026: Rs 3 | 11 of 22 |

BH q 1; era-A twin p 0.5542228885557221.
**Verdict: NOT RELIABLE.** Failed: era A: 37 trades < 40; era A: net -5,058 <= 0; era A: net without the top 5 trades -14,421 <= 0; era B: net -13,269 <= 0; era B: net without the top 5 trades -29,407 <= 0; era C: net -8,596 <= 0; era C: net without the top 5 trades -27,117 <= 0; era A twin p 0.554 >= 0.05; BH q 1 > 0.1.

## O3: V1 + trend (50-day average) + VIX overlay

**1 lot**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 127 | 30.7% | Rs 15,428 | Rs 2,660 / -Rs 1,003 | -Rs 37,762 | -Rs 4,587 | Rs 21,897 | -Rs 6,616 |
| B | 361 | 28.3% | Rs 15,261 | Rs 2,203 / -Rs 809 | -Rs 40,001 | -Rs 4,631 | Rs 25,435 | -Rs 7,385 |
| C | 286 | 30.8% | Rs 9,532 | Rs 3,709 / -Rs 1,600 | -Rs 69,664 | -Rs 11,095 | Rs 36,081 | -Rs 10,712 |

**twice the slippage**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 127 | 30.7% | Rs 13,677 | Rs 2,645 / -Rs 1,017 | -Rs 39,414 | -Rs 4,625 | Rs 22,301 | -Rs 6,761 |
| B | 361 | 27.7% | Rs 12,033 | Rs 2,237 / -Rs 811 | -Rs 43,155 | -Rs 4,643 | Rs 26,850 | -Rs 7,504 |
| C | 286 | 30.8% | Rs 5,288 | Rs 3,692 / -Rs 1,614 | -Rs 73,776 | -Rs 11,122 | Rs 36,940 | -Rs 10,932 |

**max lots that fit Rs 56k**

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 127 | 33.9% | Rs 243,409 | Rs 26,280 / -Rs 10,555 | -Rs 440,193 | -Rs 55,294 | Rs 159,375 | -Rs 113,105 |
| B | 361 | 28.8% | Rs 1,239,610 | Rs 40,964 / -Rs 11,753 | Rs 91,378 | -Rs 85,408 | Rs 240,223 | -Rs 88,018 |
| C | 286 | 30.8% | Rs 171,613 | Rs 30,400 / -Rs 12,644 | -Rs 592,118 | -Rs 81,536 | Rs 483,043 | -Rs 154,442 |

| era | twin p (empirical) | twin p (normal) | twin mean | by year | months up |
|---|---|---|---|---|---|
| A | 0.1634 | 1.60e-01 | -Rs 4,289 | 2020: -Rs 4,334, 2021: Rs 19,762 | 6 of 12 |
| B | 0.0650 | 5.78e-02 | -Rs 19,562 | 2021: Rs 3,053, 2022: Rs 15,499, 2023: Rs 1,156, 2024: -Rs 4,446 | 15 of 35 |
| C | 0.1439 | 1.37e-01 | -Rs 27,180 | 2024: Rs 3,205, 2025: -Rs 13,794, 2026: Rs 20,120 | 9 of 24 |

BH q 1; era-A twin p 0.1634182908545727.
**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -37,762 <= 0; era B: net without the top 5 trades -40,001 <= 0; era C: net without the top 5 trades -69,664 <= 0; era A twin p 0.163 >= 0.05; BH q 1 > 0.1.

