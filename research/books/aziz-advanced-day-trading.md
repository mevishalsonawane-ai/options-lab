# Advanced Techniques in Day Trading - Andrew Aziz (2018)

Source: aziz-advanced-day-trading.txt, 420 pages (printed pages 1-410 plus front matter), read in full on 25 Oct 2026 (text
layer for all prose, glossary included; OCR for the 25 image-only pages 4, 8, 124, 135, 145, 164, 203, 215, 216, 239, 241,
243, 245, 248, 259, 285, 289, 290, 291, 299, 348, 352, 386, 419 and 420, which are cover / blank pages and chart or
Level 2 screenshots: the OCR gives only axis labels, prices and captions). The many chart screenshots elsewhere have no
text layer either, so only the author's prose about them could be read.
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A sequel to the author's beginner book, by a Vancouver day trader who runs the Bear Bull Traders chatroom.
It trades US stocks intraday on 1-minute and 5-minute charts with VWAP, 9/20 EMA and 50/200 SMA, and Level 2.
Chapters 2-4 cover the platform and orders, how to pick "Stocks in Play" with scanners, and how to mark levels.
Chapter 5 covers candles, trade management and Level 2; Chapter 6 gives five strategies (Fallen Angel, ABCD, Bull Flag, ORB, VWAP).
Chapter 7 is risk (2% and 6% rules, risk/reward of at least 2:1) and Chapter 8 is psychology and the learning curve.

Note on scope: the brief mentions Red-to-Green; this book does NOT describe a Red-to-Green strategy (it is not in the
text). It also names "Top and Bottom Reversal Strategies" and says they are explained in Chapter 6, but Chapter 6 never
gives their rules: only a scanner screenshot (Fig 3.17-3.18) and one figure caption (Fig 5.3) exist. The author says
plainly that his style is discretionary and that he does not believe a mechanical system can capture it.

## Methods, rules and setups

US times are New York time. Our NSE mapping (an assumption, used in the rules file): the Open 09:15-10:15, Late-Morning
10:15-11:45, Mid-day 11:45-14:45, the Close 14:45-15:30. The index has no traded volume: VWAP on our side needs index
futures minutes (only live contracts can be fetched) or R10's option-volume proxy (89% sign agreement with the real
futures VWAP, `HUNT_R10_ORDERFLOW_TOOLS.md`).

### 1. Stocks in Play and the pre-market Gappers scanner (Ch. 3)
- Entry: none; this is selection. Trade only stocks with high RELATIVE volume and a fresh fundamental catalyst that
  move independently of their sector and the market.
- Gappers scanner (stated precisely): gap of at least 2% up or down pre-market; at least 100,000 shares traded by 9 a.m.;
  average daily volume over 500,000 shares; ATR at least 50 cents; a catalyst (earnings, FDA, M&A, contracts, splits,
  etc.). Then keep 2-3 (at most 3-5) names with clean, uniform pre-market volume and clear levels.
- Exclusions: buyout gaps (flat pre-market at the deal price on heavy volume); a single block trade as the only volume;
  mega caps without a catalyst (in his view run by algorithms).
- Float classes: low float (<20 million, under $10; Bull Flag and Fallen Angel only; never short them); medium float
  (20-500 million, $10-100; VWAP and ORB); mega caps (VWAP, only with a catalyst).
- Market and timeframe: US stocks, pre-market to the first hour.
- Signal source: underlying price/volume (the stock itself). Maps to NSE index options: only as "is today a big-range
  day", since the index is always the same instrument; stock F&O names: partly (gap and relative volume); MCX: partly
  (event days); crypto: partly (volume spikes).
- Mechanical? partly (the numeric filters yes; "clean pre-market" no).
- Our research: the SIZE side agrees: R16 and R88 find the size of the coming move predictable (R88: size 225 of 305
  cells hold, direction 0 of 8). Picking stocks did not rescue the signals: R58 traded ORB, gap and VWAP rules on the 10
  most liquid NSE option stocks, 0 of 38 pass (ORB family -56.0 lakh on 14,343 design trades; volume / VWAP family -17.9
  lakh on 7,239), and the same minutes in cash lost too.

### 2. Intraday scanners (Ch. 3)
- New High of Day (for Bull Flags): price $1-10; day volume at least 200,000; float at most 10,000,000; last-5-minute
  volume 400%-2,000% of normal; volume at least 2 times normal; 15-minute range at least $0.30.
