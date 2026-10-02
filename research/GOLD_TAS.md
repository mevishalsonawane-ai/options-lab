## "Trend Analysis Strategy" on XAUUSD (research/gold_tas.py)

2023-10-02 .. 2026-09-25. Its own stop (tracker line), targets 1.5 / 2.5 / 3.5 R, breakeven after the first, out when the tracker turns. Costs included. USD per standard lot; the last year is held out.

| chart | tracker ATR | direction | entries | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | deepest drawdown | avg month at 0.01 lot |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 15m | 19 | buys only | flip + delayed | +31,623 | +25,423 | +15,511 | +72,557 | 947 | 38% | 0.92 | -74,323 | +20.2 |
| 15m | 19 | buys only | flip only | +21,861 | +17,953 | +16,072 | +55,886 | 860 | 38% | 0.77 | -61,700 | +15.5 |
| 15m | 19 | both ways | flip + delayed | -14,379 | -12,157 | +73,831 | +47,295 | 1880 | 35% | 0.38 | -86,221 | +13.1 |
| 15m | 19 | both ways | flip only | -17,497 | -22,195 | +63,934 | +24,242 | 1708 | 36% | 0.21 | -110,067 | +6.7 |
| 15m | 14 | buys only | flip + delayed | +25,285 | +38,865 | +7,219 | +71,369 | 961 | 39% | 0.91 | -89,799 | +19.8 |
| 15m | 14 | buys only | flip only | +24,419 | +21,389 | +2,104 | +47,912 | 877 | 39% | 0.66 | -80,716 | +13.3 |
| 15m | 14 | both ways | flip + delayed | -20,661 | +2,564 | +51,032 | +32,936 | 1911 | 36% | 0.27 | -106,306 | +9.1 |
| 15m | 14 | both ways | flip only | -20,033 | -26,041 | +43,411 | -2,663 | 1730 | 36% | -0.02 | -102,422 | -0.7 |
| 30m | 19 | buys only | flip + delayed | +22,731 | +56,252 | +80,745 | +159,728 | 470 | 41% | 1.97 | -71,227 | +44.4 |
| 30m | 19 | both ways | flip + delayed | -18,018 | +54,213 | +86,501 | +122,696 | 937 | 38% | 1.00 | -82,618 | +34.1 |
| 1h | 19 | buys only | flip + delayed | +47,624 | +52,151 | +65,704 | +165,479 | 222 | 45% | 2.21 | -38,065 | +46.0 |
| 1h | 19 | both ways | flip + delayed | +19,364 | +1,859 | +82,626 | +103,849 | 444 | 36% | 0.92 | -46,476 | +28.8 |
| 4h | 19 | buys only | flip + delayed | +20,903 | +82,477 | +51,464 | +154,843 | 59 | 56% | 2.22 | -43,009 | +43.0 |
| 4h | 19 | both ways | flip + delayed | +3,067 | +67,208 | +133,660 | +203,935 | 116 | 49% | 1.79 | -44,332 | +56.6 |

### What it says

- **15 minutes, ATR 19 (the ask): weak.** Buys only made +$72.6k in three years a standard lot, but t 0.92 (not
  distinguishable from luck), 38% of trades won, and the deepest drawdown was -$74.3k - as deep as the whole profit.
  The held-out year was the weakest (+$15.5k). ATR 19 is a little better than the default 14 (+$71.4k, -$89.8k).
- **Both ways loses on 15 minutes**: the sells lost in both fitting years; only the held-out year's swings saved it.
  (The gold app buys only anyway.)
- **The same rules work better on slower charts.** 1 hour, ATR 19, buys only: +$165.5k (+47.6k, +52.2k, +65.7k - each
  year positive, the held-out year the best), 222 trades, 45% won, t 2.21, deepest drawdown -$38.1k. 4 hours: +$154.8k,
  only 59 trades, drawdown -$43.0k.
- Against the arms already in the app: Trend 4h +$321.9k (dd -$39.2k), Dip 1h+30m +$131.0k (dd -$42.3k). TAS 1h sits
  between them with the shallowest drawdown of the three, and enters at different times (a tracker flip, not a 4-hour
  Supertrend or a 30-minute dip), so it could be a fourth paper arm; TAS 15m should not be.
- Gold rose about 140% in these years, which flatters every buys-only rule.

Caveat: the paste stopped before the entry / exit block, so the entry (tracker flip with the score filter, delayed
entry up to 10 bars, one trade per up-trend) follows the inputs' descriptions rather than the script's own code.

**Added to IraGoldAlgo as the fourth paper arm, "TAS 1h" (2026-10-02)**: engine/gold/GoldTas.kt, replayed over the same
history by GoldTasReplayTest - 222 trades, +$165,479, the same as this script.
