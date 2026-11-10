# R157: the scalp network, enhanced for win rate and profit

## For Boss (short)

- **Verdict: the enhanced network still cannot make 10-15 point scalping profitable.** 0 of 114 trading rules passed the gates.
- **What was added to R156's network:** (1) the other four indices' last hour of bars, (2) results at 3 lots as well as 1, (3) four 'let the winner run' exits (stop 10 or 15 points, then a trailing stop once the trade is that far up), (4) a pickier rule: only the top 1% of forecasts.
- **Telling winners from losers (0.5 = coin):** option-point exits 0.551 now vs 0.549 in R156 (Apr - Oct 2026); index-point exits 0.568 vs 0.574. Scrambling the other indices' bars costs the forecast 8% of what all inputs together carry.
- **Highest win rate reached (final period, 40+ trades):** TOP99-A10s15t30@3: 278 trades, win 64% (needed 63%), Rs 11,199 (Rs 40 a trade).
- **Top 1% of forecasts only (TOP99, all 28 exits, 1 lot):** O1: 30,674 trades, win 53%, -Rs 52 a lot a trade; P2: 15,202 trades, win 51%, -Rs 109 a lot a trade; P3 final: 8,897 trades, win 54%, -Rs 34 a lot a trade.
- **The network picks everything (BEST):** 1 lot: 4,163 trades, win 51%, -Rs 74 a lot a trade / 782 trades, win 56%, -Rs 195 a lot a trade / 1,478 trades, win 55%, -Rs 129 a lot a trade (O1 / P2 / P3); 3 lots: 12,364 trades, win 56%, -Rs 57 a lot a trade / 6,014 trades, win 56%, -Rs 136 a lot a trade / 3,244 trades, win 55%, -Rs 176 a lot a trade. R156's BEST: -Rs 59 / -Rs 100 / -Rs 142 a trade.
- **3 lots:** an average option-point scalp loses -Rs 144 a lot at 3 lots against -Rs 175 at 1 lot (final period): cheaper per lot, still a loss.
- **'Let the winner run' exits:** an average one wins 36% where 46% is needed (-Rs 155 a trade, final period); the network's own picks on them: O1: 30 trades, win 50%, Rs 57 a lot a trade; P2: 0 trades; P3 final: 0 trades.
- **Best result per lot in the final period (40+ trades):** TOP99-R15t60@3: 283 trades, win 50% (needed 43%), Rs 1,43,449 (Rs 507 a trade); earlier periods Rs 77,203 and -Rs 1,95,261.
- 34 of 114 rules made money in the final period, 0 in all three periods; 0 beat random entries after the multiple-testing correction (smallest q 0.709).
- Nothing changes in the app. Every rule is logged in `research/experiments/entries/r157-*.yaml`.

Signal source: the index and the other four indices (1-minute bars); option-chain OI and India VIX as context; the 1-ITM option is bought. **The final period (Apr - 13 Oct 2026) has now been read four times for scalps (R154 - R157): weak evidence either way.**

## Did the enhancements help? R157 against R156 (R156's 24 exits, 1 lot)

| period | AUC option points: R157 / R156 | AUC index points: R157 / R156 | top tenth, option points: win / needed / a trade, R157 | the same, R156 |
|---|---|---|---|---|
| O1 (2023 - Mar 2025) | 0.535 / 0.539 | 0.546 / 0.549 | 57% / 69% / -Rs 75 | 57% / 69% / -Rs 75 |
| P2 (Apr 2025 - Mar 2026) | 0.525 / 0.529 | 0.544 / 0.546 | 56% / 68% / -Rs 125 | 57% / 68% / -Rs 114 |
| P3 final (Apr - 13 Oct 2026) | 0.551 / 0.549 | 0.568 / 0.574 | 58% / 69% / -Rs 112 | 58% / 69% / -Rs 113 |

## Every kind of exit: the average moment, the network's top tenth and its top 1%

