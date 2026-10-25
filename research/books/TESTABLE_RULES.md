# Testable rules from the books (25 Oct 2026)

Every concrete, mechanical rule the books teach, written precisely enough to pre-register on our data (index spot and
futures 1- and 5-minute bars, the NIFTY / BANKNIFTY option chain with OI and IV, MCX futures and options, India VIX, daily
bars since 2006). One experiment entry per rule in `research/experiments/entries/book-*.yaml`, status `proposed`:
**none of these has been run.** Boss chooses which go to a research round; nothing here touches an app arm or a paper
setting.

How to read a rule:
- **Signal source** is in IraAlgo terms (CLAUDE.md): almost every rule here reads the underlying (index spot / futures,
  the MCX future, the stock) and then buys the option a strike rule picks. No rule takes its signal from an option's own
  price chart; a few manage the exit on the premium, which CLAUDE.md allows.
- **Assumptions** say where the book was vague and we chose the most literal reading. A test must keep that reading or
  log the change in its pre-registration.
- **Data: needs data we lack** marks the rules that cannot be tested yet (index-futures volume before 29 Jul 2026,
  tick and order-book history, a second expiry's chain, an event calendar).
- **Our nearest test** cites what our research already found for the same or a close idea. Many of these rules are close
  cousins of ideas we already rejected (ORB R3/R49/R92, candles R87, chart patterns R71, levels R20b/R20c, PDHL R89, SMC
  R62/R90/R91, CCI R95, option selling R103). Testing them again counts against the same multiple-testing family, so
  pick the few that are genuinely new (see `README.md`, "Most worth testing").
- When tested: walk-forward design, the locked holdout read once, real charges and slippage (baselines.yaml), a random
  entry baseline with the same exits, and Benjamini-Hochberg across every rule tried in the round.

The entry id (`book-<book>-<nn>`) is the rule's name in the experiment log.

## The Day Trader's Bible - Wyckoff ([write-up](wyckoff-day-traders-bible.md))
### `book-wyckoff-01` Big lots at the offer vs the bid: futures signed volume as a direction lead
- Source: The Day Trader's Bible, Ch. V. Family: `flow-oi`. Signal source: underlying price/volume (index future tick-rule signed volume).
- Timeframe: 1-minute bars built from futures ticks. Instruments: NIFTY, BANKNIFTY.
- Entry: At each minute close 09:30-15:00, compute signed volume of the near-month index future over the last 5 minutes (tick rule; prints >= 5x the median print size counted separately as 'big lots'). Long when big-lot signed volume and total signed volume are both in the top decile of the trailing 20-session distribution for that time slot; short when both are in the bottom decile.
- Stop: 0.5x the R16 expected 30-minute range on the index, from entry
- Exit: 15 minutes after entry, or the stop, or 15:15 (15:05 on expiry)
- Filters: skip if the feed-health checks of R8 sec. 4 fail in the window; skip the first 15 minutes
- Trade: buy ATM CE on a long signal, ATM PE on a short; stop on the underlying
- Assumptions (our reading where the book is vague): The book reads each print at the bid or offer; we only have retail snapshots, so the tick rule stands in for the aggressor side (R7 found the quote rule fails on snapshots). 'Large lot' is made relative to the median print, as the book says lot size is relative. Horizon 15 minutes is ours.
- Data: **needs data we lack** - lack: index-futures ticks exist only in the phone's TickZoneRecorder (from 12 Oct 2026, private IraAlgo repo); about 3 months needed (R61b)
- Our nearest test: R7: signed option flow leads (IC +0.109 at 1 minute) but its edge is 0.42x of cost; R9 delta-divergence proxy no
### `book-wyckoff-02` Heavy volume packed into a narrow band near a new high, then slipping: short
- Source: The Day Trader's Bible, Ch. IV, V, X. Family: `flow-oi`. Signal source: underlying price/volume (index future volume, price on the future).
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD, SILVER.
- Entry: After the price has risen >= 1 ATR(14, 5m) in the last 12 bars and printed the session high within the last 6 bars: if the volume of the last 3 bars is >= 3x the slot's 20-day median while their combined high-low range is <= 0.5 ATR, set the zone = high/low of those 3 bars. Short on the first 5m close below the zone low within the next 6 bars.
- Stop: zone high + 0.1 ATR
- Exit: first 5m close back above the zone midpoint, or 2 ATR in favour, or 15:15 (15:05 on expiry; MCX 23:00)
- Filters: none beyond the setup; mirror version (long) after a >= 1 ATR fall into the session low
- Trade: buy ATM PE on the short (CE on the mirror); stops on the underlying future
- Assumptions (our reading where the book is vague): Book's 'abnormally large volume within a small radius' made 3x volume within 0.5 ATR; 'the price starts to slip' made a close below the zone. Thresholds are ours.
- Data: **needs data we lack** - lack: index-futures minute volume only from 29 Jul 2026; check MCX lake for per-minute futures volume (R89/R90 used close-only)
- Our nearest test: R87 absorption proxy 51.5% vs 51.5% (n 2,266); R8 heavy-volume levels broke 58-68% vs 55%; R86b absorption at day extremes almost never seen
### `book-wyckoff-03` Buy the light-volume pullback in an up move whose advances carried more volume
- Source: The Day Trader's Bible, Ch. V, IX, X. Family: `flow-oi`. Signal source: underlying price/volume (index future).
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Swings by a ZigZag of 1 ATR(14). In an uptrend (last two swing highs and lows rising), when a pullback retraces <= 50% of the last up-leg and the pullback's average bar volume is <= 0.6x the up-leg's average bar volume, buy on the first 5m close above the prior bar's high. Mirror for downtrends.
- Stop: below the pullback low
- Exit: a new swing low (trend broken), or a pullback whose volume exceeds the up-leg's, or 15:15 (15:05 on expiry)
- Filters: entries 09:45-14:30
- Trade: buy 1-ITM CE on a long signal, PE on a short; stop on the underlying
- Assumptions (our reading where the book is vague): Book gives no thresholds; 0.6x and 50% are ours. Volume must be the future's, not the cash index.
- Data: **needs data we lack** - lack: index-futures minute volume only from 29 Jul 2026
- Our nearest test: not tested by us with true volume; R86b big selling fast / buying slow (169 / 179 days), following it 0 of 8
### `book-wyckoff-04` Break of a quiet range on a burst of volume
- Source: The Day Trader's Bible, Ch. VII, X. Family: `patterns-indicators`. Signal source: underlying price (range on the index future), underlying volume (filter).
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE, NATURALGAS.
- Entry: Quiet range = the last 12 bars' high-low <= 1.0x ATR(14, 5m) of the prior 50 bars. Buy on a 5m close above the range high (sell below the range low) when that bar's volume is >= 3x the average of the 12 range bars and >= 1.5x the slot's 20-day median.
- Stop: the opposite side of the range
- Exit: a close back inside the range, or 2x the range height in favour, or 15:15 (15:05 on expiry)
- Filters: one trade per side per day; skip 09:15-09:30
- Trade: buy ATM CE on an up break, ATM PE on a down break; stops on the underlying
- Assumptions (our reading where the book is vague): 'Volume in thousands instead of hundreds' made a 3x burst; range length 12 bars (1 hour) is ours. Run the same rule without the volume filter as the price-only twin.
- Data: **needs data we lack** - lack: index-futures minute volume only from 29 Jul 2026 (price side have since 2021)
- Our nearest test: ORB family 0 of 257 (R92); Donchian 20 on paper (R51); NR7 crude lead not passed (R96); the volume filter is not tested by us
### `book-wyckoff-05` Break only when the rest of the market breaks with it
- Source: The Day Trader's Bible, Ch. V, VIII. Family: `patterns-indicators`. Signal source: underlying price (five index spots).
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: As R72 C1: a 20-bar Donchian break on an index, taken only when >= 4 of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX break their own 20-bar channel in the same direction on the same bar
- Stop: R72's exits
- Exit: R72's exits
- Filters: none
- Trade: buy 1-ITM CE on an up break, PE on a down break
- Assumptions (our reading where the book is vague): Book's 'carries the rest of the market along' read as the R72 breadth count; this is the existing arm, listed so the book's idea is linked to it
- Data: have
- Our nearest test: R72 Breadth Donchian PASS: final Rs 1,71,366 on 1,202 trades vs Donchian Rs 55,439; on paper
### `book-wyckoff-06` Close an index trade when the other indices turn against it
- Source: The Day Trader's Bible, Ch. III, IV, VI. Family: `trade-management`. Signal source: underlying price (the other four index spots).
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Any open long on one index from an existing arm (Breadth Donchian, Liquidity 15+5)
- Stop: the arm's own stop
- Exit: Exit early when >= 3 of the other 4 indices make a new 20-bar low on the same 5m bar while the trade is open (mirror for shorts)
- Filters: only after the trade has been open >= 2 bars
- Trade: close the option bought by the arm
- Assumptions (our reading where the book is vague): The book's cue from 'other stocks failing' made a breadth count against the position; 3 of 4 and 20 bars are ours. Exit-only, so risk-lowering.
- Data: have
- Our nearest test: not tested by us as an exit; early exits made the profitable arms worse (R12, R18); R72 C6 SMT divergence failed
### `book-wyckoff-07` A rally that fails to recover half the drop: sell
- Source: The Day Trader's Bible, Ch. VI, IX, X. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Swing legs by a ZigZag of 1.5 ATR(14). After a down-leg of size D, if the next rally tops out below 50% of D (confirmed when price makes a 0.5 ATR reversal from the rally high), short on the first close below the rally's start bar low. Mirror for up-legs.
- Stop: the rally high + 0.1 ATR
- Exit: the down-leg low minus 0.5 D, or 15:15 (15:05 on expiry)
- Filters: down-leg D >= 1.5 ATR
- Trade: buy 1-ITM PE on a short, CE on the mirror long
- Assumptions (our reading where the book is vague): Book says a normal rally is one half to two thirds; 'falls short' read as below 50%; target and ZigZag size are ours
- Data: have
- Our nearest test: R4: retracement levels turn price 49.0% vs 49.1% for shifted ratios (25,000+ touches); h37 Fibonacci bounces Rs -125 a trade
### `book-wyckoff-08` Stop beyond the last point of resistance, raised under each new reaction low
- Source: The Day Trader's Bible, Ch. IV. Family: `trade-management`. Signal source: underlying price.
- Timeframe: 5-minute bars (index spot). Instruments: NIFTY, BANKNIFTY.
- Entry: Any entry from an existing arm (Breadth Donchian, R51 Donchian, Liquidity 15+5)
- Stop: initial: 0.1 ATR beyond the last confirmed swing low (3-bar fractal) before entry; after each new high in favour, raise to 0.1 ATR below the latest confirmed reaction low; never lower it
- Exit: the trailed stop, or the arm's own exit, whichever first; 15:15 (15:05 on expiry)
- Filters: none
- Trade: manage the option bought by the arm; stop read on the underlying
- Assumptions (our reading where the book is vague): Book's 1/4 point beyond the point of resistance converted to 0.1 ATR; swing = 3-bar fractal
- Data: have
- Our nearest test: R19 breakeven / give-back locks 0 of 12; R90 swing stops 0 of 99; a swing-low trail on the arms is not tested by us
### `book-wyckoff-09` Fixed-distance trail until costs are covered, then hand over to the arm's exit
- Source: The Day Trader's Bible, Ch. IV. Family: `trade-management`. Signal source: underlying price.
- Timeframe: 1-minute bars. Instruments: NIFTY, BANKNIFTY, CRYPTO.
- Entry: Any entry from an existing arm
- Stop: initial stop 1.0x the R16 expected 30-minute range from entry; trail it so it stays exactly that distance from the best price, in steps of 0.25 of the distance, until it sits at entry + round-trip costs
- Exit: then stop trailing; the arm's own exits take over
- Filters: none
- Trade: manage the arm's option; for crypto this is the required stop plus profit lock
- Assumptions (our reading where the book is vague): The book's 1 point on a ~130-dollar stock is about 0.8%, far too wide for an intraday index; we scale it to the expected range. Crypto use needs a stop and a profit lock (CLAUDE.md), which this provides.
- Data: have
- Our nearest test: R19b Rs 500 give-back hurt Liquidity (holdout 15m +Rs 4,584 vs +Rs 14,633 plain); R100 0 of 15; LIQUIDITY_TRAIL halves the average winner
### `book-wyckoff-10` Abandon a trade that does not act right at once
- Source: The Day Trader's Bible, Ch. X. Family: `trade-management`. Signal source: option premium progress (exit only) on an underlying signal.
- Timeframe: 1-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Any entry from an existing arm
- Stop: the arm's own stop
- Exit: exit if, 20 minutes after entry, the index has never moved 0.25x the R16 expected 30-minute range in the trade's favour
- Filters: none
- Trade: close the arm's option
- Assumptions (our reading where the book is vague): Book: 'it must act up to expectations or be abandoned immediately'; R12 used the option's +3% in 20 minutes; this version reads progress on the underlying
- Data: have
- Our nearest test: R12 no-progress exit beat random exits on ORB Fresh (holdout +Rs 14,365 over 474 trades, twin p 0.001) but did not help Liquidity
### `book-wyckoff-11` Do not enter in the direction of a 4-day streak
- Source: The Day Trader's Bible, Ch. VI. Family: `daily-filters`. Signal source: underlying price (daily closes).
- Timeframe: daily bars (filter on intraday arms). Instruments: NIFTY, BANKNIFTY.
- Entry: On days after >= 4 consecutive up closes, skip the arms' long entries; after >= 4 consecutive down closes, skip short entries
- Stop: the arm's own
- Exit: the arm's own
- Filters: also test 3 days
- Trade: skip-only filter on the arms (risk-lowering)
- Assumptions (our reading where the book is vague): Book: the market seldom runs more than three or four days one way without a reaction; read as a skip filter, not a fade
- Data: have
- Our nearest test: not tested by us as a streak rule; R102 daily sign continuation 53.2% (n 5,086); R101 filters 0 of 20
### `book-wyckoff-12` Tighten the stop in the last 15 minutes before square-off
- Source: The Day Trader's Bible, Ch. X. Family: `trade-management`. Signal source: underlying price.
- Timeframe: 1-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Any open arm trade at 15:00 (14:50 on expiry days)
- Stop: from then on, trail the stop 0.25x the trailing 15-minute ATR(1m)-sum behind the best index price
- Exit: the trail or the 15:15 square-off (15:05 on expiry)
- Filters: only trades in profit at the start of the window
- Trade: manage the arm's option
- Assumptions (our reading where the book is vague): Book's 'within a fraction of the extreme' made 0.25x of a short-window ATR; times shifted to our 15:15 / 15:05 square-offs
- Data: have
- Our nearest test: locks on Liquidity 0 of 12 (R19) and 0 of 15 (R100); late-day squeeze alone not tested by us
### `book-wyckoff-13` A sudden burst in a secondary index while the leaders stall: skip new longs
- Source: The Day Trader's Bible, Ch. III. Family: `daily-filters`. Signal source: underlying price (index spots).
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: When MIDCPNIFTY (or FINNIFTY) rises >= 1.0% in the last 60 minutes while NIFTY and BANKNIFTY each rise <= 0.2% and are below their session highs, skip new long entries on NIFTY / BANKNIFTY for the next 60 minutes
- Stop: n/a
- Exit: n/a
- Filters: after 10:15 only
- Trade: skip-only filter on the arms (risk-lowering)
- Assumptions (our reading where the book is vague): Book's 'secondary leader bursts into activity while the leaders are distributed' mapped to a mid-cap or financials index running alone; thresholds are ours
- Data: have
- Our nearest test: not tested by us; R72 C3 lonely breakout (faded) lost Rs 1,60,188 on 499

## How to Trade Price Action - Galen Woods ([write-up](woods-price-action.md))
### `book-woods-01` Three-bar pullback in a trend, buy above the next bar in the trend's direction
- Source: How to Trade Price Action, Ch. 4.7. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD.
- Entry: Uptrend: three consecutive bearish bars (close < open); the next bullish bar is the signal bar; buy stop 1 tick above its high, valid for the next bar only. Downtrend mirror: three bullish bars, then a bearish signal bar, sell stop 1 tick below its low.
- Stop: Pattern stop: the lowest low of the three pullback bars and the signal bar (highest high for shorts), on the underlying.
- Exit: Book gives no target; use the nearest opposing swing high/low (Ch. 7.3) as the target; else the session square-off (15:10, 15:05 on expiry; MCX 10 minutes before close).
- Filters: Trend up = close above EMA 20 and EMA 20 rising over the last 5 bars (Ch. 1.5 names the 20-period EMA as the usual price-action trend tool); mirror for down. Entries 09:30-14:30.
- Trade: buy 1-ITM CE on a long signal, 1-ITM PE on a short; stops/targets on the underlying as the book states; premium safety stop as in our standard engine
- Assumptions (our reading where the book is vague): Book does not define the trend; EMA 20 slope chosen. Book does not state a stop for this pattern; Ch. 7 general pattern stop applied to the whole pullback (not just the signal bar, which R87B showed is too tight). Target from Ch. 7.3.
- Data: have
- Our nearest test: Not tested in this form. R87B: green-green-red on SENSEX 15m held out of sample (+0.259 ATR, n 182) but the call rule with a stop under the red candle lost -Rs 21,127 on 162 trades; R96 P1 MCX pullback in trend Rs 1.08 lakh on 35, twin p 0.164 (lead only).
### `book-woods-02` Hikkake: failed breakout of an inside bar within three bars
- Source: How to Trade Price Action, Ch. 5.10. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE.
- Entry: Bar B is an inside bar of bar A (lower high, higher low). Bar C has a lower high and a lower low than B (downside false break). Within the next 3 bars after C, if price trades above B's high, buy at B's high + 1 tick. Mirror for shorts (C higher high and higher low, then trade below B's low).
- Stop: The lowest low from C to entry (the false break's extreme), on the underlying.
- Exit: Not stated; nearest opposing swing as target, else session square-off.
- Filters: None in the book (it calls Hikkake a continuation pattern; optional variant: only in the direction of the EMA 20 slope).
- Trade: buy 1-ITM CE on a long signal, 1-ITM PE on a short
- Assumptions (our reading where the book is vague): 'Failure' read as price trading back beyond the inside bar's opposite end within 3 bars of C (the common Chesler definition); the book does not say which level marks the failure.
- Data: have
- Our nearest test: Not tested by us. R71 P1 inside-bar break lost -Rs 1,23,667 on 1,075 trades (its failure is the mirror); R89 break-and-reclaim lost like break-and-go; R61b: candidates after failed breaks did worse.
### `book-woods-03` Reversal bar in the trend's direction
- Source: How to Trade Price Action, Ch. 4.1. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Uptrend: a bar whose low is below the previous bar's low and whose close is above the previous bar's close; buy stop 1 tick above its high, next bar only. Downtrend mirror.
- Stop: The reversal bar's low (high for shorts).
- Exit: Nearest opposing swing; else square-off.
- Filters: Trend by EMA 20 slope as in three-bar-pullback-trend.
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): 'Closes higher than the previous bar' read as close > previous close. Trend definition ours.
- Data: have
- Our nearest test: Not tested in this form; R87 stop-run-below-day-low on 1m held out of sample but is about 1.8 points, below option cost.
### `book-woods-04` Key reversal day (gap below prior low, close above prior high)
- Source: How to Trade Price Action, Ch. 4.2. Family: `daily-filters`. Signal source: underlying price.
- Timeframe: daily bars since 2006. Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Bullish: today opens below yesterday's low and closes above yesterday's high; buy next day when price trades above today's high. Bearish mirror.
- Stop: The key reversal day's low (high for shorts).
- Exit: Not stated; test 1, 3 and 5 day holds and the nearest swing target.
- Filters: None in the book.
- Trade: buy next-week ATM CE/PE (or the future) on the signal; underlying stop
- Assumptions (our reading where the book is vague): 'Buy above' read as a buy stop above the signal day's high on the next session(s), cancelled after 1 day.
- Data: have
- Our nearest test: Not tested by us; R87B daily candle shapes 0 of 25.
### `book-woods-05` Exhaustion bar: gap open, close near the far end, gap unfilled, high volume
- Source: How to Trade Price Action, Ch. 4.3. Family: `daily-filters`. Signal source: underlying price/volume.
- Timeframe: daily bars. Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Bullish: today opens below yesterday's low (gap down), closes in the top 25% of today's range, and today's high stays below yesterday's close (gap unfilled); near-future volume above its 20-day average; next session, buy stop above today's high. Bearish mirror.
- Stop: Signal day's low.
- Exit: Not stated; 1-5 day holds.
- Filters: High volume (index futures or MCX future volume).
- Trade: buy ATM CE/PE next week, or the future
- Assumptions (our reading where the book is vague): 'Gap remains unfilled' read as today's high below yesterday's close (bullish case); 'near its top' = top 25% of range; volume on the near future.
- Data: have (daily); lack: long index-futures volume history in our store (short), MCX futures volume partly
- Our nearest test: Not tested by us.
### `book-woods-06` Pin bar rejected from a prior swing level
- Source: How to Trade Price Action, Ch. 4.4. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Bullish pin: lower wick >= 2/3 of the bar's range and the low within 0.1 ATR14 of a confirmed swing low (2 bars each side) of today or the previous session; buy stop above the pin's high, next bar only. Mirror for bearish.
- Stop: The pin's low (high).
- Exit: Nearest opposing swing; else square-off.
- Filters: Must form at the level (book).
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): 'Tail takes up most of the bar' = >= 2/3; level = fractal swing.
- Data: have
- Our nearest test: R87: big tail 51% vs 52% matched on NIFTY 15m (n 949), wick lows broken as often as distance implies; R20b levels no better than random; R91 sweep 0 of 63. Expected negative.
### `book-woods-07` Two-bar reversal: strong bar then opposite strong bar
- Source: How to Trade Price Action, Ch. 4.5. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Bar 1 bearish with body >= 60% of range; bar 2 bullish with body >= 60%; buy stop above the higher high of the two. Mirror.
- Stop: The lower low of the two bars.
- Exit: Nearest opposing swing; else square-off.
- Filters: Ch. 7: only with the market bias (EMA 20 slope) as a variant.
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): 'Strong' = body >= 60% of range (ours).
- Data: have
- Our nearest test: R87 engulfing/outside-type cells: none held at 5m/15m.
### `book-woods-08` Three-bar reversal
- Source: How to Trade Price Action, Ch. 4.6. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Bar 1 bearish; bar 2 lower high and lower low; bar 3 bullish with a higher low, closing above bar 2's high; buy stop above bar 3's high. Mirror.
- Stop: Bar 2's low.
- Exit: Not stated; nearest swing.
- Filters: None (book).
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): Book's literal rule; R71 tested a version restricted to the day's high/low.
- Data: have
- Our nearest test: R71 P7 at day high/low: final -Rs 1,67,689 on 855 trades, twin p 0.002 (timing real, below costs).
### `book-woods-09` Inside bar breakout, trend side only
- Source: How to Trade Price Action, Ch. 4.8. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: 15-min inside bar (same day mother bar); in an uptrend only a buy stop above its high, in a downtrend only a sell stop below its low, valid 30 minutes.
- Stop: The other end of the inside bar.
- Exit: Nearest swing; else square-off.
- Filters: Trend = EMA 20 slope on 15-min bars.
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): Trend definition ours; R71 ran the two-sided version.
- Data: have
- Our nearest test: R71 P1 two-sided: -Rs 1,23,667 on 1,075 trades (final). Trend-only not tested.
### `book-woods-10` Fade the breakout of a doji-like or counter-trend outside bar
- Source: How to Trade Price Action, Ch. 4.9. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Outside bar (higher high and lower low than the previous bar) with body <= 30% of range, or closing against the EMA 20 trend; when a later bar breaks its high, sell at the first close back inside the outside bar's range (mirror for a break of its low).
- Stop: The breakout bar's extreme.
- Exit: The opposite end of the outside bar; else square-off.
- Filters: Doji-like or counter-trend outside bars only.
- Trade: buy 1-ITM PE after a failed upside break, CE after a failed downside break
- Assumptions (our reading where the book is vague): 'Fade' read as entering on the close back inside; doji-like = body <= 30%.
- Data: have
- Our nearest test: Not tested by us; R87 outside-bar cells no pass.
### `book-woods-11` Outside bar closing at an extreme: trade its breakout
- Source: How to Trade Price Action, Ch. 4.9. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Outside bar closing in its top 20% (bottom 20%); buy stop above its high (sell stop below its low), next 2 bars.
- Stop: The outside bar's low (high).
- Exit: Nearest swing; else square-off.
- Filters: None.
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): 'Near its top' = top 20% (ours).
- Data: have
- Our nearest test: Not tested by us as a rule; R87 marubozu momentum is small, below option cost.
### `book-woods-12` NR7 break in the trend's direction only
- Source: How to Trade Price Action, Ch. 4.10. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars; daily bars. Instruments: NIFTY, BANKNIFTY, CRUDE.
- Entry: The last bar has the smallest high-low range of the last 7; uptrend: buy stop above its high; downtrend: sell stop below its low; valid 2 bars.
- Stop: The other end of the NR7 bar.
- Exit: Not stated; nearest swing or square-off (daily: 5 sessions).
- Filters: Trend = EMA 20 slope (ours).
- Trade: buy 1-ITM CE / PE (index); MCX 1-ITM option or the future
- Assumptions (our reading where the book is vague): Trend definition ours.
- Data: have
- Our nearest test: R71 P2 two-sided 15m: -Rs 1,68,820 on 1,341 trades; R96 crude NR7 lead (Rs 48,875 on 46) failed on COPPER.
### `book-woods-13` ID/NR4: inside bar that is also the narrowest of 4
- Source: How to Trade Price Action, Ch. 4.11. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily and 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Bar is inside the previous bar and has the smallest range of the last 4; OCO bracket 1 tick beyond its high and low, next bar.
- Stop: The opposite end of the bar.
- Exit: Not stated; session square-off (intraday) or 3 sessions (daily).
- Filters: None.
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): Bracket entry taken from the inside-bar section.
- Data: have
- Our nearest test: R96 crude NR4 -Rs 886 on 77; Crabel inside-day NR7 -Rs 14,129 on 13. Index version not tested.
### `book-woods-14` Engulfing in the trend's direction (continuation)
- Source: How to Trade Price Action, Ch. 5.4. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: In an uptrend, a bullish bar whose body covers the previous bearish bar's body; buy stop above its high. Mirror.
- Stop: The pattern's low.
- Exit: Nearest swing; else square-off.
- Filters: Trend = EMA 20 slope (ours).
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): Trend definition ours.
- Data: have
- Our nearest test: R87 engulfing cells: no pass (no trend-only rule run).
### `book-woods-15` Three white soldiers after a substantial decline
- Source: How to Trade Price Action, Ch. 5.9. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: After the close fell >= 3 ATR14 over the prior 20 bars: three bullish bars each opening inside the previous body and closing in the top 25% of its range; buy stop above the third bar's high. Mirror (crows after a rise).
- Stop: The first soldier's low.
- Exit: Nearest swing; else square-off.
- Filters: Prior decline (book: 'substantial').
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): 'Substantial decline' = 3 ATR over 20 bars (ours).
- Data: have
- Our nearest test: R87-1 three white soldiers (no prior-decline filter) -> call: -Rs 3,910 on 160 trades.
### `book-woods-16` Head and shoulders neckline break with measured target
- Source: How to Trade Price Action, Ch. 6.1. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (intraday) and daily bars. Instruments: NIFTY, BANKNIFTY, GOLD.
- Entry: Bullish (inverse): three fractal swing lows (3 bars each side), the middle lowest, both shoulders above it; neckline through the two swing highs between them; after a downtrend (close below EMA 50), buy on the first close above the neckline.
- Stop: The right shoulder's low (book states none).
- Exit: Target = head-to-neckline distance projected up from the breakout point; else square-off.
- Filters: Prior trend; volume rising on the break (futures volume, optional).
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): Swing degree, prior-trend test and stop are ours.
- Data: have
- Our nearest test: Not tested by us.
### `book-woods-17` Rectangle breakout in the prior trend's direction
- Source: How to Trade Price Action, Ch. 6.6. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: After an uptrend (close above a rising EMA 50), a range of >= 8 bars whose high-low span is <= 1.5 ATR14 with at least 2 touches of each edge; buy on the first close above the range top.
- Stop: The range bottom (or its midpoint as a variant).
- Exit: Target = range height projected from the break; else square-off.
- Filters: Prior trend; volume on the break (optional).
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): Range geometry ours.
- Data: have
- Our nearest test: Nearest: Donchian breakout arms (R51, R72) are positive; R20c S/R breaks a coin toss (51%, 7,548 breaks).
### `book-woods-18` Ascending / symmetrical triangle breakout with the trend
- Source: How to Trade Price Action, Ch. 6.8. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: In an uptrend, the last 3 fractal swing highs within 0.25 ATR of each other (ascending) or falling (symmetrical) and the last 3 swing lows rising; buy on a close above the upper line.
- Stop: The last swing low.
- Exit: Target = widest part of the triangle projected from the break.
- Filters: Prior trend.
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): Lines fitted through fractal swings (ours).
- Data: have
- Our nearest test: Not tested; R71 squeeze release (nearest contraction break) final -Rs 41,281 on 305.
### `book-woods-19` Broken level flips: buy the pullback to old resistance
- Source: How to Trade Price Action, Ch. 8.2-8.3. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: A 5-min close above a prior-session swing high; later a pullback touches within 0.1 ATR of it and a bullish bar closes above it; buy stop above that bar.
- Stop: 0.25 ATR below the level.
- Exit: Previous session high/low or next swing; else square-off.
- Filters: None.
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): Touch and stop distances ours.
- Data: have
- Our nearest test: R22 break-and-retest: retested 82%, then further on 45% (-0.020 ATR, 442); R20c; H35 retest-then-go all lost.
### `book-woods-20` Linear-regression channel: buy pullbacks to the lower line in a rising channel
- Source: How to Trade Price Action, Ch. 9.1. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: 50-bar linear regression channel (+/- 2 standard errors) with slope above a threshold; when price touches the lower line and a bullish bar forms, buy stop above it. Mirror in a falling channel.
- Stop: Below the touch bar's low.
- Exit: The upper channel line; else square-off.
- Filters: Channel slope 'healthy' (ours: > 0.1 ATR per 10 bars).
- Trade: buy 1-ITM CE / PE
- Assumptions (our reading where the book is vague): The book draws trend lines by hand; a regression channel is the mechanical stand-in it names.
- Data: have
- Our nearest test: Not tested by us.

