# NN-CRYPTO: BTC/ETH for an Indian resident, and a neural network built on it

Written 8 Oct 2026 by hunter NN-CRYPTO. This is the base for a later study. All numbers are from public data, with no
API keys. Costs are Delta Exchange India's own published rates. The tax rules are treated as a constraint here, not as
advice.

## Verdict (read this first)

**The network finds a real but tiny direction signal in BTC and ETH. It does not survive costs and Indian tax.**

- **The signal is real.** The 1-D CNN predicts the next 15/60/240-minute direction with AUC 0.52-0.57 in every year,
  2022 to 2026. It stays at 0.536 (BTC) and 0.542 (ETH) for 60 minutes on the locked last 6 months.
  - Gradient boosting does as well. Logistic regression is a little worse. The random baseline sits at 0.50.
  - At extreme scores the NN beats random entries. In the locked 6 months, BTC 60-min futures at the top/bottom 5%
    gave p = 0.001 (BH q = 0.03), the smallest p a 1,000-draw test can give.
- **The signal is far too small to pay costs.**
  - At the extremes, the NN's average gross move is **Rs 1-2.6 per BTC contract** (1 contract = 0.001 BTC, about
    Rs 8,000).
  - One taker round trip on Delta India costs **about Rs 9.4** at today's price (0.05% a side + 18% GST).
  - So the edge pays a quarter of the fee, at best.
- **No tested version made money after tax.** That is 64 pre-registered variants: 2 coins × 2 horizons × 2
  thresholds × futures/options × 4 models.
  - On the 2022-2026Q1 walk-forward, 1 of 64 was positive before tax and 0 of 64 after the 30% tax.
  - On the locked holdout (Apr-Oct 2026), the result was the same: 1 of 64 before tax and 0 of 64 after.
  - The one survivor before tax made +Rs 323 on 167 trades. Against random entries it scored p = 0.19 (BH q = 0.71),
    which is noise.
- **At Rs 1 lakh, the NN's best-ranked futures rule lost money in the locked 6 months.** That rule is BTC, 60 min,
  top/bottom 5%.
  - It lost Rs 6,540 per contract before tax.
  - Sized to Rs 1 lakh unleveraged (12 contracts), that is about **-Rs 78,000 in 6 months**.
  - It lost less than random entries did, but it still lost.
- **India's tax rule makes it worse.** Under the 30% VDA rule with no loss set-off, each winning trade is taxed and
  losers give nothing back.
  - The rule above still owed about **Rs 1,770 per contract of tax on an overall loss**.
  - Under that rule, a 1:1 trader needs to win **59.2%** of trades just to break even, against 50% before tax.
- **The market facts that do hold are about timing and size, not direction.**
  - Moves cluster at **18:00-23:00 IST**, around the US open and US data.
  - Weekends move about 30% less.
  - FOMC hours move about 3.8 times a normal hour, with no follow-through.
  - Short horizons lean slightly toward mean reversion.
  - BTC options were usually priced above the volatility that followed (dear to buy). That gap has nearly closed in
    2026.

**Plain answer: the follow-up study should not expect a profitable intraday direction trade in BTC/ETH for an Indian
retail account at taker fees. The model's size forecasts (volatility) are good and can be reused.**

## 1. Data obtained

Every source below is public. No keys were used.

