## Liquidity 15+5 with a +40 target and the profit lock (research/liquidity_target40.py)

BANKNIFTY, 15-min + 5-min books, real option prices, 1 lot of 30, after costs. The arm's other exits stay (next liquidity, failed break, new liquidity, 15:10).

### 2024-02-13 .. 2025-02-14 (249 days)

| version | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| the arm today (no fixed target) | 495 (2.0/day) | 44% | +9.3 | Rs +112 | 1.04 | Rs +55,574 | +9,698 / +45,877 | 8/13 | next liquidity 53%, failed break 25%, premium stop 18%, 15:10 2%
| +40 target | 495 (2.0/day) | 53% | +5.5 | Rs -75 | -1.20 | Rs -36,927 | -37,571 / +644 | 5/13 | next liquidity 33%, premium target 31%, failed break 21%, premium stop 14%
| +40 target + profit lock | 495 (2.0/day) | 42% | +2.9 | Rs -125 | -2.34 | Rs -61,875 | -49,869 / -12,006 | 5/13 | profit lock 36%, next liquidity 28%, premium target 17%, failed break 12%
| +40 target + profit lock, no 15% stop | 495 (2.0/day) | 42% | +1.7 | Rs -117 | -2.39 | Rs -58,087 | -45,631 / -12,456 | 4/13 | profit lock 37%, next liquidity 28%, failed break 18%, premium target 17%
| +40 target, -40 stop (exactly the ORB's), + profit lock | 495 (2.0/day) | 39% | +3.5 | Rs -132 | -3.39 | Rs -65,187 | -49,858 / -15,328 | 4/13 | profit lock 33%, next liquidity 26%, points stop 18%, premium target 15%

### 2025-02-17 .. 2026-02-23 (249 days)

| version | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|
| the arm today (no fixed target) | 545 (2.2/day) | 50% | +8.1 | Rs +40 | 0.76 | Rs +21,637 | +17,499 / +4,138 | 6/13 | next liquidity 55%, failed break 33%, premium stop 6%, new liquidity 3%
| +40 target | 545 (2.2/day) | 56% | +7.4 | Rs +66 | 1.82 | Rs +35,899 | +24,490 / +11,410 | 9/13 | next liquidity 44%, failed break 30%, premium target 22%, premium stop 4%
| +40 target + profit lock | 546 (2.2/day) | 49% | +7.0 | Rs +39 | 1.45 | Rs +21,453 | +14,437 / +7,016 | 8/13 | next liquidity 39%, profit lock 29%, failed break 18%, premium target 11%
| +40 target + profit lock, no 15% stop | 546 (2.2/day) | 49% | +7.0 | Rs +40 | 1.48 | Rs +21,874 | +14,396 / +7,478 | 9/13 | next liquidity 39%, profit lock 29%, failed break 20%, premium target 11%
| +40 target, -40 stop (exactly the ORB's), + profit lock | 546 (2.2/day) | 48% | +6.1 | Rs +3 | 0.11 | Rs +1,687 | +3,063 / -1,376 | 6/13 | next liquidity 39%, profit lock 27%, failed break 15%, premium target 11%
### Reading it

- The arm as it is: +55.6k / +21.6k (two years +77.2k).
- +40 target: -36.9k / +35.9k (-1.0k). It caps the big winners that carry year A.
- +40 target + profit lock: -61.9k / +21.5k (-40.4k). Worse again: the lock sells many trades at the price paid.
- With the ORB's exact -40 stop too: -65.2k / +1.7k.
- Not added: the arm's profit comes from a minority of trades that run well past +40 to the next liquidity level.
