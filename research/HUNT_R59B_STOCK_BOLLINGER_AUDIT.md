# R59b: audit of the "Bollinger Band Mean Reversion" on stock options (15 Oct 2026)

**Verdict: the claimed +Rs 967 a trade is not real.** Their number reproduces **exactly** on the lake's *rolling* ATM
series (7,297 trades, 73.0% win, +Rs 967 a trade, max drawdown -Rs 31,485), but **97.8% of its profit comes from trades
where that series switched to another strike or rolled expiry**, inside the band's 20-bar window or during the hold.
On contracts you could really hold, with one real lot, real fills and real charges, **0 of 126 pre-registered cells
pass the gates**. Every pooled version loses about Rs 180-200 a trade in the final test, and **before any costs it
makes about Rs 0 a trade** (design -Rs 13 to -55, final +Rs 18 to +29, t below 1). A tuning round is **not worth it**.
Nothing goes to paper. Nothing in the app changes.

**Signal source:** their rule reads its signal from the **option's own price chart** (a Bollinger band on the ATM
premium). Under CLAUDE.md that is **not the default**. It is labelled and reported separately here (family A), and it
could only ever go on paper with Boss's explicit yes. The default-compatible version (family B) reads the band from the
**stock**: the stock's close under its lower band buys the ATM call, over its upper band buys the ATM put. It fails too.

Pre-registered in `hunt/r59b/PREREG.md` (commit 09f3adb, pushed before any P&L). Code, tests, the literal reproduction
and the design results were frozen in commit a8a1527 before the one final read. R59's per-stock spread estimates
(`hunt/r59/results/spread.json`, design-only) were reused unchanged. Code, tests and results are in `hunt/r59b/`
(`results/report.md` has every cell). Experiment entries: `experiments/entries/r59b-*.yaml` (7 files).

**Data rule (Boss, 14-15 Oct):** every number below is from 20 Nov 2024 onwards, except Step 1-2, which is the literal
check of their claim on their own window (4 Nov 2024 - 14 Oct 2026), labelled as such.

## The claim (another AI, shared by Boss on 15 Oct)

- **Universe:** RELIANCE, HDFCBANK, ICICIBANK, INFY, TCS, SBIN, AXISBANK, KOTAKBANK, ITC, TMPV (Tata Motors); ATM
  monthly CE and PE from our lake's `stock_options_rolling_1m`, 1-minute resampled to 15-minute bars.
- **Rule:** Bollinger(20 bars, 2.0 sd) on the option premium close; buy when the close is at or under the lower band
  and at least Rs 5, at that bar's close; sell 4 bars (60 minutes) later at the bar close. Rs 10,000 notional
  (fractional contracts, no lot size), Rs 95 a trade, no stop, no target, overlapping trades.
- **Claimed:** 7,297 trades, 73.0% win, +Rs 967 a trade (median +Rs 626), +Rs 70.5 lakh, max drawdown -Rs 31,485,
  every quarter 70-77% win, every stock 67-77% win, 15.5 trades a day.

## Step 1: their number, reproduced (a check of their claim only, 4 Nov 2024 - 14 Oct 2026)

| | their claim | our reproduction |
|---|---|---|
| trades | 7,297 | **7,297** |
| win rate | 73.0% | **73.0%** |
| per trade (median) | +Rs 967 (+Rs 626) | **+Rs 967 (+Rs 626)** |
| total | +Rs 70.5 lakh | +Rs 70.53 lakh |
| max drawdown | -Rs 31,485 | **-Rs 31,485** |
| win per quarter | 70-77% | 70.7% (2026 Q2) - 77.1% (2024 Q4) |
| win per stock | 67-77% | 67.2% (INFY) - 77.1% (ICICIBANK) |
| trades a day | 15.5 | 15.3 (over the 477 dates with rows; their count of days probably differs) |

**It reproduces to the rupee.** Their code is pandas `rolling(20).mean()` / `.std()` on the rolling 15-minute ATM close
(empty bins dropped, so the series runs across sessions), exactly as pre-registered.

## Step 2: where the profit comes from

The lake's option file holds, for each minute, **whichever contract was ATM at that minute**. When the stock crosses
halfway to the next strike, the series jumps to a different contract. For a call, a rise in the stock moves the series
to the next higher, **cheaper** strike: the "premium" falls far under a band built mostly from the dearer strike, though
the call you held went up. When the stock comes back, the series switches back to the dearer strike and the "premium"
jumps up again. Nobody holding one option gets either move.

Each of their 7,297 trades, classified (rows overlap):

