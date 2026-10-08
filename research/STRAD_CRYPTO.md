# STRAD-CRYPTO: buy BTC/ETH straddles when a big move is forecast and options look cheap?

Written 8 Oct 2026. Option BUYING only (long ATM straddles). Delta Exchange India contracts, fees and live spreads.
Rs 1,00,000 capital. Both Indian tax readings.
- Plan, written before any P&L: `research/hunt/strad_crypto/PREREG.md`.
- Code: `research/hunt/strad_crypto/`. Logs and tables: `scratchpad/hunt/strad_crypto/logs/`.

## Verdict

**NO. Buying the straddle when the size forecast says "big move" and options look cheap does not pay after costs and
tax.** Nothing passed the pre-registered gate.

1. **Costs kill it.** A 1-day ATM straddle on Delta costs about **6-8% of the premium per round trip**: the spread
   (about 2.5-3% of a 1-day ATM leg) plus a fee of 0.01% of notional per leg per side (+18% GST). Before costs,
   most rules are close to zero. After costs, almost all lose.
   - Buy every hour, sell 1 h later: **-0.5% gross, -7.3% net** per trade (2022 to Mar 2026).
   - Buy every hour, hold 4 h: -1.9% gross, -7.9% net.
   - Buy, hold to expiry (about 2 days): +0.7% gross, -1.8% net. Holding to expiry is the cheapest way, because
     there is no exit spread.
2. **The size forecast works. It just does not make money.** It predicts the size of the next hour's move well
   (rank correlation 0.41-0.43 with the actual |move|, 0.75-0.79 with realised vol). But option prices already
   know most of it. "Big move" hours have expensive options.
   - Size-only rules: -5.8% to -8.9% net per trade. "Cheap-IV-only": -4.5% to -9.0%. Size + cheap: -4.0% to -8.3%.
3. **Only one family is positive before tax: straddles bought just before US CPI and FOMC releases.**
   - CPI/FOMC + "cheap", hold 4 h, +30%/-50% exits (the pre-registered pick): 69 trades, **+3.3% net per trade**.
     Under strict VDA tax that is **-Rs 220** in 4.25 years. The edge came from 2022 (+28% per trade). From 2023 on it
     averaged about 0%.
   - Walk-forward by quarter (re-picking the best rule each quarter on past data only): **-10.4% per trade,
     -Rs 49,400** on Rs 1 lakh over 2023 to Mar 2026. 18% of months were green.
4. **Holdout (Apr to Oct 2026, "partly seen": the NN study had opened it once).** The pick made +3.8% net per trade
   on 14 trades: **+Rs 2,644 net, +Rs 1,157 after strict VDA tax, +Rs 1,819 after business tax**. That is **Rs 6 a day**
   after VDA tax. p = 0.13 that the mean is not above zero. Too few trades to mean anything.
5. **Are BTC/ETH short-dated options cheap or dear?** Slightly dear for BTC, about fair for ETH.
   - Held to expiry (2-day ATM straddle), the payoff was **0.90× the price for BTC** and 0.97× for ETH (2022 to Mar
     2026). In the holdout: 1.00× BTC, 0.95× ETH.
   - Options are priced flat across the day, but the moves are not. At 03:00-11:00 UTC (08:30-16:30 IST) the next
     hour moved only **0.68-0.75×** what the option priced in. At 14:00 UTC (19:30 IST) it moved **1.26×**.
   - So 19:00-21:00 IST really is the "cheap" slot (gross +1.9% to +6.0% a trade). Costs take nearly all of it:
     -3.6% to +0.4% net, and the +0.4% turns into a loss after either tax.
6. **Rs 5,000/day: impossible here.** The best rule trades about 16 times a year (both coins together). At 5% of capital per
   straddle it makes single-digit rupees a day.

**Honest variant count: 280** (14 signal sets × 4 horizons × 5 exits), all pre-registered, all run.
- 141 of 280 are positive gross. 17 are positive net. 5 are positive after strict VDA tax (with compounding):
  all five are the CPI/FOMC + cheap 1 h rule (5 exits), with only 22 trades, below the 40-trade minimum for selection.
  In the holdout that rule had 4 trades and lost 2.9% a trade.
