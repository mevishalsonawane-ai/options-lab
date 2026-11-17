# R192 report: longer history and replicated patterns

Pre-registration: `research/hunt/r192_long_history/PREREG.md` (committed before any number; amendment 1 to the synthetic clock committed after the calibration and before any rule result). Code: `r192build.py`, `r192study.py`, `r192lib.py`, `r192cfg.py`; tests `test_r192.py`.

**Outcome: none of the 11 ideas passed the RELIABLE gate.** Nothing here is a "great and reliable" strategy by the rules fixed before looking.

## Calibration of the synthetic price

Choice of w on eras A + B (sum over hold types of the squared mean net error); grid 0.00-1.00:

| w | a | rmse log premium | objective |
|---|---|---|---|
| 0.0 | 0.81 | 0.152 | 400,474 |
| 0.05 | 0.85 | 0.145 | 135,991 |
| 0.1 | 0.87 | 0.141 | 49,215 |
| 0.15 | 0.89 | 0.141 | 21,176 |
| 0.2 | 0.91 | 0.141 | 31,022 |
| 0.25 | 0.93 | 0.143 | 69,164 |
| 0.3 | 0.94 | 0.145 | 123,101 |
| 0.35 | 0.95 | 0.147 | 184,680 |
| 0.4 | 0.96 | 0.149 | 253,229 |
| 0.45 | 0.97 | 0.152 | 324,835 |
| 0.5 | 0.97 | 0.155 | 379,446 |
| 0.55 | 0.98 | 0.157 | 451,715 |
| 0.6 | 0.99 | 0.159 | 528,091 |
| 0.65 | 0.99 | 0.162 | 574,391 |
| 0.7 | 1.0 | 0.164 | 645,059 |
| 0.75 | 1.0 | 0.166 | 689,106 |
| 0.8 | 1.0 | 0.168 | 730,550 |
| 0.85 | 1.01 | 0.170 | 804,799 |
| 0.9 | 1.01 | 0.172 | 843,840 |
| 0.95 | 1.01 | 0.174 | 876,471 |
| 1.0 | 1.02 | 0.176 | 948,000 |

Chosen: w = 0.15, a = 0.89.

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

Synthetic expiry calendar check: on 3 Aug 2020 - 28 Aug 2025 the synthetic Thursday calendar matched 264 of the store's 265 weekly expiries (the miss: 4 Nov 2021, the Diwali Muhurat Thursday; the real expiry was 3 Nov).

## K4's filter (chosen on 2017-04-03 - 2018-12-31, synthetic, before the run)

| filter | trades | net | per trade |
|---|---|---|---|
| F0 | 296 | -Rs 40,982 | -Rs 138 |
| F1 | 225 | -Rs 18,608 | -Rs 83 |
| F2 | 161 | Rs 15,677 | Rs 97 |
| F3 | 213 | -Rs 47,953 | -Rs 225 |
| F4 | 279 | -Rs 40,764 | -Rs 146 |
| F5 | 194 | Rs 23,869 | Rs 123 |

Chosen: **F5** (F0 none, F1 skip weekend / holiday nights, F2 with the 200-day average, F3 skip high VIX, F4 skip 1.5% days, F5 extreme close only).

## Every idea in detail

### K1: Turn of the month: call, last session 15:20 -> 2nd session of the new month 15:20

Timing CC2 (15:20 -> 2 sessions on 15:20); calls 181, puts 0 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 91 | 52.7% | Rs 52,547 | Rs 4,095 / -Rs 3,349 | (n/a) | Rs 16,664 | -Rs 9,225 |
| S (synthetic) | 32 | 37.5% | -Rs 18,496 | Rs 6,045 / -Rs 4,552 | (n/a) | Rs 62,549 | -Rs 11,205 |
| A | 11 | 72.7% | Rs 71,424 | Rs 11,050 / -Rs 5,658 | -Rs 1,383 | Rs 16,974 | -Rs 10,912 |
| B | 28 | 35.7% | -Rs 31,566 | Rs 5,812 / -Rs 4,983 | -Rs 78,593 | Rs 61,076 | -Rs 12,289 |
| C | 19 | 36.8% | -Rs 46,234 | Rs 11,552 / -Rs 10,592 | -Rs 120,149 | Rs 100,532 | -Rs 20,233 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | Rs 71,180 | -Rs 10,912 | 8 / 11 |
| B | -Rs 31,877 | -Rs 12,289 | 10 / 28 |
| C | -Rs 46,607 | -Rs 20,233 | 7 / 19 |

