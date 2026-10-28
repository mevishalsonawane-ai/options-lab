# R120 results: the time machine's verdict as a pre-entry gate

BH family: m = 9,300,113 (every logged test to 2026-10-27: 9,300,075, plus R120's 38). Twins: 2,000 random skips of the same number of trades. Skipped trades earn Rs 0. 1 lot, each arm's own charges and fills. Signal source: each arm's own (the underlying; Hero picks its strike by the option's price). The time machine reads only the underlying's 60 sessions before the entry day.

**Passed every gate: none.**

## Gates per arm and rule (final p and q)

| arm | rule | final trades kept / all | net before | net after | twin 95th pct | p | q (all) | q (R120) | passed | fails |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| R72 Breadth Donchian | SKIP_AGAINST | 1140 / 1202 | Rs 171,366 | Rs 182,527 | Rs 187,340 | 0.095 | 1.00 | 1.00 | no | final: not above the random twin's 95th percentile (p 0.095); BH q 1.000 > 0.1 |
| R72 Breadth Donchian | EDGE_ONLY | 54 / 1202 | Rs 171,366 | -Rs 2,398 | Rs 33,349 | 0.751 | 1.00 | 1.00 | no | design: net not above the arm's own; wf: net not above the arm's own; final: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.751); BH q 1.000 > 0.1 |
| R51 Donchian 20 + Supertrend | SKIP_AGAINST | 1597 / 1670 | Rs 134,743 | Rs 157,203 | Rs 154,993 | 0.039 | 1.00 | 1.00 | no | BH q 1.000 > 0.1 |
| R51 Donchian 20 + Supertrend | EDGE_ONLY | 76 / 1670 | Rs 134,743 | -Rs 393 | Rs 34,637 | 0.642 | 1.00 | 1.00 | no | design: net not above the arm's own; wf: net not above the arm's own; final: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.642); BH q 1.000 > 0.1 |
| R52 Open outside value | SKIP_AGAINST | 274 / 293 | Rs 48,612 | Rs 42,786 | Rs 63,976 | 0.588 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.588); BH q 1.000 > 0.1 |
| R52 Open outside value | EDGE_ONLY | 18 / 293 | Rs 48,612 | Rs 168 | Rs 21,317 | 0.616 | 1.00 | 1.00 | no | design: net not above the arm's own; wf: net not above the arm's own; final: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.616); BH q 1.000 > 0.1; final: 18 trades kept < 40 |
| Liquidity 15+5 | SKIP_AGAINST | 334 / 357 | Rs 11,155 | Rs 20,858 | Rs 25,050 | 0.150 | 1.00 | 1.00 | no | design: net not above the arm's own; wf: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.150); BH q 1.000 > 0.1 |
| Liquidity 15+5 | EDGE_ONLY | 3 / 357 | Rs 11,155 | -Rs 4,740 | Rs 6,581 | 0.971 | 1.00 | 1.00 | no | design: net not above the arm's own; wf: net not above the arm's own; final: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.971); BH q 1.000 > 0.1; final: 3 trades kept < 40 |
| Solo 2.0 | SKIP_AGAINST | 112 / 127 | Rs 99,735 | Rs 88,582 | Rs 104,757 | 0.468 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.468); BH q 1.000 > 0.1 |
| Solo 2.0 | EDGE_ONLY | 9 / 127 | Rs 99,735 | -Rs 5,296 | Rs 21,564 | 0.926 | 1.00 | 1.00 | no | design: net not above the arm's own; wf: net not above the arm's own; final: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.926); BH q 1.000 > 0.1; final: 9 trades kept < 40 |
| Hero Rs 1,000 ticket | SKIP_AGAINST | 28 / 107 | Rs 1,559 | -Rs 1,593 | Rs 8,062 | 0.521 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.521); BH q 1.000 > 0.1; final: 28 trades kept < 40 |
| Hero Rs 1,000 ticket | EDGE_ONLY | 0 / 107 | Rs 1,559 | Rs 0 | Rs 0 | 1.000 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1; final: 0 trades kept < 40 |
| ORB | SKIP_AGAINST | 128 / 128 | Rs 2,876 | Rs 2,876 | Rs 2,876 | 1.000 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1 |
| ORB | EDGE_ONLY | 0 / 128 | Rs 2,876 | Rs 0 | Rs 0 | 1.000 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1; final: 0 trades kept < 40 |
| ORB Fresh | SKIP_AGAINST | 128 / 128 | Rs 2,876 | Rs 2,876 | Rs 2,876 | 1.000 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1 |
| ORB Fresh | EDGE_ONLY | 0 / 128 | Rs 2,876 | Rs 0 | Rs 0 | 1.000 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1; final: 0 trades kept < 40 |
| ORB Sweep | SKIP_AGAINST | 174 / 176 | -Rs 54,421 | -Rs 51,424 | -Rs 51,338 | 0.150 | 1.00 | 1.00 | no | final: not above the random twin's 95th percentile (p 0.150); BH q 1.000 > 0.1 |
| ORB Sweep | EDGE_ONLY | 0 / 176 | -Rs 54,421 | Rs 0 | Rs 0 | 1.000 | 1.00 | 1.00 | no | final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1; final: 0 trades kept < 40 |
| Range Fade | SKIP_AGAINST | 207 / 207 | -Rs 29,811 | -Rs 29,811 | -Rs 29,811 | 1.000 | 1.00 | 1.00 | no | design: net not above the arm's own; wf: net not above the arm's own; final: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1 |
| Range Fade | EDGE_ONLY | 0 / 207 | -Rs 29,811 | Rs 0 | Rs 0 | 1.000 | 1.00 | 1.00 | no | final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1; final: 0 trades kept < 40 |
| VIX divergence | SKIP_AGAINST | 27 / 27 | -Rs 5,756 | -Rs 5,756 | -Rs 5,756 | 1.000 | 1.00 | 1.00 | no | design: net not above the arm's own; wf: net not above the arm's own; final: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1; final: 27 trades kept < 40 |
| VIX divergence | EDGE_ONLY | 0 / 27 | -Rs 5,756 | Rs 0 | Rs 0 | 1.000 | 1.00 | 1.00 | no | design: net not above the arm's own; wf: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1; final: 0 trades kept < 40 |
| Pine Supertrend+EMA 5m | SKIP_AGAINST | 178 / 182 | -Rs 52,573 | -Rs 54,061 | -Rs 39,549 | 0.675 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 0.675); BH q 1.000 > 0.1 |
| Pine Supertrend+EMA 5m | EDGE_ONLY | 0 / 182 | -Rs 52,573 | Rs 0 | Rs 0 | 1.000 | 1.00 | 1.00 | no | wf: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1; final: 0 trades kept < 40 |
| Pine Supertrend+EMA 15m | SKIP_AGAINST | 75 / 75 | -Rs 83,469 | -Rs 83,469 | -Rs 83,469 | 1.000 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1 |
| Pine Supertrend+EMA 15m | EDGE_ONLY | 1 / 75 | -Rs 83,469 | -Rs 368 | Rs 5,462 | 0.527 | 1.00 | 1.00 | no | final: not above the random twin's 95th percentile (p 0.527); BH q 1.000 > 0.1; final: 1 trades kept < 40 |
| Pine Matrix 5m | SKIP_AGAINST | 172 / 173 | Rs 39,116 | Rs 39,386 | Rs 43,166 | 0.524 | 1.00 | 1.00 | no | final: not above the random twin's 95th percentile (p 0.524); BH q 1.000 > 0.1 |
| Pine Matrix 5m | EDGE_ONLY | 0 / 173 | Rs 39,116 | Rs 0 | Rs 0 | 1.000 | 1.00 | 1.00 | no | final: net not above the arm's own; final: not above the random twin's 95th percentile (p 1.000); BH q 1.000 > 0.1; final: 0 trades kept < 40 |

