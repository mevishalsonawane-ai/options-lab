# RC2 anatomy (design data only: Delta expiries before 2026-04-13)

## 1. Spreads and liquidity on Delta India (live chain)
8 snapshots, 2026-10-13 19:00:21 to 2026-10-13 20:10:28 UTC; 6456 quotes. Spread = (ask - bid) / mid; both sides quoted only.
### BTC: median spread by time to expiry and moneyness (n = quotes)
| expiry / strike | n | spread_med | mid_bp_of_spot | ask_size |
|---|---|---|---|---|
| 6-24h / ITM >1% | 173 | 0.0115 | 233 | 5.02e+03 |
| 6-24h / ITM .25-1% | 50 | 0.0133 | 78.8 | 4.96e+03 |
| 6-24h / ATM +-.25% | 34 | 0.011 | 37.8 | 4.92e+03 |
| 6-24h / OTM .25-1% | 50 | 0.0239 | 15.8 | 4.93e+03 |
| 6-24h / OTM 1-2% | 67 | 0.0465 | 5.77 | 7.22e+03 |
| 6-24h / OTM 2-4% | 98 | 0.174 | 0.91 | 1.73e+04 |
| 6-24h / OTM 4-8% | 8 | 0.125 | 0.18 | 3.15e+03 |
| 1-2d / ITM >1% | 181 | 0.0111 | 252 | 8.17e+03 |
| 1-2d / ITM .25-1% | 50 | 0.0106 | 120 | 8.18e+03 |
| 1-2d / ATM +-.25% | 34 | 0.00805 | 85.8 | 8.17e+03 |
| 1-2d / OTM .25-1% | 50 | 0.0113 | 58.2 | 8.17e+03 |
| 1-2d / OTM 1-2% | 67 | 0.0173 | 32.3 | 8.28e+03 |
| 1-2d / OTM 2-4% | 98 | 0.0283 | 14.6 | 9.62e+03 |
| 1-2d / OTM 4-8% | 24 | 0.0451 | 6.64 | 1.29e+04 |
| 2-8d / ITM >1% | 213 | 0.011 | 406 | 4.09e+03 |
| 2-8d / ITM .25-1% | 64 | 0.0103 | 149 | 4.08e+03 |
| 2-8d / ATM +-.25% | 38 | 0.00987 | 115 | 4.08e+03 |
| 2-8d / OTM .25-1% | 64 | 0.0131 | 85.6 | 4.08e+03 |
| 2-8d / OTM 1-2% | 53 | 0.0172 | 59.6 | 4.08e+03 |
| 2-8d / OTM 2-4% | 63 | 0.0217 | 27.1 | 4.08e+03 |
| 2-8d / OTM 4-8% | 65 | 0.0471 | 9.92 | 4.08e+03 |
| 2-8d / OTM >8% | 40 | 0.286 | 0.841 | 4.08e+03 |
| >8d / ITM >1% | 936 | 0.0122 | 1.02e+03 | 4.21e+03 |
| >8d / ITM .25-1% | 84 | 0.00715 | 310 | 4.3e+03 |
| >8d / ATM +-.25% | 24 | 0.00799 | 278 | 4.29e+03 |
| >8d / OTM .25-1% | 84 | 0.00963 | 247 | 4.3e+03 |
| >8d / OTM 1-2% | 48 | 0.0115 | 210 | 4.14e+03 |
| >8d / OTM 2-4% | 183 | 0.0161 | 161 | 4.27e+03 |
| >8d / OTM 4-8% | 201 | 0.0288 | 114 | 4.23e+03 |
| >8d / OTM >8% | 744 | 0.0589 | 54.8 | 4.23e+03 |

BTC: share of strikes with BOTH bid and ask, by time to expiry: (6.0, 24.0]: 100%, (24.0, 48.0]: 100%, (48.0, 192.0]: 100%, (192.0, 100000.0]: 100%

