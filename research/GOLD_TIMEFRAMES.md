## IraGoldAlgo's liquidity rule on other timeframes (research/gold_timeframes.py)

XAUUSD, Dukascopy 1-minute bid 2023-10-01 .. 2026-09-25; ask = bid + 0.30, $7 a lot. USD per standard lot after costs; fitting years Oct 2023 - Sep 2024, Oct 2024 - Sep 2025; held out Oct 2025 - Sep 2026.

| chart | settings | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | fitting | held out | 3 years | trades | win | t | max drawdown | avg month at 0.01 lot |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 15 min | old: confirm 10, nearest level | -9,952 | +3,372 | -6,222 | -6,580 | -6,222 | -12,802 | 747 | 49% | -0.39 | -37,573 | -3.56 |
| 15 min | new: confirm 15, second level | -1,193 | +6,522 | -20,812 | +5,329 | -20,812 | -15,482 | 664 | 41% | -0.45 | -37,027 | -4.30 |
| 30 min | old: confirm 10, nearest level | +4,438 | +6,411 | +34,235 | +10,849 | +34,235 | +45,084 | 389 | 51% | 0.96 | -16,008 | +12.52 |
| 30 min | new: confirm 15, second level | +3,175 | +26,085 | +21,749 | +29,260 | +21,749 | +51,009 | 352 | 43% | 1.09 | -17,045 | +14.17 |
| 1 hour | old: confirm 10, nearest level | +834 | +9,938 | +85,985 | +10,772 | +85,985 | +96,757 | 196 | 48% | 2.10 | -11,305 | +26.88 |
| 1 hour | new: confirm 15, second level | +1,946 | +20,511 | +97,020 | +22,457 | +97,020 | +119,477 | 162 | 46% | 3.09 | -7,341 | +33.19 |
| 2 hours | old: confirm 10, nearest level | +9,216 | +5,980 | +50,078 | +15,196 | +50,078 | +65,274 | 91 | 65% | 1.47 | -22,040 | +18.13 |
| 2 hours | new: confirm 15, second level | +10,931 | +9,218 | +81,046 | +20,149 | +81,046 | +101,195 | 81 | 59% | 2.11 | -20,425 | +28.11 |
| 4 hours | old: confirm 10, nearest level | +1,682 | +568 | +40,196 | +2,250 | +40,196 | +42,446 | 34 | 74% | 1.92 | -3,951 | +11.79 |
| 4 hours | new: confirm 15, second level | +3,645 | -2,574 | +39,351 | +1,071 | +39,351 | +40,421 | 31 | 61% | 1.88 | -4,263 | +11.23 |
### Verdict

The 1-hour chart with the new settings stays the best: the most profit (+$119.5k a lot), the highest t (3.09) and a
small drawdown (-$7.3k). The new settings also help on 30 minutes and 2 hours (and are about even on 4 hours).
2 hours is the runner-up (+$101.2k, t 2.11) but with a drawdown of -$20.4k on 81 trades; 4 hours trades about once a
month (31 trades, 61% won, +$40.4k); 30 minutes is weak (t 1.09) and 15 minutes loses. IraGoldAlgo stays on 1 hour.