## Every period

### R72 Breadth Donchian

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 3071 | 172 | Rs 216,929 | Rs 273,222 | 47% | 47% | Rs 199,077 | Rs 169,427 | 0.007 |
| walk-forward | SKIP_AGAINST | 2238 | 135 | Rs 335,882 | Rs 374,736 | 49% | 49% | Rs 86,529 | Rs 66,553 | 0.003 |
| final | SKIP_AGAINST | 1202 | 62 | Rs 171,366 | Rs 182,527 | 46% | 46% | Rs 61,884 | Rs 61,333 | 0.0945 |
| design | EDGE_ONLY | 3071 | 2900 | Rs 216,929 | Rs 45,962 | 47% | 44% | Rs 199,077 | Rs 39,666 | 0.1054 |
| walk-forward | EDGE_ONLY | 2238 | 2099 | Rs 335,882 | Rs 54,807 | 49% | 47% | Rs 86,529 | Rs 29,203 | 0.078 |
| final | EDGE_ONLY | 1202 | 1148 | Rs 171,366 | -Rs 2,398 | 46% | 43% | Rs 61,884 | Rs 15,266 | 0.7506 |

Verdicts (n, win rate, mean net per trade):

- design: against 172 (44%, -Rs 327); coin flip 2601 (47%, Rs 73); edge 171 (44%, Rs 269); unknown 127 (55%, Rs 299)
- walk-forward: against 135 (42%, -Rs 288); coin flip 1854 (49%, Rs 149); edge 139 (48%, Rs 394); unknown 110 (58%, Rs 395)
- final: against 62 (42%, -Rs 180); coin flip 1059 (47%, Rs 184); edge 54 (43%, -Rs 44); unknown 27 (22%, -Rs 354)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 3055, Brier 0.2611 vs 0.2489 for the constant design win rate 47%; correlation 0.0047; bins 0-10% n 11 pred 1% actual 55%, 10-20% n 17 pred 17% actual 47%, 20-30% n 44 pred 26% actual 61%, 30-40% n 280 pred 37% actual 45%, 40-50% n 1101 pred 46% actual 43%, 50-60% n 1226 pred 54% actual 49%, 60-70% n 297 pred 64% actual 49%, 70-80% n 36 pred 74% actual 50%, 80-90% n 14 pred 83% actual 36%, 90-100% n 29 pred 99% actual 31%
- final: n 1199, Brier 0.2615 vs 0.2484 for the constant design win rate 47%; correlation -0.0349; bins 0-10% n 2 pred 0% actual 0%, 10-20% n 3 pred 14% actual 0%, 20-30% n 17 pred 26% actual 24%, 30-40% n 95 pred 37% actual 57%, 40-50% n 471 pred 46% actual 48%, 50-60% n 497 pred 54% actual 45%, 60-70% n 88 pred 64% actual 41%, 70-80% n 17 pred 74% actual 24%, 80-90% n 7 pred 84% actual 43%, 90-100% n 2 pred 100% actual 50%