### ETH: median spread by time to expiry and moneyness (n = quotes)
| expiry / strike | n | spread_med | mid_bp_of_spot | ask_size |
|---|---|---|---|---|
| 6-24h / ITM >1% | 168 | 0.0145 | 354 | 4.05e+03 |
| 6-24h / ITM .25-1% | 31 | 0.02 | 95.6 | 4.72e+03 |
| 6-24h / ATM +-.25% | 18 | 0.0204 | 59.4 | 6.31e+03 |
| 6-24h / OTM .25-1% | 31 | 0.0323 | 33.3 | 4.04e+03 |
| 6-24h / OTM 1-2% | 40 | 0.071 | 13.6 | 4.03e+03 |
| 6-24h / OTM 2-4% | 56 | 0.236 | 3.59 | 4.02e+03 |
| 6-24h / OTM 4-8% | 64 | 0.501 | 0.598 | 2e+03 |
| 1-2d / ITM >1% | 128 | 0.014 | 415 | 5.78e+03 |
| 1-2d / ITM .25-1% | 15 | 0.0125 | 135 | 5.78e+03 |
| 1-2d / OTM .25-1% | 15 | 0.0169 | 95.2 | 5.78e+03 |
| 1-2d / OTM 1-2% | 24 | 0.0249 | 62.5 | 5.78e+03 |
| 1-2d / OTM 2-4% | 40 | 0.0516 | 27.4 | 5.78e+03 |
| 1-2d / OTM 4-8% | 64 | 0.138 | 8.17 | 5.79e+03 |
| 2-8d / ITM >1% | 184 | 0.0135 | 476 | 2.8e+03 |
| 2-8d / ITM .25-1% | 15 | 0.0119 | 168 | 2.8e+03 |
| 2-8d / OTM .25-1% | 15 | 0.0156 | 129 | 2.79e+03 |
| 2-8d / OTM 1-2% | 32 | 0.0215 | 88.8 | 2.79e+03 |
| 2-8d / OTM 2-4% | 48 | 0.0324 | 53.3 | 2.8e+03 |
| 2-8d / OTM 4-8% | 40 | 0.0661 | 21.4 | 2.8e+03 |
| 2-8d / OTM >8% | 72 | 0.286 | 2.49 | 2.8e+03 |
| >8d / ITM >1% | 376 | 0.0121 | 1.17e+03 | 2.95e+03 |
| >8d / ITM .25-1% | 28 | 0.0114 | 563 | 2.94e+03 |
| >8d / ATM +-.25% | 8 | 0.0115 | 513 | 3.1e+03 |
| >8d / OTM .25-1% | 28 | 0.0132 | 481 | 3.29e+03 |
| >8d / OTM 1-2% | 16 | 0.0109 | 254 | 3.01e+03 |
| >8d / OTM 2-4% | 48 | 0.0163 | 238 | 2.94e+03 |
| >8d / OTM 4-8% | 96 | 0.0203 | 151 | 2.96e+03 |
| >8d / OTM >8% | 432 | 0.0588 | 69.5 | 3.09e+03 |

ETH: share of strikes with BOTH bid and ask, by time to expiry: (6.0, 24.0]: 100%, (24.0, 48.0]: 100%, (48.0, 192.0]: 100%, (192.0, 100000.0]: 100%

spread model BTC: full spread / spot = 0.000018 + 0.0104 x mid / spot (n = 1512 quotes, 8 snapshots)
spread model ETH: full spread / spot = 0.000090 + 0.0114 x mid / spot (n = 968 quotes, 8 snapshots)