- Pooled real: 58 trades, 43.1% won, -Rs 6,375, avg win Rs 9,096 / avg loss -Rs 7,084, max drawdown Rs 123,225, worst month -Rs 20,233.
- Twins (random day and side, same exit; pool days A / B / C 196 / 568 / 392): mean -Rs 38,788, sd Rs 57,602; empirical p 0.2813, normal-tail p 0.29; BH q 1 (m = 4,771).
- Real exits: time 58.
- Pre-registered calendar clock, synthetic eras: S0 91 trades Rs 33,457; S 32 trades -Rs 33,654. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era S: 32 trades < 40; era S: net -18,496 <= 0; era A: 11 trades < 40; era A: net without the top 5 trades -1,383 <= 0; era B: 28 trades < 40; era B: net -31,566 <= 0; era B: net without the top 5 trades -78,593 <= 0; era C: 19 trades < 40; era C: net -46,234 <= 0; era C: net without the top 5 trades -120,149 <= 0; pooled twin p 0.281 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era B: max drawdown Rs 61,076 > Rs 40k; era C: max drawdown Rs 100,532 > Rs 40k, worst month Rs -20,233 < -Rs 13k.

### K2: Pre-holiday: call, 15:20 the session before -> 15:20 on the pre-holiday session

Timing CC1 (15:20 -> next session 15:20); calls 213, puts 0 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 117 | 51.3% | Rs 46,934 | Rs 2,533 / -Rs 1,843 | (n/a) | Rs 15,948 | -Rs 6,459 |
| S (synthetic) | 36 | 38.9% | -Rs 15,221 | Rs 4,953 / -Rs 3,844 | (n/a) | Rs 55,191 | -Rs 20,938 |
| A | 9 | 44.4% | Rs 6,072 | Rs 8,212 / -Rs 5,355 | -Rs 26,215 | Rs 18,227 | -Rs 7,988 |
| B | 29 | 48.3% | Rs 39,160 | Rs 5,521 / -Rs 2,543 | -Rs 18,138 | Rs 16,791 | -Rs 5,289 |
| C | 22 | 18.2% | -Rs 101,926 | Rs 7,475 / -Rs 7,324 | -Rs 131,555 | Rs 101,926 | -Rs 20,686 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | Rs 5,921 | -Rs 7,988 | 3 / 7 |
| B | Rs 38,822 | -Rs 5,918 | 10 / 21 |
| C | -Rs 102,258 | -Rs 16,140 | 2 / 15 |

- Pooled real: 60 trades, 36.7% won, -Rs 56,694, avg win Rs 6,366 / avg loss -Rs 5,177, max drawdown Rs 118,717, worst month -Rs 20,686.
- Twins (random day and side, same exit; pool days A / B / C 196 / 568 / 393): mean -Rs 24,677, sd Rs 47,159; empirical p 0.7451, normal-tail p 0.75; BH q 1 (m = 4,771).
- Real exits: time 60.
- Pre-registered calendar clock, synthetic eras: S0 117 trades Rs 35,486; S 36 trades -Rs 25,663. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era S: 36 trades < 40; era S: net -15,221 <= 0; era A: 9 trades < 40; era A: net without the top 5 trades -26,215 <= 0; era B: 29 trades < 40; era B: net without the top 5 trades -18,138 <= 0; era C: 22 trades < 40; era C: net -101,926 <= 0; era C: net without the top 5 trades -131,555 <= 0; pooled twin p 0.745 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era C: max drawdown Rs 101,926 > Rs 40k, worst month Rs -20,686 < -Rs 13k.

### K3: Overnight drift: call every night, 15:20 -> 09:16

