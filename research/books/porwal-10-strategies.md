# 10 Most Profitable Trading Strategies - Nikhil Porwal (2023)

Source: porwal-10-strategies.txt, 61 pages, read in full on 25 Oct 2026 (text layer for all pages; OCR only for the two
cover pages, 1 and 3; the chart figures are images and their drawn entry / stop / target marks could not be read).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short self-published Kindle book (2023) by a young Indian trader who runs the "1% Trader Community" channel.
It teaches ten chart setups: three candle patterns, four breakout setups and three "most powerful" intraday setups.
Examples are mostly Indian stocks, NIFTY 50 and NIFTY BANK, plus US stocks, gold, forex, US indices and Bitcoin.
Every setup trades the stock or index directly (no options), with a stop, a minimum 1:2 reward-to-risk and a trailing stop.
It gives no backtest numbers at all; each chapter asks the reader to "self backtest" on past charts.

## Methods, rules and setups

General rules repeated in almost every chapter (Ch. 2 and each "Rules" list): reward-to-risk at least 1:2 (the author
says this keeps you profitable even if only 50-60% of trades work); one more confirmation before entry (volume or an
oscillator); a trailing stop once the trade moves your way; position size from the stop distance; backtest first;
higher timeframes give better odds; and "close the terminal" for the day after two stop-losses.

