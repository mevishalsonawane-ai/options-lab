# 9 Advanced and Profitable Trading Strategies - Roman Sadowski (year not stated; the text mentions 2013 trades and links to a "2016" post)

Source: sadowski-9-strategies.txt (+ .ocr.txt), 42 pages, read in full on 25 Oct 2026 (text layer for all pages; the OCR
file only adds the cover). The charts the text refers to are images and were not available; the rules below come from the
text alone.
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short marketing e-book from the HumbleTraders site, written for forex day and swing traders.
It sketches nine chart setups: stochastic reversals at S/R, a 20/60/100 MA cross, Heikin-Ashi, swing, candles, role reversal, Bollinger squeeze, narrow range, and 2-period RSI.
Most examples are EURUSD, GBPJPY or USDJPY on 4-hour or daily charts, and there are no tests or statistics.
The contents page also lists an "RSI, 5 systems + back test" chapter and a binary-options chapter, but neither appears in the text.
It ends with a "3 / 1000" money rule and a warning that most day traders lose.

## Methods, rules and setups

### 1. Momentum reversal (strategy 1)
- Entry: first set a mid-to-long-term bias from fundamentals (the book names the CFTC Commitments of Traders report).
  On the daily chart, look for a reversal zone that meets three conditions: a clear recent rally or fall, weekly and
  daily stochastic overbought (above 70) or oversold, and price at a major support/resistance zone. Then drop to intraday
  charts and wait for a reversal price pattern (not defined). Most orders are pending limit orders, often filled in the
  London session. Trade only in the direction of the fundamental bias. A counter-bias setup is either skipped or taken
  with a smaller lot.
- Stop: not stated.
- Target / exit: not stated. Trades are held "up to several weeks".
- Filters: COT bias; weekly and daily stochastic (settings not stated here; strategy 3 uses 14,7,3); Fibonacci
  retracements and S/R zones.
- Market and timeframe written for: major USD forex pairs; daily setup with intraday entry; 1-5 signals a month.
- Signal source: underlying price (stochastic, S/R) plus a fundamental/COT bias (discretionary). Maps to: NSE index
  options only in part (India has no COT report; the FII/DII or participant-wise OI files would be a loose stand-in);
  MCX futures yes in principle (COT exists for COMEX gold/silver/crude); crypto partly (CME COT for BTC). A crypto
  version would need a stop and a profit lock.
- Mechanical? no. The fundamental bias, the "major" S/R zone and the intraday reversal pattern are all judgement calls.
- Our research: we have not tested a weekly-plus-daily stochastic and S/R reversal. Intraday stochastic (2 rules) was
  inside HUNT_H25's "Other indicators (25)" family: 27,000 variants, 0 of 50 walk-forward families positive, and no
  family passed. S/R zones as levels were no better than random lines (HUNT_R20B_SR_RANGE: in 6 of the 7 variants the
  random levels made more). Fibonacci levels attracted price no more than random levels with the same spacing
  (HUNT_R4_GANN_FIB_MAGIC).

### 2. Moving-average crossover, 20/60/100 (strategy 2)
- Entry: buy when the 20-period MA crosses above the 60-period MA, sell when it crosses below. MA type is not stated
  (most likely simple).
- Stop: not stated. The position is closed on the opposite cross (always in the market).
- Target / exit: the opposite cross. The 100-period MA is a "trend" read only: if bars stay consistently on one side of
  it, the trend is strong and the trade is left to run.
- Filters: none stated beyond the 100 MA as context. Shorter settings are said to give more false signals.
- Market and timeframe written for: EURUSD daily (example over six months).
- Signal source: underlying price. Maps to: NIFTY/BANKNIFTY (daily or intraday bars, buy CE/PE); MCX futures yes; crypto
  yes, but only with an added stop and a profit lock.
- Mechanical? yes (except the vague "stay consistently above the 100").
- Our research: crosses are a well-tested loser for option buying. OBUY_FINAL row 59, TI-03 EMA cross: 96 variants,
  walk-forward -Rs 3,69,856 (-369,856) over 2022-2026, 1 of 5 years positive. HUNT_H25 tested SMA/EMA crosses 5/13 to
  50/200 on 6 timeframes and 5 indices: 0 of 28 EMA families and 1 of 28 SMA families positive walk-forward, no family
  passed. On daily bars, SWING.md's EMA 20/50 was in the 42-52% coin-flip band (FINDINGS.md, SWING bullet). The exact
  20/60/100 setting has not been tested by us.

