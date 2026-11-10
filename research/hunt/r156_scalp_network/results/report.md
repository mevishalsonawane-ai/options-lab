# R156: a neural network trained on the scalp itself

## For Boss (short)

- **Verdict: the network cannot make 10-15 point scalping profitable.** 0 of 51 trading rules built on its forecasts passed the gates.
- **What it was:** a network that reads the last hour of 1-minute index bars plus 43 numbers (support / resistance, trend, OI, wicks, VIX, time), trained on the real after-cost result of 733,260 scalp examples (every 5-minute moment, calls and puts, all 5 indices, 24 exits), 3 networks averaged, always tested on later days than it learned from.
- **Can it tell a winning scalp from a losing one? Only slightly.** Scored 0.5 = coin, 1.0 = perfect: option-point scalps 0.529 (Apr 2025 - Mar 2026) and 0.549 (Apr - Oct 2026); index-point scalps 0.546 and 0.574. Plain decision trees on the 43 numbers score 0.549 / 0.58.
- **Its best tenth of moments still loses.** Option points, Apr - Oct 2026: the network's top tenth won 58% against 69% needed (-Rs 113 a trade); an average moment won 47% against 61% (-Rs 175). Index points: top tenth 52% vs 60% needed (-Rs 102); average 39% vs 54% (-Rs 131).
- **Trading its picks (1 lot):** the network choosing the moment, side and exit itself (BEST): O1 (2023 - Mar 2025): 2,254 trades, win 50% (needed 55%), -Rs 1,31,888 (-Rs 59 a trade); P2 (Apr 2025 - Mar 2026): 783 trades, win 59% (needed 63%), -Rs 78,680 (-Rs 100 a trade); P3 final (Apr - 13 Oct 2026): 1,460 trades, win 53% (needed 59%), -Rs 2,06,663 (-Rs 142 a trade).
- **Best single rule in the final period:** EV-B12s18t15: 47 trades, win 60% (needed 52%), Rs 5,303 (Rs 113 a trade); earlier: -Rs 1,100 and -Rs 15,190.
- **It mostly learned to say no.** Across the final period it forecast a profit for only 1.4% of the moment x side x exit choices. The few it did take lost less than an average scalp but still lost: the 24 'forecast profit' rules together made -Rs 116 a trade.
- 10 of 51 rules made money in the final period; 2 in all three periods (EV-B10s15t15 with 9 final trades, EV-B12s12t15 with 2 final trades: far too few to mean anything); 0 beat random entries after the multiple-testing correction (smallest q 0.3355).
- **The network is no better than plain decision trees** on the same 43 numbers (table below), so the extra machinery found nothing the simpler model missed.
- **What the network leans on:** the last hour of 1-minute bars 37%; VIX, scale, day so far, premium 22%; index and side 20%; trend (EMA, Supertrend, average price) 7% (share of the forecast lost when that input is scrambled).
- Nothing changes in the app. Every rule is logged in `research/experiments/entries/r156-*.yaml`.

Signal source: the index (its 1-minute bars and index-derived context); the option chain and India VIX only as context; the 1-ITM nearest-expiry option is bought.

## How it was tested

Pre-registered in `PREREG.md` before any label or model. Examples: every 09:15-anchored 5-minute close from 09:19 to 14:44, both sides, per index: NIFTY 169,752, BANKNIFTY 168,696, FINNIFTY 167,904, MIDCPNIFTY 115,104, SENSEX 111,804 = 733,260 (93% of the moment x exit cells have a fill). **The pre-registration guessed 'about 1.3 million' examples; the real count is the one above** (66 moments x 2 sides x the sessions with options). Labels are R154's engine results (checked equal rule by rule). Network: three 1-D convolutions over the last 60 minutes + a table branch, 24 x (win chance, expected net); Adam, 6 epochs at most, early stopping on the last 10% of training sessions; 3 seeds averaged. Walk-forward: the models that score 2023, 2024, 2025, Jan - Mar 2026 and the final each saw only earlier sessions. Rules: EV-r (take the side with the higher forecast net if > 0), TOP-r (forecast win chance in the top tenth of the model's training forecasts), BEST (the best exit and side among 12 / 12 / 24); one open position per index, 5 trades per index a day. Gates: net > 0 in O1, P2 and P3; beats 2,000 random-entry twins (same number of trades per index and session, random moments and sides) with BH q <= 0.10 (m = 2,013); >= 40 final trades. **P3 was read for scalps by R154 and R155: a third look.**

