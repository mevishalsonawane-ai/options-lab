## Liquidity 15+5: getting out sooner when the direction turns (research/liquidity_reversal.py)

BANKNIFTY, 15-min + 5-min books, real option prices, 1 lot of 30, after costs. Each variant adds one exit to the arm.

### 2024-02-13 .. 2025-02-14 (249 days)

| variant | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months | avg loss | worst trade |
|---|---|---|---|---|---|---|---|---|---|---|
| the arm today | 495 (2.0/day) | 44% | +9.3 | Rs +112 | 1.04 | Rs +55,574 | +9,698 / +45,877 | 8/13 | Rs -1,176 | Rs -15,667 |
| failed break read on every 5-min close | 495 (2.0/day) | 44% | +10.0 | Rs +124 | 1.16 | Rs +61,232 | +10,171 / +51,061 | 9/13 | Rs -1,141 | Rs -15,667 |
| failed break read on every 1-min close | 495 (2.0/day) | 39% | +11.2 | Rs +156 | 1.56 | Rs +77,116 | +26,043 / +51,073 | 10/13 | Rs -921 | Rs -9,570 |
| index stop 30 pts back through the level | 495 (2.0/day) | 44% | +13.5 | Rs +194 | 1.93 | Rs +96,040 | +28,561 / +67,479 | 11/13 | Rs -1,004 | Rs -9,570 |
| index stop 60 pts back through the level | 495 (2.0/day) | 44% | +10.7 | Rs +150 | 1.47 | Rs +74,378 | +21,148 / +53,230 | 10/13 | Rs -1,108 | Rs -9,570 |
| premium stop 10% | 495 (2.0/day) | 43% | +12.4 | Rs +169 | 1.64 | Rs +83,534 | +27,933 / +55,601 | 10/13 | Rs -1,023 | Rs -15,122 |
| time stop: not +5% after 20 min -> out | 495 (2.0/day) | 45% | +9.8 | Rs +127 | 1.24 | Rs +63,098 | +14,591 / +48,508 | 11/13 | Rs -1,058 | Rs -15,667 |
| time stop: not +0% after 30 min -> out | 495 (2.0/day) | 43% | +9.8 | Rs +114 | 1.07 | Rs +56,504 | +8,409 / +48,095 | 9/13 | Rs -1,120 | Rs -15,667 |
| 5-min failed break + 30-pt index stop | 495 (2.0/day) | 44% | +13.7 | Rs +197 | 1.96 | Rs +97,750 | +29,967 / +67,783 | 11/13 | Rs -998 | Rs -9,570 |
| 30-pt index stop + time stop +5% / 20 min | 495 (2.0/day) | 45% | +13.7 | Rs +208 | 2.16 | Rs +103,019 | +36,760 / +66,259 | 11/13 | Rs -896 | Rs -9,570 |
| 30-pt index stop + time stop 0% / 30 min | 495 (2.0/day) | 43% | +14.2 | Rs +197 | 1.99 | Rs +97,565 | +28,123 / +69,442 | 10/13 | Rs -949 | Rs -9,570 |

### 2025-02-17 .. 2026-02-23 (249 days)

| variant | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months | avg loss | worst trade |
|---|---|---|---|---|---|---|---|---|---|---|
| the arm today | 545 (2.2/day) | 50% | +8.1 | Rs +40 | 0.76 | Rs +21,637 | +17,499 / +4,138 | 6/13 | Rs -751 | Rs -3,662 |
| failed break read on every 5-min close | 545 (2.2/day) | 49% | +6.6 | Rs +17 | 0.34 | Rs +9,260 | +8,772 / +488 | 6/13 | Rs -735 | Rs -3,054 |
| failed break read on every 1-min close | 545 (2.2/day) | 45% | +6.3 | Rs +16 | 0.36 | Rs +8,701 | +568 / +8,133 | 6/13 | Rs -611 | Rs -3,054 |
| index stop 30 pts back through the level | 545 (2.2/day) | 48% | +8.2 | Rs +37 | 0.74 | Rs +20,195 | +7,854 / +12,341 | 7/13 | Rs -695 | Rs -3,054 |
| index stop 60 pts back through the level | 545 (2.2/day) | 49% | +7.5 | Rs +30 | 0.59 | Rs +16,604 | +11,969 / +4,635 | 6/13 | Rs -753 | Rs -3,054 |
| premium stop 10% | 545 (2.2/day) | 48% | +4.6 | Rs -10 | -0.21 | Rs -5,214 | -4,918 / -296 | 8/13 | Rs -733 | Rs -3,053 |
| time stop: not +5% after 20 min -> out | 545 (2.2/day) | 50% | +8.7 | Rs +64 | 1.34 | Rs +34,938 | +12,391 / +22,547 | 9/13 | Rs -636 | Rs -3,054 |
| time stop: not +0% after 30 min -> out | 545 (2.2/day) | 49% | +8.9 | Rs +61 | 1.20 | Rs +33,129 | +16,698 / +16,430 | 9/13 | Rs -688 | Rs -3,594 |
| 5-min failed break + 30-pt index stop | 545 (2.2/day) | 47% | +7.6 | Rs +27 | 0.55 | Rs +14,714 | +8,661 / +6,053 | 7/13 | Rs -689 | Rs -3,054 |
| 30-pt index stop + time stop +5% / 20 min | 545 (2.2/day) | 49% | +8.4 | Rs +56 | 1.19 | Rs +30,250 | +3,844 / +26,406 | 8/13 | Rs -604 | Rs -3,054 |
| 30-pt index stop + time stop 0% / 30 min | 545 (2.2/day) | 47% | +8.7 | Rs +52 | 1.06 | Rs +28,446 | +6,705 / +21,741 | 9/13 | Rs -646 | Rs -3,054 |
### Reading it

- The arm today: +55.6k / +21.6k, average loss -1,176 / -751, worst trade -15.7k / -3.7k.
- Better in BOTH years: a time stop (not +5% after 20 min -> out: +63.1k / +34.9k) and the time stop with a 30-point
  index stop (out the minute BANKNIFTY trades 30 points back through the broken level): +103.0k / +30.3k, average loss
  -896 / -604, worst trade -9.6k / -3.1k.
- The index stop alone: +96.0k / +20.2k (year B about the same). A 10% premium stop: +83.5k / -5.2k (fails year B).
  Reading the failed break on 1- or 5-minute closes helps year A and hurts year B.
- 11 variants were tried; the combination is the best of them, so expect less than this live. Both rules are simple
  and were not tuned beyond 20/30 minutes and 30/60 points.
