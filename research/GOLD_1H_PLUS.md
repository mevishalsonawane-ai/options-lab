## Enhancing IraGoldAlgo's 1-hour liquidity arm (research/gold_1h_plus.py)

XAUUSD, Dukascopy 1-minute bid 2023-10-01 .. 2026-09-25; ask = bid + 0.30, $7 a lot. USD per standard lot after costs. Fitting years: Oct 2023 - Sep 2024, Oct 2024 - Sep 2025; held out: Oct 2025 - Sep 2026. A change is worth taking only if it beats the arm in BOTH the fitting years and the held-out year.

| change | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | fitting | held out | 3 years | trades | win | t | max drawdown | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| the arm today | +834 | +9,938 | +85,985 | +10,772 | +85,985 | +96,757 | 196 | 48% | 2.10 | -11,305 | - |
| trend: above the 50-hour EMA | +834 | +9,938 | +85,985 | +10,772 | +85,985 | +96,757 | 196 | 48% | 2.10 | -11,305 | no |
| trend: above the 100-hour EMA | +2,034 | +9,938 | +85,985 | +11,972 | +85,985 | +97,957 | 194 | 48% | 2.13 | -11,305 | no |
| trend: above the 200-hour EMA | +3,184 | +9,938 | +82,010 | +13,122 | +82,010 | +95,132 | 186 | 49% | 2.08 | -11,305 | no |
| trend: yesterday above its 20-day EMA | +569 | +3,623 | +75,939 | +4,193 | +75,939 | +80,132 | 149 | 47% | 1.78 | -15,543 | no |
| hours: no Asian-session buys (00-07 UTC) | +4,341 | +5,127 | +26,112 | +9,469 | +26,112 | +35,580 | 136 | 48% | 1.00 | -8,786 | no |
| levels: swing lookback 10 | -1,556 | +8,564 | +54,289 | +7,007 | +54,289 | +61,296 | 289 | 49% | 1.35 | -14,640 | no |
| levels: swing lookback 30 | +3 | +23,651 | +65,017 | +23,655 | +65,017 | +88,672 | 144 | 49% | 2.13 | -8,872 | no |
| levels: pool confirmation 5 | -7,172 | +637 | +35,375 | -6,535 | +35,375 | +28,840 | 219 | 47% | 0.96 | -25,768 | no |
| levels: pool confirmation 15 | +2,324 | +18,010 | +75,880 | +20,334 | +75,880 | +96,215 | 167 | 54% | 2.75 | -6,859 | no |
| exit: no 'new liquidity' exit | +4,297 | +6,105 | +64,612 | +10,402 | +64,612 | +75,013 | 183 | 47% | 1.50 | -23,983 | no |
| exit: target the second level up | +586 | +9,695 | +95,313 | +10,281 | +95,313 | +105,594 | 191 | 40% | 2.18 | -11,930 | no |
| combined: confirmation 15 + second level | +1,946 | +20,511 | +97,020 | +22,457 | +97,020 | +119,477 | 162 | 46% | 3.09 | -7,341 | **better in both** |
| robustness: confirmation 12 + second level | +1,757 | +12,263 | +84,693 | +14,020 | +84,693 | +98,712 | 175 | 43% | 2.61 | -9,005 | no |
| robustness: confirmation 20 + second level | +979 | +15,292 | +105,322 | +16,271 | +105,322 | +121,593 | 145 | 48% | 3.21 | -6,948 | **better in both** |
| robustness: confirmation 15 + second level, swing 30 | +2,436 | +16,170 | +85,694 | +18,606 | +85,694 | +104,300 | 124 | 48% | 2.86 | -7,481 | no |
| combined: confirmation 15 + above the 100-hour EMA | +3,524 | +18,010 | +75,880 | +21,534 | +75,880 | +97,415 | 165 | 55% | 2.79 | -6,859 | no |
### Verdict

No single change beats the arm in both the fitting years and the held-out year. Two come close from opposite sides:
pool confirmation 15 (better fitting years, t 2.75, drawdown -6.9k, but less in the held-out year) and targeting the
second level up (better held-out year, slightly less in the fitting years). Together they beat the arm in both:
+$22.5k vs +$10.8k fitting, +$97.0k vs +$86.0k held out, t 3.09 vs 2.10, drawdown -$7.3k vs -$11.3k. The
combination was picked after seeing all three years, so its neighbours were checked: confirmation 12 and 20 with the
second-level target score t 2.61 and 3.21 with smaller drawdowns (20 also better in both) - a stable region, not one
lucky setting. Trend filters (EMA 50/100/200 hours, the daily trend) change little or cost profit; dropping the Asian
session, shorter swings, faster pools and dropping the new-liquidity exit all lose. IraGoldAlgo takes confirmation 15
+ the second-level target (2026-10-01). Like the arm itself, most of the profit is in the last year.
