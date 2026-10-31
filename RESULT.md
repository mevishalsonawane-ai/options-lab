# R139 result (31 Oct 2026): market structure, then its enhancements and reversal logic. Nothing passes
1. Boss asked for a strategy on "equity shift, lower low, lower high". 12 pre-registered rules from internet sources (SOURCES.md), signal on the index, ATM option, 1 lot, real costs.
2. Best: S3 CHoCH reversal, +Rs 185 a trade on 1,663 (design), but past the -Rs 40k / -Rs 13k account stop; holdout +Rs 61 on 291, no better than random. Boss's "sell the lower high" lost (-Rs 148 on 4,218).
3. Amendment A (Boss: add liquidity, volume, order flow, SMC): 19 add-ons on S3, 0 pass. 9 kept S3 positive in design but all broke the account stop.
4. A's design favourites lost on the newer months (already seen): displacement -Rs 184 (144), option-volume surge -Rs 298 (68), OI flow -Rs 227 (98); best-3 stack -Rs 1,719 (18).
5. A: the 60% premium lock lifts wins to 68% but loses money (-Rs 48 on 1,783). Order-block retest entries are worse (-Rs 82 on 622).
6. A: only the stop cap (skip stops wider than 1.5 x ATR) stayed inside the account stop: +Rs 617 on 117 trades, too few; holdout 8 trades, -Rs 2,213 each.
7. Amendment B (Boss: reversal logic): 0 of 12. Flipping the rules still loses (9 of 11; option decay and costs hit both sides). Stop-and-reverse on S3: +Rs 174 vs +Rs 185, holdout +Rs 75 on 325, not better than random.
8. Volume and order flow come from the option chain (the index has no volume; no tick/bid-ask history). Win rates 32-49%, except the lock's 68%.
9. Nothing goes on paper or live. All 43 versions are rejected and logged (research/experiments/entries/r139-*, r139a-*, r139b-*).
10. Write-up: research/HUNT_R139_MARKET_STRUCTURE.md; code, tests, PREREG, PREREG_A, PREREG_B, SOURCES: research/hunt/r139_market_structure/.