### 1. Inside bar (Ch. 3.1)
- Entry: on the CLOSE of a candle beyond the mother (previous, larger) candle; the text writes the short side ("close
  below the mother candle"), the long side is the mirror. Entering on the close, not the touch, is stressed to avoid
  fake breaks.
- Stop: beyond the other side of the mother candle.
- Target / exit: three targets at 1x, 1.5x and 2x the mother candle's range, measured from the break; trail the rest.
- Filters: volume confirmation. Two uses: (a) continuation: read the trend on a bigger timeframe and take only inside-bar
  breaks in that direction on a smaller one; (b) reversal: an inside bar at a market top or bottom while an oscillator
  is overbought / oversold (example: US30).
- Market and timeframe written for: stocks (Lupin), US30; timeframe not fixed, "higher is better".
- Signal source: underlying price (plus volume); maps to: NSE index options yes (buy ATM/1-ITM CE on an upside break,
  PE on a downside break; stop and targets on the index); MCX futures yes; crypto yes, the trailing stop gives the
  required profit lock.
- Mechanical? yes for the bar, the entry, stop and targets; partly for the trend and "top / bottom" context.
- Our research: R71 (`HUNT_R71_CHART_PATTERNS.md`, P1) traded 15-minute inside-bar breaks on the five indices as 1-ITM
  options: final period -Rs 1,23,667 on 1,075 trades (42% wins), design -Rs 2,99,782; the 30-minute re-tune lost
  Rs 43,603 on 403. R87 found 2-candle patterns (inside, outside, engulfing, harami) carry no direction edge. On daily
  futures (`SWING_DEEP.md`) the NR7 / inside-day breakout looked good in hindsight but the walk-forward lost Rs 45-58k a
  year. The 1x range-extension target was tested in R22: -Rs 1,17,565 in design (stop hit on 486 of 665).

### 2. Doji reversal (Ch. 3.2)
- Entry: when price breaks the doji's high (at a bottom / oversold) or its low (at a top / overbought). In the NIFTY
  example a gap-up the next day was the trigger.
- Stop: below the doji's low for a long (above its high for a short); then trail it with "any moving average".
- Target / exit: not fixed: support / resistance, a percentage, or the trailing stop.
- Filters: only dojis at a market top or bottom; an oscillator in overbought / oversold (the oscillator is not named in
  the text; the author says to keep its default settings); one more confirmation.
- Market and timeframe written for: mainly daily charts (ABT, NIFTY 50).
- Signal source: underlying price; maps to: NSE index options yes (as a 1-3 day option trade from a daily bar, or on
  intraday bars); MCX yes; crypto yes with stop and lock.
- Mechanical? partly: the doji, break and stop are mechanical; "top / bottom" and the unnamed oscillator need a choice.
- Our research: R87 (`HUNT_R87_CANDLE_ANATOMY.md`) found no candle shape predicts the index's direction; after every
  shape the index went up 43-62% of the time; the SENSEX 15m gravestone doji put rule made +Rs 14,373 on 71 trades but
  failed walk-forward; R87B daily shapes 2015-2025: 0 of 25 cells pass. RSI 30/70 reversal as an option buy
  (`OBUY_GB.md` MR-03) lost Rs -82,004 walk-forward.

### 3. Bullish and bearish engulfing (Ch. 3.3)
- Entry: long on the break of the bullish engulfing candle's high; short on the break of the bearish engulfing candle's low.
- Stop: below the engulfing candle (long); above its high (short).
- Target / exit: at least 1:2; trail.
- Filters (bullish): a visible downtrend; day 2 is a white candle whose body covers the whole body of day 1's black candle
  (day 1 may be a doji). Extra confidence if day 1's black body is much longer than recent candles, volume spikes on
  either day, and the stochastic is oversold. Filters (bearish): an uptrend; day 1 white; day 2 black, opens above
  day 1's close and closes below day 1's open; day 2 volume above day 1's; overbought oscillator.
- Market and timeframe written for: daily (Kotak Bank, NIFTY BANK, ABT, Apollo Tyres, NIFTY, CAC 40).
- Signal source: underlying price and volume (index spot has no volume: use futures volume); maps to: NSE index options
  yes; MCX yes; crypto yes with stop and lock.
- Mechanical? yes, apart from "visible downtrend" (needs a definition, e.g. close below a 20-day average).
- Our research: R87 included engulfing among its 2-candle patterns: no direction edge on NIFTY, BANKNIFTY or SENSEX; an
  engulfing candle against an open Donchian or ORB trade used as an exit hurt (R87 table). MCX R85: engulfing "no
  reliable edge". Exact daily engulfing + stochastic + volume rule: not tested by us.

### 4. Trendline breakout / breakdown (Ch. 4.1)
- Entry (breakout of a falling trendline): do not buy the line break itself; wait until price holds above the previous
  lower high, then enter. Breakdown of a rising line: enter when a candle closes below the last swing (higher) low.
- Stop: just below the broken lower-high level (breakout); above the swing for a breakdown.
- Target / exit: earlier swing points (the older lower highs T1, T2, T3 in a breakout); at least 1:2; trail.
- Filters: a prior downtrend (for a breakout) or uptrend (for a breakdown); a volume spike on the signal day; the signal
  candle bigger than the previous two or three; trendline angle near 45 degrees is best, avoid lines steeper than 60.
- Market and timeframe written for: daily (NIFTY BANK near 42000, NIFTY 50, Coforge, Bitcoin, Aurobindo Pharma).
- Signal source: underlying price and volume; maps to: NSE index options yes; MCX yes; crypto yes with stop and lock.
- Mechanical? partly: drawing the line (which swings, the angle in price-per-bar units) is discretionary; the "hold above
  the previous lower high" trigger is mechanical.
- Our research: not tested by us as a trendline. Nearest: R71 P8 swing structure (break of the last swing high after
  higher high + higher low): final -Rs 40,109 on 374 trades (design looked good, twin p 0.88); R90 break-of-structure
  0 of 99; R62b market-structure break 0 of 16; R20c: the extra confirming candle costs 14 (5-min) / 18 (15-min) index
  points; R73: waiting 2-5 candles after a Donchian break lost money in design in every version.

### 5. Darvas box (Ch. 4.2)
- Entry: after price breaks out of a consolidation "box", once the break is confirmed by higher volume and a strong
  candle, buy slightly above the breakout level (mirror for a breakdown from a box at a top).
- Stop: below the breakout candle, or (preferred) outside the box on its far side.
- Target / exit: Fibonacci levels or prior support / resistance, or a percentage / reward-to-risk (at least 1:2); trail.
- Filters: "the longer the consolidation, the bigger the move"; liquid stocks with good fundamentals; do not chase
  extended moves; watch earnings and news.
- Market and timeframe written for: daily (Anupam Rasayan, Aurobindo Pharma after a box of more than 256 days, NIFTY after
  a 15-day box) and intraday (NIFTY 5-minute, NASDAQ).
- Signal source: underlying price and volume; maps to: NSE index options yes; MCX yes; crypto yes with stop and lock.
- Mechanical? partly: the box needs a definition (we would use an N-bar high / low channel, i.e. Donchian).
- Our research: the nearest is Donchian, our one chart arm with a record: R51 Donchian 20 on 5-minute index bars, 1-ITM,
  final +Rs 55,439 on 1,787 trades (`HUNT_R71_CHART_PATTERNS.md` reference row); R72 Breadth Donchian stronger. R71 P3
  squeeze release (quiet spell, then a band break) lost Rs 41,281 on 305 trades in the final. Daily NIFTY futures
  Donchian 20-day lost (`SWING_EXITS.md`). Waiting for confirmation after the break hurt Donchian (R73: -Rs 1,26,378
  in design). The volume filter and the "longer box" filter: not tested by us.

### 6. Turbocharged market opening (Ch. 4.3) - a 15-minute opening-range breakout
- Entry: mark the high and low of the first 15-minute candle; enter on the break of that range in the direction of the
  previous day's trend; the day bias is bullish above EMA(14) and bearish below it.
- Stop: "pre-decided" (the exact level is only on the charts); EMA(14) trails the stop.
- Target / exit: 1x, 1.5x and 2x the first 15-minute range; at least 1:2; intraday.
- Filters: trade only with the previous day's trend; gap and overnight news reviewed before the open; the author
  backtests a stock or index for at least 30 days first.
- Market and timeframe written for: NIFTY 50 and stocks (ICICI Lombard, Siemens), intraday (chart timeframe 5-minute
  by the look of the examples; not stated in the text).
- Signal source: underlying price; maps to: NSE index options yes (this IS our ORB family); MCX yes (MCX opens 09:00);
  crypto has no session open (would need a chosen daily anchor).
- Mechanical? yes once "previous day's trend" is defined (we would use yesterday's close vs its open, or vs the prior
  close).
- Our research: rejected. R23 (`HUNT_R23_ORB_RANGE.md`) traded the first-15-minute range break: -Rs 71,624 on 1,171
  trades, 0 of 4 blocks positive; the ORB family lost on every index (R3: ORB -Rs 2,23,040 on 2,489 trades, 0 of 6
  years in profit; R49 0 of 336; R92 0 of 257 time slots). R23 did find that good breaks more often go the same way as
  yesterday's candle (AUC 0.554), but no tradable filter came of it. R22: a 1x-extension target lost. R89: a daily trend
  filter on level breaks did not cut false breaks. Note: in R101 the opening-bar break is the most valuable part of
  both Donchian arms, so the first minutes matter, but a 15-minute range break bought after the move has not paid.

### 7. 52-week high-low momentum (Ch. 4.4)
- Entry: screen for stocks at a 52-week high (Chartink screener); on the weekly chart look for a long consolidation then
  the new high; on the daily mark the resistance and wait for a daily close above it; then drop to an intraday chart and
  take a long using any earlier setup (inside bar, trendline, opening range, Darvas). 52-week lows: shorts only, mirror.
- Stop / target: from whichever intraday setup is used.
- Filters: rising volume or a news catalyst; at a 52-week high only longs, at a 52-week low only shorts ("top-down").
- Market and timeframe written for: Indian cash stocks (RAMCO Cements); weekly -> daily -> intraday.
- Signal source: underlying price (daily / weekly highs); maps to: NSE index options as a direction filter (index at /
  near its 52-week high -> calls only); MCX yes; crypto yes.
- Mechanical? yes as a filter; the intraday trigger is whichever other setup is chosen.
- Our research: as a stand-alone swing rule it did nothing: NIFTY futures 52-week high/low momentum, 10-day hold,
  +Rs 2,175 a year over 11 years, inside the coin-flip range; on bought monthly options -Rs 12,054 a year
  (`SWING_DEEP.md`, `SWING_EXITS.md`); F&O stocks: no. As a direction filter for intraday index trades: not tested by us.

### 8. ADX-EMA (Ch. 5.1)
- Entry: on 5- or 15-minute bars: ADX above 20 and below 50 (the text says "greater than 20 or below 50"; we read it as a
  band), +DI above -DI for a buy (below for a sell), price above EMA(14) for a buy (below for a sell), and the previous
  day's trend in the trade's direction. In both NIFTY 5-minute examples, the actual entry was a pullback to near the
  EMA(14) with ADX above 30.
- Stop: some distance beyond the EMA so noise does not hit it; in the examples, beyond the day's opening high or the
  nearest swing.
- Target / exit: no fixed target; exit when a candle closes through EMA(14) against the trade; at least 1:2; square off
  before the close (intraday only).
- Settings: EMA 14; DMI 14/14 (ADX smoothing 14 and DI length 14).
- Market and timeframe written for: NIFTY 50 intraday, 5-minute.
- Signal source: underlying price; maps to: NSE index options yes; MCX futures yes; crypto yes with stop and lock.
- Mechanical? yes ("near the EMA" needs a distance, e.g. within 0.1%).
- Our research: the family is rejected; this exact combination is not. `OBUY_GB.md` TI-09 ADX/DMI entry (48 variants)
  lost Rs -345,311 walk-forward on index options, 0/5 years positive; TI-03 EMA cross -Rs 369,856; R29 EMA pullbacks to
  a band no better than random; R73 ADX above 20 as a Donchian filter failed; `SIDEWAYS_GUARD.md` ADX pause did not
  help; H43 EMA9/21 + ADX>20 "luck". The ADX upper cap of 50 and the prior-day trend filter: not tested by us.

### 9. Quasimodo / over-and-under (Ch. 5.2)
- Entry (bullish): after a downtrend (lower lows, lower highs), price breaks the last lower high making a higher high,
  then pulls back and forms a higher low instead of a new low: buy there. (A second wording says buy when price breaks
  above the higher swing high.) Bearish mirror: after higher highs and higher lows, a lower high, then a break below
  the previous higher low: short on that break or on a retest of it.
- Stop: below the previous lower low / the "QM zone" (bullish); above the pattern's highest point (bearish).
- Target / exit: the top of the first zig-zag, prior support / resistance or Fibonacci; at least 1:2 or 1:3; trail.
- Filters: volume confirmation; divergence or candle pattern.
- Market and timeframe written for: gold spot 15-minute, USD/JPY 30-minute, forex 4-hour for analysis and 30-minute
  entry, NIFTY BANK intraday. The author himself warns that in Indian intraday trading the pattern often runs out of
  time before the close and suggests forex or crypto instead; he points to a public TradingView indicator for it.
- Signal source: underlying price; maps to: NSE index options weakly (the book's own warning); MCX yes (long session);
  crypto yes with stop and lock.
- Mechanical? partly: swing points need a pivot width; the book's two descriptions of the pattern do not agree.
- Our research: rejected in its nearest forms: R90 break-of-structure / change-of-character on the index, 1-ITM options,
  0 of 99 cells (the target was too far for an intraday option, the same problem the author reports); R62b 0 of 16;
  R71 P8 swing structure lost in the final; R91 1-hour sweep then reversal 0 of 63.

### 10. Price-action traps (Ch. 5.3)
- Bull trap entry: in an uptrend, price breaks a key level (e.g. the day high) on weak volume, then a red candle with a
  volume spike takes it back below the breakout level: go short. Bear trap: price breaks below support, cannot stay
  there and reclaims it with a volume spike: buy above the recent swing high or above the high of the bullish
  reversal candle.
- Stop: above the recent swing high (bull trap); below the swing low or the low of the candle that broke support
  (bear trap).
- Target / exit: previous higher low or support (bull trap); previous resistance, the day high or the previous day's high,
  or Fibonacci extensions (bear trap); partial profits and a trailing stop.
- Filters: weak volume on the false break, a quick move back, divergence; 8-10 green candles before a break are read as
  big players already positioned ("when the retailer plans to enter, smart money plans to exit").
- Market and timeframe written for: NIFTY 50 and NIFTY BANK intraday, HDFC Bank, Bitcoin, US500, EUR/USD.
- Signal source: underlying price and volume; maps to: NSE index options yes (use futures volume); MCX yes; crypto
  yes with stop and lock.
- Mechanical? partly: "key level" must be fixed (day high / low, previous day high / low); "volume spike" needs a multiple.
- Our research: mixed. Fading failed pokes at yesterday's high/low, round numbers or the opening range lost in 6 of 6
  tests after costs (R86, `HUNT_R86_BIG_PLAYERS.md`). R87: a 15-minute stop run above NIFTY's day high was followed by
  MORE upside (n 1,666), and the rule built on it lost Rs 25,064 on 270 trades. R91 (sweep of a 1-hour swing) 0 of 63.
  The one piece that held: a 1-minute stop run below NIFTY's day low (wick below, close back above) was followed by
  +0.20 ATR over 5 minutes (n 1,702 in the holdout, +1.8 points), smaller than an option round trip (about 1.9 points).
  The bear-trap side with the book's volume spike and larger target (previous day's high): not tested by us.

## Performance claims
- The ten strategies are "proven to be effective" (Introduction): no evidence given.
- A 1:2 reward-to-risk keeps you profitable at a 50-60% win rate (Ch. 3.2): arithmetic only, before costs (break-even at
  1:2 is about 33% before charges); no win rates are measured anywhere.
- Inside-bar reversals give "maximum profit against small stop losses": no evidence given.
- NIFTY fell more than 14% and the CAC 40 more than 16% after bearish engulfing candles; Aurobindo Pharma had consolidated
  for more than 256 days before its box breakdown: single hand-picked chart examples.
- ADX-EMA trades "easily" captured more than 1:2 (two NIFTY examples). One opening-range example is shown losing.
- Quasimodo "works well" in forex and crypto: no evidence given.
- Backtesting traps gives "thousands" of them; no counts or results shown.
- The bibliography lists Bigalow, Douglas, Murphy, Edwards and Magee, Nison, Darvas, and ChatGPT (OpenAI) as sources.

## Risk, sizing and psychology
Risk management is set as limits per trade (stop-loss, position size), checking reward against risk before each trade
(minimum 1:2), monitoring and contingency plans. Position size is computed from the stop distance; risk only a small
share of capital per trade. The trailing stop is "the golden tool": it locks in gains as price moves your way, but too
tight a trail stops you out on noise, so use a wider trail or combine with sizing. Stop for the day after two
stop-losses. Psychology chapter: discipline, patience, emotional control, follow the plan, learn from mistakes; avoid
FOMO on breakouts. Practise on past charts (30 days for the opening strategy, one month for ADX-EMA) and on a simulator
before real money.

## How it relates to our research
- **Already rejected (the core of the book):** candle patterns (R87, R87B daily 0 of 25, R85 MCX), inside bar (R71 P1),
  the 15-minute opening-range breakout (R3, R23, R49, R92), ADX/DMI and EMA trend entries (OBUY_GB TI-09 / TI-03, R29,
  H43), structure breaks / Quasimodo (R90, R62b, R71 P8), trap fades and sweeps (R86, R87, R91, R61b), support /
  resistance and level breaks (R20b, R20c, R22, R89). FINDINGS "What is predictable? (R16)" and "Every indicator at
  once (B2)": direction on NIFTY / BANKNIFTY intraday is a coin flip; option costs (about Rs 70 NIFTY / Rs 76 BANKNIFTY
  a round trip, R102) plus decay take the small edges.