Timing ON (15:20 -> next 09:16); calls 3987, puts 0 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 2111 | 41.6% | Rs 57,653 | Rs 1,169 / -Rs 787 | (n/a) | Rs 149,768 | -Rs 19,030 |
| S (synthetic) | 719 | 53.5% | Rs 115,218 | Rs 2,064 / -Rs 2,034 | (n/a) | Rs 64,131 | -Rs 38,225 |
| A | 196 | 58.2% | Rs 61,523 | Rs 2,437 / -Rs 2,638 | Rs 11,295 | Rs 20,181 | -Rs 10,351 |
| B | 568 | 51.9% | -Rs 50,634 | Rs 1,593 / -Rs 1,907 | -Rs 87,814 | Rs 70,532 | -Rs 20,986 |
| C | 393 | 40.2% | -Rs 341,348 | Rs 3,195 / -Rs 3,601 | -Rs 420,060 | Rs 382,041 | -Rs 70,918 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | Rs 57,811 | -Rs 10,332 | 7 / 12 |
| B | -Rs 57,717 | -Rs 8,417 | 15 / 35 |
| C | -Rs 349,841 | -Rs 17,463 | 7 / 25 |

- Pooled real: 1157 trades, 49.0% won, -Rs 330,459, avg win Rs 2,209 / avg loss -Rs 2,683, max drawdown Rs 441,848, worst month -Rs 70,918.
- Twins (random day and side, same exit; pool days A / B / C 196 / 568 / 393): mean -Rs 304,823, sd Rs 119,021; empirical p 0.5837, normal-tail p 0.59; BH q 1 (m = 4,771).
- Real exits: time 1157.
- Pre-registered calendar clock, synthetic eras: S0 2111 trades -Rs 489,469; S 719 trades -Rs 283,131. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era B: net -50,634 <= 0; era B: net without the top 5 trades -87,814 <= 0; era C: net -341,348 <= 0; era C: net without the top 5 trades -420,060 <= 0; pooled twin p 0.584 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era B: max drawdown Rs 70,532 > Rs 40k, worst month Rs -20,986 < -Rs 13k; era C: max drawdown Rs 382,041 > Rs 40k, worst month Rs -70,918 < -Rs 13k.

### K4: Night proxy (strong close) + one filter chosen on 2017-18, 15:20 -> 09:16 [filter F5]

Timing ON (15:20 -> next 09:16); calls 396, puts 290 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S (synthetic, 2019 - Jul 2020) | 150 | 39.3% | -Rs 21,214 | Rs 3,437 / -Rs 2,461 | (n/a) | Rs 59,679 | -Rs 29,130 |
| A | 95 | 54.7% | Rs 28,963 | Rs 2,574 / -Rs 2,439 | -Rs 7,163 | Rs 22,400 | -Rs 12,313 |
| B | 277 | 51.6% | Rs 76,487 | Rs 2,112 / -Rs 1,684 | Rs 20,623 | Rs 33,934 | -Rs 13,511 |
| C | 164 | 45.7% | Rs 12,238 | Rs 3,867 / -Rs 3,121 | -Rs 57,501 | Rs 52,381 | -Rs 32,240 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | Rs 27,163 | -Rs 7,253 | 7 / 12 |
| B | Rs 73,009 | -Rs 6,311 | 21 / 35 |
| C | Rs 8,850 | -Rs 17,463 | 15 / 25 |

- Pooled real: 536 trades, 50.4% won, Rs 117,689, avg win Rs 2,689 / avg loss -Rs 2,287, max drawdown Rs 52,381, worst month -Rs 32,240.
- Twins (random day and side, same exit; pool days A / B / C 196 / 568 / 393): mean -Rs 137,644, sd Rs 79,801; empirical p 0.0010, normal-tail p 0.00069; BH q 1 (m = 4,771).
- Real exits: time 536.
- Pre-registered calendar clock, synthetic eras: S 150 trades -Rs 152,360. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era S: net -21,214 <= 0; era A: net without the top 5 trades -7,163 <= 0; era C: net without the top 5 trades -57,501 <= 0; BH q 1 > 0.1.
Account stop (1 lot): era B: worst month Rs -13,511 < -Rs 13k; era C: max drawdown Rs 52,381 > Rs 40k, worst month Rs -32,240 < -Rs 13k.

