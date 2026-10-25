# A Practical Guide to Swing Trading - Larry Swing ("MrSwing") (year not stated; the case studies and chart captures are dated 2002)

Source: swing-practical-guide.txt, 74 pages, read in full on 25 Oct 2026 (text layer for the whole book; OCR for pages
63-68, which are SwingTracker chart screenshots with almost no readable text; the seven figures are charts only).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short, free promotional guide (from mrswing.com) to swing trading US stocks on daily bars, held a few days to a few weeks.
Its core is one mechanical rule set, the "Master Plan": buy a pullback in an uptrend (or short a rally in a downtrend).
Entry is a buy-stop just above the previous day's high, with a gap rule that delays the order on gap days.
Exit: half the position at +7% with a limit order, the rest on a daily trailing stop under the previous day's low, 4% max loss.
Candidates come from an end-of-day scan (price, volume, 10/20-day averages, 3 lower highs, Elder's Force Index, ADX); the rest is basic TA and adverts.

## Methods, rules and setups

All US dollar amounts are the book's (stocks priced roughly $12-$45 in its examples). For our markets we restate them
as percentages: the book's 6-cent offset is about 0.15-0.5% of those prices; its 50-cent gap is about 1.1-4%.

### 1. Universe filter (Ch. 4.4, 5.1, 10.1.1)
- Entry: not an entry; a precondition. Price at least $7 (Ch. 4.4) or at least $12 (Ch. 5.1 and the scan; "sometimes $7");
  20-day average volume at least 500,000 shares (sometimes 250,000 "depending on market conditions").
- Stop / target: n/a.
- Filters: the reason given is that cheap, thin stocks are easier for market makers to push around.
- Market and timeframe written for: NYSE / AMEX / NASDAQ stocks, daily bars.
- Signal source: underlying price/volume; maps to: NSE index options - not needed (NIFTY/BANKNIFTY are always liquid);
  stock options - the natural equivalent is our R58 liquidity screen (ATM CE+PE on >= 95% of sessions); MCX - use the
  liquid minis/near month only; crypto - BTC/ETH only.
- Mechanical? yes.
- Our research: R58 (`HUNT_R58_STOCK_OPTIONS_TOP10.md`) used a liquidity/coverage screen on the top 10 option names;
  liquidity was not the problem there, costs and signals were.

### 2. Trend definition (Ch. 4.2-4.3, 5.1, 8.7)
- The book defines an uptrend three slightly different ways:
  - chart reading: higher highs and higher lows (downtrend: lower highs and lower lows);
  - scan (Ch. 5.1 and 10.1.1): today's close above both the 10-day and 20-day simple averages, and (Ch. 5.1) the
    10-day average above the 20-day;
  - Ch. 8.7 and the case studies: rising 10- and 20-day averages, closes above the 20- and 50-day averages, more volume on
    up legs; the case studies' scan line is close above the 50-day and 20-day averages (no 10-day, no ADX).
- It also says an uptrending stock tends to find support at its 20- or 50-day average (resistance in a downtrend).
- Only simple averages of the close, lengths 10, 20 and 50.
- Signal source: underlying price; maps to: NIFTY/BANKNIFTY daily spot (yes), MCX near-month futures daily (yes),
  stocks daily (yes), BTC/ETH daily (yes).
- Mechanical? yes (the scan versions); the "support at the 20/50 average" idea is discretionary.
- Our research: SWING.md tested an EMA 20/50 daily trend for 1/3/5-day BANKNIFTY option holds: index direction right
  48-61% and option losses in both years (e.g. hold 1 day: 165 trades, -Rs 120,912 ATM, Feb24-Feb25). SWING_DEEP.md:
  EMA 20/50 bullish cross on F&O stocks +2.7% CAGR (1,511 trades, fixed 10d) against random stocks -0.9%; on index
  futures inside the coin-flip band. R102 (`DEEP_NIFTY_BANKNIFTY.md` 6.10): the 20-year daily momentum (+5.8 bps NIFTY,
  +12.4 bps BANKNIFTY a day) reversed in the holdout (-9.4 / -24.6 bps, 65 days).

### 3. The 3-day pullback / pull-up (Ch. 5.1, 10.1.1)
- Long setup: today's high below yesterday's high, and yesterday's high below the high of the day before (two lower highs,
  i.e. a 3-day decline). Short setup: two higher lows (low above yesterday's low, yesterday's low above the day before).
