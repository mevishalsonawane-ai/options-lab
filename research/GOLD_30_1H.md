## IraGoldAlgo with 30-minute + 1-hour books (research/gold_30_1h.py)

Each book holds at most one buy of 1 lot; both can be open together. USD after costs. Fitting years Oct 2023 - Sep 2024, Oct 2024 - Sep 2025; held out Oct 2025 - Sep 2026.

| books | settings | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | fitting | held out | 3 years | trades | win | t | max drawdown (daily) | days both books traded | avg month at 0.01 lot a book |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 hour alone | old: confirm 10, nearest level | +834 | +9,938 | +85,985 | +10,772 | +85,985 | +96,757 | 196 | 48% | 2.10 | -11,305 | - | +26.88 |
| 30 min alone | old: confirm 10, nearest level | +4,438 | +6,411 | +34,235 | +10,849 | +34,235 | +45,084 | 389 | 51% | 0.96 | -14,413 | - | +12.52 |
| 30 min + 1 hour | old: confirm 10, nearest level | +5,273 | +16,348 | +120,221 | +21,621 | +120,221 | +141,842 | 585 | 50% | 2.16 | -16,723 | 126 | +39.40 |
| 1 hour alone | new: confirm 15, second level | +1,946 | +20,511 | +97,020 | +22,457 | +97,020 | +119,477 | 162 | 46% | 3.09 | -6,998 | - | +33.19 |
| 30 min alone | new: confirm 15, second level | +3,175 | +26,085 | +21,749 | +29,260 | +21,749 | +51,009 | 352 | 43% | 1.09 | -16,876 | - | +14.17 |
| 30 min + 1 hour | new: confirm 15, second level | +5,121 | +46,596 | +118,769 | +51,717 | +118,769 | +170,486 | 514 | 44% | 2.80 | -15,271 | 103 | +47.36 |
### Verdict

30 min + 1 hour (new settings) earns more than 1 hour alone (+$170.5k vs +$119.5k, t 2.80 vs 3.09) because it holds
up to twice the gold, but it more than doubles the drawdown (-$15.3k vs -$7.0k). The 30-minute book is the weak one
(t 1.09) and adds about $1 of drawdown for every $1 of profit; 1 hour at twice the lot instead would make about
+$239k with a drawdown near -$14k. Better use of the same money: more lot on 1 hour, not a 30-minute book.
