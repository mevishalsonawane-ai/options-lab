# R19c design results (before 1 Apr 2025)

Written before the holdout was read. Gate 1 (daily) and gate 2 (net) on the design period; a variant must pass
them here AND in the holdout.

| arm | variant | trades | days | win | net | green days | worst day | max DD | profit->loss | floor exits | gate 1 | gate 2 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity BANKNIFTY 15m | CTRL | 68 | 62 | 43% | +Rs 33,863 | 44% | -Rs 1,831 | -Rs 4,129 | 7 | 0 | - | - |
| Liquidity BANKNIFTY 15m | F1000_500 | 68 | 62 | 51% | +Rs 8,807 | 52% | -Rs 1,831 | -Rs 6,027 | 1 | 19 | fail | fail |
| Liquidity BANKNIFTY 15m | F750_500 | 68 | 62 | 51% | +Rs 7,856 | 53% | -Rs 1,831 | -Rs 5,350 | 1 | 22 | fail | fail |
| Liquidity BANKNIFTY 15m | F1500_500 | 68 | 62 | 49% | +Rs 12,632 | 48% | -Rs 1,831 | -Rs 6,462 | 3 | 15 | fail | fail |
| Liquidity BANKNIFTY 15m | F1000_300 | 68 | 62 | 51% | +Rs 8,914 | 52% | -Rs 1,831 | -Rs 6,113 | 1 | 19 | fail | fail |
| Liquidity BANKNIFTY 15m | F1000_700 | 68 | 62 | 51% | +Rs 8,735 | 52% | -Rs 1,831 | -Rs 6,364 | 1 | 19 | fail | fail |
| Liquidity BANKNIFTY 5m | CTRL | 178 | 134 | 37% | +Rs 43,379 | 40% | -Rs 2,787 | -Rs 17,803 | 14 | 0 | - | - |
| Liquidity BANKNIFTY 5m | F1000_500 | 178 | 134 | 43% | -Rs 3,219 | 46% | -Rs 2,787 | -Rs 16,713 | 3 | 41 | pass | fail |
| Liquidity BANKNIFTY 5m | F750_500 | 178 | 134 | 46% | -Rs 7,419 | 48% | -Rs 2,787 | -Rs 18,712 | 3 | 53 | fail | fail |
| Liquidity BANKNIFTY 5m | F1500_500 | 178 | 134 | 42% | +Rs 6,719 | 45% | -Rs 2,787 | -Rs 14,268 | 6 | 33 | pass | fail |
| Liquidity BANKNIFTY 5m | F1000_300 | 178 | 134 | 43% | -Rs 6,789 | 46% | -Rs 2,787 | -Rs 20,225 | 3 | 42 | fail | fail |
| Liquidity BANKNIFTY 5m | F1000_700 | 178 | 134 | 43% | +Rs 1,992 | 46% | -Rs 2,787 | -Rs 16,794 | 3 | 39 | pass | fail |
| ORB | CTRL | 267 | 240 | 43% | -Rs 15,868 | 43% | -Rs 2,678 | -Rs 18,867 | 1 | 0 | - | - |
| ORB | F1000_500 | 267 | 240 | 43% | -Rs 15,719 | 43% | -Rs 2,678 | -Rs 18,747 | 1 | 5 | fail | fail |
| ORB | F750_500 | 267 | 240 | 43% | -Rs 15,583 | 43% | -Rs 2,678 | -Rs 18,673 | 1 | 10 | fail | fail |
| ORB | F1500_500 | 267 | 240 | 43% | -Rs 15,868 | 43% | -Rs 2,678 | -Rs 18,867 | 1 | 0 | fail | fail |
| ORB | F1000_300 | 267 | 240 | 43% | -Rs 14,949 | 43% | -Rs 2,678 | -Rs 18,169 | 1 | 9 | fail | fail |
| ORB | F1000_700 | 267 | 240 | 43% | -Rs 15,868 | 43% | -Rs 2,678 | -Rs 18,867 | 1 | 0 | fail | fail |
| ORB | R1000_500 | 267 | 240 | 49% | -Rs 34,745 | 48% | -Rs 2,699 | -Rs 39,164 | 1 | 16 | fail | fail |
| ORB Fresh | CTRL | 267 | 240 | 43% | -Rs 15,868 | 43% | -Rs 2,678 | -Rs 18,867 | 1 | 0 | - | - |
| ORB Fresh | F1000_500 | 267 | 240 | 43% | -Rs 15,719 | 43% | -Rs 2,678 | -Rs 18,747 | 1 | 5 | fail | fail |
| ORB Fresh | F750_500 | 267 | 240 | 43% | -Rs 15,583 | 43% | -Rs 2,678 | -Rs 18,673 | 1 | 10 | fail | fail |
| ORB Fresh | F1500_500 | 267 | 240 | 43% | -Rs 15,868 | 43% | -Rs 2,678 | -Rs 18,867 | 1 | 0 | fail | fail |
| ORB Fresh | F1000_300 | 267 | 240 | 43% | -Rs 14,949 | 43% | -Rs 2,678 | -Rs 18,169 | 1 | 9 | fail | fail |
| ORB Fresh | F1000_700 | 267 | 240 | 43% | -Rs 15,868 | 43% | -Rs 2,678 | -Rs 18,867 | 1 | 0 | fail | fail |
| ORB Fresh | R1000_500 | 267 | 240 | 49% | -Rs 34,745 | 48% | -Rs 2,699 | -Rs 39,164 | 1 | 16 | fail | fail |
| ORB Sweep | CTRL | 386 | 230 | 29% | -Rs 35,151 | 32% | -Rs 2,657 | -Rs 35,693 | 8 | 0 | - | - |
| ORB Sweep | F1000_500 | 386 | 230 | 31% | -Rs 37,085 | 34% | -Rs 2,657 | -Rs 37,628 | 0 | 45 | fail | fail |
| ORB Sweep | F750_500 | 386 | 230 | 34% | -Rs 38,149 | 35% | -Rs 2,657 | -Rs 38,691 | 0 | 71 | fail | fail |
| ORB Sweep | F1500_500 | 386 | 230 | 29% | -Rs 37,797 | 33% | -Rs 2,657 | -Rs 38,906 | 8 | 14 | fail | fail |
| ORB Sweep | F1000_300 | 386 | 230 | 31% | -Rs 39,388 | 34% | -Rs 2,657 | -Rs 39,930 | 0 | 55 | fail | fail |
| ORB Sweep | F1000_700 | 386 | 230 | 31% | -Rs 36,904 | 35% | -Rs 2,657 | -Rs 37,550 | 0 | 30 | fail | fail |
| ORB Sweep | R1000_500 | 355 | 230 | 43% | -Rs 29,590 | 42% | -Rs 2,701 | -Rs 40,772 | 0 | 84 | fail | fail |
| Range Fade | CTRL | 411 | 229 | 30% | -Rs 42,414 | 37% | -Rs 2,565 | -Rs 44,906 | 0 | 0 | - | - |
| Range Fade | F1000_500 | 411 | 229 | 30% | -Rs 42,768 | 37% | -Rs 2,565 | -Rs 44,968 | 0 | 6 | fail | fail |
| Range Fade | F750_500 | 411 | 229 | 30% | -Rs 43,101 | 37% | -Rs 2,565 | -Rs 44,970 | 0 | 10 | fail | fail |
| Range Fade | F1500_500 | 411 | 229 | 30% | -Rs 42,414 | 37% | -Rs 2,565 | -Rs 44,906 | 0 | 0 | fail | fail |
| Range Fade | F1000_300 | 411 | 229 | 30% | -Rs 41,672 | 37% | -Rs 2,565 | -Rs 44,627 | 0 | 9 | fail | fail |
| Range Fade | F1000_700 | 411 | 229 | 30% | -Rs 42,414 | 37% | -Rs 2,565 | -Rs 44,906 | 0 | 0 | fail | fail |
| Range Fade | R1000_500 | 370 | 229 | 48% | -Rs 39,527 | 36% | -Rs 2,701 | -Rs 44,002 | 0 | 21 | fail | fail |
| VIX divergence BANKNIFTY | CTRL | 52 | 52 | 48% | +Rs 27,513 | 48% | -Rs 6,467 | -Rs 18,624 | 12 | 0 | - | - |
| VIX divergence BANKNIFTY | F1000_500 | 52 | 52 | 71% | +Rs 14,925 | 71% | -Rs 3,337 | -Rs 6,127 | 0 | 32 | pass | fail |
| VIX divergence BANKNIFTY | F750_500 | 52 | 52 | 81% | +Rs 20,621 | 81% | -Rs 2,637 | -Rs 3,752 | 0 | 38 | pass | pass |
| VIX divergence BANKNIFTY | F1500_500 | 52 | 52 | 67% | +Rs 28,218 | 67% | -Rs 3,337 | -Rs 5,726 | 2 | 28 | pass | pass |
| VIX divergence BANKNIFTY | F1000_300 | 52 | 52 | 71% | +Rs 13,426 | 71% | -Rs 3,337 | -Rs 5,990 | 0 | 32 | pass | fail |
| VIX divergence BANKNIFTY | F1000_700 | 52 | 52 | 71% | +Rs 12,279 | 71% | -Rs 3,337 | -Rs 6,327 | 0 | 32 | pass | fail |
| VIX divergence NIFTY | CTRL | 49 | 49 | 39% | +Rs 12,233 | 39% | -Rs 2,606 | -Rs 10,520 | 3 | 0 | - | - |
| VIX divergence NIFTY | F1000_500 | 49 | 49 | 45% | -Rs 1,055 | 45% | -Rs 2,606 | -Rs 5,771 | 0 | 13 | pass | fail |
| VIX divergence NIFTY | F750_500 | 49 | 49 | 49% | +Rs 876 | 49% | -Rs 1,909 | -Rs 3,526 | 0 | 17 | pass | fail |
| VIX divergence NIFTY | F1500_500 | 49 | 49 | 45% | +Rs 5,703 | 45% | -Rs 2,606 | -Rs 6,424 | 0 | 11 | pass | fail |
| VIX divergence NIFTY | F1000_300 | 49 | 49 | 45% | -Rs 1,697 | 45% | -Rs 2,606 | -Rs 5,489 | 0 | 13 | pass | fail |
| VIX divergence NIFTY | F1000_700 | 49 | 49 | 45% | +Rs 5,013 | 45% | -Rs 2,606 | -Rs 6,132 | 0 | 13 | pass | fail |
| Hero (NIFTY expiry) | CTRL | 0 | 0 | - | +Rs 0 | - | +Rs 0 | +Rs 0 | 0 | 0 | - | - |
| Hero (NIFTY expiry) | F1000_500 | 0 | 0 | - | +Rs 0 | - | +Rs 0 | +Rs 0 | 0 | 0 | fail | fail |
| Hero (NIFTY expiry) | F750_500 | 0 | 0 | - | +Rs 0 | - | +Rs 0 | +Rs 0 | 0 | 0 | fail | fail |
| Hero (NIFTY expiry) | F1500_500 | 0 | 0 | - | +Rs 0 | - | +Rs 0 | +Rs 0 | 0 | 0 | fail | fail |
| Hero (NIFTY expiry) | F1000_300 | 0 | 0 | - | +Rs 0 | - | +Rs 0 | +Rs 0 | 0 | 0 | fail | fail |
| Hero (NIFTY expiry) | F1000_700 | 0 | 0 | - | +Rs 0 | - | +Rs 0 | +Rs 0 | 0 | 0 | fail | fail |
| Solo (midday) | CTRL | 63 | 63 | 44% | -Rs 175 | 44% | -Rs 5,792 | -Rs 26,549 | 13 | 0 | - | - |
| Solo (midday) | F1000_500 | 63 | 63 | 65% | -Rs 16,102 | 65% | -Rs 5,792 | -Rs 26,086 | 0 | 39 | pass | fail |
| Solo (midday) | F750_500 | 63 | 63 | 73% | -Rs 11,561 | 73% | -Rs 5,792 | -Rs 23,463 | 0 | 45 | pass | fail |
| Solo (midday) | F1500_500 | 63 | 63 | 56% | -Rs 20,396 | 56% | -Rs 5,792 | -Rs 32,957 | 6 | 31 | fail | fail |
| Solo (midday) | F1000_300 | 63 | 63 | 65% | -Rs 16,294 | 65% | -Rs 5,792 | -Rs 25,458 | 0 | 39 | pass | fail |
| Solo (midday) | F1000_700 | 63 | 63 | 65% | -Rs 13,196 | 65% | -Rs 5,792 | -Rs 23,131 | 0 | 38 | pass | fail |