- Turbo Breakup / Breakdown: a Stock in Play making a new high (low) of the day with unusual 1-minute volume.
- VWAP Breaking Out: a Stock in Play crossing VWAP on high volume. A chat member credits a "VWAP false breakout"
  scanner traded 9:45-10:20 for about $1k a day (an anecdote).
- RSI Extreme: 5-minute RSI above 90 or below 10, at least 1 million shares that day, price $10-100. He says this finds
  candidates and is "by no means" a buy or sell signal.
- Circuit-breaker halt alerts (a 15% move in five minutes typically halts a US stock for 5 minutes).
- Top / Bottom Reversal scanners: settings shown only as screenshots.
- He says about 95% of scanner alerts are not tradeable; only 5% give an excellent risk/reward.
- Signal source: underlying price/volume. Maps to NSE index options: the volume-burst and new-high-of-day ideas can be
  computed on index futures; MCX futures yes; crypto yes.
- Mechanical? yes as filters, no as entries.
- Our research: F1 found 1-minute RSI leans weakly the other way for 15 minutes (IC about -0.05) and is gone by 60; the
  RSI 30/70 reversal (MR-03, `OBUY_GB.md`) lost Rs -82,004 in walk-forward. The 90/10 5-minute extreme itself: not
  tested by us.

### 3. Support and resistance levels (Ch. 4)
- Predetermined levels: previous day close (PCL, "the most important"), yesterday's high/low (Y), two days ago high/low
  (YY), all-time and 52-week highs/lows. Discretionary levels: pre-market high/low, moving averages on daily and
  intraday charts, and daily extremes and long wicks ("kangaroo tails").
- Strength order: daily > 5-minute > 1-minute chart, and 200 SMA > 50 SMA > 20 EMA > 9 EMA; the daily 200 SMA is the
  strongest. Half and whole dollars act as levels. A level is an area (5-10 cents on a typical stock), not a price.
- He rejects diagonal trend lines and most named chart patterns as wishful thinking; only horizontal levels count.
- Use: targets and stops for every strategy; draw only recent levels near the pre-market price.
- Signal source: underlying price. Maps to NSE index options: yes (PDC, PDH/PDL, daily SMAs, round numbers on the index);
  MCX yes; crypto yes.
- Mechanical? mostly (predetermined levels yes; "draw the line through most wicks" no).
- Our research: already rejected as signals. R20b: levels (yesterday's high/low/close, pivots, OI walls) were no better
  than random lines (random mirrored levels did better in 6 of 7 variants). R20c: two-close breaks of PDH/PDL and other
  levels went +0.02 ATR14 to the close, the same as random levels. R89: the previous-day high/low break on 1-minute bars,
  0 of 180 pass. R87: a long wick is not a defended level (a big tail's low broken within 10 candles 59% of the time on
  NIFTY 5m, n 3,314, against 57% for any candle with the same distance). One open lead: R18 found, post hoc and in design
  only, that exiting at the next support/resistance helped 9 of 10 losing arms (+Rs 64.8k on 4,964 trades); it was never
  pre-registered.

### 4. Candles and price action (Ch. 5)
- Bullish / bearish bodies show who is in control; dojis (incl. shooting star, hammer) show indecision and need a
  confirming candle and a level. He dismisses Head-and-Shoulders, Cup-and-Handle, Morning Star and the like.
- The two patterns he uses on 5-minute charts: (a) Higher Highs and Higher Lows (two bars: second high and low both above
  the first) and its mirror Lower Lows and Lower Highs; an uptrend exists as soon as two bars show it and lasts until a
  new 5-minute low; (b) Bullish / Bearish Engulfing (the second body opens beyond the first's close and closes beyond
  its open).
- Use: as entry confirmation and, above all, as exits: a short is wrong once the 5-minute chart fails to make a new low
  and turns to higher highs and higher lows.
- Signal source: underlying price. Maps to NSE index options: yes; MCX yes; crypto yes.
- Mechanical? yes for the definitions; partly for their use.
- Our research: R87 (about 1,033 sessions per index): no shape predicts direction; after hammers and shooting stars the
  prior move continued. R87B: exiting on an engulfing candle against the trade HURT (ORB -42,729; Donchian -29,944 in
  design). R71 P8, the 15-minute higher-high + higher-low swing break, lost -Rs 34,242 on 372 final trades.

### 5. Trade management (Ch. 5, repeated in every strategy)
- Take partial profit at each target: 1/4 (his hotkey) or 1/2 of the position; after the first partial, move the stop
  to break-even and never let a winner become a loss.
- Exit early when new information contradicts the plan (no new 5-minute low on a short, a strong opposite candle near
  VWAP), even before the stop.
- Add only to winners (e.g. add on a retest of a broken level that holds); never average down.
- If no target level exists: exit a long on a new 5-minute low, a short on a new 5-minute high.
- Use the 1-minute chart to place a tighter, still technical stop when the 5-minute stop is too far.
- Signal source: the stock's price for every trigger. Maps to NSE index options: yes (index triggers; stops/targets may be
  set on the premium per CLAUDE.md); MCX yes; crypto yes (the break-even move is a profit lock).