### R51 Donchian 20 + Supertrend

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 4208 | 207 | Rs 50,425 | Rs 105,946 | 44% | 45% | Rs 214,239 | Rs 212,225 | 0.0265 |
| walk-forward | SKIP_AGAINST | 3044 | 150 | Rs 213,748 | Rs 260,678 | 46% | 46% | Rs 94,176 | Rs 84,279 | 0.0085 |
| final | SKIP_AGAINST | 1670 | 73 | Rs 134,743 | Rs 157,203 | 45% | 45% | Rs 81,683 | Rs 68,659 | 0.039 |
| design | EDGE_ONLY | 4208 | 3992 | Rs 50,425 | -Rs 21,025 | 44% | 39% | Rs 214,239 | Rs 62,884 | 0.7846 |
| walk-forward | EDGE_ONLY | 3044 | 2872 | Rs 213,748 | Rs 10,499 | 46% | 42% | Rs 94,176 | Rs 31,974 | 0.5217 |
| final | EDGE_ONLY | 1670 | 1594 | Rs 134,743 | -Rs 393 | 45% | 46% | Rs 81,683 | Rs 23,926 | 0.6422 |

Verdicts (n, win rate, mean net per trade):

- design: against 207 (42%, -Rs 268); coin flip 3653 (45%, Rs 31); edge 216 (39%, -Rs 97); unknown 132 (48%, Rs 114)
- walk-forward: against 150 (40%, -Rs 313); coin flip 2623 (46%, Rs 87); edge 172 (42%, Rs 61); unknown 99 (50%, Rs 216)
- final: against 73 (41%, -Rs 308); coin flip 1491 (45%, Rs 110); edge 76 (46%, -Rs 5); unknown 30 (23%, -Rs 241)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 4197, Brier 0.2595 vs 0.2469 for the constant design win rate 44%; correlation 0.0044; bins 0-10% n 10 pred 1% actual 50%, 10-20% n 20 pred 16% actual 30%, 20-30% n 51 pred 26% actual 49%, 30-40% n 381 pred 37% actual 45%, 40-50% n 1622 pred 46% actual 43%, 50-60% n 1674 pred 54% actual 46%, 60-70% n 338 pred 63% actual 45%, 70-80% n 57 pred 73% actual 42%, 80-90% n 16 pred 84% actual 38%, 90-100% n 28 pred 100% actual 39%
- final: n 1666, Brier 0.2576 vs 0.2474 for the constant design win rate 44%; correlation -0.0039; bins 0-10% n 5 pred 0% actual 20%, 10-20% n 1 pred 14% actual 0%, 20-30% n 18 pred 26% actual 33%, 30-40% n 125 pred 37% actual 46%, 40-50% n 670 pred 46% actual 45%, 50-60% n 693 pred 54% actual 46%, 60-70% n 133 pred 63% actual 42%, 70-80% n 13 pred 74% actual 31%, 80-90% n 5 pred 83% actual 80%, 90-100% n 3 pred 100% actual 33%

### R52 Open outside value

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 706 | 84 | Rs 290,573 | Rs 338,332 | 56% | 58% | Rs 82,713 | Rs 43,131 | 0.0025 |
| walk-forward | SKIP_AGAINST | 529 | 42 | Rs 247,057 | Rs 264,628 | 57% | 59% | Rs 49,847 | Rs 40,692 | 0.0275 |
| final | SKIP_AGAINST | 293 | 19 | Rs 48,612 | Rs 42,786 | 52% | 51% | Rs 47,194 | Rs 46,792 | 0.5877 |
| design | EDGE_ONLY | 706 | 641 | Rs 290,573 | Rs 17,596 | 56% | 49% | Rs 82,713 | Rs 47,108 | 0.6487 |
| walk-forward | EDGE_ONLY | 529 | 490 | Rs 247,057 | Rs 29,867 | 57% | 62% | Rs 49,847 | Rs 24,195 | 0.2679 |
| final | EDGE_ONLY | 293 | 275 | Rs 48,612 | Rs 168 | 52% | 39% | Rs 47,194 | Rs 8,997 | 0.6162 |

Verdicts (n, win rate, mean net per trade):

- design: against 84 (42%, -Rs 569); coin flip 544 (58%, Rs 588); edge 65 (49%, Rs 271); unknown 13 (62%, Rs 54)
- walk-forward: against 42 (43%, -Rs 418); coin flip 435 (58%, Rs 538); edge 39 (62%, Rs 766); unknown 13 (62%, Rs 54)
- final: against 19 (63%, Rs 307); coin flip 253 (52%, Rs 179); edge 18 (39%, Rs 9); unknown 3 (33%, -Rs 873)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 706, Brier 0.2604 vs 0.2468 for the constant design win rate 56%; correlation 0.0471; bins 0-10% n 5 pred 6% actual 20%, 10-20% n 8 pred 14% actual 38%, 20-30% n 31 pred 27% actual 58%, 30-40% n 92 pred 36% actual 50%, 40-50% n 228 pred 46% actual 58%, 50-60% n 233 pred 54% actual 58%, 60-70% n 83 pred 64% actual 49%, 70-80% n 18 pred 74% actual 61%, 80-90% n 4 pred 85% actual 25%, 90-100% n 4 pred 96% actual 75%
- final: n 293, Brier 0.2642 vs 0.2515 for the constant design win rate 56%; correlation -0.0645; bins 0-10% n 1 pred 9% actual 0%, 10-20% n 1 pred 14% actual 100%, 20-30% n 5 pred 28% actual 80%, 30-40% n 22 pred 37% actual 50%, 40-50% n 110 pred 46% actual 54%, 50-60% n 125 pred 54% actual 50%, 60-70% n 22 pred 63% actual 46%, 70-80% n 6 pred 76% actual 50%, 80-90% n 1 pred 83% actual 0%

