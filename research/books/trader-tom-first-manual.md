# The First Trading Manual: Foundation Course and Strategies - Tom Hougaard ("Trader Tom") (2018)

Source: trader-tom-first-manual.txt, 183 pages, read in full on 25 Oct 2026 (text layer for every text page; OCR file for
the pages that are only chart screenshots: 18, 29, 32, 40, 48, 60, 71, 74, 158, 179, 181, 183; the OCR of those holds
only axis labels and broker toolbar noise, so the charts themselves could not be read).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A free beginner's manual (dated London, 18 Aug 2018) by a UK-based Danish day trader of index CFDs and spread bets.
It covers the 24-hour market clock, Dow-theory trend reading, a 3-bar swing trend tool and trendlines.
It teaches eight "essential" candle shapes, the engulfing bar as its main day-trading entry, and fake-outs.
It adds a mechanical 4-bar fractal entry, a stochastic divergence filter, position sizing and scaling in or out.
The last third is forex basics, spread-betting mechanics, two case studies and trading psychology.

Important for the caller's question: **the book teaches no opening-gap, gap-fill or opening-range strategy.** Gaps
appear only inside the daily-chart engulfing pattern (an open above the prior close that then closes below the prior
open), and the only "open" ideas are clock notes (the DAX open at 07:00 London gives a clue to the day; the first
minutes of the 14:30 US open are whippy). The comparison with our gap and ORB facts is in "How it relates", below.
Most worked examples are on EUR/USD, GBP/USD, EUR/JPY and the Dow; the DAX and FTSE appear in the clock and sizing
examples.

## Methods, rules and setups

