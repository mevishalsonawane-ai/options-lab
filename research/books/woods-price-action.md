# How to Trade with Price Action (Kickstarter Edition) - Galen Woods (2014)

Source: woods-price-action.txt, 143 pages, read in full on 25 Oct 2026 (text layer for every text page; OCR file
woods-price-action.ocr.txt read too, it holds only the cover and five chart captions - pages 1, 86, 89, 95, 101, 106).
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short beginner's collection of ten blog articles from the author's site (first edition October 2014).
It teaches how to read single bars, two-bar and three-bar sequences by range, body and wicks.
It lists 10 bar patterns, 10 candlestick patterns and 10 chart patterns, each with a one-line buy/sell trigger.
It argues the edge is the market bias (trend, structure, support/resistance), and the pattern only times the entry and sets the stop.
It ends with support/resistance, four ways to trade a channel, and ex-ante record keeping for discretionary traders.

## Methods, rules and setups

General conventions the book uses for every pattern (Ch. 4, 5, 7):
- Entry is a stop order one tick beyond the signal bar: "buy above" = buy stop above the signal bar's high, "sell
  below" = sell stop below its low.
- Stop: the "pattern stop" - the lowest point of a bullish pattern or the highest point of a bearish one (Ch. 7.2).
  The individual pattern sections never repeat a stop; this general rule is the only one given.
- Target: the nearest support/resistance level (highest probability), or a measured move (Ch. 7.3). Individual bar and
  candle patterns give no target of their own; chart patterns give a projected height.
- Every pattern is meant to be taken only with the market bias (Ch. 7.1); taking every pattern is called "a recipe for
  disaster".
- Markets: written for futures (ES, i.e. S&P 500 e-mini, in the examples), forex and stocks; NinjaTrader charts;
  timeframes 5-minute to weekly. No Indian market, no options.

### 1. Reading a bar: range, body, wicks (Ch. 2.1)
- Entry / stop / target: none; this is a reading skill.
- Content: the range shows volatility; the body shows who won and how strongly; the upper wick measures selling
  pressure and the lower wick buying pressure; a full-body bar (marubozu) is the strongest thrust, a doji is indecision.
- Filters: none. Market and timeframe: any.
- Signal source: underlying price; maps to: NSE index spot/futures yes, MCX futures yes, crypto yes (as context only).
- Mechanical? partly (the measures are mechanical; the reading is not).
- Our research: R87 measured exactly these quantities on 5m/15m NIFTY, BANKNIFTY and SENSEX candles: a long lower wick
  does not mean buyers will keep winning (NIFTY 15m big tail up 51% vs 52% matched, n 949), and a wick low is broken
  about as often as any level at the same distance (NIFTY 5m 59% vs 57%, n 3,314). HUNT_R87_CANDLE_ANATOMY.md.

### 2. Two-bar context and testing of the prior bar's high/low (Ch. 2.1)
- Entry / stop / target: none stated. The second bar is judged against the first (wider range = more volatile) and by
  whether it tests the first bar's high or low and is rejected (bearish/bullish) or continues.
- Signal source: underlying price; maps to NSE/MCX/crypto as context. Mechanical? partly.
- Our research: the "poke past the previous high and close back" idea is R87's stop-run family: on NIFTY 15m a poke
  above the day's high that closed back was followed by MORE upside (+0.129 ATR, n 1,666 design), which vanished in the
  holdout (-0.001, n 372). Not a reversal signal on our indices.

### 3. Three-bar expectation and "failure of expectation" (Ch. 2.2-2.3)
- Entry: when two bars set a bullish (bearish) expectation and the third bar does the opposite, the failure is a setup
  in the opposite direction (the book shows the morning star and the pin bar as examples). Stop / target not stated.
- Core assumption: the market has inertia (bullishness follows bullishness).
- Signal source: underlying price. Mechanical? no (expectations are judged by eye).
- Our research: inertia on its own has no edge on our indices: R102 found 1 of 70 sign rules confirmed and it was a
  cash-index staleness artefact (DEEP_NIFTY_BANKNIFTY.md); R87's 3-candle sequences gave 0 of 25 (R87B).

### 4. Reversal bar (Ch. 4.1)
- Entry: bullish bar trades below the previous bar's low and closes higher than the previous bar; buy above it, in an
  uptrend only (i.e. as a pullback-end signal). Bearish mirror: above the prior high, closes lower; sell below, in a
  downtrend.