### Liquidity 15+5

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 881 | 59 | Rs 257,249 | Rs 221,709 | 44% | 44% | Rs 32,537 | Rs 25,061 | 0.8421 |
| walk-forward | SKIP_AGAINST | 657 | 42 | Rs 238,064 | Rs 206,702 | 44% | 44% | Rs 23,139 | Rs 23,155 | 0.8471 |
| final | SKIP_AGAINST | 357 | 23 | Rs 11,155 | Rs 20,858 | 37% | 38% | Rs 61,062 | Rs 50,561 | 0.1504 |
| design | EDGE_ONLY | 881 | 877 | Rs 257,249 | Rs 8,253 | 44% | 75% | Rs 32,537 | Rs 3,828 | 0.094 |
| walk-forward | EDGE_ONLY | 657 | 654 | Rs 238,064 | Rs 12,082 | 44% | 100% | Rs 23,139 | Rs 0 | 0.04 |
| final | EDGE_ONLY | 357 | 354 | Rs 11,155 | -Rs 4,740 | 37% | 0% | Rs 61,062 | Rs 4,740 | 0.971 |

Verdicts (n, win rate, mean net per trade):

- design: against 59 (41%, Rs 602); coin flip 818 (44%, Rs 261); edge 4 (75%, Rs 2,063)
- walk-forward: against 42 (45%, Rs 747); coin flip 612 (44%, Rs 318); edge 3 (100%, Rs 4,027)
- final: against 23 (26%, -Rs 422); coin flip 331 (38%, Rs 77); edge 3 (0%, -Rs 1,580)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 881, Brier 0.3096 vs 0.2459 for the constant design win rate 44%; correlation 0.0128; bins 0-10% n 222 pred 4% actual 36%, 10-20% n 180 pred 15% actual 53%, 20-30% n 173 pred 25% actual 45%, 30-40% n 166 pred 35% actual 46%, 40-50% n 122 pred 45% actual 37%, 50-60% n 18 pred 52% actual 44%
- final: n 357, Brier 0.2759 vs 0.2378 for the constant design win rate 44%; correlation -0.0113; bins 0-10% n 79 pred 5% actual 37%, 10-20% n 80 pred 15% actual 39%, 20-30% n 68 pred 25% actual 41%, 30-40% n 60 pred 34% actual 35%, 40-50% n 54 pred 45% actual 28%, 50-60% n 16 pred 53% actual 56%

### Solo 2.0

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 166 | 33 | Rs 55,910 | Rs 73,152 | 57% | 58% | Rs 25,843 | Rs 22,752 | 0.038 |
| walk-forward | SKIP_AGAINST | 166 | 33 | Rs 55,910 | Rs 73,152 | 57% | 58% | Rs 25,843 | Rs 22,752 | 0.038 |
| final | SKIP_AGAINST | 127 | 15 | Rs 99,735 | Rs 88,582 | 60% | 57% | Rs 18,056 | Rs 18,056 | 0.4678 |
| design | EDGE_ONLY | 166 | 155 | Rs 55,910 | Rs 10,765 | 57% | 73% | Rs 25,843 | Rs 9,692 | 0.2439 |
| walk-forward | EDGE_ONLY | 166 | 155 | Rs 55,910 | Rs 10,765 | 57% | 73% | Rs 25,843 | Rs 9,692 | 0.2439 |
| final | EDGE_ONLY | 127 | 118 | Rs 99,735 | -Rs 5,296 | 60% | 44% | Rs 18,056 | Rs 7,995 | 0.9265 |

Verdicts (n, win rate, mean net per trade):

- design: against 33 (52%, -Rs 522); coin flip 113 (57%, Rs 547); edge 11 (73%, Rs 979); unknown 9 (56%, Rs 64)
- walk-forward: against 33 (52%, -Rs 522); coin flip 113 (57%, Rs 547); edge 11 (73%, Rs 979); unknown 9 (56%, Rs 64)
- final: against 15 (80%, Rs 744); coin flip 99 (58%, Rs 852); edge 9 (44%, -Rs 588); unknown 4 (75%, Rs 2,380)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 163, Brier 0.2677 vs 0.2459 for the constant design win rate 57%; correlation 0.0887; bins 0-10% n 5 pred 8% actual 20%, 10-20% n 7 pred 16% actual 57%, 20-30% n 12 pred 27% actual 67%, 30-40% n 25 pred 37% actual 64%, 40-50% n 53 pred 45% actual 47%, 50-60% n 41 pred 54% actual 58%, 60-70% n 13 pred 63% actual 69%, 70-80% n 5 pred 75% actual 60%, 80-90% n 1 pred 82% actual 100%, 90-100% n 1 pred 96% actual 100%
- final: n 124, Brier 0.2836 vs 0.2416 for the constant design win rate 57%; correlation -0.1992; bins 10-20% n 1 pred 16% actual 100%, 20-30% n 3 pred 26% actual 100%, 30-40% n 19 pred 36% actual 74%, 40-50% n 45 pred 46% actual 58%, 50-60% n 37 pred 54% actual 60%, 60-70% n 15 pred 64% actual 40%, 70-80% n 4 pred 74% actual 50%