### 3. Heikin-Ashi reversal with stochastic and Accelerator filters (strategy 3)
- Entry: on daily Heikin-Ashi (HA) candles, two consecutive completed candles of the new colour after a run of the other
  colour mark a reversal: two red after greens is a short setup, two green after reds a long setup. The stochastic
  filter (14,7,3) should confirm overbought/oversold. The book's own sentence here is self-contradictory: it says go long
  after two RED candles with stochastic above 70, and short after two GREEN with stochastic below 30. That is the
  opposite of its setup rules, and most likely the labels are swapped. The literal reading we would test: long on two
  green HA candles after reds with the stochastic recently below 30, short on two red after greens with it recently
  above 70. Entry is by a buy-stop "a few pips" above the high of the second reversal candle (sell-stop below its low).
  An optional Accelerator Oscillator (AC) filter: for longs, wait for a green AC bar above 0. For shorts the book again
  says "a red bar above the 0 line", which is most likely meant to be below 0.
- Stop: 100 pips flat, or a local technical level. Move to breakeven after +50 pips. Then trail the stop to major local
  lows/highs, or exit on an opposite signal.
- Target / exit: none fixed ("let your winners run"). Exit on the opposite signal or the trailed stop.
- Filters: skip bank holidays, NFP, FOMC and central-bank speeches. Do not enter right after a violent one-way swing.
- Market and timeframe written for: forex (GBPJPY, EURUSD examples), daily candles. Few setups.
- Signal source: underlying price. Maps to: NIFTY/BANKNIFTY daily or intraday HA on the index, buy CE/PE; MCX futures
  yes; crypto yes (the book's 100-pip stop plus breakeven move would give the required stop and lock).
- Mechanical? mostly (the stochastic condition and "violent swing" need a precise reading).
- Our research: Heikin-Ashi lost every time we tried it. HUNT_R71_CHART_PATTERNS P6 Heikin-Ashi flip in trend (15-min
  HA, EMA 20 filter, index stop): -Rs 79,173 on 357 trades in the final period (1 Apr - 13 Oct 2026), 44% winners, fail.
  OBUY_FINAL row 44, TI-08 Heikin-Ashi: 48 variants, walk-forward -Rs 1,70,762 (-170,762), 2 of 5 years positive.
  HUNT_H25 included Heikin-Ashi in its "Other indicators" family (0 of 50). The daily HA version with stochastic and AC
  has not been tested by us. The Accelerator Oscillator is not in any of our studies.

### 4. Swing trading out of a correction (strategy 4)
- Entry: in an established trend, wait for a correction. The book defines a correction as many overlapping bars in a
  contracting range (its example: 26 four-hour candles within about 100 points, contracting to a day of only 20
  points). Short version: sell when the first candle closes/moves below the contracting range of the last few
  candles. Long version: after a pullback with "slowing momentum", wait for two candles to close above the upper edge
  of the correction, then buy the open of the next candle.
- Stop: the most recent minor swing high (short), or the lowest point of the correction (long). Example risk about 30
  points against a gain of about 600.
- Target / exit: not stated ("ride it").
- Filters: trend direction (the trend "started the previous week").
- Market and timeframe written for: EURUSD 4-hour.
- Signal source: underlying price. Maps to: NIFTY/BANKNIFTY (15-min or 60-min bars), MCX futures, and crypto (with a
  profit lock added).
- Mechanical? partly. "Overlap", "contracting" and "the trend" need definitions. We would define overlap as N bars inside
  an X-ATR box, and the trend as price on one side of a 50 EMA.
- Our research: closest are HUNT_R71_CHART_PATTERNS P3 squeeze release (-Rs 41,281 on 305 trades in the final period,
  and 67% of its trades were the same trade Donchian takes) and P8 swing structure (higher high + higher low, break of
  the last swing high: -Rs 34,242 on 372 trades in the final, twin p 0.99). The plain Donchian 20 breakout, which is
  what a contraction break mostly is, is our paper arm (R71 table: Rs 55,439 on 1,787 trades over the same final
  period). The exact overlap-box-in-trend rule has not been tested by us.

### 5. Candlestick patterns: engulfing, long shadow, hammer (strategy 5)
- Entry: wait for the pattern candle to close and enter at the open of the next candle. Bullish engulfing: the real
  body covers the body of one or more previous candles; the more candles it covers, the stronger it is said to be.
  Bearish engulfing is the mirror. Long shadow: a wick at least twice the real body, said to form at turning points.
  Hammer: the body sits at one end of the candle with a long "handle".
- Stop: at the extreme of the pattern candle (the engulfing candle's low/high, the end of the shadow or hammer). For
  shadows and hammers it is then trailed with the trend.
- Target / exit: not stated beyond the trail.
- Filters: none stated (no trend or location filter).
- Market and timeframe written for: forex, timeframe not stated.
- Signal source: underlying price. Maps to: NIFTY/BANKNIFTY, MCX, crypto (stop given; lock to be added).
- Mechanical? yes ("covers N candles" and "2x body" are precise enough).
- Our research: already rejected. HUNT_R87_CANDLE_ANATOMY: over about 1,033 sessions per index, a candle's shape alone
  does not tell the next move; 92% of 234 shape cells sat between 45% and 55% up. Hammers mostly marked continuation:
  NIFTY 15m fell a further 0.47 ATR over 20 candles. A long wick is not a defended level: a big tail's low was broken
  within 10 candles 59% of the time (n 3,314). HUNT_H25: all 61 TA-Lib patterns, 65,880 variants, 6 of 122 families
  walk-forward positive, none passed. On MCX, HUNT_R85_MCX_CANDLES found no reliable engulfing edge. SWING_DEEP's
  trend-pullback with hammer/engulfing on stocks was about flat to negative (top-50 large caps, fixed 5d: 1283 trades,
  45% winners).

### 6. Role reversal at support/resistance (strategy 6)
- Entry: in an uptrend (higher highs and higher lows), a broken resistance becomes support. Buy the pullback to it,
  treating the level as a zone. The book suggests scaling in with three equal limit orders: 1/3 at the 20 MA, 1/3 at the
  role-reversal level, 1/3 at the 50% Fibonacci retracement. (It also mentions 38% as a possible pullback depth.) In a
  downtrend, the mirror: short the retest of broken support. Pivot points are named as a confirming tool.
- Stop: not stated ("risk management must be applied").
- Target / exit: not stated (the next higher high is implied).
- Filters: trend by swing structure.
- Market and timeframe written for: forex, any timeframe.
- Signal source: underlying price. Maps to: NIFTY/BANKNIFTY (5/15-min), MCX, crypto (stop and lock to be added).
- Mechanical? partly. "Zone", trend and level choice are judgement. A literal version: last broken 15-min swing high,
  limit orders at EMA 20 / level / 50% of the last up-leg.
- Our research: break-and-retest of levels lost. OBUY_FINAL row 37, LV-01 PDH/PDL break-retest: 64 variants, walk-forward
  -Rs 1,29,689 (-129,689), 1 of 5 years positive. HUNT_R20C_SR_BREAK: after a 2-close break, price was further in the
  break's direction at 15:29 in 51% of 7,548 breaks (a coin toss), and random levels did almost as well. HUNT_R20B: real
  levels were no better than random ones. HUNT_R4: Fibonacci levels were no better than random. The 3-part scale-in
  has not been tested by us.

### 7. Bollinger band squeeze (strategy 7)
- Entry: Bollinger(20, 2) with the band-width indicator on. When the bands contract and the width approaches 0.0100
  (about 100 points on EURUSD 4-hour), buy when a full candle closes above the middle SMA, and sell when one closes below
  it.
- Stop: the high/low of the preceding candle, or the stop that limits the loss to 3% of capital, whichever is smaller.
- Target / exit: not stated.
- Filters: the width threshold (absolute, so pair-specific).
- Market and timeframe written for: EURUSD 4-hour.
- Signal source: underlying price. Maps to: NIFTY/BANKNIFTY (with a width threshold redefined as a percentile, since 100
  points means nothing on the index), MCX, crypto (stop given; lock to be added).
- Mechanical? yes, once the width threshold is turned into a percentile.
- Our research: rejected. HUNT_R71_CHART_PATTERNS P3 (Bollinger 20,2 inside Keltner for 6+ bars, then a close outside
  the band): -Rs 41,281 on 305 trades in the final, twin p 0.91, and 67% of its trades were already Donchian's. ADVANCED.md
  (FINDINGS.md, ADVANCED bullet): a TTM squeeze filter removed the big-candle pullback edge. OBUY_FINAL row 30, MR-02
  Bollinger alert: walk-forward -Rs 90,790 (-90,790). MCX (HUNT_R96_SUMMARY round 1): squeeze release 1-ITM direction
  -Rs 16,521 on 7 trades. Note that Sadowski's trigger (a close beyond the MIDDLE line) differs from ours (a close beyond
  a band). That exact trigger has not been tested by us.

### 8. Narrow range, NR4 / NR7 (strategy 8)
- Entry: find the narrowest-range daily candle of the last 4 or 7 days, ideally "chubby" (open and close near the
  extremes). Two or more narrow candles together are said to lead to a bigger break. Buy when price moves above its
  high, sell when it moves below its low.
- Stop: the opposite end of the narrow candle, then trailed "to suit".
- Target / exit: not stated.
- Filters: none.
- Market and timeframe written for: daily bars, any market.
- Signal source: underlying price. Maps to: NIFTY/BANKNIFTY (daily NR7 then intraday break), MCX futures (our most-tested
  version), crypto (stop given; lock to be added).
- Mechanical? yes.
- Our research: tested many times, mostly negative. Intraday 15-min NR7 on the index: HUNT_R71_CHART_PATTERNS P2,
  -Rs 1,68,820 on 1,341 trades in the final period. Daily NR7/inside day: OBUY_FINAL row 12, LV-04, 72 variants,
  walk-forward -Rs 28,141, 1 of 5 years positive. MCX: HUNT_R96_SUMMARY, 0 of 46 ideas passed. The crude NR7 breakout is
  the one unproven lead (R4G cheap-IV: +Rs 49,381 on 28), but it failed its fresh test on COPPER (-Rs 84,978 on 13
  trades).

### 9. Two-period RSI momentum (strategy 9)
- Entry: buy when RSI(2) moves above 90, sell when it moves below 10. Note this is the MOMENTUM reading, the opposite of
  the well-known Connors mean-reversion use (buy below 10). Add another position on each extra trigger in the same
  direction while trending.
- Stop: "rigorous" but not specified.
- Target / exit: close all positions on the opposite signal (long ones when RSI(2) falls below 10).
- Filters: none. The book warns it whipsaws in ranges.
- Market and timeframe written for: not stated (forex chart).
- Signal source: underlying price. Maps to: NIFTY/BANKNIFTY, MCX, crypto (stop and lock must be added).
- Mechanical? yes, apart from the stop.
- Our research: the follow reading lost on gold. GOLD_SCALP.md (XAUUSD 1-min, Oct 2023 - Sep 2026): "M5 rsi2 follow,
  target 1 / stop 1.5 ATR, buys only" totalled -234,718 over 6629 trades, and every rsi2 follow and revert row was
  negative. On the index the opposite (Connors) reading was tested: SWING_EXITS.md, NIFTY bought monthly ATM, ">200DMA &
  RSI(2)<10", stayed inside the coin band; COMBINED.md found RSI(2) hurts the big-candle pullback. The RSI(2)>90
  momentum entry with pyramiding has not been tested by us on NIFTY/BANKNIFTY.

### Not present in the text
The contents page lists "RSI Trading Strategy, 5 Systems + Back Test Results" and "Binary options trading strategy that
generates 150% return". Neither chapter is in the 42 pages, so nothing about them can be reported.

## Performance claims
- Momentum reversal: "traded live for the last 15 months" with a documented performance section. That section is not in
  the book: no evidence given.
- MA crossover: four winning signals on EURUSD daily over six months made 600, 200, 200 and 100 points. Of two false
  signals, one broke even and one lost 35 points. The system is called correct "6/12 or 50%" of the time. This is one
  hand-picked chart with no costs.
- Heikin-Ashi: said to be "proven" in back test and live trading, with no numbers. Three example trades: 22 May 2013
  long +300, 21 June 2013 short +130, 31 October 2013 short +170 (pips). These are hand-picked, with no losing examples.
- Swing: one example with about 30 points of risk and about 600 of gain.
- Generic: using a strategy "can cut your losses by 63% immediately" (no source); over 93% of day traders lose; about
  4.5% succeed (an unattributed quote). No evidence given for any of these.
- Contents title: a binary-options strategy with a "150%" return. The chapter is not in the book.

## Risk, sizing and psychology
- The "3 / 1000" rule: never risk more than 3% of capital on one trade, and never more than one-thousandth of capital
  per point.
- Bollinger: the stop is the smaller of the prior candle's extreme or a 3% capital loss.
- Heikin-Ashi: a 100-pip flat stop, breakeven after +50 pips, trail to local swings, "let winners run".
- Counter-trend setups (against the fundamental bias) get a smaller lot.
- Psychology: a fixed rule set removes emotion and over-trading. Failures come from untested strategies, poor discipline
  and missing stops. A story about a $1,000 account wiped out by a 200-point move against a $5/point position.

## How it relates to our research
- Already rejected by us: MA crosses (OBUY_FINAL TI-03; HUNT_H25), Heikin-Ashi (HUNT_R71 P6; OBUY_FINAL TI-08), candle
  patterns (HUNT_R87; HUNT_H25; HUNT_R85 for MCX), break-and-retest of S/R (OBUY_FINAL LV-01; HUNT_R20C; HUNT_R20B),
  Fibonacci levels (HUNT_R4), Bollinger squeeze (HUNT_R71 P3; HUNT_R96 round 1), intraday and daily NR7 on the index
  (HUNT_R71 P2; OBUY_FINAL LV-04). Every one of these lost after option costs, and most were no better than random
  entries on the same days.
- Agree in part: the book says squeeze and NR setups predict that a big move is coming. That fits FINDINGS.md: the SIZE
  of the next move is predictable while its DIRECTION is a coin flip (FINDINGS.md, "5-min candles over 50 pts"
  bullet: size clusters, direction stays at 50-55% green). Our squeeze and NR tests failed because they bet on
  direction.
- Contradict: the book treats hammers and long shadows as reversal signals and the wick end as a stop level. HUNT_R87
  found hammers usually mark continuation, and wick ends are not defended.
- Overlap with what works for us: Sadowski's swing break out of a contraction is close in spirit to the Donchian 20
  breakout, our paper arm (HUNT_R71 reference row). His NR7 is close to the MCX crude NR7 lead (HUNT_R96).

## What is new to us
- The Accelerator Oscillator (Bill Williams' AC) as a confirmation filter. We have never tested it.
- Multi-timeframe stochastic agreement (weekly AND daily overbought) as a context filter on a daily setup. Our
  stochastic tests were intraday only.
- Commitments of Traders positioning as a trend bias, which could be tested on MCX via COMEX COT for gold, silver and
  crude. India's participant-wise OI (FII long/short index futures) is the nearest local analogue.
- The Bollinger trigger on the middle line (not the band) after a width percentile squeeze.
- RSI(2) above 90 as a momentum entry with pyramiding on repeated triggers. Our RSI(2) work was the mean-reversion reading.
- A three-part limit-order scale-in (20 MA / broken level / 50% Fib) on pullbacks. We have only tested single entries.
- Moving the stop to breakeven after a fixed favourable move (+50 pips) is close to our existing profit-lock ladder
  (HUNT_R19 locks), so it is not really new.

## Could not read
The chart images (about 20 figures: USDJPY, EURUSD, GBPJPY examples and the HA trade screenshots) are not in the text or
OCR files. Their captions and the text around them were read. The two chapters listed on the contents page ("RSI, 5
systems + back test" and "binary options, 150% return") do not exist in this 42-page file.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-sadowski-01`: tested (R104): DLY: 65 trades, win 42%, net Rs 416,259 design, holdout -Rs 483,356 (fail); MCXD: 49 trades, win 35%, net Rs 95,346 design, holdout Rs 184,782 (fail)
- `book-sadowski-02`: tested (R104): DLY: 324 trades, win 36%, net Rs 458,687 design, holdout Rs 49,026 (fail); MCXD: 241 trades, win 22%, net Rs 49,315 design, holdout Rs 68,655 (fail)
- `book-sadowski-03`: tested (R104): IDX: 13 trades, win 54%, net Rs 11,240 design, holdout Rs 5,700 (fail)
- `book-sadowski-04`: tested (R104): IDX: 603 trades, win 26%, net -Rs 85,097 design, holdout -Rs 30,566 (fail)
- `book-sadowski-05`: tested (R104): IDX: 4038 trades, win 26%, net -Rs 498,476 design, holdout -Rs 112,977 (fail)
- `book-sadowski-06`: tested (R104): IDX: 795 trades, win 42%, net Rs 92,562 design, holdout -Rs 14,601 (fail)
- `book-sadowski-07`: tested (R104): IDX: 189 trades, win 49%, net -Rs 30,443 design, holdout -Rs 24,943 (fail); MCXI: 109 trades, win 35%, net -Rs 91,330 design, holdout Rs 1,487 (fail)
- `book-sadowski-08`: tested (R104): IDX: 223 trades, win 37%, net -Rs 26,984 design, holdout -Rs 27,149 (fail); MCXI: 49 trades, win 55%, net Rs 78,075 design, holdout Rs 62,337 (fail)
- `book-sadowski-09`: tested (R104): IDX: 5498 trades, win 35%, net -Rs 710,888 design, holdout -Rs 150,781 (fail); DLY: 949 trades, win 33%, net Rs 1,495,059 design, holdout -Rs 878,745 (fail)
- `book-sadowski-10`: tested (R104): IDXF: 114 trades, win 33%, net Rs 43,629 design, holdout Rs 112,628 (fail)
