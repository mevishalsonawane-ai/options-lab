# R163: a stronger trade picker for the Donchian arms (R72, R51)

## For Boss, in short

- **No new picker passed. Keep R151's pickers as they are.** Gradient trees, a small neural net and a calibrated ensemble, fed many more inputs (option-chain OI / PCR / IV, VIX, breadth, 15-60 minute trend, S/R, expiry), did not raise the win rate 5 points above R151's picker without losing money, neither in the walk-forward test (Apr 2025 - Mar 2026) nor in Apr - Oct 2026. Nothing in the app changes.
- **R72, Apr - Oct 2026, 1 lot, the app's rule:** plain arm 1,202 trades, 45.8% won, Rs 1.71L. R151's picker 603 trades, 49.1% won, Rs 1.70L. Best new model by win rate (G1): 841 trades, 48.9% won, Rs 2.04L (-0.2 points vs R151; the bar was +5).
- **R51, Apr - Oct 2026, 1 lot, the app's rule:** plain arm 1,670 trades, 44.9% won, Rs 1.35L. R151's picker 653 trades, 50.4% won, Rs 1.43L. Best new model by win rate (G2): 443 trades, 50.3% won, Rs 0.88L (-0.0 points vs R151; the bar was +5).
- **Why:** at the moment of a break, whether it will win is close to a coin toss for every model (AUC 0.48 - 0.57 on Apr - Oct 2026; 0.50 = no skill). When the trees say "60%+", about half of those trades won (calibration table).
- **Worth knowing about the live picker:** R151 measured its pickers with skipped breaks FREEING their side (R72 652 trades, Rs 2.19L). The app keeps the side busy, as if traded. In that walk R72's picker made Rs 1.70L, about the same as the plain arm (Rs 1.71L); its gains are a higher win rate (49.1% vs 45.8%) and half the drawdown (Rs 33,690 vs Rs 63,365), not more money. R51's picker: Rs 1.43L vs Rs 1.35L plain.
- **At 2 and 3 lots** the plain arms make more in total than the pickers (R72 at 3 lots: plain Rs 6.28L, R151's picker Rs 5.66L), because charges weigh less per lot while the picker takes about half the trades. The picker still halves the drawdown.

## What was tested

- Pre-registered in `PREREG.md` (committed before any number). Signal source: the index (each arm's own Donchian break); option-chain data only as context; no option-chart input.
- Four new pickers per arm (G1, G2, N1, E1) against R151's live logistic picker (P151, thresholds 0.4656 / 0.4469).
- **The app's walk** everywhere: a skipped break still holds its side and counts in the day caps, so a picker only removes trades from the plain arm's list.
- Walk-forward: 4 quarters Apr 2025 - Mar 2026, each scored by models trained only on earlier trades (from 20 Nov 2024). Thresholds chosen there only. FINAL Apr - 13 Oct 2026: models trained on all trades to 31 Mar 2026, frozen, read once. FINAL has been read by many earlier studies of these arms (R151 included): a second look. The lake ends 19 Oct 2026, so the post window (14 - 19 Oct, 4 sessions) is too short for any check.
- Costs: dated Zerodha charges and R17's slippage on every trade; 2 and 3 lots from each trade's own fills.
- R123's network score was not used (its predictions were never stored; rebuilding it was out of reach). Its tabular inputs went in directly.

## R72

### Walk-forward (Apr 2025 - Mar 2026, out-of-fold, app walk, 3,071 design trades)

| | trades | win | net (1 lot) | per trade | net 2 lots | net 3 lots | worst month | drawdown |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| plain arm | 2,238 | 49.0% | Rs 3.36L | Rs 150 | Rs 7.77L | Rs 12.19L | -Rs 72,605 | Rs 86,529 |
| P151 (thr 0.4656) | 578 | 52.4% | Rs 1.57L | Rs 272 | Rs 3.42L | Rs 5.26L | -Rs 26,492 | Rs 51,564 |
| G1 (keep 55%, thr 0.3641) | 1,231 | 49.6% | Rs 2.60L | Rs 211 | Rs 5.77L | Rs 8.95L | -Rs 40,555 | Rs 50,636 |
| G2 (keep 50%, thr -271.2) | 1,119 | 50.0% | Rs 2.42L | Rs 216 | Rs 5.37L | Rs 8.32L | -Rs 40,654 | Rs 50,547 |
| N1 (keep 60%, thr 0.4203) | 1,343 | 50.2% | Rs 2.78L | Rs 207 | Rs 6.20L | Rs 9.62L | -Rs 52,128 | Rs 77,674 |
| E1 (keep 90%, thr 0.4434) | 2,014 | 48.7% | Rs 3.01L | Rs 150 | Rs 6.98L | Rs 10.94L | -Rs 72,605 | Rs 90,186 |

### FINAL (1 Apr - 13 Oct 2026, read once, app walk)

| | trades | win | net (1 lot) | per trade | net 2 lots | net 3 lots | worst month | drawdown | exact twin p | q (m all) | pass |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| plain arm | 1,202 | 45.8% | Rs 1.71L | Rs 143 | Rs 3.99L | Rs 6.28L | -Rs 40,908 | Rs 63,365 | - | - | - |
| P151 (live picker) | 603 | 49.1% | Rs 1.70L | Rs 281 | Rs 3.68L | Rs 5.66L | -Rs 20,632 | Rs 33,690 | 0.0136 | comparator | - |
| G1 | 841 | 48.9% | Rs 2.04L | Rs 242 | Rs 4.47L | Rs 6.90L | -Rs 23,894 | Rs 43,077 | 0.000776 | 1 | no |
| G2 | 776 | 48.8% | Rs 1.93L | Rs 248 | Rs 4.22L | Rs 6.51L | -Rs 23,966 | Rs 40,552 | 0.00287 | 1 | no |
| N1 | 844 | 46.4% | Rs 1.50L | Rs 178 | Rs 3.41L | Rs 5.31L | -Rs 24,732 | Rs 46,821 | 0.28 | 1 | no |
| E1 | 1,190 | 46.1% | Rs 1.87L | Rs 157 | Rs 4.30L | Rs 6.73L | -Rs 40,908 | Rs 63,365 | 0.0365 | 1 | no |

- **The app's rule vs R151's research walk:** R151's picker in the research walk (skipped breaks free their side) gives 652 trades, 48.9% won, Rs 2.19L; in the app's walk 603 trades, 49.1% won, Rs 1.70L.
- P151 refit check: largest coefficient difference vs the exported model 2.5e-15.

Why each failed:

- G1: WF: win not >= P151 + 5 pts or net below P151; FINAL win 0.489 < P151 0.491 + 0.05; BH q 1 > 0.10 (m = 2172)
- G2: WF: win not >= P151 + 5 pts or net below P151; FINAL win 0.488 < P151 0.491 + 0.05; BH q 1 > 0.10 (m = 2172)
- N1: WF: win not >= P151 + 5 pts or net below P151; FINAL win 0.464 < P151 0.491 + 0.05; FINAL net 150435 < P151 169674; BH q 1 > 0.10 (m = 2172)
- E1: WF: win not >= P151 + 5 pts or net below P151; FINAL win 0.461 < P151 0.491 + 0.05; BH q 1 > 0.10 (m = 2172)

### Calibration: predicted P(win) vs what happened

| model | period | 0.00-0.40 | 0.40-0.45 | 0.45-0.50 | 0.50-0.55 | 0.55-0.60 | 0.60-1.00 | AUC | Brier |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| P151 | WF | 48% of 1109 | 47% of 435 | 51% of 322 | 52% of 204 | 58% of 86 | 44% of 82 | 0.515 | 0.2696 |
| P151 | FINAL | 42% of 200 | 43% of 300 | 48% of 336 | 47% of 218 | 50% of 101 | 55% of 47 | 0.540 | 0.2488 |
| G1 | WF | 49% of 1158 | 49% of 221 | 47% of 190 | 48% of 150 | 54% of 154 | 47% of 365 | 0.499 | 0.2933 |
| G1 | FINAL | 40% of 458 | 45% of 120 | 45% of 129 | 48% of 125 | 55% of 102 | 53% of 268 | 0.566 | 0.2554 |
| N1 | WF | 47% of 664 | 50% of 658 | 49% of 549 | 51% of 259 | 41% of 70 | 61% of 38 | 0.510 | 0.2579 |
| N1 | FINAL | 42% of 264 | 44% of 277 | 47% of 329 | 49% of 239 | 45% of 76 | 76% of 17 | 0.534 | 0.2488 |
| E1 | WF | 67% of 9 | 51% of 324 | 50% of 1258 | 46% of 514 | 52% of 130 | 67% of 3 | 0.483 | 0.2518 |
| E1 | FINAL | - | 43% of 60 | 46% of 1142 | - | - | - | 0.477 | 0.2485 |

### What the trees look at most (G1, permutation importance on walk-forward, AUC drop)

1. `break_size` (+0.0182)
2. `ret15` (+0.0074)
3. `b_open_move` (+0.0055)
4. `ema_dist` (+0.0052)
5. `b_ret15` (+0.0051)
6. `dist_extreme` (+0.0040)
7. `ema15_slope` (+0.0030)
8. `ema_slope` (+0.0021)
9. `from_open` (+0.0020)
10. `prev_ret` (+0.0019)
11. `k_prem_pct` (+0.0017)
12. `f_dow` (+0.0016)
13. `since_prev` (+0.0014)
14. `breadth5` (+0.0013)
15. `sr_touch_ahead` (+0.0010)

Frozen G1 by gain: `b_open_move`, `b_ret15`, `f_vixchg`, `b_disp`, `vix`, `vix_chg30`, `dvix`, `break_size`, `vix_pct250`, `since_prev`

## R51

### Walk-forward (Apr 2025 - Mar 2026, out-of-fold, app walk, 4,208 design trades)

| | trades | win | net (1 lot) | per trade | net 2 lots | net 3 lots | worst month | drawdown |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| plain arm | 3,044 | 45.9% | Rs 2.14L | Rs 70 | Rs 5.71L | Rs 9.29L | -Rs 53,654 | Rs 102,882 |
| P151 (thr 0.4469) | 720 | 49.4% | Rs 2.03L | Rs 282 | Rs 4.39L | Rs 6.76L | -Rs 23,762 | Rs 37,246 |
| G1 (keep 65%, thr 0.3288) | 1,978 | 48.2% | Rs 2.50L | Rs 127 | Rs 5.94L | Rs 9.38L | -Rs 54,331 | Rs 78,327 |
| G2: no threshold reached P151's net | 0 | - | - | - | - | - | - | - |
| N1 (keep 75%, thr 0.3521) | 2,283 | 46.7% | Rs 2.53L | Rs 111 | Rs 6.13L | Rs 9.74L | -Rs 43,391 | Rs 117,108 |
| E1: no threshold reached P151's net | 0 | - | - | - | - | - | - | - |

### FINAL (1 Apr - 13 Oct 2026, read once, app walk)

| | trades | win | net (1 lot) | per trade | net 2 lots | net 3 lots | worst month | drawdown | exact twin p | q (m all) | pass |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| plain arm | 1,670 | 44.9% | Rs 1.35L | Rs 81 | Rs 3.48L | Rs 5.62L | -Rs 54,587 | Rs 92,097 | - | - | - |
| P151 (live picker) | 653 | 50.4% | Rs 1.43L | Rs 219 | Rs 3.17L | Rs 4.90L | -Rs 22,978 | Rs 42,743 | 0.000165 | comparator | - |
| G1 | 1,227 | 46.0% | Rs 1.02L | Rs 83 | Rs 2.63L | Rs 4.23L | -Rs 13,623 | Rs 57,586 | 0.0567 | 1 | no |
| G2 (WF failed; thr = best WF win, report only) | 443 | 50.3% | Rs 0.88L | Rs 198 | Rs 1.97L | Rs 3.05L | -Rs 12,323 | Rs 34,872 | 0.00402 | 1 | no |
| N1 | 1,536 | 45.8% | Rs 1.66L | Rs 108 | Rs 4.04L | Rs 6.43L | -Rs 29,889 | Rs 69,094 | 0.0065 | 1 | no |
| E1 (WF failed; thr = best WF win, report only) | 2 | 100.0% | Rs 0.01L | Rs 685 | Rs 0.03L | Rs 0.04L | Rs 662 | Rs 0 | 0.201 | 1 | no |

- **The app's rule vs R151's research walk:** R151's picker in the research walk (skipped breaks free their side) gives 693 trades, 49.9% won, Rs 1.59L; in the app's walk 653 trades, 50.4% won, Rs 1.43L.
- P151 refit check: largest coefficient difference vs the exported model 9.3e-15.

Why each failed:

- G1: WF: win not >= P151 + 5 pts or net below P151; FINAL win 0.460 < P151 0.504 + 0.05; FINAL net 102402 < P151 142884; BH q 1 > 0.10 (m = 2172)
- G2: WF: no threshold reached P151's net; FINAL win 0.503 < P151 0.504 + 0.05; FINAL net 87828 < P151 142884; not positive in every period (WF quarters + FINAL); BH q 1 > 0.10 (m = 2172)
- N1: WF: win not >= P151 + 5 pts or net below P151; FINAL win 0.458 < P151 0.504 + 0.05; BH q 1 > 0.10 (m = 2172)
- E1: WF: no threshold reached P151's net; FINAL net 1369 < P151 142884; < 40 FINAL trades; not positive in every period (WF quarters + FINAL); BH q 1 > 0.10 (m = 2172)

### Calibration: predicted P(win) vs what happened

| model | period | 0.00-0.40 | 0.40-0.45 | 0.45-0.50 | 0.50-0.55 | 0.55-0.60 | 0.60-1.00 | AUC | Brier |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| P151 | WF | 44% of 1670 | 47% of 692 | 51% of 426 | 51% of 172 | 40% of 53 | 48% of 31 | 0.526 | 0.2566 |
| P151 | FINAL | 41% of 507 | 42% of 540 | 52% of 397 | 45% of 150 | 55% of 53 | 48% of 23 | 0.546 | 0.2469 |
| G1 | WF | 45% of 1616 | 42% of 356 | 48% of 318 | 48% of 254 | 50% of 184 | 51% of 316 | 0.538 | 0.2654 |
| G1 | FINAL | 43% of 779 | 43% of 255 | 41% of 199 | 46% of 119 | 54% of 115 | 53% of 203 | 0.543 | 0.2553 |
| N1 | WF | 46% of 1528 | 46% of 896 | 47% of 445 | 45% of 142 | 53% of 30 | 67% of 3 | 0.511 | 0.2553 |
| N1 | FINAL | 41% of 460 | 47% of 569 | 43% of 410 | 50% of 171 | 45% of 49 | 64% of 11 | 0.524 | 0.2479 |
| E1 | WF | 47% of 552 | 43% of 956 | 48% of 1373 | 44% of 160 | 67% of 3 | - | 0.510 | 0.2500 |
| E1 | FINAL | - | 44% of 1360 | 48% of 310 | - | - | - | 0.530 | 0.2470 |

### What the trees look at most (G1, permutation importance on walk-forward, AUC drop)

1. `ret15` (+0.0107)
2. `b_ret15` (+0.0082)
3. `break_size` (+0.0069)
4. `bar_size` (+0.0038)
5. `tod` (+0.0038)
6. `vix_pct250` (+0.0038)
7. `ema_dist` (+0.0036)
8. `atm_iv` (+0.0035)
9. `from_open` (+0.0035)
10. `vix_chg30` (+0.0033)
11. `ret30` (+0.0025)
12. `sma5d` (+0.0024)
13. `range_ratio` (+0.0023)
14. `piv_ahead` (+0.0019)
15. `f_gap` (+0.0019)

Frozen G1 by gain: `b_ret15`, `b_open_move`, `vix_chg30`, `vix_pct250`, `atm_iv`, `b_disp`, `ret60`, `f_vixchg`, `break_size`, `rv30_rel`

## Multiple testing

- Raw p-values (exact hypergeometric random-skip twin on FINAL win rate) for the joint BH with R161 / R162: `research/hunt/r163_donchian_picker/results/pvalues.csv`.
- q counted two ways: within R163 (m = 8) and with every earlier test at p = 1 (m = 2164 + 8).

| test | trades | win | net | raw p | q (R163) | q (all) | pass |
|---|---:|---:|---:|---:|---:|---:|---|
| r163-r72-g1 | 841 | 48.9% | Rs 2.04L | 0.000776 | 0.00621 | 1 | False |
| r163-r72-g2 | 776 | 48.8% | Rs 1.93L | 0.00287 | 0.0107 | 1 | False |
| r163-r72-n1 | 844 | 46.4% | Rs 1.50L | 0.28 | 0.28 | 1 | False |
| r163-r72-e1 | 1190 | 46.1% | Rs 1.87L | 0.0365 | 0.0584 | 1 | False |
| r163-r51-g1 | 1227 | 46.0% | Rs 1.02L | 0.0567 | 0.0757 | 1 | False |
| r163-r51-g2 | 443 | 50.3% | Rs 0.88L | 0.00402 | 0.0107 | 1 | False |
| r163-r51-n1 | 1536 | 45.8% | Rs 1.66L | 0.0065 | 0.013 | 1 | False |
| r163-r51-e1 | 2 | 100.0% | Rs 0.01L | 0.201 | 0.23 | 1 | False |

## Glossary

- **win**: share of trades with net > 0 after charges and slippage.
- **app walk**: a skipped break still holds its side and day caps, as the app does.
- **exact twin p**: the chance that keeping the same number of the arm's trades at random wins at least as often.