## 2. Daily-expiry premium decay through the day (Delta marks, design expiries)
### BTC: ATM straddle (call + put) as % of spot
| BTC ATM straddle | n days | median % | p25 % | p75 % | share of the 24h value |
|---|---|---|---|---|---|
| 24 h left | 771 | 1.64 | 1.25 | 2.1 | 1 |
| 18 h left | 751 | 1.32 | 1.07 | 1.67 | 0.806 |
| 12 h left | 722 | 0.94 | 0.74 | 1.16 | 0.572 |
| 9 h left | 712 | 0.798 | 0.635 | 1.01 | 0.486 |
| 6 h left | 712 | 0.657 | 0.521 | 0.815 | 0.4 |
| 4 h left | 712 | 0.539 | 0.437 | 0.67 | 0.328 |
| 3 h left | 714 | 0.459 | 0.37 | 0.57 | 0.279 |
| 2 h left | 710 | 0.374 | 0.305 | 0.466 | 0.228 |
| 1 h left | 707 | 0.258 | 0.197 | 0.315 | 0.157 |
| 0.5 h left | 706 | 0.163 | 0.122 | 0.204 | 0.0995 |
| 0.166667 h left | 707 | 0.112 | 0.0707 | 0.17 | 0.0682 |

### ETH: ATM straddle (call + put) as % of spot
| ETH ATM straddle | n days | median % | p25 % | p75 % | share of the 24h value |
|---|---|---|---|---|---|
| 24 h left | 771 | 2.51 | 2.03 | 2.98 | 1 |
| 18 h left | 723 | 1.98 | 1.65 | 2.34 | 0.787 |
| 12 h left | 680 | 1.39 | 1.14 | 1.67 | 0.551 |
| 9 h left | 667 | 1.2 | 0.98 | 1.42 | 0.479 |
| 6 h left | 655 | 0.978 | 0.806 | 1.15 | 0.389 |
| 4 h left | 649 | 0.792 | 0.66 | 0.958 | 0.315 |
| 3 h left | 643 | 0.674 | 0.565 | 0.798 | 0.268 |
| 2 h left | 641 | 0.554 | 0.464 | 0.658 | 0.22 |
| 1 h left | 644 | 0.387 | 0.323 | 0.469 | 0.154 |
| 0.5 h left | 639 | 0.267 | 0.218 | 0.337 | 0.106 |
| 0.166667 h left | 636 | 0.213 | 0.135 | 0.336 | 0.0847 |

### Move from 00:00 UTC to settlement vs the 00:00 straddle (what a buyer of the 12-hour straddle got back)
- BTC: 722 days; median |move| 0.68%, median straddle 0.94%; payoff / price: mean 1.02, median 0.74; share of days the straddle paid back more than its price 39%
- ETH: 680 days; median |move| 1.03%, median straddle 1.39%; payoff / price: mean 1.03, median 0.79; share of days the straddle paid back more than its price 40%

## 3. Zero to hero: how often does a cheap OTM option (0.02-0.10% of spot) multiply before 11:50 UTC?
Every such option on every design day counted (per option), and per day (did ANY such option get there).
### BTC
| BTC bought | options | days | opt_3x | opt_5x | opt_10x | end_value_mean | end_zero | any_3x_days | any_5x_days | any_10x_days |
|---|---|---|---|---|---|---|---|---|---|---|
| 6 h before (UTC 06:00) | 5026.00 | 755.00 | 0.25 | 0.14 | 0.06 | 0.91 | 0.88 | 0.54 | 0.33 | 0.15 |
| 4 h before (UTC 08:00) | 4184.00 | 749.00 | 0.21 | 0.12 | 0.05 | 0.70 | 0.86 | 0.47 | 0.28 | 0.14 |
| 3 h before (UTC 09:00) | 3556.00 | 744.00 | 0.21 | 0.12 | 0.05 | 0.68 | 0.82 | 0.46 | 0.27 | 0.13 |
| 2 h before (UTC 10:00) | 3012.00 | 740.00 | 0.17 | 0.10 | 0.04 | 0.68 | 0.76 | 0.39 | 0.22 | 0.09 |
| 1 h before (UTC 11:00) | 2034.00 | 719.00 | 0.17 | 0.08 | 0.02 | 0.63 | 0.57 | 0.35 | 0.17 | 0.06 |