- Signal source: underlying price; maps to: daily bars of any of our instruments.
- Mechanical? yes.
- Our research: closest is SWING_DEEP.md's "Pullback: >200DMA & 3 down closes" (closes, not highs; 200-day filter, not
  10/20). Index futures 2016-2026: NIFTY fixed 10d 109 trades, -Rs 283,401 a lot; BANKNIFTY fixed 10d 112 trades,
  -Rs 107,798 (both after costs; inside the coin-flip band). F&O stocks in cash: +14.8% CAGR fixed 10d (2,237 trades), and
  it picked better stocks than random in 9/11 years, but the same universe held equal-weight made +17.9% a year, and
  the edge was negative in 2025 and 2026. RSI(2) pullback in trend on BANKNIFTY options (SWING.md): too few trades
  (5-16 a year) and no consistency.

### 4. Elder's Force Index, smoothed 3 and 13 days (Ch. 5.1, 8.8, 10.1.1)
- Force Index = today's volume x (today's close - yesterday's close). The book smooths it with a 3-day and a 13-day
  moving average (type not stated; we read it as simple).
- Buy opportunity: 13-day average >= 0 (bulls own the longer battle) and 3-day average <= 0 (bears won the last few
  days), in an uptrend. Sell opportunity: 13-day <= 0 and 3-day >= 0, in a downtrend.
- Note: the book's SHORT scan (Ch. 10.1.1) repeats the long condition (FORCE3<=0 AND FORCE13>=0); Ch. 8.8 gives the
  mirror. We take Ch. 8.8 as intended and record the scan as a likely typo.
- Signal source: underlying price/volume; maps to: NIFTY/BANKNIFTY - the spot index has no volume, so it would need
  the near-month futures volume (rolls distort it near expiry; assumption); MCX futures - yes (exchange volume);
  stocks - yes; crypto - yes (exchange volume, venue-dependent).
- Mechanical? yes.
- Our research: not tested by us (no study uses the Force Index). Volume-based rules on the indices so far rest on a
  proxy: R87 notes its volume results use an ATM-option activity proxy that correlates only 0.07-0.15 with true
  futures volume.

### 5. DMI / ADX trend strength (Ch. 5.1, 8.9)
- Uptrend confirmed if ADX (the book says a 20-period value) is above 30 ("the higher the better") and +DI is above -DI;
  downtrend: ADX above 30 and -DI above +DI. The book says markets trend only about 30% of the time.
- Signal source: underlying price; maps to: daily bars of any of our instruments.
- Mechanical? yes.
- Our research: ADX only as an intraday filter, never on daily bars at 30: R43/R44 EMA 8/21 + ADX>15 on 5-minute index
  bars (no pass), R51 near miss EMA 9/21 + ADX20 (final +Rs 76-78k but it lost in design), R46 BTC EMA 8/21 + ADX>15
  (0 of 14). Daily ADX > 30 as a swing filter: not tested by us.

### 6. FORCESWING LONG / SHORT scan (Ch. 10.1.1; the case studies in Ch. 11)
- LONG = 20-day average volume >= 500,000 AND close > 12 AND close > SMA10 AND close > SMA20 AND two lower highs AND
  3-day Force average <= 0 AND 13-day Force average >= 0 AND ADX(20) > 30. Run after the close.