- "Beats random times" is true for most rules (BH q < 0.10 for 203 of 280). That is because random hours include
  the dead Asian-morning hours. Against random hours **at the same clock times**, the US-hours rules are no better
  (p = 0.47-0.50).

## The gate (pre-registered) and what happened

| condition | needed | final rule: CPI/FOMC + cheap, 4 h, +30/-50 | pass? |
|---|---|---|---|
| Net after strict VDA tax, 2022-01..2026-03 | > 0 | -Rs 220 (+Rs 171 at a fixed Rs 5,000 a trade) | **no** |
| BH q vs random entries | < 0.10 | 0.015 | yes |
| Walk-forward by quarter | > 0 | -Rs 49,381 | **no** |
| Holdout net | > 0 | +Rs 2,644 (14 trades) | yes, but tiny |

## What was done

**Data (all public, no keys).** The brief's NN files (`scratchpad/hunt/nn_crypto/`: 1-min perps, sampled trades,
NN predictions) were not on this machine, so I re-downloaded what this study needs:
- BTC-PERPETUAL and ETH-PERPETUAL 5-min candles from Deribit, 2021-03-20 to 2026-10-08 (584k bars each).
- Hourly DVOL and hourly funding (Deribit), same span.
- **Deribit option trades, every hour**: the up-to-1000 most recent trades in the hour before each decision time
  (about 97,000 calls, 0 errors). Kept per expiry: median IV of near-ATM trades and the effective half-spread vs mark.
- Delta India: contract specs and fees from the live API. The live option chain was polled every 10 min from
  16:56 to 18:37 UTC today (22 snapshots, about 3,000 near-ATM quotes).
- CPI and FOMC dates typed from the Fed/BLS calendars. Each was checked against BTC's 5-min move at the release
  minute: a median 7-8× a normal bar. Jobs-report dates were dropped before any P&L (several dates in the old
  `events.csv` are wrong).

**Pricing: Black-76 on Deribit trade IV, not direct trade prices.** Deribit expiries are 08:00 UTC and Delta's are
12:00 UTC (17:30 IST), so no Deribit trade prices the Delta contract directly.
- At each hour, the IV for the Delta expiry is interpolated (total variance) from the Deribit expiries that traded
  near the money in the previous hour.
- **99.9% of pre-holdout entries (98.5-99.6% in the holdout) were priced from trade IV.** The rest used DVOL × the
  trailing short-IV/DVOL ratio.
- Marked every 5 minutes along the path with the latest hour's IV. At expiry: intrinsic value.
- Strike: spot rounded to Delta's grid (BTC 200 at $82k, ETH 20 at $2.5k). Expiry: the nearest daily expiry with at
  least h + 2 h left (so 1 h and 4 h trades use same-day or next-day; 24 h and hold-to-expiry use 26-50 h).

**Costs per leg, per side.**
- Fee: min(0.01% × spot × size, 3.5% × premium) × 1.18 GST. Also charged on an in-the-money leg at settlement.
- Spread (Delta live chain, fit across all near-ATM quotes): full spread / spot = 0.000098 + 0.0179 × leg mid / spot
  (BTC) and 0.000148 + 0.0188 × mid / spot (ETH). That is about 2.5% of a 1-day ATM BTC leg and 3% for ETH, more on
  cheaper legs. The final fit on all 22 snapshots was a little tighter (0.000082 + 0.0169), so costs are, if anything,
  slightly overstated.
- Sensitivity (in the tables): Deribit's measured effective half-spread (1.0% BTC, 1.5% ETH of premium) adds
  +0.2 to +1.1 points per trade. Doubling Delta's spread removes 1.3-3.5 points.
- Today's Delta book is used for all of 2022-2026. Delta India did not list these options in 2022, and spreads were
  likely wider then.

**Money.** Rs 1,00,000. Each straddle costs up to 5% of current capital (whole lots: 0.001 BTC, 0.01 ETH).
At most one open straddle per coin. Compounds both ways. USD/INR 96.78.

**Tax.**
- A (strict VDA): 31.2% of every winning trade, losses ignored. Sizing uses after-tax capital.
- B (business income): 31.2% of each financial year's net profit, losses set off and carried forward.
- TDS (1% of sale value, if it applies): a prepayment, not a cost. Over the pick's 69 trades it would have locked up
  about Rs 4,000 until year end.

## Results (2022-01 to 2026-03, pre-holdout)

