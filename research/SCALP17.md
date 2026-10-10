# SCALP17: "+15-20 points, up to 5 trades a day, 1 lot" on NIFTY / BANKNIFTY / FINNIFTY options

## Verdict

- **Base rate.** Bought from a random minute, an ATM or 1-ITM nearest-expiry option reaches +17 before -15 within 30 minutes 32.7% of the time and -15 first 40.7% (the rest time out). Conditional on resolving, P(+17 first) = 44.6% against a break-even of 15/32 = 46.9%. The average entry makes -0.70 pts gross and -3.31 pts net (app costs + a 1-pt spread). A plain "+15-20, stop 15" scalp is a small-negative coin flip before costs and a clear loser after them.
- **Best rule found on 2020-2024** (5m decisions): BANKNIFTY PE 1-ITM, when *streak<=-3 & b5rng Q4 [0.963,1.29)*, target +10 / stop -25 / time stop 15 min, max 5 a day. Train: 123 Rs/trade gross, -43 net. **Holdout 2025-Oct 2026: -4 Rs/trade gross (-132/month), -243 Rs/trade net (-7,422/month)**, 671 trades, random-entry p = 0.049.
- **Walk-forward** (anchored, each year picked on all earlier years): 66,015 Rs gross, -375,315 Rs net over 5 test years (2641 trades).
- **Multiple testing.** 1,100,700 condition x T/S/L x contract x underlying x timeframe combos were screened (1,061,475 with >= 100 train days). BH q < 0.05 for a positive gross edge: 0; net: 0. Of the 50 shortlisted rules, 20 were positive gross and 0 net in the holdout.
- **Best rule inside Boss's +15-20 range** (picked the same way, 2020-2024 only): BANKNIFTY PE 1-ITM (5m) when *streak<=-3 & b5rng Q4 [0.963,1.29)*, +15 / -30 / 15 min. Train 1.4 trades/day, 4,207 Rs/month gross, -890 net. Holdout: 56% wins, -35 Rs/trade gross (-1,045/month), -213 net app, -262 net +spread (-7,802/month); random-entry p 0.170.
- **1-minute vs 5-minute decisions:** 1m: 10/25 shortlisted rules positive gross in the holdout, 0/25 net, mean -6 Rs/trade gross, -145 net; 5m: 10/25 shortlisted rules positive gross in the holdout, 0/25 net, mean -4 Rs/trade gross, -258 net. Neither timeframe produces a durable edge; 5-minute rules look better in-sample and fall further out of sample.

**Bottom line: no.** A "+15-20 points, up to 5 trades a day" scalp is not achievable on this data. Gross, the average entry is a slightly losing coin flip at every T/S/time stop, and no condition survives the multiple-testing correction. Net of the app's costs (about Rs 90 a round trip, ~1.5 pts) and a realistic 1-pt spread, every shortlisted rule lost money in 2025-Oct 2026. Rs 1,000 x 5 a day is not on the table; the realistic expectation is a loss of roughly Rs 100-250 a trade.

## Setup

