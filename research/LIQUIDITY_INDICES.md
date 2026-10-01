## Liquidity 15+5 on other indices (research/liquidity_indices.py)

Rule: both tools agree (pool broken where a swing zone sits), swing lookback 20, pool confirmation 10, stop on a failed break, sell at the next liquidity / new liquidity / 15:10; the 15-minute and 5-minute books side by side.
Index points x lot = what the move was worth on the index (a futures-like view, before costs). Option P&L only where the option history exists.

| index, year, chart | trades | index moved our way | index pts / trade | t (pts) | pts x lot (Rs, before costs) | ATM option P&L (1 lot, after costs) |
|---|---|---|---|---|---|---|
| NIFTY, Feb 2024 - Feb 2025, 15-min | 109 (0.5/day) | 59% | -0.3 | -0.06 | -2,839 | Rs -21,914 (45% win) |
| NIFTY, Feb 2024 - Feb 2025, 5-min | 319 (1.5/day) | 55% | +0.5 | 0.20 | +12,529 | Rs -22,154 (40% win) |
| **NIFTY, Feb 2024 - Feb 2025, 15+5 together** | 428 (2.0/day) | 56% | +0.3 | 0.12 | +9,690 | Rs -44,068 (41% win) |
| NIFTY, Feb 2025 - Feb 2026, 15-min | 133 (0.5/day) | 64% | +4.2 | 0.84 | +41,820 | Rs +12,845 (50% win) |
| NIFTY, Feb 2025 - Feb 2026, 5-min | 397 (1.4/day) | 60% | +0.9 | 0.57 | +27,105 | Rs -35,301 (40% win) |
| **NIFTY, Feb 2025 - Feb 2026, 15+5 together** | 530 (1.9/day) | 61% | +1.7 | 1.00 | +68,925 | Rs -22,456 (43% win) |
| FINNIFTY, Feb 2024 - Feb 2025, 15-min | 119 (0.5/day) | 60% | +5.5 | 0.70 | +42,351 | no option history |
| FINNIFTY, Feb 2024 - Feb 2025, 5-min | 375 (1.5/day) | 57% | +6.0 | 1.98 | +145,360 | no option history |
| **FINNIFTY, Feb 2024 - Feb 2025, 15+5 together** | 494 (2.0/day) | 58% | +5.8 | 1.98 | +187,710 | no option history |
| FINNIFTY, Feb 2025 - Feb 2026, 15-min | 110 (0.4/day) | 61% | +7.1 | 1.23 | +50,726 | no option history |
| FINNIFTY, Feb 2025 - Feb 2026, 5-min | 365 (1.4/day) | 58% | +2.5 | 1.29 | +59,485 | no option history |
| **FINNIFTY, Feb 2025 - Feb 2026, 15+5 together** | 475 (1.9/day) | 59% | +3.6 | 1.78 | +110,211 | no option history |
| SENSEX, Feb 2024 - Feb 2025, 15-min | 135 (0.5/day) | 57% | +2.3 | 0.17 | +6,220 | no option history |
| SENSEX, Feb 2024 - Feb 2025, 5-min | 375 (1.5/day) | 53% | +5.2 | 0.72 | +39,335 | no option history |
| **SENSEX, Feb 2024 - Feb 2025, 15+5 together** | 510 (2.0/day) | 54% | +4.5 | 0.69 | +45,555 | no option history |
| SENSEX, Feb 2025 - Feb 2026, 15-min | 109 (0.4/day) | 59% | +2.4 | 0.14 | +5,176 | no option history |
| SENSEX, Feb 2025 - Feb 2026, 5-min | 364 (1.4/day) | 61% | -1.2 | -0.22 | -9,011 | no option history |
| **SENSEX, Feb 2025 - Feb 2026, 15+5 together** | 473 (1.9/day) | 60% | -0.4 | -0.07 | -3,835 | no option history |
### Reading it

- BANKNIFTY for comparison (LIQUIDITY_MORE.md): 15+5 together +9.1 / +8.1 index pts a trade, ATM options +Rs 32k / +58k.
- NIFTY with REAL option prices: loses in both years (-Rs 44k, -Rs 22k); the index move per trade is only +0.3 / +1.7
  pts (NIFTY moves ~1/2.4 of BANKNIFTY in points, but the costs per trade are the same), so the options cannot pay.
- FINNIFTY: the index moved our way in both years (+5.8 / +3.6 pts a trade, t 1.98 / 1.78). Rough option estimate from
  the BANKNIFTY / NIFTY relation (about half the index move x lot, less Rs 40 charges and ~1 pt a side slippage):
  about +Rs 40k / +Rs 6k a year on 1 lot of 65 - near break-even in the second year, and FINNIFTY now has monthly
  options only, with thinner trading (wider spreads) than BANKNIFTY.
- SENSEX: +4.5 / -0.4 pts a trade; estimate about -Rs 8k / -Rs 30k a year on 1 lot of 20 - no.
