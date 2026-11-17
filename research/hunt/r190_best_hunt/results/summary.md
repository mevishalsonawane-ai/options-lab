# R190 best strategy hunt: summary for Boss

Written 17 Nov 2026. All money at 1 lot after dated Zerodha charges and slippage, real 1-minute option prices, option BUYING only.

**Outcome: none of the 13 candidates passed the RELIABLE gate.** Nothing here is a "great and reliable" strategy by the rules we set before looking.

**What this means, in plain words:**

- **Night (R3) is still the most consistent rule we have.** It made money in all three eras: on NIFTY alone Rs 18,086 (era A, 66 trades), Rs 55,126 (B), Rs 65,250 (C), winning 58% / 57% / 53% of trades; the NIFTY + BANKNIFTY book made Rs 124,990 (B) and Rs 192,726 (C). But it is NOT proven: in era A it was not clearly better than random nights (twin p 0.070), its profit there came from its 5 best nights, and after counting the ~4,760 ideas tried it is not significant. Its drawdowns and worst months also break the account stop at 1 lot each (the book's worst month in era C was -Rs 37,286).
- **Liquidity 15+5 made money in both eras it can be tested on** (Rs 73,741 in B at 31% wins, Rs 255,293 in C at 41%), but era A has no BANKNIFTY / FINNIFTY / MIDCPNIFTY option data, so it cannot meet the gate.
- **The R72 Donchian arms lose before Nov 2024, again:** R72 -Rs 407,315 in era B (4389 trades), with R151's picker -Rs 184,515. Their profit is a recent-regime story. (The picker's era-C numbers are mostly in-sample: it was trained on Nov 2024 - Mar 2026.)
- **The new outside ideas did not hold up on NIFTY options.** End-of-day momentum lost in every era. The noise-area breakout made Rs 65,303 (A) and Rs 44,150 (B) but lost -Rs 74,700 in the recent era, and wins only about 28% of trades. The trend / VIX overlay did not rescue either.
- **High win rate and reliable profit did not come together anywhere.** The best win rates (Night, 53-58%) come with an average loss about as big as the average win.

The gate (fixed before any number): profit in ALL THREE eras with 40+ trades each, still profit without the best 5 trades, beats random entries in era A (p < 0.05), and survives the correction for the ~4,760 ideas logged so far (BH q <= 0.10).

Eras: **A** = Aug 2020 - Jul 2021 (the oldest option data, NIFTY only, read once at the end; note: an Oct 2026 hunt had used it for other rules, so it is not virgin data); **B** = Dec 2021 - Oct 2024; **C** = Nov 2024 - 13 Nov 2026.

Account stop for reference: -Rs 40,000 drawdown or -Rs 13,000 in a month.

## N1B: Night (R3) book: NIFTY + BANKNIFTY, 1 lot each

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | -Rs 4,879 | Rs 15,034 | -Rs 7,136 |
| B | 498 | 55.0% | Rs 124,990 | Rs 1,975 / -Rs 1,858 | Rs 70,545 | -Rs 15,507 | Rs 34,960 | -Rs 31,592 |
| C | 351 | 53.0% | Rs 192,726 | Rs 3,583 / -Rs 2,871 | Rs 111,548 | -Rs 15,106 | Rs 80,780 | -Rs 37,286 |

**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -13,803 <= 0; era A twin p 0.073 >= 0.05; BH q 1 > 0.1.
Account stop: era B: worst month Rs -31,592 < -Rs 13k; era C: max drawdown Rs 80,780 > Rs 40k, worst month Rs -37,286 < -Rs 13k.

## N1N: Night (R3) on NIFTY alone

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | -Rs 4,879 | Rs 15,034 | -Rs 7,136 |
| B | 244 | 56.6% | Rs 55,126 | Rs 1,868 / -Rs 1,911 | Rs 9,506 | -Rs 6,829 | Rs 17,341 | -Rs 17,341 |
| C | 155 | 52.9% | Rs 65,250 | Rs 3,532 / -Rs 3,074 | -Rs 67 | -Rs 11,341 | Rs 41,709 | -Rs 19,000 |