- Stop: pattern stop (the reversal bar's low). Target: not stated (Ch. 7: nearest S/R or measured move).
- Filters: trend must already be up (down).
- Market and timeframe: futures, intraday and daily.
- Signal source: underlying price; maps to: NSE index options yes (buy CE/PE on the index signal), MCX futures/options
  yes, crypto yes with stop and lock.
- Mechanical? yes once "uptrend" is defined.
- Our research: not tested by us in this exact with-trend form. Nearest: R87 stop run below the day's low on 1-minute
  NIFTY bars held out of sample (+0.195 ATR, n 1,702 holdout) but is worth only about 1.8 points, under an option round
  trip of about 1.9 points.

### 5. Key reversal bar (Ch. 4.2)
- Entry: bullish bar opens below the previous low (a gap) and closes above the previous high; buy above it, or if
  unsure wait for a close above it. Bearish mirror.
- Stop: pattern stop. Target: not stated.
- Market and timeframe: the book says it is mostly a daily-chart pattern because intraday gaps are rare.
- Signal source: underlying price; maps to: NSE yes on daily index bars (the gap is the overnight gap), MCX yes,
  crypto has no session gap (pattern barely exists).
- Mechanical? yes.
- Our research: not tested by us as such. R87B's daily candle shapes (2015 - Oct 2025) gave 0 of 25 cells; R102 gap
  facts: a 0.5-1% gap fills by the close 42% (NIFTY) / 53% (BANKNIFTY) of the time, and gap fades never passed.

### 6. Exhaustion bar (Ch. 4.3)
- Entry: bullish bar opens with a gap down, rallies to close near its high, and the gap stays unfilled, on high volume;
  buy above it. Bearish mirror.
- Stop: pattern stop. Target: not stated. Filter: high volume.
- Signal source: underlying price/volume (index has no volume; index futures do). Maps to NSE daily, MCX yes.
- Mechanical? yes (needs a "near its high" threshold and a volume threshold).
- Our research: not tested by us.

### 7. Pin bar ("Pinocchio bar") (Ch. 4.4, also Ch. 2.3 and 5.6)
- Entry: a bar whose lower tail takes up most of its range, rejected from a support level; buy above it. Bearish:
  long upper tail at resistance; sell below.
- Stop: pattern stop (the tip of the tail; the book's Ch. 7 chart shows this for a bearish pin bar).
- Target: nearest resistance (support).
- Filters: must form at a support/resistance level; the logic is trapped traders who sold the false break.
- Signal source: underlying price; maps to NSE yes, MCX yes, crypto yes with stop and lock.
- Mechanical? partly (the level must be defined).
- Our research: tested and rejected. R87: big tail and hammer have no direction edge (NIFTY 15m 51% vs 52%, n 949;
  none passes BH, q 0.79-0.95), the tail's low is not defended, and "reversal" shapes mostly mark continuation (after
  a NIFTY 15m hammer the index fell a further 0.47 ATR over 20 candles). The level part: R20b found levels no better
  than random lines; R91's sweep-and-reject of a 1h swing (the same trapped-trader logic) passed 0 of 63.

### 8. Two-bar reversal (Ch. 4.5)
- Entry: a strong bearish bar followed by a bullish bar; buy above the higher high of the two. Bearish mirror; sell
  below the lower low.
- Stop: pattern stop (the low of the two bars). Target: not stated.
- Filters: none in Ch. 4; Ch. 7 says only with the market bias.
- Signal source: underlying price. Mechanical? yes. Maps to NSE/MCX/crypto.
- Our research: closest is R87's engulfing / piercing-type cells (12 two/three-candle patterns in the atlas; nothing
  held out of sample at 5m/15m). Not tested by us as a with-bias entry rule.

### 9. Three-bar reversal (Ch. 4.6)
- Entry: bar 1 bearish; bar 2 makes a lower high and a lower low; bar 3 is bullish, makes a higher low and closes above
  bar 2's high; buy above bar 3. Bearish mirror.