### K5: Gap fade 0.3-1.0% at 09:20, target the previous close, stop -30%, out 15:20

Timing G5 (K5 intraday (target + stop)); calls 252, puts 625 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S (synthetic) | 292 | 48.6% | -Rs 47,504 | Rs 1,813 / -Rs 2,033 | (n/a) | Rs 60,478 | -Rs 15,615 |
| A | 128 | 46.9% | Rs 28,947 | Rs 3,194 / -Rs 2,393 | -Rs 11,708 | Rs 23,624 | -Rs 7,867 |
| B | 310 | 43.9% | Rs 52,282 | Rs 2,508 / -Rs 1,660 | Rs 15,410 | Rs 30,458 | -Rs 15,282 |
| C | 147 | 40.8% | Rs 3,127 | Rs 4,386 / -Rs 2,989 | -Rs 48,976 | Rs 53,955 | -Rs 20,581 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | Rs 26,846 | -Rs 6,068 | 5 / 12 |
| B | Rs 48,982 | -Rs 3,625 | 23 / 35 |
| C | Rs 582 | -Rs 6,738 | 12 / 25 |

- Pooled real: 585 trades, 43.8% won, Rs 84,356, avg win Rs 3,109 / avg loss -Rs 2,163, max drawdown Rs 53,955, worst month -Rs 20,581.
- Twins (random day and side, same exit; pool days A / B / C 248 / 721 / 499): mean -Rs 22,096, sd Rs 73,313; empirical p 0.0762, normal-tail p 0.073; BH q 1 (m = 4,771).
- Real exits: stop 272, target 222, time 91.
- Pre-registered calendar clock, synthetic eras: S 292 trades -Rs 15,069. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era S: net -47,504 <= 0; era A: net without the top 5 trades -11,708 <= 0; era C: net without the top 5 trades -48,976 <= 0; pooled twin p 0.076 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era B: worst month Rs -15,282 < -Rs 13k; era C: max drawdown Rs 53,955 > Rs 40k, worst month Rs -20,581 < -Rs 13k.

### K6: Gap follow >= 0.5% confirmed at 09:30, stop -30%, out 15:20

Timing G6 (K6 intraday (stop)); calls 157, puts 76 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S (synthetic) | 69 | 36.2% | -Rs 2,469 | Rs 4,711 / -Rs 2,733 | (n/a) | Rs 61,920 | -Rs 10,495 |
| A | 38 | 28.9% | -Rs 19,915 | Rs 3,655 / -Rs 2,227 | -Rs 52,239 | Rs 29,694 | -Rs 7,159 |
| B | 84 | 39.3% | -Rs 27,561 | Rs 2,399 / -Rs 2,093 | -Rs 60,681 | Rs 30,267 | -Rs 12,884 |
| C | 42 | 26.2% | -Rs 40,461 | Rs 6,988 / -Rs 3,785 | -Rs 104,411 | Rs 63,258 | -Rs 19,692 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | -Rs 20,521 | -Rs 5,364 | 4 / 12 |
| B | -Rs 28,586 | -Rs 4,518 | 9 / 26 |
| C | -Rs 41,281 | -Rs 7,149 | 3 / 17 |

- Pooled real: 164 trades, 33.5% won, -Rs 87,936, avg win Rs 3,568 / avg loss -Rs 2,607, max drawdown Rs 87,936, worst month -Rs 19,692.
- Twins (random day and side, same exit; pool days A / B / C 248 / 721 / 499): mean -Rs 25,028, sd Rs 47,515; empirical p 0.9144, normal-tail p 0.91; BH q 1 (m = 4,771).
- Real exits: stop 87, time 77.
- Pre-registered calendar clock, synthetic eras: S 69 trades -Rs 13,718. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era S: net -2,469 <= 0; era A: 38 trades < 40; era A: net -19,915 <= 0; era A: net without the top 5 trades -52,239 <= 0; era B: net -27,561 <= 0; era B: net without the top 5 trades -60,681 <= 0; era C: net -40,461 <= 0; era C: net without the top 5 trades -104,411 <= 0; pooled twin p 0.914 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era C: max drawdown Rs 63,258 > Rs 40k, worst month Rs -19,692 < -Rs 13k.

