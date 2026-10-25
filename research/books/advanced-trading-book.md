# Advanced Trading Book / Simple Trading Book - credited to Eman Mohmaad (year not stated; latest chart example dated 05 Oct 2022)

Source: advanced-trading-book (epub of 58 page images plus a cover, no text layer; pages carry an "@tradingface"
watermark), 58 pages, read in full on 25 Oct 2026 (how: tesseract OCR of every page, and every diagram, strategy and
chart page also viewed as an image; details under "Could not read"). Page numbers below are the image order (p1 = the
first page after the cover); the book's own contents page uses different numbers (its "Page 6-64").
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short picture book for beginners in four chapters: psychology, candlesticks, seven "strategies", chart patterns.
Chapter 1 is about FOMO in crypto: verify news, use trusted sources, accept losses, keep a checklist.
Chapter 2 shows 11 candlestick patterns (bullish, bearish, indecision), each with one drawn example.
Chapter 3 gives seven breakout setups whose common rule is "do not buy the first break; wait for a retest or a second break".
Chapter 4 draws 22 chart patterns with entry, stop and target marks, mostly on crypto, forex and gold charts.

## Methods, rules and setups

All entries, stops and targets in this book are read from the price chart of the thing traded (crypto pair, forex pair,
gold, an Indian stock). In IraAlgo terms every method below is **signal source: underlying price** (no volume, no
indicator except one mention of RSI, no option data). Mapped to IraAlgo: compute the pattern on index spot / futures
bars and buy the option chosen by the strike rule; on MCX compute it on the future; on crypto compute it on the coin,
and add a profit lock (the book gives stops but never a trailing or step lock, which CLAUDE.md requires for crypto).
No timeframe is prescribed; the worked examples use 30-minute to daily charts.

### Chapter 2 - candlestick patterns (p9-p23)

#### 1. Candle anatomy (p9)
- What it says: a candle shows open, high, low, close; the body is the open-to-close range, the wicks the extremes;
  green = closed up, red = closed down.
- Entry / stop / target: none (definitions only).
- Signal source: underlying price; maps to NSE index, MCX, crypto as a definition only.
- Mechanical? yes (definitions).
- Our research: R87 measures exactly these parts (body %, wick %, close location) on 1m/5m/15m index candles.

#### 2. Hammer (p11)
- Entry: a small body with a long lower wick and little or no upper wick, after a decline, at a support line; the book
  insists price must start moving up after it ("confirmation") before it counts. Exact entry point not stated.
- Stop: not stated. Target: not stated ("potential reversal to the upside").
- Filters: prior decline; at support.
- Market and timeframe: not stated.
- Signal source: underlying price; maps to NSE index options (buy CE), MCX, crypto (plus stop and lock).
- Mechanical? partly (wick and body ratios, "support" and "confirmation" need defining).
- Our research: R87 (HUNT_R87_CANDLE_ANATOMY.md, "The answer for Boss"): after a NIFTY 15m hammer the index fell a
  further 0.47 ATR over 20 candles (matched candles +0.19): "reversal" shapes mostly mark continuation. A long wick is
  not a defended level: a big tail's low is broken within 10 candles 59% of the time vs 57% for any low at the same
  distance (NIFTY 5m, n 3,314). GREEN_CANDLES.md (BANKNIFTY 2025-26): after a long-lower-wick 15m candle the next was
  green 50.5% of 1,321 vs a 50.0% base ("noise"). GOLD_CANDLES.md: the 1-hour hammer / pin bar, buys only, was the one
  consistent gold family but weak (+$37.7k a lot with a 2R target, 424 trades, t 0.84).

#### 3. Morning star (p12)
- Entry: three candles - a long red, a short-bodied middle (either colour, sometimes gapping down), then a long green
  that "confirms the reversal". Exact entry not stated (by the drawing, after the third candle).
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index (CE), MCX, crypto (plus stop and lock).
- Mechanical? yes once "long" and "short" are given numbers.
- Our research: R87 tested it among the 12 two/three-candle patterns; the only design finding (SENSEX 1m morning star,
  +0.090 ATR, n 3,466) reversed in the holdout (-0.083 ATR, n 837). GOLD_CANDLES.md: 15m and 1h morning-star versions
  on XAUUSD were mixed in sign across years (for example 15m, exit 2R, buys only: -958, +7,111, +10,334 over three years,
  684 trades, t 0.39).

