## The owner's Matrix Premium v2.2 Pine script (EMA 20 cross, 2.7x ATR stop, 2.5R target, 11:00-13:15 dead zone), BANKNIFTY 5-min, buying options (research/matrix_pine.py)

### 2025-02-17 .. 2026-02-23 (249 days)

| version | trades | win (Rs) | avg R (index) | per trade | t | net for the year | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| as written, ATM, 1 lot | 356 (1.4/day) | 38% | -0.09 | Rs -307 | -2.09 | Rs -109,367 | -82,054 / -27,313 | 3/13 |
| as written, 2 strikes ITM, 1 lot | 356 (1.4/day) | 38% | -0.09 | Rs -314 | -1.92 | Rs -111,634 | -84,846 / -26,788 | 3/13 |
| + TP1 partial (half at 1.2R, breakeven), ATM, 2 lots | 366 (1.5/day) | 41% | -0.09 | Rs -652 | -2.58 | Rs -238,671 | -170,747 / -67,924 | 2/13 |
| + TP1 partial, 2 strikes ITM, 2 lots | 366 (1.5/day) | 41% | -0.09 | Rs -676 | -2.39 | Rs -247,236 | -179,383 / -67,853 | 2/13 |
| no dead zone, ATM, 1 lot | 384 (1.5/day) | 34% | -0.05 | Rs -216 | -1.42 | Rs -82,797 | -60,799 / -21,998 | 5/13 |

As written, ATM: exits 15:15 56%, stop 39%, tp2 5%; median risk 157 index pts (so TP2 is ~392 pts away), median hold 90 min.
By entry hour (as written, ATM): 9h 112 trades Rs -61,719, 10h 71 trades Rs -24,770, 11h 6 trades Rs -7,659, 13h 91 trades Rs -16,095, 14h 61 trades Rs +2,015, 15h 15 trades Rs -1,140

### 2024-02-13 .. 2025-02-14 (249 days)

| version | trades | win (Rs) | avg R (index) | per trade | t | net for the year | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| as written, ATM, 1 lot | 352 (1.4/day) | 35% | -0.04 | Rs -311 | -1.40 | Rs -109,375 | -23,268 / -86,106 | 4/13 |
| as written, 2 strikes ITM, 1 lot | 352 (1.4/day) | 37% | -0.04 | Rs -330 | -1.31 | Rs -116,070 | -26,640 / -89,429 | 4/13 |
| + TP1 partial (half at 1.2R, breakeven), ATM, 2 lots | 358 (1.4/day) | 39% | -0.04 | Rs -651 | -1.81 | Rs -233,168 | -72,261 / -160,907 | 3/13 |
| + TP1 partial, 2 strikes ITM, 2 lots | 358 (1.4/day) | 41% | -0.04 | Rs -691 | -1.68 | Rs -247,373 | -80,188 / -167,184 | 3/13 |
| no dead zone, ATM, 1 lot | 382 (1.5/day) | 35% | +0.00 | Rs -126 | -0.56 | Rs -48,075 | +16,220 / -64,295 | 8/13 |

As written, ATM: exits 15:15 55%, stop 38%, tp2 7%; median risk 191 index pts (so TP2 is ~478 pts away), median hold 95 min.
By entry hour (as written, ATM): 9h 117 trades Rs -76,842, 10h 74 trades Rs +7,110, 11h 4 trades Rs -11,328, 13h 78 trades Rs -26,022, 14h 56 trades Rs -1,868, 15h 23 trades Rs -425