# C1: What is predictable in crypto? (BTC and ETH, design data only)

Written 13 Oct 2026. Boss asked for "the same model we created for storing and studying the Indian market, for crypto".
This is the crypto version of R16 (`HUNT_R16_PREDICTABILITY.md`), run on the new crypto Data Brain
(`research/brain/crypto`). Pre-registration: `research/brain/crypto/PREREG.md`, committed (5adf2a6) before any result
was computed. Code: `research/brain/crypto/study.py`. Every number: `research/brain/crypto/results/c1_design.json`.

## Verdict (read this first)

**Only the SIZE of the next move is predictable, not its direction: the range of the next hour and of the next day,
for both BTC and ETH.** Crypto is also much quieter at the weekend. Nothing about direction pays after costs.

| test | verdict (design data, holdout NOT read) | effect |
|---|---|---|
| A1 range of the next hour | **PREDICTABLE** (BTC and ETH) | error 24.7% (BTC) / 19.9% (ETH) smaller than the hour-of-day average |
| A2 range of the next day | **PREDICTABLE** (BTC and ETH) | error 19.9% (BTC) / 15.1% (ETH) smaller than the 20-day average |
| D1s weekend size | **real**: weekends are quieter | weekend hours move 0.595x (BTC) / 0.672x (ETH) of weekday hours |
| B1 direction, next hour | statistically real, **worthless after costs** | AUC 0.535 (BTC) / 0.543 (ETH), but gross +0.52 / -1.25 bps a trade against a 12 bps round trip |
| B2 direction, next day | not predictable | AUC 0.500 (BTC) / 0.513 (ETH) |
| C1-C3 funding, basis, OI change vs next-day return | not predictable | rank correlation -0.063 to +0.026, best q 0.26 |
| D1d weekend vs weekday return | no difference | q 0.91 / 0.95 |
| D2 hour-of-day return (48 tests) | nothing beats costs | best: ETH 21:00-22:00 UTC +7.5 bps (q 0.02), below the 12 bps round trip |
| D4 day-of-week return (14 tests) | not significant | best q 0.12 (BTC Wednesday +62.4 bps, 125 weeks) |

Multiple testing: 80 tests (40 per symbol), one Benjamini-Hochberg family. 6 pass every pre-registered bar: A1, A2 and
D1s on both symbols (q < 0.001). B1 is significant (q < 0.001) but fails its trading bar (net -11.51 bps BTC, -13.29
bps ETH). ETH 21:00 UTC passes q (0.02) but not its cost bar. Everything else has q above 0.07.

**These are design-period results.** The locked holdout (1 Apr 2026 on) was not read and was not even downloaded. A
pass here is a candidate for one pre-registered holdout read, not a proven finding. Nothing here trades, on paper or
live.

## Data

| source | what | span | rows |
|---|---|---|---|
| Binance bulk archive (data.binance.vision), checksums verified | BTCUSDT and ETHUSDT spot 1-minute klines, USD-M perpetual 1-minute klines, mark / index / premium-index klines, funding settlements, 5-minute OI and long/short metrics | 1 Oct 2023 - 31 Mar 2026 | 913 days each, 1,314,720 minutes each, no gap minutes |
| repo `crypto/data_extra/dvol_1h.csv` | Deribit DVOL hourly (BTC only; there is no ETH DVOL file) | Sep 2023 - Sep 2026 | |

The crypto lake on Google Drive (the sibling session) had no crypto files yet when this ran, so the sample came straight
from the archive (2,186 files, 416 MB, outside git). Design rows: 21,143 hourly decision points and 880 daily ones per
symbol (2 Nov 2023 - 31 Mar 2026 after the 32-day feature warm-up). Out-of-sample rows (walk-forward): 16,823 hourly
and 670 daily per symbol.