**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -13,803 <= 0; era C: net without the top 5 trades -67 <= 0; era A twin p 0.070 >= 0.05; BH q 1 > 0.1.
Account stop: era B: worst month Rs -17,341 < -Rs 13k; era C: max drawdown Rs 41,709 > Rs 40k, worst month Rs -19,000 < -Rs 13k.

## N2: Night for Rs 56k: one position a night, month cap -Rs 13k

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | -Rs 4,879 | Rs 15,034 | -Rs 7,136 |
| B | 336 | 53.0% | Rs 35,938 | Rs 1,844 / -Rs 1,850 | -Rs 12,540 | -Rs 9,398 | Rs 27,456 | -Rs 19,233 |
| C | 238 | 53.4% | Rs 132,014 | Rs 3,467 / -Rs 2,778 | Rs 58,202 | -Rs 11,341 | Rs 35,390 | -Rs 15,503 |

**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -13,803 <= 0; era B: net without the top 5 trades -12,540 <= 0; era A twin p 0.074 >= 0.05; BH q 1 > 0.1.
Account stop: era B: worst month Rs -19,233 < -Rs 13k; era C: worst month Rs -15,503 < -Rs 13k.

## L1: Liquidity 15+5 (BANKNIFTY, FINNIFTY, MIDCPNIFTY), as built

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | no data (untestable) | | | | | | | |
| B | 1099 | 31.0% | Rs 73,741 | Rs 1,572 / -Rs 610 | Rs 10,378 | -Rs 5,742 | Rs 51,118 | -Rs 13,213 |
| C | 1323 | 41.5% | Rs 255,293 | Rs 1,885 / -Rs 1,007 | Rs 168,104 | -Rs 11,405 | Rs 61,062 | -Rs 37,821 |

**Verdict: NOT RELIABLE.** Failed: era A: untestable (no data); era A twin p: untestable; BH q 1 > 0.1.
Account stop: era B: max drawdown Rs 51,118 > Rs 40k, worst month Rs -13,213 < -Rs 13k; era C: max drawdown Rs 61,062 > Rs 40k, worst month Rs -37,821 < -Rs 13k.

## D1: R72 Breadth Donchian, today's rules

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | no data (untestable) | | | | | | | |
| B | 4389 | 39.8% | -Rs 407,315 | Rs 888 / -Rs 743 | -Rs 455,331 | -Rs 16,757 | Rs 451,132 | -Rs 54,175 |
| C | 4571 | 46.5% | Rs 390,101 | Rs 1,711 / -Rs 1,327 | Rs 326,344 | -Rs 58,613 | Rs 199,077 | -Rs 78,467 |

**Verdict: NOT RELIABLE.** Failed: era A: untestable (no data); era B: net -407,315 <= 0; era B: net without the top 5 trades -455,331 <= 0; era A twin p: untestable; BH q 1 > 0.1.
Account stop: era B: max drawdown Rs 451,132 > Rs 40k, worst month Rs -54,175 < -Rs 13k; era C: max drawdown Rs 199,077 > Rs 40k, worst month Rs -78,467 < -Rs 13k.

## D2: R72 + R151 picker (live by Boss's exception)

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | no data (untestable) | | | | | | | |
| B | 2503 | 41.4% | -Rs 184,515 | Rs 973 / -Rs 812 | -Rs 232,531 | -Rs 13,602 | Rs 247,894 | -Rs 37,319 |
| C | 2405 | 51.9% | Rs 689,639 | Rs 1,953 / -Rs 1,508 | Rs 620,864 | -Rs 44,106 | Rs 110,062 | -Rs 19,993 |