## Portfolio (1 lot each arm, plain rules; Night not run)

| arm | variant | trades | days | win | net | green days | worst day | max DD | profit->loss | floor exits | fired | gate 1 | gate 2 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P0 |  | 1741 | 272 | 36% | +Rs 7,512 | 40% | -Rs 10,136 | -Rs 85,018 | 59 | 0 | 0 | - | - |
| P1 |  | 410 | 197 | 41% | +Rs 116,813 | 43% | -Rs 8,188 | -Rs 23,585 | 49 | 0 | 0 | pass | pass |
| P2 |  | 1510 | 272 | 36% | +Rs 32,546 | 39% | -Rs 8,329 | -Rs 43,932 | 47 | 0 | 92 | fail | pass |
| P3 |  | 1634 | 272 | 37% | +Rs 41,195 | 40% | -Rs 8,329 | -Rs 47,800 | 51 | 0 | 48 | fail | pass |
| P4 |  | 1674 | 272 | 37% | -Rs 19,045 | 43% | -Rs 10,136 | -Rs 97,209 | 52 | 0 | 37 | fail | fail |
| P5 |  | 395 | 197 | 43% | +Rs 108,010 | 49% | -Rs 8,188 | -Rs 19,751 | 37 | 0 | 35 | pass | pass |
| P6 |  | 388 | 197 | 41% | +Rs 123,524 | 42% | -Rs 6,781 | -Rs 21,035 | 45 | 0 | 43 | pass | pass |
| P7 |  | 374 | 197 | 43% | +Rs 106,530 | 47% | -Rs 6,781 | -Rs 19,514 | 35 | 0 | 70 | pass | pass |