## The Power of Divergence Trading - David Carli ([write-up](carli-divergence.md))
### `book-carli-01` CCI(20) regular divergence, three qualifying rules, trendline-break entry, Fibonacci targets (daily bars)
- Source: The Power of Divergence Trading, Ch. 4. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily bars (book: mostly daily; weekly as a variant). Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: Bullish: two confirmed swing lows L1 < ... with price low(L2) < low(L1) and CCI(L2) > CCI(L1); CCI(L2) <= -150; max CCI between L1 and L2 <= 0; with A = low(L1), B = the highest high between L1 and L2, the extension (B - low(L2)) / (B - A) is in [1.13, 1.618]. Then draw the resistance trendline through the two most recent confirmed swing highs at or before L2 (the first being B or the high before it) and extend it; go long when a daily CLOSE is above the line (conservative version; the aggressive version enters intraday on the first cross). Bearish mirrored (swing highs, CCI >= +150, min CCI between the highs >= 0, support line through swing lows).
- Stop: 15-20 ticks beyond the divergence extreme, read on the underlying: low(L2) minus 0.1% of price for longs (high(L2) plus 0.1% for shorts).
- Exit: Target 1 at the 127.2% extension, Target 2 at the 161.8% extension of the leg from the second extreme L2 to the first opposite swing after it (projected from L2 in the trade direction); close half at T1 and move the stop to the entry price; close the rest at T2. Skip the trade if reward to T1 is less than 1:1 of the stop distance.
- Filters: CCI length 20 on hlc3; no trade if the trendline breaks more than 20 bars after L2 (setup stale); skip a long on a gap-up day and a short on a gap-down day (Ch. 2).
- Trade: on index: buy the next-month 1-ITM CE (long) / PE (short) or trade the future, at the next session's 09:16 open; on MCX: the near future or a monthly ITM option; all stops and targets on the underlying as the book states
- Assumptions (our reading where the book is vague): The book draws swings, trendlines and Fibonacci legs by eye and grants unquantified 'small exceptions' to rules 1-2; we use swing pivots of 3 bars each side (confirmed 3 bars later), no exceptions, the hlc3 CCI input, a 0.1%-of-price buffer for '15-20 pips/ticks', the trendline through the last two swing highs (lows), a 20-bar staleness limit, and our reading of the target leg (Figure 42 unreadable). Rule 3 read as the second extreme lying 1.13-1.618 of the AB pullback beyond B.
- Data: have
- Our nearest test: R95 tested CCI(25) regular divergence on 3m-60m bars only (no trendline, no Fibonacci size rule, no +-150 zone): entered when known, 1,778 index trades -Rs 3,37,415 design; not tested by us on daily bars or with this entry
### `book-carli-02` Same Carli divergence setup on roughly 4-hour bars
- Source: The Power of Divergence Trading, Ch. 4. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: half-session bars: NSE 09:15-12:15 and 12:15-15:30; MCX 4-hour bars from 09:00. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE, NATURALGAS.
- Entry: as carli-cci-div-trendline-daily, computed on these bars; entry at the next minute's open after the bar that closes beyond the trendline
- Stop: as carli-cci-div-trendline-daily
- Exit: as carli-cci-div-trendline-daily (half at 127.2%, stop to entry, rest at 161.8%); positions may be held overnight
- Filters: CCI length 20; staleness 20 bars
- Trade: buy the 1-ITM CE / PE of the next-week (index) or monthly (MCX) expiry; stops and targets on the underlying
- Assumptions (our reading where the book is vague): The book says 'rarely' 4-hour and never lower; NSE has no natural 4-hour bar, so two bars a session stand in. Same pivot, buffer and leg assumptions as the daily rule.
- Data: have
- Our nearest test: R95: CCI fade and divergence variants on 60m failed the holdout (BANKNIFTY 60m B passed design on 48 trades, holdout 17 trades -Rs 1,014); this exact setup not tested by us
### `book-carli-03` Do Carli's three divergence filters improve R95's confirmed divergences?
- Source: The Power of Divergence Trading, Ch. 4. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute and 60-minute bars (R95's stores). Instruments: NIFTY, BANKNIFTY.
- Entry: R95's confirmed regular divergence (pivots 5/5, known at pivot + 5 bars), kept only if: CCI(20) at the second extreme beyond -+150; CCI did not cross zero between the two pivots; second price extreme is a 1.13-1.618 extension of the AB pullback. Entry at the next minute's open after confirmation, as in R95.
- Stop: beyond the second pivot by 1 tick (R95) and, variant, by 0.1% of price (Carli)
- Exit: variant A: R95's CCI-back-to-0 exit; variant B: half at 127.2%, stop to entry, rest at 161.8%, flat at 15:15
- Filters: R95's session window 09:30-14:45 entries
- Trade: 1-ITM nearest-expiry CE on bullish, PE on bearish; real prints, real costs, 1 lot
- Assumptions (our reading where the book is vague): Tests the filters on intraday bars, which the author says he never uses; the point is a cheap check on existing R95 code of whether the filters separate good from bad divergences. Unfiltered R95 divergences are the control.
- Data: have
- Our nearest test: R95 confirmed-divergence entries lost (index design 1,778 trades -Rs 3,37,415; holdout 691 trades -Rs 37,156); DV variant 0-52 trades a cell
### `book-carli-04` Skip a trade in the direction of today's opening gap
- Source: The Power of Divergence Trading, Ch. 2. Family: `daily-filters`. Signal source: underlying price.
- Timeframe: daily gap (today's open vs yesterday's high / low), applied to any intraday or swing entry. Instruments: NIFTY, BANKNIFTY.
- Entry: no new entry of its own; on a rising-window day (open > yesterday's high) skip long signals, on a falling-window day (open < yesterday's low) skip short signals
- Stop: unchanged (filter only)
- Exit: unchanged (filter only)
- Filters: true window only: today's low > yesterday's high (gap up) or today's high < yesterday's low (gap down), known from the first bar that keeps the gap unfilled; the literal reading uses the open only
- Trade: applied to Carli's own divergence entries (the book's intent); optionally as a shadow skip rule on the running arms (risk-lowering only)
- Assumptions (our reading where the book is vague): The book gives the advice for its own trades without a time limit; we apply it to the gap day only.
- Data: have
- Our nearest test: not tested by us in this form; R87 found gap-day context did not make any candle shape predictive (0 of 4,049 shape x context cells)

