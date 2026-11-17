# R193 summary: the live arms from Nov 2024 (current exits, picker, FB-R72-A10)

Research only; nothing in the app changes. Signal on the index; options bought; 1 lot; dated Zerodha charges and R17 slippage. Gate (PREREG section 6): vs the arm's own exits, higher net AND lower max drawdown in both W1 and W2, 40+ trades in each, net without the top 5 above A's net, twin p < 0.05, BH q <= 0.10 (m = 4,765).

## Outcome

- **R72 Breadth Donchian**: HELPS: R72-P (R72 + picker), R72-V4 (R72 picker + breakeven at +10% (V4))
- **R51 Donchian 20 + Supertrend**: HELPS: R51-P (R51 + picker)
- **Liquidity 15+5**: nothing passes the gate

- **R72**: the picker on its own made Rs 652,008 vs Rs 390,101 with the arm's own exits, and roughly halved the worst drawdown (Rs 99,586 vs Rs 199,077). Most of the extra money is in W1, where the picker was trained; in W2 (out-of-sample) it made Rs 152,620 vs Rs 150,061: about the same money with a much smaller drawdown.
  - The +10% breakeven (V4) changed the net by -Rs 29,547 over the span on top of the picker; the Rs 500 step lock that is live now (V5) took away Rs 264,383 compared with V4 (win rate up to 64%, but the average win falls to Rs 1,115). V5 made less than the arm's own exits with no picker in W2.
  - FB-R72-A10 on top of V5 changed the net by -Rs 55,165 (the variant fails the gate).
- **R51**: the picker on its own made Rs 488,964 vs Rs 191,394 with the arm's own exits, and roughly halved the worst drawdown (Rs 72,535 vs Rs 214,239). Most of the extra money is in W1, where the picker was trained; in W2 (out-of-sample) it made Rs 140,702 vs Rs 135,983: about the same money with a much smaller drawdown.
  - The +10% breakeven (V4) changed the net by -Rs 19,229 over the span on top of the picker; the Rs 500 step lock that is live now (V5) took away Rs 266,155 compared with V4 (win rate up to 62%, but the average win falls to Rs 1,050). V5 made less than the arm's own exits with no picker in W2.
  - FB-R72-A10 on top of V5 changed the net by Rs 15,116 (the variant fails the gate).
- **Liquidity 15+5**: its own exits made the most (Rs 255,293); the +10% breakeven made Rs 236,805 and today's rule (plus breakeven at +Rs 1,000) Rs 220,469. Liquidity barely made money in W2 under any exit (Rs 5,717 with its own exits).
- **Account stop**: at 1 lot almost every row breaks the -Rs 40k drawdown or -Rs 13k month rule somewhere; the picker shrinks the drawdowns but does not bring R72 / R51 inside the rule.
- None of this changes the app. The live rules are Boss's own choices (CLAUDE.md's fourth and fifth exceptions); R193 only reports what the data show.

## Reproduction first

| check | trades | net | published | gap | reproduced |
|---|---:|---:|---|---|---|
| R186 A R72 | 2,279 | Rs 300,426 | 2,281 / Rs 298,253 | -2 trades, +2,173 | NO |
| R186 BASE R72 | 1,068 | Rs 326,006 | 1,069 / Rs 324,629 | -1 trades, +1,377 | NO |
| R186 L500 R72 | 1,096 | Rs 214,487 | 1,097 / Rs 213,110 | -1 trades, +1,377 | NO |
| R186 A R51 | 3,081 | Rs 224,676 | 3,084 / Rs 221,238 | -3 trades, +3,438 | NO |
| R186 BASE R51 | 1,365 | Rs 252,282 | 1,367 / Rs 249,903 | -2 trades, +2,379 | NO |
| R186 L500 R51 | 1,428 | Rs 165,339 | 1,430 / Rs 162,959 | -2 trades, +2,380 | NO |
| R186 A LIQ | 688 | Rs 117,923 | 688 / Rs 117,923 | +0 trades, +0 | yes |
| R186 BASE LIQ | 688 | Rs 100,222 | 688 / Rs 100,222 | +0 trades, +0 | yes |
| R186 LBE1000 LIQ | 688 | Rs 98,239 | 688 / Rs 98,239 | +0 trades, +0 | yes |
| R190 D1 | 4,571 | Rs 390,101 | 4,571 / Rs 390,101 | +0 trades, +0 | yes |
| R190 D3 | 3,721 | Rs 478,594 | 3,721 / Rs 478,594 | +0 trades, +0 | yes |
| R190 L1 | 1,323 | Rs 255,293 | 1,323 / Rs 255,293 | +0 trades, +0 | yes |

