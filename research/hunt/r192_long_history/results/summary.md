# R192 longer history and replicated patterns: summary for Boss

Written 17 Nov 2026. NIFTY only, option BUYING only, 1 lot, the 1-ITM option of the nearest expiry. Real money (eras A, B, C) uses real 1-minute option prices, dated Zerodha charges and R17 slippage. Eras S0 / S use a SYNTHETIC option price (Black-76 from the index and India VIX, calibrated on real fills) as a check of the underlying edge only; synthetic rupees are never added to real rupees.

**Outcome: none of the 11 ideas passed the RELIABLE gate.** Nothing here is a "great and reliable" strategy by the rules fixed before looking.

**What this means, in plain words:**

- **The best lead is K4, a Night-like rule: buy the call after a strong close at the day's top (put after a weak close at the bottom), 15:20 to 09:16.** Its filter (only a close in the top / bottom 20% of the day's range) was chosen on 2017-18 only, then left alone. On real prices it made money in all three eras (Rs 28,963 in A, Rs 76,487 in B, Rs 12,238 in C; 536 trades, 50% won) and beat random nights clearly (twin p 0.001). But it lost on the synthetic 2019 - Jul 2020 check (-Rs 21,214), its profit in A and C came from a handful of big nights, and after counting the ~4,770 ideas tried it is not significant. Not proven.
- **Gap fade (K5) is the second lead:** buy against a 0.3-1.0% opening gap at 09:20, out when the gap fills. Real money in all three eras (Rs 28,947, Rs 52,282, Rs 3,127), but the synthetic 2017-20 check lost -Rs 47,504, era C rests on its best 5 trades, and twin p 0.076 misses 0.05.
- **The famous calendar and overnight effects do not survive the option's costs.** Buying a call every night (K3) lost -Rs 391,982 in eras B + C, worse than random sides. Turn of the month, pre-holiday and VIX spikes are too rare for 40 trades an era (the 52-week signal fired only 9 times in era C), and each lost in at least one era.
- **Trend, gap-follow and big-move rules lost on real prices** (K6, K9, K10, K11).
- **High win rate and reliable profit did not come together anywhere.** The win rates of the two leads are 41-55%; their average win is only a little bigger than their average loss.
- **Longer history did not rescue anything.** Where the old synthetic eras were positive the real eras often were not (K3, K8, K11), and the two real-money leads lost in the synthetic years; the edges move with the regime.

The gate (fixed before any number): net > 0 with 40+ trades in every era (synthetic S0 for daily rules, synthetic S, real A, B, C), net without the best 5 trades > 0 in each real era, beats random days / sides on the pooled real trades (twin p < 0.05, 5,000 twins), and survives the correction for all 4,771 ideas logged (BH q <= 0.10).

Eras: **S0** Jan 2008 - Mar 2017 (synthetic, daily bars); **S** Apr 2017 - Jul 2020 (synthetic, index minutes); **A** Aug 2020 - Jul 2021; **B** Dec 2021 - Oct 2024; **C** Nov 2024 - 13 Nov 2026 (real). A, B and C were seen by earlier hunts at the data level (not these rules).

Account stop for reference: -Rs 40,000 drawdown or -Rs 13,000 in a month (account about Rs 56k).

## How good is the synthetic price?

- Amended model (trading-time clock, w = 0.15, a = 0.89): entry premium median absolute error 8.2% on 10,374 era A + B entries (the fit), 9.6% on 5,316 era C entries (the check).
- Pre-registered model (calendar clock, a = 1.02): 10.8% (A + B), 13.8% (C).

Synthetic minus real net per trade, every pool trade (all eligible days, both sides), 1 lot at the real lot:

