## Liquidity 15+5 with a trailing profit stop (research/liquidity_trail.py)

BANKNIFTY, 15-min + 5-min books, real option prices, 1 lot of 30, after costs. 'From +5%' = the trail starts once the option has been 5% above the price paid (about Rs 1,000 on a 700 premium x 30).

### 2024-02-13 .. 2025-02-14 (249 days)

| variant | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months | avg win | avg loss |
|---|---|---|---|---|---|---|---|---|---|---|
| the arm today | 495 (2.0/day) | 44% | +9.3 | Rs +112 | 1.04 | Rs +55,574 | +9,698 / +45,877 | 8/13 | Rs 1,736 | Rs -1,176 |
| trail: keep 50% of the best, from +3% | 495 (2.0/day) | 52% | +0.1 | Rs -80 | -1.29 | Rs -39,822 | -24,734 / -15,088 | 4/13 | Rs 648 | Rs -879 |
| trail: keep 50% of the best, from +5% | 495 (2.0/day) | 53% | +1.6 | Rs -61 | -0.95 | Rs -30,234 | -24,483 / -5,751 | 5/13 | Rs 736 | Rs -950 |
| trail: keep 50% of the best, from +10% | 495 (2.0/day) | 51% | -0.3 | Rs -87 | -1.22 | Rs -42,827 | -22,565 / -20,262 | 5/13 | Rs 913 | Rs -1,107 |
| trail: keep 70% of the best, from +5% | 495 (2.0/day) | 53% | +2.2 | Rs -54 | -0.84 | Rs -26,658 | -21,183 / -5,474 | 4/13 | Rs 743 | Rs -957 |
| trail: keep 30% of the best, from +5% | 495 (2.0/day) | 52% | +3.8 | Rs -10 | -0.12 | Rs -4,851 | -15,855 / +11,004 | 6/13 | Rs 853 | Rs -942 |
| trail: keep 50% of the best, from +25% | 495 (2.0/day) | 46% | +3.9 | Rs +10 | 0.11 | Rs +5,183 | +3,757 / +1,426 | 7/13 | Rs 1,378 | Rs -1,158 |
| trail: keep 50% of the best, from +40% | 495 (2.0/day) | 45% | +8.1 | Rs +89 | 0.87 | Rs +43,971 | +7,136 / +36,835 | 8/13 | Rs 1,643 | Rs -1,175 |
| index stop + time stop (no trail) | 495 (2.0/day) | 45% | +13.7 | Rs +208 | 2.16 | Rs +103,019 | +36,760 / +66,259 | 11/13 | Rs 1,566 | Rs -896 |
| index + time stop + trail 50% from +25% | 495 (2.0/day) | 46% | +8.9 | Rs +115 | 1.41 | Rs +56,697 | +27,570 / +29,127 | 10/13 | Rs 1,266 | Rs -884 |
| index + time stop + trail 50% from +5% | 495 (2.0/day) | 51% | +4.7 | Rs +7 | 0.13 | Rs +3,492 | -5,956 / +9,448 | 7/13 | Rs 735 | Rs -761 |
| index + time stop + trail 50% from +10% | 495 (2.0/day) | 50% | +4.7 | Rs +12 | 0.21 | Rs +6,114 | +2,373 / +3,741 | 7/13 | Rs 883 | Rs -854 |

### 2025-02-17 .. 2026-02-23 (249 days)

| variant | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months | avg win | avg loss |
|---|---|---|---|---|---|---|---|---|---|---|
| the arm today | 545 (2.2/day) | 50% | +8.1 | Rs +40 | 0.76 | Rs +21,637 | +17,499 / +4,138 | 6/13 | Rs 845 | Rs -751 |
| trail: keep 50% of the best, from +3% | 545 (2.2/day) | 58% | +5.7 | Rs +23 | 0.78 | Rs +12,540 | +3,097 / +9,443 | 5/13 | Rs 437 | Rs -549 |
| trail: keep 50% of the best, from +5% | 545 (2.2/day) | 56% | +5.3 | Rs +18 | 0.51 | Rs +9,678 | +11,290 / -1,611 | 7/13 | Rs 546 | Rs -653 |
| trail: keep 50% of the best, from +10% | 545 (2.2/day) | 54% | +7.2 | Rs +50 | 1.19 | Rs +27,321 | +16,586 / +10,735 | 9/13 | Rs 705 | Rs -712 |
| trail: keep 70% of the best, from +5% | 545 (2.2/day) | 56% | +6.0 | Rs +29 | 0.81 | Rs +15,538 | +14,519 / +1,019 | 8/13 | Rs 560 | Rs -657 |
| trail: keep 30% of the best, from +5% | 545 (2.2/day) | 55% | +6.2 | Rs +20 | 0.50 | Rs +10,887 | +10,355 / +532 | 7/13 | Rs 560 | Rs -646 |
| trail: keep 50% of the best, from +25% | 545 (2.2/day) | 51% | +9.1 | Rs +72 | 1.38 | Rs +39,307 | +25,367 / +13,940 | 7/13 | Rs 866 | Rs -742 |
| trail: keep 50% of the best, from +40% | 545 (2.2/day) | 50% | +8.4 | Rs +52 | 0.98 | Rs +28,196 | +21,186 / +7,010 | 7/13 | Rs 859 | Rs -752 |
| index stop + time stop (no trail) | 545 (2.2/day) | 49% | +8.4 | Rs +56 | 1.19 | Rs +30,250 | +3,844 / +26,406 | 8/13 | Rs 753 | Rs -604 |
| index + time stop + trail 50% from +25% | 545 (2.2/day) | 49% | +9.3 | Rs +81 | 1.73 | Rs +44,049 | +10,532 / +33,517 | 9/13 | Rs 774 | Rs -594 |
| index + time stop + trail 50% from +5% | 545 (2.2/day) | 53% | +4.7 | Rs +12 | 0.41 | Rs +6,784 | +766 / +6,018 | 8/13 | Rs 503 | Rs -541 |
| index + time stop + trail 50% from +10% | 545 (2.2/day) | 51% | +6.8 | Rs +45 | 1.25 | Rs +24,758 | +5,801 / +18,957 | 9/13 | Rs 630 | Rs -573 |
### Reading it

- A trailing profit stop that starts early (keep 30-70% of the best from +3 to +10%) turns year A from +55.6k into a
  loss (-5k to -43k): it raises the win rate (44% -> 52%) but halves the average winner (Rs 1,736 -> Rs 650-900).
- Started late it is mixed: from +25%: +5.2k / +39.3k; from +40%: +44.0k / +28.2k (arm +55.6k / +21.6k).
- With the index + time stop: no trail +103.0k / +30.3k; adding the +25% trail +56.7k / +44.0k (two years 133k -> 101k).
- The arm's profit is in a few trades that run far; any stop that trails the best price gives most of them back on an
  ordinary pullback. Not recommended; the index stop + time stop (LIQUIDITY_REVERSAL.md) is what cuts the losses.