#### 4. Bullish engulfing (p13)
- Entry: a small red body completely engulfed by the next, larger green body after a downtrend. Entry point not stated.
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index (CE), MCX, crypto.
- Mechanical? yes.
- Our research: R87 (engulfing in its 12 patterns): no pattern passed out of sample; R87B found exiting a trade on an
  engulfing candle against it made the ORB arm worse (-Rs 42,729, 813 design trades). GOLD_CANDLES.md: 1h bullish
  engulfing, exit 2R, buys only: +15,648, +21,653, then -37,057 in the held-out year (706 trades, t 0.00). R85: on MCX
  futures "hammer, shooting star, engulfing ... carry no tradable meaning".

#### 5. Three white soldiers (p14)
- Entry: three long green candles in a row, each opening and closing higher than the one before, after a downtrend; the
  book calls it reliable "when confirmed" by other indicators such as RSI (no RSI level given).
- Stop / target: not stated.
- Signal source: underlying price (RSI also on the underlying); maps to NSE index (CE), MCX, crypto.
- Mechanical? yes (RSI threshold must be chosen).
- Our research: R87 rule R87-1, NIFTY 5m three white soldiers -> 1-ITM call: -Rs 3,910 on 160 design trades, twin p
  0.236, fail. GOLD_CANDLES.md: 15m three soldiers lost in the held-out year in every version shown (for example "trend,
  exit 2R, buys only" +12,546, +48,877, then -50,842; 1,215 trades). GREEN_CANDLES.md: after 3+ green 15m candles in a
  row the next was green only 45.1% (647, "LESS green"). RSI: B2 (FINDINGS.md) put RSI among 469 inputs; next-candle
  colour AUC 0.516.

#### 6. Hanging man (p16)
- Entry: the hammer shape (short body, long lower wick, little upper wick) at the end of an uptrend; bearish. Entry not
  stated (the drawing shows red candles after it).
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index (PE), MCX, crypto.
- Mechanical? partly ("end of an uptrend" is hindsight unless defined as e.g. a new N-bar high).
- Our research: R87 (hammer / hanging man family): no direction edge; up-rates for all shapes 43-62% vs ~51% matched.

#### 7. Shooting star (p17)
- Entry: a red candle with a short body and a long upper wick, little lower wick, often after a small gap up; bearish
  at a local peak. Entry not stated.
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index (PE), MCX, crypto.
- Mechanical? yes.
- Our research: R87: shooting stars came after a ~5.9 ATR rise that kept going (+0.64 ATR after 20 candles vs +0.20
  matched). Big upper wick on NIFTY 15m: up 53% vs 51% matched (n 592, q 0.81).

#### 8. Bearish engulfing (p18)
- Entry: a small green body completely covered by the next long red candle at the top of an uptrend; the red candle
  opens at or above the prior close and closes below the prior open. "The lower the second candle goes, the stronger."
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index (PE), MCX, crypto.
- Mechanical? yes.
- Our research: as method 4 (R87 no pattern held out of sample; R85 none on MCX).

#### 9. Evening star (p19)
- Entry: mirror of the morning star: long green, short-bodied middle, large red that finishes deep into the first body.
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index (PE), MCX, crypto.
- Mechanical? yes.
- Our research: R87 (12 patterns): nothing held out of sample.

#### 10. Three black crows (p20)
- Entry: three long red candles with tiny wicks, each opening near the previous close and closing much lower; "a strong
  bearish signal".
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index (PE), MCX, crypto.
- Mechanical? yes.
- Our research: R87 (three soldiers / crows family): no out-of-sample finding. GREEN_CANDLES.md (BANKNIFTY 5m): after
  3+ red candles in a row the next was green 53.5% (2,019): if anything a bounce, not continuation.

#### 11. Doji (p22)
- Entry: a candle with an exceptionally small body and long wicks; the book says it is usually read as continuation but
  can be a reversal, so open a position only "a few candles after" it, once direction is clear.
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index, MCX, crypto.
- Mechanical? partly ("a few candles" and "clear" undefined).
- Our research: R87 rule R87-3 (SENSEX 15m gravestone doji -> put): +Rs 14,373 on 71 trades but walk-forward -Rs 1,923
  and -Rs 2,721 without its 5 best trades: fail.

#### 12. Spinning top (p23)
- Entry: a short body centred between two equal wicks; indecision, possibly a pause after a strong move. No trade rule.
- Signal source: underlying price. Mechanical? yes as a label, no as a trade.
- Our research: R87 "small both" / "both big" wick families: no direction edge (NIFTY 15m both big 52% vs 52%, n 467).

### Chapter 3 - "Trading strategies" (p25-p32)

#### 13. Trading terms and the 1:3 ratio (p25)
- What it says: defines target, entry, stop-loss ("maximum trade loss"), retest ("price at a key level again") and a 1:3
  ratio (three units of expected gain to one unit of risk). The ratio is defined but no strategy states its target in R.
- Signal source: none (definitions). Mechanical? yes (a fixed 3R target is mechanical).
- Our research: a fixed 3R target was not tested by us as such; R90 (FINDINGS.md) found a target about 2x risk away was
  rarely reached by 15:15 on index trades (5m: 3 of 76), and R18 found the arms keep about 0% of their options' best
  price with their current exits.

#### 14. Strategy 1 - falling trendline ("neckline") break, then retest (p26)
- Entry: price is rejected again and again by a falling line (drawn through lower highs). Take only the bullish trade:
  do NOT buy the first break of the line; wait for price to come back to the line (retest) and then move up with a
  bullish candle; buy then.
- Stop: not stated. Target: not stated.
- Filters: none besides the retest and bullish candle. Drawing also marks a flat "support" under the base.
- Market and timeframe: not stated (schematic).
- Signal source: underlying price; maps to NSE index (buy CE on the confirmation candle's close), MCX, crypto.
- Mechanical? partly (trendline through pivots and "retest" tolerance must be defined).
- Our research: retest entries were tested on horizontal levels, not on trendlines. R22 (FINDINGS.md): after a held
  break of the morning range the price retested 82% of the time and the retest closed beyond 78%, but was further on by
  the close only 45% (-0.020 ATR, 442); the retest rule lost (-20,547 design / -6,095 holdout). HUNT_H35.md:
  "retest-then-go" on 8 level families, with "break-and-hold" and "bounce", 408 variants: all lost after costs.

#### 15. Strategy 2 - symmetrical triangle after a rise, break, retest, buy the next candle (p27)
- Entry: a converging triangle after an up move is a "continuation" pattern, so look only to buy. When price breaks the
  upper line, do not buy; wait for a retest of the line and a bullish candle, then buy on the NEXT candle. The text also
  says "when the price breaks its previous high, then we can buy".
- Stop / target: not stated. The book admits these patterns "sometimes fail".
- Signal source: underlying price; maps to NSE index (CE), MCX, crypto.
- Mechanical? partly (triangle lines from pivots; retest tolerance).
- Our research: triangles not tested by us. Nearest: R71 squeeze release (a volatility contraction then a band break):
  design +Rs 57,763, final -Rs 41,281 on 305 trades, and 67% of its trades were Donchian's own trades.

#### 16. Strategy 3 - rising wedge as a bearish continuation: second break, retest, sell (p28)
- Entry: a rising wedge (both lines up, converging) after a fall. Do NOT sell the first break of the lower line (price
  can break and go back up). Wait until price breaks the previous swing low inside the pattern ("confirmation"), then
  wait for price to come back up to that broken low (retest) and turn down with a bearish candle; sell there.
- Stop: above the previous swing high.
- Target: not stated in this strategy (Chapter 4 draws one).
- Signal source: underlying price; maps to NSE index (buy PE), MCX, crypto.
- Mechanical? partly (pivots and the wedge must be defined).
- Our research: wedges not tested. "Break of the previous swing" entries: R90 (BOS, FINDINGS.md) 0 of 99 and R89
  (previous-day high / low break with a confirmation candle) 0 of 180, about a third of trades false breaks.

#### 17. Strategy 4 - triangle in a downtrend, sell the break at once, tight stop (p29)
- Entry: in a downtrend a triangle forms (the drawing has a flat lower line and falling upper line, though the book calls
  it a "symmetrical continuation triangle"). Wait until it is complete; when price breaks the lower line (which is also
  the previous low), sell immediately - no retest here.
- Stop: two possible levels - level 1 (the earlier lower high) or level 2 (the last, lower high); the book chooses level
  2, the tighter one.
- Target: not stated (drawn as a continuing fall).
- Signal source: underlying price; maps to NSE index (PE), MCX, crypto.
- Mechanical? partly.
- Our research: not tested as a triangle. R20c (FINDINGS.md): two-close breaks of horizontal levels went on only +0.02
  ATR to the close, 51% further on, and random levels did about the same (7,548 breaks).

#### 18. Strategy 5 - bearish flag (rising channel after a drop) (p30)
- Entry: after a sharp fall, price drifts up in a parallel channel. Wait until it breaks the lower channel line, then
  sell.
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index (PE), MCX, crypto.
- Mechanical? partly.
- Our research: R71 P4 flag (pole of 2 ATR, tight flag, break of the pole extreme, NIFTY / BANKNIFTY / FINNIFTY /
  MIDCPNIFTY / SENSEX): only 32 trades in the final period (-Rs 442 with L25; +Rs 5,805 with the index stop, below
  random entries' +Rs 17,798): too rare to judge; design -Rs 27,378.

#### 19. Strategy 6 - consolidation box (rectangle) breakout with retest (p31)
- Entry: after an up or down move, price moves sideways; mark the box's high (resistance) and low (support) and wait for
  price to leave the box either way. After an upside break, wait for the retest of the box top; enter after a bullish
  candle ("A" confirms momentum, enter at "B"). Mirror for the downside.
- Stop / target: not stated.
- Signal source: underlying price; maps to NSE index (CE above / PE below), MCX, crypto.
- Mechanical? yes once the box is defined (e.g. N bars inside a range of at most k ATR).
- Our research: the closest IraAlgo rule is the Donchian arm, which buys a plain N-bar breakout without waiting for a
  retest: Donchian 20 made Rs 55,439 on 1,787 final-period trades (R71 table, paper arm). Waiting for the retest was
  not better on the morning range (R22, above). The ORB family (a morning box) lost everywhere (R92: 0 of 257).
  MCX: NR7 (narrowest bar) breakout on crude options is R96's only lead (R4G +Rs 49,381 on 28, q 0.046, failed the
  family gate).

#### 20. Strategy 7 - bullish flag: wait for the last high to break, then buy the pullback candle (p32)
- Entry: after a sharp rise, price drifts down in a falling channel. Breaking the channel's upper line is not enough
  ("if price stays under the previous high the pattern may fail"); wait for the last swing high (horizontal line) to
  break. Then: candle A breaks it, candle B is a bearish pullback, candle C is bullish; enter on C, because entering
  right after A makes the stop bigger.
- Stop: implied below the pullback (B); not stated exactly.
- Target: not stated.
- Signal source: underlying price; maps to NSE index (CE), MCX, crypto.
- Mechanical? yes once swings are defined (A/B/C are single candles).
- Our research: R71 P4 flag (above) and P8 swing structure (buy the break of the last 15-minute swing high: final
  -Rs 34,242 on 372 trades, twin p 0.99). The "enter on the first pullback candle after the break to shrink the stop"
  step was not tested by us; R87B's one held sequence (SENSEX 15m green, green, medium red -> more upside, +0.26 ATR,
  n 182) is similar in spirit, but bought as a call with a stop under the red candle it lost (-Rs 21,127, 162 trades;
  the stop was hit on 117).

### Chapter 4 - chart patterns (p34-p52) and printable sheets (p53-p58)

Each Chapter 4 page shows a schematic (neckline or boundary lines with "entry", "stop" and "target" marks) and one
real chart (crypto, forex, gold, natural gas or an Indian stock), with no text. Rules below are read from the drawings.
The common drawn rule: **entry just beyond the neckline / boundary after the break; stop back inside the pattern (beyond
the last swing or the far side of a box); target a measured move about the pattern's height beyond the break** (the
height rule is inferred from the drawings; the book never states it). None of these pages repeats Chapter 3's "wait for
the retest" rule. All are signal source: underlying price, and map to NSE index options (buy CE on bullish, PE on
bearish), MCX futures / options and crypto (plus a profit lock).