| hold type | A + B trades | A + B mean error | A + B mean abs error | A + B corr | C trades | C mean error | C mean abs error | calendar clock A + B / C mean error |
|---|---|---|---|---|---|---|---|---|
| ON (15:20 -> next 09:16) | 1,524 | -Rs 7 | Rs 530 | 0.97 | 780 | Rs 227 | Rs 1,282 | -Rs 771 / -Rs 1,012 |
| CC1 (15:20 -> next session 15:20) | 1,524 | Rs 31 | Rs 592 | 0.97 | 780 | Rs 390 | Rs 1,427 | -Rs 277 / -Rs 160 |
| CC2 (15:20 -> 2 sessions on 15:20) | 1,524 | Rs 110 | Rs 712 | 0.97 | 780 | Rs 652 | Rs 1,707 | -Rs 261 / -Rs 211 |
| D2 (09:20 -> next session 15:20) | 1,934 | Rs 87 | Rs 728 | 0.97 | 992 | Rs 565 | Rs 1,572 | Rs 318 / Rs 867 |
| G5 (K5 intraday (target + stop)) | 1,934 | Rs 19 | Rs 461 | 0.95 | 992 | -Rs 6 | Rs 889 | Rs 195 / Rs 261 |
| G6 (K6 intraday (stop)) | 1,934 | Rs 10 | Rs 482 | 0.96 | 992 | Rs 63 | Rs 912 | Rs 264 / Rs 449 |

So the synthetic eras can tell a clear edge from a clear loss, but an edge of a few hundred rupees a trade is inside their error. Era C (bigger lots, higher STT) is priced more kindly by the model than reality.

## K1: Turn of the month: call, last session 15:20 -> 2nd session of the new month 15:20

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 91 | 52.7% | Rs 52,547 | Rs 4,095 / -Rs 3,349 | (n/a) | Rs 16,664 | -Rs 9,225 |
| S (synthetic) | 32 | 37.5% | -Rs 18,496 | Rs 6,045 / -Rs 4,552 | (n/a) | Rs 62,549 | -Rs 11,205 |
| A | 11 | 72.7% | Rs 71,424 | Rs 11,050 / -Rs 5,658 | -Rs 1,383 | Rs 16,974 | -Rs 10,912 |
| B | 28 | 35.7% | -Rs 31,566 | Rs 5,812 / -Rs 4,983 | -Rs 78,593 | Rs 61,076 | -Rs 12,289 |
| C | 19 | 36.8% | -Rs 46,234 | Rs 11,552 / -Rs 10,592 | -Rs 120,149 | Rs 100,532 | -Rs 20,233 |

**Verdict: NOT RELIABLE.** Failed: era S: 32 trades < 40; era S: net -18,496 <= 0; era A: 11 trades < 40; era A: net without the top 5 trades -1,383 <= 0; era B: 28 trades < 40; era B: net -31,566 <= 0; era B: net without the top 5 trades -78,593 <= 0; era C: 19 trades < 40; era C: net -46,234 <= 0; era C: net without the top 5 trades -120,149 <= 0; pooled twin p 0.281 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era B: max drawdown Rs 61,076 > Rs 40k; era C: max drawdown Rs 100,532 > Rs 40k, worst month Rs -20,233 < -Rs 13k.

## K2: Pre-holiday: call, 15:20 the session before -> 15:20 on the pre-holiday session

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 117 | 51.3% | Rs 46,934 | Rs 2,533 / -Rs 1,843 | (n/a) | Rs 15,948 | -Rs 6,459 |
| S (synthetic) | 36 | 38.9% | -Rs 15,221 | Rs 4,953 / -Rs 3,844 | (n/a) | Rs 55,191 | -Rs 20,938 |
| A | 9 | 44.4% | Rs 6,072 | Rs 8,212 / -Rs 5,355 | -Rs 26,215 | Rs 18,227 | -Rs 7,988 |
| B | 29 | 48.3% | Rs 39,160 | Rs 5,521 / -Rs 2,543 | -Rs 18,138 | Rs 16,791 | -Rs 5,289 |
| C | 22 | 18.2% | -Rs 101,926 | Rs 7,475 / -Rs 7,324 | -Rs 131,555 | Rs 101,926 | -Rs 20,686 |

**Verdict: NOT RELIABLE.** Failed: era S: 36 trades < 40; era S: net -15,221 <= 0; era A: 9 trades < 40; era A: net without the top 5 trades -26,215 <= 0; era B: 29 trades < 40; era B: net without the top 5 trades -18,138 <= 0; era C: 22 trades < 40; era C: net -101,926 <= 0; era C: net without the top 5 trades -131,555 <= 0; pooled twin p 0.745 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era C: max drawdown Rs 101,926 > Rs 40k, worst month Rs -20,686 < -Rs 13k.

