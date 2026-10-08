# NN-CRUDE: MCX crude oil, deep look and a neural network

Written 8 Oct 2026 by hunter NN-CRUDE. Base for a follow-up study.
- Code: `research/hunt/nn_crude/` (pre-registration `PREREG.md`).
- Data, models, logs: `scratchpad/hunt/nn_crude/{data,models,logs}` (179 MB, zstd parquet).
- Nothing committed. `android/` untouched.

## Verdict (read this first)

**The neural network cannot call the direction of MCX crude 15, 30 or 60 minutes ahead. Nor can the baselines.**

- On the locked holdout (1 Apr - 8 Oct 2026, opened once), every model scored **AUC 0.48-0.51**. A random score
  scored 0.49-0.50. Accuracy was 49-51%.
- Every trade translation **lost money after costs** on the holdout: futures and ATM options, all 3 horizons, all
  5 models. The one exception (logistic, 60 min, options, +Rs 11k on 57 trades) is noise: p = 0.52 against random
  entries.
- The development period looked good for options (+Rs 57k to +Rs 187k per lot). **All of it came from one month,
  March 2026**, when crude jumped from about Rs 6,100 to Rs 9,600. Every other development month lost.
- **The size of the next move IS predictable.** "Will the next 15-60 minutes move more than usual?" scores AUC
  0.63-0.69 in development and **0.57-0.58 on the holdout**. That is the one real signal. Direction is not.
- **MCX crude is just NYMEX WTI times USD/INR.** Price ratio 1.001 (median), 5-minute return correlation 0.94, no
  lead or lag. A model of MCX crude is a model of WTI.

Costs are not the problem for crude options. One CRUDEOIL ATM option costs about **Rs 148 a round trip** (0.44% of
premium). The problem is that there is no directional edge to pay for.

## Data obtained (and refused)

Dhan token used before it expires (2026-10-09 04:57 UTC). Every Dhan call worked; nothing was refused for auth.

| source | what | result |
|---|---|---|
| Dhan `/charts/historical`, MCX_COMM, live id + expiryCode 0 | continuous near-month DAILY with OI | **CRUDEOIL 2 Jan 2012 - 7 Oct 2026 (3,634 days); CRUDEOILM 2 Feb 2015 - (2,074)**. Before 2012: DH-907. expiryCode 1 returns the same series |
| Dhan `/charts/intraday`, 1-min, live futures | OHLC + volume + OI | Only while the contract is listed: CRUDEOIL Oct from 19 Jun 2026 (58k bars), Nov from 20 Jul, Dec (28k); CRUDEOILM Oct from 22 May 2026 (75k), Nov (56k). Expired contracts and "continuous" requests: 0 bars. Option ids: 0 bars |
| Dhan `/charts/rollingoption`, MCX | **expired MCX options, 1-min** | **Works** with underlying id 294 (CRUDEOIL) / 556 (CRUDEOILM), instrument OPTFUT, MONTH. From **5 Aug 2025** only (none before). **ATM±3 strikes only** (ATM±4 and wider: empty). expiryCode 1 (near) and 2 (next month). Fields: OHLC, IV, volume, OI, strike, **spot**. 3.58M rows near, 1.49M next, 0.51M CRUDEOILM ATM |
| same, `spot` field | **near-month futures LTP every minute** | Matches the live contract to the rupee after each roll. This is the MCX minute history used for the model: 303 days, 257k minutes |
| Dhan option chain (`/optionchain`, MCX_COMM) | bid/ask/OI/IV/greeks, 3 expiries | Works. 3 snapshots (8 Oct, 20:39-21:22 IST) + more from a loop that runs until about 22:40 IST. Note: its `last_price` of the underlying is the previous settlement, not live |
| Dhan `/marketfeed/quote` | futures depth (5 levels) | Works. 7+ snapshots, 10 minutes apart |
| Dhan `/margincalculator` | margin per lot | Works (table below) |
| Dhan instrument master | lots, ticks, expiries, price bands | Read from the 6 Oct copy |
| Yahoo chart API (cookie+crumb) | daily since 2000; 1h for 730 days; 5m for 60 days | CL=F daily from 23 Aug 2000, BZ=F from 30 Jul 2007, INR=X from 1 Dec 2003, DXY, India VIX (2008-), VIX, NG, gold, S&P 500, 10y, USO. 1h: CL, BZ, DXY, ES from 16 May 2024; INR from Dec 2023. 5m: last 60 days |
| eia.gov WPSR schedule page | official release-time exceptions 2025-26 | Works. Earlier years use the rule (Wed 10:30 ET; Thu 11:00 ET after a Mon-Wed holiday) |
| EIA API, opec.org | | 403. OPEC meeting dates typed from memory (approximate; used for nothing in the model) |
| zerodha.com/charges; api.kite.trade/margins/commodity | charges and margins | Works; rates verified today |
| CPU deep-learning library (torch) | | Not installed. A CPU wheel needs about 700 MB installed on a shared disk with 2 GB free. Used scikit-learn's MLP instead (a 30-step return sequence plus features, flattened) |