#### 21. Double top / double bottom (p34, p38; sheets p53, p54)
- Entry: sell below the neckline (the low between two equal highs); mirror: buy above the neckline between two equal
  lows. Stop: above the second top's lower part (drawn inside the pattern, below the peaks); mirror below. Target: about
  the pattern height beyond the neckline. Examples: BTC/USDT 2H, 05 Oct 2022; USDCHF 4H, 29 Sep 2022.
- Mechanical? yes. Our research: R71 P5 double bottom / top (5-min swings within 0.25 ATR, neckline at least 1 ATR
  away, index stop at the lower low): final -Rs 12,091 on 502 trades, twin p 0.21; design -Rs 94,311: fail.

#### 22. Head and shoulders / inverse head and shoulders (p36, p39; sheets p53, p54)
- Entry: sell under the neckline after the right shoulder (mirror: buy over it). Stop: above the right shoulder's
  lower half (mirror below). Target: about the head-to-neckline height. Examples: NATGASUSD daily, 19 Aug 2022; BATA
  INDIA 1H, 07 Mar 2022.
- Mechanical? partly (shoulder symmetry tolerances). Our research: not tested by us. The pattern's break of a swing
  level relates to R89 / R90 (0 of 180, 0 of 99).

#### 23. Falling wedge (continuation after a rise, p35; reversal after a fall, p40; sheets p53, p54)
- Entry: buy the break above the upper (falling) line. Stop: below the lower line. Target: above the start of the
  wedge. Examples: GRT/USDT 4H, 25 Jul 2022; NZDCAD daily, 05 Aug 2022.