## The First Trading Manual - Trader Tom ([write-up](trader-tom-first-manual.md))
### `book-tom-01` 4-bar fractal momentum entry (David Paul)
- Source: The First Trading Manual, Trade Entry Technique 4 Bar Fractal, p. 115-118. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute and 60-minute bars (5-minute as a variant). Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: Long when bar t closes above high[t-1] AND above high[t-3]; short when bar t closes below low[t-1] AND below low[t-3]. Enter at the next bar's open, 09:30-14:30 (MCX: session hours).
- Stop: Underlying stop at the lowest low of bars t-3..t for a long (highest high for a short).
- Exit: Opposite fractal signal (stop-and-reverse off), else the stop, else square-off 15:10 (15:05 on expiry).
- Filters: none (base); variant: only in the direction of the daily 3-bar swing trend (rule three-bar-swing-bias)
- Trade: buy 1-ITM CE on a long signal, 1-ITM PE on a short; MCX: the near future or its 1-ITM option; stop on the underlying
- Assumptions (our reading where the book is vague): The book gives no stop or exit; the four-bar extreme as stop and the opposite signal as exit are our most literal choices. 'Bar three back' read as high[t-3] (the book numbers bars 1 = current, 2 = previous, 4 = three back).
- Data: have
- Our nearest test: not tested by us; nearest R71 P7 three-bar reversal at the day high/low lost (-Rs 1,67,689, 855 trades) and R4 G3 Gann 2-bar swing lost Rs 172 a trade net (6,227)
### `book-tom-02` Stochastic hidden divergence in the trend, timed by a 4-bar fractal
- Source: The First Trading Manual, Divergence Strategies, p. 119-126. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 60-minute bars (book uses 4-hour FX and 5-minute Dow); 15-minute variant. Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Trend down (close below its 89-period SMA): price makes a lower confirmed swing high (2-bar fractal pivot) while slow stochastic %K(14,3) at that pivot is higher than at the previous swing high; then the first 4-bar fractal sell within 10 bars enters short. Mirror for longs in an uptrend (higher low in price, lower low in %K).
- Stop: Above the divergence swing high (below the swing low for longs).
- Exit: Opposite 4-bar fractal, else stop, else square-off 15:10 (15:05 expiry) for intraday; 60-minute version may hold to 15:10 only.
- Filters: trend by the 89-period SMA on the trading timeframe
- Trade: buy 1-ITM PE on the short signal, 1-ITM CE on the long; stop on the underlying
- Assumptions (our reading where the book is vague): The book gives no stochastic settings, pivot rule, trend definition, stop or exit; 14,3 stochastic, 2-bar pivots, 89-SMA trend (the book's own trend MA) and a 10-bar window are our choices.
- Data: have
- Our nearest test: R95 CCI divergence labels: +Rs 12.0 lakh where drawn, -Rs 3.4 lakh once known; stochastic only inside B2's coin-flip model
### `book-tom-03` Daily / weekly 3-bar swing (3-bar Donchian) as the trend bias
- Source: The First Trading Manual, Trend Indicators; Daily Work Routine, p. 67-74. Family: `daily-filters`. Signal source: underlying price.
- Timeframe: daily bars (weekly variant). Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE, NATURALGAS.
- Entry: State = UP after any trade above the highest high of the previous 3 completed daily bars; DOWN after any trade below their lowest low; state persists until the opposite break. Used as a filter: allow an arm's long (call) entries only in UP and short (put) entries only in DOWN.
- Stop: the arm's own stop
- Exit: the arm's own exit
- Filters: apply to R51 Donchian 20, R72 Breadth Donchian, Liquidity 15+5; weekly variant uses the previous 3 weekly bars, set Sunday night
- Trade: filter only (skip entries against the bias); for live this would start in shadow (logged only), switch off by default
- Assumptions (our reading where the book is vague): Book says 'trade below the 3-bar low' turns the trend; we use intraday trade-through of the level (not a close). Book text says both 'last 3 bars' and 'last 4 trading days'; we take the previous 3 completed bars.
- Data: have
- Our nearest test: not tested by us at 3 bars; R101 filters on the Donchian arms 0 of 20 (filters that raised the win rate lowered the net); R89 daily+15m trend filter did not cut false breaks
### `book-tom-04` Daily 3-bar breakout traded directly
- Source: The First Trading Manual, Trend Indicators, p. 67-70. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily levels, entry on 1-minute trade-through. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD.
- Entry: Buy-stop at the highest high of the previous 3 daily bars, sell-stop at their lowest low; first trigger of the day only, 09:20-14:30.
- Stop: the opposite 3-bar level, capped at 1.0 x daily ATR14 from entry
- Exit: square-off 15:10 (15:05 expiry); variant: hold the future until the opposite 3-bar break
- Filters: none
- Trade: buy 1-ITM CE on an up-break, 1-ITM PE on a down-break; MCX: the near future
- Assumptions (our reading where the book is vague): The book uses it as a trend tool, not an entry with stops; the entry, the ATR cap and the exit are ours.
- Data: have
- Our nearest test: not tested at 3 bars; R89 PDHL (1-bar daily levels) 0 of 180; R51 Donchian 20 on 5-minute passed thin (+Rs 48,689, 1,775 trades)
### `book-tom-05` Body-engulfing bar on 10-minute index bars in the trend direction
- Source: The First Trading Manual, Engulfing Patterns for Day-Trading, p. 105-110. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 10-minute bars built from 1-minute (5-minute variant). Instruments: NIFTY, BANKNIFTY.
- Entry: Bullish: bar t is green, bar t-1 red, open[t] <= close[t-1] and close[t] >= open[t-1] (body engulfs body), with the 60-minute close above its 89-period SMA. Cautious entry (book's method 3): buy-stop at high[t] valid for the next 2 bars. Bearish mirror.
- Stop: the far end of the engulfing bar (low[t] for longs) on the underlying
- Exit: move the stop to breakeven once the underlying is +1R; then trail at the low of the last completed 10-minute bar; exit at the next doji (body <= 10% of range) against the position's high/low by tightening to that bar; square-off 15:10 (15:05 expiry)
- Filters: 09:30-14:30 entries; trend by 60-minute 89-SMA
- Trade: buy 1-ITM CE on a bullish signal, 1-ITM PE on bearish; stops and trail on the underlying as the book states
- Assumptions (our reading where the book is vague): Book gives no explicit stop; 'tells us how much to risk' read as the bar's far end. Trend filter not defined in the chapter; the book's 89-period MA used. Breakeven trigger at +1R and the bar-low trail are our readings of 'runs immediately' and 'move the stop progressively'.
- Data: have
- Our nearest test: R87 tested engulfing among 43 shapes: no direction edge, 0 of 4,049 context cells; R87B exits on an opposite engulfing hurt
### `book-tom-06` Reverse after a failed engulfing trade (5-minute scalp)
- Source: The First Trading Manual, A Special Note on Scalping, p. 114. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: When an engulfing-bar trade (as in engulfing-10min-trend but on 5-minute bars, normal entry at the bar's close) hits its stop within 3 bars, enter the opposite side at the stop price.
- Stop: the extreme of the failed engulfing trade's adverse move plus 0.25 x 5-minute ATR14
- Exit: target 1.5 x initial risk on the underlying, else 30 minutes, else 15:10
- Filters: 09:30-14:30; one reversal per day per index
- Trade: buy 1-ITM option in the reversal direction
- Assumptions (our reading where the book is vague): Book shows one example with no stop or target; the 3-bar window, stop buffer, 1.5R target and 30-minute time stop are ours.
- Data: have
- Our nearest test: not tested by us as an entry; R87B: after a strong candle against you price tends to come back (exits on opposite engulfing hurt)
### `book-tom-07` Fake-out / fake-down of the previous swing, fade back into the range
- Source: The First Trading Manual, The Fake Moves, p. 103-104. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (5-minute variant). Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Fake-down: a bar trades below the most recent confirmed swing low (2-bar fractal) and the same or next bar closes back above it; buy at that close. Fake-out mirror for shorts.
- Stop: 1 tick beyond the fake extreme (the lowest low of the break)
- Exit: target the most recent confirmed swing high on the other side of the range; else 15:10 (15:05 expiry)
- Filters: range must be at least 1.0 x ATR14 wide; 09:30-14:30
- Trade: buy 1-ITM CE after a fake-down, 1-ITM PE after a fake-out; stop and target on the underlying
- Assumptions (our reading where the book is vague): Book gives only a diagram; swing definition, close-back confirmation and range minimum are ours.
- Data: have
- Our nearest test: R3 ORB Sweep / Range Fade lost Rs 5,05,954 / 5,10,978; R91 swing-sweep fade 0 of 63; R89 break-and-reclaim lost
### `book-tom-08` 1-minute trendline break after a fake-down (Stanley)
- Source: The First Trading Manual, Case Story: Stanley the Intraday Trader, p. 159-160. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 1-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Long: at least 3 descending 1-minute swing highs (2-bar fractals) lie within 0.05% of one straight line through the first and last; price then trades below the prior swing low, turns, and a 1-minute close crosses above the line; buy next minute. Short mirror.
- Stop: below the low made by the fake-down
- Exit: not stated; ours: 2R target or 30 minutes, square-off 15:10
- Filters: skip if the first-hour range is below its 20-day median (range-bound proxy); no entries 15 minutes before and after scheduled high-impact events; risk 1% of capital
- Trade: buy ATM CE on long, ATM PE on short
- Assumptions (our reading where the book is vague): Line-fitting tolerance, the range-bound proxy, the event window and the exit are ours; the book is discretionary on all of these.
- Data: have
- Our nearest test: not tested by us exactly; fake-out fades (R3, R91) failed
### `book-tom-09` Trend change on the break of the lower low (and continuation buy above the old high)
- Source: The First Trading Manual, Principles of Price Action & Trend, p. 56-62. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: With confirmed 2-bar fractal pivots: after an uptrend (last two swing highs and lows rising), a lower swing high then a lower swing low; short when price trades below that lower low. If instead price trades above the last swing high first, buy (continuation).
- Stop: the lower high (short) / the last swing low (continuation long)
- Exit: 2R target or square-off 15:10 (15:05 expiry)
- Filters: 09:30-14:30
- Trade: buy 1-ITM PE on the short, 1-ITM CE on the continuation long
- Assumptions (our reading where the book is vague): Pivot strength, timeframe, target are ours.
- Data: have
- Our nearest test: R71 P8 swing structure HH+HL lost Rs 40,109 on 374 final trades; R90 BOS entries 0 of 99
### `book-tom-10` Buy the 60-minute pullback in a daily uptrend (fade the short-term trend)
- Source: The First Trading Manual, Price Behaviour Principle 2, p. 43-48. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily trend, 60-minute pullback, 15-minute trigger. Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Daily trend UP by the 3-bar swing (rule three-bar-swing-bias); the 60-minute chart has made a lower high and lower low since the last daily high; enter long on the first 15-minute 4-bar fractal buy. Mirror for daily DOWN.
- Stop: the lowest low of the 60-minute pullback
- Exit: the pullback's starting high (prior 60-minute swing high) or square-off 15:10; futures variant may hold up to 3 sessions
- Filters: none beyond the daily trend
- Trade: buy 1-ITM CE in a daily uptrend, 1-ITM PE in a daily downtrend
- Assumptions (our reading where the book is vague): The book names the idea but no trigger; combining it with the book's own 3-bar swing and 4-bar fractal is our reading.
- Data: have
- Our nearest test: not tested by us as a fixed rule; R89 daily+15m trend filter did not help PDHL breaks; direction at 15-60 min a coin flip (R16)
### `book-tom-11` Buy-stop above a dragonfly doji (where the shorts' stops sit)
- Source: The First Trading Manual, The Dragonfly Pattern, p. 89. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: After at least 5 lower closes in the prior 8 bars, a dragonfly doji (body <= 10% of range, close in the top 10% of range); buy-stop 1 tick above its high, valid 3 bars.
- Stop: the dragonfly's low
- Exit: 2R or square-off 15:10 (15:05 expiry)
- Filters: 09:30-14:30
- Trade: buy 1-ITM CE
- Assumptions (our reading where the book is vague): Downtrend definition, doji thresholds, validity and target are ours; the book gives the stop-hunt tip only.
- Data: have
- Our nearest test: R87: dragonfly/doji shapes no direction edge; stop runs above the day high are followed by more upside (R87, R86)
### `book-tom-12` Short on the break of a gravestone doji's low after a rise
- Source: The First Trading Manual, Gravestone Doji, p. 88. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: After at least 5 higher closes in the prior 8 bars, a gravestone doji (body <= 10% of range, close in the bottom 10%); sell-stop 1 tick below its low, valid 3 bars.
- Stop: the gravestone's high
- Exit: 2R or square-off 15:10 (15:05 expiry)
- Filters: 09:30-14:30
- Trade: buy 1-ITM PE
- Assumptions (our reading where the book is vague): Trend count, thresholds and target are ours.
- Data: have
- Our nearest test: R87-3 SENSEX 15m gravestone -> put +Rs 14,373 on 71 trades but failed walk-forward; NIFTY 1m gravestone did not hold out of sample
### `book-tom-13` Spinning top at the end of a pullback, enter on its break with the main trend
- Source: The First Trading Manual, Pattern Number 2, Spinning Top, p. 83-84. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Main trend DOWN (close below 89-SMA on 15-minute, falling over the last 40 bars); a counter-rally of at least 5 bars; a spinning top (body <= 30% of range, both wicks >= 25%); sell-stop below its low, valid 5 bars. Mirror for uptrends.
- Stop: the rally's high
- Exit: the low that started the rally, or square-off 15:10
- Filters: 09:30-14:30
- Trade: buy 1-ITM PE (CE in the mirror)
- Assumptions (our reading where the book is vague): The book's example uses 40-50 bars of downtrend and 8-10 bars of rally; thresholds, target and trend proxy are ours.
- Data: have
- Our nearest test: R87: spinning top no edge; 0 of 4,049 shape x context cells
### `book-tom-14` Hanging man / hammer confirmed by a close beyond support within 3 bars
- Source: The First Trading Manual, Pattern Number 4, Umbrella, p. 91-93. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute and daily bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Umbrella = small body, upper wick <= 10% of range, lower wick >= 2 x body. After an uptrend (two prior strong green bars), a hanging man; short if any of the next 3 bars closes below the hanging man's low (the support). Hammer after a downtrend: long if a close above its high within 3 bars.
- Stop: the umbrella's high (hanging man) / low (hammer)
- Exit: 2R or square-off 15:10 (daily variant: 5 sessions on the future)
- Filters: none
- Trade: buy 1-ITM PE after a confirmed hanging man, 1-ITM CE after a confirmed hammer
- Assumptions (our reading where the book is vague): Book's '2/3 times the body' read as 2-3x; 'support' read as the umbrella's low.
- Data: have
- Our nearest test: R87: NIFTY 15m hammer -0.47 ATR after 20 candles vs +0.19 matched (continuation, not reversal); daily shapes 0 pass (R87B)
### `book-tom-15` Red marubozu after a failed push up in a downtrend
- Source: The First Trading Manual, Pattern Number 7, Marabozu, p. 95-96. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Downtrend (close below 89-SMA); a rally of at least 2 green bars; then a red marubozu (wicks each <= 5% of range, body >= 1.0 x ATR14); sell at its close. Mirror for uptrends.
- Stop: the rally's high
- Exit: 2R or 30 minutes or 15:10
- Filters: 09:30-14:30; skip the 15 minutes after scheduled data
- Trade: buy 1-ITM PE
- Assumptions (our reading where the book is vague): Wick tolerance, size threshold, trend filter and exit are ours.
- Data: have
- Our nearest test: R87: marubozu no direction edge out of sample on 5m/15m (NIFTY 1m red marubozu did not hold)
### `book-tom-16` Equal-size pullback limit buy (harmonic retracement)
- Source: The First Trading Manual, Case Story: David the Swing Trader, p. 156-158. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (60-minute trend filter). Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: In a 60-minute uptrend, measure the last completed pullback (swing high to swing low, 2-bar fractals); after a new swing high, place a limit buy at new high minus that pullback size; fill on touch. Mirror in downtrends.
- Stop: 1.0 x the pullback size beyond the fill
- Exit: the new swing high, or square-off 15:10; futures variant may hold 3 sessions
- Filters: only when the 60-minute chart shows higher highs and higher lows
- Trade: buy 1-ITM CE when the limit level is touched (option bought at that minute)
- Assumptions (our reading where the book is vague): The book gives the entry only; stop size, target and trend test are ours.
- Data: have
- Our nearest test: R4 ABCD on the touch of D: 10,927 trades, -Rs 227 a trade net; Fibonacci pullback entries no better than random (R4/h37)
### `book-tom-17` Trade only the first busy hours after the open
- Source: The First Trading Manual, The 24-Hour Trading Cycle, p. 15-19. Family: `daily-filters`. Signal source: none / clock.
- Timeframe: intraday clock. Instruments: NIFTY, BANKNIFTY.
- Entry: Filter for any arm: allow new entries only 09:15-12:15 IST (the first 3 hours, the book's busy window); none 12:15-14:30.
- Stop: the arm's own
- Exit: the arm's own
- Filters: applies to Liquidity 15+5, R51 Donchian, R72 Breadth Donchian
- Trade: filter only; skip entries outside the window (risk-lowering; shadow first for live)
- Assumptions (our reading where the book is vague): The book's London window (08:00 plus 2-3 hours) mapped to NSE's open; the cut at 12:15 is ours.
- Data: have
- Our nearest test: R92: every entry hour loses for the ORB arms (0 of 257); R101 B4 mornings only on R72 raised win rate but cut the final net (-Rs 81,567)
### `book-tom-18` No new entries around high-impact scheduled news
- Source: The First Trading Manual, The 24-Hour Trading Cycle; Stanley case study. Family: `calendar-events`. Signal source: none / calendar.
- Timeframe: intraday. Instruments: NIFTY, BANKNIFTY, CRUDE, NATURALGAS, GOLD.
- Entry: Filter: no new entries from 15 minutes before to 15 minutes after a high-impact release (India: RBI policy, Budget, CPI; MCX: EIA crude/gas storage, US CPI, NFP, FOMC in MCX hours).
- Stop: the arm's own
- Exit: the arm's own
- Filters: event calendar (research/context/events.csv)
- Trade: filter only (skip), shadow first for live
- Assumptions (our reading where the book is vague): Window width is ours; 'red-flagged' mapped to the events in our calendar.
- Data: have (India events); lack: a verified historical calendar of US release times for every MCX date
- Our nearest test: EVENT_SHIELD: BANKNIFTY policy-day range 1.48x (15 sessions); pausing arms on event days not an edge; R83 EIA fade/follow failed
### `book-tom-19` Add one lot at +1R with the stop to entry (scaling in)
- Source: The First Trading Manual, Managing Your Risk, Scaling In, p. 131. Family: `trade-management`. Signal source: underlying price (the arm's own signal).
- Timeframe: the arm's own. Instruments: NIFTY, BANKNIFTY.
- Entry: On an open winning position at +1R (underlying), add an equal position and move both stops to the first entry; book's numbers: add at +75, exit at +150 on the 4-hour chart (reward/risk 3 instead of 2).
- Stop: first entry price for both
- Exit: first position's target (2R from the first entry)
- Filters: paper arms with a record (R51 Donchian, R72 Breadth Donchian)
- Trade: a second 1-ITM option lot of the same strike
- Assumptions (our reading where the book is vague): Points mapped to R multiples of the arm's stop.
- Data: have
- Our nearest test: R81 X7 pyramid on Liquidity +Rs 58k design, -Rs 20k final; H5 pyramiding raised gross not net
### `book-tom-20` Scale out in thirds (Mark Douglas)
- Source: The First Trading Manual, Managing Your Risk, Scaling Out, p. 130. Family: `trade-management`. Signal source: underlying price (the arm's own signal).
- Timeframe: the arm's own. Instruments: NIFTY, BANKNIFTY.
- Entry: the arm's own, in 3 lots
- Stop: the arm's own; after the second third, stop to entry
- Exit: one third at +1R, one third at +2R (book: +20 and +40 points on 4-hour FX), last third on the arm's own exit
- Filters: none
- Trade: 3 lots of the arm's option, sold in thirds
- Assumptions (our reading where the book is vague): Points mapped to R multiples.
- Data: have
- Our nearest test: R81 X5 scale out half at 1R rejected (-Rs 79k design, -Rs 35k final)

## Liquidity, Markets and Trading in Action - Ozenbas, Pagano, Schwartz and Weber ([write-up](ozenbas-liquidity-markets.md))
### `book-ozenbas-01` Daily close crosses its 65-day moving average
- Source: Liquidity, Markets and Trading in Action, Ch. 3.7 (footnote 7). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily bars. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: Long when the daily close crosses from below to above its 65-day simple moving average; short (or flat, for a long-only test) when it crosses from above to below. Act at the next session's open.
- Stop: Not stated by the book. Assumption: initial stop 2 x ATR(14) daily from the entry price on the underlying.
- Exit: The opposite cross (literal reading of the book's buy/sell pair), or the stop.
- Filters: The book puts a top-down macro view and a fundamental value check first; neither is mechanical, so none is applied.
- Trade: Trade the near-month future, or buy the nearest-monthly ATM CE on a long signal / ATM PE on a short, rolled 5 sessions before expiry; stops on the underlying.
- Assumptions (our reading where the book is vague): Simple (not exponential) average; signal on the close, fill at next open; 2 x ATR stop and the option roll are ours. The book gives the crossover only as an example of technical analysis and neither supports nor denies it.
- Data: have
- Our nearest test: Not tested by us on daily index or MCX bars. Intraday EMA crosses lost (OBUY_FINAL TI-03 EMA cross, -Rs 369,856 over 2022-2026, 96 variants; R29 ribbons 0 pass). Crypto daily SMA/Donchian trend (RC1) made +24.4% a year after tax vs +58.8% buy and hold.
### `book-ozenbas-02` Enter with a capped limit instead of a market order; never chase
- Source: Liquidity, Markets and Trading in Action, Ch. 2.1, 2.9.1, 5.4. Family: `sizing-execution`. Signal source: none (execution overlay on any underlying signal).
- Timeframe: 1-minute bars (entry minute + 3 minutes). Instruments: NIFTY, BANKNIFTY.
- Entry: On an arm's buy signal, send a limit buy at the entry-bar option price + 0.5%; take what fills at once up to that price, leave the rest resting 3 minutes, then cancel.
- Stop: Unchanged from the arm.
- Exit: Unchanged from the arm (market or capped limit).
- Filters: None.
- Trade: the arm's own option (signal on the underlying, strike by the arm's rule)
- Assumptions (our reading where the book is vague): The book only states the trade-off (limit saves the spread but risks no fill); the +0.5% cap and 3-minute rest are h14's pre-registered values.
- Data: have
- Our nearest test: h14: the only one of 9 execution policies to pass every gate (+Rs 288/day vs +250 required); holdout +Rs 625/day at kappa 0.02; not robust at kappa 0.04. Already used by the Liquidity arm.
### `book-ozenbas-03` Rest the entry at the best bid (maker) instead of crossing the spread
- Source: Liquidity, Markets and Trading in Action, Ch. 2.1, 2.9.1 (limit orders save the spread; non-execution and pick-off risk). Family: `sizing-execution`. Signal source: none (execution overlay on any underlying signal).
- Timeframe: tick / 1-second quotes, entry window 1-3 minutes. Instruments: NIFTY, BANKNIFTY.
- Entry: On an arm's buy signal, post a limit buy at the current best bid of the chosen option; re-peg to the new best bid at most once; cancel if not filled within 2 minutes (the signal is then skipped).
- Stop: Unchanged from the arm.
- Exit: Unchanged from the arm.
- Filters: Only when the half-spread at the signal is >= 0.15% of premium (otherwise the saving is too small to pay for missed trades).
- Trade: the arm's own option
- Assumptions (our reading where the book is vague): The 2-minute window, one re-peg and the 0.15% threshold are ours. The test must count missed trades at their market-order P&L (opportunity cost) and the adverse-selection bias (fills happen more often when price moves against us).
- Data: **needs data we lack** - lack: historical bid/ask and top-of-book quantity for index options (only one snapshot, 2026-10-06; the app recorder's quote log since 6 Oct 2026 is the source once long enough)
- Our nearest test: Not tested by us. h24 measured the spread to be saved (half-spread 0.16% NIFTY/BANKNIFTY, one snapshot); h14 tested only marketable limits.
### `book-ozenbas-04` Fade big moves on no-news days, follow them on scheduled-event days
- Source: Liquidity, Markets and Trading in Action, Ch. 2.3 (information vs liquidity shocks). Family: `calendar-events`. Signal source: underlying price (plus an event calendar).
- Timeframe: 1-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Between 09:30 and 14:30, when a 5-minute index move exceeds 3x the trailing 20-day average absolute 5-minute move for that time of day: on a day with no scheduled event, enter AGAINST the move at the next bar; on an RBI / Budget / heavyweight-results day, enter WITH the move.
- Stop: Assumption: 1x the triggering 5-minute move beyond the entry, on the index.
- Exit: Assumption: 30 minutes after entry, or the stop; flat by 15:10.
- Filters: No entries 09:15-09:30 (opening discovery) or after 14:30.
- Trade: buy ATM CE on a long signal, ATM PE on a short; stops on the underlying
- Assumptions (our reading where the book is vague): The book gives only the principle (liquidity shocks revert, information shocks do not). The 3x threshold, windows, stop and holding time are our literal reading and must be pre-registered.
- Data: have
- Our nearest test: R86: fading failed pokes lost on all 6 level x index tests (295-804 trades, Rs -2.3 to -5.5 lakh); fade to VWAP lost Rs 64,786 on 71 trades. h28: event days move more but 8,820 variants found no side.
### `book-ozenbas-05` Buy when the best bid steps to a new higher level (TraderEx Exercise 4)
- Source: Liquidity, Markets and Trading in Action, Ch. 5.4, Exercise 4. Family: `patterns-indicators`. Signal source: underlying price (index futures best bid; proxy: 1-minute high).
- Timeframe: 1-minute bars (tick data preferred). Instruments: NIFTY, BANKNIFTY.
- Entry: Long when the index future's best bid (proxy: the 1-minute close) makes a new high of the last 15 minutes; short on a new 15-minute low. One position at a time.
- Stop: Not stated. Assumption: the opposite 15-minute extreme on the future.
- Exit: Not stated beyond 'sell later at a higher price' and 'flat by the close'. Assumption: exit on the opposite 15-minute extreme or at 15:10.
- Filters: None in the book.
- Trade: buy ATM CE on a long signal, ATM PE on a short; stops on the underlying
- Assumptions (our reading where the book is vague): The 15-minute lookback and the 1-minute-close proxy for the bid are ours; the book's exercise is discretionary and warns the spread is paid on every round trip.
- Data: have for the 1-minute proxy (index futures minutes); lack for the literal best-bid version (no bid/ask history)
- Our nearest test: Close to tested breakout rules: R89 previous-day high/low break 0 of 180, ORB family 0 of 257 (R92), F1/R30 direction coin flip; R7 order flow 0.42x of cost.
### `book-ozenbas-06` Work a multi-lot entry in slices and score it against interval VWAP
- Source: Liquidity, Markets and Trading in Action, Ch. 2.8.2, 5.4 (Exercises 1-2). Family: `sizing-execution`. Signal source: none (execution overlay).
- Timeframe: 1-minute bars over a 5-minute entry window. Instruments: BANKNIFTY.
- Entry: When an arm buys N lots with N above the lots shown at the best ask, split the order into k = ceil(N / top-of-book lots) child orders, one per minute, each a capped limit (+0.5%), instead of one order.
- Stop: Unchanged from the arm.
- Exit: Unchanged; exits may be sliced the same way.
- Filters: Only when N exceeds top-of-book size.
- Trade: the arm's own option
- Assumptions (our reading where the book is vague): The slicing schedule is ours; the book only says slicing and patience beat one big market order and that VWAP is the yardstick. Only matters at size (tens of lots).
- Data: **needs data we lack** - lack: option book depth history (have option 1-minute volume for a VWAP benchmark; h10's capacity model is the stand-in for depth)
- Our nearest test: h10: capacity BANKNIFTY 129 lots in the holdout, FINNIFTY 0; costs ate 55-65% of gross at plan size. h14: the capped limit keeps the cost curve from bending at large size.
### `book-ozenbas-07` Post a bid and an offer around the mid and capture the spread (TraderEx Exercise 3)
- Source: Liquidity, Markets and Trading in Action, Ch. 5.4, Exercise 3. Family: `option-structure`. Signal source: none (order book, market making).
- Timeframe: seconds. Instruments: NIFTY, BANKNIFTY.
- Entry: Keep a limit buy at the best bid and a limit sell at the best ask of an ATM option; inventory cap 1 lot either side.
- Stop: Assumption: flatten if inventory is held more than 2 minutes or the mid moves 1% against it.
- Exit: Flat by the close (the book's prop-trader rule).
- Filters: None in the book.
- Trade: quote the ATM option both ways; 1 lot
- Assumptions (our reading where the book is vague): Inventory cap and stop are ours. Listed for completeness: it needs co-location-level speed and queue position.
- Data: **needs data we lack** - lack: tick-by-tick order book and queue position; retail latency (R7: 0.3-1 s to see a tick, 0.2-1 s to send an order) makes it infeasible
- Our nearest test: Not tested by us. R7: the published order-flow edge belongs to co-located market makers; a phone app cannot compete.

## Advanced Trading Book / Simple Trading Book - The Advanced Trading Book ([write-up](advanced-trading-book.md))
### `book-atb-01` Hammer at a prior swing low, bought only after an up candle confirms
- Source: Advanced Trading Book / Simple Trading Book, Ch. 2, Hammer (p11). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (5-minute as a variant). Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Hammer bar H: lower wick >= 2x body, upper wick <= 0.25x range, range >= 0.8 ATR; downtrend into H; H's low within 0.25 ATR of a swing low from the last 5 sessions. Confirmation: the next bar closes above H's high. Enter at the open of the bar after the confirmation bar. Mirror (shooting star / hanging man at a swing high, confirmation close below H's low) for shorts.
- Stop: Underlying 1 tick beyond H's low (high for shorts).
- Exit: Target 3R on the underlying (the book's 1:3 ratio); else square-off.
- Filters: None stated beyond prior decline and support.
- Trade: buy 1-ITM CE on a long signal, 1-ITM PE on a short; stop and target on the underlying
- Assumptions (our reading where the book is vague): Book gives no numbers for wick ratios, 'support', 'confirmation', stop or target; we use the hammer's low as stop and the glossary's 1:3 as target.
- Data: have
- Our nearest test: R87: NIFTY 15m hammer followed by -0.47 ATR over 20 candles vs +0.19 matched; wick lows not defended (59% vs 57%, n 3,314); GOLD_CANDLES 1h hammer buys-only weak (t 0.84, 424 trades)
### `book-atb-02` Engulfing candle at the end of a trend
- Source: Advanced Trading Book / Simple Trading Book, Ch. 2, Bullish / bearish engulfing (p13, p18). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, GOLD.
- Entry: Downtrend into bar 1; bar 1 red; bar 2 green with open <= bar 1 close and close >= bar 1 open (body engulfs body), bar 2 body >= 1.5x bar 1 body. Enter at bar 3 open. Mirror for bearish engulfing after an uptrend.
- Stop: Beyond bar 2's low (high for shorts).
- Exit: 3R target or square-off.
- Filters: None.
- Trade: buy 1-ITM CE on bullish engulfing, 1-ITM PE on bearish
- Assumptions (our reading where the book is vague): Stop, target and entry timing are ours (book: 'leads to a reversal').
- Data: have
- Our nearest test: R87: engulfing among 12 patterns, nothing held out of sample; GOLD_CANDLES: 144 versions, none with t above 1.1; R85 MCX: no tradable meaning
### `book-atb-03` Morning / evening star three-candle reversal
- Source: Advanced Trading Book / Simple Trading Book, Ch. 2, Morning star / evening star (p12, p19). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Downtrend; bar 1 red with body >= 1 ATR; bar 2 body <= 0.3x bar 1 body (either colour); bar 3 green closing above the midpoint of bar 1's body. Enter at bar 4 open. Mirror for evening star.
- Stop: Beyond bar 2's extreme.
- Exit: 3R or square-off.
- Filters: None.
- Trade: buy 1-ITM CE on a morning star, 1-ITM PE on an evening star
- Assumptions (our reading where the book is vague): 'Long', 'short-bodied' and the third candle's reach are our numbers; the book's evening-star drawing has bar 3 closing deep into bar 1's body.
- Data: have
- Our nearest test: R87: SENSEX 1m morning star +0.090 ATR design (n 3,466) reversed to -0.083 in the holdout (n 837)
### `book-atb-04` Three white soldiers after a downtrend, confirmed by RSI
- Source: Advanced Trading Book / Simple Trading Book, Ch. 2, Three white soldiers / three black crows (p14, p20). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, GOLD.
- Entry: Downtrend before bar 1; bars 1-3 green, each body >= 0.6 ATR, each open and close above the previous bar's open and close, wicks <= 0.25x range; RSI(14) on the same bars crosses above 50 by bar 3. Enter at bar 4 open. Mirror (three black crows, RSI below 50) for shorts.
- Stop: Below bar 1's low (above for crows).
- Exit: 3R or square-off.
- Filters: RSI(14) > 50 at bar 3 (the book names RSI without a level).
- Trade: buy 1-ITM CE on soldiers, 1-ITM PE on crows
- Assumptions (our reading where the book is vague): RSI level 50 and the stop at the pattern's start are ours.
- Data: have
- Our nearest test: R87-1 NIFTY 5m three white soldiers -> call: -Rs 3,910 on 160 trades; GOLD_CANDLES 15m soldiers negative in the held-out year in every version
### `book-atb-05` Doji, then trade the direction that is clear three candles later
- Source: Advanced Trading Book / Simple Trading Book, Ch. 2, Doji (p22). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Doji D: body <= 0.1x range, range >= 0.8 ATR. Wait 3 bars; if bar 3 after D closes above D's high, enter long at the next open; if below D's low, short; else no trade.
- Stop: The opposite end of D.
- Exit: 3R or square-off.
- Filters: None.
- Trade: buy 1-ITM CE on long, 1-ITM PE on short
- Assumptions (our reading where the book is vague): 'A few candles' = 3; 'clear' = a close beyond the doji's range.
- Data: have
- Our nearest test: R87-3 SENSEX 15m gravestone doji -> put: +Rs 14,373 on 71 but walk-forward -Rs 1,923: fail
### `book-atb-06` Falling trendline (neckline) break, retest and bullish candle (Strategy 1)
- Source: Advanced Trading Book / Simple Trading Book, Ch. 3, Strategy 1 (p26). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars for the line, 5-minute bars for the retest candle. Instruments: NIFTY, BANKNIFTY, CRUDE.
- Entry: Line through the last 3 or more falling swing highs (each touch within 0.1 ATR of the line, no close above it between touches). A 15m close above the line = break (no entry). Within 12 bars after the break, a 5m bar trades back within 0.1 ATR of the line (retest) and closes green above the line: buy at the next 5m open. Long only (the book takes only the bullish side).
- Stop: Below the retest bar's low.
- Exit: 3R or square-off.
- Filters: At least 3 line touches; break and retest in the same session.
- Trade: buy 1-ITM CE
- Assumptions (our reading where the book is vague): Touch tolerance, retest window and stop are ours; the book gives no stop or target for this setup.
- Data: have
- Our nearest test: R22: break-and-retest of the morning range no better than the break (retest rule -20,547 design / -6,095 holdout); H35 retest-then-go on levels: 408 variants all lose after costs; trendlines themselves never tested
### `book-atb-07` Symmetrical triangle after a rise: break, retest, bullish candle, buy the next candle (Strategy 2)
- Source: Advanced Trading Book / Simple Trading Book, Ch. 3, Strategy 2 (p27). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD.
- Entry: Uptrend (close >= 2 ATR above the close 20 bars before the first triangle swing); at least 2 falling swing highs and 2 rising swing lows whose lines converge; close above the upper line = break (no entry); within 8 bars a bar trades back to within 0.1 ATR of the upper line and the next bar closes green above it; buy at the open of the bar after that green bar. Mirror after a fall.
- Stop: Below the last swing low inside the triangle.
- Exit: Measured move: triangle height at its widest, projected from the break (Chapter 4 drawings); else square-off.
- Filters: Continuation only: trade in the direction of the prior trend.
- Trade: buy 1-ITM CE after a rise, 1-ITM PE after a fall
- Assumptions (our reading where the book is vague): Swing count, trend test, retest window, stop and measured target are ours (book gives none).
- Data: have
- Our nearest test: Not tested by us as a triangle; nearest R71 P3 squeeze release: final -Rs 41,281 on 305 trades
### `book-atb-08` Rising wedge in a downtrend: lower-line break, previous-low break, retest, sell (Strategy 3)
- Source: Advanced Trading Book / Simple Trading Book, Ch. 3, Strategy 3 (p28). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE.
- Entry: Downtrend before the wedge; a wedge of at least 2 rising swing highs and 2 rising swing lows with the lower line rising faster (converging). Step 1: close below the lower line (no entry). Step 2: close below the last swing low inside the wedge. Step 3: within 8 bars price trades back up to within 0.1 ATR of that broken low and a bar closes red below it: sell at the next open. Mirror (falling wedge in an uptrend) for longs.
- Stop: Above the last swing high inside the wedge (the book: 'above the previous high').
- Exit: Measured move (wedge height at its start) or square-off.
- Filters: All three steps in order; a close back above the broken low cancels.
- Trade: buy 1-ITM PE (CE for the mirror)
- Assumptions (our reading where the book is vague): Wedge geometry, windows and target are ours; stop is the book's.
- Data: have
- Our nearest test: Wedges not tested; previous-swing breaks failed in R90 (0 of 99) and R89 (0 of 180)
### `book-atb-09` Triangle in a downtrend: sell the lower-line break at once, stop at the last lower high (Strategy 4)
- Source: Advanced Trading Book / Simple Trading Book, Ch. 3, Strategy 4 (p29). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE.
- Entry: Downtrend; at least 2 falling swing highs and 2 swing lows within 0.3 ATR of each other (flat base); the first close below the base: sell at the next open (no retest). Mirror (ascending triangle in an uptrend) for longs.
- Stop: Above the LAST swing high inside the triangle (the book's 'level 2', the tighter of two).
- Exit: Measured move (triangle height) or square-off.
- Filters: Prior downtrend; triangle at least 8 bars long.
- Trade: buy 1-ITM PE (CE for the mirror)
- Assumptions (our reading where the book is vague): The book calls it 'symmetrical' but draws a flat base; we follow the drawing. Target is ours.
- Data: have
- Our nearest test: R20c: two-close breaks of horizontal levels +0.02 ATR to the close, random levels the same (7,548 breaks)
### `book-atb-10` Flag (parallel channel against a sharp pole), trade the channel break (Strategy 5, sheet p58)
- Source: Advanced Trading Book / Simple Trading Book, Ch. 3, Strategy 5 (p30); Ch. 4 flags (p58). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE, CRYPTO.
- Entry: Pole: a move of >= 2 ATR within 5 bars. Flag: 4-15 bars drifting against the pole inside two parallel lines (slopes within 30% of each other), retracing <= 50% of the pole. Bearish flag (rising channel after a drop): sell at the open after the first close below the lower line. Bullish mirror: buy after the first close above the upper line.
- Stop: Beyond the opposite channel line at the break bar.
- Exit: Target = pole length from the break (drawn on p58), else square-off.
- Filters: None stated.
- Trade: buy 1-ITM PE on a bearish flag, 1-ITM CE on a bullish flag; CRYPTO: same stop plus the step profit lock
- Assumptions (our reading where the book is vague): Pole size, flag length, parallel tolerance, stop and target are ours.
- Data: have
- Our nearest test: R71 P4 flag: only 32 final trades (-Rs 442 with L25), design -Rs 27,378: too rare to judge
### `book-atb-11` Sideways box after a move: break, retest, confirming candle (Strategy 6)
- Source: Advanced Trading Book / Simple Trading Book, Ch. 3, Strategy 6 (p31). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars for the box, 5-minute for the retest. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD.
- Entry: Prior move >= 2 ATR within 10 bars; then >= 6 bars whose highs and lows all lie inside a band <= 1.2 ATR tall (box top = highest high, bottom = lowest low). A 15m close above the top = break. Within 12 five-minute bars price returns to within 0.1 ATR of the top and a 5m bar closes green above it: buy at the next open. Mirror below the bottom.
- Stop: Below the box midpoint (variant: below the retest bar's low).
- Exit: Measured move = box height from the break, else square-off.
- Filters: Box must start after the prior move (consolidation, not opening range).
- Trade: buy 1-ITM CE above the box, 1-ITM PE below
- Assumptions (our reading where the book is vague): Box size and length, retest window, stop and target are ours; book gives only the retest-and-bullish-candle entry.
- Data: have
- Our nearest test: Closest arm: Donchian plain breakout, Rs 55,439 on 1,787 final trades (R71); retest not better on the morning range (R22); ORB family 0 of 257 (R92); run it beside Donchian with and without the retest
### `book-atb-12` Bullish flag: wait for the last swing high to break, buy the first bullish candle after one pullback candle (Strategy 7)
- Source: Advanced Trading Book / Simple Trading Book, Ch. 3, Strategy 7 (p32). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute bars (15-minute variant). Instruments: NIFTY, BANKNIFTY, CRUDE.
- Entry: Flag as in flag-channel-break. Candle A: first close above the highest high of the flag (the last swing high, not the channel line). Candle B: the next bar closes red. Candle C: the next bar closes green: buy at C's close (next-minute fill). If the bar after A is green, wait up to 6 bars for a red one; no red bar = no trade. Mirror for bearish flags.
- Stop: Below B's low.
- Exit: Target = pole length from the break, else square-off.
- Filters: A close back below the flag high before C cancels.
- Trade: buy 1-ITM CE (PE for the mirror)
- Assumptions (our reading where the book is vague): The book implies the stop near the pullback ('a smaller stop'); exact level, waiting window and target are ours.
- Data: have
- Our nearest test: R71 P8 swing-high break: final -Rs 34,242 on 372; R87B green-green-red cluster held (+0.26 ATR, n 182) but its call with a stop under the red candle lost -Rs 21,127 (162)
### `book-atb-13` Double top / bottom neckline break with stop inside and measured target
- Source: Advanced Trading Book / Simple Trading Book, Ch. 4, Double top / double bottom (p34, p38, p53, p54). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Two swing highs within 0.25 ATR, at least 4 bars apart, a swing low between them (neckline) at least 1 ATR below; sell at the open after the first close below the neckline. Mirror for double bottoms.
- Stop: Midway between the neckline and the tops (the drawing places it inside the pattern, below the peaks).
- Exit: Target = top-to-neckline height projected from the neckline; else square-off.
- Filters: None.
- Trade: buy 1-ITM PE on a double top, 1-ITM CE on a double bottom
- Assumptions (our reading where the book is vague): The midway stop and measured target are read from the drawings; R71 used the extreme as stop and Donchian's exits.
- Data: have
- Our nearest test: R71 P5 double bottom / top (5-min swings): final -Rs 12,091 on 502, design -Rs 94,311; the book's stop and target differ from R71's
### `book-atb-14` Head and shoulders / inverse: neckline break
- Source: Advanced Trading Book / Simple Trading Book, Ch. 4, Head and shoulders (p36, p39, p53, p54). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (daily variant for MCX and index). Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE, NATURALGAS.
- Entry: Three swing highs with the middle highest by >= 0.5 ATR and the outer two within 0.5 ATR of each other; neckline = line through the two swing lows between them; sell at the open after the first close below the neckline after the right shoulder. Mirror for inverse.
- Stop: Above the midpoint between the right shoulder top and the neckline (as drawn).
- Exit: Target = head-to-neckline height from the break; else square-off (daily variant: hold up to 5 sessions with futures, not options).
- Filters: Prior uptrend into the left shoulder (downtrend for inverse).
- Trade: buy 1-ITM PE (CE for inverse); MCX daily variant on the future
- Assumptions (our reading where the book is vague): Symmetry tolerances, stop and target from the drawings.
- Data: have
- Our nearest test: not tested by us
### `book-atb-15` Falling wedge buy / rising wedge sell on the boundary break (no retest)
- Source: Advanced Trading Book / Simple Trading Book, Ch. 4, Falling wedge / rising wedge (p35, p37, p40, p42, p53-p55). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE, CRYPTO.
- Entry: Falling wedge: >= 2 falling swing highs and >= 2 falling swing lows, the upper line falling faster (converging); buy at the open after the first close above the upper line. Rising wedge mirror: sell after the first close below the lower line.
- Stop: Beyond the opposite line at the break bar.
- Exit: Target = the wedge's starting height (back to its first swing), else square-off.
- Filters: Both the continuation (after a move the same way) and reversal versions are drawn; test both separately.
- Trade: buy 1-ITM CE on a falling-wedge break, 1-ITM PE on a rising-wedge break; CRYPTO: plus step profit lock
- Assumptions (our reading where the book is vague): Wedge geometry and target from the drawings.
- Data: have
- Our nearest test: not tested by us
### `book-atb-16` Bullish / bearish pennant break
- Source: Advanced Trading Book / Simple Trading Book, Ch. 4, Pennants (p43, p45, p55). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute and 15-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE.
- Entry: Pole >= 2 ATR within 5 bars; then 4-12 bars forming a converging triangle (falling highs, rising lows) inside the pole's last 50%; buy after the first close above the upper line (bullish pole) or sell below the lower line (bearish pole).
- Stop: Beyond the opposite pennant line.
- Exit: Target = pole length from the break, else square-off.
- Filters: Break in the pole's direction only.
- Trade: buy 1-ITM CE / PE in the pole's direction
- Assumptions (our reading where the book is vague): Pole, pennant size and target ours, from the drawings.
- Data: have
- Our nearest test: R71 P4 flag (nearest): 32 final trades, too rare
### `book-atb-17` Bullish / bearish rectangle break with stop at the far side (chart-pattern version, no retest)
- Source: Advanced Trading Book / Simple Trading Book, Ch. 4, Rectangles (p41, p44, p54, p55). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Box as in consolidation-box-break-retest; buy at the open after the first close above the top (sell below the bottom), only in the direction of the move before the box.
- Stop: Just beyond the far side of the box.
- Exit: Target = box height from the break, else square-off.
- Filters: Continuation only.
- Trade: buy 1-ITM CE above, 1-ITM PE below
- Assumptions (our reading where the book is vague): Box definition ours; stop and target from the drawings.
- Data: have
- Our nearest test: Close cousin of the Donchian arm (positive record, R71/R72); compare head to head on the same days
### `book-atb-18` Triple top / bottom: break of the opposite side after three touches
- Source: Advanced Trading Book / Simple Trading Book, Ch. 4, Triple top / triple bottom (p46, p47, p56). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, GOLD.
- Entry: Three swing highs within 0.25 ATR of each other; support = lowest swing low between them; sell at the open after the first close below support. Mirror for triple bottoms.
- Stop: Above the midpoint of the pattern.
- Exit: Target = pattern height from the break, else square-off.
- Filters: None.
- Trade: buy 1-ITM PE (CE for triple bottoms)
- Assumptions (our reading where the book is vague): Book draws only the lines; stop and target are ours.
- Data: have
- Our nearest test: R71 P5 (two touches) lost; R20b levels no better than random lines
### `book-atb-19` Cup and handle (and inverse): break of the rim after the handle
- Source: Advanced Trading Book / Simple Trading Book, Ch. 4, Cup and handle (p51, p52, p57). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute and 60-minute bars. Instruments: NIFTY, BANKNIFTY, GOLD, CRYPTO.
- Entry: Rim = a swing high; a cup of >= 15 bars whose lowest low is >= 1.5 ATR below the rim and whose right side returns within 0.25 ATR of the rim; a handle of 3-10 bars retracing <= 50% of the cup depth; buy at the open after the first close above the rim. Mirror below a support for the inverse.
- Stop: Below the handle's low.
- Exit: Target = cup depth from the rim, else square-off.
- Filters: None.
- Trade: buy 1-ITM CE (PE for the inverse); CRYPTO: plus step profit lock
- Assumptions (our reading where the book is vague): All geometry ours; the book draws only a resistance line and the shape.
- Data: have
- Our nearest test: not tested by us

## Trading Volatility - Colin Bennett ([write-up](bennett-trading-volatility.md))
### `book-bennett-01` Sell defined-risk premium only when implied is dear against forecast realised (volatility risk premium filter)
- Source: Trading Volatility, Ch. 1.2 Figure 1, Ch. 2.1 (implied - realised = carry), Ch. 3.1. Family: `option-structure`. Signal source: volatility / options structure (index's own ATM IV vs its own forecast realised vol); no direction.
- Timeframe: 1-minute chain, one decision a day at 09:20; daily bars for the realised-vol forecast. Instruments: NIFTY, BANKNIFTY.
- Entry: At 09:20, compute ratio = nearest-expiry ATM IV (mean of ATM call and put IV) / forecast realised vol to expiry (HAR on daily close-to-close plus intraday Yang-Zhang, fitted on earlier data only). If ratio >= its rolling 252-day 70th percentile, sell the ATM straddle and buy wings 4 strikes out (iron fly).
- Stop: Basket loss = 100% of the credit received, or the fly's max loss if smaller (stops on hedged structures belong at the max loss, per R103).
- Exit: 15:10 square-off on non-expiry days; on expiry day 15:05 (app rule). No overnight hold in version A; version B holds to expiry with the same stop.
- Filters: Skip if a pre-listed event (Budget, RBI, election result, US CPI/FOMC) falls before exit; skip if the index fell more than 3% the previous day (Bennett A.6: after a crash do not sell near-dated calls below the size of the fall).
- Trade: Iron fly, 1 lot: short ATM CE + PE, long ATM+4 CE and ATM-4 PE; longs filled first, shorts closed first.
- Assumptions (our reading where the book is vague): Bennett gives no threshold for 'dear'; the 70th percentile and the 4-strike wing are our choices. The forecast excludes the overnight gap for intraday holds because h29 found IV carries overnight risk an intraday seller does not collect.
- Data: have (wings within ATM +-10; reprice any leg without a print, per R103's stale-wing warning)
- Our nearest test: R103 sold iron flies / condors every day: 0 of 24 confirmed. R102: India VIX over-states NIFTY's next 21 days on 81% of days (median 1.28, n 4,507) but under-states BANKNIFTY's on 60%. A VRP-timed filter was not tested.
### `book-bennett-02` NIFTY buy-write: long future, short weekly ~25-delta call
- Source: Trading Volatility, Ch. 1.4, Call overwriting. Family: `option-structure`. Signal source: volatility / options structure (overwriting); the long future is a passive holding.
- Timeframe: daily; weekly expiry cycle. Instruments: NIFTY.
- Entry: Each Wednesday 09:20 (day after the Tuesday expiry): hold 1 lot NIFTY future (rolled monthly) and sell 1 lot of the nearest weekly call whose delta is closest to 0.25 (Bennett: one-month 103-104% strike or a fixed 25-delta).
- Stop: None in the book; research variant adds a stop on the future at -3% from entry to stay inside the account stop.
- Exit: Call held to expiry (cash settled); re-sell next Wednesday.
- Filters: None (Bennett: enhanced / timed overwriting on an index rarely beats always overwriting).
- Trade: Benchmark only; compare with long future alone on return, Sortino and worst month. Not an app arm (the app holds no index longs).
- Assumptions (our reading where the book is vague): Weekly instead of monthly because NIFTY weeklies exist; 25-delta weekly strike is about 1-2% OTM, inside ATM +-10.
- Data: have (futures bars, nearest-expiry chain with IV for delta)
- Our nearest test: not tested by us
### `book-bennett-03` Put underwriting as a defined-risk bull put spread at 25 delta
- Source: Trading Volatility, Ch. 1.2, Put underwriting benefits from selling expensive implied. Family: `option-structure`. Signal source: volatility / options structure (sell the vol premium and skew); no direction signal.
- Timeframe: daily; weekly (NIFTY) / monthly (BANKNIFTY) expiry cycle. Instruments: NIFTY, BANKNIFTY.
- Entry: Day after expiry 09:20: sell the nearest-expiry put with delta closest to -0.25 and buy the put 3 strikes lower.
- Stop: Close if the spread's value reaches 2x the credit.
- Exit: Hold to 15:05 on expiry day (app square-off) or the stop.
- Filters: Optional (book): only when the put's IV exceeds the index's forecast realised vol (see vrp-filtered-iron-fly).
- Trade: 1 lot bull put spread; long leg filled first.
- Assumptions (our reading where the book is vague): Bennett's naked short put at a support level is replaced by a 25-delta strike (support levels were random lines in R20b) and a spread (no naked shorts).
- Data: have (strikes within ATM +-10; BANKNIFTY monthly 25-delta put may sit near the edge of +-10 - reprice stale legs)
- Our nearest test: R103 tested symmetric credit structures, not one-sided put spreads: 0 of 24. IV1's put-writing OI rule was a buying signal, not put selling.
### `book-bennett-04` Long ATM straddle, gamma-scalped with futures at fixed index bands, only when implied is cheap
- Source: Trading Volatility, Ch. 2.1 Delta hedging, Ch. 3.5 Discrete hedging. Family: `option-structure`. Signal source: volatility / options structure (implied vs forecast realised); hedge on the underlying future.
- Timeframe: 1-minute bars, intraday. Instruments: NIFTY, BANKNIFTY.
- Entry: At 09:20 if nearest-expiry ATM IV / forecast intraday realised vol <= its rolling 30th percentile, buy 1 lot ATM straddle.
- Stop: Close everything if the straddle plus hedge loses 40% of the premium paid.
- Exit: 15:10 (15:05 on expiry day).
- Filters: Hedge rule: compute straddle delta from expected (forecast) vol, not implied (Bennett 3.5). When straddle delta x option lot >= +0.5 future lot, sell 1 future lot; when <= -0.5, buy 1; flatten the future when delta returns inside +-0.25 lot. Variant B: hedge only when the index moves 0.5% from the last hedge (bands), to test 'hedge less in trends'.
- Trade: 1 lot ATM CE + PE plus 0 or +-1 NIFTY / BANKNIFTY future lot.
- Assumptions (our reading where the book is vague): A one-lot hedge is coarse (NIFTY future 65 units = option lot), so the hedge only switches in whole lots; thresholds are ours. Futures costs added per hedge.
- Data: have (index futures 1-minute bars and option chain)
- Our nearest test: R89: unhedged straddles 0 of 18 (4,302 design straddles); h29: buying when IV is cheap, 16 variants all lost. A futures-hedged version was never tested.
### `book-bennett-05` Sell skew (short ATM-5 put, long ATM+5 call, delta hedged) outside jumpy regimes
- Source: Trading Volatility, Ch. 7.5, Skew trading; Ch. 1.6 risk reversals. Family: `option-structure`. Signal source: volatility / options structure (skew level and realised skew); hedge on the underlying future.
- Timeframe: 1-minute chain; daily decision at 09:20; held to 15:10. Instruments: NIFTY, BANKNIFTY.
- Entry: At 09:20 if skew = IV(ATM-5 put) - IV(ATM+5 call) is above its rolling 60-day median AND the regime is not jumpy (yesterday's realised skew = change in ATM IV / skew between yesterday's and today's spot <= 1, and India VIX up less than 10% on the day), sell the ATM-5 put, buy the ATM+5 call, and short futures to delta-neutral in whole lots.
- Stop: Basket loss of 1.5% of the index notional of one lot, or India VIX up 15% intraday.
- Exit: 15:10.
- Filters: Skip event days; skip if the index fell more than 2% the previous day (jumpy regimes cluster after falls).
- Trade: 1 lot each leg plus futures hedge. Note: the short put is naked unless a further put is bought; research version adds a long ATM-9 put to keep risk defined.
- Assumptions (our reading where the book is vague): Bennett's realised-skew definition is made literal on daily ATM IV changes; strikes 5 out because 90% strikes are outside ATM +-10 on a weekly.
- Data: have
- Our nearest test: not tested by us; R102 measured the skew level only (ATM-5 put dearer than ATM+5 call by 2.56 IV points on 91% of 1,222 NIFTY days).
### `book-bennett-06` Calendar trade when implied moves break the square-root-of-time rule
- Source: Trading Volatility, Ch. 7.2-7.3, Square root of time rule; term structure trading. Family: `option-structure`. Signal source: volatility / options structure (term structure).
- Timeframe: daily, 09:20 and 15:10 snapshots. Instruments: NIFTY.
- Entry: Each day estimate the power p from the change in ATM IV of the nearest and next weekly expiries: dIV_near / dIV_next = (T_next / T_near)^p. If ATM IV rose and p > 0.7, buy the calendar (sell nearest ATM, buy next-expiry ATM, same strike); if IV rose and p < 0.3, sell the calendar. Mirror for falls.
- Stop: Close at a loss of 50% of the debit (long calendar) or 2x the credit-equivalent risk.
- Exit: Close at 15:10 the day before the near expiry, or when p returns to 0.4-0.6.
- Filters: Skip when the near expiry is the day after an event (event vol, not term structure).
- Trade: 1 lot each leg; since 1 Feb 2025 calendar margin benefit is removed on the near leg's expiry day.
- Assumptions (our reading where the book is vague): Bennett's 0.5 power and his long/short rule; the 0.3 / 0.7 bands are ours.
- Data: **needs data we lack** - lack: next-expiry ATM chain is mostly missing from the rolling lake (h29 had monthly-minus-weekly IV only while both were listed)
- Our nearest test: h29: term structure (monthly minus weekly ATM IV) did not sort the realised move; calendar trades not tested by us (R94's calendars were MCX futures spreads).
### `book-bennett-07` Sell an overpriced event jump: short the expiry after the event, long the expiry before
- Source: Trading Volatility, Ch. 6.4, Trading earnings announcements / jumps. Family: `calendar-events`. Signal source: volatility / options structure around a calendar event.
- Timeframe: daily, entry the session before the event. Instruments: NIFTY.
- Entry: For each Budget, RBI policy and general-election result day bracketed by two NIFTY expiries: implied jump vol = sqrt(IV_after^2 x T_after - IV_before^2 x (T_after - 1 day)) using the before-expiry IV as diffusive vol; convert to an expected one-day move. If it exceeds 1.3x the median absolute move of past events of the same type, sell the after-event ATM straddle and buy the before-event ATM straddle (vega-matched).
- Stop: Close if the basket loses 1x the jump premium collected.
- Exit: Close at 15:10 on the event day (or next session if the event is after hours).
- Filters: Defined risk: add wings 4 strikes out on the short straddle.
- Trade: 1 lot short after-expiry iron fly, long before-expiry straddle scaled by vega.
- Assumptions (our reading where the book is vague): Bennett's stock earnings method moved to index macro events; the 1.3x threshold is ours.
- Data: **needs data we lack** - lack: needs two expiries' chains on event days (rolling lake keeps the nearest expiry only); events.csv exists (R98d)
- Our nearest test: Event straddle buying lost (R1 rows 22-24: realised 0.69x implied); Budget day doubles the range (2.13% NIFTY, n 6). The jump calendar was not tested.
### `book-bennett-08` Relative-value vol: NIFTY vs BANKNIFTY ATM IV spread mean reversion (theta-weighted)
- Source: Trading Volatility, Ch. 6.2, Relative value volatility trading. Family: `option-structure`. Signal source: volatility / options structure (spread of two indices' implieds).
- Timeframe: daily at 09:20. Instruments: NIFTY, BANKNIFTY.
- Entry: Compute s = BANKNIFTY ATM IV / NIFTY ATM IV at matched time to expiry (convert each to 30-day forward vol with additive variance, or use only days when both have 5-7 days to expiry). If s is above its rolling 120-day 90th percentile, sell BANKNIFTY vol and buy NIFTY vol; below the 10th, the reverse. Legs theta-weighted (Bennett: same-type securities).
- Stop: Spread move of 2 rolling standard deviations against, or basket loss of 2% of combined notional.
- Exit: s back to its median, or 10 sessions.
- Filters: Defined risk on the short leg (iron fly).
- Trade: Short iron fly on one index, long straddle on the other, theta-weighted in whole lots.
- Assumptions (our reading where the book is vague): BANKNIFTY is monthly and NIFTY weekly, so the matched-expiry rule is ours; whole-lot weighting is coarse.
- Data: have for same-day ATM IV of both nearest expiries; lack for matched maturities
- Our nearest test: not tested by us; R102: BANKNIFTY realised is about 1.3x NIFTY's and beta 1.157.
### `book-bennett-09` After a sharp fall, near-zero-cost 1x2 call spread made defined-risk (broken-wing butterfly)
- Source: Trading Volatility, Ch. 1.4, 1x2 call spreads are useful when a bounce-back is expected. Family: `option-structure`. Signal source: underlying price (the fall), then options structure.
- Timeframe: daily bars for the signal; option chain at 09:20 next day. Instruments: NIFTY, BANKNIFTY.
- Entry: If the index fell 4% or more over the last 3 sessions, at 09:20 buy 1 ATM call, sell 2 calls at the strike nearest half the fall above spot, buy 1 call 2 strikes above that.
- Stop: None beyond the structure's defined max loss.
- Exit: Expiry (15:05 on expiry day) or when spot reaches the short strike.
- Filters: None stated.
- Trade: 1-2-1 broken-wing call butterfly, 1 lot.
- Assumptions (our reading where the book is vague): 'Sharp drop' and 'bounce to earlier levels' are made literal as 4% over 3 sessions and half the fall; the extra long call replaces the stock that covered the second short call in the book.
- Data: have
- Our nearest test: Direction after falls is a coin flip; R102 daily momentum (+5.8 bps NIFTY) reversed in the holdout (-9.4 bps, 65 days). Not tested as a structure.
### `book-bennett-10` Is skew a risk indicator? Next-day range vs 09:20 skew, controlling for ATM IV
- Source: Trading Volatility, Ch. 7.1, Skew is often mistakenly used as a risk indicator. Family: `daily-filters`. Signal source: option chain as context (skew and ATM IV).
- Timeframe: daily. Instruments: NIFTY, BANKNIFTY.
- Entry: Descriptive test, no trade: regress the rest-of-day range (09:20 to 15:30) and next-day range on 09:20 ATM IV and on skew = IV(ATM-5 put) - IV(ATM+5 call). Bennett's claim: skew adds nothing once ATM IV is known and often moves against it.
- Stop: n/a
- Exit: n/a
- Filters: n/a
- Trade: none; if skew adds no information Jarvis must not call high skew a fear signal
- Assumptions (our reading where the book is vague): Fixed-strike skew uses ATM-5 vs ATM+5 because 90% strikes are outside ATM +-10.
- Data: have
- Our nearest test: R16 and R102: VIX / ATM IV is the range dial; IV1 and h29: skew carries no direction. Skew vs range given ATM IV not tested.

## 9 Advanced and Profitable Trading Strategies - Roman Sadowski ([write-up](sadowski-9-strategies.md))
### `book-sadowski-01` 20/60 SMA crossover with a 100 SMA trend read
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 2. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily bars (book); 60-minute bars as a variant. Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Long when SMA20 crosses above SMA60 at a bar close; short when it crosses below. Enter at the next bar's open.
- Stop: None stated; the opposite cross is the exit. For option buying add a premium safety stop of -40% (our assumption).
- Exit: Opposite SMA20/SMA60 cross.
- Filters: Optional variant: take only longs while the close is above SMA100 and shorts while below (the book uses the 100 as a trend read, not a hard filter).
- Trade: Buy the 1-ITM monthly CE on a long signal, PE on a short. On MCX, buy the near-month option; or trade the future.
- Assumptions (our reading where the book is vague): MA type assumed simple. Always-in-market reversal. The SMA100 filter is our reading of 'bars stay above the 100'.
- Data: have
- Our nearest test: OBUY_FINAL TI-03 EMA cross: 96 variants, walk-forward -369,856, 1/5 years positive; HUNT_H25 MA crosses 0 of 28 EMA families positive walk-forward
### `book-sadowski-02` Daily Heikin-Ashi two-candle reversal with stochastic 14,7,3
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 3. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily bars. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: Setup long: two consecutive completed green HA candles after at least 3 red HA candles, and the stochastic(14,7,3) %K was below 30 within the last 5 bars. Place a buy-stop 0.1 ATR14 above the second green HA candle's high, valid for the next 3 sessions. Short is the mirror (two red after 3+ green, %K above 70 within 5 bars, sell-stop below the second red candle's low).
- Stop: 1.0 x ATR14(daily) from entry (the book's '100 pips flat'), or the setup's swing low/high if nearer.
- Exit: Move the stop to breakeven after +0.5 ATR14 (the book's +50 pips); then trail to the latest 2-bar swing low/high; exit on an opposite HA setup.
- Filters: Skip the RBI policy day, the US FOMC day, the Union Budget and exchange holidays (the book's NFP/FOMC/bank-holiday rule). Skip if the prior daily bar's range > 2 x ATR14 ('volatile swing'). Optional AC variant: require Accelerator Oscillator > 0 and rising for longs (< 0 and falling for shorts).
- Trade: Buy the 1-ITM monthly CE (long) / PE (short); stops and trail on the underlying; max hold 10 sessions or 5 sessions before expiry.
- Assumptions (our reading where the book is vague): The book's stochastic sentence has the colours swapped; we read it as oversold for longs. Pips converted to ATR fractions. 'Series of candles' = at least 3. The AC sign for shorts is read as below zero.
- Data: have
- Our nearest test: HUNT_R71 P6 Heikin-Ashi flip (15-min, EMA filter): -Rs 79,173 on 357 trades, fail; OBUY_FINAL TI-08 Heikin-Ashi -170,762 walk-forward; daily HA + stochastic not tested by us
### `book-sadowski-03` Break out of an overlapping correction in the trend direction
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 4. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 60-minute bars (book: 4-hour forex); 15-minute variant. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD.
- Entry: Trend down: close below EMA50 and EMA50 falling over 10 bars. Correction: the last 8 bars all sit inside a box no taller than 1.5 x ATR14 and each overlaps the previous bar by at least 50% of its range. Short on the first close below the box low; long is the mirror (close above the box high in an uptrend). Book long variant: wait for 2 closes above the box high and buy the next open.
- Stop: The most recent 2-bar swing high (short) or the box low (long).
- Exit: Not stated by the book; we use a 3R target or a trailing stop at the last 2-bar swing, plus a time exit at 15:15 for intraday options or 5 sessions on 60-min bars.
- Filters: Trend by EMA50 as above.
- Trade: Buy the 1-ITM CE/PE; stop on the underlying.
- Assumptions (our reading where the book is vague): Overlap, contraction and trend are all our definitions (8 bars, 1.5 ATR box, 50% overlap, EMA50); the book gives only examples.
- Data: have
- Our nearest test: HUNT_R71 P3 squeeze release -Rs 41,281 on 305 trades and P8 swing structure -Rs 34,242 on 372 trades, both fail; Donchian 20 breakout is our paper arm
### `book-sadowski-04` Engulfing candle, enter the next open, stop at its extreme
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 5. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (book: timeframe not stated). Instruments: NIFTY, BANKNIFTY.
- Entry: Bullish: a green candle whose real body covers the real bodies of the previous N (1, 2 or 3) candles; buy at the next candle's open. Bearish is the mirror.
- Stop: The engulfing candle's low (long) / high (short) on the underlying.
- Exit: Not stated; we use a trailing stop at the prior candle's low/high, and square-off at 15:15.
- Filters: None (the book gives none).
- Trade: Buy the 1-ITM nearest-expiry CE on bullish, PE on bearish.
- Assumptions (our reading where the book is vague): 'Covers' = body high >= max of prior bodies and body low <= min of prior bodies. N swept 1..3 as the book's 'one or more'.
- Data: have
- Our nearest test: HUNT_R87 candle anatomy: shapes do not predict direction (92% of 234 cells 45-55% up); HUNT_H25 61 TA-Lib patterns, 6 of 122 families positive walk-forward, none passed
### `book-sadowski-05` Long shadow / hammer candle, enter the next open
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 5. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: A candle whose lower wick >= 2 x its real body and whose body sits in the top third of the range: buy at the next open. Mirror (upper wick, body in the bottom third) for shorts.
- Stop: The wick's extreme.
- Exit: Trail the stop to the prior candle's low/high; square-off at 15:15.
- Filters: None.
- Trade: Buy the 1-ITM nearest-expiry CE/PE.
- Assumptions (our reading where the book is vague): 'Body at one end' = top/bottom third; the 2x rule is the book's.
- Data: have
- Our nearest test: HUNT_R87: hammers mostly mark continuation (NIFTY 15m: a further -0.47 ATR over 20 candles); a big tail's low is broken within 10 candles 59% of the time
### `book-sadowski-06` Buy the retest of broken resistance, scaled in three parts
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 6. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Uptrend = the last two 15-min swing highs and lows (3 bars each side) both rising. When a close breaks above the last swing high, place three equal limit orders for the pullback: 1/3 at EMA20, 1/3 at the broken swing high, 1/3 at the 50% retracement of the leg from the last swing low to the new high. Orders live until 14:30 or a new high is made. Mirror for downtrends.
- Stop: Not stated; we use the last swing low below the 50% level.
- Exit: Target = the new high plus 1 x the leg length, or square-off at 15:15.
- Filters: Swing-structure trend as above; pivot points optional (not used).
- Trade: Buy the 1-ITM CE in parts as each limit on the index fills (PE for the mirror).
- Assumptions (our reading where the book is vague): The book gives no stop, target or trend definition; all are ours. 'Zone' treated as the exact level for the limit.
- Data: have
- Our nearest test: OBUY_FINAL LV-01 PDH/PDL break-retest: 64 variants, -129,689 walk-forward; HUNT_R20C 2-close breaks continue 51% of 7,548; HUNT_R20B levels no better than random
### `book-sadowski-07` Bollinger 20,2 squeeze, enter on a close across the middle SMA
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 7. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 60-minute bars (book: 4-hour forex); 15-minute variant. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD.
- Entry: Squeeze = Bollinger band width (upper-lower)/middle is in its lowest 10% of the last 120 bars. While squeezed, or within 3 bars of it, buy when a full candle lies above SMA20 (open and close both above the middle line); sell when both are below.
- Stop: The prior candle's low (long) / high (short), or the level that loses 3% of capital, whichever is nearer.
- Exit: Not stated; we use the opposite band touch as the target and a 15:15 square-off intraday (5 sessions on 60-min).
- Filters: Squeeze percentile as above.
- Trade: Buy the 1-ITM CE/PE; stop on the underlying.
- Assumptions (our reading where the book is vague): The book's absolute width (0.0100 on EURUSD) is replaced by a width percentile. 'A full candle above' read as open and close above the middle line.
- Data: have
- Our nearest test: HUNT_R71 P3 squeeze release (band-close trigger): -Rs 41,281 on 305 trades, 67% overlap with Donchian; OBUY_FINAL MR-02 Bollinger alert -90,790
### `book-sadowski-08` Daily NR4 / NR7 breakout
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 8. Family: `daily-filters`. Signal source: underlying price.
- Timeframe: daily bars for the setup, 5-minute bars for the break. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD, SILVER, NATURALGAS.
- Entry: Yesterday's daily range is the smallest of the last 4 (NR4) or 7 (NR7). Today, buy on the first 5-min close above yesterday's high, sell on the first close below its low (first break only).
- Stop: The other end of the narrow day's range, on the underlying.
- Exit: Trail the stop to the prior 5-min swing low/high after +1R; square-off 15:15 (NSE) / 23:00 (MCX).
- Filters: Variant: 'chubby' narrow day (open and close both within 25% of the range from opposite ends), and 2+ consecutive narrow days.
- Trade: Buy the 1-ITM CE/PE; stop on the underlying.
- Assumptions (our reading where the book is vague): 'Trailed to suit' made precise as the swing trail after +1R. Chubby = our definition.
- Data: have
- Our nearest test: OBUY_FINAL LV-04 NR7/inside day: 72 variants, -28,141 walk-forward; HUNT_R71 P2 15-min NR7 -Rs 1,68,820; HUNT_R96 crude NR7 the only unproven MCX lead (failed on COPPER)
### `book-sadowski-09` RSI(2) above 90 buy / below 10 sell, with adds
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 9. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (book: not stated); daily variant. Instruments: NIFTY, BANKNIFTY.
- Entry: Long when RSI(2) crosses above 90; short when it crosses below 10. Add one more unit on each new cross in the same direction, up to 3 units.
- Stop: Not specified ('rigorous'); we use 1.5 x ATR14 from each entry on the underlying.
- Exit: Close all units on the opposite signal (RSI(2) crossing the other extreme), or square-off at 15:15 intraday.
- Filters: None.
- Trade: Buy the 1-ITM CE/PE per unit.
- Assumptions (our reading where the book is vague): The stop and the 3-unit cap are ours. 'Moves above' = a cross at a bar close.
- Data: have
- Our nearest test: GOLD_SCALP.md rsi2 follow on XAUUSD all negative (-234,718 over 6629 trades, M5 buys only); the Connors reading (RSI(2)<10 buy) inside the coin band on NIFTY (SWING_EXITS.md); momentum reading on NIFTY not tested by us
### `book-sadowski-10` Weekly + daily stochastic extreme at a swing level, intraday reversal entry
- Source: 9 Advanced and Profitable Trading Strategies, Strategy 1. Family: `daily-filters`. Signal source: underlying price.
- Timeframe: weekly and daily bars for the setup, 60-minute bars for entry. Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: Setup short: weekly and daily stochastic(14,3,3) %K both above 70, the index up more than 2 x ATR14(daily) over the last 10 sessions, and price within 0.5 ATR of a 60-session swing high. Over the next 5 sessions, sell on a 60-min close below the prior 60-min bar's low. Long is the mirror (below 30, near a 60-session swing low).
- Stop: Above the setup's swing high + 0.25 ATR (short), below the swing low (long).
- Exit: Not stated; we use 2R or 10 sessions.
- Filters: The book's COT bias is replaced (variant) by the 50-day trend: take only setups in its direction; counter-trend setups at half size.
- Trade: Buy the 1-ITM monthly PE (short) / CE (long).
- Assumptions (our reading where the book is vague): The COT bias, the 'major' level and the intraday 'reversal pattern' are discretionary in the book; all of these definitions are ours. Stochastic settings are borrowed from the common 14,3,3.
- Data: have (lack: CFTC COT data for a literal MCX version)
- Our nearest test: HUNT_H25 intraday stochastic within 'Other indicators' 0 of 50 families positive walk-forward; weekly+daily version not tested by us

## A Complete Guide to Day Trading - Markus Heitkoetter ([write-up](heitkoetter-day-trading.md))
### `book-heitkoetter-01` Trade with an intraday trendline; exit when it breaks
- Source: A Complete Guide to Day Trading, Step 3, Technical Indicators (Uptrend / Downtrend). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute bars (book's example), also 15-minute. Instruments: NIFTY, BANKNIFTY.
- Entry: Uptrend line: anchor 1 = the session low so far; anchor 2 = the low of the first later bar whose low is not higher than the prior bar's low (first retracement), provided anchor 2 > anchor 1. Long when a bar closes above the most recent swing high after the line is drawn (the book's example breaks a resistance level on high volume). Short mirrors with a downtrend line from the session high and the first higher high.
- Stop: A 5-minute close below the projected uptrend line (above the downtrend line for shorts).
- Exit: Trendline break (as the stop); square off 15:10 (15:05 on expiry).
- Filters: Line update rule: when a new lower low (higher high for downtrends) appears, re-anchor only if the new line is flatter, no bar's low lies below it, and every earlier anchor lies within 0.15 x ATR14(5-min) of it; if re-anchoring would break the last condition the trend is over (no new entries that side).
- Trade: buy 1-ITM CE on a long, 1-ITM PE on a short; stop and exit on the underlying as the book states
- Assumptions (our reading where the book is vague): The book draws lines by eye; 'close to the lows' made 0.15 x ATR14; entry trigger (break of the last swing high) and the 15:10 square-off are ours; volume confirmation dropped (index spot has no volume; could use futures volume from 29 Jul 2026).
- Data: have
- Our nearest test: not tested by us; nearest R71 P8 swing structure (HH + HL) lost in the final period (-Rs 40,109 on 374 trades) and H25 break-of-structure families were 0 of 18 positive
### `book-heitkoetter-02` Close crosses a 10-period SMA
- Source: A Complete Guide to Day Trading, Step 3, Simple Moving Averages. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (book's default timeframe); book's example 10-minute. Instruments: NIFTY, BANKNIFTY.
- Entry: Long when a bar closes above SMA(10) of closes after closing below it on the prior bar; short on the reverse cross.
- Stop: 0.27% of the index against the entry (the book's $200 per E-mini contract at ~1460).
- Exit: Target 0.41% of the index (book's $300); time stop after 45 minutes (3 bars); opposite cross; 15:10 square-off.
- Filters: Book: only in trending markets; tested both without a filter and with method hk-trendline-break-trend's trend state agreeing.
- Trade: buy 1-ITM CE on a long signal, 1-ITM PE on a short; stops/targets on the underlying
- Assumptions (our reading where the book is vague): Book suggests 5 or 10 periods without picking; SMA(10) chosen, SMA(5) as the one variant. Stop/target converted from the author's own $200 / $300 per contract; time stop from the book's three-bar rule.
- Data: have
- Our nearest test: H25 price x SMA crosses (SMA family 15,120 variants, Rs -145 a trade, 1 of 28 walk-forward families positive)
### `book-heitkoetter-03` 14 / 20 moving-average crossover
- Source: A Complete Guide to Day Trading, Step 3, Crossover of Moving Averages. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Long when SMA(14) crosses above SMA(20) on a bar close; short when it crosses below.
- Stop: 0.27% of the index
- Exit: 0.41% target, 45-minute time stop, opposite cross, 15:10 square-off
- Filters: none stated
- Trade: buy 1-ITM CE on long, 1-ITM PE on short; stops/targets on the underlying
- Assumptions (our reading where the book is vague): Book does not say simple or exponential; simple used (book calls them moving averages in the SMA section). Exits from the book's generic $200/$300 and three-bar time stop.
- Data: have
- Our nearest test: OBUY_GB TI-03 EMA crosses Rs -369,856 walk-forward on 4,526 trades; R29 EMA 9 x 21 Rs -110 a trade (1,785 holdout trades); the 14/20 pair itself not tested
### `book-heitkoetter-04` Turtle 20-bar breakout on 15-minute bars with the book's exits
- Source: A Complete Guide to Day Trading, Step 3, Turtle Trading; Step 5 exits. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: Long the first time price trades above the highest high of the previous 20 completed 15-minute bars; short below the lowest low of the previous 20 bars.
- Stop: 0.27% of the underlying (index or MCX future)
- Exit: 0.41% target or a 45-minute time stop, whichever first; 15:10 square-off on NSE, MCX session close on MCX
- Filters: none; at most 2 trades a day a side (as our Donchian arm)
- Trade: buy 1-ITM CE on long, 1-ITM PE on short (MCX: 1-ITM option where it prints, else the mini future)
- Assumptions (our reading where the book is vague): Book gives no exits for the turtle rule; its generic $200/$300 and three-bar time stop are used. Our Donchian arm already covers 5-minute bars with -15%/+30% premium exits; this is the book's 15-minute version for comparison.
- Data: have
- Our nearest test: R51/R73 Donchian 20 on 5-minute index bars: final Rs 81,845 on 1,781 trades (thin, R51 about zero at 2x costs); R83 crude daily Donchian-20 + Supertrend not proven
### `book-heitkoetter-05` MACD 12/26/9 signal-line cross
- Source: A Complete Guide to Day Trading, Step 3, MACD. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: MACD = EMA12 - EMA26 of closes, signal = EMA9 of MACD; long when MACD crosses above the signal on a bar close, short when it crosses below.
- Stop: 0.27% of the index
- Exit: 0.41% target, 45-minute time stop, opposite cross, 15:10
- Filters: trending markets (book); none in the base test
- Trade: buy 1-ITM CE on long, 1-ITM PE on short
- Assumptions (our reading where the book is vague): The book's wording of which line crosses which is ambiguous; the standard reading is used.
- Data: have
- Our nearest test: H25 MACD signal and zero cross in the 'other indicators' family: 27,000 variants, Rs -146 a trade, 0 of 50 walk-forward families positive
### `book-heitkoetter-06` Williams %R 14, sell above 80, buy below 20
- Source: A Complete Guide to Day Trading, Step 3, Williams %R. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (book: intraday, 14 bars). Instruments: NIFTY, BANKNIFTY.
- Entry: %R on the book's 0-100 scale = 100 x (close - lowest low 14) / (highest high 14 - lowest low 14). Short (buy PE) on the first bar close with %R > 80; long (buy CE) on the first close with %R < 20; one signal per zone visit.
- Stop: 0.27% of the index
- Exit: 0.41% target, 45-minute time stop, 15:10
- Filters: sideways markets only (book); base test unfiltered
- Trade: buy 1-ITM PE on a sell signal, 1-ITM CE on a buy signal
- Assumptions (our reading where the book is vague): Book's scale is inverted from the usual -100..0; 'one signal per zone visit' added to stop repeats.
- Data: have
- Our nearest test: H25 Williams %R as given: family no better than random
### `book-heitkoetter-07` RSI 14 crosses back through 30 / 70
- Source: A Complete Guide to Day Trading, Step 3, Relative Strength Index. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Long when Wilder RSI(14) crosses from below 30 to above 30 on a bar close; short when it crosses from above 70 to below 70.
- Stop: 0.27% of the index
- Exit: 0.41% target, 45-minute time stop, 15:10
- Filters: sideways markets (book)
- Trade: buy 1-ITM CE on long, 1-ITM PE on short
- Assumptions (our reading where the book is vague): Exits from the book's generic guidance.
- Data: have
- Our nearest test: OBUY_GB MR-03 RSI 30/70 reversal Rs -82,004 walk-forward over 1272 trades
### `book-heitkoetter-08` Bollinger 21 / 2 band fade
- Source: A Complete Guide to Day Trading, Step 3, Bollinger Bands and Channels. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 10-minute bars (book's example) and 15-minute. Instruments: NIFTY, BANKNIFTY.
- Entry: Long on the first bar that trades below SMA(21) - 2 SD of closes; short on the first bar above SMA(21) + 2 SD; entry at the next bar's open.
- Stop: 0.27% of the index
- Exit: 0.41% target or the middle band, 45-minute time stop, 15:10
- Filters: sideways markets (book)
- Trade: buy 1-ITM CE on long, 1-ITM PE on short
- Assumptions (our reading where the book is vague): 'Moves below' read as a trade beyond the band; middle-band exit added as the natural mean-reversion target.
- Data: have
- Our nearest test: OBUY_GB MR-02 Bollinger alert candle Rs -90,790 walk-forward (2136 trades); R71 P3 squeeze release lost in the final
### `book-heitkoetter-09` Trend channel: enter at the trendline, exit at the channel line
- Source: A Complete Guide to Day Trading, Step 3, Trendlines & Trend Channels. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: With a live downtrend line (hk-trendline-break-trend), draw the channel line parallel through the lowest low since anchor 1; short when price touches the downtrend line and the bar closes below it. Mirror for uptrends.
- Stop: a close beyond the trendline by 0.1 x ATR14
- Exit: touch of the channel line; 15:10
- Filters: channel at least 2 x ATR14 wide so the target exceeds the round-trip cost
- Trade: buy 1-ITM PE in a downtrend channel, CE in an uptrend channel
- Assumptions (our reading where the book is vague): Stop buffer, width filter and channel construction details are ours; the book gives only the idea.
- Data: have
- Our nearest test: not tested by us; nearest OBUY_GB MR-05 TWAP stretch fade Rs -91,856 walk-forward (713 trades)
### `book-heitkoetter-10` First 15-minute bar direction, previous-day high/low target, then reverse
- Source: A Complete Guide to Day Trading, Step 5, Stop Losses (technical) and Profit-Taking (technical). Family: `orb`. Signal source: underlying price.
- Timeframe: 15-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: At 09:30, if the 09:15-09:30 bar closed above its open, long; if below, short. If the long reaches the previous day's high, close and reverse short with the previous day's low as target (mirror for shorts).
- Stop: The far end of the first 15-minute bar (stands in for the book's trendline from yesterday's high)
- Exit: Target previous-day high (long) / low (short); 15:10 square-off
- Filters: skip if the open is already beyond the previous-day level in the trade's direction
- Trade: buy 1-ITM CE on long, 1-ITM PE on short
- Assumptions (our reading where the book is vague): The book shows two single-chart examples, not a system; the stop rule and skip filter are ours.
- Data: have
- Our nearest test: ORB family lost everywhere (R3, R92 0 of 257); R1 N11 / N12 first-candle rules lost; R89 PDH/PDL 0 of 180
### `book-heitkoetter-11` Three-bar time stop on the Donchian arm
- Source: A Complete Guide to Day Trading, Step 5, Time-Stops. Family: `trade-management`. Signal source: underlying price.
- Timeframe: 5-minute Donchian signals; time stop 15 minutes (3 bars). Instruments: NIFTY, BANKNIFTY.
- Entry: Our Donchian 20 / Breadth Donchian entries unchanged.
- Stop: arm's own stop
- Exit: exit if neither stop nor target is hit within 3 signal bars (15 minutes) instead of the arm's 30 minutes
- Filters: none
- Trade: as the arm (1-ITM)
- Assumptions (our reading where the book is vague): The book's 3 x timeframe applied to the arm's 5-minute bars; risk-lowering (earlier exit), paper only.
- Data: have
- Our nearest test: R18 V3 45-minute time stop on other arms: 0 of 60 overlays pass; R81 X4 no-progress stop -Rs 85k design
### `book-heitkoetter-12` Trail the stop at the previous 60-minute bar's high/low
- Source: A Complete Guide to Day Trading, Step 5, Trailing Stops. Family: `trade-management`. Signal source: underlying price.
- Timeframe: 60-minute bars. Instruments: NIFTY, BANKNIFTY, GOLD, CRUDE.
- Entry: any trend arm's entry (Donchian, Breadth Donchian)
- Stop: initial: the high (short) / low (long) of the entry bar; after each completed 60-minute bar move it to that bar's high / low if tighter
- Exit: trail hit; 15:10 / MCX close
- Filters: trending markets (book)
- Trade: as the arm; stop on the underlying
- Assumptions (our reading where the book is vague): Book's example is 60-minute E-mini; a 15-minute version is the natural variant.
- Data: have
- Our nearest test: not tested by us exactly; R81 X3 ATR trail cut runners (-Rs 62k design, -Rs 17k final); R19b Rs 500 trail failed
### `book-heitkoetter-13` Take profits in thirds
- Source: A Complete Guide to Day Trading, Step 5, Taking Partial Profits. Family: `trade-management`. Signal source: underlying price.
- Timeframe: arm's own. Instruments: NIFTY, BANKNIFTY.
- Entry: any arm, 3 lots
- Stop: arm's stop for all lots
- Exit: lot 1 at a fixed +0.41% index move (book's $300), lot 2 at the previous-day high/low or next pivot, lot 3 on the previous-bar trail (hk-prev-bar-trail-60m on 15-minute bars)
- Filters: none
- Trade: 3 x 1-ITM lots
- Assumptions (our reading where the book is vague): Which S/R level and which trail are our choices.
- Data: have
- Our nearest test: R81 X5 scale out half at 1R rejected (-Rs 79k design, -Rs 35k final)
### `book-heitkoetter-14` Stop trading for the week once a weekly profit goal per lot is reached
- Source: A Complete Guide to Day Trading, Is It Really Possible to Make a Living; Tenets of Day Trading. Family: `sizing-execution`. Signal source: none / account rule.
- Timeframe: weekly. Instruments: NIFTY, BANKNIFTY.
- Entry: the arm's normal entries until the week's closed P&L per lot reaches the goal; then no new entries until Monday
- Stop: arm's own
- Exit: arm's own
- Filters: goal per lot fixed in advance, low (book: start near $100 per contract, later $250-300); test goals of 0.5x, 1x and 2x the arm's median winning trade
- Trade: as the arm; applied to the Donchian, Breadth Donchian and Liquidity paper records
- Assumptions (our reading where the book is vague): Conversion of the book's dollar goals to rupees per lot via the arm's own median win; risk-lowering, so paper-eligible only after it passes gates.
- Data: have
- Our nearest test: not tested by us; nearest R19c day profit lock raised green days (42% -> 49%) but failed net / drawdown
### `book-heitkoetter-15` Only one morning and one afternoon trade a day
- Source: A Complete Guide to Day Trading, Tenets of Day Trading. Family: `sizing-execution`. Signal source: none / account rule.
- Timeframe: intraday. Instruments: NIFTY, BANKNIFTY.
- Entry: the arm's first signal before 12:30 and its first signal from 12:30 onward; all others skipped
- Stop: arm's own
- Exit: arm's own
- Filters: none
- Trade: as the arm
- Assumptions (our reading where the book is vague): Morning/afternoon split at 12:30 is ours.
- Data: have
- Our nearest test: R80 V9 2-a-day cap led in design and lost in the final; R14 1-a-side cap only limits ORB damage
### `book-heitkoetter-16` Skip holiday-adjacent and pre-major-release days
- Source: A Complete Guide to Day Trading, Is It Really Possible to Make a Living. Family: `calendar-events`. Signal source: none / calendar.
- Timeframe: daily filter. Instruments: NIFTY, BANKNIFTY.
- Entry: no new entries on any arm on: the session before and after an NSE trading holiday; the session before an RBI policy decision, the Union Budget, US CPI or US payrolls
- Stop: n/a
- Exit: n/a
- Filters: as entry
- Trade: risk-lowering skip on existing arms
- Assumptions (our reading where the book is vague): US Thanksgiving / Christmas and Fed / jobs-report days mapped to Indian holidays and the releases that move NIFTY; list fixed before any result.
- Data: **needs data we lack** - lack: a dated event calendar for RBI / Budget / US releases 2021-2026 in the lake (holidays are in the NSE calendar)
- Our nearest test: not tested by us; R15 pre-open day filters 0 of 5; R102 US-event sign rules never passed

## A Practical Guide to Swing Trading - Larry Swing ([write-up](swing-practical-guide.md))
### `book-lswing-01` FORCESWING pullback scan + Master Plan entry and exits on the index
- Source: A Practical Guide to Swing Trading, Ch. 5.1, 6, 8.8, 8.9, 10.1.1. Family: `patterns-indicators`. Signal source: underlying price/volume (index spot daily OHLC; Force Index volume from the continuous near-month index future).
- Timeframe: daily bars for the setup; 1-minute index bars for the intraday trigger and stops. Instruments: NIFTY, BANKNIFTY.
- Entry: Setup after the close on day D. LONG: close > SMA10(close) and close > SMA20(close); high(D) < high(D-1) < high(D-2); SMA3(ForceIndex) <= 0 and SMA13(ForceIndex) >= 0 where ForceIndex = futures volume x (close - previous close); ADX(20) > 30. SHORT: close < SMA10 and < SMA20; low(D) > low(D-1) > low(D-2); SMA3(FI) >= 0 and SMA13(FI) <= 0 (Ch. 8.8 mirror); ADX(20) > 30. Trigger on day D+1 (re-armed daily for up to 5 sessions while not filled): no gap (|open - previous close| < 0.6 x ATR14) -> from 09:20 a buy-stop at previous high + 0.07 x ATR14 (short: sell-stop at previous low - 0.07 x ATR14). Gap in the trade's direction -> wait until 09:45, trigger = that day's high so far + 0.07 x ATR14; gap against -> wait until 09:20, same re-based trigger.
- Stop: Initial: the HIGHER (long) of entry - 1.3 x ATR14 and the low of the reference day (previous day, or today's pre-entry low on a gap day) - 0.07 x ATR14; mirror for shorts. Every morning (after the same gap waits) raise it to previous day's low - 0.07 x ATR14 if that is higher; never lower it.
- Exit: Half the position at entry + 2.3 x ATR14 (limit); the rest only on the trailing stop. No time stop in the book; add a 20-session max hold as a backstop (assumption).
- Filters: none beyond the scan; only one open position per index per side
- Trade: Main: 1 lot of the index future (allows the half exit with 2 lots). Option version: buy 2 lots of the 1-ITM nearest-MONTHLY CE (long) / PE (short) when the index trigger fires; stops and target on the index as above; roll to the next monthly if fewer than 5 sessions to expiry at entry.
- Assumptions (our reading where the book is vague): The book's $ amounts are for $12-45 stocks with ATR about 3%: $0.06 -> 0.07 ATR, $0.50 gap -> 0.6 ATR, 4% -> 1.3 ATR, 7% -> 2.3 ATR (the literal % would almost never be hit on NIFTY, daily sd 1.316%). Force Index MA type not stated: simple. The short scan in Ch. 10 repeats the long Force condition; Ch. 8.8's mirror is used. ADX period 20 as the scan says (Wilder smoothing). 2 lots so 'half' exists.
- Data: have
- Our nearest test: SWING_DEEP: the 3-down-closes pullback above the 200DMA lost on NIFTY and BANKNIFTY futures 2016-2026 (NIFTY fixed 10d: 109 trades, -Rs 283,401); SWING.md: no daily rule right in both years for BANKNIFTY option holds; R102: daily momentum reversed in the holdout. Force Index and ADX>30: not tested by us.
### `book-lswing-02` FORCESWING + Master Plan on MCX futures daily bars
- Source: A Practical Guide to Swing Trading, Ch. 5.1, 6, 8.8, 8.9, 10.1.1. Family: `mcx-crypto`. Signal source: underlying price/volume (MCX continuous near-month future, daily, exchange volume).
- Timeframe: daily bars; MCX minute bars for the trigger where available (from Aug 2025), else a daily-bar fill model. Instruments: GOLD, SILVER, CRUDE, NATURALGAS.
- Entry: Same scan and trigger as forceswing-master-plan-index, on the MCX future; 'open' = the MCX 09:00 open; gap waits 30 / 5 minutes as in the book.
- Stop: Same as forceswing-master-plan-index (higher of -1.3 ATR14 or reference low - 0.07 ATR14; daily trail under the prior low).
- Exit: Half at +2.3 ATR14, rest on the trail; 20-session backstop; exit before the contract's last 3 sessions (roll gap not modelled - assumption).
- Filters: none
- Trade: 2 mini futures lots (GOLDM, SILVERM, CRUDEOILM, NATGASMINI); option version: 1-ITM monthly CE/PE on the same trigger, stops on the future.
- Assumptions (our reading where the book is vague): ATR scaling as in the index rule; rolls make continuous-future volume jump near expiry, so Force Index uses the volume of the contract held.
- Data: have (daily futures since 2011/2012; MCX options only ATM-5..+5 from mid-2025)
- Our nearest test: R83: crude daily Donchian-20 + Supertrend final +Rs 21.8k on 27 trades but not proven (twin p 0.23); R82: daily Donchian on gold/silver proxies lost in design; R96: NR7 breakout on crude is the only MCX daily lead (not passed). Force Index / pullback scan not tested by us.
### `book-lswing-03` FORCESWING + Master Plan on the R58 stocks, bought monthly options
- Source: A Practical Guide to Swing Trading, Ch. 5.1, 6, 10.1.1. Family: `outside-strategies`. Signal source: underlying price/volume (the stock's daily OHLCV).
- Timeframe: daily bars; stock 1-minute bars for the trigger. Instruments: RELIANCE, SBIN, MARUTI, ICICIBANK, INFY, LT, KOTAKBANK, M&M, TRENT, TATASTEEL.
- Entry: Scan exactly as the book on each stock (20-day avg volume filter dropped: all are liquid): close > SMA10 and SMA20, two lower highs, SMA3(FI) <= 0, SMA13(FI) >= 0, ADX(20) > 30 (short mirror). Trigger: no gap (|open - prev close| < 1.8%) -> buy-stop at prev high + 0.2% from 09:20; gap with the trade -> wait to 09:45, gap against -> 09:20, trigger = today's high so far + 0.2%. Order valid 5 sessions.
- Stop: Higher of entry - 4% or reference low - 0.2% (on the stock); daily trail to prev low - 0.2%.
- Exit: Half at stock +7%; rest on the trail; out by 15:15 three sessions before expiry (physical settlement) as in R58; R58's band exit applies to the option.
- Filters: none
- Trade: Long: 2 lots nearest-monthly ATM CE; short: ATM PE; stops/targets on the stock. Baseline: the same trades in cash (longs as delivery).
- Assumptions (our reading where the book is vague): $0.06 on a ~$27 stock -> 0.2%; $0.50 -> 1.8%; literal 4% / 7% kept because stock ATRs are close to the book's. Lot sizes from bhavcopy.
- Data: have (stock daily 2002+, 1-min 2017+, stock options ATM+-1 monthly; the lake has no +-2..5 strikes for these names)
- Our nearest test: R58: 38 stock-option rules, 0 pass; F5 daily momentum / Donchian final -Rs 5.5 lakh on 970 trades; round trip about Rs 280 a lot (2% of premium). SWING_DEEP: stock pullbacks beat random stocks 9/11 years but not buy-and-hold.
### `book-lswing-04` Plain 3-day pullback in a 10/20 SMA uptrend (no Force, no ADX) with Master Plan orders
- Source: A Practical Guide to Swing Trading, Ch. 5.1 (first two indicators only), Ch. 6. Family: `patterns-indicators`. Signal source: underlying price (index spot daily).
- Timeframe: daily bars; 1-minute bars for the trigger. Instruments: NIFTY, BANKNIFTY.
- Entry: LONG: close > SMA10 and close > SMA20 and SMA10 > SMA20 and high(D) < high(D-1) < high(D-2); SHORT mirror (close below both, SMA10 < SMA20, two higher lows). Trigger and 5-session validity as forceswing-master-plan-index.
- Stop: As forceswing-master-plan-index.
- Exit: As forceswing-master-plan-index.
- Filters: none
- Trade: 1-ITM nearest-monthly CE / PE, 2 lots, or 2 index futures lots; stops/targets on the index.
- Assumptions (our reading where the book is vague): Isolates the book's simplest trend+pullback definition so the Force/ADX filters can be judged as add-ons (pre-register as a pair).
- Data: have
- Our nearest test: SWING_DEEP: 3-down-closes pullback above 200DMA lost on both index futures; SWING.md RSI(2) pullback in trend inconsistent (5-16 trades a year).
### `book-lswing-05` Master Plan exits (half at target, prior-low trail) on our existing daily swing signals
- Source: A Practical Guide to Swing Trading, Ch. 6.5-6.8. Family: `trade-management`. Signal source: underlying price (index spot daily).
- Timeframe: daily bars. Instruments: NIFTY, BANKNIFTY.
- Entry: SWING_DEEP's existing signals unchanged (Donchian 20, pullback >200DMA & 3 down closes, NR7/inside-day), entered at the next open as there.
- Stop: Higher of entry - 1.3 x ATR14 or signal-day low - 0.07 x ATR14; raised each day to previous low - 0.07 x ATR14.
- Exit: Half at +2.3 x ATR14, rest on the trail; 20-session backstop.
- Filters: none
- Trade: 2 index futures lots; compare with SWING_DEEP's fixed 10d and 2ATR+3ATR chandelier exits and with coin-flip entries under the same exits.
- Assumptions (our reading where the book is vague): ATR scaling of the book's 4% / 7% / $0.06 as in forceswing-master-plan-index.
- Data: have
- Our nearest test: SWING_EXITS: the app's stop+target+ladder made stock swing portfolios worse in all 24 and futures stayed inside the coin band; MATRIX PINE: a TP1 half-exit + breakeven doubled the loss intraday. The prior-day-low trail itself is not tested by us.
### `book-lswing-06` Gap delay on a daily breakout trigger (wait 30 min with the gap, 5 min against, re-base on today's high)
- Source: A Practical Guide to Swing Trading, Ch. 6.3-6.4. Family: `overnight`. Signal source: underlying price (index spot daily setup, 1-minute trigger).
- Timeframe: daily setup, 1-minute execution. Instruments: NIFTY, BANKNIFTY.
- Entry: Take the setups of forceswing-master-plan-index (or pullback-2lh-sma-plain-index). Compare two pre-registered entry modes on the SAME setups: (A) the book's gap rule (|gap| >= 0.6 x ATR14 -> wait to 09:45 with / 09:20 against, trigger = today's high so far + 0.07 ATR); (B) no gap rule (buy-stop at previous high + 0.07 ATR from 09:16 every day).
- Stop: As forceswing-master-plan-index.
- Exit: As forceswing-master-plan-index.
- Filters: only days with |gap| >= 0.6 x ATR14 differ between A and B; report them separately
- Trade: 1-ITM nearest-monthly CE / PE; stops/targets on the index.
- Assumptions (our reading where the book is vague): The book's 'a few minutes after the open' read as 09:20; its 30-minute wait as 09:45.
- Data: have
- Our nearest test: R102: 0.5-1% NIFTY gaps fill by the close 42% (n 484), filled gaps fill a median 52 minutes after the open; R58 gap-go rules lost in the final test. A gap delay on a daily setup: not tested by us.

## Short Swing Trading - David Graeme Smith ([write-up](smith-short-swing.md))
### `book-smith-01` SST six rules on NIFTY/BANKNIFTY daily bars, buy the monthly option
- Source: Short Swing Trading, Ch. 7-9. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily bars (index spot), entry and stops checked on 1-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Set-up on day t close: (a) close above SMA(50) of daily closes for longs (below for shorts); (b) Wilder DMI with period 8: +DI crosses above -DI on day t, or the two touch (|+DI - -DI| <= 1.0 after having been apart by > 3.0 within the last 5 days) and then widen in the long direction (mirror for shorts). Reference day = the most recent day at or before t that is not an inside day (inside day: high <= previous high and low >= previous low). From day t+1, enter long when the index trades above reference high (short: below reference low). Set-up expires after 3 sessions without a trigger (book: late entries usually skipped).
- Stop: Initial: reference day low minus a buffer (long) / high plus buffer (short); buffer = 0.5% of price as written, plus a pre-registered index variant of 0.25 x ATR(14).
- Exit: Trailing: after each daily close, if that day made a new high since entry and is not an inside day, raise the stop to its low minus buffer (short mirror). If a day's favourable open-to-close or high-low move is >= 5% (book) or >= 2.5 x ATR(14) (index variant), raise the stop to that day's midpoint minus buffer. No target. Stops checked intraday on the index; exit the option at the next minute's open after the index trades through the stop. Hard time cap: exit 5 sessions before the monthly expiry.
- Filters: Skip if the trigger is hit by the open gapping through it (book Ch. 13.1); skip if SMA(200) lies between entry and entry + (entry - stop) (book's 200-day barrier); if the DI cross and the SMA(50) cross happen within 2 sessions of each other, both must have happened before entry.
- Trade: buy the 1-ITM CE of the nearest monthly (next monthly if fewer than 10 sessions left) on a long trigger, 1-ITM PE on a short; stops on the index as the book states; also run a futures mirror to separate signal from theta
- Assumptions (our reading where the book is vague): Book is written for UK small-cap shares with a 0.5% buffer and says major indices need a wider, judged buffer; the ATR variants and the touch tolerance are our literal readings. 'Big move day' 5% is almost never hit on NIFTY, hence the ATR-scaled variant. Set-up expiry of 3 sessions makes 'do not enter late' precise.
- Data: have
- Our nearest test: SWING_DEEP: NR7 / inside-day breakout on index futures sat inside the coin-flip band, walk-forward lost Rs 45-58k a year; OBUY_LV05_MULTIDAY: multi-day Donchian option holds about -Rs 18,000 a year, p 0.68 vs random; OBUY_GB TI-09 intraday ADX/DMI 0/5 years. The exact daily rule is not tested by us.
### `book-smith-02` SST six rules on 1-hour index bars (intraday-to-few-days mapping)
- Source: Short Swing Trading, Ch. 7-9 (our timeframe mapping). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 60-minute bars of index spot (09:15-15:15 buckets), stops on 1-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Same as sst-six-rules-index-daily with every 'day' replaced by a completed 60-minute bar: SMA(50) of hourly closes, DMI(8) on hourly bars, reference bar = last non-inside hourly bar, enter on a trade beyond its high/low within the next 3 hourly bars.
- Stop: Reference bar's low minus 0.25 x ATR(14, hourly) (long).
- Exit: Trail to each new non-inside hourly bar's low minus the same buffer once it makes a new high since entry; half-bar lock on a bar >= 2.5 x hourly ATR; no target; either square off at 15:15 (intraday variant) or hold overnight up to 5 sessions (swing variant).
- Filters: No entries in the first hourly bar if it gapped through the trigger; no entries after 14:15 for the intraday variant; skip expiry day for weekly contracts.
- Trade: intraday variant: 1-ITM weekly CE/PE; swing variant: 1-ITM monthly CE/PE; stops on the index
- Assumptions (our reading where the book is vague): The book is end-of-day only; the hourly version is our mapping and should be labelled as such. Buffer and 'big bar' scaled to ATR because the book's 0.5% / 5% are share-sized.
- Data: have
- Our nearest test: OBUY_GB TI-09 (ADX/DMI + set-up candle break, 5/15-minute): walk-forward Rs -345,311, 0/5 years, 48 variants; R73 ADX filters failed; R89 previous-day high/low break 0 of 180.
### `book-smith-03` Significant-day trailing stop as an exit module on existing multi-day holds
- Source: Short Swing Trading, Ch. 8.3, 9.1. Family: `trade-management`. Signal source: underlying price.
- Timeframe: daily bars for the trail, 1-minute bars for the stop check. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE, NATURALGAS.
- Entry: Unchanged: the entries of SWING_DEEP's swing rules, the LV-05 multi-day Donchian, and R96's crude NR7 lead (same signals and dates as their studies).
- Stop: Initial stop as each study already defines it.
- Exit: Replace the study's trail/time exit with: after each close, if the day made a new extreme in the trade's favour and is not an inside day, move the stop to that day's far end (low for longs) minus buffer; buffer 0.5% of price (book) and 0.25 x ATR(14) (variant); midpoint lock on a day >= 2.5 x ATR(14) in the trade's favour; inside days never move the stop; keep the study's max-hold cap.
- Filters: none beyond each host study's.
- Trade: the host study's instrument (1-ITM monthly option or futures mirror; MCX 1-ITM option on the near future); stops on the underlying
- Assumptions (our reading where the book is vague): The book uses it on its own entry only; testing it on our existing entries isolates the exit. Compare with coin-flip entries carrying the same exit (SWING_EXITS method).
- Data: have
- Our nearest test: SWING_EXITS: the app's stop/target/ladder cut drawdowns but no row beat coin-flip entries with the same exits; LV-05 trailed the opposite N/2-day channel and lost. This trail itself is not tested by us.
### `book-smith-04` SST six rules on MCX near-month futures, daily
- Source: Short Swing Trading, Ch. 7-9. Family: `mcx-crypto`. Signal source: underlying price.
- Timeframe: daily bars of the near-month continuous future (rolled 5 sessions before expiry). Instruments: GOLD, SILVER, CRUDE, NATURALGAS.
- Entry: As sst-six-rules-index-daily on the future's daily bars (SMA 50 side, DMI 8 cross or touch, break of last non-inside day).
- Stop: Reference day's far end minus 0.5% of price (book) and minus 0.25 x ATR(14) (variant).
- Exit: Significant-day trail; midpoint lock on a >= 5% day in the trade's favour (book's number is usable on SILVER, NATURALGAS); no target; exit before the option's or future's last 3 sessions.
- Filters: skip gap-through triggers at the session open; skip entries in the 2 sessions before EIA (CRUDE, Wednesdays), storage (NATURALGAS, Thursdays) and US CPI/NFP (GOLD, SILVER) as the book's scheduled-news blackout mapped to commodities.
- Trade: futures mirror (1 mini lot) and 1-ITM option on the near future; stops on the future
- Assumptions (our reading where the book is vague): Book trades UK shares; mapping its company-results blackout to commodity data releases and its 2 weeks to 2 sessions is our reading.
- Data: have (MCX futures daily since our M2 study; MCX option prints only Aug 2025 on, ATM +-3 strikes)
- Our nearest test: MCX_TREND: trend edge real but small, zero variants pass BH, buying options the worst way; R96: 0 of 46 MCX option-buying ideas pass; R93 breadth Donchian on MCX 0 of 5.
### `book-smith-05` SST six rules on F&O stocks chosen by a walk-forward 'step-ness' screen
- Source: Short Swing Trading, Ch. 11, 7-9. Family: `daily-filters`. Signal source: underlying price.
- Timeframe: daily bars of F&O stocks. Instruments: .
- Entry: Each quarter, rank F&O stocks on the PREVIOUS 6 months only by: (1) step score = share of up-trend days (close > SMA50) whose low >= previous low, plus the mirror for down-trend days; (2) median shadow/range ratio (lower is better); (3) 6-month daily return correlation with NIFTY (book wants < 0.20; use the bottom quartile if too few qualify); (4) median daily traded value above a floor. Keep the top 20. Trade the SST six rules on them next quarter.
- Stop: Last significant day's far end minus 0.5% of price (book's number, written for shares).
- Exit: Significant-day trail; midpoint lock on a >= 5% favourable day; no target.
- Filters: No new entry in the 10 sessions (two weeks) before the stock's scheduled results or AGM; skip gap-through triggers; skip if SMA(200) lies between entry and first 1R of profit.
- Trade: stock futures (1 lot) and the 1-ITM monthly stock option; stops on the stock; out 3 sessions before expiry (physical settlement)
- Assumptions (our reading where the book is vague): The book's screen is by eye with a weed-out loop on past back-tests (in-sample); we make it a frozen, walk-forward ranking. Thresholds above are our literal reading.
- Data: **needs data we lack** - lack: corporate results / AGM calendar for F&O stocks (have daily bars and the R58 stock-option lake for 10 names)
- Our nearest test: R58: 0 of 38 stock-option rules pass, round trip about Rs 280 a lot; SWING_DEEP: stock swing set-ups did not beat holding the same stocks.
### `book-smith-06` SST six rules on BTC/ETH daily, with the trail as the profit lock
- Source: Short Swing Trading, Ch. 7-9. Family: `mcx-crypto`. Signal source: underlying price.
- Timeframe: daily bars (00:00 UTC close) of BTC and ETH spot/perp index. Instruments: CRYPTO.
- Entry: As sst-six-rules-index-daily on BTC/ETH daily bars (SMA50 side, DMI 8 cross or touch, break of last non-inside day).
- Stop: Mandatory: reference day's far end minus 0.5% of price, and a variant at 0.25 x ATR(14).
- Exit: Mandatory profit lock: the significant-day step trail (each new non-inside favourable day moves the stop to its far end minus buffer) plus the midpoint lock on a >= 5% favourable day; no target; max hold 20 days.
- Filters: No entries in the 24 hours before FOMC or US CPI (book's scheduled-news blackout mapped); gap rule not applicable (24/7 market).
- Trade: spot or 1x perp, and Delta Exchange India ATM option on the trigger; stops on the underlying
- Assumptions (our reading where the book is vague): Crypto never appears in the book; mapping is ours. Stop and step lock are both present as CLAUDE.md requires; compare against lock/stop variants, never 'no stop'.
- Data: have (BTC/ETH history used in RC1; Delta India BTC option prints used in R46)
- Our nearest test: RC1: 0 of 8 active ideas beat buy-and-hold after India's 30% tax; R46: BTC EMA 8/21 + ADX(14) > 15 option calls, 0 of 14 variants pass.
### `book-smith-07` Skip swing entries whose trigger is hit by a gap
- Source: Short Swing Trading, Ch. 13.1. Family: `daily-filters`. Signal source: underlying price.
- Timeframe: daily trigger levels, first minute of the session. Instruments: NIFTY, BANKNIFTY.
- Entry: Applied to any daily-level stop-entry rule (SST, NR7/inside-day breakout, Donchian next-day): if the 09:15 open is already beyond the trigger, do not trade; otherwise trade as usual.
- Stop: host rule's
- Exit: host rule's
- Filters: variant: skip only if the gap beyond the trigger is >= 0.25% of price.
- Trade: host rule's option (1-ITM monthly)
- Assumptions (our reading where the book is vague): Book's 'gaps through the trigger' read as the open being beyond the trigger level.
- Data: have
- Our nearest test: R102: a 0.5-1% NIFTY gap fills by the close less than half the time (NIFTY 42%); SWING_DEEP and LV-05 found swing profit is made overnight, which this filter discards. Not tested by us as a filter.

## The Advanced Trading Methods in the Financial Markets - The Advanced Trading Methods book ([write-up](advanced-trading-methods.md))
### `book-atm-01` TWAP-style sliced exit of multi-lot option positions vs one market order
- Source: The Advanced Trading Methods in the Financial Markets, Sec. 3.2, item 2 (TWAP algorithms); Sec. 2.1.2 (minimise market impact). Family: `sizing-execution`. Signal source: none (execution method applied to existing arm signals on the underlying).
- Timeframe: 1-minute bars. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: unchanged: take the existing arm's entries (Liquidity 15+5, R72 Breadth Donchian, R51 Donchian) as they are
- Stop: unchanged from each arm; a stop exit is never sliced (sent in full at once)
- Exit: on a target, trail or time exit of N >= 2 lots, sell N/3 lots (rounded up) at each of the next 3 one-minute bar opens instead of all N at the signal bar's next open
- Filters: only for option contracts whose median 1-minute volume is below 20x the position size (thin strikes, MCX options)
- Trade: same option as the arm; compare net P&L and fill slippage of sliced vs single exits on the same trades
- Assumptions (our reading where the book is vague): The paper only names TWAP. The 3 slices, 1-minute spacing, the thin-contract filter and never slicing stops are ours (stops must not be delayed per CLAUDE.md priority rules). Fills at bar open plus our usual spread model.
- Data: **needs data we lack** - lack: realistic fill depth for multi-lot exits needs 5-level book recordings; 1-minute bars alone overstate fills
- Our nearest test: not tested by us; nearest is HUNT_H14.md (entry execution: only a marketable limit at +0.5% passed, +Rs 288/day vs +250 required); HUNT_H10.md suggests slicing exits in thin contracts
### `book-atm-02` NIFTY/BANKNIFTY futures-spot basis snap-back (literal statistical arbitrage)
- Source: The Advanced Trading Methods in the Financial Markets, Sec. 3.1.3, Statistical arbitrage. Family: `flow-oi`. Signal source: underlying price (index futures vs index spot).
- Timeframe: 1-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: at the close of a 1-minute bar where (futures - spot) minus its 60-minute rolling mean exceeds 2.5 rolling standard deviations: if the futures is rich, expect the spot to catch up (buy ATM CE); if cheap, buy ATM PE, at the next bar open
- Stop: index spot moves 0.15% against the trade
- Exit: basis back within 0.5 standard deviations of its mean, or 10 minutes, whichever first; square off by 15:05 on expiry day and 15:20 otherwise
- Filters: 09:20-15:00 only; skip the first 5 minutes after any gap of 0.5% or more
- Trade: buy ATM CE on a rich-futures signal, ATM PE on a cheap-futures signal; stops on the underlying
- Assumptions (our reading where the book is vague): The paper gives no pair, threshold or holding time. Thresholds (2.5 sd, 60-minute window, 10-minute hold, 0.15% stop) are ours. A true arbitrage would trade futures vs spot basket at sub-second speed; this is the slow, single-leg version a retail phone can run.
- Data: have
- Our nearest test: HUNT_H39.md: heavyweight futures, basis and synthetic forward move with the index in the same minute; from 2 minutes ahead every |t| is below 2.5, and the index's own future does not lead by a tradeable amount. FINDINGS.md DIRECTION MODEL: basis rising right 50.4-51.6%. Expected to fail.

## 10 Most Profitable Trading Strategies - Nikhil Porwal ([write-up](porwal-10-strategies.md))
### `book-porwal-01` Inside bar, entry on the close beyond the mother bar, 1x/1.5x/2x targets
- Source: 10 Most Profitable Trading Strategies, Ch. 3.1. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (also 60-minute and daily as variants). Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: Bar t-1 (inside bar) has high <= high and low >= low of bar t-2 (mother). The first later bar (within 4 bars) that CLOSES above the mother high -> long; closes below the mother low -> short. Enter at the next bar's open.
- Stop: Long: mother low; short: mother high (on the underlying).
- Exit: Three equal parts: targets at entry +/- 1.0x, 1.5x and 2.0x the mother range; after target 1 move the stop to entry (book: trailing stop, method not given). Intraday versions square off at 15:15 (15:05 on expiry).
- Filters: Continuation variant: trade only in the direction of the 60-minute (or daily for daily bars) trend, trend = close above / below EMA 20 of that timeframe. Volume confirmation: break-bar futures volume above its 20-bar average. Stop for the day after two stopped-out trades.
- Trade: buy 1-ITM CE on a long signal, 1-ITM PE on a short; stops and targets on the underlying as the book states; MCX: the future itself or its 1-ITM option
- Assumptions (our reading where the book is vague): The book writes only the short side; long is the mirror. Higher-timeframe trend definition, the 4-bar validity window, the breakeven step and the volume multiple are ours. Partial exits at 1x/1.5x/2x as written.
- Data: have
- Our nearest test: R71 P1: 15-minute inside-bar breaks, 1-ITM, final -Rs 1,23,667 on 1,075 trades; R22 1x-extension target lost; SWING_DEEP NR7/inside-day walk-forward lost
### `book-porwal-02` Doji at an oversold / overbought extreme, traded on the break of its high / low
- Source: 10 Most Profitable Trading Strategies, Ch. 3.2. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily bars (the book's main timeframe); 15-minute as a variant. Instruments: NIFTY, BANKNIFTY.
- Entry: Doji: |close - open| <= 0.1 x (high - low). Long setup: doji whose low is the lowest low of the last 10 bars and RSI(14) < 30 (or stochastic 14,3,3 < 20) on the doji. Buy when price trades above the doji high within the next 3 bars. Short is the mirror (highest high of 10 bars, RSI > 70).
- Stop: Below the doji low (long) / above the doji high (short).
- Exit: Trail with EMA(20): exit on a close back through it; or a 2R target (book's 1:2 minimum); max hold 10 sessions for the daily version.
- Filters: One extra confirmation is required by the book; we use a gap or a close beyond the doji as that confirmation in a variant.
- Trade: daily: buy the next-monthly ATM CE / PE with stops on the index; intraday: 1-ITM nearest expiry
- Assumptions (our reading where the book is vague): The oscillator is not named (default settings); RSI(14) 30/70 and stochastic 14,3,3 80/20 tested as two readings. Doji body threshold, 10-bar extreme, 3-bar window, EMA 20 trail are ours.
- Data: have
- Our nearest test: R87 / R87B: no candle shape predicts direction; daily shapes 0 of 25; OBUY_GB MR-03 RSI 30/70 reversal lost walk-forward
### `book-porwal-03` Engulfing reversal with trend, volume and stochastic filters
- Source: 10 Most Profitable Trading Strategies, Ch. 3.3. Family: `patterns-indicators`. Signal source: underlying price and volume (futures volume).
- Timeframe: daily bars. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: Bullish: close below SMA 20 and SMA 20 falling (downtrend); day 1 close < open; day 2 close > open with body covering day 1's body (open2 <= close1 and close2 >= open1). Buy when price trades above day 2's high within the next 3 sessions. Bearish mirror, plus open2 > close1, close2 < open1 and day-2 volume > day-1 volume.
- Stop: Below day 2's low (bullish) / above day 2's high (bearish).
- Exit: Target 2R; else trail with EMA 20 close; max hold 10 sessions.
- Filters: Optional confidence filters as in the book: day-1 body longer than each of the previous 5 bodies; futures volume on day 1 or day 2 above 1.5x its 20-day average; stochastic 14,3,3 below 20 (bullish) / above 80 (bearish).
- Trade: buy ATM CE / PE of the next monthly expiry (or 1-ITM weekly), stop and target on the index; MCX: the future
- Assumptions (our reading where the book is vague): Downtrend definition, the 3-session trigger window, the volume multiple and the stochastic settings are ours; the book shows the trend 'visually'.
- Data: have
- Our nearest test: R87 engulfing among 2-candle patterns: no direction edge; R85 MCX engulfing no reliable edge; R87B daily 0 of 25
### `book-porwal-04` Falling trendline break confirmed by a hold above the previous lower high
- Source: 10 Most Profitable Trading Strategies, Ch. 4.1. Family: `patterns-indicators`. Signal source: underlying price and volume (futures volume).
- Timeframe: daily bars; 60-minute as a variant. Instruments: NIFTY, BANKNIFTY.
- Entry: Find 3-bar fractal swing highs. A falling trendline joins the last two lower swing highs (H1 > H2) and its slope, in ATR14 per bar, is between 0.5 and 1.5 (stands in for 'about 45 degrees, never above 60'). After the close crosses above the line, wait for a close above H2 (the previous lower high); buy at the next open.
- Stop: Below H2 minus 0.25 x ATR14 (book: 'below the level').
- Exit: Targets at the next older swing highs above entry (T1/T2/T3, a third each), at least 2R for T1 or the trade is skipped; trail the rest with the last swing low.
- Filters: Signal bar range larger than each of the previous 3 bars; futures volume on the signal bar above its 20-bar average; a prior downtrend (the line must span at least 10 bars). Breakdown of a rising line is the mirror (close below the last higher low).
- Trade: buy ATM monthly CE (PE for breakdowns), stops and targets on the index
- Assumptions (our reading where the book is vague): Chart angle depends on scaling, so it is replaced by a slope band in ATR per bar; the band edges, fractal width, 10-bar span and volume multiple are ours.
- Data: have
- Our nearest test: not tested by us as a trendline; nearest R71 P8 swing structure (final -Rs 40,109 on 374), R90 BOS 0 of 99, R20c and R73 confirmation-wait costs
### `book-porwal-05` Darvas box break with volume confirmation, stop outside the box
- Source: 10 Most Profitable Trading Strategies, Ch. 4.2. Family: `patterns-indicators`. Signal source: underlying price and volume (futures volume).
- Timeframe: 5-minute bars intraday; daily as a variant. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE, NATURALGAS.
- Entry: Box = the high / low of the last N bars (N = 20 and 40) when (high - low) <= 1.5 x ATR14 x sqrt(N)/sqrt(20) (a quiet box). Long when a bar closes above the box high with a body >= 60% of its range and futures volume >= 1.5x its 20-bar average; buy at the next bar's open. Short mirror.
- Stop: Below the box low (book's preferred stop, outside the box); variant: below the breakout bar's low.
- Exit: 2R target, or trail by the 10-bar low; intraday square-off 15:15.
- Filters: 'Longer consolidation, bigger move': variant N = 40 vs N = 20. No entry when price is already more than 2 ATR beyond the box (no chasing).
- Trade: buy 1-ITM CE / PE nearest expiry, stops on the index; compare with the R51 Donchian arm on the same days
- Assumptions (our reading where the book is vague): The box definition, quiet-box width test, body and volume thresholds are ours; the book gives no numbers.
- Data: have
- Our nearest test: nearest R51 Donchian 20 5-minute: final +Rs 55,439 on 1,787 trades (our real arm); R71 P3 squeeze release lost; R73 confirmation wait lost; volume filter not tested by us
### `book-porwal-06` Turbocharged opening: first 15-minute range break with prior-day trend and EMA(14) bias
- Source: 10 Most Profitable Trading Strategies, Ch. 4.3. Family: `orb`. Signal source: underlying price.
- Timeframe: first 15-minute candle (09:15-09:29) as the range; 5-minute bars for the break. Instruments: NIFTY, BANKNIFTY.
- Entry: Prior-day trend = up if yesterday's close > yesterday's open and > the day before's close (down mirror; else no trade). Long: a 5-minute close above the 15-minute range high, prior-day trend up, and the 5-minute close above EMA(14). Short mirror. One trade a side a day, entries 09:30-11:00.
- Stop: The other side of the 15-minute range (book: 'pre-decided'; variant: the range midpoint).
- Exit: A third each at entry +/- 1.0x, 1.5x, 2.0x the range; the rest trailed by a 5-minute close through EMA(14); square-off 15:15 (15:05 on expiry).
- Filters: Skip if the range is narrower or wider than its 20-day 20th-80th percentile (variant only). Gap noted but not a filter (book: gap analysis without a rule).
- Trade: buy ATM CE on a long, ATM PE on a short; stops and targets on the index
- Assumptions (our reading where the book is vague): Prior-day trend definition, the stop level, the entry window and the range-width variant are ours.
- Data: have
- Our nearest test: R23 first-15-minute range break -Rs 71,624 on 1,171 trades; R3 ORB -Rs 2,23,040; R49 0 of 336; R92 0 of 257; R22 1x-extension target lost
### `book-porwal-07` 52-week high / low as a direction filter for intraday index trades
- Source: 10 Most Profitable Trading Strategies, Ch. 4.4. Family: `daily-filters`. Signal source: underlying price (daily closes).
- Timeframe: daily filter, intraday trades. Instruments: NIFTY, BANKNIFTY.
- Entry: Filter state at 09:15: LONG-ONLY if yesterday's close is within 2% of the 252-session high and yesterday closed above the prior 20-session high at least once in the last 10 sessions; SHORT-ONLY for the mirror at the 252-session low; else NEUTRAL. Apply to an existing arm's signals (R51 Donchian, R72 Breadth Donchian): drop signals against the state.
- Stop: the arm's own
- Exit: the arm's own
- Filters: Variant: NEUTRAL days trade both sides (filter only removes counter-trend trades).
- Trade: the arm's own 1-ITM option; this rule only removes trades (a risk-lowering change, shadow first on live per CLAUDE.md)
- Assumptions (our reading where the book is vague): The book uses it as a stock screener feeding any intraday setup; turning it into an index direction filter, the 2% band and the 20-session breakout check are ours.
- Data: have
- Our nearest test: SWING_DEEP: 52-week momentum on NIFTY futures as a swing rule, about +Rs 2,175 a year over 11 years, inside the coin-flip range; as an intraday filter not tested by us
### `book-porwal-08` ADX 20-50 + DI alignment + EMA(14) pullback with prior-day trend
- Source: 10 Most Profitable Trading Strategies, Ch. 5.1. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 5-minute bars (15-minute variant). Instruments: NIFTY, BANKNIFTY.
- Entry: Wilder ADX(14) with DI length 14: 20 < ADX < 50; +DI > -DI; close above EMA(14); prior-day trend up (as in porwal-turbo). Trigger: a bar whose low comes within 0.1% of EMA(14) and closes above it; buy at the next bar's open. Short mirror. Entries 09:30-14:30.
- Stop: The lower of the last 5-bar swing low and EMA(14) minus 0.25 x ATR14 (book: 'some space' below the EMA, or beyond the nearest swing).
- Exit: Exit on a 5-minute close below EMA(14); 2R minimum target as a variant; square-off 15:15 (15:05 on expiry).
- Filters: Variant from the examples: ADX > 30 instead of > 20.
- Trade: buy ATM CE on a long, ATM PE on a short; stop on the index
- Assumptions (our reading where the book is vague): 'Greater than 20 or below 50' read as a band; pullback distance, stop offset and the entry window are ours.
- Data: have
- Our nearest test: OBUY_GB TI-09 ADX/DMI lost Rs -345,311 walk-forward; TI-03 EMA cross lost; R29 EMA pullbacks no better than random; R73 ADX filter on Donchian failed
### `book-porwal-09` Quasimodo: higher high after a downtrend, buy the higher low
- Source: 10 Most Profitable Trading Strategies, Ch. 5.2. Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: 15-minute bars (30-minute variant); MCX also 60-minute. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: 3-bar fractal swings. Setup: swing highs H1 > H2 and swing lows L1 > L2 (downtrend), then a close above H2 (higher high, HH). Long when a later swing low L3 forms above L2 (higher low) and the next bar closes above L3's bar high. Bearish mirror. Variant B (book's second wording): buy the first close above HH.
- Stop: Below L2 (the head / QM zone) minus 1 tick.
- Exit: Target H1 (top of the first zig-zag) or 2R, whichever is nearer; 3R variant; trail by swing lows; square-off 15:15 for NSE, MCX by 23:15.
- Filters: Volume above its 20-bar average on the HH break (futures volume).
- Trade: buy 1-ITM CE / PE; MCX the future or 1-ITM option; stops on the underlying
- Assumptions (our reading where the book is vague): Fractal width, the trigger bar and the target choice are ours; the book's two descriptions disagree, so both readings are pre-registered.
- Data: have
- Our nearest test: R90 BOS / CHoCH 0 of 99 (target too far for intraday options); R62b 0 of 16; R71 P8 final -Rs 40,109 on 374
### `book-porwal-10` Bull trap: failed break of the day high reclaimed with a volume spike, go short
- Source: 10 Most Profitable Trading Strategies, Ch. 5.3. Family: `flow-oi`. Signal source: underlying price and volume (futures volume).
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: After 10:00, a 5-minute bar closes above the running day high (break) on futures volume below its 20-bar average (weak break). Within 6 bars, a red bar closes back below the broken day high with volume >= 2x its 20-bar average: short at the next open.
- Stop: Above the highest high since the break.
- Exit: Target the last 5-minute swing low before the break (previous higher low), else 2R; trail by 3-bar highs; square-off 15:15.
- Filters: Prior uptrend: close above EMA(20) for the 6 bars before the break.
- Trade: buy ATM PE; stop and target on the index
- Assumptions (our reading where the book is vague): Volume thresholds, the 6-bar window and the trend check are ours.
- Data: have
- Our nearest test: R86 stop-run fades lost in 6 of 6 tests; R87 stop run above the 15-minute day high was followed by more upside (n 1,666); R91 0 of 63
### `book-porwal-11` Bear trap: failed break of the day low / previous-day low, reclaimed on volume, buy above the reversal bar
- Source: 10 Most Profitable Trading Strategies, Ch. 5.3. Family: `flow-oi`. Signal source: underlying price and volume (futures volume).
- Timeframe: 1-minute and 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Level = the running day low (variant: the previous day's low). A bar trades below the level and, within 3 bars, a green bar closes back above it with futures volume >= 2x its 20-bar average. Buy when price trades above that green bar's high (book: above the high of the bullish reversal candle).
- Stop: Below the lowest low of the poke (the swing low).
- Exit: Book target: the previous day's high or the day high, partial at 1R and the rest trailed by 3-bar lows; variant 2R; square-off 15:15.
- Filters: Entries 09:30-14:30; skip when the level was poked 3+ times already.
- Trade: buy 1-ITM CE; variant: trade the index future (lower cost) with the same levels
- Assumptions (our reading where the book is vague): Level choice, volume multiple, the 3-bar reclaim window and the partial exit are ours.
- Data: have
- Our nearest test: R87: 1-minute stop run below NIFTY's day low followed by +0.20 ATR over 5 minutes, n 1,702 in the holdout, below option cost (weak positive base); fades in R86 lost
### `book-porwal-12` Stop trading for the day after two stop-losses
- Source: 10 Most Profitable Trading Strategies, Ch. 2 / 3.1 rules. Family: `sizing-execution`. Signal source: none (account / trade state).
- Timeframe: per session. Instruments: NIFTY, BANKNIFTY.
- Entry: No new entries for the rest of the day once two trades of the arm have exited at their stop.
- Stop: n/a
- Exit: n/a
- Filters: Variant: count any two losing trades, not only stop exits.
- Trade: applies to an existing arm (risk-lowering; shadow first on live)
- Assumptions (our reading where the book is vague): 'Two stop-losses hit' read per arm per day.
- Data: have
- Our nearest test: R75 L1-K2 (no new Donchian entries after 2 losses in a row) failed; R19c daily loss cap made things worse

## Advanced Techniques in Day Trading - Andrew Aziz ([write-up](aziz-advanced-day-trading.md))
### `book-aziz-01` Opening range breakout on VWAP's side, range narrower than ATR, VWAP stop, next-level target
- Source: Advanced Techniques in Day Trading, Ch. 6, Strategy 4: Opening Range Breakout. Family: `orb`. Signal source: underlying price/volume (index futures; VWAP from futures volume or R10's option-volume proxy).
- Timeframe: 5-, 15- or 30-minute opening range; 1-minute bars for the break; 5-minute bars for the stop and exit. Instruments: NIFTY, BANKNIFTY.
- Entry: Opening range = high/low of 09:15 to 09:15+R (R = 5, 15 or 30 min). Long on the first 1-minute close above the range high while that close is above VWAP; short on the first 1-minute close below the range low while below VWAP. One trade a side a day; no entries after 10:15 (the book's Open).
- Stop: Long: a 5-minute close below VWAP. Short: a 5-minute close above VWAP (the book is inconsistent between close and break; use closes).
- Exit: First target = the nearest of previous day close, yesterday's high/low, two-days-ago high/low, daily 50/200 SMA beyond entry: sell half there and move the stop to entry. Rest: exit a long on a new 5-minute low (lower than the prior 5-minute bar's low), a short on a new 5-minute high, or at 15:05 expiry / 15:15 otherwise.
- Filters: Range width < 0.5 x daily ATR14 (the book: significantly smaller than ATR). Skip if the distance to the first target is under 2 x the distance to VWAP at entry.
- Trade: buy ATM CE on a long signal, ATM PE on a short; stops and targets on the index as the book states
- Assumptions (our reading where the book is vague): 'Significantly smaller than ATR' fixed at 0.5x; 'break' read as a 1-minute close; NSE Open mapped to 09:15-10:15; ATR14 on daily bars; the half / break-even management from Ch. 5.
- Data: have (index spot, chains); VWAP needs index futures minutes (live contracts only) or the R10 proxy
- Our nearest test: R3 / R49 / R92: the ORB family lost on every index (0 of 16, 0 of 336, 0 of 257); R10 V1 (VWAP side) and T6 (narrow first-30-min range) did not rescue it
### `book-aziz-02` VWAP false breakout: sold off below VWAP, reclaimed, lost again in the Late-Morning -> short
- Source: Advanced Techniques in Day Trading, Ch. 6, VWAP False Breakout. Family: `patterns-indicators`. Signal source: underlying price/volume (index futures VWAP or R10 proxy).
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD, SILVER.
- Entry: Day state: at least one 5-minute close below VWAP after 09:15, later at least one 5-minute close above VWAP. Between 10:15 and 11:45 (MCX: the second and third hours of the day session), the first 5-minute close back below VWAP -> short at that close. Mirror for longs (above VWAP, lost, regained).
- Stop: a 5-minute close above VWAP
- Exit: Half at the low of the day so far (stop to entry); rest at the next level below (previous day close, yesterday's low, 5-minute 200 SMA) or a new 5-minute high; time exit 15:15 (MCX 23:00).
- Filters: One trade a day; skip if the distance to the low of the day is under 2 x the distance to VWAP.
- Trade: buy ATM PE on the short signal (ATM CE on the mirror); stops and targets on the underlying
- Assumptions (our reading where the book is vague): 'Sold off', 'reclaimed' and 'lost' read as 5-minute closes; US 10:30-12:00 mapped to NSE 10:15-11:45; the optional anticipation entry before the loss is not used.
- Data: have for MCX futures (real volume); index VWAP needs futures minutes or the R10 proxy
- Our nearest test: not tested by us as a sequence; R10 V1 side-of-VWAP had no filter value; F1 found no VWAP direction; R86: a midday VWAP stretch tends to continue
### `book-aziz-03` VWAP reversal: opening move fails to make a new 5-minute extreme -> trade back to VWAP
- Source: Advanced Techniques in Day Trading, Ch. 6, VWAP Reversals. Family: `patterns-indicators`. Signal source: underlying price/volume.
- Timeframe: 5-minute bars for the setup, 1-minute bars for the trigger. Instruments: NIFTY, BANKNIFTY.
- Entry: Before 10:45: the index is below VWAP and has made the day's low; a completed 5-minute bar fails to make a new low (its low >= the prior bar's low). Long on the first 1-minute close above the 1-minute 9 EMA after that bar. Mirror short above VWAP after a 5-minute bar fails to make a new high.
- Stop: below the low of the day (or the previous 5-minute bar's low if closer)
- Exit: Sell half at VWAP and move the stop to entry; rest at the 5-minute 9 EMA, then 20 EMA (whichever comes later above VWAP), or a new 5-minute low; time exit 11:45.
- Filters: Skip unless the distance from entry to VWAP is at least 2 x the distance from entry to the stop (the book's risk/reward rule).
- Trade: buy ATM CE on a long signal, ATM PE on a short; stops and targets on the index
- Assumptions (our reading where the book is vague): 'Fails to make a new low' = one 5-minute bar whose low is not below the previous bar's low; 1-minute 9 EMA close as the trigger (DLTR / AA examples); window ends at the NSE equivalent of the Late-Morning start.
- Data: have (VWAP via futures or proxy)
- Our nearest test: R71 P7 three-bar reversal at the day's high/low beat random timing (twin p 0.002) but lost after option costs; R86 V1 midday fade to VWAP lost Rs 64,786 on 71 trades
### `book-aziz-04` VWAP moving-average trend: below VWAP and riding the 5-minute 20 EMA after the Late-Morning
- Source: Advanced Techniques in Day Trading, Ch. 6, VWAP Moving Average Trend. Family: `patterns-indicators`. Signal source: underlying price/volume.
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD.
- Entry: From 11:00 to 13:30: the last 6 five-minute closes all below VWAP and the 20 EMA falling; a 5-minute bar's high touches the 9 or 20 EMA and the bar closes below both -> short at that close. Mirror long above VWAP.
- Stop: a 5-minute close above the 20 EMA
- Exit: Exit on a 5-minute close above the 20 EMA; take a quarter when the close is more than 1 x the 14-bar 5-minute ATR beyond the 20 EMA and move the stop to entry; time exit 15:00 (the book: trends end around 3 p.m.).
- Filters: None beyond the VWAP side; one trade a side a day.
- Trade: buy ATM PE on a short signal, ATM CE on a long; stops on the index
- Assumptions (our reading where the book is vague): 'Lost VWAP in the Late-Morning' fixed as 6 consecutive 5-minute closes on one side; 'touch' = the bar's high/low reaches the EMA; the 1-ATR distance for the partial is ours.
- Data: have (VWAP via futures or proxy)
- Our nearest test: R29 EMA pullback patterns 0 of 55 pass; R86 continuation of midday VWAP stretches is descriptive only
### `book-aziz-05` ABCD: new high of day, higher pullback that holds, buy the turn near C
- Source: Advanced Techniques in Day Trading, Ch. 6, Strategy 2: ABCD Pattern. Family: `patterns-indicators`. Signal source: underlying price/volume.
- Timeframe: 1-minute and 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: A = the day's low before the run; B = a new high of the day at least 0.5 x daily ATR14 above A; C = the lowest low after B, above A and at or above VWAP, confirmed when 3 one-minute bars close without a lower low. Long on the first 1-minute bar that makes a new high (above the prior bar's high) with volume above the prior bar's volume, while price is within 25% of the B-C distance above C. Reverse ABCD mirrored for shorts.
- Stop: a 1-minute close below C
- Exit: Sell half at B (or the first new high of the day) and move the stop to entry; rest on the first 5-minute bar that makes a new low (lower than the prior 5-minute bar's low), or 15:15.
- Filters: Before 11:45 only; skip if C is below VWAP.
- Trade: buy ATM CE on a long signal, ATM PE on a short; stops and targets on the index
- Assumptions (our reading where the book is vague): Thresholds for B (0.5 ATR), C confirmation (3 bars) and 'near C' (25%) are ours; volume from index futures (spot has none).
- Data: have (volume needs index futures minutes)
- Our nearest test: R87B pullback continuation held on SENSEX but its call rule lost -Rs 21,127 on 162 trades (stop under the pullback too tight); R71 P8 swing break lost in the final
### `book-aziz-06` Bull flag: break of the first or second consolidation with a joint 1- and 5-minute new high
- Source: Advanced Techniques in Day Trading, Ch. 6, Strategy 3: Bull Flag. Family: `patterns-indicators`. Signal source: underlying price/volume.
- Timeframe: 1-minute and 5-minute bars. Instruments: NIFTY, BANKNIFTY, CRUDE, NATURALGAS.
- Entry: Pole: the move to a new high of the day of at least 1 x the 14-bar 5-minute ATR within 3 five-minute bars. Flag: 3-12 one-minute bars that stay below the pole high and retrace at most half the pole. Long when a 1-minute bar breaks the flag high in the same minute that the current 5-minute bar exceeds the previous 5-minute bar's high, with 1-minute volume above the average of the flag bars. Only the first or second flag of the day.
- Stop: below the flag's low
- Exit: Half at a new high of the day (stop to entry); rest on a new 1-minute low (low below the prior 1-minute bar's low) or 15:15.
- Filters: Before 10:15 (the book trades flags at the Open); skip the third and later flags.
- Trade: buy ATM CE; stops on the underlying
- Assumptions (our reading where the book is vague): Pole and flag sizes are ours (the book is visual); built for low-float stocks, so the index version is an analogue.
- Data: have (volume needs futures minutes; MCX futures have it)
- Our nearest test: R71 P4 flag on index 5-minute bars: too rare, 32 final trades -Rs 442, design -Rs 27,378
### `book-aziz-07` Fallen Angel analogue: gap-up open spike, flush below VWAP, base, volume breakout
- Source: Advanced Techniques in Day Trading, Ch. 6, Strategy 1: Fallen Angel. Family: `patterns-indicators`. Signal source: underlying price/volume.
- Timeframe: 1-minute and 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: Day filter: gap up of at least 0.5%. The index makes a new high in the first 15 minutes, then trades below VWAP; a base of at least 15 one-minute bars with a range under 0.3 x daily ATR14 forms at or above the previous day's close or the 5-minute 50 SMA. Long on the first 1-minute close above the base high with volume at least 2 x the base's average 1-minute volume.
- Stop: below the base low
- Exit: Part at VWAP, part at the day's high (stop to entry after the first), rest at yesterday's high or a new 5-minute low; time exit 11:45.
- Filters: Before 11:45; skip if no base forms or the breakout volume test fails.
- Trade: buy ATM CE; stops on the index
- Assumptions (our reading where the book is vague): The book's Angel is a low-float US stock gapping on news with pre-market levels; the index has no comparable pre-market, so the previous close stands in for the pre-market low and every threshold is ours.
- Data: have (volume needs futures minutes); the book's own market (US low floats) is lacking
- Our nearest test: not tested by us; nearest R87B pullback continuation and R102 gap facts (a 0.5-1% NIFTY gap fills by the close 42% of the time)
### `book-aziz-08` Exit when the 5-minute chart stops making new extremes in the trade's direction
- Source: Advanced Techniques in Day Trading, Ch. 5, Trade Management Based on Price Action. Family: `trade-management`. Signal source: underlying price.
- Timeframe: 5-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: None (overlay on an arm's open position).
- Stop: the arm's own stop
- Exit: Long: exit at the close of the second consecutive 5-minute bar that fails to make a new high above the trade's best high AND whose low is below the prior bar's low. Short: mirror. Only after the trade has been open for at least two 5-minute bars.
- Filters: Applies only to exits earlier than the arm's own (risk-lowering).
- Trade: applies to the arm's option; triggers read on the index
- Assumptions (our reading where the book is vague): The book's 'fails to make a new low and turns to higher highs and higher lows' fixed as two bars; overlay on paper arms only.
- Data: have
- Our nearest test: R18: five early-exit overlays failed (0 of 60); R87B: candle exits against the trade mostly hurt; R81 / R100: exits that cut runners lose on Liquidity
### `book-aziz-09` Sell half (or a quarter) at the first level, move the stop to break-even
- Source: Advanced Techniques in Day Trading, Ch. 5, Trade Management; Ch. 6 strategy summaries. Family: `trade-management`. Signal source: underlying price for the level; premium for the lots.
- Timeframe: any (overlay). Instruments: NIFTY, BANKNIFTY.
- Entry: None (overlay; 2 lots per signal).
- Stop: after the first partial, the stop on the remaining lot moves to the entry price (plus charges)
- Exit: Lot 1 at the first pre-marked level (previous day close, yesterday's high/low, daily 50/200 SMA); lot 2 by the arm's own exit.
- Filters: none
- Trade: two lots of the arm's option
- Assumptions (our reading where the book is vague): The book uses 1/4 with a hotkey or 1/2 in summaries; 2 lots = halves.
- Data: have
- Our nearest test: R81 X5 (half at +15%, runner to breakeven) Rs 1,78,523 vs Rs 2,57,249 own exits; R100 G13 failed
### `book-aziz-10` Target the next pre-marked level instead of a fixed premium target
- Source: Advanced Techniques in Day Trading, Ch. 4 and Ch. 6 (profit targets). Family: `trade-management`. Signal source: underlying price.
- Timeframe: levels from daily bars; checked on 1-minute bars. Instruments: NIFTY, BANKNIFTY.
- Entry: None (overlay).
- Stop: the arm's own stop
- Exit: Exit when the index touches the nearest level beyond the entry in the trade's direction among: previous day close, yesterday's high/low, two-days-ago high/low, daily 50 and 200 SMA, round 100-point (NIFTY) / 500-point (BANKNIFTY) numbers; otherwise the arm's own exit.
- Filters: Only levels at least 0.2 x daily ATR14 beyond the entry.
- Trade: applies to the arm's option
- Assumptions (our reading where the book is vague): Round-number steps and the 0.2 ATR minimum are ours (the book uses half / whole dollars on stocks).
- Data: have
- Our nearest test: R18 post hoc (design only): exit at the next support/resistance helped 9 of 10 losing arms (+Rs 64.8k on 4,964 trades) - never pre-registered
### `book-aziz-11` Skip entries whose reward to the next level is under 2x the risk to the technical stop
- Source: Advanced Techniques in Day Trading, Ch. 7, Risk/Reward Analysis. Family: `daily-filters`. Signal source: underlying price.
- Timeframe: at the signal bar. Instruments: NIFTY, BANKNIFTY.
- Entry: At each arm signal: risk = index distance from entry to the arm's index stop (or VWAP for the book's strategies); reward = distance to the next level (list as in aziz-next-level-target). Take the signal only if reward >= 2 x risk.
- Stop: the arm's own
- Exit: the arm's own
- Filters: this is the filter (it only skips trades: risk-lowering)
- Trade: the arm's option
- Assumptions (our reading where the book is vague): Next level list as above; arms without an index stop use the book's VWAP stop for the risk distance.
- Data: have
- Our nearest test: not tested by us as an entry gate
### `book-aziz-12` Stop for the day after giving back 30% of the first hour's profit
- Source: Advanced Techniques in Day Trading, Ch. 6, One Stock in Play, All Strategies. Family: `sizing-execution`. Signal source: none (account P&L rule).
- Timeframe: intraday. Instruments: NIFTY, BANKNIFTY.
- Entry: None.
- Stop: If the account's realised P&L at 10:15 is positive and later realised plus open P&L falls by more than 30% of it, take no new entries for the rest of the day.
- Exit: Open positions keep their own exits (the rule blocks only new entries).
- Filters: Applies only on days with a positive P&L at 10:15.
- Trade: all paper arms
- Assumptions (our reading where the book is vague): US Open (to 10:30) mapped to NSE 10:15; 'switch to the simulator' read as no new entries.
- Data: have
- Our nearest test: R19c: a day profit lock raised green days but failed net / drawdown; the day loss cap lowered green days
### `book-aziz-13` Per-trade risk 1% (max 2%) of equity; stop for the month at a 6% loss
- Source: Advanced Techniques in Day Trading, Ch. 7, Position Sizing (2% and 6% rules). Family: `sizing-execution`. Signal source: none (account rule).
- Timeframe: per trade and per calendar month. Instruments: NIFTY, BANKNIFTY.
- Entry: Lots = floor(1% of equity / (option stop distance x lot size)), at least 0 (skip the trade when one lot risks more than 2%).
- Stop: Monthly: when realised losses plus open risk reach 6% of equity at the month's start, no new entries until the next month.
- Exit: unchanged
- Filters: none
- Trade: all arms
- Assumptions (our reading where the book is vague): Equity excludes margin; option stop distance = the arm's premium stop.
- Data: have
- Our nearest test: not a research test; compare with Boss's account stop (-Rs 40k drawdown or -Rs 13k a month)

## Trading in the Zone - Mark Douglas ([write-up](douglas-trading-in-the-zone.md))
### `book-douglas-01` Judge each arm in 20-trade blocks; pause an arm whose block falls outside its own expected range
- Source: Trading in the Zone, Ch. 11 (Trading in Sample Sizes, Testing). Family: `trade-management`. Signal source: none (a rule on an arm's own closed trades; the arm's signals stay on the underlying).
- Timeframe: per closed trade, blocks of 20. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: Unchanged: every signal of the existing arm (Liquidity 15+5, R72 Breadth Donchian, R51 Donchian, R52 OOV).
- Stop: Unchanged (the arm's own).
- Exit: Unchanged. After each completed block of 20 trades, compare the block's win count and net with the arm's expected 20-trade range from its design record; if the block is below the 5th percentile of that range, pause new entries for the next 20 signals (shadow-log them), then resume.
- Filters: None besides the block check. Variables are never changed mid-block.
- Trade: as the arm (1-ITM CE/PE chosen by its strike rule; stops/targets as the arm)
- Assumptions (our reading where the book is vague): The book says to judge in samples of at least 20 and that 20 is small enough to catch a fading edge, but gives no stop criterion; the 5th-percentile bootstrap of the arm's own design trades and the 20-signal pause are ours. Risk-lowering only; would start in shadow for live per CLAUDE.md.
- Data: have
- Our nearest test: R75 L1-K2 (stop after 2 losses in a row) and L7-DD20 (half size in a Rs 20k drawdown) both failed: the trades after a bad stretch paid. A 20-trade block rule is a slower version of the same idea and is not tested by us.
### `book-douglas-02` Fixed rupee risk per trade so that 20 straight losses fit the account stop
- Source: Trading in the Zone, Ch. 11 (Accepting the Risk). Family: `sizing-execution`. Signal source: none (sizing); stop distance from the arm's index-level stop.
- Timeframe: per trade. Instruments: NIFTY, BANKNIFTY, GOLD, SILVER, CRUDE.
- Entry: Unchanged: the arm's own signals.
- Stop: Unchanged: the arm's index-level stop (never moved to change size).
- Exit: Unchanged.
- Filters: Lots = floor(R / rupee loss of 1 lot if the stop is hit), minimum 0 (skip the trade if even 1 lot risks more than R). R = Rs 2,000 (Rs 40,000 drawdown stop / 20) or Rs 650 (Rs 13,000 monthly stop / 20); with k arms live at once, divide R by k.
- Trade: the arm's option, sized by the rule above instead of a fixed 1 lot
- Assumptions (our reading where the book is vague): The book sizes so that 20 losses are acceptable but gives no formula; dividing CLAUDE.md's account stop by 20 is ours. The rupee loss of 1 lot at the stop is estimated from the option's delta at entry times the index stop distance plus charges (about Rs 70 NIFTY / Rs 76 BANKNIFTY a round trip).
- Data: have
- Our nearest test: Not tested by us as a sizing rule. R75: every Donchian book breaks the account stop at 1 lot on all five indices; its conclusion was that size, not filters, is the lever. R101: 2 lots on 5-of-5 breadth earned what any extra lot earns (Rs 161 vs random 160, p 0.475).
### `book-douglas-03` Three lots: one off at a small first profit, one at the next level with stop to entry, last before the next swing
- Source: Trading in the Zone, Ch. 11 (Taking Profits). Family: `trade-management`. Signal source: underlying price (levels on the index); first-third profit on the premium.
- Timeframe: the arm's own bars (5-minute / 15-minute). Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD.
- Entry: The arm's signal, 3 lots.
- Stop: The arm's index stop on all lots until the second third is sold, then the remaining lot's stop moves to the entry price (premium).
- Exit: Lot 1 when the index has moved two-thirds of the stop distance in favour (the book's 4 ticks against a 6-tick stop). Lot 2 at the next index swing high/low or prior-day high/low beyond entry (the arm's target if it has one). Lot 3 just before the next significant higher-timeframe high/low (60-minute swing), else session square-off (15:05 expiry / 15:10 NSE, MCX 10 minutes before close).
- Filters: None.
- Trade: buy 3 lots of the arm's CE/PE; stops and levels on the index as the book states
- Assumptions (our reading where the book is vague): Book gives tick numbers for T-bonds and points for S&P only; translated as fractions of the stop distance. 'Most likely place for the market to stop' read as the next 60-minute swing.
- Data: have
- Our nearest test: R81 X5 (half at +15% = 1R, runner to breakeven) failed: design Rs 1,78,523 vs Rs 2,57,249 (881 trades), final -Rs 23,896 vs Rs 11,155 (357). R100 half off at +15% failed; R18 breakeven after +15% changed nothing. Expect worse, since the first third is taken earlier than 1R.
### `book-douglas-04` Daily uptrend: buy a pullback to 30-minute support; daily downtrend: sell a rally to 30-minute resistance
- Source: Trading in the Zone, Ch. 11 (Time Frame). Family: `patterns-indicators`. Signal source: underlying price.
- Timeframe: daily trend filter, 30-minute entry bars. Instruments: NIFTY, BANKNIFTY, CRUDE, GOLD.
- Entry: Daily trend up = the last two completed daily swing highs and swing lows are each higher (swing = 3-bar fractal); then on 30-minute bars, buy when price trades down to the most recent 30-minute swing low that lies above the daily trend's last higher low, and a 30-minute bar closes back above it. Mirror for the downtrend (sell = buy PE).
- Stop: On the index, a fixed buffer (0.25 x 30-minute ATR(14)) beyond the 30-minute swing low/high used for entry.
- Exit: Target = the prior 30-minute swing high (low for shorts); skip the trade if target distance < 3 x stop distance (the book's 3:1 ideal). Else square-off at session end (15:10 NSE, 15:05 expiry; MCX 10 minutes before close).
- Filters: Only with the daily trend; one trade a side a day; entries 09:45-14:30 NSE.
- Trade: buy 1-ITM CE in a daily uptrend, 1-ITM PE in a daily downtrend; stops/targets on the index
- Assumptions (our reading where the book is vague): The book gives this only as an example and leaves 'support' to the reader's edge; swing-fractal support, the ATR buffer and the 3:1 skip are ours. Intraday hold, no overnight.
- Data: have
- Our nearest test: Daily trend as an intraday filter: with-trend better in one year, against-trend in the other, no consistent value (FINDINGS.md, SWING). R20b: support/resistance no better than random lines (random levels beat real in 6 of 7). R73 / R79: trend checks before entries did not help.
### `book-douglas-05` Skip any arm signal whose first target is less than 3x its stop distance
- Source: Trading in the Zone, Ch. 11 (Taking Profits). Family: `daily-filters`. Signal source: underlying price (index distance to the arm's target vs its stop).
- Timeframe: the arm's own bars. Instruments: NIFTY, BANKNIFTY.
- Entry: The arm's signal, taken only when (index distance to the arm's target or next level) >= 3 x (index distance to its stop).
- Stop: Unchanged.
- Exit: Unchanged.
- Filters: The 3:1 ratio at the signal time, on the index.
- Trade: as the arm
- Assumptions (our reading where the book is vague): The book calls 3:1 ideal, not required; using it as an entry filter is our most literal mechanical reading. Only arms with an index target or level (Liquidity, ORB-type) can use it.
- Data: have
- Our nearest test: A fixed 3:1 premium rule (target +10%, stop -3.3%) won 19-31% vs about 28% needed (MANUAL_METHOD.md). R80: 0 of 10 entry filters on Liquidity passed. An index-level 3:1 filter is not tested by us.