**Watch out:** in the Dhan rolling data, option volume is about 100 times larger before Dec 2025 than after (a unit
change). Use it only as a ratio to its own recent average.

## Market facts

### Contract

| item | CRUDEOIL | CRUDEOILM |
|---|---|---|
| lot | 100 barrels | 10 barrels |
| futures tick | Re 1 / bbl = Rs 100 a lot | Re 1 / bbl = Rs 10 a lot |
| option tick | Rs 0.10 | Rs 0.05 |
| option strike step | Rs 50 | Rs 50 |
| expiries now | options 15 Oct, 17 Nov, 16 Dec 2026; futures 19 Oct, 19 Nov, 18 Dec 2026 (and out to Mar 2028) | same |
| rule | options expire 2 business days before the future (checked on every month since Aug 2025) | |
| margin, 1 futures lot (Dhan calculator, 8 Oct) | **Rs 2,67,850** (about 30%) | **Rs 26,785** |
| margin, 1 futures lot (Zerodha, 8 Oct) | **Rs 3,14,061** (35.6%) | **Rs 31,499** |

Price on 8 Oct 2026: about **Rs 9,000 / bbl** (WTI about $100). Margins are high now because crude is volatile.

**What Rs 1 lakh can hold:**
- CRUDEOIL futures: **none** (one lot needs Rs 2.7-3.1 lakh).
- CRUDEOILM futures: up to 3 lots at Zerodha's margin; 1 lot is the prudent size (a 3% day = Rs 2,700 a lot).
- CRUDEOIL ATM option (100 bbl): **1 lot**. The premium is Rs 5k (expiry day) to Rs 35k (a month out), median
  Rs 33.5k since Sep 2026.
- CRUDEOILM options: possible, but the Rs 40 brokerage makes them cost 1.7% of premium a round trip.

### Session and when the moves happen

- Session **09:00 - 23:30 IST** (US summer time) or **23:55** (US winter time).
- **US hours carry the action.** Mean absolute 1-hour move: 0.23% at 10:00 IST, **0.57-0.60% at 19:00-21:00 IST**.
- But in 2025-26 the Indian afternoon also moved a lot (crisis news came at any hour). Of the intraday variance,
  50% came from 09:00-18:00 and 50% from 18:00-close.
- Futures volume: 44% of it trades 19:00-23:59. Option volume: 84% after 17:00.
- The day's high or low is most often set in the first hour (11-12% of days) or the last (9-12%).

| IST hour | 9 | 11 | 13 | 15 | 17 | 18 | 19 | 20 | 21 | 22 | 23 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| share of variance % | 4.7 | 4.3 | 5.7 | 6.0 | 7.5 | 9.2 | 11.0 | 10.4 | 8.0 | 8.1 | 3.4 |
| mean abs 1h move % | 0.30 | 0.33 | 0.46 | 0.45 | 0.47 | 0.51 | 0.57 | 0.60 | 0.56 | 0.52 | 0.30 |
| futures volume % | 4.8 | 3.9 | 5.1 | 6.2 | 8.1 | 8.3 | 8.9 | 10.3 | 11.5 | 9.1 | 4.3 |

### Daily range, gaps, trend (Dhan continuous daily, 2012-2026)