- SHORT = the same with close below SMA10 and SMA20, two higher lows, ADX(20) > 30 (Force condition: see method 4).
- The author picks about 25 names a day from the results and adds "only trade what you like" (a discretionary final pick).
- Signal source: underlying price/volume; maps to: NIFTY/BANKNIFTY daily (with futures volume), MCX daily futures,
  stocks daily (the R58 universe), BTC/ETH daily.
- Mechanical? partly: the scan is exact; the final pick of which names to place is the trader's choice.
- Our research: not tested as a whole by us. Its parts: pullback-in-trend (method 3) lost on index futures and was
  thin on stocks; daily trend rules (method 2) gave no lasting edge.

### 7. Master Plan entry: buy-stop above the previous high, with the gap rule (Ch. 6.3, 6.4, 6.7)
- Signals are found after the close; orders go in the next morning.
- No gap (open within $0.50 of the prior close): place a buy-stop $0.06 above the previous day's high a few minutes
  after the open. Buying a little after the open is better, the book says, to let the market "breathe".
- Gap of $0.50 or more: the entry is re-based on the NEW day. A gap in the trade's direction (gap up for a long):
  wait 30 minutes; a gap against it (gap down for a long): wait 5 minutes; then buy-stop $0.06 above the high made so
  far today.
- Not filled: repeat the same process each morning for up to 5 trading days.
- The point, per the book: you only get in if price first moves your way; continued pulling back means no trade.
- Signal source: underlying price; maps to: NIFTY/BANKNIFTY - the index buy-stop triggers a 1-ITM / ATM monthly CE buy
  (PE for shorts) or a futures entry; MCX - futures or the 1-ITM option; stocks - the R58 option or the stock.
- Mechanical? yes, once the $ amounts are made into percentages.
- Our research: the trigger is a previous-day-high break. R89 (`HUNT_R89_PDHL_BREAK.md`) tested PDH/PDL breaks on
  1-minute index charts: 0 of 180 pass; Boss's rule was no better than random minutes (-Rs 3.07 lakh on 2,925 design
  trades vs twins -Rs 3.13 lakh). R89 had no prior-pullback condition and was intraday. Gap behaviour (R102 6.7-6.8): a
  0.5-1% NIFTY gap fills by the close 42% of the time (up gaps, n 484), and filled gaps fill a median 52 minutes after
  the open; that is the kind of early fade the book's 30-minute wait tries to sit out.