| category | trades | share of trades | profit | share of profit | Rs a trade | win |
|---|---|---|---|---|---|---|
| **one contract throughout** (band window and hold on one contract) | 651 | 8.9% | +Rs 1.5 lakh | **2.2%** | +Rs 235 | 60.1% |
| the band's 20-bar window mixed contracts | 6,181 | 84.7% | +Rs 62.5 lakh | 88.6% | +Rs 1,011 | 74.5% |
| the band break itself (bar t-1 -> t) was a strike switch | 3,546 | 48.6% | +Rs 38.4 lakh | 54.5% | +Rs 1,084 | 76.3% |
| the "recovery" (t -> t+4) was a strike switch | 3,297 | 45.2% | +Rs 54.8 lakh | 77.7% | +Rs 1,663 | 83.7% |
| the exit was priced on the next expiry | 2 | 0.0% | +Rs 0.26 lakh | 0.4% | +Rs 12,957 | 100% |
| **any switch or roll** | 6,646 | **91.1%** | +Rs 69.0 lakh | **97.8%** | +Rs 1,038 | 74.3% |

- **97.8% of the profit is from switches and rolls.** Every one of the 20 series shows it (92.6-109% of each series'
  profit).
- The 651 one-contract trades made +Rs 235 a trade with 60% win, but with their flat Rs 95, no spread, entry at the
  signal bar's own close and Rs 10,000 of fractional contracts. Step 3 shows what is left with real fills and lots.
- **Their own trades valued on the contract actually held at the signal bar** (a diagnostic: Rs 10,000, their Rs 95,
  no spread): 7,064 trades, 44.3% win, **-Rs 60 a trade**.
- Unlike R59, rolls matter little here (2 trades): the Bollinger profit is almost all strike switches.

This is the same artefact R45 (NIFTY premium scalp), R46 amendment 3 (BTC dip buy) and R59 (stock "S2 dip buy") found.

## Step 3: the honest test

Pre-registered before any P&L (`hunt/r59b/PREREG.md`), on R59's engine:

- **Held contracts.** A contract is (expiry, strike, side), followed across the ATM-1, ATM and ATM+1 rows. Expiries
  and **lot sizes from the weekly NSE F&O bhavcopies**, per stock, expiry and date.
- **Family A (option chart, NON-DEFAULT):** at a 15-minute close (bars 2-24), the ATM contract closes at or under the
  lower Bollinger(20, 2) band of **its own** 20 most recent 15-minute closes (they may reach the previous session of the
  same contract; no other contract ever enters the window; fewer than 20: no signal), at Rs 5 or more, with its last
  trade in the bar's last 5 minutes.
- **Family B (stock, default):** the stock's own 15-minute close (`equity_1m`) at or under its lower band buys the ATM
  call; at or over its upper band buys the ATM put.
- **Fills:** buy at the open of the contract's next 1-minute row (within 5 minutes), sell at the open 60 minutes later
  (or 15:25) on the **same** contract; R47 stray-print guard.
- **Spread, per stock (R59, frozen):** 5 bps plus the larger of R17's 1/2/4 ticks and a design-only Roll half-spread of
  0.26-0.50% of the premium a side. Stops and locks pay double. **Charges:** `Costs("dated")`, both orders. 1x and 2x.
- **Size:** exactly 1 lot. **Variants:** NS (no stop, their exit; comparison only), S25 (25% premium stop) and S25L
  (S25, plus a profit lock: once a 1-minute high reaches +20% over the fill, the stop moves to the fill price).
- **Cleaning:** foreign rows, `spikes.py` ticks, short sessions, 5 sessions either side of a corporate action (HDFCBANK
  bonus 26 Aug 2025, KOTAKBANK split 14 Jan 2026, Tata Motors demerger 14 Oct 2025); no trade on the expiry session or
  the 2 sessions before (physical settlement); one position per stock and side.
- **Splits:** design 20 Nov 2024 - 31 Mar 2026; final test 1 Apr - 12 Oct 2026 (equity minutes end on 12 Oct), read
  once.
- **Gates (per cell, final, 1x costs):** net > 0; p < 0.05 against 500 sets of random-entry twins; Benjamini-Hochberg
  q <= 0.10 across all 126 cells; 40+ trades; net without the top 5 trades > 0; design net > 0.

### Results, pooled (all 10 stocks, both sides)