| year | mean abs day % | mean range % | mean abs gap % | annual vol % |
|---|---|---|---|---|
| 2012-14 | 0.9-1.0 | 1.6-1.9 | 0.2 | 20-22 |
| 2015-16 | 2.0-2.1 | 3.5-3.6 | 0.5-0.6 | 41-45 |
| 2017-19 | 1.2-1.6 | 2.2-2.8 | 0.4-0.7 | 26-36 |
| 2020 (Apr-May 2020 negative prices dropped) | 2.6 | 4.3 | 1.0 | 72 |
| 2021-25 | 1.4-2.3 | 2.4-4.1 | 0.5-0.8 | 28-49 |
| **2026** | **3.0** | **4.9** | **1.2** | **65** |

- From minutes: the daily range was 1.7-3.0% (**Rs 9-17k per 100-bbl lot**) from Aug 2025 to Feb 2026,
  **7.8% (Rs 66k a lot) in Mar 2026**, and 3.5-6.5% (Rs 27-61k a lot) from Apr to Oct 2026.
- **No trend persistence.** Daily return autocorrelation is within ±0.05 at lags 1-5. Tomorrow has the same sign as
  today 48% of the time. 5-day variance ratio 0.92-0.98.
- **No intraday persistence either.** Consecutive 5/15/30/60-minute returns: autocorrelation -0.05 to +0.03. The
  09:00-17:30 move vs the 17:30-close move: correlation +0.02, same sign 50% of the time.
- The gap is 16% of the close-to-close variance (2020-26). Rolls are excluded: the near future switches on the 3rd
  trading day after option expiry, and in Sep-Oct 2026 the next month was Rs 60-870 cheaper (steep backwardation).

### Around scheduled releases (Aug 2025 - Oct 2026)

| window | EIA (62 releases, 20:00/21:00 IST) | US CPI (13, 18:00/19:00 IST) |
|---|---|---|
| release minute, abs 1-min move vs normal | **1.7x** | |
| 0-15 min after | 0.86x | 1.17x |
| 0-60 min after | 0.89x | 0.92x |

- The EIA minute itself jumps (1.7x a normal minute; about 1.2x for the next 15 minutes). Over an hour it is no
  bigger than a normal evening in this crisis-dominated period.
- The first 5 minutes after EIA do not predict the next 55 (correlation +0.13, same sign 52%, n = 61).

### MCX crude vs WTI x USD/INR

| check | result |
|---|---|
| price ratio MCX / (CL=F x INR=X), daily, by year 2012-2026 | median 1.000-1.005; 90% band 0.98-1.02 (to 1.05 in 2020) |
| daily return correlation | 0.87 (2012-19), 0.91 (2020-26), **0.96 (2025-26)**; beta 0.8-0.94 (calendar-date mismatch) |
| 5-minute return correlation (Jul-Oct 2026) | **0.94**; lag ±1-2 bars: -0.02 to +0.02 |
| 1-hour return correlation (Aug 2025 - Oct 2026) | 0.93; lag ±1: -0.07 / 0.00 |

So any long WTI minute history (CME) is a near-perfect stand-in for MCX crude before Aug 2025.

### Options: liquidity and spreads

- **Only the near month trades.** Next-month options had no trade in 80-93% of minutes and OI near 0.
- Near-month ATM option: median about 100 contracts a minute; a few minutes with no trade (2-3%).
- ATM IV: median 46-56% in 2025-26. ATM straddle = 9.8% of the price 20-40 days out, 3.1% at 1-2 days, 1.7% on
  expiry day.

| bucket (8 Oct snapshots, Oct expiry) | CRUDEOIL spread | CRUDEOILM spread |
|---|---|---|
| ATM / 1 ITM | **Rs 0.50 (0.19%)** | Rs 0.55 (0.21%) |
| 1-2 OTM | Rs 0.60 (0.28%) | Rs 0.50 (0.23%) |
| 3-6 OTM | Rs 0.55 (0.33%) | Rs 0.55 (0.32%) |
| > 6 OTM (cheap) | Rs 0.40 (2.2%) | Rs 0.25 (0.8%) |
| Nov expiry, ATM | Rs 191 (22%): no real market | Rs 717 (98%) |

