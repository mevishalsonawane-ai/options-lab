# R19c post-hoc (after the holdout read; descriptive only, not gated)

## Rs 1,000 a lot in option points

| index | lot | when | Rs 1,000 | Rs 500 | Rs 300 | Rs 700 |
|---|---|---|---|---|---|---|
| BANKNIFTY | 15 | Feb - Nov 2024 | 66.67 | 33.33 | 20.00 | 46.67 |
| BANKNIFTY | 30 | Nov 2024 - Jun 2025 (and the app's lot today) | 33.33 | 16.67 | 10.00 | 23.33 |
| BANKNIFTY | 35 | Jun 2025 - Feb 2026 in the release | 28.57 | 14.29 | 8.57 | 20.00 |
| NIFTY | 25 | Apr - Nov 2024 | 40.00 | 20.00 | 12.00 | 28.00 |
| NIFTY | 75 | Nov 2024 - Dec 2025 | 13.33 | 6.67 | 4.00 | 9.33 |
| NIFTY | 65 | from 31 Dec 2025 | 15.38 | 7.69 | 4.62 | 10.77 |
| MIDCPNIFTY | 120 | today's trade | 8.33 | 4.17 | 2.50 | 5.83 |

## The day lock on top of the ORB family switched off (P5 against P1)

| period | rule | days | green | net | worst day | max DD |
|---|---|---|---|---|---|---|
| design | P1 | 197 | 43% | +Rs 116,813 | -Rs 8,188 | -Rs 23,585 |
| design | P5 | 197 | 49% | +Rs 108,010 | -Rs 8,188 | -Rs 19,751 |
| design | P5 vs P1 sign test | 13 up / 1 down | p 0.001 (post hoc) | | | |
| holdout | P1 | 177 | 44% | +Rs 68,935 | -Rs 11,951 | -Rs 35,221 |
| holdout | P5 | 177 | 53% | +Rs 72,392 | -Rs 11,951 | -Rs 29,254 |
| holdout | P5 vs P1 sign test | 16 up / 0 down | p 0.000 (post hoc) | | | |

## Red days and their 3 worst trades (plain rules)

| portfolio | period | traded days | red days | median share of the red day's loss from its 3 worst trades | red days the 3 worst alone exceed |
|---|---|---|---|---|---|
| P0 everything | design | 272 | 163 | 100% | 114 |
| P0 everything | holdout | 222 | 128 | 100% | 90 |
| P1 ORB family off | design | 197 | 112 | 100% | 110 |
| P1 ORB family off | holdout | 177 | 99 | 100% | 95 |