## Can a scalp's result be forecast? (AUC: 0.5 = coin)

| period | option points: network / trees (median of 12 exits) | index points: network / trees |
|---|---|---|
| O1 (2023 - Mar 2025) | 0.539 / 0.541 | 0.549 / 0.565 |
| P2 (Apr 2025 - Mar 2026) | 0.529 / 0.53 | 0.546 / 0.551 |
| P3 final (Apr - 13 Oct 2026) | 0.549 / 0.549 | 0.574 / 0.58 |

Per exit rule (network AUC in O1 / P2 / P3):

| exit | O1 | P2 | P3 |
|---|---|---|---|
| A10s10t15 | 0.5283 | 0.521 | 0.5434 |
| A10s10t30 | 0.5251 | 0.5177 | 0.5345 |
| A10s15t15 | 0.5594 | 0.5515 | 0.5739 |
| A10s15t30 | 0.5451 | 0.5365 | 0.5552 |
| A12s12t15 | 0.53 | 0.5208 | 0.5438 |
| A12s12t30 | 0.5262 | 0.5163 | 0.5341 |
| A12s18t15 | 0.5657 | 0.557 | 0.5789 |
| A12s18t30 | 0.5546 | 0.545 | 0.5625 |
| A15s15t15 | 0.5324 | 0.5216 | 0.5434 |
| A15s15t30 | 0.5285 | 0.5177 | 0.5334 |
| A15s22.5t15 | 0.5669 | 0.5566 | 0.5806 |
| A15s22.5t30 | 0.5633 | 0.5538 | 0.5692 |
| B10s10t15 | 0.5512 | 0.5459 | 0.5793 |
| B10s10t30 | 0.556 | 0.5512 | 0.5833 |
| B10s15t15 | 0.5606 | 0.5584 | 0.5852 |
| B10s15t30 | 0.5712 | 0.5683 | 0.5968 |
| B12s12t15 | 0.5455 | 0.5416 | 0.5699 |
| B12s12t30 | 0.5496 | 0.546 | 0.5755 |
| B12s18t15 | 0.5488 | 0.5484 | 0.5715 |
| B12s18t30 | 0.5583 | 0.5597 | 0.5841 |
| B15s15t15 | 0.5359 | 0.5322 | 0.5566 |
| B15s15t30 | 0.5413 | 0.5372 | 0.5613 |
| B15s22.5t15 | 0.5373 | 0.5344 | 0.5544 |
| B15s22.5t30 | 0.5444 | 0.5454 | 0.565 |

## The forecast in tenths, option points, P2 (Apr 2025 - Mar 2026)

| tenth (1 = lowest forecast) | moments x exits | forecast win | real win | win needed | net a trade |
|---|---|---|---|---|---|
| 1 | 193,875 | 37% | 42% | 53% | -Rs 199 |
| 2 | 193,878 | 41% | 44% | 55% | -Rs 162 |
| 3 | 193,878 | 43% | 45% | 56% | -Rs 154 |
| 4 | 193,876 | 44% | 45% | 56% | -Rs 150 |
| 5 | 193,877 | 46% | 46% | 57% | -Rs 144 |
| 6 | 193,877 | 47% | 47% | 57% | -Rs 140 |
| 7 | 193,876 | 48% | 49% | 58% | -Rs 137 |
| 8 | 193,877 | 51% | 51% | 62% | -Rs 148 |
| 9 | 193,876 | 54% | 55% | 66% | -Rs 132 |
| 10 | 193,878 | 58% | 57% | 68% | -Rs 114 |

## The forecast in tenths, index points, P2 (Apr 2025 - Mar 2026)

| tenth (1 = lowest forecast) | moments x exits | forecast win | real win | win needed | net a trade |
|---|---|---|---|---|---|
| 1 | 193,877 | 24% | 35% | 54% | -Rs 71 |
| 2 | 193,877 | 31% | 37% | 55% | -Rs 98 |
| 3 | 193,877 | 35% | 39% | 54% | -Rs 116 |
| 4 | 193,876 | 38% | 40% | 54% | -Rs 124 |
| 5 | 193,877 | 41% | 42% | 54% | -Rs 122 |
| 6 | 193,877 | 43% | 43% | 54% | -Rs 128 |
| 7 | 193,875 | 45% | 44% | 54% | -Rs 124 |
| 8 | 193,878 | 47% | 45% | 55% | -Rs 120 |
| 9 | 193,877 | 49% | 47% | 56% | -Rs 118 |
| 10 | 193,877 | 53% | 51% | 59% | -Rs 105 |