- Mechanical? partly. Our research: not tested by us.

#### 24. Rising wedge (reversal after a rise, p37; continuation in a downtrend, p42; sheets p53, p55)
- Entry: sell the break below the lower (rising) line. Stop: above the upper line. Target: below the wedge's start.
  Examples: BTCUSDT 4H, 06 Aug and 26 Aug 2022. (Chapter 3 Strategy 3 adds the "second break + retest" filter.)
- Mechanical? partly. Our research: not tested by us.

#### 25. Bullish rectangle / bearish rectangle (p41, p44; sheets p54, p55)
- Entry: after a strong move, a flat box; buy the break above the box top (bullish) or sell below the bottom (bearish).
  Stop: just beyond the opposite side of the box. Target: about the box height (drawn larger) beyond the break.
  Examples: GBPJPY 4H, 13 Aug 2020; GALAUSDT 2H, 20 Jan 2022.
- Mechanical? yes. Our research: a box break without retest is close to the Donchian arm (Rs 55,439 on 1,787 final
  trades) and to the losing ORB family; the box defined by consolidation, not by time, was not tested as such.

#### 26. Bullish pennant / bearish pennant (p43, p45; sheet p55)
- Entry: after a sharp pole, a small converging triangle; buy above its upper line (bullish) / sell below its lower line
  (bearish). Stop: beyond the opposite line. Target: drawn about the pole's length. Examples: ENJUSDT 18H, 24 Nov 2021;
  ADAUSDT 1H, 02 Oct 2022.
