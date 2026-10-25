# The Complete Guide to Day Trading - Markus Heitkoetter (2008)

Source: heitkoetter-day-trading.txt, 273 pages (printed pages i-xvii and 1-256), read in full on 25 Oct 2026 (text layer for
all prose; OCR for the image-only pages 3, 7, 18, 59, 60, 72, 99, 171, 209 and 244, which are the dedication, part title
pages, a forex quote screen, an IBM key-statistics screen and two weekly charts; the many chart screenshots have no text
layer, so only the author's prose about them could be read).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A beginner's manual by the founder of a US coaching firm (Rockwell Trading), written in January 2008.
Part 1 covers goals, money needed, risk tolerance, software, brokers and account size for US stocks, forex, futures and stock options.
Part 2 is a seven-step recipe for building a day-trading strategy: market, timeframe, approach, entry, exit, evaluate, improve.
It describes a dozen textbook indicators and trendline rules, then many stop, target, trailing, partial and time-stop exits.
Part 3 is psychology: seven mistakes, the trader's mindset, three "secrets", weekly goals and paper trading first.

## Methods, rules and setups

The book never discloses its own coached strategies (it sells them separately). What it teaches is a toolbox: one way to
draw trendlines, seven "popular approaches" with their standard settings, four ways to set stops and targets, trailing,
partial and time stops, and rules for judging a back-test. Every indicator below reads the traded instrument's own price,
which for us means the index (underlying price), never the option's chart. Dollar amounts are for one US contract or 100
shares; for reference the book's E-mini S&P is $50 a point, so its favourite $200 stop / $300 target is 4 / 6 points, about
0.27% / 0.41% of the index at about 1460 (our arithmetic).

### 1. Trend definition and trendline drawing (Step 3, "Technical Indicators", pp. 96-120)
- Entry: not a stand-alone entry. An uptrend is a series of higher highs and higher lows (downtrend the mirror). The
  book's 5-minute E-mini example starts the uptrend when price breaks a resistance level (1462.50) on high volume.
- Construction rules (stated precisely): an uptrend line needs two points, the low of the day and the first retracement
  (the first bar that fails to make a higher low); the line may only become flatter, never steeper; it must never run
  through price bars; it must stay close to the recent lows (if adjusting it would move it far away, the trend is
  treated as broken). Downtrend lines mirror this above the highs, starting from the first higher high.
- Stop: not stated for this method. Target / exit: the trend is over when the line is broken (in the example the
  uptrend ran 2 hours, 1462.50 to a high of 1472, broken at 1467.75); the first higher high after lower highs starts a
  downtrend line.
- Filters: a longer-lasting, more-often-tested line is more important, and its break means a bigger reversal.
- Market and timeframe written for: E-mini S&P, 5-minute bars, any market.
- Signal source: underlying price; maps to: NSE index options yes (draw on the index, buy the CE/PE by strike rule),
  MCX yes (on the future), crypto yes on BTC/ETH spot or perp (stop and profit lock required).
- Mechanical? partly: the drawing rules can be coded; "too far from the lows" needs a number we would have to choose.
- Our research: no study of ours draws trendlines. Nearest: `HUNT_R71_CHART_PATTERNS.md` P8 swing structure (higher high
  + higher low on 15-minute swings, buy the break of the last swing high) lost -Rs 40,109 on 374 trades in the final
  period (re-tune -Rs 34,242 on 372) and did worse than random entries (twin p 0.99); `HUNT_H25.md` tested "break of
  structure" among 9 chart patterns, 0 of 18 walk-forward families positive. Trendline breaks themselves: not tested by us.

### 2. Support and resistance (pp. 121-124)
- Entry: buy near support, sell near resistance that is unlikely to break; once broken, levels swap roles. A break that
  closes back inside is "not significant".
- Stop / target: not stated (used later as technical stops and targets, method 13-14).
- Filters: levels must have been tested repeatedly.
- Market and timeframe written for: E-mini S&P 5-minute.
- Signal source: underlying price; maps to NSE index yes, MCX yes, crypto yes (with stop and lock).
- Mechanical? partly (level choice is discretionary).
- Our research: already rejected. `FINDINGS.md` R20b: levels (previous-day high / low / close, pivots, swing zones, OI
  walls) were no better than random lines; mirrored random levels made more in 6 of 7 variants. R20c: two-candle breaks of
  levels moved +0.02 ATR14 in design and -0.004 to -0.020 ATR in the holdout. R89: previous-day high / low breaks, 0 of 180
  pass.

### 3. Trend channels (pp. 125-127)
- Entry: draw the trendline, then a parallel "channel line" through the opposite extremes. In a downtrend, sell at the
  primary downtrend line; in an uptrend, buy at the uptrend line.
- Target / exit: buy back (or sell) at the opposite channel line. Stop: not stated.
- Market and timeframe: any; intraday example.
- Signal source: underlying price; maps to NSE index / MCX / crypto as method 1.
- Mechanical? partly (needs the trendline algorithm of method 1 plus a channel width rule).
- Our research: not tested by us as drawn. Nearest fades to a band: `OBUY_GB.md` MR-05 TWAP stretch fade lost
  Rs -91,856 walk-forward (713 trades) and MR-02 Bollinger alert lost Rs -90,790 (2136 trades).

### 4. Choose the approach by regime: trend-following vs trend-fading (pp. 128, 140, 166)
- Rule: markets trend about 30% of the time and range the rest. Identify the regime first (trendlines), then use a
  trend-following tool (moving averages, crossovers, turtle breakout, MACD) in a trend and a trend-fading tool
  (Williams %R, RSI, Bollinger) in a range. Keep at least one approach for each regime. Also: if back-tests show
  trend-following failed recently and fading worked, trade the fading system (and vice versa).
- Stop / target: per the chosen tool.
- Signal source: underlying price; maps to NSE index, MCX, crypto alike.
- Mechanical? partly: the regime call is by eye in the book.
- Our research: contradicted. `FINDINGS.md` R16: trend vs range day from the first 30/45 min is not predictable
  (AUC 0.46-0.52). R3: ORB Sweep and Range Fade win only on trend days traded with the trend, known only after the close.

### 5. Close crosses a simple moving average (pp. 129-130)
- Entry: buy when the close moves above the moving average, sell when it dips below; shorter averages (5 or 10 periods)
  lag less than 40 or 200.
- Stop / target: not stated with the method (Step 5 exits apply). Exit implied at the opposite cross.
- Filters: only in trending markets (confirm with trendlines or another indicator); whipsaws in sideways markets.
- Market and timeframe: Amazon, 10-minute bars.
- Signal source: underlying price; maps to NSE index yes, MCX yes, crypto yes (stop and lock).
- Mechanical? yes (the trend filter is the vague part).
- Our research: rejected. `HUNT_H25.md`: price crossing SMA / EMA 9, 20, 50 and 200 on 1-60 minute index bars, SMA family
  15,120 variants, 2.5% net positive, Rs -145 a trade, 1 of 28 walk-forward families positive.

### 6. Moving-average crossover, 14 and 20 bars (p. 131)
- Entry: buy when the fast average (example 14 bars) crosses the slow one (example 20 bars) from below; sell on the
  reverse cross.
- Stop / target: not stated; exit implied at the opposite cross.
- Filters: none stated (the trend-only caution of method 5 applies).
- Market and timeframe: chart example, timeframe not stated.
- Signal source: underlying price; maps to NSE index, MCX, crypto.
- Mechanical? yes.
- Our research: rejected for the nearest settings. `OBUY_GB.md` TI-03 EMA fast / slow cross (5/20, 9/21, 13/34, 9/50 on
  3/5/15-minute bars): Rs -369,856 walk-forward on 4,526 trades. `FINDINGS.md` R29: EMA 9 x 21 cross Rs -110 a trade on
  1,785 holdout trades. `HUNT_H25.md` crosses 5/13, 9/21, 13/34, 20/50, 50/200: no family beats random. The exact 14/20
  pair: not tested by us.

### 7. Turtle trading, 20-bar breakout (p. 132)
- Entry: buy when price trades above the high of the previous 20 bars; sell when it trades below the previous 20-bar low.
- Stop / target: not stated (the book says exits must be added; its forex example ran 48 pips, $480, before retracing).
- Market and timeframe: EUR/USD intraday, timeframe not stated.
- Signal source: underlying price; maps to NSE index yes (this is our Donchian arm), MCX yes, crypto yes (stop and lock).
- Mechanical? yes.
- Our research: the one family here with a real record, and only with our own exits. `HUNT_R73_DONCHIAN_TREND_WAIT.md`:
  Donchian 20 on 5-minute index bars, 1-ITM, -15% / +30% / 30-minute exits plus a lock ladder, made Rs 81,845 on 1,781
  final-period trades but only Rs 9,998 in design; a Supertrend (10, 3) agreement filter lifted the final to Rs 1,34,743.
  `FINDINGS.md` R51: the same rule passed (+Rs 48,689 on 1,775 trades) but is thin: about zero at 2x costs, the profit is
  FINNIFTY, NIFTY and the puts lost. R72 Breadth Donchian is the stronger version. As a bare trigger with generic exits
  it loses (`HUNT_H25.md` Donchian 20 / 55 inside the "other indicators" family).

### 8. MACD signal-line cross, 12 / 26 / 9 (pp. 133-134)
- Entry: MACD = EMA 12 minus EMA 26; signal line = EMA 9 of MACD. Buy when the lines cross upward, sell on the downward
  cross (the book words it as the signal line crossing the MACD, but the chart reading is the usual cross).
- Stop / target: not stated. Market / timeframe: chart example. Filters: trending markets.
- Signal source: underlying price; maps to NSE index, MCX, crypto.
- Mechanical? yes.
- Our research: rejected. `HUNT_H25.md` MACD signal and zero cross, in the 25 "other indicators": 27,000 variants, 1.9%
  net positive, Rs -146 a trade, 0 of 50 walk-forward families positive.

### 9. Williams %R, 14 bars, 80 / 20 (pp. 135-136)
- Entry: on the book's 0-100 scale (close relative to the 14-bar high-low range), sell when the reading is above 80,
  buy when it is below 20.
- Stop / target: not stated. Filters: works best in sideways markets.
- Market and timeframe: intraday bars, 14-bar setting; example Google.
- Signal source: underlying price; maps to NSE index, MCX, crypto.
- Mechanical? yes.
- Our research: rejected. Tested in the same `HUNT_H25.md` family (Williams %R as given; no family beats random).

### 10. RSI 14, crossing 70 and 30 (p. 137)
- Entry: sell when RSI 14 crosses the 70 line from above; buy when it crosses the 30 line from below.
- Stop / target: Step 5 exits. Filters: sideways markets.
- Market and timeframe: intraday chart example (the buy came at the day's low).
- Signal source: underlying price; maps to NSE index, MCX, crypto.
- Mechanical? yes.
- Our research: rejected. `OBUY_GB.md` MR-03 RSI 30/70 reversal (RSI 9/14, 5/15-minute, 6 exits): Rs -82,004 walk-forward
  over 1272 trades. `HUNT_H25.md` RSI 30/70 and 50: no better than random. `FINDINGS.md` F1: 1-minute RSI leans the wrong
  way over the next 15 minutes and fades by 60.

### 11. Bollinger Bands, 21 bars, 2 standard deviations (pp. 138-139)
- Entry: buy when price moves below the lower band, sell when it moves above the upper band.
- Stop / target: not stated. Filters: sideways markets.
- Market and timeframe: E-mini S&P, 10-minute bars.
- Signal source: underlying price; maps to NSE index, MCX, crypto.
- Mechanical? yes.
- Our research: rejected. `OBUY_GB.md` MR-02 Bollinger alert candle Rs -90,790 walk-forward (2136 trades); `HUNT_H25.md`
  Bollinger breakout / re-entry / squeeze; `HUNT_R71_CHART_PATTERNS.md` P3 squeeze release lost -Rs 41,281 in the final.

### 12. First 15-minute bar direction, with a trendline stop or previous-day target (pp. 149-150, 157)
- Entry (two worked examples, not presented as a system): wait for the first 15-minute bar; if the market moved down,
  sell short (AAPL at $184.80); if it moved up, buy (E-mini at 1452).
- Stop: a trendline drawn from the previous day's high to this morning's bar high ($187.50 in the AAPL case).
- Target / exit: the previous day's high for a long (13.25 points, $662.50); then reverse short with the previous day's
  low as the target (another 21.75 points, $1,087.50).
- Market and timeframe: US stocks and E-mini S&P, 15-minute bars.
- Signal source: underlying price; maps to NSE index yes, MCX yes, crypto yes (with stop and lock).
- Mechanical? yes once the trendline stop is replaced by a fixed rule.
- Our research: the opening-bar family lost everywhere. `FINDINGS.md` R3 (ORB family 0 of 16 versions), R92 (0 of 257),
  `HUNT_R1_INTERNET.md` N11 first two candles and N12 first 5-minute candle direction; previous-day levels R89 (0 of 180).
  One wrinkle: R101 found the opening bar's break is the most valuable part of both Donchian arms.

### 13. Four ways to set a stop loss (pp. 143-150)
- Always place a stop immediately after entry; use bracket or OCO orders so a filled target cancels the stop.
- Fixed money (EUR/USD $100 = 10 pips); percent of price (0.5% on the E-mini NASDAQ); percent of volatility (50% of the
  average daily range from ATR, for markets whose volatility changes, e.g. grains); technical (trendline, support /
  resistance, pivots, Fibonacci, channel or Bollinger levels).
- Signal source: underlying price for all four; maps to NSE index (stop on the index, or on the option premium, which
  CLAUDE.md allows), MCX, crypto (stop mandatory).
- Mechanical? yes for the first three.
- Our research: a stop sized by volatility matches what we can predict (`FINDINGS.md` R16: the size of the next 30-60
  minutes is predictable, direction is not). The book's exact 50%-of-ATR stop: not tested by us. R89B: an ATR stop
  halved false breaks (33% -> 14%) but no variant turned positive (0 of 45).

### 14. Four ways to set a profit target (pp. 151-157)
- "Good traders use a stop loss; great traders use a profit target." Fixed money ($100 on 100 IBM shares); percent of
  price (1%); percent of volatility; technical (previous day's high / low, S/R, pivots, Fibonacci, channel lines).
- Percent targets are preferred when trading several markets (IBM needs 0.9% but Ford almost 15% for the same $100).
- Signal source: underlying price; maps as method 13.
- Mechanical? yes.
- Our research: `FINDINGS.md` R18: "exit at the next support / resistance" as an overlay, 0 of 60 overlays passed (post
  hoc it helped 9 of 10 arms in design only). Our one working arm already uses a fixed +30% premium target (R51).

### 15. Trailing stops (pp. 158-159)
- Fixed money trail (a $300 trail locks $700 after a $1,000 move); the previous bar's high / low (60-minute E-mini short
  from 1502, stop walked 1504 -> 1502.50 -> 1499 -> 1494.50, exit +7.5 points, $375); or a moving average / Parabolic SAR.
- For trending markets; needs constant attention.
- Signal source: underlying price; maps to NSE index, MCX, crypto (where a trail can be the required profit lock).
- Mechanical? yes.
- Our research: trails mostly hurt our arms. `HUNT_R81_LIQUIDITY_EXITS.md` X3 ATR trail on the index cut runners
  (-Rs 62k design, -Rs 17k final); `FINDINGS.md` R19b Rs 500 give-back trail cut 12 of 13 big winners; R19 and R100 locks
  0 of 12 and 0 of 15. The previous-bar trail on 60-minute bars: not tested by us.

### 16. Taking partial profits (p. 160)
- Close half at a fixed profit and let the rest run to the next S/R level; or thirds: one third at a fixed target, one
  at the next S/R level, one on a trailing stop. Test exits more than entries.
- Signal source: underlying price; maps to NSE index (needs 2-3 lots), MCX, crypto.
- Mechanical? yes.
- Our research: rejected. `HUNT_R81_LIQUIDITY_EXITS.md` X5 scale out half at 1R: -Rs 79k design, -Rs 35k final on
  Liquidity 15+5.

### 17. Time stop of three bars (pp. 160-161)
- If neither stop nor target is hit after three times the chart timeframe, exit: 45 minutes on 15-minute charts, 3 hours
  on 60-minute charts. "When to use: always."
- Signal source: none needed (clock); maps to NSE index, MCX, crypto.
- Mechanical? yes.
- Our research: mixed. As an add-on to existing arms it failed: `HUNT_R18_EXITS.md` V3 time stop 45 min, part of 0 of 60
  overlays; R81 X4 no-progress stop was the worst idea (-Rs 85k design). But our one passing arm (R51 Donchian) has a
  30-minute time exit built in, close to the book's idea; and in `HUNT_H25.md` time stops "lose the least gross".

### 18. Evaluating a strategy (Step 6, pp. 162-172; Principles 3, 8, 9, 10)
- Back-test, Monte Carlo (shuffle the trade order, e.g. 1,000 runs at 5% risk, read the share with drawdown under 25%),
  then paper trade. At least 40 trades for a valid report, 200 is "optimal"; the book's table gives a margin of error of
  14% at 50 trades, 10% at 100, 7% at 200, 6% at 300 and 4% at 500.
- Read: net profit; average trade above slippage and commission; win rate; average win above average loss; profit
  factor 1.5 or more, suspect over-fitting above 3.0; double the worst drawdown to plan for. A robust system: 60-80%
  winners, profit factor 1.3-2.5, maximum drawdown 10-20% of the yearly profit.
- Only back-test the period the market has looked like now: the last 3-4 years of the E-mini; the pit and electronic
  versions of a contract are different markets. Markets change (E-mini daily range 100-150 ticks in 2000, 40-60 in 2004;
  intraday trend systems stopped working after 2002).
- Improve without curve-fitting: change exits first; choose parameters from a broad plateau (a breakout system's
  TF_Param set to 0.3 because 0.25-0.4 was good on net, drawdown, average trade, win rate and trade count).
- If a system loses, "reverse your entry signals" (trading as a zero-sum game).
- Our research: largely agrees with the procedure and goes further (walk-forward, a locked holdout read once, random-entry
  twins, Benjamini-Hochberg, real Indian costs). Our gates use at least 40 trades (R71). Two book points are contradicted:
  the 60-80% win-rate expectation (R48: 0 of 10 high-win-rate candidates passed; FINNIFTY ATM +3% / -50% won 70.6% of 218
  trades and lost Rs 1,05,369) and "reverse a losing system" (`HUNT_H25.md`: follow lost Rs 141 a trade and fade Rs 151
  after costs; costs and time decay make both sides lose for an option buyer). `OBUY_SEARCH.md`: in-sample Monte Carlo
  promised P(profit in a year) of 93-99%, the holdout gave 2-71%.

### 19. The 10 Power Principles (pp. 181-191)
Few rules (fewer than ten; entry in two lines); electronic, liquid markets; realistic expectations; risk / reward at
least 1:1.5 (a $100 stop needs a $150 target; avoid $100 targets with $2,000 stops); at least five trades a week;
start small, grow big, never martingale; automate exits with bracket orders; more than 50% winners (ideally above 65%);
test on at least 200 trades; choose a valid back-test period.
- Signal source: n/a (design rules). Maps to everything.
- Our research: we agree on few rules, liquid markets, automated (resting) exits, no martingale and large samples; our
  Donchian exits are 1:2 (-15% / +30%). The win-rate principle is contradicted by R48 (above).

### 20. Weekly profit goal, then stop (pp. 9-14, 219-220)
- Never set daily goals. Start with a low weekly goal for one contract (e.g. $100 per contract for the first four weeks,
  then $150, then $250-300); once the weekly goal is reached, stop trading until Monday. With a $300 target and $200
  stop per contract, a 50% win rate still reaches the goal. Scale income by contracts ($3,000 a week = 10 contracts at
  $300 each; 15 contracts for $225,000 a year).
- In the first 2 to 3 months trade only twice a day, once in the morning and once in the afternoon.
- Skip thin days: around holidays (day after Thanksgiving, 24 and 26 Dec) and days before a major release (Fed rate
  decision, unemployment report). Pause after a loss instead of revenge trading.
- Signal source: n/a (account and calendar rules); maps to NSE index, MCX, crypto alike.
- Mechanical? yes.
- Our research: the weekly stop is not tested by us. Nearest: `FINDINGS.md` R19c, a day profit lock (+Rs 2,000 -> keep
  +Rs 1,000) raised green days (42% -> 49%) but failed net / drawdown; R80 V10 stop-after-first-loss and V9 a 2-a-day cap
  led in design and lost in the final; R14 capping ORB re-entries to 1 a side a day cut losses (a damage limiter, not an
  edge). Holiday-adjacent day skips: not tested by us; R15 pre-open day filters 0 of 5.

### 21. Timeframe choice: 15-minute bars (pp. 78-79)
- Use 15-minute charts (1-5 minute bars carry noise from scalpers, hedge funds and automated trading); if results are
  poor, change the timeframe before changing entry or exit rules.
- Our research: `HUNT_H25.md` by timeframe: 60-minute candles had 7.9% of variants net positive, 1-minute only 1.1%, but
  only because fewer trades pay fewer costs; the edge over random is the same, about zero.

### 22. Market selection, capital and leverage (Step 1, pp. 45-77, 21-25)
- Criteria: low capital, leverage, liquidity, volatility. Stocks need $25,000 (US pattern-day-trader rule), futures
  $5,000-10,000, forex as little as $500, options $1,000-5,000 (though day trading stock options is "extremely risky",
  illiquid and best on daily or weekly charts). Keep 3-6 months of expenses aside; never borrow to trade; "many traders"
  risk only 10% of the initial deposit.
- The book prefers liquid electronic futures (E-mini S&P, NASDAQ, gold, crude, Euro FX).
- Our research: context only. Its warning that intraday option buying is hard matches our record (`FINDINGS.md`: costs of
  about Rs 70 a NIFTY round trip and time decay kill most option-buying rules; `HUNT_H25.md` random entries lose Rs 90-240
  a trade).

## Performance claims
- The author's own (undisclosed) strategy uses a $300 target and $200 stop per contract and reaches a $300 weekly goal
  at a 50% win rate; he says he has used the same strategy for four years. No trade record given.
- Day traders can make a living: $150,000 a year = $3,000 a week = 10 contracts at $300. Illustration, no evidence.
- About 30% of the time a market trends. No source.
- 90% of traders lose; NASAA: only 11.5% might trade profitably, at least 70% lose. Cited report, no data shown.
- Ralph Vince experiment: 40 PhDs with a 60%-win, even-payoff game over 100 trades; only 2 made money (CSI News Journal,
  March 1992). Cited, not reproduced.
- Homma "never had a single losing trade" (repeated as rumour).
- Worked examples, each a single hand-picked chart: E-mini trendline trade 1462.50 -> 1472; previous-day target trades
  13.25 points ($662.50) and 21.75 points ($1,087.50); 60-minute trailing short +7.5 points ($375); turtle short 48 pips
  ($480); RSI buy "coincidentally at the low of the day".
- Robust systems show 60-80% winners, profit factor 1.3-2.5, drawdown 10-20% of yearly profit. No evidence; a rule of thumb.
- Intraday trend-following on the E-mini worked until 2002 and then failed (pattern-day-trader rule moved scalpers to
  the E-mini; automated overbought / oversold systems rose 268% in 2002). Plausible story, no test shown.
- The breakout system with TF_Param 0.3 is "robust" (charts not readable; the strategy itself not disclosed).
- Coaching ads: "five proven trading strategies", "strongest performance guarantee". No evidence.

## Risk, sizing and psychology
- Sizing: start with one contract or 100 shares and add contracts as the account grows and the weekly goal is met
  consistently; scale income by contract count. The book gives no fixed-ratio, fixed-fractional or percent-of-equity
  formula; percentage risk appears only as an input to its Monte Carlo example (5% a trade). Martingale (doubling after
  losses) is shown to need 512 contracts by the tenth trade and $102,300 of losses, and is ruled out.
- Risk per trade: small stops, targets larger than stops (at least 1:1.5); never trade money you cannot afford to lose;
  fund the account "not too much and not too little".
- Drawdown: recovery is asymmetric (a 20% loss needs 25%, 50% needs 100%); plan for twice the worst back-tested
  drawdown.
- Process: write a trading plan (Appendix A template: goals, account size, risk, market, timeframe, approach, entry, exit,
  other rules, expected vs realised stats after 40 trades); keep a journal; paper trade at least 40 trades first; do not
  "fine-tune" after 5 trades of a system tested on hundreds; judge a trade by whether the plan was followed.
- Mistakes: complicated indicators, not taking profits, not limiting losses, trading choppy markets, no strategy,
  emotions (greed, fear, panic, indecision), overtrading from greed, revenge and boredom.
- Mindset: ownership, positive attitude ("Law of Attraction", daily "I am" statements), honesty, commitment, time off.

## How it relates to our research
- Agree: always use a stop placed at entry and resting exits (our order-path and SL/GTT rules); keep rules few; test on
  many trades and recent, relevant data (our Nov 2024+ studies R49-R53 follow the same logic as the book's "last 3-4
  years"); beware over-fitting and parameter cliffs; never martingale; paper first; plan for a worse drawdown than seen.
- Already rejected by us (the book's whole indicator menu): close-vs-SMA and MA crosses (`HUNT_H25.md`, `OBUY_GB.md`
  TI-03, R29), MACD and Williams %R (`HUNT_H25.md`), RSI 30/70 (`OBUY_GB.md` MR-03), Bollinger fades (MR-02, R71 P3),
  support / resistance (R20b, R20c, R89), the opening-bar entry (R3, R92), partial profits (R81 X5), trails and locks on
  our arms (R19, R19b, R81 X3, R100), and a 45-minute time stop as an overlay (R18 V3).
- Contradicted: "markets trend 30% of the time, so pick the tool for the regime" assumes the regime can be called in
  time; R16 says it cannot from the first 30/45 min. "60-80% winners" and "above 65%" as goals: R48 shows high win rates
  are easy to fit and lose. "Reverse a losing system": for an option buyer both directions lose after costs (H25).
- The one overlap with a real record: the turtle 20-bar breakout is our Donchian 20 arm (R51, R73), and its profit depends
  on exits the book does not give (-15% / +30% on the premium, a 30-minute time exit, at most 2 a day a side) and on
  entering at once (R73: waiting 5 one-minute candles lost Rs 1,26,378 in design). The book's general exit advice
  (fixed target larger than the stop, time stop) is close to what that arm does.
- Signal source: every method in the book reads the traded instrument's own chart. For us that is the index or MCX future,
  which is our default; nothing in it needs an option-premium signal.

## What is new to us
- A precise trendline algorithm (two points from the day's extreme and first retracement; only flatten, never steepen;
  never through bars; break when the line would have to move far from the lows). We have tested swing structure (R71 P8)
  but never a trendline break or a trendline-defined trend filter. Testable on index 5-minute bars.
- A weekly profit goal with "stop for the week once reached" (and a fixed low weekly goal per lot). We have tested day
  locks (R19c) but not a weekly one; it could be read on the existing Donchian / Liquidity paper records as an account
  rule (risk-lowering, so it fits the paper rules).
- Skipping holiday-adjacent thin days and days before major scheduled releases (for us RBI policy, US CPI / payrolls,
  Budget). Event-day studies exist for MCX (R83) and US events in R102's sign rules, but a "do not trade the index arms
  that day" filter has not been tested.
- A time stop set at three bars of the signal timeframe, as part of an entry rule (not as an overlay on other arms'
  exits).
- The previous-bar high / low trailing stop on 60-minute bars.
- The 14 / 20 moving-average pair and the 21-bar Bollinger setting (we used 20 and other pairs; unlikely to matter).

## Could not read
- The chart screenshots throughout (trendline sequence pp. 98-120, S/R, channels, indicator examples, stop / target and
  trailing examples, MOSH pump-and-dump, E-mini volume chart, choppy vs trending examples) have no text layer; only the
  prose about them was read.
- The sample performance report (p. 167) and the five TF_Param optimisation charts (pp. 175-179) are images; their numbers
  are known only from the prose.
- The OCR of the forex quote screen (p. 55) and IBM key statistics (p. 82) is partly garbled; nothing in them is a rule.