- Stop: pattern stop (bar 2's low). Target: not stated.
- Signal source: underlying price. Mechanical? yes. Maps to NSE/MCX/crypto.
- Our research: tested and rejected. R71 P7 (three-bar reversal at the day's high/low, 5m, buy 1-ITM option): final
  -Rs 1,67,689 on 855 trades, re-tune -Rs 83,990 on 675, design -Rs 3,21,930. It did beat random timing (twin p
  0.002), so the timing is real but smaller than the option's costs. HUNT_R71_CHART_PATTERNS.md.

### 10. Three-bar pullback (Ch. 4.7)
- Entry: in a bull trend, wait for three consecutive bearish bars, then buy above the next bullish bar. Bear-trend
  mirror (three bullish bars, sell below the next bearish bar).
- Stop: pattern stop (the low of the pullback). Target: not stated (the trend's prior high is the natural one).
- Filters: a trend must exist (not defined).
- Signal source: underlying price. Mechanical? yes once the trend is defined. Maps to NSE/MCX/crypto.
- Our research: not tested by us in this form. Nearest: R87B found that on SENSEX 15m two medium green candles then one
  medium red (a shallow pullback) are followed by more upside, and it held out of sample (+0.259 ATR, n 182), but the
  call-buying rule built on it lost (-Rs 21,127 on 162 trades) because the stop under the red candle's low was hit on
  117 of them. On MCX, R96's "pullback in trend" (P1) made Rs 1.08 lakh on 35 trades in design but twin p 0.164: a
  lead, not a pass. This is one of the two ideas most worth a fresh test (wider stop, see below).

### 11. Inside bar (Ch. 4.8)
- Entry, three ways: (a) OCO bracket - buy stop above its high and sell stop below its low, cancel the other once one
  fills; (b) only the side that agrees with the trend; (c) wait for the breakout and trade its failure (see Hikkake).
- Stop: pattern stop (the other side of the inside bar or its mother bar; not stated precisely). Target: not stated.
- Signal source: underlying price. Mechanical? yes. Maps to NSE/MCX/crypto.
- Our research: (a)/(b) tested and rejected: R71 P1 (15-min inside bar, break on a 5-min close) final -Rs 1,23,667 on
  1,075 trades; 30-min re-tune -Rs 43,603 on 403; twin p 0.72 / 0.82. The trend-only version (b) was not tested
  separately.

### 12. Outside bar (Ch. 4.9)
- Entry: (a) wait for a breakout of the outside bar and fade it, especially when the outside bar looks like a doji or
  goes against the trend; (b) trade the breakout when the outside bar closes near its high or low (the "popgun").
- Stop / target: not stated (pattern stop by Ch. 7).
- Signal source: underlying price. Mechanical? yes. Maps to NSE/MCX/crypto.
- Our research: R87's outside-bar cells did not pass; R87B found exiting on a strong opposite candle (marubozu,
  engulfing) HURT the Donchian and ORB reconstructions. The fade (a) is not tested by us.

### 13. NR7 (Ch. 4.10)
- Entry: the last of 7 bars has the smallest range of the 7; buy a break of its high if the trend is up, sell a break
  of its low if the trend is down.
- Stop: pattern stop (the other end of the NR7 bar). Target: not stated; "standby for explosive moves".
- Signal source: underlying price. Mechanical? yes. Maps to NSE/MCX/crypto.
- Our research: tested and rejected on indices. R71 P2 (15-min NR7, both sides, no trend filter) final -Rs 1,68,820 on
  1,341 trades. On MCX, R96 found crude NR7 the only lead (R3A Rs 48,875 on 46 trades, q 0.161) and it failed its fresh
  test on COPPER (-Rs 84,978 on 13). The book's trend-only version was not tested separately.

### 14. Hybrid bar patterns (Ch. 4.11)
- ID/NR4 (an inside bar that is also the narrowest of 4), a reversal bar after a three-bar pullback, a two-bar reversal
  whose second bar is an inside bar. Triggers as for the parts.
- The book also states that bar patterns alone give no edge.
- Our research: R96 tested NR4 on crude (-Rs 886 on 77) and Crabel's inside-day NR7 (-Rs 14,129 on 13). ID/NR4 on the
  indices and the other hybrids: not tested by us.

### 15. Doji (Ch. 5.1)
- Entry: trade it as a reversal signal when there is a trend to reverse; stand aside when there is none. Trigger,
  stop and target not stated.
- Signal source: underlying price. Mechanical? partly. Our research: R87, no doji type predicts direction at 5m/15m;
  the SENSEX 15m gravestone-doji put rule (R87-3) made +Rs 14,373 on 71 trades in design but failed walk-forward.

### 16. Marubozu (Ch. 5.2)
- Use: mainly a teaching tool; if traded, as a continuation bar in a strong breakout that agrees with the bias.
- Signal source: underlying price. Our research: the one shape fact that holds up: a full-body green candle's low is
  broken less often than its distance implies (NIFTY 15m 38% vs 42%, n 881) and 1-minute green marubozus continue a
  little on SENSEX (+0.08 ATR, n 5,851 holdout), both below an option's cost. The book's "continuation" reading agrees
  in sign.

### 17. Harami (Ch. 5.3)
- Entry: in a bull trend, a bullish harami (small up body inside a prior down body) marks the end of a pullback; mirror
  in a bear trend. Trigger, stop and target not stated (by Ch. 4/7: buy above, pattern stop).
- Our research: R87 1-minute harami cells passed design but not the holdout (NIFTY bearish harami q 0.22 holdout).

### 18. Engulfing (Ch. 5.4)
- Entry: in a bull trend, buy above a bullish engulfing (second body covers the first body) as a continuation; sell
  below a bearish one in a bear trend. Stop: pattern stop. Target: not stated.
- Our research: R87 atlas, no pass; R87B: exiting on an opposite engulfing made the Donchian reconstruction worse
  (Rs 33,012 -> Rs 3,067 on 1,722 trades).

### 19. Piercing line / dark cloud cover (Ch. 5.5)
- Entry: second bar opens below the first (bearish) bar's low and closes above its midpoint; use it for major bullish
  reversals, preferably after a trend-line break. Mirror for dark cloud. Needs a gap, so mostly daily bars.
- Our research: not tested by us as a rule; R87B daily shapes 0 of 25.

### 20. Hammer / hanging man (Ch. 5.6)
- Shape: body near the top, lower shadow about twice the body, colour irrelevant. Hammer after a decline: buy above
  it (or trade like a bullish pin bar). Hanging man at the top of a rise: sell below it only after a bearish
  confirmation bar.
- Our research: R87, rejected (see pin bar): after the hammer the decline continued.

### 21. Inverted hammer / shooting star (Ch. 5.7)
- Inverted hammer in a downtrend: buy above it after bullish confirmation. Shooting star in an uptrend: sell below it
  (like a bearish pin bar).
- Our research: R87: shooting stars came after a ~5.9 ATR rise that kept going (+0.64 ATR after 20 candles vs +0.20
  matched). Rejected.

### 22. Morning star / evening star (Ch. 5.8)
- Shape: long bearish bar, a small "star" body gapped below it, then a bullish bar closing inside the first body. Buy
  above the third bar (mirror for evening star). Used for both reversals and continuations. Rare intraday (needs a gap).
- Our research: R87 SENSEX 1m morning star passed design (+0.090) and reversed in the holdout (-0.083). Rejected.

### 23. Three white soldiers / three black crows (Ch. 5.9)
- Shape: three bars each opening inside the prior body and closing near the high. Buy above them after a substantial
  decline (mirror after a substantial rise).
- Our research: tested and rejected. R87-1 (NIFTY 5m three white soldiers -> buy call): -Rs 3,910 on 160 trades,
  walk-forward -Rs 2,932. Note the book uses it as a REVERSAL after a decline; R87 tested it without that condition.

### 24. Hikkake (Ch. 5.10)
- Entry: find an inside bar; the next bar breaks it to one side (a lower high and lower low for the bullish case); if
  that break fails within three bars, buy (bearish mirror: an upside break that fails, sell). The book calls it a
  continuation pattern and frames it as trading against trapped inside-bar breakout traders.
- Stop / target: not stated (pattern stop: the false-break bar's extreme).
- Signal source: underlying price. Mechanical? yes (the "failure" must be defined; the usual reading is price trading
  back beyond the inside bar's opposite end).
- Our research: not tested by us. Related evidence is against it: R20c found 32-49% of level breaks fake out within
  30 minutes yet neither direction pays; R89's "break and reclaim" lost the same as "break and go" (design -Rs 3.06 lakh
  vs -Rs 3.07 lakh); R61b found candidates after any failed break did worse (win 43.9% vs 48.8%, 5,780 candidates).
  Still the second idea most worth testing, because R71 inside-bar breaks lost so consistently that their failure is
  the natural mirror.

### 25. Head and shoulders (Ch. 6.1)
- Entry: bullish pattern (three swing lows, the middle lowest) in a downtrend: buy the breakout above the neckline, or
  the pullback to the neckline after it. Bearish mirror.
- Stop: not stated. Target: the head-to-neckline distance projected from the breakout point.
- Filters: a prior trend to reverse (longer trend needs a bigger pattern); volume should rise on the breakout.
- Signal source: underlying price/volume. Mechanical? partly (swing degree and neckline drawing vary). Maps to NSE
  index (no volume on spot; futures volume), MCX, crypto.
- Our research: not tested by us.

### 26. Double top / double bottom (Ch. 6.2)
- Entry: two swing lows near the same price, buy the break above the swing high between them (or its retest). Never in
  a sideways market. Target: the pattern height projected from the breakout. Stop: not stated. Volume rises on break.
- Our research: tested and rejected. R71 P5 (5-min, lows within 0.25 ATR14, neckline at least 1 ATR above): final
  -Rs 12,091 on 502 trades, design -Rs 94,311.

### 27. Triple top / triple bottom (Ch. 6.3)
- As the double, with three swings; the resistance line may be drawn at the higher swing high, the second one, or in
  between. Volume should shrink on each test and rise on the break. Target: pattern height.
- Our research: not tested by us.

### 28. Rounding top / bottom (Ch. 6.4)
- Entry: buy a close above the pattern's high (sell a close below the low of a rounding top); aggressive entry on a
  short-term pattern before completion. Mostly weekly charts. Target: pattern height. Volume U-shaped.
- Mechanical? no (the dome is judged by eye). Our research: not tested by us.

### 29. Island reversal (Ch. 6.5)
- Entry: after an exhaustion gap and sideways trading, buy when price gaps up away from the island (the two gaps
  overlap in price). Target: island height from the breakaway point. Volume falls on the first gap, rises on the second.
- Market: daily bars (needs two gaps). Our research: not tested by us; R102 gap facts are descriptive only.

### 30. Rectangle (Ch. 6.6)
- Entry: a sideways range after a trend; buy the break above the top (or the retest of it) if the prior trend was up.
  Target: rectangle height from the breakout. Volume rises on the break.
- Our research: the nearest is a channel breakout: R51/R72 Donchian are the arms with a real record (R71's reference:
  Donchian 20 made Rs 55,439 in the final on 1,787 trades). Rectangle with a prior-trend filter: not tested by us.

### 31. Wedge (Ch. 6.7)
- Entry: in an uptrend, a pullback between two converging, down-sloping lines (falling wedge); buy the break above the
  upper line. Mirror. Target: the whole wedge height. Volume shrinks, then rises on the break.
- Mechanical? partly. Our research: not tested by us.

### 32. Triangle (Ch. 6.8)
- Ascending (flat top, rising lows) is bullish, descending bearish, symmetrical follows the prior trend. In a bull
  trend buy the break above an ascending or symmetrical triangle. Target: the widest part projected from the break.
- Our research: not tested by us (R71's squeeze release P3 is the nearest volatility-contraction break: final
  -Rs 41,281 on 305 trades, and 67% of its trades were Donchian's).

### 33. Flag / pennant (Ch. 6.9)
- Entry: a sharp pole with the trend, then a parallel counter-sloping flag; buy the break above the flag. Target: the
  pole height added to the flag's lowest point. Volume shrinks in the flag, rises on the break.
- Our research: tested; too rare to judge. R71 P4 final -Rs 442 on 32 trades (re-tune Rs 5,805 on 32, below random
  entries' Rs 17,798).

### 34. Cup and handle (Ch. 6.10)
- Entry: conservative - buy the break of the cup's high; aggressive - buy when the handle pullback fails. Target: cup
  depth added to its high. Mechanical? no. Our research: not tested by us.

### 35. Market bias first (Ch. 7.1)
- Rule: decide the bias before looking for patterns, using a higher timeframe, market structure (higher highs and
  higher lows = up), trend lines (a decisive break starts the opposite trend), support/resistance behaviour, and
  volume (rises with the trend, dries up against it). Then take only patterns in the bias direction.
- The book's own illustration marks every opposite-colour two-bar pair on an ES chart and says the bearish ones did
  well because the bias was bearish - and admits the bias was chosen with hindsight.
- Signal source: underlying price/volume. Mechanical? partly.
- Our research: the parts we tested failed as filters. R71 P8 swing structure (HH + HL on 15m swings, buy the break)
  lost in the final (-Rs 40,109 on 374 trades) after a good design; R90's multi-timeframe structure/BOS indicator
  passed 0 of 99; R89's daily + 15-minute trend filter raised false breaks on NIFTY (32% -> 39%) and cut the win rate
  (36% -> 26%). The one bias that pays for us is a trend/breakout rule with its own evidence (Donchian, R51/R72).

### 36. Pattern stop and exits: nearest S/R, measured move (Ch. 7.2-7.3)
- Stop at the pattern's far end. Target: the nearest opposing support/resistance (higher hit rate) or a measured move
  equal to 100% of the prior impulse swing (a Fibonacci extension of 100%; smaller percentages are more conservative).
- Our research: R87B: a stop under the signal candle is too close for an option buyer (117 of 162 trades stopped). R22:
  a 1x-extension target lost (design -1,17,565, stop hit on 486 of 665). R90: far structural targets are rarely reached
  intraday while the option decays (3 of 76 index trades reached target on the 5m chart).

### 37. Support and resistance: where to find them (Ch. 8.1-8.2)
- Swing highs/lows (major ones), congestion areas (price-by-volume helps), psychological numbers (round numbers,
  52-week high/low), calculated levels (moving average in trends; Fibonacci retracements 23.6%, 38.2%, 50%, 61.8%, 100%),
  flipping (broken support becomes resistance), and levels taken from a higher timeframe.
- Signal source: underlying price. Mechanical? yes for most level types.
- Our research: levels as such were tested many ways and failed. R20b: random lines did as well (6 of 7 variants worse
  than random-level twins). H35: 408 level variants of the app's own levels (incl. round numbers, pivots, OI walls),
  all lost after costs. R4: swing Fibonacci levels reversed price no more than random lines (NIFTY 49.0% vs 49.1% at
  0.10%). R6/R4: round numbers on gold and crude were broken MORE often than random levels (the opposite of the book).

### 38. Using S/R in a strategy (Ch. 8.3)
- Direction: if supports keep holding, take only longs. Filter: if a buy signal is just under major resistance, wait
  for a clear breakout and buy the pullback. Entries: bullish signals at support, bearish at resistance. Exits: S/R
  (even minor) as targets and stops; for day traders the previous session's high and low.
- Our research: R20c: after 2 closes beyond a level, price was further on at the close only 51% of the time (7,548
  breaks); R22: break-and-retest was retested 82% of the time, then went on only 45% (-0.020 ATR, 442). R89: previous
  day high/low break entries 0 of 180. The previous-session high/low as a target: not tested separately.

### 39. Channels, four ways (Ch. 9)
- Trend: in a channel sloping at a healthy angle, enter on a pullback to the channel's trend line in the trend
  direction, target the opposite line. Reversal: when price overshoots the channel line and is rejected strongly,
  trade the reversal, only if the channel runs against the higher-timeframe trend and is steep. Range: in a horizontal
  channel, sell the top and buy the bottom (Bollinger Bands as the channel in the "Gimmee bar" setup). Break-out: on
  volume and with a wider-than-average bar.
- Overtrading fix: draw a larger channel (longer look-back, wider deviation) and trade only in its direction.
- Signal source: underlying price/volume. Mechanical? partly (indicator channels yes; drawn trend lines no).
- Our research: Donchian channel breakout is our proven arm (R51, R72); Bollinger squeeze release failed (R71 P3);
  the range-fade arm (ORB Range Fade) lost (H35 lists the ORB family arms at Rs 2.5-9.8 lakh losses each). Trend-line
  and regression channels: not tested by us.

### 40. Record keeping: ex-ante commentary and mechanical vs discretionary labels (Ch. 10)
- Write a running market commentary in real time, before outcomes are known; label each trade "mechanical" or
  "discretionary" before it closes; after enough trades, check whether discretion adds value; if not, stick to rules.
- Signal source: none / discretionary (a process). Maps to the app's manual trade journal.
- Our research: agrees with our pre-registration discipline (CLAUDE.md research rules). The mechanical/discretionary
  label on Boss's own manual trades is not something the app records today (see "What is new").

### 41. Choosing a futures market and simulation first (Ch. 3)
- Pick a volatile and liquid contract (low spread and slippage), learn one market at a time, trade in simulation with
  a concrete profit goal before going live.
- Our research: agrees with Boss's paper-only window; our liquidity finding that GOLDM/SILVERM 1-ITM options print too
  rarely to fill (R89, R90, R91) is the same point about liquidity.

## Performance claims
- No numbers anywhere: no win rates, returns, sample sizes or backtests.
- Qualitative claims: the market bias gives the edge "far more than" any one- to three-bar pattern; once the bias is
  right "almost any pattern" works. Evidence: three hand-marked ES charts, chosen with hindsight (the author says so).
- The book concedes the weak side itself: bar patterns alone will not offer an edge; most reversal chart patterns fail,
  especially in strong trends; textbook examples are cherry-picked; the trading rules are "more like guidelines";
  for real success rates it points to Bulkowski's Encyclopedia of Chart Patterns.
- Every example chart is a winner; no losing example is shown.

## Risk, sizing and psychology
- Risk control is the reason a setup exists: a pattern gives a nearby logical stop, which lets a small trader risk a
  little per trade instead of trading the bias with deep pockets (Ch. 7.2).
- No position sizing rule, no daily loss limit, no percentage risk per trade.
- Avoid overtrading: do not trade every pattern you see; master one setup at a time; with channels, trade only with the
  larger channel's direction.
- Simulation with a concrete goal before live money (Ch. 3.4).
- Hindsight bias is the main psychological enemy; ex-ante records are the cure (Ch. 10).
- Treat chart-pattern targets as guidelines and combine them with other S/R levels.

## How it relates to our research
- **Already rejected (tested, failed):** pin bar / hammer / shooting star / doji / harami / engulfing / morning star /
  three soldiers (R87, 0 of 3 option rules; atlas 0 held at 5m/15m), inside-bar break and NR7 break (R71 P1, P2), the
  three-bar reversal at day extremes (R71 P7), double bottom/top (R71 P5), flag (R71 P4, too rare), swing structure
  HH/HL (R71 P8, R90), support/resistance levels and their breaks (R20b, R20c, R22, H35), Fibonacci levels (R4), round
  numbers (H35, R4, R6), previous-day high/low as entry levels (R89, 0 of 180), stop-hunt / trapped-trader reversals
  (R87 stop runs, R91 sweep 0 of 63, R8 absorption, R61b trap flags), multi-timeframe trend filters (R89, R90).
- **Agree:** the book's own view that bar patterns alone have no edge matches R87 and R71 exactly. Its warning that
  reversal patterns fail in strong trends matches R87's finding that "reversal" candles mostly mark continuation. Its
  marubozu-as-continuation reading matches the one small R87 shape effect. Its emphasis on volatility contraction and
  expansion (inside bar, NR7) matches our finding that move SIZE is predictable (R16, R88), though direction is not.
- **Contradict:** S/R zones as places where price turns (R20b: no better than random lines; R8: absorption levels broke
  58-68% vs 55%), wick tips as stops (R87: wick lows are broken as often as any level at that distance), round numbers
  as support (R6: broken more often), and the multi-timeframe bias filter as a fix for false breaks (R89).
- **Not tested by us:** three-bar pullback in trend (exact form), Hikkake, the with-trend-only versions of inside bar,
  NR7, reversal bar and engulfing, outside-bar fade, key reversal / exhaustion bar / island reversal on daily bars,
  head and shoulders, triple, rounding, wedge, triangle, rectangle with a prior-trend filter, cup and handle,
  trend-line and regression channels, measured-move targets on chart patterns.
- Note on volume: almost every chart pattern here asks for volume confirmation. The NSE index has no volume (R87, R71);
  only index futures (short history in our data) and MCX futures could carry it.

## What is new to us
1. **Three-bar pullback with trend, stop below the pullback low (not the last candle).** R87B showed the pullback
   pattern's direction held out of sample on SENSEX but the option rule died on a too-tight stop; the book's version
   stops at the whole pullback's low and needs a trend. A clean, pre-registerable re-test.
2. **Hikkake (failed inside-bar breakout).** We tested inside-bar breaks (they lose consistently) but never their
   failure as an entry.
3. **With-trend filtering of each bar pattern** (the book's central claim). R71 traded patterns both ways; the
   with-trend-only versions of inside bar, NR7 and reversal bar were not run. Prior evidence (R89 HTF filter) is
   negative, so this is low priority.
4. **Outside-bar fade** (fade the breakout of a doji-like or counter-trend outside bar).
5. **Daily-bar gap patterns** (key reversal, exhaustion bar, island reversal) on NIFTY/BANKNIFTY daily data since 2006,
   traded as next-day option buys or swing futures.
6. **Mechanical vs discretionary labels on Boss's manual trades**, recorded before the exit, so the app can later show
   whether Boss's discretion beats the rules - a journaling feature, not a signal.
7. **Volume-confirmed chart patterns on MCX futures** (the one market where we have volume on the traded underlying).

## Could not read
Every chart (about 70 example and diagram images) has no text layer; only their captions were read, and OCR of the
six image-only pages (1, 86, 89, 95, 101, 106) gives captions and fragments only. The charts are illustrations of the
rules already stated in the text, so no rule is lost. Hyperlinked follow-up articles ("Read: ...") are not in the book.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-woods-01`: tested (R104): IDX: 476 trades, win 47%, net -Rs 47,085 design, holdout -Rs 32,754 (fail); MCXI: 28 trades, win 29%, net -Rs 25,227 design, holdout -Rs 50,868 (fail)
- `book-woods-02`: tested (R104): IDX: 1240 trades, win 46%, net -Rs 95,643 design, holdout -Rs 17,566 (fail); MCXI: 34 trades, win 21%, net -Rs 6,455 design, holdout -Rs 6,681 (fail)
- `book-woods-03`: tested (R104): IDX: 2099 trades, win 44%, net -Rs 249,800 design, holdout -Rs 146,463 (fail)
- `book-woods-04`: tested (R104): DLY: 115 trades, win 43%, net -Rs 116,147 design, holdout -Rs 273,480 (fail); MCXD: 23 trades, win 43%, net Rs 3,801 design, holdout Rs 24,281 (fail)
- `book-woods-05`: tested (R104): DLY: 36 trades, win 36%, net -Rs 121,797 design, holdout -Rs 155,711 (fail); MCXD: 12 trades, win 33%, net -Rs 14,684 design, holdout -Rs 43,872 (fail)
- `book-woods-06`: tested (R104): IDX: 283 trades, win 37%, net -Rs 57,502 design, holdout Rs 26,154 (fail)
- `book-woods-07`: tested (R104): IDX: 469 trades, win 50%, net -Rs 61,716 design, holdout Rs 1,169 (fail)
- `book-woods-08`: not re-run (R104): covered: R71 P7 three-bar reversal (final -Rs 1,67,689 on 855, twin p 0.002: timing real, below option cost); the book's stop/exit are R71's
- `book-woods-09`: tested (R104): IDX: 2992 trades, win 40%, net -Rs 259,116 design, holdout -Rs 143,840 (fail)
- `book-woods-10`: tested (R104): IDX: 1063 trades, win 42%, net -Rs 121,020 design, holdout -Rs 40,726 (fail)
- `book-woods-11`: tested (R104): IDX: 1221 trades, win 45%, net -Rs 53,374 design, holdout Rs 4,876 (fail)
- `book-woods-12`: tested (R104): IDX: 3454 trades, win 37%, net -Rs 445,187 design, holdout -Rs 150,047 (fail); DLY: 579 trades, win 32%, net Rs 124,016 design, holdout -Rs 310,727 (fail); MCXI: 98 trades, win 24%, net -Rs 26,072 design, holdout -Rs 16,801 (fail)
- `book-woods-13`: tested (R104): IDX: 2790 trades, win 22%, net -Rs 378,844 design, holdout -Rs 98,293 (fail); DLY: 404 trades, win 41%, net Rs 486,756 design, holdout Rs 391 (fail)
- `book-woods-14`: tested (R104): IDX: 1830 trades, win 50%, net -Rs 116,652 design, holdout -Rs 76,472 (fail)
- `book-woods-15`: tested (R104): IDX: 55 trades, win 45%, net Rs 340 design, holdout Rs 15,605 (fail)
- `book-woods-16`: tested (R104): IDX: 459 trades, win 33%, net -Rs 46,244 design, holdout -Rs 75,273 (fail); DLY: 94 trades, win 55%, net Rs 214,631 design, holdout Rs 139,562 (fail); MCXI: 26 trades, win 38%, net -Rs 1,899 design, holdout -Rs 11,073 (fail)
- `book-woods-17`: tested (R104): IDX: 51 trades, win 29%, net -Rs 24,834 design, holdout -Rs 945 (fail)
- `book-woods-18`: tested (R104): IDX: 85 trades, win 27%, net -Rs 41,080 design, holdout -Rs 1,848 (fail)
- `book-woods-19`: tested (R104): IDX: 3804 trades, win 29%, net -Rs 727,169 design, holdout -Rs 90,506 (fail)
- `book-woods-20`: tested (R104): IDX: 1221 trades, win 33%, net -Rs 76,440 design, holdout -Rs 59,374 (fail)
