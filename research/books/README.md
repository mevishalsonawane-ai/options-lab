# Books: what they teach, and what our research says about it (25 Oct 2026)

Boss asked (25 Oct): "Understand and memorize everything from the books". Every book below was read in full on 25 Oct 2026
(text layer; OCR for scanned pages and the image-only epub; the pages that could not be read are listed in each write-up,
mostly chart images and garbled formulas). Each write-up is in our own words: what the book is about, every method with its
entry / stop / exit / filters as the book states them, the signal source in IraAlgo terms, the book's performance claims and
their evidence, risk and psychology, and how it relates to what our research already found.

- **Jarvis's memory:** `research/knowledge/book_facts.yaml`, short sourced cards built into the knowledge pack as kind
  `book_fact`. Jarvis says every card is the book's claim, not a tested result. Where our research tested the idea, the
  card carries our result beside the claim. Book cards weigh least in the retrieval and never enter the agents' shared
  section, so they cannot crowd out or outrank a tested research verdict.
- **Testable rules:** `TESTABLE_RULES.md`, plus one `proposed` entry per rule in `research/experiments/entries/book-*.yaml`.
  None has been run; Boss picks which go to a research round.
- No book text, no long quotes and no book files are in git: the books stay outside the repo.

## The trading books (15)

Books 1-13 come from Boss's Drive folder; 14 and 15 are the two links Boss sent afterwards. "Tested" counts the cards
whose idea our research already tested.

