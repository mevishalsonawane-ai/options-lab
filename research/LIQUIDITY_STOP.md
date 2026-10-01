## Liquidity 15+5 with a stop on the option premium (research/liquidity_stop.py)

15-minute + 5-minute books, both tools agree, lookback 20, confirmation 10, failed-break stop; plus a resting stop X% below the price paid (checked on the option's minute lows). 1 lot of 30, real option prices, costs included.

### 2025-02-17 .. 2026-02-23 (249 days)

| premium stop | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| none (as tested before) | 545 (2.2/day) | 50% | +9.1 | Rs +58 | 1.08 | Rs +31,710 | +19,243 / +12,467 | 8/13 |
| 10% below the price paid | 545 (2.2/day) | 48% | +4.6 | Rs -10 | -0.21 | Rs -5,214 | -4,918 / -296 | 8/13 | stops hit: 10%
| 15% below the price paid | 545 (2.2/day) | 50% | +8.1 | Rs +40 | 0.76 | Rs +21,637 | +17,499 / +4,138 | 6/13 | stops hit: 6%
| 20% below the price paid | 545 (2.2/day) | 50% | +8.7 | Rs +48 | 0.91 | Rs +26,170 | +20,343 / +5,827 | 9/13 | stops hit: 3%
| 25% below the price paid | 545 (2.2/day) | 50% | +9.1 | Rs +57 | 1.07 | Rs +31,141 | +19,034 / +12,107 | 8/13 | stops hit: 1%
### 2024-02-13 .. 2025-02-14 (249 days)

| premium stop | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| none (as tested before) | 495 (2.0/day) | 46% | +8.1 | Rs +118 | 1.05 | Rs +58,396 | +15,537 / +42,860 | 10/13 |
| 10% below the price paid | 495 (2.0/day) | 43% | +12.4 | Rs +169 | 1.64 | Rs +83,534 | +27,933 / +55,601 | 10/13 | stops hit: 28%
| 15% below the price paid | 495 (2.0/day) | 44% | +9.3 | Rs +112 | 1.04 | Rs +55,574 | +9,698 / +45,877 | 8/13 | stops hit: 18%
| 20% below the price paid | 495 (2.0/day) | 45% | +8.6 | Rs +107 | 0.98 | Rs +52,771 | +4,386 / +48,385 | 9/13 | stops hit: 10%
| 25% below the price paid | 495 (2.0/day) | 45% | +5.8 | Rs +71 | 0.64 | Rs +35,292 | -7,695 / +42,987 | 10/13 | stops hit: 8%