- Mechanical? partly. Our research: R71 P4 flag is the nearest (32 final trades, too rare).

#### 27. Triple top / triple bottom (p46, p47; sheet p56)
- Entry: three touches of a flat resistance (or support); the drawn move is a break of the support under a triple top
  (bearish) or of the resistance over a triple bottom (bullish). No entry, stop or target marks. Examples: ADAUSDT 1H,
  06 Sep 2022; ETHUSDT 30m, 28 Sep 2022.
- Mechanical? yes as a box break. Our research: R20b: support / resistance levels were no better than random lines;
  R71 P5 (double, the 2-touch version) lost.

#### 28. Bullish / bearish diamond (p48; sheet p56)
- Entry: a broadening then narrowing formation; the drawn move breaks out of the lower right side (bearish, after a rise)
  or upper right side (bullish, after a fall). No marks. Example: GBPNZD 4H, 09 Feb 2022.
- Mechanical? no (shape recognition by eye). Our research: not tested by us.

#### 29. Rounding bottom / rounding top (p49, p50; sheet p57)
- Entry: a slow saucer under a resistance line (bullish when price clears the line) or a dome over a support line
  (bearish when price falls through it). No marks. Examples: AVAXUSDT daily, 24 Aug 2021; AUDUSD 4H, 19 Sep 2019.
