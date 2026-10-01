## Gold strategies at 3:1 leverage on $500 (research/gold_leverage.py)

Each trade holds gold worth 3x the balance (compounding); costs included. Oct 2023 - Sep 2026.

| strategy | trades | balance Sep 2024 | Sep 2025 | Sep 2026 | 3 years | deepest drawdown | avg month | median month | best month | worst month | green months | fixed $1,500 position: 3 years (drawdown) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 1h (IraGoldAlgo now), buys only | 162 | $509 | $609 | $1,151 | +130% | -9% | +2.5% | +0.6% | +25.7% | -3.9% | 20/36 | +438 (-46) |
| Option 1: EMA 9/21 + RSI M15, H1 bias, 2R, buys only | 265 | $512 | $545 | $569 | +14% | -16% | +0.4% | +0.0% | +7.2% | -6.4% | 18/36 | +79 (-81) |
| Option 1: EMA 9/21 + RSI M15, H1 bias, opposite cross, buys + sells | 530 | $578 | $788 | $872 | +74% | -21% | +1.7% | +1.7% | +14.6% | -11.2% | 21/36 | +318 (-115) |
| Option 2: Asian-range sweep M5, 100% target, buys only | 269 | $459 | $460 | $481 | -4% | -18% | -0.0% | -1.0% | +18.8% | -4.8% | 13/36 | -5 (-99) |
### Notes

- At 3:1, $500 holds about $1,500 of gold, roughly 0.35 oz: below a 0.01-lot (1 oz) trade. A broker with a 0.01-lot
  minimum needs about $1,400 at 3:1; on $500 the smallest real trade is already about 8:1 leverage.
- The 1-hour liquidity arm turns $500 into $1,151 (+130%) with a deepest drawdown of 9%; most of the gain came in
  the last year (balance $609 after two years). The best of the owner's strategies make +14% (buys) or +74% (buys
  and sells, which the app does not do) with drawdowns of 16-21%; the Asian-range sweep loses 4%.