- **Agrees with us:** waiting for a candle close rather than a touch reduces false breaks (R20c: true, but the wait
  costs 14-18 points); higher timeframes are not more predictable though (R87B). Breakouts beat fades (R86: "keep
  trading breaks, not fades") - consistent with the book's Darvas / Donchian-style entries, and our only chart arms
  are Donchian breaks (R51, R72).
- **Contradicts us:** "stop after two losses" - R75 tested exactly that on Donchian (no new entries after 2 losses in a
  row) and it failed; R19c's daily loss cap made things worse. "Trade with yesterday's trend" - R89's daily trend filter
  did not cut false breaks; R102's daily momentum reversed in its holdout. "Confirm the break, then enter" - R73 found
  that waiting after a Donchian break drops the move.
- **Untested here:** option chain, VWAP, CPR, Supertrend and option selling (R103) are not in the book.

## What is new to us
- Trendline breaks as such (a fitted line through lower highs, an angle filter near 45 degrees, reject above 60): we
  have tested swing-structure breaks but never a drawn trendline with an angle rule.
- An ADX band with an upper cap (20-50) plus a prior-day trend filter and a pullback-to-EMA(14) entry: each piece is
  close to something rejected, but this exact combination was not run.
- 52-week high / low as a direction filter for intraday index trades (calls only near a 52-week high): tested only as a
  stand-alone swing rule.
- Bear-trap reclaim of the day low with a futures-volume spike and a larger target (the previous day's high): the only
  candle fact that held out of sample in R87 is this shape on 1-minute NIFTY, so a version with a bigger target or a
  cheaper instrument (the future) is the one idea here with a measured base.
- A "box length" filter on breakouts (longer consolidation -> bigger move) and a futures-volume confirmation on Donchian
  breaks: not tested on our arms.
- Fixed range-multiple targets (1x / 1.5x / 2x of the setup bar) as partial exits.

## Could not read
The chart figures (Fig 3.1 to Fig 5.3.8) are images; their drawn entries, stops, targets and oscillator names were not
readable from the text layer (OCR covered only the cover pages 1 and 3). All rules above come from the text.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-porwal-01`: tested (R104): IDX: 671 trades, win 42%, net -Rs 164,067 design, holdout -Rs 71,837 (fail); MCXI: 326 trades, win 49%, net -Rs 235,896 design, holdout -Rs 40,867 (fail)
- `book-porwal-02`: tested (R104): DLY: 80 trades, win 28%, net -Rs 136,157 design, holdout -Rs 56,444 (fail)
- `book-porwal-03`: tested (R104): DLY: 124 trades, win 44%, net Rs 88,560 design, holdout -Rs 259,614 (fail); MCXD: 136 trades, win 33%, net Rs 3,047 design, holdout Rs 15,512 (fail)
- `book-porwal-04`: tested (R104): DLY: 1 trades, win 0%, net -Rs 839 design, holdout Rs 0 (fail)
- `book-porwal-05`: tested (R104): MCXI: 3 trades, win 33%, net -Rs 6 design, holdout -Rs 233 (fail)
- `book-porwal-06`: not re-run (R104): covered: R23 first-15-minute break (-Rs 71,624 on 1,171) and R92 by day type (0 of 257); the prior-day trend and EMA bias are day-type filters R92 covered
- `book-porwal-07`: tested (R104): ARM: delta vs the arm -Rs 40,946 design (fail)
- `book-porwal-08`: tested (R104): IDX: 1115 trades, win 25%, net -Rs 123,184 design, holdout -Rs 72,134 (fail)
- `book-porwal-09`: tested (R104): IDX: 756 trades, win 46%, net -Rs 34,592 design, holdout Rs 7,493 (fail); MCXI: 124 trades, win 57%, net Rs 117,287 design, holdout -Rs 90,018 (fail)
- `book-porwal-10`: not testable (R104): index rule needs futures minute volume (57 sessions only); the price part (fade the day-high break) is covered by R86 (6 of 6 lost) and R91
- `book-porwal-11`: not testable (R104): index rule needs futures minute volume (57 sessions only); the price part (day-low reclaim) is R87's held fact, below option cost
- `book-porwal-12`: tested (R104): ARM: delta vs the arm -Rs 35,929 design (fail)