### Hero Rs 1,000 ticket

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 248 | 176 | -Rs 14,507 | -Rs 7,500 | 16% | 21% | Rs 18,386 | Rs 7,594 | 0.7786 |
| walk-forward | SKIP_AGAINST | 190 | 129 | -Rs 12,935 | -Rs 5,213 | 18% | 23% | Rs 14,868 | Rs 5,213 | 0.6772 |
| final | SKIP_AGAINST | 107 | 79 | Rs 1,559 | -Rs 1,593 | 22% | 29% | Rs 5,359 | Rs 1,837 | 0.5212 |
| design | EDGE_ONLY | 248 | 248 | -Rs 14,507 | Rs 0 | 16% | - | Rs 18,386 | Rs 0 | 1.0 |
| walk-forward | EDGE_ONLY | 190 | 190 | -Rs 12,935 | Rs 0 | 18% | - | Rs 14,868 | Rs 0 | 1.0 |
| final | EDGE_ONLY | 107 | 107 | Rs 1,559 | Rs 0 | 22% | - | Rs 5,359 | Rs 0 | 1.0 |

Verdicts (n, win rate, mean net per trade):

- design: against 176 (14%, -Rs 40); coin flip 72 (21%, -Rs 104)
- walk-forward: against 129 (16%, -Rs 60); coin flip 61 (23%, -Rs 86)
- final: against 79 (20%, Rs 40); coin flip 28 (29%, -Rs 57)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 248, Brier 0.1671 vs 0.1353 for the constant design win rate 16%; correlation 0.0805; bins 10-20% n 2 pred 19% actual 0%, 20-30% n 60 pred 26% actual 15%, 30-40% n 143 pred 35% actual 15%, 40-50% n 42 pred 43% actual 19%, 50-60% n 1 pred 50% actual 100%
- final: n 107, Brier 0.1794 vs 0.178 for the constant design win rate 16%; correlation 0.1451; bins 10-20% n 4 pred 18% actual 0%, 20-30% n 48 pred 26% actual 19%, 30-40% n 39 pred 35% actual 26%, 40-50% n 13 pred 43% actual 15%, 50-60% n 3 pred 54% actual 100%

### ORB

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 320 | 4 | -Rs 41,427 | -Rs 40,437 | 38% | 38% | Rs 44,952 | Rs 43,962 | 0.4098 |
| walk-forward | SKIP_AGAINST | 231 | 4 | -Rs 28,480 | -Rs 27,490 | 36% | 35% | Rs 36,089 | Rs 36,444 | 0.3838 |
| final | SKIP_AGAINST | 128 | 0 | Rs 2,876 | Rs 2,876 | 44% | 44% | Rs 7,493 | Rs 7,493 | 1.0 |
| design | EDGE_ONLY | 320 | 318 | -Rs 41,427 | -Rs 598 | 38% | 50% | Rs 44,952 | Rs 1,546 | 0.6582 |
| walk-forward | EDGE_ONLY | 231 | 229 | -Rs 28,480 | -Rs 598 | 36% | 50% | Rs 36,089 | Rs 1,546 | 0.6887 |
| final | EDGE_ONLY | 128 | 128 | Rs 2,876 | Rs 0 | 44% | - | Rs 7,493 | Rs 0 | 1.0 |

Verdicts (n, win rate, mean net per trade):

- design: against 4 (50%, -Rs 248); coin flip 314 (38%, -Rs 127); edge 2 (50%, -Rs 299)
- walk-forward: against 4 (50%, -Rs 248); coin flip 225 (35%, -Rs 120); edge 2 (50%, -Rs 299)
- final: coin flip 128 (44%, Rs 22)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 320, Brier 0.2553 vs 0.2351 for the constant design win rate 38%; correlation -0.0403; bins 30-40% n 9 pred 37% actual 44%, 40-50% n 146 pred 47% actual 38%, 50-60% n 153 pred 54% actual 38%, 60-70% n 12 pred 62% actual 25%
- final: n 128, Brier 0.2517 vs 0.2496 for the constant design win rate 38%; correlation 0.0288; bins 30-40% n 3 pred 39% actual 0%, 40-50% n 61 pred 45% actual 43%, 50-60% n 60 pred 55% actual 47%, 60-70% n 4 pred 60% actual 50%

