# R95: the "Mo3ty CCI Signals" indicator (21 Oct 2026) - fails as given; 2 of 328 cells survive (FINNIFTY MO)

Boss, 21 Oct: "Deep understand the indicator below, find the connection between the indicator data and actual data, and
find out the strategy's win rate."

**Outcome in one paragraph.** The indicator's own BUY / SELL signals (CCI 25 crossing back inside +-200) do not predict
direction. Price moves the signal's way about as often as after any random candle. As a trade (signal on the underlying,
1-ITM option bought, real prints, real costs, 1 lot) they lose on index 3m and 5m candles in both periods, are mixed on
15m and 60m (pooled 15m: design -Rs 71,491 on 1,156 trades, holdout +Rs 66,740 on 477; 60m: +Rs 61,524 on 244, then
-Rs 20,858 on 86), and lose on every MCX timeframe. The underlying moves the right way only 33-44% of the time (pooled,
before costs; single cells 30-53%). After costs the option wins 28-47% of index trades and 11-39% of MCX trades
(single cells, both periods). Average option win / average option loss is 1.1-2.1 on the pooled index books but
0.6-1.3 on MCX, so a 35% win rate does not pay the costs. The divergence
labels look far better on the chart than they can be traded: entered where they are drawn (hindsight) the index trades
made +Rs 12.0 lakh (design); entered when they are actually known, 5 candles later, -Rs 3.4 lakh. Of 328 pre-registered
cells, 4 passed every design gate and 2 survived the one holdout read: **"MO" (buy WITH the acceleration: the call when
the CCI crosses UP through +200, the put down through -200) on FINNIFTY 5m and 15m.** They are thin (negative without
their 5 best holdout trades; one instrument), so they are paper CANDIDATES for Boss's decision, not an arm. The
early-exit idea (EX) lowered both running arms' design profit in all 4 cells. Nothing goes to paper or live from this
study.

Research only: nothing in `android/**` or `.github/**` changed.

- **Plan:** `hunt/r95_cci_signals/PREREG.md`: 8 variants x 4 timeframes x 9 instruments = 288 cells, the gates, the
  twins, the walk-forward and the one holdout read. Amendment A1 (section 10, also before any data) adds two ideas
  from the formula: MO (36 cells) and the EX exit overlay (4 cells), so 328 cells in one BH family.
