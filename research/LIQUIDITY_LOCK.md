## Liquidity 15+5 with the profit-lock ladder (research/liquidity_lock.py)

BANKNIFTY, 15-min + 5-min books, 15% premium stop (the arm as it is), real option prices, 1 lot of 30, after costs.
Reference target = X% of the premium paid; rungs at 25 / 50 / 75 % of it lock price paid / +25% / +50% of it.

### 2024-02-13 .. 2025-02-14 (249 days)

| ladder | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| none (the arm today) | 495 (2.0/day) | 44% | +9.3 | Rs +112 | 1.04 | Rs +55,574 | +9,698 / +45,877 | 8/13 |
| target = 15% of premium | 495 (2.0/day) | 44% | +2.5 | Rs -47 | -0.69 | Rs -23,340 | -23,258 / -81 | 5/13 | locks: 33%
| target = 30% of premium | 495 (2.0/day) | 43% | +1.7 | Rs -60 | -0.75 | Rs -29,734 | -15,575 / -14,159 | 5/13 | locks: 22%
| target = 45% of premium | 495 (2.0/day) | 44% | +3.3 | Rs -11 | -0.13 | Rs -5,676 | -8,350 / +2,675 | 6/13 | locks: 13%
| target = 60% of premium | 495 (2.0/day) | 44% | +7.5 | Rs +82 | 0.82 | Rs +40,450 | +8,817 / +31,633 | 9/13 | locks: 8%
| target = 100% of premium | 495 (2.0/day) | 44% | +8.0 | Rs +97 | 0.94 | Rs +48,012 | +13,208 / +34,804 | 7/13 | locks: 3%

### 2025-02-17 .. 2026-02-23 (249 days)

| ladder | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| none (the arm today) | 545 (2.2/day) | 50% | +8.1 | Rs +40 | 0.76 | Rs +21,637 | +17,499 / +4,138 | 6/13 |
| target = 15% of premium | 545 (2.2/day) | 48% | +4.7 | Rs -4 | -0.12 | Rs -2,225 | -397 / -1,828 | 6/13 | locks: 22%
| target = 30% of premium | 545 (2.2/day) | 49% | +6.6 | Rs +40 | 0.96 | Rs +21,581 | +8,200 / +13,381 | 7/13 | locks: 12%
| target = 45% of premium | 545 (2.2/day) | 50% | +8.2 | Rs +52 | 1.05 | Rs +28,326 | +16,907 / +11,419 | 7/13 | locks: 6%
| target = 60% of premium | 545 (2.2/day) | 50% | +8.1 | Rs +54 | 1.06 | Rs +29,628 | +16,369 / +13,259 | 7/13 | locks: 4%
| target = 100% of premium | 545 (2.2/day) | 50% | +8.5 | Rs +49 | 0.95 | Rs +26,663 | +18,496 / +8,168 | 7/13 | locks: 2%
### Reading it

- The arm as it is: +55.6k / +21.6k (the same as LIQUIDITY_STOP.md, so the simulator is unchanged without a ladder).
- A tight ladder (reference target 15-30% of the premium) cuts the winners short: year A falls to -23k / -30k.
- Wider ones are about neutral: 45% -> -5.7k / +28.3k, 60% -> +40.5k / +29.6k, 100% -> +48.0k / +26.7k. None beats
  the arm in both years; summed over the two years every ladder is worse (best: 60%, -7k).
- Why: the arm already takes profit at the next liquidity level and leaves on a failed break, so the trades the
  ladder saves are few, and the ones it cuts are the big winners that pay for the arm. Not added.
