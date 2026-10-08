# HUNT h46: the "VWAP reject" strategy (EMA9 vs VWAP, buy the rejection candle, exit on EMA9)

## Verdict

**NO. The strategy loses money. Rs 5,000/day: NO.**

- Before costs it is a coin flip: gross is about Rs 0 per trade.
- After real costs it loses Rs 75-155 per trade on every index.
- The more it trades, the more it loses. On 1-minute charts that is up to Rs 1,900 a day per index.
- **Primary version** (5-min, TWAP, ATM, NIFTY + BANKNIFTY, exit on a close beyond EMA9):
  - In-sample 2020-21 to Sep 2025: **net -Rs 818/day**, gross -Rs 59/day. 9.6 trades/day. Win rate 27%.
  - **Locked holdout** (Oct 2025 to Oct 2026, run once): **net -Rs 1,505/day**, gross -Rs 287/day. Win rate 27%.
    Only 1 of 13 months was positive.
- **Variants:** 2,400 were pre-registered. None survives multiple-testing correction.
  - Best BH q = 0.12. SPA p = 0.75 (TWAP) and 0.84 (stock volume).
  - Walk-forward: -Rs 5.4 lakh in total. It was negative on 4 of the 5 indices.
- **Volume-weighted VWAP does not help.** Version (b), weighted by constituent stock volume, did no better than TWAP
  over the same months.
- **Futures VWAP does not help.** Version (c), on the futures chart, lost on all five indices. It covers only 47 days,
  all inside the holdout, so it is descriptive only.
- **His chart days are the lucky tail.** For the primary version:
  - The best 5% of days average +Rs 6,660.
  - The median day is -Rs 905.
  - Only 3.5% of days make Rs 5,000 or more.
  - Only 2% of index-days have two or more trades that all win, which is what a clean "textbook" chart shows.

This rule has already failed here in other forms: OBUY_GB TI-04 (EMA9 x TWAP) and TI-05 (VWAP pullback). h46 tests
the exact social-media version and gets the same answer.

## What was tested (pre-registered: research/hunt/h46/PREREG.md)

| item | setting |
|---|---|
| Trend | EMA9 of candle closes (continuous across days, like a chart) above / below session VWAP |
| VWAP | (a) TWAP of hlc3 (no volume); (b) hlc3 weighted by constituent-stock traded value, from Oct 2024; (c) futures chart + futures volume, Jul-Oct 2026 (in holdout) |
| Rejection R1 | bull: low within 0.02% of VWAP (or below it), open and close above VWAP; bear mirror |
| Rejection R2 | R1 plus a green candle (bull) or a red candle (bear) |
| Entry | buy CE / PE, ATM or 1-ITM, nearest expiry, at the open of the next minute |
| Exit CLOSE | a later candle CLOSES beyond EMA9 against the trade; sell at the next minute's open |
| Exit TOUCH | a 1-minute bar breaches the last closed EMA9; sell at the next minute's open |
| Always | square-off 15:10; one position at a time per index; max 3 / 5 / unlimited trades a day; expiry days skipped or allowed |
| Timeframes | 1, 2, 3, 5, 15 minutes |
| Indices | NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX |
| Costs | app fills (5 bps) and app charges; real spread charged as max(half-spread, 5 bps) per side (BN / NIFTY 0.16%, FIN 0.42%, MIDCP 0.21%, SENSEX 0.20%) |
| Random baseline | the same index, year, minute of day, side and holding time; a random other day; B = 200 (B = 2,000 for the 68 net-positive variants) |

## Primary version, in-sample (NIFTY + BANKNIFTY, 5-min, TWAP, R1, CLOSE, ATM, unlimited, skip expiry)

| | value |
|---|---|
| trades / days | 10,531 / 1,101 (9.6 a day across both indices) |
| win rate (net / gross) | 26.7% / 35.4% |
| gross per trade | -Rs 6 |
| charges + extra spread per trade | Rs 64 + Rs 15 |
| net per trade | -Rs 86 |
| gross per day | -Rs 59 |
| **net per day** | **-Rs 818** |
| max drawdown, 1 lot each | Rs 9.07 lakh (it only went down) |
| worst / best day | -Rs 14,937 / +Rs 20,754 |
| days positive | 30% |
| beats random (time-matched)? | no (p = 1) |

By year (net Rs, both indices):

| year | trades | gross | net | win |
|---|---|---|---|---|
| 2020 (Aug+) | 526 | +29,035 | -11,624 | 26% |
| 2021 | 1,451 | +6,574 | -106,015 | 29% |
| 2022 | 2,060 | +4,507 | -159,075 | 28% |
| 2023 | 2,449 | -22,935 | -186,272 | 25% |
| 2024 | 2,191 | +14,537 | -136,267 | 27% |
| 2025 (to Sep) | 1,854 | -96,900 | -301,587 | 26% |

Every year lost money after costs.

## Locked holdout (2025-10-01 to 2026-10-05; run once, primary only: no variant qualified)

| | NIFTY + BN | NIFTY | BANKNIFTY |
|---|---|---|---|
| trades | 2,466 | 1,096 | 1,370 |
| trades / day | 10.4 | 5.6 | 5.8 |
| win rate | 27% | 28% | 26% |
| gross / trade | -Rs 27 | -Rs 22 | -Rs 32 |
| net / trade | -Rs 144 | -Rs 108 | -Rs 172 |
| gross / day | -Rs 287 | -Rs 121 | -Rs 186 |
| **net / day** | **-Rs 1,505** | -Rs 606 | -Rs 1,001 |
| max drawdown | Rs 3.64 lakh | Rs 1.27 lakh | Rs 2.43 lakh |
| worst month | -Rs 51,736 | -Rs 21,275 | -Rs 42,395 |
| months positive | 1 / 13 | 2 / 13 | 0 / 13 |