- Mechanical? partly (curvature fit). Our research: not tested by us.

#### 30. Cup ("cap") and handle / inverse cup and handle (p51, p52; sheet p57)
- Entry: a rounded base up to a resistance, a small handle dip, then a break above the resistance, with a small retest
  drawn (bullish); mirror under a support for the inverse. No marks. Examples: ETHUSDT 4H, 08 Aug 2022; XAUUSD 4H,
  26 Aug 2022.
- Mechanical? partly. Our research: not tested by us.

#### 31. Expanding (broadening) triangles (sheet p58)
- Entry: diverging lines; the bearish one breaks out of the lower line, the bullish one out of the upper line. No marks.
- Mechanical? partly. Our research: not tested by us.

#### 32. "Downward flag" (bullish) and "upward flag" (bearish) (sheet p58)
- Entry: named by the flag's slope: a falling channel after a rally is bought on the break of its upper line; a rising
  channel after a drop is sold on the break of its lower line. Stop: beyond the opposite channel line. Target: drawn
  about one flag's height. Same as Strategies 5 and 7 without their extra filters.
- Our research: R71 P4 (above).

### Chapter 1 - psychology (p3-p7)

#### 33. FOMO checklist (crypto)
- What it says: FOMO (most common at ages 18-35, the book says) drives buying at tops and selling at bottoms. Remedies:
  research before acting, rely on a list of trusted outlets, accept that nobody wins every time, keep a written guiding
  strategy or checklist (combine fundamental and technical analysis), and remember markets move in bull / bear cycles.
- Signal source: none / discretionary. Maps to Boss's crypto rules as behaviour, not as a signal.
- Mechanical? no.
- Our research: not a testable rule. It agrees with CLAUDE.md's crypto stance (enter, take profit or stop out; no
  holding on hype).

## Performance claims
- No numbers anywhere: no win rate, no profit figure, no sample. Claims are verbal: three white soldiers "strong" and
  "reliable when confirmed by RSI"; three black crows "strong"; the bullish flag gives "good bullish momentum"; the
  triangle "sometimes fails" but one should still go to the buy side.
- Evidence given: one hand-picked chart per pattern (19 example charts, mostly crypto in 2021-22, plus forex, XAUUSD,
  NATGASUSD and one Indian stock, BATA INDIA). Every example works; no failed example, cost, or count of trades is shown.

## Risk, sizing and psychology
- Risk: every Chapter 3 and 4 setup that has marks places a stop inside the pattern; Strategy 4 explicitly picks the
  tighter of two stops, and Strategy 7 delays entry to the pullback candle to shrink the stop. The 1:3 ratio is
  defined but not tied to any setup.
- Sizing: nothing (no position size, no account risk per trade, no daily limit).
- Psychology: the FOMO chapter (method 33); its health aside quotes a long-running Harvard study on relationships, not
  trading data.
- Exits other than the fixed target and stop: none (no trailing stop, no profit lock, no time exit).

## How it relates to our research
- **Candlesticks: already rejected.** R87 (HUNT_R87_CANDLE_ANATOMY.md): no candle shape predicts the index's direction;
  92% of the 234 tested shape cells had a next-5-candle up rate between 45% and 55% vs about 51% matched; 0 of 3 option
  rules passed; reversal shapes at trend extremes mostly mark continuation (hammer above). GREEN_CANDLES.md
  (BANKNIFTY, Feb 2025 - Feb 2026): hammers, long upper wicks and big bodies are "noise" for the next 15m / 5m candle.
  GOLD_CANDLES.md (XAUUSD, 144 versions): 13 positive in both fitting years and the held-out year, none with t above
  1.1 - "what chance alone would give"; the weak 1h hammer / pin bar is the only consistent family. R85 (MCX): 0 of 4.
  The book's "wait for confirmation" (hammer, doji) is close to R87's design (it measured what follows the candle) and
  does not rescue it.
