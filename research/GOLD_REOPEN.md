## The reopen drift (research/gold_reopen.py)

Buy at the first time, sell at the second, every trading night or day; ask = bid + 0.30, $7 a lot. USD per standard lot.

| hold (UTC) | IST | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | max drawdown | avg month at 0.01 lot |
|---|---|---|---|---|---|---|---|---|---|---|
| 22:05-23:55 | 03:35-05:25 | -1,580 | -4,015 | +11,966 | +6,370 | 479 | 49% | 0.25 | -17,665 | +1.77 |
| 22:05-22:55 | 03:35-04:25 | -4,509 | -4,838 | +7,416 | -1,930 | 479 | 46% | -0.12 | -10,480 | -0.54 |
| 23:00-23:55 | 04:30-05:25 | +6,975 | +2,023 | +72,225 | +81,223 | 707 | 54% | 2.80 | -11,654 | +22.56 |
| 22:05-00:55 | 03:35-06:25 | -5,416 | +4,795 | +20,365 | +19,743 | 479 | 50% | 0.55 | -23,429 | +5.48 |
| 22:05-06:55 | 03:35-12:25 | -2,651 | +35,631 | -18,443 | +14,537 | 479 | 52% | 0.24 | -64,325 | +4.04 |
| 01:05-02:55 | 06:35-08:25 | -4,264 | +16,368 | -14,486 | -2,382 | 718 | 50% | -0.05 | -54,621 | -0.66 |
| 08:05-09:55 | 13:35-15:25 | -3,609 | +1,792 | -57,607 | -59,424 | 716 | 49% | -1.64 | -71,650 | -16.51 |
| 13:35-15:25 | 19:05-20:55 | -7,115 | +22,510 | -70,975 | -55,580 | 718 | 51% | -0.98 | -105,233 | -15.44 |
| 18:05-19:55 | 23:35-01:25 | -225 | +7,136 | -24,941 | -18,030 | 695 | 51% | -0.55 | -40,246 | -5.01 |
### Measured from each day's reopen

| buy | sell | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | max drawdown | avg month at 0.01 lot |
|---|---|---|---|---|---|---|---|---|---|---|
| reopen +5 min | reopen +60 min | -97 | -5,344 | +36,938 | +31,497 | 716 | 48% | 1.22 | -12,756 | +8.75 |
| reopen +5 min | reopen +120 min | +5,796 | +1,866 | +55,052 | +62,714 | 716 | 52% | 1.83 | -20,346 | +17.42 |
| reopen +60 min | reopen +120 min | -2,746 | -3,437 | +9,552 | +3,369 | 716 | 49% | 0.14 | -19,615 | +0.94 |
| reopen +5 min | reopen +30 min | +3,294 | -12,303 | +11,658 | +2,649 | 720 | 44% | 0.14 | -17,771 | +0.74 |
| reopen +30 min | reopen +90 min | -10,240 | +891 | +34,122 | +24,774 | 718 | 48% | 0.98 | -12,958 | +6.88 |
### Verdict

Holding gold through the reopen made money (from the reopen +5 to +120 minutes: +$62.7k a lot, t 1.83), but almost
all of it in the last year (+$55.1k; +$5.8k and +$1.9k before), when gold rose fastest. The fixed 23:00-23:55 UTC hour
looked better (t 2.80) but it is the best of nine windows tried and mixes the first and second hour after the open
across summer and winter, so it is likely luck. Not a reliable edge: a candidate to watch on paper, not to trade.