Per trade = return on the premium paid. Rs columns use the 5% compounding sizing. "Fixed Rs 5k" = Rs 5,000 of
premium every trade, no compounding (easier to compare). p rand = share of 2,000 random-hour draws that did as well;
p same-clock = random hours with the same hour-of-day mix (a post-hoc extra check).

| rule | trades | hit | gross/trade | net/trade | net, fixed Rs 5k | after VDA tax, fixed Rs 5k | net Rs (5% sizing) | after VDA tax Rs | after business tax Rs | Rs/day after VDA tax | max DD Rs | green months | p rand | p same-clock |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Always, 1 h | 74,448 | 15% | -0.5% | -7.3% | -27.3 lakh | -30.0 lakh | -99,900 (ruin) | -99,901 | -99,900 | -64 | 1,00,448 | 4% | 0.51 | 0.50 |
| Always, 4 h | 18,612 | 22% | -1.9% | -7.9% | -73.6 lakh | -93.0 lakh | -99,838 (ruin) | -99,838 | -99,838 | -64 | 1,00,008 | 2% | 0.55 | 0.50 |
| Always, 24 h | 3,102 | 28% | -2.0% | -6.4% | -9.9 lakh | -19.5 lakh | -99,495 (ruin) | -99,604 | -99,495 | -64 | 99,552 | 8% | 0.71 | 0.50 |
| Always, hold to expiry | 1,552 | 35% | +0.7% | -1.8% | -1.4 lakh | -9.6 lakh | -91,696 (ruin) | -99,007 | -91,648 | -64 | 2,12,289 | 37% | 0.01 | 0.44 |
| Same time daily (13:00 UTC = 18:30 IST), 4 h | 3,102 | 32% | +6.0% | +0.4% | +57,719 | -4.0 lakh | +1,328 | -97,316 | -11,874 | -63 | 1,69,504 | 35% | 0.00 | 0.47 |
| Size only (NN), 4 h | 7,147 | 24% | -1.5% | -6.8% | -24.5 lakh | -33.2 lakh | -99,770 (ruin) | -99,776 | -99,770 | -64 | 1,30,917 | 2% | 0.00 | 0.00 |
| Cheap IV only (HAR), 4 h | 10,355 | 25% | +0.5% | -5.5% | -28.4 lakh | -41.0 lakh | -99,783 (ruin) | -99,804 | -99,783 | -64 | 1,04,761 | 6% | 0.00 | 0.02 |
| Size + cheap (NN), 1 h | 11,135 | 21% | +1.5% | -4.0% | -22.2 lakh | -28.0 lakh | -99,649 (ruin) | -99,676 | -99,649 | -64 | 1,08,910 | 4% | 0.00 | 0.00 |
| Size + cheap (NN), 4 h | 4,698 | 26% | +0.2% | -5.0% | -11.8 lakh | -17.7 lakh | -99,328 (ruin) | -99,569 | -99,328 | -64 | 1,19,886 | 10% | 0.00 | 0.01 |
| 19:00-21:00 IST + cheap, 4 h | 4,442 | 31% | +4.7% | -0.9% | -1.97 lakh | -7.4 lakh | -91,114 (ruin) | -98,761 | -91,649 | -64 | 1,97,941 | 37% | 0.00 | 0.08 |
| Extreme funding + cheap, 24 h | 302 | 32% | -1.7% | -5.9% | -89,416 | -1.85 lakh | -65,234 | -84,977 | -66,537 | -55 | 94,754 | 28% | 0.52 | 0.53 |
| CPI/FOMC, 4 h | 168 | 39% | +5.5% | +0.9% | +7,153 | -12,308 | +5,712 | -12,220 | -1,962 | -8 | 24,145 | 45% | 0.00 | 0.05 |
| **CPI/FOMC + cheap, 4 h, +30/-50 (the pick)** | **69** | 43% | +8.2% | **+3.3%** | +11,450 | +171 | +11,246 | **-220** | +5,843 | **-0.1** | 8,223 | 27% | 0.01 | 0.004 |
| CPI/FOMC + cheap, 1 h (22 trades, below the 40 minimum) | 22 | 55% | +22.7% | +17.6% | +19,305 | +12,441 | +20,003 | +12,671 | +13,473 | +8 | 1,562 | 12% | 0.00 | 0.00 |