## K3: Overnight drift: call every night, 15:20 -> 09:16

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 2111 | 41.6% | Rs 57,653 | Rs 1,169 / -Rs 787 | (n/a) | Rs 149,768 | -Rs 19,030 |
| S (synthetic) | 719 | 53.5% | Rs 115,218 | Rs 2,064 / -Rs 2,034 | (n/a) | Rs 64,131 | -Rs 38,225 |
| A | 196 | 58.2% | Rs 61,523 | Rs 2,437 / -Rs 2,638 | Rs 11,295 | Rs 20,181 | -Rs 10,351 |
| B | 568 | 51.9% | -Rs 50,634 | Rs 1,593 / -Rs 1,907 | -Rs 87,814 | Rs 70,532 | -Rs 20,986 |
| C | 393 | 40.2% | -Rs 341,348 | Rs 3,195 / -Rs 3,601 | -Rs 420,060 | Rs 382,041 | -Rs 70,918 |

**Verdict: NOT RELIABLE.** Failed: era B: net -50,634 <= 0; era B: net without the top 5 trades -87,814 <= 0; era C: net -341,348 <= 0; era C: net without the top 5 trades -420,060 <= 0; pooled twin p 0.584 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era B: max drawdown Rs 70,532 > Rs 40k, worst month Rs -20,986 < -Rs 13k; era C: max drawdown Rs 382,041 > Rs 40k, worst month Rs -70,918 < -Rs 13k.

## K4: Night proxy (strong close) + one filter chosen on 2017-18, 15:20 -> 09:16 [filter F5]

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S (synthetic, 2019 - Jul 2020) | 150 | 39.3% | -Rs 21,214 | Rs 3,437 / -Rs 2,461 | (n/a) | Rs 59,679 | -Rs 29,130 |
| A | 95 | 54.7% | Rs 28,963 | Rs 2,574 / -Rs 2,439 | -Rs 7,163 | Rs 22,400 | -Rs 12,313 |
| B | 277 | 51.6% | Rs 76,487 | Rs 2,112 / -Rs 1,684 | Rs 20,623 | Rs 33,934 | -Rs 13,511 |
| C | 164 | 45.7% | Rs 12,238 | Rs 3,867 / -Rs 3,121 | -Rs 57,501 | Rs 52,381 | -Rs 32,240 |

**Verdict: NOT RELIABLE.** Failed: era S: net -21,214 <= 0; era A: net without the top 5 trades -7,163 <= 0; era C: net without the top 5 trades -57,501 <= 0; BH q 1 > 0.1.
Account stop (1 lot): era B: worst month Rs -13,511 < -Rs 13k; era C: max drawdown Rs 52,381 > Rs 40k, worst month Rs -32,240 < -Rs 13k.

## K5: Gap fade 0.3-1.0% at 09:20, target the previous close, stop -30%, out 15:20

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S (synthetic) | 292 | 48.6% | -Rs 47,504 | Rs 1,813 / -Rs 2,033 | (n/a) | Rs 60,478 | -Rs 15,615 |
| A | 128 | 46.9% | Rs 28,947 | Rs 3,194 / -Rs 2,393 | -Rs 11,708 | Rs 23,624 | -Rs 7,867 |
| B | 310 | 43.9% | Rs 52,282 | Rs 2,508 / -Rs 1,660 | Rs 15,410 | Rs 30,458 | -Rs 15,282 |
| C | 147 | 40.8% | Rs 3,127 | Rs 4,386 / -Rs 2,989 | -Rs 48,976 | Rs 53,955 | -Rs 20,581 |

**Verdict: NOT RELIABLE.** Failed: era S: net -47,504 <= 0; era A: net without the top 5 trades -11,708 <= 0; era C: net without the top 5 trades -48,976 <= 0; pooled twin p 0.076 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era B: worst month Rs -15,282 < -Rs 13k; era C: max drawdown Rs 53,955 > Rs 40k, worst month Rs -20,581 < -Rs 13k.

## K6: Gap follow >= 0.5% confirmed at 09:30, stop -30%, out 15:20

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S (synthetic) | 69 | 36.2% | -Rs 2,469 | Rs 4,711 / -Rs 2,733 | (n/a) | Rs 61,920 | -Rs 10,495 |
| A | 38 | 28.9% | -Rs 19,915 | Rs 3,655 / -Rs 2,227 | -Rs 52,239 | Rs 29,694 | -Rs 7,159 |
| B | 84 | 39.3% | -Rs 27,561 | Rs 2,399 / -Rs 2,093 | -Rs 60,681 | Rs 30,267 | -Rs 12,884 |
| C | 42 | 26.2% | -Rs 40,461 | Rs 6,988 / -Rs 3,785 | -Rs 104,411 | Rs 63,258 | -Rs 19,692 |

