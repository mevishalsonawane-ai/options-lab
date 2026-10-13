# C1: What is predictable in crypto? (pre-registration)

Written 13 Oct 2026, before any study result was computed. Boss: "I want to create the same model we created for
storing and studying the Indian market, for crypto." This is the crypto version of R16
(`research/HUNT_R16_PREDICTABILITY.md`): which questions the data can answer, on DESIGN data only.

## Data and the holdout

- Symbols: BTCUSDT and ETHUSDT. Binance spot 1-minute klines, USD-M perpetual klines, mark/index/premium klines,
  funding and 5-minute OI / long-short metrics from the Binance bulk archive (data.binance.vision), Oct 2023 – Mar 2026.
  Deribit DVOL (hourly) from the repo's `crypto/data_extra/dvol_1h.csv`, BTC only.
- Built with the crypto brain (`research/brain/crypto`): cube v1, the feature signature recorded in the results file.
- **Locked holdout: every minute from 1 Apr 2026 00:00 UTC on** (`config.HOLDOUT_START`). This study reads none of it;
  design rows whose label window reaches it are dropped (`holdout.design`). It was not even downloaded.
- Design period: from the first day with all features (the 32-day warm-up) to 31 Mar 2026.

## Decision points and targets

- Hourly: the row of each hour's last minute (HH:59, known at HH+1:00). Targets over the next 60 minutes:
  log range (`y_range_60m`) and direction (`y_ret_60m` > 0).
- Daily: the 23:59 UTC row (known at 00:00 UTC). Targets over the next 1,440 minutes: log range and direction.
- Rows with a missing target or with the spot bar not ok are skipped. No other filtering.

## Method

- Walk-forward: `holdout.walk_forward` (research/brain/split.walk_forward): 30 equal blocks of days, the first 6 train
  only, then each block is tested on a model trained on all earlier blocks, with label windows purged and a 1-day
  embargo. Models are refitted each block. Features are standardised and missing values set to the training median.
- Models (fixed now, no tuning): ridge regression (alpha 1.0) for sizes, L2 logistic regression (C 1.0) for direction.

## Tests (one Benjamini–Hochberg family, q = 0.05, both symbols together)

| id | question | inputs | baseline | statistic | pass bar |
|---|---|---|---|---|---|
| A1 | next-hour range | log range 60/240 min, log 24h realised vol, log DVOL (BTC), hour-of-day x weekend mean (fitted in train), weekend | hour-of-day x weekend mean of log range | weekly means of (abs error baseline - abs error model), one-sided t | q < 0.05 and skill >= 5% |
| A2 | next-day range | log previous-day range, log 7-day mean daily range, log 24h realised vol, log DVOL (BTC), weekend-tomorrow | mean log range of the previous 20 days | as A1 | q < 0.05 and skill >= 5% |
| B1 | next-hour direction | returns 60/240/1440 min, funding z, basis z, OI change 60 min, perp taker imbalance, VWAP-24h z, hour (sin/cos), weekend | coin (AUC 0.5) | AUC, one-sided weekly-block bootstrap (2,000) | q < 0.05, AUC >= 0.52 and net > 0 after costs |
| B2 | next-day direction | returns 1440 min, previous-day range, funding z, basis z, OI change 24h, VWAP-24h z, weekend-tomorrow | coin | as B1 | as B1 |
| C1 | funding vs next-day return | funding z (30 days) at 23:59 | none | Spearman rank correlation, two-sided | q < 0.05 |
| C2 | basis vs next-day return | basis z (7 days) at 23:59 | none | as C1 | q < 0.05 |
| C3 | OI change vs next-day return | OI change 24h at 23:59 | none | as C1 | q < 0.05 |
| D1s | weekend size | mean abs next-hour return, weekend vs weekday hours | none | Welch t on weekly means | q < 0.05 |
| D1d | weekend direction | mean next-day return, weekend vs weekday days | none | Welch t | q < 0.05 and net > 0 |
| D2-hh | hour-of-day return (24 per symbol) | mean next-hour return at each UTC hour | 0 | t across days | q < 0.05 and |mean| > round trip |
| D4-dow | day-of-week return (7 per symbol) | mean next-day return by weekday | 0 | t across weeks | q < 0.05 and |mean| > round trip |

40 tests per symbol, 80 in all. Trading versions (B1, B2, D1d, D2, D4) are scored as perpetual taker trades: 0.05%
fee + 1 bp slippage per leg (0.12% a round trip) plus funding at the last settled rate for each settlement held;
stressed at 2x slippage. Direction trades are taken when the model's probability is >= 0.55 (long) or <= 0.45 (short).

## Honest limits, said now

- Design data only. Anything that passes is a **candidate**, not a finding: it needs its one holdout read in a new,
  pre-registered test before any use. Nothing here trades, on paper or live.
- 2.5 years of data and one regime of venue rules. Overlapping hourly windows are handled by weekly blocks.
- Slippage is assumed (no bid/ask in the archive). Sizes in bps of notional.
- Post-hoc descriptions (hour-of-day size shape, funding quintiles) are descriptions, not tests.