### ETH
| ETH bought | options | days | opt_3x | opt_5x | opt_10x | end_value_mean | end_zero | any_3x_days | any_5x_days | any_10x_days |
|---|---|---|---|---|---|---|---|---|---|---|
| 6 h before (UTC 06:00) | 2740.00 | 739.00 | 0.24 | 0.15 | 0.07 | 0.83 | 0.84 | 0.53 | 0.34 | 0.17 |
| 4 h before (UTC 08:00) | 2460.00 | 726.00 | 0.22 | 0.13 | 0.07 | 0.90 | 0.80 | 0.50 | 0.29 | 0.16 |
| 3 h before (UTC 09:00) | 2185.00 | 700.00 | 0.19 | 0.11 | 0.06 | 0.69 | 0.76 | 0.44 | 0.26 | 0.14 |
| 2 h before (UTC 10:00) | 1879.00 | 683.00 | 0.17 | 0.09 | 0.04 | 0.57 | 0.70 | 0.37 | 0.20 | 0.09 |
| 1 h before (UTC 11:00) | 1346.00 | 623.00 | 0.14 | 0.08 | 0.03 | 0.60 | 0.53 | 0.28 | 0.15 | 0.05 |

- opt_Nx: share of cheap options whose mark high reached N x the entry mark before 11:50 UTC (marks, before costs).
- end_value_mean: the mark at 11:50 / entry (mean); end_zero: share worth < 5% of entry at 11:50.

## 4. Intraday move sizes vs implied, by hour (UTC); design span only
### BTC: |1-hour move| and realised / DVOL-implied (RMS), by hour
| BTC hour | n | median_abs_move_pct | realised_vs_implied |
|---|---|---|---|
| 23-00 UTC (04:30 IST) | 773.000 | 0.191 | 0.751 |
| 00-01 UTC (05:30 IST) | 772.000 | 0.237 | 0.941 |
| 01-02 UTC (06:30 IST) | 773.000 | 0.237 | 1.055 |
| 02-03 UTC (07:30 IST) | 773.000 | 0.238 | 0.894 |
| 03-04 UTC (08:30 IST) | 773.000 | 0.200 | 0.740 |
| 04-05 UTC (09:30 IST) | 773.000 | 0.181 | 0.697 |
| 05-06 UTC (10:30 IST) | 773.000 | 0.185 | 0.674 |
| 06-07 UTC (11:30 IST) | 773.000 | 0.183 | 0.870 |
| 07-08 UTC (12:30 IST) | 773.000 | 0.195 | 0.786 |
| 08-09 UTC (13:30 IST) | 773.000 | 0.195 | 0.778 |
| 09-10 UTC (14:30 IST) | 773.000 | 0.190 | 0.699 |
| 10-11 UTC (15:30 IST) | 773.000 | 0.182 | 0.681 |
| 11-12 UTC (16:30 IST) | 773.000 | 0.186 | 0.665 |
| 12-13 UTC (17:30 IST) | 773.000 | 0.212 | 0.776 |
| 13-14 UTC (18:30 IST) | 773.000 | 0.307 | 1.121 |
| 14-15 UTC (19:30 IST) | 773.000 | 0.401 | 1.463 |
| 15-16 UTC (20:30 IST) | 773.000 | 0.307 | 1.321 |
| 16-17 UTC (21:30 IST) | 773.000 | 0.280 | 1.039 |
| 17-18 UTC (22:30 IST) | 773.000 | 0.280 | 1.120 |
| 18-19 UTC (23:30 IST) | 773.000 | 0.259 | 1.023 |
| 19-20 UTC (00:30 IST) | 773.000 | 0.226 | 1.044 |
| 20-21 UTC (01:30 IST) | 773.000 | 0.232 | 0.928 |
| 21-22 UTC (02:30 IST) | 773.000 | 0.208 | 0.883 |
| 22-23 UTC (03:30 IST) | 773.000 | 0.213 | 0.899 |