**Verdict: NOT RELIABLE.** Failed: era A: untestable (no data); era B: net -184,515 <= 0; era B: net without the top 5 trades -232,531 <= 0; era A twin p: untestable; BH q 1 > 0.1.
Account stop: era B: max drawdown Rs 247,894 > Rs 40k, worst month Rs -37,319 < -Rs 13k; era C: max drawdown Rs 110,062 > Rs 40k, worst month Rs -19,993 < -Rs 13k.

## D3: R72 + FB-R72-A10 (skip after a very wide day)

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | no data (untestable) | | | | | | | |
| B | 3484 | 41.5% | -Rs 210,414 | Rs 852 / -Rs 708 | -Rs 250,976 | -Rs 13,602 | Rs 263,801 | -Rs 34,263 |
| C | 3721 | 47.6% | Rs 478,594 | Rs 1,691 / -Rs 1,289 | Rs 414,837 | -Rs 58,613 | Rs 147,672 | -Rs 65,007 |

**Verdict: NOT RELIABLE.** Failed: era A: untestable (no data); era B: net -210,414 <= 0; era B: net without the top 5 trades -250,976 <= 0; era A twin p: untestable; BH q 1 > 0.1.
Account stop: era B: max drawdown Rs 263,801 > Rs 40k, worst month Rs -34,263 < -Rs 13k; era C: max drawdown Rs 147,672 > Rs 40k, worst month Rs -65,007 < -Rs 13k.

## M1: End-of-day momentum (r1 + r2), NIFTY ATM, 14:50 -> 15:20

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 65 | 43.1% | -Rs 3,026 | Rs 1,137 / -Rs 942 | -Rs 20,665 | -Rs 3,294 | Rs 8,719 | -Rs 5,434 |
| B | 206 | 45.1% | -Rs 20,878 | Rs 708 / -Rs 767 | -Rs 36,793 | -Rs 4,507 | Rs 30,613 | -Rs 8,606 |
| C | 141 | 36.9% | -Rs 27,783 | Rs 1,180 / -Rs 1,002 | -Rs 49,655 | -Rs 5,021 | Rs 31,593 | -Rs 11,299 |

**Verdict: NOT RELIABLE.** Failed: era A: net -3,026 <= 0; era A: net without the top 5 trades -20,665 <= 0; era B: net -20,878 <= 0; era B: net without the top 5 trades -36,793 <= 0; era C: net -27,783 <= 0; era C: net without the top 5 trades -49,655 <= 0; era A twin p 0.342 >= 0.05; BH q 1 > 0.1.

## M2: End-of-day momentum (r2 alone), NIFTY ATM, 14:50 -> 15:20

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 76 | 32.9% | -Rs 15,802 | Rs 1,641 / -Rs 1,114 | -Rs 38,496 | -Rs 3,561 | Rs 15,802 | -Rs 5,910 |
| B | 212 | 42.9% | -Rs 19,522 | Rs 879 / -Rs 823 | -Rs 35,979 | -Rs 4,507 | Rs 25,435 | -Rs 7,513 |
| C | 169 | 40.2% | -Rs 27,428 | Rs 1,040 / -Rs 972 | -Rs 47,410 | -Rs 4,891 | Rs 36,536 | -Rs 11,914 |

**Verdict: NOT RELIABLE.** Failed: era A: net -15,802 <= 0; era A: net without the top 5 trades -38,496 <= 0; era B: net -19,522 <= 0; era B: net without the top 5 trades -35,979 <= 0; era C: net -27,428 <= 0; era C: net without the top 5 trades -47,410 <= 0; era A twin p 0.747 >= 0.05; BH q 1 > 0.1.

