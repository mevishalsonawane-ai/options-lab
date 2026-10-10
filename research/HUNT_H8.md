# HUNT h8: the Liquidity 15+5 rule on 214 F&O stocks, long-only intraday cash. Can it make Rs 5,000/day?

## Verdict

**NO.** Moving the app's Liquidity 15+5 rule onto stocks, buying only, does not make Rs 5,000/day. It does not make
money at all after costs.

**The signal has no long-side edge on stocks.**
- A Liquidity long earns about **+1 bp gross** per trade.
- A random long entry, on the same stock and day with exactly the same exits, earns **+4 to +6 bps**.
- So the signal is about **4.5 bps worse than random**, in both periods. The 95% confidence intervals lie wholly
  below zero:

| Period | Signal minus random | 95% CI |
|---|---|---|
| Before the holdout | −4.4 bps | −5.5 to −3.2 |
| Holdout | −4.6 bps | −6.2 to −2.9 |

**Costs make it hopeless.** The rule's targets are only about 1–3 index stops away, roughly 15–40 bps of price. A
round trip in cash costs about 15 bps (charges plus spread). That is about 8 times the gross edge.

**The same thing shows up on the indices, so the option arm's profit is not a long-side spot signal.** I took the
app arm's own option trades and re-priced them on the index spot over the identical holding window:
- **Longs:** +2.0 bps on average. That is below the drift that random longs with the same exits earn on the same
  indices.
- **Shorts:** +5.3 bps.
- **Option P&L:** 40% of trades win, yet the option P&L is positive. The arm's money comes from the short side and from
  option payoff shape: convexity, and the −15% premium stop cutting losers short. It does not come from a directional
  edge that a long-only cash trader could capture.

**Simple rule tested** (fixed before any stock result was seen):
1. Use the 15-minute book only, on the 50 most liquid F&O stocks.
2. Buy when a 15-minute bar closes above a liquidity pool that overlaps a live swing high, with the bar ending
   09:20–14:00 and the next level at least 1 stop away.
3. The stop is the level minus 0.046 × ATR14. Exit at the next level, on a failed break, on a new level, after
   20 minutes unless the trade is up 1.75 stops, or at 15:10.
4. Hold at most 5 positions at a time, at a fixed Rs 1 lakh per trade.

**Holdout result** (Oct 2025 – Oct 2026, 248 days, 1,915 trades):

| | Result |
|---|---|
| Gross | +Rs 57/day |
| Net | **−Rs 1,161/day** |
| Months that lost money (net) | 13 of 13 |
| Max drawdown | −Rs 2.87 lakh net (gross −Rs 19.5k) |
| P(losing month) | 99.8% net, 45% gross |

**Size needed for Rs 5,000/day**
- **Gross:** about Rs 87 lakh per trade on the holdout figures, or Rs 36 lakh on the pre-holdout figures. With 5
  positions open that is Rs 1.8–4.4 crore of exposure and about Rs 36–87 lakh of MIS margin (20%).
- **Net:** there is no size that works. Net gets more negative the bigger the size, because market impact grows: −Rs
  646/day at Rs 50k per trade, −Rs 9,733/day at Rs 10 lakh per trade.

## What was done

Code is in `research/hunt/h8/`:
- `liqcash.py`: the rule and the exits.
- `parity.py`: checks the fast port against the reference.
- `build.py`: builds the trades.
- `spot_vs_option.py`: re-prices the arm's trades on spot.
- `analyze.py`: selection, statistics and the holdout.

Caches are in `scratchpad/hunt/h8/` (about 11 MB).

**Data.** 214 F&O stocks' Dhan 1-minute candles (NSE_EQ), 7 Oct 2024 – 5 Oct 2026, and index minutes.

**The rule is unchanged.** It uses the same pool-on-swing zones, books, de-duplication, room filter,
failed-break / new-level exits and time window as `h4/comps.py` (a port of `LiquidityRules.kt`).
- For speed, the zones are computed over the whole history once instead of once per 10-day window.
- `parity.py` shows this matches the per-day reference **signal-for-signal**: 131 of 131 signals were identical across
  RELIANCE, TATASTEEL, DIXON and BANKNIFTY, both books.
- Only the long side is used, and the 5-minute and 15-minute books trade one position each per stock.

**Pre-registered conversions from options to cash.** These were fixed before any stock result was looked at.

| Option rule | Cash version | How it was set |
|---|---|---|
| Index stop | 0.046 × ATR14 (floor Rs 0.10), median 13 bps | BANKNIFTY 30 and FINNIFTY 15 index stops divided by their median ATR14, Oct 2024 – Sep 2025 |
| −15% premium stop | Resting stop at entry − 5 stops | A 1-ITM premium is about 1.2% of spot, delta about 0.6 |
| 20-minute time stop, unless the premium is up 5% | At minute 20, exit unless up 1.75 stops | Same conversion |