The holdout is worse than in-sample for two reasons. Lot sizes and premiums are bigger now, and STT is 0.15% from April 2026.

## All variants (in-sample, 2,400)

Net Rs per day, 1 lot. Settings: TWAP, R1, CLOSE exit, ATM, unlimited trades, expiry skipped.

| index | 1-min | 2-min | 3-min | 5-min | 15-min |
|---|---|---|---|---|---|
| NIFTY | -1,516 | -911 | -587 | -448 | -147 |
| BANKNIFTY | -1,910 | -1,091 | -804 | -530 | -270 |
| FINNIFTY | -1,937 | -1,113 | -808 | -536 | -358 |
| MIDCPNIFTY | -1,191 | -722 | -582 | -290 | +103 |
| SENSEX | -1,573 | -975 | -690 | -478 | -155 |

Gross Rs per day for the same cells:

| index | 1-min | 2-min | 3-min | 5-min | 15-min |
|---|---|---|---|---|---|
| NIFTY | -57 | -59 | +43 | -29 | +22 |
| BANKNIFTY | -234 | -114 | -77 | -43 | -73 |
| FINNIFTY | +41 | +51 | +51 | +37 | -129 |
| MIDCPNIFTY | +156 | +87 | +19 | +111 | +275 |
| SENSEX | -47 | -91 | -47 | -55 | +21 |

- 1-minute charts: about 18 trades a day per index. Costs of Rs 75-90 a trade swamp a gross of about 0.
- TOUCH exit (the "pushed past EMA9" variant) is worse than CLOSE everywhere. Example, 5-min, net/day: NIFTY -767
  vs -448, BANKNIFTY -890 vs -530. It trades more and its win rate drops to 15-23%.
- 1-ITM vs ATM: no difference (Rs -101 vs -107 per trade on average).
- Green/red candle filter (R2): fewer trades, so it loses less per day. Per trade it is the same (Rs -98 vs -110).
- Max 3 trades a day loses least per day only because it trades least.
- Expiry days skipped or allowed: no difference.
- **68 of 2,400 variants are net positive.** They are all 15-minute charts, and 48 of them are MIDCPNIFTY.
  - The MIDCPNIFTY 15-min CLOSE family made Rs +60-335/day. It beats the time-matched random baseline
    (p = 0.0005 at B = 2,000).
  - **It does not survive correction:** BH q = 0.12 and SPA p = 0.75.
  - It was also not steady. The TWAP version made +18k in 2023, -7k in 2024 and +51k in 2025 (to Sep).
  - MIDCPNIFTY monthly options are thin (h7/h10), so this capacity is small.
  - Per protocol it was not run on the holdout. It is a lead at most, not a rule.
- Walk-forward (each year, the variant with the best earlier results is picked) by index: NIFTY -1.81 lakh,
  BANKNIFTY -2.18 lakh, FINNIFTY -1.36 lakh, SENSEX -0.18 lakh, MIDCPNIFTY +0.14 lakh (one test year). Total
  -5.39 lakh.

## Which VWAP? (5-min, R1, CLOSE, ATM, same window Oct 2024 to Sep 2025)

| index | TWAP net/trade | stock-volume VWAP net/trade |
|---|---|---|
| NIFTY | -132 | -138 |
| BANKNIFTY | -154 | -132 |
| FINNIFTY | -175 | -197 |
| MIDCPNIFTY | -123 | -153 |
| SENSEX | -104 | -76 |

The choice of VWAP does not matter. Both lose.

Futures-chart version (c), 47 days in the holdout. Primary settings, net Rs/day: NIFTY -959, BANKNIFTY -842,
MIDCP -407, SENSEX -251, FINNIFTY (9 days) -362. 127 of its 1,200 cells were net positive. That is what noise
gives on 47 days.

## How his chart days compare with the average day (primary, NIFTY + BANKNIFTY, in-sample)

| | Rs/day |
|---|---|
| average day | -818 |
| median day | -905 |
| best 5% of days (average) | +6,660 |
| best 10 days (average) | +11,130 |
| worst 5% of days (average) | -7,295 |
| days >= Rs 5,000 | 3.5% |
| days positive | 30% |
| index-days with 2+ trades, all winners | 1.9% |

A screenshot of a day where every rejection worked is a 1-in-30 to 1-in-50 day. The other days are a slow bleed of
small losers. Wins average about 3x the losses, but only 1 trade in 4 wins, and charges come out of every trade.

## Caveats

- **VWAP (b)** uses approximate current constituent lists (survivorship). It is weighted by traded value, not by
  index weight.
- **Futures data** covers only Jul to Oct 2026.
- **Thin early option data:** 56% of FINNIFTY 2021 trades had the same entry and exit print, so FINNIFTY 2021 is
  unreliable. Other indices and years are below 4%.
- **Index-based exits** are decided on index closes and filled at the next minute's option open. No intrabar
  premium stop was used, because the strategy has none.
- **The TOUCH exit** uses the EMA9 of the last closed candle as the line.

## Files

- Code: `research/hunt/h46/`
  - `PREREG.md`: the pre-registration.
  - `sim.py`: signals, exits, pricing and the random baseline.
  - `analyze.py`: variants, BH, SPA, walk-forward, chart days and the holdout.
  - `base2.py`: the B = 2,000 baseline rerun.
  - `fut.py`: the futures-chart version.
- Logs and outputs: `scratchpad/hunt/h46/`
  - `sim.log`, `an.log`, `an.json`, `holdout.log`, `fut.log`.
  - `variants.csv`, `variants_b2.csv`, `wf.csv`, `holdout.csv`, `fut_variants.csv`.
  - `trades_*.parquet`: 48 MB in total.