Futures top of book (7 snapshots): CRUDEOIL and CRUDEOILM near month **Rs 2 / bbl**; next month Rs 4; third
month Rs 11.

### Costs per round trip, 1 lot, Zerodha (rates checked on zerodha.com today)

Rates: futures brokerage 0.03% or Rs 20 an order (lower), CTT 0.01% on the sell side, MCX fee 0.0021%, stamp 0.002%
on the buy. Options: Rs 20 an order, CTT 0.05% of sell premium, MCX fee 0.0418% of premium, stamp 0.003%. SEBI
Rs 10/crore. GST 18% on brokerage + fees + SEBI.

| at price Rs 9,013, ATM premium Rs 335 | charges | spread | total | % of notional / premium | break-even move |
|---|---|---|---|---|---|
| CRUDEOIL futures (100 bbl) | Rs 202 | Rs 200 | **Rs 402** | 0.045% | Rs 4.0 / bbl |
| CRUDEOILM futures (10 bbl) | Rs 63 | Rs 20 | **Rs 83** | 0.092% | Rs 8.3 / bbl |
| CRUDEOIL ATM option (100 bbl) | Rs 98 | Rs 50 | **Rs 148** | 0.44% | Rs 1.5 / bbl of premium |
| CRUDEOILM ATM option (10 bbl) | Rs 52 | Rs 6 | **Rs 58** | 1.7% | Rs 5.8 / bbl of premium |

- That is cheaper than the BANKNIFTY options in our earlier studies (X3: about Rs 146 of charges and spread a trade).
- CRUDEOILM futures pay 0.09% a round trip. A typical 30-minute move is 0.25% (Rs 251 a lot), so a trader must be
  right well over half the time.

## The model

Pre-registered in `research/hunt/nn_crude/PREREG.md` before any fit.

- **Target:** up or down over the next 15, 30 or 60 minutes. Decision every 5 minutes, 09:30-23:00 IST. Also a
  "size" target (move larger than the median).
- **Inputs (71):**
  - the last 30 one-minute returns (scaled by recent volatility);
  - returns over 5-480 minutes, the move since the open, the gap;
  - position in the day's range and distance to the day's high and low;
  - short/long volatility ratios;
  - time of day and day of week;
  - EIA / CPI / FOMC flags and minutes to/since EIA;
  - last completed hour of USD/INR, DXY, S&P futures and Brent-minus-WTI;
  - ATM IV level and change, straddle size, days to expiry;
  - option volume burst, call/put volume imbalance, call/put OI change.
- **Network:** scikit-learn MLP, 1 or 2 hidden layers (32 or 64-32), L2 penalty, early stopping, 5-seed average.
  Hyper-parameters chosen by walk-forward AUC on development data only.
- **Proxy pre-training:** the same net (23 shared inputs) trained first on hourly WTI x USD/INR (May 2024 - Mar
  2026), then continued on MCX ("mlp_pre").
- **Baselines:** logistic regression, gradient boosting, the 23-input MLP without pre-training, a random score,
  and always-flat.
- **Validation:** anchored walk-forward by month on Aug 2025 - Mar 2026 (6 test months), with a 1-day embargo and
  label-overlap purge.
- **Locked holdout:** 1 Apr - 8 Oct 2026, scored once (`models/HOLDOUT_OPENED` records when).
- **30 variants** in all (10 per horizon).

### Results: direction (AUC; 0.50 = coin)

| horizon | model | dev walk-forward AUC | **holdout AUC** | holdout accuracy | holdout Brier (0.25 = coin) |
|---|---|---|---|---|---|
| 15 min | MLP (chosen: 32 units, alpha 0.1) | 0.518 | **0.503** | 49.9% | 0.258 |
| 15 min | logistic | 0.525 | 0.507 | 50.7% | 0.252 |
| 15 min | gradient boosting | 0.514 | 0.504 | 50.6% | 0.251 |
| 15 min | MLP pre-trained on WTI proxy | 0.510 | 0.490 | 49.2% | 0.258 |
| 30 min | **MLP (pre-registered primary)** (64-32, alpha 0.01) | 0.512 | **0.499** | 49.9% | 0.278 |
| 30 min | logistic | 0.523 | 0.498 | 50.2% | 0.255 |
| 30 min | gradient boosting | 0.514 | 0.496 | 50.1% | 0.255 |
| 30 min | MLP pre-trained on WTI proxy | 0.508 | 0.480 | 48.6% | 0.264 |
| 60 min | MLP (32, alpha 0.01) | 0.514 | **0.491** | 49.8% | 0.287 |
| 60 min | logistic | 0.519 | 0.497 | 50.8% | 0.255 |
| 60 min | gradient boosting | 0.498 | 0.494 | 50.3% | 0.257 |
| 60 min | MLP pre-trained on WTI proxy | 0.511 | 0.484 | 49.4% | 0.272 |
| any | random score | 0.50 | 0.494-0.498 | | |