**Reproduction result.** R190's era C (R72 own exits D1, R72 + FB-R72-A10 D3, Liquidity L1) reproduced to the rupee, which also checks R193's own A10 code. R186's Liquidity year (A, V4, V5) reproduced to the rupee. R186's R72 and R51 year rows are 1-3 trades and Rs 1,377-3,438 off. The whole gap sits in 14-19 Oct 2026: every month from Oct 2025 to Sep 2026 is identical to R186's published monthly nets, and R179's final (1 Apr - 13 Oct 2026: R72 A 1,200 trades / Rs 1,70,336; R51 A 1,668 / Rs 1,31,546) reproduces exactly. Cause: R186 read stores built to 19 Oct from the lake as it was then; R193 reads R190's stores, whose 2026 files are joined with the later 20 Oct - 13 Nov fill, so the last days before 19 Oct carry minutes R186 did not have. The engine is the same; the gap is data on 3-4 sessions, smaller than 1.2% of any row, and was not tuned away.

**Run note.** The span's first run was stopped by the session's 30-minute background limit after R72 and most of R51's pricing, before any results file was written (PREREG 7.5 allows that re-run). It was re-run unchanged with the same seed; R72's five rows and twins came out identical to the first run's log.

## Per variant and window (1 lot)

### R72 Breadth Donchian


**W1 1 Nov 2024 - 31 Mar 2026 (picker and A10 in-sample)**

| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |
|---|---:|---:|---:|---|---:|---|---:|---|---|
| A (own exits) | 3,176 | 46.8% | Rs 240,041 | Rs 1,699 / -Rs 1,352 | Rs 199,077 (breach) | -Rs 78,467 (2025-02) | Rs 183,587 |  |  |
| R72-P: R72 + picker | 1,530 | 54.0% | Rs 499,387 | Rs 1,973 / -Rs 1,611 | Rs 99,586 (breach) | -Rs 13,832 (2025-02) | Rs 443,649 |  |  |
| R72-V4: R72 picker + breakeven at +10% (V4) | 1,539 | 59.5% | Rs 457,476 | Rs 1,603 / -Rs 1,617 | Rs 94,500 (breach) | -Rs 15,785 (2025-03) | Rs 405,452 |  |  |
| R72-V5: R72 V4 + Rs 500 step lock (V5, live) | 1,595 | 65.4% | Rs 237,089 | Rs 1,074 / -Rs 1,600 | Rs 100,582 (breach) | -Rs 26,420 (2025-02) | Rs 211,858 |  |  |
| R72-V5A10: R72 V5 + FB-R72-A10 | 1,277 | 65.5% | Rs 186,197 | Rs 1,068 / -Rs 1,609 | Rs 96,797 (breach) | -Rs 32,874 (2025-02) | Rs 161,532 |  |  |

**W2 1 Apr - 13 Nov 2026 (picker out-of-sample; A10 unseen only from 14 Oct)**

| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |
|---|---:|---:|---:|---|---:|---|---:|---|---|
| A (own exits) | 1,395 | 45.8% | Rs 150,061 | Rs 1,738 / -Rs 1,271 | Rs 61,884 (breach) | -Rs 40,908 (2026-06) | Rs 91,318 |  |  |
| R72-P: R72 + picker | 726 | 48.5% | Rs 152,620 | Rs 1,893 / -Rs 1,374 | Rs 37,082 (breach) | -Rs 20,632 (2026-05) | Rs 96,333 |  |  |
| R72-V4: R72 picker + breakeven at +10% (V4) | 729 | 53.5% | Rs 164,985 | Rs 1,626 / -Rs 1,384 | Rs 33,418 (breach) | -Rs 18,110 (2026-05) | Rs 108,698 |  |  |
| R72-V5: R72 V4 + Rs 500 step lock (V5, live) | 757 | 60.8% | Rs 120,989 | Rs 1,208 / -Rs 1,464 | Rs 36,477 (breach) | -Rs 13,690 (2026-04) | Rs 70,982 |  |  |
| R72-V5A10: R72 V5 + FB-R72-A10 | 648 | 60.3% | Rs 116,717 | Rs 1,247 / -Rs 1,444 | Rs 41,466 (breach) | -Rs 18,679 (2026-04) | Rs 66,710 |  |  |

**total 1 Nov 2024 - 13 Nov 2026**

| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |
|---|---:|---:|---:|---|---:|---|---:|---|---|
| A (own exits) | 4,571 | 46.5% | Rs 390,101 | Rs 1,711 / -Rs 1,327 | Rs 199,077 (breach) | -Rs 78,467 (2025-02) | Rs 326,344 |  |  |
| R72-P: R72 + picker | 2,256 | 52.3% | Rs 652,008 | Rs 1,949 / -Rs 1,528 | Rs 99,586 (breach) | -Rs 20,632 (2026-05) | Rs 588,421 | HELPS |  |
| R72-V4: R72 picker + breakeven at +10% (V4) | 2,268 | 57.5% | Rs 622,461 | Rs 1,610 / -Rs 1,535 | Rs 94,500 (breach) | -Rs 18,110 (2026-05) | Rs 562,062 | HELPS |  |
| R72-V5: R72 V4 + Rs 500 step lock (V5, live) | 2,352 | 63.9% | Rs 358,078 | Rs 1,115 / -Rs 1,552 | Rs 100,582 (breach) | -Rs 26,420 (2025-02) | Rs 308,071 | no | W1: net 237,089 not above A's 240,041; W2: net 120,989 not above A's 150,061; total: net without top 5 308,071 not above A's 390,101 |
| R72-V5A10: R72 V5 + FB-R72-A10 | 1,925 | 63.8% | Rs 302,913 | Rs 1,125 / -Rs 1,548 | Rs 96,797 (breach) | -Rs 32,874 (2025-02) | Rs 252,907 | no | W1: net 186,197 not above A's 240,041; W2: net 116,717 not above A's 150,061; total: net without top 5 252,907 not above A's 390,101 |

### R51 Donchian 20 + Supertrend


**W1 1 Nov 2024 - 31 Mar 2026 (picker and A10 in-sample)**

| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |
|---|---:|---:|---:|---|---:|---|---:|---|---|
| A (own exits) | 4,336 | 44.4% | Rs 55,411 | Rs 1,737 / -Rs 1,364 | Rs 214,239 (breach) | -Rs 69,428 (2025-03) | -Rs 12,907 |  |  |
| R51-P: R51 + picker | 2,099 | 49.0% | Rs 348,262 | Rs 1,943 / -Rs 1,543 | Rs 72,535 (breach) | -Rs 36,937 (2025-06) | Rs 279,944 |  |  |
| R51-V4: R51 picker + breakeven at +10% (V4) | 2,114 | 54.9% | Rs 314,674 | Rs 1,546 / -Rs 1,550 | Rs 67,819 (breach) | -Rs 37,319 (2025-06) | Rs 254,043 |  |  |
| R51-V5: R51 V4 + Rs 500 step lock (V5, live) | 2,240 | 61.9% | Rs 124,786 | Rs 1,043 / -Rs 1,547 | Rs 85,937 (breach) | -Rs 36,762 (2025-03) | Rs 89,691 |  |  |
| R51-V5A10: R51 V5 + FB-R72-A10 | 1,829 | 62.5% | Rs 141,750 | Rs 1,039 / -Rs 1,525 | Rs 89,471 (breach) | -Rs 30,947 (2025-03) | Rs 107,654 |  |  |