**Verdict: NOT RELIABLE.** Failed: era S: net -2,469 <= 0; era A: 38 trades < 40; era A: net -19,915 <= 0; era A: net without the top 5 trades -52,239 <= 0; era B: net -27,561 <= 0; era B: net without the top 5 trades -60,681 <= 0; era C: net -40,461 <= 0; era C: net without the top 5 trades -104,411 <= 0; pooled twin p 0.914 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era C: max drawdown Rs 63,258 > Rs 40k, worst month Rs -19,692 < -Rs 13k.

## K7: India VIX spike (+25% vs 10-day mean on a down day): call 09:20 -> next session 15:20

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 28 | 57.1% | Rs 30,153 | Rs 5,232 / -Rs 4,463 | (n/a) | Rs 18,880 | -Rs 9,542 |
| S (synthetic) | 14 | 28.6% | -Rs 27,294 | Rs 15,479 / -Rs 8,921 | (n/a) | Rs 38,874 | -Rs 23,029 |
| A | 0 | | | | | | |
| B | 8 | 37.5% | -Rs 8,847 | Rs 3,246 / -Rs 3,717 | -Rs 13,781 | Rs 11,347 | -Rs 6,328 |
| C | 7 | 71.4% | Rs 6,660 | Rs 6,074 / -Rs 11,856 | -Rs 23,711 | Rs 17,287 | -Rs 13,289 |

**Verdict: NOT RELIABLE.** Failed: era S0: 28 trades < 40; era S: 14 trades < 40; era S: net -27,294 <= 0; era A: no trades; era B: 8 trades < 40; era B: net -8,847 <= 0; era B: net without the top 5 trades -13,781 <= 0; era C: 7 trades < 40; era C: net without the top 5 trades -23,711 <= 0; pooled twin p 0.418 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era C: worst month Rs -13,289 < -Rs 13k.

## K8: 52-week closing high / low: call / put 15:20 -> next session 15:20

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 184 | 42.9% | Rs 3,872 | Rs 2,644 / -Rs 1,953 | (n/a) | Rs 45,826 | -Rs 11,162 |
| S (synthetic) | 83 | 50.6% | Rs 71,058 | Rs 3,961 / -Rs 2,324 | (n/a) | Rs 30,768 | -Rs 7,919 |
| A | 43 | 48.8% | Rs 25,781 | Rs 4,325 / -Rs 2,957 | -Rs 10,961 | Rs 19,246 | -Rs 4,072 |
| B | 68 | 44.1% | Rs 18,477 | Rs 2,855 / -Rs 1,768 | -Rs 20,165 | Rs 23,938 | -Rs 4,880 |
| C | 9 | 11.1% | -Rs 45,472 | Rs 2,809 / -Rs 6,035 | -Rs 35,087 | Rs 48,282 | -Rs 25,118 |

**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -10,961 <= 0; era B: net without the top 5 trades -20,165 <= 0; era C: 9 trades < 40; era C: net -45,472 <= 0; era C: net without the top 5 trades -35,087 <= 0; pooled twin p 0.265 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era C: max drawdown Rs 48,282 > Rs 40k, worst month Rs -25,118 < -Rs 13k.

## K9: 200-day trend with a low-volatility filter: call / put 15:20 -> 09:16

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 1203 | 37.1% | -Rs 53,649 | Rs 1,042 / -Rs 685 | (n/a) | Rs 119,573 | -Rs 16,973 |
| S (synthetic) | 318 | 54.7% | Rs 37,062 | Rs 1,344 / -Rs 1,366 | (n/a) | Rs 22,295 | -Rs 13,343 |
| A | 122 | 56.6% | Rs 24,148 | Rs 1,984 / -Rs 2,128 | -Rs 6,377 | Rs 18,367 | -Rs 10,459 |
| B | 286 | 47.9% | -Rs 54,984 | Rs 1,496 / -Rs 1,745 | -Rs 82,722 | Rs 69,621 | -Rs 12,687 |
| C | 177 | 44.1% | -Rs 55,478 | Rs 2,715 / -Rs 2,699 | -Rs 102,612 | Rs 92,723 | -Rs 24,950 |