| source | what | status | rows / span | size |
|---|---|---|---|---|
| Binance REST API (api/fapi.binance.com) | anything | **HTTP 451, blocked** | - | - |
| **Binance public archive** (data.binance.vision) | USDT-M perpetual 1m candles BTCUSDT, ETHUSDT (OHLC, volume, taker-buy volume, trades) | **200, used** | 3.56M each, 2020-01-01..2026-10-07, no gaps | 89 + 88 MB |
| same | spot 1m BTCUSDT, ETHUSDT | used | 1.05M each, 2018-01..2019-12 | 27 + 23 MB |
| same | funding rate (8h) | used | 7,395 each, 2020-01..2026-09-30 (Oct missing in archive) | 0.1 MB |
| same | 5-min open interest, long/short ratios, taker buy/sell ratio | used | BTC from 2020-09, ETH from 2021-12 to 2026-10-07 | 25 MB |
| **Deribit** www | DVOL (30-day implied vol index) hourly BTC, ETH | used | 48,592 each, 2021-03-24..2026-10-08 | 1.1 MB |
| Deribit www | daily 08:00 UTC delivery prices | used | full history | 0.04 MB |
| Deribit www | full option chain now (bid/ask/mark/IV/OI, 944 BTC + ETH options) | used | snapshot 8 Oct 15:11 UTC | 0.06 MB |
| **Deribit history** (history.deribit.com) | historical option trades (price, mark, IV, side) | used, **sample** | 536,382 trades: one rotating 1-hour window every 3rd day, 2020-01..2026-10 | in parquet |
| **Delta Exchange India** (api.india.delta.exchange) | contract specs + fee rates (perps, 861 options) | used | snapshot | 0.04 MB |
| Delta India | ticker chain with best bid/ask, bid/ask IV, greeks, OI; L2 books of both perps | used | snapshot | 0.08 MB |
| Delta India | perp 1m candles BTCUSD, ETHUSD | used | BTC from 2023-12-29, ETH from 2024-02-06 | 17 MB + |
| Delta India | hourly funding and OI series (FUNDING:, OI: candles) | used | 2024-02..2026-10 | small |
| Delta India | option candles | **live contracts only**; expired contracts return empty | - | - |
| Coinbase Exchange, Kraken, OKX, Bitstamp, Bitfinex, BitMEX, Gemini, CoinGecko, WazirX, CoinDCX | probed | 200 (reachable) | not needed: the Binance archive is longer and cleaner | - |
| Bybit API | probed | **403** (its public.bybit.com archive answers 200, not used) | - | - |
| CryptoCompare | probed | 401 (needs a key; not used) | - | - |
| Hyperliquid | probed | 405 (POST-only API; not tried) | - | - |
| FX | USD/INR | 96.78 (frankfurter.dev, 8 Oct 2026) | - | - |

All data is 355 MB of zstd parquet in `scratchpad/hunt/nn_crypto/data/`. The derived 15-minute feature table adds
74 MB and predictions add 60 MB, about 490 MB in all. Raw zips were parsed in memory and never kept.

**Is Binance a fair stand-in for Delta India?** Yes for prices.
- Delta's BTCUSD perp tracks Binance BTCUSDT with a 1-hour return correlation of 0.987 (ETH 0.994).
- The median gap is 1-2 bp, and the 1-hour returns differ by 1.4-2.1 bp on average.
- Delta's funding is a bit higher: 0.009% vs 0.007% per 8h for BTC. Using Binance funding is therefore slightly
  kind to longs.

## 2. Indian costs and rules (constraint, not advice)

| item | value used | source / certainty |
|---|---|---|
| Futures fee, Delta India | taker 0.05%, maker 0.02% of notional, + 18% GST on the fee | Delta API (`taker_commission_rate` 0.0005, `maker` 0.0002); GST stated on delta.exchange/fees. **Certain** |
| Option fee, Delta India | 0.01% of underlying notional, capped at 3.5% of premium, + 18% GST | Delta API (`premium_commission_rate` 0.035). **Certain** for live contracts |
| Contract sizes | BTCUSD 0.001 BTC (Rs ~7,970 today); ETHUSD 0.01 ETH (Rs ~2,440); options the same per contract | Delta API |
| Bid/ask, perps | BTC 0.06 bp, ETH 0.4 bp; 1,000-3,800 contracts at the top of the book | one L2 snapshot |
| Bid/ask, ATM options | 1-day 2.0-2.8% of premium; weekly 1.1-2.0%; monthly 0.8-1.1% | chain snapshot |
| Funding | paid by longs when positive, every 8h; Binance BTC averaged 3-31%/yr (by year), Delta slightly higher | Binance archive, Delta API |
| **Tax on crypto (VDA) gains** | **30% + 4% cess = 31.2%** on each gain; **no set-off of losses**, no carry-forward; only cost deductible | Sec 115BBH; carried into the Income-tax Act 2025 from FY 2026-27 per commentary (taxguru, incorpx). **Certain for spot crypto** |
| **1% TDS** | 1% of sale value on VDA transfers (Sec 194S); a cash-flow item, refundable against the final tax | **Certain for spot**. **Uncertain for derivatives** |
| **Delta India F&O** | Delta says its INR-settled futures/options are **not** VDA transfers: no 1% TDS, and profit is business income at slab rates with set-off | Delta marketing and support replies; KoinX, BDO and tax2win say there is **no CBDT ruling**; CoinSwitch/Mudrex guides argue that 115BBH applies. **Open question: ask a CA** |

