# RC2 Deribit depth and cross-check (design span only)

## (a) Delta India mark vs Deribit trade IV (near the money)
- BTC: 229 hours; Delta mark IV / Deribit trade IV: median 0.878, 25-75% 0.785-0.951; median IV Delta 34.9, Deribit 41.3; |difference| median 4.8 vol points
- ETH: 227 hours; Delta mark IV / Deribit trade IV: median 0.920, 25-75% 0.853-0.988; median IV Delta 55.2, Deribit 59.8; |difference| median 5.2 vol points

## (b) Zero to hero on Deribit's traded prices (daily 08:00 UTC expiries, design span, every 3rd day)
### BTC (traded prices; an option with no later trade counts as 1x)
| bought | options | days | reached 3x | 5x | 10x | days any 3x | any 5x | any 10x |
|---|---|---|---|---|---|---|---|---|
| 4 h before 08:00 UTC | 1087 | 462 | 9.7% | 4.7% | 1.4% | 19% | 10% | 3% |
| 3 h before 08:00 UTC | 1048 | 466 | 8.6% | 4.3% | 1.3% | 16% | 8% | 2% |
| 2 h before 08:00 UTC | 897 | 441 | 7.7% | 3.2% | 0.9% | 15% | 7% | 2% |
| 1 h before 08:00 UTC | 718 | 407 | 7.5% | 3.8% | 0.6% | 13% | 6% | 1% |

- BTC, bought 2 h before, reached 3x by year: 2022: 3.5% of 143, 2023: 6.8% of 234, 2024: 7.4% of 272, 2025: 9.9% of 202, 2026: 17.4% of 46

### ETH (traded prices; an option with no later trade counts as 1x)
| bought | options | days | reached 3x | 5x | 10x | days any 3x | any 5x | any 10x |
|---|---|---|---|---|---|---|---|---|
| 4 h before 08:00 UTC | 825 | 433 | 8.6% | 5.0% | 2.8% | 15% | 8% | 5% |
| 3 h before 08:00 UTC | 790 | 410 | 9.0% | 4.1% | 2.0% | 14% | 7% | 3% |
| 2 h before 08:00 UTC | 770 | 422 | 7.9% | 3.9% | 1.8% | 14% | 7% | 3% |
| 1 h before 08:00 UTC | 729 | 407 | 4.7% | 1.8% | 1.1% | 8% | 3% | 2% |

- ETH, bought 2 h before, reached 3x by year: 2022: 8.5% of 153, 2023: 4.1% of 148, 2024: 7.4% of 216, 2025: 8.4% of 203, 2026: 18.0% of 50

## (c) Delta India traded price vs its mark (same 5-minute bar, bars with volume, design days)
- BTC: 850241 bars with trades on 109 expiries; traded close / mark - 1: median -0.08%, 25-75% -4.58% to +4.10%; |dev| median 4.34%
  bars with trades by hours to expiry: (0, 3]: 91397.0 bars, (3, 6]: 87559.0 bars, (6, 12]: 180809.0 bars, (12, 30]: 484639.0 bars
- ETH: 353996 bars with trades on 108 expiries; traded close / mark - 1: median +0.10%, 25-75% -3.80% to +4.81%; |dev| median 4.29%
  bars with trades by hours to expiry: (0, 3]: 38973.0 bars, (3, 6]: 35215.0 bars, (6, 12]: 70247.0 bars, (12, 30]: 207088.0 bars