**Verdict: NOT RELIABLE.** Failed: era S0: net -53,649 <= 0; era A: net without the top 5 trades -6,377 <= 0; era B: net -54,984 <= 0; era B: net without the top 5 trades -82,722 <= 0; era C: net -55,478 <= 0; era C: net without the top 5 trades -102,612 <= 0; pooled twin p 0.220 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era B: max drawdown Rs 69,621 > Rs 40k; era C: max drawdown Rs 92,723 > Rs 40k, worst month Rs -24,950 < -Rs 13k.

## K10: Reversal after a 1.5% day: opposite side 15:20 -> next session 15:20

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 441 | 37.4% | -Rs 194,353 | Rs 2,865 / -Rs 2,417 | (n/a) | Rs 232,038 | -Rs 22,667 |
| S (synthetic) | 67 | 43.3% | Rs 118,003 | Rs 10,835 / -Rs 5,164 | (n/a) | Rs 35,166 | -Rs 5,771 |
| A | 26 | 34.6% | -Rs 47,816 | Rs 5,228 / -Rs 5,580 | -Rs 87,465 | Rs 56,644 | -Rs 18,442 |
| B | 54 | 46.3% | -Rs 2,870 | Rs 4,910 / -Rs 4,331 | -Rs 58,279 | Rs 34,132 | -Rs 21,446 |
| C | 32 | 43.8% | -Rs 24,208 | Rs 7,388 / -Rs 7,091 | -Rs 100,717 | Rs 57,957 | -Rs 27,173 |

**Verdict: NOT RELIABLE.** Failed: era S0: net -194,353 <= 0; era A: 26 trades < 40; era A: net -47,816 <= 0; era A: net without the top 5 trades -87,465 <= 0; era B: net -2,870 <= 0; era B: net without the top 5 trades -58,279 <= 0; era C: 32 trades < 40; era C: net -24,208 <= 0; era C: net without the top 5 trades -100,717 <= 0; pooled twin p 0.692 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era A: max drawdown Rs 56,644 > Rs 40k, worst month Rs -18,442 < -Rs 13k; era B: worst month Rs -21,446 < -Rs 13k; era C: max drawdown Rs 57,957 > Rs 40k, worst month Rs -27,173 < -Rs 13k.

## K11: Continuation after a 1.5% day: same side 15:20 -> next session 15:20

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 441 | 47.8% | Rs 89,823 | Rs 2,986 / -Rs 2,349 | (n/a) | Rs 43,760 | -Rs 25,505 |
| S (synthetic) | 67 | 40.3% | Rs 3,808 | Rs 8,566 / -Rs 5,687 | (n/a) | Rs 56,892 | -Rs 15,615 |
| A | 26 | 42.3% | Rs 16,177 | Rs 8,198 / -Rs 4,934 | -Rs 43,378 | Rs 30,218 | -Rs 16,465 |
| B | 54 | 35.2% | -Rs 23,681 | Rs 6,041 / -Rs 3,956 | -Rs 83,326 | Rs 54,543 | -Rs 12,888 |
| C | 32 | 34.4% | -Rs 58,412 | Rs 11,266 / -Rs 8,683 | -Rs 151,596 | Rs 117,041 | -Rs 45,670 |

**Verdict: NOT RELIABLE.** Failed: era A: 26 trades < 40; era A: net without the top 5 trades -43,378 <= 0; era B: net -23,681 <= 0; era B: net without the top 5 trades -83,326 <= 0; era C: 32 trades < 40; era C: net -58,412 <= 0; era C: net without the top 5 trades -151,596 <= 0; pooled twin p 0.624 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era A: worst month Rs -16,465 < -Rs 13k; era B: max drawdown Rs 54,543 > Rs 40k; era C: max drawdown Rs 117,041 > Rs 40k, worst month Rs -45,670 < -Rs 13k.

## Caveats

- Synthetic eras check the underlying move with a modelled option; the model's error per trade (above) is as big as the edges tested. They are reported apart and never added to real money.
- Only NIFTY could be tested on the long history; BANKNIFTY and the others have no minutes or options before 2021.
- Eras A, B, C were seen at the data level by R190 and earlier hunts; several ideas are relatives of logged rules (PREREG section 3). Nothing was tuned on them; K4's filter was chosen on 2017-18 synthetic only.
- The synthetic clock was amended after the calibration and before any rule result (PREREG section 10); the pre-registered calendar clock gives the same verdicts (report.md).
- Research only. Nothing in the app changes.