- Mechanical? yes.
- Our research: partial-at-target plus a break-even runner failed where we tried it: R81 X5 (half at +15% (1R), runner to
  breakeven) on Liquidity 15+5 made Rs 1,78,523 against Rs 2,57,249 for its own exits in design, and -Rs 23,896 in the
  final. R100 G13 (two lots, one sold at +15%) made Rs 1,64,131 against Rs 2,68,404 on 1,238 trades. R19, R19c and R100:
  locks and early profit-taking raise the win rate but cut the few long runners that pay. R12 notes the general finding
  that early break-even moves lower trend-following expectancy.

### 6. Level 2 signals (Ch. 2 and 5)
- A big bid close to the inside market is BEARISH (a patient buyer waiting for lower prices); a big ask is BULLISH.
  Aggressive buyers hit the ask; time and sales shows who is aggressive. Only an obvious one-sided imbalance counts.
- "No intention to fill" orders: big orders far (e.g. 40+ cents) from the inside are fake and should be ignored.
- Never trade on Level 2 alone; he used it mainly to exit early (MOMO short covered before a squeeze).
- Signal source: order book of the traded instrument (index futures for us). Maps to NSE index options: only with depth
  history we do not have; MCX same; crypto: depth is available.
- Mechanical? partly.
- Our research: R10: no tick or depth history exists for Indian index futures or options, so this is untestable on our
  history (the app now records). Related order-flow trap studies R7 / R8 / R61b failed; R86 found stop runs do not reverse.

### 7. Strategy 1: Fallen Angel (Ch. 6)
- Setup: a low-float stock (usually under 20 million shares) gapping up hard on news with heavy pre-market volume (often
  over one million shares); at the open it makes a new high, then sells off quickly, often below VWAP.
- Entry: only after it consolidates at a level (pre-market low, a daily or 5-minute moving average; two MAs on two
  timeframes make it stronger), on a new 1-minute or 5-minute high out of the consolidation with "massive" volume,
  clearly above previous candles. Skip if no clear support/consolidation, or if the breakout lacks volume.
- Stop: below the consolidation.
- Target / exit: VWAP, then the high of the day, then the pre-market high, then Y high/low. Examples: risk/reward about 2.
- Market and timeframe: US low-float stocks under $10, the first hour; 1-minute and 5-minute charts. He takes about
  4,000 shares on such stocks and says it is hard to manage.
- Signal source: underlying price/volume. Maps to NSE index options: only as a loose analogue (a big gap-up day, an
  opening spike, a flush, a base, a volume breakout); MCX/crypto: loose analogue.
- Mechanical? partly (consolidation and "massive volume" need thresholds).
- Our research: not tested by us as such. Nearest: R87B found a short red pullback after two green 15-minute candles
  continued up on SENSEX (held in holdout) but the call rule with a stop under the pullback lost -Rs 21,127 on 162 trades.

### 8. Strategy 2: ABCD and Reverse ABCD (Ch. 6)
- Setup: a strong move from A to a new high of the day at B; a pullback that finds support at C above A (C may be VWAP or
  the 1-minute 9/20 EMA).
- Entry: near C once it holds, as the price turns back toward B (on the 1-minute chart, a new high with volume higher than
  the previous candle). He prefers buying near C to buying the break of B (waiting for D, he says, cuts the reward and raises
  the risk). Volume is high at B and D.
- Stop: a break of C.
- Target / exit: sell half at D (the break of B) and move the stop to break-even; sell the rest at a level or when the
  5-minute chart makes a new low. Reverse ABCD is the mirror for shorts (example stop: a new 5-minute high or the break of
  a pre-marked level).