So every result is shown under two regimes:
- (i) **VDA**: 31.2% on each profitable trade, losses ignored. This is the strict reading.
- (ii) **business income**: 31.2% (top slab) on the year's net profit, losses set off.

TDS is ignored as a cost because it is a prepayment.

### What a strategy must make gross under a 31.2% tax with no loss set-off

| win rate | reward:risk | gross expectancy (R) | net, VDA rule (R) | net, business rule (R) |
|---|---|---|---|---|
| 50% | 1:1 | 0.00 | **-0.156** | 0.00 |
| 55% | 1:1 | +0.10 | **-0.072** | +0.069 |
| 65% | 1:1 | +0.30 | +0.097 | +0.206 |
| 45% | 2:1 | +0.35 | +0.069 | +0.241 |
| 35% | 3:1 | +0.40 | +0.072 | +0.275 |

- Break-even win rate under the VDA rule is 1 / (1 + 0.688 × RR):
  - **59.2% at 1:1**;
  - 42.1% at 2:1;
  - 32.6% at 3:1.
- Under the VDA rule a strategy that only breaks even before tax loses about 15% of R per trade.
- A strategy that loses overall still pays tax on its winners. This happened in the holdout: Rs 1,771 of tax on a
  Rs 6,540 loss.

## 3. Market facts (2020-2026, Binance perps, IST clock)

**When the moves happen.**
- 19:00-21:00 IST is the most active slot (US cash open and US data):
  - BTC's mean absolute hourly move there is 0.51-0.54%, against 0.30% at 09:00-11:00 IST;
  - those three hours carry 18% of all variance (BTC) and 22% of dollar volume.
- 08:00 IST is the Asia/Europe handover. It is the main secondary peak (5.7% of variance, up to 13% in one year).
- The quietest hours are 09:00-13:00 IST, the Indian market hours.
- No hour has a reliable drift. The largest is |t| = 2.7 among 48 tests, which is what chance gives.

**Weekdays and weekends.**
- Sat/Sun hourly moves are about 30% smaller: BTC 0.29% vs 0.41-0.43% on weekdays.
- Median weekend daily range: BTC 1.7-2.2% vs 3.4-4.3% on weekdays (2023-26).

**Daily range (IST day).**

| year | BTC median range | BTC days > 5% | ETH median range | ETH days > 5% |
|---|---|---|---|---|
| 2020 | 4.2% | 38% | 5.8% | 62% |
| 2021 | 6.2% | 65% | 7.6% | 81% |
| 2022 | 4.3% | 39% | 6.1% | 63% |
| 2023 | 2.9% | 17% | 3.4% | 24% |
| 2024 | 3.7% | 28% | 4.5% | 40% |
| 2025 | 3.0% | 18% | 5.2% | 55% |
| 2026 (to 7 Oct) | 2.9% | 18% | 3.9% | 35% |

**Trend or mean reversion?**
- Variance ratios on 5-min returns are below 1 from 15 minutes to 4 hours in almost every year:
  - BTC 1h 0.81-0.94;
  - BTC 4h 0.80-0.92.
  - So there is mild mean reversion at intraday horizons.
- At 1 day and 1 week the ratios swing either side of 1 from year to year. There is no stable trend at those horizons.
- Autocorrelation of 15-min and 1-hour returns is -0.04 to +0.02, which is tiny.

**Funding extremes (Binance, 8h, 2020-26).**
- After very negative funding (< -1 bp per 8h, 159 cases), BTC rose 1.6% on average over 24h (66% up, t ≈ 2.4).
- After very positive funding (> 5 bp, 329 cases), nothing followed (-0.1%, t -0.3).
- ETH shows no effect.
- So one bucket out of 14 is borderline. It is a lead to test with costs, not a rule.
- Funding is now low: 0.3 bp per 8h average in 2026, against 2.8 bp in 2021.

**Open interest extremes (24h OI change, top/bottom 10%, with the price direction).** None is significant: every
|t| < 1.6 after allowing for overlap. "OI up and price down" was followed by -0.3% (BTC) and -1.5% (ETH) over 24h,
on 164-243 hours.

**US CPI and FOMC (h28 calendar, 2020-2026).**

| | events | median move in the first hour | normal hour, same clock time | ratio | first move carries on? |
|---|---|---|---|---|---|
| FOMC (BTC) | 53 | 0.90% | 0.24% | **3.8×** | 15 min vs 1-4 h: same sign 58%; 1 h vs 4-24 h: 55% |
| CPI (BTC) | 79 | 0.60% | 0.25% | 2.4× | 46% / 43% |
| FOMC (ETH) | 53 | 0.84% | 0.35% | 2.4× | 51% / 55% |
| CPI (ETH) | 79 | 0.96% | 0.35% | 2.8× | 49% / 46% |

