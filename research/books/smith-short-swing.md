# Short Swing Trading - David Graeme-Smith (2004)

Source: smith-short-swing.txt, 121 pages, read in full on 25 Oct 2026 (text layer, pdftotext -layout; every page's text
was read; the 39 figures are chart images with no text layer, so only their captions and the author's notes on them
could be read).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short manual (version 6.1, Feb 2004) for one end-of-day swing system on UK shares, held a day to a few weeks.
The whole system is "six rules": risk cap, 50-day trend side, an 8-period +DI/-DI cross, a break of the last
"significant" (non-inside) day, a stop just beyond that day, and a day-by-day trailing stop with no target.
The author says the real secret is choosing shares with the right "personality": neat daily steps, small shadows,
low intraday volatility and low correlation with the index; he advises leaving major indices alone.
About half the book is beginner material (what a share is, chart types, personality quiz, business plan, records).
Evidence is three hand-marked six-month chart exercises and one month of the author's own trade log, all self-reported.

## Methods, rules and setups

### 1. Rule 1: risk cap per trade (Ch. 6.3, 7.1)
- Entry: not an entry rule. Money at risk on one trade (entry to initial stop, times size) must not exceed 5% of the
  trading account; the author's own cap is 3%.
- Stop: the initial stop of Rule 5 defines the risk in points; size = allowed money risk / points at risk.
- Target / exit: n/a.
- Filters: start small, raise size only gradually (see section on sizing below).
- Market and timeframe written for: any; UK spread bets in the examples.
- Signal source: none (sizing); maps to: NSE index options yes (risk = premium at risk or index-stop distance times
  delta times lot), MCX yes, crypto yes.
- Mechanical? yes.
- Our research: our account rule is far tighter (CLAUDE.md: -Rs 40k drawdown or -Rs 13k a month); fixed-lot sizing is
  what our studies use. Not tested by us as a percent-risk rule on swing trades.