- Market and timeframe: medium and large float US stocks; 1-minute and 5-minute; mostly the Open.
- Signal source: underlying price/volume. Maps to NSE index options: yes on index futures (volume) or spot (price only);
  MCX yes; crypto yes.
- Mechanical? partly (C needs a swing definition).
- Our research: the nearest is R87B: the 3-candle "pullback in a rise" cluster on SENSEX 15m held out of sample (+0.26
  ATR, n 182) but its option rule lost (-Rs 21,127 on 162 trades; the stop under the red candle's low was hit on 117 of
  them). R71 P8 (buy the break of the last swing high after HH + HL) lost in the final. The exact ABCD: not tested by us.

### 9. Strategy 3: Bull Flag (Ch. 6)
- Setup: an ABCD on a low-float stock: one or more large green "pole" candles, then small sideways candles (the flag).
  Found with the New High of Day scanner (e.g. 120 times normal relative volume in one example).
- Entry: on the break of the consolidation high with heavy volume; best when a new 1-minute high and a new 5-minute high
  happen together. Only the first and second consolidation; the third and later are too extended.
- Stop: below the consolidation low.
- Target / exit: sell half on the break of the high of the day, stop to break-even; exit the rest at the target or on a
  new 1-minute or 5-minute low. He does not trade Bear Flags (low floats are hard to borrow and squeeze).
- Market and timeframe: US low-float stocks under $10; the Open; "more or less a scalping strategy".
- Signal source: underlying price/volume. Maps to NSE index options: a pole on the index is rare (see below); MCX yes;
  crypto yes.
- Mechanical? partly.
- Our research: R71 P4 (a 3-bar pole of at least 2 x ATR14, 3-10 flag bars, first close above the pole high): too rare to
  judge on the indices, design -Rs 27,378, final 32 trades -Rs 442 (+Rs 5,805 with the index stop, below random entries).

### 10. Strategy 4: Opening Range Breakout (Ch. 6)
- Setup: watch the Stock in Play for the first 5 minutes (he now prefers 15 or 30, some use 60). The opening range must be
  SIGNIFICANTLY smaller than the daily ATR (PG example: a 5-cent range against a $0.79 ATR); a range near or above ATR
  disqualifies the stock. He wants many orders, not a few big blocks. Not for low-float gappers.
- Entry: on the break of the range in its direction, on the correct side of VWAP (his examples short only below VWAP).
- Stop: VWAP. The summary says a close below VWAP for longs and a break above VWAP for shorts; the ELF example says the
  reverse ("always a close above VWAP for short positions"), so the book is inconsistent here.
- Target / exit: the next technical level (pre-marked daily level, a daily moving average, previous day close); with no
  level, exit a long on a new 5-minute low (a short on a new 5-minute high). Partial profits and break-even as in section 5.
- Market and timeframe: mid and large cap US stocks; 5-, 15- or 30-minute range; the first hour.
- Signal source: underlying price (and VWAP). Maps to NSE index options: yes, this is our ORB family; MCX yes (US-open ORB
  tested); crypto: no natural open.
- Mechanical? yes, apart from "significantly smaller than ATR" (we must pick a fraction).
- Our research: ALREADY REJECTED. R3: ORB -Rs 2,23,040 on 2,489 trades over 5 years (BANKNIFTY + NIFTY), 0 of 6 years
  positive, 0 of 16 versions pass, and it lost Rs 52 a trade even on days that trended its way. R49 (Nov 2024 onward): 0 of
  336 tests pass. R92: 0 of 257 cells by entry time, range length (15-75 min), day type and exit time; the index is on the
  right side on 64-72% of trades yet the option loses Rs 33-110 a trade. R20: 15- and 30-minute ranges trade more and lose
  more (A15 -Rs 34,682 over 416 design trades vs plain -Rs 14,772 over 337). R22: a 15-minute close break lost Rs -8,573
  design / -8,882 holdout. R23: all 18 plain ranges lose. R83: the MCX US-open ORB failed (0 of 13 MCX ideas pass). His
  two filters: the VWAP side (R10 V1, "trade on the VWAP side of the move", ORB +9 -> -7 Rs/day design -> holdout: no
  value) and a narrow range vs ATR (R10 T6 narrow first-30-min range: positive in both periods but BH q 0.32, and the kept
  trades still lose Rs 105-183 each in the holdout). On NSE stocks (R58) the ORB family lost -56.0 lakh on 14,343 design
  trades.