| cell | design: trades, win, Rs/trade, net, gross/trade | final: trades, win, Rs/trade, net (2x costs) | final gross/trade before costs | p, q | random twins Rs/trade | capital per trade (median / max) | trades per session | stop / lock exits |
|---|---|---|---|---|---|---|---|---|
| A NS (option chart) | 2830, 42.0%, -189, -Rs 5.34 lakh, -13 | 1052, 40.9%, **-180**, -Rs 1,89,075 (-Rs 3,96,042) | +29 (t 0.7) | 0.002, 0.01 | -415 | Rs 12,075 / 28,694 | 9.2 | 0 / 0 |
| A S25 | 2923, 42.2%, -194, -Rs 5.68 lakh, -18 | 1064, 41.0%, **-185**, -Rs 1,96,742 (-Rs 4,05,598) | +24 (t 0.6) | 0.002, 0.01 | -418 | Rs 12,018 / 28,694 | 9.3 | 29 / 0 |
| A S25L | 2931, 41.4%, -207, -Rs 6.06 lakh, -30 | 1066, 40.6%, **-185**, -Rs 1,97,543 (-Rs 4,06,715) | +23 (t 0.6) | 0.002, 0.01 | -416 | Rs 12,018 / 28,694 | 9.4 | 30 / 10 |
| B NS (stock signal) | 3264, 40.8%, -227, -Rs 7.42 lakh, -43 | 1269, 40.0%, **-198**, -Rs 2,51,874 (-Rs 5,15,204) | +20 (t 0.6) | 0.002, 0.01 | -433 | Rs 12,100 / 33,131 | 11.1 | 0 / 0 |
| B S25 | 3382, 40.8%, -237, -Rs 8.01 lakh, -52 | 1297, 40.1%, **-202**, -Rs 2,61,472 (-Rs 5,30,686) | +18 (t 0.5) | 0.002, 0.01 | -436 | Rs 12,012 / 33,131 | 11.4 | 39 / 0 |
| B S25L | 3390, 40.2%, -240, -Rs 8.14 lakh, -55 | 1299, 39.9%, **-200**, -Rs 2,60,016 (-Rs 5,30,503) | +19 (t 0.5) | 0.002, 0.01 | -438 | Rs 12,012 / 33,131 | 11.4 | 39 / 9 |

(Trades per session: pooled over the 20 series, per eligible session.)

- Every pooled cell loses in design and in the final test, at 1x and 2x costs. Win rates are 40-42%, not 73%.
- The pooled cells "beat" their random twins (p 0.002), but only because a random ATM option bought and held for an
  hour loses even more (about -Rs 415 to -440 a trade: time decay plus the spread). Beating a losing baseline is not
  making money. The same thing happened in R59.
- The stop and the lock barely matter: in 60 minutes a 25% stop is hit on about 3% of final trades (6% in design),
  and the +20% lock exits about 1%.

### Results per stock and side (final test, 1x costs, S25 variants; every cell, NS and S25L too, in `results/report.md`)

Trades, win %, net Rs, p (q).

| stock | A S25 CE | A S25 PE | B S25 CE | B S25 PE |
|---|---|---|---|---|
| RELIANCE | 82, 37%, -22,895, 0.068 (0.13) | 58, 36%, -20,070, 0.301 (0.33) | 86, 38%, -20,495, 0.036 (0.09) | 60, 30%, -19,016, 0.146 (0.22) |
| HDFCBANK | 64, 38%, -10,340, 0.062 (0.13) | 57, 37%, -4,549, 0.052 (0.11) | 70, 31%, -14,810, 0.285 (0.32) | 61, 33%, -11,331, 0.164 (0.24) |
| ICICIBANK | 64, 38%, -19,947, 0.104 (0.18) | 62, 39%, -23,012, 0.216 (0.30) | 65, 46%, -4,526, 0.006 (0.02) | 68, 43%, -24,420, 0.262 (0.31) |
| INFY | 62, 42%, -14,516, 0.375 (0.38) | 48, 56%, +4,076, 0.002 (0.01) | 66, 52%, -84, 0.004 (0.02) | 62, 48%, -5,607, 0.106 (0.18) |
| TCS | 59, 41%, -16,339, 0.272 (0.32) | 55, 51%, -9,725, 0.198 (0.28) | 64, 44%, -18,643, 0.138 (0.21) | 70, 49%, -16,255, 0.281 (0.32) |
| SBIN | 70, 31%, -19,563, 0.020 (0.05) | 50, 46%, -5,161, 0.006 (0.02) | 74, 24%, -37,011, 0.359 (0.37) | 59, 48%, -5,705, 0.016 (0.04) |
| AXISBANK | 58, 52%, +17,574, 0.004 (0.02) | 54, 48%, -1,540, 0.032 (0.08) | 58, 48%, +19,937, 0.002 (0.01) | 64, 50%, +2,362, 0.010 (0.03) |
| KOTAKBANK | 51, 47%, +9,148, 0.002 (0.01) | 48, 44%, -19,182, 0.116 (0.19) | 63, 40%, +3,613, 0.002 (0.01) | 69, 29%, -42,915, 0.230 (0.31) |
| ITC | 25, 24%, -9,334, 0.249 (0.31) | 7, 29%, -1,302, 0.088 (0.16) | 68, 31%, -24,602, 0.010 (0.03) | 63, 51%, -9,898, 0.004 (0.02) |
| TMPV | 49, 33%, -20,420, 0.369 (0.38) | 41, 42%, -9,647, 0.038 (0.09) | 56, 32%, -24,150, 0.247 (0.31) | 51, 39%, -7,916, 0.004 (0.02) |

