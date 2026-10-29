# R131: ten ICT / SMC video strategies, each tested as stated

29 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss sent ten strategies from an ICT / SMC
video: Silver Bullet, Cameron's Model, CISD, CRT, AMD, IFVG, Turtle Soup, Order Block, Breaker Block and OTE.

## The answer: none of the ten works. 0 of 39 variants pass, and nothing goes on paper

**Each strategy was turned into fixed rules and tested on all five indices, buying the ATM option, Aug 2021 to 19 Oct
2026. Nothing made money reliably after charges and slippage.** There were 3 variants per strategy, plus 9 wick-only
versions Boss asked for on 29 Oct.

- **Design period (Aug 2021 - 15 Mar 2026, 1,144 sessions): 35 of 39 variants lost money** after costs: 26 of the 30
  and all 9 wick-only (the best, S3-W, lost Rs 5 a trade).
  - The design period has 127,630 trades in the 30 variants and 61,395 in the wick-only ones.
  - Taken together, the 30 variants lost Rs 1.31 crore on 1 lot.
  - The 4 that made money in design (S1-V2, S3-V3, S4-V2, S4-V3) failed other gates: too few trades, or no better
    than random entries.
- **Holdout (1 Apr - 19 Oct 2026, 137 sessions, read once): 24 of the 30 lost**, including S3-V3, the closest design
  candidate (407 trades, -Rs 165 a trade).
  - The few holdout winners had all lost in design (S2-V2: +Rs 452 a trade on 128 trades; S4-W: +Rs 516 on 95 trades,
    66% won). A reversal like that is luck, not a signal.
- **Before costs, on the index itself, the setups are a coin flip.** The average trade makes between -3 and +5 index
  points (-0.05 to +0.1 R) in nearly every variant. That is what random entries with the same stop and target give.
  The option's charges, spread and time decay (about Rs 100-200 a trade) then turn it into a loss.
- **Win rates are 24% to 62%.** The strategies with tight stops (IFVG, order blocks, OTE, 2R targets) win 25-40% of
  the time, and their wins are not big enough to make up for it.
- **Every variant breaks Boss's account stop** (-Rs 40k drawdown or -Rs 13k in a month) on 1 lot. Drawdowns run from
  -Rs 44k to -Rs 18.7 lakh across design and holdout. The only exception is S4-V2, with 34 trades in five
  years: too few to judge.

**Nothing passes, so no paper arm is proposed. Nothing changes in the app.**

## What was new, and what was already tested