### 8. Initial stop: the higher of -4% or $0.06 under the signal day's low (Ch. 6.2, 6.5)
- Sell stop at whichever is HIGHER: 4% below the entry or $0.06 below the low of the day used for entry (the previous
  day without a gap; the new day's low before entry with a gap). So the stop is never more than 4% away.
- Placed with the profit order as an OCO pair; the book says this lets you skip watching the market during the day.
- Signal source: underlying price; maps to: put the stop on the index/future/stock price as written; on options the
  CLAUDE.md rules let us set it on the premium instead.
- Mechanical? yes.
- Our research: not tested in this form.

### 9. Profit target: half off at +7% (Ch. 6.2, 6.5)
- A sell limit for half the position at 7% above the entry. The book says the actual average win should be above 7%
  and the average loss under 4%. The target is a fixed % for every stock regardless of its volatility.
- Signal source: underlying price; maps to: on NIFTY (daily sd 1.316% over 2006-2026, R102) a 7% move is about five
  average days' sd; it would rarely be hit in a short swing; a percentage of ATR is the honest translation (assumption).
- Mechanical? yes.
- Our research: the part-exit-at-target + rest-on-trail structure was tested only intraday: MATRIX PINE (FINDINGS
  "MATRIX PINE"): the TP1 half-exit + breakeven doubled the loss on 2 lots. SWING_EXITS.md: adding the app's stop,
  target and profit-lock ladder to the stock swing portfolios made them worse in every one of the 24 portfolios.

### 10. Daily trailing stop for the second half (Ch. 4.4 step 5, 6.6, 6.8)
- Each morning: if $0.06 under the previous day's low is above the current stop, raise the stop to it (never lower it).
  On a gap day ($0.50+) wait (the text says 30 minutes for a gap down / 5 for a gap up on a long; the flowchart agrees)
  and use today's low instead. The second half is closed only by this trailing stop.
- Signal source: underlying price; maps to: daily bars of any instrument; the stop rests at the broker (SL/GTT for
  live, per CLAUDE.md).
- Mechanical? yes.
- Our research: a prior-day-low trail on daily bars is not tested by us. SWING_DEEP.md's 2-ATR stop + 3-ATR chandelier
  trail on the index pullback rules lost (e.g. NIFTY 3-down-closes: 124 trades, -Rs 498,240).

### 11. The short swing (Ch. 6.9, Appendix A)
- Exact mirror: downtrend + 2-3 day rally (pull-up); sell-stop $0.06 under the previous low; buy-stop the lower of +4% or
  $0.06 above the signal day's high; buy-limit for half at -7%; trail $0.06 above the previous day's high. Gap waits
  are mirrored (gap down = with the trade: wait 30 minutes; gap up: wait 5).
- Appendix A explains US short selling: margin account, borrowed shares, the (old) up-tick rule, 30% maintenance.
- Signal source: underlying price; maps to: NIFTY/BANKNIFTY - buy the PE (or short the future); MCX - short the future
  or buy the PE; stocks - buy the PE (cash shorts cannot be held overnight in India, as R58 notes); crypto - perp short or
  put, always with a stop and a profit lock.
- Mechanical? yes.
- Our research: SWING_DEEP.md found shorts the weak side on the indices (BANKNIFTY RSI(2) pullback, chandelier exit:
  long 92 trades +Rs 319,220 vs short 25 trades -Rs 238,674).

### 12. Volume reading table (Ch. 8.5, Figure 6)
- Reversal clues: above-average volume with little price movement; above-average volume after a huge advance or decline.
- Continuation clues: above-average volume with a strong move or a breakout; below-average volume with no movement.
- Strong uptrends have more volume on up legs than on pullbacks; high volume often marks tops and panic bottoms.
- Signal source: underlying price/volume; maps to: MCX futures and stocks (real volume); index only via futures volume.
- Mechanical? partly (the words "huge", "little" and "strong" need numbers).
- Our research: R86 big-player footprints 0 of 23; SWING_DEEP.md 4b volume breakouts lost in large and mid caps; R87's
  volume results rest on a weak proxy.

### 13. Equivolume boxes and the "power box" (Ch. 8.6)
- Richard Arms' chart: each bar is a box whose height is the range and whose width is the volume. Short, wide boxes
  (heavy volume, small range) show up at turning points; tall, narrow ones in trends. A "power box" (both height and
  width much larger than usual) confirms a breakout; a narrow box on a breakout casts doubt on it.
- Signal source: underlying price/volume; maps to: as method 12.
- Mechanical? partly; "much larger" is not defined.
- Our research: not tested by us as such. R84 found Breadth Donchian's best intraday signals were the shock bars 5-10x
  the usual size (+Rs 1.44 lakh on 142, a post-hoc note only), which is a range, not a volume, measure.

### 14. Up / Down / In / Out bars (Ch. 8.10)
- Each bar is coloured by its high and low against the previous bar: Up (higher high and higher low), Down (lower
  high and lower low), In (inside bar), Out (outside bar). A chart overlay; no trading rule is attached.
- Signal source: underlying price; maps to: any bar series.
- Mechanical? yes as a label; no rule given.
- Our research: R87 / R87B: no candle shape or 3-candle sequence on 1m-daily bars predicts direction (R87B 0 of 225);
  R85: inside/outside bars carry no tradable meaning on MCX. SWING.md: daily inside-day breakout on BANKNIFTY options
  was inconsistent (12-18 trades a year).

### 15. Market direction note (Ch. 6.2)
- When the whole market moves with your trades, a high share of them win; when it moves against them, more lose than
  expected and the stop limits the damage. No market filter is given.
- Signal source: underlying (the broad index); maps to: an index-trend filter on stock swings; for NIFTY itself it is
  the same as method 2.
- Mechanical? no (no rule given).
- Our research: R72 breadth (4 of 5 indices breaking together) is our strongest agreement filter, intraday; R58's
  relative-strength-vs-NIFTY daily rules lost (F2 family final -Rs 3.1 lakh on 915 trades).

### 16. Portfolio sizing: capital divided by 15 (Introduction, Ch. 9)
- Split the account into 15 equal slices (up to 20 if you can manage them; 2-3 to start); each trade gets one slice,
  growing as the account grows. Each day pick about 25 candidates, place stop orders for all, and let the first ones
  that trigger fill the free slots; in a cash account, orders beyond the cash simply do not fill.
- The book calls this risking 6.66% a trade; in fact that is the capital per trade; the money at risk is at most 4% of
  the slice, about 0.27% of the account.
- Signal source: n/a (sizing); maps to: not to one-lot index options. For Boss it maps to "one lot per signal, never
  more than N open", with the account stop rule (-Rs 40k drawdown / -Rs 13k a month) on top.
- Mechanical? yes.
- Our research: R84 (Breadth Donchian positions per signal): capping correlated positions cut the worst day 32-77% but
  kept 28% of the net at best and none beat a random subset. SWING_DEEP.md 6d: on the stock pullback portfolio, more
  positions and more risk per trade raised CAGR and drawdown together (10 positions at 1% risk: +10.8%, DD -43.7%).

### 17. Order handling (Ch. 4.4, 6.5, 7)
- Use OCO (one-cancels-other) exits; a broker that takes entry, stop and target together; an autotrading service for
  people who cannot watch the market. Not a strategy; a reminder that the plan is designed to run from resting orders.
- Our relevance: matches CLAUDE.md's "live stops and targets also rest at the broker (SL/GTT)".

### Not methods (described only)
- Japanese candlesticks (Ch. 8.4): a definition only; no pattern rules.
- SwingTracker / SwingLab / BestSwings / MasterSwings / MrSwing Lite and broker pages (Ch. 4.7, 7, 9, 10): product
  descriptions. The list of SwingTracker indicators (Bollinger, CCI, MACD, RSI, stochastics and more) comes with no
  rules. The book mentions QQQ / OEX / DIA / SMH Swings and an "OptionSwings" service, but gives no option rules.

## Performance claims
- "Thousands of investors" have used the Master Plan; reader testimonials (one says up over $5000 in one month). No
  evidence beyond quotes.
- Following the plan "will insure that profits exceed losses"; swing trading has "very little risk"; profits come
  "slowly, but surely" and the account will go up. No evidence given.
- A high percentage of winning trades because the chart patterns "have been back-tested" (Ch. 9). No backtest, sample
  size, period or win rate is shown.
- The average win should exceed 7% and the average loss stay under 4% (Ch. 6.2). Asserted, not shown.
- Markets trend about 30% of the time and move sideways about 70% (Ch. 8.9). No source.
- Three worked case studies (Ch. 11, spring 2002), all winners: DFXI +4.63% (half at the 7% target, half on the trail),
  PFG +2.49% (target never hit; trail), PTEN +9.37%. Three chosen examples, no losers shown; not evidence of an edge.
- Pages 63-68 show six more SwingTracker charts (GP, KTC, JWN, MYGN, one unreadable, WYE) with no trade results.

## Risk, sizing and psychology
- Discipline over prediction: "Confidence, Discipline, Focus and Patience"; the rules are mechanical so fear and greed
  have no say. Stops are always placed; stops only move up (long).
- Max loss 4% of a trade; 1/15 of capital per trade; positions that never trigger cost nothing.
- Prefers a cash (non-margin) account for longs but needs margin approval to short.
- Swing trading suits people who cannot watch the market: orders rest at the broker; the plan is checked once a day.
- "You trade people, not stocks": traders repeat the same mistakes, which is why chart patterns recur (asserted).

## How it relates to our research
- **Pullback-in-trend on daily bars (methods 2, 3, 6): mostly rejected on the indices.** SWING_DEEP.md: no index swing
  rule passed on NIFTY/BANKNIFTY futures 2016-2026 (walk-forward lost); the 3-down-closes pullback lost on both
  indices. SWING.md: no daily rule was right in both years for BANKNIFTY option holds; option buying lost for almost
  everything held 3-5 days (decay). R102: daily index direction is a coin at every horizon and the 20-year daily
  momentum reversed in the holdout.
- **On stocks the book's idea has some support, but not enough.** SWING_DEEP.md section 3: uptrend pullbacks picked
  better stocks than chance in 9 of 11 years (about +0.3% per 10-day trade), yet after costs the portfolio did not beat
  holding the same stocks, and the edge was negative in 2025-2026. R58: daily momentum/Donchian rules on stock
  OPTIONS lost (F5 final -Rs 5.5 lakh on 970 trades; costs about Rs 280 a lot round trip, about 2% of the premium).
- **Prior-high break entries (method 7)** are R89's subject intraday (0 of 180) and R51 / R72's Donchian arms (5-minute
  channel breaks). Those arms work intraday because the opening bar's break carries most of the profit (R101); the book's
  daily buy-stop is a different timeframe and has not been tested after a pullback.
- **Exits (methods 8-10):** a half-off target and a trail have not helped in our tests (MATRIX PINE; SWING_EXITS). The
  book's specific prior-day-low trail and the "higher of 4% or prior low" stop are untested.
- **Agreement / trend filters:** R73's Supertrend filter on intraday Donchian passed (final Rs 1,34,743 vs Rs 81,845);
  the book's "trade with the trend" is the same instinct on a slower clock. On MCX, agreement filters did not carry
  over (R82, R83).
- **Overnight:** our data says index drift is overnight (R102: close-to-close +0.051% a day vs open-to-close -0.035%,
  NIFTY, n 5,087), and SWING_DEEP.md found index swing profits came from overnight gaps while market hours lost. The
  book's multi-day hold keeps the overnight part, but its gap rule delays entries on gap days, which is when part of
  that move happens.

## What is new to us
- **Elder's Force Index (3- and 13-day averages)** as a pullback detector: never tested by us. On NIFTY/BANKNIFTY it
  needs futures volume; on MCX futures and stocks the volume is real.
- **The complete FORCESWING scan + Master Plan** as one daily system: two lower highs + Force 3/13 + ADX > 30 + close
  above SMA10/20, buy-stop above the prior high valid for 5 days, stop = higher of -4% / prior low, half at +7%, trail
  on prior lows. Our swing studies tested pieces (pullbacks, breakouts, trend) with time exits, not this package.
- **The gap-delay rule** (wait 30 minutes after a gap in your direction, 5 after one against, then re-base the trigger on
  the new day's high). Our gap studies faded or followed gaps; none delayed a daily setup's trigger this way.
- **An entry that expires** (the buy-stop stays live for up to 5 sessions, so most setups never trade).
- **Daily ADX > 30** as a trend-strength gate (ours used ADX 15-20 intraday only).
- **Equivolume "power box"** (range and volume both unusually large) as a breakout confirmation.

## Could not read
- Figures 1-7 (example charts: MYGN uptrend, VRSN downtrend, candlesticks, Equivolume, volume table, SwingTracker
  moving averages, Force Index) are images; their captions and the text around them were read.
- Pages 63-68 (OCR): SwingTracker screenshots for GP, KTC, JWN, MYGN, an unnamed chart (page 67) and WYE; only the
  tickers, dates and some indicator read-outs are legible; no trade details.
- The contents page lists "7.1 Interactive Brokers"; the body has only an optionsXpress section ("Error! Bookmark not
  defined" in the original). The two Ch. 6 flowcharts were read from their extracted text.