### K7: India VIX spike (+25% vs 10-day mean on a down day): call 09:20 -> next session 15:20

Timing D2 (09:20 -> next session 15:20); calls 57, puts 0 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 28 | 57.1% | Rs 30,153 | Rs 5,232 / -Rs 4,463 | (n/a) | Rs 18,880 | -Rs 9,542 |
| S (synthetic) | 14 | 28.6% | -Rs 27,294 | Rs 15,479 / -Rs 8,921 | (n/a) | Rs 38,874 | -Rs 23,029 |
| A | 0 | | | | | | |
| B | 8 | 37.5% | -Rs 8,847 | Rs 3,246 / -Rs 3,717 | -Rs 13,781 | Rs 11,347 | -Rs 6,328 |
| C | 7 | 71.4% | Rs 6,660 | Rs 6,074 / -Rs 11,856 | -Rs 23,711 | Rs 17,287 | -Rs 13,289 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | Rs 0 | Rs 0 | 0 / 0 |
| B | -Rs 8,924 | -Rs 6,470 | 2 / 4 |
| C | Rs 6,493 | -Rs 17,287 | 1 / 2 |

- Pooled real: 15 trades, 53.3% won, -Rs 2,187, avg win Rs 5,014 / avg loss -Rs 6,042, max drawdown Rs 28,634, worst month -Rs 13,289.
- Twins (random day and side, same exit; pool days A / B / C 248 / 721 / 498): mean -Rs 6,961, sd Rs 27,978; empirical p 0.4175, normal-tail p 0.43; BH q 1 (m = 4,771).
- Real exits: time 15.
- Pre-registered calendar clock, synthetic eras: S0 28 trades Rs 31,966; S 14 trades -Rs 20,308. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era S0: 28 trades < 40; era S: 14 trades < 40; era S: net -27,294 <= 0; era A: no trades; era B: 8 trades < 40; era B: net -8,847 <= 0; era B: net without the top 5 trades -13,781 <= 0; era C: 7 trades < 40; era C: net without the top 5 trades -23,711 <= 0; pooled twin p 0.418 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era C: worst month Rs -13,289 < -Rs 13k.

### K8: 52-week closing high / low: call / put 15:20 -> next session 15:20

Timing CC1 (15:20 -> next session 15:20); calls 345, puts 42 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 184 | 42.9% | Rs 3,872 | Rs 2,644 / -Rs 1,953 | (n/a) | Rs 45,826 | -Rs 11,162 |
| S (synthetic) | 83 | 50.6% | Rs 71,058 | Rs 3,961 / -Rs 2,324 | (n/a) | Rs 30,768 | -Rs 7,919 |
| A | 43 | 48.8% | Rs 25,781 | Rs 4,325 / -Rs 2,957 | -Rs 10,961 | Rs 19,246 | -Rs 4,072 |
| B | 68 | 44.1% | Rs 18,477 | Rs 2,855 / -Rs 1,768 | -Rs 20,165 | Rs 23,938 | -Rs 4,880 |
| C | 9 | 11.1% | -Rs 45,472 | Rs 2,809 / -Rs 6,035 | -Rs 35,087 | Rs 48,282 | -Rs 25,118 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | Rs 25,114 | -Rs 8,387 | 4 / 7 |
| B | Rs 17,863 | -Rs 5,170 | 6 / 14 |
| C | -Rs 45,620 | -Rs 9,876 | 0 / 4 |

- Pooled real: 120 trades, 43.3% won, -Rs 1,215, avg win Rs 3,448 / avg loss -Rs 2,654, max drawdown Rs 61,756, worst month -Rs 25,118.
- Twins (random day and side, same exit; pool days A / B / C 196 / 568 / 393): mean -Rs 37,850, sd Rs 59,978; empirical p 0.2645, normal-tail p 0.27; BH q 1 (m = 4,771).
- Real exits: time 120.
- Pre-registered calendar clock, synthetic eras: S0 184 trades -Rs 17,821; S 83 trades Rs 61,793. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era A: net without the top 5 trades -10,961 <= 0; era B: net without the top 5 trades -20,165 <= 0; era C: 9 trades < 40; era C: net -45,472 <= 0; era C: net without the top 5 trades -35,087 <= 0; pooled twin p 0.265 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era C: max drawdown Rs 48,282 > Rs 40k, worst month Rs -25,118 < -Rs 13k.

