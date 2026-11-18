# R194 summary: does support / resistance help R72, R51 and Liquidity 15+5?

Research only; nothing in the app changes. Signal on the index; options bought; 1 lot; dated Zerodha charges and R17 slippage; 1 Nov 2024 - 13 Nov 2026 (R193's span, stores and engine). Gate (PREREG section 8): vs the baseline, higher net AND lower max drawdown in both W1 and W2, 40+ trades in each, net without the top 5 above the baseline's net, twin p < 0.05, BH q <= 0.10 (m = 4,808). Each cell: trades / win % / net / max drawdown.

## Outcome: none of the 24 pre-registered tests passes the gate

Boss's question: *"Does support/resistance help R51, R72 and Liquidity?"* Short answer: **this study cannot show that any
support / resistance piece helps any of the three arms reliably.** Every number below is 1 lot, after dated Zerodha
charges and R17 slippage, 1 Nov 2024 - 13 Nov 2026 (W1 = Nov 2024 - Mar 2026, where R151's pickers were trained; W2 =
Apr - 13 Nov 2026, out-of-sample for the pickers but already read by R151-R193, so not fresh).

**R72 Breadth Donchian**
- *S/R inside the picker (A, B).* With its five S/R features set to "missing", the frozen picker keeps 1,848 trades and
  makes Rs 4,99,737 instead of 2,256 trades and Rs 6,52,008. But all of that difference is in W1, where the picker was
  trained (Rs 4,99,387 vs Rs 3,46,009). In W2 the S/R features changed almost nothing: 726 trades / Rs 1,52,620 with them,
  607 / Rs 1,53,728 without (drawdown Rs 37,082 vs Rs 41,116). A picker retrained by R151's own procedure without the S/R
  features made more money: Rs 6,60,987 over the span and Rs 1,73,629 in W2 (828 trades), with a larger W2 drawdown
  (Rs 55,383). So the S/R features look useful only where the model saw the answers.
- *Explicit S/R filters (C).* All six cut money badly in both windows (skipping breaks with a level within 0.15% / 0.30%
  ahead, or taking only breaks that crossed a level), on own exits and on top of the picker.
- *S/R target (D).* Selling at the next 3+-touch level ahead cut net in both windows on top of the picker (Rs 4,31,272 vs
  Rs 6,52,008) and on top of the live V5 (Rs 2,77,466 vs Rs 3,58,078).

**R51 Donchian 20 + Supertrend**
- *S/R inside the picker.* Ablated: Rs 4,78,169 vs Rs 4,88,964 over the span, but in W2 the picker WITHOUT its S/R inputs
  did better (709 trades / Rs 1,60,497 vs 791 / Rs 1,40,702). Retrained without S/R: Rs 4,83,954 vs Rs 4,88,964, and again
  more in W2 (Rs 1,60,119). The S/R features help R51's picker only in-sample, if at all.
- *Filters and target.* Same picture as R72: every filter and the target lost money in both windows. The filters did lower
  drawdowns on top of the picker (e.g. WALL15: W2 drawdown Rs 26,705 vs Rs 38,799) but by giving up about half the net.

**Liquidity 15+5**
- *Room check.* The live check (room >= 1 index stop to the next level) is the best of the four rooms on net in W1
  (Rs 2,49,576 vs 2,15,413 with no check, 2,39,895 at 0.5, 2,36,955 at 2). Removing it (room 0) or loosening it (0.5)
  lost money in both windows, and the skips it makes were better than random skips of the same number (L1's net sits high
  among the twins: lower-tail p 0.998 / 0.965, i.e. roughly p 0.003 / 0.04 the other way; descriptive, not a pre-registered
  test in that direction). A stricter room 2 made a little more in W2 (Rs 6,715 vs Rs 5,717) with a smaller W2 drawdown,
  but less in W1 and twin p 0.14: no evidence.
- *New-level exit.* Switching it off made less in W1 (Rs 2,16,139 vs Rs 2,49,576), more in W2 (Rs 10,546 vs Rs 5,717,
  smaller drawdown), twin p 0.10: no evidence either way. Liquidity barely made money in W2 under any setting.

**Which piece, in one line each.** R72 / R51: no S/R piece helps; the picker's S/R features add money only in its training
years, and explicit S/R skips and targets cost money. Liquidity: the room check that is live now is the S/R piece with the
best case (it beats no check and a looser check on net in both windows), but it did not have to pass a test in that
direction here, and a stricter check did not help. Nothing in the app changes.

**Caveats.** W2 is out-of-sample for the pickers but has been read by many studies (R151-R193), so it is not fresh. Family
C repeats the kind of filter R150 already rejected on other years (not independent). The exit twins (a random sale between
the fill and the baseline's exit) are a weak benchmark: D-TGT on V5 has twin p 0.0005 although it lost money against V5 in
both windows; its verdict rests on the net and drawdown checks. The Liquidity room 0 / 0.5 and NL0 twins test the reverse
tail as pre-registered. The B retrain reproduced R151's app models to the last digit (max difference 0), and every
baseline reproduced R193 to the rupee. Nothing here speaks for years before Nov 2024.


## Reproduction first

| check | trades | net | R193 | gap | reproduced |
|---|---:|---:|---|---|---|
| R72 A | 4,571 | Rs 390,101 | 4,571 / Rs 390,101 | +0, +0 | yes |
| R72 P | 2,256 | Rs 652,008 | 2,256 / Rs 652,008 | +0, +0 | yes |
| R72 V5 | 2,352 | Rs 358,078 | 2,352 / Rs 358,078 | +0, +0 | yes |
| R51 A | 6,269 | Rs 191,394 | 6,269 / Rs 191,394 | +0, +0 | yes |
| R51 P | 2,890 | Rs 488,964 | 2,890 / Rs 488,964 | +0, +0 | yes |
| R51 V5 | 3,082 | Rs 203,580 | 3,082 / Rs 203,580 | +0, +0 | yes |
| LIQ L1 (R194's signal copy) | 1,323 | Rs 255,293 | 1,323 / Rs 255,293 | +0, +0 | yes |

**R151's model (family B)**, refit in R151's store window and compared with the app's numbers:

| arm | design trades | max abs diff (median / mean / scale / coef / intercept) | reproduced | rule threshold of the refit full model | no-S/R threshold (rule) |
|---|---:|---|---|---|---|
| R72 | 3,071 | 0.0e+00 / 0.0e+00 / 0.0e+00 / 0.0e+00 / 0.0e+00 | yes | 0.4639613199077514 (best_point) | 0.45355714588582235 (best_point) |
| R51 | 4,208 | 0.0e+00 / 0.0e+00 / 0.0e+00 / 0.0e+00 / 0.0e+00 | yes | 0.44688323477746883 (best_point) | 0.4411116876229912 (best_point) |

## A. Picker ablation (S/R features at their training medians)

| test | row | W1 trades / win / net / max DD | W2 trades / win / net / max DD | total net | total net w/o top 5 | twin p | BH q | verdict |
|---|---|---|---|---:|---:|---:|---:|---|
| A-R72 | baseline: picker with the 5 S/R features at their training medians | 1,241 / 53.7% / Rs 346,009 / Rs 108,314 | 607 / 50.4% / Rs 153,728 / Rs 41,116 | Rs 499,737 | Rs 436,150 | | | |
| A-R72 | variant: own exits + R151 picker | 1,530 / 54.0% / Rs 499,387 / Rs 99,586 | 726 / 48.5% / Rs 152,620 / Rs 37,082 | Rs 652,008 | Rs 588,421 | 0.0005 | 0.00292 | no: W2: net 152,620 not above the baseline's 153,728 |
| A-R51 | baseline: picker with the 5 S/R features at their training medians | 2,005 / 49.1% / Rs 317,672 / Rs 99,620 | 709 / 52.0% / Rs 160,497 / Rs 39,790 | Rs 478,169 | Rs 406,366 | | | |
| A-R51 | variant: own exits + R151 picker | 2,099 / 49.0% / Rs 348,262 / Rs 72,535 | 791 / 50.8% / Rs 140,702 / Rs 38,799 | Rs 488,964 | Rs 418,880 | 0.0365 | 1 | no: W2: net 140,702 not above the baseline's 160,497; total: net without top 5 418,880 not above the baseline's 478,169; BH q 1 > 0.1 |

## B. Picker retrained without the S/R features

| test | row | W1 trades / win / net / max DD | W2 trades / win / net / max DD | total net | total net w/o top 5 | twin p | BH q | verdict |
|---|---|---|---|---:|---:|---:|---:|---|
| B-R72 | baseline: picker retrained without the 5 S/R features | 1,761 / 52.4% / Rs 487,358 / Rs 86,909 | 828 / 49.5% / Rs 173,629 / Rs 55,383 | Rs 660,987 | Rs 597,400 | | | |
| B-R72 | variant: own exits + R151 picker | 1,530 / 54.0% / Rs 499,387 / Rs 99,586 | 726 / 48.5% / Rs 152,620 / Rs 37,082 | Rs 652,008 | Rs 588,421 | 0.0030 | 1 | no: W1: max drawdown 99,586 not below the baseline's 86,909; W2: net 152,620 not above the baseline's 173,629; total: net without top 5 588,421 not above the baseline's 660,987; BH q 1 > 0.1 |
| B-R51 | baseline: picker retrained without the 5 S/R features | 2,315 / 48.4% / Rs 323,835 / Rs 111,954 | 835 / 50.9% / Rs 160,119 / Rs 46,225 | Rs 483,954 | Rs 412,151 | | | |
| B-R51 | variant: own exits + R151 picker | 2,099 / 49.0% / Rs 348,262 / Rs 72,535 | 791 / 50.8% / Rs 140,702 / Rs 38,799 | Rs 488,964 | Rs 418,880 | 0.0205 | 1 | no: W2: net 140,702 not above the baseline's 160,119; total: net without top 5 418,880 not above the baseline's 483,954; BH q 1 > 0.1 |

## C. Explicit S/R entry filters

| test | row | W1 trades / win / net / max DD | W2 trades / win / net / max DD | total net | total net w/o top 5 | twin p | BH q | verdict |
|---|---|---|---|---:|---:|---:|---:|---|
| C-WALL15-R72-A | baseline: own exits, no picker | 3,176 / 46.8% / Rs 240,041 / Rs 199,077 | 1,395 / 45.8% / Rs 150,061 / Rs 61,884 | Rs 390,101 | Rs 326,344 | | | |
| C-WALL15-R72-A | variant: own exits, no picker + skip when a 3+-touch level is ahead within 0.15% | 2,012 / 47.0% / Rs 169,079 / Rs 108,539 | 884 / 44.5% / Rs 58,780 / Rs 54,357 | Rs 227,859 | Rs 170,355 | 0.6102 | 1 | no: W1: net 169,079 not above the baseline's 240,041; W2: net 58,780 not above the baseline's 150,061; total: net without top 5 170,355 not above the baseline's 390,101; twin p 0.6102 >= 0.05; BH q 1 > 0.1 |
| C-WALL15-R72-P | baseline: own exits + R151 picker | 1,530 / 54.0% / Rs 499,387 / Rs 99,586 | 726 / 48.5% / Rs 152,620 / Rs 37,082 | Rs 652,008 | Rs 588,421 | | | |
| C-WALL15-R72-P | variant: own exits + R151 picker + skip when a 3+-touch level is ahead within 0.15% | 1,097 / 54.0% / Rs 369,230 / Rs 58,828 | 499 / 47.1% / Rs 91,050 / Rs 32,848 | Rs 460,280 | Rs 402,776 | 0.4968 | 1 | no: W1: net 369,230 not above the baseline's 499,387; W2: net 91,050 not above the baseline's 152,620; total: net without top 5 402,776 not above the baseline's 652,008; twin p 0.4968 >= 0.05; BH q 1 > 0.1 |
| C-WALL15-R51-A | baseline: own exits, no picker | 4,336 / 44.4% / Rs 55,411 / Rs 214,239 | 1,933 / 45.7% / Rs 135,983 / Rs 81,683 | Rs 191,394 | Rs 118,071 | | | |
| C-WALL15-R51-A | variant: own exits, no picker + skip when a 3+-touch level is ahead within 0.15% | 2,727 / 45.1% / Rs 157,806 / Rs 109,370 | 1,208 / 44.7% / Rs 36,969 / Rs 71,939 | Rs 194,776 | Rs 124,692 | 0.1789 | 1 | no: W2: net 36,969 not above the baseline's 135,983; total: net without top 5 124,692 not above the baseline's 191,394; twin p 0.1789 >= 0.05; BH q 1 > 0.1 |
| C-WALL15-R51-P | baseline: own exits + R151 picker | 2,099 / 49.0% / Rs 348,262 / Rs 72,535 | 791 / 50.8% / Rs 140,702 / Rs 38,799 | Rs 488,964 | Rs 418,880 | | | |
| C-WALL15-R51-P | variant: own exits + R151 picker + skip when a 3+-touch level is ahead within 0.15% | 1,371 / 49.3% / Rs 249,860 / Rs 48,337 | 520 / 49.4% / Rs 72,093 / Rs 26,705 | Rs 321,953 | Rs 251,870 | 0.4963 | 1 | no: W1: net 249,860 not above the baseline's 348,262; W2: net 72,093 not above the baseline's 140,702; total: net without top 5 251,870 not above the baseline's 488,964; twin p 0.4963 >= 0.05; BH q 1 > 0.1 |
| C-WALL30-R72-A | baseline: own exits, no picker | 3,176 / 46.8% / Rs 240,041 / Rs 199,077 | 1,395 / 45.8% / Rs 150,061 / Rs 61,884 | Rs 390,101 | Rs 326,344 | | | |
| C-WALL30-R72-A | variant: own exits, no picker + skip when a 3+-touch level is ahead within 0.30% | 1,025 / 48.2% / Rs 119,512 / Rs 74,964 | 451 / 44.4% / Rs 14,516 / Rs 52,189 | Rs 134,028 | Rs 82,620 | 0.4503 | 1 | no: W1: net 119,512 not above the baseline's 240,041; W2: net 14,516 not above the baseline's 150,061; total: net without top 5 82,620 not above the baseline's 390,101; twin p 0.4503 >= 0.05; BH q 1 > 0.1 |
| C-WALL30-R72-P | baseline: own exits + R151 picker | 1,530 / 54.0% / Rs 499,387 / Rs 99,586 | 726 / 48.5% / Rs 152,620 / Rs 37,082 | Rs 652,008 | Rs 588,421 | | | |
| C-WALL30-R72-P | variant: own exits + R151 picker + skip when a 3+-touch level is ahead within 0.30% | 720 / 53.5% / Rs 219,153 / Rs 33,661 | 315 / 47.6% / Rs 63,962 / Rs 36,049 | Rs 283,115 | Rs 231,706 | 0.6242 | 1 | no: W1: net 219,153 not above the baseline's 499,387; W2: net 63,962 not above the baseline's 152,620; total: net without top 5 231,706 not above the baseline's 652,008; twin p 0.6242 >= 0.05; BH q 1 > 0.1 |
| C-WALL30-R51-A | baseline: own exits, no picker | 4,336 / 44.4% / Rs 55,411 / Rs 214,239 | 1,933 / 45.7% / Rs 135,983 / Rs 81,683 | Rs 191,394 | Rs 118,071 | | | |
| C-WALL30-R51-A | variant: own exits, no picker + skip when a 3+-touch level is ahead within 0.30% | 1,328 / 45.3% / Rs 81,976 / Rs 76,020 | 610 / 45.4% / Rs 34,608 / Rs 47,104 | Rs 116,584 | Rs 56,027 | 0.2279 | 1 | no: W2: net 34,608 not above the baseline's 135,983; total: net without top 5 56,027 not above the baseline's 191,394; twin p 0.2279 >= 0.05; BH q 1 > 0.1 |
| C-WALL30-R51-P | baseline: own exits + R151 picker | 2,099 / 49.0% / Rs 348,262 / Rs 72,535 | 791 / 50.8% / Rs 140,702 / Rs 38,799 | Rs 488,964 | Rs 418,880 | | | |
| C-WALL30-R51-P | variant: own exits + R151 picker + skip when a 3+-touch level is ahead within 0.30% | 805 / 49.8% / Rs 154,566 / Rs 41,508 | 335 / 50.1% / Rs 72,212 / Rs 26,705 | Rs 226,779 | Rs 166,700 | 0.2854 | 1 | no: W1: net 154,566 not above the baseline's 348,262; W2: net 72,212 not above the baseline's 140,702; total: net without top 5 166,700 not above the baseline's 488,964; twin p 0.2854 >= 0.05; BH q 1 > 0.1 |
| C-CROSS-R72-A | baseline: own exits, no picker | 3,176 / 46.8% / Rs 240,041 / Rs 199,077 | 1,395 / 45.8% / Rs 150,061 / Rs 61,884 | Rs 390,101 | Rs 326,344 | | | |
| C-CROSS-R72-A | variant: own exits, no picker + take only breaks that crossed a 3+-touch level | 957 / 47.1% / Rs 84,941 / Rs 66,622 | 389 / 45.0% / Rs 10,290 / Rs 31,003 | Rs 95,232 | Rs 32,486 | 0.6127 | 1 | no: W1: net 84,941 not above the baseline's 240,041; W2: net 10,290 not above the baseline's 150,061; total: net without top 5 32,486 not above the baseline's 390,101; twin p 0.6127 >= 0.05; BH q 1 > 0.1 |
| C-CROSS-R72-P | baseline: own exits + R151 picker | 1,530 / 54.0% / Rs 499,387 / Rs 99,586 | 726 / 48.5% / Rs 152,620 / Rs 37,082 | Rs 652,008 | Rs 588,421 | | | |
| C-CROSS-R72-P | variant: own exits + R151 picker + take only breaks that crossed a 3+-touch level | 474 / 50.8% / Rs 125,608 / Rs 44,171 | 194 / 45.4% / Rs 22,031 / Rs 31,048 | Rs 147,639 | Rs 84,893 | 0.8246 | 1 | no: W1: net 125,608 not above the baseline's 499,387; W2: net 22,031 not above the baseline's 152,620; total: net without top 5 84,893 not above the baseline's 652,008; twin p 0.8246 >= 0.05; BH q 1 > 0.1 |
| C-CROSS-R51-A | baseline: own exits, no picker | 4,336 / 44.4% / Rs 55,411 / Rs 214,239 | 1,933 / 45.7% / Rs 135,983 / Rs 81,683 | Rs 191,394 | Rs 118,071 | | | |
| C-CROSS-R51-A | variant: own exits, no picker + take only breaks that crossed a 3+-touch level | 1,421 / 43.4% / Rs 23,051 / Rs 101,546 | 545 / 44.8% / -Rs 10,916 / Rs 39,730 | Rs 12,136 | -Rs 55,975 | 0.7241 | 1 | no: W1: net 23,051 not above the baseline's 55,411; W2: net -10,916 not above the baseline's 135,983; total: net without top 5 -55,975 not above the baseline's 191,394; twin p 0.7241 >= 0.05; BH q 1 > 0.1 |
| C-CROSS-R51-P | baseline: own exits + R151 picker | 2,099 / 49.0% / Rs 348,262 / Rs 72,535 | 791 / 50.8% / Rs 140,702 / Rs 38,799 | Rs 488,964 | Rs 418,880 | | | |
| C-CROSS-R51-P | variant: own exits + R151 picker + take only breaks that crossed a 3+-touch level | 599 / 49.1% / Rs 117,486 / Rs 48,638 | 151 / 51.0% / Rs 16,511 / Rs 23,198 | Rs 133,997 | Rs 70,743 | 0.4553 | 1 | no: W1: net 117,486 not above the baseline's 348,262; W2: net 16,511 not above the baseline's 140,702; total: net without top 5 70,743 not above the baseline's 488,964; twin p 0.4553 >= 0.05; BH q 1 > 0.1 |

## D. S/R exits and Liquidity's room check

| test | row | W1 trades / win / net / max DD | W2 trades / win / net / max DD | total net | total net w/o top 5 | twin p | BH q | verdict |
|---|---|---|---|---:|---:|---:|---:|---|
| D-TGT-R72-P | baseline: own exits + R151 picker | 1,530 / 54.0% / Rs 499,387 / Rs 99,586 | 726 / 48.5% / Rs 152,620 / Rs 37,082 | Rs 652,008 | Rs 588,421 | | | |
| D-TGT-R72-P | variant: own exits + R151 picker + S/R target: sell when the index touches the next 3+-touch level ahead | 1,583 / 56.5% / Rs 342,931 / Rs 101,616 | 768 / 51.6% / Rs 88,340 / Rs 36,893 | Rs 431,272 | Rs 379,863 | 0.1324 | 1 | no: W1: net 342,931 not above the baseline's 499,387; W1: max drawdown 101,616 not below the baseline's 99,586; W2: net 88,340 not above the baseline's 152,620; total: net without top 5 379,863 not above the baseline's 652,008; twin p 0.1324 >= 0.05; BH q 1 > 0.1 |
| D-TGT-R72-V5 | baseline: live rule (picker + breakeven +10% + Rs 500 step lock) | 1,595 / 65.4% / Rs 237,089 / Rs 100,582 | 757 / 60.8% / Rs 120,989 / Rs 36,477 | Rs 358,078 | Rs 308,071 | | | |
| D-TGT-R72-V5 | variant: live rule (picker + breakeven +10% + Rs 500 step lock) + S/R target: sell when the index touches the next 3+-touch level ahead | 1,620 / 64.8% / Rs 205,975 / Rs 104,489 | 777 / 60.0% / Rs 71,491 / Rs 38,243 | Rs 277,466 | Rs 240,964 | 0.0005 | 2.84e-13 | no: W1: net 205,975 not above the baseline's 237,089; W1: max drawdown 104,489 not below the baseline's 100,582; W2: net 71,491 not above the baseline's 120,989; W2: max drawdown 38,243 not below the baseline's 36,477; total: net without top 5 240,964 not above the baseline's 358,078 |
| D-TGT-R51-P | baseline: own exits + R151 picker | 2,099 / 49.0% / Rs 348,262 / Rs 72,535 | 791 / 50.8% / Rs 140,702 / Rs 38,799 | Rs 488,964 | Rs 418,880 | | | |
| D-TGT-R51-P | variant: own exits + R151 picker + S/R target: sell when the index touches the next 3+-touch level ahead | 2,207 / 52.9% / Rs 134,584 / Rs 92,543 | 824 / 53.3% / Rs 75,131 / Rs 27,903 | Rs 209,715 | Rs 158,791 | 0.0940 | 1 | no: W1: net 134,584 not above the baseline's 348,262; W1: max drawdown 92,543 not below the baseline's 72,535; W2: net 75,131 not above the baseline's 140,702; total: net without top 5 158,791 not above the baseline's 488,964; twin p 0.0940 >= 0.05; BH q 1 > 0.1 |
| D-TGT-R51-V5 | baseline: live rule (picker + breakeven +10% + Rs 500 step lock) | 2,240 / 61.9% / Rs 124,786 / Rs 85,937 | 842 / 62.0% / Rs 78,794 / Rs 45,825 | Rs 203,580 | Rs 161,487 | | | |
| D-TGT-R51-V5 | variant: live rule (picker + breakeven +10% + Rs 500 step lock) + S/R target: sell when the index touches the next 3+-touch level ahead | 2,286 / 61.6% / Rs 41,409 / Rs 104,495 | 854 / 60.9% / Rs 36,676 / Rs 39,773 | Rs 78,085 | Rs 52,042 | 0.0005 | 3.22e-23 | no: W1: net 41,409 not above the baseline's 124,786; W1: max drawdown 104,495 not below the baseline's 85,937; W2: net 36,676 not above the baseline's 78,794; total: net without top 5 52,042 not above the baseline's 203,580 |
| D-LQ-R0 | baseline: Liquidity as live (room 1 index stop, new-level exit on) | 908 / 43.1% / Rs 249,576 / Rs 32,537 | 415 / 38.1% / Rs 5,717 / Rs 61,062 | Rs 255,293 | Rs 168,104 | | | |
| D-LQ-R0 | variant: Liquidity, no room check | 1,443 / 44.6% / Rs 215,413 / Rs 43,754 | 624 / 42.0% / -Rs 1,362 / Rs 62,100 | Rs 214,050 | Rs 126,861 | 0.9980 | 1 | no: W1: net 215,413 not above the baseline's 249,576; W1: max drawdown 43,754 not below the baseline's 32,537; W2: net -1,362 not above the baseline's 5,717; W2: max drawdown 62,100 not below the baseline's 61,062; total: net without top 5 126,861 not above the baseline's 255,293; twin p 0.9980 >= 0.05; BH q 1 > 0.1 |
| D-LQ-R05 | baseline: Liquidity as live (room 1 index stop, new-level exit on) | 908 / 43.1% / Rs 249,576 / Rs 32,537 | 415 / 38.1% / Rs 5,717 / Rs 61,062 | Rs 255,293 | Rs 168,104 | | | |
| D-LQ-R05 | variant: Liquidity, room 0.5 index stop | 1,100 / 44.5% / Rs 239,895 / Rs 35,566 | 507 / 40.0% / -Rs 1,628 / Rs 62,231 | Rs 238,266 | Rs 151,077 | 0.9650 | 1 | no: W1: net 239,895 not above the baseline's 249,576; W1: max drawdown 35,566 not below the baseline's 32,537; W2: net -1,628 not above the baseline's 5,717; W2: max drawdown 62,231 not below the baseline's 61,062; total: net without top 5 151,077 not above the baseline's 255,293; twin p 0.9650 >= 0.05; BH q 1 > 0.1 |
| D-LQ-R2 | baseline: Liquidity as live (room 1 index stop, new-level exit on) | 908 / 43.1% / Rs 249,576 / Rs 32,537 | 415 / 38.1% / Rs 5,717 / Rs 61,062 | Rs 255,293 | Rs 168,104 | | | |
| D-LQ-R2 | variant: Liquidity, room 2 index stops | 719 / 40.3% / Rs 236,955 / Rs 32,920 | 343 / 35.9% / Rs 6,715 / Rs 56,505 | Rs 243,670 | Rs 156,481 | 0.1444 | 1 | no: W1: net 236,955 not above the baseline's 249,576; W1: max drawdown 32,920 not below the baseline's 32,537; total: net without top 5 156,481 not above the baseline's 255,293; twin p 0.1444 >= 0.05; BH q 1 > 0.1 |
| D-LQ-NL0 | baseline: Liquidity as live (room 1 index stop, new-level exit on) | 908 / 43.1% / Rs 249,576 / Rs 32,537 | 415 / 38.1% / Rs 5,717 / Rs 61,062 | Rs 255,293 | Rs 168,104 | | | |
| D-LQ-NL0 | variant: Liquidity, new-level exit off | 900 / 42.0% / Rs 216,139 / Rs 32,537 | 411 / 38.0% / Rs 10,546 / Rs 54,948 | Rs 226,685 | Rs 140,099 | 0.1035 | 1 | no: W1: net 216,139 not above the baseline's 249,576; W1: max drawdown 32,537 not below the baseline's 32,537; total: net without top 5 140,099 not above the baseline's 255,293; twin p 0.1035 >= 0.05; BH q 1 > 0.1 |

## Extras (descriptive)

- R72 ablation flips: 1,481 of 8,861 candidates change side of the threshold; taken trades only the real picker keeps / only the ablated keeps: W1 356 / 67, W2 142 / 23.
- D-TGT-R72-P: 7,054 candidates had a 3+-touch level ahead, 4,090 reached it before the square-off; exits: {'time': 1145, 'stop': 365, 'rule_exit': 750, 'lock': 32, 'squareoff': 57, 'missing': 2}; trades the target closed >= 2 minutes earlier: 739.
- D-TGT-R72-V5: 7,054 candidates had a 3+-touch level ahead, 4,090 reached it before the square-off; exits: {'time': 514, 'step_lock': 839, 'stop': 310, 'rule_exit': 638, 'lock': 58, 'squareoff': 37, 'missing': 1}; trades the target closed >= 2 minutes earlier: 537.
- R51 ablation flips: 2,174 of 18,598 candidates change side of the threshold; taken trades only the real picker keeps / only the ablated keeps: W1 287 / 193, W2 152 / 70.
- D-TGT-R51-P: 14,641 candidates had a 3+-touch level ahead, 8,441 reached it before the square-off; exits: {'time': 1426, 'rule_exit': 1057, 'stop': 500, 'lock': 31, 'squareoff': 14, 'missing': 3}; trades the target closed >= 2 minutes earlier: 1,041.
- D-TGT-R51-V5: 14,641 candidates had a 3+-touch level ahead, 8,441 reached it before the square-off; exits: {'lock': 83, 'rule_exit': 902, 'time': 724, 'step_lock': 984, 'stop': 437, 'squareoff': 8, 'missing': 2}; trades the target closed >= 2 minutes earlier: 778.
- Liquidity signals in the span by room: {'0.0': 2103, '0.5': 1635, '1.0': 1345, '2.0': 1080}.

## Twins and p

| test | twin p | tail | normal p | twin mean | twin sd | stat |
|---|---:|---|---:|---:|---:|---:|
| A-R72 | 0.0005 | upper | 1.82e-06 | Rs 454,346 | Rs 42,682 | Rs 652,008 |
| A-R51 | 0.0365 | upper | 0.0373 | Rs 396,132 | Rs 52,070 | Rs 488,964 |
| B-R72 | 0.0030 | upper | 0.0028 | Rs 502,027 | Rs 54,134 | Rs 652,008 |
| B-R51 | 0.0205 | upper | 0.022 | Rs 373,874 | Rs 57,159 | Rs 488,964 |
| C-WALL15-R72-A | 0.6102 | upper | 0.608 | Rs 246,446 | Rs 67,563 | Rs 227,859 |
| C-WALL15-R72-P | 0.4968 | upper | 0.501 | Rs 460,474 | Rs 52,195 | Rs 460,280 |
| C-WALL15-R51-A | 0.1789 | upper | 0.178 | Rs 119,784 | Rs 81,120 | Rs 194,776 |
| C-WALL15-R51-P | 0.4963 | upper | 0.505 | Rs 322,738 | Rs 61,048 | Rs 321,953 |
| C-WALL30-R72-A | 0.4503 | upper | 0.464 | Rs 127,983 | Rs 66,896 | Rs 134,028 |
| C-WALL30-R72-P | 0.6242 | upper | 0.622 | Rs 300,326 | Rs 55,483 | Rs 283,115 |
| C-WALL30-R51-A | 0.2279 | upper | 0.223 | Rs 57,697 | Rs 77,273 | Rs 116,584 |
| C-WALL30-R51-P | 0.2854 | upper | 0.286 | Rs 192,571 | Rs 60,567 | Rs 226,779 |
| C-CROSS-R72-A | 0.6127 | upper | 0.614 | Rs 113,858 | Rs 64,067 | Rs 95,232 |
| C-CROSS-R72-P | 0.8246 | upper | 0.819 | Rs 193,553 | Rs 50,377 | Rs 147,639 |
| C-CROSS-R51-A | 0.7241 | upper | 0.725 | Rs 57,902 | Rs 76,694 | Rs 12,136 |
| C-CROSS-R51-P | 0.4553 | upper | 0.442 | Rs 125,916 | Rs 55,539 | Rs 133,997 |
| D-TGT-R72-P | 0.1324 | upper | 0.127 | Rs 396,540 | Rs 30,385 | Rs 431,272 |
| D-TGT-R72-V5 | 0.0005 | upper | 1.18e-16 | Rs 132,786 | Rs 17,640 | Rs 277,466 |
| D-TGT-R51-P | 0.0940 | upper | 0.0847 | Rs 163,338 | Rs 33,756 | Rs 209,715 |
| D-TGT-R51-V5 | 0.0005 | upper | 6.69e-27 | -Rs 124,595 | Rs 18,987 | Rs 78,085 |
| D-LQ-R0 | 0.9980 | lower | 0.997 | Rs 143,779 | Rs 40,085 | Rs 255,293 |
| D-LQ-R05 | 0.9650 | lower | 0.958 | Rs 199,854 | Rs 32,027 | Rs 255,293 |
| D-LQ-R2 | 0.1444 | upper | 0.148 | Rs 209,902 | Rs 32,303 | Rs 243,670 |
| D-LQ-NL0 | 0.1035 | lower | 0.104 | Rs 279,895 | Rs 15,129 | Rs 260,859 |