## V1: Noise-area breakout over TWAP, NIFTY ATM, 30-min checks

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 261 | 27.2% | Rs 65,303 | Rs 3,750 / -Rs 1,058 | -Rs 22,691 | -Rs 6,344 | Rs 25,908 | -Rs 7,820 |
| B | 756 | 27.5% | Rs 44,150 | Rs 2,412 / -Rs 835 | -Rs 30,538 | -Rs 4,631 | Rs 66,874 | -Rs 13,728 |
| C | 550 | 30.4% | -Rs 74,700 | Rs 3,310 / -Rs 1,638 | -Rs 159,141 | -Rs 11,095 | Rs 113,025 | -Rs 32,005 |

**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -22,691 <= 0; era B: net without the top 5 trades -30,538 <= 0; era C: net -74,700 <= 0; era C: net without the top 5 trades -159,141 <= 0; BH q 1 > 0.1.
Account stop: era B: max drawdown Rs 66,874 > Rs 40k, worst month Rs -13,728 < -Rs 13k; era C: max drawdown Rs 113,025 > Rs 40k, worst month Rs -32,005 < -Rs 13k.

## O1: M1 + trend (50-day average) + VIX overlay

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 40 | 42.5% | -Rs 1,530 | Rs 683 / -Rs 571 | -Rs 8,439 | -Rs 2,141 | Rs 8,214 | -Rs 1,946 |
| B | 115 | 43.5% | -Rs 12,679 | Rs 758 / -Rs 778 | -Rs 28,220 | -Rs 4,507 | Rs 20,402 | -Rs 7,052 |
| C | 84 | 38.1% | -Rs 4,725 | Rs 1,358 / -Rs 926 | -Rs 26,598 | -Rs 5,021 | Rs 18,564 | -Rs 8,891 |

**Verdict: NOT RELIABLE.** Failed: era A: net -1,530 <= 0; era A: net without the top 5 trades -8,439 <= 0; era B: net -12,679 <= 0; era B: net without the top 5 trades -28,220 <= 0; era C: net -4,725 <= 0; era C: net without the top 5 trades -26,598 <= 0; era A twin p 0.357 >= 0.05; BH q 1 > 0.1.

## O2: M2 + trend (50-day average) + VIX overlay

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 37 | 37.8% | -Rs 5,058 | Rs 958 / -Rs 803 | -Rs 14,421 | -Rs 2,141 | Rs 10,546 | -Rs 2,063 |
| B | 106 | 43.4% | -Rs 13,269 | Rs 948 / -Rs 948 | -Rs 29,407 | -Rs 4,507 | Rs 21,895 | -Rs 6,764 |
| C | 77 | 39.0% | -Rs 8,596 | Rs 1,165 / -Rs 926 | -Rs 27,117 | -Rs 3,102 | Rs 15,986 | -Rs 8,891 |

**Verdict: NOT RELIABLE.** Failed: era A: 37 trades < 40; era A: net -5,058 <= 0; era A: net without the top 5 trades -14,421 <= 0; era B: net -13,269 <= 0; era B: net without the top 5 trades -29,407 <= 0; era C: net -8,596 <= 0; era C: net without the top 5 trades -27,117 <= 0; era A twin p 0.554 >= 0.05; BH q 1 > 0.1.

## O3: V1 + trend (50-day average) + VIX overlay

| era | trades | win % | net | avg win / avg loss | net without top 5 | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| A | 127 | 30.7% | Rs 15,428 | Rs 2,660 / -Rs 1,003 | -Rs 37,762 | -Rs 4,587 | Rs 21,897 | -Rs 6,616 |
| B | 361 | 28.3% | Rs 15,261 | Rs 2,203 / -Rs 809 | -Rs 40,001 | -Rs 4,631 | Rs 25,435 | -Rs 7,385 |
| C | 286 | 30.8% | Rs 9,532 | Rs 3,709 / -Rs 1,600 | -Rs 69,664 | -Rs 11,095 | Rs 36,081 | -Rs 10,712 |

**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -37,762 <= 0; era B: net without the top 5 trades -40,001 <= 0; era C: net without the top 5 trades -69,664 <= 0; era A twin p 0.163 >= 0.05; BH q 1 > 0.1.