### 11. Strategy 5: VWAP basics (Ch. 6)
- Reading: above VWAP the buyers are in control, below it the sellers; institutions benchmark fills to VWAP, so a test
  of VWAP 10-15 minutes into the open shows whether a big buyer or seller is there.
- Long: price finds support above VWAP; stop a 5-minute close below VWAP; half at the first target (half/whole dollars),
  stop to break-even. Short: price tries and fails to break VWAP on the 5-minute chart; stop above VWAP.
- Signal source: underlying price/volume (VWAP). Maps to NSE index options: yes with futures VWAP or a proxy; MCX yes
  (futures volume); crypto yes (session definition needed).
- Mechanical? partly.
- Our research: F1: VWAP carries no usable direction on the indices; C1 (EMA 9/21 + Supertrend + VWAP) lost -Rs 2.99 lakh
  on 2,256 holdout trades. R10: 7 VWAP filters, none helps the arm that pays (White reality check p = 0.11). R58 on NSE
  stocks: the VWAP family lost.

### 12. VWAP False Breakout (Ch. 6) - his favourite
- Setup: a Stock in Play sells off below VWAP, then reclaims VWAP (shorts cover), then LOSES VWAP again in the
  Late-Morning (10:30-12:00 New York; mostly after 10:30 and into the early afternoon), often after failing at a level
  (previous day close, 50 SMA, 20 EMA).
- Entry: short below VWAP once it is lost (he sometimes anticipates, with small size, adding on the way down).
- Stop: above VWAP.
- Target / exit: the low of the day, then the next daily level or the 5-minute 200 SMA.
- Signal source: underlying price/volume. Maps to NSE index options: yes (buy the ATM put on the short signal); MCX yes;
  crypto yes.
- Mechanical? yes once "sold off", "reclaimed" and "lost" are defined as 5-minute closes.
- Our research: the exact pattern is not tested by us. Nearest: R10 V1 (side of VWAP) had no filter value; F1 found no
  VWAP direction; R86 found that a midday stretch away from VWAP tends to continue (1,465 days), which is on the side of
  this trade (a lost VWAP keeps falling) but is descriptive, not a tested rule.

### 13. VWAP Reversal (Ch. 6)
- Setup: after the opening move carries the stock away from VWAP, it fails to make a new low of the day or a new 5-minute
  low (or starts higher highs and higher lows); a bullish engulfing is a plus. Mirror for shorts above VWAP (fails to
  make a new high).
- Entry: long when the 1-minute closes above its 9 EMA or makes a new 1-minute high; only if the entry still offers a good
  risk/reward (DE example: skipped because the next candle was already too close to VWAP).
- Stop: below the low of the day or the previous 5-minute candle's low.
- Target / exit: VWAP first (sell part, stop to break-even), then the 5-minute 9 EMA, 20 EMA, or a daily level; keep a
  piece for the "VWAP pop". Examples quote risk/reward about 2.
