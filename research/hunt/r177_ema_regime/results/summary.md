# R177-EMA for Boss: 9/21 EMA crossover + all three filters

**It does not work. No version passed the gates, including all three filters together.**

All three together (1 lot, NIFTY + BANKNIFTY, calls + puts): -Rs 26,183 (40, 30%) over the full period and -Rs 6,892 (8, 12%) in the last 6 months. The plain crossover: -Rs 1.06 L (660, 37%) and -Rs 3,703 (174, 40%).

Net after real charges and slippage, 1 lot, both indices, calls + puts (trades, win %):

| rule | full period (May 2024 - Feb 2026) | last 6 months (Aug 2025 - Feb 2026) | max drawdown (full) | worst month (full) |
|---|---|---|---|---|
| plain 9/21 crossover (no filter) | -Rs 1.06 L (660, 37%) | -Rs 3,703 (174, 40%) | Rs 1.59 L | -Rs 45,982 |
| filter 1 alone: ADX(14) > 25 | Rs 13,438 (163, 39%) | -Rs 3,259 (47, 38%) | Rs 36,414 | -Rs 22,704 |
| filter 2 alone: 1-hour trend + VWAP | -Rs 97,633 (234, 33%) | -Rs 10,810 (58, 33%) | Rs 1.22 L | -Rs 32,398 |
| filter 3 alone: 2 x ATR index stop | -Rs 1.15 L (660, 36%) | -Rs 1,845 (174, 40%) | Rs 1.64 L | -Rs 46,169 |
| all three together (the PDF's full rule) | -Rs 26,183 (40, 30%) | -Rs 6,892 (8, 12%) | Rs 34,687 | -Rs 23,746 |
| all three, 1-hour trend as EMA 9 > 21 | -Rs 23,576 (30, 27%) | -Rs 7,698 (9, 11%) | Rs 23,576 | -Rs 13,311 |
| all three, TWAP instead of the volume-weighted VWAP | -Rs 26,968 (40, 30%) | -Rs 6,892 (8, 12%) | Rs 35,472 | -Rs 23,746 |
| all three + clearing the opening range | -Rs 12,753 (17, 35%) | -Rs 1,941 (2, 0%) | Rs 23,221 | -Rs 18,382 |
| all three + a 2R target | -Rs 16,939 (40, 32%) | -Rs 6,892 (8, 12%) | Rs 33,772 | -Rs 23,746 |
| all three, 1-ITM strike | -Rs 27,719 (40, 30%) | -Rs 7,594 (8, 12%) | Rs 38,253 | -Rs 26,222 |

In plain words:

- **Stacking the filters cut the trades, not the losing.** Trades fell from 660 to 40 and the total loss from -Rs 1.06 L to -Rs 26,183, but the loss per trade got worse (-Rs 160 -> -Rs 655) and the win rate fell (37% -> 30%). In the last 6 months all three together gave only 8 trades (12% won).
- The only version in profit over the whole period was **ADX alone**: Rs 13,438 (163, 39%), all of it in the design months; in the last 6 months its net was -Rs 3,259 on 47 trades.
- The PDF's other choices (1-hour trend as EMA 9 > 21, a plain TWAP, clearing the opening range, a 2R target, a 1-ITM strike) did not change the picture: every one lost over the full period.
- **No version beat random entries** on the same days: the lowest twin p of all 270 tests is 0.26 (the bar is 0.05), and the multiple-testing q is 1.00 everywhere.
- This matches every earlier test here: EMA 8/21 + ADX (R44 / R51: -Rs 50,947 on 3,129 design trades, -Rs 32,749 on 1,262 final), ADX + EMA + ATR (book-porwal-08: -Rs 1.23 L on 1,115 trades), VWAP filters (H46, 2,400 variants), ATR stops (R17 / R20) and ADX (rejected in 77 of 91 tests).
- **Nothing in the app changes.** It should not be added as an arm.

All three together, per index and lots (net (trades, win %)):

| index | lots | full period | last 6 months | worst day (full) | max drawdown (full) |
|---|---|---|---|---|---|
| NIFTY | 1 | -Rs 19,340 (21, 24%) | -Rs 6,891 (5, 0%) | -Rs 7,665 | Rs 27,472 |
| NIFTY | 2 | -Rs 37,688 (21, 24%) | -Rs 13,545 (5, 0%) | -Rs 15,236 | Rs 54,189 |
| NIFTY | 3 | -Rs 56,036 (21, 24%) | -Rs 20,200 (5, 0%) | -Rs 22,806 | Rs 80,905 |
| BANKNIFTY | 1 | -Rs 6,843 (19, 37%) | -Rs 1 (3, 33%) | -Rs 8,307 | Rs 15,162 |
| BANKNIFTY | 2 | -Rs 12,789 (19, 37%) | Rs 139 (3, 33%) | -Rs 16,519 | Rs 30,041 |
| BANKNIFTY | 3 | -Rs 18,736 (19, 37%) | Rs 279 (3, 33%) | -Rs 24,731 | Rs 44,920 |
| both indices | 1 | -Rs 26,183 (40, 30%) | -Rs 6,892 (8, 12%) | -Rs 15,972 | Rs 34,687 |
| both indices | 2 | -Rs 50,477 (40, 30%) | -Rs 13,406 (8, 12%) | -Rs 31,754 | Rs 68,100 |
| both indices | 3 | -Rs 74,772 (40, 30%) | -Rs 19,921 (8, 12%) | -Rs 47,537 | Rs 1.02 L |

Calls vs puts, all three together, 1 lot, both indices: calls -Rs 22,169 (20, 15%), puts -Rs 4,013 (20, 45%) (full period).

**How it was tested.** Signals on the index (15-minute bars; the 1-hour trend from completed 1-hour bars); the ATM option bought at the next minute; out on the opposite crossover, the 2 x ATR index stop or 15:10. Real option prices, R17 slippage, dated Zerodha charges, stops sold as stop orders. Written down before any number (PREREG.md); the last 6 months were read once; 2,000 random-entry twins for the p-value; Benjamini-Hochberg over 4,950 tests. Data: NIFTY and BANKNIFTY only, May 2024 - 23 Feb 2026 (the newer data is on the Drive lake, not reachable here). VWAP uses option-chain volume as its weight (the index has no volume).

Full tables (every index, side and lot, month by month, gates): `report.md`.