- **R24 (HUNT_R24_WHATIF_PATTERNS.md)** is a different kind of "pattern" (the market state before the biggest option
  winners) but carries the lesson this book misses: the states before big winners also came before big losers
  (expiry-day expansion: big-win lift 3.6-3.8x but big-loss lift 2.7-2.8x), and 0 of 8 rules built from them
  passed. One winning chart per pattern, as in this book, is exactly that selection effect.
- **Chart patterns: mostly rejected where tested.** R71 (HUNT_R71_CHART_PATTERNS.md): double bottom / top and flag lost
  or were too rare; all 16 tests failed; Donchian stays the only chart arm. Head and shoulders, triangles, wedges,
  pennants, rectangles defined as consolidations, cup and handle, rounding, diamond and expanding triangles were **not
  tested by us**.
- **Break-and-retest: tested on horizontal levels and rejected.** R22 (retest not better than the plain break), H35
  (408 level variants including retest-then-go, all lose after costs), R20b / R20c (levels no better than random
  lines). The book's core advice ("never buy the first break, wait for the retest and a confirming candle") was
  therefore tested on levels, never on trendlines or pattern boundaries.
- **Breakouts in general: agree only in the Donchian form.** The plain N-bar breakout (Donchian, R51 / R71 / R72) is one
  of the few arms with a record; ORB (R3, R92: 0 of 257), previous-day high / low (R89: 0 of 180) and structure breaks
  (R90: 0 of 99) all failed.
- **Crypto (the book's main market):** RC1 found 0 of 8 active crypto ideas beat random-entry twins after BH (a 4h
  breakout +48.0% a year gross but -12.2% after tax); RC2 found nothing passing on Delta India options once costs of
  7-14% of premium a round trip are counted. The book's examples ignore costs and tax.
- **Signal source:** fully consistent with CLAUDE.md's default - every rule reads the underlying's own chart.

## What is new to us
- Geometric patterns drawn with sloping lines (triangles, wedges, pennants, channels / flags, expanding triangles) as
  mechanical rules on index or MCX bars; R71 covered only double tops / bottoms, flags of a fixed shape, inside bar,
  NR7, squeeze, Heikin-Ashi, three-bar reversal and swing structure.
- Head and shoulders / inverse, cup and handle, rounding top / bottom, diamonds, triple tops / bottoms.
- Measured-move targets (pattern height projected from the break) - none of our pattern tests used them.
- A **second-break confirmation**: after a boundary break, wait for the previous swing low / high inside the pattern to
  break as well (Strategies 3 and 7).
- **Pullback entry after the confirmed break** (Strategy 7's A-B-C: break candle, one counter candle, entry on the next
  candle in the break's direction) to shrink the stop; and choosing the tighter of two candidate stops (Strategy 4).
- A **consolidation box defined by price** (a sideways range after a move) rather than by clock time (ORB) or a fixed
  lookback (Donchian), with or without a retest.

## Could not read
- Every page was readable. OCR alone (text or title pages, read in full from the OCR): cover, p2 (contents), p4-p7
  (FOMO text), p8, p15, p21, p24, p33 (chapter or section dividers), p9 (candle anatomy, labels captured by OCR).
- Viewed as images (diagrams, strategies, charts, sheets): p1, p3, p10-p14, p16-p20, p22, p23, p25-p32, p34-p58. The
  OCR had captured nothing on p10, p38, p40 and p48 and only fragments on most chart pages; the pattern names, entry /
  stop / target marks and chart captions above come from the images.
- Minor gaps: most Chapter 4 pages carry no pattern title (only p34, p35, p36, p43 and p52 do); p37-p42 and p44-p51
  were identified from their drawings, which match the titled printable sheets (p53-p58) one to one. The label over
  the evening star's middle candle (p19) and the top edge of the p53 "Falling Wedge" title are cut off in the image.
  Example-chart price scales are too small to read (not needed: the book draws no numbers on them).