- Market and timeframe: the Open and into the Late-Morning; 1-minute and 5-minute.
- Signal source: underlying price/volume. Maps to NSE index options: yes; MCX yes; crypto yes.
- Mechanical? yes with a fixed "fails to make a new low" test.
- Our research: nearest is R71 P7 (three-bar reversal at the day's high / low): it beat random timing (twin p 0.002) but
  still lost Rs 84k-1.68 lakh in the final after option costs. R86 V1, a midday fade back to VWAP on busy days, lost
  Rs 64,786 on 71 trades (a different clock: noon, not the opening move). The early VWAP Reversal itself: not tested by us.

### 14. VWAP Moving Average Trend (Ch. 6)
- Setup: in the Late-Morning or Mid-day, after the stock has held VWAP (long) or lost it (short), it trends along the
  1-minute and 5-minute 9 EMA / 20 EMA; the 20 EMA is the stronger line.
- Entry: as close to the moving average as possible after it has acted as support (resistance).
- Stop: 5-10 cents beyond the moving average, or a 5-minute close beyond it.
- Target / exit: ride until a 5-minute close beyond the 20 EMA (a low-volume poke does not count); take 1/4 or 1/2 when
  far from the MA, re-add on a pullback; stop to break-even after any profit; trends end around 3 p.m.
- Signal source: underlying price/volume. Maps to NSE index options: yes, but a several-hour hold pays time decay; MCX yes;
  crypto yes.
- Mechanical? yes.
- Our research: R29 (EMA ribbons, pullback to 21/34 among the patterns): 0 of 55 rule x index tests pass; EMA 9 x 21 cross
  Rs -110 a trade in the holdout (1,785 trades). R86's continuation finding agrees with the idea that a lost VWAP keeps
  losing, but no trade rule was built on it.

### 15. Time-of-day map and the 30% rule (Ch. 6)
- The Open (9:30-10:30): Bull Flag, ORB, ABCD, VWAP Reversal, Fallen Angel. Late-Morning (10:30-12:00): VWAP Reversal and
  VWAP False Breakout; easiest for new traders (their worst results are at the Open, their best here). Mid-day (12-3):
  VWAP MA Trend and False Breakout; the glossary calls it the most dangerous time. The Close (3-4): trend trades with the
  professionals.
- Rule: never lose more than 30% of the Open's profit in the rest of the day; if so, stop or switch to the simulator.
- Signal source: none (a schedule and a day rule). Maps to NSE: yes (clock mapping above); MCX: needs its own session map.
- Mechanical? yes.
- Our research: R102: the day is front-loaded (61% / 65% of the median NIFTY / BANKNIFTY day range is made by 10:30;
  the first 15 minutes run about 8x the midday rate), which agrees with "the action is at the Open". But R92 found that
  every entry hour loses for the ORB family. R19c: a day profit lock raised green days (42% -> 49%) but failed net /
  drawdown; a day loss cap lowered green days. The exact 30% give-back rule: not tested by us.

### 16. Risk/reward gate and multi-timeframe stops (Ch. 6-7)
- Take a trade only if the reward to the next level is at least 2 times the risk to the technical stop (he cites 1:3,
  1:4 and one 1:8 example). The stop must sit at a technical level (usually VWAP), never tightened just to improve the
  ratio; if a late entry leaves less than 2:1, the trade is missed. Use the 1-minute chart to find a closer technical stop.
- Signal source: underlying price. Maps to NSE index options: yes (measure the index distance to the stop and to the
  next level); MCX yes; crypto yes.
- Mechanical? yes once "next level" is fixed.
- Our research: not tested by us as an entry gate. R18's post hoc "exit at the next support/resistance" lead (above) is
  the nearest.

## Performance claims
- "All of the strategies outlined in Chapter 6 have been demonstrated, if executed properly, to show positive
  expectancy." No evidence given: no backtest, no trade count, no track record.
- He is consistently profitable with about 30% losing trades; with 2:1 trades one can be wrong 40% of the time and still
  profit (arithmetic, not evidence).
- Averaging down wins about 85% of the time, but the other 15% blow up the account (his own experience, no numbers).
- Day traders aim for 0.5-1% of the account a day; the best swing traders 15-20% a year. No evidence given.
- Over 60% of US volume is algorithmic high-frequency trading. No source given.
- About 95% of scanner alerts are untradeable. Anecdote.
- A chat member says a VWAP false-breakout scanner gave "easily 1k" a day in July 2017. Anecdote.
- Individual winning trades with P&L screenshots (for example JD $1,375.92 in about 20 minutes, SYMC $1,539, VIPS $1,249,
  SMIT $883, FOLD $595, X $490 on 1,600 shares). Selected examples, not a record; a few losing trades are also shown
  (TEVA, JWN) to teach management.
- New traders do worst at the Open and best in the Late-Morning (his review of students' trades; no numbers).
- Trading is 80% psychology and 20% technical knowledge (he repeats this as a common saying).

## Risk, sizing and psychology
- 2% rule: never risk more than 2% of account equity (not buying power) on a trade; he risks 1%; he recommends 0.5-1%
  for beginners. Size = max dollar risk / per-share risk to the technical stop (worked example: $40,000 account, 1% =
  $400, $0.40 stop, 1,000 shares max). Risk less if the stop is too far; never more.
- 6% rule (from Elder): when the month's losses plus open risk reach 6% of equity, stop live trading for the month.
- Risk/reward: never below 2:1; a good entry defines the ratio.
- Size by stock: start with 100 shares; his usual 2,000 shares at $20-50, 1,000 at $50-100, about 4,000 on sub-$10 low
  floats; reduce size in losing streaks (the opposite of the gambler's martingale; the 1913 Monte Carlo run of 26 blacks).
- Never average down; add only to winners; never turn a losing day trade into a swing trade; always close by the end of
  the day.
- Accept small losses and re-enter later ("several quick stabs"); move the stop only in the trade's direction; no "just
  this once" exceptions (his diet story: a rule kept 100% is easier than 98%).
- Overtrading: profitable traders make two or three trades a day; 20-60 trades a day feeds the broker.
- No daily dollar goals; hide the P&L column; judge a day by discipline, not by profit.
- Simulator first (at least three months, with real size), then small real size; expect 8-12 months to consistency;
  revenge trading and the "Robert" blow-up story (2 winners in 20 trades after two hotkey mistakes).
- Resilience, fear, greed, revenge trading; discipline is what institutions enforce and retail traders lack. One line
  worth keeping: "Indicators only indicate; they should not be allowed to dictate."

## How it relates to our research
- ORB (section 10): already rejected on NIFTY/BANKNIFTY and the other indices: R3 (0 of 16), R49 (0 of 336), R92 (0 of 257),
  R20 / R22 / R23 (no range length helps; 15- and 30-minute ranges lose more), R14 (re-entries churn charges). His two
  filters, the VWAP side and a range narrow vs ATR, were tested in R10 (V1, T6) and do not rescue it. R58: the same family
  lost on NSE stocks, his home market type. R92's anatomy explains the gap with his account: the index ends on the
  right side on 64-72% of ORB trades, but an option buyer still loses after costs and decay.
- VWAP (sections 11-14): VWAP as a filter or a side-of-VWAP signal has no value on our indices (R10, F1, R58). R86 disagrees
  with "price returns to VWAP" at midday (a stretch continues; the midday fade lost Rs 64,786 on 71 trades) and in that
  sense agrees with his afternoon trend idea. His named VWAP setups (False Breakout, early Reversal, MA Trend) were not
  tested as he defines them.
- PDH/PDL and levels (section 3): R89 (0 of 180), R20b (no better than random lines), R20c, R22 and R87 (wicks are not
  defended) reject levels as signals. Levels as targets are untested except R18's post hoc lead.
- Pullback / flag (sections 7-9): R71 P4 flag too rare on the index; R87B's pullback continuation held on SENSEX but the
  option rule lost because the stop under the pullback was too tight for an option buyer - the same stop ABCD uses.
- Candles (section 4): R87 / R87B: shapes do not predict direction; engulfing-against exits hurt.
- Trade management (section 5): partial profits and break-even stops failed on our arm that pays (R81 X5, R100 G13, R19).
- Risk rules: his per-trade 1-2% and monthly 6% stops match the spirit of Boss's account stop (-Rs 40k drawdown or
  -Rs 13k a month); not a research question. His "stocks in play" idea maps to our size-is-predictable result (R16, R88,
  R102), not to direction.
- Overall: the book is discretionary by design; where its rules are mechanical, our studies of the nearest version
  found no edge on NSE index options after costs.

## What is new to us
- The VWAP False Breakout as a defined sequence (below VWAP, reclaim, lose again in the Late-Morning), traded short
  with a VWAP stop: a sequence, not a side-of-VWAP filter. Never tested; testable on index futures VWAP or R10's proxy,
  and more naturally on MCX futures, which have real volume.
- The risk/reward ENTRY gate: skip any signal whose distance to the next pre-marked level is under 2x the distance to the
  technical stop. We have tested targets and exits, not this gate on entries.
- "Fails to make a new 5-minute low" as a timed exit for short-side (and mirror long-side) trades, applied to the arms.
- The 30% give-back day rule (stop for the day after losing 30% of the first hour's profit).
- An entry that needs a new 1-minute high and a new 5-minute high at the same time, with bar volume above the prior bar.
- Level 2 contrarian reading (big passive bid = bearish) and far-away "no intention to fill" orders: ideas for the
  depth recordings, not testable on history.

## Could not read
Pages 4, 8, 124, 164, 216, 386, 419 and 420 (image-only, the OCR found no text); the chart and Level 2 screenshots on the
other image pages and throughout the book (only captions and axis labels readable); the Top / Bottom Reversal scanner
settings (screenshots only). No prose was missing.