- "Ruin" = capital fell below Rs 25,000. Every always-on and size/cheap rule ruins the account. They lose 4-8% of each
  premium, and they trade thousands of times.
- The full 280-row table, with holdout rows and all columns: `scratchpad/hunt/strad_crypto/logs/rules_all.csv`.
  Formatted tables: `logs/report_tables.md`.

**Straddle returns are lottery tickets.** The median trade loses 9-36% of its premium. The mean is carried by rare
huge wins. Example: a 2-day BTC straddle bought on 19 Aug 2026 at 22.8% IV, when BTC went from 64,400 to 77,800 in
48 h, returned **+1,269%**. One such trade decides a quarter. So per-trade averages on fewer than 100 trades are
very noisy. Strict VDA tax hurts this shape most: it taxes the big winners and ignores the many small losers.

### Walk-forward (each quarter, pick the best rule on all earlier data; ≥ 40 trades)

| quarter | rule picked | its past net/trade | trades | net/trade in the quarter |
|---|---|---|---|---|
| 2023 Q1 | CPI/FOMC, 24 h, +60/-50 | +15.8% | 14 | +6.0% |
| 2023 Q2 | CPI/FOMC, to expiry | +19.0% | 10 | -37.4% |
| 2023 Q3 | CPI/FOMC, 24 h | +10.2% | 10 | -22.9% |
| 2023 Q4 | CPI/FOMC, 24 h | +5.5% | 10 | -23.6% |
| 2024 Q1 | CPI/FOMC, 24 h, +60/-30 | +6.2% | 20 | +3.8% |
| 2024 Q2 | CPI/FOMC, to expiry | +7.7% | 8 | -25.0% |
| 2024 Q3 | CPI/FOMC, 24 h, +60/-30 | +5.4% | 11 | -18.1% |
| 2024 Q4 | CPI/FOMC, 24 h, +60/-30 | +3.6% | 17 | -4.5% |
| 2025 Q1-Q4 | CPI/FOMC + cheap, 4 h | +5.8% to +8.3% | 2-7 each | -13.2% to +5.0% |
| 2026 Q1 | CPI/FOMC + cheap, 4 h | +5.1% | 9 | -10.1% |
| **all** | | | **125** | **-10.4%; -Rs 49,381; -Rs 58,203 after VDA tax; 18% green months** |

Every quarter picked a CPI/FOMC rule. The rule kept looking good on the past and then lost.

### The pick by year

| year | trades | net per trade |
|---|---|---|
| 2022 | 11 | +28.2% |
| 2023 | 11 | -4.2% |
| 2024 | 22 | +1.4% |
| 2025 | 16 | -1.5% |
| 2026 (Jan-Mar, then holdout) | 23 | +0.6% |

### Holdout (2026-04-01 to 2026-10-07), run once, labelled "partly seen"

| rule | trades | hit | gross/trade | net/trade | net Rs | after VDA tax | after business tax | Rs/day after VDA tax | max DD | p (mean ≤ 0) |
|---|---|---|---|---|---|---|---|---|---|---|
| **The pick: CPI/FOMC + cheap, 4 h, +30/-50** | 14 | 64% | +9.6% | +3.8% | +2,644 | +1,157 | +1,819 | +6 | 1,063 | 0.13 |
| Always, 1 h | 9,152 | 17% | +0.1% | -8.1% | -99,817 (ruin) | -99,824 | -99,817 | -525 | 1,00,431 | 1.00 |
| Always, hold to expiry | 190 | 33% | -3.0% | -6.0% | -64,847 | -83,768 | -64,847 | -441 | 74,884 | 0.76 |
| Same time daily, 4 h | 380 | 27% | +5.5% | -1.0% | -28,009 | -59,864 | -28,009 | -315 | 46,742 | 0.69 |
| Size + cheap (NN), 4 h | 483 | 27% | +2.7% | -3.5% | -61,031 | -80,247 | -61,031 | -422 | 60,640 | 0.99 |
| CPI/FOMC, 4 h (descriptive) | 20 | 60% | +15.3% | +9.3% | +9,278 | +5,283 | +6,384 | +28 | 1,740 | 0.06 |

28 of 280 variants were positive net in the holdout (descriptive only; none of them was chosen on it).