| period | exits | lots | average: win / needed / a trade | top tenth | top 1% |
|---|---|---|---|---|---|
| O1 (2023 - Mar 2025) | fixed, option points | 1 | 47% / 59% / -Rs 94 | 57% / 69% / -Rs 75 | 59% / 69% / -Rs 67 |
| O1 (2023 - Mar 2025) | fixed, index points | 1 | 42% / 54% / -Rs 74 | 52% / 60% / -Rs 63 | 55% / 61% / -Rs 53 |
| O1 (2023 - Mar 2025) | run exits | 1 | 37% / 46% / -Rs 86 | 42% / 48% / -Rs 62 | 45% / 49% / -Rs 50 |
| O1 (2023 - Mar 2025) | fixed, option points | 3 | 48% / 56% / -Rs 189 | 57% / 64% / -Rs 127 | 59% / 64% / -Rs 99 |
| O1 (2023 - Mar 2025) | fixed, index points | 3 | 47% / 54% / -Rs 130 | 54% / 58% / -Rs 86 | 57% / 59% / -Rs 58 |
| O1 (2023 - Mar 2025) | run exits | 3 | 40% / 46% / -Rs 165 | 45% / 48% / -Rs 89 | 48% / 48% / -Rs 31 |
| P2 (Apr 2025 - Mar 2026) | fixed, option points | 1 | 48% / 59% / -Rs 148 | 56% / 68% / -Rs 125 | 57% / 69% / -Rs 143 |
| P2 (Apr 2025 - Mar 2026) | fixed, index points | 1 | 42% / 54% / -Rs 113 | 50% / 58% / -Rs 111 | 54% / 59% / -Rs 90 |
| P2 (Apr 2025 - Mar 2026) | run exits | 1 | 38% / 47% / -Rs 145 | 43% / 48% / -Rs 116 | 45% / 49% / -Rs 110 |
| P2 (Apr 2025 - Mar 2026) | fixed, option points | 3 | 49% / 57% / -Rs 353 | 56% / 65% / -Rs 270 | 58% / 66% / -Rs 278 |
| P2 (Apr 2025 - Mar 2026) | fixed, index points | 3 | 45% / 53% / -Rs 247 | 51% / 57% / -Rs 227 | 54% / 58% / -Rs 160 |
| P2 (Apr 2025 - Mar 2026) | run exits | 3 | 40% / 47% / -Rs 344 | 44% / 48% / -Rs 228 | 45% / 49% / -Rs 218 |
| P3 final (Apr - 13 Oct 2026) | fixed, option points | 1 | 47% / 61% / -Rs 175 | 58% / 69% / -Rs 112 | 60% / 69% / -Rs 86 |
| P3 final (Apr - 13 Oct 2026) | fixed, index points | 1 | 39% / 54% / -Rs 131 | 52% / 60% / -Rs 104 | 56% / 60% / -Rs 70 |
| P3 final (Apr - 13 Oct 2026) | run exits | 1 | 36% / 46% / -Rs 155 | 44% / 47% / -Rs 83 | 46% / 48% / -Rs 28 |
| P3 final (Apr - 13 Oct 2026) | fixed, option points | 3 | 47% / 59% / -Rs 431 | 58% / 65% / -Rs 228 | 60% / 65% / -Rs 140 |
| P3 final (Apr - 13 Oct 2026) | fixed, index points | 3 | 42% / 53% / -Rs 301 | 53% / 58% / -Rs 187 | 57% / 59% / -Rs 60 |
| P3 final (Apr - 13 Oct 2026) | run exits | 3 | 38% / 47% / -Rs 374 | 45% / 48% / -Rs 156 | 49% / 47% / Rs 154 |

Rupees are for the whole position (divide the 3-lot rows by 3 for a lot).

## Rule families pooled (5 indices)

| family | lots | O1 | P2 | P3 final |
|---|---|---|---|---|
| EV (forecast net > 0), 28 exits | 1 | 13,138 trades, win 50%, -Rs 51 a lot a trade | 2,804 trades, win 54%, -Rs 233 a lot a trade | 5,254 trades, win 55%, -Rs 109 a lot a trade |
| TOP99 (top 1% win forecast), 28 exits | 1 | 30,674 trades, win 53%, -Rs 52 a lot a trade | 15,202 trades, win 51%, -Rs 109 a lot a trade | 8,897 trades, win 54%, -Rs 34 a lot a trade |
| BEST | 1 | 4,163 trades, win 51%, -Rs 74 a lot a trade | 782 trades, win 56%, -Rs 195 a lot a trade | 1,478 trades, win 55%, -Rs 129 a lot a trade |
| EV (forecast net > 0), 28 exits | 3 | 180,378 trades, win 52%, -Rs 33 a lot a trade | 54,201 trades, win 52%, -Rs 107 a lot a trade | 34,063 trades, win 51%, -Rs 109 a lot a trade |
| TOP99 (top 1% win forecast), 28 exits | 3 | 36,429 trades, win 55%, -Rs 13 a lot a trade | 17,303 trades, win 52%, -Rs 68 a lot a trade | 8,409 trades, win 55%, Rs 8 a lot a trade |
| BEST | 3 | 12,364 trades, win 56%, -Rs 57 a lot a trade | 6,014 trades, win 56%, -Rs 136 a lot a trade | 3,244 trades, win 55%, -Rs 176 a lot a trade |