- Big moves, no direction.
- BTC drifted up in the 24h before FOMC (+0.8%, 64% of meetings), but with n = 53 that is untested.

**Implied vs realised volatility: are options cheap or dear?** This compares DVOL (Deribit's 30-day implied vol) with
the realised vol of the next 30 days.

| year | BTC DVOL | BTC realised next 30d | IV > RV, share of days | ETH DVOL | ETH realised | IV > RV share |
|---|---|---|---|---|---|---|
| 2021 | 91.8 | 83.7 | 84% | 109.4 | 111.2 | 73% |
| 2022 | 73.7 | 63.1 | 70% | 91.8 | 83.9 | 63% |
| 2023 | 49.5 | 45.3 | 65% | 51.7 | 49.2 | 64% |
| 2024 | 57.7 | 52.4 | 77% | 65.7 | 63.7 | 65% |
| 2025 | 46.0 | 43.8 | 68% | 69.3 | 71.4 | 46% |
| 2026 | 43.5 | 43.9 | 56% | 60.1 | 58.3 | 62% |

- **BTC options have been dear**: on average +5 vol points above what followed. In 2026 the gap is about zero.
- ETH has been close to fair.
- Real option buys (Deribit trade sample, bought at the traded price, held to expiry) agree:
  - ATM BTC ≤1-day options returned -2.9% on average before tax and **-21% after a 31.2% tax on the winners**;
  - only 30-33% of ATM buys finish in profit.
  - After tax, almost every tenor and moneyness bucket loses.
  - Far OTM options lose 60-100%.
  - Taker effective half-spread is a median 0.25% of premium for ATM, 3% for OTM > 1 sd, and 14-18% for far OTM.

**What Rs 1,00,000 holds on Delta India today** (BTC $82,300, ETH $2,518, USD/INR 96.78):

| item | cost each | units for Rs 1 lakh | round-trip fee each | spread each |
|---|---|---|---|---|
| BTC perp, 1 contract (0.001 BTC) | Rs 7,966 notional | 12.5 at 1x (63 at 5x) | Rs 9.40 taker | ~Rs 0.05 |
| ETH perp, 1 contract (0.01 ETH) | Rs 2,437 notional | 41 at 1x | Rs 2.88 taker | ~Rs 0.1 |
| BTC ATM call, expiring next day, at ask | Rs 48 | ~2,090 | Rs 1.88 | Rs 1.16 (2.4%) |
| BTC ATM call, 2-day | Rs 72 | ~1,390 | Rs 1.88 | Rs 1.45 (2.0%) |
| ETH ATM call, next day | Rs 21 | ~4,760 | Rs 0.58 | Rs 0.58 (2.8%) |

Delta lets a buyer start at about Rs 50 a trade, so size is not the problem. The problem is edge per rupee.

## 4. The neural network

**Pre-registered** in `scratchpad/hunt/nn_crypto/PREREG.txt` before any result was seen.
- The holdout was locked to 2026-04-01..2026-10-07 and opened once.
- The walk-forward is anchored, with test blocks 2022, 2023, 2024, 2025 and 2026Q1.
- It is purged and embargoed: 1 day plus the longest label.
- Early stopping and the trade thresholds use only the last 15% of each training window.

**Inputs.** Every 15-minute bar close, BTC and ETH pooled, 473k rows.
- **Sequence**: the last 64 bars (16 h) of 8 channels. These are BTC and ETH return, range, volume and taker-buy share,
  each scaled by 7-day volatility.
- **Static (37)**:
  - returns over 1h/4h/1d/3d/7d;
  - realised vol 1d/7d and their ratio;
  - distance from the 24h high and low;
  - funding (last, 3-period mean, z-score);
  - OI change 1h/4h/24h, top-trader long/short ratio and taker ratio;
  - DVOL, its 24h change and DVOL minus realised vol;
  - IST hour and weekday (sin/cos), weekend;
  - time to and since the next/last CPI or FOMC, and a ±2h flag;
  - the other coin's move minus this coin's (1h/4h/1d).

**Network.**
- Three dilated 1-D conv layers (16 filters, receptive field 29 bars), pooled as mean plus last.
- A static branch of 32 units.
- A 64-unit head with dropout 0.2.
- Six outputs: up/down for 15/60/240 min, and the size |return| / vol.
- Training: AdamW, weight decay 1e-4, at most 12 epochs, early stop with patience 2, 3 seeds averaged. 9,014
  weights; 30-60 s per fold on 3 CPU threads (torch 2.14 CPU).
- Baselines on the same features (static + the last 4 bars):
  - logistic regression (C = 0.05);
  - HistGradientBoosting (200 trees, min leaf 500);
  - uniform random scores.

**A leak was caught on dev and fixed before the holdout.**
- Binance changed the timestamp of its 5-minute OI/taker files on **2024-03-04**: from the end of the window to the
  start.
- The first dev run therefore saw 5 minutes of future taker flow from 2024 on. The taker ratio alone reached AUC 0.70
  for 15 minutes in 2025.
- Stamps after the switch are now shifted +5 min (`check_metrics_switch.py`).
- The leaky run is kept as `logs/model_dev_LEAKY.log` and was not used.
- After the fix, no single feature has an AUC beyond 0.47-0.54 (`check_feature_auc.py`). The strongest are the last
  hour's return (contrarian) and the distance from the 24h high, i.e. mean reversion.

### Direction AUC by year (walk-forward test blocks; holdout = locked, run once)

| model | coin | horizon | 2022 | 2023 | 2024 | 2025 | 2026Q1 | **holdout** |
|---|---|---|---|---|---|---|---|---|
| NN | BTC | 15 m | .543 | .568 | .551 | .538 | .548 | **.534** |
| NN | BTC | 60 m | .541 | .563 | .548 | .533 | .539 | **.536** |
| NN | BTC | 240 m | .527 | .555 | .547 | .542 | .522 | **.519** |
| NN | ETH | 15 m | .544 | .566 | .548 | .547 | .546 | **.549** |
| NN | ETH | 60 m | .539 | .560 | .549 | .537 | .545 | **.542** |
| NN | ETH | 240 m | .518 | .548 | .540 | .540 | .521 | **.527** |
| boosting | BTC | 60 m | .543 | .559 | .547 | .535 | .527 | .533 |
| logistic | BTC | 60 m | .532 | .551 | .533 | .523 | .529 | .528 |
| random | BTC | 60 m | .494 | .499 | .497 | .501 | .502 | .504 |

**What the AUCs mean.**
- Accuracy is 52-55%.
- Calibration is good: expected calibration error 0.01-0.04 for the NN, against 0.25 for random.
- At the top/bottom 5% of scores the NN calls the direction right 56-60% of the time (60-min holdout: BTC 57%, ETH 56%).
- **Size** is forecast well:
  - the rank correlation of predicted with actual |move| is 0.30-0.38;
  - realised vol alone gives 0.26-0.33.
  - The size head is the most reusable part.

### Trade translation (pre-registered)

The rule:
- Take the top/bottom 2% or 5% of scores, with thresholds set on the training window.
- Hold exactly 60 or 240 minutes, one position per coin at a time, 1 contract.
- Futures: Delta taker fee + GST + half the measured spread + funding.
- Options: buy an ATM call or put, nearest expiry at least 1 day out. Prices use Black-Scholes at DVOL × today's Delta
  ask/bid IV ratio (BTC 0.85/0.83, ETH 0.81/0.79), with Delta's option fee.
- Rupees are per 1 contract, at each trade's own price.

| | trades | gross Rs | net Rs (fees, spread, funding) | after VDA tax | after business tax | net Rs/trade | random entries, Rs/trade after VDA tax | p vs random |
|---|---|---|---|---|---|---|---|---|
| **Holdout, NN BTC 60 m futures, 5%** | 1,094 | +2,475 | **-6,540** | -8,311 | -6,540 | -5.98 | -9.88 | 0.001 (BH 0.03) |
| Holdout, NN BTC 60 m options, 5% | 1,094 | +1,093 | -1,936 | -3,014 | -1,936 | -1.77 | -3.77 | 0.001 (BH 0.03) |
| Holdout, NN ETH 60 m futures, 5% | 1,105 | +61 | -2,729 | -3,462 | -2,729 | -2.47 | -3.24 | 0.35 |
| Holdout, NN BTC 240 m futures, 5% | 387 | +891 | -2,287 | -4,050 | -2,287 | -5.91 | -12.76 | 0.16 |
| Holdout, best of all 64 (LR BTC 240 m options, 2%) | 167 | +779 | **+323** | -302 | +222 | +1.93 | -3.61 | 0.19 (BH 0.71) |
| Walk-forward, NN BTC 60 m futures, 5% (2022-26Q1) | 8,244 | +8,168 | -49,672 | -67,421 | -49,672 | -6.03 | -9.03 | 0.004 (BH 0.03) |

- Walk-forward NN BTC 60-min futures (5%) net by year: -6.1k, -6.3k, -9.1k, -25.1k and -3.0k. Random entries:
  -9.2k, -9.2k, -24.0k, -27.0k and -5.2k.
- Pre-registered success needed all three of these:
  - holdout AUC ≥ 0.52 on both coins: **met**;
  - profit after fees, spread and tax: **not met**;
  - beating random after BH correction: **met for 2 of 64 variants, both losing**.
- **Verdict: FAIL.**

**Why it fails, in one line.** The NN's best trades move about 3 bp in its favour on average. A taker round trip costs
about 12 bp. Even maker fees (about 5 bp round trip) would not close the gap, and resting orders get filled more often
when the price is about to move against them.

## 5. What to study next

1. **Use the size forecast, not the direction.** The NN predicts the size of the next hour's move better than recent
   volatility does (rank correlation 0.35 vs 0.30). Test it against option prices: buy straddles when the predicted
   move beats the implied move, using Delta's real 1-day ATM quotes. **This needs a live record of Delta's option chain
   (see 3).**
2. **Lower costs before signals.** Re-run the trade rule as maker-only on the perps (0.02% + GST) with a fill model:
   fill only if the price trades through the limit; check how often fills come right before an adverse move. Even then
   the gross edge (~3 bp) is below the maker round trip (~5 bp), so expect NO unless the edge is pooled with other
   signals.
3. **Record Delta India live.** Snapshot the BTC/ETH option chain (bid/ask/IV) and the perp book every 5 minutes. Delta
   does not serve candles for expired options, so this is the only way to test option strategies on Delta's real
   spreads. The fetchers are ready: `fetch_delta.py chain`, run from cron.
4. **Weekend vs weekday, and the 19:00-23:00 IST window.** The model could be trained only on the active US hours. The
   moves are 70% larger there, while fees are a fixed share of notional.
5. **Very negative funding** (< -1 bp per 8h) was followed by +1.6% over 24h in BTC (t ≈ 2.4, 159 cases). It is worth
   one pre-registered test, with funding costs and the 2025-26 data as holdout. It was not traded here.
6. **Ask a CA about the tax rule for Delta India F&O.** Under the business-income reading the arithmetic is much kinder
   (break-even 50% at 1:1, not 59%). No strategy here was positive even before tax, so this does not change today's
   verdict.

## 6. Files and how to re-run

Code: `research/hunt/nn_crypto/`. Run each with `python3 -I`.
- `common.py`: paths, polite HTTP getter.
- `fetch_binance.py klines funding metrics`
- `fetch_deribit.py dvol delivery chain trades`
- `fetch_delta.py specs chain candles`
- `features.py`: builds `data/feat_15m.parquet`.
- `facts.py [hours range persist funding oi events vol options delta money tax basis]`: market facts. Writes
  `logs/facts_*.csv`.
- `model.py dev` and `model.py holdout`. The holdout refuses to run twice (`models/HOLDOUT_DONE`).
- `evaluate.py dev|hold`: trade translation and random baseline.
- Checks: `check_feature_auc.py`, `check_metrics_window.py`, `check_metrics_switch.py`, `inventory.py`.

Outputs live in `scratchpad/hunt/nn_crypto/`:
- `data/` (parquet);
- `PREREG.txt` (with the leak addendum);
- `models/`:
  - `nn_holdout_seed{0,1,2}.pt`: the CNN trained through 2026-03-31;
  - `scaler_holdout.pkl`;
  - `lr_holdout.pkl`, `hgb_holdout.pkl`;
  - `frozen.json`: the feature spec, window, horizons, seeds and cost settings;
- `preds/`: dev and holdout scores, and every simulated trade;
- `logs/`: `model_dev.log`, `model_holdout.log`, `dev_metrics.csv`, `holdout_metrics.csv`, `trades_dev.csv`,
  `trades_hold.csv`, `facts_*.csv` and the fetch logs.

Notes:
- `pip install torch` (CPU, 773 MB) also upgraded setuptools 68 → 78 in the system Python.
- Nothing under `android/` was touched. Nothing was committed.
