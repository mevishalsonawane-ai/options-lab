# R19c holdout results (from 1 Apr 2025; read once, 2026-10-13 09:02 UTC)

p: one-sided exact sign test on days that changed colour; q: Benjamini-Hochberg over 61;
twin: share of 1,000 random-early-exit twins beaten (needs 95%). d1/d2: design gates 1/2; h1/h2: holdout gates 1/2.

| arm | variant | trades | days | win | net | green days | worst day | max DD | profit->loss | floor exits | up/down | p | q | twin | d1 d2 h1 h2 | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity BANKNIFTY 15m | CTRL | 51 | 49 | 37% | +Rs 23,373 | 37% | -Rs 5,294 | -Rs 14,552 | 6 | 0 | | | | | | |
| Liquidity BANKNIFTY 15m | F1000_500 | 51 | 49 | 47% | -Rs 6,911 | 47% | -Rs 5,294 | -Rs 11,144 | 1 | 17 | 5/0 | 0.031 | 0.06 | 0% | n n y n | FAIL |
| Liquidity BANKNIFTY 15m | F750_500 | 51 | 49 | 63% | +Rs 2,825 | 61% | -Rs 1,895 | -Rs 5,843 | 0 | 27 | 12/0 | 0.000 | 0.00 | 0% | n n y n | FAIL |
| Liquidity BANKNIFTY 15m | F1500_500 | 51 | 49 | 41% | -Rs 4,639 | 41% | -Rs 5,294 | -Rs 12,577 | 4 | 10 | 2/0 | 0.250 | 0.46 | 0% | n n y n | FAIL |
| Liquidity BANKNIFTY 15m | F1000_300 | 51 | 49 | 47% | -Rs 5,024 | 47% | -Rs 5,294 | -Rs 10,553 | 1 | 17 | 5/0 | 0.031 | 0.06 | 0% | n n y n | FAIL |
| Liquidity BANKNIFTY 15m | F1000_700 | 51 | 49 | 47% | -Rs 6,679 | 47% | -Rs 5,294 | -Rs 11,983 | 1 | 17 | 5/0 | 0.031 | 0.06 | 0% | n n y n | FAIL |
| Liquidity BANKNIFTY 5m | CTRL | 170 | 131 | 41% | +Rs 27,342 | 40% | -Rs 4,183 | -Rs 10,806 | 19 | 0 | | | | | | |
| Liquidity BANKNIFTY 5m | F1000_500 | 170 | 131 | 51% | +Rs 6,675 | 50% | -Rs 4,183 | -Rs 13,371 | 2 | 52 | 15/2 | 0.001 | 0.00 | 0% | y n n n | FAIL |
| Liquidity BANKNIFTY 5m | F750_500 | 170 | 131 | 57% | +Rs 11,051 | 57% | -Rs 4,183 | -Rs 11,721 | 1 | 71 | 23/1 | 0.000 | 0.00 | 0% | n n n n | FAIL |
| Liquidity BANKNIFTY 5m | F1500_500 | 170 | 131 | 46% | +Rs 10,857 | 47% | -Rs 4,183 | -Rs 11,973 | 10 | 35 | 9/1 | 0.011 | 0.03 | 0% | y n n n | FAIL |
| Liquidity BANKNIFTY 5m | F1000_300 | 170 | 131 | 51% | +Rs 8,278 | 51% | -Rs 4,183 | -Rs 12,290 | 2 | 53 | 15/1 | 0.000 | 0.00 | 0% | n n n n | FAIL |
| Liquidity BANKNIFTY 5m | F1000_700 | 170 | 131 | 51% | +Rs 8,174 | 51% | -Rs 4,183 | -Rs 13,775 | 2 | 49 | 15/1 | 0.000 | 0.00 | 0% | y n n n | FAIL |
| ORB | CTRL | 207 | 188 | 36% | -Rs 10,958 | 38% | -Rs 1,767 | -Rs 18,653 | 0 | 0 | | | | | | |
| ORB | F1000_500 | 207 | 188 | 36% | -Rs 11,758 | 38% | -Rs 1,767 | -Rs 19,146 | 0 | 10 | 0/0 | 1.000 | 1.00 | 0% | n n n n | FAIL |
| ORB | F750_500 | 207 | 188 | 36% | -Rs 13,963 | 38% | -Rs 1,767 | -Rs 19,204 | 0 | 24 | 0/0 | 1.000 | 1.00 | 0% | n n n n | FAIL |
| ORB | F1500_500 | 207 | 188 | 36% | -Rs 10,958 | 38% | -Rs 1,767 | -Rs 18,653 | 0 | 0 | 0/0 | 1.000 | 1.00 | - | n n n n | FAIL |
| ORB | F1000_300 | 207 | 188 | 36% | -Rs 11,086 | 38% | -Rs 1,767 | -Rs 18,597 | 0 | 25 | 0/0 | 1.000 | 1.00 | 69% | n n n n | FAIL |
| ORB | F1000_700 | 207 | 188 | 36% | -Rs 10,958 | 38% | -Rs 1,767 | -Rs 18,653 | 0 | 0 | 0/0 | 1.000 | 1.00 | - | n n n n | FAIL |
| ORB | R1000_500 | 207 | 188 | 58% | -Rs 18,026 | 56% | -Rs 3,059 | -Rs 27,354 | 0 | 52 | 45/11 | 0.000 | 0.00 | 0% | n n n n | FAIL |
| ORB Fresh | CTRL | 207 | 188 | 36% | -Rs 10,958 | 38% | -Rs 1,767 | -Rs 18,653 | 0 | 0 | | | | | | |
| ORB Fresh | F1000_500 | 207 | 188 | 36% | -Rs 11,758 | 38% | -Rs 1,767 | -Rs 19,146 | 0 | 10 | 0/0 | 1.000 | 1.00 | 0% | n n n n | FAIL |
| ORB Fresh | F750_500 | 207 | 188 | 36% | -Rs 13,963 | 38% | -Rs 1,767 | -Rs 19,204 | 0 | 24 | 0/0 | 1.000 | 1.00 | 0% | n n n n | FAIL |
| ORB Fresh | F1500_500 | 207 | 188 | 36% | -Rs 10,958 | 38% | -Rs 1,767 | -Rs 18,653 | 0 | 0 | 0/0 | 1.000 | 1.00 | - | n n n n | FAIL |
| ORB Fresh | F1000_300 | 207 | 188 | 36% | -Rs 11,086 | 38% | -Rs 1,767 | -Rs 18,597 | 0 | 25 | 0/0 | 1.000 | 1.00 | 70% | n n n n | FAIL |
| ORB Fresh | F1000_700 | 207 | 188 | 36% | -Rs 10,958 | 38% | -Rs 1,767 | -Rs 18,653 | 0 | 0 | 0/0 | 1.000 | 1.00 | - | n n n n | FAIL |
| ORB Fresh | R1000_500 | 207 | 188 | 58% | -Rs 18,026 | 56% | -Rs 3,059 | -Rs 27,354 | 0 | 52 | 45/11 | 0.000 | 0.00 | 0% | n n n n | FAIL |
| ORB Sweep | CTRL | 291 | 177 | 24% | -Rs 85,997 | 24% | -Rs 3,104 | -Rs 89,661 | 36 | 0 | | | | | | |
| ORB Sweep | F1000_500 | 292 | 177 | 36% | -Rs 84,704 | 31% | -Rs 3,104 | -Rs 89,783 | 0 | 98 | 15/4 | 0.010 | 0.02 | 0% | n n n n | FAIL |
| ORB Sweep | F750_500 | 292 | 177 | 51% | -Rs 82,402 | 37% | -Rs 3,104 | -Rs 87,438 | 0 | 142 | 26/4 | 0.000 | 0.00 | 0% | n n y n | FAIL |
| ORB Sweep | F1500_500 | 292 | 177 | 24% | -Rs 92,551 | 24% | -Rs 3,104 | -Rs 95,748 | 36 | 48 | 2/2 | 0.688 | 1.00 | 0% | n n n n | FAIL |
| ORB Sweep | F1000_300 | 292 | 177 | 36% | -Rs 81,919 | 31% | -Rs 3,104 | -Rs 87,592 | 0 | 101 | 16/4 | 0.006 | 0.02 | 0% | n n y n | FAIL |
| ORB Sweep | F1000_700 | 292 | 177 | 36% | -Rs 86,709 | 33% | -Rs 3,104 | -Rs 90,593 | 0 | 87 | 17/1 | 0.000 | 0.00 | 0% | n n n n | FAIL |
| ORB Sweep | R1000_500 | 284 | 177 | 48% | -Rs 89,097 | 32% | -Rs 3,104 | -Rs 94,879 | 0 | 125 | 21/7 | 0.006 | 0.02 | 0% | n n n n | FAIL |
| Range Fade | CTRL | 326 | 186 | 28% | -Rs 90,301 | 35% | -Rs 3,084 | -Rs 92,573 | 0 | 0 | | | | | | |
| Range Fade | F1000_500 | 326 | 186 | 28% | -Rs 88,922 | 35% | -Rs 3,084 | -Rs 91,194 | 0 | 14 | 0/0 | 1.000 | 1.00 | 63% | n n n n | FAIL |
| Range Fade | F750_500 | 326 | 186 | 28% | -Rs 88,971 | 35% | -Rs 3,084 | -Rs 91,243 | 0 | 33 | 0/0 | 1.000 | 1.00 | 2% | n n n n | FAIL |
| Range Fade | F1500_500 | 326 | 186 | 28% | -Rs 90,301 | 35% | -Rs 3,084 | -Rs 92,573 | 0 | 0 | 0/0 | 1.000 | 1.00 | - | n n n n | FAIL |
| Range Fade | F1000_300 | 326 | 186 | 28% | -Rs 87,827 | 35% | -Rs 3,084 | -Rs 89,664 | 0 | 30 | 0/0 | 1.000 | 1.00 | 26% | n n n n | FAIL |
| Range Fade | F1000_700 | 326 | 186 | 28% | -Rs 90,301 | 35% | -Rs 3,084 | -Rs 92,573 | 0 | 0 | 0/0 | 1.000 | 1.00 | - | n n n n | FAIL |
| Range Fade | R1000_500 | 292 | 186 | 47% | -Rs 99,264 | 34% | -Rs 3,084 | -Rs 101,859 | 0 | 64 | 21/22 | 0.620 | 1.00 | 0% | n n n n | FAIL |
| VIX divergence BANKNIFTY | CTRL | 37 | 37 | 32% | -Rs 11,232 | 32% | -Rs 8,700 | -Rs 29,298 | 13 | 0 | | | | | | |
| VIX divergence BANKNIFTY | F1000_500 | 37 | 37 | 68% | +Rs 806 | 68% | -Rs 8,700 | -Rs 18,352 | 0 | 23 | 13/0 | 0.000 | 0.00 | 70% | y n y y | FAIL |
| VIX divergence BANKNIFTY | F750_500 | 37 | 37 | 73% | -Rs 5,661 | 73% | -Rs 8,700 | -Rs 17,422 | 0 | 25 | 15/0 | 0.000 | 0.00 | 42% | y y y n | FAIL |
| VIX divergence BANKNIFTY | F1500_500 | 37 | 37 | 57% | -Rs 3,817 | 57% | -Rs 8,700 | -Rs 23,131 | 4 | 19 | 9/0 | 0.002 | 0.01 | 55% | y y y n | FAIL |
| VIX divergence BANKNIFTY | F1000_300 | 37 | 37 | 68% | -Rs 1,647 | 68% | -Rs 8,700 | -Rs 16,866 | 0 | 23 | 13/0 | 0.000 | 0.00 | 58% | y n y n | FAIL |
| VIX divergence BANKNIFTY | F1000_700 | 37 | 37 | 68% | +Rs 1,879 | 68% | -Rs 8,700 | -Rs 18,749 | 0 | 23 | 13/0 | 0.000 | 0.00 | 75% | y n y y | FAIL |
| VIX divergence NIFTY | CTRL | 33 | 33 | 27% | +Rs 6,570 | 27% | -Rs 2,203 | -Rs 12,747 | 5 | 0 | | | | | | |
| VIX divergence NIFTY | F1000_500 | 33 | 33 | 42% | -Rs 6,241 | 42% | -Rs 2,130 | -Rs 6,836 | 0 | 11 | 5/0 | 0.031 | 0.06 | 0% | y n y n | FAIL |
| VIX divergence NIFTY | F750_500 | 33 | 33 | 48% | -Rs 4,401 | 48% | -Rs 2,130 | -Rs 5,305 | 0 | 13 | 7/0 | 0.008 | 0.02 | 0% | y n y n | FAIL |
| VIX divergence NIFTY | F1500_500 | 33 | 33 | 36% | -Rs 300 | 36% | -Rs 2,203 | -Rs 7,338 | 2 | 8 | 3/0 | 0.125 | 0.24 | 9% | y n y n | FAIL |
| VIX divergence NIFTY | F1000_300 | 33 | 33 | 42% | -Rs 5,768 | 42% | -Rs 2,130 | -Rs 6,176 | 0 | 11 | 5/0 | 0.031 | 0.06 | 0% | y n y n | FAIL |
| VIX divergence NIFTY | F1000_700 | 33 | 33 | 42% | -Rs 881 | 42% | -Rs 2,130 | -Rs 6,856 | 0 | 11 | 5/0 | 0.031 | 0.06 | 4% | y n y n | FAIL |
| Hero (NIFTY expiry) | CTRL | 10 | 10 | 10% | -Rs 19,576 | 10% | -Rs 3,353 | -Rs 19,576 | 0 | 0 | | | | | | |
| Hero (NIFTY expiry) | F1000_500 | 10 | 10 | 10% | -Rs 9,542 | 10% | -Rs 3,353 | -Rs 18,251 | 0 | 1 | 0/0 | 1.000 | 1.00 | 89% | n n n n | inconclusive |
| Hero (NIFTY expiry) | F750_500 | 10 | 10 | 10% | -Rs 21,465 | 10% | -Rs 3,353 | -Rs 21,465 | 0 | 1 | 0/0 | 1.000 | 1.00 | 61% | n n n n | inconclusive |
| Hero (NIFTY expiry) | F1500_500 | 10 | 10 | 10% | -Rs 9,542 | 10% | -Rs 3,353 | -Rs 18,251 | 0 | 1 | 0/0 | 1.000 | 1.00 | 88% | n n n n | inconclusive |
| Hero (NIFTY expiry) | F1000_300 | 10 | 10 | 10% | -Rs 16,959 | 10% | -Rs 3,353 | -Rs 18,251 | 0 | 1 | 0/0 | 1.000 | 1.00 | 71% | n n n n | inconclusive |
| Hero (NIFTY expiry) | F1000_700 | 10 | 10 | 10% | -Rs 9,699 | 10% | -Rs 3,353 | -Rs 18,251 | 0 | 1 | 0/0 | 1.000 | 1.00 | 90% | n n n n | inconclusive |
| Solo (midday) | CTRL | 75 | 75 | 52% | +Rs 42,457 | 52% | -Rs 7,154 | -Rs 13,864 | 21 | 0 | | | | | | |
| Solo (midday) | F1000_500 | 75 | 75 | 80% | +Rs 18,451 | 80% | -Rs 7,154 | -Rs 13,469 | 0 | 60 | 21/0 | 0.000 | 0.00 | 3% | y n y n | FAIL |
| Solo (midday) | F750_500 | 75 | 75 | 84% | +Rs 21,079 | 84% | -Rs 7,154 | -Rs 13,696 | 0 | 63 | 24/0 | 0.000 | 0.00 | 2% | y n y n | FAIL |
| Solo (midday) | F1500_500 | 75 | 75 | 73% | +Rs 30,484 | 73% | -Rs 7,154 | -Rs 15,178 | 5 | 54 | 16/0 | 0.000 | 0.00 | 23% | n n n y | FAIL |
| Solo (midday) | F1000_300 | 75 | 75 | 80% | +Rs 11,809 | 80% | -Rs 7,154 | -Rs 13,928 | 0 | 60 | 21/0 | 0.000 | 0.00 | 1% | y n n n | FAIL |
| Solo (midday) | F1000_700 | 75 | 75 | 80% | +Rs 21,552 | 80% | -Rs 7,154 | -Rs 14,142 | 0 | 60 | 21/0 | 0.000 | 0.00 | 5% | y n n n | FAIL |

