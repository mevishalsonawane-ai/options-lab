# The Power of Divergence (Trading with the Trendlines, vol. 2) - David Carli (fifth edition April 2020; first printing 2016)

Source: carli-divergence.txt (83 PDF pages; the book numbers 75 of them), read in full on 25 Oct 2026 (text layer for
every text page; OCR for the pages with no text layer: cover, title, dedication and the page with figures 15-16). The 55
figures are screenshots without a text layer, so the example charts themselves (where each pivot, trendline and
Fibonacci leg sits) could not be read; their captions and the text around them were.
Book claims are the author's, not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short free e-book teaching ONE reversal strategy: regular divergence between price and the CCI oscillator.
A divergence qualifies only if it passes three rules: CCI zone (beyond +-150), CCI stays on one side of zero, and a
Fibonacci-extension size check on the second price extreme.
The trade is entered only when price breaks a trendline drawn against the old move; stop just beyond the extreme.
Targets are Fibonacci extensions 127.2% (half off, stop to break-even) and 161.8% (the rest).
Written for forex, stocks, gold, indices and BTC, mainly on DAILY charts; ends with a fixed-risk position-size chapter.

## Methods, rules and setups

Scope check (asked for explicitly): the book teaches only the REGULAR (classic) divergence, bullish and bearish. It
never mentions hidden divergence, triple divergence, or divergence on RSI, MACD, stochastic or volume (it only says in
passing that divergence can exist between price and volume or any oscillator). The only oscillator is the CCI. There is
no Chapter 5 (the contents jump from Chapter 4 to Chapter 6), and Chapter 2's last line promises "Harmonic Patterns"
that the book never teaches; the Fibonacci size check in rule 3 is the only harmonic-like element.

### 1. The CCI and its settings (Ch. 3 and end of Ch. 4)
- What it is: Lambert's Commodity Channel Index, read as how stretched price is against its own average. The book says
  it usually swings between -150 and +150, and calls above +100 overbought and below -100 oversold (the text has a typo,
  "CCI <100").
- Setting: the author uses CCI length 20; says 14 (the usual default) or any other length is acceptable, admitting
  different lengths give different signals. The price input is not stated (TradingView's default CCI uses the typical
  price, (high + low + close) / 3).
- Zones used by the strategy: +-150, "more extreme" than +-100, which the author says filters many false signals.
- He explicitly does NOT use the CCI's ordinary signals (crossing +-100), arguing standard indicator use does not pay.
- Signal source: underlying price (CCI on the index / future / stock itself).
- Our research: R95 (`HUNT_R95_CCI_SIGNALS.md`) studied CCI deeply on NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY and
  4 MCX minis: +200 is 3 mean deviations, about 2.4 SD, about 3 ATR; a steady trend sits near +128; the cross back
  inside +-200 has no direction edge (forward win 38-54% vs 48-52% for any candle). The CCI ordinary signal as given:
  0 of 288 fade cells pass. R95 used length 25 (variants 20 and 50) and levels +-200 (variant +-100), never +-150.

### 2. Regular divergence, bullish and bearish (Ch. 3)
- Bullish: price makes a lower low while the CCI makes a higher low. Bearish: price makes a higher high while the CCI
  makes a lower high. The author says a divergence often foretells a turn or the slowing of a violent move.
- How a "low" or "high" (swing point) is found: not stated (drawn by eye on the chart).
- He stresses that a divergence alone is NOT a trade: it can keep running for days or weeks with new extremes.
- Signal source: underlying price.

### 3. The three qualifying rules (Ch. 4)
1. Zone: in a bullish divergence the CCI's higher (second) low should be below -150; in a bearish one the CCI's lower
   (second) high should be above +150. Exception allowed: the second extreme may complete "slightly outside" the zone.
2. Zero line: during a bullish divergence the CCI should not rise above zero between the two lows (for a bearish one,
   not fall below zero between the two highs). Exception allowed: a shallow, very brief crossing.
3. Size: take the last retracement AB before the second price extreme. The second extreme should reach a Fibonacci
   extension of AB of 1.13, 1.272, 1.414 or 1.618; beyond 1.618 the pattern is cancelled. (Literal reading: in a
   bearish case A is the first high, B the pullback low; the new high C must lie between B + 1.13 x (A - B) and
   B + 1.618 x (A - B). Mirror for bullish.)