- 18 of 120 per-stock cells are positive in the final test (AXISBANK CE in every family and variant, KOTAKBANK CE, INFY
  PE in A, AXISBANK PE in B). **Every one of them lost in design**, and in every one the top 5 trades carry more than
  the whole net. Only one cell made money in design (A NS TMPV CE, +Rs 105 over 148 trades); it lost in the final test.
- Gate failures across the 126 cells: top 5 >= 100% of net 126, design net <= 0 125, final net <= 0 108, q > 0.10
  71, p >= 0.05 68, fewer than 40 trades 6 (ITC in family A).

### Before costs: is a tuning round worth it?

The pre-registered rule: worth trying only if a family's pooled gross per trade (no spread, no bps, no charges) is
positive in BOTH design and final with t >= 2, and the final gross covers the median cost per trade.

| pooled cell | design gross / trade (t) | final gross / trade (t) | median cost / trade (final) | worth trying? |
|---|---|---|---|---|
| A NS | -Rs 13 (-0.5) | +Rs 29 (0.7) | Rs 180 | no |
| A S25 | -Rs 18 (-0.7) | +Rs 24 (0.6) | Rs 180 | no |
| A S25L | -Rs 30 (-1.2) | +Rs 23 (0.6) | Rs 181 | no |
| B NS | -Rs 43 (-1.8) | +Rs 20 (0.6) | Rs 199 | no |
| B S25 | -Rs 52 (-2.2) | +Rs 18 (0.5) | Rs 198 | no |
| B S25L | -Rs 55 (-2.3) | +Rs 19 (0.5) | Rs 198 | no |

**Not worth it.** Before any cost, the held-contract version is about zero: slightly negative in design (family B
significantly so), slightly positive in the final test, never with t near 2. Costs are about Rs 180-200 a trade, so a
tuned version would need a gross edge 6-10 times larger than anything seen here. The band break does pick better
moments than a random entry (roughly Rs 230 a trade better before costs: a random hour loses to time decay; the figure
is the twins' net less a typical cost), but "less bad than random" is still about zero. No tuning grid was run.

### Capital, lots and costs

- **Rs 10,000 notional is not a tradable size.** The smallest order is 1 lot (lots in this window: see R59, e.g.
  RELIANCE 500, TCS 175 then 225, KOTAKBANK 400 then 2,000 after the split).
- **One lot of the ATM option** cost a median Rs 12,000-12,100 in the final test (per-cell medians Rs 6,700-18,300,
  largest Rs 33,131).
- **Costs:** about 0.6-1.1% of the premium in spread and bps per round trip plus Rs 50-160 of charges a lot, about
  Rs 180-200 a trade at 1x. Their flat Rs 95 with no spread understates it by half, but costs are not why the claim
  fails. The switches are.

## Honest limits

- The final test (1 Apr - 12 Oct 2026) is a fresh read for this idea, but not pristine: other studies (R59 among them)
  have looked at these months.
- Stock-option minutes match NSE bhavcopy highs and lows only about 68% of the time (`data/VALIDATION.md` section 3).
  Bad ticks are filtered, but stock-option prices are noisier than index ones.
- The lake has only ATM-1..ATM+1 for these stocks, so a contract's own band history has gaps when it is two strikes
  away; those bars are simply left out of its 20-bar window. Exits with no row use the last close with a 4-tick spread
  (25-32 of each pooled final cell).
- The Roll spread is an estimate from minute closes; Dhan has no historical bid/ask. The 2x-cost columns and the gross
  column show the results do not hinge on it.
- Their band (20, 2.0), hold (60 minutes) and the stop / lock levels were taken as given; nothing was tuned. The
  "worth tuning" call rests on the gross edge being about zero, not on a grid.
- Two cleaning rules use whole-day knowledge (a session needs 300+ stock minutes; the 5 sessions before a corporate
  action are skipped); neither reads prices after a signal.

## What changes

Nothing in the app. Their rule should not be used anywhere: it only "works" on a price series nobody can trade, and the
held-contract version, with the signal on the option or on the stock, loses after costs and is about zero before them.
The lesson is the same as R45, R46 and R59: **for any claim built on the lake's rolling option files, check that the
whole signal window and the trade are on one contract** (strike + expiry + side). An indicator with a look-back window
(Bollinger, RSI, moving averages) on the rolling series mixes contracts even when the trade itself does not.