- Holdout AUC by month stayed between 0.45 and 0.53 for every model. No month stood out.
- **The MLP is over-confident.** Its Brier score (0.26-0.29) is worse than always saying 50% (0.25). In its top
  decile the 60-min net said "86% up"; 58% went up in development.
- Measuring the move from the next minute (to remove last-trade bounce) changes AUC by under 0.005.
- **Size target (will the move be bigger than usual?):** MLP AUC 0.63-0.67 dev, **0.57-0.58 holdout**. A logistic
  size model scored 0.68-0.69 in development. This is volatility clustering. It is real, and it carries no direction.

### Results: the simple trade translation (holdout, per lot, after costs)

Rule (fixed beforehand): score above the development 95th percentile = long (buy CRUDEOILM future, or buy the
CRUDEOIL ATM call); below the 5th = short (sell the future, or buy the ATM put). Exit after h minutes. One position
at a time, at most 6 trades a day. 135 holdout sessions.

| horizon / model | trades | CRUDEOILM fut net | CRUDEOIL ATM option net | option p vs random entries | option p vs random side at the same moments |
|---|---|---|---|---|---|
| 15 / MLP | 732 | -Rs 55,128 | -Rs 1,06,436 | 0.37 | 0.47 |
| 15 / logistic | 436 | -51,267 | -1,53,680 | 0.96 | 0.99 |
| **30 / MLP (primary)** | 596 | **-45,891** | **-84,999** | 0.53 | 0.53 |
| 30 / logistic | 312 | -31,490 | -73,417 | 0.69 | 0.79 |
| 30 / MLP pre-trained | 127 | -22,105 | -89,458 | 0.98 | 0.995 |
| 60 / MLP | 320 | -49,844 | -62,141 | 0.78 | 0.95 |
| 60 / logistic | 57 | -3,622 | +11,436 | 0.52 | 0.42 |
| 60 / gradient boosting | 31 | -13,142 | -47,770 | 0.98 | 0.99 |

- Primary rule (30 min, MLP): Rs -340 a session on futures, Rs -630 on options, gross about zero (+Rs 5 and +Rs 25
  a trade). Option drawdown -Rs 97.6k on 1 lot. Every holdout month lost on futures.
- Random entries (same days and count, random time and side, same exits and costs) lose about the same.
- With a 3x wider option spread the option losses grow by 50-80%.

### Why development looked good (the trap)

| development (Oct 2025 - Mar 2026), options net | Oct | Nov | Dec | Jan | Feb | **Mar** | total |
|---|---|---|---|---|---|---|---|
| 30 / MLP | -1.2k | -2.7k | -8.2k | -10.8k | -5.3k | **+85.5k** | +57.4k |
| 30 / MLP pre-trained | -8.5k | +2.6k | -1.2k | -9.7k | -10.4k | **+189.6k** | +162.4k |
| 60 / MLP | -8.9k | -5.3k | -1.2k | +0.9k | +3.4k | **+197.8k** | +186.7k |

- Futures lost in development too (-Rs 5k to -46k). The option "profit" was long volatility in a crash-and-spike
  month, not direction.
- Some development p-values against random entries were 0.001-0.05. With 30 variants and a single month doing all
  the work, they meant nothing. The holdout confirmed it.

### Long-history proxy (per year)