**Fills.**
- A decision on a minute's close fills at the next minute's open.
- The resting stop fills at the stop price, or at the open if the price gaps through it.
- Square-off is at 15:10.

**Costs (net).**
- Intraday cash charges: brokerage, 0.025% STT on the sell, exchange fee, SEBI fee, stamp duty and GST.
- Half-spread of 2, 3, 5 or 8 bps a side, by the stock's traded value. Stop fills pay double.
- Market impact of 10 × √(order / average 5-minute traded value) bps a side, the same model as h2.

The app's options cost model does not apply to cash equity, so this uses the same structure with cash-intraday rates.

**Controls.**
- **Random baseline:** 5 random long entries per trade, on the same stock and day, at a minute drawn uniformly from
  09:20–14:00. They use identical stop, target and exit-time offsets and the identical portfolio rules.
- **Variants:** 27 in total, all counted. Books (both / 15-min / 5-min) × universe (top 50 / 100 / 214 by prior 20-day
  traded value) × cap on open positions (5 / 10 / 20).
- **Selection:** the variant with the best pre-holdout net Rs/day, chosen only on data before 1 Oct 2025.
- **Multiple-testing checks:** BH and White RC / Hansen SPA across the 27 variants.
- **Walk-forward:** anchored, quarterly. A full year-by-year walk-forward was not possible because the stock minutes
  start in Oct 2024.
- **Holdout:** run once.

## Results

### Before the holdout (Oct 2024 – Sep 2025, 245 days), at Rs 1 lakh per trade

- **Gross:** −Rs 115 to +Rs 487/day.
- **Net:** −Rs 1,032 to −Rs 19,544/day. All 27 variants are negative.
- **Correction for everything tried:** BH q = 1.0 for every variant. SPA p = 1.0 net and 0.25 gross; White RC p = 0.25
  gross.
- **Random baseline:** the mean of the 5 random-entry portfolios beats the signal portfolio's net in 26 of 27 variants.
- **Best variant:** b15 | top50 | cap5. It made −Rs 1,032/day net and +Rs 139/day gross, with 7.5 trades/day. All 12
  months lost money net.
- **Walk-forward:** the best variant was picked every quarter. Out-of-sample it made −Rs 1,060/day net and
  +Rs 192/day gross.

Trade-level results across all 214 stocks (about 120 trades/day):

| Period | Signal, gross per trade | Random, gross per trade |
|---|---|---|
| Before the holdout | +0.8 bp | +5.2 bps |
| Holdout | +1.0 bp | +4.4 bps |

How trades exited: index stop 32%, target 23%, time stop 22%, failed break / new level 14%, hard stop 6.5%,
square-off 2%.

### Holdout (chosen variant, run once)

| | Signal | Random entries, same exits and portfolio rules (5 runs) |
|---|---|---|
| Gross Rs/day | +57 | +433 to +607 |
| Net Rs/day | −1,161 | −698 to −877 |

- Worst day: −Rs 5,384. Worst month: −Rs 36,124.
- By calendar year, net Rs/day: 2024 −943, 2025 −1,111, 2026 −1,126.

### Signal or option effect? Index spot

**Test 1: the arm's actual option trades, re-priced on spot** (h4 cache, 2021–2026, same entry and exit minutes).

| Index | Long side, spot bps (before / holdout) | Short side, spot bps (before / holdout) |
|---|---|---|
| BANKNIFTY | +4.0 / +0.6 | +5.0 / +7.2 |
| FINNIFTY | +2.4 / +0.6 | +4.9 / +2.4 |
| MIDCPNIFTY | +1.0 / −0.4 | +10.4 / +5.4 |
| NIFTY | +1.7 / +0.7 | +3.1 / +2.4 |

**Test 2: the same long-only cash-converted rule run on the spot index**, with the app's index stops, compared with
random longs using the same exits.

| Index | Signal, pts per trade (pre) | Random, pts per trade (pre) |
|---|---|---|
| BANKNIFTY | +9.4 | +10.9 |
| FINNIFTY | −0.8 | +7.2 |
| MIDCPNIFTY | −1.0 | +1.5 |
| NIFTY | +3.4 | +3.4 |

In both tests the long breaks carry nothing beyond market drift.

## Caveats

- **Survivorship.** The 214 names are today's F&O list. Stocks that were dropped from F&O, or that fell out of
  liquidity, are missing. This usually flatters long-only results, so the true result is, if anything, worse.
- **Short sample.** The stock minutes cover only 2 years.
- **The conversions are judgement calls.** The option-to-cash conversions were fixed in advance, but they are still
  approximations. However, the result does not depend on the exit details: the signal loses to random entries that use
  the same exits.
- **Costs.** The costs assume a discount broker; a full-service broker would make it worse. The gross column is free of
  any cost assumption, and even gross is far below Rs 5,000/day at any sane size.