## The forecast in tenths, option points, P3 final (Apr - 13 Oct 2026)

| tenth (1 = lowest forecast) | moments x exits | forecast win | real win | win needed | net a trade |
|---|---|---|---|---|---|
| 1 | 102,855 | 38% | 38% | 58% | -Rs 259 |
| 2 | 102,856 | 41% | 41% | 58% | -Rs 220 |
| 3 | 102,856 | 43% | 43% | 59% | -Rs 194 |
| 4 | 102,855 | 45% | 44% | 59% | -Rs 178 |
| 5 | 102,856 | 46% | 45% | 58% | -Rs 166 |
| 6 | 102,855 | 48% | 47% | 59% | -Rs 161 |
| 7 | 102,856 | 49% | 48% | 60% | -Rs 151 |
| 8 | 102,855 | 51% | 51% | 63% | -Rs 155 |
| 9 | 102,856 | 54% | 54% | 67% | -Rs 148 |
| 10 | 102,856 | 57% | 58% | 69% | -Rs 113 |

## The forecast in tenths, index points, P3 final (Apr - 13 Oct 2026)

| tenth (1 = lowest forecast) | moments x exits | forecast win | real win | win needed | net a trade |
|---|---|---|---|---|---|
| 1 | 102,856 | 29% | 31% | 60% | -Rs 123 |
| 2 | 102,855 | 34% | 32% | 57% | -Rs 135 |
| 3 | 102,856 | 37% | 34% | 56% | -Rs 138 |
| 4 | 102,855 | 39% | 35% | 55% | -Rs 142 |
| 5 | 102,856 | 41% | 37% | 55% | -Rs 145 |
| 6 | 102,855 | 43% | 39% | 55% | -Rs 142 |
| 7 | 102,856 | 45% | 42% | 55% | -Rs 137 |
| 8 | 102,855 | 47% | 44% | 56% | -Rs 126 |
| 9 | 102,856 | 50% | 47% | 57% | -Rs 122 |
| 10 | 102,856 | 54% | 52% | 60% | -Rs 102 |

## Every trading rule (1 lot, pooled over the 5 indices)