- Data: real Dhan 1-minute bars of the nearest-expiry option series (weekly; BANKNIFTY / FINNIFTY monthly after Nov 2024, when their weeklies stopped), NIFTY Aug 2020 - Oct 2026, BANKNIFTY / FINNIFTY Aug 2021 - Oct 2026; index minutes from `jx/ix_<U>.pkl` (the files the validated lab uses). Days whose index minutes are not real bars are skipped.
- Entries: decision on the close of every minute 09:16-15:00 (1m) or of every 5-minute bar (5m: 09:19, 09:24, ..., 14:59), fill at the next minute's OPEN. Contracts: CE/PE x ATM/1-ITM, strike from the index close at the decision minute. Exits checked minute by minute on the option's own high/low: target +T, stop -S, time stop L minutes, never past the 15:10 square-off. If target and stop fall in the same minute the stop is assumed first (conservative).
- Rs are for a fixed 60 qty (Boss's "1 lot ~60"; today's lots: NIFTY 65, BANKNIFTY 35, FINNIFTY 60), so 17 pts = Rs 1,020.
- Columns: **Gross** = points x 60, no charges, no slippage (Boss's headline). **Net app** = the app's fills (+-5 bps, SL -10 bps, stop gaps filled at the bar open) and the app's charges (`costs.Costs('app')`: Rs 20 + 20 brokerage, STT 0.15% of the sell, exchange, GST, stamp), about Rs 85-95 a round trip at Rs 100-250 premiums. **Net +spread** = also a 1-pt bid/ask spread (buy at mid + 0.5; the +T limit fills only when the traded high reaches T + 1 above the open; the SL-M triggers on the LTP and fills 0.5 below). **Stress** = a 2-pt spread.
- Choices made ONLY on 2020-2024; 2025-Oct 2026 run once at the end. Quintile cut-points of every feature come from 2020-2024.

## 1. First-passage map

### All entry minutes, all three indices, all four contracts (1-minute decisions)

P(+T first) is the share of entries that hit +T before -S within L minutes; P(-S) the share that hit -S first; the rest time out. "won/resolved" = P(+T) / (P(+T) + P(-S)) - compare it with break-even S/(S+T). EV = average points per entry (time-outs at their exit price).

| T | S | BE S/(S+T) | P(+T) 15m | P(+T) 30m | P(+T) 60m | P(-S) 30m | won/resolved 30m | EV gross 30m (pts) | EV net+spread 30m (pts) |
|---|---|---|---|---|---|---|---|---|---|
| 10 | 10 | 50.0% | 37.2% | 42.6% | 45.4% | 47.2% | 47.4% | -0.57 | -3.17 |
| 10 | 15 | 60.0% | 41.6% | 48.8% | 53.2% | 34.0% | 59.0% | -0.71 | -3.32 |
| 10 | 20 | 66.7% | 44.0% | 52.3% | 57.8% | 24.9% | 67.7% | -0.79 | -3.39 |
| 10 | 25 | 71.4% | 45.3% | 54.3% | 60.7% | 18.6% | 74.4% | -0.82 | -3.43 |
| 10 | 30 | 75.0% | 46.1% | 55.6% | 62.5% | 14.2% | 79.6% | -0.85 | -3.45 |
| 15 | 10 | 40.0% | 25.5% | 31.2% | 34.9% | 53.2% | 37.0% | -0.54 | -3.14 |
| 15 | 15 | 50.0% | 29.0% | 36.5% | 41.8% | 39.3% | 48.2% | -0.71 | -3.32 |
| 15 | 20 | 57.1% | 31.0% | 39.5% | 46.1% | 29.3% | 57.4% | -0.81 | -3.41 |
| 15 | 25 | 62.5% | 32.2% | 41.5% | 48.9% | 22.2% | 65.1% | -0.86 | -3.46 |
| 15 | 30 | 66.7% | 32.9% | 42.7% | 50.7% | 17.1% | 71.4% | -0.89 | -3.49 |
| 17 | 10 | 37.0% | 22.2% | 27.8% | 31.7% | 54.8% | 33.7% | -0.52 | -3.13 |
| 17 | 15 | 46.9% | 25.4% | 32.7% | 38.2% | 40.7% | 44.6% | -0.70 | -3.31 |
| 17 | 20 | 54.1% | 27.3% | 35.6% | 42.3% | 30.6% | 53.8% | -0.81 | -3.41 |
| 17 | 25 | 59.5% | 28.4% | 37.5% | 45.0% | 23.3% | 61.7% | -0.87 | -3.46 |
| 17 | 30 | 63.8% | 29.0% | 38.7% | 46.9% | 18.0% | 68.3% | -0.90 | -3.49 |
| 20 | 10 | 33.3% | 18.3% | 23.7% | 27.7% | 56.6% | 29.5% | -0.50 | -3.10 |
| 20 | 15 | 42.9% | 21.1% | 28.0% | 33.6% | 42.4% | 39.8% | -0.69 | -3.29 |
| 20 | 20 | 50.0% | 22.7% | 30.7% | 37.4% | 32.0% | 48.9% | -0.80 | -3.41 |
| 20 | 25 | 55.6% | 23.7% | 32.4% | 40.0% | 24.5% | 56.9% | -0.86 | -3.46 |
| 20 | 30 | 60.0% | 24.3% | 33.6% | 41.8% | 19.0% | 63.8% | -0.90 | -3.49 |
| 25 | 10 | 28.6% | 13.6% | 18.5% | 22.4% | 58.7% | 24.0% | -0.46 | -3.07 |
| 25 | 15 | 37.5% | 15.8% | 22.1% | 27.4% | 44.3% | 33.2% | -0.66 | -3.27 |
| 25 | 20 | 44.4% | 17.2% | 24.3% | 30.8% | 33.8% | 41.9% | -0.78 | -3.39 |
| 25 | 25 | 50.0% | 18.0% | 25.8% | 33.1% | 26.0% | 49.8% | -0.85 | -3.45 |
| 25 | 30 | 54.5% | 18.5% | 26.8% | 34.8% | 20.3% | 56.9% | -0.89 | -3.49 |

### By index and contract, T 17 / S 15 / 30 min (1-minute decisions, all years)

| index | contract | entries | P(+17) | P(-15) | won/resolved (BE 46.9%) | EV gross pts | EV net+spread pts |
|---|---|---|---|---|---|---|---|
| NIFTY | CE ATM | 519,927 | 22.5% | 29.3% | 43.4% | -0.63 | -2.76 |
| NIFTY | CE 1-ITM | 520,879 | 27.9% | 37.0% | 43.0% | -0.56 | -2.80 |
| NIFTY | PE ATM | 519,777 | 24.7% | 26.9% | 47.8% | -0.50 | -2.60 |
| NIFTY | PE 1-ITM | 520,854 | 29.9% | 35.2% | 45.9% | -0.47 | -2.67 |
| BANKNIFTY | CE ATM | 428,008 | 42.6% | 54.1% | 44.0% | -0.90 | -4.25 |
| BANKNIFTY | CE 1-ITM | 427,973 | 43.6% | 54.7% | 44.4% | -0.79 | -4.34 |
| BANKNIFTY | PE ATM | 427,997 | 42.1% | 53.5% | 44.1% | -0.87 | -3.96 |
| BANKNIFTY | PE 1-ITM | 428,032 | 43.3% | 54.2% | 44.4% | -0.76 | -4.02 |
| FINNIFTY | CE ATM | 307,421 | 28.1% | 35.3% | 44.3% | -0.79 | -3.17 |
| FINNIFTY | CE 1-ITM | 280,974 | 31.9% | 40.3% | 44.2% | -0.76 | -3.20 |
| FINNIFTY | PE ATM | 308,957 | 26.9% | 32.0% | 45.6% | -0.81 | -3.10 |
| FINNIFTY | PE 1-ITM | 290,710 | 30.9% | 38.3% | 44.7% | -0.82 | -3.17 |

### By condition, T 17 / S 15 / 30 min, 1m decisions (all indices and contracts pooled, all years)

Directional features are aligned with the trade: for a PE, a falling index counts as positive momentum. dfav = distance (bps) from the day's extreme in the trade's favour (day high for a CE), dadv = from the other extreme; pcrch = net put-minus-call OI added near the money in 15 min (bps of OI, aligned); oich = the contract's own OI change % in 15 min; opm5 = the option's premium change over the last 5 minutes (pts); rexp = mean 1-minute range of the last 15 minutes / that of the previous 5 days; b5rng = the 5-minute bar's range / the previous 5 days' average; streak = consecutive 5-minute bars of one colour, aligned (streak<=-3 = three or more bars AGAINST the trade, e.g. three green bars before buying a PE: a fade); "adverse extreme" = the 5-minute bar closed beyond the previous bar's low for a CE (high for a PE).

Quintiles (Q1 lowest .. Q5 highest) are cut per index on 2020-2024.

| condition | entries | P(+17) | P(-15) | won/resolved (BE 46.9%) | EV gross | EV net+spread |
|---|---|---|---|---|---|---|
| all | 4,981,509 | 32.7% | 40.7% | 44.6% | -0.70 | -3.31 |
| 09:16-09:44 | 422,919 | 39.1% | 48.0% | 44.9% | -0.68 | -3.30 |
| 09:45-10:29 | 653,658 | 34.6% | 43.3% | 44.4% | -0.77 | -3.39 |
| 10:30-11:59 | 1,299,582 | 30.7% | 38.3% | 44.5% | -0.67 | -3.29 |
| 12:00-13:29 | 1,295,448 | 31.0% | 38.6% | 44.6% | -0.68 | -3.29 |
| 13:30-14:29 | 864,663 | 33.8% | 41.8% | 44.7% | -0.71 | -3.29 |
| 14:30-15:00 | 445,239 | 32.8% | 41.1% | 44.4% | -0.78 | -3.35 |
| Mon | 1,010,673 | 32.3% | 40.5% | 44.3% | -0.68 | -3.35 |
| Tue | 1,020,213 | 32.3% | 40.3% | 44.5% | -0.78 | -3.29 |
| Wed | 980,216 | 32.1% | 39.9% | 44.6% | -0.66 | -3.24 |
| Thu | 986,254 | 33.9% | 41.3% | 45.1% | -0.70 | -3.19 |
| Fri | 984,153 | 32.9% | 41.5% | 44.2% | -0.69 | -3.46 |
| DTE0 | 903,322 | 33.1% | 39.7% | 45.5% | -0.81 | -2.85 |
| DTE1 | 892,135 | 31.0% | 38.7% | 44.5% | -0.66 | -2.92 |
| DTE2 | 882,695 | 31.2% | 39.3% | 44.3% | -0.65 | -3.06 |
| DTE3-4 | 1,512,104 | 31.5% | 39.5% | 44.3% | -0.60 | -3.18 |
| DTE5+ | 781,764 | 38.2% | 47.9% | 44.3% | -0.90 | -4.79 |
| vix Q1 | 1,285,891 | 28.7% | 35.1% | 44.9% | -0.58 | -3.08 |
| vix Q2 | 1,007,861 | 31.7% | 39.2% | 44.7% | -0.66 | -3.22 |
| vix Q3 | 944,095 | 34.3% | 42.5% | 44.7% | -0.69 | -3.31 |
| vix Q4 | 886,094 | 34.6% | 43.5% | 44.3% | -0.75 | -3.41 |
| vix Q5 | 857,568 | 36.2% | 45.9% | 44.1% | -0.90 | -3.64 |
| prem Q1 | 816,487 | 28.5% | 32.6% | 46.6% | -0.65 | -2.65 |
| prem Q2 | 814,707 | 29.8% | 36.3% | 45.1% | -0.52 | -2.71 |
| prem Q3 | 820,699 | 30.7% | 37.8% | 44.8% | -0.52 | -2.85 |
| prem Q4 | 871,732 | 32.5% | 41.4% | 44.0% | -0.68 | -3.16 |
| prem Q5 | 1,657,884 | 37.3% | 47.9% | 43.8% | -0.92 | -4.23 |
| r5 Q1 | 954,972 | 35.2% | 43.9% | 44.5% | -0.64 | -3.28 |
| r5 Q2 | 1,013,073 | 31.0% | 38.7% | 44.5% | -0.64 | -3.23 |
| r5 Q3 | 1,030,756 | 29.9% | 37.6% | 44.3% | -0.75 | -3.33 |
| r5 Q4 | 1,018,388 | 31.3% | 39.3% | 44.4% | -0.79 | -3.38 |
| r5 Q5 | 964,320 | 36.4% | 44.5% | 45.0% | -0.68 | -3.32 |
| r15 Q1 | 958,492 | 34.5% | 44.5% | 43.7% | -0.85 | -3.50 |
| r15 Q2 | 1,012,672 | 30.5% | 38.5% | 44.2% | -0.67 | -3.26 |
| r15 Q3 | 1,020,769 | 30.0% | 37.1% | 44.7% | -0.66 | -3.23 |
| r15 Q4 | 1,019,219 | 31.6% | 38.9% | 44.8% | -0.73 | -3.32 |
| r15 Q5 | 970,357 | 37.2% | 44.8% | 45.4% | -0.60 | -3.24 |
| r30 Q1 | 958,569 | 34.1% | 44.8% | 43.3% | -0.97 | -3.63 |
| r30 Q2 | 1,010,877 | 30.3% | 38.3% | 44.2% | -0.67 | -3.25 |
| r30 Q3 | 1,023,071 | 30.2% | 36.8% | 45.0% | -0.60 | -3.16 |
| r30 Q4 | 1,018,393 | 31.7% | 38.9% | 44.9% | -0.72 | -3.30 |
| r30 Q5 | 970,599 | 37.6% | 45.1% | 45.4% | -0.56 | -3.21 |
| rexp Q1 | 923,854 | 27.4% | 34.7% | 44.2% | -0.76 | -3.32 |
| rexp Q2 | 1,003,982 | 29.7% | 37.2% | 44.4% | -0.70 | -3.33 |
| rexp Q3 | 1,036,399 | 32.0% | 39.7% | 44.7% | -0.66 | -3.28 |
| rexp Q4 | 1,031,949 | 34.8% | 43.1% | 44.6% | -0.69 | -3.30 |
| rexp Q5 | 984,124 | 39.2% | 48.3% | 44.8% | -0.70 | -3.31 |
| gap Q1 | 909,180 | 33.7% | 41.7% | 44.7% | -0.77 | -3.40 |
| gap Q2 | 1,016,184 | 32.5% | 39.6% | 45.1% | -0.69 | -3.27 |
| gap Q3 | 1,123,798 | 32.1% | 40.2% | 44.4% | -0.74 | -3.37 |
| gap Q4 | 1,017,400 | 32.1% | 40.2% | 44.4% | -0.64 | -3.22 |
| gap Q5 | 914,947 | 33.4% | 42.0% | 44.3% | -0.67 | -3.29 |
| dvwap Q1 | 957,527 | 33.3% | 43.7% | 43.2% | -0.99 | -3.64 |
| dvwap Q2 | 1,019,372 | 29.9% | 38.6% | 43.6% | -0.86 | -3.47 |
| dvwap Q3 | 1,005,547 | 30.5% | 37.5% | 44.8% | -0.64 | -3.19 |
| dvwap Q4 | 1,029,146 | 32.7% | 39.2% | 45.5% | -0.52 | -3.11 |
| dvwap Q5 | 969,917 | 37.5% | 44.8% | 45.5% | -0.50 | -3.13 |
| dfav Q1 | 1,033,626 | 33.9% | 40.1% | 45.8% | -0.46 | -3.03 |
| dfav Q2 | 1,009,535 | 31.9% | 38.9% | 45.0% | -0.57 | -3.13 |
| dfav Q3 | 992,128 | 31.1% | 39.2% | 44.2% | -0.74 | -3.31 |
| dfav Q4 | 989,092 | 31.8% | 40.2% | 44.1% | -0.78 | -3.41 |
| dfav Q5 | 957,128 | 34.9% | 45.2% | 43.6% | -0.99 | -3.68 |
| dadv Q1 | 1,017,903 | 29.5% | 39.1% | 43.0% | -0.99 | -3.58 |
| dadv Q2 | 1,005,225 | 30.5% | 38.4% | 44.2% | -0.78 | -3.35 |
| dadv Q3 | 995,412 | 32.2% | 39.1% | 45.2% | -0.59 | -3.17 |
| dadv Q4 | 997,366 | 33.7% | 41.2% | 45.0% | -0.60 | -3.23 |
| dadv Q5 | 965,603 | 37.9% | 45.9% | 45.2% | -0.54 | -3.20 |
| dayret Q1 | 989,711 | 32.8% | 42.7% | 43.5% | -0.96 | -3.63 |
| dayret Q2 | 994,834 | 30.7% | 38.7% | 44.2% | -0.75 | -3.32 |
| dayret Q3 | 997,342 | 31.5% | 38.5% | 45.0% | -0.61 | -3.17 |
| dayret Q4 | 1,001,858 | 32.4% | 39.6% | 45.0% | -0.62 | -3.19 |
| dayret Q5 | 997,764 | 36.1% | 43.9% | 45.2% | -0.58 | -3.23 |
| pcrch Q1 | 988,016 | 32.1% | 42.3% | 43.1% | -1.03 | -3.49 |
| pcrch Q2 | 976,341 | 30.5% | 39.9% | 43.3% | -0.94 | -3.59 |
| pcrch Q3 | 1,046,069 | 31.9% | 39.9% | 44.4% | -0.69 | -3.50 |
| pcrch Q4 | 979,308 | 32.8% | 39.2% | 45.6% | -0.49 | -3.13 |
| pcrch Q5 | 991,124 | 36.3% | 42.2% | 46.3% | -0.36 | -2.82 |
| oich Q1 | 989,564 | 34.8% | 41.5% | 45.6% | -0.48 | -3.00 |
| oich Q2 | 1,072,072 | 32.2% | 39.7% | 44.8% | -0.59 | -3.36 |
| oich Q3 | 992,485 | 31.2% | 38.9% | 44.5% | -0.67 | -3.35 |
| oich Q4 | 964,740 | 31.3% | 39.7% | 44.0% | -0.82 | -3.38 |
| oich Q5 | 953,949 | 34.1% | 43.7% | 43.8% | -0.97 | -3.44 |
| opm5 Q1 | 1,023,903 | 36.4% | 44.4% | 45.0% | -0.54 | -3.19 |
| opm5 Q2 | 973,114 | 30.3% | 38.6% | 44.0% | -0.76 | -3.33 |
| opm5 Q3 | 949,268 | 28.4% | 36.4% | 43.8% | -0.85 | -3.38 |
| opm5 Q4 | 976,728 | 30.7% | 38.1% | 44.6% | -0.72 | -3.29 |
| opm5 Q5 | 1,055,778 | 37.1% | 45.3% | 45.1% | -0.66 | -3.35 |

### By condition, T 17 / S 15 / 30 min, 5m decisions (all indices and contracts pooled, all years)

Directional features are aligned with the trade: for a PE, a falling index counts as positive momentum. dfav = distance (bps) from the day's extreme in the trade's favour (day high for a CE), dadv = from the other extreme; pcrch = net put-minus-call OI added near the money in 15 min (bps of OI, aligned); oich = the contract's own OI change % in 15 min; opm5 = the option's premium change over the last 5 minutes (pts); rexp = mean 1-minute range of the last 15 minutes / that of the previous 5 days; b5rng = the 5-minute bar's range / the previous 5 days' average; streak = consecutive 5-minute bars of one colour, aligned (streak<=-3 = three or more bars AGAINST the trade, e.g. three green bars before buying a PE: a fade); "adverse extreme" = the 5-minute bar closed beyond the previous bar's low for a CE (high for a PE).

Quintiles (Q1 lowest .. Q5 highest) are cut per index on 2020-2024.

| condition | entries | P(+17) | P(-15) | won/resolved (BE 46.9%) | EV gross | EV net+spread |
|---|---|---|---|---|---|---|
| all | 998,241 | 32.7% | 40.5% | 44.7% | -0.67 | -3.28 |
| 09:16-09:44 | 87,773 | 39.3% | 47.3% | 45.4% | -0.54 | -3.17 |
| 09:45-10:29 | 130,937 | 34.3% | 43.0% | 44.4% | -0.77 | -3.38 |
| 10:30-11:59 | 260,387 | 30.7% | 38.1% | 44.7% | -0.62 | -3.26 |
| 12:00-13:29 | 259,489 | 31.0% | 38.6% | 44.6% | -0.67 | -3.27 |
| 13:30-14:29 | 173,232 | 33.8% | 41.7% | 44.8% | -0.68 | -3.28 |
| 14:30-15:00 | 86,423 | 32.5% | 40.9% | 44.3% | -0.81 | -3.37 |
| Mon | 202,533 | 32.2% | 40.4% | 44.3% | -0.68 | -3.35 |
| Tue | 204,462 | 32.4% | 40.1% | 44.7% | -0.73 | -3.25 |
| Wed | 196,461 | 32.1% | 39.7% | 44.7% | -0.64 | -3.22 |
| Thu | 197,627 | 34.0% | 41.1% | 45.3% | -0.67 | -3.18 |
| Fri | 197,158 | 33.0% | 41.4% | 44.4% | -0.64 | -3.42 |
| DTE0 | 180,633 | 33.1% | 39.5% | 45.6% | -0.77 | -2.82 |
| DTE1 | 178,433 | 31.0% | 38.5% | 44.7% | -0.63 | -2.90 |
| DTE2 | 176,604 | 31.2% | 39.1% | 44.4% | -0.62 | -3.04 |
| DTE3-4 | 302,614 | 31.5% | 39.4% | 44.4% | -0.57 | -3.16 |
| DTE5+ | 158,065 | 38.1% | 47.7% | 44.4% | -0.87 | -4.76 |
| vix Q1 | 257,719 | 28.7% | 35.0% | 45.0% | -0.56 | -3.07 |
| vix Q2 | 201,989 | 31.8% | 39.0% | 44.9% | -0.61 | -3.19 |
| vix Q3 | 189,088 | 34.4% | 42.3% | 44.8% | -0.64 | -3.27 |
| vix Q4 | 177,368 | 34.5% | 43.4% | 44.3% | -0.75 | -3.40 |
| vix Q5 | 172,077 | 36.2% | 45.7% | 44.2% | -0.87 | -3.62 |
| prem Q1 | 163,720 | 28.6% | 32.5% | 46.8% | -0.60 | -2.61 |
| prem Q2 | 163,212 | 29.9% | 36.2% | 45.2% | -0.49 | -2.69 |
| prem Q3 | 164,087 | 30.8% | 37.6% | 45.0% | -0.48 | -2.83 |
| prem Q4 | 174,143 | 32.4% | 41.3% | 44.0% | -0.66 | -3.16 |
| prem Q5 | 333,079 | 37.3% | 47.7% | 43.9% | -0.89 | -4.20 |
| b5ret Q1 | 188,222 | 35.2% | 43.4% | 44.7% | -0.59 | -3.23 |
| b5ret Q2 | 200,289 | 30.9% | 38.5% | 44.5% | -0.62 | -3.22 |
| b5ret Q3 | 203,838 | 29.9% | 37.4% | 44.4% | -0.72 | -3.30 |
| b5ret Q4 | 201,272 | 31.2% | 39.0% | 44.4% | -0.78 | -3.37 |
| b5ret Q5 | 189,996 | 36.3% | 44.2% | 45.1% | -0.67 | -3.33 |
| b5rng Q1 | 190,299 | 27.9% | 35.4% | 44.1% | -0.78 | -3.36 |
| b5rng Q2 | 197,142 | 30.5% | 38.2% | 44.4% | -0.70 | -3.31 |
| b5rng Q3 | 198,940 | 32.1% | 40.1% | 44.5% | -0.71 | -3.32 |
| b5rng Q4 | 199,728 | 34.3% | 42.1% | 44.9% | -0.62 | -3.25 |
| b5rng Q5 | 197,516 | 37.9% | 46.0% | 45.2% | -0.58 | -3.21 |
| b5ema Q1 | 188,817 | 35.0% | 43.5% | 44.6% | -0.60 | -3.26 |
| b5ema Q2 | 200,114 | 30.2% | 37.7% | 44.5% | -0.59 | -3.18 |
| b5ema Q3 | 201,077 | 29.7% | 36.1% | 45.1% | -0.57 | -3.13 |
| b5ema Q4 | 202,012 | 31.5% | 38.9% | 44.8% | -0.77 | -3.36 |
| b5ema Q5 | 191,605 | 36.9% | 46.2% | 44.4% | -0.86 | -3.52 |
| dvwap Q1 | 193,015 | 33.8% | 42.9% | 44.0% | -0.78 | -3.44 |
| dvwap Q2 | 203,965 | 30.1% | 38.2% | 44.1% | -0.74 | -3.36 |
| dvwap Q3 | 199,929 | 30.6% | 37.3% | 45.1% | -0.58 | -3.15 |
| dvwap Q4 | 205,910 | 32.4% | 39.3% | 45.2% | -0.59 | -3.19 |
| dvwap Q5 | 195,422 | 36.9% | 45.3% | 44.9% | -0.66 | -3.29 |
| r15 Q1 | 193,780 | 35.3% | 43.6% | 44.7% | -0.58 | -3.25 |
| r15 Q2 | 202,326 | 30.8% | 37.9% | 44.8% | -0.53 | -3.11 |
| r15 Q3 | 202,393 | 30.1% | 36.8% | 45.0% | -0.60 | -3.16 |
| r15 Q4 | 203,654 | 31.3% | 39.1% | 44.5% | -0.80 | -3.40 |
| r15 Q5 | 196,088 | 36.4% | 45.5% | 44.4% | -0.85 | -3.51 |
| dfav Q1 | 206,813 | 33.4% | 40.5% | 45.2% | -0.61 | -3.19 |
| dfav Q2 | 201,139 | 31.8% | 38.8% | 45.1% | -0.55 | -3.13 |
| dfav Q3 | 198,955 | 31.2% | 39.0% | 44.5% | -0.67 | -3.24 |
| dfav Q4 | 198,400 | 32.0% | 39.9% | 44.5% | -0.68 | -3.33 |
| dfav Q5 | 192,934 | 35.2% | 44.7% | 44.1% | -0.85 | -3.55 |
| rexp Q1 | 185,459 | 27.4% | 34.6% | 44.2% | -0.75 | -3.31 |
| rexp Q2 | 201,554 | 29.7% | 37.1% | 44.5% | -0.67 | -3.31 |
| rexp Q3 | 208,301 | 32.0% | 39.5% | 44.8% | -0.62 | -3.25 |
| rexp Q4 | 207,121 | 34.9% | 43.1% | 44.7% | -0.67 | -3.28 |
| rexp Q5 | 195,565 | 39.3% | 48.1% | 45.0% | -0.65 | -3.27 |
| opm5 Q1 | 206,327 | 36.5% | 44.2% | 45.3% | -0.47 | -3.13 |
| opm5 Q2 | 195,069 | 30.2% | 38.4% | 44.0% | -0.74 | -3.32 |
| opm5 Q3 | 187,495 | 28.3% | 36.1% | 43.9% | -0.81 | -3.32 |
| opm5 Q4 | 195,786 | 30.5% | 37.9% | 44.7% | -0.72 | -3.30 |
| opm5 Q5 | 212,993 | 37.2% | 45.2% | 45.2% | -0.64 | -3.35 |
| opposite engulfing | 59,762 | 31.7% | 40.2% | 44.1% | -0.70 | -3.28 |
| no engulfing | 863,620 | 32.6% | 40.5% | 44.6% | -0.68 | -3.30 |
| engulfing our way | 60,243 | 33.0% | 39.8% | 45.4% | -0.59 | -3.19 |
| not inside bar | 848,721 | 32.6% | 40.3% | 44.7% | -0.67 | -3.28 |
| inside bar | 134,904 | 32.7% | 40.9% | 44.4% | -0.74 | -3.35 |
| 5m close beyond prior bar's adverse extreme | 232,192 | 32.8% | 39.4% | 45.4% | -0.37 | -2.99 |
| 5m close inside prior bar | 516,037 | 32.3% | 40.3% | 44.5% | -0.71 | -3.31 |
| 5m close beyond prior bar's favourable extreme | 235,396 | 32.9% | 41.6% | 44.2% | -0.92 | -3.55 |
| streak<=-3 | 110,323 | 32.8% | 39.6% | 45.3% | -0.40 | -3.03 |
| streak-2 | 127,136 | 32.5% | 39.8% | 45.0% | -0.51 | -3.12 |
| streak-1 | 251,660 | 31.9% | 40.7% | 43.9% | -0.81 | -3.41 |
| streak+1 | 252,239 | 32.9% | 39.7% | 45.3% | -0.55 | -3.15 |
| streak+2 | 128,327 | 32.8% | 40.9% | 44.5% | -0.80 | -3.42 |
| streak>=3 | 112,299 | 33.1% | 42.3% | 43.9% | -1.00 | -3.63 |

### The strongest cells on 2020-2024 and what they did in 2025-2026

Every (index, contract, condition cell incl. pairs, T/S/L) ranked by the day-clustered t of its average gross points per entry on 2020-2024 (>= 100 train days). Holdout columns use the same cell and cut-points.

| # | tf | index | contract | condition | T/S/L | train days | train P(+T) / BE | train EV gross | hold P(+T) | hold EV gross | hold EV net+spread |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/30/15 | 598 | 75.1% / 75.0% | +2.20 | 69.8% | +0.02 | -4.11 |
| 2 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/25/15 | 598 | 73.2% / 71.4% | +2.02 | 67.9% | -0.08 | -4.08 |
| 3 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/30/30 | 598 | 79.2% / 75.0% | +2.17 | 72.9% | -0.09 | -4.35 |
| 4 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/30/60 | 598 | 80.0% / 75.0% | +2.14 | 74.1% | -0.25 | -4.53 |
| 5 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/25/30 | 598 | 76.4% / 71.4% | +1.96 | 70.5% | -0.09 | -4.24 |
| 6 | 5m | BANKNIFTY | CE 1-ITM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/25/15 | 788 | 72.1% / 71.4% | +1.11 | 70.0% | +0.23 | -4.16 |
| 7 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/25/60 | 598 | 76.6% / 71.4% | +1.89 | 71.4% | -0.05 | -4.24 |
| 8 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 15/25/15 | 598 | 61.9% / 62.5% | +2.22 | 53.7% | -0.51 | -4.21 |
| 9 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 15/30/15 | 598 | 63.8% / 66.7% | +2.39 | 55.4% | -0.52 | -4.26 |
| 10 | 5m | BANKNIFTY | CE ATM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/25/15 | 788 | 69.8% / 71.4% | +1.05 | 69.7% | +0.35 | -3.95 |
| 11 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/20/15 | 598 | 69.4% / 66.7% | +1.57 | 64.0% | -0.24 | -4.30 |
| 12 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 17/25/15 | 598 | 57.3% / 59.5% | +2.29 | 49.8% | -0.41 | -4.46 |
| 13 | 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/15/15 | 925 | 18.6% / 37.5% | +1.03 | 19.2% | +0.19 | -2.08 |
| 14 | 5m | BANKNIFTY | CE 1-ITM | 12:00-13:29 & streak<=-3 | 10/25/30 | 599 | 75.2% / 71.4% | +1.74 | 69.9% | -0.17 | -4.63 |
| 15 | 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 15/20/15 | 598 | 58.5% / 57.1% | +1.96 | 50.2% | -0.66 | -4.48 |
| 16 | 5m | BANKNIFTY | CE ATM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/15/15 | 788 | 61.3% / 60.0% | +0.84 | 58.8% | -0.13 | -4.37 |
| 17 | 5m | BANKNIFTY | CE 1-ITM | 12:00-13:29 & streak<=-3 | 10/25/60 | 599 | 76.2% / 71.4% | +1.74 | 71.0% | -0.13 | -4.61 |
| 18 | 5m | BANKNIFTY | CE 1-ITM | 12:00-13:29 & streak<=-3 | 10/25/15 | 599 | 71.7% / 71.4% | +1.65 | 67.2% | -0.05 | -4.53 |
| 19 | 5m | BANKNIFTY | CE ATM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/30/15 | 788 | 72.0% / 75.0% | +1.11 | 72.3% | +0.60 | -3.64 |
| 20 | 5m | BANKNIFTY | CE 1-ITM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/30/15 | 788 | 74.5% / 75.0% | +1.12 | 73.1% | +0.55 | -3.87 |

## 2. Rules: chosen on 2020-2024, tested once on 2025-Oct 2026

Shortlist: the top 25 combos per timeframe by train t (positive gross mean, >= 100 train days), each run as a real sequence (one position at a time, next entry only after the exit, max 5 a day); the pick is the best sequential daily t on 2020-2024. Random baseline: the same days, the same number of trades per day, random decision minutes 09:16-15:00, same contract and identical T/S/L (2,000 draws; p = share of random means >= the rule's mean, gross points).

### Best 1m rule: NIFTY PE 1-ITM when *r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7)*; +20 / -15 / 60 min

| period | trades | trades/day | win rate | | Rs/trade | Rs/day | Rs/month | Rs/year | worst day | worst month | max DD | years + | P(month > 0) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2020-24 | 3006 | 2.77 | 43% | gross | +76 | +211 | +4,324 | +52,433 | -4,533 | -11,295 | 25,572 | 5/5 | 72% |
| 2020-24 | 3006 | 2.77 | 43% | net app | +2 | +6 | +127 | +1,534 | -4,992 | -15,252 | 55,002 | 3/5 | 53% |
| 2020-24 | 3006 | 2.77 | 43% | net +1pt spread | -58 | -162 | -3,304 | -40,057 | -5,142 | -19,031 | 197,981 | 0/5 | 34% |
| 2020-24 | 3006 | 2.77 | 43% | stress 2pt | -127 | -352 | -7,190 | -87,177 | -5,292 | -24,004 | 387,110 | 0/5 | 17% |
| 2025-Oct 26 | 1066 | 2.50 | 38% | gross | -35 | -87 | -1,683 | -21,559 | -4,845 | -12,453 | 45,696 | 0/2 | 45% |
| 2025-Oct 26 | 1066 | 2.50 | 38% | net app | -113 | -282 | -5,468 | -70,038 | -5,155 | -17,879 | 126,533 | 0/2 | 23% |
| 2025-Oct 26 | 1066 | 2.50 | 38% | net +1pt spread | -164 | -410 | -7,932 | -101,592 | -5,335 | -23,552 | 178,252 | 0/2 | 9% |
| 2025-Oct 26 | 1066 | 2.50 | 38% | stress 2pt | -213 | -534 | -10,338 | -132,409 | -5,515 | -24,961 | 230,765 | 0/2 | 9% |

Random entries with identical T/S/L (gross pts per trade): train rule +1.27 vs random +0.81 (p 0.054); holdout rule -0.58 vs random +0.90 (p 1.000).

### Best 5m rule: BANKNIFTY PE 1-ITM when *streak<=-3 & b5rng Q4 [0.963,1.29)*; +10 / -25 / 15 min

| period | trades | trades/day | win rate | | Rs/trade | Rs/day | Rs/month | Rs/year | worst day | worst month | max DD | years + | P(month > 0) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2020-24 | 1190 | 1.48 | 73% | gross | +123 | +182 | +3,670 | +45,173 | -4,854 | -7,206 | 13,062 | 4/4 | 78% |
| 2020-24 | 1190 | 1.48 | 73% | net app | +10 | +14 | +285 | +3,512 | -5,315 | -10,572 | 23,932 | 2/4 | 55% |
| 2020-24 | 1190 | 1.48 | 73% | net +1pt spread | -43 | -63 | -1,268 | -15,605 | -5,465 | -10,965 | 55,345 | 0/4 | 45% |
| 2020-24 | 1190 | 1.48 | 73% | stress 2pt | -104 | -154 | -3,103 | -38,191 | -5,615 | -13,485 | 125,377 | 0/4 | 30% |
| 2025-Oct 26 | 671 | 1.54 | 68% | gross | -4 | -7 | -132 | -1,659 | -5,400 | -9,015 | 41,085 | 1/2 | 55% |
| 2025-Oct 26 | 671 | 1.54 | 68% | net app | -182 | -280 | -5,542 | -69,514 | -6,332 | -16,898 | 130,268 | 0/2 | 9% |
| 2025-Oct 26 | 671 | 1.54 | 68% | net +1pt spread | -243 | -375 | -7,422 | -93,089 | -6,452 | -19,532 | 168,560 | 0/2 | 5% |
| 2025-Oct 26 | 671 | 1.54 | 68% | stress 2pt | -314 | -485 | -9,587 | -120,248 | -6,572 | -22,204 | 212,593 | 0/2 | 5% |

Random entries with identical T/S/L (gross pts per trade): train rule +2.06 vs random -1.57 (p 0.000); holdout rule -0.07 vs random -1.06 (p 0.049).

### All shortlisted rules, train vs holdout (Rs per trade)

| tf | index | contract | condition | T/S/L | train trades | train gross | train net+spread | hold trades | hold gross | hold net app | hold net+spread | hold rand p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/15/15 | 3140 | +52 | -77 | 1092 | -26 | -103 | -160 | 0.997 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 20/15/15 | 3191 | +47 | -83 | 1115 | -36 | -113 | -166 | 0.998 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 20/15/60 | 3006 | +76 | -58 | 1066 | -35 | -113 | -164 | 1.000 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/15/60 | 2907 | +76 | -50 | 1037 | -30 | -108 | -164 | 1.000 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/15/30 | 3005 | +64 | -62 | 1070 | -18 | -96 | -147 | 0.994 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 20/15/30 | 3087 | +63 | -67 | 1096 | -28 | -106 | -157 | 0.998 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/20/15 | 3108 | +56 | -72 | 1070 | -22 | -99 | -159 | 0.991 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 20/20/15 | 3162 | +51 | -80 | 1094 | -31 | -107 | -171 | 0.992 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/10/60 | 3104 | +56 | -76 | 1132 | -4 | -83 | -139 | 0.974 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/10/15 | 3236 | +45 | -89 | 1156 | +0 | -78 | -128 | 0.933 |
| 1m | NIFTY | PE ATM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/15/15 | 3102 | +42 | -84 | 1069 | -16 | -87 | -152 | 0.984 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/10/30 | 3148 | +55 | -77 | 1146 | +2 | -77 | -129 | 0.935 |
| 1m | NIFTY | PE 1-ITM | 09:16-09:44 & pcrch Q4 [45.2,188) | 17/10/60 | 1036 | +76 | -67 | 391 | +75 | -5 | -78 | 0.006 |
| 1m | NIFTY | PE 1-ITM | prem Q2 [63.6,88.6) & r15 Q5 (>=8.36) | 25/20/15 | 1875 | +59 | -60 | 617 | +29 | -35 | -108 | 0.333 |
| 1m | NIFTY | PE ATM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 20/15/15 | 3135 | +40 | -86 | 1084 | -20 | -92 | -152 | 0.979 |
| 1m | NIFTY | PE 1-ITM | 09:16-09:44 & pcrch Q4 [45.2,188) | 20/10/60 | 1008 | +84 | -83 | 372 | +33 | -46 | -100 | 0.131 |
| 1m | NIFTY | PE ATM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 25/10/15 | 3166 | +38 | -86 | 1123 | +0 | -72 | -134 | 0.919 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 20/10/60 | 3182 | +47 | -91 | 1161 | -3 | -81 | -136 | 0.952 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 17/15/15 | 3233 | +39 | -92 | 1127 | -48 | -125 | -178 | 1.000 |
| 1m | NIFTY | PE 1-ITM | prem Q2 [63.6,88.6) & r15 Q5 (>=8.36) | 25/15/15 | 1887 | +55 | -68 | 623 | +9 | -55 | -111 | 0.596 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 20/10/15 | 3288 | +42 | -93 | 1180 | -9 | -87 | -138 | 0.949 |
| 1m | NIFTY | PE 1-ITM | prem Q2 [63.6,88.6) & r15 Q5 (>=8.36) | 17/20/15 | 1905 | +49 | -70 | 631 | +9 | -54 | -117 | 0.483 |
| 1m | BANKNIFTY | CE 1-ITM | r5 Q1 (<-6.18) & rexp Q1 (<0.707) | 17/10/15 | 1789 | +55 | -120 | 716 | +55 | -180 | -254 | 0.000 |
| 1m | NIFTY | PE 1-ITM | prem Q2 [63.6,88.6) & r15 Q5 (>=8.36) | 20/20/15 | 1887 | +50 | -68 | 621 | +29 | -35 | -105 | 0.315 |
| 1m | NIFTY | PE 1-ITM | r15 Q5 (>=8.36) & dfav Q2 [9.73,20.7) | 17/15/60 | 3079 | +60 | -69 | 1091 | -54 | -132 | -190 | 1.000 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/30/15 | 1172 | +131 | -35 | 661 | -3 | -180 | -251 | 0.051 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/25/15 | 1190 | +123 | -43 | 671 | -4 | -182 | -243 | 0.049 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/30/30 | 1172 | +129 | -42 | 658 | -15 | -193 | -267 | 0.067 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/30/60 | 1171 | +129 | -43 | 658 | -26 | -203 | -279 | 0.104 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/25/30 | 1190 | +121 | -52 | 671 | -7 | -185 | -251 | 0.047 |
| 5m | BANKNIFTY | CE 1-ITM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/25/15 | 2942 | +54 | -116 | 1679 | +20 | -190 | -244 | 0.000 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/25/60 | 1189 | +118 | -57 | 671 | -5 | -182 | -252 | 0.046 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 15/25/15 | 1178 | +131 | -44 | 665 | -33 | -212 | -259 | 0.179 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 15/30/15 | 1153 | +146 | -31 | 654 | -35 | -213 | -262 | 0.170 |
| 5m | BANKNIFTY | CE ATM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/25/15 | 2891 | +56 | -118 | 1674 | +21 | -176 | -240 | 0.000 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/20/15 | 1199 | +97 | -83 | 680 | -14 | -192 | -253 | 0.073 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 17/25/15 | 1175 | +133 | -45 | 664 | -33 | -212 | -282 | 0.166 |
| 5m | BANKNIFTY | CE 1-ITM | 12:00-13:29 & streak<=-3 | 10/25/30 | 1237 | +91 | -94 | 728 | +1 | -208 | -272 | 0.004 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 15/20/15 | 1188 | +116 | -79 | 677 | -37 | -216 | -267 | 0.218 |
| 5m | BANKNIFTY | CE ATM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/15/15 | 2984 | +43 | -126 | 1712 | -8 | -208 | -266 | 0.001 |
| 5m | BANKNIFTY | CE 1-ITM | 12:00-13:29 & streak<=-3 | 10/25/60 | 1235 | +89 | -99 | 726 | -0 | -210 | -275 | 0.005 |
| 5m | BANKNIFTY | CE 1-ITM | 12:00-13:29 & streak<=-3 | 10/25/15 | 1247 | +89 | -93 | 733 | +2 | -207 | -273 | 0.010 |
| 5m | BANKNIFTY | CE ATM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/30/15 | 2860 | +61 | -110 | 1650 | +37 | -160 | -226 | 0.000 |
| 5m | BANKNIFTY | CE 1-ITM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/30/15 | 2913 | +59 | -115 | 1657 | +39 | -170 | -224 | 0.000 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 17/20/15 | 1185 | +119 | -77 | 676 | -37 | -217 | -289 | 0.226 |
| 5m | BANKNIFTY | PE 1-ITM | streak<=-3 & b5rng Q4 [0.963,1.29) | 17/30/15 | 1148 | +143 | -34 | 653 | -36 | -214 | -282 | 0.182 |
| 5m | BANKNIFTY | CE 1-ITM | 12:00-13:29 & streak<=-3 | 10/30/30 | 1200 | +101 | -77 | 699 | +25 | -183 | -258 | 0.000 |
| 5m | BANKNIFTY | PE ATM | streak<=-3 & b5rng Q4 [0.963,1.29) | 10/20/15 | 1179 | +92 | -72 | 678 | +4 | -164 | -234 | 0.028 |
| 5m | BANKNIFTY | CE 1-ITM | 12:00-13:29 & streak<=-3 | 10/30/60 | 1198 | +100 | -82 | 696 | +29 | -180 | -254 | 0.001 |
| 5m | BANKNIFTY | CE 1-ITM | 5m close beyond prior bar's adverse extreme & streak<=-3 | 10/25/30 | 2938 | +48 | -122 | 1676 | +18 | -193 | -246 | 0.000 |

### 1-minute vs 5-minute decisions

| timeframe | shortlisted | holdout gross > 0 | holdout net+spread > 0 | mean holdout gross Rs/trade | mean holdout net+spread Rs/trade |
|---|---|---|---|---|---|
| 1m | 25 | 10 | 0 | -6 | -145 |
| 5m | 25 | 10 | 0 | -4 | -258 |

### Anchored walk-forward

| test year | picked on all earlier years | trades | gross Rs | net app Rs | net+spread Rs | stress Rs |
|---|---|---|---|---|---|---|
| 2022 | NIFTY CE 1-ITM 5m *b5ema Q4 [1.76,6.41) & b5ret Q5 (>=4.82)* 25/10/30 | 708 | 20,451 | -33,910 | -75,971 | -118,109 |
| 2023 | FINNIFTY CE 1-ITM 1m *r15 Q5 (>=8.86) & dvwap Q5 (>=16.8)* 25/20/15 | 653 | 19,866 | -27,747 | -65,170 | -95,802 |
| 2024 | BANKNIFTY PE 1-ITM 5m *streak<=-3 & b5rng Q4 [0.963,1.29)* 10/25/15 | 335 | 38,763 | -3,823 | -25,839 | -42,298 |
| 2025 | BANKNIFTY PE 1-ITM 5m *streak<=-3 & b5rng Q4 [0.963,1.29)* 10/25/15 | 384 | 11,400 | -50,240 | -71,075 | -96,359 |
| 2026 | FINNIFTY CE 1-ITM 1m *r15 Q5 (>=8.86) & dfav Q1 (<11.7)* 25/10/15 | 561 | -24,465 | -99,722 | -137,260 | -176,033 |
| all | | 2641 | 66,015 | -215,442 | -375,315 | -528,600 |

(Walk-forward years before 2025 use cut-points fitted on 2020-2024, a mild look-ahead in the bucket edges only.)

## Multiple testing

- Combos screened: **1,100,700** = 3 indices x 4 contracts x 75 (T, S, L) x 1,223 condition cells (1m: 777, 5m: 446; singles and pairs of features, both timeframes counted together).
- Day-clustered one-sided p of the mean points per entry > 0 on 2020-2024, BH over all combos: gross q<0.05 = 0 (q<0.10 = 0, min q 0.123); net+spread q<0.05 = 0 (min q 1).
- Hansen SPA_c / White RC over the 24 shortlisted NIFTY rules' train daily P&L vs not trading: gross SPA p = 0.002 (RC 0.002), net+spread SPA p = 1.000. (The shortlist was itself picked from the screen, so these p-values are optimistic.)
- Hansen SPA_c / White RC over the 26 shortlisted BANKNIFTY rules' train daily P&L vs not trading: gross SPA p = 0.000 (RC 0.001), net+spread SPA p = 1.000. (The shortlist was itself picked from the screen, so these p-values are optimistic.)

## Slippage realism for 15-20 point scalps

- Charges alone (app model) cost about 1.4-1.6 pts a round trip on 60 qty; a 1-pt spread adds ~1 pt more on entry plus a stricter target fill and an earlier stop trigger, a 2-pt spread about double that. Against a +17 / -15 bracket that is 10-20% of the win, which is why rules that look flat gross lose net.
- Stops are SL-M on 1-minute bars: when the option gaps through the stop the fill is the bar's open (included), and a target and stop in the same minute counts as the stop. Targets only fill when the traded high goes past the limit by the half-spread.
- Weekly expiry-day ATM options move 15 points in seconds; real fills there are worse than any 1-minute model.

## Reproduce

`flock <scratch>/obuy.lock python3 -I research/obuy/scalp17.py build NIFTY BANKNIFTY FINNIFTY` (~3 min, run the three in
parallel), then `screen <U>` for each (~6 min), `select` (~1 min), `report`. Code: `research/obuy/scalp17.py` (first-passage
build, fill model) and `research/obuy/scalp17_an.py` (conditions, screen, selection, holdout, walk-forward, report). The large
intermediate tables (~600 MB) were deleted after the run to free disk; `select.pkl`, `shortlist.csv`, `top_*.csv` remain in
`<cache>/scalp17/`.