Method: 30 equal blocks of days, the first 6 train only, each later block predicted by a model trained on all earlier
blocks, label windows purged and a 1-day embargo (`research/brain/split.walk_forward`). Ridge for sizes, L2 logistic for
direction, fixed in advance. Costs: perpetual taker fee 0.05% a side plus 1 bp assumed slippage a side (0.12% a round
trip), plus funding at the last settled rate for each settlement held; stressed at 2x slippage.

## A) Size: PREDICTABLE, next hour and next day

Error = mean absolute error of the log range (0.35 means typically off by a factor of about 1.4).

| test | model error | baseline error | skill | weeks | q |
|---|---|---|---|---|---|
| A1 BTC next hour | **0.347** | 0.462 (hour-of-day x weekend average) | 24.7% | 101 | < 0.001 |
| A1 ETH next hour | **0.353** | 0.441 | 19.9% | 101 | < 0.001 |
| A2 BTC next day | **0.358** | 0.447 (20-day average) | 19.9% | 97 | < 0.001 |
| A2 ETH next day | **0.365** | 0.430 | 15.1% | 97 | < 0.001 |

- Simpler baselines (post hoc, walk-forward the same way): the last hour's range alone gives 0.392 (BTC) and 0.391
  (ETH) for the next hour; DVOL alone 0.494 (BTC). For the next day, yesterday's range alone gives 0.426 (BTC) and 0.400
  (ETH); DVOL alone 0.427 (BTC). The model beats each of them: recent realised ranges carry most of it, the hour of day
  the rest. DVOL adds little at these horizons.
- Typical sizes (design, medians): BTC next-hour range 0.546% (10-90%: 0.244-1.307%), next-day range 3.34%
  (1.55-6.68%); ETH 0.786% (0.378-1.798%) and 4.64% (2.37-9.11%).
- A portable next-hour formula (post hoc, fitted on all 21,143 design hours; natural logs; rangeK = high - low of the
  last K one-minute bars, % of the close; rv1440 = realised vol of the last 24 hours, %):
  BTC: log range = -0.613 + 0.411 log range60 + 0.172 log range240 + 0.309 log rv1440;
  ETH: log range = -0.623 + 0.365 log range60 + 0.131 log range240 + 0.373 log rv1440.
  It is a median-type forecast (half the hours come in above it).

## B) Direction: real reversal next hour, but it cannot pay

| test | AUC | up share | trades (p >= .55 / <= .45) | hit rate | gross / trade | net / trade | net at 2x slippage | q |
|---|---|---|---|---|---|---|---|---|
| B1 BTC next hour | 0.535 | 50.4% | 4,340 | 55.0% | +0.52 bps | **-11.51 bps** | -13.51 bps | < 0.001 |
| B1 ETH next hour | 0.543 | 50.4% | 5,217 | 55.4% | -1.25 bps | **-13.29 bps** | -15.29 bps | < 0.001 |
| B2 BTC next day | 0.500 | 49.6% | 370 | 50.0% | -3.69 bps | -16.3 bps | -18.3 bps | 0.86 |
| B2 ETH next day | 0.513 | 50.0% | 280 | 49.3% | -25.04 bps | -37.61 bps | -39.61 bps | 0.69 |

- What drives B1 (post hoc, single inputs): the last 60 minutes' return has AUC 0.465 (BTC) / 0.464 (ETH), i.e. a
  short-term **reversal**: after an up hour the next hour is slightly more often down. VWAP-24h z and the perp taker
  imbalance point the same way. Funding, basis and OI change are at 0.49-0.50 (nothing).
- It is not the last trade's bounce: with the entry one minute later the AUC stays 0.536 (BTC) / 0.540 (ETH).
- Why it cannot pay: it calls the direction of small moves right and the big ones no better than a coin, so 55% hits
  are worth about 0 bps gross. A taker round trip costs 12 bps; even a maker-only round trip (0.04%, 4 bps) would not
  be covered.

## C) Funding, basis and OI: no next-day signal

Spearman correlation with the next 24-hour return, at 23:59 UTC each day (880 days per symbol; 876 / 878 for OI change):