## Are short-dated BTC/ETH options cheap or dear?

Implied move = the straddle's IV × √(h / 8760). Realised = √(sum of squared 5-min returns) over the same h hours.
Ratio = total realised / total implied (above 1 = options were cheap).

| coin | horizon | median IV | median realised vol | realised / implied, 2022-26Q1 | same, holdout |
|---|---|---|---|---|---|
| BTC | 1 h | 44% | 33% | 0.90 | 0.96 |
| BTC | 4 h | 44% | 37% | 0.95 | 1.01 |
| BTC | 24 h | 46% | 43% | 0.99 | 1.04 |
| ETH | 1 h | 57% | 44% | 0.93 | 0.94 |
| ETH | 4 h | 57% | 50% | 0.99 | 1.00 |
| ETH | 24 h | 59% | 57% | 1.03 | 1.04 |

What a buyer actually gets: **payoff at expiry / price paid (mid, before costs), 2-day ATM straddle, every hour**

| coin | 2022 | 2023 | 2024 | 2025 | 2026 | all pre-holdout | holdout |
|---|---|---|---|---|---|---|---|
| BTC | 0.87 | 0.90 | 0.92 | 0.90 | 0.99 | **0.90** | 1.00 |
| ETH | 0.99 | 0.92 | 0.94 | 1.00 | 0.99 | **0.97** | 0.95 |

- BTC straddles were about 10% dear until 2026, then about fair. ETH was about fair (3% dear).
- The hour-of-day pattern is the big one (1 h horizon, realised / implied): 0.68-0.75 at 03:00-11:00 UTC,
  1.08-1.26 at 13:00-16:00 UTC (18:30-21:30 IST). Options do not price the US session's extra movement hour by hour.
  But a 1-day straddle's price covers the whole day, so you cannot buy "just the US hours" without paying the
  round-trip cost.

## The size forecasts (for comparison with the NN study)

Walk-forward by quarter, out-of-sample 2022-01..2026-03.

| coin | horizon | HAR: rank corr with |move| | NN: rank corr with |move| | NN: rank corr with realised vol |
|---|---|---|---|---|
| BTC | 1 h | 0.43 | 0.43 | 0.77 |
| BTC | 4 h | 0.38 | 0.40 | 0.79 |
| BTC | 24 h | 0.26 | 0.29 | 0.73 |
| ETH | 1 h | 0.41 | 0.41 | 0.75 |
| ETH | 4 h | 0.37 | 0.39 | 0.78 |
| ETH | 24 h | 0.28 | 0.30 | 0.75 |

- These are a little above the NN study's 0.30-0.38. They include hour-of-day, which carries much of the skill.
- The NN adds almost nothing over HAR (+0.00 to +0.03).
- A good size forecast is not enough. The option price already contains most of the same information.

## Limits

- Deribit IV, not Delta's own historical prices (Delta does not publish history for expired options). Delta's own
  quotes may sit above or below Deribit's IV.
- Today's spreads are applied to 2022-2026. The 2022 market was thinner.
- Fills at the 5-minute mark when a target or stop is crossed. Real fills could be worse in fast moves.
- Event dates for CPI/FOMC were typed by hand and checked against price jumps; one wrong date would not change the
  verdict.
- The holdout is "partly seen": the NN study opened Apr-Oct 2026 once before. This study did not use it to choose.

## Files

- `research/hunt/strad_crypto/PREREG.md`: the plan, written before any P&L (with two pre-P&L amendments, noted).
- `fetch_base.py` (perps, funding, DVOL), `fetch_trades.py` (hourly Deribit option-trade IV), `delta_spread.py`
  (Delta live chain spread fit), `events.py`, `build.py` (features), `models.py` (HAR and NN, walk-forward),
  `sim.py` (straddle pricing and exits for every hour), `rules.py` (rules, money, tax, random baselines, BH,
  walk-forward, holdout), `report.py` (tables).
- Logs: `scratchpad/hunt/strad_crypto/logs/` (`rules_all.csv`, `walkforward.csv`, `final.json`, `report_tables.md`,
  `final_trades_pre.csv`, `final_trades_hold.csv`, `model_skill_*.csv`, `event_check.csv`).
- Data (168 MB): `scratchpad/hunt/strad_crypto/data/` and `trades/`.