| strategy | tested before? | what R131 adds |
|---|---|---|
| S1 Silver Bullet | the sweep + CHoCH idea: R90 (CHoCH/BOS), R91 (SMC sweep) | the 15-minute version as stated, and an Indian one-hour window (09:45-10:45) |
| S2 Cameron's Model | sweeps of the previous day's high / low were faded in R1 N7 and R52 | the trend filter plus the FVG reversal signal |
| S3 CISD | **never** | both readings: "a close above the last green candle's high" (as stated) and ICT's (above the drop run's open) |
| S4 CRT | **never** | the previous day's candle, and today's first 4-hour candle |
| S5 AMD | **never** | the consolidation box, the false break and the CISD |
| S6 IFVG | **never** (R62 used FVGs only as a filter) | an invalidated gap traded on its retest |
| S7 Turtle Soup | yes: R1 N7, R52, R61b traps, R91 | the CISD confirmation |
| S8 Order Block | yes: R62 (as a POC filter), R62b (EmreKb's indicator) | the block as its own entry, with a displacement rule and a break-of-structure variant |
| S9 Breaker Block | **never** | failed order blocks |
| S10 OTE | **never** | 0.618 / 0.705 pullbacks in a higher-high / higher-low trend |

- R131 reuses the sibling study R130's definitions of swings, sweeps, MSS (= CHoCH) and FVGs, and its option, cost
  and twin code. It does not re-test R130's sweep, MSS, FVG and engulfing setup.
- The Liquidity paper arm trades a level **break** on a close. These strategies fade a **wick** poke, the opposite
  trade. Nothing here touches that arm.

## Every variant (design period; years positive counts design + holdout, 2022-2026)

"q (all)" is Benjamini-Hochberg over all 39 variants together. "p vs twins" compares the rule's total with 200
random-entry twins per trade.

| strategy / variant | trades | win | avg win / avg loss | net per trade (2x slip) | total 1 lot | max drawdown | years + (2022-26) | p vs twins | q (all) | pts / trade, R |
|---|---|---|---|---|---|---|---|---|---|---|
| **S1-V1** Silver Bullet: 15-min sweep then CHoCH, all day | 920 | 52% | Rs 1,779 / -Rs 2,306 | -Rs 166 (-Rs 186) | -Rs 1.53 lakh | -Rs 193,584 | 0 of 5 | 1.000 | 1.000 | +2.7, -0.00R |
| **S1-V2** Silver Bullet: Indian window 09:45-10:45, 5-min | 107 | 62% | Rs 1,690 / -Rs 2,680 | Rs 16 (-Rs 6) | Rs 0.02 lakh | -Rs 36,831 | 2 of 5 | 0.930 | 1.000 | +15.6, +0.02R |
| **S1-V3** Silver Bullet: 15-min, fixed 2R | 1,039 | 40% | Rs 3,755 / -Rs 2,500 | -Rs 8 (-Rs 27) | -Rs 0.08 lakh | -Rs 254,105 | 2 of 5 | 1.000 | 1.000 | +13.8, +0.05R |
| **S2-V1** Cameron: PDH/PDL sweep with the trend, then FVG | 1,254 | 54% | Rs 1,553 / -Rs 1,918 | -Rs 44 (-Rs 64) | -Rs 0.55 lakh | -Rs 136,907 | 1 of 5 | 0.433 | 0.993 | +2.1, +0.01R |
| **S2-V2** Cameron: fixed 2R | 1,149 | 35% | Rs 3,687 / -Rs 2,168 | -Rs 100 (-Rs 120) | -Rs 1.14 lakh | -Rs 212,680 | 2 of 5 | 1.000 | 1.000 | +3.9, +0.04R |
| **S2-V3** Cameron: no trend filter | 2,212 | 54% | Rs 1,629 / -Rs 2,004 | -Rs 56 (-Rs 77) | -Rs 1.25 lakh | -Rs 279,350 | 3 of 5 | 0.363 | 0.885 | +1.2, +0.01R |
| **S3-V1** CISD as stated: close above the last green high | 3,082 | 57% | Rs 1,131 / -Rs 1,578 | -Rs 28 (-Rs 48) | -Rs 0.88 lakh | -Rs 158,466 | 1 of 5 | 0.562 | 1.000 | +3.0, +0.01R |
| **S3-V2** CISD, ICT's: close above the drop run's open | 4,004 | 58% | Rs 1,134 / -Rs 1,635 | -Rs 21 (-Rs 41) | -Rs 0.85 lakh | -Rs 150,023 | 1 of 5 | 0.070 | 0.209 | +1.8, +0.02R |
| **S3-V3** CISD as stated, fixed 2R | 2,871 | 41% | Rs 2,899 / -Rs 1,892 | Rs 71 (Rs 51) | Rs 2.03 lakh | -Rs 219,889 | 4 of 5 | 1.000 | 1.000 | +12.5, +0.08R |
| **S4-V1** CRT: previous day's range, 15-min CHoCH | 412 | 55% | Rs 1,598 / -Rs 2,239 | -Rs 116 (-Rs 135) | -Rs 0.48 lakh | -Rs 106,093 | 2 of 5 | 1.000 | 1.000 | +3.4, +0.08R |
| **S4-V2** CRT: today's first 4-hour candle, from 13:15 | 30 | 60% | Rs 2,110 / -Rs 1,653 | Rs 605 (Rs 587) | Rs 0.18 lakh | -Rs 6,206 | 2 of 5 | 0.905 | 1.000 | +33.3, +0.22R |
| **S4-V3** CRT: previous day's range, fixed 2R | 431 | 45% | Rs 3,129 / -Rs 2,361 | Rs 110 (Rs 91) | Rs 0.47 lakh | -Rs 110,405 | 3 of 5 | 1.000 | 1.000 | +13.8, +0.12R |
| **S5-V1** AMD: box, false break, CISD (ICT's) | 5,739 | 49% | Rs 883 / -Rs 1,176 | -Rs 165 (-Rs 185) | -Rs 9.44 lakh | -Rs 975,844 | 0 of 5 | 0.005 | 0.018 | -2.6, -0.05R |
| **S5-V2** AMD: fixed 2R | 4,790 | 34% | Rs 2,194 / -Rs 1,368 | -Rs 161 (-Rs 183) | -Rs 7.72 lakh | -Rs 854,317 | 0 of 5 | 0.005 | 0.018 | -0.5, -0.03R |
| **S5-V3** AMD: CISD as stated (last green high) | 4,700 | 51% | Rs 886 / -Rs 1,262 | -Rs 171 (-Rs 191) | -Rs 8.05 lakh | -Rs 837,467 | 0 of 5 | 0.184 | 0.479 | -2.9, -0.05R |
| **S6-V1** IFVG: first touch of the inverted gap | 14,952 | 29% | Rs 1,267 / -Rs 624 | -Rs 81 (-Rs 106) | -Rs 12.17 lakh | -Rs 1,235,807 | 0 of 5 | 0.005 | 0.018 | +0.6, +0.08R |
| **S6-V2** IFVG: touch then a close back out | 9,333 | 39% | Rs 1,145 / -Rs 921 | -Rs 117 (-Rs 140) | -Rs 10.92 lakh | -Rs 1,117,007 | 0 of 5 | 0.035 | 0.113 | -1.0, -0.01R |
| **S6-V3** IFVG: first touch, fixed 2R | 16,581 | 35% | Rs 938 / -Rs 636 | -Rs 78 (-Rs 102) | -Rs 12.94 lakh | -Rs 1,299,802 | 0 of 5 | 0.005 | 0.018 | +0.4, +0.02R |
| **S7-V1** Turtle Soup: 5-min swing sweep, CISD | 9,568 | 46% | Rs 1,325 / -Rs 1,455 | -Rs 165 (-Rs 186) | -Rs 15.78 lakh | -Rs 1,601,047 | 0 of 5 | 0.005 | 0.018 | -2.5, -0.04R |
| **S7-V2** Turtle Soup: PDH/PDL sweep, CISD | 3,040 | 46% | Rs 1,843 / -Rs 1,579 | -Rs 14 (-Rs 35) | -Rs 0.43 lakh | -Rs 210,200 | 3 of 5 | 0.005 | 0.018 | +5.2, +0.05R |
| **S7-V3** Turtle Soup: 5-min swing, fixed 2R | 7,447 | 34% | Rs 2,766 / -Rs 1,651 | -Rs 131 (-Rs 153) | -Rs 9.77 lakh | -Rs 1,017,045 | 0 of 5 | 0.005 | 0.018 | +1.9, -0.02R |
| **S8-V1** Order Block: first return to the block | 5,257 | 35% | Rs 1,816 / -Rs 1,145 | -Rs 97 (-Rs 119) | -Rs 5.10 lakh | -Rs 581,676 | 0 of 5 | 0.915 | 1.000 | +2.8, +0.07R |
| **S8-V2** Order Block: fixed 2R | 5,675 | 37% | Rs 1,879 / -Rs 1,197 | -Rs 63 (-Rs 85) | -Rs 3.57 lakh | -Rs 454,782 | 1 of 5 | 0.851 | 1.000 | +4.3, +0.05R |
| **S8-V3** Order Block: impulse must break structure | 3,659 | 33% | Rs 1,758 / -Rs 1,043 | -Rs 107 (-Rs 129) | -Rs 3.91 lakh | -Rs 432,346 | 0 of 5 | 1.000 | 1.000 | +1.7, +0.08R |
| **S9-V1** Breaker: first retest of the failed block | 2,498 | 39% | Rs 1,437 / -Rs 1,051 | -Rs 90 (-Rs 112) | -Rs 2.25 lakh | -Rs 268,746 | 0 of 5 | 0.945 | 1.000 | +0.9, +0.09R |
| **S9-V2** Breaker: fixed 2R | 2,659 | 37% | Rs 1,744 / -Rs 1,132 | -Rs 72 (-Rs 94) | -Rs 1.92 lakh | -Rs 246,328 | 1 of 5 | 0.995 | 1.000 | +2.3, +0.07R |
| **S9-V3** Breaker: retest then a close back out | 1,780 | 46% | Rs 1,203 / -Rs 1,368 | -Rs 178 (-Rs 199) | -Rs 3.16 lakh | -Rs 321,382 | 0 of 5 | 1.000 | 1.000 | -1.2, -0.00R |
| **S10-V1** OTE: trend pullback to 0.618 | 4,412 | 41% | Rs 1,571 / -Rs 1,374 | -Rs 161 (-Rs 183) | -Rs 7.11 lakh | -Rs 774,014 | 0 of 5 | 1.000 | 1.000 | +0.4, +0.01R |
| **S10-V2** OTE: trend pullback to 0.705 | 3,831 | 36% | Rs 1,646 / -Rs 1,143 | -Rs 145 (-Rs 167) | -Rs 5.55 lakh | -Rs 595,240 | 0 of 5 | 1.000 | 1.000 | +1.1, +0.02R |
| **S10-V3** OTE: 0.618, fixed 2R | 4,198 | 36% | Rs 2,050 / -Rs 1,380 | -Rs 160 (-Rs 182) | -Rs 6.72 lakh | -Rs 753,735 | 0 of 5 | 1.000 | 1.000 | +1.2, +0.01R |
| **S1-W** Silver Bullet wick-only: CHoCH by a wick | 1,163 | 53% | Rs 1,761 / -Rs 2,266 | -Rs 137 (-Rs 156) | -Rs 1.59 lakh | -Rs 205,272 | 0 of 5 | 1.000 | 1.000 | +2.9, +0.01R |
| **S2-W** Cameron wick-only: FVG low back above PDL | 1,146 | 54% | Rs 1,485 / -Rs 1,908 | -Rs 67 (-Rs 87) | -Rs 0.76 lakh | -Rs 138,536 | 2 of 5 | 0.796 | 1.000 | +1.1, -0.00R |
| **S3-W** CISD wick-only: a wick above the last green high | 5,761 | 57% | Rs 1,055 / -Rs 1,388 | -Rs 5 (-Rs 25) | -Rs 0.31 lakh | -Rs 197,115 | 2 of 5 | 0.005 | 0.018 | +2.2, +0.01R |
| **S4-W** CRT wick-only: a wick outside, CHoCH by a wick | 865 | 57% | Rs 1,708 / -Rs 2,447 | -Rs 60 (-Rs 79) | -Rs 0.52 lakh | -Rs 133,220 | 2 of 5 | 1.000 | 1.000 | +3.0, +0.05R |
| **S5-W** AMD wick-only: CISD by a wick | 7,126 | 47% | Rs 882 / -Rs 1,060 | -Rs 151 (-Rs 172) | -Rs 10.74 lakh | -Rs 1,090,328 | 0 of 5 | 0.005 | 0.018 | -1.5, -0.03R |
| **S6-W** IFVG wick-only: invalidated by a wick | 15,381 | 28% | Rs 1,256 / -Rs 609 | -Rs 95 (-Rs 119) | -Rs 14.63 lakh | -Rs 1,476,936 | 0 of 5 | 0.005 | 0.018 | -0.0, +0.03R |
| **S7-W** Turtle Soup wick-only: CISD by a wick | 11,755 | 44% | Rs 1,358 / -Rs 1,279 | -Rs 131 (-Rs 152) | -Rs 15.36 lakh | -Rs 1,591,385 | 0 of 5 | 0.005 | 0.018 | -0.8, -0.03R |
| **S8-W** Order Block wick-only: impulse wick to wick | 10,722 | 35% | Rs 1,759 / -Rs 1,099 | -Rs 106 (-Rs 129) | -Rs 11.41 lakh | -Rs 1,191,950 | 0 of 5 | 0.085 | 0.236 | +2.1, +0.05R |
| **S9-W** Breaker wick-only: fails on a wick | 7,476 | 39% | Rs 1,267 / -Rs 923 | -Rs 60 (-Rs 82) | -Rs 4.49 lakh | -Rs 498,987 | 0 of 5 | 0.706 | 1.000 | +1.2, +0.05R |

## The holdout (read once), with the account check on design + holdout

| variant | holdout trades | win | net per trade | total | p vs twins | design + holdout drawdown | worst month |
|---|---|---|---|---|---|---|---|
| S1-V1 | 122 | 56% | Rs 36 | Rs 4,427 | 1.000 | -Rs 193,584 | -Rs 55,426 (2024-08) |
| S1-V2 | 9 | 44% | -Rs 1,103 | -Rs 9,924 | 0.975 | -Rs 44,250 | -Rs 15,213 (2024-12) |
| S1-V3 | 141 | 44% | -Rs 238 | -Rs 33,526 | 1.000 | -Rs 254,105 | -Rs 84,594 (2024-08) |
| S2-V1 | 135 | 56% | Rs 61 | Rs 8,296 | 0.512 | -Rs 137,283 | -Rs 30,229 (2025-09) |
| S2-V2 | 128 | 38% | Rs 452 | Rs 57,906 | 0.199 | -Rs 212,680 | -Rs 49,618 (2024-06) |
| S2-V3 | 275 | 53% | Rs 83 | Rs 22,780 | 0.209 | -Rs 289,159 | -Rs 42,987 (2025-09) |
| S3-V1 | 432 | 51% | -Rs 234 | -Rs 101,156 | 0.975 | -Rs 209,070 | -Rs 35,625 (2024-05) |
| S3-V2 | 544 | 54% | -Rs 202 | -Rs 110,145 | 0.706 | -Rs 204,256 | -Rs 43,951 (2024-05) |
| S3-V3 | 407 | 37% | -Rs 165 | -Rs 67,107 | 1.000 | -Rs 219,889 | -Rs 83,996 (2025-02) |
| S4-V1 | 63 | 52% | -Rs 296 | -Rs 18,665 | 1.000 | -Rs 118,818 | -Rs 35,276 (2026-05) |
| S4-V2 | 4 | 75% | Rs 94 | Rs 375 | 0.960 | -Rs 6,206 | -Rs 5,277 (2026-03) |
| S4-V3 | 65 | 32% | -Rs 890 | -Rs 57,859 | 1.000 | -Rs 118,083 | -Rs 49,792 (2026-05) |
| S5-V1 | 815 | 52% | -Rs 126 | -Rs 102,663 | 0.015 | -Rs 1,064,728 | -Rs 64,194 (2024-01) |
| S5-V2 | 667 | 36% | -Rs 131 | -Rs 87,177 | 0.144 | -Rs 900,549 | -Rs 78,156 (2024-01) |
| S5-V3 | 668 | 53% | -Rs 125 | -Rs 83,170 | 0.119 | -Rs 909,202 | -Rs 57,162 (2025-02) |
| S6-V1 | 1992 | 24% | -Rs 198 | -Rs 394,208 | 0.637 | -Rs 1,625,100 | -Rs 103,705 (2024-06) |
| S6-V2 | 1195 | 34% | -Rs 207 | -Rs 247,285 | 0.517 | -Rs 1,359,494 | -Rs 96,519 (2024-06) |
| S6-V3 | 2228 | 32% | -Rs 164 | -Rs 365,609 | 0.463 | -Rs 1,668,992 | -Rs 97,239 (2025-03) |
| S7-V1 | 1223 | 45% | -Rs 207 | -Rs 252,799 | 0.015 | -Rs 1,844,000 | -Rs 128,344 (2026-06) |
| S7-V2 | 394 | 40% | -Rs 27 | -Rs 10,666 | 0.020 | -Rs 257,422 | -Rs 68,069 (2026-06) |
| S7-V3 | 1019 | 31% | -Rs 427 | -Rs 435,267 | 0.537 | -Rs 1,444,654 | -Rs 143,937 (2026-05) |
| S8-V1 | 705 | 33% | -Rs 211 | -Rs 149,078 | 0.955 | -Rs 672,620 | -Rs 73,518 (2025-02) |
| S8-V2 | 740 | 32% | -Rs 280 | -Rs 207,247 | 1.000 | -Rs 572,213 | -Rs 82,425 (2025-02) |
| S8-V3 | 450 | 33% | -Rs 156 | -Rs 70,196 | 0.950 | -Rs 482,370 | -Rs 59,214 (2024-12) |
| S9-V1 | 329 | 40% | -Rs 11 | -Rs 3,574 | 0.269 | -Rs 268,746 | -Rs 46,490 (2025-02) |
| S9-V2 | 341 | 37% | -Rs 15 | -Rs 5,076 | 0.408 | -Rs 246,328 | -Rs 51,271 (2025-01) |
| S9-V3 | 236 | 49% | Rs 39 | Rs 9,142 | 0.413 | -Rs 327,070 | -Rs 40,771 (2025-02) |
| S10-V1 | 581 | 41% | -Rs 284 | -Rs 164,964 | 1.000 | -Rs 887,759 | -Rs 59,821 (2025-03) |
| S10-V2 | 491 | 34% | -Rs 260 | -Rs 127,451 | 0.985 | -Rs 686,536 | -Rs 55,100 (2026-07) |
| S10-V3 | 556 | 37% | -Rs 255 | -Rs 141,824 | 0.960 | -Rs 829,237 | -Rs 54,939 (2024-12) |
| S1-W | 155 | 55% | Rs 141 | Rs 21,915 | 1.000 | -Rs 205,272 | -Rs 43,862 (2025-05) |
| S2-W | 120 | 59% | Rs 174 | Rs 20,879 | 0.682 | -Rs 138,536 | -Rs 31,730 (2025-09) |
| S3-W | 791 | 52% | -Rs 254 | -Rs 200,799 | 0.781 | -Rs 287,390 | -Rs 49,193 (2026-07) |
| S4-W | 95 | 66% | Rs 516 | Rs 49,048 | 0.985 | -Rs 140,729 | -Rs 20,832 (2024-03) |
| S5-W | 987 | 47% | -Rs 154 | -Rs 152,453 | 0.005 | -Rs 1,234,633 | -Rs 71,832 (2025-06) |
| S6-W | 1996 | 25% | -Rs 174 | -Rs 346,408 | 0.229 | -Rs 1,820,570 | -Rs 95,472 (2024-10) |
| S7-W | 1512 | 41% | -Rs 194 | -Rs 293,085 | 0.005 | -Rs 1,869,141 | -Rs 120,500 (2026-06) |
| S8-W | 1331 | 33% | -Rs 228 | -Rs 303,490 | 0.905 | -Rs 1,456,632 | -Rs 77,744 (2025-05) |
| S9-W | 966 | 38% | -Rs 89 | -Rs 86,119 | 0.338 | -Rs 591,238 | -Rs 50,009 (2026-04) |

The wick-only (W) rows were read after the V1-V3 holdout read, so that period was not unseen to this study when
they were read. That is labelled; it changes nothing, because every W variant had already failed in design.

## The closest few, by index and by year (design + holdout, 1 lot)

| variant | 2022 | 2023 | 2024 | 2025 | 2026 to 19 Oct | by index (trades, net a trade) |
|---|---|---|---|---|---|---|
| S3-V3 CISD as stated, 2R | -Rs 66.5k | +Rs 55.1k | +Rs 122.3k | +Rs 25.5k | +Rs 10.7k | BANKNIFTY 790 / +Rs 109; FINNIFTY 556 / +Rs 154; SENSEX 528 / +Rs 119; NIFTY 875 / -Rs 79; MIDCPNIFTY 529 / -Rs 56 |
| S4-V3 CRT daily, 2R | +Rs 16.9k | +Rs 21.0k | +Rs 44.6k | -Rs 52.5k | -Rs 45.2k | NIFTY 130 / +Rs 349; MIDCPNIFTY 73 / +Rs 470; FINNIFTY 88 / +Rs 144; BANKNIFTY 126 / -Rs 503; SENSEX 79 / -Rs 499 |
| S3-W CISD wick-only | -Rs 43.1k | -Rs 65.5k | +Rs 4.6k | +Rs 39.1k | -Rs 159.7k | FINNIFTY 1,123 / +Rs 6; the other four lose |
| S4-W CRT wick-only | +Rs 44.0k | -Rs 35.0k | -Rs 15.8k | -Rs 37.5k | +Rs 22.8k | NIFTY 219 / +Rs 173; MIDCPNIFTY 148 / +Rs 133; BANKNIFTY 257 / -Rs 178; SENSEX 155 / -Rs 142 |
| S7-V2 Turtle Soup on PDH/PDL | +Rs 4.8k | +Rs 21.8k | +Rs 87.1k | -Rs 128.9k | -Rs 28.3k | BANKNIFTY 921 / +Rs 41; FINNIFTY 589 / +Rs 15; the other three lose |

- **S3-V3 was the nearest miss.** In design it made +Rs 71 a trade on 2,871 trades (+Rs 51 at 2x slippage), and it
  was positive in all 4 walk-forward folds.
  - It failed the twin gate (p 1.00, q 1.00). It did beat the after-signal twins (p 0.005, q 0.03; see the notes).
  - Then it lost in the holdout: 407 trades, 37% won, -Rs 165 a trade, -Rs 67k, and its worst month was -Rs 84k
    (Feb 2025).
  - The index breakdown is mixed (BANKNIFTY, FINNIFTY and SENSEX up; NIFTY and MIDCPNIFTY down). That is the pattern
    of noise, not of an edge.
- **No index is good across strategies.** MIDCPNIFTY is positive in some variants and negative in others, as in R62.

## Boss's 29 Oct notes, applied

1. **"65% is only for new strategies created by Jarvis."** These are Boss's own strategies, so the 65% win-rate gate
   was dropped (PREREG N3). It changes nothing: every variant fails the design gates first.
2. **"Use wick to wick candles, not only opening and closing candles."**
   - Every level, sweep, gap, zone, stop, target and exit in R131 was already read from the wicks (highs and lows).
   - Closes were still used to *confirm* some signals: the CHoCH, the CISD, CRT's outside close, IFVG and breaker
     invalidation, and the impulse size. So 9 wick-only variants were added (PREREG N4, before their code). Each
     confirmation becomes the first minute whose wick crosses the level, and entry is the next minute.
   - **They did not help.** In design, all 9 lost after costs (-Rs 5 to -Rs 151 a trade, 865 to 15,381 trades each).
     None passes.
   - Candle colour (green / red) is kept only where the idea is a colour ("the last green candle", "the last opposite
     candle").

## How it was done

- **Pre-registration:** `research/hunt/r131_ict_ten/PREREG.md`, committed before any data was read (`c9ca0d1`).
  Dated notes were added later, each before the numbers it affects:
  - N1: implementation choices, before any run;
  - N2: the after-signal twins, after the design run and before the holdout;
  - N3 and N4: Boss's two notes, after the holdout.
  - The design results were committed (`929a664`) before the one holdout read. A lock file refuses a second read.
- **Signal source: the index spot, always.** Swings, sweeps, CHoCH, FVGs, CISD, blocks, fib levels, stops and targets
  are all read on the index's wicks. The trade buys the ATM CE / PE of the nearest expiry that does not expire that
  day (the monthly contract on a weekly expiry day). No option-chain input is used.
- **Definitions (all in PREREG):**
  - swings: a pivot with 3 bars each side, known only after the third bar closes, from today and the previous 4
    sessions;
  - sweep: a wick 1 tick beyond the level;
  - CHoCH: a close beyond the recent swing within 12 bars;
  - FVG: a three-bar gap ≥ max(2 ticks, 0.01%);
  - impulse: ≥ 2 ATR within 3 bars;
  - AMD box: 12 bars within 2.5 ATR;
  - OTE: 0.618 / 0.705 of a higher-high / higher-low leg ≥ 2 ATR.
- **Stops and targets.** Where the video gives none, the default is used. The stop is 0.02% beyond the sweep's or
  zone's extreme. The target is the nearest swing high (low) not yet taken, with a fixed 2R variant.
- **Trading rules.** Entries 09:15-15:00, out by 15:20, one trade at a time per index per variant. CRT and Cameron read
  only completed daily / 4-hour candles.
- **Money.** 1 lot at today's lots, dated charges, liquidity-aware slippage (also at 2x), with the R123 lake cleaning.
- **Gates (R130's):**
  - design: ≥ 300 trades; net > 0 at 1x and 2x slippage; ≥ 3 of 4 walk-forward folds positive (2023, 2024, 2025,
    2026 Q1); BH q ≤ 0.10;
  - holdout: ≥ 50 trades and net > 0;
  - account: drawdown above -Rs 40k and no month below -Rs 13k.
- **Code and tests:** `research/hunt/r131_ict_ten/` (`r131.py`, `test_r131.py`, 32 tests).
  - Every strategy's bullish trigger is tested on hand-built candles.
  - The mirror, targets, sequencer, holdout lock and vectorised twins are tested.
  - A look-ahead guard covers all 39 variants: each setup is found again when every later minute is deleted.
- **Results:** `results/design.json`, `holdout.json`, `design_w.json`, `holdout_w.json`, `combined.json`. Trade
  lists are on Drive in `derived/r131_ict_ten/`, not in git. The PREREG named `derived/r131/`, but that folder
  already holds an older, unrelated study's files.
- **Entries:** `research/experiments/entries/r131-*.yaml`, one per variant, all `rejected`.

## Notes

1. **The random twins are tilted against reversal rules.** They are the same design as R127 and R130: a random entry
   at any minute from 09:20 to 15:00, on the same day and side.
   - A reversal setup picks its side *after* the move that made it. A twin that enters earlier can ride that very move.
     For example, S1-V1's twins averaged +Rs 5.87 lakh against the rule's -Rs 1.53 lakh.
   - So "p 1.00" on several rows mostly measures this tilt.
   - An after-signal twin, which enters at random only after the rule's own entry, was added before the holdout (N2) as
     a descriptive check, not a gate. It still finds nothing that also makes money: the variants that beat it (S3,
     S6, S9-W and others) lose after costs or in the holdout.
2. **"Strong", "rapid" and "consolidation" are not exact in the video.** The 2 ATR, 2.5 ATR and 12-bar settings were
   fixed before any data was read and were never tuned. With a coin-flip edge before costs on 189,000 design trades,
   other settings would change trade counts, not the conclusion.
3. **This agrees with earlier work:** R62 (0 of 32), R62b (0 of 16), R90/R91 (CHoCH and sweeps failed) and R130
   (sweep, MSS and FVG: every variant lost in design).