| rule | O1 trades / win / net | P2 trades / win / net | P3 trades | P3 win / needed | P3 net | P3 a trade | random twins' mean | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| EV-A10s10t15 | 0 / - / Rs 0 | 1 / 100% / Rs 590 | 0 | - / - | Rs 0 | - | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A10s10t30 | 6 / 67% / Rs 619 | 2 / 100% / Rs 1,171 | 0 | - / - | Rs 0 | - | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A10s15t15 | 85 / 54% / -Rs 473 | 253 / 54% / -Rs 58,358 | 444 | 53% / 60% | -Rs 80,164 | -Rs 181 | -Rs 1,19,253 | 0.0575 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A10s15t30 | 111 / 58% / -Rs 4,332 | 312 / 60% / -Rs 42,639 | 601 | 55% / 63% | -Rs 1,36,296 | -Rs 227 | -Rs 1,67,994 | 0.1589 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A12s12t15 | 27 / 48% / Rs 3,774 | 6 / 50% / -Rs 1,608 | 0 | - / - | Rs 0 | - | Rs 0 | 1.0 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A12s12t30 | 96 / 51% / -Rs 5,172 | 18 / 61% / Rs 1,335 | 3 | 33% / 39% | -Rs 387 | -Rs 129 | -Rs 443 | 0.4863 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A12s18t15 | 231 / 56% / Rs 8,921 | 378 / 56% / -Rs 40,282 | 465 | 52% / 57% | -Rs 47,702 | -Rs 103 | -Rs 1,15,898 | 0.0055 | 0.9226 | fail: not positive in O1, P2 and P3; random-entry BH q 0.92 > 0.1 |
| EV-A12s18t30 | 435 / 54% / -Rs 16,003 | 560 / 60% / -Rs 41,506 | 807 | 55% / 61% | -Rs 1,14,963 | -Rs 142 | -Rs 2,06,262 | 0.0085 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-A15s15t15 | 101 / 50% / Rs 909 | 30 / 67% / Rs 8,103 | 2 | 0% / 100% | -Rs 1,731 | -Rs 865 | -Rs 367 | 0.8421 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A15s15t30 | 834 / 49% / -Rs 28,284 | 52 / 48% / -Rs 5,649 | 10 | 70% / 29% | Rs 5,324 | Rs 532 | -Rs 1,277 | 0.055 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-A15s22.5t15 | 775 / 50% / Rs 4,893 | 516 / 50% / -Rs 76,049 | 653 | 54% / 55% | -Rs 28,050 | -Rs 43 | -Rs 1,29,615 | 0.0005 | 0.3355 | fail: not positive in O1, P2 and P3; random-entry BH q 0.34 > 0.1 |
| EV-A15s22.5t30 | 2,107 / 50% / -Rs 1,42,909 | 774 / 59% / -Rs 50,754 | 1,424 | 53% / 59% | -Rs 1,93,412 | -Rs 136 | -Rs 3,26,365 | 0.0055 | 0.9226 | fail: not positive in O1, P2 and P3; random-entry BH q 0.92 > 0.1 |
| EV-B10s10t15 | 0 / - / Rs 0 | 0 / - / Rs 0 | 1 | 100% / 0% | Rs 127 | Rs 127 | -Rs 26 | 0.1574 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B10s10t30 | 0 / - / Rs 0 | 0 / - / Rs 0 | 1 | 100% / 0% | Rs 127 | Rs 127 | -Rs 29 | 0.1499 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B10s15t15 | 4 / 50% / Rs 405 | 25 / 72% / Rs 3,150 | 9 | 67% / 58% | Rs 1,246 | Rs 138 | -Rs 907 | 0.2274 | 1.0 | fail: random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B10s15t30 | 15 / 67% / -Rs 235 | 40 / 65% / -Rs 1,424 | 78 | 53% / 51% | Rs 1,400 | Rs 18 | -Rs 14,454 | 0.03 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B12s12t15 | 2 / 100% / Rs 1,177 | 4 / 100% / Rs 1,421 | 2 | 50% / 22% | Rs 90 | Rs 45 | -Rs 75 | 0.2629 | 1.0 | fail: random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B12s12t30 | 11 / 45% / Rs 2,287 | 3 / 33% / -Rs 449 | 1 | 100% / 0% | Rs 127 | Rs 127 | -Rs 54 | 0.1659 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B12s18t15 | 124 / 49% / -Rs 1,100 | 83 / 51% / -Rs 15,190 | 47 | 60% / 52% | Rs 5,303 | Rs 113 | -Rs 6,605 | 0.0335 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B12s18t30 | 68 / 65% / Rs 10,304 | 156 / 54% / -Rs 13,825 | 322 | 52% / 59% | -Rs 40,132 | -Rs 125 | -Rs 73,526 | 0.0445 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| EV-B15s15t15 | 31 / 45% / -Rs 4 | 15 / 73% / Rs 964 | 5 | 40% / 68% | -Rs 1,017 | -Rs 203 | -Rs 229 | 0.7186 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B15s15t30 | 412 / 50% / -Rs 3,490 | 36 / 58% / Rs 4,343 | 4 | 50% / 12% | Rs 2,005 | Rs 501 | -Rs 205 | 0.058 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1; < 40 final trades |
| EV-B15s22.5t15 | 296 / 50% / -Rs 9,415 | 172 / 57% / -Rs 11,053 | 213 | 61% / 58% | Rs 14,742 | Rs 69 | -Rs 43,811 | 0.0015 | 0.4314 | fail: not positive in O1, P2 and P3; random-entry BH q 0.43 > 0.1 |
| EV-B15s22.5t30 | 852 / 53% / -Rs 24,150 | 406 / 53% / -Rs 67,841 | 664 | 54% / 58% | -Rs 52,208 | -Rs 79 | -Rs 1,48,317 | 0.0015 | 0.4314 | fail: not positive in O1, P2 and P3; random-entry BH q 0.43 > 0.1 |
| TOP-A10s10t15 | 7,143 / 49% / -Rs 4,22,402 | 4,066 / 47% / -Rs 4,09,423 | 2,061 | 49% / 59% | -Rs 2,16,396 | -Rs 105 | -Rs 2,97,534 | 0.003 | 0.671 | fail: not positive in O1, P2 and P3; random-entry BH q 0.67 > 0.1 |
| TOP-A10s10t30 | 7,398 / 51% / -Rs 4,20,788 | 4,213 / 47% / -Rs 4,75,728 | 2,019 | 51% / 61% | -Rs 2,28,018 | -Rs 113 | -Rs 3,14,952 | 0.0075 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-A10s15t15 | 5,051 / 58% / -Rs 3,57,155 | 2,738 / 53% / -Rs 3,60,227 | 1,607 | 57% / 69% | -Rs 1,68,793 | -Rs 105 | -Rs 2,11,654 | 0.035 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-A10s15t30 | 6,045 / 58% / -Rs 4,41,106 | 3,209 / 55% / -Rs 4,13,652 | 1,918 | 58% / 70% | -Rs 2,45,575 | -Rs 128 | -Rs 3,04,919 | 0.048 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-A12s12t15 | 6,762 / 49% / -Rs 3,64,275 | 3,616 / 48% / -Rs 3,63,959 | 2,076 | 49% / 58% | -Rs 1,96,982 | -Rs 95 | -Rs 3,02,333 | 0.0005 | 0.3355 | fail: not positive in O1, P2 and P3; random-entry BH q 0.34 > 0.1 |
| TOP-A12s12t30 | 7,266 / 50% / -Rs 4,46,921 | 4,131 / 47% / -Rs 5,25,079 | 2,070 | 51% / 59% | -Rs 2,09,882 | -Rs 101 | -Rs 3,34,291 | 0.0005 | 0.3355 | fail: not positive in O1, P2 and P3; random-entry BH q 0.34 > 0.1 |
| TOP-A12s18t15 | 4,599 / 58% / -Rs 3,29,853 | 2,595 / 55% / -Rs 3,12,773 | 1,422 | 57% / 70% | -Rs 1,62,987 | -Rs 115 | -Rs 1,80,088 | 0.2029 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-A12s18t30 | 5,482 / 59% / -Rs 3,73,493 | 2,915 / 55% / -Rs 3,65,328 | 1,804 | 57% / 67% | -Rs 2,19,119 | -Rs 121 | -Rs 2,82,088 | 0.0355 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-A15s15t15 | 6,320 / 50% / -Rs 3,26,844 | 3,274 / 48% / -Rs 3,54,898 | 1,978 | 48% / 56% | -Rs 1,92,204 | -Rs 97 | -Rs 2,90,954 | 0.001 | 0.4314 | fail: not positive in O1, P2 and P3; random-entry BH q 0.43 > 0.1 |
| TOP-A15s15t30 | 7,106 / 50% / -Rs 3,92,746 | 3,743 / 48% / -Rs 4,81,642 | 2,118 | 50% / 57% | -Rs 2,14,785 | -Rs 101 | -Rs 3,48,732 | 0.0025 | 0.6291 | fail: not positive in O1, P2 and P3; random-entry BH q 0.63 > 0.1 |
| TOP-A15s22.5t15 | 4,288 / 58% / -Rs 2,99,370 | 2,453 / 55% / -Rs 2,82,041 | 1,305 | 57% / 69% | -Rs 1,54,643 | -Rs 119 | -Rs 1,60,095 | 0.3693 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-A15s22.5t30 | 4,876 / 59% / -Rs 3,48,884 | 2,712 / 57% / -Rs 3,18,935 | 1,519 | 57% / 68% | -Rs 1,99,262 | -Rs 131 | -Rs 2,11,814 | 0.3278 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B10s10t15 | 3,305 / 48% / -Rs 1,66,262 | 2,093 / 46% / -Rs 1,87,246 | 1,320 | 44% / 54% | -Rs 1,28,421 | -Rs 97 | -Rs 1,69,159 | 0.03 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B10s10t30 | 3,163 / 49% / -Rs 1,54,630 | 1,993 / 47% / -Rs 1,92,763 | 1,322 | 46% / 54% | -Rs 1,17,127 | -Rs 89 | -Rs 1,71,193 | 0.0115 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B10s15t15 | 2,980 / 55% / -Rs 86,127 | 1,863 / 51% / -Rs 1,59,996 | 1,150 | 49% / 58% | -Rs 1,16,731 | -Rs 102 | -Rs 1,51,427 | 0.07 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B10s15t30 | 2,924 / 55% / -Rs 1,27,037 | 1,664 / 53% / -Rs 1,53,117 | 1,216 | 51% / 60% | -Rs 1,24,562 | -Rs 102 | -Rs 1,70,468 | 0.0355 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B12s12t15 | 3,622 / 51% / -Rs 1,16,580 | 2,087 / 44% / -Rs 2,06,211 | 1,230 | 45% / 54% | -Rs 1,33,596 | -Rs 109 | -Rs 1,56,374 | 0.1744 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B12s12t30 | 3,209 / 50% / -Rs 1,44,870 | 1,948 / 47% / -Rs 1,73,765 | 1,281 | 46% / 54% | -Rs 1,18,348 | -Rs 92 | -Rs 1,71,110 | 0.023 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B12s18t15 | 3,250 / 55% / -Rs 1,12,056 | 2,029 / 50% / -Rs 2,10,900 | 1,134 | 49% / 59% | -Rs 1,51,920 | -Rs 134 | -Rs 1,61,409 | 0.3598 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B12s18t30 | 3,013 / 56% / -Rs 1,40,999 | 1,803 / 52% / -Rs 2,12,003 | 1,150 | 52% / 61% | -Rs 1,39,087 | -Rs 121 | -Rs 1,68,582 | 0.1489 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B15s15t15 | 4,317 / 50% / -Rs 1,39,494 | 2,359 / 45% / -Rs 2,79,728 | 1,350 | 45% / 54% | -Rs 1,52,830 | -Rs 113 | -Rs 1,76,630 | 0.1939 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B15s15t30 | 3,631 / 50% / -Rs 1,55,585 | 2,111 / 44% / -Rs 3,01,327 | 1,286 | 46% / 54% | -Rs 1,52,678 | -Rs 119 | -Rs 1,85,195 | 0.1514 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B15s22.5t15 | 4,502 / 53% / -Rs 1,72,271 | 2,364 / 48% / -Rs 3,03,202 | 1,257 | 51% / 59% | -Rs 1,29,331 | -Rs 103 | -Rs 1,78,380 | 0.0565 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| TOP-B15s22.5t30 | 3,338 / 56% / -Rs 1,27,727 | 2,084 / 50% / -Rs 3,09,576 | 1,123 | 52% / 60% | -Rs 1,58,335 | -Rs 141 | -Rs 1,79,433 | 0.2699 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| BEST-A | 2,138 / 50% / -Rs 1,36,261 | 780 / 59% / -Rs 79,694 | 1,455 | 53% / 59% | -Rs 2,07,306 | -Rs 142 | -Rs 3,36,814 | 0.007 | 1.0 | fail: not positive in O1, P2 and P3; random-entry BH q 1.00 > 0.1 |
| BEST-B | 1,035 / 52% / -Rs 23,422 | 407 / 53% / -Rs 69,103 | 664 | 54% / 58% | -Rs 52,208 | -Rs 79 | -Rs 1,45,883 | 0.0015 | 0.4314 | fail: not positive in O1, P2 and P3; random-entry BH q 0.43 > 0.1 |
| BEST | 2,254 / 50% / -Rs 1,31,888 | 783 / 59% / -Rs 78,680 | 1,460 | 53% / 59% | -Rs 2,06,663 | -Rs 142 | -Rs 3,34,937 | 0.0035 | 0.7046 | fail: not positive in O1, P2 and P3; random-entry BH q 0.70 > 0.1 |