### K9: 200-day trend with a low-volatility filter: call / put 15:20 -> 09:16

Timing ON (15:20 -> next 09:16); calls 1731, puts 375 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 1203 | 37.1% | -Rs 53,649 | Rs 1,042 / -Rs 685 | (n/a) | Rs 119,573 | -Rs 16,973 |
| S (synthetic) | 318 | 54.7% | Rs 37,062 | Rs 1,344 / -Rs 1,366 | (n/a) | Rs 22,295 | -Rs 13,343 |
| A | 122 | 56.6% | Rs 24,148 | Rs 1,984 / -Rs 2,128 | -Rs 6,377 | Rs 18,367 | -Rs 10,459 |
| B | 286 | 47.9% | -Rs 54,984 | Rs 1,496 / -Rs 1,745 | -Rs 82,722 | Rs 69,621 | -Rs 12,687 |
| C | 177 | 44.1% | -Rs 55,478 | Rs 2,715 / -Rs 2,699 | -Rs 102,612 | Rs 92,723 | -Rs 24,950 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | Rs 21,952 | -Rs 7,024 | 6 / 10 |
| B | -Rs 58,550 | -Rs 8,417 | 9 / 24 |
| C | -Rs 59,155 | -Rs 6,596 | 6 / 18 |

- Pooled real: 585 trades, 48.5% won, -Rs 86,314, avg win Rs 1,950 / avg loss -Rs 2,126, max drawdown Rs 143,722, worst month -Rs 24,950.
- Twins (random day and side, same exit; pool days A / B / C 196 / 568 / 393): mean -Rs 151,211, sd Rs 83,932; empirical p 0.2196, normal-tail p 0.22; BH q 1 (m = 4,771).
- Real exits: time 585.
- Pre-registered calendar clock, synthetic eras: S0 1203 trades -Rs 331,863; S 318 trades -Rs 93,109. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era S0: net -53,649 <= 0; era A: net without the top 5 trades -6,377 <= 0; era B: net -54,984 <= 0; era B: net without the top 5 trades -82,722 <= 0; era C: net -55,478 <= 0; era C: net without the top 5 trades -102,612 <= 0; pooled twin p 0.220 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era B: max drawdown Rs 69,621 > Rs 40k; era C: max drawdown Rs 92,723 > Rs 40k, worst month Rs -24,950 < -Rs 13k.

### K10: Reversal after a 1.5% day: opposite side 15:20 -> next session 15:20

Timing CC1 (15:20 -> next session 15:20); calls 299, puts 321 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 441 | 37.4% | -Rs 194,353 | Rs 2,865 / -Rs 2,417 | (n/a) | Rs 232,038 | -Rs 22,667 |
| S (synthetic) | 67 | 43.3% | Rs 118,003 | Rs 10,835 / -Rs 5,164 | (n/a) | Rs 35,166 | -Rs 5,771 |
| A | 26 | 34.6% | -Rs 47,816 | Rs 5,228 / -Rs 5,580 | -Rs 87,465 | Rs 56,644 | -Rs 18,442 |
| B | 54 | 46.3% | -Rs 2,870 | Rs 4,910 / -Rs 4,331 | -Rs 58,279 | Rs 34,132 | -Rs 21,446 |
| C | 32 | 43.8% | -Rs 24,208 | Rs 7,388 / -Rs 7,091 | -Rs 100,717 | Rs 57,957 | -Rs 27,173 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | -Rs 48,268 | -Rs 10,466 | 2 / 9 |
| B | -Rs 3,543 | -Rs 8,235 | 9 / 22 |
| C | -Rs 24,911 | -Rs 18,008 | 6 / 16 |