### ETH: |1-hour move| and realised / DVOL-implied (RMS), by hour
| ETH hour | n | median_abs_move_pct | realised_vs_implied |
|---|---|---|---|
| 23-00 UTC (04:30 IST) | 773.000 | 0.270 | 0.748 |
| 00-01 UTC (05:30 IST) | 772.000 | 0.335 | 1.032 |
| 01-02 UTC (06:30 IST) | 773.000 | 0.348 | 1.298 |
| 02-03 UTC (07:30 IST) | 773.000 | 0.334 | 0.932 |
| 03-04 UTC (08:30 IST) | 773.000 | 0.294 | 0.803 |
| 04-05 UTC (09:30 IST) | 773.000 | 0.265 | 0.729 |
| 05-06 UTC (10:30 IST) | 773.000 | 0.266 | 0.800 |
| 06-07 UTC (11:30 IST) | 773.000 | 0.270 | 0.897 |
| 07-08 UTC (12:30 IST) | 773.000 | 0.278 | 0.867 |
| 08-09 UTC (13:30 IST) | 773.000 | 0.280 | 0.902 |
| 09-10 UTC (14:30 IST) | 773.000 | 0.258 | 0.762 |
| 10-11 UTC (15:30 IST) | 773.000 | 0.258 | 0.737 |
| 11-12 UTC (16:30 IST) | 773.000 | 0.257 | 0.806 |
| 12-13 UTC (17:30 IST) | 773.000 | 0.282 | 0.872 |
| 13-14 UTC (18:30 IST) | 773.000 | 0.434 | 1.123 |
| 14-15 UTC (19:30 IST) | 773.000 | 0.526 | 1.400 |
| 15-16 UTC (20:30 IST) | 773.000 | 0.478 | 1.333 |
| 16-17 UTC (21:30 IST) | 773.000 | 0.370 | 1.165 |
| 17-18 UTC (22:30 IST) | 773.000 | 0.410 | 1.237 |
| 18-19 UTC (23:30 IST) | 773.000 | 0.359 | 1.058 |
| 19-20 UTC (00:30 IST) | 773.000 | 0.326 | 1.149 |
| 20-21 UTC (01:30 IST) | 773.000 | 0.327 | 1.046 |
| 21-22 UTC (02:30 IST) | 773.000 | 0.278 | 1.016 |
| 22-23 UTC (03:30 IST) | 773.000 | 0.307 | 0.999 |

## 5. What separates big-move days (UTC day range in its top 20%) from the rest? Known at 00:00 UTC
| medians (weekend = share) | rng | prev_rng | prev_abs_ret | dvol_0 | dvol_chg_24h | funding_8h_bp | weekend | n days |
|---|---|---|---|---|---|---|---|---|
| BTC other | 2.95 | 3.17 | 1.16 | 50.34 | -0.08 | 0.17 | 0.33 | 617.00 |
| BTC big | 6.83 | 4.72 | 2.09 | 56.79 | 0.38 | 0.05 | 0.11 | 155.00 |
| ETH other | 4.25 | 4.65 | 1.67 | 68.25 | -0.24 | 0.04 | 0.32 | 617.00 |
| ETH big | 9.89 | 6.17 | 2.62 | 71.23 | 0.41 | 0.02 | 0.17 | 155.00 |

- BTC rank correlation with the day's range (n = 772 days): prev_rng +0.35, prev_abs_ret +0.24, dvol_0 +0.39, dvol_chg_24h +0.18, funding_8h_bp -0.10
- ETH rank correlation with the day's range (n = 772 days): prev_rng +0.32, prev_abs_ret +0.23, dvol_0 +0.28, dvol_chg_24h +0.15, funding_8h_bp -0.04

Design expiries with Delta chains: {'BTC': 772, 'ETH': 772}