EV rules together: O1 (2023 - Mar 2025): 6,623 trades, -Rs 31 a trade; P2 (Apr 2025 - Mar 2026): 3,842 trades, -Rs 106 a trade; P3 final (Apr - 13 Oct 2026): 5,756 trades, -Rs 116 a trade.
TOP rules together: O1 (2023 - Mar 2025): 113,590 trades, -Rs 54 a trade; P2 (Apr 2025 - Mar 2026): 64,063 trades, -Rs 115 a trade; P3 final (Apr - 13 Oct 2026): 36,716 trades, -Rs 110 a trade.

## BEST (the network picks moment, side and exit): where its trades are

| period | by index (trades / net) | calls | by hour (trades / net) | exits it chose most |
|---|---|---|---|---|
| O1 (2023 - Mar 2025) | NIFTY 1406 / -Rs 1,02,289; BANKNIFTY 81 / -Rs 1,245; FINNIFTY 382 / -Rs 22,677; MIDCPNIFTY 169 / Rs 8,690; SENSEX 216 / -Rs 14,367 | 56% | 9h 644 / -Rs 19,395; 10h 453 / -Rs 48,167; 11h 287 / -Rs 13,212; 12h 266 / -Rs 32,297; 13h 309 / -Rs 964; 14h 295 / -Rs 17,853 | A15s22.5t30 2033, B15s15t30 102, B15s22.5t30 66, A15s22.5t15 28 |
| P2 (Apr 2025 - Mar 2026) | NIFTY 280 / -Rs 25,480; BANKNIFTY 35 / -Rs 53; FINNIFTY 159 / -Rs 19,406; MIDCPNIFTY 295 / -Rs 33,363; SENSEX 14 / -Rs 377 | 52% | 9h 416 / -Rs 57,432; 10h 187 / -Rs 29,011; 11h 63 / -Rs 6,115; 12h 47 / Rs 2,484; 13h 40 / Rs 6,557; 14h 30 / Rs 4,837 | A15s22.5t30 608, A12s18t30 166, B15s22.5t30 9 |
| P3 final (Apr - 13 Oct 2026) | NIFTY 529 / -Rs 62,732; BANKNIFTY 15 / -Rs 7,243; FINNIFTY 317 / -Rs 62,791; MIDCPNIFTY 595 / -Rs 74,054; SENSEX 4 / Rs 157 | 51% | 9h 790 / -Rs 83,997; 10h 344 / -Rs 81,770; 11h 97 / -Rs 27,442; 12h 65 / Rs 4,125; 13h 84 / -Rs 9,960; 14h 80 / -Rs 7,619 | A15s22.5t30 1060, A12s18t30 360, A10s15t30 34, B15s22.5t30 4 |

