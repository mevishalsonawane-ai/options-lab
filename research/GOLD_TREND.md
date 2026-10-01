## Buying gold with the trend (research/gold_trend.py)

XAUUSD 2023-10-02 .. 2026-09-25: gold 1,848 -> 4,283. Buys only; costs and an assumed $40 a lot a night swap included. USD per standard lot.

| rule | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | of which swap | trades | win | t | max drawdown | time in market | profit / drawdown | avg month at 0.01 lot |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| buy and hold | +199,774 | +0 | +0 | +199,774 | -43,640 | 1 | 100% | 0.00 | -159,969 | 100% | 1.2 | +55.49 |
| donchian 20/10 (daily) | +21,553 | +49,504 | +50,073 | +121,129 | -20,720 | 12 | 50% | 1.46 | -105,829 | 48% | 1.1 | +33.65 |
| donchian 55/20 (daily) | +28,229 | +163,168 | -35,275 | +156,122 | -22,160 | 5 | 80% | 1.16 | -109,199 | 51% | 1.4 | +43.37 |
| supertrend 4h | +46,492 | +89,770 | +124,794 | +261,056 | -23,760 | 52 | 54% | 2.53 | -55,439 | 55% | 4.7 | +72.52 |
| supertrend 1h | +32,399 | +70,733 | +17,619 | +120,752 | -23,400 | 196 | 44% | 1.43 | -75,935 | 55% | 1.6 | +33.54 |
| supertrend 4h (7, 3) | +48,332 | +97,859 | +115,450 | +261,641 | -24,120 | 50 | 54% | 2.51 | -55,037 | 56% | 4.8 | +72.68 |
| supertrend 4h (14, 3) | +48,063 | +79,310 | +86,473 | +213,847 | -24,400 | 55 | 51% | 1.92 | -63,998 | 57% | 3.3 | +59.40 |
| supertrend 4h (10, 2) | +37,870 | +85,444 | -12,151 | +111,164 | -25,680 | 103 | 52% | 1.14 | -143,977 | 57% | 0.8 | +30.88 |
| supertrend 4h (10, 2.5) | +43,577 | +98,973 | +51,334 | +193,884 | -24,640 | 67 | 48% | 1.62 | -80,873 | 57% | 2.4 | +53.86 |
| supertrend 4h (10, 3.5) | +37,594 | +108,774 | +82,890 | +229,257 | -25,080 | 43 | 56% | 2.10 | -86,447 | 57% | 2.7 | +63.68 |
| supertrend 4h (10, 4) | +43,606 | +117,602 | +50,980 | +212,189 | -26,000 | 35 | 54% | 1.69 | -87,760 | 59% | 2.4 | +58.94 |
| supertrend 2h | +33,448 | +85,127 | -22,350 | +96,225 | -24,720 | 112 | 48% | 1.08 | -132,271 | 56% | 0.7 | +26.73 |
| supertrend 8h | +40,948 | +107,937 | +28,944 | +177,830 | -28,360 | 29 | 45% | 1.43 | -99,829 | 66% | 1.8 | +49.40 |
| ema 20/50 4h | +41,077 | +96,641 | -29,578 | +108,140 | -28,560 | 39 | 36% | 1.04 | -173,427 | 66% | 0.6 | +30.04 |
| pullback 1h (EMA 20 in an EMA 50/200 uptrend), 3 ATR trail | +48,472 | +37,974 | +20,358 | +106,804 | -16,960 | 249 | 40% | 1.31 | -128,675 | 39% | 0.8 | +29.67 |
| chandelier 4h (20-bar high break), 3 ATR trail | +33,759 | +54,843 | +43,077 | +131,679 | -16,000 | 82 | 54% | 1.58 | -119,332 | 38% | 1.1 | +36.58 |
### Verdict

Supertrend(10, 3) on 4-hour bars is the stand-out: +$261.1k a lot over three years after costs and swap, positive in
each year (+$46.5k, +$89.8k, +$124.8k), t 2.53, 52 trades, in the market 55% of the time, deepest drawdown -$55.4k
against buying and holding's -$160.0k (which made +$199.8k). Its neighbours hold up: ATR 7 and 14 with multiplier 3
make +$261.6k and +$213.8k (t 2.51, 1.92), multipliers 2.5-4 make +$194k to +$229k; only multiplier 2 and the 2-hour
chart are much weaker. Caveats: these three years were a strong rise for gold; a buy-only trend rule has not been
seen in a falling market here (by design it should then be out, but that is untested). Trades last days to weeks: at
0.01 lot the deepest drawdown is about -$554, more than a $500 account, so it needs roughly $2,000+ per 0.01 lot.