- The author calls these "not strict rules" with "small exceptions"; the exceptions are not quantified.
- Mechanical? partly: rules 1-3 can be coded with pivots and tolerances, but the book's exceptions are judgement.

### 4. Entry: the trendline break (Ch. 4)
- Entry: after a qualifying divergence, draw a trendline against the old move: a resistance trendline over the falling
  highs for a bullish divergence, a support trendline under the rising lows for a bearish one. Its break is the signal.
  - Aggressive: enter the moment price crosses the line (better price, more fakeouts).
  - Conservative: wait for a candle CLOSE beyond the line (worse price, fewer fakeouts).
- Which points the trendline joins: not stated (drawn by eye; the figures cannot be read).
- No break, no trade: a divergence whose price keeps going without breaking the line gives no signal (his Eur-Chf
  example).
- Advice: also check fundamentals and trade only when both agree (Preface).
- Market and timeframe: forex, stocks, gold, Nasdaq 100 futures, BTC-USD; daily charts.
- Signal source: underlying price; maps to NSE index options as "signal on the NIFTY / BANKNIFTY daily spot, buy the
  option" but the hold is days to weeks (theta), so a monthly ITM option or the future fits better than a weekly ATM;
  MCX: GOLD / SILVER / CRUDE futures daily bars, directly; crypto: BTC/ETH daily (book shows a BTC-USD example), would
  need a profit lock on top (CLAUDE.md), and the book's half-off + break-even step is one.
- Mechanical? partly: needs a coded trendline rule (e.g. line through the two most recent swing highs between the
  divergence lows); the book never defines one.
