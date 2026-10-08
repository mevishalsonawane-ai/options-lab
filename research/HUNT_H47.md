# HUNT h47: the "TQE 9 EMA + VWAP" guide on BTC and ETH (Delta Exchange India)

## Verdict

**NO. The guide's rules lose money on BTC and ETH. Rs 5,000/day: NO.**

- Before costs the rules are a coin flip. The primary version made about -Rs 7 (BTC) and -Rs 1 (ETH) per trade
  at Rs 1 lakh. That is less than 0.1% of the trade size.
- Costs are about Rs 118 per trade at Rs 1 lakh (taker 0.05% + 18% GST, each side). The rules never come close to
  paying that.
- **Primary version** (pre-registered): setup A "the cross", 3-min, VWAP from 00:00 UTC, perpetual futures, taker.
  - In-sample (Jan 2020 to Mar 2026): **net -Rs 975/day (BTC) and -Rs 920/day (ETH)** at Rs 1 lakh each.
    0 green months out of 75 on both coins.
  - **Locked holdout** (Apr to Oct 2026, run once): **net -Rs 1,009/day (BTC) and -Rs 1,099/day (ETH)**.
    0 of 7 months green. Gross was also negative.
  - It did not beat random entries (p = 0.80 BTC, 0.93 ETH).
- **Variants:** 14,112 were pre-registered: 2,352 rule sets × 2 coins × 3 instruments (taker futures, maker-entry
  futures, options).
  - The best one made **+Rs 7/day**, on 0.2 trades a day.
  - Only 20 are positive after the strict 30% crypto tax.
  - SPA p = 1.00 (taker futures), 1.00 (maker futures), 0.99 (options). **No variant survives**, so only the primary
    went to the holdout.
- **Walk-forward** (each year, pick the best variant from earlier years): 25 of 28 coin-instrument test years lost
  money. The 3 winners made Rs 115 to Rs 1,618 in a year.
- **The guide's quality filters do not create an edge.** Slope, volume and hold make gross per trade no better, and
  often worse. The hours, skip and 5-min choices help by a few rupees. A cost of Rs 118 needs about 10 times that.
- **Options are not the fix.** They cost less per trade (about Rs 45-60 at Rs 1 lakh of underlying) but still lose
  -Rs 383/day (BTC) and -Rs 459/day (ETH) on the primary rules.
- **The guide's "best days" are the lucky tail.** Primary, both coins, in-sample:
  - average day -Rs 1,894;
  - best 5% of days +Rs 4,167;
  - only 1.2% of days reach Rs 5,000.