### ORB Fresh

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 320 | 5 | -Rs 41,427 | -Rs 39,517 | 38% | 37% | Rs 44,952 | Rs 43,042 | 0.2289 |
| walk-forward | SKIP_AGAINST | 231 | 5 | -Rs 28,480 | -Rs 26,570 | 36% | 35% | Rs 36,089 | Rs 35,524 | 0.2474 |
| final | SKIP_AGAINST | 128 | 0 | Rs 2,876 | Rs 2,876 | 44% | 44% | Rs 7,493 | Rs 7,493 | 1.0 |
| design | EDGE_ONLY | 320 | 319 | -Rs 41,427 | Rs 949 | 38% | 100% | Rs 44,952 | Rs 0 | 0.0935 |
| walk-forward | EDGE_ONLY | 231 | 230 | -Rs 28,480 | Rs 949 | 36% | 100% | Rs 36,089 | Rs 0 | 0.094 |
| final | EDGE_ONLY | 128 | 128 | Rs 2,876 | Rs 0 | 44% | - | Rs 7,493 | Rs 0 | 1.0 |

Verdicts (n, win rate, mean net per trade):

- design: against 5 (60%, -Rs 382); coin flip 314 (37%, -Rs 129); edge 1 (100%, Rs 948)
- walk-forward: against 5 (60%, -Rs 382); coin flip 225 (35%, -Rs 122); edge 1 (100%, Rs 948)
- final: coin flip 128 (44%, Rs 22)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 320, Brier 0.2556 vs 0.2351 for the constant design win rate 38%; correlation -0.0496; bins 30-40% n 13 pred 38% actual 46%, 40-50% n 154 pred 47% actual 38%, 50-60% n 144 pred 54% actual 38%, 60-70% n 9 pred 62% actual 22%
- final: n 128, Brier 0.2514 vs 0.2496 for the constant design win rate 38%; correlation 0.0348; bins 30-40% n 2 pred 40% actual 0%, 40-50% n 60 pred 46% actual 42%, 50-60% n 63 pred 55% actual 48%, 60-70% n 3 pred 61% actual 33%

### ORB Sweep

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 454 | 23 | -Rs 112,523 | -Rs 107,115 | 26% | 26% | Rs 118,586 | Rs 113,178 | 0.5287 |
| walk-forward | SKIP_AGAINST | 326 | 21 | -Rs 100,325 | -Rs 91,914 | 25% | 25% | Rs 106,263 | Rs 97,853 | 0.3658 |
| final | SKIP_AGAINST | 176 | 2 | -Rs 54,421 | -Rs 51,424 | 28% | 29% | Rs 54,421 | Rs 51,831 | 0.1499 |
| design | EDGE_ONLY | 454 | 454 | -Rs 112,523 | Rs 0 | 26% | - | Rs 118,586 | Rs 0 | 1.0 |
| walk-forward | EDGE_ONLY | 326 | 326 | -Rs 100,325 | Rs 0 | 25% | - | Rs 106,263 | Rs 0 | 1.0 |
| final | EDGE_ONLY | 176 | 176 | -Rs 54,421 | Rs 0 | 28% | - | Rs 54,421 | Rs 0 | 1.0 |

Verdicts (n, win rate, mean net per trade):

- design: against 23 (26%, -Rs 235); coin flip 431 (26%, -Rs 248)
- walk-forward: against 21 (19%, -Rs 400); coin flip 305 (25%, -Rs 301)
- final: against 2 (0%, -Rs 1,499); coin flip 174 (29%, -Rs 296)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 454, Brier 0.1992 vs 0.1945 for the constant design win rate 26%; correlation 0.0137; bins 10-20% n 18 pred 17% actual 22%, 20-30% n 167 pred 26% actual 25%, 30-40% n 249 pred 34% actual 27%, 40-50% n 20 pred 42% actual 35%
- final: n 176, Brier 0.2128 vs 0.2038 for the constant design win rate 26%; correlation -0.1178; bins 10-20% n 2 pred 15% actual 0%, 20-30% n 55 pred 27% actual 42%, 30-40% n 111 pred 35% actual 23%, 40-50% n 8 pred 41% actual 12%

### Range Fade

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 500 | 1 | -Rs 114,383 | -Rs 114,609 | 31% | 31% | Rs 114,383 | Rs 114,609 | 0.7946 |
| walk-forward | SKIP_AGAINST | 362 | 1 | -Rs 89,023 | -Rs 89,249 | 32% | 32% | Rs 90,037 | Rs 90,263 | 0.7761 |
| final | SKIP_AGAINST | 207 | 0 | -Rs 29,811 | -Rs 29,811 | 33% | 33% | Rs 32,363 | Rs 32,363 | 1.0 |
| design | EDGE_ONLY | 500 | 496 | -Rs 114,383 | Rs 652 | 31% | 50% | Rs 114,383 | Rs 133 | 0.1524 |
| walk-forward | EDGE_ONLY | 362 | 358 | -Rs 89,023 | Rs 652 | 32% | 50% | Rs 90,037 | Rs 133 | 0.1429 |
| final | EDGE_ONLY | 207 | 207 | -Rs 29,811 | Rs 0 | 33% | - | Rs 32,363 | Rs 0 | 1.0 |

Verdicts (n, win rate, mean net per trade):