- Our research: trendline breaks have not been tested by us. Nearest: drawn levels (R20b: support / resistance no better
  than random lines; 6 of 7 variants' random levels made more on average).

### 5. Stop-loss (Ch. 4)
- 15-20 pips / ticks beyond the divergence's last extreme: below the last low (bullish), above the last high (bearish).
- For NSE indices a tick is 0.05, so "15-20 ticks" means about 1 point; the FX intent (15-20 pips on a daily chart) is
  closer to 0.1-0.2% of price. Mechanical: yes.

### 6. Targets and trade management (Ch. 4)
- Target 1: the 127.2% extension of the move that began with the last price retracement; close half the position there
  and move the stop to break-even.
- Target 2: the 161.8% extension; close the rest. Example (Eur-Nzd, short): half at 1.68741 (127.2%), the rest at
  1.67598 (161.8%); the levels may be rounded.
- Which swing the extension is measured on is shown only in the unreadable Figure 42 (most literal reading: the leg from
  the divergence's second extreme to the first reaction swing, projected 1.272 and 1.618 from that extreme).
- Flexibility: the target is the only part he says you may change, as long as reward : risk is at least 1:1; part of
  the position may be left to run.
- Mechanical? yes once the leg is defined.
- Our research: Fibonacci extension targets 127.2 / 161.8% were tested on Fibonacci-bounce signals in H37 and made them
  worse (Rs -182 and -193 a trade net). Break-even after a partial exit is a lock; our lock / trail studies are R18 and
  R19*.

### 7. Timeframe (Ch. 4, Ch. 7)
- Mostly daily; rarely 4-hour; never lower; sometimes weekly (signals "excellent" but slow). He says the higher the
  timeframe, the bigger and more reliable the following move. (He also says it "works on all time-frames".)
- Our research: R95 tested CCI and its divergences only on 3m / 5m / 15m / 60m. Daily-bar direction rules on the index
  (SWING.md, in FINDINGS) sat at 42-52% right, and option buying held 3-5 days lost to decay.

### 8. Gap ("window") filter (Ch. 2)
- Do not open a bullish trade right after a rising window (gap up) or a bearish trade right after a falling window: a
  plain retracement can hit the stop. Gaps matter in an established trend, little inside congestion.
- Gap types (descriptive): breakaway (out of a range, volume picks up), continuation / runaway (trend acceleration),
  exhaustion (near the end of a move, exceptionally high volume).
- Signal source: underlying price (daily gaps). Mechanical: the filter yes; the gap typing partly (needs volume and
  "end of trend" judgement). Our research: this exact filter not tested by us.

### 9. Background technical analysis (Ch. 1-2) - context, not rules
- Chart types; candle definitions: long candle, short candle, marubozu, spinning top (indecision), four doji types
  (long-legged; gravestone, bearish at highs; dragonfly, bullish at lows; four-price doji).
- Dow theory: major (years), medium (months), minor (weeks) trends nested by timeframe; trade with the main trend of
  your timeframe (note this sits awkwardly with a reversal strategy).
- Support / resistance: more tests without a break = stronger; old resistance becomes support after a break (polarity
  change); static (yearly highs / lows, Fibonacci levels) vs dynamic (trendlines, moving averages; e.g. the 200-day EMA
  as support then resistance). SMA / EMA / WMA defined.
- Breakout vs fakeout (close back inside within one or more sessions); retracement and pullback; a pullback retest of a
  broken level "confirms" the breakout and, he says, raises the success rate.
- Signal source: underlying price; none / discretionary as written. Our research: candle shapes predict size, not
  direction (R87); S/R levels no better than random (R20b); chart patterns failed (R71).

### 10. Position sizing (Ch. 6)
- Shares: quantity = max loss / (entry - stop) for longs, max loss / (stop - entry) for shorts; or with max loss =
  capital x % risk. Examples (US dollars): $400 risk on a $3 stop gives 133 shares; 0.5% of a $60,000 account on a $5
  stop gives 60 shares.
- Forex: size = (1,000 x max loss / stop in pips) / pip value per 1,000; e.g. 0.75% of a $50,000 account.
- Mechanical: yes. It maps directly to lots: lots = floor(risk rupees / (stop distance x lot size)) on the underlying,
  or on the premium if the stop is on the option.

## Performance claims
- "A high percentage of profitable trades" (Preface) and "a very high percentage of trades closed in gain" (Ch. 7). No
  number, no sample, no period, no backtest, no costs.
- The CCI gives better divergence signals than other oscillators, and the +-150 zone filters many false signals: no
  evidence given beyond "I have noticed over the years".
- The targets are "the method that gave me the best results": no evidence given.
- Evidence offered: about 16 hand-picked example charts (Eur-Nzd, Apple, Usd-Chf, Gold, Gbp-Usd, Berkshire Hathaway,
  Usd-Cad, Nasdaq 100 futures, BTC-USD, Aud-Jpy, Alibaba, LAM Research, JD.COM, Eur-Chf, Aud-Cad), nearly all winners,
  plus one loser (Amazon, stopped out a couple of days after entry) shown as a lesson in discipline. One caption
  (Berkshire, figure 48) says bearish where the text says bullish.

## Risk, sizing and psychology
- Always use a stop: the strategy is "just graphical" and news can reverse it.
- Size every trade from the stop and a fixed money or percent risk (examples 0.5% and 0.75% of the account).
- Take half at the first target and move the stop to break-even so the rest is risk-free.
- Most trading stress (he says "at least in 90% of cases") comes from positions too large for the account; leverage
  ruins small accounts; growing $1,000 into $100,000 takes a long time and higher monthly targets mean more risk.
- Discipline: follow every rule; the strategy pays only if applied consistently.

## How it relates to our research

**Divergence specifically - what we tested and what we did not.**
- Tested (closest match): R95 `HUNT_R95_CCI_SIGNALS.md`, Boss's "Mo3ty CCI Signals" Pine. Its divergence = confirmed
  price pivots (5 candles each side), regular divergence (lower pivot low with higher CCI, or the mirror), one of the two
  CCI values beyond -+200, CCI(25) of hlc3; signal on the underlying, 1-ITM option on real prints, real costs.
  - Variant DV (trade the CCI cross-back signal only when a divergence confirms within 10 bars; exit CCI crosses 0)
    on 3m / 5m / 15m / 60m x 9 instruments: 0-52 trades a cell, 26 of 36 cells under the 40-trade gate; no DV cell
    passed (the whole fade family: 0 of 288).
  - Every confirmed divergence traded directly (exit CCI back to 0, stop beyond the pivot): entered when actually known
    (5 candles after the pivot), indices design 1,778 trades, 43% win, -Rs 3,37,415; holdout 691 trades, -Rs 37,156;
    MCX design 807 trades, -Rs 2,19,949; holdout 1,005 trades, -Rs 2,48,916. Entered at the label candle (hindsight)
    the same index set shows +Rs 12,01,300 on 1,687 trades: the chart flatters divergences because the pivot is known
    only later (FINDINGS.md, "The Mo3ty CCI Signals indicator (R95)").
- NOT tested by us (Carli's own rules): CCI length 20 with the +-150 zone; the zero-line rule; the 1.13-1.618 Fibonacci
  size check; the trendline-break entry; the 15-20 tick stop; the 127.2% half-off + break-even and 161.8% targets; daily,
  4-hour and weekly bars (R95 stopped at 60m). Also not tested: hidden divergence, and RSI / MACD / stochastic
  divergence of any kind (R12 notes no published evidence for RSI-divergence exits; B2 and R30 fed RSI, MACD,
  stochastic and CCI LEVELS into models, never divergence features).
- Other things we called "divergence" (different concepts, all failed or weak):
  - VIX / index divergence (R1 N13, re-tested as R74 V1): fail, design 253 trades -Rs 25,287, final 65 trades
    -Rs 23,860 (`HUNT_R74_STRATEGY_MAP.md`).
  - SMT divergence between indices at the day's low / high (R72 C6): fail, design -Rs 1,68,737; final 348 trades
    +Rs 35,463 resting on its top 5 trades (`HUNT_R72_CROSS_INDEX.md`).
  - Delta (order-flow) divergence, proxies only: R9 reversal -2.6 to +0.6 bp in design (no); R87 delta-divergence
    footprint 5m n 1,520 / 1,492, q 1.0 / 0.81 (no); R8 rule: divergence is never a trigger, at most a reason to skip.
  - Crypto perps OI divergence fade (F1 P13): design net -Rs 425,978 on 2,299 trades; holdout -Rs 127,074 on 541
    (`HUNT_F1_FACTORY.md`).
  - Index-pair divergence (H6): all 24 variants lost net.
- Fibonacci pieces: H37 extension targets 127.2 / 161.8% made Fibonacci bounces worse (Rs -182 and -193 a trade net);
  R4 swing Fibonacci .382-.786 + 1.272 / 1.618 reversals: 49.8% vs 49.9% (BANKNIFTY), no edge.
- General: R95's main lesson contradicts the book's premise that a stretched CCI foretells a turn. On our intraday index
  and MCX data the stretch is real but the turn is a coin flip (forward win 38-54% vs 48-52%), and the option round trip
  plus decay eats the small moves. The book's evidence is chart examples, which is exactly what R95's repaint test
  shows to be misleading.

## What is new to us
- A divergence trade with a CONFIRMATION entry (trendline break, close beyond it) instead of entering at the pivot or
  on the CCI cross: never tested. A coded version (line through the last two swing highs / lows between the divergence
  extremes) would be new.
- The three qualifying filters (CCI +-150 zone, zero line not crossed, 1.13-1.618 extension) as a quality screen.
- DAILY-bar oscillator divergence on NIFTY / BANKNIFTY since 2006 and MCX futures: our divergence work is all intraday.
  Daily data give few signals (a handful a year per index), so it needs a long history and pooled instruments.
- The trade plan itself (stop just beyond the extreme, half off at 1.272 with break-even, rest at 1.618) as a fixed
  exit template for a multi-day underlying swing trade.
- Gap-direction filter (no long after a gap up, no short after a gap down) as a skip rule; a risk-lowering filter.

## Could not read
All 55 figures (screenshots with no text layer; OCR of figures 15-16 produced only fragments). In particular Figures 38
(the Fibonacci size rule) and 42 (which leg the target extensions use) are explained only by the pictures, so those
two rules are written here in their most literal reading. Nothing else.

## R104 results (25 Oct 2026)

Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in `research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:

- `book-carli-01`: tested (R104): DLY: 0 trades, win -, net Rs 0 design, holdout Rs 0 (fail); MCXD: 0 trades, win -, net Rs 0 design, holdout -Rs 284 (fail)
- `book-carli-02`: tested (R104): IDXF: 0 trades, win -, net Rs 0 design, holdout Rs 0 (fail); MCXI: 0 trades, win -, net Rs 0 design, holdout Rs 2,646 (fail)
- `book-carli-03`: tested (R104): IDX: 11 trades, win 36%, net -Rs 11,019 design, holdout Rs 0 (fail)
- `book-carli-04`: tested (R104): ARM: delta vs the arm -Rs 518,470 design (fail)