| # | Book | Author, year | What it teaches | Pages | Cards (tested) | Rules |
|---|---|---|---|---|---|---|
| 1 | [The Day Trader's Bible](wyckoff-day-traders-bible.md) | Richard D. Wyckoff, 1919 | Tape reading: volume against price, the line of least resistance, follow the break the whole market joins | 116 | 15 (9) | 13 |
| 2 | [How to Trade with Price Action](woods-price-action.md) | Galen Woods, 2014 | Bar-by-bar setups: pin bar, inside bar, NR7, three-bar pullback, hikkake, failed breaks, market bias first | 143 | 15 (11) | 20 |
| 3 | [The Power of Divergence](carli-divergence.md) | David Carli, 2016 (5th ed. 2020) | Regular CCI(20) divergence on daily charts, three qualifying rules, trendline-break entry, Fibonacci exits | 83 | 11 (4) | 4 |
| 4 | [The First Trading Manual](trader-tom-first-manual.md) | Tom Hougaard ("Trader Tom"), 2018 | Foundation course: candles, a 4-bar fractal entry, swing structure, engulfing reversals, timing of the session | 183 | 13 (9) | 20 |
| 5 | [Liquidity, Markets and Trading in Action](ozenbas-liquidity-markets.md) | Ozenbas, Pagano, Schwartz, Weber, 2022 | Market microstructure: order types, spread, impact, call auctions, price discovery; execution, not signals | 111 | 14 (6) | 7 |
| 6 | [Advanced Trading Book / Simple Trading Book](advanced-trading-book.md) | credited to Eman Mohmaad, no year | Candle and chart patterns, seven breakout setups that wait for a retest, a crypto FOMO checklist | 58 + cover | 14 (7) | 19 |
| 7 | [Trading Volatility](bennett-trading-volatility.md) | Colin Bennett, 2014 | Implied vs realised volatility, gamma scalping, skew, term structure, overwriting, variance and dispersion | 317 | 15 (5) | 10 |
| 8 | [9 Advanced and Profitable Trading Strategies](sadowski-9-strategies.md) | Roman Sadowski, no year (~2013-16) | RSI(2) momentum, stochastic at levels, MA cross, Heikin-Ashi, Bollinger, NR4/NR7, role reversal | 42 | 12 (7) | 10 |
| 9 | [The Complete Guide to Day Trading](heitkoetter-day-trading.md) | Markus Heitkoetter, 2008 | A toolbox: indicators, trendlines, stops and targets, back-test checks, a weekly profit goal | 273 | 15 (10) | 16 |
| 10 | [A Practical Guide to Swing Trading](swing-practical-guide.md) | Larry Swing, ~2002 | A daily swing scan (10/20-day MAs, Force Index, ADX > 30), buy-stop entry, half off at +7%, trailing low stop | 74 | 13 (3) | 6 |
| 11 | [Short Swing Trading](smith-short-swing.md) | David Graeme-Smith, 2004 | Six daily rules: 50-day side, 8-period DMI cross, break of the last non-inside day, step trailing stop | 121 | 14 (2) | 7 |
| 12 | [The Advanced Trading Methods in the Financial Markets](advanced-trading-methods.md) | Boussafsaf and Taleb, 2019 | A journal survey of algorithmic and high-frequency trading and execution algorithms; no trading rules | 18 | 10 (5) | 2 |
| 13 | [10 Most Profitable Trading Strategies](porwal-10-strategies.md) | Nikhil Porwal, 2023 | Ten setups for Indian markets: inside bar, doji, engulfing, 15-minute opening break, ADX-EMA, traps, 52-week levels | 61 | 14 (11) | 12 |
| 14 | [Advanced Techniques in Day Trading](aziz-advanced-day-trading.md) | Andrew Aziz, 2018 | US stocks intraday: Stocks in Play, VWAP setups, ORB, ABCD, bull flag; the 2% / 6% risk rules | 420 | 15 (8) | 13 |
| 15 | [Trading in the Zone](douglas-trading-in-the-zone.md) | Mark Douglas, 2000 | Psychology: think in probabilities, pre-define risk, judge an edge over a series, the 20-trade exercise | 143 | 14 (5) | 5 |

The third link Boss sent (the book that never downloaded) could not be reached on any route and was dropped on Boss's
instruction.

## What several books agree on

1. **Risk is set before the trade, and small.** Every trading book except the survey: a fixed risk per trade (Aziz 2% and
   a 6% month, Smith 3-5%, Carli and Woods size from the stop, Douglas "pre-define the risk"), a stop on every trade, and
   a reward of at least the risk (Aziz and Porwal 2:1, Douglas 3:1, Carli 1:1). Our research agrees on the stop and the
   account limits (CLAUDE.md account stop rule), but not that a reward multiple creates an edge: target and exit overlays
   did not help (R18 0 of 60, R81, R100), and Douglas's 3:1 and scale-out-in-thirds cards are marked negative.
2. **Trade with the larger trend; patterns need context.** Woods ("market bias matters more than the pattern"), Hougaard,
   Swing, Smith (50-day side), Porwal (yesterday's trend), Aziz (the higher timeframe), Sadowski. Our tests of trend
   filters on the index were mostly negative (R71 P8 structure, R90, R89 multi-timeframe bias); the trend-following arms that
   hold up (R72 Breadth Donchian, R51 Donchian) are breakouts, not pattern filters.
3. **Breakouts fail often; wait for the retest or trade the failure.** Woods (hikkake, failed breakouts), the Advanced
   Trading Book (skip the first break), Aziz (VWAP false breakout), Porwal (bull and bear traps), Hougaard (fake-outs),
   Wyckoff (shakeouts). Our research: fading failed breaks and stop runs lost (R86 6 of 6 tests, R91 0 of 63, R8, R61b), and
   a retest was no better than the plain break (R22, H35). The one candle fact that held out of sample (R87, the
   bear-trap-like reclaim on 1-minute NIFTY, n 1,702) was smaller than an option's cost.
4. **Volume confirms price.** Wyckoff, Aziz, Woods, Porwal, Swing (Force Index). Our order-flow and volume work used option
   activity as a proxy for index volume, because the NIFTY cash index has no volume (R9, R61b, R87); real index-futures
   minute volume exists only from 29 Jul 2026. This is the most consistent book idea we have not tested properly.
5. **Costs matter and most traders lose.** Wyckoff (the "invisible eighth"), Ozenbas (implementation shortfall), Heitkoetter,
   the survey paper. Agrees fully with us: costs (about Rs 70 a NIFTY round trip, Rs 76 BANKNIFTY) decide most of our tests.
6. **Judge a method over many trades, not one.** Douglas (the casino, the 20-trade series), Heitkoetter (40-200 back-test
   trades), Woods (label every trade). Agrees in spirit; our bar is stricter (walk-forward, a locked holdout, hundreds of
   trades, BH across ideas). Douglas's 20 trades is far too few to judge an edge.
7. **Options are usually priced above the moves that follow** (Bennett; also Javaheri, Wilmott and the textbooks below).
   Confirmed: India VIX is above NIFTY's next-21-day realised volatility on 81% of days (R102, 4,507 days). But selling
   every day did not pay after costs and stale prices (R103: 0 of 24).

## Where the books contradict each other

- **Win rate.** Heitkoetter wants 60-80% winners; Douglas says win rate is irrelevant if risk is defined; Carli and Woods
  accept low win rates for bigger winners. Our R48: high design win rates turned into losses on unseen data.
- **The first move of the day.** Aziz and Porwal trade the opening-range break; Hougaard calls the US open whippy and waits;
  Swing delays entries after a gap (30 minutes with it, 5 against). Ours: the ORB family lost everywhere (R3, R49, R92 0 of
  257); the open is the most active part of the day (R102), and gaps fill early (median 52 minutes, NIFTY).
- **Profit taking.** Aziz, Swing and Douglas take partial profits; Smith and Wyckoff let the trail decide; Carli takes half at
  127.2%. Ours: partial exits and trails did not beat the plain exits (R81, R19, R19b, R100).
- **Indicators.** Heitkoetter and Sadowski build on indicator signals; Woods and Wyckoff say price and volume alone; Douglas
  says the analysis matters less than the trader. Our research sides with the sceptics: indicator signals were coin flips on
  direction (F1, B2, R30, R95).
- **Fading vs following.** Wyckoff and Smith follow strength; Carli, Sadowski (RSI(2) is used both ways across the
  literature) and the trap setups fade it. Ours: neither has a reliable sign on NIFTY/BANKNIFTY (R102: 1 of 70 sign rules
  confirmed, and that one was an artefact).

## What our research already confirmed or rejected

**Confirmed (the book's claim matches our data):**
- Options are priced above realised volatility (Bennett): R102, 81% of 4,507 days.
- Follow a break the whole market joins (Wyckoff): R72 Breadth Donchian, one of our arms with a real record.
- A losing streak says nothing about the next trade (Douglas): our direction results (R16, R88, R102).
- The size of the next move is predictable, direction is not (the textbooks' stylised facts): R16, R41, R88.

**Rejected (tested on our markets and failed):**
- Opening-range breakouts (Aziz, Porwal, Heitkoetter): R3, R23, R49, R92.
- Candle shapes (pin bar, engulfing, doji, hammer, stars; Woods, Hougaard, the Advanced Trading Book, Porwal, Sadowski):
  R87, R85, GREEN_CANDLES, GOLD_CANDLES.
- Chart patterns (double tops, flags, inside bars, NR7 on the index, three-bar reversal): R71.
- Support / resistance, role reversal, round numbers, Fibonacci levels and targets: R20b, R20c, R22, H35, R4, H37.
- Previous-day high / low breaks: R89 (0 of 180).
- CCI signals as given (Carli's oscillator, not his full setup): R95 (0 of 288).
- Moving-average crosses, Heikin-Ashi, ADX/DMI intraday, EMA ribbons: OBUY_FINAL, R71 P6, R29, R46.
- Fading stop runs / traps and smart-money structure: R86, R91, R90, R62b.
- Buying volatility or straddles, buying "cheap" IV, defined-risk selling: R89, h29, R103.
- Daily pullback-in-trend on the index and daily momentum: SWING_DEEP, R102 (reversed in the holdout).
- Partial profits, trails, break-even locks, early exits: R18, R19, R19b, R81, R100.

## Most worth testing (Boss chooses; none is run)

1. **Volume-confirmed range breakout on index futures and MCX futures** (Wyckoff, Aziz, Porwal): every one of our breakout
   tests used price alone. Needs index-futures minute volume (from 29 Jul 2026 only) or MCX futures volume (have).
   Rules `book-wyckoff-04`, `book-porwal-11` (bear trap), `book-wyckoff-02`, `book-wyckoff-03`.
2. **Sell premium only when it is rich** (Bennett, Javaheri, Wilmott): R103 sold every day; time a defined-risk structure
   to days when the index's own ATM IV is high against a forecast of realised volatility (BANKNIFTY's own IV, not VIX).
   Rule `book-bennett-01`.
3. **The book's wider stop on the three-bar pullback** (Woods; also the flag pullback of the Advanced Trading Book): R87B's
   held candle sequence lost only because its stop was hit on 117 of 162 trades. Rules `book-woods-01`, `book-atb-12`.
4. **Daily divergence with Carli's three rules and a trendline-break entry** on NIFTY/BANKNIFTY daily (since 2006) and MCX:
   R95 tested only intraday CCI divergence entered at a fixed time. Rules `book-carli-01`, `book-carli-03`.
5. **A weekly profit goal, then stop, as a paper account rule** (Heitkoetter): it can only lower risk; we tested only a day
   lock (R19c). Rule `book-heitkoetter-14`.

Also new but lower prior: the 4-bar fractal entry (Hougaard, `book-tom-01`), the hikkake (Woods, `book-woods-02`), the
consolidation-box breakout beside Donchian (the Advanced Trading Book, `book-atb-11`), the gap-delay entry (Swing,
`book-lswing-06`), Smith's step trailing stop as an exit module on our multi-day holds (`book-smith-03`), and a 52-week-level
filter (Porwal, `book-porwal-07`).

## The FinMathematics textbooks

Being read in full (Boss, 25 Oct: all 40 PDFs). This section is completed when the last one is in.