- **Source:** `hunt/r95_cci_signals/SOURCE.md` (Boss's Pine v6, verbatim; the divergence block as summarised).
- **Code:** `cci.py` (the indicator, bar for bar as Pine), `rules.py` (trades), `price.py` (option prices, twins),
  `describe.py` (indicator vs price, the repaint test), `overlay.py` (the EX exit overlay), `study.py` (cells, gates,
  holdout guard).
- **Tests:** `tests/test_r95.py`, 50 tests on synthetic data:
  - the CCI against a hand-written Pine reference, and the ATR / RMA, pivots, divergences and the pulse;
  - the trade rules and the twins' exits;
  - the look-ahead guard: indicator values and signals on data cut at any bar equal the full-data values; a
    divergence is never known before its pivot + 5 bars; trades on data cut at a session boundary equal the
    full-data trades, for all 9 entry variants (the 8 fade variants and MO);
  - MO's entry, stop and exit, and the EX overlay's exit rule, summary, gates and cell loop (with a fake arm and
    pricer, since the arms' trades need the data stores);
  - the results writer (`_save_split`, N4: each results file under 1 MB).
- **Run:** notes N3-N5 (`hunt/r95_cci_signals/NOTES.md`); results in `hunt/r95_cci_signals/results/`
  (`counts_design.json`, `design.json` + `design_describe.json`, `final.json` + `final_describe.json`, `FINAL_READ`).
  Data: the Drive lake copy of 21 Oct 2026 (index_1m and options_rolling_1m WEEK to 19 Oct 2026 through R89's R51
  stores, clean + stale step; MCX MONTH books GOLDM, SILVERM, CRUDEOILM, NATGASMINI through R53's loader).

## Signal source of every rule: the UNDERLYING

| rule piece | read on |
|---|---|
| CCI of hlc3 (25; variants 20 / 50), levels +-200 (variant +-100) | the underlying (index spot 1m OHLC built into 3m / 5m / 15m / 60m bars; MCX near future, close-only) |
| the Volatility Pulse (ATR14 vs its SMA20, body vs ATR) | the underlying |
| price pivots (5 / 5) and divergences | the underlying |
| entry (the next minute's open), stop (beyond the stretch's extreme), exits (CCI crosses 0 / 2R / 10 bars / flat) | the underlying |
| what is bought | the 1-ITM call on BUY, the 1-ITM put on SELL, nearest expiry, real 1-minute prints |

No rule reads an option's own chart, and no option-chain data are used.

## What the indicator measures, in plain English

**CCI** (Commodity Channel Index, Lambert 1980) compares each candle's typical price, tp = (high + low + close) / 3,
with the average tp of the last 25 candles:

`CCI = (tp - average of the last 25 tp) / (0.015 x MD)`

MD (mean deviation) is how far, on average, those 25 tp values sit from their own average, in points. So CCI is "how
many MDs today's candle is from the 25-candle average", times 66.7 (1 / 0.015).

- **What +200 means:** tp - average = 0.015 x 200 x MD = **3 MDs above the 25-candle average**. If the 25 values were
  spread like a bell curve, one MD would be about 0.8 standard deviations, so 3 MDs is about 2.4 standard deviations.
  Minus 200 is the same distance below. In points it depends on the market: if NIFTY's 5-minute tp values sit on
  average 10 points from their mean, +200 means about 30 points above it. **Measured** (design, B signals, the candle
  before a SELL): the typical price sat a median 2.7-3.3 ATR(14) above its 25-candle average on the indices (3.0-4.2 on
  MCX). In points: NIFTY 47 (3m), 64 (5m), 116 (15m), 244 (60m); BANKNIFTY 118 / 167 / 309 / 614. MD was 0.77-0.79 of
  the standard deviation in every cell (the bell-curve value is 0.80), so "+200 = about 2.4 standard deviations" holds.
- **The CCI has a ceiling.** With 25 candles it can never go beyond +-833 (n / 0.03). That happens when one candle jumps
  away from 24 identical ones. With 20 candles the limit is +-667, with 50 it is +-1,667.
- **A steady trend does not reach +200.** If price rises by the same amount every candle, CCI(25) stays at about
  **+128** forever (CCI 20: +127, CCI 50: +131). To get above +200, price must ACCELERATE: move faster than its own
  recent pace.
- **So what does the SELL signal really say?** "The CCI was at or above +200 on the last candle and is below it now."
  The acceleration has stopped. It does NOT say price has turned down. The average can catch up, or MD can widen, while
  price keeps rising. Worked example (in the tests): price climbs 3 points a candle for 8 candles, then keeps climbing
  at 1.5 a candle. The SELL fires while price is still rising, and price rises another 15 points over the next 10
  candles. It is a mean-reversion bet that the stretch will relax, made at the moment the stretch starts to relax.
  BUY is the mirror image.
- **The Volatility Pulse filter** (off by default) lets a signal through only if (a) ATR(14) is above its own 20-candle
  average, so volatility is expanding, and (b) the signal candle's body |close - open| is at least 0.25 x ATR. It does
  not check the body's colour: a SELL can pass on a green candle.
- **The divergences** look at confirmed price pivots: a candle whose low is below the 5 candles on each side (or whose
  high is above them).
  - **BULL:** the new pivot low is below the previous pivot low, the CCI at the new low is higher than at the old one,
    and one of the two CCI values is at or below -200. BEAR is the mirror image.
  - In plain words: price made a lower low, but with less stretch than the last time.

## WARNING: the divergence labels repaint in time

The label is drawn AT the pivot candle, but nobody can know it is a pivot until 5 more candles have closed after it.

- On a chart the labels look perfectly placed at the bottoms and tops. That is hindsight.
- Any "win rate" read from the chart's labels assumes you bought at the pivot candle, which no live trader can do.
- On 5-minute candles the true entry comes 25 minutes later; on 60-minute candles it comes 5 hours later, usually the
  next day. By then price has already moved away from the pivot, by definition, because the next 5 candles did not
  make a new low.
- R95 measures this inflation directly (`describe.py`, `repaint`). Every divergence is entered twice: at the label
  candle (hindsight, what the chart shows) and at the confirmation candle (what is really possible). Both are priced
  on the real option, with the same exit.
- The BUY / SELL arrows themselves do NOT repaint. They use only closed candles: `barstate.isconfirmed`, `cci[1]` and
  the current close.

## What was measured (pre-registered)

Per cell, the design period first, then the locked holdout (1 Apr - 19 Oct 2026), read once:
- THE WIN RATE on the underlying (points, no costs) and on the option after costs;
- average win / average loss and the payoff ratio. A high win rate can still lose money when the wins are small and
  the losses big, and a CCI-zero exit tends to take small wins;
- expectancy per trade, trades a day, net, max drawdown, the worst day and the worst month (against the -Rs 13k
  monthly stop);
- the after-trigger random-entry twins (1000 per cell) and BH q-values over all 328 cells, the 4 walk-forward folds,
  and the pooled index and MCX books.

Descriptive, for the B signals:
- the price distance from the 25-candle mean at the cross (points, ATR, MD);
- signals per day by timeframe;
- forward returns and MFE / MAE over 5 / 15 / 30 / 60 candles, against every candle as a baseline;
- the divergence repaint inflation.

## Two ideas built from the formula (amendment A1, before any data)

Both use the fact above: a steady trend holds CCI(25) near +128, so crossing +-200 means price is ACCELERATING, and
crossing back inside means the acceleration has STOPPED. Their results are in the Results section.

- **Idea 1, EX: use the cross back inside as an early exit for arms that already run.** While a Liquidity or Breadth
  Donchian trade is open, the indicator's signal against it (a SELL for a call, a BUY for a put; 5m or 15m candles)
  sells the option at the next minute, if that comes before the arm's own exit.
  - It never adds a trade and can only end one earlier, so it only lowers risk.
  - Each arm trade is priced twice on the same option (its own exit and the CCI exit), so the difference is the exit
    alone. The test is whether that difference is positive and beats early exits at random minutes.
  - What to expect: a higher win rate (more trades closed while green) but smaller big winners. The money, not the
    win rate, decides.
- **Idea 2, MO: trade WITH the acceleration.** Buy the call when the CCI crosses UP through +200 (the put when it
  crosses down through -200). The stop goes beyond the signal candle, and the exit comes when the CCI is back inside the
  level, i.e. the original indicator's own signal. Base settings, 4 timeframes, 9 instruments.
  - It is still a direction bet from candles, which R85-R88 found does not work. The prior was a fail.

## Results (design 20 Nov 2024 - 31 Mar 2026, MCX from 5 Aug 2025; holdout 1 Apr - 19 Oct 2026, read once)

All rules signal on the UNDERLYING (index spot; MCX near future) and buy the 1-ITM option on real 1-minute prints, 1 lot
in force, with the app's slippage model and dated charges (MCX: R53 charges + R85 strict half-spread).
- "Underlying win" = the trade's underlying points > 0, before any cost.
- "Option win" = the option's P&L after all costs > 0. Payoff = |average option win / average option loss|.
- p_emp = the share of 1000 random after-trigger entries (same side, stop and holding time) that did as well; BH q
  over all 328 cells.

### 1. How often it fires, and what price does next (descriptive, B signals, design)

- Signals per session (all hours): indices 3.2-3.4 on 3m, 2.0-2.2 on 5m, 0.71-0.75 on 15m, 0.15-0.21 on 60m; MCX (long
  sessions) 7.8-8.0 / 4.9-5.0 / 1.6-1.9 / 0.33-0.43. 76-97% fall inside the entry window.
- **No direction edge.** 15 candles after the entry, price is higher after 38-51% of BUY signals on 3m / 5m / 15m
  index candles (lower after 40-54% of SELL signals). After any candle taken at random it is 48-52%. On 60m there are
  only 24-38 BUY signals per cell, and the share swings both ways (35-72%). MCX shows the same picture.
- So the indicator measures a stretch accurately, but its cross back inside does not tell which way price goes next.
- Full numbers (forward returns and MFE / MAE over 5, 15, 30, 60 candles, in points and ATR, continuous and within the
  session, against the baseline): `results/design_describe.json` and `final_describe.json`.

### 2. The win rate, per instrument and timeframe

Design | holdout. The underlying columns include the trades whose option never printed; the option columns do not.

#### B (the indicator as given: CCI 25, +-200 cross back inside, exit when the CCI crosses 0)

| instrument | tf | design: und trades | und win | und pts/trade | option trades | option win | payoff | option net Rs | p_emp | holdout: und trades | und win | und pts/trade | option trades | option win | payoff | option net Rs | p_emp |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 3m | 814 | 34% | -0.64 | 814 | 36% | 1.331 | -130,025 | 0.91808 | 341 | 36% | 0.59 | 341 | 36% | 1.606 | -14,837 | 0.24076 |
| BANKNIFTY | 3m | 806 | 36% | 0.0 | 806 | 37% | 1.142 | -146,090 | 0.998 | 336 | 34% | -1.0 | 336 | 35% | 1.356 | -53,259 | 0.92607 |
| SENSEX | 3m | 810 | 35% | 0.95 | 810 | 35% | 1.398 | -89,235 | 0.87712 | 336 | 33% | 2.79 | 336 | 33% | 1.898 | -7,854 | 0.28072 |
| FINNIFTY | 3m | 821 | 33% | -1.21 | 803 | 33% | 1.378 | -130,850 | 0.88911 | 333 | 33% | -0.01 | 281 | 28% | 1.301 | -76,520 | 0.98202 |
| MIDCPNIFTY | 3m | 840 | 32% | -0.12 | 839 | 34% | 1.328 | -204,360 | 0.97602 | 329 | 35% | 0.24 | 324 | 34% | 1.44 | -48,680 | 0.60739 |
| GOLDM | 3m | 586 | 34% | 15.82 | 231 | 21% | 0.686 | -141,382 | 1.0 | 512 | 35% | 17.99 | 451 | 27% | 0.8 | -209,138 | 1.0 |
| SILVERM | 3m | 577 | 38% | 100.28 | 238 | 25% | 0.515 | -189,267 | 1.0 | 511 | 36% | 41.62 | 340 | 24% | 0.773 | -218,065 | 1.0 |
| CRUDEOIL (mini) | 3m | 583 | 37% | 1.76 | 534 | 11% | 0.893 | -43,241 | 0.94006 | 505 | 31% | 1.13 | 499 | 20% | 0.652 | -56,726 | 0.999 |
| NATURALGAS (mini) | 3m | 589 | 38% | 0.11 | 511 | 19% | 0.638 | -66,432 | 0.99401 | 510 | 39% | 0.04 | 501 | 14% | 0.447 | -56,469 | 1.0 |
| NIFTY | 5m | 572 | 35% | 0.28 | 572 | 36% | 1.598 | -46,804 | 0.51948 | 227 | 34% | -1.06 | 227 | 35% | 1.398 | -34,965 | 0.88012 |
| BANKNIFTY | 5m | 541 | 33% | -3.02 | 541 | 33% | 1.551 | -91,952 | 0.95604 | 246 | 39% | 5.01 | 246 | 37% | 1.519 | -16,214 | 0.73826 |
| SENSEX | 5m | 559 | 35% | 4.69 | 559 | 35% | 1.658 | -37,378 | 0.38062 | 231 | 33% | -4.9 | 231 | 33% | 1.64 | -27,083 | 0.81119 |
| FINNIFTY | 5m | 531 | 30% | -5.49 | 521 | 31% | 1.389 | -143,350 | 0.996 | 239 | 34% | -1.22 | 200 | 30% | 1.747 | -29,538 | 0.51249 |
| MIDCPNIFTY | 5m | 575 | 34% | -0.3 | 573 | 34% | 1.416 | -153,179 | 0.87113 | 220 | 35% | -0.5 | 218 | 39% | 1.276 | -32,018 | 0.3007 |
| GOLDM | 5m | 557 | 39% | 20.53 | 212 | 22% | 0.555 | -175,487 | 1.0 | 488 | 39% | 27.26 | 429 | 34% | 1.015 | -149,266 | 0.78821 |
| SILVERM | 5m | 527 | 40% | 43.16 | 203 | 26% | 0.569 | -195,052 | 1.0 | 491 | 37% | 68.77 | 322 | 27% | 0.978 | -201,008 | 1.0 |
| CRUDEOIL (mini) | 5m | 543 | 36% | -0.05 | 497 | 19% | 0.509 | -43,776 | 0.996 | 464 | 33% | 2.99 | 454 | 26% | 0.839 | -46,865 | 0.54446 |
| NATURALGAS (mini) | 5m | 550 | 36% | 0.11 | 503 | 23% | 0.761 | -69,094 | 0.995 | 474 | 37% | 0.02 | 468 | 22% | 0.533 | -54,129 | 1.0 |
| NIFTY | 15m | 228 | 33% | 0.01 | 228 | 34% | 1.925 | +245 | 0.02797 | 105 | 31% | 4.03 | 105 | 31% | 2.424 | +12,407 | 0.22478 |
| BANKNIFTY | 15m | 226 | 35% | 0.26 | 226 | 35% | 1.733 | -12,949 | 0.2997 | 96 | 42% | 22.31 | 96 | 41% | 1.937 | +34,945 | 0.02398 |
| SENSEX | 15m | 231 | 35% | -5.21 | 231 | 32% | 1.898 | -22,301 | 0.28272 | 103 | 33% | 12.24 | 103 | 33% | 2.354 | +16,881 | 0.20579 |
| FINNIFTY | 15m | 237 | 37% | -0.78 | 231 | 34% | 1.837 | -15,786 | 0.17782 | 98 | 35% | 14.03 | 77 | 32% | 2.233 | +5,657 | 0.43756 |
| MIDCPNIFTY | 15m | 240 | 38% | 1.97 | 240 | 37% | 1.604 | -20,700 | 0.1958 | 97 | 36% | 0.4 | 96 | 35% | 1.779 | -3,150 | 0.27473 |
| GOLDM | 15m | 260 | 35% | -45.54 | 112 | 23% | 0.545 | -115,009 | 1.0 | 216 | 39% | 37.58 | 192 | 33% | 1.244 | -82,590 | 0.67632 |
| SILVERM | 15m | 264 | 36% | -105.57 | 103 | 21% | 0.587 | -148,242 | 1.0 | 205 | 37% | 131.5 | 149 | 34% | 1.22 | -71,738 | 0.36763 |
| CRUDEOIL (mini) | 15m | 222 | 34% | 2.38 | 210 | 25% | 0.813 | -19,057 | 0.86414 | 193 | 32% | 5.41 | 188 | 27% | 1.006 | -25,905 | 0.64136 |
| NATURALGAS (mini) | 15m | 256 | 32% | 0.38 | 245 | 27% | 1.099 | -37,137 | 0.95005 | 206 | 38% | 0.3 | 205 | 32% | 1.015 | -17,412 | 0.35365 |
| NIFTY | 60m | 49 | 51% | 21.08 | 49 | 43% | 2.04 | +23,582 | 0.03197 | 16 | 50% | -6.67 | 16 | 44% | 0.578 | -11,895 | 0.76224 |
| BANKNIFTY | 60m | 48 | 50% | 58.83 | 48 | 44% | 3.212 | +51,359 | 0.004 | 17 | 53% | 12.38 | 17 | 47% | 1.065 | -1,014 | 0.40859 |
| SENSEX | 60m | 49 | 45% | 67.69 | 49 | 39% | 2.515 | +22,347 | 0.12787 | 19 | 32% | -44.07 | 19 | 32% | 0.916 | -14,108 | 0.97802 |
| FINNIFTY | 60m | 40 | 40% | 10.38 | 38 | 37% | 1.897 | +4,450 | 0.03996 | 14 | 36% | 6.93 | 13 | 46% | 2.237 | +8,656 | 0.02298 |
| MIDCPNIFTY | 60m | 60 | 32% | -8.96 | 60 | 28% | 1.618 | -40,214 | 0.98801 | 21 | 48% | 6.19 | 21 | 43% | 1.237 | -2,497 | 0.57243 |
| GOLDM | 60m | 56 | 38% | 305.57 | 29 | 28% | 0.714 | -30,266 | 0.86513 | 50 | 48% | 149.56 | 44 | 39% | 1.063 | -18,611 | 0.58342 |
| SILVERM | 60m | 54 | 33% | -258.85 | 19 | 11% | 1.004 | -51,525 | 1.0 | 46 | 35% | -484.78 | 31 | 29% | 0.809 | -50,888 | 1.0 |
| CRUDEOIL (mini) | 60m | 42 | 40% | 12.67 | 39 | 31% | 0.7 | -5,177 | 0.91608 | 38 | 37% | 16.82 | 38 | 24% | 0.689 | -9,804 | 0.65135 |
| NATURALGAS (mini) | 60m | 54 | 39% | 1.03 | 48 | 29% | 1.463 | -6,474 | 0.56643 | 38 | 39% | 0.64 | 36 | 31% | 1.909 | -1,074 | 0.07493 |

#### MO (amendment A1: buy WITH the acceleration, exit when the CCI is back inside +-200)

| instrument | tf | design: und trades | und win | und pts/trade | option trades | option win | payoff | option net Rs | p_emp | holdout: und trades | und win | und pts/trade | option trades | option win | payoff | option net Rs | p_emp |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 3m | 737 | 35% | 0.02 | 737 | 33% | 1.561 | -71,320 | 0.54246 | 303 | 35% | 0.27 | 303 | 30% | 1.798 | -28,360 | 0.64136 |
| BANKNIFTY | 3m | 728 | 40% | 2.67 | 728 | 32% | 1.745 | -46,553 | 0.03097 | 305 | 34% | 2.96 | 305 | 26% | 2.483 | -14,257 | 0.17083 |
| SENSEX | 3m | 728 | 38% | -0.02 | 728 | 34% | 1.534 | -51,113 | 0.37762 | 295 | 33% | -0.07 | 295 | 29% | 1.855 | -25,368 | 0.82118 |
| FINNIFTY | 3m | 733 | 39% | 1.22 | 726 | 35% | 1.613 | -29,490 | 0.001 | 295 | 32% | 0.51 | 274 | 33% | 2.139 | +4,030 | 0.00799 |
| MIDCPNIFTY | 3m | 765 | 42% | 1.69 | 765 | 32% | 1.754 | -67,952 | 0.07792 | 297 | 40% | 0.64 | 296 | 36% | 1.694 | -6,580 | 0.001 |
| GOLDM | 3m | 583 | 33% | 12.52 | 193 | 16% | 0.656 | -115,716 | 1.0 | 507 | 31% | 2.43 | 462 | 19% | 1.513 | -157,182 | 0.95704 |
| SILVERM | 3m | 574 | 36% | 56.9 | 208 | 15% | 1.353 | -135,821 | 0.99301 | 509 | 30% | -0.3 | 378 | 16% | 1.884 | -172,216 | 1.0 |
| CRUDEOIL (mini) | 3m | 582 | 30% | -0.18 | 561 | 8% | 1.586 | -41,862 | 0.82817 | 502 | 39% | 2.88 | 499 | 16% | 1.14 | -45,210 | 0.37263 |
| NATURALGAS (mini) | 3m | 588 | 33% | 0.07 | 547 | 12% | 0.905 | -69,032 | 0.997 | 509 | 29% | 0.02 | 500 | 9% | 0.899 | -53,146 | 0.999 |
| NIFTY | 5m | 457 | 40% | 2.32 | 457 | 34% | 1.93 | -2,000 | 0.01099 | 157 | 48% | 5.57 | 157 | 43% | 1.692 | +17,855 | 0.02098 |
| BANKNIFTY | 5m | 441 | 41% | 10.58 | 441 | 37% | 2.023 | +30,695 | 0.001 | 181 | 44% | 15.82 | 181 | 39% | 2.202 | +30,180 | 0.004 |
| SENSEX | 5m | 443 | 39% | 7.36 | 443 | 35% | 1.872 | +4,605 | 0.005 | 164 | 44% | 13.47 | 164 | 41% | 1.781 | +13,747 | 0.05295 |
| FINNIFTY | 5m | 410 | 47% | 7.54 | 407 | 43% | 1.837 | +59,212 | 0.001 | 168 | 33% | 2.66 | 158 | 32% | 2.977 | +23,387 | 0.002 |
| MIDCPNIFTY | 5m | 454 | 37% | 0.82 | 454 | 31% | 1.519 | -92,570 | 0.73926 | 168 | 42% | 2.36 | 166 | 41% | 1.435 | -356 | 0.04695 |
| GOLDM | 5m | 526 | 34% | 3.24 | 175 | 18% | 1.068 | -86,635 | 0.998 | 464 | 34% | 11.29 | 442 | 24% | 1.567 | -127,378 | 0.47852 |
| SILVERM | 5m | 502 | 38% | 61.04 | 194 | 26% | 0.979 | -89,534 | 0.98102 | 467 | 34% | 24.52 | 370 | 26% | 1.505 | -139,041 | 0.82418 |
| CRUDEOIL (mini) | 5m | 529 | 30% | -0.09 | 527 | 12% | 1.032 | -49,549 | 1.0 | 441 | 37% | 0.81 | 438 | 18% | 1.053 | -42,680 | 0.71828 |
| NATURALGAS (mini) | 5m | 530 | 35% | -0.01 | 510 | 15% | 1.139 | -63,156 | 0.996 | 456 | 32% | 0.02 | 450 | 12% | 1.221 | -45,917 | 0.94905 |
| NIFTY | 15m | 212 | 38% | 3.75 | 212 | 37% | 1.672 | -1,260 | 0.23876 | 97 | 44% | -0.41 | 97 | 33% | 1.223 | -27,657 | 0.90909 |
| BANKNIFTY | 15m | 208 | 50% | 14.37 | 208 | 41% | 1.689 | +29,890 | 0.00999 | 93 | 37% | -5.42 | 93 | 29% | 1.831 | -20,882 | 0.69331 |
| SENSEX | 15m | 214 | 41% | 10.61 | 214 | 38% | 1.6 | -3,659 | 0.33766 | 92 | 41% | 0.71 | 92 | 36% | 1.373 | -12,262 | 0.58941 |
| FINNIFTY | 15m | 219 | 49% | 14.66 | 216 | 47% | 2.062 | +113,428 | 0.001 | 93 | 42% | -2.1 | 76 | 39% | 2.0 | +14,980 | 0.01798 |
| MIDCPNIFTY | 15m | 218 | 43% | 4.4 | 218 | 39% | 1.832 | +34,142 | 0.08392 | 89 | 47% | 2.45 | 89 | 42% | 1.368 | -2,093 | 0.14785 |
| GOLDM | 15m | 265 | 41% | 41.23 | 85 | 25% | 0.502 | -70,310 | 1.0 | 208 | 37% | 21.35 | 201 | 25% | 1.829 | -70,080 | 0.73526 |
| SILVERM | 15m | 275 | 34% | -153.13 | 120 | 24% | 1.068 | -98,160 | 1.0 | 205 | 36% | 106.0 | 166 | 30% | 0.988 | -118,391 | 0.98302 |
| CRUDEOIL (mini) | 15m | 229 | 38% | 5.92 | 228 | 18% | 1.185 | -18,257 | 0.84715 | 197 | 38% | 6.09 | 194 | 27% | 0.924 | -20,438 | 0.61638 |
| NATURALGAS (mini) | 15m | 260 | 39% | 1.02 | 257 | 25% | 1.951 | -16,781 | 0.13886 | 206 | 37% | -0.0 | 205 | 17% | 1.402 | -22,260 | 0.78921 |
| NIFTY | 60m | 51 | 51% | 1.66 | 51 | 41% | 1.138 | -15,666 | 0.5994 | 16 | 56% | 25.31 | 16 | 38% | 1.827 | +1,025 | 0.1998 |
| BANKNIFTY | 60m | 54 | 52% | -9.14 | 54 | 46% | 0.715 | -24,472 | 0.58442 | 16 | 38% | -3.92 | 16 | 31% | 1.464 | -7,539 | 0.81518 |
| SENSEX | 60m | 48 | 52% | 43.71 | 48 | 48% | 1.326 | +10,378 | 0.25275 | 18 | 50% | 13.66 | 18 | 22% | 2.162 | -5,245 | 0.54446 |
| FINNIFTY | 60m | 47 | 38% | -10.44 | 46 | 33% | 1.358 | -18,817 | 0.52348 | 14 | 71% | 27.49 | 12 | 50% | 1.888 | +8,134 | 0.3027 |
| MIDCPNIFTY | 60m | 60 | 42% | -1.51 | 60 | 35% | 1.274 | -31,188 | 0.92008 | 22 | 45% | 2.83 | 22 | 36% | 1.826 | +1,239 | 0.42657 |
| GOLDM | 60m | 56 | 54% | 107.38 | 14 | 36% | 0.236 | -21,592 | 0.98302 | 54 | 48% | 26.26 | 50 | 36% | 1.258 | -18,013 | 0.72028 |
| SILVERM | 60m | 58 | 41% | 7.76 | 20 | 35% | 1.503 | -3,397 | 0.35764 | 52 | 37% | 11.77 | 43 | 26% | 1.316 | -35,915 | 0.54046 |
| CRUDEOIL (mini) | 60m | 49 | 47% | 0.67 | 47 | 32% | 1.051 | -2,691 | 0.23876 | 44 | 43% | 3.95 | 44 | 27% | 1.282 | -4,277 | 0.22478 |
| NATURALGAS (mini) | 60m | 57 | 61% | 1.74 | 56 | 43% | 1.698 | +3,467 | 0.07992 | 42 | 38% | -0.05 | 42 | 26% | 1.115 | -5,498 | 0.78422 |

Pooled (1 lot, after costs):

| book | design: option trades | option win | payoff | net Rs | holdout: option trades | option win | payoff | net Rs |
|---|---|---|---|---|---|---|---|---|
| indices B 3m | 4,072 | 35% | 1.31 | -7,00,560 | 1,618 | 34% | 1.52 | -2,01,150 |
| indices B 5m | 2,766 | 34% | 1.52 | -4,72,663 | 1,122 | 35% | 1.51 | -1,39,818 |
| indices B 15m | 1,156 | 35% | 1.80 | -71,491 | 477 | 35% | 2.13 | +66,740 |
| indices B 60m | 244 | 38% | 2.03 | +61,524 | 86 | 42% | 1.12 | -20,858 |
| MCX B 3m | 1,514 | 18% | 0.78 | -4,40,322 | 1,791 | 21% | 0.95 | -5,40,398 |
| MCX B 5m | 1,415 | 22% | 0.65 | -4,83,409 | 1,673 | 27% | 1.07 | -4,51,268 |
| MCX B 15m | 670 | 25% | 0.63 | -3,19,445 | 734 | 31% | 1.29 | -1,97,645 |
| MCX B 60m | 135 | 27% | 0.67 | -93,442 | 149 | 31% | 1.05 | -80,377 |
| indices MO 3m | 3,684 | 33% | 1.64 | -2,66,428 | 1,473 | 31% | 1.97 | -70,535 |
| indices MO 5m | 2,202 | 36% | 1.79 | -58 | 826 | 39% | 1.93 | +84,813 |
| indices MO 15m | 1,068 | 40% | 1.77 | +1,72,541 | 447 | 36% | 1.55 | -47,914 |
| indices MO 60m | 259 | 41% | 1.13 | -79,765 | 84 | 35% | 1.84 | -2,386 |
| MCX MO (each timeframe) | 137-1,509 | 11-37% | 1.0-1.4 | -24,213 to -3,62,431 | 179-1,839 | 15-29% | 1.4-1.8 | -63,703 to -4,27,754 |

- **Underlying, before costs (pooled):** B wins 33-36% of index trades on 3m / 5m / 15m and 43-44% on 60m; 34-40% on
  MCX. Its underlying payoff is about 1.8-2.7 (60m holdout 1.2): the stop is tight and the CCI-zero exit lets winners
  run a little, so the underlying roughly breaks even before costs. The option round trip (index 1.1-3.8 points a trade)
  and time decay then make it a loss.
- **MCX is worse:** the costs are large next to the move (GOLDM 30-42 points a trade, SILVERM 94-147). In the design,
  only 35-52% of GOLDM and SILVERM trades found an option print to fill (holdout: GOLDM 88-89%, SILVERM 66-73%).
- **The other fade variants** (2R target, 10-candle exit, Volatility Pulse, divergence-only, CCI 20 / 50, +-100
  levels) all fail:
  - pooled, they lose on 3m / 5m / 15m everywhere and on all MCX timeframes;
  - only index 60m books are positive in the design (XR +Rs 85,856, PU +Rs 81,919, K100 +Rs 64,499; 112-631 trades),
    and no 60m cell passed except BANKNIFTY B / XT, which failed the holdout;
  - DV (divergence-confirmed only) has 0-52 trades a cell (26 of 36 cells under the 40-trade gate).
- Walk-forward selection (pick the best variant so far before each fold) summed -Rs 16.4 lakh against B's -Rs 21.3 lakh
  over the 36 instrument x timeframe pairs. Picking variants does not rescue it.

### 3. The divergence labels repaint: what that does to the result

Every confirmed divergence was entered twice, with the same exit (CCI back to 0, stop beyond the pivot, session flat),
on the 1-ITM option on real prints after costs:

| | entered at the label candle (what the chart shows) | entered at the confirmation, 5 candles later (what is possible) |
|---|---|---|
| indices, design | 1,687 option trades, win 60%, **+Rs 12,01,300**; underlying win 63% | 1,778 trades, win 43%, **-Rs 3,37,415**; underlying win 49% |
| indices, holdout | 674 trades, win 59%, +Rs 4,75,436; underlying win 61% | 691 trades, win 44%, -Rs 37,156; underlying win 52% |
| MCX, design | 835 trades, win 46%, -Rs 26,965; underlying win 68% | 807 trades, win 22%, -Rs 2,19,949; underlying win 53% |
| MCX, holdout | 995 trades, win 54%, +Rs 1,68,649; underlying win 69% | 1,005 trades, win 27%, -Rs 2,48,916; underlying win 52% |

- On every index timeframe the label-candle entry shows a profit and the real entry a loss. Design: 3m +Rs 4.76 lakh
  vs -Rs 1.57 lakh; 5m +Rs 3.69 lakh vs -Rs 89,426; 15m +Rs 2.64 lakh vs -Rs 85,129; 60m +Rs 92,418 (48 trades) vs
  -Rs 5,608 (12).
- On the indices the repaint adds 9-17 points of win rate (more on MCX) and turns a loss into a profit. **Do not judge this indicator by how its
  BULL / BEAR labels look on a chart.**

### 4. MO (buy WITH the acceleration): the two survivors

| cell | design trades | option win | payoff | net Rs | p_emp | q (328) | holdout trades | option win | payoff | net Rs | p_emp | q (4 passers) | holdout worst month |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| FINNIFTY 5m MO | 407 | 43% | 1.84 | +59,212 | 0.001 | 0.00016 | 158 | 32% | 2.98 | +23,387 | 0.002 | 0.011 | -2,923 |
| FINNIFTY 15m MO | 216 | 47% | 2.06 | +1,13,428 | 0.001 | 0.00016 | 76 | 39% | 2.00 | +14,980 | 0.018 | 0.032 | -9,214 |
| BANKNIFTY 60m B | 48 | 44% | 3.21 | +51,359 | 0.004 | 0.081 | 17 | 47% | 1.07 | -1,014 | 0.41 | 0.42 | fails |
| BANKNIFTY 60m XT (same entries) | 48 | 44% | 2.62 | +35,651 | 0.005 | 0.082 | 17 | 47% | 1.44 | +4,916 | 0.155 | 0.20 | fails |

- The two FINNIFTY MO cells meet the pre-registered holdout rule (net > 0, >= 20 trades, p_emp < 0.10, q < 0.10 among
  the design passers, worst month >= -Rs 13,000). They are PAPER candidates.
- **But they are thin:**
  - both lose without their 5 best holdout trades (-Rs 7,071 and -Rs 21,042);
  - they are one instrument with overlapping signals (a 15m burst is usually also a 5m burst);
  - over all 328 holdout cells their q would be 0.12 and 0.40;
  - FINNIFTY 15m MO's underlying points were negative in the holdout (-2.1 a trade). Its profit comes from the payoff
    shape (small losses cut at the signal candle, a few large bursts kept), not from calling direction.
- MO on the other indices did not pass the design (BANKNIFTY 5m MO missed only "without its top 5"). MO on MCX lost
  everywhere.

### 5. EX (the CCI cross as an early exit for the running arms)

| arm, CCI candles | design: trades changed | net change Rs | p_emp | holdout: changed | net change Rs |
|---|---|---|---|---|---|
| Liquidity (BANKNIFTY 15+5, FINNIFTY 30+5), 5m | 171 of 585 | -70,223 | 1.00 | 62 of 238 | -1,899 |
| Liquidity, 15m | 62 of 585 | -26,010 | 0.89 | 23 of 238 | +1,247 |
| Breadth Donchian (C1, 4 of 5, XL), 5m | 1,368 of 3,071 | -1,39,342 | 0.95 | 589 of 1,225 | -1,80,328 |
| Breadth Donchian, 15m | 110 of 3,071 | -15,795 | 1.00 | 51 of 1,225 | -588 |

- 0 of 4 pass. The early exit cuts the arms' big winners more than it saves on losers (Liquidity 5m: average win
  Rs 1,830 -> Rs 1,446, average loss Rs 830 -> Rs 768). The arms keep their own exits.

## Prior work, and what to expect

- **R85 / R87 / R88:** candles and 22 parameters predict the next candle's SIZE, not its DIRECTION. The CCI is a
  function of the last 25 candles' prices, so the prior is that it will not predict direction either.
- **R90 (Almazov BOS indicator, 0 of 99) and R91 (ICT sweep, 0 of 63):** indicator strategies failed after costs. The
  underlying moved too little per trade to pay the option round trip (about 1-4 points on the indices) and the time
  decay.
- **R86 / R87:** fading failed pokes beyond yesterday's high / low, round numbers and the opening range lost (6 of 6
  tests), and stop runs above the day's high were followed by more upside. A CCI +-200 cross is also a stretch fade.
- The prior was a fail. The results confirm it for the indicator as given (see Results).

## The app's Pine engine: would it run unchanged?

Checked against `android/engine/.../pine` (Pine.kt, PineInterp.kt, PineCheck.kt):
- **Mostly yes.** These are all supported: `ta.cci`, `ta.atr`, `ta.sma`, `math.abs`, `input.int / float / bool /
  source(hlc3)`, `cci[1]`, `barstate.isconfirmed`, `ta.pivotlow / ta.pivothigh`, `ta.valuewhen`, `plot`, `hline`,
  `plotshape` (shown as markers) and `alertcondition` (shown as signals). The declaration's `max_lines_count /
  max_labels_count` are accepted.
- **The same CCI.** The engine's `ta.cci` uses the same formula (the SMA and mean absolute deviation of the source).
  Its pivot rule (a tie on the left counts, a tie on the right does not) is what `cci.py` copies, so the research
  matches the phone.
- **The divergence labels would not show.** The engine accepts `label.*` / `line.*` drawings but does not draw them;
  it gives a warning instead. The BUY / SELL markers and alerts work.
- **One caveat for any live use:** the engine returns `barstate.isconfirmed = true` on every bar. An arm built on it
  must act only on closed candles, or a signal could appear and then vanish on a candle that is still forming.

## Verdict

- **What CCI measures:** how far the candle's typical price, (high + low + close) / 3, is from its own 25-candle
  average, in units of the average distance (MD). +200 = 3 MDs, about 2.4 standard deviations, about 3 ATR. A steady
  trend sits near +128, so +-200 needs acceleration, and the cross back inside means the acceleration has stopped.
- **Connection with actual price:** the levels map cleanly onto price stretch (MD / SD 0.77-0.79 everywhere). But the
  BUY / SELL cross back inside does not predict the next move: forward win rates sit at the random-candle baseline.
- **Win rate of the indicator as given (B), signal on the underlying:**
  - underlying (before costs): 33-44% pooled;
  - option after costs: 28-47% of index trades, 11-39% of MCX trades;
  - payoff: 1.1-2.1 on the pooled index books, 0.6-1.3 on MCX.
  - It loses after costs on 3m / 5m everywhere and on every MCX timeframe; index 15m and 60m flip sign between design
    and holdout. **0 of 288 fade cells pass.**
- **Divergence labels:** the chart flatters them (index design: +Rs 12.0 lakh at the label vs -Rs 3.4 lakh when the
  divergence is actually known).
- **MO:** 2 cells (FINNIFTY 5m and 15m) pass every pre-registered gate, design and holdout. They are thin (negative
  without the top 5 in the holdout, one instrument).
  - They are PAPER candidates for Boss to decide on; nothing was switched on.
  - If Boss wants it, the next step is an APP_SPEC for a PAPER-only FINNIFTY MO arm behind a switch that is off by
    default, logged in the Autopilot change timeline and undoable, with its own forward record from 20 Oct 2026. It
    never goes live by itself.
- **EX:** rejected; it lowers the Liquidity and Breadth Donchian arms' profit.
- **As a chart overlay** in the app it is fine as an honest "stretched / stopped accelerating" gauge, with the
  divergence labels ignored (the app's engine does not draw them anyway).