## What the network leans on (shuffle test, Apr - Dec 2025)

| input group | loss increase | share |
|---|---|---|
| index and side | 0.00122 | 36% |
| VIX, scale, day so far, premium | 0.00092 | 27% |
| candle wicks | 0.0003 | 9% |
| the other four indices' last hour | 0.00027 | 8% |
| the traded index's last hour of 1-minute bars | 0.00025 | 7% |
| support / resistance | 0.00023 | 7% |
| time and calendar | 0.0001 | 3% |
| trend (EMA, Supertrend, average price) | 5e-05 | 1% |
| OI | 3e-05 | 1% |

## Every rule (pooled over the 5 indices; rupees for the position)

| rule | O1 trades / win / net | P2 trades / win / net | P3 trades | P3 win / needed | P3 net | twins' mean | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| EV-A10s10t15@1 | 0 / - / Rs 0 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A10s10t30@1 | 0 / - / Rs 0 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A10s15t15@1 | 47 / 57% / -Rs 1,231 | 212 / 49% / -Rs 76,965 | 323 | 53% / 61% | -Rs 63,785 | -Rs 93,945 | 0.09 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A10s15t30@1 | 222 / 61% / -Rs 2,415 | 294 / 54% / -Rs 99,175 | 543 | 57% / 64% | -Rs 97,943 | -Rs 1,51,749 | 0.038 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A12s12t15@1 | 80 / 52% / Rs 2,043 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A12s12t30@1 | 194 / 51% / -Rs 8,291 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A12s18t15@1 | 264 / 48% / -Rs 9,862 | 290 / 52% / -Rs 72,829 | 448 | 55% / 59% | -Rs 32,430 | -Rs 1,00,364 | 0.003 | 0.7976 | fail: not positive in O1, P2 and P3; random-entry BH q 0.80 > 0.1 |
| EV-A12s18t30@1 | 1,049 / 55% / -Rs 54,543 | 485 / 58% / -Rs 98,122 | 1,023 | 57% / 61% | -Rs 98,312 | -Rs 2,34,671 | 0.001 | 0.709 | fail: not positive in O1, P2 and P3; random-entry BH q 0.71 > 0.1 |
| EV-A15s15t15@1 | 531 / 47% / -Rs 11,110 | 0 / - / Rs 0 | 1 | 100% / 0% | Rs 237 | -Rs 75 | 0.4128 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A15s15t30@1 | 557 / 50% / -Rs 21,119 | 0 / - / Rs 0 | 28 | 43% / 55% | -Rs 5,536 | -Rs 3,021 | 0.7211 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A15s22.5t15@1 | 1,528 / 48% / -Rs 75,375 | 390 / 54% / -Rs 56,979 | 675 | 51% / 55% | -Rs 47,006 | -Rs 1,25,849 | 0.0075 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| EV-A15s22.5t30@1 | 3,611 / 53% / -Rs 2,72,022 | 774 / 55% / -Rs 1,46,903 | 1,428 | 53% / 59% | -Rs 1,91,573 | -Rs 3,39,233 | 0.002 | 0.709 | fail: not positive in O1, P2 and P3; random-entry BH q 0.71 > 0.1 |
| EV-B10s10t15@1 | 8 / 62% / Rs 4,834 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B10s10t30@1 | 0 / - / Rs 0 | 0 / - / Rs 0 | 1 | 100% / 0% | Rs 923 | -Rs 70 | 0.1359 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B10s15t15@1 | 35 / 43% / Rs 536 | 0 / - / Rs 0 | 12 | 50% / 44% | Rs 866 | -Rs 1,144 | 0.2199 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B10s15t30@1 | 23 / 35% / -Rs 2,945 | 0 / - / Rs 0 | 12 | 50% / 41% | Rs 1,776 | -Rs 1,631 | 0.1394 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B12s12t15@1 | 184 / 44% / -Rs 6,990 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B12s12t30@1 | 8 / 38% / -Rs 1,767 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B12s18t15@1 | 27 / 63% / -Rs 465 | 12 / 42% / -Rs 4,913 | 50 | 58% / 63% | -Rs 4,830 | -Rs 7,107 | 0.3533 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B12s18t30@1 | 628 / 51% / -Rs 28,493 | 52 / 52% / -Rs 3,034 | 79 | 52% / 57% | -Rs 7,570 | -Rs 17,091 | 0.1624 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B15s15t15@1 | 380 / 46% / -Rs 3,799 | 0 / - / Rs 0 | 1 | 100% / 0% | Rs 237 | -Rs 117 | 0.4008 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B15s15t30@1 | 587 / 40% / -Rs 27,492 | 0 / - / Rs 0 | 3 | 67% / 35% | Rs 1,104 | -Rs 186 | 0.2309 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B15s22.5t15@1 | 993 / 49% / -Rs 41,616 | 61 / 49% / -Rs 6,509 | 115 | 63% / 57% | Rs 10,576 | -Rs 15,670 | 0.0055 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| EV-B15s22.5t30@1 | 2,152 / 50% / -Rs 1,04,362 | 234 / 47% / -Rs 87,822 | 512 | 56% / 60% | -Rs 40,044 | -Rs 95,646 | 0.011 | 0.9359 | fail: not positive in O1, P2 and P3; random-entry BH q 0.94 > 0.1 |
| EV-R10t30@1 | 0 / - / Rs 0 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-R10t60@1 | 2 / 100% / Rs 264 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-R15t30@1 | 26 / 50% / Rs 1,499 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-R15t60@1 | 2 / 0% / -Rs 67 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| TOP99-A10s10t15@1 | 1,952 / 53% / -Rs 91,104 | 544 / 54% / -Rs 32,426 | 326 | 52% / 56% | -Rs 14,467 | -Rs 45,168 | 0.007 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| TOP99-A10s10t30@1 | 1,679 / 51% / -Rs 1,22,716 | 842 / 52% / -Rs 76,503 | 322 | 53% / 56% | -Rs 11,815 | -Rs 46,798 | 0.0085 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| TOP99-A10s15t15@1 | 1,401 / 59% / -Rs 93,051 | 596 / 59% / -Rs 55,657 | 301 | 62% / 73% | -Rs 22,228 | -Rs 33,247 | 0.039 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A10s15t30@1 | 1,239 / 59% / -Rs 93,377 | 564 / 59% / -Rs 74,758 | 304 | 61% / 67% | -Rs 21,459 | -Rs 47,371 | 0.022 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A12s12t15@1 | 1,783 / 51% / -Rs 1,07,824 | 423 / 54% / -Rs 24,272 | 313 | 51% / 58% | -Rs 18,680 | -Rs 33,545 | 0.074 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A12s12t30@1 | 1,798 / 51% / -Rs 93,935 | 585 / 48% / -Rs 72,392 | 313 | 53% / 55% | -Rs 8,214 | -Rs 50,229 | 0.01 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| TOP99-A12s18t15@1 | 1,226 / 61% / -Rs 72,243 | 577 / 55% / -Rs 80,788 | 353 | 56% / 72% | -Rs 42,491 | -Rs 40,653 | 0.5952 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A12s18t30@1 | 1,406 / 59% / -Rs 98,621 | 609 / 60% / -Rs 61,260 | 339 | 61% / 72% | -Rs 34,645 | -Rs 48,412 | 0.1059 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A15s15t15@1 | 1,639 / 52% / -Rs 80,012 | 474 / 49% / -Rs 54,857 | 288 | 51% / 59% | -Rs 18,534 | -Rs 26,233 | 0.1374 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A15s15t30@1 | 1,540 / 51% / -Rs 99,737 | 563 / 51% / -Rs 54,629 | 328 | 55% / 58% | -Rs 12,415 | -Rs 40,135 | 0.0215 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A15s22.5t15@1 | 1,010 / 61% / -Rs 66,059 | 518 / 57% / -Rs 69,689 | 378 | 58% / 70% | -Rs 40,932 | -Rs 44,299 | 0.3533 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A15s22.5t30@1 | 1,384 / 60% / -Rs 82,544 | 672 / 60% / -Rs 64,998 | 365 | 61% / 69% | -Rs 30,547 | -Rs 50,721 | 0.0315 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B10s10t15@1 | 684 / 52% / -Rs 21,075 | 450 / 46% / -Rs 64,107 | 319 | 45% / 52% | -Rs 22,540 | -Rs 36,415 | 0.1319 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B10s10t30@1 | 818 / 49% / -Rs 49,253 | 407 / 43% / -Rs 59,668 | 342 | 49% / 51% | -Rs 9,451 | -Rs 39,680 | 0.0125 | 0.9496 | fail: not positive in O1, P2 and P3; random-entry BH q 0.95 > 0.1 |
| TOP99-B10s15t15@1 | 507 / 55% / -Rs 23,893 | 376 / 51% / -Rs 45,821 | 265 | 59% / 57% | Rs 6,454 | -Rs 30,307 | 0.002 | 0.709 | fail: not positive in O1, P2 and P3; random-entry BH q 0.71 > 0.1 |
| TOP99-B10s15t30@1 | 546 / 51% / -Rs 43,910 | 305 / 55% / -Rs 32,297 | 270 | 53% / 58% | -Rs 17,685 | -Rs 32,837 | 0.1219 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B12s12t15@1 | 963 / 51% / -Rs 46,904 | 476 / 49% / -Rs 32,803 | 322 | 51% / 54% | -Rs 14,597 | -Rs 35,532 | 0.0665 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B12s12t30@1 | 966 / 52% / -Rs 33,972 | 537 / 47% / -Rs 56,581 | 340 | 50% / 52% | -Rs 7,134 | -Rs 38,463 | 0.015 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B12s18t15@1 | 546 / 56% / -Rs 23,633 | 409 / 50% / -Rs 53,353 | 263 | 58% / 58% | Rs 1,094 | -Rs 35,417 | 0.006 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| TOP99-B12s18t30@1 | 574 / 58% / -Rs 14,365 | 337 / 52% / -Rs 52,522 | 275 | 59% / 62% | -Rs 12,207 | -Rs 37,531 | 0.041 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B15s15t15@1 | 1,009 / 50% / -Rs 44,351 | 499 / 45% / -Rs 57,435 | 277 | 52% / 51% | Rs 3,646 | -Rs 30,929 | 0.0065 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| TOP99-B15s15t30@1 | 1,028 / 53% / -Rs 33,288 | 660 / 48% / -Rs 70,139 | 339 | 50% / 54% | -Rs 20,215 | -Rs 41,071 | 0.092 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B15s22.5t15@1 | 767 / 56% / -Rs 23,192 | 521 / 50% / -Rs 66,807 | 251 | 61% / 57% | Rs 15,246 | -Rs 35,172 | 0.001 | 0.709 | fail: not positive in O1, P2 and P3; random-entry BH q 0.71 > 0.1 |
| TOP99-B15s22.5t30@1 | 550 / 57% / -Rs 20,814 | 433 / 54% / -Rs 47,811 | 248 | 59% / 62% | -Rs 12,904 | -Rs 34,149 | 0.0955 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-R10t30@1 | 991 / 44% / -Rs 54,035 | 750 / 43% / -Rs 70,103 | 401 | 47% / 48% | -Rs 8,878 | -Rs 52,936 | 0.0605 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-R10t60@1 | 838 / 45% / -Rs 23,951 | 679 / 43% / -Rs 77,231 | 363 | 44% / 45% | -Rs 5,820 | -Rs 47,411 | 0.08 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-R15t30@1 | 953 / 47% / -Rs 31,455 | 753 / 44% / -Rs 76,780 | 378 | 51% / 48% | Rs 28,637 | -Rs 48,813 | 0.01 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| TOP99-R15t60@1 | 877 / 47% / -Rs 7,694 | 643 / 44% / -Rs 66,467 | 314 | 52% / 47% | Rs 47,908 | -Rs 35,877 | 0.01 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| EV-A10s10t15@3 | 930 / 46% / -Rs 1,00,827 | 20 / 25% / -Rs 17,562 | 248 | 51% / 52% | -Rs 7,194 | -Rs 73,713 | 0.077 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A10s10t30@3 | 97 / 55% / Rs 1,162 | 41 / 37% / -Rs 15,662 | 195 | 45% / 53% | -Rs 57,039 | -Rs 70,452 | 0.6167 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A10s15t15@3 | 10,714 / 54% / -Rs 17,15,555 | 3,755 / 54% / -Rs 14,55,807 | 2,175 | 53% / 65% | -Rs 12,78,490 | -Rs 11,10,943 | 0.8881 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A10s15t30@3 | 11,193 / 56% / -Rs 20,63,936 | 4,336 / 55% / -Rs 21,28,009 | 2,293 | 55% / 66% | -Rs 14,60,063 | -Rs 13,66,934 | 0.7486 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A12s12t15@3 | 3,322 / 47% / -Rs 3,84,839 | 95 / 52% / -Rs 11,081 | 496 | 50% / 49% | Rs 13,875 | -Rs 1,22,310 | 0.01 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| EV-A12s12t30@3 | 1,004 / 50% / -Rs 84,102 | 57 / 32% / -Rs 31,008 | 336 | 48% / 53% | -Rs 71,177 | -Rs 95,356 | 0.4423 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A12s18t15@3 | 11,394 / 54% / -Rs 17,97,431 | 4,631 / 53% / -Rs 16,97,209 | 2,742 | 54% / 64% | -Rs 13,00,915 | -Rs 12,16,337 | 0.7426 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A12s18t30@3 | 11,786 / 55% / -Rs 22,14,531 | 5,349 / 56% / -Rs 22,16,270 | 2,882 | 55% / 65% | -Rs 15,65,564 | -Rs 15,23,016 | 0.6142 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A15s15t15@3 | 5,335 / 48% / -Rs 4,62,588 | 420 / 51% / -Rs 67,118 | 719 | 48% / 50% | -Rs 46,604 | -Rs 1,68,039 | 0.038 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A15s15t30@3 | 3,337 / 48% / -Rs 3,57,422 | 557 / 55% / -Rs 15,911 | 680 | 49% / 52% | -Rs 90,982 | -Rs 1,75,798 | 0.1319 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A15s22.5t15@3 | 11,540 / 53% / -Rs 16,54,187 | 4,941 / 53% / -Rs 18,35,620 | 2,874 | 54% / 61% | -Rs 9,17,676 | -Rs 11,30,640 | 0.063 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A15s22.5t30@3 | 12,034 / 55% / -Rs 20,41,862 | 5,543 / 55% / -Rs 19,44,406 | 3,088 | 56% / 63% | -Rs 13,69,228 | -Rs 15,02,447 | 0.2364 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B10s10t15@3 | 3,075 / 47% / -Rs 91,343 | 201 / 48% / -Rs 44,063 | 450 | 52% / 51% | Rs 3,859 | -Rs 63,570 | 0.0155 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B10s10t30@3 | 4,333 / 47% / -Rs 86,386 | 245 / 51% / -Rs 30,765 | 430 | 52% / 52% | -Rs 5,143 | -Rs 61,189 | 0.04 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B10s15t15@3 | 9,283 / 51% / -Rs 3,57,650 | 2,616 / 49% / -Rs 5,73,479 | 1,574 | 50% / 56% | -Rs 3,33,250 | -Rs 4,84,499 | 0.0345 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B10s15t30@3 | 9,692 / 51% / -Rs 4,36,839 | 2,851 / 49% / -Rs 6,37,876 | 1,747 | 49% / 56% | -Rs 3,91,191 | -Rs 5,58,755 | 0.03 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B12s12t15@3 | 5,050 / 48% / -Rs 2,87,320 | 287 / 46% / -Rs 69,518 | 495 | 53% / 53% | -Rs 6,149 | -Rs 72,181 | 0.0475 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B12s12t30@3 | 5,246 / 49% / -Rs 42,922 | 389 / 46% / -Rs 78,151 | 563 | 49% / 53% | -Rs 59,409 | -Rs 86,177 | 0.2604 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B12s18t15@3 | 10,455 / 51% / -Rs 6,67,403 | 3,534 / 49% / -Rs 7,13,511 | 1,849 | 48% / 56% | -Rs 4,72,332 | -Rs 6,00,694 | 0.077 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B12s18t30@3 | 11,125 / 52% / -Rs 6,89,057 | 3,878 / 49% / -Rs 9,98,263 | 2,049 | 49% / 55% | -Rs 4,74,875 | -Rs 7,08,916 | 0.01 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| EV-B15s15t15@3 | 8,122 / 48% / -Rs 4,28,530 | 494 / 46% / -Rs 87,437 | 735 | 47% / 51% | -Rs 92,992 | -Rs 1,16,175 | 0.3313 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B15s15t30@3 | 8,596 / 48% / -Rs 4,09,171 | 926 / 49% / -Rs 1,26,618 | 910 | 46% / 53% | -Rs 2,02,595 | -Rs 1,69,429 | 0.7126 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B15s22.5t15@3 | 10,962 / 51% / -Rs 7,83,254 | 4,042 / 49% / -Rs 10,20,336 | 2,090 | 48% / 54% | -Rs 4,53,061 | -Rs 7,02,939 | 0.004 | 0.8508 | fail: not positive in O1, P2 and P3; random-entry BH q 0.85 > 0.1 |
| EV-B15s22.5t30@3 | 11,740 / 52% / -Rs 8,80,788 | 4,993 / 49% / -Rs 15,02,792 | 2,439 | 49% / 55% | -Rs 5,51,898 | -Rs 9,16,248 | 0.0025 | 0.7596 | fail: not positive in O1, P2 and P3; random-entry BH q 0.76 > 0.1 |
| EV-R10t30@3 | 0 / - / Rs 0 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-R10t60@3 | 0 / - / Rs 0 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-R15t30@3 | 13 / 46% / -Rs 2,328 | 0 / - / Rs 0 | 4 | 75% / 62% | Rs 4,477 | -Rs 1,092 | 0.1919 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-R15t60@3 | 0 / - / Rs 0 | 0 / - / Rs 0 | 0 | - / - | Rs 0 | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| TOP99-A10s10t15@3 | 1,954 / 52% / -Rs 1,18,903 | 677 / 51% / -Rs 1,02,208 | 301 | 55% / 54% | Rs 6,988 | -Rs 1,00,564 | 0.007 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| TOP99-A10s10t30@3 | 1,815 / 52% / -Rs 1,30,406 | 934 / 50% / -Rs 1,84,663 | 309 | 53% / 52% | Rs 8,043 | -Rs 1,12,348 | 0.0075 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| TOP99-A10s15t15@3 | 1,459 / 60% / -Rs 1,36,344 | 481 / 58% / -Rs 1,09,314 | 272 | 62% / 67% | -Rs 28,862 | -Rs 56,995 | 0.0545 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A10s15t30@3 | 1,271 / 62% / -Rs 53,118 | 621 / 60% / -Rs 1,28,319 | 278 | 64% / 63% | Rs 11,199 | -Rs 99,744 | 0.0005 | 0.709 | fail: not positive in O1, P2 and P3; random-entry BH q 0.71 > 0.1 |
| TOP99-A12s12t15@3 | 2,052 / 52% / -Rs 1,03,065 | 606 / 54% / -Rs 31,616 | 320 | 52% / 52% | Rs 2,705 | -Rs 89,379 | 0.015 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A12s12t30@3 | 1,778 / 53% / -Rs 1,21,718 | 529 / 50% / -Rs 1,17,115 | 292 | 52% / 53% | -Rs 8,683 | -Rs 1,14,005 | 0.0255 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A12s18t15@3 | 1,339 / 61% / -Rs 1,00,998 | 593 / 59% / -Rs 1,11,901 | 318 | 60% / 67% | -Rs 43,572 | -Rs 66,411 | 0.1274 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A12s18t30@3 | 1,439 / 61% / -Rs 1,32,727 | 731 / 60% / -Rs 1,64,983 | 289 | 60% / 67% | -Rs 57,313 | -Rs 90,119 | 0.1289 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A15s15t15@3 | 1,745 / 53% / -Rs 24,123 | 477 / 50% / -Rs 1,15,766 | 299 | 53% / 54% | -Rs 6,330 | -Rs 69,479 | 0.0375 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A15s15t30@3 | 1,592 / 52% / -Rs 1,01,181 | 587 / 50% / -Rs 1,71,610 | 317 | 49% / 52% | -Rs 46,364 | -Rs 1,08,395 | 0.1774 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A15s22.5t15@3 | 1,050 / 61% / -Rs 48,538 | 465 / 57% / -Rs 1,10,566 | 352 | 54% / 66% | -Rs 1,15,755 | -Rs 82,600 | 0.9115 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-A15s22.5t30@3 | 1,438 / 60% / -Rs 1,14,375 | 675 / 59% / -Rs 1,64,574 | 363 | 59% / 66% | -Rs 78,746 | -Rs 1,01,990 | 0.2224 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B10s10t15@3 | 1,371 / 53% / -Rs 44,861 | 695 / 52% / -Rs 31,529 | 323 | 53% / 51% | Rs 10,264 | -Rs 47,805 | 0.0255 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B10s10t30@3 | 1,297 / 52% / -Rs 63,729 | 669 / 50% / -Rs 1,11,843 | 312 | 51% / 53% | -Rs 12,344 | -Rs 48,312 | 0.1224 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B10s15t15@3 | 753 / 57% / -Rs 74,724 | 453 / 57% / -Rs 51,886 | 259 | 59% / 54% | Rs 38,038 | -Rs 41,233 | 0.0115 | 0.9408 | fail: not positive in O1, P2 and P3; random-entry BH q 0.94 > 0.1 |
| TOP99-B10s15t30@3 | 840 / 59% / -Rs 45,282 | 517 / 58% / -Rs 8,605 | 284 | 58% / 56% | Rs 13,631 | -Rs 51,171 | 0.0335 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B12s12t15@3 | 1,446 / 57% / Rs 22,945 | 614 / 47% / -Rs 1,40,471 | 296 | 53% / 53% | -Rs 682 | -Rs 43,444 | 0.1014 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B12s12t30@3 | 1,265 / 54% / -Rs 41,408 | 641 / 49% / -Rs 74,211 | 309 | 54% / 53% | Rs 11,431 | -Rs 48,611 | 0.0395 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B12s18t15@3 | 812 / 59% / -Rs 49,719 | 483 / 52% / -Rs 1,27,475 | 256 | 60% / 56% | Rs 31,057 | -Rs 50,387 | 0.0095 | 0.8862 | fail: not positive in O1, P2 and P3; random-entry BH q 0.89 > 0.1 |
| TOP99-B12s18t30@3 | 837 / 60% / -Rs 33,239 | 490 / 56% / -Rs 95,355 | 275 | 61% / 59% | Rs 24,660 | -Rs 62,815 | 0.012 | 0.9453 | fail: not positive in O1, P2 and P3; random-entry BH q 0.95 > 0.1 |
| TOP99-B15s15t15@3 | 1,307 / 55% / Rs 9,942 | 457 / 50% / -Rs 72,526 | 285 | 54% / 51% | Rs 36,911 | -Rs 43,614 | 0.014 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B15s15t30@3 | 1,330 / 54% / -Rs 38,260 | 674 / 49% / -Rs 1,13,827 | 321 | 53% / 50% | Rs 35,993 | -Rs 58,124 | 0.0165 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-B15s22.5t15@3 | 1,017 / 58% / Rs 45,603 | 481 / 53% / -Rs 1,27,944 | 255 | 60% / 55% | Rs 55,620 | -Rs 47,421 | 0.0035 | 0.8272 | fail: not positive in O1, P2 and P3; random-entry BH q 0.83 > 0.1 |
| TOP99-B15s22.5t30@3 | 815 / 58% / -Rs 42,888 | 496 / 55% / -Rs 64,408 | 265 | 61% / 60% | Rs 6,322 | -Rs 62,370 | 0.0635 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-R10t30@3 | 1,207 / 48% / -Rs 80,025 | 863 / 44% / -Rs 2,72,362 | 339 | 49% / 45% | Rs 59,824 | -Rs 80,025 | 0.0255 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-R10t60@3 | 1,124 / 48% / -Rs 3,305 | 861 / 43% / -Rs 2,48,897 | 308 | 47% / 45% | Rs 29,341 | -Rs 78,090 | 0.081 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-R15t30@3 | 1,122 / 51% / Rs 1,05,863 | 777 / 46% / -Rs 2,72,025 | 329 | 51% / 47% | Rs 83,605 | -Rs 84,486 | 0.0265 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP99-R15t60@3 | 954 / 48% / Rs 77,203 | 756 / 45% / -Rs 1,95,261 | 283 | 50% / 43% | Rs 1,43,449 | -Rs 69,110 | 0.021 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| BEST@1 | 4,163 / 51% / -Rs 3,06,285 | 782 / 56% / -Rs 1,52,260 | 1,478 | 55% / 60% | -Rs 1,90,906 | -Rs 3,52,997 | 0.002 | 0.709 | fail: not positive in O1, P2 and P3; random-entry BH q 0.71 > 0.1 |
| BEST@3 | 12,364 / 56% / -Rs 21,30,960 | 6,014 / 56% / -Rs 24,47,156 | 3,244 | 55% / 66% | -Rs 17,12,937 | -Rs 15,91,462 | 0.7701 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |

## Post window (14-19 Oct 2026, never gated)

BEST@1: 58 trades, win 48% (needed 66%), -Rs 30,723 (-Rs 530 a trade). BEST@3: 100 trades, win 54% (needed 64%), -Rs 55,459 (-Rs 555 a trade).