| | AUC (OOS walk-forward) |
|---|---|
| hourly WTI x INR, next 60 min, dev 2025 Q1 - 2026 Q1 | MLP 0.506, logistic 0.503, boosting 0.501 |
| same, holdout 2026 Q2-Q4 | MLP 0.465, logistic 0.465, boosting 0.514, random 0.487 |
| daily WTI x INR, next day, 2010-2025 (one test per year) | MLP 0.489 (by year 0.46-0.54), logistic 0.499 (0.44-0.54), boosting 0.519 (0.46-0.58), random 0.516 (0.44-0.61) |
| same, holdout Apr-Oct 2026 | MLP 0.472, logistic 0.530, boosting 0.507, random 0.510 |

A random score swings from 0.44 to 0.61 between single years. So a model that "wins" one year means nothing. No
model beats random across years.

## What to study next

1. **Volatility, not direction.** Move size is predictable (holdout AUC 0.57-0.58), and options are cheap to trade
   (0.44% a round trip). Test buying the ATM straddle when the size model plus the time of day (19:00-22:00 IST, EIA
   minute) say "big move", against what the IV already prices in. This is a test of the volatility risk premium.
   Pre-register it and use the same random-time baseline.
2. **More MCX history.** Dhan serves MCX minutes only from Aug 2025. For more years, buy MCX minute data or CME WTI
   1-minute history (Databento, FirstRate). The 0.94 correlation makes CME data a fair stand-in.
3. **Record live data from now on.** Keep the chain and depth loops running each session (`chain_loop.sh`,
   `quote_loop.sh`, after a token refresh). Spreads are known for one evening only.
4. **Event surprises, not event times.** EIA and OPEC need the consensus forecast to be useful (a draw vs the
   expected draw). That needs a data source we do not have.
5. **Regime split.** Mar-Apr 2026 (crisis) and Aug 2025 - Feb 2026 (calm) behave differently. Any rule must work in
   both.
6. **A real sequence model (1-D CNN/LSTM in torch)** needs about 700 MB of disk. The linear model is no better than
   the MLP here, so do not expect a deeper net to find direction where none shows.

## How to re-run (all from `research/hunt/nn_crude/`)

| step | command | output |
|---|---|---|
| 1 Dhan data (needs a valid token) | `python3 fetch_dhan.py all` (+ `chain_loop.sh`, `quote_loop.sh` during the session; `quote_snap.py` once) | `data/daily_*`, `min_*`, `rollparts/*`, `chain_*`, `quotes`, `margin_dhan` |
| 2 Yahoo | `./fetch_yahoo.sh` then `python3 -I parse_yahoo.py <raw> <data>` | `data/yahoo_*` |
| 3 events | `python3 calendar_events.py` | `data/events.parquet` |
| 4 MCX minute table | `python3 build_mcx.py` | `data/mcx_min.parquet`, `opt_expiries.parquet` |
| 5 market facts | `python3 analyze_daily.py`, `analyze_intraday.py`, `proxy_fit_intraday.py` | `logs/analyze_*.txt`, `proxy_fit_intraday.txt`, `option_liquidity.txt` |
| 6 features | `python3 features.py` | `data/feat_mcx.parquet`, `feat_proxy.parquet`, `models/feature_spec.json` |
| 7 P&L grid | `python3 trades.py` | `data/pnl_grid.parquet` |
| 8 models (dev) | `python3 model.py` (`NN_H=15` etc. to split by horizon; results cached in `data/oos_cache`) | `logs/model_dev.txt`, `models/choice.json` |
| 9 evaluation | `python3 evaluate.py` (dev), then `python3 evaluate.py --holdout` (refuses a second run) | `logs/eval_dev.txt`, `logs/holdout.txt`, `holdout_summary.csv` |
| 10 proxy | `python3 proxy_study.py [--holdout]` | `logs/proxy_study.txt` |

Model files (`scratchpad/hunt/nn_crude/models/`):
- `mlp_h{15,30,60}.pkl`: direction nets trained to 31 Mar 2026, with their feature list and frozen thresholds.
- `mlp_size_h*.pkl`: size nets.
- `feature_spec.json`, `choice.json`, `thresholds.json`.

Load them with `pickle` after `sys.path.insert(0, "research/hunt/nn_crude")`, because the class `MLPEns` lives in
`model.py`.