- design: against 1 (100%, Rs 226); coin flip 495 (31%, -Rs 233); edge 4 (50%, Rs 163)
- walk-forward: against 1 (100%, Rs 226); coin flip 357 (31%, -Rs 252); edge 4 (50%, Rs 163)
- final: coin flip 207 (33%, -Rs 144)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 500, Brier 0.2512 vs 0.2154 for the constant design win rate 31%; correlation -0.0112; bins 30-40% n 11 pred 38% actual 55%, 40-50% n 255 pred 46% actual 30%, 50-60% n 225 pred 53% actual 32%, 60-70% n 9 pred 62% actual 33%
- final: n 207, Brier 0.2471 vs 0.2226 for the constant design win rate 31%; correlation 0.0917; bins 30-40% n 11 pred 39% actual 36%, 40-50% n 98 pred 44% actual 28%, 50-60% n 87 pred 55% actual 40%, 60-70% n 11 pred 61% actual 27%

### VIX divergence

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 116 | 2 | Rs 15,658 | Rs 1,937 | 36% | 35% | Rs 43,407 | Rs 43,407 | 0.9895 |
| walk-forward | SKIP_AGAINST | 84 | 2 | Rs 21,521 | Rs 7,801 | 37% | 35% | Rs 34,376 | Rs 34,376 | 0.98 |
| final | SKIP_AGAINST | 27 | 0 | -Rs 5,756 | -Rs 5,756 | 33% | 33% | Rs 16,533 | Rs 16,533 | 1.0 |
| design | EDGE_ONLY | 116 | 115 | Rs 15,658 | Rs 319 | 36% | 100% | Rs 43,407 | Rs 0 | 0.2804 |
| walk-forward | EDGE_ONLY | 84 | 83 | Rs 21,521 | Rs 319 | 37% | 100% | Rs 34,376 | Rs 0 | 0.2759 |
| final | EDGE_ONLY | 27 | 27 | -Rs 5,756 | Rs 0 | 33% | - | Rs 16,533 | Rs 0 | 1.0 |

Verdicts (n, win rate, mean net per trade):

- design: against 2 (100%, Rs 6,860); coin flip 54 (24%, -Rs 103); edge 1 (100%, Rs 319); unknown 59 (44%, Rs 122)
- walk-forward: against 2 (100%, Rs 6,860); coin flip 37 (24%, Rs 42); edge 1 (100%, Rs 319); unknown 44 (43%, Rs 135)
- final: coin flip 7 (14%, -Rs 928); unknown 20 (40%, Rs 37)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 57, Brier 0.2567 vs 0.2085 for the constant design win rate 36%; correlation -0.0914; bins 30-40% n 3 pred 34% actual 67%, 40-50% n 32 pred 46% actual 22%, 50-60% n 18 pred 54% actual 33%, 60-70% n 3 pred 62% actual 0%, 70-80% n 1 pred 72% actual 100%
- final: n 7, Brier 0.2422 vs 0.1705 for the constant design win rate 36%; correlation 0.3687; bins 40-50% n 3 pred 44% actual 0%, 50-60% n 4 pred 56% actual 25%

### Pine Supertrend+EMA 5m

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 467 | 11 | -Rs 27,008 | -Rs 20,675 | 37% | 38% | Rs 92,740 | Rs 92,740 | 0.3163 |
| walk-forward | SKIP_AGAINST | 322 | 11 | Rs 52,485 | Rs 58,818 | 40% | 41% | Rs 27,066 | Rs 25,518 | 0.2599 |
| final | SKIP_AGAINST | 182 | 4 | -Rs 52,573 | -Rs 54,061 | 33% | 33% | Rs 71,492 | Rs 65,909 | 0.6747 |
| design | EDGE_ONLY | 467 | 461 | -Rs 27,008 | Rs 1,155 | 37% | 33% | Rs 92,740 | Rs 634 | 0.3763 |
| walk-forward | EDGE_ONLY | 322 | 316 | Rs 52,485 | Rs 1,155 | 40% | 33% | Rs 27,066 | Rs 634 | 0.4573 |
| final | EDGE_ONLY | 182 | 182 | -Rs 52,573 | Rs 0 | 33% | - | Rs 71,492 | Rs 0 | 1.0 |

Verdicts (n, win rate, mean net per trade):

- design: against 11 (18%, -Rs 576); coin flip 450 (38%, -Rs 48); edge 6 (33%, Rs 192)
- walk-forward: against 11 (18%, -Rs 576); coin flip 305 (41%, Rs 189); edge 6 (33%, Rs 192)
- final: against 4 (25%, Rs 372); coin flip 178 (33%, -Rs 304)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 467, Brier 0.2497 vs 0.2343 for the constant design win rate 37%; correlation 0.0104; bins 20-30% n 1 pred 28% actual 100%, 30-40% n 24 pred 37% actual 33%, 40-50% n 257 pred 46% actual 37%, 50-60% n 172 pred 54% actual 38%, 60-70% n 13 pred 63% actual 31%
- final: n 182, Brier 0.2475 vs 0.223 for the constant design win rate 37%; correlation -0.001; bins 30-40% n 12 pred 38% actual 25%, 40-50% n 96 pred 45% actual 33%, 50-60% n 74 pred 54% actual 34%