- Pooled real: 112 trades, 42.9% won, -Rs 74,894, avg win Rs 5,692 / avg loss -Rs 5,439, max drawdown Rs 83,721, worst month -Rs 27,173.
- Twins (random day and side, same exit; pool days A / B / C 196 / 568 / 393): mean -Rs 42,991, sd Rs 63,002; empirical p 0.6921, normal-tail p 0.69; BH q 1 (m = 4,771).
- Real exits: time 112.
- Pre-registered calendar clock, synthetic eras: S0 441 trades -Rs 246,752; S 67 trades Rs 90,552. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era S0: net -194,353 <= 0; era A: 26 trades < 40; era A: net -47,816 <= 0; era A: net without the top 5 trades -87,465 <= 0; era B: net -2,870 <= 0; era B: net without the top 5 trades -58,279 <= 0; era C: 32 trades < 40; era C: net -24,208 <= 0; era C: net without the top 5 trades -100,717 <= 0; pooled twin p 0.692 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era A: max drawdown Rs 56,644 > Rs 40k, worst month Rs -18,442 < -Rs 13k; era B: worst month Rs -21,446 < -Rs 13k; era C: max drawdown Rs 57,957 > Rs 40k, worst month Rs -27,173 < -Rs 13k.

### K11: Continuation after a 1.5% day: same side 15:20 -> next session 15:20

Timing CC1 (15:20 -> next session 15:20); calls 321, puts 299 (all eras).

| era | trades | win % | net | avg win / avg loss | net without top 5 | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S0 (synthetic) | 441 | 47.8% | Rs 89,823 | Rs 2,986 / -Rs 2,349 | (n/a) | Rs 43,760 | -Rs 25,505 |
| S (synthetic) | 67 | 40.3% | Rs 3,808 | Rs 8,566 / -Rs 5,687 | (n/a) | Rs 56,892 | -Rs 15,615 |
| A | 26 | 42.3% | Rs 16,177 | Rs 8,198 / -Rs 4,934 | -Rs 43,378 | Rs 30,218 | -Rs 16,465 |
| B | 54 | 35.2% | -Rs 23,681 | Rs 6,041 / -Rs 3,956 | -Rs 83,326 | Rs 54,543 | -Rs 12,888 |
| C | 32 | 34.4% | -Rs 58,412 | Rs 11,266 / -Rs 8,683 | -Rs 151,596 | Rs 117,041 | -Rs 45,670 |

| real era | net at 2x slippage | worst day | months up / months |
|---|---|---|---|
| A | Rs 15,708 | -Rs 11,105 | 6 / 9 |
| B | -Rs 24,331 | -Rs 9,223 | 8 / 22 |
| C | -Rs 59,057 | -Rs 17,803 | 6 / 16 |

- Pooled real: 112 trades, 36.6% won, -Rs 65,915, avg win Rs 8,021 / avg loss -Rs 5,561, max drawdown Rs 163,234, worst month -Rs 45,670.
- Twins (random day and side, same exit; pool days A / B / C 196 / 568 / 393): mean -Rs 44,462, sd Rs 62,934; empirical p 0.6243, normal-tail p 0.63; BH q 1 (m = 4,771).
- Real exits: time 112.
- Pre-registered calendar clock, synthetic eras: S0 441 trades Rs 32,946; S 67 trades -Rs 23,163. Same verdict.

**Verdict: NOT RELIABLE.** Failed: era A: 26 trades < 40; era A: net without the top 5 trades -43,378 <= 0; era B: net -23,681 <= 0; era B: net without the top 5 trades -83,326 <= 0; era C: 32 trades < 40; era C: net -58,412 <= 0; era C: net without the top 5 trades -151,596 <= 0; pooled twin p 0.624 >= 0.05; BH q 1 > 0.1.
Account stop (1 lot): era A: worst month Rs -16,465 < -Rs 13k; era B: max drawdown Rs 54,543 > Rs 40k; era C: max drawdown Rs 117,041 > Rs 40k, worst month Rs -45,670 < -Rs 13k.

## Multiple testing

BH over m = 4,771: the 4,755 entry files at the branch base count as p = 1, plus R192's 16 tests (K1-K11, K4's six menu members counted separately; the five unchosen members count as p = 1). The smallest normal-tail p would need to be below about 2e-5 to reach q <= 0.10.