## Portfolio (1 lot each arm, plain rules)

| arm | variant | trades | days | win | net | green days | worst day | max DD | profit->loss | floor exits | fired | up/down | p | q | twin | d1 d2 h1 h2 | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P0 |  | 1407 | 222 | 33% | -Rs 129,279 | 42% | -Rs 13,384 | -Rs 171,019 | 100 | 0 | 0 | | | | | | |
| P1 |  | 376 | 177 | 40% | +Rs 68,935 | 44% | -Rs 11,951 | -Rs 35,221 | 64 | 0 | 0 | 20/36 | 0.989 | 1.00 | - | y y y y | FAIL |
| P2 |  | 1061 | 222 | 32% | -Rs 132,252 | 36% | -Rs 11,454 | -Rs 140,664 | 69 | 0 | 126 | 1/15 | 1.000 | 1.00 | 3% | n y n n | FAIL |
| P3 |  | 1200 | 222 | 32% | -Rs 134,308 | 39% | -Rs 11,454 | -Rs 157,262 | 80 | 0 | 91 | 0/8 | 1.000 | 1.00 | 5% | n y n n | FAIL |
| P4 |  | 1311 | 222 | 33% | -Rs 142,030 | 49% | -Rs 13,384 | -Rs 153,401 | 85 | 0 | 54 | 16/2 | 0.001 | 0.00 | 6% | n n y n | FAIL |
| P5 |  | 355 | 177 | 43% | +Rs 72,392 | 53% | -Rs 11,951 | -Rs 29,254 | 45 | 0 | 47 | 32/32 | 0.550 | 0.99 | 62% | y y y y | FAIL |
| P6 |  | 340 | 177 | 40% | +Rs 55,277 | 43% | -Rs 10,139 | -Rs 23,007 | 56 | 0 | 62 | 20/38 | 0.994 | 1.00 | 21% | y y y y | FAIL |
| P7 |  | 328 | 177 | 44% | +Rs 44,989 | 50% | -Rs 10,139 | -Rs 19,097 | 42 | 0 | 91 | 29/34 | 0.775 | 1.00 | 17% | y y y y | FAIL |

## Each arm's net in the two years (plain rules)

| arm | design year | holdout year | both negative |
|---|---|---|---|
| Liquidity BANKNIFTY 15m | +Rs 33,863 | +Rs 23,373 | no |
| Liquidity BANKNIFTY 5m | +Rs 43,379 | +Rs 27,342 | no |
| ORB | -Rs 15,868 | -Rs 10,958 | yes |
| ORB Fresh | -Rs 15,868 | -Rs 10,958 | yes |
| ORB Sweep | -Rs 35,151 | -Rs 85,997 | yes |
| Range Fade | -Rs 42,414 | -Rs 90,301 | yes |
| VIX divergence BANKNIFTY | +Rs 27,513 | -Rs 11,232 | no |
| VIX divergence NIFTY | +Rs 12,233 | +Rs 6,570 | no |
| Hero (NIFTY expiry) | +Rs 0 | -Rs 19,576 | no |
| Solo (midday) | -Rs 175 | +Rs 42,457 | no |