**W2 1 Apr - 13 Nov 2026 (picker out-of-sample; A10 unseen only from 14 Oct)**

| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |
|---|---:|---:|---:|---|---:|---|---:|---|---|
| A (own exits) | 1,933 | 45.7% | Rs 135,983 | Rs 1,653 / -Rs 1,263 | Rs 81,683 (breach) | -Rs 54,587 (2026-06) | Rs 77,951 |  |  |
| R51-P: R51 + picker | 791 | 50.8% | Rs 140,702 | Rs 1,728 / -Rs 1,424 | Rs 38,799 (breach) | -Rs 22,978 (2026-06) | Rs 99,274 |  |  |
| R51-V4: R51 picker + breakeven at +10% (V4) | 795 | 56.4% | Rs 155,061 | Rs 1,462 / -Rs 1,440 | Rs 36,021 (breach) | -Rs 20,990 (2026-06) | Rs 114,891 |  |  |
| R51-V5: R51 V4 + Rs 500 step lock (V5, live) | 842 | 62.0% | Rs 78,794 | Rs 1,069 / -Rs 1,498 | Rs 45,825 (breach) | -Rs 29,871 (2026-04) | Rs 48,874 |  |  |
| R51-V5A10: R51 V5 + FB-R72-A10 | 721 | 61.9% | Rs 76,947 | Rs 1,071 / -Rs 1,458 | Rs 44,706 (breach) | -Rs 28,751 (2026-04) | Rs 47,026 |  |  |

**total 1 Nov 2024 - 13 Nov 2026**

| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |
|---|---:|---:|---:|---|---:|---|---:|---|---|
| A (own exits) | 6,269 | 44.8% | Rs 191,394 | Rs 1,710 / -Rs 1,333 | Rs 214,239 (breach) | -Rs 69,428 (2025-03) | Rs 118,071 |  |  |
| R51-P: R51 + picker | 2,890 | 49.5% | Rs 488,964 | Rs 1,883 / -Rs 1,511 | Rs 72,535 (breach) | -Rs 36,937 (2025-06) | Rs 418,880 | HELPS |  |
| R51-V4: R51 picker + breakeven at +10% (V4) | 2,909 | 55.3% | Rs 469,735 | Rs 1,522 / -Rs 1,521 | Rs 67,819 (breach) | -Rs 37,319 (2025-06) | Rs 405,967 | no | BH q 1 > 0.1 |
| R51-V5: R51 V4 + Rs 500 step lock (V5, live) | 3,082 | 61.9% | Rs 203,580 | Rs 1,050 / -Rs 1,534 | Rs 85,937 (breach) | -Rs 36,762 (2025-03) | Rs 161,487 | no | W2: net 78,794 not above A's 135,983; total: net without top 5 161,487 not above A's 191,394 |
| R51-V5A10: R51 V5 + FB-R72-A10 | 2,550 | 62.3% | Rs 218,696 | Rs 1,048 / -Rs 1,506 | Rs 89,471 (breach) | -Rs 30,947 (2025-03) | Rs 177,601 | no | W2: net 76,947 not above A's 135,983; total: net without top 5 177,601 not above A's 191,394 |

### Liquidity 15+5


**W1 1 Nov 2024 - 31 Mar 2026 (picker and A10 in-sample)**

| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |
|---|---:|---:|---:|---|---:|---|---:|---|---|
| A (own exits) | 908 | 43.1% | Rs 249,576 | Rs 1,955 / -Rs 995 | Rs 32,537 (breach) | -Rs 17,360 (2025-09) | Rs 168,041 |  |  |
| LIQ-V4: Liquidity + breakeven at +10% (V4) | 908 | 45.5% | Rs 232,230 | Rs 1,698 / -Rs 947 | Rs 28,622 | -Rs 10,140 (2025-09) | Rs 150,694 |  |  |
| LIQ-V5: Liquidity V4 + breakeven at +Rs 1,000 (live) | 908 | 50.0% | Rs 218,210 | Rs 1,380 / -Rs 899 | Rs 25,771 | -Rs 10,129 (2024-11) | Rs 137,356 |  |  |