### Pine Supertrend+EMA 15m

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 161 | 1 | -Rs 68,841 | -Rs 68,734 | 42% | 42% | Rs 106,872 | Rs 106,766 | 0.5812 |
| walk-forward | SKIP_AGAINST | 118 | 1 | -Rs 26,000 | -Rs 25,894 | 45% | 45% | Rs 77,767 | Rs 77,660 | 0.5172 |
| final | SKIP_AGAINST | 75 | 0 | -Rs 83,469 | -Rs 83,469 | 41% | 41% | Rs 98,510 | Rs 98,510 | 1.0 |
| design | EDGE_ONLY | 161 | 161 | -Rs 68,841 | Rs 0 | 42% | - | Rs 106,872 | Rs 0 | 1.0 |
| walk-forward | EDGE_ONLY | 118 | 118 | -Rs 26,000 | Rs 0 | 45% | - | Rs 77,767 | Rs 0 | 1.0 |
| final | EDGE_ONLY | 75 | 74 | -Rs 83,469 | -Rs 368 | 41% | 0% | Rs 98,510 | Rs 368 | 0.5267 |

Verdicts (n, win rate, mean net per trade):

- design: against 1 (0%, -Rs 106); coin flip 160 (42%, -Rs 430)
- walk-forward: against 1 (0%, -Rs 106); coin flip 117 (45%, -Rs 221)
- final: coin flip 74 (42%, -Rs 1,123); edge 1 (0%, -Rs 368)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 161, Brier 0.248 vs 0.243 for the constant design win rate 42%; correlation 0.0753; bins 30-40% n 5 pred 38% actual 20%, 40-50% n 86 pred 47% actual 41%, 50-60% n 66 pred 53% actual 42%, 60-70% n 4 pred 62% actual 75%
- final: n 75, Brier 0.2512 vs 0.2425 for the constant design win rate 42%; correlation -0.0101; bins 30-40% n 2 pred 38% actual 50%, 40-50% n 44 pred 47% actual 39%, 50-60% n 28 pred 53% actual 46%, 60-70% n 1 pred 63% actual 0%

### Pine Matrix 5m

| period | rule | trades | skipped | net before | net after | win before | win after | max DD before | max DD after | twin p |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| design | SKIP_AGAINST | 459 | 5 | -Rs 195,492 | -Rs 190,370 | 35% | 35% | Rs 198,516 | Rs 193,394 | 0.3588 |
| walk-forward | SKIP_AGAINST | 336 | 5 | -Rs 137,347 | -Rs 132,225 | 36% | 37% | Rs 143,100 | Rs 137,977 | 0.3288 |
| final | SKIP_AGAINST | 173 | 1 | Rs 39,116 | Rs 39,386 | 41% | 41% | Rs 43,066 | Rs 42,795 | 0.5242 |
| design | EDGE_ONLY | 459 | 452 | -Rs 195,492 | -Rs 2,704 | 35% | 14% | Rs 198,516 | Rs 2,862 | 0.4443 |
| walk-forward | EDGE_ONLY | 336 | 329 | -Rs 137,347 | -Rs 2,704 | 36% | 14% | Rs 143,100 | Rs 2,862 | 0.4523 |
| final | EDGE_ONLY | 173 | 173 | Rs 39,116 | Rs 0 | 41% | - | Rs 43,066 | Rs 0 | 1.0 |

Verdicts (n, win rate, mean net per trade):

- design: against 5 (20%, -Rs 1,024); coin flip 447 (36%, -Rs 420); edge 7 (14%, -Rs 386)
- walk-forward: against 5 (20%, -Rs 1,024); coin flip 324 (37%, -Rs 400); edge 7 (14%, -Rs 386)
- final: against 1 (0%, -Rs 270); coin flip 172 (41%, Rs 229)

Calibration (predicted = target first / decided draws; actual = net > 0):

- design: n 459, Brier 0.2527 vs 0.2277 for the constant design win rate 35%; correlation -0.0261; bins 30-40% n 17 pred 37% actual 29%, 40-50% n 235 pred 46% actual 37%, 50-60% n 190 pred 53% actual 33%, 60-70% n 17 pred 63% actual 35%
- final: n 173, Brier 0.2488 vs 0.2455 for the constant design win rate 35%; correlation 0.0527; bins 30-40% n 4 pred 38% actual 25%, 40-50% n 93 pred 46% actual 42%, 50-60% n 72 pred 54% actual 39%, 60-70% n 4 pred 61% actual 75%

## Pooled calibration (all tested arms, design + final)

n 16810, Brier 0.2576 vs 0.245 for the pooled win rate 43%; correlation 0.0371.

| bin | n | predicted | actual |
|---|---:|---:|---:|
| 0-10% | 340 | 4% | 36% |
| 10-20% | 344 | 15% | 45% |
| 20-30% | 753 | 26% | 38% |
| 30-40% | 1923 | 36% | 40% |
| 40-50% | 6179 | 46% | 42% |
| 50-60% | 5920 | 54% | 45% |
| 60-70% | 1079 | 63% | 45% |
| 70-80% | 157 | 74% | 45% |
| 80-90% | 48 | 84% | 42% |
| 90-100% | 67 | 99% | 39% |

## Not tested

- FT3 near-ATM morning: the app passes no stop and no target, so its time machine always says 'unknown'
- Night (R3): held overnight: the app skips the replay ('no intraday replay')
- IraThinker mom60 (ntfy): no trade list rebuilt
- NG evening breakout: no trade list rebuilt
- SILVERM morning: no trade list rebuilt
- US-night silver: no trade list rebuilt
- MCX 12-month trend: no trade list rebuilt