### 1. The 24-hour trading cycle and when to trade (ch. "The 24-Hour Trading Cycle", p. 14-22)
- Entry: no entry rule. Times are London: FX volatility rises after 06:00; DAX futures open 07:00 and hint at the day;
  European cash opens 08:00 and the next 2-3 hours are the first busy period, where the author finds good setups;
  by 10:00 the first two hours are done and volatility fades; after 11:00 Europe goes quiet ("relatively safe to go to
  lunch"); 13:30 US data, 14:30 US open (first few minutes whippy and confusing), 15:00 second-tier US data, 16:30
  European close, 19:00 FOMC statements, 21:00 US close. Oil is more active in US hours.
- Stop / target: not stated.
- Filters: check the economic calendar each morning; act only on "red-flagged" (high-impact) releases. Earnings
  season (January, April, July, October) brings big swings in and out of hours.
- Market and timeframe written for: European and US indices and FX, intraday.
- Signal source: none / discretionary (clock and calendar). Maps to: NSE as "trade the first hours after 09:15, expect a
  midday lull, know the event calendar"; MCX crude/natural gas as "more activity in the US session"; crypto no.
- Mechanical? partly (a time window and a news blackout are mechanical).
- Our research: agrees on shape. DEEP_NIFTY_BANKNIFTY.md §0 / FINDINGS R102: the day is front-loaded; by 10:30 NIFTY
  has made a median 61% of its day range (BANKNIFTY 65%), and the first 15 minutes run about 8x the midday rate per
  minute. But busier is not profitable: R92 (HUNT_R92_ORB_TIME.md) found every entry hour loses for every ORB arm
  (0 of 257 cells). EVENT_SHIELD.md: BANKNIFTY day range is 1.48x normal on RBI/Budget days (15 sessions); US CPI/Fed
  nights give bigger Indian gaps (median 0.35% vs 0.23%, 39 sessions) but not bigger days; pausing arms on event days
  was not an edge.

### 2. Check Asia (and do not trade one index off another) (p. 16, 21-22)
- Entry: if Wall Street closed down and a weak European open is expected, but Asia is up overnight, look for buys
  instead. Australian miners overnight feed into the FTSE.
- Counter-rule: do not short the DAX just because the Dow is weak, nor buy the Dow because the DAX is strong; two
  indices can disagree. A currency and its stock index are not reliably correlated.
- Stop / target: not stated.
- Market and timeframe written for: European index open.
- Signal source: underlying price (other markets' overnight moves) as a bias. Maps to: NSE via US close, Asia morning,
  GIFT Nifty; MCX via overnight COMEX / NYMEX; crypto not discussed.
- Mechanical? partly (a sign-of-Asia bias is mechanical; "look for buys" is not).
- Our research: tested and rejected. HUNT_H27.md: US, ADR and Asian cues line up with the 09:15 gap (correlation about
  0.5-0.65) and are priced into it; 234 option variants, none passed; the best (fade the Nasdaq-100 cue on BANKNIFTY)
  went from +Rs 135 to -Rs 218 a day in the holdout; 0 of 240 index-level tests held. R74 lists global cues as "priced
  into the gap". The author's own counter-rule (indices can diverge) fits R72 only partly: on our indices NIFTY's
  candle explains 51-90% of the others' (R88), so they rarely disagree.

### 3. Dow-theory trend and the trend-change sequence (ch. "Basic Technical Analysis", "Principles of Price Action & Trend", p. 23-33, 55-66)
- Definitions: uptrend = higher highs and higher lows; downtrend = lower highs and lower lows; no clear sequence =
  range-bound.
- Trend change (the author's taught definition): a lower high, then a lower low, then a trade below that lower low. A
  lower high plus lower low alone is only a "warning" (often a two-leg ABCD pullback). If the warning fails and price
  passes the old swing high, that is a "continuation buy".
- Stop: not stated beyond "take the loss quickly if wrong".
- Target / exit: not stated.
- Filters: a trend change on a small timeframe (5-min) is not a change on a higher one (60-min).
- Market and timeframe written for: any market and timeframe.
- Signal source: underlying price. Maps to: NSE index spot/futures, buy ATM/1-ITM CE or PE; MCX futures; crypto yes
  (with stop and lock).
- Mechanical? yes once swing points are defined (e.g. fractal pivots).
- Our research: HUNT_R71_CHART_PATTERNS.md P8 "swing structure (HH + HL)" with 2-bar pivots: final period -Rs 40,109 on
  374 trades (fail; re-tune positive in design, also failed). R90 (Almazov BOS / CHoCH structure entries): 0 of 99 pass.
  R62/R62b break-of-structure: failed. Not tested as the exact three-step "break of the lower low" sell.

### 4. ABCD two-leg correction (p. 56, 59-63)
- Entry: implied - in an uptrend, a two-leg pullback (A-B down, B-C up, C-D down) is a correction, not a reversal; buy
  when the market passes the old swing high (the "continuation buy").
- Stop / target: not stated.
- Signal source: underlying price. Maps to: NSE/MCX/crypto as above.
- Mechanical? partly (needs a swing rule).
- Our research: HUNT_R4_GANN_FIB_MAGIC.md tested ABCD (5% tolerance, entry on the touch of D, 5/15/60-min and daily):
  10,927 design trades, net -Rs 227 a trade against -Rs 180 for random twins; BH q 1.00. The book's entry (break of the
  old high, not the touch of D) was not tested.

### 5. Fade the short-term trend in the direction of the long-term trend (p. 43-54)
- Entry: when the daily trend is up and the 60-min (or 30-min) trend is down, look to buy the 60-min dip ("fading the
  60-min trend in the direction of the daily trend"); in a daily downtrend, sell the 30-min rallies.
- Stop / target: not stated here (entries come from the candle and fractal tools).
- Filters: trend on the bigger timeframe by swing structure, a moving average or the 3-bar swing.
- Market and timeframe written for: FX and indices, 30-min/60-min against daily.
- Signal source: underlying price. Maps to: NSE (daily trend of NIFTY/BANKNIFTY, 15/60-min pullback, buy option);
  MCX yes; crypto yes with stop and lock.
- Mechanical? partly (the pullback entry trigger is not fixed; with the 4-bar fractal it becomes mechanical).
- Our research: no exact test. Nearest: R89 PDHL (HUNT_R89_PDHL_BREAK.md) added a daily + 15m trend filter and it did
  not cut false breaks and lowered the win rate; R101 filters that raised the win rate lowered the net; SWING_EXITS /
  SWING_DEEP: a "trend pullback to 20/50 EMA + hammer/engulfing" stock swing rule was flat at best (top-50 large caps,
  fixed 10 days: 1,020 trades, +0.2% a year, 6 of 11 years positive, a -37.8% drawdown) and lost with the app's stop /
  target exits. On the index, direction after any trend state is a coin flip at 15-60 minutes (R16, B2, R88).

### 6. Trend persistence and how trends end (ch. "Price Action & Price Behaviour", p. 34-54)
- Claims (principles): price is the only truth; "value" does not exist (never buy because something looks cheap);
  trends are more likely to continue than reverse; trends rarely stop without warning (price spike, volume spike,
  euphoric media, or a slide into a range); the mentor's line that 90% of a move comes in the last 10% of the trend's
  time; a trend ends at a level that matters to investors (old high/low, round number), on a volume spike, or at an
  emotional extreme (the Bitcoin top at Christmas 2017, 70% lower six months later). Markets alternate ranges and
  trends. A 200-day moving average on the Dow since 1960 beat every other tool shown at a 2010 seminar.
- Entry / stop / target: none.
- Signal source: underlying price/volume. Maps to: NSE/MCX/crypto as context.
- Mechanical? no (principles).
- Our research: mixed. Trend continuation over reversal shapes agrees with R87 ("reversal" shapes at trend extremes
  mostly mark continuation). Round numbers as reversal points disagree with R4 (round levels were broken through more
  often than random levels). Volume-spike endings: R87 climax/absorption footprints carried no signal; R8 absorption
  levels broke 58-68% of the time. 20-year daily momentum on NIFTY/BANKNIFTY reversed in the R102 holdout (+5.8 bps a
  day in design, -9.4 bps in 65 holdout days). 200-day MA as a trend tool: only tested inside stock swing rules
  (SWING_DEEP, pullback above the 200DMA lost in most cells); not as an index filter.

### 7. Three-bar swing chart / 3-bar break (ch. "Trend Indicators" and "Daily Work Routine", p. 67-74; from Bryce Gilmore)
- Rule: take the highest high and lowest low of the last 3 bars (daily or weekly). A trade below the 3-bar low turns
  the trend down; above the 3-bar high turns it up. The author keeps the level as the dividing line. Weekly version is
  set on Sunday night as the week's bias; daily version gives earlier warnings.
- Stop / target: not stated (it is a trend/bias tool).
- Filters: on charts below daily the signals come faster and are less reliable (the author's warning).
- Market and timeframe written for: FX, daily and weekly bars.
- Signal source: underlying price. Maps to: NSE daily NIFTY/BANKNIFTY as a bias for intraday option buys; MCX futures;
  crypto yes (stop and lock needed).
- Mechanical? yes. The author calls it the best purely mechanical trend indicator he knows.
- Our research: it is a 3-bar Donchian channel. Not tested at 3 bars. Our Donchian 20 on 5-minute index bars (R51)
  passed thin: final +Rs 48,689 on 1,775 trades (+Rs 55,439 after cleaning), carried by FINNIFTY; daily Donchian 55 on
  NIFTY/BANKNIFTY futures and options lost (SWING_EXITS). As a daily bias filter it is untested.

### 8. Trendlines and the copied trendline (p. 70-71)
- Rule: connect a low and a higher low; a break below is, in theory, a sell signal (mirror for a falling line). The
  "little secret": draw one trendline, copy the same slope onto other highs or lows to find parallel support and
  resistance where you can buy or sell.
- Stop / target: not stated.
- Signal source: underlying price. Maps to: NSE/MCX/crypto.
- Mechanical? partly (which two points to join is a choice).
- Our research: not tested by us as trendlines. Static levels (R20b) were no better than random lines; Gann 1x1 angle
  lines (R4 G5_ANG, 18,339 trades) lost -Rs 113 a trade net.

### 9. Weekly 89-period moving average trend (ch. "Understanding Chart Time Frames", p. 111-114)
- Rule: above the 89-period MA on the weekly chart = uptrend on that timeframe; if price straddles it, there is no
  trend. Daily chart for the 3-day rule; 1-hour and 4-hour for swing patterns and support/resistance; 10 and 15-min to
  trade after preparation; drop to 5 or 1-min for timing only.
- Signal source: underlying price. Maps to: NSE weekly/daily index; MCX; crypto.
- Mechanical? yes.
- Our research: not tested by us at 89 periods (R29 EMA ribbons and R51's EMA 9/21 crosses failed; different use).

### 10. The Essential 8 candle patterns (ch. "Candlestick Charting", "The Essential 8 Candle Patterns", p. 75-102)
Signal source for all: underlying price (the index's own candle). Market: any; timeframe: any (5-min to daily).
Mechanical? partly: shapes can be coded; the author always adds "context" (trend, level, news). Book gives no stops
except where noted.
1. Standard pattern: strong close, small tails both sides; a continuation signal in the candle's colour; says nothing
   about trend change.
2. Spinning top: small body, long tails; consolidation/pause. Worked trade: in a pullback inside a bigger downtrend,
   short only after a later bar trades below the spinning top's low.
3. Doji (three kinds): long-legged doji / doji star (no body), gravestone (closes at the low after trying higher),
   dragonfly (closes at the high after trying lower). After a long-legged doji in an uptrend: raise a long's stop to
   just under the doji's low, or take profit, or wait; he would not short it at once. Gravestone: more willing to short,
   wants a trade below its low first. Dragonfly: found at bottoms; tip - shorts will put stops just above its high, so
   place a buy order there. Doji star "pattern of three": the bar after the doji closes above the high of the bar before
   it.
4. Umbrella: small body, little or no upper tail, lower tail 2-3x the body (the text says "2/3 times"); hanging man at
   tops, hammer at bottoms. Needs confirmation: for the hanging man, a close below support within the next three bars
   or a trendline break.
5-6. Shaven top / shaven bottom (no tail on one side): continuation, may show trend weakness; minor.
7. Marubozu (no tails at all): common on short charts after data releases; the 50% level of the bar is "critical"
   (chart label, not explained). Worked trade: in a downtrend, a failed push up ending in a red marubozu closing at its
   low is a strong short.
8. Shooting star (reversal only at a new high in an uptrend) / inverted hammer (needs confirmation before buying).
- Two-bar engulfing (as a reversal): needs a reasonable prior trend. On daily bars, the bearish version opens above the
  prior close (a gap up) and closes below the prior candle's open; viewed as one bar it is a shooting star (bullish
  version = a hammer), so the author says it needs no further confirmation. Keep stops tight and be ready to reverse.
- Maps to: NSE index candles then buy the option; MCX; crypto.
- Our research: rejected. HUNT_R87_CANDLE_ANATOMY.md tested 43 shapes/patterns/footprints including every one of these:
  after any shape the index rose over the next 5 candles 43-62% of the time against ~51% for matched candles; 0 of 4,049
  shape x context cells passed; a long wick is not a defended level; reversal shapes at extremes mostly mark
  continuation; daily/30m/60m shapes 0 of 200 in design. The one near miss, SENSEX 15m gravestone doji -> put (+Rs 14,373
  on 71 trades), failed walk-forward. MCX: R85, hammer, shooting star, engulfing and marubozu carry no tradable meaning.

### 11. Engulfing bar for day trading (p. 105-110, 114)
- Entry: body of the current bar engulfs the body of the previous bar (the example shows the whole previous candle
  engulfed). Three entries: aggressive (enter while the bar is forming, exit if it fails to engulf), normal (on the bar's
  close), cautious (when the next bar trades through the engulfing bar's high/low, e.g. a buy-stop above its high). Used
  as reversal or continuation; best when it ends a correction against the trend.
- Stop: not stated in words; the author says the bar shows "how much to risk", and his 10-min worked example implies the
  far end of the engulfing bar. Long "road map" bars: trade smaller size rather than skip.
- Target / exit: no money target (aim to ride a runner). Move the stop to breakeven if price runs at once; trail it in
  the trend's direction; tighten on a doji; tighten or take profit if volume spikes into old support/resistance; else
  be patient - a good 10-min engulfing trade can last an hour or more.
- Filters: trend direction; 10-min or higher on FTSE, DAX, S&P 500 and Dow.
- Scalping variant (5-min, p. 114): when an engulfing trade is stopped out, a move the other way often follows; he
  reversed and it paid.
- Market and timeframe written for: index CFDs, 10-min (5-min for scalps).
- Signal source: underlying price. Maps to: NSE index 10-min bars, buy ATM/1-ITM CE/PE; MCX futures; crypto with stop
  and lock.
- Mechanical? yes (with the stop assumption).
- Our research: R87 (engulfing among the 12 two/three-candle patterns, no edge); R87B: exiting a position on an
  opposite engulfing candle HURT (ORB reconstruction -Rs 42,729; Donchian -Rs 29,944 in design); SCALP17: an
  engulfing "our way" barely changed outcomes. The "failed engulfing -> reverse" idea is not tested by us.

### 12. Fake-out and fake-down (p. 103-104)
- Entry: price breaks above a previous high (fake-out) or below a previous low (fake-down) and then reverses back into
  the range; trade back into the range (diagram: buy after the fake-down).
- Stop: beyond the fake extreme (diagram).
- Target: the other side of the range (diagram).
- Filters: none stated; he says it works on many timeframes, found by studying thousands of charts.
- Signal source: underlying price. Maps to: NSE/MCX/crypto.
- Mechanical? yes once "previous high/low" is fixed.
- Our research: rejected in several forms. HUNT_R3_ORB_FAMILY.md: ORB Sweep and Range Fade (they bet the break of the
  opening range fails) lost Rs 5,05,954 (3,156 trades) and Rs 5,10,978 (3,512) on BANKNIFTY + NIFTY, 0 of 6 years in
  profit, and lost on unseen indices. HUNT_R91_SMC_SWEEP.md (fade a failed break of a 1h swing): 0 of 63. R89 PDHL
  "break and reclaim": lost like the break itself. R87: stop runs beyond the day's high are followed by more upside, not
  a reversal.

### 13. Trendline break after a fake-out (case study "Stanley the Intraday Trader", p. 159-160)
- Entry (long): on a 1-min chart, draw a falling line touching 3 or 4 tops; wait for price to dip below an old low, turn
  back up, and break the trendline; buy.
- Stop: below the last low; if that is far, cut the stake.
- Target / exit: not stated; positions closed by day end.
- Filters: risk at most 1% of the account per trade; stay out of range-bound markets and before important news.
  Written for EUR/JPY, GBP/USD, EUR/USD; a handful of signals a day.
- Signal source: underlying price. Maps to: NSE 1-min index then option; MCX; crypto.
- Mechanical? partly (the trendline choice).
- Our research: not tested by us exactly; the fake-down part is the R3/R91 family above (failed).

### 14. Four-bar fractal entry (ch. "Trade Entry Technique 4 Bar Fractal", p. 115-118; credited to Dr David Paul)
- Entry: buy when the current bar's CLOSE is above the HIGH of the previous bar AND above the HIGH of the bar three bars
  back (bars counted 1 = current, 2 = previous, 4 = three back). Sell mirror: close below the low of the previous bar
  and of the bar three back. Signal on the bar's close.
- Stop: not stated. Target / exit: not stated (the author warns of many small wins and losses from being stopped).
- Filters: any timeframe; longer timeframes give bigger signals. Best combined with other tools (divergence).
- Signal source: underlying price. Maps to: NSE index 5/15/60-min then option; MCX futures; crypto with stop and lock.
- Mechanical? yes, fully (entry only).
- Our research: not tested by us. Closest: R71 P7 "three-bar reversal at the day high/low" lost (-Rs 1,67,689 on 855
  final trades) and R4 G3 Gann 2-bar swing lost -Rs 172 a trade net (6,227 trades); R52 R4-8 (Gann 2-bar swing, 30-min
  exit) failed only the same-side twin test.

### 15. Divergence strategy with the 4-bar fractal (ch. "Divergence Strategies", p. 119-126)
- Entry: stochastic oscillator on price. In a downtrend, price makes a lower high while the oscillator makes a higher
  high; the author reads this as the trend about to continue (this is what others call hidden divergence). Wait for a
  4-bar fractal sell to confirm and enter. Mirror for buys in an uptrend.
- Stop / target: not stated.
- Market and timeframe written for: Dow 5-min (illustration), GBP/USD 4-hour (examples).
- Signal source: underlying price (an oscillator computed on the underlying). Maps to: NSE/MCX/crypto.
- Mechanical? yes once stochastic settings and pivot rules are fixed (the book gives no settings).
- Our research: not tested with stochastics. HUNT_R95_CCI_SIGNALS.md (CCI, includes divergence labels): the divergence
  labels made +Rs 12.0 lakh when entered where drawn (hindsight) but -Rs 3.4 lakh when entered once known (5 candles
  later); 0 of 288 for the indicator as given. Stochastic was one of B2's 469 inputs (direction stayed a coin flip).

### 16. Harmonic retracement (case study "David the Swing Trader", p. 156-158)
- Entry: copy the size of the most recent pullback (point 1) onto the latest high (point 2); place a limit buy where an
  equal-sized pullback would end (example EUR/GBP 15-min, buy at 0.8945).
- Stop: a stop order is placed with the entry, level not stated. Target: sometimes a limit order, level not stated.
- Filters: only trending markets (4-hour and 1-hour charts), "confluences".
- Signal source: underlying price. Maps to: NSE/MCX/crypto (limit order on the future; for options, a trigger).
- Mechanical? yes for the entry.
- Our research: not tested by us as equal-leg pullbacks; R4/h37 Fibonacci retracement entries (e.g. golden pocket
  48,227 trades, -Rs 177 a trade) were no better than random.

### 17. Position sizing and the stop (ch. "Managing Your Risk", p. 127-129)
- Rules: never trade without a stop. Size = money at risk / stop distance (example: £1,000 account, 5% = £50 risk, DAX
  long 7920 with stop 7910 -> £5 a point; or 1% = £10 on GBP/USD with a 10-point stop -> £1 a point). Start at 1% or 2%
  per trade.
- Signal source: none (sizing). Maps to: NSE options as lots = risk budget / (premium stop x lot size); MCX; crypto.
- Mechanical? yes.
- Our research: agrees in spirit with the app's fixed-risk lots; R88/R16 say stops and size should follow the expected
  move size (predictable), which the book does not use. R101: 2 lots on a "better" signal earned what any extra lot earns.

### 18. Scaling out and scaling in (p. 130-131, 180)
- Scaling out (from Mark Douglas): exit a third at +20 points, a third at +40 points (or the next swing high) with the
  stop moved to entry, let the last third run. Recommended to start with, but the author does not do it himself.
- Scaling in: add only where the first position's stop can go to entry, so total risk does not grow; on the 4-hour
  chart, double at +75 points and take profit at +150: 225 points for 75 at risk, a reward-to-risk of 3 instead of 2.
  He adds to winners himself (trade 2: 80 points in double size).
- Signal source: none (trade management). Maps to: NSE/MCX options/futures; crypto as a step lock.
- Mechanical? yes.
- Our research: rejected on our arms. HUNT_R81_LIQUIDITY_EXITS.md: scale out half at 1R lost (X5); pyramiding into
  runners (X7) made +Rs 58k in design and lost about Rs 20k in the final; HUNT_H5.md: pyramiding raised gross but not
  net. R19/R19b/R100 profit locks on Liquidity also failed.

### 19. Daily routine, one instrument, demo accounts, deliberate practice (p. 72-74, 132-137, 175-177)
- The author swing-trades six FX pairs and day-trades two or three; checks weekly (3-bar bias), daily, 4-hour, 1-hour
  and 15-min charts each morning. Advice: focus on one instrument; use a demo only to learn the platform; Linda Raschke's
  drill of marking every major intraday high and low on a 5-min chart for a week.
- Signal source: none / discretionary. Mechanical? no.
- Our research: not applicable.

## Performance claims
- Audited annual trading competition since 2014: one year £25,000 to nearly £1m; every other year at least 100%; no
  losing years. No statements, dates or drawdowns shown in the book.
- Journalists saw a year of his statements (book not named in the text). No numbers.
- A client turned 25,000 Danish kroner into 180,000 in 4 months after a month of demo practice. Anecdote.
- Most of his 4-hour FX trades "will go at least 40 points" (used to justify scaling out). No evidence.
- Very successful position traders who add to winners make big gains on the 40% of trades that run. No evidence.
- With an average win 3 times the average loss, a 25% hit rate breaks even before costs (arithmetic, true).
- A coached trader with an 85% hit rate lost 20 times on the losing 15% what the winners made. Anecdote.
- Brokers disclose 76% and 79% of clients unprofitable; he places himself in the profitable 20% and the "1%" who live
  off trading. No evidence beyond the broker disclosures.
- The 3-bar swing "really works well"; the 4-bar fractal is "reliable" but brings many small wins and losses; engulfing
  trades "work well"; fake-outs "work very well in so many time frames". No statistics for any setup.
- Worked trades: a Dow long stopped for -25 points (missed a 100-point rise); a Dow short held 5 hours for +80 points in
  double size. Two trades, no record.
- Bitcoin fell 70% within six months of the late-2017 euphoria (used as an "emotional top" example).

## Risk, sizing and psychology
- A stop on every trade is the most important rule; size from account, risk % and stop distance; start at 1-2%
  (examples use 1% and 5%); 1% for frequent intraday trading (Stanley).
- Losses define a trader more than hit rate; "being right" matters less than making money; average winner vs loser is
  what counts.
- Four fears: being wrong, losing money, missing out, leaving money on the table. Untrained traders are scared when they
  should hope and hopeful when they should be scared.
- Main failure causes he lists: not following stops and not letting winners run; too little money, no tested strategy,
  wrong mind-set.
- Success = a strategy with positive expectancy plus the ability to apply it consistently; follow a written plan; a
  "justified loss" beats an "unjustified win"; do not chase every opportunity; avoid perfectionism and analysis paralysis.
- Energy, sleep and rest matter (he trades best on holiday); deliberate practice (30-120 extra minutes a day over 10
  years separates great from good, citing Ericsson).
- Demo accounts remove the emotional sting and give a false picture; move to small real size soon.

## How it relates to our research
- **Gaps (DEEP_NIFTY_BANKNIFTY.md §2.4, R102).** The book has no gap rule, so there is nothing to confirm or reject
  directly. For the record against its daily-engulfing description (gap up, then close below the prior open): on our
  daily lake a gap up of any size touches the prior close by the day's end 68.9% of the time on NIFTY (n 3,267) and
  68.4% on BANKNIFTY (n 3,121), but a 0.5-1% gap fills only 42% (NIFTY up, n 484) / 53% (BANKNIFTY up, n 687), and a
  2%-plus NIFTY gap closes on its own side 94-96% of the time. Filled gaps fill early (median 52 / 32 minutes after the
  open). B4 gap-fade rules: 0 passed (best: fade a NIFTY gap up of 0.3%+, +6.3 bps, n 1,262, q 0.53); MARKET_HOW and h42
  lost Rs 106-363 a trade trading the fill. R87's "gap day" context and R89B's gap filter added nothing. So a
  "gap-then-engulf" daily reversal is not something our data supports as a trade.
- **Opening range / "the open gives a clue" (R3, R49, R92).** The book's only opening idea is that the DAX's 07:00 open
  hints at the day. Our ORB family lost everywhere: R3 every arm 0 of 6 years in profit; R49 0 of 336 tests on the
  Nov-2024+ market; R92 0 of 257 cells by entry time, range length, day type and exit. The only opening idea with a
  thin pass is R52's H37-10 "open outside value and go" (294 trades, +Rs 48,772, paper candidate only). The book's
  "busy first 2-3 hours" matches R102's front-loaded day, but size, not direction, is what is predictable there (R16).
- **Already rejected by us:** candle shapes and engulfing (R87, R87B, R85), fake-out fades (R3 Sweep/Range Fade, R91,
  R89 reclaim), swing structure HH/HL (R71 P8, R90), ABCD (R4), Fibonacci-style retracements (R4/h37), global/Asia cues
  (H27), scaling out and pyramiding (R81, H5), round numbers as reversal points (R4), volume-spike/absorption endings
  (R8, R87), divergence labels (R95, CCI).
- **Agrees with us:** trends tend to continue rather than reverse at "reversal" candles (R87); never buy just because
  it is "cheap" (no value rule is in our arms); the day is busiest early (R102); stops always (CLAUDE.md, crypto rule);
  a high win rate is not the goal (R48: a high win rate on design became losses on unseen data; R101).
- **Not tested by us:** the 4-bar fractal entry; the 3-bar swing (3-bar Donchian) as a daily or weekly bias; the
  89-period weekly MA; the "failed engulfing -> reverse" scalp; the trendline-after-fake-out (Stanley); equal-leg
  "harmonic retracement" limit entries; stochastic hidden divergence + fractal; "fade the 60-min in the direction of
  the daily trend" as a fixed rule; a red-flag-news entry blackout on NSE (EVENT_SHIELD measured ranges, not a blackout
  rule on the arms that work).
- **Signal source.** Every method in the book reads the underlying's own price (or an oscillator on it), so all are
  default-compliant: signal on the index/future, buy the option chosen by a strike rule. None reads an option's chart.

## What is new to us
- **4-bar fractal (close beyond the highs of bar 2 and bar 4)** as an entry trigger: simple, fully mechanical, untested;
  it is a short-memory momentum trigger, so our coin-flip direction results (R16, B2, R88) make an edge unlikely, but it
  is cheap to pre-register on 15/60-min NIFTY/BANKNIFTY and MCX futures.
- **3-bar swing (3-bar Donchian) on daily/weekly bars as a bias** for the arms that already work (R51/R72 Donchian,
  Liquidity): a risk-lowering "skip against the daily/weekly 3-bar trend" filter would have to start in shadow for live.
- **Failed-setup reversal** (an engulfing entry stopped out -> reverse): the opposite-side follow-through after a
  failed signal is not a rule we have tested as an entry (R87B only tested candles as exits).
- **Hidden divergence (price lower high, oscillator higher high) as a continuation filter** rather than a reversal
  signal; our R95 tested regular CCI divergences.
- **Copied (parallel) trendlines** as levels; only static levels (R20b) and Gann angles (R4) were tested.
- Concepts we lack in Jarvis: "fade the short-term trend in the direction of the long-term trend" as a plain-language
  framing; the author's four trading fears; "justified loss vs unjustified win" as a journaling label.

## Could not read
- The chart screenshots (about 60 figures, including every worked example of the engulfing, fractal, divergence,
  spinning-top, marubozu, fake-out and trendline setups, and the DAX/EUR overlay, AUD/USD, EUR/USD, EUR/GBP and Dow
  charts). The text layer and the OCR give only captions and axis labels, so exact bar counts, stop placements and
  targets in those examples are unknown; where the book's words were vague the rules file says so in `assumptions`.
- Pages 18, 29, 32, 40, 48, 60, 71, 74, 158, 179, 181 and 183 had no text layer; their OCR is toolbar and axis noise.