**W2 1 Apr - 13 Nov 2026 (picker out-of-sample; A10 unseen only from 14 Oct)**

| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |
|---|---:|---:|---:|---|---:|---|---:|---|---|
| A (own exits) | 415 | 38.1% | Rs 5,717 | Rs 1,712 / -Rs 1,030 | Rs 61,062 (breach) | -Rs 37,821 (2026-04) | -Rs 56,814 |  |  |
| LIQ-V4: Liquidity + breakeven at +10% (V4) | 415 | 42.6% | Rs 4,575 | Rs 1,369 / -Rs 999 | Rs 67,497 (breach) | -Rs 37,773 (2026-04) | -Rs 57,956 |  |  |
| LIQ-V5: Liquidity V4 + breakeven at +Rs 1,000 (live) | 415 | 45.1% | Rs 2,259 | Rs 1,178 / -Rs 956 | Rs 59,441 (breach) | -Rs 32,670 (2026-04) | -Rs 55,447 |  |  |

**total 1 Nov 2024 - 13 Nov 2026**

| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |
|---|---:|---:|---:|---|---:|---|---:|---|---|
| A (own exits) | 1,323 | 41.5% | Rs 255,293 | Rs 1,885 / -Rs 1,007 | Rs 61,062 (breach) | -Rs 37,821 (2026-04) | Rs 168,104 |  |  |
| LIQ-V4: Liquidity + breakeven at +10% (V4) | 1,323 | 44.6% | Rs 236,805 | Rs 1,599 / -Rs 964 | Rs 67,497 (breach) | -Rs 37,773 (2026-04) | Rs 149,615 | no | W1: net 232,230 not above A's 249,576; W2: net 4,575 not above A's 5,717; W2: max drawdown 67,497 not below A's 61,062; total: net without top 5 149,615 not above A's 255,293; twin p 1.0000 >= 0.05; BH q 1 > 0.1 |
| LIQ-V5: Liquidity V4 + breakeven at +Rs 1,000 (live) | 1,323 | 48.5% | Rs 220,469 | Rs 1,321 / -Rs 918 | Rs 60,281 (breach) | -Rs 32,670 (2026-04) | Rs 133,279 | no | W1: net 218,210 not above A's 249,576; W2: net 2,259 not above A's 5,717; total: net without top 5 133,279 not above A's 255,293; twin p 1.0000 >= 0.05; BH q 1 > 0.1 |

## Twins and BH

| variant | twin p (2,000) | normal p | BH q (m = 4,765) | trades the exit changed | trades removed |
|---|---:|---:|---:|---:|---:|
| R72-P | 0.0005 | 6.56e-11 | 1.56e-07 | 0 | 2,315 |
| R72-V4 | 0.0005 | 6.48e-06 | 0.00441 | 549 | 2,323 |
| R72-V5 | 0.0005 | 7.55e-12 | 3.6e-08 | 2,298 | 2,448 |
| R72-V5A10 | 0.0005 | 2.55e-10 | 4.05e-07 | 2,298 | 2,875 |
| R51-P | 0.0005 | 6.32e-07 | 0.000502 | 0 | 3,379 |
| R51-V4 | 0.0050 | 0.00421 | 1 | 758 | 3,377 |
| R51-V5 | 0.0005 | 3.43e-08 | 3.27e-05 | 3,115 | 3,391 |
| R51-V5A10 | 0.0005 | 2.36e-08 | 2.81e-05 | 3,115 | 3,923 |
| LIQ-V4 | 1.0000 | 1 | 1 | 113 | 0 |
| LIQ-V5 | 1.0000 | 1 | 1 | 251 | 0 |