## What the network leans on (shuffle test, Apr - Dec 2025, fold 2025's models)

Win-forecast loss 0.6868 on 60,000 examples; the increase when one input group is shuffled across examples:

| input group | loss increase | share |
|---|---|---|
| the last hour of 1-minute bars | 0.0009 | 37% |
| VIX, scale, day so far, premium | 0.00054 | 22% |
| index and side | 0.00048 | 20% |
| trend (EMA, Supertrend, average price) | 0.00016 | 7% |
| candle wicks | 0.00014 | 6% |
| time and calendar | 0.00013 | 5% |
| OI | 7e-05 | 3% |
| support / resistance | 2e-05 | 1% |

## Training (validation loss per epoch; the lowest is kept)

| fold | seed 156 | seed 157 | seed 158 |
|---|---|---|---|
| 2023 | 0.7614, 0.7624, 0.7613, 0.7632, 0.7603, 0.7613 | 0.7613, 0.7621, 0.7635, 0.7638, 0.7606, 0.7627 | 0.7618, 0.7647, 0.7609, 0.7610, 0.7603, 0.7637 |
| 2024 | 0.7426, 0.7425, 0.7414, 0.7412, 0.7412, 0.7418 | 0.7426, 0.7416, 0.7416, 0.7421, 0.7421, 0.7431 | 0.7426, 0.7421, 0.7424, 0.7422, 0.7433, 0.7433 |
| 2025 | 0.8025, 0.8028, 0.8032, 0.8047, 0.8047, 0.8082 | 0.8024, 0.8023, 0.8044, 0.8049, 0.8042, 0.8066 | 0.8023, 0.8036, 0.8036, 0.8040, 0.8048, 0.8082 |
| 2026q1 | 0.9332, 0.9337, 0.9323, 0.9321, 0.9335, 0.9328 | 0.9316, 0.9325, 0.9320, 0.9333, 0.9341, 0.9347 | 0.9323, 0.9323, 0.9333, 0.9324, 0.9325, 0.9319 |
| final | 0.9401, 0.9392, 0.9394, 0.9384, 0.9398, 0.9402 | 0.9408, 0.9382, 0.9398, 0.9394, 0.9385, 0.9399 | 0.9401, 0.9409, 0.9391, 0.9399, 0.9386, 0.9395 |

## Post window (14-19 Oct 2026, 4 sessions, never gated)

BEST: 60 trades, win 53% (needed 69%), -Rs 30,472 (-Rs 508 a trade). EV rules together: 280 trades, -Rs 98,555.

## Earlier findings, honestly

- R154: no entry makes the scalp pay. R155: no filter does; a logistic picker loses less.
- R16, B1, R41, R123, R124: the size of the next move is predictable, its direction is a coin. A scalp with a target and a stop of similar size is a bet on direction, minus costs. R156's network, trained on the scalp itself, leans on the same inputs that predict size (the last hour of bars, VIX and the day's scale) and gains only a small edge in picking winners, well short of the win rate the costs require.