This is the same answer as h46 (the same EMA9/VWAP family on Indian index options) and NN_CRYPTO (no intraday
direction edge in BTC/ETH that pays Delta's fees).

## What was tested (pre-registered: research/hunt/h47/PREREG.md)

| item | setting |
|---|---|
| Data | Binance USDT-M perpetual 1-min candles with volume, BTCUSDT and ETHUSDT, 2020-01-01 to 2026-10-07 (3.56M rows each, no gaps) |
| Proxy | Delta India BTCUSD/ETHUSD perps track Binance (1-hour return correlation 0.987/0.994, median gap 1-2 bp; NN_CRYPTO). VWAP uses Binance volume. |
| Lines | EMA 9 of bar closes (continuous). VWAP = sum(hlc3 × volume) / sum(volume) since the anchor. |
| Anchors | 00:00 UTC (24h session); 00:00 IST = 18:30 UTC (24h); US cash session 09:30-16:00 ET, Mon-Fri (the guide's) |
| Warm-up | setups A and C take no entry in the first 30 min after the anchor |
| Timeframes | 1, 2, 3, 5 min |
| Fills | signal on a closed bar; fill at the next bar's open; all positions closed at the session end |
| Exit | the first candle that closes back through the 9 EMA (a wick does not count) |
| Setup A, the cross | EMA9 crosses VWAP; enter on the first close beyond both lines (within 3 bars). Filters: VWAP slope (off / with the trade / the guide's "flat or falling" for longs), volume above its 20-bar average, hold 0/15/30 min, hours 10:00-13:30 ET |
| Setup B, the hold | inside the first 30 min (or 30 min after a cross), price riding the EMA9; enter on a bar whose wick tests the EMA9 and closes back with the trend |
| Setup C, the snapback | price more than k × daily ATR(14) from VWAP (k = 0.3, 0.5, 0.75); a candle closes back over the EMA9 toward VWAP; exit at the VWAP touch or the EMA9 exit |
| Skip rules | none / EMA9 flat 15 min / lines too far apart / chop (VWAP flat and 3+ crosses) / all three |
| Instruments | (1) perp futures, taker 0.05% + GST, measured half-spread (BTC 0.03 bp, ETH 0.10 bp), funding; (2) maker entry 0.02% + GST, filled only if price trades through the limit; (3) ATM call/put, nearest daily expiry 6h+ away, priced by Black-Scholes at Deribit DVOL × Delta's live ask/bid IV ratio (BTC 1.039/1.018, ETH 1.165/1.136), Delta option fee |
| Size | Rs 1,00,000 of underlying per trade per coin (unleveraged futures; options on the same notional, premium about 0.7-1.1% of it). Also per 1 contract (0.001 BTC, 0.01 ETH). |
| Tax | VDA strict: 31.2% of each winning trade, no loss set-off. Business income: 31.2% of each financial year's net profit. |
| Random baseline | per trade: a random other day of the same year (same weekday/weekend type), same UTC minute, same side, same holding time, same costs |
| Periods | in-sample 2020-01 to 2026-03 (options from 2021-04, when DVOL starts); walk-forward 2021-2025; locked holdout 2026-04-01 to 2026-10-07 |

The option prices are a model (DVOL plus Delta's live quotes), not traded prices. Delta does not serve candles for
expired options.

## Primary version, in-sample (2020-01 to 2026-03, 2,282 days)

Setup A, 3-min, 00:00 UTC, no filters, taker futures, Rs 1 lakh per coin.

| | BTC | ETH |
|---|---|---|
| trades (per day) | 17,750 (7.8) | 17,361 (7.6) |
| win rate, gross / net | 30% / 16% | 31% / 19% |
| gross Rs per trade | -6.7 | -0.9 |
| net Rs per trade | -125 | -121 |
| gross Rs per day | -52 | -6 |
| **net Rs per day** | **-975** | **-920** |
| after VDA tax, per day | -1,145 | -1,171 |
| after business tax, per day | -975 | -920 |
| per 1 contract: gross / net Rs per trade | -0.20 / -5.70 | +0.10 / -2.44 |
| max drawdown (cumulative) | Rs 22.2 lakh | Rs 21.0 lakh |
| green months | 0 of 75 | 0 of 75 |
| random entries, net Rs per trade | -118 | -120 |
| beats random? | no (p = 0.99) | no (p = 0.64) |
| median holding time | 15 min | 15 min |

- An average winner makes Rs 442-550 net. An average loser costs Rs 232-281.
- The rule loses because only 1 trade in 6 wins after costs.
- The drawdown is larger than the capital: Rs 1 lakh would be gone in about 3 months.

By year, net Rs at Rs 1 lakh (gross in brackets):

| year | BTC net (gross) | ETH net (gross) |
|---|---|---|
| 2020 | -4.09 lakh (-85,778) | -4.15 lakh (-1,01,493) |
| 2021 | -3.13 lakh (-6,098) | -2.45 lakh (+58,104) |
| 2022 | -3.51 lakh (+2,338) | -3.39 lakh (+5,346) |
| 2023 | -3.80 lakh (-13,163) | -3.62 lakh (+8,271) |
| 2024 | -3.50 lakh (-1,801) | -2.86 lakh (+48,063) |
| 2025 | -3.35 lakh (-13,656) | -3.53 lakh (-22,337) |
| 2026 Q1 | -0.86 lakh (-1,426) | -1.00 lakh (-10,741) |

Every year lost about Rs 3-4 lakh per coin after costs. Gross swings either side of zero. The fees alone are about
Rs 3.4 lakh a year on Rs 1 lakh at 8 trades a day.

The same signals on other instruments, in-sample:

| primary signals on | BTC net Rs/trade | BTC net Rs/day | ETH net Rs/trade | ETH net Rs/day |
|---|---|---|---|---|
| taker futures | -125 | -975 | -121 | -920 |
| maker-entry futures (fills 94-95%) | -96 | -706 | -93 | -669 |
| ATM options (from 2021-04) | -49 | -383 | -59 | -459 |
| options after VDA tax | | -481 | | -593 |

- Maker entry saves fees but fills on the worse trades. Gross per trade drops from -Rs 7 to -Rs 13 (BTC) and from
  -Rs 1 to -Rs 9 (ETH).
- Options cost less per trade: about Rs 15 of spread and Rs 24 of fee. They still lose.

## Locked holdout (2026-04-01 to 2026-10-07, 190 days; run once, primary only)

| | BTC | ETH |
|---|---|---|
| trades (per day) | 1,536 (8.1) | 1,559 (8.2) |
| win rate, gross / net | 32% / 16% | 30% / 16% |
| gross Rs per trade | -6.2 | -13.9 |
| net Rs per trade | -125 | -134 |
| gross Rs per day | -50 | -114 |
| **net Rs per day** | **-1,009** | **-1,099** |
| after VDA tax, per day | -1,113 | -1,234 |
| after business tax, per day | -1,009 | -1,099 |
| per 1 contract, net Rs per trade | -8.72 | -2.73 |
| max drawdown | Rs 1.92 lakh | Rs 2.09 lakh |
| green months | 0 of 7 | 0 of 7 |
| beats random? | no (p = 0.80) | no (p = 0.93) |

Monthly net, Rs (both coins lost every month):

| month | BTC | ETH |
|---|---|---|
| Apr 2026 | -29,922 | -29,297 |
| May | -30,590 | -35,194 |
| Jun | -30,430 | -43,512 |
| Jul | -24,000 | -22,895 |
| Aug | -31,715 | -34,046 |
| Sep | -38,324 | -34,593 |
| Oct (1-7) | -6,755 | -9,254 |

Pre-registered success needed holdout net after VDA tax > 0 on both coins and a win over random. **Neither was met.**

## All 14,112 variants (in-sample)

| | taker futures | maker-entry futures | options |
|---|---|---|---|
| variants | 4,704 | 4,704 | 4,704 |
| gross positive | 1,834 | 1,180 | 2,699 |
| net positive (before tax) | 11 | 39 | 87 |
| positive after VDA tax | 2 | 8 | 10 |
| best net Rs/day | +1.5 | +3.6 | +6.8 |
| median net Rs/day | -64 | -47 | -24 |
| beats random at BH q < 0.10 | 63 | 19 | 559 |
| ...and net positive | 2 | 4 | 58 |
| SPA p (all variants, benchmark 0) | 1.00 | 1.00 | 0.99 |

- Every net-positive variant trades rarely, at 0.01 to 0.2 trades a day. The best one makes Rs 1-7 a day.
- 12 taker variants have gross above the Rs 118 fee. Each has fewer than 200 trades in 6 years, which is noise.
- Many option variants "beat random", because random option entries lose even more to spread and time decay. Beating
  random is not the same as making money: only 3 of those 559 are positive after VDA tax.
- SPA near 1 means the best variant is what chance alone gives with this many tries.

Median net Rs/day by setup and timeframe (taker futures; median over its variants):

| setup | 1-min | 2-min | 3-min | 5-min |
|---|---|---|---|---|
| A, the cross | -24 | -63 | -74 | -71 |
| B, the hold | -368 | -176 | -123 | -83 |
| C, the snapback | -205 | -102 | -71 | -37 |

(Setup A's medians look small only because most of its filtered variants trade a few times a month.)

Plain setup A (no filters), net Rs/day, taker futures:

| anchor | BTC 1m | 2m | 3m | 5m | ETH 1m | 2m | 3m | 5m |
|---|---|---|---|---|---|---|---|---|
| 00:00 UTC | -1,598 | -1,183 | -975 | -751 | -1,526 | -1,121 | -920 | -689 |
| 00:00 IST | -1,674 | -1,255 | -1,030 | -791 | -1,666 | -1,224 | -1,036 | -793 |
| US 09:30 ET | -471 | -321 | -258 | -212 | -462 | -334 | -273 | -201 |

The US session loses less per day only because it trades 2-4 times a day instead of 6-13. Its gross per trade is
+Rs 4 to +Rs 16 (3-min and 5-min). That is about 1 bp, against a 12 bp fee.

### Do the guide's filters help? (setup A, taker futures, median gross Rs per trade at Rs 1 lakh)

| filter | off | on |
|---|---|---|
| VWAP slope "with the trade" / the guide's "flat or falling" | -5.0 | -5.3 / -5.9 |
| volume above average on the cross | -5.2 | -5.4 |
| price held 15 / 30 min first | -4.1 | -5.9 / -6.6 |
| hours 10:00-13:30 ET only | -7.5 | +1.5 |
| skip: EMA flat / chasing / chop / all three | -6.4 | -6.6 / -5.6 / -4.8 / -1.2 |
| timeframe 1 / 2 / 3 / 5 min | | -14.2 / -7.6 / -3.7 / +5.5 |
| anchor UTC / IST / US | | -6.3 / -9.7 / +4.4 |

- Slope, volume and hold do nothing or make it worse.
- Hours, skip-all, 5-min and the US anchor each add Rs 5-10. The fee to beat is Rs 118.
- "Everything on" (slope with, volume, hold 15/30, 10:00-13:30 ET, all skips; 24 variants per coin) has gross
  Rs +0.1 (BTC) / +18 (ETH) per trade, net -Rs 118 / -102, at 0.12 trades a day.

Setup B: the "first 30 min" window has gross +Rs 5 to +10 per trade at 1, 2 and 5 min, and -Rs 1 at 3 min. The
"after any cross" window is about -Rs 2. Setup C: gross rises with the stretch: -Rs 10 (k = 0.3), -Rs 4 (0.5),
+Rs 13 (0.75, 0.2 trades a day). None comes near the fee.

### Walk-forward (each year uses the variant with the best net on all earlier years)

| coin | instrument | test years | total net Rs at Rs 1 lakh | years positive |
|---|---|---|---|---|
| BTC | taker futures | 2021-2025 | -25,939 | 0 of 5 |
| BTC | maker futures | 2021-2025 | -18,432 | 2 of 5 (+115, +287) |
| BTC | options | 2022-2025 | -18,905 | 0 of 4 |
| ETH | taker futures | 2021-2025 | -38,521 | 0 of 5 |
| ETH | maker futures | 2021-2025 | -47,644 | 0 of 5 |
| ETH | options | 2022-2025 | -11,322 | 1 of 4 (+1,618) |

The losses look small only because the picked variants are the rare-trading ones. Every pick lost once it was used.
Most picks were a snapback with k = 0.75 or a heavily filtered cross.

## The guide's "best days" against the average day

Primary, BTC + ETH together, Rs 1 lakh each:

| | in-sample (2,282 days) | holdout (190 days) |
|---|---|---|
| average day | -1,894 | -2,108 |
| median day | -1,899 | -1,952 |
| best 5% of days (average) | +4,167 | +918 |
| best 10 days (average) | +10,716 | +918 (best 5% = 10 days) |
| best single day | +14,907 | +3,235 |
| worst 5% of days (average) | -7,113 | -5,951 |
| days positive | 16% | 6% |
| days of Rs 5,000 or more | 1.2% | 0% |

A chart where every cross works is about a 1-in-80 day. The other days are a steady bleed of small losers plus
fees on every trade.

## Why it fails, in one line

The cross catches about 0 bp of move on average. A taker round trip costs 11.8 bp (8.3 bp with maker entry), and
an ATM option costs about 4-6 bp of the underlying in spread and fee. The 30% VDA tax on winners makes it worse.

## Caveats

- **Proxy.** Prices, volume and VWAP come from Binance. Delta's own book is thinner, but its spread is tiny (BTC
  0.03 bp, ETH 0.10 bp in four snapshots) and Rs 1 lakh is about 12 BTC contracts, well inside the top of the book.
- **Funding** is Binance's 8-hourly rate (Delta's runs slightly higher). The archive has no October 2026 file, so
  1-7 Oct carries no funding. It is a few rupees a day either way.
- **Option prices** are a model: DVOL × today's Delta ask/bid IV ratios at an 18-hour tenor, applied to every year.
  Short-dated IV is not always at that ratio to DVOL. The futures results do not depend on this.
- **Session end.** The 24h anchors close every position at the anchor, so the VWAP resets.
- **"Per day"** is per calendar day. Crypto trades 7 days a week. The US anchor trades Monday-Friday only.
- **Holdout run.** The first holdout run crashed in its monthly-table code (a pandas call) before any number was
  printed or saved. The bug was fixed and the same pre-registered run was repeated once
  (`scratchpad/hunt/h47/HOLDOUT_CRASH_NOTE.txt`).
- **Thresholds.** The flat, chase and chop thresholds are percentiles of in-sample feature values, set before any
  P&L was seen (`thresholds.json`). The guide's "location" (premarket and prior-day levels) and "SPY/QQQ agree"
  checks have no crypto version and were not coded.

## Files

- Code: `research/hunt/h47/`
  - `PREREG.md`: the pre-registration.
  - `fetch.py`: the Binance archive, funding, Deribit DVOL and Delta India snapshots.
  - `calib.py`: Delta spreads and IV ratios.
  - `core.c`: the rule state machine (C via ctypes; built automatically).
  - `lib.py`: bars, lines, sessions, costs, the option model and the random baseline.
  - `sim.py`: all 14,112 variants, in-sample.
  - `analyze.py`: BH, SPA, walk-forward, primary detail and best days.
  - `detail.py`: one-variant deep run.
  - `holdout.py`: the locked holdout. It refuses a second run.
- Logs and outputs: `scratchpad/hunt/h47/`, 271 MB in all.
  - `data/`: 140 MB of zstd parquet.
  - `sim.log`, `an.log`, `holdout.log`.
  - `is_summary_q.csv`: every variant with its p and q values.
  - `is_daily.npy`: daily net, 129 MB, can be deleted.
  - `wf.csv`, `an.json`, `holdout.json`, `thresholds.json`, `delta_cal.json`.
  - `prim_is_*.parquet`, `prim_hold_*.parquet`: primary trades.
- Nothing under `android/` was touched. Nothing was committed.