### 2. Rule 2: trade only on the side of the 50-day simple moving average (Ch. 7.1)
- Entry: longs only while the price is above the 50-day SMA, shorts only while below. The 200-day is too slow, anything
  shorter than 50 gives too many false signals (author's view, no test shown). Simple, not exponential, average.
- Stop / exit: none of its own.
- Filters: when the DI cross and the 50-day cross happen together, both must have happened (and close together in
  time) before entry; a short in the exercise was taken only after the price closed below the 50-day.
- Market and timeframe written for: daily bars, UK shares.
- Signal source: underlying price; maps to: NSE index options yes (NIFTY/BANKNIFTY spot daily close vs 50-day SMA,
  then call or put), MCX yes (future), crypto yes (spot).
- Mechanical? yes.
- Our research: near tests failed. SWING_DEEP.md ("EMA 20/50 trend" on NIFTY futures, inside the coin-flip band);
  R73 (HUNT_R73_DONCHIAN_TREND_WAIT.md) T2 "close beyond EMA 50" on 5-minute bars passed the gates but was no better
  than skipping trades at random (random-skip p 0.14). The daily 50-day SMA side as the only filter: not tested by us.

### 3. Rule 3: 8-period Directional Movement, +DI crosses or touches -DI (Ch. 7.2)
- Entry (potential only): on Wilder's Directional Movement indicator with the period changed from 14 to 8, a long
  set-up is +DI crossing up through -DI (or the two lines converging to touch and parting again, typical in a strong
  trend); a short set-up is the mirror. Only set-ups on the same side as Rule 2 count. ADX itself is not used.
- Stop / exit: from Rules 5-6.
- Filters: if in doubt, skip ("pick the low hanging fruit"); a set-up spotted a day or two late should normally be
  skipped; trades where the DI lines did not actually touch or cross are invalid (the author shows two such trades
  in an exercise losing 8 points between them).
- Market and timeframe written for: daily bars, end of day.
- Signal source: underlying price (daily high, low, close); maps to: NSE index options yes (index spot daily DMI),
  MCX yes, crypto yes.
- Mechanical? yes, except "touch" needs a tolerance (we would define it as |+DI - -DI| below a small threshold then
  widening).
- Our research: the DI/ADX family failed intraday. OBUY_GB.md TI-09 (ADX rising + DI aligned + break of the set-up
  candle, 5/15-minute bars) lost in walk-forward, Rs -345,311, 0/5 years positive, 48 variants. R73 ADX above 25 as a
  Donchian filter lost -Rs 1,74,950 in design. R46 (BTC EMA cross + ADX(14) > 15): 0 of 14 variants pass a gate.
  HUNT_F1_FACTORY.md: ADX/DI features were noise for direction. The exact daily 8-period +DI/-DI cross: not tested by us.

### 4. Rule 4: price confirmation by the "last significant day" break (Ch. 7.3)
- Entry: after a valid Rule 3 set-up, buy when the price trades above the high of the last significant day (sell short
  below its low). An inside day (high not above and low not below the previous day's) is "insignificant" and is
  skipped when finding that reference day; several inside days in a row are all skipped. The reference day may be up
  or down. Entry is meant to be a resting stop-entry order ("order to open"), filled during the day.
- Extra entry notes: if the DI lines are about to cross, a big day can cross them and confirm in the same session; the
  author then enters intraday, or at the next open if missed (accepting a worse price).
- Filters: do not enter if the 200-day SMA is between the entry and the point where the trade would be in profit (the
  200-day is a "barrier"); do not take a trade that triggers by gapping through the trigger (Method 10); avoid trading
  too many names at once while learning.
- Market and timeframe written for: daily bars.
- Signal source: underlying price; maps to: NSE index options yes (index stop-entry at the reference high, buy 1-ITM
  call on the trigger), MCX yes, crypto yes.
- Mechanical? yes.
- Our research: the closest daily test is SWING_DEEP.md's "NR7 / inside-day breakout" on NIFTY/BANKNIFTY futures: best
  rows looked good in hindsight but sat inside the coin-flip range and the walk-forward lost Rs 45-58k a year. On
  1-minute bars, Boss's previous-day high/low break (R89, HUNT_R89_PDHL_BREAK.md) passed 0 of 180 cells. Prior-high
  break as a direction signal is a coin flip in our data (R102: 1 of 70 sign rules confirmed, an artefact).

### 5. Rule 5: initial stop just beyond the last significant day (Ch. 8.2)
- Stop: long, a little below the low of the last significant day; short, a little above its high. "A little" is 0.5%
  of the share price for shares that fit his profile; for indices or wilder shares he says the buffer must be wider,
  by trader judgement. Once set, the stop is never loosened.
- Risk at entry is therefore the range of that one day plus the buffer.
- Market and timeframe written for: daily bars; placed (not mental) stops preferred.
- Signal source: underlying price; maps to: NSE index options yes (stop on the index level, exit the option; or a
  premium stop computed from delta), MCX yes, crypto yes (this is the mandatory stop).
- Mechanical? yes (buffer 0.5% for "fitting" shares; for an index the width is not given).
- Our research: not tested by us in this form. R89 used the set-up candle's far end as the stop (on 1-minute bars) and
  about a third of trades were stopped within 3 candles.

### 6. Rule 6: significant-day trailing stop, no target (Ch. 8.3, 9.0)
- Exit: at the end of the entry day (which is always a new high for a long), move the stop to 0.5% below that day's
  low. After that, each new significant day that makes a new high (long) moves the stop to 0.5% below its low; inside
  days never move the stop. Shorts mirror with highs. The trade ends only when the stop is hit; the author gives back
  at most about one day's range. No profit target (he rarely uses targets and says beginners should not).
- Market and timeframe written for: daily bars, stops updated after the close and phoned in or placed next morning.
- Signal source: underlying price; maps to: NSE index options yes (index-level trail, exit the option), MCX yes,
  crypto yes (this trail is a step profit lock, so crypto would have both a stop and a lock).
- Mechanical? yes.
- Our research: not tested by us as a "previous significant day low" trail. Nearest: SWING_EXITS.md (the app's
  stop/target/ladder on swing entries): exits cut drawdowns but no row beat coin-flip entries with the same exits;
  OBUY_LV05_MULTIDAY.md trailed the opposite N/2-day channel and lost (about -Rs 18,000 a year).

### 7. "Big move day" half-way lock (Ch. 9.1, 13.1)
- Exit: if a day moves 5% of the price or more in the trade's favour, move the stop to just below the half-way point
  of that day (long) or just above it (short), instead of below the day's low. After a gap, the big-move test is
  measured open to close on the new day.
- Market and timeframe written for: daily bars on UK shares (a 5% day is ordinary for them).
- Signal source: underlying price; maps to: NSE index options partly (a 5% NIFTY day is very rare; daily sd 1.316%
  over 2006-2026 per R102, so the threshold would have to be scaled, e.g. to a multiple of ATR), MCX yes (silver,
  natural gas often move this much), crypto yes.
- Mechanical? yes.
- Our research: not tested by us.

### 8. Share "personality" screen and watch list (Ch. 11)
- What it is: the author says the system only works on shares that (1) move in neat steps, so that when long the next
  day's low rarely undercuts the previous low (the single most important test, judged on 3 months); (2) make average
  moves large enough to beat costs over 6 months; (3) have low correlation with the index (guide: below 0.20; he likes
  0.2 down to 0.05 and will trade less liquid shares to get it); (4) are reasonably liquid; and (5) have small
  shadows and low intraday volatility. Smaller companies fit better. Major indices are "volatile and difficult": he
  shows FTSE 100 as unsuitable and FTSE 250 as ideal. Process: back-test the six rules on each candidate over 6
  months, weed out the worst, repeat on the 6 months before, paper trade, trade small, keep replacing the bottom third.
- Signal source: underlying price (daily bars; correlation to the index); maps to: NSE index options poorly (NIFTY and
  BANKNIFTY are the "FTSE 100" type the book tells you to avoid); NSE stock options/futures yes as a screen; MCX yes
  as a per-commodity screen; crypto yes.
- Mechanical? partly: steps, correlation and shadow size can be measured; "looks like neat steps" is a judgement. The
  weed-out loop is in-sample selection on past back-tests, i.e. a selection-bias machine unless walk-forward.
- Our research: not tested by us as a screen. R58 (HUNT_R58_STOCK_OPTIONS_TOP10.md) found 0 of 38 stock-option rules
  pass, with a round trip about Rs 280 a lot (about 2% of the median premium); SWING_DEEP found that on stocks only
  dip-buying in uptrends beat random stocks, and not buy-and-hold.

### 9. Results / news blackout (Ch. 13)
- Filter: do not open a trade in a share during the two weeks before its interim or final results, AGM or other
  scheduled company news (profit warnings gap the price). General market news is ignored.
- Signal source: none (calendar); maps to: NSE stock options yes (results dates), NSE index options weakly (index
  events: RBI policy, budget, election), MCX yes (EIA, storage, CPI, NFP), crypto partly (FOMC/CPI).
- Mechanical? yes, given a calendar.
- Our research: on MCX, event straddles and event-follow trades lost (R96 E1/E2); "US events" sign rules never passed
  in R102. A pre-results blackout for stocks: not tested by us.

### 10. Skip gap-triggered entries; trail after a favourable gap (Ch. 13.1)
- Filter: if the trigger in Rule 4 is hit by the price gapping through it at the open, do not take the trade; the
  author's experience is that the gap is more often filled and the trade whipsaws. If the price gaps in your favour
  after entry, the normal trail applies: the gap day is a new high, so the stop goes under its low.
- Signal source: underlying price; maps to: NSE index options yes, MCX yes (session gaps), crypto little (24/7).
- Mechanical? yes ("gap" needs a threshold: open beyond the trigger).
- Our research: R102 (DEEP_NIFTY_BANKNIFTY.md): a 0.5-1% gap fills by the close less than half the time (NIFTY 42%,
  BANKNIFTY 49-53%), and filled gaps fill early; gap fades never passed as sign rules. So the book's premise ("more
  likely to fill") is not what our data shows for NIFTY. Skipping gap-triggered swing entries: not tested by us.

### 11. End-of-day routine and resting orders (Ch. 14)
- Process: update end-of-day data after the close, page through a watch list of about 30 names in minutes, then place
  next-day stop-entry orders and adjust stops; stops can be phoned in at the next open.
- Signal source: none (workflow). Maps to the app as a daily after-close scan with GTT/SL orders resting at the broker.
- Mechanical? yes.
- Our research: n/a (fits CLAUDE.md's "live stops also rest at the broker" rule).

### 12. Back-test acceptance yardstick (Ch. 11.2, 15.2)
- Rule: a share is acceptable if its back-test shows at least 60% of trades at break-even or better (he prefers over
  70%, expecting live results to be worse) and at least a few trades of 8% or more of the price in 6 months; out of
  100 trades, 60 break-even-or-better and 20 moves over 8% would make "a VERY good profit". Subtract real costs.
- Signal source: none (evaluation). Mechanical? yes, but it counts trades from charts by eye and has no random or
  out-of-sample comparison.
- Our research: our gates (pre-registration, walk-forward, twins, BH) are stricter; a 6-month eyeball back-test is
  far below them.

### 13. Records and the "profitability" check; separate books for off-system trades (Ch. 18)
- What it is: a form per trade (entry, stop, risk in points and money, each trailing stop, exit, review two weeks
  after); monthly stats (average risk, average gain and loss, win share); a running points total per share to drop
  the weakest; and a monthly check that "points made times average size" is beaten by actual money (to catch
  uneven sizing). Trades outside the system must be kept in separate records.
- Signal source: none. Mechanical? yes.
- Our research: matches our labelled-and-separated paper records (CLAUDE.md BANKNIFTY note); nothing to test.

### Discretionary parts (not mechanical)
- Judging "neat steps" and "small shadows" by eye; the extra stop buffer for indices or wild shares; "if in doubt,
  leave it"; occasional profit targets (explicitly out of scope of the book); the author's habit of refusing set-ups
  that "almost triggered".

## Performance claims
- Roughly 90% of people who try trading lose enough to quit (no source given).
- The author's average trade lasts about eight working days (no data shown).
- Worked long (Intertek, 2003): entry at 4.68, exit at 5.20, 52 pence, about 10% before costs in 10 days. Worked
  short (Ultraframe): entry 2.60, exit 2.18, 42 pence, about 16% before costs in 15 days. Both hand-picked examples.
- Share comparison over the same 6 months: BT (unsuitable) would have had 4 winners of 10 (40%); Bovis (suitable) 6 of
  9 (67%) with smaller losses and bigger wins, and 117 points after costs, "25% from one share in 6 months". Counted
  by eye from a chart; no other names shown.
- Exercise charts (three shares from his own watch list, 6 months each, after his spread-bet spread): Bovis 8 trades,
  +75 points (spread 6.5 a trade); Westbury 6 trades, +152 points (spread 3); Pillar Property 4 trades, +118 points
  (spread 5). 18 trades in all, 13 positive. The watch list was itself chosen by back-testing, so this is in-sample.
- His January 2004 log: 30 trades closed (about 1.5 a day; only 4 short), average maximum risk 8.06 points (high 16.7,
  low 2.1), 252.1 points gained, 43 points of losses, 209 points net; at a notional 30 per point that is 6,270 for the
  month; 77% of trades at break-even or better; he says 36% would have broken even given his win/loss sizes. He says
  it was not an exceptional month but shows only that one; the table itself is an image we could not read.
- Usually about 25 trades a month; 2 points a trade lost to sloppiness over 30 trades is 60 points.
- He has "regular and consistent success" in up and down markets, developed over three years, and expects buyers to
  trade profitably "from the first month". No audited record, no multi-year statistics, no costs breakdown beyond
  the spread, no comparison with random entries.

## Risk, sizing and psychology
- Preserve capital first; 5% maximum risk per trade, 3% his own; only trade money you can afford to lose.
- Start with back-testing, then paper trading in real time, then small real size, then raise size slowly. He warns
  against "yo-yo" sizing (raising size after a good run, cutting after a bad one), which makes losers big and winners
  small; keep a constant risk profile through poor spells, which he says are usually only a few days.
- Expect losing streaks (three or four in a row); losses are "expenses" of the business; do not override the rules,
  do not move a stop away; placed stops beat mental ones.
- Greed and fear drive the classic buy-high, sell-low cycle; a rule set should be like "painting by numbers".
- Ignore tips, chat rooms and TV forecasts ("trade what you see"); keep records; keep off-system trades separate.
- A light-hearted risk-personality quiz (worry, patience, risk taking).
- Correlated losses: when market news reverses everything, several longs stop out together; he accepts this and
  expects shorts to start triggering if the fall continues.

## How it relates to our research
- **Direction from daily index price rules: already rejected in our data.** SWING_DEEP.md: no NIFTY/BANKNIFTY swing
  rule passed after costs and walk-forward (classic breakouts, RSI(2) pullback, EMA 20/50, NR7/inside-day breakout).
  OBUY_LV05_MULTIDAY.md: the Donchian breakout held for days lost about -Rs 18,000 a year (-Rs 85,300 over 4.8 years,
  135 trades) and did no better than random (p = 0.68); the futures mirror lost too, so it is the signal, not theta.
  R102 (DEEP_NIFTY_BANKNIFTY.md): the 20-year daily momentum (+5.8 bps NIFTY, +12.4 bps BANKNIFTY a day) reversed in
  the holdout (-9.4 / -24.6 bps, n 65). SST is a daily trend-continuation system, so this is its nearest prior, and it
  is negative.
- **ADX/DMI: rejected intraday, untested daily.** OBUY_GB TI-09 (48 variants, 0/5 years), R73 ADX filters (fail),
  R46 BTC EMA+ADX (0 of 14), SIDEWAYS_GUARD.md ADX pause (no better than random). The daily 8-period +DI/-DI cross
  with the significant-day trigger is not something we have run.
- **Overnight is where swing money is made.** SWING_DEEP and LV-05 both found gross swing profit comes from the close-
  to-open gap and is lost during market hours; R102 shows NIFTY's drift is overnight (open-to-close -0.035% a day).
  A daily system that enters intraday on a stop-entry and is stopped intraday is exposed to exactly the hours that
  lost. The book's "skip gap-triggered entries" rule throws away the overnight part, which our data says is the
  profitable part.
- **Our intraday Donchian arms (R51, R72 Breadth Donchian, R73 Supertrend filter) are a different horizon** (5-minute
  bars, 30-minute holds, out by 15:15). SST shares their trend-following logic but not their timeframe; the R73
  finding that waiting for confirmation enters after the burst is the intraday cousin of the book's warning about late
  entries.
- **The book agrees with us that NIFTY-type indices are the wrong vehicle** for this kind of tight-stop daily swing:
  it calls large-cap indices too volatile intraday and says to leave them alone. Our numbers back the mechanism
  (median NIFTY day range 0.907%, with a gap of 0.5% or more on 20% of days), which makes a 0.5% buffer under one
  day's low very tight.
- **Stocks and stock options:** R58 found stock-option costs about Rs 280 a lot round trip and 0 of 38 rules pass;
  SWING_DEEP found stock swing set-ups did not beat holding the same stocks. SST's low-correlation small-cap screen
  would point to illiquid F&O names where those costs are worse.
- **MCX:** R96 (0 of 46 option-buying ideas) and MCX_TREND.md (trend edge real but small, zero variants pass BH; buying
  options was the worst way) are the priors for an SST port to MCX futures.
- **Crypto:** RC1 found 0 of 8 active ideas beat buy-and-hold after India's tax; R46 rejected an ADX-based BTC rule.
  An SST port would meet CLAUDE.md's crypto rule (Rule 5 stop plus Rule 6 step lock).

## What is new to us
- **The "significant day" concept:** ignoring inside days when choosing the reference bar for entries, stops and
  trail steps. We have tested inside days as set-ups, never as bars to skip in a trail.
- **The significant-day trailing stop (prior non-inside bar's low minus a 0.5% buffer) with a half-way lock on
  outsized days.** A simple, parameter-light step lock we have not run on any of our holds (Night overnight arm,
  multi-day options, MCX crude NR7 lead, crypto). It could be tested as an exit module, scaled by ATR for indices.
- **The exact SST entry** (8-period +DI/-DI cross or touch, on the 50-day SMA side, triggered by the break of the
  last significant day) on daily bars of NIFTY/BANKNIFTY spot, MCX futures, F&O stocks and BTC/ETH.
- **A "step-ness" personality metric for choosing instruments**: share of days in a trend whose low holds above the
  prior low, shadow-to-range ratio, and correlation with NIFTY. We have no such per-instrument fitness screen; it
  could rank F&O stocks or MCX books before any rule is run (it must be walk-forward to avoid selection bias).
- **The 200-day "barrier" skip** (no entry if the 200-day SMA lies between entry and first profit) and **the pre-results
  two-week blackout** for stock trades.
- **The monthly "points times average size" sizing-discipline check** for Boss's own trading records.

## Could not read
- All 39 figures (chart images, pp. 5-113): price charts of UK shares and the FTSE 100/250 with the trades marked;
  only their captions and the author's surrounding text were readable. The January 2004 trade log (Figure 39, p. 112)
  is an image; only the author's summary of it was readable. Blank print-out forms (pp. 15, 37-39, 110) have no
  content. No text pages were missing.