| | funding z (30 d) | basis z (7 d) | OI change 24 h |
|---|---|---|---|
| BTC | -0.005 (q 0.96) | -0.026 (q 0.84) | -0.063 (q 0.26) |
| ETH | -0.010 (q 0.94) | -0.024 (q 0.86) | +0.026 (q 0.84) |

Funding is positive on 88.2% (BTC) / 86.7% (ETH) of days, median 0.7 / 0.67 bps per 8 hours: longs usually pay. High
funding does not foretell a fall the next day in this sample.

## D) Calendar

- **Weekends are quieter** (D1s): mean absolute next-hour move 22.7 bps at weekends vs 38.2 bps on weekdays (BTC,
  126 weeks), 34.6 vs 51.5 bps (ETH). Weekend direction is no different (D1d: BTC +1.1 vs +10.2 bps a day, q 0.91).
- **Hour of day shapes the size, not the direction.** The busiest hour is 14:00-15:00 UTC (the US open, 19:30-20:30
  IST), the quietest 05:00-06:00 UTC (BTC) / 06:00-07:00 (ETH); the busiest hour's median range is 2.29x (BTC) / 2.0x
  (ETH) the quietest. No hour's mean return beats the round trip: the largest is ETH 21:00-22:00 UTC, +7.5 bps over
  881 days (q 0.02), against 12 bps of costs.
- Day of week (D4, 125-126 weeks each): no weekday is significant after BH (best q 0.12).

## What the app and Jarvis could use

1. **Expected range, next hour and next day**, for BTC and ETH: "BTC is likely to move about 0.55% in the next hour
   (typically 0.24-1.31%)". Uses: stop and target sizing in % terms, a "too quiet to cover costs" warning, Jarvis
   explaining whether the market is busy for the hour. Any trading use is a new, pre-registered test.
2. **Weekend and hour-of-day context**: weekends move about 0.6-0.7x of weekdays; the US open is the busy hour.
3. **Honest "no"**: direction (any horizon), funding, basis and OI do not predict the next day. When Boss asks "will
   BTC go up?", the data's answer is "nobody's model here can tell; here is how far it is likely to move".

## Deviations and caveats (all of them)

1. Design data only, 2.5 years of one exchange. The holdout (1 Apr 2026 on, about 6 months) is locked and unread.
2. Slippage is an assumption (no bid/ask in the archive). The weekly-block tests handle the overlap of hourly windows;
   C1-C3 use one non-overlapping row a day.
3. B1's statistical significance comes from 16,822 / 16,816 out-of-sample hours: a tiny effect is detectable but
   cannot cover even maker fees.
4. Post-hoc numbers (simpler baselines, single-input AUCs, the one-minute-later check, the formula, typical sizes) are
   descriptions, not tests, and were not in the BH family.
5. ETH has no DVOL input (no file); its A1/A2 models ran without it.
6. SOL, BNB and XRP are not in this run: the archive sample covered BTC and ETH only. The same commands run them
   when the lake has them.
7. Walk-forward deviation (found in review, not in the pre-registration): a block is tested only when its training set
   has at least 200 rows. For the daily tests (A2, B2) the first test block had 179, so in effect the first 7 of 30
   blocks trained only, not 6 (880 daily rows, 670 tested). The hourly tests used 6 as stated.
8. Wording deviations from PREREG.md: rows whose spot bar is `stale` (zero volume) are kept, only gap / suspect rows
   are dropped (there were none); D1d's cost bar is coded as |weekend - weekday| > the round trip. Neither changes a
   verdict. C3 has 876 (BTC) / 878 (ETH) days, where the OI change needs both ends.
9. Review fix before publishing (13 Oct): Binance's 5-minute metrics are stamped at the START of their period (their
   OI value matches the mark price 5 minutes later), so cube v1 had OI and long/short values up to 4 minutes early.
   Cube v2 places them at the period's end and every number here is from the rebuilt v2 cube. C1 samples HH:59 and
   23:59 only, so the effect on it was at most one snapshot.
